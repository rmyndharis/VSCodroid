package com.vscodroid.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
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
class SafStorageManager(private val context: Context) {

    private val tag = "SafStorageManager"
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val syncEngine = SafSyncEngine(context)

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
     */
    fun onWriteBackFailed(announce: (File) -> Unit) {
        syncEngine.onWriteBackFailed = { file ->
            val now = System.currentTimeMillis()
            val last = lastFailureAnnouncedAt.get()
            // `compareAndSet`, not read-then-write. Two threads genuinely arrive here:
            // the `saf-writeback` daemon draining the queue, and `Dispatchers.IO`
            // running the write-backs `initialSync` issues itself. `@Volatile` gave
            // visibility but not atomicity, so both could read the same stale `last`,
            // both find the interval elapsed, and both announce for one burst, which
            // is the wall of toasts the throttle exists to prevent.
            //
            // The loser does not retry, deliberately. Losing means another thread has
            // just announced this same burst, which is the answer the user needed.
            if (shouldAnnounce(now, last) && lastFailureAnnouncedAt.compareAndSet(last, now)) {
                announce(file)
            }
        }
    }

    private val lastFailureAnnouncedAt = AtomicLong(0)

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
            addToRecentFolders(uri)
            Logger.i(tag, "Persisted permission for: $uri")
        } catch (e: SecurityException) {
            Logger.e(tag, "Failed to persist permission for: $uri", e)
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
     * Returns the list of recently opened SAF folders with persisted permissions.
     * Folders whose permissions have been externally revoked are pruned from the list.
     *
     * Pruning the list is all this does. Deleting the mirror of a pruned folder used to
     * happen here too, which put a recursive delete of the user's files inside a method
     * the workbench calls whenever it wants the recent list — and made a permission that
     * read as absent for a moment enough to take the mirror of the folder currently open
     * in the editor out from under it. That reclamation lives in [reclaimRevokedMirrors]
     * alone now.
     */
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

    fun getPersistedFolders(): List<SafFolderInfo> {
        val json = prefs.getString(KEY_RECENT_FOLDERS, "[]") ?: "[]"
        val array = JSONArray(json)
        val result = mutableListOf<SafFolderInfo>()
        val toRemove = mutableListOf<Int>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val uri = Uri.parse(obj.getString("uri"))

            // Prune folders whose permissions have been revoked externally
            if (!hasPersistedPermission(uri)) {
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
     * ordering requirement against the read — a list already pruned no longer names the
     * folders whose mirrors are stale, and an orphan left by a cleared list or a crashed
     * sync is not in the list at all.
     *
     * Deletes the sync record beside each mirror too: both are named after the same
     * hash, the record with a suffix.
     *
     * Call it where no folder is open. Nothing here can tell which mirror the editor is
     * holding, so the call site is what keeps it away from one — see
     * [com.vscodroid.SplashActivity], which always precedes `MainActivity`.
     *
     * Returns immediately. The scan itself is a handful of stats, but what it can find
     * is a mirror of a whole project, and deleting one of those is a recursive delete of
     * thousands of files. Its caller is the launch-time repair block in
     * [com.vscodroid.SplashActivity], which runs on the main thread before anything is
     * drawn — the same reason `repairInstalledToolchains` hands its walk off there.
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
     * **A candidate is set aside before it is deleted.** The obvious version — delete in
     * place — rests on nothing else touching that directory meanwhile, and that does not
     * hold: this runs on a detached thread, so it outlives the splash screen that starts
     * it, and its duration is proportional to the mirror it is deleting. The user can
     * reach `MainActivity`, re-grant the same folder and re-sync it into the directory
     * the walk is still inside; the walk's remaining deletes then land on the new copy,
     * under a running watcher, and go out to the device as deletions of the user's real
     * documents. Renaming is atomic and instant, so a folder granted a moment later gets
     * a fresh directory this pass cannot reach. A rename that survives a killed process
     * is reclaimed by the next pass.
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

            // The journal keys its entries on the mirror's real path, which is
            // the name before this pass renames it. `removePrefix` is a no-op on
            // the branch that has not been renamed yet, so one expression serves
            // both. Passing the `discarded-` path instead matched no entry at
            // all, so the records outlived the mirror they distrust, and a later
            // re-grant of the same folder read the device's own document as this
            // app's interrupted upload and wrote the stale mirror back over it.
            val originalPath = File(root, name.removePrefix(DISCARD_PREFIX))

            val discarded: File
            if (alreadySetAside) {
                discarded = entry
            } else {
                val target = File(root, DISCARD_PREFIX + name)
                if (!entry.renameTo(target)) {
                    Logger.w(tag, "Could not set $name aside; leaving it in place")
                    return@forEach
                }
                discarded = target
            }
            if (discarded.deleteRecursively()) {
                removed++
                // The mirror's distrust of its own device copies goes with it; see
                // [SafSyncEngine.clearUploadsUnder] for what an entry that outlives
                // its mirror costs a later re-grant.
                syncEngine.clearUploadsUnder(originalPath)
            }
        }
        if (removed > 0) {
            Logger.i(tag, "Reclaimed $removed mirror entr(ies) without a live permission")
        }
        return removed
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
            throw SecurityException("Permission revoked for: $safUri")
        }

        val mirrorDir = getMirrorDir(safUri)
        mirrorDir.mkdirs()

        syncEngine.initialSync(safUri, mirrorDir, onProgress)
        updateLastOpened(safUri)

        Logger.i(tag, "Sync complete: $safUri → ${mirrorDir.absolutePath}")
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
        val folders = getPersistedFolders().toMutableList()

        // Remove existing entry for this URI (will re-add with updated timestamp)
        folders.removeAll { it.uri == uri }

        val name = getDisplayName(uri)
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
        val (trimmed, dropped) = splitRecent(folders, MAX_RECENT)
        saveRecentFolders(trimmed)

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

    private fun updateLastOpened(uri: Uri) {
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
         * An entry in `saf-mirrors` that this app created: a mirror directory, or the
         * sync record beside it.
         *
         * [com.vscodroid.util.Environment.getSafMirrorDir] names a mirror after the first
         * six bytes of a digest, so twelve hex characters. Pinned by
         * `SafMirrorReclamationTest`, because the length lives there and the consequence
         * of the two drifting apart is a reclamation pass that stops recognising its own
         * mirrors — or starts recognising files it did not write.
         */
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
         * The persisted folder whose mirror contains [opened], if any.
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

        internal fun folderForOpenedPath(
            folders: List<SafFolderInfo>,
            opened: String,
        ): SafFolderInfo? = folders.firstOrNull {
            opened == it.mirrorPath || opened.startsWith(it.mirrorPath + File.separator)
        }

        internal val MIRROR_ENTRY =
            Regex("^[0-9a-f]{12}(${Regex.escape(SafSyncEngine.SYNCED_RECORD_SUFFIX)})?$")

        /**
         * Marks an entry renamed out of the way and awaiting deletion. Reclaimed
         * unconditionally on a later pass: nothing but this method creates one, and
         * whatever it named is already unreachable.
         */
        internal const val DISCARD_PREFIX = "discarded-"

        /** How long one write-back failure notice suppresses the next. */
        internal const val FAILURE_NOTICE_INTERVAL_MS = 10_000L

        /**
         * Whether a failure at [now] is far enough from [lastAnnouncedAt] to be said.
         *
         * A function rather than an inline comparison so the rule can be asserted: the
         * wiring around it reaches a Toast, which no JVM test here can see. The first
         * failure of a session announces, because a zero last-announced time is further
         * back than any interval.
         */
        internal fun shouldAnnounce(now: Long, lastAnnouncedAt: Long): Boolean =
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
