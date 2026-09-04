package com.vscodroid.storage

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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
 * [setAsideDeviceCopy] says when.
 * External changes are picked up the next time the folder is opened, which is
 * the only refresh that exists: there is no "Refresh from device" action, and nothing
 * clears a mirror from inside the app either. Reopening also removes mirror files
 * for documents deleted on the device, under the conditions [reconcileDeletions]
 * spells out, and puts onto the device the files only the mirror has, under the
 * conditions [uploadMirrorOnlyDocuments] spells out.
 */
class SafSyncEngine(private val context: Context) {

    private val tag = "SafSyncEngine"
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

    @Volatile private var isWatching = false

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
    private val lifecycleLock = Any()

    /**
     * Whether [shutdown] has been called, after which no start is honoured again.
     *
     * Ordering the calls is not enough on its own. The Activity's teardown is the last
     * word about an engine nothing can reach afterwards -- the replacement Activity
     * builds its own [SafSyncEngine], so its stop cannot reach this one -- and a start
     * that arrives after it belongs to a coroutine of the Activity that has gone. It is
     * refused rather than run late, because there is no one left to stop what it starts.
     */
    @Volatile private var shutDown = false

    /**
     * One observer per watched directory, keyed by that directory.
     *
     * Holding them here is not only bookkeeping: [FileObserver]'s shared ObserverThread
     * refers to each observer through a [java.lang.ref.WeakReference], so one that
     * nothing else references stops delivering events as soon as it is collected.
     */
    private val watchers = mutableMapOf<File, DirectoryObserver>()
    private val watchersLock = Any()

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
    private val docIdCache = ConcurrentHashMap<String, String>()

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
     * one engine serves every folder in turn. Files only: a directory is never
     * recorded, so a question about one is answered by prefix, in [handleMirrorEvent].
     */
    private val unfetched = ConcurrentHashMap.newKeySet<String>()

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
     */
    private val refusalsAnnounced = ConcurrentHashMap.newKeySet<String>()

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
    private var cacheTree: Uri? = null

    /** The cached document id for [relativePath], or null when the cache does not speak for [treeUri]. */
    private fun cachedDocId(treeUri: Uri, relativePath: String): String? =
        synchronized(docIdCache) { if (cacheTree == treeUri) docIdCache[relativePath] else null }

    /** Remembers [docId] for [relativePath], unless the cache speaks for another tree by now. */
    private fun cacheDocId(treeUri: Uri, relativePath: String, docId: String) {
        synchronized(docIdCache) { if (cacheTree == treeUri) docIdCache[relativePath] = docId }
    }

