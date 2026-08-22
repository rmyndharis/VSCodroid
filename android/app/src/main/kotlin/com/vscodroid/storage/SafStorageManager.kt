package com.vscodroid.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import com.vscodroid.R
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Manages Storage Access Framework (SAF) interactions for VSCodroid.
 *
 * Responsibilities:
 * - Launching/handling folder picker results
 * - Persisting URI permissions across app restarts
 * - Tracking recently opened SAF folders
 * - Coordinating initial sync + ongoing file watching via [SafSyncEngine]
 *
 * SAF provides access to user-selected folders via content:// URIs. Since
 * VS Code Server (Node.js) requires real filesystem paths, we maintain a
 * local "mirror" directory that is kept in sync with the SAF source.
 */
class SafStorageManager(context: Context) {

    /**
     * The application context, whatever the caller handed over.
     *
     * Both production callers construct this with an Activity, and one of them
     * ([com.vscodroid.SplashActivity]) then starts [reclaimRevokedMirrors], whose
     * detached thread captures this object and through it whatever Context it
     * holds. That thread outlives the Activity by construction (the file says so
     * where the thread is started), and its duration is the recursive delete of a
     * project-sized mirror, so an Activity kept here stays reachable, with its
     * Window and its ContextImpl, through exactly the minutes when `MainActivity`,
     * the WebView renderer and the Node server are all starting.
     *
     * Nothing here needs an Activity: what is read is `contentResolver`, `filesDir`
     * (through [com.vscodroid.util.Environment]) and the preferences file, all
     * identical on the application context. This is the same reasoning
     * `AndroidBridge` gives for building its `ToolchainManager` on
     * `applicationContext`.
     *
     * ⚠️ The two initialisers below say `this.context` deliberately. A constructor
     * parameter shadows the property of the same name inside an initialiser, so a
     * bare `context` there is the Activity again, and handing that to
     * [SafSyncEngine] would keep the whole retention with the unwrapping still in
     * place and reading as though it worked.
     */
    private val context: Context = context.applicationContext ?: context

    private val tag = "SafStorageManager"
    private val prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val syncEngine = SafSyncEngine(this.context)

    /**
     * Told when a write-back has given up on a file, once per burst.
     *
     * Forwarded from the engine rather than set on it directly, so the caller does not
     * have to know the engine exists. [MainActivity] is the one caller, because saying
     * anything needs a screen.
     *
     * Throttled here rather than at the call site: a provider that has started refusing
     * refuses everything, and the editor saves on a timer, so the unthrottled version is
     * a wall of the same notice. Globally rather than per file, for the same reason. The
     * user's problem is the folder.
     *
     * The reading is the monotonic clock, never wall time, and that is the same reason
     * `AuthTabWindow` gives one file over. The throttle is a subtraction against a stamp
     * held for the life of this object, so a wall clock corrected backwards by more than
     * the interval (NTP after a drifted RTC, or the user setting the date) leaves every
     * later difference below it, and a folder that is still refusing every save
     * says nothing at all until the clock catches up. Silence is the failure this notice
     * exists to prevent.
     */
    fun onWriteBackFailed(announce: (File) -> Unit) {
        syncEngine.onWriteBackFailed = { file ->
            if (claimAnnouncement(SystemClock.elapsedRealtime(), lastFailureAnnouncedAt.get())) {
                announce(file)
            }
        }
    }

    /**
     * Whether the caller that read [last] off the throttle is the one that gets to speak.
     *
     * `compareAndSet`, not read-then-write. Two threads genuinely arrive here: the
     * `saf-writeback` daemon draining the queue, and `Dispatchers.IO` running the
     * write-backs `initialSync` issues itself. `@Volatile` gave visibility but not
     * atomicity, so both could read the same stale [last], both find the interval
     * elapsed, and both announce for one burst, which is the wall of toasts the
     * throttle exists to prevent.
     *
     * The loser does not retry, deliberately. Losing means another thread has just
     * announced this same burst, which is the answer the user needed.
     *
     * [last] is a parameter rather than a read taken here, and that is what makes the
     * claim assertable: a caller can be handed a reading that another caller has already
     * consumed, which is the losing thread's whole situation, without any threads being
     * involved. Racing two of them is not an instrument for this. Measured on this JDK
     * with both shapes behind a `CyclicBarrier`, the read-then-write version announced
     * twice in 0 of 50,000 two-thread trials, 0 of 20,000 eight-thread trials and 0 of
     * 2,000 sixteen-thread ones: the window is one read, a subtraction and one write,
     * and the throttle confines it to the first instant of each interval, so a race test
     * would have reported the shape this replaced as green.
     */
    internal fun claimAnnouncement(now: Long, last: Long): Boolean =
        shouldAnnounce(now, last) && lastFailureAnnouncedAt.compareAndSet(last, now)

    /**
     * Told when a folder opened without every document reaching the mirror.
     *
     * Forwarded rather than set on the engine directly, for the reason
     * [onWriteBackFailed] is, and unthrottled for the reason that one is throttled: this
     * fires at most once per folder open and the count it carries is the whole burst.
     */
    fun onDocumentsNotCopied(announce: (Int, Boolean) -> Unit) {
        syncEngine.onDocumentsNotCopied = announce
    }

    /**
     * Told when a directory copied out to the device arrived incomplete.
     *
     * Forwarded rather than set on the engine directly, for the reason the two above
     * are. Unthrottled, like [onDocumentsNotCopied] and unlike [onWriteBackFailed]:
     * this fires once per directory copy and the count it carries is the whole burst,
     * so there is nothing for a throttle to collapse.
     */
    fun onUploadIncomplete(announce: (File, Int, Boolean) -> Unit) {
        syncEngine.onUploadIncomplete = announce
    }

    private val lastFailureAnnouncedAt = AtomicLong(NEVER_ANNOUNCED)

    // -- Permission Management --

