package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    /**
     * Releases a persisted URI permission and removes the folder from recent list.
     * Also cleans up the corresponding local mirror directory.
     */
    fun revokePermission(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            Logger.d(tag, "Permission already revoked for: $uri")
        }
        removeFromRecentFolders(uri)
        cleanupMirror(uri)
        Logger.i(tag, "Revoked permission and cleaned up: $uri")
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
     * and in [revokePermission] now.
     */
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
        val live = context.contentResolver.persistedUriPermissions
            .map { getMirrorDir(it.uri).name }
            .toSet()
        val root = File(com.vscodroid.util.Environment.getSafMirrorsDir(context))
        var removed = 0

        root.listFiles()?.forEach { entry ->
            val name = entry.name
            // The prefix alone is not enough to claim an entry: a person can name a
            // directory anything, and this one is only ours when what follows the prefix
            // is a name we would have set aside.
            val alreadySetAside = name.startsWith(DISCARD_PREFIX) &&
                MIRROR_ENTRY.matches(name.removePrefix(DISCARD_PREFIX))
            val reclaimable = alreadySetAside ||
                (MIRROR_ENTRY.matches(name) && name.substringBefore('.') !in live)
            if (!reclaimable) return@forEach

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
            if (discarded.deleteRecursively()) removed++
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
        val trimmed = folders.take(MAX_RECENT)
        saveRecentFolders(trimmed)
    }

    private fun removeFromRecentFolders(uri: Uri) {
        val folders = getPersistedFolders().toMutableList()
        folders.removeAll { it.uri == uri }
        saveRecentFolders(folders)
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

    private fun cleanupMirror(safUri: Uri) {
        val mirrorDir = getMirrorDir(safUri)
        if (mirrorDir.exists()) {
            try {
                mirrorDir.deleteRecursively()
                Logger.i(tag, "Cleaned up mirror: ${mirrorDir.absolutePath}")
            } catch (e: Exception) {
                Logger.w(tag, "Failed to clean up mirror: ${e.message}")
            }
        }
        // The sync engine keeps its record of the folder beside the mirror rather than
        // inside it, so removing the mirror alone leaves the record orphaned.
        File(mirrorDir.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
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
        internal val MIRROR_ENTRY =
            Regex("^[0-9a-f]{12}(${Regex.escape(SafSyncEngine.SYNCED_RECORD_SUFFIX)})?$")

        /**
         * Marks an entry renamed out of the way and awaiting deletion. Reclaimed
         * unconditionally on a later pass: nothing but this method creates one, and
         * whatever it named is already unreachable.
         */
        internal const val DISCARD_PREFIX = "discarded-"
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