    /**
     * Empties the cache and labels it for [treeUri]: the state [initialSync] puts it in
     * before filling it, and the only way the label moves.
     *
     * Internal so a test can hand an engine the folder a sync would have handed it
     * without running one. No JVM test can drive [startWatching], so the event tests
     * call [handleMirrorEvent] directly, and an engine that never synced speaks for no
     * tree at all: every one of their resolutions would go to the provider, and a case
     * about what the cache must not answer would pass with nothing in it.
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
    private val vanishedDirectories = mutableListOf<VanishedDirectory>()
    private val vanishedLock = Any()

    // -- Initial Sync --

    /**
     * Performs a full sync from SAF tree URI to the local mirror directory.
     *
     * @param safUri The tree URI granted by the SAF folder picker.
     * @param mirrorDir The local directory to mirror into.
     * @param onProgress Callback with (filesDone, totalFiles).
     */
    suspend fun initialSync(
        safUri: Uri,
        mirrorDir: File,
        onProgress: (Int, Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        // By mirror name rather than by tree URI: the URI spells the user's own
        // directory path, and Logger.i is not gated on a debuggable build. See
        // SafStorageManager.persistPermission for the whole of that reasoning.
        Logger.i(tag, "Starting initial sync of ${mirrorDir.name}")
        val startTime = System.currentTimeMillis()

        // Clear cache for fresh sync
        resetCache(safUri)
        // Scoped rather than cleared: this engine serves one folder at a time, and
        // a switch that fails partway must not drop the protection the previous
        // folder's mirror is still relying on.
        unfetched.removeAll { it.startsWith(mirrorDir.absolutePath + File.separator) }
        refusalsAnnounced.removeAll { it.startsWith(mirrorDir.absolutePath + File.separator) }

        // Phase 1: Enumerate all documents in the tree
        val documents = mutableListOf<DocumentInfo>()
        val rootDocId = DocumentsContract.getTreeDocumentId(safUri)
        cacheDocId(safUri, "", rootDocId)  // root entry
        val enumerationComplete = walkTree(safUri, rootDocId, "", documents)

        // What phase 2 is about to fetch, against what there is room for. Nothing is
        // refused on the strength of it: these are the sizes the provider reported, and a
        // folder that opens is worth more than a prediction. It decides one thing, which
        // notice the user gets if a copy does fail, and that is the difference between a
        // message they can act on and one they cannot.
        val toFetch = bytesToFetch(documents, mirrorDir)
        val room = usableSpaceOf(mirrorDir)
        val outOfRoom = toFetch > room
        if (outOfRoom) {
            Logger.w(
                tag,
                "Opening ${mirrorDir.name} needs ${toFetch / 1_048_576} MB and " +
                    "${room / 1_048_576} MB is free; some documents will not be copied",
            )
        }

        val totalFiles = documents.count { !it.isDirectory }
        var filesDone = 0
        var skippedLarge = 0
        var failedCopies = 0
        var keptLocal = 0
        var setAside = 0
        /*
         * What the mirror holds, for the files this sync can vouch for. Filled at the two
         * points below that know the mirror copy corresponds to the device document (it
         * was just written, or it was found identical), and nowhere else.
         *
         * An allowlist, and that is the whole point. The previous version recorded every
         * enumerated path and then subtracted the ways that could be wrong, which meant a
         * way nobody had thought of defaulted to "safe to delete". Four were found that
         * way, each a separate patch. Here a branch that does not record leaves its path
         * out, so an unforeseen one defaults to "never a candidate".
         */
        val recorded = mutableListOf<String>()
        var uploadsDone = 0

        Logger.i(tag, "Enumerated ${documents.size} items ($totalFiles files)")

        // Loaded once, before any doc is considered: the uploads a previous
        // session started and nothing finished. Their documents' timestamps are
        // this app's own interrupted writes, and phase 2 below has to know that
        // before it asks the clock anything.
        val unfinishedUploads = uploadsInFlight().toMutableSet()

        // Loaded for the same reason and read the same way: what the last sync wrote,
        // as (path, mtime, size) lines. Four readers, each of which needs it only in a
        // case it can rule out cheaply first: [shouldOverwriteMirror] for providers that
        // omit COLUMN_LAST_MODIFIED, where there is no clock to compare;
        // [setAsideDivergedMirror] when a device document is strictly newer than the
        // mirror copy it is about to replace; [deviceChangedSinceRecord] when a mirror
        // copy is strictly newer than the device document it is about to replace; and
        // [uploadMirrorOnlyDocuments] when the mirror holds a file the enumeration did
        // not return. Read once here rather than per document; [reconcileDeletions]
        // reads the same file again in phase 3, after phase 2 may have changed what is
        // on disk.
        //
        // `by lazy`, and it takes both halves to make that true. This was passed to
        // [shouldOverwriteMirror] by value, and Kotlin evaluates arguments before the
        // call, so the first ordinary file forced the read whether or not its provider
        // reported a time: the deferral the word promised never happened on any folder
        // with a file in it. Every reader takes a function now and invokes it only from
        // the branch that needs an answer, so a folder that nothing has changed since
        // the last sync does not hold one string per mirrored file through phase 2 for a
        // question no branch is going to ask.
        //
        // Note the scope carefully: it is this binding that is deferred, not the file.
        // [reconcileDeletions] opens the same record again in phase 3 on every sync
        // that enumerates anything, whatever the provider reports, so the saving is one
        // parse and one live Set across phase 2, never "the record is not read".
        //
        // Null rather than an empty set when there is no record to read, and that
        // distinction is the whole of what the readers below disagree about. A record
        // that is absent, unreadable, or written in a format this build does not know
        // says NOTHING about any path; a record that parsed and lists nothing says the
        // last sync could vouch for no file. Collapsing the two into "empty" left
        // [uploadMirrorOnlyDocuments] reading silence as evidence: with no usable
        // record every mirror file the enumeration did not return became an upload
        // candidate, so a document the user deleted on the device was put straight back
        // onto it -- the one thing that pass documents as never allowed. The header
        // arrived after a build that wrote records without one, so a record this build
        // cannot verify is in the field and reachable on upgrade.
        val previouslyRecorded: Set<String>? by lazy {
            readSyncedRecord(File(mirrorDir.path + SYNCED_RECORD_SUFFIX))
                .let { if (it.firstOrNull() == RECORD_HEADER) it.drop(1).toHashSet() else null }
        }

        // Where the mirror ends, resolved rather than compared lexically, and the one
        // thing phase 2 refuses to write outside. Every outbound path in this engine
        // already declines a symbolic link and says why; the inbound copy did not ask at
        // all, and it is the direction that needs the wider question. [isLink]
        // deliberately answers about an entry rather than about its parents, which is
        // right where it is used and wrong here: `mkdirs` follows a link above the file,
        // and so does the rename that moves a finished copy into place, so a single
        // `docs -> ../../..` planted by a checkout or a terminal put every device
        // document under `docs/` wherever that pointed: this app's own private storage,
        // outside the folder the user granted and outside anything [reconcileDeletions]
        // is allowed to touch.
        //
        // A mirror whose own path will not resolve leaves nothing here able to say
        // where a destination lands, so phase 2 does not run at all. That is the same
        // direction [reconcileDeletions] takes for the same question, and it is
        // unreachable in practice: the directory was created by the caller moments ago.
        val confine = try {
            mirrorDir.canonicalPath + File.separator
        } catch (e: Exception) {
            Logger.e(tag, "Could not resolve the mirror's own path; copying nothing: ${e.message}")
            return@withContext
        }

        // Phase 2: Create directories and copy files
        for (doc in documents) {
            val localPath = File(mirrorDir, doc.relativePath)

            // The DIRECTORY the copy will work in, not the entry itself, and the
            // difference is what keeps this from refusing a case that is already safe:
            // a link at the leaf is replaced rather than written through, because
            // [copyDocumentToLocal] writes beside its destination and renames, and a
            // rename replaces a link. What carries bytes out of the mirror is a link
            // above the file, which is exactly what this resolves.
            if (!resolvesInside(localPath.parentFile, confine)) {
                Logger.w(
                    tag,
                    "Not copying ${doc.relativePath} in: the directory it would be " +
                        "written in resolves outside the mirror",
                )
                if (!doc.isDirectory) {
                    failedCopies++
                    // The device holds a document this sync did not read, which is
                    // exactly what the set means and what keeps a local file of that
                    // name from opening it with "wt" and truncating it.
                    unfetched.add(localPath.absolutePath)
                    filesDone++
                    onProgress(filesDone, totalFiles)
                }
                continue
            }

            if (doc.isDirectory) {
                localPath.mkdirs()
            } else {
                // The one thing no timestamp comparison can see: this app was
                // writing this path back when it stopped. A newer, shorter
                // document here is CONSISTENT with this app's own truncated copy,
                // which is not the same as being one, so the bytes are asked
                // rather than assumed below. Where they answer yes the mirror is
                // kept, it is the only complete version, and the upload is
                // attempted again, which is also the repair. The record is
                // consumed by the sync that acted on it; the re-upload re-marks
                // it if it is cut short again.
                //
                // Repaired here rather than queued: the queue belongs to the
                // watcher's lifecycle, and [startWatching] opens by calling
                // [stopWatching], whose no-worker branch clears the queue, so a
                // job offered between the sync and the thread that would run it
                // is discarded before anything executes it. The sync already
                // owns this moment; it is on the IO dispatcher and mid-copy.
                if (localPath.exists() && localPath.absolutePath in unfinishedUploads) {
                    // The journal line is not the licence the comment above reads
                    // it as. It says a write started here and did not finish; it
                    // says nothing about what touched the document between then
                    // and now, and the window is as long as the app was away. A
                    // user who edits that file on the device before opening the
                    // app again, or another app that rewrites it, leaves a
                    // document this branch used to overwrite with `"wt"`, which
                    // truncates at open. The bytes were gone and nothing had been
                    // kept.
                    //
                    // [deviceIsTruncatedMirror] is what the comment above assumed
                    // rather than checked: the interrupted write leaves a strict
                    // prefix of the mirror, so anything else came from somewhere
                    // else. In the expected case it costs one provider read and
                    // no copy, which is why it is asked before the set-aside
                    // rather than instead of it: routing every repair through
                    // [setAsideDeviceCopy] would keep a `.device-` copy of this
                    // app's own half-written bytes, and phase 2b would then put
                    // that copy on the device.
                    val ownPartialWrite = deviceIsTruncatedMirror(doc.uri, localPath, doc.size)
                    if (!ownPartialWrite && !setAsideDeviceCopy(doc, localPath)) {
                        // Held back, not abandoned, and the record is deliberately
                        // NOT consumed: the mirror keeps the only complete copy,
                        // unvouched, and the next open tries the whole repair
                        // again. Writing anyway would be the loss this guard
                        // exists to prevent.
                        keptLocal++
                        Logger.w(
                            tag,
                            "Not replacing ${doc.relativePath}: its device copy is not the " +
                                "unfinished upload this app left and could not be read to " +
                                "set aside, so the mirror keeps its copy and the next open " +
                                "tries again",
                        )
                        // Said on screen as well, for the reason every sibling
                        // path here says it: the mirror now holds a copy no other
                        // app can see, that an uninstall takes with it, and a log
                        // line is not an answer to a user who thinks they saved.
                        // Throttled twice over, per path here and per interval in
                        // SafStorageManager, so a folder full of them is one
                        // notice rather than a wall of them.
                        announceLost(localPath)
                        filesDone++
                        onProgress(filesDone, totalFiles)
                        continue
                    }
                    unfinishedUploads.remove(localPath.absolutePath)
                    consumeStaleUploadRecord(localPath.absolutePath)
                    keptLocal++
                    if (!ownPartialWrite) setAside++
                    Logger.i(
                        tag,
                        "Kept the mirror of ${doc.relativePath}: its device copy was an " +
                            "upload this app did not finish"
                    )
                    writeLocalToSaf(localPath, doc.uri)
                    filesDone++
                    onProgress(filesDone, totalFiles)
                    continue
                }
                // Skip files larger than MAX_FILE_SIZE.
                // Not recorded, because this sync did not fetch the file and so cannot
                // say what is at that path. Usually there is nothing; but a file that was
                // under the limit at an earlier sync and has since grown past it leaves a
                // real, sync-written copy sitting there, and "nothing ever wrote this"
                // would be the wrong reason for the right behaviour. Not vouching for it
                // costs a stale copy that lingers, which is the direction to fail in.
                if (doc.size > MAX_FILE_SIZE) {
                    skippedLarge++
                    unfetched.add(localPath.absolutePath)
                    // At Logger.i, not d, for the reason the no-clock line below gives:
                    // Logger.d is gated on a debuggable build, so on a release build
                    // the only trace of this document was the count in the summary. The
                    // file is intact on the device and the editor simply does not have
                    // it, which is a folder that opens successfully and is quietly
                    // incomplete, and a user asking why they cannot find it needs the
                    // name rather than a number.
                    Logger.i(tag, "Skipped large file: ${doc.relativePath} (${doc.size / 1_048_576}MB)")
                    filesDone++
                    onProgress(filesDone, totalFiles)
                    continue
                }
                if (!shouldOverwriteMirror(
                        localPath.exists(), localPath.lastModified(), localPath.length(),
                        doc.lastModified, doc.size,
                        mirrorIsOurCopy = {
                            // A record this build cannot verify vouches for nothing,
                            // which is what the unknown-time branch already does with
                            // an answer of false: it keeps the mirror copy.
                            identityLine(doc.relativePath, localPath) in
                                previouslyRecorded.orEmpty()
                        },
                    )
                ) {
                    keptLocal++
                    var deviceAgrees = false
                    // Inside this branch the mirror is not older, so equal means the
                    // mirror is already this document and can be vouched for, while
                    // strictly newer means it holds an edit that has not been written
                    // back and cannot.
                    if (localPath.lastModified() == doc.lastModified) {
                        recordIdentity(recorded, doc.relativePath, localPath)
                    } else if (doc.lastModified == 0L &&
                        deviceMatchesMirror(doc.uri, localPath, doc.size)
                    ) {
                        // No clock, the mirror no longer matches the record, and yet the
                        // device document holds exactly these bytes. That is the ordinary
                        // end of a local edit: the watcher carried it to the device
                        // already, so the two agree in content while the record still
                        // describes what was there before.
                        //
                        // Nothing here writes anything. It records what is already true
                        // on both sides, which is what the record means and what
                        // `holdsOnlyVouchedCopies` reads it to mean.
                        //
                        // This recovers two of the three costs of never being vouched:
                        // device changes reach the file again, and the mirror becomes
                        // reclaimable. It does not recover the third. An edit the watcher
                        // never delivered is not on the device, so this cannot fire for
                        // it, and with no clock there is nothing to say whether the
                        // device copy is older or newer, so it is not pushed either.
                        //
                        // Deliberately not paired with a write in the other direction. If
                        // the two do not agree there is no clock to say which is newer,
                        // and pushing either way would destroy the other side's work.
                        deviceAgrees = true
                        Logger.i(
                            tag,
                            "Tracking ${doc.relativePath} again: the provider reports no " +
                                "modification time, and the device copy already holds " +
                                "what the mirror holds",
                        )
                        recordIdentity(recorded, doc.relativePath, localPath)
                    } else if (doc.lastModified > 0L &&
                        localPath.lastModified() > doc.lastModified
                    ) {
                        // Strictly newer with a time to compare against: an edit the
                        // watcher never carried out. That happens whenever the watcher
                        // was not running -- the app was killed, the directory sat past
                        // the watch cap, or the write-back gave up -- and until now
                        // nothing ever retried it. The prose around this feature calls
                        // reopening the folder the way back; this is what makes that
                        // true.
                        //
                        // Only when the provider reported a time. An unknown one arrives
                        // as 0, and every real mirror timestamp is greater than 0, so
                        // without this guard every file in such a folder would look
                        // newer and be pushed over the device document on every reopen.
                        //
                        // Deliberately NOT capped at [MAX_FILE_SIZE], which the same loop
                        // applies a few lines up, and the asymmetry is the right way
                        // round. That cap keeps a large DEVICE document out of
                        // `filesDir`, which is this app's own storage and the thing that
                        // runs out; writing back spends the device folder's space
                        // instead, so the reason for the cap does not apply in this
                        // direction.
                        //
                        // The decisive argument is consistency, not data loss. Capping
                        // costs nothing permanent -- an uncapped file simply stays in
                        // the mirror, where [holdsOnlyVouchedCopies] keeps it because
                        // the record cannot vouch for it -- but none of the other three
                        // `writeLocalToSaf` sites cap, so a cap here alone would upload
                        // a file when the watcher saves it and refuse the same file on
                        // reopen. One engine, two answers to one question, is worse
                        // than either answer.
                        //
                        // Note also how narrow the case is: reaching here needs the
                        // DEVICE document under the cap, since a larger one is skipped
                        // above, so only a file the user grew past 50 MB locally
                        // qualifies.
                        //
                        // What it costs is honest: a large file makes the folder-open
                        // dialog sit on this one entry. Progress is per file, so the bar
                        // does not move until the copy finishes. This runs on
                        // Dispatchers.IO, so it is a slow dialog and not an ANR.
                        //
                        // "Newer" says which side moved last, not that only one side
                        // moved. The record carries the device time this file had at
                        // the last sync, so a device document that no longer matches it
                        // was written since by something other than this app's sync,
                        // and the write below opens it with "wt". What wrote it is not
                        // knowable from here: another app is the case that matters,
                        // and this app's own delivered write-back is the common one,
                        // since the record never learns the time that write left
                        // behind. [setAsideDeviceCopy] tells the two apart by bytes
                        // and keeps a copy only where they differ. The mirror-side
                        // guard for the symmetric case already exists
                        // ([setAsideDivergedMirror]); this is the same guard facing
                        // the other way, and the one provider read it costs is paid
                        // wherever the record cannot vouch for the path: where it
                        // shows the device moved, and where it says nothing at all.
                        // Silence is the arm below's own leavings, so a file written
                        // back once pays that read on every open after it, and a
                        // second mirror edit before the next open fetches this app's
                        // own older bytes, which differ, and keeps them beside the
                        // file. That is the known false positive [setAsideDivergedMirror]
                        // already names for the three other producers of silence.
                        //
                        // A copy that fails leaves both sides as they are: the mirror
                        // keeps its edit, unvouched, and the next open tries again. The
                        // fetch failing is therefore a deferral, not a loss, but it is a
                        // deferral of the user's own save and nothing but this log says
                        // so, which for a persistent cause is indistinguishable from the
                        // save never happening.
                        val deviceChanged =
                            previouslyRecorded?.let { deviceChangedSinceRecord(it, doc) } == true
                        if (deviceChanged && !setAsideDeviceCopy(doc, localPath)) {
                            Logger.w(
                                tag,
                                "Not writing ${doc.relativePath} back: the record cannot " +
                                    "vouch for its device copy and that copy could not be " +
                                    "read to set aside, so the mirror keeps the edit and " +
                                    "the next open tries again",
                            )
                            // The deferral this branch's comment describes, said
                            // to the user rather than to logcat alone. Same seam,
                            // same throttles and same sentence as every other
                            // path that ends with the only copy inside the app.
                            announceLost(localPath)
                        } else {
                            if (deviceChanged) setAside++
                            uploadsDone++
                            Logger.i(tag, "Writing back a newer mirror copy: ${doc.relativePath}")
                            writeLocalToSaf(localPath, doc.uri)
                        }
                    }
                    // The flag the branch above set, not a fresh stat plus a scan of the
                    // recorded list. Re-deriving it would also read "the two differ" out of
                    // a recordIdentity that simply declined the path.
                    if (doc.lastModified == 0L && !deviceAgrees) {
                        // At Logger.i, not d: this file stops receiving device-side
                        // changes from here on, and a user asking why their folder does
                        // not update needs this line to exist in a bug report.
                        Logger.i(
                            tag,
                            "Kept ${doc.relativePath}: the provider reports no " +
                                "modification time, and the device copy differs from the " +
                                "mirror, so nothing here can say which is newer. Both " +
                                "sides are left as they are.",
                        )
                    }
                    Logger.d(tag, "Kept local copy: ${doc.relativePath}")
                    filesDone++
                    onProgress(filesDone, totalFiles)
                    continue
                }
                localPath.parentFile?.mkdirs()
                // One sync at a time per mirror file, and the loser walks away rather
                // than waiting, which is the shape [documentWritesInFlight] already
                // uses on the outbound side and for the same measured reason: a
                // provider stream has no timeout, so waiting turns a race into a stall
                // behind a dialog the user cannot cancel. Two syncs over one mirror is
                // not exotic: the mirror is named by a hash of the tree URI rather than
                // by the session, the phase-2 loop has no suspension point so cancelling
                // the coroutine does not stop it, and an activity recreated mid-open
                // builds a second engine over the same directory. Both wrote the same
                // scratch path, so one writer's rename pulled the inode out from under
                // the other, whose remaining bytes then landed inside the finished
                // mirror file that [recordIdentity] had already vouched for.
                if (!mirrorCopiesInFlight.add(localPath.absolutePath)) {
                    Logger.w(
                        tag,
                        "Another sync of this folder is already copying " +
                            "${doc.relativePath}; leaving it to that one",
                    )
                    filesDone++
                    onProgress(filesDone, totalFiles)
                    continue
                }
                val copied = try {
                    copyDocumentToLocal(doc.uri, localPath, doc.lastModified) { fetched ->
                        val moved =
                            setAsideDivergedMirror(doc, localPath, fetched) { previouslyRecorded }
                        if (moved) setAside++
                    }
                } finally {
                    mirrorCopiesInFlight.remove(localPath.absolutePath)
                }
                if (copied) {
                    recordIdentity(recorded, doc.relativePath, localPath)
                } else {
                    // The mirror has no copy and the device does. Counted as well
                    // as recorded: a failed copy had no counter at all, so the
                    // summary reported a clean sync for a folder it did not read.
                    failedCopies++
                    unfetched.add(localPath.absolutePath)
                }
                filesDone++
                onProgress(filesDone, totalFiles)
            }
        }

        // Phase 2b: put onto the device what only the mirror has.
        //
        // Guarded by the same two facts [reconcileDeletions] needs, and for a mirrored
        // reason. A partial enumeration does not know what the device holds, so a
        // mirror file it cannot find would be created beside a document that is already
        // there, or written into it; and an enumeration that succeeded and returned
        // nothing is a folder the user emptied and a provider answering for a volume
        // that is no longer mounted, told apart by nothing. This phase writes to the
        // device, so both cases decline.
        // The tick is added to the phase-2 figures rather than replacing them, so the
        // dialog goes on counting up instead of restarting at one, and the total grows
        // by exactly the work that is left.
        val putBack = if (enumerationComplete && documents.isNotEmpty()) {
            uploadMirrorOnlyDocuments(
                safUri,
                mirrorDir,
                documents.mapTo(HashSet()) { it.relativePath },
                { previouslyRecorded }
            ) { done, total -> onProgress(filesDone + done, totalFiles + total) }
        } else {
            0
        }

        // Phase 3: drop what the device no longer has
        val removed = reconcileDeletions(mirrorDir, documents, enumerationComplete, recorded)

        val elapsed = System.currentTimeMillis() - startTime
        Logger.i(
            tag,
            "Initial sync complete: $filesDone files ($skippedLarge too large, " +
                "$failedCopies could not be copied, $keptLocal kept, " +
                "$uploadsDone written back, $setAside set aside, $putBack put back, " +
                "${recorded.size} vouched for, $removed removed) in ${elapsed}ms"
        )

        // Only the copies that were attempted and failed. A document skipped for size is
        // an expected, permanent condition that would toast on every open of the folder
        // holding it, and the write-back guard already keeps a local file of that name
        // from replacing it.
        if (failedCopies > 0) onDocumentsNotCopied(failedCopies, outOfRoom)
    }

    /**
     * Moves the mirror copy of [doc] out of the way when the device's copy is about to
     * replace it and nothing can say the mirror held this app's own copy, and answers
     * whether it did.
     *
     * What it stands between: a save the write-back never delivered and a device edit
     * made afterwards. [shouldOverwriteMirror] is last-writer-wins on modification time,
     * so the device copy wins that comparison and [copyDocumentToLocal] replaces the
     * mirror, after which [recordIdentity] vouches for the result. The edit is then gone
     * with no copy of it anywhere: the mirror held the only one. Reaching that state
     * needs nothing exotic, only a save the watcher did not carry (the app killed before
     * the drain ran it, or the file sitting past [MAX_WATCHED_DIRECTORIES]) and then any
     * other app touching the same file on the device.
     *
     * Two conditions, and each one is what keeps this from firing on the ordinary case:
     *
     * - **A record exists and does not vouch for the mirror copy.** A file whose mirror
     *   copy still matches the line the last sync wrote is this app's own copy and the
     *   device is entitled to replace it, which is the same evidence
     *   [holdsOnlyVouchedCopies] and [reconcileDeletions] act on. A record that is
     *   absent, unreadable or in a format this build does not know arrives as null, and
     *   then its silence about a file says nothing at all: every mirror file would look
     *   diverged, including the ordinary stale copy a previous sync wrote. So no record
     *   means the pre-existing behaviour, which is to replace. A record that parsed and
     *   vouches for nothing is declined for the same reason and separately, because it
     *   is the shape a sync that kept every file it saw leaves behind.
     *
     * - **The fetched bytes differ from the mirror copy.** Without this the guard would
     *   fire on every file the user edited in the last session: a save the watcher DID
     *   deliver leaves the record naming the pre-edit copy and the device carrying the
     *   write-back's own fresh timestamp, so the first condition holds for it too, and
     *   the two sides hold the same bytes. [fetched] is the copy [copyDocumentToLocal]
     *   has just finished writing beside the destination, so this is a local comparison
     *   and not a second read of the document.
     *
     * Three ordinary branches of phase 2 also leave a path out of the record, and each
     * is a known false positive here: a copy that failed, a document skipped for size,
     * and a document whose destination directory resolved outside the mirror. If the
     * device copy of such a file later changes, the stale sync-written mirror copy is
     * set aside under a `.local-` name and [uploadMirrorOnlyDocuments] then puts that
     * name onto the device folder. Nothing is lost, and both folders gain a file
     * nothing removes. Narrowing the test would need the reason a path went unrecorded
     * to survive to the next sync, which nothing on disk carries today: `unfetched` is
     * this sync's own set, and every path in it took a branch that never reaches here.
     *
     * The copy is renamed rather than duplicated, so the space cost is nothing and what
     * the sync then finds at the original name is absent, which is where the fetched copy
     * is about to land. A rename the filesystem refuses answers false and the caller
     * replaces the mirror exactly as it did before: worse than preserving, better than
     * refusing to open the folder.
     *
     * Deliberately not routed through [announceLost]. The set-aside copy is a candidate
     * for [uploadMirrorOnlyDocuments] in this same sync, so it usually reaches the device
     * folder a moment later, and the notice that seam owns says the opposite ("the only
     * copy is inside VSCodroid"). What tells the user is stronger than a toast anyway:
     * the file sits in the explorer beside the one it was set aside from. The
     * announcement is left to the upload, which fires it only when the copy really did
     * stay inside the app.
     */
    private fun setAsideDivergedMirror(
        doc: DocumentInfo,
        localPath: File,
        fetched: File,
        previouslyRecorded: () -> Set<String>?,
    ): Boolean {
        if (!localPath.isFile) return false
        val recorded = previouslyRecorded() ?: return false
        if (recorded.isEmpty()) return false
        if (identityLine(doc.relativePath, localPath) in recorded) return false
        if (sameBytes(localPath, fetched)) return false

        val preserved = File(
            localPath.parentFile,
            "${localPath.name}$LOCAL_COPY_SUFFIX${localPath.lastModified()}",
        )
        if (!localPath.renameTo(preserved)) {
            Logger.w(
                tag,
                "Could not set the mirror copy of ${doc.relativePath} aside; the device " +
                    "copy is about to replace it",
            )
            return false
        }
        Logger.i(
            tag,
            "Set the mirror copy of ${doc.relativePath} aside as ${preserved.name}: it " +
                "differs from the device copy replacing it and no sync wrote it",
        )
        return true
    }

    /**
     * Whether the device document [doc] has changed since the last sync recorded it.
     *
     * The record's line for a path carries the mirror copy's modification time, and
     * [copyDocumentToLocal] stamps that copy with the provider's own time, so for a copy
     * that landed the line holds the device time as of the last sync. A device document
     * reporting any other time has been written since by something other than this
     * app's own sync. The one false positive is a filesystem that cannot hold the stamp
     * exactly, which `filesDir` never is; it would cost a spare `.device-` copy per
     * reopen, not a byte.
     *
     * A record that parses but does not name the path answers **true**, and so does a
     * line whose time field will not parse. Silence here is not evidence that the device
     * document is ours: the record is silent about a path this app wrote back and never
     * re-read, which is exactly what the caller's own write-back branch leaves behind,
     * and about a document the device gained under a name the mirror also gained.
     * Nothing on disk tells those apart, and one of them is a stranger's bytes. Answering
     * false meant the write-back arm disarmed the guard for every later pass over the
     * same path, so the second foreign edit was destroyed by a `"wt"` open with no copy
     * kept anywhere.
     *
     * True is not free, and the three costs are worth stating because the arm below
     * manufactures the silence that triggers them. One provider read per affected path
     * per open, forever, since nothing here re-records the path. A `.device-` copy kept
     * whenever the bytes differ, which includes the case where the device holds this
     * app's own earlier push and the mirror has been edited again since: that copy is a
     * stale duplicate of the user's own file, and it is the same known false positive
     * [setAsideDivergedMirror] already names for the other three producers of silence.
     * And a fetch that fails withholds the write-back, which defers the user's save
     * rather than losing it. False costs the user someone else's edit outright, which is
     * why true is still the right answer.
     *
     * This writes nothing to the record, deliberately. Every fix that adds a line here
     * is the one [reconcileDeletions] and `SafReconcileDeletionsTest` exist to refuse.
     *
     * The unusable record, absent or in a format this build cannot read, still answers
     * false, at the call site rather than here, for the reason [setAsideDivergedMirror]
     * gives: its silence says nothing, and a mirror that was never vouched for at all is
     * not the same claim as one vouched for and then written past.
     */
    private fun deviceChangedSinceRecord(recorded: Set<String>, doc: DocumentInfo): Boolean {
        val prefix = escapeRecordPath(doc.relativePath) + '\t'
        val line = recorded.firstOrNull { it.startsWith(prefix) } ?: return true
        val recordedModified = line.split('\t').getOrNull(1)?.toLongOrNull() ?: return true
        return recordedModified != doc.lastModified
    }

    /**
     * Fetches the device copy of [doc] to `<name>.device-<device time>` beside
     * [localPath], and answers whether it is there.
     *
     * The counterpart of [setAsideDivergedMirror] for the direction it does not cover:
     * a save the watcher never delivered meeting a device edit made BEFORE it. The
     * mirror copy is newer, so phase 2 writes it over the device document, and the
     * device edit was gone with no copy anywhere; the mirror-side guard cannot fire,
     * because it runs only where the device wins.
     *
     * Fetched rather than renamed, because the copy lives on the other side; that is
     * the one provider read this guard costs, and [deviceChangedSinceRecord] keeps it
     * off every file the record vouches for. The name carries the device's own time so
     * that a sync interrupted after the fetch and before the write reuses it rather
     * than leaving one copy per attempt. Stamped with that time too, so the next sync
     * sees the copy phase 2b puts onto the device as newer and takes it without a
     * second set-aside.
     *
     * Like the `.local-` copy, this one is a candidate for [uploadMirrorOnlyDocuments]
     * in the same sync, so it usually reaches the device folder a moment later and sits
     * in the explorer beside the file it was set aside from. The same in-flight guard
     * as phase 2's own copies, and for the same reason: two syncs over one mirror write
     * one scratch path.
     */
    private fun setAsideDeviceCopy(doc: DocumentInfo, localPath: File): Boolean {
        val preserved = File(
            localPath.parentFile,
            "${localPath.name}$DEVICE_COPY_SUFFIX${doc.lastModified}",
        )
        if (!mirrorCopiesInFlight.add(preserved.absolutePath)) return false
        val fetched = try {
            copyDocumentToLocal(doc.uri, preserved, doc.lastModified)
        } finally {
            mirrorCopiesInFlight.remove(preserved.absolutePath)
        }
        // A changed time is not a changed file. This app's own delivered
        // write-back moves the device time and the record never learns the new
        // one, and a provider with coarse stamps (FAT32 at two seconds, cloud
        // providers at whole seconds) reports the write-back's time below the
        // mirror's, so on every reopen after an ordinary save the record differs
        // and the mirror reads newer. The bytes are the same on both sides then,
        // and a copy of them is not a set-aside but a duplicate that phase 2b
        // would put into the user's folder. Compared after the fetch, which is
        // the one provider read this costs either way.
        if (fetched && sameBytes(localPath, preserved)) {
            preserved.delete()
            return true
        }
        if (fetched) {
            Logger.i(
                tag,
                "Set the device copy of ${doc.relativePath} aside as ${preserved.name}: " +
                    "it differs from the newer mirror copy replacing it and was written " +
                    "since the last sync",
            )
        }
        return fetched
    }

    /**
     * Creates on the device the mirror files the device has no document for, and answers
     * with how many arrived.
     *
     * Until this existed nothing in a sync ever looked at the mirror: phase 2 iterates
     * the DEVICE tree and its only outbound arm needs a device document to compare
     * against, and phase 3 only deletes. So a file that reached the mirror and not the
     * device stayed there for good. It is not hard to produce one: a create whose
     * write-back job died with the process before the drain ran it, a file made in a
     * directory past [MAX_WATCHED_DIRECTORIES] where no observer exists, or one made
     * while [initialSync] itself is running and the watcher is deliberately stopped.
     * The mirror keeps it ([holdsOnlyVouchedCopies] refuses to reclaim a mirror holding
     * anything the record cannot vouch for), so it is not destroyed, but it lives only
     * in this app's private storage: no other app can see it and it dies with an
     * uninstall. Reopening the folder is what the design calls the way back, and this is
     * what makes that true in the outbound direction.
     *
     * **What it must never do is undo a deletion.** A document the user deleted on the
     * device is absent from the enumeration and still present in the mirror, which is
     * indistinguishable from the case above by anything except the record: phase 3 is
     * about to remove exactly those, and it proves them by the line the last sync wrote.
     * So a path the previous record names is never a candidate here, whatever phase 3
     * then decides to do with it. The two passes therefore cannot disagree: this one
     * only ever touches paths the record has never carried.
     *
     * **A record that did not parse is not a record that names nothing**, and telling
     * those apart is what keeps the rule above from inverting. An absent record, one
     * this build cannot read, and one from the build before [RECORD_HEADER] existed all
     * say nothing about any path, so every mirror file the enumeration did not return
     * would be a candidate and every deletion the user made on the device since that
     * install's last sync would be undone, permanently, on the next open.
     * [reconcileDeletions] cannot offset it either: it declines on the same unusable
     * record, so the file stays in the mirror and is offered again on every later open.
     * This pass writes to the device, so it declines, exactly as [setAsideDivergedMirror]
     * does and for the same reason.
     *
     * That rule is exact for the case it exists for and deliberately not wider. A path
     * the record no longer names, because the last sync kept a local edit there rather
     * than vouching for it, is a candidate again once the device drops its document, and
     * that file IS put back. It is the same file phase 3 keeps in the mirror for the same
     * reason, so the editor goes on showing it either way; the choice is only whether the
     * device folder shows it too, and a copy the user is still editing belongs where they
     * can reach it.
     *
     * [previouslyRecorded] is a function for the reason [shouldOverwriteMirror]'s
     * argument is one. Reading the record costs a parse and a live set, and a healthy
     * folder has no candidate at all, so the read happens only once one is found.
     *
     * [onCandidateDone] is told (done, total) once per candidate, which the caller
     * folds into the progress it is already reporting. It is separate from that
     * callback rather than the same one because the totals are different: the caller
     * counts device documents and this counts mirror files, and only the caller can add
     * the two.
     *
     * Nothing is recorded for a file this puts across, and that is deliberate.
     * [recordIdentity]'s precondition is that the device is known to hold what is on
     * disk here, and [writeLocalToSaf] reports a failed copy through a notice rather
     * than a return value, so this cannot know. Leaving the path unrecorded costs one
     * sync: the next open finds the device holding it, copies it down and records it
     * then. Recording it here on a write that did not land would license a later
     * deletion of the mirror's only copy, which is the direction that cannot be undone.
     *
     * Two ceilings, both stated rather than hidden. [uploadPlan] stops once it has
     * [MAX_UPLOAD_ENTRIES] files the device does not have, so a mirror with more than
     * that stranded is put across breadth-first and no deeper; and a directory the
     * device does not have is created only when a file below it is going to be put
     * there, so a directory the user deleted on the device does not reappear empty on
     * every open.
     *
     * The cap counts the files this pass would put across, not the entries it walks
     * past, and the difference is what the reopen promise rests on. It used to count
     * every entry: the walk is local and costs the provider nothing, but the cap that
     * bounds [createChildrenInSaf]'s provider round trips was applied to it unchanged,
     * so in any mirror over 2000 entries, an ordinary repository, a file stranded past
     * the first 2000 in breadth-first order was never reached, on that open or any
     * later one, because the walk is deterministic. Counting candidates keeps the bound
     * on provider work, which is one create and one write per candidate, and lets the
     * walk cover the mirror. Once the cap does bite it is still permanent for that
     * folder until the earlier candidates are put across, since the walk stays
     * deterministic; the shortfall and the cap share one seam, [onUploadIncomplete],
     * one notice for the whole pass, and the cap reaches it only with a stranded file
     * in hand, which past the cap is always. [createChildrenInSaf] announces the cap
     * either way, because there the copy is work the user just asked for.
     */
    private fun uploadMirrorOnlyDocuments(
        safUri: Uri,
        mirrorDir: File,
        onDevice: Set<String>,
        previouslyRecorded: () -> Set<String>?,
        onCandidateDone: (Int, Int) -> Unit,
    ): Int {
        // Filtered inside the walk rather than after it, so that an entry the device
        // already answers for spends none of the cap: phase 2 settled every one of those,
        // and on a healthy folder that is every entry, so the record is never read.
        val plan = uploadPlan(mirrorDir, MAX_UPLOAD_ENTRIES) {
            it.isFile && it.toRelativeString(mirrorDir) !in onDevice
        }
        // Logged even where the returns below leave first, because the record can still
        // refuse everything the walk found, and then the entries past the cap are exactly
        // the ones nobody looked at. The log, and not the notice channel: a mirror with
        // more than the cap stranded is rare and the pass is already about to say so
        // through [onUploadIncomplete] for the files it does have in hand.
        if (plan.truncated) {
            Logger.w(
                tag,
                "Stopped at $MAX_UPLOAD_ENTRIES files only the mirror has; the rest stay " +
                    "in the mirror until these are on the device",
            )
        }

        val missing = plan.entries.map { it to it.toRelativeString(mirrorDir) }
        if (missing.isEmpty()) return 0

        // An unusable record cannot tell a save that never reached the device from a
        // document the user deleted there, and this pass writes to the device, so it
        // declines rather than guessing. Said at warn: the folder keeps files that only
        // the mirror holds, which is the state this pass exists to end, and the next
        // sync rewrites the record so it ends on the open after that.
        val recorded = previouslyRecorded() ?: run {
            Logger.w(
                tag,
                "Not putting files onto ${mirrorDir.name}: its record is missing or in a " +
                    "format this build cannot read, so a stranded save and a deletion " +
                    "made on the device look the same from here",
            )
            return 0
        }
        val recordedPaths = recorded.mapTo(HashSet()) { unescapeRecordPath(it.substringBefore('\t')) }
        val candidates = missing.filterNot { (_, relativePath) -> relativePath in recordedPaths }
        if (candidates.isEmpty()) return 0

        // Seeded with the root, which [resolveDocumentUri] answers for without asking
        // the provider anything. Every directory this pass resolves or creates is
        // remembered here, so a directory holding twenty stranded files is looked up
        // once rather than twenty times.
        val directories = mutableMapOf<String, Uri?>("" to resolveDocumentUri(safUri, ""))
        var made = 0
        candidates.forEachIndexed { index, (file, relativePath) ->
            val parent = deviceDirectoryFor(
                safUri, mirrorDir, parentPathOf(relativePath), onDevice, directories
            )
            if (parent != null && createOneInSaf(file, parent, safUri) != null) made++
            // Every candidate, including one that did not land: this is the folder
            // dialog's only sign of life through a stretch that is one createDocument
            // plus one provider write per file, behind a dialog the user cannot cancel.
            // Phase 2's own count has already reached its total by here, so the tick
            // carries its own denominator; without it the dialog reads "N of N" for as
            // long as this pass runs, which on a mirror full of stranded saves is the
            // longest part of opening the folder.
            onCandidateDone(index + 1, candidates.size)
        }
        // Documents created, which is not the same as bytes delivered: [createOneInSaf]
        // answers with the document even where it declined to write into it, which it
        // does when the device already holds a document this sync never read. That
        // refusal announces itself through [refuseUnreadDocument], so nothing is silent;
        // only this number is optimistic.
        Logger.i(
            tag,
            "Put $made of ${candidates.size} file(s) only ${mirrorDir.name} had onto the " +
                "device folder",
        )
        // One notice for the pass, not one per file, and spelled exactly as
        // [createChildrenInSaf] spells it for the identical situation. That is the
        // reason [onUploadIncomplete] exists at all: a provider that refuses one create
        // refuses them all, and forty toasts of "the only copy is inside VSCodroid",
        // each three and a half seconds long behind a dialog the user cannot cancel, is
        // another way of telling them nothing. The cap rides along here rather than at
        // the walk itself because this is the point where the pass has a stranded file
        // in hand, which is what makes "there may be more below the cap" worth a
        // sentence instead of noise on every open.
        val lost = candidates.size - made
        if (lost > 0 || plan.truncated) onUploadIncomplete(mirrorDir, lost, plan.truncated)
        return made
    }

    /**
     * The device document for the mirror directory at [relativePath], creating it and
     * whatever is missing above it, or null when the provider would not.
     *
     * Memoised in [resolved] across one pass, negatives included: a parent the provider
     * refused refuses every file under it too, and asking again is a binder walk per
     * segment for an answer already known.
     *
     * A directory the enumeration returned is resolved rather than created, which is
     * what [onDevice] is for: [docIdCache] already holds it, so that costs nothing,
     * while asking the provider to create a name it already has is a round trip whose
     * answer is the same document.
     */
    private fun deviceDirectoryFor(
        safUri: Uri,
        mirrorDir: File,
        relativePath: String,
        onDevice: Set<String>,
        resolved: MutableMap<String, Uri?>,
    ): Uri? {
        if (relativePath in resolved) return resolved[relativePath]
        val uri = if (relativePath in onDevice) {
            resolveDocumentUri(safUri, relativePath)
        } else {
            deviceDirectoryFor(safUri, mirrorDir, parentPathOf(relativePath), onDevice, resolved)
                ?.let { createOneInSaf(File(mirrorDir, relativePath), it, safUri) }
        }
        resolved[relativePath] = uri
        return uri
    }

    /**
     * Removes mirror files for documents that have since been deleted on the device.
     *
     * Reopening a folder used to only create and overwrite, so anything deleted on the
     * device came straight back from the stale mirror, and because a MODIFY on a file
     * the tree no longer knows falls through to a create, editing it pushed it back
     * onto the device too.
     *
     * The two ways of being wrong here do not cost the same. A file left behind is
     * untidy; deleting work that exists only in the mirror cannot be undone. So this
     * removes only what a *complete* enumeration proves is gone, and only files it can
     * show it put there itself:
     *
     * - A partial enumeration proves nothing. [walkTree] logs and carries on when a
     *   provider query fails, so a folder that answered for two directories out of
     *   twenty would otherwise read as "eighteen directories were deleted". One failure
     *   anywhere disables the pass and leaves the last complete record untouched.
     * - Neither does an enumeration that succeeded and returned nothing. A folder the
     *   user emptied and a provider answering for a volume that is no longer mounted
     *   look identical from here, and only one of them costs the whole mirror. The
     *   price of declining is a folder emptied on the device keeping its mirror copies
     *   until they are deleted in the editor, which propagates the other way.
     * - **A candidate must still be the file that was recorded**, matched on modification
     *   time *and* length, not merely on its path. This is the rule the others used to
     *   stand in for, and getting here took four separate defects to notice. Each was a
     *   different way for a path to be recorded while the mirror held something else (a
     *   local edit, a file too large to have ever been copied, a copy that failed), and
     *   each was patched on its own. They were one gap: the record named *paths*, while
     *   the argument for deleting needs *identity*. Matching identity is what makes a
     *   fifth way, which nobody has found yet, stop mattering: a path recorded in error
     *   will not have a file that matches what was recorded for it.
     * - A line that cannot be parsed that way is never a candidate, and neither is any
     *   line of a record that does not open with [RECORD_HEADER]. Records written by an
     *   earlier build are one path per line and carry no identity at all, so they are
     *   unverifiable by construction, and unverifiable has to mean "keep". The header is
     *   what extends that to a format this build has never seen: field count is not a
     *   version, and the operation on the other side of the guess is a delete.
     * - A candidate that does not resolve inside the mirror is left alone. The record is
     *   built from provider-supplied display names, and a delete is the wrong operation
     *   to point at a path that walked out of the directory it belongs to.
     * - Directories are left alone. Removing one means a recursive delete, the
     *   operation with the worst outcome if any of the reasoning above is wrong. An
     *   empty directory left behind is noise.
     *
     * **Where all of this stops being true.** Every line of it assumes the provider
     * reports `COLUMN_LAST_MODIFIED`. When it does not, [shouldOverwriteMirror]'s
     * unknown-time branch answers from the record instead, so a diverged file is kept
     * rather than overwritten, and is tracked again once a sync finds the two sides
     * holding the same bytes, before deletion is considered at
     * all. Nothing recorded here can protect what the copy already replaced, and no
     * identity check reaches a file that is gone. The account above of four defects
     * turning out to be one gap holds for providers that report a time, and only those.
     * MTP, some USB-OTG bridges and some network providers do not.
     *
     * @return how many files were removed.
     */
    private fun reconcileDeletions(
        mirrorDir: File,
        documents: List<DocumentInfo>,
        enumerationComplete: Boolean,
        recorded: List<String>
    ): Int {
        if (!enumerationComplete || documents.isEmpty()) {
            Logger.w(
                tag,
                "Enumeration proved nothing: leaving the mirror of ${mirrorDir.name} as it is"
            )
            return 0
        }

        val record = File(mirrorDir.path + SYNCED_RECORD_SUFFIX)
        val present = documents.filterNot { it.isDirectory }.map { it.relativePath }.toSet()
        var removed = 0

        // Resolved rather than checked lexically: a mirror is routinely a checked-out
        // repository, so a link inside one is attacker-supplied in the ordinary case and
        // `..` handling alone would follow it out.
        val confine = try {
            mirrorDir.canonicalPath + File.separator
        } catch (e: Exception) {
            Logger.w(tag, "Could not resolve the mirror's own path; removing nothing: ${e.message}")
            return 0
        }

        val lines = readSyncedRecord(record)
        val entries = if (lines.firstOrNull() == RECORD_HEADER) {
            lines.drop(1)
        } else {
            if (lines.isNotEmpty()) {
                Logger.i(tag, "Record of ${mirrorDir.name} is not this format; removing nothing")
            }
            emptyList()
        }

        for (line in entries) {
            val parts = line.split('\t')
            if (parts.size != 3) continue  // an older build's record, or a damaged line
            val path = unescapeRecordPath(parts[0])
            val wasModified = parts[1].toLongOrNull() ?: continue
            val wasSize = parts[2].toLongOrNull() ?: continue

            if (path.isEmpty() || path in present) continue
            val stale = File(mirrorDir, path)
            if (!stale.isFile) continue
            val resolved = try {
                stale.canonicalPath
            } catch (e: Exception) {
                continue
            }
            if (!resolved.startsWith(confine)) {
                Logger.w(tag, "Recorded path resolves outside the mirror, not touching it: $path")
                continue
            }
            if (stale.lastModified() != wasModified || stale.length() != wasSize) {
                Logger.d(tag, "Kept a file that is no longer the copy recorded for it: $path")
                continue
            }
            if (stale.delete()) removed++
        }

        writeSyncedRecord(record, recorded)
        return removed
    }

    /**
     * Replaces the synced record in one step, or leaves the previous one exactly where
     * it was.
     *
     * Written beside the record and renamed, for the reason [rewriteJournal] gives about
     * the file next to it: a truncate-and-write loses the whole record to a process
     * death in the middle, and the process being killed while a large folder finishes
     * opening is an ordinary event here rather than a rare one. Every consumer of a
     * damaged record does fail safe ([holdsOnlyVouchedCopies] keeps the mirror,
     * [reconcileDeletions] removes nothing, [shouldOverwriteMirror]'s unknown-time
     * branch keeps the mirror copy), so what this costs is a mirror that cannot be
     * reclaimed and, on a provider that omits COLUMN_LAST_MODIFIED, a folder that stops
     * receiving device-side changes until [deviceMatchesMirror] heals it a file at a
     * time. The next sync of the same folder rewrites the record in full, so the damage
     * lasts one sync, which is exactly as long as the folder goes unopened.
     *
     * The temporary name is deterministic rather than unique, and that is what keeps an
     * interrupted write from leaving one small file per interruption beside the mirror:
     * the next write reuses the same name. That holds only while the folder is opened
     * again, though, and a name `MIRROR_ENTRY` does not match is a name nothing else in
     * `saf-mirrors` ever looks at, so a record whose mirror is then reclaimed used to
     * leave this behind for good, in a directory exported into every terminal as
     * `SAF_MIRRORS_DIR`. `SafStorageManager.reclaimRevokedMirrorsSync` deletes it with
     * the pair it belonged to.
     */
    private fun writeSyncedRecord(record: File, recorded: List<String>) {
        val body = (listOf(RECORD_HEADER) + recorded).joinToString("\n")
        val tmp = File(record.parentFile, record.name + PARTIAL_SUFFIX)
        try {
            tmp.writeText(body)
            // The same fallback [rewriteJournal] makes, and for the same reason: a
            // rename the filesystem refuses would otherwise leave the record never
            // written at all, which is worse than the write it was replacing.
            if (!tmp.renameTo(record)) {
                record.writeText(body)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            Logger.w(tag, "Could not record the synced set: ${e.message}")
        }
    }

    /**
     * Appends [path]'s identity as it stands on disk, or nothing when there is no file
     * there.
     *
     * Read back rather than predicted, so that a timestamp the filesystem truncated or a
     * length the provider misreported cannot put the record out of step with the disk.
     *
     * ⚠️ Reading back is only sound because the caller has established the file is this
     * sync's. It cannot tell on its own: [copyDocumentToLocal] writes beside the
     * destination and renames, deliberately leaving the destination untouched when it
     * fails, so after a failed copy this reads whatever was already there, which can be
     * an edit of the user's that no sync ever wrote. Recording that identity is what
     * would make the user's only copy match, and match is what licenses the delete.
     *
     * So the precondition is about the DEVICE, not about who authored the bytes: call
     * this only where the device is known to hold what is on disk here. A landed copy
     * establishes that. So does a comparison that read both sides and found them equal,
     * which is how a file the editor wrote and the watcher delivered gets recorded
     * without any copy having happened.
     */
    private fun recordIdentity(into: MutableList<String>, path: String, file: File) {
        if (!file.isFile) return
        into.add(identityLine(path, file))
    }

    /**
     * One file's line in the synced record: relative path, mtime, size, tab separated.
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
        "${escapeRecordPath(path)}\t${file.lastModified()}\t${file.length()}"

    /**
     * Whether every file under [mirrorDir] is one this app can prove it copied from the
     * device folder, unchanged since.
     *
     * The question the reclaim pass has to answer before deleting a mirror, and it is
     * narrower than the one it used to ask. That pass reads [uploadsInFlight], which
     * records write-backs that were ATTEMPTED and failed. A file that was never queued
     * for write-back at all has no journal entry, and there are several ways to be in
     * that state: anything under [SKIP_DIRECTORIES], so a `.git` from a terminal clone;
     * a file below a directory past [MAX_WATCHED_DIRECTORIES]; anything written while no
     * watcher was running; a mirror copy [initialSync] kept for being newer than the
     * device document, which it never uploads; entries past the upload cap; and jobs the
     * queue dropped on [stopWatching]. Each of those is the user's only copy, and the
     * journal cannot see any of them.
     *
     * So the test is inverted: rather than look for evidence a file is precious, require
     * evidence that it is disposable. The record beside the mirror is that evidence and
     * already exists. [recordIdentity] writes one line per file the device is known to
     * hold, and this compares against it in the same format, so a file absent from the
     * record, or present but no longer the bytes recorded for it, means the mirror holds
     * something the device does not.
     *
     * "The device holds it" rather than "this app copied it", and the difference is not
     * pedantry. A file the editor wrote and the watcher delivered is recorded too, once a
     * sync has read both sides and found them equal. It was never copied here, and it is
     * still disposable, because deleting the mirror does not destroy it.
     *
     * Fails closed, in three directions, all deliberate. A missing record, a record whose
     * first line is not [RECORD_HEADER], and an unreadable directory each return false
     * and keep the mirror. That is the same direction [reconcileDeletions] takes when it
     * cannot vouch for a path, and the cost of being wrong here is disk rather than the
     * user's only copy.
     *
     * Symlinks are not followed: `walkTopDown` reports the link itself, and [isLink]
     * sends it down the not-a-plain-file branch, so a link out of the mirror can neither
     * be vouched for nor be walked through.
     */
    internal fun holdsOnlyVouchedCopies(mirrorDir: File): Boolean {
        val lines = readSyncedRecord(File(mirrorDir.path + SYNCED_RECORD_SUFFIX))
        if (lines.firstOrNull() != RECORD_HEADER) return false
        val vouched = lines.drop(1).toHashSet()
        // A directory the walk could not read leaves the mirror only partly examined,
        // which is indistinguishable from an empty one to `all`. Recorded rather than
        // returned from the handler: `onFail` is not inline, so it cannot return here.
        var unreadable = false
        return try {
            mirrorDir.walkTopDown().onFail { _, _ -> unreadable = true }.all { file ->
                if (isLink(file)) false
                else if (!file.isFile) true
                else identityLine(file.toRelativeString(mirrorDir), file) in vouched
            } && !unreadable
        } catch (e: Exception) {
            Logger.w(tag, "Could not walk ${mirrorDir.name}; treating it as unvouched: ${e.message}")
            false
        }
    }

    /**
     * The lines of the last complete sync's record, or none when there is no file to
     * read or the read failed.
     *
     * The first line is [RECORD_HEADER]; every line after it is `path`, `modification
     * time` and `length` separated by tabs, the path spelled as [escapeRecordPath]
     * spells it. The header check and the parsing are both the caller's, because a
     * record in a format this build does not know has to be discarded whole and a line
     * it cannot read has to be dropped rather than repaired.
     */
    private fun readSyncedRecord(record: File): List<String> =
        if (!record.isFile) {
            emptyList()
        } else {
            try {
                record.readLines()
            } catch (e: Exception) {
                Logger.w(tag, "Could not read the synced record: ${e.message}")
                emptyList()
            }
        }

    // -- File Watching (Write-back) --

    /**
     * Starts watching the mirror directory for changes and syncing them back to SAF.
     * Must be called after [initialSync] completes.
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
            stopWatching()

            // Published before any observer exists, because an observer fires into whatever
            // session is current and this is the one its jobs belong to.
            val opening = WatchSession()
            session = opening
            isWatching = true
            watchTree(mirrorDir, mirrorDir, safUri)

            // Background thread to process this session's write-back queue. Handed over
            // before it starts, so a stop arriving at once cannot find a null worker for a
            // thread that is already running.
            val worker = thread(start = false, name = "saf-writeback", isDaemon = true) {
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
     * interrupted, then sends out whatever is still queued.
     *
     * Drives whichever session is current, which is what a test without a live watcher
     * needs: no JVM test can call [startWatching], because registering a watch builds a
     * [FileObserver] and its static initializer reaches native code.
     */
    internal fun runWriteBackLoop(isRunning: () -> Boolean) = runWriteBackLoop(session, isRunning)

    /**
     * The same loop bound to one folder's [session], which is the form the watcher's own
     * thread runs: a drain that outlives [stopWatching] goes on polling the queue it was
     * started with, and the folder opened next has a different one.
     */
    internal fun runWriteBackLoop(session: WatchSession, isRunning: () -> Boolean) {
        var interrupted = false
        try {
            while (isRunning()) {
                val job = session.queue.poll()
                if (job != null) {
                    processWriteBack(session, job)
                } else {
                    Thread.sleep(WRITEBACK_POLL_MS)
                }
            }
        } catch (_: InterruptedException) {
            // stopWatching() interrupts to wake this thread out of the sleep it spends
            // nearly all of its idle time in, so this is the ordinary way the loop ends,
            // not a fault. Letting it escape reaches the thread's uncaught handler, and
            // Android's default handler ends the process: closing a folder, which
            // happens on every folder switch and on destroy, would close the app.
            interrupted = true
        }
        // Whichever way the loop ended, the queue can still hold writes the user is
        // expecting on the device, and they have to go out with the interrupt status
        // clear: the stream copies below throw InterruptedIOException on a thread still
        // carrying the flag, losing exactly what this drain exists to save.
        if (Thread.interrupted()) interrupted = true

        var remaining = session.queue.poll()
        while (remaining != null) {
            processWriteBack(session, remaining)
            remaining = session.queue.poll()
        }
        // Said after the last poll rather than before it, so the flag is never set while
        // this thread could still deliver: an event that reads it answers "nothing will
        // take this now" only when that is already true.
        session.abandoned = true
        if (interrupted) Thread.currentThread().interrupt()
    }

    /**
     * Stops the file watcher and drains the write-back queue.
     */
    fun stopWatching() {
        synchronized(lifecycleLock) {
            isWatching = false
            synchronized(watchersLock) {
                watchers.values.forEach { it.stopWatching() }
                watchers.clear()
            }
            // The folder being closed keeps the queue its jobs are in, and the folder opened
            // next gets an empty one, whether or not the drain below finishes in time.
            val closing = session
            session = WatchSession()
            closing.running = false

            // Wake the thread out of its sleep, then wait for it to drain remaining writes.
            // An idle queue drains in microseconds, so this costs only what there is to lose.
            val worker = closing.worker
            closing.worker = null
            worker?.interrupt()
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
                // into. Counted rather than dropped in silence: the mirror keeps the file and
                // the record cannot vouch for it, so nothing is destroyed, but the save is
                // not on the device and only reopening the folder puts it there. A user
                // asking why needs this line to exist in a bug report.
                val dropped = closing.queue.size
                closing.queue.clear()
                closing.abandoned = true
                if (dropped > 0) {
                    Logger.w(tag, "$dropped write-back(s) arrived after the drain ended")
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
     * Puts an observer on each of [watchableDirectories] for [dir], one per directory,
     * because a watch descriptor covers a directory and not a tree.
     */
    private fun watchTree(dir: File, rootDir: File, safTreeUri: Uri) {
        // The walk calls listFiles() once per directory, so it stays outside the lock:
        // holding a monitor across a tree's worth of filesystem calls would block the
        // observer thread's own registrations behind them. Its result is a bound on how
        // much work follows, not a reservation; the cap is enforced again below, where
        // it can be exact.
        val room = synchronized(watchersLock) { MAX_WATCHED_DIRECTORIES - watchers.size }
        val targets = watchableDirectories(dir, room)
        val started = mutableListOf<DirectoryObserver>()
        var atLimit = false

        synchronized(watchersLock) {
            for (target in targets) {
                if (watchers.size >= MAX_WATCHED_DIRECTORIES) {
                    atLimit = true
                    break
                }
                if (watchers.containsKey(target)) continue
                val observer = DirectoryObserver(target, rootDir, safTreeUri)
                watchers[target] = observer
                started += observer
            }
        }

        if (atLimit || targets.size >= room) {
            Logger.w(
                tag,
                "Watch limit of $MAX_WATCHED_DIRECTORIES reached; directories below " +
                    "${dir.name} are unwatched"
            )
        }
        // Registering the watch reaches the kernel, and nothing here needs the lock
        // held while it does.
        started.forEach { it.startWatching() }
    }

    /** Releases the observer for [dir] and for anything below it. A no-op if unwatched. */
    private fun unwatchTree(dir: File) {
        synchronized(watchersLock) {
            // Every deleted file reaches here, and only a deleted directory can be
            // holding watches. Without this the scan below would run once per entry of
            // a `git clean`, across the whole map, on the observer thread.
            if (!watchers.containsKey(dir)) return

            val prefix = dir.path + File.separator
            watchers.keys
                .filter { it == dir || it.path.startsWith(prefix) }
                .forEach { watchers.remove(it)?.stopWatching() }
        }
    }

    // -- Internal: Tree Walking --

    /**
     * Recursively walks a SAF document tree, collecting [DocumentInfo] entries.
     *
     * @return whether every directory under [parentDocId] could be enumerated. A false
     *   here is what stops [reconcileDeletions] from reading a provider that stopped
     *   answering as a device folder that was emptied.
     */
    private fun walkTree(
        treeUri: Uri,
        parentDocId: String,
        parentRelPath: String,
        result: MutableList<DocumentInfo>
    ): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        var complete = true

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                // Not every provider fills this one, so a missing column is not fatal.
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex)
                    val mimeType = cursor.getString(mimeIndex)
                    val size = cursor.getLong(sizeIndex)
                    val lastModified =
                        if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L
                    val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

                    // Before the name is composed into a path, not after: see
                    // [isSafeSegment].
                    if (!isSafeSegment(name)) {
                        Logger.w(tag, "Skipped a document whose display name is not a path segment")
                        continue
                    }

                    val relativePath = if (parentRelPath.isEmpty()) name else "$parentRelPath/$name"
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                    // Skip hidden files and common large directories
                    if (shouldSkip(name, isDir)) continue

                    // Cache the docId so write-back resolves this path without
                    // walking the tree again
                    cacheDocId(treeUri, relativePath, docId)

                    result.add(DocumentInfo(docUri, docId, relativePath, isDir, size, lastModified))

                    if (isDir && !walkTree(treeUri, docId, relativePath, result)) {
                        complete = false
                    }
                }
            } ?: return false  // the provider refused to answer at all
        } catch (e: Exception) {
            // The path inside the folder, not the document id, which the platform
            // provider spells as the user's own directory; and the exception's class,
            // because a provider quotes the document URI in its message.
            Logger.w(
                tag,
                "Failed to enumerate " +
                    (if (parentRelPath.isEmpty()) "the folder root" else parentRelPath) +
                    ": ${e.javaClass.simpleName}",
            )
            return false
        }
        return complete
    }

