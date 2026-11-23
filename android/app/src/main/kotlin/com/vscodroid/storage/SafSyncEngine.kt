package com.vscodroid.storage

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.os.SystemClock
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * Bidirectional sync engine between SAF content:// URIs and local mirror directories.
 *
 * ## Initial Sync (SAF → local)
 * Recursively walks the SAF document tree via [DocumentsContract] and copies
 * all files to a local mirror directory, preserving the folder structure.
 *
 * ## Write-back (local → SAF)
 * Watches the mirror with one [FileObserver] per directory. When a file is modified,
 * created, or deleted locally (by VS Code), the change is synced back to the original
 * SAF location via [ContentResolver]. One observer over the root is not enough: inotify
 * watches a directory rather than a tree, so a saved file one level down would update
 * the mirror and never reach the device.
 *
 * ## Conflict Resolution
 * The later modification time wins, with length deciding a tie: the mirror is replaced
 * when it is absent, when the source is newer, or when the two match in time and differ
 * in size. A source carrying no timestamp is decided by the synced record instead,
 * because there is no clock to compare; see [shouldOverwriteMirror] for what that costs.
 * That rule alone would let a device edit overwrite a save the write-back never
 * delivered, so before such a replacement the mirror copy is set aside under a
 * `.local-<time>` name; [setAsideDivergedMirror] says exactly when. The other
 * direction has the same guard: a device edit made since the last sync is set aside
 * under a `.device-<time>` name before a newer mirror copy is written over it;
 * [setAsideDeviceCopy] says when. While the folder is open, a save that would replace
 * a device document changed since this engine last read or wrote it keeps the device's
 * copy as `.device-<time>` first; see [keepsDeviceEdit]. A delete of one is declined;
 * see [keepsDeviceDocument].
 * External changes are picked up the next time the folder is opened, which is
 * the only refresh that exists: there is no "Refresh from device" action, and the one
 * in-app action on a mirror, "VSCodroid: Manage Device Folder Storage", removes the
 * copy rather than refreshing it. Reopening also removes mirror files for documents
 * deleted on the device, under the conditions [reconcileDeletions] spells out, and
 * puts onto the device the files only the mirror has, under the conditions
 * [uploadMirrorOnlyDocuments] spells out.
 */
class SafSyncEngine(internal val context: Context) {

    internal val tag = "SafSyncEngine"
    /**
     * The write-back of the folder being watched: its queue of jobs and its thread.
     *
     * Replaced, never mutated, by [startWatching] and [stopWatching]. A drain that
     * outlives its stop keeps the object it was started with, so the folder opened next
     * hands its jobs to a different queue and no two threads ever poll one.
     */
    @Volatile
    internal var session = WatchSession()
        private set

    @Volatile internal var isWatching = false

    /**
     * Held across a whole [startWatching], [stopWatching] or [shutdown].
     *
     * The three decide between them whether this engine is live, and each of them takes
     * several steps to say so: [startWatching] publishes a session, sets [isWatching] and
     * then registers up to [MAX_WATCHED_DIRECTORIES] watches, and it opens by calling
     * [stopWatching], which blocks up to [DRAIN_GRACE_MS] joining the previous drain.
     * Run against each other those interleave, and the outcome is decided by whichever
     * of the two [isWatching] writes lands last: a stop finishing inside a start's inner
     * drain leaves observers registered, a worker polling and [isWatching] true, on an
     * engine whose owner is gone. The Activity serialises its own three calls, but the
     * one in `onDestroy` runs on a detached thread deliberately outside that mutex, so
     * the serialisation has to be here, where every caller passes.
     *
     * Reentrant, which [startWatching] relies on: it calls [stopWatching] while holding
     * this, and Java monitors admit the thread that already owns them.
     */
    internal val lifecycleLock = Any()

    /**
     * Whether [shutdown] has been called, after which no start is honoured again.
     *
     * Ordering the calls is not enough on its own. The Activity's teardown is the last
     * word about an engine nothing can reach afterwards -- the replacement Activity
     * builds its own [SafSyncEngine], so its stop cannot reach this one -- and a start
     * that arrives after it belongs to a coroutine of the Activity that has gone. It is
     * refused rather than run late, because there is no one left to stop what it starts.
     */
    @Volatile internal var shutDown = false

    /**
     * One observer per watched directory, keyed by that directory.
     *
     * Holding them here is not only bookkeeping: [FileObserver]'s shared ObserverThread
     * refers to each observer through a [java.lang.ref.WeakReference], so one that
     * nothing else references stops delivering events as soon as it is collected.
     */
    internal val watchers = mutableMapOf<File, DirectoryObserver>()
    internal val watchersLock = Any()

    /** What each mirror's open recorded, kept until [startWatching] takes it; see [ChangesDuringOpen]. */
    internal val changesDuringOpen = ConcurrentHashMap<String, ChangesDuringOpen>()

    /** The recording [startWatching] is taking over, read by [watchTree] under [lifecycleLock]. */
    internal var handingOver: ChangesDuringOpen? = null

    /**
     * What has changed in the folder a switch stopped watching, by that folder's absolute path, kept until a
     * [startWatching] of the same folder takes it; see [pauseWatching]. Guarded by [lifecycleLock].
     */
    internal var pausedChanges: Pair<String, ChangesDuringOpen>? = null

    /** Whether the [stopWatching] running is the one [pauseWatching] asked for. Guarded by [lifecycleLock]. */
    internal var pausing = false

    /**
     * How one directory is watched, by the watcher ([DirectoryObserver]) and while an open
     * fills the mirror ([ChangesDuringOpen]). A `var` so a JVM test, where no [FileObserver]
     * can be built, can stand in for it.
     */
    internal var directoryWatchFactory: (dir: File, deliver: (event: Int, name: String?) -> Unit) -> OpenWatch =
        { dir, deliver ->
            object : FileObserver(
                dir,
                FileObserver.MODIFY or FileObserver.CREATE or FileObserver.DELETE or
                    FileObserver.MOVED_FROM or FileObserver.MOVED_TO,
            ), OpenWatch {
                override fun onEvent(event: Int, path: String?) = deliver(event, path)
                override fun start() = this.startWatching()
                override fun stop() = this.stopWatching()
            }
        }

    /**
     * Maximum queued live observer callbacks per [WatchSession] before new callbacks escalate
     * to overflow reconciliation instead of growing unboundedly. Replaceable in JVM tests.
     */
    internal var observerQueueCapacity: Int = DEFAULT_OBSERVER_QUEUE_CAPACITY

    /**
     * How long [stopWatching] waits for already-observed callbacks on the closing session's
     * observer dispatcher to finish enqueueing write-back jobs before force-closing it.
     */
    internal var observerDrainGraceMs: Long = OBSERVER_DRAIN_GRACE_MS

    internal fun dispatchObserverEvent(
        owner: WatchSession,
        rootDir: File,
        safTreeUri: Uri,
        task: () -> Unit,
    ) {
        if (shutDown) return
        val accepted = owner.dispatchObserverEvent(observerQueueCapacity, task)
        if (!accepted && !shutDown && isWatching && owner === session) {
            handleOverflow(rootDir, safTreeUri, owner, reason = "observer dispatch queue saturated")
        }
    }

    /**
     * Monotonic millisecond clock used for rename-pair windows and recent-drop expiry so
     * wall-clock adjustments cannot break pairing or expiration.
     */
    internal var monotonicClock: () -> Long = ::defaultMonotonicMillis

    private fun defaultMonotonicMillis(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: RuntimeException) {
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
    }

    /**
     * When an open's watch saw an event, read as the event comes: the replay pairs a folder's departure and arrival
     * by these times, the way the watcher pairs them by its own clock ([RENAME_PAIR_WINDOW_MS]). The same clock as
     * that one, since a pair can straddle the handover. A `var` so a JVM test can space two events apart.
     */
    internal var openEventClock: () -> Long = { monotonicClock() }

    /**
     * Cache: relativePath → document ID. Built during [initialSync] and used for
     * O(1) write-back lookups instead of walking the tree for each event.
     *
     * Four threads reach it: [initialSync] on Dispatchers.IO, [resolveDocumentUri] from
     * an observer thread, [createInSaf] from the write-back thread, and [stopWatching]
     * from whichever thread closes the folder. A plain map rehashing under a concurrent
     * read loses entries or spins, so the map itself carries the synchronization.
     *
     * Every read and write that turns on [cacheTree] goes through [cachedDocId],
     * [cacheDocId] and [resetCache], which hold the map's own monitor across the label
     * test and the access. The map being concurrent does not make the pair atomic: an
     * event still inside its provider round trips when the folder was switched used to
     * test the label, walk the provider, and then write its answer into a cache the next
     * folder's sync had relabelled and was filling in between, so that folder's phase 2b
     * read the previous folder's directory for a same-named path. Where one granted
     * folder sits inside another, that directory is writable, and the write opens it
     * with "wt". The monitor is held for a map access and never across a provider call.
     */
    internal val docIdCache = ConcurrentHashMap<String, String>()

    /**
     * Mirror paths the device holds a document for that this sync never read.
     *
     * A document skipped for size, or one whose copy failed, leaves no trace in
     * the mirror, so "absent from the mirror" means both "not on the device" and
     * "on the device with content this app has never seen". Every write-back path
     * resolves that ambiguity the destructive way, by design: `createOneInSaf`
     * exists to write into an existing document rather than fork it, so a local
     * file appearing at that name opens the device's document with truncation and
     * a 60 MB archive becomes whatever was just typed.
     *
     * Absolute paths, like the upload journal's entries and for the same reason:
     * one engine serves every folder in turn. A file by its own path, and a directory by
     * its own path only where this sync read nothing of it (its name is skipped, or its
     * enumeration failed); a question about any other directory is answered by prefix, in
     * [handleMirrorEvent]. Both follow a directory rename ([renameDeviceMemoryUnder]).
     */
    internal val unfetched = ConcurrentHashMap.newKeySet<String>()

    /** Files vouched for by this open, even when an ancestor listing was incomplete. */
    internal val fetchedPaths = ConcurrentHashMap.newKeySet<String>()

    /**
     * Copies an open kept inside the app ([ChangesDuringOpen.keptByOpen]) in a folder its replay sends on, by mirror and
     * identity row ([keptFromCreatesKey]): a create that makes that folder on the device leaves each out while it
     * matches its row ([createChildrenInSaf]), as the open said it would. Scoped per mirror in [initialSync].
     */
    internal val keptFromCreates = ConcurrentHashMap.newKeySet<String>()

    internal fun keptFromCreatesKey(root: File, identity: String) = root.absolutePath + "\n" + identity

    /**
     * The record rows of successful uploads that no write of the record has taken yet, by the file's absolute
     * path: those made during an open, which its final record takes, and those made over a record that could
     * not be read, which the next write of it takes ([recordDeliveredCopy]).
     */
    internal val deliveredDuringSync = ConcurrentHashMap<String, String>()

    /**
     * Mirror paths a refusal has already been announced for.
     *
     * The refusal fires on every inotify MODIFY, so an editor autosaving a file whose
     * device document this sync could not read reaches it on every save. Announcing each
     * one would be a wall of the same notice, and worse: the manager's throttle is one
     * timer for the whole folder, so the wall would swallow an unrelated write-back
     * failure for as long as it lasted. One notice per path carries the whole fact,
     * which is that this file is not reaching the device folder.
     *
     * Absolute paths and scoped per mirror in [initialSync], next to [unfetched] and for
     * the same reason: one engine serves every folder in turn.
     *
     * "One notice per path" is therefore per ENGINE, not per device, and the
     * difference is reachable: an activity recreated mid-open leaves two engines
     * over one mirror, both of them announcing through a lambda that closes over
     * the application context, so one file can be announced twice. That is left
     * as it is deliberately. Moving the set to the companion object, the shape
     * [mirrorCopiesInFlight] and its siblings use, would let the engine whose
     * notice the manager's own per-activity throttle swallowed spend the other
     * engine's budget, and the failure would be silence about a save that is not
     * reaching the device folder. A duplicate of the same sentence is the cheaper
     * way to be wrong, and two is not the wall this set exists to stop.
     */
    internal val refusalsAnnounced = ConcurrentHashMap.newKeySet<String>()

    /**
     * What the device reported for each document, as (COLUMN_LAST_MODIFIED, COLUMN_SIZE)
     * by absolute mirror path, when this engine last read it ([initialSync] phase 2) or
     * wrote it (every write-back), with the SHA-256 of what phase 2 fetched, of the mirror
     * copy it kept, taken from the record or read by [digestKeptCopies], or of what a landed
     * write streamed. A save may replace exactly that and nothing newer; [keepsDeviceEdit]
     * is what asks. Where phase 2 could not place what the device holds there is no stamp,
     * only the digest of the copy the previous record vouched for at that path, and none
     * either once a stamp has moved past what this engine read and the bytes did not show
     * the same version, until a read does or a write lands ([deviceMovedPast]). A read that
     * finds the digested bytes at a moved stamp makes that stamp the one compared next, and
     * where the provider did not answer the stamp query, a placeholder no answer equals,
     * (0, [UNANSWERED_SIZE]), so the next answer is read against the bytes again.
     *
     * A stable completed upload also records the delivered mirror identity through
     * [recordDeliveredCopy], so a later device deletion can remove that disposable copy.
     * Scoped per mirror in
     * [initialSync] like [unfetched], and for the same reason: a switch that fails partway
     * restores the previous folder's watcher on this engine, and its entries must survive
     * that. Follows a directory rename, as [unfetched] does ([renameDeviceMemoryUnder]): the
     * device holds the same documents under the new name, and an entry left at the old one
     * leaves every file below the directory unguarded until the next open.
     */
    internal val deviceSeen = ConcurrentHashMap<String, DeviceState>()
    internal val mirrorGenerations = ConcurrentHashMap<String, Any>()

    /**
     * How long a write to the device waits for another write to the same mirror to finish
     * before it gives up and is announced as kept inside the app.
     */
    internal var outboundTurnWaitMs: Long = OUTBOUND_TURN_WAIT_MS

    /**
     * How long a delete, a create or a rename waits for another folder's write to the same
     * provider before it is refused. See [claimProviderMutationWithin].
     */
    internal var providerClaimWaitMs: Long = PROVIDER_CLAIM_WAIT_MS

    internal fun outboundAccess(root: File, generation: Any? = null): OutboundAccess {
        val access = mirrorAccess.computeIfAbsent(root.absolutePath) { MirrorAccess() }
        val observed = generation
            ?: mirrorGenerations.computeIfAbsent(root.absolutePath) { access.generation }
        return OutboundAccess(access, observed, outboundTurnWaitMs)
    }

    /**
     * Runs [action] with the mirror to itself, see [OutboundAccess.run]; null when it must not run.
     *
     * [giveBack] is for the provider claim a write took before it asked for the turn, and runs
     * before the turn is released, whether or not [action] ran. The claim is per provider and a
     * save asks for it without waiting, and a write waiting for the turn is handed it the moment
     * it is freed and asks for the claim next: a claim given back after the turn was found still
     * held in that gap, and the save that had only waited was held back instead of sent. A
     * delete and a create take theirs before the turn too, and give it back the same way.
     */
    internal fun <T> withCurrent(access: OutboundAccess?, giveBack: (() -> Unit)? = null, action: () -> T): T? =
        if (access == null) action() else access.run(giveBack, action)

    internal fun <T> withCurrentMemory(access: OutboundAccess?, action: () -> T): T? =
        if (access == null) action() else access.update(action)