    /**
     * Takes a persistable URI permission for the given tree URI.
     *
     * This must be called with the URI returned from [ActivityResultContracts.OpenDocumentTree].
     * Persisted permissions survive across app restarts and device reboots until
     * the user explicitly revokes them in system settings.
     */
    fun persistPermission(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            // Between the grant and the row, and that order is what [grantsTaken] is
            // read against: a reader whose snapshot of the grants was taken before this
            // line must not judge the row the next one writes.
            grantsTaken.incrementAndGet()
            addToRecentFolders(uri)
            Logger.i(tag, "Persisted permission for ${getMirrorDir(uri).name}")
        } catch (e: SecurityException) {
            // Neither the URI nor the throwable, and it takes both to be redaction at
            // all. A SAF tree URI spells the user's own directory
            // (`.../tree/primary%3ADocuments%2F<folder>`), and
            // takePersistableUriPermission's SecurityException quotes that URI in its
            // own message, so dropping the interpolation while still passing the
            // exception along would put the same string in logcat by another route.
            // Logger.i, .w and .e are not gated on a debuggable build, so every one of
            // them ships. The mirror name is the six-byte digest of that same URI: it is
            // stable, it is not reversible, and it lines this up with every other line
            // the app writes about the folder.
            Logger.e(
                tag,
                "Could not persist the permission for ${getMirrorDir(uri).name}: " +
                    e.javaClass.simpleName,
            )
        }
    }

    /**
     * Checks if we still hold a valid persisted permission for the given URI.
     */
    fun hasPersistedPermission(uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }

    // -- Recent Folders --

    /**
     * The device folder whose mirror the workbench has opened, if it opened one.
     *
     * VS Code switches folders by navigating its own WebView, so a folder reached
     * through Open Recent, the Get Started list or Open Folder never passes
     * through the picker and never starts a watcher. Kotlin sees only the
     * finished page load, and this is what turns that URL back into the grant it
     * belongs to.
     */
    fun folderForOpenedPath(opened: String): SafFolderInfo? =
        folderForOpenedPath(getPersistedFolders(), opened)

    /**
     * Returns the list of recently opened SAF folders with persisted permissions.
     * Folders whose permissions have been externally revoked are pruned from the list.
     *
     * Pruning the list is all this does. Deleting the mirror of a pruned folder used to
     * happen here too, which put a recursive delete of the user's files inside a method
     * the workbench calls whenever it wants the recent list, and made a permission that
     * read as absent for a moment enough to take the mirror of the folder currently open
     * in the editor out from under it. That reclamation lives in [reclaimRevokedMirrors]
     * alone now.
     */
    fun getPersistedFolders(): List<SafFolderInfo> {
        // Read before the lock, and as one call rather than one per entry. The prune
        // below used to ask the system server for each folder in turn, so a listing held
        // the monitor across up to [MAX_RECENT] binder round trips while the bridge's
        // disk-work thread, a sync on Dispatchers.IO and the reclaim thread waited on
        // it. One reading also judges every entry against one answer, which is what the
        // per-entry version could not promise.
        //
        // What this does not buy is a monitor free of binder calls, which is how the
        // hoist has already been read once. Three of the four read-modify-writes the
        // lock exists for call this from inside it ([releaseGrantFor],
        // [addToRecentFolders], [updateLastOpened]; the fourth is the prune below), and
        // each of those nested calls makes its own [persistedReadUris] round trip with
        // the monitor already held. What the hoist bounds is the cost of that: one round
        // trip rather than one per entry.
        // Who pays it is a background thread in every case, so it is a wait and not a
        // freeze: MainActivity hops folderForOpenedPath, persistPermission and
        // syncToLocal onto Dispatchers.IO (DeviceFolderOpenThreadTest pins that), the
        // bridge answers listings on its disk-work executor and on the JavaBridge
        // thread, and [reclaimRevokedMirrors] runs on a thread of its own.
        val stamp = grantsTaken.get()
        val granted = persistedReadUris()
        return synchronized(recentFoldersLock) {
            // What holding the reading inside the lock used to buy, without holding it
            // there. The prune below is a read-modify-write that DELETES rows, and a
            // reading taken before the lock can be older than the list read inside it: a
            // grant taken in between belongs to a row the picker saves before this thread
            // gets the monitor, and judging that row against the older reading prunes the
            // folder the user just picked and persists the list without it, with the
            // grant still held. Nothing here can tell which row that would be, so against
            // such a reading no row is judged at all: the list is returned as it stands
            // and left alone, and the next read, whose reading names the new grant,
            // prunes then. Keeping a row too long is the harmless direction, because the
            // pass that deletes mirrors is [reclaimRevokedMirrors] and it never consults
            // this list.
            readAndPruneRecentFolders(granted.takeIf { grantsTaken.get() == stamp })
        }
    }

    /** Every tree this app still holds a persisted read grant for, in one binder call. */
    private fun persistedReadUris(): Set<Uri> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .toSet()

    /**
     * The body of [getPersistedFolders]. Caller must hold [recentFoldersLock]: the
     * prune below is itself a read-modify-write of the list.
     *
     * [granted] is every tree still granted, as of a reading the caller took. Null means
     * the caller cannot vouch that its reading is not older than this list, and then
     * nothing is pruned and nothing is written: see [getPersistedFolders].
     */
    private fun readAndPruneRecentFolders(granted: Set<Uri>?): List<SafFolderInfo> {
        val json = prefs.getString(KEY_RECENT_FOLDERS, "[]") ?: "[]"
        val array = JSONArray(json)
        val result = mutableListOf<SafFolderInfo>()
        val toRemove = mutableListOf<Int>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val uri = Uri.parse(obj.getString("uri"))

            // Prune folders whose permissions have been revoked externally
            if (granted != null && uri !in granted) {
                toRemove.add(i)
                continue
            }

            result.add(
                SafFolderInfo(
                    uri = uri,
                    displayName = obj.optString("name", uri.lastPathSegment ?: "Unknown"),
                    lastOpened = obj.optLong("lastOpened", 0),
                    mirrorPath = getMirrorDir(uri).absolutePath
                )
            )
        }

        // Persist pruned list if any entries were removed
        if (toRemove.isNotEmpty()) {
            saveRecentFolders(result)
            Logger.i(tag, "Pruned ${toRemove.size} revoked folder(s) from recent list")
        }

        return result.sortedByDescending { it.lastOpened }
    }

    /**
     * Deletes the mirrors of folders no longer backed by a live permission.
     *
     * The reclamation half of what [getPersistedFolders] used to do in one pass. It
     * works off the mirrors directory rather than the recent list so that it carries no
     * ordering requirement against the read: a list already pruned no longer names the
     * folders whose mirrors are stale, and an orphan left by a cleared list or a crashed
     * sync is not in the list at all.
     *
     * Deletes the sync record beside each mirror too: both are named after the same
     * hash, the record with a suffix.
     *
     * Call it where no folder is open. Nothing here can tell which mirror the editor is
     * holding, so the call site is what keeps it away from one; see
     * [com.vscodroid.SplashActivity], which always precedes `MainActivity`.
     *
     * Returns immediately. The scan itself is a handful of stats, but what it can find
     * is a mirror of a whole project, and deleting one of those is a recursive delete of
     * thousands of files. Its caller is the launch-time repair block in
     * [com.vscodroid.SplashActivity], which runs on the main thread before anything is
     * drawn, the same reason `repairInstalledToolchains` hands its walk off there.
     *
     * Note what that does *not* buy: the thread outlives the activity that started it,
     * so "no folder is open when it starts" is not "no folder can be opened before it
     * ends". [reclaimRevokedMirrorsSync] is what has to survive that, and how it does is
     * documented there.
     */
    fun reclaimRevokedMirrors() {
        thread(name = "saf-reclaim", isDaemon = true) {
            try {
                reclaimRevokedMirrorsSync()
            } catch (e: Exception) {
                // Nothing depends on this having run: the next launch tries again, and
                // until then the cost is disk that is already spent.
                Logger.w(tag, "Mirror reclamation pass failed: ${e.message}")
            }
        }
    }

    /**
     * The body of [reclaimRevokedMirrors], on the caller's thread.
     *
     * Two things this has to be careful about, and neither is the scan itself.
     *
     * **It only ever removes what this app creates.** `saf-mirrors` is not private
     * scratch space: [com.vscodroid.setup.FirstRunSetup] exports it into every terminal
     * as `SAF_MIRRORS_DIR`, and the WebView publishes it as a resource root, so a person
     * can put a file there and some will. Only names of the shape [getMirrorDir]
     * produces, and the sync record beside them, are candidates.
     *
     * **A candidate is set aside before it is deleted.** The obvious version (delete in
     * place) rests on nothing else touching that directory meanwhile, and that does not
     * hold: this runs on a detached thread, so it outlives the splash screen that starts
     * it, and its duration is proportional to the mirror it is deleting. The user can
     * reach `MainActivity`, re-grant the same folder and re-sync it into the directory
     * the walk is still inside; the walk's remaining deletes then land on the new copy,
     * under a running watcher, and go out to the device as deletions of the user's real
     * documents. Renaming is atomic and instant, so a folder granted a moment later gets
     * a fresh directory this pass cannot reach. A rename that survives a killed process
     * is reclaimed by the next pass.
     *
     * That last argument holds only for a grant taken *after* the rename, so what
     * decides the removal has to sit next to the rename rather than at the top of the
     * loop. It stopped doing so when the vouching walk was interposed between them: a
     * grant arriving during the walk was judged by an answer taken before it. The
     * question is therefore asked twice, cheaply at the top so the walk never runs for an
     * entry that is plainly live, and again immediately before the rename.
     *
     * @return how many entries were removed.
     */
    internal fun reclaimRevokedMirrorsSync(): Int {
        fun liveMirrorNames(): Set<String> =
            context.contentResolver.persistedUriPermissions
                .map { getMirrorDir(it.uri).name }
                .toSet()

        val root = File(com.vscodroid.util.Environment.getSafMirrorsDir(context))
        var removed = 0
        // Read once for the pass rather than per candidate: it is one file read,
        // and a write-back cannot start for a mirror whose permission is already
        // gone, which is the only kind of entry examined here.
        val stranded = syncEngine.uploadsInFlight()

        // One verdict per hash for the whole pass, and the memo is correctness rather
        // than speed. A mirror is two entries, `<hash>` and `<hash>.synced`, and
        // `listFiles()` does not promise an order: ext4 and APFS disagree. Asking the
        // record per entry meant that when the record was visited first it was deleted,
        // and the directory behind it was then judged with its record already gone,
        // answered "nothing vouches for this", and was kept. The pair has to stand or
        // fall together, so the answer is computed once, before either is touched.
        val vouched = mutableMapOf<String, Boolean>()

        root.listFiles()?.forEach { entry ->
            val name = entry.name
            // The prefix alone is not enough to claim an entry: a person can name a
            // directory anything, and this one is only ours when what follows the prefix
            // is a name we would have set aside.
            val alreadySetAside = name.startsWith(DISCARD_PREFIX) &&
                MIRROR_ENTRY.matches(name.removePrefix(DISCARD_PREFIX))
            // Asked per entry rather than from one snapshot taken before the loop,
            // and the gap between the two is the point. This runs on a detached
            // thread whose duration is proportional to what it deletes -- a project
            // mirror is a recursive delete of thousands of files -- and the user
            // reaches MainActivity while it works. A folder granted during that
            // window is absent from a snapshot predating the grant, so its freshly
            // synced mirror was set aside and deleted underneath the editor holding
            // it. The cost is one binder call per candidate, and a candidate is an
            // entry that already looks unowned, which is normally none.
            val reclaimable = alreadySetAside ||
                (MIRROR_ENTRY.matches(name) && name.substringBefore('.') !in liveMirrorNames())
            if (!reclaimable) return@forEach
            // A mirror is reclaimable when the device folder holds everything in it,
            // which is what makes deleting it lose nothing. Usually that is because the
            // mirror is a copy; it is equally true of a file the editor wrote once the
            // watcher has carried it across, which `holdsOnlyVouchedCopies` recognises
            // by comparing bytes rather than by asking who authored them. That stops
            // being true when
            // this app's own records say a write never reached the device: a
            // write-back that gave up after two failures, or one refused with a
            // SecurityException, which is precisely what a permission withdrawn
            // mid-session produces and therefore what puts the mirror in front of
            // this pass in the first place. Deleting then is not reclaiming a
            // copy, it is deleting the only copy, on a launch-time thread with no
            // screen to ask from.
            //
            // The set-aside branch is exempt: those are this app's own leftovers
            // from an earlier pass that already made this decision.
            // On the mirror's own name, the same `substringBefore` the
            // reclaimable test above uses. An entry here is either the mirror
            // directory or the `<hash>.synced` record beside it, and they stand
            // or fall together: keeping the directory while dropping its record
            // leaves the next sync with no snapshot of what it had already
            // fetched.
            val hash = name.substringBefore('.')
            if (!alreadySetAside &&
                !mayReclaim(hash, stranded, root.absolutePath)
            ) {
                Logger.w(
                    tag,
                    "Keeping $name: it holds a write that never reached the device folder",
                )
                return@forEach
            }

            // The journal above answers "did a write-back fail", which is only one way a
            // mirror can hold the user's only copy. It cannot see a file that was never
            // queued for write-back at all, and there are several routes into that state:
            // anything under SKIP_DIRECTORIES, so a `.git` from a terminal clone; a file
            // below a directory past the watch cap; anything written while no watcher
            // ran; and a mirror copy the initial sync kept for being newer than the
            // device document. Asking the record instead inverts the test: prove the
            // mirror is disposable rather than look for evidence that it is not.
            // `&&` first, so the walk never runs for an entry the next line exempts.
            // A `discarded-` entry is an earlier pass's leftover whose verdict is already
            // made, and asking anyway walked the whole tree that is about to be deleted,
            // on every launch until the delete finally succeeded.
            if (!alreadySetAside &&
                !vouched.getOrPut(hash) {
                    syncEngine.holdsOnlyVouchedCopies(File(root, hash))
                }
            ) {
                Logger.w(
                    tag,
                    "Keeping $name: it holds files no sync ever vouched for",
                )
                return@forEach
            }

            // Asked a second time, and this is the one that matters. The gate above
            // used to be the last thing before the rename; the vouching walk now sits
            // between the two, and that walk is a full traversal of the mirror -- the
            // slowest case by construction, because a mirror that passes it is one the
            // walk had to finish. On a project tree that is seconds to tens of seconds
            // of a detached thread, and the whole window is one where the user can reach
            // MainActivity, re-pick the same folder and have it re-synced and watched
            // into the very directory this pass is about to rename away. The set-aside
            // branch is exempt for the reason it is exempt above: a `discarded-` entry
            // is an earlier pass's leftover, already unreachable, and no grant can bring
            // it back.
            if (!alreadySetAside && hash in liveMirrorNames()) {
                Logger.i(
                    tag,
                    "Leaving $name alone: its folder was granted again while this pass ran",
                )
                return@forEach
            }

            if (discardEntry(root, name)) removed++
        }
        if (removed > 0) {
            Logger.i(tag, "Reclaimed $removed mirror entr(ies) without a live permission")
        }
        return removed
    }

    /**
     * Renames one entry out of the way, and answers with where it went.
     *
     * The rename is the commit point of every removal in this file, and the reason is
     * in [reclaimRevokedMirrorsSync]: a delete in place rests on nothing else touching
     * the directory while the walk is inside it, and a mirror the size of a project
     * takes long enough for the user to re-grant the same folder and re-sync it there.
     * The walk's remaining deletes then land on the new copy under a running watcher
     * and go out to the device as deletions of the user's real documents. A rename is
     * atomic and instant, so the re-granted folder gets a fresh directory this removal
     * cannot reach.
     *
     * It is also what makes an interrupted removal safe to abandon. Everything after
     * the rename is idempotent and is finished off unconditionally by the next launch
     * pass, so a return added between here and the delete costs disk until the next
     * launch rather than correctness. That is the property to preserve when editing
     * either caller.
     *
     * @return the set-aside entry, or null when there was nothing to move or the
     *   rename failed. An entry already carrying [DISCARD_PREFIX] is returned as it
     *   stands: an earlier pass reached this same point and its verdict still holds.
     */
    private fun setAside(root: File, name: String): File? {
        if (name.startsWith(DISCARD_PREFIX)) return File(root, name).takeIf { it.exists() }
        val entry = File(root, name)
        if (!entry.exists()) return null
        val target = File(root, DISCARD_PREFIX + name)
        if (!entry.renameTo(target)) {
            Logger.w(tag, "Could not set $name aside; leaving it in place")
            return null
        }
        // The records move with the directory, and this is the only moment they can.
        // They are keyed by absolute path, and the name is a hash of the tree URI
        // rather than of the session, so from here on the path this rename frees is
        // the path the same folder gets again the moment the user re-grants it. Left
        // behind, the departing mirror's records and the replacement's are the same
        // strings and nothing downstream can tell them apart.
        syncEngine.renameUploadsUnder(entry, target)
        return target
    }

    /**
     * Deletes an entry [setAside] has already moved, and retires the upload records
     * that named it.
     *
     * The records are retired under the `discarded-` path, which is the path they are
     * filed under: [setAside] moves them when it moves the directory. Clearing under
     * the name the mirror had *before* the rename is what this used to do, and it is
     * wrong in the one case that matters. A mirror's directory name is a hash of the
     * tree URI, so the path a removal frees is the path the same folder returns to when
     * the user re-grants it, and a sweep finishing after that re-grant then retires the
     * live mirror's records instead of the dead one's. That loses the very distrust
     * that stops the next sync copying a truncated device document over the mirror's
     * only complete copy. Clearing under the discarded path cannot reach a live mirror,
     * because nothing else is ever filed there.
     *
     * The delete is [com.vscodroid.util.StorageManager.deleteRecursive] rather than
     * `File.deleteRecursively`, which asks `isDirectory` and `listFiles` and therefore
     * descends through a symlink pointing out of the mirror. On the launch pass that
     * was masked by [SafSyncEngine.holdsOnlyVouchedCopies] refusing any mirror holding
     * a link; a removal the user confirms has no such gate, and a mirror is routinely
     * a checked-out repository, so a link inside one is ordinary.
     *
     * Success is read off the filesystem rather than from a return value, because the
     * two callers need the same answer and one of them counts it.
     */
    private fun finishOff(discarded: File, originalPath: File): Boolean {
        StorageManager.deleteRecursive(discarded)
        if (discarded.exists()) {
            Logger.w(tag, "Could not finish removing ${discarded.name}; the next launch retries")
            return false
        }
        syncEngine.clearUploadsUnder(discarded)
        // The second clear is for entries set aside before the records learned to move
        // with the directory: theirs are still filed under the name the mirror had, and
        // nothing else will ever retire them. Guarded on the path being free, because
        // that is exactly the case this cannot tell apart. If the folder has been
        // re-granted, the records under that name may be the new mirror's, and the two
        // mistakes are not equal: keeping a dead record makes the next sync write a
        // mirror over a device copy that matches it anyway, while dropping a live one
        // lets a truncated device document overwrite the only complete copy. So the
        // ambiguous case keeps them, and the next removal of that mirror clears them
        // under its own discarded name.
        if (!originalPath.exists()) syncEngine.clearUploadsUnder(originalPath)
        return true
    }

    /** [setAside] then [finishOff], for a caller removing one entry on its own. */
    private fun discardEntry(root: File, name: String): Boolean {
        val originalPath = File(root, name.removePrefix(DISCARD_PREFIX))
        val discarded = setAside(root, name) ?: return false
        return finishOff(discarded, originalPath)
    }

    // -- Device folder storage, as the user sees it --

    /**
     * Every mirror on disk, with its size and whether the launch pass would remove it.
     *
     * This exists because the count budget the app already has produces a result
     * nobody can see. [addToRecentFolders] keeps [MAX_RECENT] folders and releases the
     * grant of the one that falls off, which is what makes its mirror a candidate for
     * [reclaimRevokedMirrorsSync]. But that pass then almost always declines, and it
     * declines hardest on the mirrors worth the most disk: a mirror gets large by being
     * worked in, and working in one creates files the sync record cannot vouch for.
     * [SafSyncEngine.SKIP_DIRECTORIES] keeps `node_modules`, `.git`, `__pycache__` and
     * `.gradle` out of that record by construction, so a single `npm install` inside a
     * device folder makes its mirror permanently unreclaimable. Its recent-list entry
     * went with its grant, so it also has no name anywhere in the app, only a hash.
     *
     * So the missing authority is the user's, not the app's, and this is the listing
     * that lets them exercise it.
     *
     * Only entries of the shape [getMirrorDir] produces are reported, for the reason
     * the launch pass gives: `saf-mirrors` is exported into every terminal as
     * `SAF_MIRRORS_DIR` and published as a WebView resource root, so a person can leave
     * a file there and some will. [isMirrorDirectoryName] is the predicate, and it is
     * narrower than [MIRROR_ENTRY]: that pattern matches the `<hash>.synced` record
     * too, because the launch pass has to recognise both halves of a mirror, and here
     * the record is not a row. A `discarded-` entry fails it outright, which is what
     * keeps a removal already in progress from being offered as a folder to remove.
     *
     * ⚠️ **Two full walks of every mirror**, one to size it and one to vouch for it, so
     * this must not run on the main thread. The count is bounded by what is on disk
     * rather than by [MAX_RECENT]: an orphan outlives its grant, which is the whole
     * reason this method exists.
     *
     * The upload journal is read once for the listing rather than per mirror, as the
     * launch pass does, so that every row is judged against one reading of it.
     */
    fun listMirrors(): List<MirrorInfo> {
        val root = File(com.vscodroid.util.Environment.getSafMirrorsDir(context))
        val entries = root.listFiles()?.filter {
            it.isDirectory && isMirrorDirectoryName(it.name)
        } ?: return emptyList()

        val granted = context.contentResolver.persistedUriPermissions
            .map { getMirrorDir(it.uri).name }
            .toSet()
        val named = getPersistedFolders().associateBy { getMirrorDir(it.uri).name }
        val stranded = syncEngine.uploadsInFlight()

        return entries.map { dir ->
            MirrorInfo(
                hash = dir.name,
                displayName = named[dir.name]?.displayName,
                bytes = StorageManager.dirSize(dir),
                lastOpened = named[dir.name]?.lastOpened ?: 0L,
                granted = dir.name in granted,
                reclaimable = mayReclaim(dir.name, stranded, root.absolutePath) &&
                    syncEngine.holdsOnlyVouchedCopies(dir),
            )
        }.sortedByDescending { it.bytes }
    }

    /**
     * Gives up the grant on the folder [hash] mirrors, and drops it from the recent
     * list.
     *
     * Called as part of removing a mirror, so that the two records of the folder go
     * with it. A recent-list entry that survives its mirror is an Open Recent row
     * pointing at a directory that is not there, and a grant that survives it makes the
     * launch pass treat the next mirror of that folder as live.
     *
     * It runs once the rename that commits the removal has succeeded, and not before
     * it. A rename the filesystem refuses leaves the mirror exactly where it was, while
     * nothing here can hand a released grant back: only the user re-picking the folder
     * can. Releasing first therefore turned a removal that did not happen into a folder
     * the app can no longer name, open or reclaim.
     *
     * A grant the system has already dropped is not an error here: the folder may have
     * been revoked in system settings, which is one of the ways a mirror becomes an
     * orphan in the first place.
     */
    fun releaseGrantFor(hash: String) {
        context.contentResolver.persistedUriPermissions
            .filter { getMirrorDir(it.uri).name == hash }
            .forEach { held ->
                try {
                    context.contentResolver.releasePersistableUriPermission(
                        held.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (e: SecurityException) {
                    Logger.d(tag, "Grant for $hash was already gone")
                }
            }
        synchronized(recentFoldersLock) {
            saveRecentFolders(getPersistedFolders().filterNot { getMirrorDir(it.uri).name == hash })
        }
    }

    /**
     * Removes one mirror the user has asked to remove, and reports what that freed.
     *
     * The gate is the same one [reclaimRevokedMirrorsSync] applies and is re-asked
     * here rather than taken from [listMirrors]: the listing is rendered, read and
     * confirmed by a person, and a write-back can strand a file in between.
     *
     * [force] is what the user's confirmation buys, and it is the only thing that ever
     * passes a mirror the gate refuses. Refusing outright was the alternative and it is
     * not one: the mirrors the gate refuses are exactly the large ones, so an
     * unforceable removal leaves the user looking at the disk they cannot reclaim. What
     * [force] must never become is a default, because the files it removes are the ones
     * this app never delivered to the device. The caller is responsible for saying so
     * in as many words before setting it, and for refusing outright when the mirror is
     * in use, which nothing here can see.
     *
     * **This method only sets the mirror aside; [sweepDiscardedMirrors] does the
     * deleting.** The split is not tidiness, it is what makes the answer honest. Both
     * entries are renamed first, which is atomic and instant, and from that moment the
     * mirror is unreachable: nothing can open it, a re-grant of the folder gets a fresh
     * directory, and the next launch pass finishes the deletion whatever happens to
     * this process. The recursive delete of a project tree is not instant, and the
     * caller reaching this is a bridge call whose promise in the extension host times
     * out after five seconds, so doing it here would report a failure for a removal
     * that had in fact succeeded.
     *
     * Both entries go together, and it matters which way an interruption falls. A
     * directory left behind with its record deleted can never be vouched for again; a
     * record left behind without its directory makes a later re-grant of the same
     * folder read the device's own document as this app's interrupted upload. Renaming
     * both before deleting either means an exit added between the phases leaves a pair
     * of `discarded-` entries, which is a state the existing pass already resolves.
     *
     * ⚠️ Walks the mirror to size it, so it must not run on the main thread.
     *
     * @return the bytes the removal frees, or [RECLAIM_UNKNOWN] when no such mirror
     *   exists, or [RECLAIM_REFUSED] when the gate declined and [force] was not set, or
     *   [RECLAIM_FAILED] when the rename that commits the removal was refused.
     */
    fun reclaimMirror(hash: String, force: Boolean): Long {
        val root = File(com.vscodroid.util.Environment.getSafMirrorsDir(context))
        val dir = File(root, hash)
        // [hash] arrives from the page, so it is judged rather than trusted, and by the
        // same predicate that produced the listing it came from.
        if (!isMirrorDirectoryName(hash) || !dir.isDirectory) return RECLAIM_UNKNOWN
        if (!force &&
            !(mayReclaim(hash, syncEngine.uploadsInFlight(), root.absolutePath) &&
                syncEngine.holdsOnlyVouchedCopies(dir))
        ) {
            return RECLAIM_REFUSED
        }

        val bytes = StorageManager.dirSize(dir)
        // The directory's rename is the commit point of the removal, so the directory's
        // rename is what decides whether one happened. Answering with the byte count
        // whatever the rename did reported a folder that is still on disk as reclaimed,
        // and by then the grant and the recent-list row were already gone, which is the
        // one state nothing in the app can undo or even name afterwards.
        //
        // Only the directory may decide. [setAside] answers null for an entry that is
        // not there as well as for a rename it could not make, and the record beside a
        // mirror is legitimately absent: it is written at the end of a sync, so a sync
        // that was interrupted leaves the directory without one.
        if (setAside(root, hash) == null) return RECLAIM_FAILED
        setAside(root, hash + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        releaseGrantFor(hash)
        Logger.i(tag, "Set a device folder's local copy aside at the user's request")
        return bytes
    }

    /**
     * Deletes everything an earlier removal renamed out of the way, and reports how
     * many entries went.
     *
     * The second half of [reclaimMirror], and safe to call at any time from any
     * thread: a `discarded-` entry is this app's own leftover, already unreachable and
     * already judged, so there is no gate left to apply and nothing to race with. Only
     * names this app produces are touched, for the reason [reclaimRevokedMirrorsSync]
     * gives about `saf-mirrors` not being private scratch space.
     *
     * Failing part way costs disk until the next launch rather than correctness, which
     * is why the caller is free to run it on a thread it does not wait for.
     */
    fun sweepDiscardedMirrors(): Int {
        val root = File(com.vscodroid.util.Environment.getSafMirrorsDir(context))
        val leftovers = root.listFiles()?.map { it.name }?.filter {
            it.startsWith(DISCARD_PREFIX) && MIRROR_ENTRY.matches(it.removePrefix(DISCARD_PREFIX))
        } ?: return 0
        return leftovers.count { discardEntry(root, it) }
    }

    // -- Sync Coordination --

    /**
     * Syncs the contents of a SAF folder tree to a local mirror directory.
     *
     * @param safUri The tree URI from the SAF folder picker.
     * @param onProgress Callback with (filesDone, totalFiles) for progress reporting.
     * @return The local mirror directory containing the synced files.
     * @throws SecurityException if the URI permission has been revoked.
     */
    suspend fun syncToLocal(
        safUri: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): File {
        if (!hasPersistedPermission(safUri)) {
            // Named by its mirror, like every other line about this folder. The
            // interpolation here is the one channel redacting the log lines could
            // not close: `MainActivity.openSafFolder` catches this and passes the
            // throwable itself to `Logger.e`, which prints the message, and none
            // of the three severities is gated on a debuggable build. Redacting
            // the caller alone would have left the same tree URI arriving by the
            // other route.
            throw SecurityException("Permission revoked for ${getMirrorDir(safUri).name}")
        }

        val mirrorDir = getMirrorDir(safUri)
        mirrorDir.mkdirs()

        syncEngine.initialSync(safUri, mirrorDir, onProgress)
        updateLastOpened(safUri)

        // By mirror name, for the reason [persistPermission] gives: the tree URI is the
        // user's own directory path and this level ships.
        Logger.i(tag, "Sync complete for ${mirrorDir.name}")
        return mirrorDir
    }

    /**
     * Starts a FileObserver on the mirror directory that writes changes back to SAF.
     */
    fun startFileWatcher(localMirrorDir: File, safUri: Uri) {
        syncEngine.startWatching(localMirrorDir, safUri)
        Logger.i(tag, "File watcher started for: ${localMirrorDir.absolutePath}")
    }

    /**
     * Stops the active file watcher. Call this when switching folders or on destroy.
     */
    fun stopFileWatcher() {
        syncEngine.stopWatching()
    }

    // -- Mirror Directory --

    /**
     * Returns a deterministic local directory for mirroring a SAF URI.
     * Delegates to [Environment.getSafMirrorDir] for consistent path resolution.
     */
    fun getMirrorDir(safUri: Uri): File {
        return File(com.vscodroid.util.Environment.getSafMirrorDir(context, safUri))
    }

    /**
     * Resolves a human-readable display name for a SAF tree URI.
     */
    fun getDisplayName(safUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(safUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(safUri, docId)
            val cursor = context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: safUri.lastPathSegment ?: "Unknown"
        } catch (e: Exception) {
            Logger.d(tag, "Failed to resolve display name: ${e.message}")
            safUri.lastPathSegment ?: "Unknown"
        }
    }

    // -- Internal --

    private fun addToRecentFolders(uri: Uri) {
        // Resolved before the lock: it is a provider round trip, and a network or MTP
        // provider can make it a long one. Nothing in the list is read to compute it.
        val name = getDisplayName(uri)
        val dropped = synchronized(recentFoldersLock) {
            val folders = getPersistedFolders().toMutableList()

            // Remove existing entry for this URI (will re-add with updated timestamp)
            folders.removeAll { it.uri == uri }

            folders.add(
                0,
                SafFolderInfo(
                    uri = uri,
                    displayName = name,
                    lastOpened = System.currentTimeMillis(),
                    mirrorPath = getMirrorDir(uri).absolutePath
                )
            )

            // Keep at most MAX_RECENT entries
            val (trimmed, evicted) = splitRecent(folders, MAX_RECENT)
            saveRecentFolders(trimmed)
            evicted
        }

        // The grant goes with the list entry, and until it did there was no way
        // out at all: the reclaim pass judges a mirror by whether a permission is
        // still persisted, so a folder that fell off this list kept its grant,
        // looked live for ever, and its mirror could never be reclaimed by
        // anything the app does. Nothing in the UI removes a folder either.
        //
        // Safe to do here only because of what the reclaim pass refuses to delete,
        // and that clause has been wrong once already. It used to read "a mirror
        // holding a write that never reached the device", meaning the upload
        // journal, which records write-backs that were ATTEMPTED and failed. A
        // file never queued for write-back at all leaves no journal entry, and
        // several routes end there: anything under SKIP_DIRECTORIES, so a `.git`
        // from a terminal clone; a file below a directory past the watch cap;
        // anything written while no watcher ran; and a copy the initial sync kept
        // for being newer than the device document. Every one of those is the
        // user's only copy, and eviction at the eleventh folder deleted it.
        //
        // The gate now asks the record instead, which has to prove a mirror is
        // disposable rather than look for evidence that it is not. See
        // SafSyncEngine.holdsOnlyVouchedCopies.
        for (gone in dropped) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    gone.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                Logger.i(tag, "Released the grant for a folder that left the recent list")
            } catch (e: SecurityException) {
                Logger.d(tag, "Grant already gone for ${gone.displayName}")
            }
        }
    }

    private fun updateLastOpened(uri: Uri) = synchronized(recentFoldersLock) {
        val folders = getPersistedFolders().toMutableList()
        val index = folders.indexOfFirst { it.uri == uri }
        if (index >= 0) {
            folders[index] = folders[index].copy(lastOpened = System.currentTimeMillis())
            saveRecentFolders(folders)
        }
    }

    private fun saveRecentFolders(folders: List<SafFolderInfo>) {
        val array = JSONArray()
        folders.forEach { f ->
            array.put(JSONObject().apply {
                put("uri", f.uri.toString())
                put("name", f.displayName)
                put("lastOpened", f.lastOpened)
            })
        }
        prefs.edit().putString(KEY_RECENT_FOLDERS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "vscodroid_saf"
        private const val KEY_RECENT_FOLDERS = "recent_folders"
        private const val MAX_RECENT = 10

        /**
         * Serialises every read-modify-write of the recent list.
         *
         * The list is one JSON string in one preference, and four operations rewrite it
         * from a value they read first: the prune inside [getPersistedFolders],
         * [addToRecentFolders], [updateLastOpened] and [releaseGrantFor]. Three threads
         * reach those. The UI thread picks a folder, a Dispatchers.IO thread finishes a
         * sync, and the WebView's bridge thread asks for the recent list and removes
         * device-folder copies. Interleaved, the later writer saves a list it read
         * before the earlier one wrote: a folder just picked drops out of Open Recent,
         * or a folder whose mirror has just been removed keeps a row pointing at a
         * directory that is not there.
         *
         * In the companion, like [SafSyncEngine]'s journal lock and for the reason given
         * there: this class is one per Activity, a recreated Activity builds a second one
         * while the first one's sync is still finishing on the IO dispatcher, and both
         * address the one preferences file.
         */
        private val recentFoldersLock = Any()

        /**
         * How many persisted grants this process has taken, counted only so that two
         * readings of them can be told apart.
         *
         * The prune inside [getPersistedFolders] judges the saved list against a reading
         * of the system server's grants, and that reading is deliberately taken outside
         * [recentFoldersLock], so an ordinary listing does not hold the monitor across a
         * binder round trip. A call nested inside one of the read-modify-writes still
         * does, and [getPersistedFolders] says which ones and what it costs.
         * Taking the reading first leaves one ordering the lock used to forbid: a
         * reading older than the list
         * it is judging, which is what a grant taken between the two produces, and which
         * would prune away the row the picker has just written. This is bumped between
         * the grant and the row (see [persistPermission]), so a reader that took its
         * reading before the grant sees a different value once it holds the monitor, and
         * declines to judge.
         *
         * Only a grant TAKEN is counted. A grant released moves the other way: the
         * reading still names it, so the row survives one more listing and the next one
         * prunes it, which is the same one-listing delay a revocation in system settings
         * already has.
         *
         * Never reset, so no path can leave it stale: it counts up, comparisons are for
         * equality between two reads on one thread, and the whole of it dies with the
         * process. In the companion for the reason [recentFoldersLock] gives, and because
         * the grants it counts belong to the process rather than to one activity's
         * manager.
         */
        private val grantsTaken = AtomicLong(0)

        /**
         * Whether a mirror with no live permission may be deleted, given the writes
         * this app records as never having reached the device.
         *
         * Keyed on the mirror directory rather than on individual files: one
         * stranded write is enough to make the whole tree the only copy of
         * something, and the pass deletes trees, not files.
         *
         * The journal holds absolute paths, so the comparison is built from the
         * mirrors root and the entry name with a separator between them. Without
         * the separator a mirror named `abc123` would be protected by a stranded
         * write under `abc123def`.
         */
        internal fun mayReclaim(
            mirrorName: String,
            strandedPaths: Set<String>,
            mirrorsRoot: String,
        ): Boolean {
            val prefix = mirrorsRoot + File.separator + mirrorName + File.separator
            return strandedPaths.none { it.startsWith(prefix) }
        }

        /**
         * Answers of [reclaimMirror] that are not a byte count.
         *
         * [RECLAIM_FAILED] is separate from the other two because the folder it names
         * is untouched rather than gone: the gate passed, the mirror is where it was,
         * and asking again is worth something. Folding it into either of the others
         * would tell the user the copy is not there, or that it holds work the device
         * does not, neither of which is true.
         */
        internal const val RECLAIM_REFUSED = -1L
        internal const val RECLAIM_UNKNOWN = -2L
        internal const val RECLAIM_FAILED = -3L

        /**
         * Why a mirror the user asked to remove must not be removed now, as the string
         * resource that says so, or null when nothing this side knows about stands in
         * the way.
         *
         * The question is "is anything still using this mirror", and it is separate
         * from the question [mayReclaim] and [SafSyncEngine.holdsOnlyVouchedCopies]
         * answer, which is "would removing it lose anything". Both have to be asked,
         * and only this one needs state that lives in the Activity, which is why it
         * takes its inputs rather than reading them.
         *
         * A mirror the editor still has open is not an inconvenience to delete, it is
         * device data loss. The observers are live: a `FileObserver.DELETE` reaches
         * `handleMirrorEvent`, becomes a DELETE write-back job and calls `deleteFromSaf`
         * on the matching document, so a recursive delete of a watched mirror is
         * replayed onto the user's real files through the provider. Nothing about the
         * delete looks unusual from the watcher's side; a person deleting files is
         * exactly what it exists to carry across.
         *
         * [watchedThisProcess] is the wide one and the others are narrower cases of it
         * that produce a better sentence. It is deliberately never cleared. Closing a
         * folder stops its observers, but `SafSyncEngine.stopWatching` waits only
         * `DRAIN_GRACE_MS` for the write-back worker and then leaves it running rather
         * than throwing away writes the user is expecting on the device, so a drain can
         * still be streaming out of a mirror the app considers closed, and every write
         * it performs opens the device document with `"wt"`, which truncates at open. A
         * drain only ever touches the mirror it was started for, so refusing every
         * mirror this process has watched is what puts it out of reach, and a restart
         * is what makes the folder removable. Removing any of the three narrower checks
         * degrades the message rather than opening the hole; removing this one opens
         * it.
         */
        @StringRes
        internal fun reclaimRefusal(
            hash: String,
            watchedMirror: String?,
            syncingMirror: String?,
            openWorkspaceMirror: String?,
            watchedThisProcess: Set<String>,
        ): Int? = when (hash) {
            watchedMirror -> RECLAIM_FOLDER_OPEN
            syncingMirror -> RECLAIM_FOLDER_OPENING
            openWorkspaceMirror -> RECLAIM_FOLDER_OPEN
            in watchedThisProcess -> RECLAIM_FOLDER_THIS_SESSION
            else -> null
        }

        /**
         * The three refusals above, as string resources rather than sentences.
         *
         * They cross the bridge and the bundled extension shows whichever comes
         * back verbatim, so they are user-facing text, and a sentence written in
         * Kotlin is the same in every locale for ever. Nothing reported that:
         * `check-translatable-strings.py` finds a literal only where the literal
         * is written at the sink, and these leave through a return value, which
         * its own docstring names as the hole it has.
         *
         * The ids and not the text, so this stays a pure predicate a JVM test can
         * exercise with no Context: what belongs to which case is decided here and
         * the words are resolved by the Activity that has one. Resource ids are
         * never zero, so null still means "no refusal".
         */
        @StringRes
        internal val RECLAIM_FOLDER_OPEN = R.string.saf_mirror_folder_open
        @StringRes
        internal val RECLAIM_FOLDER_OPENING = R.string.saf_mirror_folder_opening
        @StringRes
        internal val RECLAIM_FOLDER_THIS_SESSION = R.string.saf_mirror_folder_this_session

        /**
         * The mirror [path] sits in, or null when it is not under [mirrorsRoot].
         *
         * The separator rule is [folderForOpenedPath]'s and is load-bearing for the
         * same reason: mirror names are a hash prefix, so one being a prefix of another
         * is ordinary, and a bare `startsWith` would name the wrong folder. Here the
         * cost of naming the wrong one is refusing to remove a mirror that is free
         * while allowing one that is open.
         */
        internal fun mirrorNameFor(path: String?, mirrorsRoot: String): String? {
            val prefix = mirrorsRoot + File.separator
            if (path == null || !path.startsWith(prefix)) return null
            return path.removePrefix(prefix).substringBefore(File.separatorChar)
                .takeIf { it.isNotEmpty() }
        }

        /**
         * Splits the recent list into what is kept and what falls off the end.
         *
         * Returned as a pair rather than trimmed in place because the tail is not
         * waste: each entry there still holds a persisted permission, and that
         * grant is what the reclaim pass reads to decide a mirror is still in
         * use. Dropping the entry without releasing the grant leaves the mirror
         * permanently unreclaimable.
         */
        internal fun splitRecent(
            folders: List<SafFolderInfo>,
            max: Int,
        ): Pair<List<SafFolderInfo>, List<SafFolderInfo>> =
            folders.take(max) to folders.drop(max)

        /**
         * The folder in [folders] whose mirror contains [opened], if any. The one
         * production caller passes the persisted list.
         *
         * The subdirectory case is not a nicety. Open Folder can point at a
         * directory *inside* a mirror, and the watcher's root has to be the
         * mirror root or every relative path the sync computes is resolved
         * against the wrong base.
         *
         * The separator matters for the same reason it does in the reclaim gate:
         * mirror names are a hash prefix, so one being a prefix of another is
         * ordinary, and a bare `startsWith` would match the wrong folder.
         */
        internal fun folderForOpenedPath(
            folders: List<SafFolderInfo>,
            opened: String,
        ): SafFolderInfo? = folders.firstOrNull {
            opened == it.mirrorPath || opened.startsWith(it.mirrorPath + File.separator)
        }

        /**
         * An entry in `saf-mirrors` that this app created: a mirror directory, or the
         * sync record beside it.
         *
         * [com.vscodroid.util.Environment.getSafMirrorDir] names a mirror after the first
         * six bytes of a digest, so twelve hex characters. Pinned by
         * `SafMirrorReclamationTest`, because the length lives there and the consequence
         * of the two drifting apart is a reclamation pass that stops recognising its own
         * mirrors, or starts recognising files it did not write.
         */
        internal val MIRROR_ENTRY =
            Regex("^[0-9a-f]{12}(${Regex.escape(SafSyncEngine.SYNCED_RECORD_SUFFIX)})?$")

        /**
         * Marks an entry renamed out of the way and awaiting deletion. Reclaimed
         * unconditionally on a later pass: nothing but this method creates one, and
         * whatever it named is already unreachable.
         */
        internal const val DISCARD_PREFIX = "discarded-"

        /**
         * Whether [name] is a mirror directory rather than anything else in
         * `saf-mirrors`.
         *
         * Narrower than [MIRROR_ENTRY] by exactly one case, and the difference is why
         * this is a predicate rather than a use of that pattern. The pattern matches
         * both `<hash>` and `<hash>.synced`, because the reclaim pass has to recognise
         * both halves of a mirror and remove them together. The user-facing side has
         * the opposite need: the record is not a folder anybody opened, so listing it
         * claims two folders where there is one, and accepting it as a removal target
         * would set aside a record while leaving its mirror.
         *
         * Not covered by an `isDirectory` test at the call sites, which is the tempting
         * simplification and was measured to be wrong: `saf-mirrors` is exported into
         * every terminal as `SAF_MIRRORS_DIR`, so a directory can be created there
         * under any name at all, this one included.
         */
        internal fun isMirrorDirectoryName(name: String): Boolean =
            MIRROR_ENTRY.matches(name) && !name.endsWith(SafSyncEngine.SYNCED_RECORD_SUFFIX)

        /** How long one write-back failure notice suppresses the next. */
        internal const val FAILURE_NOTICE_INTERVAL_MS = 10_000L

        /**
         * The stamp of a session in which nothing has been announced yet.
         *
         * Tested for rather than subtracted from, and that became necessary with the
         * clock. Under wall time zero was further back than any interval on its own;
         * under the monotonic clock the reading is milliseconds since boot, so a device
         * that has been up for less than the interval would have found the FIRST failure
         * of a session too close to zero to be worth saying, which is the one notice
         * that always has to be given.
         */
        internal const val NEVER_ANNOUNCED = 0L

        /**
         * Whether a failure at [now] is far enough from [lastAnnouncedAt] to be said.
         *
         * A function rather than an inline comparison so the rule can be asserted: the
         * wiring around it reaches a Toast, which no JVM test here can see.
         *
         * [now] comes from the monotonic clock; see [onWriteBackFailed] for why it is
         * not the wall clock.
         */
        internal fun shouldAnnounce(now: Long, lastAnnouncedAt: Long): Boolean =
            lastAnnouncedAt == NEVER_ANNOUNCED ||
                now - lastAnnouncedAt >= FAILURE_NOTICE_INTERVAL_MS
    }
}

/**
 * Data class representing a SAF folder that the user has granted access to.
 */
data class SafFolderInfo(
    val uri: Uri,
    val displayName: String,
    val lastOpened: Long,
    val mirrorPath: String
)

/**
 * One device folder's local copy, as the storage screen has to describe it.
 *
 * [displayName] and [lastOpened] come from the recent list and are therefore absent
 * for an orphan: the grant and the list entry are released together when a folder
 * falls off the end of [SafStorageManager.MAX_RECENT], so the mirror that survives
 * has no name anywhere in the app. That is precisely the mirror worth showing, so
 * the absence is carried rather than filled in with the hash.
 *
 * [reclaimable] is the launch pass's own verdict, not a prediction of what a removal
 * will do. False means the mirror holds files the device folder does not, so removing
 * it destroys the only copy of them; it is the ordinary state of any folder somebody
 * has run a build or a clone in.
 */
data class MirrorInfo(
    val hash: String,
    val displayName: String?,
    val bytes: Long,
    val lastOpened: Long,
    val granted: Boolean,
    val reclaimable: Boolean,
)
