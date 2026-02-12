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
     * Folders whose permissions have been externally revoked are automatically pruned.
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
                cleanupMirror(uri)
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
    }

    companion object {
        private const val PREFS_NAME = "vscodroid_saf"
        private const val KEY_RECENT_FOLDERS = "recent_folders"
        private const val MAX_RECENT = 10
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