    /**
     * Saves [keepsDeviceEdit], [writeLocalToSaf], or an open arm held back, by absolute
     * mirror path: the document each was going to, and when [retryHeldBack] tries it again.
     *
     * A held-back save used to wait for the next save of that file or the next open of the
     * folder, so on a provider that reads only while it is online, Nextcloud among them, the
     * save stayed inside the app for as long as the user did neither, long after the network
     * came back. Tried again as the save it was, through every guard a save goes through, so
     * what decides is what is known of the device's bytes, never the time that has passed.
     *
     * Scoped per mirror in [initialSync] like [deviceSeen], because an open that reads file
     * times settles the mirror's newer copy as it settles any save the watcher did not
     * deliver. One with no time to compare leaves a file whose two copies differ as they
     * are, so it keeps that file's hold, the one thing saying the mirror's copy was never
     * sent. One that skips the file for its size puts back no hold, since it refuses the
     * file's saves until an open finds the document smaller, and nor does one that cannot
     * list the folder, or the directory holding the file, which leaves the file no stamp
     * either; saves stay blocked until a later open reads it. A hold the closing folder's drain
     * records after the open has cleared the folder's holds, its read having outlasted the
     * stop, is refused like a save of the file where the open could not read the device
     * copy; see [processWriteBack]. In memory only, on an engine that lives as long as the
     * activity whose [SafStorageManager] built it (see [shutdown]): once that activity is
     * destroyed, as swiping the app from Recents does while the process goes on serving, the
     * next activity's open of a folder with no clock sends nothing, and the next save of the
     * file is what goes out. Tried only by the loop watching that mirror, so nothing is
     * written into a folder that is closed. A hold ends at [keepsDeviceEdit]'s next answer
     * for its file, at any write of the file, which sends what the hold was waiting to send
     * (a create writes a document it has just made without asking [keepsDeviceEdit]), at a
     * refusal of it, or once the file is deleted or gone from the mirror. A directory rename
     * ends it too, because the hold names the document by the URI it had and a provider whose
     * ids are paths gives that URI to whatever is made at the old path next, but the save is not
     * dropped there: the rename's job sends it to the new name, where [createOneInSaf] finds the
     * document by name and asks [keepsDeviceEdit] again, and a hold that comes due while its
     * file is between the two halves of a rename is sent by that rename ([retryHeldBack]).
     *
     * The waits are read off the monotonic clock, never wall time, for the reason
     * `SafStorageManager.onWriteBackFailed` gives about its throttle: a wall clock corrected
     * backwards, by NTP after a drifted RTC or by the user setting the date, left every wait
     * unfinished until the clock caught up, and the save inside the app until then.
     */
    internal val heldBack = ConcurrentHashMap<String, HeldBackSave>()

    /**
     * Prior wait of a [heldBack] entry that [keepsDeviceEdit] or [initialSync] just
     * settled, so a [writeLocalToSaf] that declines for an in-flight write in the same
     * turn doubles the prior wait rather than restarting at [HELD_BACK_RETRY_FIRST_MS].
     */
    internal val settledHeldWait = ConcurrentHashMap<String, Long>()

    /**
     * Mirror paths of files whose delete [keepsDeviceDocument] declined because the device
     * document had moved past what this engine last read or wrote, so the device holds there
     * a version nothing here has read.
     *
     * Asked by both delete guards of a directory, beside [unfetched], because `rm -r`
     * deletes a directory right after the files in it and `deleteDocument` on the directory
     * takes whatever it still holds: without this, the file kept on the device went with its
     * directory a moment later, under a notice saying it was kept. Not [unfetched] itself,
     * which also refuses a file made again under the name until the next open, where
     * [keepsDeviceEdit] can keep the device's version and send the new file at once.
     *
     * Ends at a landed write of the path, after which the device holds this app's bytes
     * there, past whatever [keepsDeviceEdit] kept first. Follows a directory rename and is
     * scoped per mirror in [initialSync], as [unfetched] is.
     */
    internal val keptOnDevice = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many bytes [digestKeptCopies] reads per open, [KEPT_COPY_DIGEST_BYTES]. A seam
     * because no JVM test can hold that much in kept copies.
     */
    internal var keptCopyDigestBytes = KEPT_COPY_DIGEST_BYTES

    /**
     * The tree [docIdCache]'s entries were resolved against. The cache is cleared
     * and refilled per folder, and a write-back drain can outlive its folder, so
     * anything resolving at processing time has to know whether the cache still
     * speaks for the tree the job belongs to before it reads an answer from it.
     *
     * Moved only by [resetCache], which [initialSync] calls before it fills the cache.
     * An event for any other tree resolves against the provider and caches nothing: it
     * used to adopt the cache for its own tree instead, which is the interleaving the
     * note on [docIdCache] describes.
     */
    @Volatile
    internal var cacheTree: Uri? = null

    /** The cached document id for [relativePath], or null when the cache does not speak for [treeUri]. */
    @JvmName("cachedDocId")
    internal fun cachedDocId(treeUri: Uri, relativePath: String): String? =
        synchronized(docIdCache) { if (cacheTree == treeUri) docIdCache[relativePath] else null }

    /** Remembers [docId] for [relativePath], unless the cache speaks for another tree by now. */
    @JvmName("cacheDocId")
    internal fun cacheDocId(treeUri: Uri, relativePath: String, docId: String) {
        synchronized(docIdCache) { if (cacheTree == treeUri) docIdCache[relativePath] = docId }
    }

    /**
     * Empties the cache and labels it for [treeUri]: the state [initialSync] puts it in
     * before filling it, and the only way the label moves.
     *
     * Internal so a test can hand an engine the folder a sync would have handed it
     * without running one. Most event tests call [handleMirrorEvent] directly rather than
     * start a watcher, and an engine that never synced speaks for no tree at all: every
     * one of their resolutions would go to the provider, and a case about what the cache
     * must not answer would pass with nothing in it.
     */
    internal fun resetCache(treeUri: Uri) {
        synchronized(docIdCache) {
            docIdCache.clear()
            cacheTree = treeUri
        }
    }

    /**
     * Directories that have just left the mirror and may be the first half of a rename.
     *
     * inotify reports a rename as MOVED_FROM on the old name and MOVED_TO on the new one
     * and pairs the two with a cookie, which [FileObserver] does not expose, so the
     * halves arrive here as an unrelated delete and create. This is what carries the
     * first half across to the second: [renameSourceFor] decides whether a MOVED_TO may
     * claim one, and a claim turns the create into a rename of the device's own document.
     *
     * Entries expire on their own ([RENAME_PAIR_WINDOW_MS]); nothing acts when one does.
     * An unclaimed entry therefore costs the behaviour that would have happened anyway.
     */
    internal val vanishedDirectories = mutableListOf<VanishedDirectory>()

    /**
     * The saves, creates and held saves the write-back thread dropped for a file that was under a
     * directory that left the mirror, by that directory's absolute path, the ones it dropped in the
     * moment before the departure was reported included ([WatchSession.recentDrops]). The file is gone
     * from its path and the job is dropped, so the rename that claims the directory no longer finds the
     * save among the queued ones: this is what it adds. Guarded by [vanishedLock], forgotten
     * with the directory's entry when nothing claims it, and handed to the session by a claim.
     */
    internal val droppedSaves = HashMap<String, MutableList<String>>()

    /**
     * The identity ([BasicFileAttributes.fileKey]) of each directory [watchTree] put an observer on,
     * by absolute path, read when the watch was placed: a directory that has left the mirror cannot be
     * read any more, and its MOVED_FROM takes this into [VanishedDirectory.directoryKey] for
     * [renameSourceFor] to compare with the arriving directory's own. Leaves with the watch. A directory
     * past [MAX_WATCHED_DIRECTORIES] has none. Internal so a test that calls [handleMirrorEvent] directly
     * can record one as a watch would.
     */
    internal val watchedDirectoryKeys = ConcurrentHashMap<String, Any>()
    internal val vanishedLock = Any()

    // -- Initial Sync --

    /**
     * Performs a full sync from SAF tree URI to the local mirror directory.
     *
     * @param safUri The tree URI granted by the SAF folder picker.
     * @param mirrorDir The local directory to mirror into.
     * @param onProgress Callback with (filesDone, totalFiles).
     * @throws FolderBusyException when another sync of this mirror, or a save, delete or
     *   rename still being sent to the provider, holds it: the mirror is left as it was, and
     *   the caller says the folder is busy.
     */
    suspend fun initialSync(
        safUri: Uri,
        mirrorDir: File,
        onProgress: (Int, Int) -> Unit
    ) = initialSync(safUri, mirrorDir, recordChanges = false, onProgress = onProgress)