    /**
     * Skip patterns: large auto-generated directories that would slow sync unnecessarily.
     */
    private fun shouldSkip(name: String, isDir: Boolean): Boolean =
        Companion.shouldSkip(name, isDir)

    // -- Internal: File Operations --

    /**
     * Copies a single SAF document to a local file.
     *
     * @return whether [dest] now holds this document. False means [dest] was left exactly
     *   as it was, which, because of the partial-and-rename below, can mean it still
     *   holds an edit of the user's that no sync wrote. Callers that record what the
     *   mirror holds have to know the difference.
     */
    private fun copyDocumentToLocal(
        docUri: Uri,
        dest: File,
        sourceModified: Long,
        onReplacing: (fetched: File) -> Unit = {},
    ): Boolean {
        // Written beside the destination and moved into place only once the stream
        // finished. Writing straight to dest would truncate it first, so a copy cut
        // short (by an exception, or by the process being killed mid-stream) would
        // leave a short file carrying a fresh timestamp. That is indistinguishable
        // from an unsaved local edit, and the sync decision has to tell them apart.
        val partial = File(dest.parentFile, "${dest.name}$PARTIAL_SUFFIX")
        try {
            val source = context.contentResolver.openInputStream(docUri)
            if (source == null) {
                // By the mirror file's own name, never the document id: a provider
                // composes that from the user's own path, and this level ships. See
                // SafStorageManager.persistPermission for the whole of that reasoning.
                Logger.w(tag, "No stream for ${dest.name}")
                return false
            }
            source.use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                }
            }
            // Stamp with the source's own time so later syncs compare two timestamps
            // from the same clock rather than a provider's against the filesystem's.
            if (sourceModified > 0) partial.setLastModified(sourceModified)
            // The one moment anything can act on the copy this is about to replace, and
            // the only one where replacing it is already certain: the stream finished, so
            // the caller is not being asked to judge a copy that may yet fail and leave
            // the destination exactly as it was. The fetched bytes are on disk here, so
            // the comparison the caller needs costs no second read of the document.
            if (dest.exists()) onReplacing(partial)
            if (!partial.renameTo(dest)) {
                partial.delete()
                Logger.w(tag, "Could not move ${partial.name} into place")
                return false
            }
            return true
        } catch (e: Exception) {
            partial.delete()
            // The class rather than the message, and it takes both halves to be
            // redaction at all: the document id names the user's directory, and a
            // provider stream's own exception quotes the URI in its message, so
            // dropping the interpolation alone would put the same string in logcat by
            // the other route.
            Logger.w(tag, "Failed to copy into ${dest.name}: ${e.javaClass.simpleName}")
            return false
        }
    }

    /**
     * Whether the device document and the mirror hold the same bytes.
     *
     * The one question that can be answered without a clock, and the only one this
     * path needs. When the watcher has already carried an edit to the device, the two
     * agree in content while the record still describes what was there before, and
     * that mismatch is what froze the file: nothing else ever brings the bookkeeping
     * back into step.
     *
     * Reads the device document, so lengths are compared first: unequal lengths cannot
     * hold equal bytes, and skipping the read there is what keeps this off the common
     * path.
     *
     * ⚠️ That length is the provider's claim, not a measurement, and `COLUMN_SIZE` is
     * optional in the same way `COLUMN_LAST_MODIFIED` is. A provider omitting both
     * reports every length as 0, and this then answers false for every non-empty file,
     * so the fix does not apply there. It cannot answer a wrong yes, because the bytes
     * are compared afterwards, so the ceiling is "does not help", never a loss.
     *
     * A read that fails answers false. That is the safe direction: false leaves the
     * mirror kept and unrecorded, which is what happens today, while a true this could
     * not justify would vouch for bytes nobody compared.
     */
    /**
     * Whether the device document is exactly what this app's own interrupted
     * write-back leaves behind: a STRICT PREFIX of the mirror.
     *
     * The upload journal records that a write started and did not finish. It does
     * not record that nothing touched the document afterwards, and the two are
     * only the same thing if the app was the last writer. `"wt"` truncates at
     * open and the copy runs forward from zero, so a write cut short leaves the
     * document holding the mirror's leading bytes and nothing else. Any other
     * content, longer, shorter with different bytes, or the same length, was
     * written by something that is not that interrupted write, and the caller
     * must preserve it rather than overwrite it.
     *
     * Strict, because equal length is [deviceMatchesMirror]'s question and means
     * the write finished after all.
     *
     * One provider read, the same one [setAsideDeviceCopy] would pay, and it
     * spends it on the question that decides whether a copy is worth keeping
     * rather than on the copy itself. Answering false on any failure is the safe
     * direction: the caller then preserves the document before replacing it.
     */
    private fun deviceIsTruncatedMirror(safDocUri: Uri, localFile: File, deviceSize: Long): Boolean {
        if (deviceSize <= 0L || deviceSize >= localFile.length()) return false
        return try {
            context.contentResolver.openInputStream(safDocUri)?.use { device ->
                localFile.inputStream().use { mirror ->
                    val a = ByteArray(COPY_BUFFER_SIZE)
                    val b = ByteArray(COPY_BUFFER_SIZE)
                    var compared = 0L
                    while (true) {
                        val read = device.readNBytes(a, 0, a.size)
                        if (read == 0) return@use compared == deviceSize
                        // The mirror is the longer side, so a short read from it
                        // means the two disagree about their own lengths and the
                        // prefix cannot hold.
                        if (mirror.readNBytes(b, 0, read) != read) return@use false
                        if (!java.util.Arrays.equals(a, 0, read, b, 0, read)) return@use false
                        compared += read
                        if (compared > deviceSize) return@use false
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
            } ?: false
        } catch (e: Exception) {
            Logger.w(
                tag,
                "Could not compare ${localFile.name} with its device copy: " +
                    e.javaClass.simpleName,
            )
            false
        }
    }

    private fun deviceMatchesMirror(safDocUri: Uri, localFile: File, deviceSize: Long): Boolean {
        if (deviceSize != localFile.length()) return false
        return try {
            context.contentResolver.openInputStream(safDocUri)?.use { device ->
                localFile.inputStream().use { mirror ->
                    val a = ByteArray(COPY_BUFFER_SIZE)
                    val b = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = device.readNBytes(a, 0, a.size)
                        val same = mirror.readNBytes(b, 0, b.size)
                        if (read != same) return@use false
                        if (read == 0) return@use true
                        // Only the bytes this chunk filled. Comparing the whole buffer
                        // would drag in the previous, longer chunk's tail.
                        if (!java.util.Arrays.equals(a, 0, read, b, 0, read)) return@use false
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
            } ?: false
        } catch (e: Exception) {
            Logger.w(
                tag,
                "Could not compare ${localFile.name} with its device copy: " +
                    e.javaClass.simpleName,
            )
            false
        }
    }

    /**
     * Writes a local file's contents back to its corresponding SAF document.
     *
     * Declines outright when another write already holds the document, so two streams
     * never interleave into one file: see [documentWritesInFlight]. Declines a symbolic
     * link outright too, for the reason spelled out at that test.
     *
     * The copy itself runs in [writeLocalToSafHoldingDocument], bracketed by the
     * in-flight journal, and the bracket is the whole point of that method's shape:
     * `"wt"` truncates the document at open, and the provider stamps a fresh
     * modification time on the shorter bytes, so a copy
     * that dies partway leaves the device holding a *newer* copy of a file whose
     * only complete version is the mirror's. That is indistinguishable, at the
     * next sync, from an edit made on the device, and the sync would copy the
     * truncation over the edit. The journal line is written before the stream
     * opens and removed only by a copy that ran to its end, so the next sync can
     * tell the two apart by the one fact it can know: whether this app was
     * mid-upload when the copy stopped.
     *
     * One retry, because a first attempt that fails has already paid the
     * truncation, and a second full write is cheaper than a session of a stale
     * device copy. Permission loss is not retried: nothing about a revoked grant
     * gets better within a second.
     */
    private fun writeLocalToSaf(localFile: File, safDocUri: Uri) {
        // A symbolic link is never written out, which is the same refusal [uploadPlan]
        // and [watchableDirectories] make, in the same words and for the same reason: a
        // mirror is routinely a checked-out repository, so a link inside one is
        // attacker-supplied in the ordinary case. Every stream below reads *through* the
        // link, so without this a link named `notes.txt` puts its target's bytes into the
        // device document of that name, and the target can be anywhere this app can read,
        // its own private storage included.
        //
        // Here rather than at the four call sites, because this is the one point all four
        // pass through and two of them had no link test anywhere: a CREATE or MODIFY the
        // observer reports for a single entry never goes near [uploadPlan], which is the
        // only place the tree stated this policy. A DELETE does not come through here,
        // which is what it needs: by then the entry is gone, the link question has no
        // answer, and the device document still has to be removed.
        if (isLink(localFile)) {
            Logger.w(
                tag,
                "Not writing ${localFile.name} back: it is a symbolic link, and writing " +
                    "it would copy its target's bytes into the device folder",
            )
            return
        }
        val document = safDocUri.toString()
        if (!documentWritesInFlight.add(document)) {
            // Someone is streaming into this document now. Declining is what keeps the
            // two from interleaving, and it costs nothing the winner is not already
            // writing.
            Logger.d(tag, "Another write holds ${localFile.name}; leaving it to that one")
            return
        }
        try {
            writeLocalToSafHoldingDocument(localFile, safDocUri)
        } finally {
            documentWritesInFlight.remove(document)
        }
    }

    /** The copy itself, with the document already claimed by the caller. */
    private fun writeLocalToSafHoldingDocument(localFile: File, safDocUri: Uri) {
        val claim = markUploadInFlight(localFile.absolutePath)
        // Released in a finally rather than at each exit, and this is not style.
        // The claim taken above was originally dropped at the one exit its author
        // had in mind, and the failure exits added later each returned without it,
        // so the count never came back to zero for the life of the process. From
        // then on [clearUploadInFlight] saw a writer that no longer existed and
        // kept the journal line after a write-back that had landed, and
        // [consumeStaleUploadRecord] refused to consume it, so every later
        // initialSync force-pushed the mirror over whatever the device held. A
        // device edit made in between was destroyed, silently, on every reopen.
        //
        // The distinction the two paths draw is what the journal means, and it is
        // the reason there are two calls rather than one. The line records an edit
        // that has not reached the device, so a landed write retires it and a
        // failed one leaves it standing for the next sync to retry. The one thing
        // that leaves nothing to retry is the mirror file going away while this
        // write ran, and the failing path retires the line for that alone; see
        // [releaseUploadClaim].
        var landed = false
        try {
            var attempts = 0
            while (true) {
                try {
                    val copied = context.contentResolver.openOutputStream(safDocUri, "wt")
                        ?.use { output ->
                            localFile.inputStream().use { input ->
                                input.copyTo(output, COPY_BUFFER_SIZE)
                            }
                            true
                        } ?: false
                    if (copied) {
                        landed = true
                        return
                    }
                    attempts++
                } catch (e: SecurityException) {
                    Logger.e(tag, "Permission revoked while writing back: ${localFile.name}")
                    onWriteBackFailed(localFile)
                    return
                } catch (e: Exception) {
                    attempts++
                }
                if (attempts >= 2) {
                    Logger.e(
                        tag,
                        "Write-back of ${localFile.name} did not finish; its device copy " +
                            "is recorded as not to be trusted"
                    )
                    onWriteBackFailed(localFile)
                    return
                }
            }
        } finally {
            // By the claim rather than by the path this opened with: a rename of the
            // directory above it moves both records while the stream runs.
            if (landed) clearUploadInFlight(claim)
            else releaseUploadClaim(claim)
        }
    }

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
     * Three callers reach this, and the mirror is protected on all three, by two gates
     * rather than the one this comment used to name. The two exits inside
     * [writeLocalToSaf] leave the file's journal entry in place, which
     * `SafStorageManager.mayReclaim` reads. [refuseUnreadDocument] writes no journal
     * entry at all, because it declines before the write begins; what covers it is the
     * second gate, [holdsOnlyVouchedCopies], which refuses to reclaim a mirror holding
     * any file the `.synced` record cannot vouch for, and a file that was never written
     * back never is.
     *
     * What was missing is the user knowing, and that is a different fact from the data
     * being safe. A save that never reached the device folder looks exactly like one
     * that did, and the difference only shows when the app is uninstalled and the work
     * is gone.
     */
    internal var onWriteBackFailed: (File) -> Unit = {}

    /**
     * Told, once per [initialSync], when documents the device holds did not reach the
     * mirror: how many, and whether the sizes reported for them did not fit in the room
     * that was left.
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
     */
    internal var onUploadIncomplete: (File, Int, Boolean) -> Unit = { _, _, _ -> }

    /**
     * Told when something deleted in the editor was left standing on the device,
     * because it holds content this sync never copied in: a directory holding such a
     * document, or the document itself.
     *
     * Its own seam for the reason [onDocumentsNotCopied] has one: [onWriteBackFailed]
     * says the app holds the only copy, and here the opposite is true. The mirror copy
     * is gone, the device holds the only one, and a notice worded the other way would
     * send the user looking inside the app for files that are safe where they are.
     *
     * The flag is what the sentence turns on and it cannot be recovered downstream:
     * the entry is already unlinked when the event arrives, so nothing left on disk
     * can be asked whether it was a directory. "It holds files that never reached the
     * editor" is the right sentence for one case and false for the other.
     */
    internal var onKeptOnDevice: (file: File, isDirectory: Boolean) -> Unit = { _, _ -> }

    /**
     * How much room is left where the mirror lives.
     *
     * A seam because no JVM test can make a real filesystem answer that it is full, and
     * the pre-flight that reads this is exactly the code that has to be right when it is.
     *
     * `usableSpace` rather than `StorageManager.getAllocatableBytes`, which lint asks for
     * and which reports more, because it counts cache Android is willing to evict. The
     * larger figure is the right one for deciding whether to attempt a write; this decides
     * nothing of the kind. It runs after a copy has already failed and only picks which
     * sentence explains it, so the question is not "could this have fit" but "was the disk
     * visibly full when it did not", and free space is what answers that one.
     */
    @SuppressLint("UsableSpace")
    internal var usableSpaceOf: (File) -> Long = { it.usableSpace }

    /**
     * Declines to write [localFile] out, because the device holds a document under that
     * name this sync never read, and says so once per file.
     *
     * Both file-level refusal sites come through here, and the message living in one
     * place is what keeps them saying the same thing: [createOneInSaf] asks the same
     * question while
     * walking into a directory, and no JVM test can reach it, because delivering a
     * directory event builds a `FileObserver` and its static initializer needs native
     * code. One of them is the only thing holding the two together.
     *
     * The notice is [onWriteBackFailed]'s and it fits: the file did not reach the device
     * folder, and the copy inside the app is the only one of it that exists. What the
     * device holds under that name is a different document, which is why this refuses.
     * A refused delete does not come through here, because for it the opposite is
     * true; see [keepUnread].
     */
    private fun refuseUnreadDocument(localFile: File, describedAs: String) {
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
    private fun announceLost(localFile: File) {
        if (refusalsAnnounced.add(localFile.absolutePath)) onWriteBackFailed(localFile)
    }

    /**
     * Declines to delete what the device holds at [relativePath], because it is, or it
     * holds, a document this sync never read, and says so.
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
    private fun keepUnread(localFile: File, relativePath: String, isDirectory: Boolean) {
        Logger.w(
            tag,
            "Not deleting $relativePath from the device: it holds content this sync " +
                "never read",
        )
        onKeptOnDevice(localFile, isDirectory)
    }

    private fun uploadJournal(): File = File(context.filesDir, UPLOADS_IN_FLIGHT_FILE)

    /**
     * Takes this writer's claim on [absolutePath] and records that a write began.
     *
     * The claim is handed back rather than looked up again at the end, because the path
     * it names can move while the stream runs; see [UploadClaim].
     */
    private fun markUploadInFlight(absolutePath: String): UploadClaim =
        synchronized(uploadJournalLock) {
            val claim = UploadClaim(absolutePath)
            uploadClaims.add(claim)
            try {
                val journal = uploadJournal()
                val lines = if (journal.isFile) journal.readLines() else emptyList()
                if (absolutePath !in lines) {
                    rewriteJournal(lines + absolutePath)
                }
            } catch (e: Exception) {
                Logger.w(tag, "Could not record the upload in flight: ${e.message}")
            }
            claim
        }

    /**
     * Drops this writer's claim, and with it the line only when the mirror file it names
     * went away while the write ran.
     *
     * The counterpart to [clearUploadInFlight], and deliberately not the same
     * function. Both say "this writer is finished"; only one of them says "and the
     * device copy is current now". A failed write-back has to say the first without
     * the second, or the mirror edit it could not deliver is forgotten and the next
     * sync has no reason to retry it.
     *
     * The one line it does take is the one with nothing left to retry. Two retirements
     * defer to a live claim rather than take it, [consumeStaleUploadRecord] for a delete
     * or rename of the file itself and [consumeStaleUploadsUnder] for a directory that
     * left the mirror, and each of them was the last pass that could reach that line
     * while the file was gone. Deferring to a writer that then fails here left it
     * standing, and a standing line is not inert: `SafStorageManager.mayReclaim` reads it
     * as the mirror holding a write the device never received, so the launch pass keeps
     * that mirror and the device-folder screen reports it as holding work the device
     * folder does not have. So the test here is the one [consumeStaleUploadsUnder]
     * applies, the file being there, asked at the moment this writer is the only party
     * that knows the line is now unowned.
     *
     * How long that used to last is worth stating exactly, because the two deferrals
     * differ and only one of them ever healed. A directory departure nobody claimed is
     * never propagated to the device ([handleMirrorEvent] holds it back), so the device
     * still holds the document: the next open of the folder copies it back into the
     * mirror, and the open after that reaches [initialSync]'s repair and consumes the
     * line. That is two whole reopens with the mirror mislabelled in between, and no
     * repair at all where the copy cannot come back, which is a folder never opened
     * again, a document past [MAX_FILE_SIZE], one outside the enumeration cap, or one
     * whose stale old-named copy the user removed on the device first. A delete or rename
     * of the file itself has no such second chance: that one IS propagated, so the device
     * document goes with it and nothing can ever copy it back down.
     *
     * The other two branches are unchanged and both matter: a line whose file is still in
     * the mirror stands, because that is the edit the next sync retries, and a line
     * another writer holds a claim on is left to that writer's own release, for the
     * reason [clearUploadInFlight] gives.
     */
    private fun releaseUploadClaim(claim: UploadClaim) = synchronized(uploadJournalLock) {
        uploadClaims.remove(claim)
        if (!File(claim.path).exists() && uploadClaims.none { it.path == claim.path }) {
            removeJournalLine(claim.path)
        }
    }

    /**
     * Releases [claim] and removes the journal line only when no other writer holds the
     * path it names now.
     *
     * Paired with [markUploadInFlight] and called by writers alone. A second writer of
     * the same path is reachable whenever a folder is reopened while a drain of the
     * previous session is still streaming: the mirror is named by a hash of the folder,
     * so both writers address one file, and before the claims the first to finish removed
     * the line the other was still inside.
     *
     * The line is named by the claim rather than by the path the writer opened, which is
     * what makes a write that lands during a rename of the directory above it retire its
     * own line: [renameUploadsUnder] moved both records while this one streamed.
     */
    private fun clearUploadInFlight(claim: UploadClaim) = synchronized(uploadJournalLock) {
        uploadClaims.remove(claim)
        if (uploadClaims.none { it.path == claim.path }) removeJournalLine(claim.path)
    }

    /**
     * Drops a journal line whose reason to exist has gone, without touching any claim.
     *
     * Two callers, and neither of them is a writer. [initialSync] consumes a record left
     * by a process that died, for a path it is about to write itself. [processWriteBack]
     * consumes one when the mirror file the line names has been deleted, which is where
     * a file rename arrives too: the line records an edit that never reached the device,
     * and once the file holding that edit is gone there is no edit left to protect.
     *
     * Not the same operation as [clearUploadInFlight] and deliberately not spelled as
     * one. Treating either of those as releasing a claim would
     * drop a live writer's claim and remove the line while that writer was still
     * streaming, which is the defect the claims exist to prevent, arriving through a
     * different door. A path somebody is writing right now is left alone, and that
     * writer's own release retires the line: [clearUploadInFlight] when the write lands,
     * and [releaseUploadClaim] when it fails onto a path the file has left, which is the
     * state the [processWriteBack] caller defers in. The [initialSync] caller defers in
     * the other one, where the file is there and about to be written out again, so the
     * line it leaves is retired by that write landing.
     */
    private fun consumeStaleUploadRecord(absolutePath: String) = synchronized(uploadJournalLock) {
        if (uploadClaims.any { it.path == absolutePath }) return@synchronized
        removeJournalLine(absolutePath)
    }

    /** Caller must hold [uploadJournalLock]. */
    private fun removeJournalLine(absolutePath: String) {
        try {
            val journal = uploadJournal()
            if (journal.isFile) {
                val all = journal.readLines()
                val kept = all.filter { it != absolutePath }
                if (kept.size != all.size) rewriteJournal(kept)
            }
        } catch (e: Exception) {
            Logger.w(tag, "Could not clear the upload record: ${e.message}")
        }
    }

    /**
     * Moves the upload records of [from] to [to], so they follow a directory that has
     * moved.
     *
     * Two callers, and the same one fact makes both necessary: the records are keyed by
     * absolute mirror path, so a path that moves takes its meaning with it. A removal
     * renames a whole mirror out of the way; a rename in the editor moves one directory
     * inside it, which nothing else in the write-back carries across, because a claimed
     * rename deliberately re-uploads nothing.
     *
     * The journal is keyed by absolute path, and a mirror's directory name is a hash
     * of the tree URI rather than of the session, so the path a removal frees is the
     * path the same folder gets again when the user re-grants it. Without this, the
     * records of the departing mirror and of its replacement are the same strings, and
     * whichever side is cleared takes the other's with it: retiring them loses the live
     * mirror's distrust, and keeping them applies a dead mirror's distrust to a live
     * one. Both end the same way, with a stale copy written over the user's file.
     *
     * Renaming them at the moment the directory moves is what keeps the two apart,
     * because that is the last moment anything can still tell them apart. It survives a
     * process death for the same reason the rename does: both are on disk before
     * anything else happens.
     *
     * The live writers' claims move with the lines, in the same critical section, because
     * they are two records of one write and this runs on a thread of its own: a directory
     * can be renamed in the editor while a write below it is still streaming. Moving only
     * the line would leave that write releasing a name nothing is filed under, so a write
     * that landed would be recorded for ever as one that never reached the device.
     *
     * Which is why the claims move *after* the lines, and not at all when moving the
     * lines threw. The journal half is best effort by design, and it fails in exactly the
     * state that fills the journal in the first place: [rewriteJournal] writes a temporary
     * file in `filesDir`, so internal storage that has run out refuses it. A claim that
     * moved on its own is the same defect from the other side, and permanent rather than
     * retried: the writer would retire a line nobody ever wrote and leave the real one
     * standing under a path that has gone, which nothing can consume afterwards
     * ([consumeStaleUploadsUnder] never sees a claimed departure, and [initialSync]'s
     * repair needs the file the line names to be there), so `SafStorageManager.mayReclaim`
     * would refuse that mirror for a save that did reach the device. Both records staying
     * at the old name is the recoverable half of the pair: the writer's release still
     * names the line it is filed under.
     */
    internal fun renameUploadsUnder(from: File, to: File) {
        synchronized(uploadJournalLock) {
            val old = from.absolutePath + File.separator
            val new = to.absolutePath + File.separator
            try {
                val journal = uploadJournal()
                // A journal that is not there, and a rewrite with nothing to move, are
                // both moves that succeeded: there is no line to disagree with the claim.
                // A writer whose own record could not be written is exactly that case,
                // and its claim still has to follow the file.
                if (journal.isFile) {
                    val all = journal.readLines()
                    val moved =
                        all.map { if (it.startsWith(old)) new + it.removePrefix(old) else it }
                    if (moved != all) rewriteJournal(moved)
                }
            } catch (e: Exception) {
                Logger.w(tag, "Could not move the upload records of ${from.name}: ${e.message}")
                return
            }
            uploadClaims.forEach {
                if (it.path.startsWith(old)) it.path = new + it.path.removePrefix(old)
            }
        }
    }

    /**
     * Moves the unread-document memory under [from] to [to], for the same rename.
     *
     * The provider move that a claimed rename becomes carries the documents this sync
     * never read to the new name, and the guards that keep them safe look them up by
     * mirror path: [handleMirrorEvent]'s directory guard by prefix, and the file guard
     * by the exact name. Left at the old path, both stopped answering the moment the
     * rename landed, so `mv docs archive` followed by `rm -r archive` sent
     * `deleteDocument` for a directory the set knew held an unread archive.
     *
     * Not under [uploadJournalLock]: the set is its own concurrent structure, nothing
     * reads it and the journal together, and the entries are replaced one by one, so a
     * reader on the observer thread sees each path at the old name or the new one and
     * never at neither.
     */
    private fun renameUnfetchedUnder(from: File, to: File) {
        val old = from.absolutePath + File.separator
        val new = to.absolutePath + File.separator
        for (path in unfetched.filter { it.startsWith(old) }) {
            unfetched.add(new + path.removePrefix(old))
            unfetched.remove(path)
        }
    }

    /**
     * Retires the journal lines under [directory] whose mirror file is no longer there.
     *
     * The directory-shaped counterpart to [consumeStaleUploadRecord], and it exists
     * because the delete that retires a line never arrives for the files under a
     * directory that leaves the mirror. [handleMirrorEvent] holds that departure back for
     * a rename to claim rather than queueing anything, inotify raises no event at all for
     * the entries beneath a directory that moved, and [consumeStaleUploadRecord] matches
     * one exact path, so it would not reach them even if it ran. Nothing else could:
     * [initialSync]'s call is guarded by the file still existing, and [clearUploadsUnder]
     * runs only once the whole mirror is going away. One write-back that gave up followed
     * by a directory move no rename claimed therefore left a line standing for the life of
     * the install, and a stranded line is not inert: `SafStorageManager.mayReclaim` reads
     * any line under a mirror as that mirror holding a write the device never received, so
     * the launch pass kept it and the device-folder screen described it as holding work
     * the device folder does not have.
     *
     * A line whose file is still there is left alone, and that is the whole distinction:
     * something is at that path again, a directory recreated under the same name or a
     * checkout that restored it, and the line still records that the mirror holds an edit
     * the device never received. So is a line some writer holds a claim on, for the reason
     * [consumeStaleUploadRecord] gives, and taking it here is the defect [UploadClaim]
     * exists to prevent. That deferral is not a hand-off into nothing: the writer applies
     * this same test when it releases, so a write that fails onto the path this pass
     * skipped retires the line itself ([releaseUploadClaim]), and one that lands retires
     * it through [clearUploadInFlight].
     */
    private fun consumeStaleUploadsUnder(directory: File) {
        synchronized(uploadJournalLock) {
            try {
                val journal = uploadJournal()
                if (!journal.isFile) return
                val prefix = directory.absolutePath + File.separator
                val all = journal.readLines()
                val kept = all.filter { line ->
                    !line.startsWith(prefix) || File(line).exists() ||
                        uploadClaims.any { it.path == line }
                }
                if (kept.size != all.size) rewriteJournal(kept)
            } catch (e: Exception) {
                Logger.w(
                    tag,
                    "Could not retire the upload records under ${directory.name}: ${e.message}"
                )
            }
        }
    }

    /**
     * Drops every vanished directory [predicate] answers for, and retires the upload
     * records the files beneath them left behind.
     *
     * One function for all three removals of an *unclaimed* departure, so that the
     * retirement cannot be forgotten at one of them. The fourth removal is deliberately
     * not here: [claimVanishedDirectory] takes an entry precisely because a rename is
     * about to move its records to the new path, which [renameUploadsUnder] does.
     *
     * The journal is touched outside [vanishedLock], because it has a lock of its own
     * and this is the only path that would otherwise hold both.
     */
    private fun dropVanished(predicate: (VanishedDirectory) -> Boolean) {
        val gone = synchronized(vanishedLock) {
            val taken = vanishedDirectories.filter(predicate)
            vanishedDirectories.removeAll(predicate)
            taken
        }
        gone.forEach { consumeStaleUploadsUnder(File(it.localPath)) }
    }

    /**
     * Drops every journal entry under [mirrorRoot], for when the mirror itself is
     * going away.
     *
     * An entry outlives its mirror otherwise, and the damage is delayed rather
     * than avoided: the folder's permission lapses, the mirror is reclaimed with
     * the entry still recorded, and a re-grant weeks later hashes back to the same
     * path. The first sync after it copies the device down with the mirror absent,
     * so the entry is not consumed; an edit made on the device after that is then
     * read back as this app's own interrupted upload and thrown away in favour of
     * the stale copy. Reclaiming the mirror has to reclaim its distrust with it.
     */
    internal fun clearUploadsUnder(mirrorRoot: File) {
        synchronized(uploadJournalLock) {
            try {
                val journal = uploadJournal()
                if (!journal.isFile) return
                val prefix = mirrorRoot.absolutePath + File.separator
                val all = journal.readLines()
                val kept = all.filterNot { it.startsWith(prefix) }
                if (kept.size != all.size) rewriteJournal(kept)
            } catch (e: Exception) {
                Logger.w(tag, "Could not clear the upload records under ${mirrorRoot.name}: ${e.message}")
            }
        }
    }

    internal fun uploadsInFlight(): Set<String> = synchronized(uploadJournalLock) {
        try {
            val journal = uploadJournal()
            if (journal.isFile) journal.readLines().filter { it.isNotBlank() }.toSet()
            else emptySet()
        } catch (e: Exception) {
            // Said rather than swallowed: an unreadable journal silently disables
            // the whole bracket, and that is worth a log line when it happens.
            Logger.w(tag, "Could not read the upload journal: ${e.message}")
            emptySet()
        }
    }

    /**
     * Replaces the journal in one step, or leaves the previous content exactly
     * where it was.
     *
     * Caller must hold [uploadJournalLock]. The rename is the atomic half; a
     * truncate-and-write here would lose every bracket at once to a process death
     * in the middle, which is the one failure this file exists to remember across.
     */
    private fun rewriteJournal(lines: List<String>) {
        val journal = uploadJournal()
        if (lines.isEmpty()) {
            journal.delete()
            return
        }
        val tmp = File(journal.parentFile, journal.name + PARTIAL_SUFFIX)
        tmp.writeText(lines.joinToString("\n"))
        if (!tmp.renameTo(journal)) {
            journal.writeText(lines.joinToString("\n"))
            tmp.delete()
        }
    }

    /**
     * Puts a locally created file into the SAF tree, writing into the document that
     * already carries that name rather than adding a second one.
     *
     * [DocumentsContract.createDocument] does not merge: handed a name the folder
     * already has, a provider invents "notes (1).txt" and the user's file quietly
     * forks. Two ordinary things arrive here as a create: the rename
     * [copyDocumentToLocal] performs to move a finished copy into place, and any local
     * rename over an existing name, which is what `mv` and a git checkout do. Resolving
     * first is also what makes the MODIFY-on-unknown fallback in [processWriteBack]
     * safe to keep.
     */
    private fun createInSaf(localFile: File, parentSafUri: Uri, treeUri: Uri) {
        val docUri = createOneInSaf(localFile, parentSafUri, treeUri)
        if (docUri == null) {
            // The provider refused, and the file exists only inside the app. Exactly
            // what onWriteBackFailed already means, so it says it rather than a second
            // wording of the same fact. Until this line the caller returned on the null
            // and the user was never told: a file they created in the editor simply was
            // not on the device, and the difference only shows after an uninstall.
            announceLost(localFile)
            return
        }
        if (localFile.isDirectory) {
            // A directory that just appeared brings whatever is inside it; without
            // this it landed on the device empty and its files stayed in the mirror.
            // What it cannot do is stand in for a copy that already exists on the
            // device: it stops at [MAX_UPLOAD_ENTRIES] and excludes whatever
            // [SKIP_DIRECTORIES] kept out of the mirror. That is why a rename does not
            // come through here at all when [handleMirrorEvent] can pair its two halves
            // it renames the device's document instead, and why the old name is
            // never deleted when it cannot.
            createChildrenInSaf(localFile, docUri, treeUri)
        }
    }

    /**
     * Creates or opens the document for [localFile] under [parentSafUri], writing its
     * bytes if it is a file, and returns the document's URI.
     *
     * Does not descend. [createInSaf] owns that, so that the recursion lives in one
     * place and this stays the single-entry operation both it and
     * [createChildrenInSaf] need.
     */
    private fun createOneInSaf(localFile: File, parentSafUri: Uri, treeUri: Uri): Uri? {
        return try {
            val parentDocId = DocumentsContract.getDocumentId(parentSafUri)
            val existingDocId = findChildDocId(treeUri, parentDocId, localFile.name)

            val docUri = if (existingDocId != null) {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDocId)
            } else {
                val mimeType = if (localFile.isDirectory) {
                    DocumentsContract.Document.MIME_TYPE_DIR
                } else {
                    CREATED_FILE_MIME_TYPE
                }
                DocumentsContract.createDocument(
                    context.contentResolver, parentSafUri, mimeType, localFile.name
                )?.also { warnIfNameNotHonoured(it, localFile.name) }
            } ?: return null

            if (localFile.isFile) {
                // The same question [handleMirrorEvent] asks before queueing a job, asked
                // again here because this path does not come through it. A directory
                // create, or a move whose other half never arrived, walks the tree
                // through [createChildrenInSaf] and reaches every child directly.
                //
                // `existingDocId != null` IS the provider answer for this child, already
                // paid for above, so unlike the event path this costs no extra binder
                // call. A file this sync could not read, whose document still exists on
                // the device, would otherwise be written back as whatever placeholder the
                // mirror holds -- truncating a document nothing here has ever seen.
                if (writeWouldReplaceUnreadDocument(
                        localFile.absolutePath, existingDocId != null, unfetched,
                    )
                ) {
                    refuseUnreadDocument(localFile, localFile.name)
                } else {
                    writeLocalToSaf(localFile, docUri)
                }
            }
            // Cache the document ID for future write-back lookups, but only while the
            // cache speaks for this job's tree and knows the parent. A miss is not "the
            // parent is the root" -- initialSync puts the root in under the empty key,
            // so the root is found. The label test is what a drain outliving its own
            // lifecycle needs: the cache then belongs to the next folder, and writing
            // the entry anyway would file a document from the old folder under a
            // plausible name in the new one, so the next write-back for that name would
            // land in the wrong folder entirely. Where two granted folders nest, a
            // document id can be the same in both trees, which is why the label is
            // tested rather than the reverse lookup trusted to miss.
            val parentRelPath = synchronized(docIdCache) {
                if (cacheTree == treeUri) {
                    docIdCache.entries.firstOrNull { it.value == parentDocId }?.key
                } else {
                    null
                }
            }
            if (parentRelPath != null) {
                val relPath = if (parentRelPath.isEmpty()) localFile.name
                    else "$parentRelPath/${localFile.name}"
                cacheDocId(treeUri, relPath, DocumentsContract.getDocumentId(docUri))
            }
            docUri
        } catch (e: Exception) {
            // The class rather than the message, for the reason [copyDocumentToLocal]
            // spells out: a provider quotes the document URI in its own exception, and
            // the platform provider builds that URI out of the user's own directory path.
            Logger.w(tag, "Failed to create ${localFile.name} in SAF: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Says so when the provider stored a document under a name other than the one it was
     * asked for.
     *
     * The name is the only thing tying a mirror file to its device document: every lookup
     * here is [findChildDocId] by display name, and the cache below is keyed by the
     * mirror's own path. A provider that renames on create therefore leaves the device
     * holding the bytes under a name the next [initialSync] reads as a second file, so the
     * editor shows both and the next edit of the original creates a third.
     *
     * Said and not repaired, and both halves are deliberate. What made this happen was the
     * type this app asked for, which [CREATED_FILE_MIME_TYPE] settles for every extension.
     * What is left is a provider normalising the name itself, as the platform one does for
     * characters FAT cannot hold, and a rename back would be handed to the same
     * normaliser; deleting the document it did make would take the user's bytes off the
     * device to tidy up a name, which is the wrong way round. So the bytes stay where the
     * provider put them and this is the line that explains the duplicate in a bug report.
     *
     * A provider that will not answer for the document it just made proves nothing either
     * way, and this stays quiet: the create itself succeeded.
     */
    private fun warnIfNameNotHonoured(docUri: Uri, requestedName: String) {
        val stored = try {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        } catch (e: Exception) {
            Logger.d(
                tag,
                "Could not read back the name of $requestedName: ${e.javaClass.simpleName}",
            )
            null
        }
        if (stored != null && stored != requestedName) {
            Logger.w(
                tag,
                "The device folder stored $requestedName as $stored; the editor will show " +
                    "both the next time the folder is opened",
            )
        }
    }

    /**
     * Creates everything under [localDir] on the device, beneath [dirSafUri].
     *
     * Iterative rather than recursive through [createInSaf], and that is deliberate:
     * [uploadPlan] already returns parents before children, so each entry's
     * parent document exists by the time it is reached, and the URI of every
     * directory created along the way is remembered here. Recursing instead would
     * re-query the provider for a parent it had just made.
     *
     * A child whose parent is missing from [created] is skipped rather than guessed
     * at. That happens when the parent's own creation failed, and inventing a place
     * to put the child would file it somewhere the user did not put it.
     */
    private fun createChildrenInSaf(localDir: File, dirSafUri: Uri, treeUri: Uri) {
        val plan = uploadPlan(localDir, MAX_UPLOAD_ENTRIES)
        val entries = plan.entries
        if (entries.isEmpty()) return

        val created = mutableMapOf(localDir.absolutePath to dirSafUri)
        var made = 0
        for (entry in entries) {
            // Split rather than chained: `parentFile` is a platform type, so the
            // chained form indexed the map with a String? and the compiler warned
            // about it. An entry with no parent cannot be placed anywhere.
            val parentPath = entry.parentFile?.absolutePath ?: continue
            val parentUri = created[parentPath] ?: continue
            createOneInSaf(entry, parentUri, treeUri)?.let { uri ->
                if (entry.isDirectory) created[entry.absolutePath] = uri
                made++
            }
        }
        // The walk's own answer, not `entries.size >= MAX_UPLOAD_ENTRIES`. That test
        // reported a directory holding exactly the cap as incompletely copied when every
        // entry of it had just been created, because the walk stops at the cap and so
        // can never return more than it.
        val capped = plan.truncated
        if (capped) {
            Logger.w(tag, "Stopped at $MAX_UPLOAD_ENTRIES entries under ${localDir.name}; " +
                "the rest were not copied to the device")
        }
        Logger.i(tag, "Created $made of ${entries.size} entries under ${localDir.name}")

        // One notice for the directory, not one per entry. `made` already counts both
        // ways an entry can be lost: a creation the provider refused, and a child
        // skipped because its own parent was never created. Reading the shortfall off
        // that count is what keeps a new silent branch from appearing here later, the
        // way one did when this reported only to the log.
        //
        // The comment above the cap used to say a log line was the only place that
        // could say so. That was true when it was written and stopped being true when
        // the notice channel arrived, which is the shape a reason comment rots in.
        val lost = entries.size - made
        if (lost > 0 || capped) onUploadIncomplete(localDir, lost, capped)
    }

    /**
     * Looks up a direct child of [parentDocId] by display name, from the provider.
     *
     * Deliberately not through [docIdCache]: both callers use the answer to choose
     * between writing into a document and making a new one, and the cache can still
     * name a document deleted since it was filled.
     */
    private fun findChildDocId(treeUri: Uri, parentDocId: String, name: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                )
                val nameIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == name) return cursor.getString(idIndex)
                }
            }
        } catch (e: Exception) {
            Logger.d(tag, "Lookup of $name under $parentDocId failed: ${e.message}")
        }
        return null
    }

    /**
     * Gives an existing device document a new display name, in place.
     *
     * The whole point is what it does *not* touch: the provider moves the document itself,
     * so everything beneath it travels along, including the `.git` and `node_modules` the
     * mirror never held and the files past [MAX_UPLOAD_ENTRIES] it could not have re-sent.
     * Nothing else here can move a subtree that the mirror does not fully contain.
     *
     * @return whether the device now holds the document under [newName]. False covers a
     *   provider that does not support renaming at all, one that refuses this document,
     *   and one that no longer has it.
     */
    private fun renameInSaf(safDocUri: Uri, newName: String): Boolean = try {
        DocumentsContract.renameDocument(context.contentResolver, safDocUri, newName) != null
    } catch (e: Exception) {
        Logger.w(tag, "Could not rename a document to $newName: ${e.javaClass.simpleName}")
        false
    }

    /**
     * Moves an existing device document into a different parent, in place.
     *
     * The counterpart to [renameInSaf] for the half of the problem a rename cannot
     * express. It carries the subtree for the same reason: the provider relocates the
     * document itself, so the `.git` and `node_modules` the mirror never held travel with
     * it. Without this, dragging `src/util` into `src/legacy/` left the old copy standing
     * on the device, and reopening the folder copied it back down beside the new one.
     *
     * `moveDocument` is a different call from `renameDocument` behind a different flag
     * (`FLAG_SUPPORTS_MOVE` rather than `FLAG_SUPPORTS_RENAME`), so a provider may offer
     * one and not the other. A null return puts the caller back on the pre-existing
     * fallback: build the new name from the mirror and leave the old copy alone.
     *
     * @return the document's URI in its new parent, or null when the provider would not
     *   move it.
     */
    private fun moveInSaf(safDocUri: Uri, sourceParentUri: Uri, targetParentUri: Uri): Uri? = try {
        DocumentsContract.moveDocument(
            context.contentResolver, safDocUri, sourceParentUri, targetParentUri
        )
    } catch (e: Exception) {
        Logger.w(tag, "Could not move a document between directories: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Deletes a document from the SAF tree.
     */
    private fun deleteFromSaf(safDocUri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, safDocUri)
        } catch (e: Exception) {
            Logger.w(tag, "Failed to delete from SAF: ${e.javaClass.simpleName}")
        }
    }

    // -- Internal: Write-back Processing --

    private fun processWriteBack(session: WatchSession, job: SyncJob) {
        // Small debounce: skip if more recent job for same path exists. Scoped to the
        // session the job came off, so it cannot see, or be blinded by, the jobs of a
        // folder that is closing or one that has just opened.
        if (session.queue.any { it.localPath == job.localPath && it.timestamp > job.timestamp }) {
            return
        }

        Logger.d(tag, "Write-back: ${job.type} ${job.localPath}")

        when (job.type) {
            SyncType.MODIFY -> {
                val localFile = File(job.localPath)
                if (!localFile.exists()) return
                if (job.safDocUri != null) {
                    writeLocalToSaf(localFile, job.safDocUri)
                } else if (job.safParentUri != null && job.safTreeUri != null) {
                    // FileObserver may report MODIFY instead of CREATE for new files
                    // (e.g., `echo > file` on Android API 36). Fall through to CREATE.
                    Logger.d(tag, "MODIFY on unknown file, treating as CREATE: ${localFile.name}")
                    createInSaf(localFile, job.safParentUri, job.safTreeUri)
                }
            }
            SyncType.CREATE -> {
                val localFile = File(job.localPath)
                if (localFile.exists() && job.safParentUri != null && job.safTreeUri != null) {
                    createInSaf(localFile, job.safParentUri, job.safTreeUri)
                }
            }
            SyncType.DELETE -> {
                if (job.safDocUri != null) {
                    deleteFromSaf(job.safDocUri)
                }
                // The enqueue resolved that path to the document it was about to
                // remove, and the resolution is cached. Left behind, the entry
                // answers for a name the device no longer holds: the next event
                // on a recreated path writes into the dead document, and a rename
                // into that name is refused its pair. Nothing else removes these,
                // so the delete that strands them is the delete that clears them.
                if (job.relativePath.isNotEmpty()) {
                    forgetCachedSubtree(job.relativePath)
                }
                // And the upload record, for the same reason and with the same
                // reach. A journal line is keyed by the mirror path and is removed
                // only by a write of that path that lands, so a write-back that gave
                // up left one standing, deliberately, and nothing ever retired it
                // once the file it names went away. Since a file rename arrives here
                // as MOVED_FROM, one failed save followed by renaming the file in the
                // editor was enough to strand a line for ever, and a stranded line is
                // not inert: `SafStorageManager.mayReclaim` reads any line under a
                // mirror as "this mirror holds a write that never reached the device",
                // so the launch pass kept the mirror and the storage screen described
                // it as holding work the device does not, all on the strength of a file
                // that is not there. Not that the removal is refused: the bridge
                // extension sends `force` for exactly the rows this mislabels, so what
                // the user gets is a modal saying the copy holds files that exist
                // nowhere else and then the deletion they asked for.
                // The claims are what keep this off a path somebody is streaming
                // into right now; see [consumeStaleUploadRecord].
                consumeStaleUploadRecord(job.localPath)
            }
            SyncType.RENAME -> {
                // safDocUri is the document under the *old* name here, and localPath the
                // new one. Falling back to a create when the provider will not rename is
                // not a nicety: without it a provider lacking FLAG_SUPPORTS_RENAME would
                // leave the new name absent from the device entirely, which is worse than
                // the duplicate this job exists to avoid.
                //
                // A rename that succeeds does not re-upload anything, and it should not:
                // the watcher has been writing every change in that subtree back as it
                // happened, so re-sending it would be a file-by-file rewrite of a copy
                // that is already correct, 1500 provider writes for a renamed `src/`,
                // with every save queued behind them. What that rewrite would also have
                // done is push mirror files that never reached the device because their
                // directory fell past [MAX_WATCHED_DIRECTORIES] and no observer ever
                // reported them. Those stay mirror-only. They were mirror-only before the
                // rename too, this does not lose them, it just stops incidentally
                // rescuing them.
                //
                // A move to a different directory is a second call and has to come first:
                // `moveDocument` returns the document's URI in its new home, and renaming
                // before moving would rename in the old one and then move a URI this code
                // no longer holds. Where the parents match, `safSourceParentUri` is null
                // and nothing here changes.
                //
                // The destination parent is re-resolved when the event could not resolve
                // it, because "could not" is usually timing rather than absence:
                // `mkdir lib && mv util lib/` delivers the MOVED_TO while lib's own
                // create job is still ahead of this one in the queue, and the answer
                // from event time cannot see a document that lands a tick later.
                val localFile = File(job.localPath)
                val targetParentUri = job.safParentUri
                    ?: if (job.safSourceParentUri != null && job.relativePath.isNotEmpty()) {
                        // Only while the cache still belongs to this job's tree. A
                        // drain can outlive its folder, and a cache refilled for the
                        // next one answers a relative path with the wrong folder's
                        // documents; declining costs the documented stale-copy
                        // fallback, which the pre-existing behaviour was anyway.
                        job.safTreeUri?.takeIf { it == cacheTree }?.let {
                            resolveDocumentUri(it, File(job.relativePath).parent ?: "")
                        }
                    } else {
                        null
                    }
                val moved = when {
                    job.safDocUri != null && job.safSourceParentUri != null &&
                        targetParentUri != null ->
                        moveInSaf(job.safDocUri, job.safSourceParentUri, targetParentUri)
                    // A rename inside one directory has nothing to move. This arm is
                    // only that case: [handleMirrorEvent] does not offer a RENAME at
                    // all when the pair crossed directories and the source parent
                    // could not be resolved, because renaming in the parent the
                    // document is already in is not the move that was asked for.
                    job.safSourceParentUri == null -> job.safDocUri
                    // A cross-directory move that could not be attempted has not
                    // happened, whatever an unchanged name implies; reporting it
                    // done is what left the device holding the old parent forever.
                    else -> null
                }
                // Renaming is skipped when the name did not change, which is the common
                // shape of a move: dragging `src/util` into `src/legacy/` keeps the name.
                // Decided from the two paths rather than from the moved document's URI,
                // because a document ID is not required to be a path and reading a name
                // out of one is only right on the providers where it happens to be.
                val nameUnchanged = job.previousName == localFile.name
                val renamed = moved != null && (nameUnchanged || renameInSaf(moved, localFile.name))
                // Whether the document is now where the user put it, under whatever
                // name. The second arm of the `when` above hands back the document that
                // never moved, so a non-null [moved] on its own does not mean a move
                // happened; only the first arm calls `moveDocument`, and it is the arm
                // that requires a source parent.
                val relocated = moved != null && job.safSourceParentUri != null
                if (!renamed && !relocated && localFile.exists() &&
                    targetParentUri != null && job.safTreeUri != null
                ) {
                    createInSaf(localFile, targetParentUri, job.safTreeUri)
                } else if (!renamed && relocated) {
                    // The move landed and the rename did not, which a provider carrying
                    // FLAG_SUPPORTS_MOVE and not FLAG_SUPPORTS_RENAME produces. The
                    // create fallback is the wrong answer here and used to run anyway:
                    // the device would keep the whole moved subtree under its OLD name
                    // in the NEW parent and gain a second copy under the new name, built
                    // from the mirror, capped at [MAX_UPLOAD_ENTRIES] and missing
                    // everything [SKIP_DIRECTORIES] kept out. The fallback exists for
                    // the case where nothing moved at all, where the alternative is the
                    // new name being absent from the device entirely. That is not this
                    // case: the content is on the device, whole, in the right place, and
                    // wearing the old name.
                    Logger.w(
                        tag,
                        "Moved ${localFile.name} on the device but could not rename it; " +
                            "it is in the right folder under its previous name",
                    )
                }
            }
        }
    }

    // -- FileObserver --

    /**
     * Watches one directory and enqueues write-back jobs for what changes inside it.
     *
     * [path] arrives as inotify's own `name` field (the bare entry name within the
     * watched directory, not a path relative to the mirror root), so [dir] is what it
     * has to be resolved against. Covering the tree is [watchTree]'s job: one observer
     * per directory, because a watch descriptor covers a directory and not a tree.
     */
    private inner class DirectoryObserver(
        private val dir: File,
        private val rootDir: File,
        private val safTreeUri: Uri
    ) : FileObserver(dir, MODIFY or CREATE or DELETE or MOVED_FROM or MOVED_TO) {

        override fun onEvent(event: Int, path: String?) {
            if (path == null || !isWatching) return
            handleMirrorEvent(event, File(dir, path), rootDir, safTreeUri)
        }
    }

    /**
     * Turns one inotify event on [localFile] into the watch bookkeeping and the
     * write-back job it implies.
     *
     * On the engine rather than on [DirectoryObserver] so a test can drive it without
     * one: constructing a [FileObserver] runs a static initializer that reaches native
     * code a JVM test has no way to satisfy, and what happens to the device copy is
     * decided here.
     */
    internal fun handleMirrorEvent(event: Int, localFile: File, rootDir: File, safTreeUri: Uri) {
        // The session this event belongs to, read here rather than at the offer at the
        // end. Everything in between is provider round trips, a query per path segment
        // and several of them, and a stop can run to completion inside that window: it
        // installs a fresh session that nothing drains, since only [startWatching]
        // starts a worker and it opens with a stop that clears that queue again. A save
        // offered there went nowhere and said nothing. Read at entry, a late offer lands
        // in the queue the closing folder's drain is still emptying, which is what the
        // grace period exists for; if that drain has already finished, the offer below
        // says so, because the stop's own count cannot. That count is read and the queue
        // cleared in microseconds on an idle folder, while this event is still inside the
        // provider, so the one job it can see is one that was already queued.
        //
        // A capture rather than a liveness re-check, because the answer has to be the
        // same object for the whole event: two threads polling one queue is what the
        // session split exists to prevent, and this offers into a queue exactly one
        // worker was ever started for.
        val target = session
        // Nothing here touches the cache's label. An event whose tree the cache does not
        // speak for is one that was still inside its provider round trips when the
        // folder was switched, arriving after the next folder's sync has relabelled the
        // cache and is filling it; every resolution below then walks the provider and
        // caches nothing, which [cachedDocId] and [cacheDocId] decide per access. This
        // used to clear the cache and adopt it for the event's own tree, and the clear
        // and the sync's fill interleaved: the sync went on writing its entries under
        // the old folder's label, the event wrote the old folder's directories beside
        // them, and the new folder's phase 2b read one of those for a same-named path.
        // Where one granted folder sits inside another, that directory is writable, and
        // the write opens it with "wt", which truncates at open. A drain that outlived
        // its folder is unaffected either way: its one cache read is guarded by the same
        // label (see [processWriteBack]'s late-parent resolution).
        val relativePath = localFile.relativeTo(rootDir).path

        val type = when (event and FileObserver.ALL_EVENTS) {
            FileObserver.MODIFY -> SyncType.MODIFY
            FileObserver.CREATE, FileObserver.MOVED_TO -> SyncType.CREATE
            FileObserver.DELETE, FileObserver.MOVED_FROM -> SyncType.DELETE
            else -> return
        }

        // inotify reports this alongside the event type when the entry is a
        // directory, and for a delete it is the only way to know: there is nothing
        // left to stat by then.
        val isDirectory = (event and IN_ISDIR) != 0 || localFile.isDirectory

        // A directory arriving or leaving changes what has to be watched. Unwatching
        // is unconditional because it is a no-op for anything never watched.
        when (type) {
            SyncType.CREATE ->
                if (isDirectory && !shouldSkip(localFile.name, isDir = true)) {
                    watchTree(localFile, rootDir, safTreeUri)
                }
            SyncType.DELETE -> unwatchTree(localFile)
            else -> Unit
        }

        if (!shouldWriteBack(relativePath, isDirectory)) return

        // Placed here rather than in the write-back branches: this covers CREATE,
        // MODIFY and the arriving half of a move in one statement, and stops the job
        // being queued at all.
        //
        // A delete is excluded and handled below instead, and the exclusion is the
        // whole of why the type is read here at all. Both refusals are correct and
        // they are correct for opposite reasons: a refused write leaves the edit
        // inside the app, a refused delete leaves the document on the device and
        // nowhere else. Answering for a delete here said the first sentence about
        // the second situation, sending the user into the app after a file the app
        // had never held, and spent that path's one refusal notice on it, so the
        // save that came afterwards, the one that really did stay inside the app,
        // was silent for the rest of the session.
        //
        // The provider is asked rather than trusted from the set alone, because
        // the set is a memory of what this sync could not read and the document
        // may have been deleted on the device since. When it has, the local file
        // is an ordinary new file and uploads normally. The binder walk costs
        // something only for paths in the set, which is normally empty.
        //
        // The set is therefore tested twice, here and inside the predicate, and the
        // outer one is not a duplicate: it is what keeps [providerHoldsDocument] off
        // the common path. [createOneInSaf] needs no such guard, because there the
        // provider's answer is already in hand.
        if (type != SyncType.DELETE &&
            localFile.absolutePath in unfetched &&
            writeWouldReplaceUnreadDocument(
                localFile.absolutePath,
                providerHoldsDocument(safTreeUri, relativePath),
                unfetched,
            )
        ) {
            refuseUnreadDocument(localFile, relativePath)
            return
        }

        val now = System.currentTimeMillis()

        // A directory that moved away is one half of a rename, and the device holds the
        // only complete copy of it. Android does not expose inotify's cookie, so the
        // MOVED_FROM and the MOVED_TO cannot be paired by the kernel's own identifier and
        // this half arrives as a plain delete, which on a directory document is
        // `deleteDocument` taking everything beneath it on the device. What the other half
        // gives back is the mirror's copy, and the mirror is not all of it:
        // [createChildrenInSaf] stops at [MAX_UPLOAD_ENTRIES], and the mirror never held
        // the `.git` or `node_modules` that [SKIP_DIRECTORIES] kept out of it, so those go
        // and never return. Renaming a folder of 3000 files therefore left 1000 of them
        // nowhere but the mirror, which is reclaimed as soon as the folder's permission
        // lapses.
        //
        // So the delete is never propagated, it is held instead, for the MOVED_TO that a
        // rename sends a moment later. That one renames the device's *own* document into
        // the new name, which carries the subtree the mirror could not because the
        // provider moves it in place, and leaves nothing under the old name.
        //
        // When nothing claims it the record expires and the device keeps a stale copy
        // under the old name, and that copy is durable, not merely untidy: reopening the
        // folder copies it back down, so the old name reappears in the editor beside the
        // new one, and an unclaimed move leaves one more of them every time. Nothing
        // removes it on the user's behalf, and the Explorer's own Delete does not
        // either: measured on an API 37 emulator, the workbench deletes a folder by
        // renaming it into its temporary directory first, so what arrives here is
        // this MOVED_FROM, the pair never comes, and the device keeps the folder. An
        // outright DELETE for a directory comes from a real unlink, `rm -r` in the
        // terminal, `git clean`, a build script, and is acted on below unless the
        // directory holds documents this sync never read.
        if (isDirectory && (event and FileObserver.ALL_EVENTS) == FileObserver.MOVED_FROM) {
            dropVanished { now - it.atMillis > RENAME_PAIR_WINDOW_MS }
            synchronized(vanishedLock) {
                vanishedDirectories.add(
                    VanishedDirectory(relativePath, now, localFile.absolutePath)
                )
            }
            Logger.i(
                tag,
                "Directory moved away; keeping the device copy of $relativePath until a " +
                    "rename claims it"
            )
            return
        }

        // A delete the device holds the only copy of, at either level.
        //
        // On a directory document `deleteDocument` takes everything beneath it on the
        // device, and the mirror is not all of it: a child skipped for size, or one
        // whose copy failed, was never in the mirror, so the editor showed the
        // directory without it, the user deleted what they could see, and the device
        // lost a 60 MB archive nothing on screen had mentioned. A file arrives here for
        // the same reason one step down: what the user deleted in the editor was a
        // mirror entry that was never the document, and the document on the device is
        // the only copy of itself.
        //
        // Matched by prefix for a directory, since a directory holds what is under it
        // and [unfetched] holds files only, and by exact path for a file, since its own
        // path is the only one that names it. The separator is appended so `docs` does
        // not answer for `docs2`, and any depth below counts; on the file side
        // exactness is what stops an unread `notes.md.bak` from keeping `notes.md`.
        //
        // Below the MOVED_FROM hold rather than above it, deliberately. A directory
        // with unread children is renamed by the same event pair as any other, and
        // the hold is what turns the pair into one provider move, which is the one
        // path that carries those children to the new name. Refusing the MOVED_FROM
        // would have left the pair unclaimed: the new name created from the mirror
        // without them, the old one standing on the device with them. Only an
        // outright DELETE therefore reaches here as a directory. A file has no pair to
        // protect, so both halves of its delete, the unlink and the move away, reach
        // this and are kept the same way.
        //
        // The provider is asked for the reason the write guard asks it: the set is a
        // memory, and the document may have been deleted on the device since, in
        // which case there is nothing left to keep. Only a provider that positively
        // answers "gone" lets the delete through. The write guard folds a failed
        // lookup into "not held", and there that costs a duplicate name; here it would
        // cost `deleteDocument` on the one copy the set says nothing else has, so
        // a provider that cannot answer keeps. One scan of a set that is normally
        // empty, on the observer thread; the binder walk is paid only on a match.
        if (type == SyncType.DELETE) {
            val holdsUnread = if (isDirectory) {
                val below = localFile.absolutePath + File.separator
                unfetched.any { it.startsWith(below) }
            } else {
                localFile.absolutePath in unfetched
            }
            if (holdsUnread && providerHolds(safTreeUri, relativePath) != false) {
                keepUnread(localFile, relativePath, isDirectory)
                return
            }
        }

        // Resolve the SAF URI for this file via its relative path, except where
        // nothing reads it. A file arriving as CREATE (which is also where a file's
        // MOVED_TO lands) is the one shape with no reader: the claim gate below is
        // reached only by a directory, and [processWriteBack]'s CREATE arm takes the
        // parent and the tree and never the document. Resolving anyway is not free and
        // is paid on the worst thread for it: a name the mirror has just gained misses
        // [docIdCache] by construction, so every one of them walked the provider a
        // query per path segment, each query a full linear scan of that directory's
        // cursor, inline on FileObserver's single process-wide ObserverThread, which is
        // also the thread that has to keep up with every inotify event for the whole
        // mirror. A `git checkout` adding two thousand files paid it two thousand
        // times for an answer nothing looked at, while the events behind it queued.
        // [createOneInSaf] asks the provider for the same child again on the path that
        // does need the answer, so nothing here is lost by not asking early.
        val safDocUri =
            if (type == SyncType.CREATE && !isDirectory) null
            else resolveDocumentUri(safTreeUri, relativePath)
        val safParentUri = resolveDocumentUri(
            safTreeUri,
            File(relativePath).parent ?: ""
        )

        // The other half. Claiming is refused unless the device has nothing under the new
        // name. A null [safDocUri] has already asked: the resolution above missed the
        // cache and walked the provider on this very event, so the walk is only
        // repeated where a non-null answer can be stale, which is the cache hit and
        // the not-yet-visible rename:
        // `renameDocument` handed a name the folder already holds is the same trap as
        // `createDocument`, and providers answer it by inventing "util (1)", which
        // would leave the user two directories again, one of them named nonsense. But
        // the cache can name a document deleted since it was filled, a DELETE job
        // here, or a device-side removal no job of ours saw, and a dead entry
        // answering for the new name is what refuses the pair whose other half is
        // holding the only complete copy. A swap, `mv dist dist.old; mv dist.new
        // dist`, is refused by the same test, because the first rename is still in
        // the queue when the second pair arrives and the device still answers for
        // `dist`. Its second half falls back to being created from the mirror, which
        // is what it did before; its first half still moves in one piece.
        val renamedFrom =
            if (isDirectory &&
                (event and FileObserver.ALL_EVENTS) == FileObserver.MOVED_TO &&
                hasVanishedCandidate(now) &&
                (safDocUri == null || !providerHoldsDocument(safTreeUri, relativePath))
            ) claimVanishedDirectory(relativePath, now) else null
        // The upload records follow the directory, and the claim is the only point that
        // can move them: it is the one place holding both names. The job carries only
        // the old base name, and a RENAME job can be superseded by a newer one, cleared
        // by a stop, or never run at all, while this has to be true from the instant the
        // disk changed.
        //
        // Nothing here is conditioned on what the device does. A line records that the
        // MIRROR holds an edit the device never received, so a provider that refuses the
        // rename, or an old name that resolves to no document, changes nothing about
        // where those files now are. Left behind, the line names a path that no longer
        // exists: the next sync cannot consume it (its repair branch needs the file to
        // be there), so it copies the device's own truncated upload over the mirror's
        // only complete copy, and until then it refuses the removal the user asked for.
        //
        // Only a claimed pair, because only a claimed pair re-uploads nothing. An
        // unclaimed one falls back to createInSaf, which writes every child out again
        // and brackets each write with the journal itself.
        //
        // This is also the one place where a wrong claim can cost bytes rather than
        // placement, because these lines land under the ARRIVING directory's paths and
        // the next sync's repair acts on whatever real file it finds there. The window
        // and why it is left where it is are in [renameSourceFor].
        //
        // This runs on the observer thread while write-backs run on their own, so a save
        // can be streaming out of the directory that is moving. Its claim travels with
        // the lines, which is why [UploadClaim] carries a path rather than being found by
        // one: a writer that released the name it opened with would leave the moved line
        // standing for a write that landed.
        if (renamedFrom != null) {
            renameUploadsUnder(File(rootDir, renamedFrom), localFile)
            renameUnfetchedUnder(File(rootDir, renamedFrom), localFile)
        }
        // Resolved after the claim rather than when the directory vanished, so that the
        // provider is only asked on the events that turn out to be renames. Null means
        // the device has no document under the old name, there is nothing to rename and
        // nothing to duplicate, so this falls back to creating the new name outright.
        val renamedFromUri = renamedFrom?.let { resolveDocumentUri(safTreeUri, it) }
        if (renamedFrom != null && renamedFromUri != null) forgetCachedSubtree(renamedFrom)

        // Only for a pair that crosses directories, and resolved from where the document
        // actually is rather than from the destination: `moveDocument` refuses a source
        // parent the document is not in. Resolving it unconditionally would ask the
        // provider on every ordinary rename for a value nothing would read.
        val crossesDirectories = renamedFrom != null && renamedFromUri != null &&
            parentPathOf(renamedFrom) != parentPathOf(relativePath)
        val sourceParentUri =
            if (crossesDirectories) {
                resolveDocumentUri(safTreeUri, parentPathOf(renamedFrom!!))
            } else {
                null
            }

        // A pair that crossed directories and whose source parent did not resolve is not
        // a rename this write-back can carry out, so it is not offered as one. Both
        // reasons for a null source parent used to arrive at the same place -- a pair
        // inside one directory, which genuinely has nothing to move, and a cross-directory
        // pair whose old parent the provider would not answer for -- and the arm they
        // shared hands the write-back the document under its OLD name with nothing to
        // move it. On the common shape of a move, dragging `src/util` into `src/legacy/`,
        // the name does not change either, so the write-back performed no call at all and
        // still reported the move done: the device kept `src/util`, never received
        // `src/legacy/util`, and the create fallback that would have delivered it was
        // skipped. Declining the pairing puts the job back on the ordinary create path,
        // which is what an unclaimed move has always fallen back to, and leaves the old
        // copy standing on the device where the user can see it.
        val pairedRename = renamedFromUri != null && !(crossesDirectories && sourceParentUri == null)

        // Asked before the offer, never after. A session that has stopped being polled
        // never starts again, so an answer taken here cannot go stale in the two
        // instructions that follow, while the other order would report a job the drain
        // had already taken as lost. Still offered: the queue is the honest record of
        // what was asked for, and the mirror keeps the file either way. What is missing
        // is the user knowing, and that is what this line is.
        if (target.abandoned) {
            Logger.w(
                tag,
                "Write-back of $relativePath arrived after the drain ended; the save is " +
                    "in the mirror only until the folder is opened again",
            )
        }

        target.queue.offer(
            SyncJob(
                type = if (pairedRename) SyncType.RENAME else type,
                localPath = localFile.absolutePath,
                safDocUri = if (pairedRename) renamedFromUri else safDocUri,
                safParentUri = safParentUri,
                safTreeUri = safTreeUri,
                timestamp = now,
                safSourceParentUri = sourceParentUri,
                previousName = if (pairedRename) renamedFrom?.let { File(it).name } else null,
                relativePath = relativePath
            )
        )
    }

    /**
     * Whether the provider itself answers for [relativePath], asked segment by
     * segment rather than through [docIdCache].
     *
     * The cache is the right answer for the paths jobs write into, but it is a
     * record of what was true when it was filled, and nothing maintains it
     * against deletions the provider performed. A decision that turns on the
     * device *not* holding a name has to ask the device.
     */
    /**
     * Whether the device still holds anything at [relativePath]: true, false, or null
     * when the provider could not be asked. Type-agnostic, because it walks display
     * names, which is what the delete guard needs: it asks the same question of a
     * directory and of the document inside one.
     *
     * [providerHoldsDocument] folds a query that threw, or a cursor the provider would
     * not give, into "not held", which suits its caller: the cost of being wrong there
     * is a duplicate name. The delete guard in [handleMirrorEvent] cannot take that
     * answer, because for it "not held" means the delete goes through, and the delete
     * is `deleteDocument` on content the sync knows it never read. So this walk
     * reports the three cases apart, and the guard treats only a positive "gone" as
     * permission.
     */
    private fun providerHolds(treeUri: Uri, relativePath: String): Boolean? {
        var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        for (segment in relativePath.split("/")) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
            val next = try {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    var found: String? = null
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == segment) {
                            found = cursor.getString(idIndex)
                            break
                        }
                    }
                    found ?: return false
                } ?: return null
            } catch (e: Exception) {
                Logger.d(tag, "Lookup of $segment under $currentDocId failed: ${e.message}")
                return null
            }
            currentDocId = next
        }
        return true
    }

    private fun providerHoldsDocument(treeUri: Uri, relativePath: String): Boolean {
        var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        for (segment in relativePath.split("/")) {
            currentDocId = findChildDocId(treeUri, currentDocId, segment) ?: return false
        }
        return true
    }

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

    /**
     * Takes the vanished directory a MOVED_TO on [newRelativePath] may be the other half
     * of, or null when none of them can be claimed. A claimed entry is consumed.
     */
    private fun claimVanishedDirectory(newRelativePath: String, now: Long): String? =
        synchronized(vanishedLock) {
            val source = renameSourceFor(vanishedDirectories, newRelativePath, now)
            if (source != null) vanishedDirectories.removeAll { it.relativePath == source }
            source
        }

    /**
     * Drops [relativePath] and everything under it from [docIdCache].
     *
     * Called when the device document behind that path has been renamed, which is the one
     * event that invalidates a whole branch of the cache at once. Two things go wrong
     * without it, and both are quiet: on a provider whose document IDs are paths, every
     * cached ID under the old name now names a document that does not exist; and on a
     * provider whose IDs survive a rename, [createOneInSaf]'s reverse lookup can find the
     * *old* path for the renamed document and file new children under a relative path the
     * mirror no longer has. Dropping them costs a tree walk on the next lookup of a path
     * below the new name, which is the slow path that already exists for new files.
     */
    private fun forgetCachedSubtree(relativePath: String) {
        val prefix = "$relativePath/"
        docIdCache.keys.removeAll { it == relativePath || it.startsWith(prefix) }
    }

    /**
     * Resolves a document URI within a SAF tree given a relative path.
     * Uses [docIdCache] for O(1) lookup when available, falling back to tree
     * traversal on a cache miss.
     */
    private fun resolveDocumentUri(treeUri: Uri, relativePath: String): Uri? {
        if (relativePath.isEmpty()) {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        }

        // Fast path: use cached docId if available, and only while the cache speaks
        // for this tree.
        val cached = cachedDocId(treeUri, relativePath)
        if (cached != null) {
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, cached)
        }

        // Slow path: walk the tree segment by segment (for newly created files)
        var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        for (segment in relativePath.split("/")) {
            currentDocId = findChildDocId(treeUri, currentDocId, segment) ?: return null
        }

        // Cache for next time, unless the cache moved on to another tree during the
        // walk above; then this answer belongs to a folder it no longer speaks for.
        cacheDocId(treeUri, relativePath, currentDocId)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 8192
        private const val WRITEBACK_POLL_MS = 200L

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
        private const val DRAIN_GRACE_MS = 2000L

        /**
         * Suffix for a copy still being written; moved into place when complete.
         *
         * Internal because the reclaim pass has to recognise the one of these that
         * outlives its own writer: [writeSyncedRecord] puts a record's scratch file
         * beside the mirror rather than inside it, where nothing else in `saf-mirrors`
         * would ever look at it again.
         */
        internal const val PARTIAL_SUFFIX = ".vscodroid-partial"

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
        private val uploadJournalLock = Any()

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
        private val uploadClaims = mutableListOf<UploadClaim>()

        /**
         * Documents a write is streaming into right now, keyed by document URI.
         *
         * `openOutputStream(uri, "wt")` truncates at open, so two writers of one
         * document produce a file that is neither of their inputs. Two writers are
         * reachable: the mirror is named by a hash of the folder rather than by the
         * session, so reopening a folder while the previous session's drain is still
         * streaming puts [SafSyncEngine.initialSync] on the IO dispatcher and that
         * drain on the same document.
         *
         * In this object rather than on the engine, next to [uploadJournalLock] and
         * [uploadClaims] and for the reason those give: the engine is one per
         * [SafStorageManager] and the manager one per activity, while
         * [SafSyncEngine.stopWatching] waits [DRAIN_GRACE_MS] and then deliberately
         * leaves a drain it could not join to finish on its own. The two writers above
         * are therefore routinely in two different engines, an activity recreated while
         * a drain is still streaming being the ordinary way there, and a set held per
         * instance is empty in exactly the engine that has to see the departing drain's
         * claim. Process scope is what makes the claim mean anything at all.
         *
         * A set rather than a lock, and that is the whole design. The obvious fix, a
         * per-document lock the second writer waits on, was rejected for a measured
         * reason: a `ContentResolver` stream to a network or MTP provider has no
         * timeout, so waiting converts today's race into an unbounded stall of
         * [SafSyncEngine.initialSync], behind a dialog built with
         * `setCancelable(false)`. A trade of corruption for a hang is not a fix. `add`
         * returns false when someone already holds the document, and the loser declines
         * instead of waiting.
         *
         * What the loser gives up is one write, and both writers copy the same local
         * file, so the bytes it would have written are the bytes the winner is writing.
         * If the mirror changed in between, the watcher raises MODIFY again and the
         * queue carries it; the write-back already drops a job a newer one supersedes.
         *
         * Keyed by document rather than by local path deliberately, though the two
         * coincide today: the mirror is one directory per folder, so one local path
         * resolves to one document and nothing distinguishes the keys. Measured, and
         * stated because it is a coincidence rather than a guarantee. The thing being
         * protected is the document that `"wt"` truncates, so that is what the key
         * names; a local path is a proxy that happens to agree.
         */
        private val documentWritesInFlight = ConcurrentHashMap.newKeySet<String>()

        /**
         * Mirror files a sync is streaming a device document into right now, keyed by
         * absolute mirror path.
         *
         * The inbound twin of [documentWritesInFlight], in this object for the reason
         * that one is: two syncs over one mirror are routinely in two different engines.
         * A mirror is named by a hash of the tree URI rather than by the session, and
         * [initialSync]'s phase-2 loop contains no suspension point, so cancelling the
         * coroutine an activity started does not stop it: an activity destroyed mid-open
         * leaves one sync copying while its replacement opens the same folder and starts
         * another.
         *
         * Both wrote the same scratch path, `<name>.vscodroid-partial`, so one writer's
         * rename into place carried the other writer's open stream with it and the rest
         * of that copy landed inside the finished mirror file, which [recordIdentity]
         * had already vouched for by then. The vouched identity is what
         * [reconcileDeletions] and [holdsOnlyVouchedCopies] later act on, so a corrupted
         * file was also a deletable one.
         *
         * The loser declines rather than waits, exactly as [documentWritesInFlight]'s
         * loser does and for the same measured reason: a `ContentResolver` stream to a
         * network or MTP provider has no timeout, so waiting would trade a race for an
         * unbounded stall of a folder open behind a dialog with `setCancelable(false)`.
         * What the loser gives up is one copy of a document the winner is already
         * copying, and it records nothing for that path, which is the direction that
         * costs disk rather than work.
         */
        private val mirrorCopiesInFlight = ConcurrentHashMap.newKeySet<String>()

        /**
         * Suffix of the sibling file recording what the last complete sync found.
         *
         * A sibling and not a child: the mirror directory is the folder VS Code opens,
         * and a bookkeeping file inside it would show up in the explorer and in search.
         * Being a sibling is also why whatever removes a mirror has to remove this too,
         * which is what [SafStorageManager] uses it for.
         */
        internal const val SYNCED_RECORD_SUFFIX = ".synced"

        /**
         * First line of the record, and the only thing that says which format follows.
         *
         * Field count is not a version. A later format that also has three fields with
         * different meanings would be read as this one and acted on, and acting means
         * deleting. A record whose first line is not exactly this is ignored, which is
         * how a record from the build before headers is already treated, and in the same
         * direction: unverifiable means keep.
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
        private const val IN_ISDIR = 0x40000000

        /**
         * How long a directory that left the mirror stays claimable as a rename source.
         *
         * A rename(2) emits MOVED_FROM and MOVED_TO back to back and `FileObserver`
         * delivers them from one thread in that order, so the true pair is milliseconds
         * apart; this is slack for a busy observer thread, not a guess at how long a user
         * might take. Widening it does not make pairing more likely to succeed, it makes
         * a mis-pair more likely, because it is the only thing bounding how far apart two
         * *unrelated* moves can be and still be joined.
         */
        internal const val RENAME_PAIR_WINDOW_MS = 1_000L

        /**
         * Which vanished directory a MOVED_TO on [newRelativePath] is the other half of,
         * or null when the question cannot be answered.
         *
         * inotify's cookie is what would answer it; `FileObserver` does not expose it, so
         * this is a heuristic and it declines rather than guesses. **When it is wrong:**
         * one directory leaves the mirror for good (`mv dir ~/elsewhere`, a build tool
         * swapping trees) and, within [RENAME_PAIR_WINDOW_MS], an unrelated directory
         * arrives in the same parent. The two are then joined, and the device ends up with
         * the departed directory's subtree under the arriving one's name while the
         * arriving one's own contents stay in the mirror. No document is deleted in that
         * case, reopening the folder brings the misplaced subtree back down where it can
         * be seen and moved, which is why the pairing is allowed to be a heuristic at
         * all, and why a rule that cannot be sure says no. It is not the whole cost
         * though; the paragraph below the two rules has the part that can reach bytes.
         *
         * - **Exactly one candidate.** Two directories in flight at once cannot be told
         *   apart by arrival order alone once their events interleave, and pairing the
         *   wrong two would put each subtree under the other's name.
         * - **Recent.** See [RENAME_PAIR_WINDOW_MS].
         *
         * A third rule required the two halves to share a parent, and it was dropped once
         * the write-back learned `moveDocument`. It was never about pairing being less
         * certain across parents; it was that `renameDocument` cannot express a move, so a
         * pair the engine could not act on was better left unclaimed. Dragging `src/util`
         * into `src/legacy/` is an ordinary thing to do and used to leave the old copy on
         * the device, reappearing beside the new one on every reopen. Removing the rule
         * does widen the mis-pair window: an unrelated arrival anywhere in the mirror can
         * now be joined to a departure, where before it had to arrive in the same
         * directory. The two surviving rules are what bound it.
         *
         * **What a mis-pair costs, stated exactly, because "nothing is deleted" is not
         * the whole of it.** For the device documents themselves it is placement and not
         * bytes: [moveInSaf] relocates the document rather than removing it, and
         * reopening the folder brings the misplaced subtree back down. The one path that
         * does reach bytes runs through the journal. A claim also calls
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
         * the window sat before the third rule went. So the bound stays the two rules
         * above, and this is written down so the byte-losing path is read alongside them
         * rather than found later.
         *
         * Declining costs exactly what the code did before there was any pairing: the
         * device keeps the copy under the old name.
         */
        internal fun renameSourceFor(
            vanished: List<VanishedDirectory>,
            newRelativePath: String,
            now: Long
        ): String? = vanished
            .filter { now - it.atMillis <= RENAME_PAIR_WINDOW_MS }
            .singleOrNull()
            ?.relativePath

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
        private const val MAX_WATCHED_DIRECTORIES = 2048

        /** Max file size to sync (50 MB). Larger files are skipped. */
        internal const val MAX_FILE_SIZE = 50L * 1024 * 1024

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
         * Extracted because two paths ask it and only one of them can be reached from a
         * JVM test. [handleMirrorEvent] asks before queueing a job; [createOneInSaf] asks
         * again while walking into a directory, which no event-driven test can drive here
         * at all, since delivering a directory event constructs a `FileObserver` and that
         * runs a static initializer reaching native code (see [SafWatchCoverageTest]).
         * Naming the rule once is what lets it be asserted at all, and what stops the two
         * call sites drifting apart, which is how the second one came to be missing it.
         *
         * [deviceHoldsDocument] is the provider's answer, not a guess: the set alone is a
         * memory of what this sync could not read, and the document may have been deleted
         * on the device since, in which case the local file is an ordinary new file.
         */
        internal fun writeWouldReplaceUnreadDocument(
            localPath: String,
            deviceHoldsDocument: Boolean,
            unfetched: Set<String>,
        ): Boolean = deviceHoldsDocument && localPath in unfetched

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
         *   elsewhere now, by [copyDocumentToLocal] writing beside the destination and
         *   renaming only once the stream finished, so an interrupted copy leaves no
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
                            val read = left.readNBytes(one, 0, one.size)
                            if (read != right.readNBytes(two, 0, two.size)) return false
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

        /**
         * Whether [file] is a symbolic link, without following it.
         *
         * `File.exists()` follows links, so it cannot answer this. Comparing the
         * canonical path against the absolute one can: they differ exactly when some
         * component of the path was a link. The parent is canonicalised first so a
         * link *above* this file -- which is not this file's problem -- does not make
         * every entry beneath it look like one.
         */
        private fun isLink(file: File): Boolean = try {
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
        private fun isMachineTemporary(name: String): Boolean =
            name.endsWith(PARTIAL_SUFFIX) || name.endsWith("~") || name.endsWith(".tmp")

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
         * The temporary suffixes are the one asymmetry that stays, and it is the
         * intended one: [PARTIAL_SUFFIX] files are this engine's own half-written
         * copies, and `~`/`.tmp` belong to a writer that is about to rename them away.
         * Uploading them would push a file that is about to stop existing.
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

// -- Data Classes --

internal data class DocumentInfo(
    val uri: Uri,
    val docId: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Provider's last-modified time, or 0 when it does not report one. */
    val lastModified: Long = 0
)

internal data class SyncJob(
    val type: SyncType,
    val localPath: String,
    val safDocUri: Uri?,
    val safParentUri: Uri?,
    val safTreeUri: Uri?,
    val timestamp: Long,
    /**
     * For a RENAME, the parent the document sits under *now*, which is what
     * `moveDocument` needs alongside the destination and is not recoverable from
     * the other fields: [safDocUri] names the document and [safParentUri] names
     * where it is going. Null on every other job type, and on a rename that
     * stays inside one directory.
     */
    val safSourceParentUri: Uri? = null,
    /**
     * For a RENAME, the name the document currently carries on the device, so
     * the write-back can tell a move that keeps its name from one that does not
     * without reading a name out of a document ID. Null on every other type.
     */
    val previousName: String? = null,
    /**
     * The path of [localPath] relative to the mirror's root, which the write-back
     * thread has no other way to recover: it knows absolute paths and document
     * URIs, and neither names the cache entries or the destination parent a
     * rename needs. Empty only where a job was built by something other than
     * [SafSyncEngine.handleMirrorEvent], which fills it.
     */
    val relativePath: String = ""
)

internal enum class SyncType {
    MODIFY, CREATE, DELETE, RENAME
}

/**
 * What [SafSyncEngine.uploadPlan] found under a directory, and whether the cap stopped
 * the walk short of the tree.
 *
 * The flag is carried rather than derived because the two part company at exactly the
 * cap: [entries] is capped at the limit, so its size reads as "there was more" for a
 * directory that ends there naturally as readily as for one with a thousand more files
 * below.
 */
internal data class UploadPlan(val entries: List<File>, val truncated: Boolean)

/**
 * A directory that left the mirror and has not been accounted for yet.
 *
 * [relativePath] is where it was, which is all a later MOVED_TO needs to find the
 * document still standing under that name on the device.
 */
internal data class VanishedDirectory(
    val relativePath: String,
    val atMillis: Long,
    /**
     * Where it was in the mirror, absolutely, which is the only spelling the upload
     * journal answers to: its lines are absolute paths because one engine serves every
     * folder in turn, while [relativePath] is what the device side needs. Carried on the
     * record rather than rebuilt from a mirror root, because the readers that retire
     * those lines ([SafSyncEngine.dropVanished]'s callers) run where no root is in hand.
     */
    val localPath: String
)

/**
 * One write-back's claim on the mirror path it is streaming into, held from the moment
 * its journal line is written until that write ends.
 *
 * [path] is a var because the directory above it can be renamed in the editor while the
 * stream runs, and the claim and the line are two records of one write:
 * [SafSyncEngine.renameUploadsUnder] moves both together, so the writer's release names
 * the line as it is spelled then rather than as it was spelled when the stream opened.
 * Identity is what a writer releases, so this is deliberately not a data class: two
 * writers of one path hold two claims and each must retire only its own.
 *
 * Read and written only under the journal lock, which is what makes a plain var safe here.
 */
private class UploadClaim(var path: String)

/**
 * One folder's write-back: the jobs its observers produced, and the thread sending them.
 *
 * Per folder rather than per engine, and that is what keeps two writers off one
 * document. [SafSyncEngine.stopWatching] leaves a drain that outran its grace period
 * holding this object and installs a fresh one, so the jobs of the folder opened next go
 * somewhere the departing thread cannot reach, and the thread started for that folder
 * polls a queue nothing else polls.
 */
internal class WatchSession {
    val queue = ConcurrentLinkedQueue<SyncJob>()

    /** Cleared by [SafSyncEngine.stopWatching]; the loop's own termination condition. */
    @Volatile var running = true

    /**
     * Whether [queue] has stopped being polled, which no session ever comes back from:
     * a worker is started once, by the [SafSyncEngine.startWatching] that installed the
     * session, and never again.
     *
     * What it buys is a sentence in a bug report. An event observed while the folder was
     * open can finish its provider round trips after the drain has taken its last look,
     * and the save it carries then reaches no device and nothing retries it. The stop's
     * own count cannot see that one: on an ordinary folder switch the idle drain exits in
     * microseconds, so the queue is counted and cleared while the event is still inside
     * the provider, and the offer that follows is what has to say so.
     */
    @Volatile var abandoned = false

    /** The thread draining [queue], or null before one is started and once it is joined. */
    @Volatile var worker: Thread? = null
}