    /**
     * [initialSync], and with [recordChanges] what the person changes in the mirror while it
     * runs, kept for [startWatching] to send; see [ChangesDuringOpen]. Only the folder open
     * the editor waits on asks for it ([SafStorageManager.syncToLocal]).
     */
    suspend fun initialSync(
        safUri: Uri,
        mirrorDir: File,
        recordChanges: Boolean,
        onProgress: (Int, Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val access = mirrorAccess.computeIfAbsent(mirrorDir.absolutePath) { MirrorAccess() }
        if (access.lock.isHeldByCurrentThread || !access.lock.tryLock()) {
            synchronized(access.stateLock) {
                if (mirrorGenerations[mirrorDir.absolutePath] !== access.generation) {
                    unfetched.add(mirrorDir.absolutePath)
                }
            }
            throw FolderBusyException()
        }
        val preserved = mutableListOf<File>()
        val localPreserved = mutableListOf<File>()
        var changes: ChangesDuringOpen? = null
        // The receipts the open took out of memory and has not yet written into a record ([writePendingReceipts]).
        val receiptsTaken = HashMap<String, String>()
        try {
            preservedDuringSync.set(preserved)
            localPreservedDuringSync.set(localPreserved)
            synchronized(access.stateLock) {
                access.syncing = true
                access.generation = Any()
                mirrorGenerations[mirrorDir.absolutePath] = access.generation
            }
            dropChangesDuringOpen()
            // What a switch away from this folder recorded ends before this open's own watches start, which would
            // share each directory's watch with it and lose it when it is stopped. This open reads what changed
            // since as a change made while the folder was closed.
            dropPausedChanges(mirrorDir)
            // Kept where a stop or a shutdown arriving during the open finds it and ends its watches.
            if (recordChanges) changes = watchChangesDuringOpen(mirrorDir).also { recording ->
                synchronized(lifecycleLock) {
                    if (shutDown) recording.close() else changesDuringOpen[mirrorDir.absolutePath] = recording
                }
            }
            recordingDuringSync.set(changes)
            initialSyncHoldingMirror(safUri, mirrorDir, onProgress, changes, receiptsTaken)
        } catch (failure: Throwable) {
            // A folder that did not open gets no watcher, so nothing would send what was recorded.
            changes?.let {
                changesDuringOpen.remove(mirrorDir.absolutePath, it)
                it.close()
            }
            throw failure
        } finally {
            // Put back on every way out, a failure and an early return included, unless a record holding them was
            // written: put back only at the end of an open that wrote none, they were lost to one that ended before it.
            receiptsTaken.forEach { (path, receipt) -> deliveredDuringSync.putIfAbsent(path, receipt) }
            preservedDuringSync.remove()
            localPreservedDuringSync.remove()
            recordingDuringSync.remove()
            synchronized(access.stateLock) { access.syncing = false }
            access.lock.unlock()
            if (preserved.isNotEmpty()) onDeviceCopiesPreserved(preserved.toList())
            if (localPreserved.isNotEmpty()) onLocalCopiesPreserved(localPreserved.toList())
        }
    }

    /**
     * One file's line in the synced record: relative path, mtime, size, tab separated. That
     * is the whole line unless [recordLine] puts the copy's digest after it, and every
     * reader takes the digest off with [splitRecordLine] before it compares.
     *
     * Shared rather than spelled out at each site, because two of them have to agree
     * byte for byte or the disagreement shows up as behaviour: [recordIdentity] writes
     * the line and [holdsOnlyVouchedCopies] looks the same line up. A fixture that
     * composes it by hand is a third reader of the same bytes, which is why the tests
     * call this too.
     *
     * The path is spelled through [escapeRecordPath], so a name holding a tab or a line
     * break is one line of three fields like any other. Such a name used to be left out
     * of the record instead, and a path the record never names is what
     * [uploadMirrorOnlyDocuments] reads as "never on the device": a document the user
     * deleted on the device came back on the next open, [reconcileDeletions] could not
     * remove its mirror copy, and [setAsideDivergedMirror] set that copy aside on every
     * device-side change. Readers that want the path back go through
     * [unescapeRecordPath]; the ones comparing whole lines need nothing, since both
     * sides come through here.
     */
    internal fun identityLine(path: String, file: File): String =
        identityLine(path, file.lastModified(), file.length())

    /** The same line for a copy whose time and length are already known. */
    internal fun identityLine(path: String, lastModified: Long, length: Long): String =
        "${escapeRecordPath(path)}\t$lastModified\t$length"

    /**
     * Starts watching the mirror directory for changes and syncing them back to SAF.
     * Must be called after [initialSync] completes.
     *
     * What the open recorded the person doing meanwhile ([ChangesDuringOpen]) is handed
     * over directory by directory, by the directory's identity: this watcher's watch of each
     * starts first, and from then on the kernel reports to it alone, so each event reaches one
     * of the two; see [ChangesDuringOpen.handOver]. A folder the open made itself had no watch
     * until this one. The record is then sent by the write-back thread before anything else,
     * through the same guards as an event this watcher sees, and what this watcher queues
     * meanwhile waits behind it; see [replayChangesDuringOpen].
     *
     * A no-op once [shutdown] has run; see [shutDown] for why that is a refusal rather
     * than a start that happens to be late.
     */
    fun startWatching(mirrorDir: File, safUri: Uri) {
        synchronized(lifecycleLock) {
            if (shutDown) {
                Logger.i(
                    tag,
                    "Not watching ${mirrorDir.name}: this engine's owner is gone and " +
                        "nothing could stop the watcher again",
                )
                return
            }
            // Taken before the stop, which drops what no watcher is about to take. What changed while a switch that
            // failed had this folder's watcher stopped is sent the same way; see [pauseWatching].
            val changes = takeChangesDuringOpen(mirrorDir) ?: takePausedChanges(mirrorDir)
            stopWatching()

            // Published before any observer exists so each captures its owning session.
            // A switch that failed leaves the cache speaking for the folder that did not open, and every lookup that
            // asks whether it speaks for this one declined: a save in a folder renamed while the watcher was stopped,
            // and a move into a folder made then, went nowhere. Emptied for this folder, it is filled again by lookups.
            if (cacheTree != safUri) resetCache(safUri)
            val opening = WatchSession(mirrorDir, mirrorGenerations[mirrorDir.absolutePath])
            session = opening
            isWatching = true
            // Before the first observer starts: what it queues waits behind what the open recorded.
            if (changes != null) opening.holdLiveJobs()
            handingOver = changes
            try {
                watchTree(mirrorDir, mirrorDir, safUri, opening)
            } finally {
                handingOver = null
                changes?.close()
            }

            // Background thread to process this session's write-back queue. Handed over
            // before it starts, so a stop arriving at once cannot find a null worker for a
            // thread that is already running. What the open recorded goes first, off this
            // lock, because turning it into jobs asks the provider.
            val worker = thread(start = false, name = "saf-writeback", isDaemon = true) {
                // Contained, and the loop runs whatever became of it: an exception that ended the thread here left
                // the saves held behind the replay, and every save after them, in a queue nothing polled.
                if (changes != null) try {
                    if (!replayChangesDuringOpen(changes, mirrorDir, safUri, opening)) {
                        onOpenChangesUnsent(mirrorDir, changes.afterSwitch)
                    }
                } catch (t: Throwable) {
                    Logger.w(tag, "Could not send what changed while ${mirrorDir.name} opened: ${t.javaClass.simpleName}")
                    if (t is InterruptedException) Thread.currentThread().interrupt()
                    onOpenChangesUnsent(mirrorDir, changes.afterSwitch)
                }
                runWriteBackLoop(opening) { opening.running }
            }
            opening.worker = worker
            worker.start()

            val watched = synchronized(watchersLock) { watchers.size }
            Logger.i(tag, "File watcher started for ${mirrorDir.absolutePath} ($watched directories)")
        }
    }

    /**
     * Processes queued write-backs until [isRunning] goes false or the thread is
     * interrupted, then sends out whatever is still queued. Whenever the queue runs empty
     * it tries a held-back save whose wait is over; see [retryHeldBack].
     *
     * Drives whichever session is current, which is what a test without a live watcher
     * needs: a real [FileObserver]'s static initializer reaches native code, so a test that
     * starts a watcher stands in for its watches ([directoryWatchFactory]), and most drain the
     * open folder's queue pass by pass instead. A pass leaves [WatchSession.abandoned] as it
     * found it while it is still the current one: the folder is still open, and an event
     * after the pass is announced as undeliverable only where the session really is one no
     * drain will poll again.
     */
    internal fun runWriteBackLoop(isRunning: () -> Boolean) {
        val current = session
        val abandoned = current.abandoned
        runWriteBackLoop(current, isRunning)
        if (current === session) current.abandoned = abandoned
    }

    /**
     * Stops the file watcher and drains the write-back queue.
     */
    fun stopWatching() {
        synchronized(lifecycleLock) {
            // A switch records the folder it closes through the watcher's own watches, each turned to the recording
            // before the watcher stops sending, so no watch of the folder ends and each event the kernel reports
            // reaches the one or the other. The recording used to start watches of its own once these had ended,
            // after a walk of the whole folder, and what a program did in that time reached neither. Turned under the
            // lock a watch is added under, so a folder the watcher takes up meanwhile is turned with the rest.
            val recording = if (pausing && !shutDown && session.root != null) ChangesDuringOpen(afterSwitch = true) else null
            synchronized(watchersLock) {
                if (recording != null) for ((dir, observer) in watchers) {
                    (watchedDirectoryKeys[dir.absolutePath] ?: directoryKeyOf(dir))?.let { observer.recordInto(recording, it) }
                }
                isWatching = false
                // A watch the recording took is stopped by it: stopped here, it would end with the kernel's watch of
                // the directory, which the two share.
                watchers.values.forEach { if (!it.recorded) it.stopWatching() }
                watchers.clear()
                watchedDirectoryKeys.clear()
            }
            val closing = session
            // Stop accepting new observer callbacks for this session and wait briefly for any
            // already-observed callbacks to finish queueing into closing.queue before switching
            // sessions or stopping the write-back worker. handleMirrorEvent/watchTree/unwatchTree
            // never acquire lifecycleLock, so awaiting after watchersLock is released cannot
            // deadlock; if a provider call stalls, force-shutdown detaches it from future sessions.
            closing.drainObserverDispatcher(observerDrainGraceMs)
            dropChangesDuringOpen()
            dropPausedChanges()
            // Kept until a start of this folder takes it, before the drain below, whose wait a program in the folder
            // goes on writing through.
            recording?.let { pausedChanges = closing.root!!.absolutePath to it }
            session = WatchSession().apply { abandoned = true }
            closing.running = false

            // Wake the thread out of its wait, then wait for it to drain remaining writes.
            // An idle queue drains in microseconds, so this costs only what there is to lose.
            // Not interrupted: a save in flight would fail its next read of the local file.
            val worker = closing.worker
            closing.worker = null
            worker?.let { LockSupport.unpark(it) }
            try { worker?.join(DRAIN_GRACE_MS) } catch (_: InterruptedException) {}

            if (worker != null && worker.isAlive) {
                // Still draining. Emptying the queue from here would throw away exactly the
                // writes the drain exists to save, and would do it in the case where there
                // are the most of them: a burst of saves, or one slow provider. The thread
                // owns this session's queue until it finishes, and nothing else can reach
                // it: the queue went with the session, so the folder opened next offers its
                // jobs somewhere this drain never polls. Addressing was never the question.
                // Two threads polling one queue was: each write opens its document with
                // "wt", which truncates at open, so the two interleave inside one document.
                Logger.w(tag, "Write-back still draining after ${DRAIN_GRACE_MS}ms; leaving it to finish")
            } else {
                // Anything still here arrived after the drain took its last look, which is
                // the one window an event observed while the folder was open can still fall
                // into. Counted and announced rather than dropped in silence: the mirror keeps
                // the file and the record cannot vouch for it, so nothing is destroyed, but the
                // save is not on the device, only reopening the folder puts it there, and the
                // editor shows the file as saved. A user asking why needs the line in a bug
                // report too. Marked abandoned first, so an event offering meanwhile says so
                // itself. A file gone since has nothing to send, and one that went with a
                // renamed directory is named by that rename.
                closing.abandoned = true
                val dropped = generateSequence { closing.queue.poll() }.toList()
                if (dropped.isNotEmpty()) {
                    Logger.w(tag, "${dropped.size} write-back(s) arrived after the drain ended")
                }
                for (job in dropped) when (job.type) {
                    SyncType.MODIFY, SyncType.CREATE ->
                        File(job.localPath).takeIf { it.exists() }?.let { announceLost(it) }
                    SyncType.RENAME -> announceDroppedRename(File(job.localPath), job.renamedSaves, job.mirrorRoot)
                    SyncType.DELETE -> Unit
                }
            }
            // docIdCache is deliberately not cleared here. A drain that outlived the wait
            // still needs the mappings of the folder it is finishing, and [initialSync]
            // clears the cache itself before anything reads it for the next one.
            //
            // The half-renames are cleared, and the difference is that nothing consumes them
            // after this point: a MOVED_TO of the next folder must not be able to claim a
            // directory that left the previous one, which would rename a document in a folder
            // the user has closed.
            //
            // Which is the same instant their upload records stop being able to move: a claim
            // is the only thing that carries a line to the directory's new path. Retired here
            // rather than left standing, because after this nothing can; see
            // [consumeStaleUploadsUnder].
            dropVanished { true }
            Logger.i(tag, "File watcher stopped")
        }
    }

    /**
     * [stopWatching] for a switch to another folder, which records what changes in the folder being closed from the
     * moment the watcher stops sending it, through the watcher's own watches ([ChangesDuringOpen]): there is no
     * moment between the two in which a change reaches neither. A [startWatching] of that same folder, which a switch
     * that fails makes to put its watcher back, sends it as it sends what an open recorded ([replayChangesDuringOpen]).
     * Unrecorded, a file a program saved there while the other folder opened stayed inside the app until its next
     * save or the next open, and one it deleted came back at the next open, while the notice of the failed switch
     * said the folder already open was unaffected. A start of any other folder, an open of this one, a stop and a
     * shutdown drop the recording; the next open of the folder reads what it held as changes made while it was closed.
     */
    fun pauseWatching() {
        synchronized(lifecycleLock) {
            pausing = true
            try {
                stopWatching()
            } finally {
                pausing = false
            }
        }
    }

    /**
     * Ends what [pauseWatching] recorded when the folder's watcher is not put back after all, as after a switch that
     * failed on the folder itself; its next open reads what changed as changes made while it was closed.
     */
    fun dropPausedRecording() = dropPausedChanges()

    /** What changed in [mirrorDir] since a switch stopped watching it, for [startWatching]; nothing once taken. */
    internal fun takePausedChanges(mirrorDir: File): ChangesDuringOpen? = synchronized(lifecycleLock) {
        pausedChanges?.takeIf { it.first == mirrorDir.absolutePath }?.second?.also { pausedChanges = null }
    }

    /** Ends what [pauseWatching] recorded, of [mirrorDir] alone when one is named. */
    internal fun dropPausedChanges(mirrorDir: File? = null) {
        val dropped = synchronized(lifecycleLock) {
            pausedChanges?.takeIf { mirrorDir == null || it.first == mirrorDir.absolutePath }?.also { pausedChanges = null }
        }
        dropped?.second?.close()
    }

    /**
     * Stops the watcher for good: the same teardown as [stopWatching], plus the promise
     * that no later [startWatching] will undo it.
     *
     * For the owner that is going away rather than switching folders. The two differ
     * only in what may follow: a folder switch stops one watcher and starts the next on
     * the same engine, while a teardown has no next. A start still on its way when this
     * runs -- the one in a coroutine of the Activity being destroyed, which is not
     * cancellable once it is inside the engine -- would otherwise finish afterwards and
     * leave observers and a drain running on an engine no one holds any more.
     */
    fun shutdown() {
        synchronized(lifecycleLock) {
            shutDown = true
            stopWatching()
        }
    }

    // -- Internal: Watch Registration --

    /**
     * Skip patterns: large auto-generated directories that would slow sync unnecessarily.
     */
    internal fun shouldSkip(name: String, isDir: Boolean): Boolean =
        Companion.shouldSkip(name, isDir)

    // -- Internal: File Operations --

    /**
     * How an entry is moved to a name only while that name is free: [ExclusiveMove.move] with
     * this build's helper, unavailable where there is none. A `var` so that the suite, which
     * runs where the helper does not, can stand in for it.
     */
    internal var exclusiveMove: (directory: File, key: Any, from: String, to: String) -> ExclusiveMove.Outcome =
        { directory, key, from, to ->
            moveHelper?.let { ExclusiveMove.move(it, directory, key, from, to) } ?: ExclusiveMove.Outcome.UNAVAILABLE
        }

    internal val moveHelper: File? by lazy {
        try {
            File(com.vscodroid.util.Environment.getMoveHelperPath(context)).takeIf { it.isFile }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * How the fetched copy is renamed over the file at [dest], which [installFetched] has
     * judged; a `var` so the suite can put a save in the moment before the rename.
     */
    internal var replaceName: (dest: File, rename: () -> Unit) -> Unit = { _, rename -> rename() }

    /**
     * The pause between two copies of a save [keepHeldSave] keeps; a `var` so the suite can write into the
     * held file there.
     */
    internal var heldSaveSettle: () -> Unit = { Thread.sleep(HELD_SAVE_SETTLE_MS) }

    /**
     * How [keepHeldSave] makes the `.local-` file a held save goes to; a `var` so the suite can make that fail as a
     * full disk does.
     */
    internal var createKeptSave: (kept: File, create: () -> SeekableByteChannel) -> SeekableByteChannel = { _, create -> create() }

    /**
     * Told when a write-back has given up on [File]. Does nothing until something wires
     * it up, and [MainActivity] is what does.
     *
     * The default is a no-op rather than a Toast, and that is not squeamishness about
     * layers. A default that touched `Looper.getMainLooper()` threw "not mocked" in every
     * existing JVM test that drives a failing write-back, which is a fair warning: this
     * class has no screen and should not reach for one. The layer that owns a screen
     * decides how to say it.
     *
     * A failed upload leaves its journal entry for a later initial sync. A refusal
     * before the write begins, either [refuseUnreadDocument] or an unavailable
     * upload journal, has no new entry. The local copy remains in the mirror.
     *
     * What was missing is the user knowing, and that is a different fact from the data
     * being safe. A save that never reached the device folder looks exactly like one
     * that did, and the difference only shows when the app is uninstalled and the work
     * is gone.
     */
    internal var onWriteBackFailed: (File) -> Unit = {}

    internal var onDeviceCopiesPreserved: (List<File>) -> Unit = {}
    internal val preservedDuringSync = ThreadLocal<MutableList<File>>()

    /** What the open running on this thread records the person doing meanwhile; see [ChangesDuringOpen.placed]. */
    internal val recordingDuringSync = ThreadLocal<ChangesDuringOpen?>()

    /** The session whose job [processWriteBack] is sending on this thread, whose queued renames a create leaves alone. */
    internal val writeBackSession = ThreadLocal<WatchSession?>()

    /**
     * Told, once per [initialSync], when documents the device holds did not reach the
     * mirror: how many, and whether the sizes reported for them did not fit in the room
     * that was left or free space fell below [OPEN_SPACE_FLOOR_BYTES] and held them back.
     *
     * Its own seam rather than a reuse of [onWriteBackFailed], because the two say
     * opposite things. That one means the app holds the only copy; this one means the
     * DEVICE does and the editor simply does not have the file, so a notice worded "the
     * only copy is inside VSCodroid" would send the user looking after a file that is
     * safe where it is. A no-op by default for the reason that one is: no screen here.
     */
    internal var onDocumentsNotCopied: (Int, Boolean) -> Unit = { _, _ -> }

    /**
     * Told when a directory being copied out to the device arrived incomplete: which
     * directory, how many entries did not make it, and whether the enumeration cap was
     * the reason rather than a provider refusal.
     *
     * The outbound twin of [onDocumentsNotCopied], and a separate seam from
     * [onWriteBackFailed] for a reason of accuracy rather than symmetry. That one says
     * the app holds the only copy, which is true of a single file that never arrived
     * and false of a directory where most entries did: announcing forty failures with
     * it would overstate forty times, and announcing them one by one is another way of
     * not telling the user anything.
     *
     * Also told once per [initialSync], with the mirror ROOT, of the files only the mirror
     * holds that its put-back pass could not land, and of the cap that pass stopped at.
     * Not of the files an open keeps inside the app on purpose: [onFilesKeptInApp].
     */
    internal var onUploadIncomplete: (File, Int, Boolean) -> Unit = { _, _, _ -> }

    /**
     * Told, with the mirror ROOT, how many files only the mirror holds an open keeps inside the
     * app because it cannot tell them from a document deleted on the device, and the first of
     * them by relative path: those [initialSync] leaves out or gives a row of their own, and
     * those [replayChangesDuringOpen] does not send. Its own seam, because the two cases are
     * not one sentence: an entry the device refused or a folder did not bring along may be a
     * directory, or a file whose folder never reached the device, which no save sends; a file
     * kept here may be a document the person deleted on the device, and saving it again in the
     * editor sends it (live, or at the next open, where it no longer matches its row).
     */
    internal var onFilesKeptInApp: (File, Int, String) -> Unit = { _, _, _ -> }

    /**
     * Told when something deleted in the editor was left standing on the device,
     * because it holds content this sync never copied in: a directory holding such a
     * document, or the document itself. With [changed], because what the device holds
     * is a version of a file the editor had that this engine has not read: one that
     * moved past what it last read or wrote, as another app's edit does, or a directory
     * holding such a file; see [keepsDeviceDocument].
     *
     * Its own seam for the reason [onDocumentsNotCopied] has one: [onWriteBackFailed]
     * says the app holds the only copy, and here the opposite is true. The mirror copy
     * is gone, the device holds the only one, and a notice worded the other way would
     * send the user looking inside the app for files that are safe where they are.
     *
     * The flags are what the sentence turns on and they cannot be recovered downstream:
     * the entry is already unlinked when the event arrives, so nothing left on disk
     * can be asked whether it was a directory. "It holds files that never reached the
     * editor" is the right sentence for one case and false for the other, and "the
     * editor never had a copy of it" is false of a file the user had open and deleted.
     */
    internal var onKeptOnDevice: (file: File, isDirectory: Boolean, changed: Boolean) -> Unit =
        { _, _, _ -> }

    /**
     * Told when something the editor deleted is still on the device folder because the
     * delete did not happen there: the provider refused it, or the engine could not name the
     * document to ask it about, as when a folder was renamed and removed again inside one
     * poll, the device had no document of the new name yet, and this engine had not read
     * what the rename carried there ([SafSyncEngine.resolvedAtRun]).
     *
     * Its own seam rather than [onKeptOnDevice], which reads as a decision this
     * app made and explains it with a reason that is false here: the editor did
     * have a copy, and it deleted it. The outcome is the same either way, so the
     * sentence has to be about the device folder rather than about the editor.
     *
     * Worth a notice at all because the outcome is not merely a stale document.
     * The mirror entry is unlinked whatever the provider says, so the next time
     * the folder is opened the document is copied back in and the file the user
     * deleted returns, with nothing in between having said why.
     */
    internal var onDeleteRefused: (File) -> Unit = {}

    /**
     * Told when a deletion the editor made did not reach the device folder because another
     * write to the same provider still held it after [providerClaimWaitMs]: a closing
     * folder's save, or a second drain of this one.
     *
     * Its own seam rather than [onDeleteRefused], whose sentence says the device folder
     * refused and sends the person to delete the file in their file manager. Nothing
     * refused here, and the file comes back at the next open, where deleting it again
     * works. Not retried: the delete has to name the document it resolved, and a later
     * lookup by path finds whatever stands there by then.
     */
    internal var onDeleteBusy: (File) -> Unit = {}

    /**
     * Told, with the mirror ROOT, when the replay of what a program changed while the folder opened
     * ([replayChangesDuringOpen]) failed before it had sent all of it, or held back a removal the device could not
     * be asked about. What it had not sent then stays as the replay found it: a save inside the app until the file
     * is saved again, a deletion or a rename not made on the device, so the next open brings the device's copy back.
     * Its own seam because none of the others is true of all three. A no-op by default for the reason
     * [onWriteBackFailed] is.
     *
     * The second argument is true for what a switch recorded ([ChangesDuringOpen.afterSwitch]): the replay of the
     * folder whose watcher a switch that failed put back, which never opened then. Told as the open's, it said the
     * changes were made while that folder was opening, just after the notice of the failed switch said the folder
     * was unaffected.
     */
    internal var onOpenChangesUnsent: (File, Boolean) -> Unit = { _, _ -> }

    /**
     * Told, with the file, when a save made into it as an open put the device's newer copy in its place could not be
     * kept whole ([keepHeldSave]): the file holds the device's version, and what was written of the save, if anything,
     * is the `.local-` copy beside it, told of through [onLocalCopiesPreserved] as well. Its own seam, because that
     * notice says a copy was kept, which is not all of what happened, or not true at all where none could be made.
     */
    internal var onHeldSaveIncomplete: (File) -> Unit = {}

    /**
     * Told when mirror copies or conflicting local entries are set aside under `.local-`
     * because the device folder held a newer copy or incompatible kind: once per batch after
     * [initialSync] finishes, or once per live copy outside a sync.
     */
    internal var onLocalCopiesPreserved: (List<File>) -> Unit = {}
    internal val localPreservedDuringSync = ThreadLocal<MutableList<File>>()

    internal fun notePreservedLocalCopy(file: File) {
        val batch = localPreservedDuringSync.get()
        if (batch == null) onLocalCopiesPreserved(listOf(file)) else batch.add(file)
    }

    /**
     * How much room is left where the mirror lives.
     *
     * A seam because no JVM test can make a real filesystem answer that it is full, and
     * the pre-flight and the floor that read this are exactly the code that has to be
     * right when it is.
     *
     * `usableSpace` rather than `StorageManager.getAllocatableBytes`, which lint asks for
     * and which reports more, because it counts cache Android is willing to evict. This
     * also decides whether a fetch is attempted at all, through the floor in
     * [copyDocumentToLocal], and the larger figure is wrong for that too, although this
     * comment once called it the right one for deciding a write: the evictable cache
     * belongs to other apps, it is not space this app can count on, and free space is
     * what the floor exists to leave behind.
     */
    internal var usableSpaceOf: (File) -> Long = defaultUsableSpaceOf

    /**
     * Declines to write [localFile] out, because the device holds a document under that
     * name this sync never read, and says so once per file.
     *
     * Every file-level refusal site comes through here, and the message living in one
     * place is what keeps them saying the same thing: [handleMirrorEvent] asks before it
     * queues a job, [processWriteBack] asks again before it writes a save, a held-back
     * save's try included, and [createOneInSaf] asks the same question while walking into
     * a directory. One helper is the only thing holding those sites to the same words.
     *
     * The notice is [onWriteBackFailed]'s and it fits: the file did not reach the device
     * folder, and the copy inside the app is the only one of it that exists. What the
     * device holds under that name is a different document, which is why this refuses.
     * A refused delete does not come through here, because for it the opposite is
     * true; see [keepUnread].
     */
    internal fun refuseUnreadDocument(localFile: File, describedAs: String) {
        Logger.w(
            tag,
            "Not writing $describedAs back: the device holds a document there " +
                "that this sync never read, and writing would replace it",
        )
        announceLost(localFile)
    }

    /**
     * Says once that [localFile] exists only inside the app.
     *
     * The throttle is per path and lives here rather than at each site, so a file the
     * watcher retries on every save is announced once instead of on every keystroke.
     * [initialSync] clears the set for a folder when it is reopened, which is the one
     * moment the user could act on the notice again. Named precisely because it was
     * named wrongly here for a while: [startWatching] never touches the set, and a
     * reader deciding when a refusal can be said again went to the wrong function.
     */
    internal fun announceLost(localFile: File) {
        if (refusalsAnnounced.add(localFile.absolutePath)) onWriteBackFailed(localFile)
    }

    internal fun announceDroppedRename(
        localFile: File,
        renamedSaves: List<String>,
        mirrorRoot: File?,
    ) {
        announceLost(localFile)
        announceUnsentSaves(renamedSaves, mirrorRoot)
    }

    /** Announces each of [savedPaths], mirror-relative, that is still a file or folder in the mirror. */
    internal fun announceUnsentSaves(savedPaths: List<String>, mirrorRoot: File?) {
        if (mirrorRoot == null) return
        for (savedPath in savedPaths) {
            val savedFile = File(mirrorRoot, savedPath)
            if (savedFile.exists() && !isLink(savedFile)) {
                announceLost(savedFile)
            }
        }
    }

    internal fun clearRefusalNotice(localFile: File, isDirectory: Boolean) {
        val path = localFile.absolutePath
        if (isDirectory) {
            val prefix = path + File.separator
            refusalsAnnounced.removeAll { it == path || it.startsWith(prefix) }
        } else {
            refusalsAnnounced.remove(path)
        }
    }

    /**
     * Declines to delete what the device holds at [relativePath], because it is, or it
     * holds, a document this sync never read, or with [changed] a version of a file the
     * editor had that this engine has not read, and says so.
     *
     * Not through [refuseUnreadDocument]: that one announces on [onWriteBackFailed],
     * whose wording says the app holds the only copy, and here the app holds none.
     * Not through [refusalsAnnounced] either, and that is the second half of the same
     * point: something is deleted once per deletion, and a user who recreates it and
     * deletes it again is owed the notice again. Spending that budget here also costs
     * the notice its owner needs, since the next save of a recreated name is refused
     * as a write and is the one occasion on which "the only copy is inside VSCodroid"
     * is both true and urgent.
     */
    internal fun keepUnread(
        localFile: File,
        relativePath: String,
        isDirectory: Boolean,
        changed: Boolean,
    ) {
        Logger.w(
            tag,
            "Not deleting $relativePath from the device: " +
                if (changed) {
                    "what it holds there may have changed since this app last read or wrote it"
                } else {
                    "it holds content this sync never read"
                },
        )
        onKeptOnDevice(localFile, isDirectory, changed)
    }

    /**
     * Says that a deletion did not reach the device folder.
     *
     * Not routed through [keepUnread], whose sentence is about content this sync
     * never read and which is this app declining on purpose. Here the app asked
     * and was told no.
     */
    internal fun announceDeleteRefused(localFile: File) {
        Logger.w(
            tag,
            "The device folder would not delete ${localFile.name}; the next open will " +
                "copy it back into the editor",
        )
        onDeleteRefused(localFile)
    }

    /** Says that a deletion did not reach the device folder because the provider was busy. */
    internal fun announceDeleteBusy(localFile: File) {
        Logger.w(
            tag,
            "Did not delete ${localFile.name} from the device folder: another write to the " +
                "same storage still held it; the next open will copy it back into the editor",
        )
        onDeleteBusy(localFile)
    }

    /**
     * What the person changed in a mirror while an open was filling it, recorded by a watch of
     * its own because the watcher is stopped for the open: left running, it read every copy the
     * open put in place as a save and sent it straight back.
     *
     * Nothing used to watch in that window. A file deleted or moved away before the open reached
     * it was fetched back, so the deletion was undone, and one saved after the open had passed it
     * stayed in the app until its next save or the next open. Now a deletion keeps phase 2 off the
     * path ([removed]), and [startWatching] hands what was recorded to the write-back thread
     * ([replayChangesDuringOpen]), which sends it through the same guards as any change.
     *
     * The open's own moves, set-asides and removals raise the same events and are told apart by
     * what is on disk once it has finished, not by marking each of them, since the kernel
     * reports them after the fact. Only the directories that existed when the open began are
     * watched, as many as the watcher itself would take; one the open makes is new from the
     * device, and what is done inside it before the watcher starts is seen at the next open.
     *
     * A switch to another folder records the folder it stops watching the same way, through the
     * watcher's own watches of it, which go on ([pauseWatching], [afterSwitch]), for the watcher a
     * switch that fails puts back.
     */
    internal class ChangesDuringOpen(
        /**
         * Recorded while a switch to another folder ran ([pauseWatching]) rather than while this folder opened: no open
         * filled the cache for it, so the replay asks the device what it holds.
         */
        val afterSwitch: Boolean = false,
    ) {
        /**
         * The open's watch of each directory, by the directory's identity ([BasicFileAttributes.fileKey]). A watch
         * belongs to the directory and not to its name: filed by name, a folder renamed while the open ran was handed
         * over under a name the watcher never asked for, and the watch left over was stopped, which removed the one
         * the watcher's own observer of the folder shares, and the folder went unheard for the rest of the session.
         */
        private val watches = LinkedHashMap<Any, OpenWatch>()

        /** Where each watched directory is now, as far as the events recorded so far show, by identity. */
        private val directories = HashMap<Any, File>()

        /** Where each watched directory was when the open began: the name the device and the record give it. */
        private val origins = HashMap<Any, File>()

        /**
         * The watched directories that have left the mirror as far as the events show, each with the one whose
         * departure took it along, so an arrival of that one brings them back with it.
         */
        private val departedWith = HashMap<Any, Any>()

        /**
         * Every event, in the order the kernel reported it, by the order it came in. A file written again keeps only
         * its last write: the replay sends a file once, from what the mirror holds then, and the open's own copies
         * wrote an event for every 8 KiB they put in place, all kept until the folder was open.
         */
        private val events = LinkedHashMap<Long, Recorded>()
        private val lastWrite = HashMap<String, Long>()
        private var next = 0L
        @Volatile var overflowed: Boolean = false
            private set

        fun markOverflow() {
            overflowed = true
        }
        private val removedPaths = ConcurrentHashMap.newKeySet<String>()
        private val removedDirectories = ConcurrentHashMap.newKeySet<String>()
        private val placedPaths = ConcurrentHashMap.newKeySet<String>()
        private val placedDirectories = ConcurrentHashMap.newKeySet<String>()
        private val arrivedDirectories = ConcurrentHashMap.newKeySet<String>()

        /** Where each folder in [arrivedDirectories] was when the open began, by where it arrived. Guarded by this. */
        private val arrivedFrom = HashMap<String, File>()
        private val leftToReplay = ConcurrentHashMap.newKeySet<String>()
        private val keptByOpen = ConcurrentHashMap.newKeySet<String>()

        /**
         * One recorded event on [file], with the identity of the directory it names where it is one the open watched,
         * and when it came, on the clock the watcher pairs a rename's halves by ([SafSyncEngine.openEventClock]).
         */
        data class Recorded(val event: Int, val file: File, val identity: Any?, val at: Long)

        fun watch(dir: File, identity: Any, watch: OpenWatch) = synchronized(this) {
            watches[identity] = watch
            directories[identity] = dir
            origins[identity] = dir
        }

        /**
         * One inotify [event] on the entry [name] of the watched directory [directory], an identity given to [watch],
         * in the order the kernel reported it. The path is built from where that directory is now, so a save in a
         * folder renamed while the open runs is recorded under the name it has. A folder's arrival is read for its
         * identity, which is how the record learns where a watched folder went; a folder's departure carries the
         * identity it had, for [renameSourceFor].
         */
        fun record(event: Int, directory: Any, name: String, at: Long) {
            val type = event and FileObserver.ALL_EVENTS
            val isDirectory = (event and IN_ISDIR) != 0
            // The open's own staging copies, which nothing sends; and a directory's own change, which the replay skips.
            if (!isDirectory && isMachineTemporary(name) || isDirectory && type == FileObserver.MODIFY) return
            val removal = type == FileObserver.DELETE || type == FileObserver.MOVED_FROM
            val file: File
            synchronized(this) {
                if (directory in departedWith) return
                file = File(directories[directory] ?: return, name)
                var identity: Any? = null
                if (isDirectory && removal) {
                    identity = directories.entries.firstOrNull { it.value == file && it.key !in departedWith }?.key
                    identity?.let { gone ->
                        val below = directories.filter { (key, dir) -> key !in departedWith && isAtOrBelow(dir, file) }.keys
                        // A directory deleted is forgotten: its watch has ended, and its identity can be given to the next.
                        if (type == FileObserver.DELETE) below.forEach { directories.remove(it); origins.remove(it) }
                        else below.forEach { departedWith[it] = gone }
                    }
                } else if (isDirectory && type == FileObserver.MOVED_TO) {
                    identity = directoryKeyOf(file)
                    // A folder renamed onto an empty one replaces it, and the watch of the one replaced ended with it.
                    // Kept, its place answered for the next departure from this name, and its identity, which the
                    // kernel can give to the next folder made, would move whatever is recorded under the name.
                    directories.filter { (key, dir) -> key != identity && dir == file && key !in departedWith }.keys
                        .forEach { directories.remove(it); origins.remove(it) }
                    directories[identity]?.let { from ->
                        arrivedDirectories.add(file.absolutePath)
                        origins[identity]?.let { arrivedFrom[file.absolutePath] = it }
                        val group = departedWith[identity]
                        directories.filter { (key, dir) -> departedWith[key] == group && isAtOrBelow(dir, from) }.keys.forEach {
                            directories[it] = File(file.path + directories.getValue(it).path.removePrefix(from.path))
                            departedWith.remove(it)
                        }
                    }
                }
                val order = next++
                if (!isDirectory && !removal) lastWrite.put(file.path, order)?.let { events.remove(it) }
                events[order] = Recorded(event, file, identity, at)
            }
            if (removal) {
                removedPaths.add(file.absolutePath)
                if (isDirectory) removedDirectories.add(file.absolutePath)
            } else if (!isDirectory) {
                placedPaths.add(file.absolutePath)
            } else {
                placedDirectories.add(file.absolutePath)
            }
        }

        private fun isAtOrBelow(path: File, dir: File) = path == dir || path.path.startsWith(dir.path + File.separator)

        /**
         * Whether [file] was made, saved or moved in while the open ran, itself or with a folder above it. Such a
         * file is the replay's to send, so the open neither keeps it with a row of its own nor says it stayed in
         * the app ([leaveToReplay]). The open's own copies raise the same events; the replay tells them apart.
         */
        fun placed(file: File): Boolean {
            val path = file.absolutePath
            return path in placedPaths || placedDirectories.any { path.startsWith(it + File.separator) }
        }

        /**
         * Whether [file] is in a folder the open watched that arrived under another name while it ran, a rename, which
         * the replay makes on the device before it sends what the folder holds that the device lacks.
         */
        fun renamedIn(file: File): Boolean {
            val path = file.absolutePath
            return arrivedDirectories.any { path.startsWith(it + File.separator) }
        }

        /**
         * Where [file] was when the open began, for a file that came only with a folder renamed in while the open ran:
         * neither made, saved nor moved in itself, nor in a folder made then. Null for any other file.
         */
        fun renamedOnly(file: File): File? = synchronized(this) {
            val path = file.absolutePath
            if (path in placedPaths ||
                placedDirectories.any { it !in arrivedDirectories && path.startsWith(it + File.separator) }
            ) return null
            val arrived = arrivedFrom.keys.filter { path.startsWith(it + File.separator) }.maxByOrNull { it.length } ?: return null
            File(arrivedFrom.getValue(arrived), path.removePrefix(arrived + File.separator))
        }

        /** A file only the mirror holds that the open did not keep because the replay is to send it. */
        fun leaveToReplay(file: File) = leftToReplay.add(file.absolutePath)

        fun leftToReplay(): Set<String> = leftToReplay.toSet()

        /** Rows without a digest the open wrote for copies it keeps inside the app, which the replay does not send. */
        fun keptByOpen(rows: Collection<String>) = keptByOpen.addAll(rows.map { splitRecordLine(it).first })

        fun isKeptByOpen(identity: String) = identity in keptByOpen

        fun keptAnyByOpen() = keptByOpen.isNotEmpty()

        /** Whether [file] was deleted or moved away while the open ran, itself or with a folder above it. */
        fun removed(file: File): Boolean {
            val path = file.absolutePath
            return path in removedPaths || removedDirectories.any { path.startsWith(it + File.separator) }
        }

        /** Where the watched directory [identity] was when the open began, or null for one it did not watch. */
        fun origin(identity: Any?): File? = synchronized(this) { identity?.let { origins[it] } }

        /**
         * Hands the watch of the directory [identity] to the watcher that has just started its own, without stopping
         * it. Two observers of one directory share one inotify watch, and the one started last is the one the kernel's
         * events go to, so from that start each event reaches one of the two and none falls between them. Stopping this
         * one then would remove the shared watch and leave the watcher deaf; it is stopped with the watcher's own
         * instead ([DirectoryObserver.stopWatching]). By identity, so a folder renamed while the open ran is handed over
         * under the name the watcher found it by.
         */
        fun handOver(identity: Any?): OpenWatch? = identity?.let { synchronized(this) { watches.remove(it) } }

        /**
         * Stops every watch not handed over: those of directories the watcher does not watch, which no observer of the
         * watcher shares. What was recorded stays to be read.
         */
        fun close() {
            val left = synchronized(this) { watches.values.toList().also { watches.clear() } }
            left.forEach { runCatching { it.stop() } }
        }

        fun recorded(): List<Recorded> = synchronized(this) { events.values.toList() }

        /**
         * Lets go of everything recorded, once the replay has sent it. The watcher holds the recording for the rest
         * of the session, through its write-back thread and through the observers that took its watches over, and
         * nothing reads it again; an event that still reaches it then names a directory it no longer knows, and is
         * not kept.
         */
        fun forget() {
            synchronized(this) {
                directories.clear()
                origins.clear()
                departedWith.clear()
                events.clear()
                lastWrite.clear()
                arrivedFrom.clear()
            }
            for (set in listOf(removedPaths, removedDirectories, placedPaths, placedDirectories, arrivedDirectories,
                leftToReplay, keptByOpen)) set.clear()
        }
    }

    /** What [mirrorDir]'s open recorded, for [startWatching] to send; nothing once taken. */
    internal fun takeChangesDuringOpen(mirrorDir: File): ChangesDuringOpen? = changesDuringOpen.remove(mirrorDir.absolutePath)

    /**
     * Watches one directory and enqueues write-back jobs for what changes inside it.
     *
     * [path] arrives as inotify's own `name` field (the bare entry name within the
     * watched directory, not a path relative to the mirror root), so [dir] is what it
     * has to be resolved against. Covering the tree is [watchTree]'s job: one observer
     * per directory, because a watch descriptor covers a directory and not a tree.
     */
    internal inner class DirectoryObserver(
        val dir: File,
        private val rootDir: File,
        private val safTreeUri: Uri,
        private val owner: WatchSession,
    ) {
        /** Where what the directory reports goes instead of the watcher, once a switch records it ([recordInto]). */
        @Volatile private var recording: ((Int, String?) -> Unit)? = null

        private val watch = directoryWatchFactory(dir) { event, path ->
            if ((event and IN_Q_OVERFLOW) != 0) {
                val into = recording
                if (into != null) into(event, null)
                else if (isWatching) handleOverflow(rootDir, safTreeUri, owner)
                return@directoryWatchFactory
            }
            if (path != null) {
                val into = recording
                if (into != null) into(event, path)
                else if (isWatching) {
                    val localFile = File(dir, path)
                    dispatchObserverEvent(owner, rootDir, safTreeUri) {
                        handleMirrorEvent(event, localFile, rootDir, safTreeUri, owner)
                    }
                }
            }
        }

        /** The open's watch of the directory, which shares this one's inotify watch; see [ChangesDuringOpen.handOver]. */
        var adopted: OpenWatch? = null

        /** Whether a switch has turned this watch to its recording, which then stops it ([recordInto]). */
        val recorded get() = recording != null

        /**
         * Turns this watch to [changes], for a switch to another folder ([stopWatching]): what the directory, of
         * identity [identity], reports from now on is recorded rather than sent, and the watch goes on. Handed to the
         * recording as the directory's watch, which stops it with its own or hands it to the watcher put back
         * ([ChangesDuringOpen.handOver]). Kept referenced there, as here: an observer the collector takes is stopped
         * by its finalizer, which removes the kernel's watch for whoever else shares it.
         */
        fun recordInto(changes: ChangesDuringOpen, identity: Any) {
            changes.watch(dir, identity, object : OpenWatch {
                override fun start() = Unit
                override fun stop() = stopWatching()
            })
            recording = { event, name ->
                if ((event and IN_Q_OVERFLOW) != 0) {
                    changes.markOverflow()
                    Logger.w(tag, "File watcher event queue overflowed while switching from ${rootDir.name}")
                } else if (name != null) {
                    changes.record(event, identity, name, openEventClock())
                }
            }
        }

        fun startWatching() = watch.start()

        fun stopWatching() {
            watch.stop()
            adopted?.let { runCatching { it.stop() } }
            adopted = null
        }
    }

    internal fun writeWouldReplaceUnreadDocument(path: String, deviceHoldsDocument: Boolean): Boolean =
        writeWouldReplaceUnreadDocument(path, deviceHoldsDocument, unfetched, path in fetchedPaths)

    /**
     * Whether any vanished directory is still claimable at [now], without consuming
     * anything.
     *
     * Asked before the provider walk in the claim decision, because it is a lock
     * and a size check while the walk is a binder round trip per path segment on
     * the observer thread, and most directory MOVED_TO events have nothing to
     * pair with: a folder arriving from outside the mirror, a build tool swapping
     * trees. Those used to pay the walk for an answer the cheap check settles.
     *
     * [now] is a parameter rather than a reading taken here so that this and
     * [renameSourceFor] judge one event against one clock. Without the window this
     * answered "yes" for an entry [renameSourceFor] would decline as expired, and
     * because entries are otherwise pruned only when the next directory MOVED_FROM
     * arrives, one departure that never found a partner (`mv dist /sdcard/...`) left
     * the filter answering yes for the rest of the session: every directory a
     * `git checkout` or an `npm install` created then paid the per-segment provider
     * walk on the observer thread, which is the thread that has to keep up with every
     * inotify event for the whole mirror. Pruning here as well as on the add is what
     * stops it: the verdict is unchanged, since [renameSourceFor] applies the same
     * window, and the walk it saves is the point.
     *
     * Pruning through [dropVanished] rather than off the list here, so that a departure
     * expiring at this reader retires the upload records left under it exactly as one
     * expiring at any other does. That reads the journal, one small file in `filesDir`,
     * and only when something did expire, which is a fraction of the binder walk this
     * check exists to keep off the observer thread.
     */
    internal fun hasVanishedCandidate(now: Long): Boolean {
        dropVanished { now - it.atMillis > RENAME_PAIR_WINDOW_MS }
        return synchronized(vanishedLock) { vanishedDirectories.isNotEmpty() }
    }


    // Module delegates (preserve public, internal, and reflection-pinned signatures).

    internal fun retryHeldBack(session: WatchSession): Boolean = retryHeldBackImpl(session)

    @JvmOverloads
    private fun reconcileDeletions(
        mirrorDir: File,
        documents: List<DocumentInfo>,
        enumerationComplete: Boolean,
        recorded: List<String>,
        unplaced: List<String>,
        recordUnreadable: Boolean,
        onWritten: () -> Unit,
        onKept: (List<String>) -> Unit,
        receipts: Collection<String> = emptyList(),
        unlisted: Collection<String> = emptyList(),
    ): Int = reconcileDeletionsImpl(
        mirrorDir, documents, enumerationComplete, recorded, unplaced,
        recordUnreadable, onWritten, onKept, receipts, unlisted,
    )

    @JvmOverloads
    private fun writeSyncedRecord(
        record: File,
        recorded: List<String>,
        sorted: Boolean = false,
        unsorted: Boolean = false,
    ): Boolean = writeSyncedRecordImpl(record, recorded, sorted, unsorted)

    private fun recordDeliveredCopy(
        localFile: File,
        mirrorRoot: File,
        lastModified: Long,
        length: Long,
        sha256: ByteArray,
    ) = recordDeliveredCopyImpl(localFile, mirrorRoot, lastModified, length, sha256)

    internal fun holdsOnlyVouchedCopies(mirrorDir: File): Boolean = holdsOnlyVouchedCopiesImpl(mirrorDir)

    internal fun runWriteBackLoop(session: WatchSession, isRunning: () -> Boolean) =
        runWriteBackLoopImpl(session, isRunning)

    private fun watchTree(dir: File, rootDir: File, safTreeUri: Uri, owner: WatchSession) =
        watchTreeImpl(dir, rootDir, safTreeUri, owner)

    private fun unwatchTree(dir: File, owner: WatchSession) = unwatchTreeImpl(dir, owner)

    private fun walkTree(
        treeUri: Uri,
        parentDocId: String,
        parentRelPath: String,
        result: MutableList<DocumentInfo>,
        skipped: MutableList<String>,
        ancestors: Set<String> = emptySet(),
    ): Boolean = walkTreeImpl(treeUri, parentDocId, parentRelPath, result, skipped, ancestors)

    internal fun parentIsCurrent(dest: OutboundPath, file: File, mirrorRoot: File): Boolean =
        parentIsCurrentImpl(dest, file, mirrorRoot)

    private fun writeLocalToSaf(
        localFile: File,
        safDocUri: Uri,
        mirrorRoot: File,
        mutation: ProviderMutation,
    ): Boolean = writeLocalToSafImpl(localFile, safDocUri, mirrorRoot, mutation)

    internal fun openOutboundPath(localFile: File, mirrorRoot: File): OutboundPath =
        openOutboundPathImpl(localFile, mirrorRoot)

    private fun markUploadInFlight(absolutePath: String): UploadClaim? = markUploadInFlightImpl(absolutePath)

    private fun recordUploadIntent(absolutePath: String): Boolean? = recordUploadIntentImpl(absolutePath)

    @JvmOverloads
    private fun releaseUploadClaim(claim: UploadClaim, wroteNothing: Boolean = false) =
        releaseUploadClaimImpl(claim, wroteNothing)

    private fun consumeStaleUploadRecord(absolutePath: String) = consumeStaleUploadRecordImpl(absolutePath)

    internal fun renameUploadsUnder(from: File, to: File) = renameUploadsUnderImpl(from, to)

    internal fun clearUploadsUnder(mirrorRoot: File) = clearUploadsUnderImpl(mirrorRoot)

    internal fun uploadsInFlight(): Set<String> = uploadsInFlightImpl()

    internal fun partlyWrittenUploads(): Set<String> = partlyWrittenUploadsImpl()

    private fun createInSaf(
        localFile: File,
        parentSafUri: Uri,
        treeUri: Uri,
        mirrorRoot: File,
        access: OutboundAccess? = null,
        mutation: ProviderMutation? = null,
    ) = createInSafImpl(localFile, parentSafUri, treeUri, mirrorRoot, access, mutation)

    private fun mirrorNamedAsStored(
        docUri: Uri,
        localFile: File,
        stored: String?,
    ): File = mirrorNamedAsStoredImpl(docUri, localFile, stored, mirrorRoot = null)

    private fun deleteFromSaf(safDocUri: Uri): Boolean = deleteFromSafImpl(safDocUri)

    private fun processWriteBack(session: WatchSession, queued: SyncJob) = processWriteBackImpl(session, queued)

    private fun watchChangesDuringOpen(mirrorDir: File): ChangesDuringOpen = watchChangesDuringOpenImpl(mirrorDir)

    internal fun replayChangesDuringOpen(
        changes: ChangesDuringOpen,
        mirrorDir: File,
        safUri: Uri,
        owner: WatchSession = session,
    ) = replayChangesDuringOpenImpl(changes, mirrorDir, safUri, owner)

    internal fun handleMirrorEvent(
        event: Int,
        localFile: File,
        rootDir: File,
        safTreeUri: Uri,
        sourceSession: WatchSession = session,
        departedKey: Any? = null,
        replayed: Boolean = false,
        recordedAt: Long? = null,
    ) = handleMirrorEventImpl(event, localFile, rootDir, safTreeUri, sourceSession, departedKey, replayed, recordedAt)

    private fun providerHolds(treeUri: Uri, relativePath: String): Boolean? = providerHoldsImpl(treeUri, relativePath)

    private fun providerHoldsDocument(treeUri: Uri, relativePath: String): Boolean =
        providerHoldsDocumentImpl(treeUri, relativePath)

    private fun resolveDocumentUri(treeUri: Uri, relativePath: String, access: OutboundAccess? = null): Uri? =
        resolveDocumentUriImpl(treeUri, relativePath, access)

    companion object {
        internal const val MAX_ENUMERATION_DEPTH = 128
        /**
         * What [usableSpaceOf] starts as in every engine: the room the filesystem reports.
         *
         * A variable so the unit suite can stand it down for the whole JVM. The floor in
         * [copyDocumentToLocal] refuses every fetch below [OPEN_SPACE_FLOOR_BYTES], and a test
         * that leaves this alone is asking the machine it runs on: with the temp volume that
         * low, the document is never copied and the save or delete for it is refused as
         * unread, which no test about those rules expects to be decided by a disk.
         */
        @SuppressLint("UsableSpace")
        internal var defaultUsableSpaceOf: (File) -> Long = { it.usableSpace }

        internal const val UPLOAD_JOURNAL_HEADER = "#vscodroid-saf-uploads 1"

        /** What a journal line of a write that never handed the provider a stream is marked with; see [markUnwritten]. */
        internal const val UNWRITTEN_MARK = "unwritten\t"
        internal const val COPY_BUFFER_SIZE = 8192
        internal const val WRITEBACK_POLL_MS = 200L

        /**
         * The type every file is created on the device with, and the file's NAME is the
         * reason it is this one.
         *
         * `createDocument` takes a name and a type, and the platform provider settles a
         * disagreement between them by rewriting the name. Measured on an API 33
         * emulator's own `framework.jar` rather than read from a source tree:
         * `FileSystemProvider.createDocument` (classes4.dex) calls
         * `FileUtils.buildValidFatFilename` and then `FileUtils.buildUniqueFile(parent,
         * mimeType, displayName)`, whose `splitFileName` (classes2.dex, source lines
         * 1216-1217) keeps the requested extension only while the type is the one the
         * platform's own table gives that extension, or the extension is the one it gives
         * that type, and otherwise sets `name = displayName; ext = extFromMimeType`.
         *
         * A table of guesses therefore decided what the user's file was called. `.kt`,
         * `.java` and `.md` were all claimed to be `text/plain`, whose extension is `txt`
         * (`res/android.mime.types:85`), while those tables give `md` to `text/markdown`,
         * `java` to `text/x-java` and have no `kt` entry at all, so both tests failed and
         * `Main.kt` was stored on the device as `Main.kt.txt`. Nothing noticed: the name
         * was never read back, and every reopen enumerated the mangled name into the
         * mirror beside the original and made one more.
         *
         * This is the one value that cannot do that, whatever the extension. The same
         * method maps `ContentResolver.MIME_TYPE_DEFAULT` to a null `extFromMimeType`
         * (0x0033-0x003b in the same disassembly), so a mismatch takes `name =
         * displayName` with nothing to append, and `buildFile` appends no dot for an empty
         * extension. It costs nothing where the type is read back either: the same
         * provider reports `COLUMN_MIME_TYPE` from the file's own extension
         * (`getDocumentType`, classes4.dex), not from what a create asked for.
         */
        internal const val CREATED_FILE_MIME_TYPE = "application/octet-stream"

        /** How long [stopWatching] waits for queued writes to reach the device. */
        internal const val DRAIN_GRACE_MS = 2000L

        /** Default bound on queued live observer callbacks per session before escalating to overflow. */
        internal const val DEFAULT_OBSERVER_QUEUE_CAPACITY = 1024

        /** Default wait in [stopWatching] for already-observed callbacks to enqueue their write-back jobs. */
        internal const val OBSERVER_DRAIN_GRACE_MS = 1000L

        /**
         * How long a save [keepsDeviceEdit], [writeLocalToSaf], or an open arm holds back
         * waits before [retryHeldBack] tries it again. Each hold after that doubles the wait,
         * up to [HELD_BACK_RETRY_MAX_MS].
         */
        internal const val HELD_BACK_RETRY_FIRST_MS = 30_000L

        /** The longest a held-back save waits between tries. */
        internal const val HELD_BACK_RETRY_MAX_MS = 5 * 60_000L

        /**
         * Suffix for a copy still being written; moved into place when complete.
         *
         * Internal because the reclaim pass has to recognise the one of these that
         * outlives its own writer: [writeSyncedRecord] puts a record's scratch file
         * beside the mirror rather than inside it, where nothing else in `saf-mirrors`
         * would ever look at it again.
         */
        internal const val PARTIAL_SUFFIX = ".vscodroid-partial"

        /** The default for [outboundTurnWaitMs]. */
        internal const val OUTBOUND_TURN_WAIT_MS = 120_000L

        /**
         * The default for [providerClaimWaitMs]: ten seconds.
         *
         * Far below [OUTBOUND_TURN_WAIT_MS] on purpose: the longer wait is the one that gives up on
         * a stalled provider, and this one must not be the reason it does. A save, a rename, a
         * delete and a create all take the claim before their mirror's turn, so nobody waits for
         * it holding a turn that the claim's holder waits for. An open's own creates claim while
         * the open holds the mirror, which cannot close a cycle: a write that meets a mirror being
         * opened is refused at once instead of waiting for it.
         */
        internal const val PROVIDER_CLAIM_WAIT_MS = 10_000L

        /**
         * What [setAsideDivergedMirror] puts in front of the mirror copy's own
         * modification time when it moves one out of a newer device copy's way.
         *
         * Deliberately not a [PARTIAL_SUFFIX]-shaped name. A machine-temporary name is
         * excluded from every outbound path in this engine, and the whole point of the
         * file it names here is that it holds work only this app has: it has to be
         * uploadable, watchable and visible in the explorer like any other file.
         */
        internal const val LOCAL_COPY_SUFFIX = ".local-"

        /**
         * The name a copy taken for deletion waits under while its bytes are proved, then is removed.
         *
         * A machine-temporary name, like the other scratch files of this engine: a process that dies
         * between the move and the removal leaves the copy of a document the user deleted on the device,
         * and under an ordinary name the next open took it for work only this app has and created it on
         * the device again. A copy that proves to be an edit is moved to its own `<name>.local-<time>`
         * from here, which is the name that is uploaded.
         */
        internal fun removalCaptureName(): String = "removed-${UUID.randomUUID()}$PARTIAL_SUFFIX"

        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

        /** [removalCaptureName]'s shape, and `removed.local-<uuid>`, which builds up to 1.9.0 used. */
        private val REMOVAL_CAPTURE_NAME = Regex(
            "removed-$UUID_PATTERN${Regex.escape(PARTIAL_SUFFIX)}|removed${Regex.escape(LOCAL_COPY_SUFFIX)}$UUID_PATTERN",
        )

        internal fun isRemovalCaptureName(name: String): Boolean = REMOVAL_CAPTURE_NAME.matches(name)

        /**
         * `<name>.local-<time>`, the name a mirror copy is kept under beside [name]. A name too
         * long to take the suffix within the limit, which is in bytes, is cut at a character
         * first: the full name failed the rename, so a long name could not be set aside at all
         * and a deletion kept its copy under a name that said nothing.
         *
         * A caller moves its copy there with [moveToFreeName], which refuses a name that is taken,
         * and goes on to the next time while it is refused. [removeRecordedCopy] puts a copy it
         * took for deletion back under the copy's own name the same way.
         */
        internal fun localCopyName(name: String, time: Long): String = cutToFit(name, "$LOCAL_COPY_SUFFIX$time")

        /**
         * `<name>.device-<time>`, cut to fit as [localCopyName] is: with the whole name a long
         * file's device copy could not be made, so its newer mirror copy was refused at every
         * open and every save.
         */
        internal fun deviceCopyName(name: String, time: Long): String = cutToFit(name, "$DEVICE_COPY_SUFFIX$time")

        private fun cutToFit(name: String, suffix: String): String {
            var stem = name
            while (stem.isNotEmpty() &&
                (stem + suffix).toByteArray(Charsets.UTF_8).size > SafStorageManager.MAX_NAME_BYTES
            ) {
                stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
            }
            return stem + suffix
        }

        /**
         * What [setAsideDeviceCopy] puts in front of the device document's own
         * modification time when it fetches one out of a newer mirror copy's way.
         *
         * A different word from [LOCAL_COPY_SUFFIX] because the two copies come from
         * opposite sides, and which side an edit came from is the one thing the user
         * needs to know when merging the two. The same rule about the name's shape
         * applies: it has to be uploadable, watchable and visible like any other file.
         */
        internal const val DEVICE_COPY_SUFFIX = ".device-"

        /**
         * The file, inside [android.content.Context.getFilesDir], naming every mirror
         * path with an upload in flight. See the journal's users for why it exists.
         */
        internal const val UPLOADS_IN_FLIGHT_FILE = "saf-uploads-in-flight"

        /**
         * Serialises every access to [UPLOADS_IN_FLIGHT_FILE], across engine
         * instances: a write-back drain can outlive the activity whose engine
         * started it, and the next activity's engine reads and rewrites the same
         * one file on its own monitor otherwise.
         */
        internal val uploadJournalLock = Any()

        /**
         * The write-backs streaming into a mirror path right now, one entry per writer,
         * across engine instances and guarded by [uploadJournalLock].
         *
         * The journal line means "an upload of this path did not finish", and one line
         * cannot carry two writers: [initialSync] writes a newer mirror copy back on the
         * IO dispatcher while a drain that outlived its folder is still streaming the
         * same file, and whichever landed first used to remove the line for both. The
         * next sync then read a device copy another writer was still part way through as
         * a finished one, and copied it over the mirror. Reopening the same folder is
         * what makes the two paths identical, because the mirror is named by a hash of
         * the folder rather than by the session.
         *
         * A claim per writer rather than a count per path, because the two records of one
         * write have to move together. [renameUploadsUnder] moves the journal line when
         * the directory above it is renamed mid-stream, and a count keyed by the name the
         * writer opened with could no longer name the line that writer has to retire.
         * Each writer releases its own [UploadClaim], whatever its path is called by then.
         *
         * In memory rather than in the file, because the writers are threads of one
         * process. A process that dies mid-write leaves the line behind, which is exactly
         * what the journal is for.
         */
        internal val uploadClaims = mutableListOf<UploadClaim>()

        /**
         * Active document reads and truncating writers, shared across engine instances.
         * Tree grants can spell one document differently, so identity is authority plus
         * document ID. A reader retains the write generation until its stream closes,
         * rejecting bytes touched by a write even when that write has already finished.
         * Entries disappear once no read or write uses them. A contending writer holds
         * its save for [retryHeldBack] without waiting indefinitely.
         */
        internal val documentAccess = mutableMapOf<Pair<String?, String>, DocumentAccess>()

        // Recursive provider deletes include child IDs that overlapping grants spell differently.
        internal val providerMutations = mutableMapOf<String?, ProviderMutation>()

        internal fun providerMutationKey(uri: Uri): String? = uri.authority?.takeIf { it.isNotEmpty() }

        @JvmName("claimProviderMutation")
        internal fun claimProviderMutation(uri: Uri): ProviderMutation? = synchronized(providerMutations) {
            val key = providerMutationKey(uri)
            if (providerMutations.containsKey(key)) null else ProviderMutation(key).also {
                providerMutations[key] = it
            }
        }

        /**
         * [claimProviderMutation], waiting up to [waitMs] for the claim to come free.
         *
         * For a delete, a create and a rename, which were refused with a notice and had to be
         * repeated when they met another folder's write to the same provider, so that a closing
         * write still running as the next folder opens cost the user a refusal. A save does not
         * use it: it is held back and tried again on its own. Woken by
         * [releaseProviderMutation], so a write that ends in a second costs the waiter a second.
         *
         * Bounded, and once per holder. The first waiter to run out of time marks the holder
         * [ProviderMutation.waitedOut], and everyone after it is refused at once for as long as
         * that holder has the claim, so a provider call that never returns costs one wait and not
         * one per queued delete, which is what the drain of a removed tree would otherwise be. A
         * refusal is what these paths did before this existed, so a wait that runs out changes
         * nothing about what happens next.
         *
         * An interrupt ends the wait as a refusal and is handed back: the thread is a write-back
         * drain, and an interrupt is it being told to stop.
         */
        @JvmName("claimProviderMutationWithin")
        internal fun claimProviderMutationWithin(uri: Uri, waitMs: Long): ProviderMutation? {
            val key = providerMutationKey(uri)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
            var waitedFor: ProviderMutation? = null
            return synchronized(providerMutations) {
                var claim: ProviderMutation? = null
                while (true) {
                    val holder = providerMutations[key]
                    if (holder == null) {
                        claim = ProviderMutation(key).also { providerMutations[key] = it }
                        break
                    }
                    val left = deadline - System.nanoTime()
                    if (holder.waitedOut || left <= 0) {
                        if (holder === waitedFor) holder.waitedOut = true
                        break
                    }
                    if (waitedFor == null) waitedFor = holder
                    try {
                        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                        (providerMutations as Object).wait(TimeUnit.NANOSECONDS.toMillis(left).coerceAtLeast(1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                claim
            }
        }

        internal fun ownsProviderMutation(uri: Uri, mutation: ProviderMutation): Boolean =
            synchronized(providerMutations) {
                mutation.key == providerMutationKey(uri) && providerMutations[mutation.key] === mutation
            }

        @JvmName("releaseProviderMutation")
        internal fun releaseProviderMutation(mutation: ProviderMutation) {
            synchronized(providerMutations) {
                if (providerMutations[mutation.key] === mutation) {
                    providerMutations.remove(mutation.key)
                    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    (providerMutations as Object).notifyAll()
                }
            }
        }

        internal fun documentWriteKey(safDocUri: Uri): Pair<String?, String> {
            val authority = safDocUri.authority?.takeIf { it.isNotEmpty() }
            val docId = authority?.let {
                runCatching { DocumentsContract.getDocumentId(safDocUri) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            }
            return if (authority != null && docId != null) {
                authority to docId
            } else {
                null to safDocUri.toString()
            }
        }

        internal fun anotherMirrorWritesDocument(safDocUri: Uri, mirrorRoot: File): Boolean =
            synchronized(documentAccess) {
                documentAccess[documentWriteKey(safDocUri)]?.writer
                    ?.let { it != mirrorRoot.absolutePath } == true
            }

        /** Reads outside a writer claim, retaining its version until the stream closes. */
        internal fun <T> withStableDocumentRead(docUri: Uri, read: () -> T): T {
            val document = documentWriteKey(docUri)
            val (state, generation) = synchronized(documentAccess) {
                val current = documentAccess.getOrPut(document) { DocumentAccess() }
                if (current.writer != null) {
                    throw DocumentWriteDuringReadException()
                }
                current.readers++
                current to current.generation
            }
            try {
                val result = try {
                    read()
                } catch (failure: Exception) {
                    synchronized(documentAccess) {
                        if (state.generation != generation) {
                            throw DocumentWriteDuringReadException().apply { addSuppressed(failure) }
                        }
                    }
                    throw failure
                }
                synchronized(documentAccess) {
                    // A writer may finish entirely during the read, leaving no active stream.
                    if (state.generation != generation) throw DocumentWriteDuringReadException()
                }
                return result
            } finally {
                synchronized(documentAccess) {
                    state.readers--
                    if (state.readers == 0 && state.writer == null) documentAccess.remove(document)
                }
            }
        }

        /**
         * Mirror files a sync is streaming a device document into right now, keyed by
         * absolute mirror path.
         *
         * The inbound twin of [documentAccess], in this object for the reason
         * that one is: two syncs over one mirror are routinely in two different engines.
         * A mirror is named by a hash of the tree URI rather than by the session, and
         * [initialSync]'s phase-2 loop contains no suspension point, so cancelling the
         * coroutine an activity started does not stop it: an activity destroyed mid-open
         * leaves one sync copying while its replacement opens the same folder and starts
         * another.
         *
         * Both used to write the same scratch path, `<name>.vscodroid-partial`, so one
         * writer's rename into place carried the other writer's open stream with it and
         * the rest of that copy landed inside the finished mirror file, which
         * [recordIdentity] had already vouched for by then. Scratch names are unique per
         * copy now; what the loser still has to be kept from is choosing the same
         * preserved-copy name. The vouched identity is what
         * [reconcileDeletions] and [holdsOnlyVouchedCopies] later act on, so a corrupted
         * file was also a deletable one.
         *
         * The loser declines rather than waits, exactly as [documentAccess]'s
         * loser does and for the same measured reason: a `ContentResolver` stream to a
         * network or MTP provider has no timeout, so waiting would trade a race for an
         * unbounded stall of a folder open behind a dialog with `setCancelable(false)`.
         * What the loser gives up is one copy of a document the winner is already
         * copying, and it vouches for nothing at that path, which is the direction
         * that costs disk rather than work. It does record the path as one it did
         * not read: this set is shared between the two engines and `unfetched` is
         * not, so the loser is the only thing that can arm its own write-back
         * guard, and the loser is routinely the engine that outlives the winner.
         */
        internal val mirrorCopiesInFlight = ConcurrentHashMap.newKeySet<String>()
        internal val inboundScratchPaths = mutableSetOf<InboundScratchKey>()
        internal val mirrorAccess = ConcurrentHashMap<String, MirrorAccess>()

        /**
         * Suffix of the sibling file recording copies known to have reached the device.
         *
         * A sibling and not a child: the mirror directory is the folder VS Code opens,
         * and a bookkeeping file inside it would show up in the explorer and in search.
         * Being a sibling is also why whatever removes a mirror has to remove this too,
         * which is what [SafStorageManager] uses it for.
         */
        internal const val SYNCED_RECORD_SUFFIX = ".synced"

        /**
         * The pause between two copies of a save [keepHeldSave] keeps. An editor writes a save in one
         * go, so a save still under way when the copy is made has written the rest well within this.
         */
        internal const val HELD_SAVE_SETTLE_MS = 50L

        /**
         * The longest [keepHeldSave] goes on copying a save that keeps changing. It runs inside the
         * mirror's turn, behind the dialog of an open nobody can cancel, so a writer that does not
         * stop (a log, a download) is kept as it stands after this.
         */
        internal const val HELD_SAVE_WAIT_MS = 1_000L

        /**
         * The most [keepHeldSave] copies of one save in all, so a large file costs a few reads of
         * itself at most; the copy made when this is reached stands, and a file this large is kept
         * as its first copy found it, as every kept save was before the copy was made again.
         */
        internal const val HELD_SAVE_COPY_BYTES = 256L * 1024 * 1024

        /**
         * The mark beside a mirror's record that an open of this build sorted the files only the
         * mirror holds against it; see [writeSyncedRecord]. Removed with the record
         * (`SafStorageManager.reclaimRevokedMirrorsSync`, `SafStorageManager.reclaimMirror`).
         */
        internal const val SORTED_RECORD_SUFFIX = ".sorted"

        /**
         * First line of the record, and the only thing that says which format follows.
         *
         * Field count is not a version. A later format that also has three fields with
         * different meanings would be read as this one and acted on, and acting means
         * deleting. A record whose first line is not exactly this is ignored, which is
         * how a record from the build before headers is already treated, and in the same
         * direction: unverifiable means keep.
         *
         * Not bumped for the digest a line may carry as a fourth field ([recordLine]), and
         * that is the safer choice for a build before it, which then reads such a line,
         * wherever it needs the whole identity, as one it cannot verify, and keeps: its
         * [reconcileDeletions] skips a line that is not three fields, and its readers that
         * compare whole lines find no match. A bumped header would have that build discard
         * the whole record instead, and with no record [setAsideDivergedMirror] and
         * [deviceChangedSinceRecord] answer false, the direction that loses an edit. A record
         * from before the digests reads here as it always did.
         */
        internal const val RECORD_HEADER = "#vscodroid-saf-sync 2"

        /**
         * A relative path as the record spells it: the tab, the two line breaks and the
         * backslash each behind a backslash, so a name holding any of them stays one
         * field of one line.
         *
         * The header is not bumped for this, and that is by measurement rather than
         * oversight. A record the previous build wrote holds none of the four: the walk
         * refuses a display name with a backslash before it is composed into a path
         * ([isSafeSegment]), and [recordIdentity] left a name with the other three out
         * of the record altogether. So unescaping an older record is the identity, and
         * an older build reading a newer one misreads only the lines it could never have
         * written, as paths no file has, which every reader treats as "keep".
         */
        internal fun escapeRecordPath(path: String): String =
            path.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

        /**
         * A record line: [identity], as [identityLine] spells it, with the SHA-256 of the copy
         * it names after it where [digest] is not null, in base64, whose alphabet holds no tab
         * or line break.
         */
        internal fun recordLine(identity: String, digest: ByteArray?): String =
            if (digest == null) identity
            else identity + '\t' + Base64.getEncoder().encodeToString(digest)

        /**
         * [line] taken apart into the identity [identityLine] spells and the digest
         * [recordLine] put after it. A line with no digest, or one this build cannot read,
         * comes back whole and with none, so each reader keeps or drops it as it always has.
         */
        internal fun splitRecordLine(line: String): Pair<String, ByteArray?> {
            val fields = line.split('\t')
            val digest = if (fields.size != 4) null else try {
                // Thirty-two bytes, a SHA-256; anything else is not a digest this build wrote.
                Base64.getDecoder().decode(fields[3]).takeIf { it.size == 32 }
            } catch (e: IllegalArgumentException) {
                null
            }
            return if (digest == null) line to null else line.substringBeforeLast('\t') to digest
        }

        /** The inverse of [escapeRecordPath]. A stray backslash is kept as it is. */
        internal fun unescapeRecordPath(field: String): String {
            if ('\\' !in field) return field
            val out = StringBuilder(field.length)
            var i = 0
            while (i < field.length) {
                val c = field[i]
                if (c == '\\' && i + 1 < field.length) {
                    when (field[i + 1]) {
                        '\\' -> { out.append('\\'); i += 2; continue }
                        't' -> { out.append('\t'); i += 2; continue }
                        'n' -> { out.append('\n'); i += 2; continue }
                        'r' -> { out.append('\r'); i += 2; continue }
                    }
                }
                out.append(c)
                i++
            }
            return out.toString()
        }

        /**
         * inotify's flag for "the entry this event is about is a directory".
         *
         * [FileObserver] does not expose it, but it passes the kernel's mask through
         * untouched, which is also why the existing `event and ALL_EVENTS` is needed
         * to read the event type at all.
         */
        internal const val IN_ISDIR = 0x40000000

        /**
         * inotify's flag for "the kernel event queue overflowed and one or more events were dropped".
         *
         * Delivered with a null path and outside [FileObserver.ALL_EVENTS], so observers must check
         * for it before filtering on the path or masking the event type.
         */
        internal const val IN_Q_OVERFLOW = 0x4000

        /**
         * How long a directory that left the mirror stays claimable as a rename source.
         *
         * A rename(2) emits MOVED_FROM and MOVED_TO back to back and `FileObserver`
         * delivers them from one thread in that order, so the true pair is milliseconds
         * apart; this is slack for a busy observer thread, not a guess at how long a user
         * might take. Widening it does not make pairing more likely to succeed, it makes
         * a mis-pair more likely, because where one half carries no identity it is the only
         * thing bounding how far apart two *unrelated* moves can be and still be joined.
         * It holds for a pair whose identities agree too.
         */
        internal const val RENAME_PAIR_WINDOW_MS = 1_000L

        /**
         * Which vanished directory a MOVED_TO on [newRelativePath] is the other half of,
         * or null when the question cannot be answered.
         *
         * inotify's cookie is what would answer it; `FileObserver` does not expose it. The
         * directory itself does where both halves carry its identity: [arrivingKey] is the
         * arriving directory's `fileKey`, read at its MOVED_TO, and
         * [VanishedDirectory.directoryKey] the departed one's, recorded when its watch was
         * placed, since a directory that has left cannot be read. A rename keeps the inode,
         * so the departure whose key equals the arrival's is the source, and one whose key
         * differs from a known arrival's never is: a folder moved out of the mirror is not
         * joined to an unrelated one arriving within the window, the case that used to put
         * the departed folder's subtree under the arriving one's name.
         *
         * A half without one (a directory past [MAX_WATCHED_DIRECTORIES], an arrival that
         * moved on before its event was handled) is judged by two rules, and those are a
         * heuristic that declines rather than guesses. **When it is wrong:** one directory
         * leaves the mirror for good (`mv dir ~/elsewhere`, a build tool swapping trees)
         * and, within [RENAME_PAIR_WINDOW_MS], an unrelated directory arrives anywhere in
         * the mirror. The two are then joined, and the device ends up with the departed
         * directory's subtree under the arriving one's name while the arriving one's own
         * contents stay in the mirror. Reopening the folder brings the misplaced subtree
         * back down where it can be seen and moved; the paragraph below the two rules has
         * the part that can reach bytes.
         *
         * - **Exactly one candidate** among those no identity rules out. Two directories in
         *   flight at once cannot be told apart by arrival order alone once their events
         *   interleave, and pairing the wrong two would put each subtree under the other's name.
         * - **Recent.** See [RENAME_PAIR_WINDOW_MS]. Two halves an open recorded are judged by when each was seen,
         *   [VanishedDirectory.recordedAt] and [arrivingAt], as well: the watcher replays a whole open in moments,
         *   and judged by that alone, a departure was joined to an arrival minutes later that carried its identity,
         *   which the kernel gives the next folder made once a deleted folder's inode is free.
         *
         * A third rule required the two halves to share a parent, and it was dropped once
         * the write-back learned `moveDocument`. It was never about pairing being less
         * certain across parents; it was that `renameDocument` cannot express a move, so a
         * pair the engine could not act on was better left unclaimed. Dragging `src/util`
         * into `src/legacy/` is an ordinary thing to do and used to leave the old copy on
         * the device, reappearing beside the new one on every reopen. Removing the rule
         * does widen the mis-pair window: an unrelated arrival anywhere in the mirror can
         * now be joined to a departure, where before it had to arrive in the same
         * directory. Identity and the two surviving rules are what bound it.
         *
         * **What a mis-pair costs, stated exactly, because "nothing is deleted" is not
         * the whole of it.** Moving the documents is placement and not bytes: [moveInSaf]
         * relocates the document rather than removing it, and reopening the folder brings
         * the misplaced subtree back down. Two paths do reach bytes. The rename's job sends
         * the saves queued or dropped for the departed directory at the arriving one's
         * paths, from whatever the mirror holds there: where the arriving directory has a
         * file of the same name, its bytes go over the device's copy of the departed one's,
         * an older version of a file the person moved away or deleted. And a claim also calls
         * [renameUploadsUnder], which re-files the departed directory's upload records
         * under paths beneath the ARRIVING directory; where a real mirror file sits at
         * one of those paths, the next [initialSync] reads it as this app's own
         * interrupted upload, keeps it, and writes it over the device's document, so an
         * edit made on the device to that file is gone.
         *
         * Not closed by narrowing the pairing, and that is a choice rather than an
         * oversight. The rule that would close most of it is that a real directory move
         * keeps either its parent or its basename, so a candidate would be claimed only
         * when `parentPathOf(from) == parentPathOf(to) || File(from).name ==
         * File(to).name`. That declines the cross-mirror coincidence; it also declines
         * `mv src/util src/legacy/helpers`, which is a move a person makes and which
         * `SafRenamePairingTest` pins as claimable. And it would still not decline the
         * coincidence that lands in the departed directory's own parent, which is where
         * the window sat before the third rule went. So where identity cannot answer, the
         * bound stays the two rules above, and this is written down so the byte-losing
         * paths are read alongside them rather than found later.
         *
         * Declining costs exactly what the code did before there was any pairing: the
         * device keeps the copy under the old name.
         */
        internal fun renameSourceFor(
            vanished: List<VanishedDirectory>,
            newRelativePath: String,
            now: Long,
            arrivingKey: Any? = null,
            arrivingAt: Long? = null,
        ): String? {
            val recent = vanished.filter {
                now - it.atMillis <= RENAME_PAIR_WINDOW_MS &&
                    (arrivingAt == null || it.recordedAt == null || arrivingAt - it.recordedAt <= RENAME_PAIR_WINDOW_MS)
            }
            if (arrivingKey != null) recent.singleOrNull { it.directoryKey == arrivingKey }?.let { return it.relativePath }
            return recent.singleOrNull { it.directoryKey == null || arrivingKey == null }?.relativePath
        }

        /**
         * Whether [later], queued after [job] for the same path, makes running [job]
         * pointless.
         *
         * A save is made from the mirror as it is when it runs, so any later job for the
         * path redoes it and the earlier one can go. A rename goes only for a later rename
         * onto the same name: nothing else puts the move on the device, but two cannot
         * both land, and the second would be asked to rename into a name the device holds
         * by then, which providers answer with an invented one. Where a rename queued
         * between the two takes the first one's result away from that name, the two do
         * not meet, and [processWriteBack] keeps the first. A delete goes for a later
         * delete and for a later write of the same kind, and for no other. A file removed and written again is
         * overwritten in place, and the save queued behind a checkout's unlink holds the
         * document the delete would have removed, so the delete has to be the one that is
         * dropped. A file replaced by a folder, or a folder by a file, is not the document
         * the later write would find, and dropping the delete left the device with the
         * old kind.
         */
        internal fun supersededByLaterJob(job: SyncJob, later: SyncJob): Boolean = when (job.type) {
            SyncType.CREATE, SyncType.MODIFY -> true
            SyncType.RENAME -> later.type == SyncType.RENAME
            SyncType.DELETE -> later.type == SyncType.DELETE ||
                ((later.type == SyncType.CREATE || later.type == SyncType.MODIFY) &&
                    later.isDirectory == job.isDirectory)
        }

        /** The directory part of a mirror-relative path; empty for a top-level entry. */
        internal fun parentPathOf(relativePath: String): String =
            File(relativePath).parent ?: ""

        /**
         * Ceiling on watched directories.
         *
         * Each watch is a kernel inotify descriptor drawn from a per-uid budget
         * (`fs.inotify.max_user_watches`, commonly 8192), and this process is not the
         * only claimant: the file watcher the VS Code server runs draws on the same
         * pool. [SKIP_DIRECTORIES] already excludes what generates most of the count,
         * so a real project tree lands in the low hundreds; this is the backstop for
         * the folder that does not. Past it, deeper directories go unwatched and their
         * changes stay in the mirror, which is the behaviour the whole engine had
         * before and is better than starving the editor's own watcher.
         */
        internal const val MAX_WATCHED_DIRECTORIES = 2048

        /** Max file size to sync (50 MB). Larger files are skipped. */
        internal const val MAX_FILE_SIZE = 50L * 1024 * 1024

        /**
         * The length [deviceMovedPast] gives a stamp the provider did not answer, which no
         * answer carries: [walkTree] and [deviceStamp] read a missing size column, and a
         * negative size, as 0. [setAsideDeviceCopy] stops its fetch at [MAX_FILE_SIZE] for it.
         */
        internal const val UNANSWERED_SIZE = -1L

        /**
         * Free space below which a fetch into the mirror is not attempted: the app's own
         * critically-low line plus one [MAX_FILE_SIZE] fetch, because the check runs
         * before a fetch and one fetch can spend that much. Read live from the disk, so
         * it needs no slack for block rounding the way a predicted total does.
         *
         * The [MAX_FILE_SIZE] half holds only for documents whose provider reports a
         * size. One reported as 0 passes the size gate unmeasured, and a single such
         * fetch can still run from the floor to a full disk.
         */
        internal const val OPEN_SPACE_FLOOR_BYTES = StorageManager.LOW_STORAGE_BYTES + MAX_FILE_SIZE

        /**
         * How many bytes of the copies an open kept rather than fetched, and that the record
         * holds no digest for, it reads to digest them, newest first; see [digestKeptCopies].
         * A copy read once is not read again while its identity stands, so this bounds the
         * opens after an update from a build that recorded none: a typical source tree fits
         * in the first, and a folder of photos or data is read over several rather than in
         * full by one.
         *
         * 64 MB, decimal like every size a user reads ([StorageManager.formatSize]), because
         * the user guide and the changelog give the figure.
         */
        internal const val KEPT_COPY_DIGEST_BYTES = 64_000_000L

        /**
         * How many entries one directory-create is allowed to copy to the device.
         *
         * A rename normally moves a handful of files, but nothing stops it moving a
         * whole project, and every entry here is a provider round trip on the main
         * sync path. The cap bounds that; reaching it is logged, because past it the
         * copy on the device is genuinely incomplete and nothing else would say so.
         */
        internal const val MAX_UPLOAD_ENTRIES = 2000

        /**
         * How many bytes phase 2 would have to fetch for [documents] into [mirrorDir].
         *
         * Counts only what a copy would actually spend: a directory costs nothing, a
         * document past [MAX_FILE_SIZE] is skipped rather than copied, and one the mirror
         * already holds at the same length and no older is kept rather than fetched.
         * Without those two exclusions, reopening a folder of large files reads as
         * needing the whole folder again.
         *
         * A length the provider does not report arrives as 0 and is counted as 0, so a
         * provider omitting `COLUMN_SIZE` makes the whole sync look free rather than made
         * to fit a number nobody measured. That is the direction to fail in: the caller
         * chooses the wording of a notice with this and refuses nothing, so a prediction
         * that cannot be made must not become a claim.
         */
        internal fun bytesToFetch(documents: List<DocumentInfo>, mirrorDir: File): Long =
            documents.asSequence()
                .filterNot { it.isDirectory }
                .filter { it.size in 1..MAX_FILE_SIZE }
                .filterNot { doc ->
                    val mirrored = File(mirrorDir, doc.relativePath)
                    mirrored.isFile && mirrored.length() == doc.size &&
                        mirrored.lastModified() >= doc.lastModified
                }
                .sumOf { it.size }

        /**
         * Whether writing [localPath] back would replace a device document this sync
         * never read.
         *
         * Extracted because three paths ask it and one of them cannot be reached from a JVM
         * test. [handleMirrorEvent] asks before queueing a job, and [processWriteBack] again
         * just before it writes a save; [createOneInSaf] asks while walking into a
         * directory, which no event-driven test can drive here at all, since delivering a
         * directory event constructs a `FileObserver` and that runs a static initializer
         * reaching native code (see [SafWatchCoverageTest]). Naming the rule once is what
         * lets it be asserted for that path at all, and what stops the call sites drifting
         * apart, which is how the directory walk came to be missing it.
         *
         * [deviceHoldsDocument] is the provider's answer, not a guess: the set alone is a
         * memory of what this sync could not read, and the document may have been deleted
         * on the device since, in which case the local file is an ordinary new file.
         */
        internal fun writeWouldReplaceUnreadDocument(
            localPath: String,
            deviceHoldsDocument: Boolean,
            unfetched: Set<String>,
            fetched: Boolean = false,
        ): Boolean = deviceHoldsDocument && unfetched.any {
            localPath == it || (!fetched && localPath.startsWith(it + File.separator))
        }

        /**
         * Whether the mirror copy may be replaced with the one from the device folder.
         *
         * Opening a folder re-runs the whole copy, so this is what stands between a
         * reopen and the loss of edits not yet written back.
         *
         * Timestamps alone are not enough to decide this, for two reasons found the
         * hard way:
         *
         * - A size mismatch decides the tie, and only when the two timestamps agree.
         *   Writers that preserve mtime (unzip, cp -p, rsync -t, git checkout) land
         *   there with different lengths and still have to copy. Size came first once,
         *   against a copy cut short leaving a short file with a *fresh* mtime, and it
         *   cost far more than it saved: almost every edit changes a file's length, so
         *   almost every unsaved edit read as "not a copy of the source" and was
         *   overwritten by the older device copy. That truncation case is closed
         *   elsewhere now, by [copyDocumentToLocal] staging the bytes outside the mirror
         *   and renaming only once the stream finished, so an interrupted copy leaves no
         *   file at the destination at all.
         * - An unknown source timestamp (0) is decided by [mirrorIsOurCopy], not by the
         *   clock, because there is no clock to ask. COLUMN_LAST_MODIFIED is optional and
         *   arrives as 0 from MTP, some USB-OTG and some network providers.
         *
         *   Both obvious answers are wrong. "Always copy" freezes nothing but replaces a
         *   local edit on every reopen, which is what this used to do. "Always keep"
         *   protects the edit and freezes the folder for ever, since nothing in the app
         *   can clear a mirror. The record settles it without guessing: if the mirror is
         *   still exactly what the last sync wrote there, it is this app's own copy and
         *   the device is entitled to replace it, so the folder still heals. If it is
         *   not, it holds something written since, and that is the only copy.
         *
         * The comparison is only sound because [copyDocumentToLocal] stamps the mirror
         * with the source's own timestamp, so both sides come from the same clock.
         */
        internal fun shouldOverwriteMirror(
            mirrorExists: Boolean,
            mirrorModified: Long,
            mirrorSize: Long,
            sourceModified: Long,
            sourceSize: Long,
            /**
             * Whether the mirror file is byte-identical to the line the last sync
             * recorded for it. No default: there is one production caller and it has the
             * record in hand, and a default here would let a future caller answer the
             * question by not thinking about it.
             *
             * A function, not a value, and the laziness belongs here rather than at the
             * call site. Only the unknown-time branch below asks, and answering costs
             * the caller a read of the whole synced record; passed by value, that read
             * happened for every file on every provider, including the ones that report
             * a time and never reach the branch. The alternative was for the caller to
             * repeat `sourceModified == 0L` before computing it, which puts this
             * function's branch condition in the caller and goes stale the moment a
             * second branch here wants the same answer.
             */
            mirrorIsOurCopy: () -> Boolean,
        ): Boolean = when {
            !mirrorExists -> true
            // The provider did not report a time, so the record answers instead: our
            // own copy may be replaced, anything else is the only copy. A mirror with no
            // record behind it is not ours to overwrite either, which is the direction
            // reconcileDeletions already fails in.
            //
            // ⚠️ What this costs, and what gets it back. A file answering false here is
            // kept and is not vouched, and while it stays that way device changes stop
            // arriving and the mirror cannot be reclaimed. It does not stay that way by
            // itself: the caller compares the two sides' bytes, and when they agree,
            // which is where an edit the watcher carried to the device ends up, it
            // records the file and this clause answers true again on the next sync.
            //
            // What no branch recovers is an edit the watcher never delivered. With no
            // clock there is nothing to say whether the device copy is older or newer,
            // so neither side is pushed over the other.
            //
            // Kept anyway, because the alternative is worse and is what shipped: with no
            // timestamp there is nothing to tell a device edit from a local one, and the
            // old answer assumed the device always won, so every reopen destroyed the
            // local edit. This one assumes the local edit wins once the two diverge. It
            // loses updates; the other lost work. Note the divergence usually resolves
            // itself in content if not in bookkeeping: the watcher writes the local edit
            // out on the next save, after which both sides hold the same bytes.
            //
            // Content is what tells a device change from a local one without a clock,
            // and the caller reads both sides to ask. It is affordable because the
            // question is only asked for files this clause kept, which on a healthy
            // folder is none: every other file is answered true here and copied, which
            // reads the device document anyway.
            sourceModified == 0L -> mirrorIsOurCopy()
            sourceModified > mirrorModified -> true
            // A newer local copy wins, whatever its size. Checking size before this
            // was the defect: almost every edit changes a file's length, so almost
            // every unsaved edit looked like "not a copy of the source" and was
            // overwritten, the exact case this guard exists for.
            mirrorModified > sourceModified -> false
            // Same timestamp, different content. Writers that preserve mtime (unzip,
            // cp -p, rsync -t, git checkout) land here.
            else -> mirrorSize != sourceSize
        }

        /**
         * Directories to skip during sync: auto-generated and too large.
         *
         * "build" and ".vscode" are deliberately absent. build/ holds real
         * source in many projects (Gradle build scripts, C output kept in
         * tree), and .vscode/ holds launch.json, settings.json and
         * extensions.json, the workspace settings users most want synced.
         * Adding either back silently stops those files reaching the device.
         */
        internal val SKIP_DIRECTORIES = setOf(
            "node_modules",
            ".git",
            "__pycache__",
            ".gradle",
            ".idea",
            "venv",
            ".env"
        )

        /**
         * Whether a provider's display name can stand as one path segment, as it is.
         *
         * `COLUMN_DISPLAY_NAME` is whatever text the provider returned; nothing promises
         * it is a single name. [walkTree] composes it into a relative path and the copy
         * turns that into a real one with [File], creating directories along the way, so
         * a value carrying a separator or a parent reference would land outside the
         * mirror, somewhere the engine has no business writing and no record of having
         * written.
         *
         * Worth knowing when judging the cost: a platform provider derives the name from
         * a real filename and cannot return such a value, so this never fires for the
         * common case. Providers that relay names from elsewhere (cloud, WebDAV, SMB)
         * have no such guarantee.
         *
         * [reconcileDeletions] already declines to act on a path that resolves out of the
         * mirror. This is the same check one step earlier, where the path is composed
         * rather than consumed; having it on only one side was the oversight.
         */
        internal fun isSafeSegment(name: String?): Boolean =
            !name.isNullOrEmpty() && name != "." && name != ".." &&
                name.none { it == '/' || it == '\\' }

        /**
         * Whether the directory [path] really sits at or under [confine], which is a
         * canonical directory path with a trailing separator.
         *
         * The separator is appended to [path] as well, so the confining directory itself
         * answers true: a file at the root of the mirror has the mirror as its parent,
         * and refusing that would refuse the whole folder.
         *
         * The question [isSafeSegment] cannot answer, because it looks at one display
         * name and this looks at what the filesystem does with the whole path. A mirror
         * is routinely a checked-out repository, so a link inside one is
         * attacker-supplied in the ordinary case, and every stream in this engine reads
         * and writes *through* a link. [isLink] is deliberately about the entry rather
         * than about its parents, which is right where it is used and wrong here: what
         * puts a device document outside the granted folder is a link one or more levels
         * above it.
         *
         * A path that will not resolve is not inside anything, so it answers false. That
         * is the direction the rest of this engine fails in, and it is what
         * [reconcileDeletions] does with the same question on the other side.
         */
        internal fun resolvesInside(path: File?, confine: String): Boolean = try {
            path != null && (path.canonicalPath + File.separator).startsWith(confine)
        } catch (e: Exception) {
            false
        }

        /** Testable: checks if a directory should be skipped during sync. */
        internal fun shouldSkip(name: String, isDir: Boolean): Boolean {
            if (!isDir) return false
            return name in SKIP_DIRECTORIES
        }

        /**
         * Everything under [root] that has to be created on the device when [root]
         * itself is created, parents before children.
         *
         * A directory that simply appears in the mirror is the case this serves: without
         * the walk the create put an empty directory on the device and the files stayed
         * in the local mirror, which is reclaimed as soon as the permission lapses.
         *
         * What it cannot do is stand in for a copy the device already has: the cap bounds
         * it, and [SKIP_DIRECTORIES] excludes directories the mirror never held a copy of
         * in the first place. That is why a rename [handleMirrorEvent] could pair goes to
         * `renameDocument` instead of coming through here, and why an unpaired one leaves
         * the old name on the device rather than deleting it and rebuilding from here.
         *
         * Breadth-first so the cap, when it bites, spends the budget on the shallow
         * entries a person is likelier to be looking at. Parents precede children by
         * construction, which the caller needs: a child cannot be created until its
         * parent document exists.
         *
         * [UploadPlan.truncated] is how the caller learns the cap bit at all. The list
         * cannot say so on its own, which is what the walk below collects one entry past
         * the cap to answer.
         *
         * Only the entries [counts] accepts are listed and spend the cap; the walk still
         * descends through every directory, accepted or not. The default accepts
         * everything, which is what a directory-create needs, since every entry under it
         * is a provider round trip. [uploadMirrorOnlyDocuments] accepts only the files
         * the device lacks, because there the entries it already has cost the provider
         * nothing and a cap spent on them was permanent for the folder.
         *
         * Symbolic links are not followed. A mirror is routinely a checked-out
         * repository, so a link inside one is attacker-supplied in the ordinary case,
         * and following it would copy files from outside the folder the user granted.
         */
        internal fun uploadPlan(
            root: File,
            limit: Int,
            counts: (File) -> Boolean = { true },
        ): UploadPlan {
            // [root] is tested for being a link before anything else, and that is not
            // symmetry with the children loop below -- it is the case that reaches
            // outside the folder the user granted. `File.isDirectory` follows links,
            // and so does the observer that produces these jobs
            // (`(event and IN_ISDIR) != 0 || localFile.isDirectory`), so a symlink to
            // a directory arrives here looking exactly like a directory. Walking it
            // would list the *target's* contents and copy them onto the device: point
            // one at a home directory inside a synced folder and the whole of it
            // uploads.
            if (limit <= 0 || isLink(root) || !root.isDirectory) {
                return UploadPlan(emptyList(), truncated = false)
            }

            val found = mutableListOf<File>()
            val pending = ArrayDeque<File>()
            pending.addLast(root)

            // One entry past the cap, which is what makes the truncation answer exact:
            // a walk that stops at exactly [limit] cannot tell a directory that ends
            // there from one with a thousand more files below it, and the extra entry
            // exists only in the second case.
            //
            // Written as `<= limit` rather than as a `limit + 1` ceiling because a
            // caller passing Int.MAX_VALUE would overflow that into a negative one and
            // get an empty plan for a folder full of files.
            while (pending.isNotEmpty() && found.size <= limit) {
                val dir = pending.removeFirst()
                val children = dir.listFiles() ?: continue
                for (child in children.sortedBy { it.name }) {
                    if (found.size > limit) break
                    if (isLink(child)) continue
                    val isDir = child.isDirectory
                    if (isDir && shouldSkip(child.name, isDir = true)) continue
                    // The same filter every other local-to-device path applies.
                    // shouldWriteBack drops these for single-file events, and the
                    // recursion was the one route around it -- so a directory
                    // appearing while an editor held a `.swp`, or while a mirror
                    // copy was still `.vscodroid-partial`, put that scratch file on
                    // the user's device folder under its temporary name, where
                    // nothing later removes it.
                    if (!isDir && isMachineTemporary(child.name)) continue
                    if (counts(child)) found.add(child)
                    if (isDir) pending.addLast(child)
                }
            }
            // The extra entry is reported and then dropped: what the caller creates is
            // still capped at [limit], and a prefix of a breadth-first walk keeps
            // parents ahead of their children, which is what the caller creates by.
            val truncated = found.size > limit
            return UploadPlan(if (truncated) found.take(limit) else found, truncated)
        }

        /**
         * Whether two local files hold the same bytes.
         *
         * Lengths first, so the read is skipped for the pair that cannot match, which is
         * the usual shape of an edit. Deliberately not a length test on its own: writers
         * that keep a file's size while changing it are ordinary, and the whole reason
         * [setAsideDivergedMirror] asks is to tell an edit from a copy of the same thing.
         *
         * A read that fails answers false, which sends the caller down the preserving
         * branch. That costs a spare copy of a file in the mirror and never a byte.
         */
        internal fun sameBytes(a: File, b: File): Boolean {
            if (a.length() != b.length()) return false
            return try {
                a.inputStream().use { left ->
                    b.inputStream().use { right ->
                        val one = ByteArray(COPY_BUFFER_SIZE)
                        val two = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val read = readFully(left, one, one.size)
                            if (read != readFully(right, two, two.size)) return false
                            if (read == 0) return true
                            // Only the bytes this chunk filled, for the reason
                            // [deviceMatchesMirror] gives: the previous, longer chunk's
                            // tail is still in the buffer otherwise.
                            if (!java.util.Arrays.equals(one, 0, read, two, 0, read)) return false
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        /** Waits for a non-blocking provider without spinning or waiting forever without bytes. */
        internal class StreamReader(
            private val idleWaitMs: Long = PROVIDER_CLAIM_WAIT_MS,
            private val pauseMs: Long = 10L,
            private val nanoTime: () -> Long = System::nanoTime,
            private val pause: (Long) -> Unit = Thread::sleep,
        ) {
            fun read(input: InputStream, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int {
                var idleSince: Long? = null
                while (true) {
                    val count = input.read(buffer, offset, length)
                    if (count != 0 || length == 0) return count
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedIOException("The stream read was interrupted")
                    }
                    val now = nanoTime()
                    val started = idleSince ?: now.also { idleSince = it }
                    val left = idleWaitMs - TimeUnit.NANOSECONDS.toMillis(now - started)
                    if (left <= 0) throw IOException("The stream supplied no bytes before its wait expired")
                    try {
                        pause(minOf(pauseMs, left))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("The stream read was interrupted")
                    }
                }
            }
        }

        /**
         * The SHA-256 of what [input] holds and how many bytes that is, or null as soon as
         * it holds more than [limit].
         */
        @JvmName("sha256")
        internal fun sha256(input: InputStream, limit: Long, reader: StreamReader = StreamReader()): Pair<ByteArray, Long>? {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var total = 0L
            var read = reader.read(input, buffer)
            while (read >= 0) {
                total += read
                if (total > limit) return null
                digest.update(buffer, 0, read)
                read = reader.read(input, buffer)
            }
            return digest.digest() to total
        }

        /**
         * Fills [buffer] from [input] until [length] bytes are in it or the stream ends, and
         * answers how many that was: zero for an end that came first, where `read` says -1.
         *
         * `InputStream.readNBytes(byte[], int, int)` with the same contract, which is API 33.
         * Short reads are waited out rather than taken for the end of the document.
         * Zero-byte provider reads use [StreamReader]'s bounded wait.
         */
        @JvmName("readFully")
        internal fun readFully(input: InputStream, buffer: ByteArray, length: Int, reader: StreamReader = StreamReader()): Int {
            var filled = 0
            while (filled < length) {
                val count = reader.read(input, buffer, filled, length - filled)
                if (count < 0) break
                filled += count
            }
            return filled
        }

        /**
         * Copies [input] into [output] and answers how many bytes that was, or null as soon as
         * it holds more than [limit], having written no more than [limit] of them.
         */
        @JvmName("copyAtMost")
        internal fun copyAtMost(input: InputStream, output: OutputStream, limit: Long, reader: StreamReader = StreamReader()): Long? {
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var total = 0L
            var read = reader.read(input, buffer)
            while (read >= 0) {
                total += read
                if (total > limit) return null
                output.write(buffer, 0, read)
                read = reader.read(input, buffer)
            }
            return total
        }

        /**
         * Whether [file] is a symbolic link, without following it.
         *
         * `File.exists()` follows links, so it cannot answer this. Comparing the
         * canonical path against the absolute one can: they differ exactly when some
         * component of the path was a link. The parent is canonicalised first so a
         * link *above* this file -- which is not this file's problem -- does not make
         * every entry beneath it look like one.
         */
        internal fun isLink(file: File): Boolean = try {
            val parent = file.parentFile?.canonicalFile ?: return false
            File(parent, file.name).let { it.canonicalPath != it.absolutePath }
        } catch (e: Exception) {
            true
        }

        /**
         * The directories a watch has to cover for [root], one per directory, capped
         * at [limit] and spent breadth-first.
         *
         * [root] itself is included, and below it everything the walk would have
         * mirrored: the exclusions are [SKIP_DIRECTORIES], the set [walkTree] already
         * obeys, so nothing inside them is mirrored in and a watch there would spend a
         * kernel descriptor on changes with nowhere to go. That is also what keeps the
         * count survivable: one `node_modules` runs to thousands of directories on its
         * own.
         *
         * The order matters only once the cap bites, and then it decides which
         * directories go unwatched: breadth-first spends the budget on the shallow
         * ones, which is where a person edits, rather than on wherever a depth-first
         * descent happened to reach first.
         *
         * Symbolic links are not followed, for the same reason [uploadPlan]
         * refuses them and with the same words: a mirror is routinely a checked-out
         * repository, so a link inside one is attacker-supplied in the ordinary
         * case, and inotify follows a symlinked path. Watching through one spends
         * this app's descriptor budget on a tree outside the granted folder, and
         * every change there arrives as an event under a mirror-relative path that
         * [shouldWriteBack] happily serves, copying content from outside the grant
         * onto the device.
         */
        internal fun watchableDirectories(root: File, limit: Int): List<File> {
            if (limit <= 0 || isLink(root) || !root.isDirectory) return emptyList()

            val found = mutableListOf<File>()
            val pending = ArrayDeque<File>()
            pending.addLast(root)

            while (pending.isNotEmpty() && found.size < limit) {
                val dir = pending.removeFirst()
                found.add(dir)
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    if (isLink(child)) continue
                    if (child.isDirectory && !shouldSkip(child.name, isDir = true)) {
                        pending.addLast(child)
                    }
                }
            }
            return found
        }

        /** Scratch files the machine makes for itself and is about to rename or drop. */
        internal fun isMachineTemporary(name: String): Boolean =
            name.endsWith(PARTIAL_SUFFIX)

        /** The identity of the directory at [dir], or null when it cannot be read; see [renameSourceFor]. */
        internal fun directoryKeyOf(dir: File): Any? = runCatching {
            Files.readAttributes(dir.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        }.getOrNull()

        /**
         * Whether a change at [relativePath] should be pushed back to the device.
         *
         * This is [shouldSkip] applied to a path rather than to a single entry, so that
         * what the walk declines to mirror in is what this declines to write back out.
         *
         * The rule it replaces tested `relativePath.startsWith(".")`, which had no
         * counterpart on the way in: [SKIP_DIRECTORIES] deliberately does not list
         * `.vscode`, so workspace settings, `.gitignore` and `.editorconfig` were copied
         * into the mirror and then never allowed back onto the device: edited in the
         * editor, saved, and silently lost on the device side.
         *
         * [PARTIAL_SUFFIX] alone is reserved for unfinished engine copies. Other suffixes
         * can name user files, so their saves must reach the device too.
         */
        internal fun shouldWriteBack(relativePath: String, isDirectory: Boolean): Boolean {
            if (relativePath.isEmpty()) return false
            val segments = relativePath.split('/')
            val name = segments.last()
            if (isMachineTemporary(name)) return false
            // Every segment but the last is a directory by construction; the last one is
            // only a directory when the caller says so.
            if (segments.dropLast(1).any { shouldSkip(it, isDir = true) }) return false
            return !shouldSkip(name, isDirectory)
        }
    }
}
