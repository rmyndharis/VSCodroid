package com.vscodroid.storage

import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
 * Uses [FileObserver] to watch the mirror directory for changes. When a file
 * is modified, created, or deleted locally (by VS Code), the change is synced
 * back to the original SAF location via [ContentResolver].
 *
 * ## Conflict Resolution
 * Local changes win when both sides changed: the mirror is only replaced when the
 * source differs in size, carries no timestamp, or is newer than the copy already
 * held. External changes are picked up the next time the folder is opened, which is
 * the only refresh that exists — there is no "Refresh from device" action, and
 * nothing calls [com.vscodroid.util.StorageManager.clearSafMirrors] either, so a
 * mirror cannot be cleared from inside the app.
 */
class SafSyncEngine(private val context: Context) {

    private val tag = "SafSyncEngine"
    private val writeBackQueue = ConcurrentLinkedQueue<SyncJob>()
    private var fileObserver: RecursiveFileObserver? = null
    private var writeBackThread: Thread? = null
    @Volatile private var isWatching = false

    /**
     * Cache: relativePath → document ID. Built during [initialSync] and used for
     * O(1) write-back lookups instead of walking the tree for each event (Q1 fix).
     */
    private val docIdCache = mutableMapOf<String, String>()

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
        Logger.i(tag, "Starting initial sync: $safUri → ${mirrorDir.absolutePath}")
        val startTime = System.currentTimeMillis()

        // Clear cache for fresh sync
        docIdCache.clear()

        // Phase 1: Enumerate all documents in the tree
        val documents = mutableListOf<DocumentInfo>()
        val rootDocId = DocumentsContract.getTreeDocumentId(safUri)
        docIdCache[""] = rootDocId  // root entry
        walkTree(safUri, rootDocId, "", documents)

        val totalFiles = documents.count { !it.isDirectory }
        var filesDone = 0
        var skippedLarge = 0
        var keptLocal = 0

        Logger.i(tag, "Enumerated ${documents.size} items ($totalFiles files)")

        // Phase 2: Create directories and copy files
        for (doc in documents) {
            val localPath = File(mirrorDir, doc.relativePath)

            if (doc.isDirectory) {
                localPath.mkdirs()
            } else {
                // Q2: Skip files larger than MAX_FILE_SIZE
                if (doc.size > MAX_FILE_SIZE) {
                    skippedLarge++
                    Logger.d(tag, "Skipped large file: ${doc.relativePath} (${doc.size / 1_048_576}MB)")
                    filesDone++
                    onProgress(filesDone, totalFiles)
                    continue
                }
                if (!shouldOverwriteMirror(
                        localPath.exists(), localPath.lastModified(), localPath.length(),
                        doc.lastModified, doc.size
                    )
                ) {
                    keptLocal++
                    Logger.d(tag, "Kept newer local copy: ${doc.relativePath}")
                    filesDone++
                    onProgress(filesDone, totalFiles)
                    continue
                }
                localPath.parentFile?.mkdirs()
                copyDocumentToLocal(doc.uri, localPath, doc.lastModified)
                filesDone++
                onProgress(filesDone, totalFiles)
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Logger.i(
            tag,
            "Initial sync complete: $filesDone files ($skippedLarge too large, $keptLocal kept as newer) in ${elapsed}ms"
        )
    }

    // -- File Watching (Write-back) --

    /**
     * Starts watching the mirror directory for changes and syncing them back to SAF.
     * Must be called after [initialSync] completes.
     */
    fun startWatching(mirrorDir: File, safUri: Uri) {
        stopWatching()

        isWatching = true
        fileObserver = RecursiveFileObserver(mirrorDir, safUri).also {
            it.startWatching()
        }

        // Background thread to process write-back queue
        writeBackThread = thread(name = "saf-writeback", isDaemon = true) {
            while (isWatching) {
                val job = writeBackQueue.poll()
                if (job != null) {
                    processWriteBack(job)
                } else {
                    Thread.sleep(WRITEBACK_POLL_MS)
                }
            }
            // Drain remaining items before exiting so pending writes aren't lost
            var remaining = writeBackQueue.poll()
            while (remaining != null) {
                processWriteBack(remaining)
                remaining = writeBackQueue.poll()
            }
        }

        Logger.i(tag, "File watcher started for: ${mirrorDir.absolutePath}")
    }

    /**
     * Stops the file watcher and drains the write-back queue.
     */
    fun stopWatching() {
        isWatching = false
        fileObserver?.stopWatching()
        fileObserver = null
        // Wake thread from sleep, then wait for it to drain remaining writes
        writeBackThread?.interrupt()
        try { writeBackThread?.join(2000) } catch (_: InterruptedException) {}
        writeBackThread = null
        writeBackQueue.clear()
        docIdCache.clear()
        Logger.i(tag, "File watcher stopped")
    }

    // -- Internal: Tree Walking --

    /**
     * Recursively walks a SAF document tree, collecting [DocumentInfo] entries.
     */
    private fun walkTree(
        treeUri: Uri,
        parentDocId: String,
        parentRelPath: String,
        result: MutableList<DocumentInfo>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

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

                    val relativePath = if (parentRelPath.isEmpty()) name else "$parentRelPath/$name"
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                    // Skip hidden files and common large directories
                    if (shouldSkip(name, isDir)) continue

                    // Q1: Cache docId for fast write-back resolution
                    docIdCache[relativePath] = docId

                    result.add(DocumentInfo(docUri, docId, relativePath, isDir, size, lastModified))

                    if (isDir) {
                        walkTree(treeUri, docId, relativePath, result)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(tag, "Failed to enumerate children of $parentDocId: ${e.message}")
        }
    }

    /**
     * Skip patterns: large auto-generated directories that would slow sync unnecessarily.
     */
    private fun shouldSkip(name: String, isDir: Boolean): Boolean =
        Companion.shouldSkip(name, isDir)

    // -- Internal: File Operations --

    /**
     * Copies a single SAF document to a local file.
     */
    private fun copyDocumentToLocal(docUri: Uri, dest: File, sourceModified: Long) {
        try {
            context.contentResolver.openInputStream(docUri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                }
            }
            // Stamp the mirror with the source's own time so later syncs compare
            // two timestamps from the same clock rather than a provider's against
            // the local filesystem's.
            if (sourceModified > 0) dest.setLastModified(sourceModified)
        } catch (e: Exception) {
            // FileOutputStream truncates before the first byte arrives, so a failure
            // here leaves a short file behind. Remove it: a half copy carrying a fresh
            // mtime would otherwise be mistaken for a local edit worth keeping.
            dest.delete()
            Logger.w(tag, "Failed to copy ${docUri.lastPathSegment} → ${dest.name}: ${e.message}")
        }
    }

    /**
     * Writes a local file's contents back to its corresponding SAF document.
     */
    private fun writeLocalToSaf(localFile: File, safDocUri: Uri) {
        try {
            context.contentResolver.openOutputStream(safDocUri, "wt")?.use { output ->
                localFile.inputStream().use { input ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                }
            }
        } catch (e: SecurityException) {
            Logger.e(tag, "Permission revoked while writing back: ${localFile.name}")
        } catch (e: Exception) {
            Logger.w(tag, "Write-back failed for ${localFile.name}: ${e.message}")
        }
    }

    /**
     * Creates a new document in the SAF tree corresponding to a locally created file.
     */
    private fun createInSaf(localFile: File, parentSafUri: Uri, treeUri: Uri) {
        try {
            val mimeType = if (localFile.isDirectory) {
                DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                guessMimeType(localFile.name)
            }
            val newDocUri = DocumentsContract.createDocument(
                context.contentResolver, parentSafUri, mimeType, localFile.name
            )
            if (newDocUri != null && localFile.isFile) {
                writeLocalToSaf(localFile, newDocUri)
            }
            // Cache the new document ID for future write-back lookups
            if (newDocUri != null) {
                val newDocId = DocumentsContract.getDocumentId(newDocUri)
                val parentDocId = DocumentsContract.getDocumentId(parentSafUri)
                val parentRelPath = docIdCache.entries
                    .firstOrNull { it.value == parentDocId }?.key ?: ""
                val relPath = if (parentRelPath.isEmpty()) localFile.name
                    else "$parentRelPath/${localFile.name}"
                docIdCache[relPath] = newDocId
            }
        } catch (e: Exception) {
            Logger.w(tag, "Failed to create ${localFile.name} in SAF: ${e.message}")
        }
    }

    /**
     * Deletes a document from the SAF tree.
     */
    private fun deleteFromSaf(safDocUri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, safDocUri)
        } catch (e: Exception) {
            Logger.w(tag, "Failed to delete from SAF: ${e.message}")
        }
    }

    // -- Internal: Write-back Processing --

    private fun processWriteBack(job: SyncJob) {
        // Small debounce: skip if more recent job for same path exists
        if (writeBackQueue.any { it.localPath == job.localPath && it.timestamp > job.timestamp }) {
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
            }
        }
    }

    // -- FileObserver --

    /**
     * Recursive file observer that watches all subdirectories and enqueues
     * write-back jobs when files are modified, created, or deleted.
     *
     * Note: On API 29+, FileObserver supports watching entire directory trees
     * via the File constructor variant.
     */
    private inner class RecursiveFileObserver(
        private val rootDir: File,
        private val safTreeUri: Uri
    ) : FileObserver(rootDir, MODIFY or CREATE or DELETE or MOVED_FROM or MOVED_TO) {

        override fun onEvent(event: Int, path: String?) {
            if (path == null || !isWatching) return

            val localFile = File(rootDir, path)
            val relativePath = localFile.relativeTo(rootDir).path

            // Skip hidden files and temp files
            if (relativePath.startsWith(".") || path.endsWith("~") || path.endsWith(".tmp")) return

            val type = when (event and ALL_EVENTS) {
                MODIFY -> SyncType.MODIFY
                CREATE, MOVED_TO -> SyncType.CREATE
                DELETE, MOVED_FROM -> SyncType.DELETE
                else -> return
            }

            // Resolve the SAF URI for this file via its relative path
            val safDocUri = resolveDocumentUri(safTreeUri, relativePath)
            val safParentUri = resolveDocumentUri(
                safTreeUri,
                File(relativePath).parent ?: ""
            )

            writeBackQueue.offer(
                SyncJob(
                    type = type,
                    localPath = localFile.absolutePath,
                    safDocUri = safDocUri,
                    safParentUri = safParentUri,
                    safTreeUri = safTreeUri,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Resolves a document URI within a SAF tree given a relative path.
     * Q1 optimization: uses [docIdCache] for O(1) lookup when available,
     * falling back to tree traversal on cache miss.
     */
    private fun resolveDocumentUri(treeUri: Uri, relativePath: String): Uri? {
        if (relativePath.isEmpty()) {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        }

        // Fast path: use cached docId if available
        val cachedDocId = docIdCache[relativePath]
        if (cachedDocId != null) {
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, cachedDocId)
        }

        // Slow path: walk the tree segment by segment (for newly created files)
        val segments = relativePath.split("/")
        var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)

        for (segment in segments) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
            var found = false

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
                        if (cursor.getString(nameIndex) == segment) {
                            currentDocId = cursor.getString(idIndex)
                            found = true
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                return null
            }

            if (!found) return null
        }

        // Cache for next time
        docIdCache[relativePath] = currentDocId
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
    }

    private fun guessMimeType(filename: String): String =
        Companion.guessMimeType(filename)

    companion object {
        private const val COPY_BUFFER_SIZE = 8192
        private const val WRITEBACK_POLL_MS = 200L

        /** Q2: Max file size to sync (50 MB). Larger files are skipped. */
        internal const val MAX_FILE_SIZE = 50L * 1024 * 1024

        /**
         * Whether the mirror copy may be replaced with the one from the device folder.
         *
         * Opening a folder re-runs the whole copy, so this is what stands between a
         * reopen and the loss of edits not yet written back.
         *
         * Timestamps alone are not enough to decide this, for two reasons found the
         * hard way:
         *
         * - A size mismatch beats any timestamp. A copy that fails part-way leaves a
         *   short file carrying a *fresh* mtime, which timestamps alone would read as
         *   "newer, keep it" — freezing the truncation in place forever and letting
         *   the write-back push it onto the device. Different sizes mean the mirror is
         *   not a copy of the source, whatever the clocks say.
         * - An unknown source timestamp (0) must still copy. COLUMN_LAST_MODIFIED is
         *   optional, and treating unknown as "keep" would freeze such folders
         *   permanently: nothing in the app can clear a mirror, so there would be no
         *   way out short of clearing app data. Copying is what the old code did, and
         *   it is the behaviour that heals itself.
         *
         * The comparison is only sound because [copyDocumentToLocal] stamps the mirror
         * with the source's own timestamp, so both sides come from the same clock.
         */
        internal fun shouldOverwriteMirror(
            mirrorExists: Boolean,
            mirrorModified: Long,
            mirrorSize: Long,
            sourceModified: Long,
            sourceSize: Long
        ): Boolean =
            !mirrorExists ||
                mirrorSize != sourceSize ||
                sourceModified == 0L ||
                sourceModified > mirrorModified

        /**
         * Directories to skip during sync — auto-generated and too large.
         * Q3: Removed "build" (legitimate source dir) and ".vscode" (workspace settings).
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

        /** Testable: checks if a directory should be skipped during sync. */
        internal fun shouldSkip(name: String, isDir: Boolean): Boolean {
            if (!isDir) return false
            return name in SKIP_DIRECTORIES
        }

        /** Testable: heuristic MIME type detection from filename extension. */
        internal fun guessMimeType(filename: String): String {
            return when {
                filename.endsWith(".txt") || filename.endsWith(".md") -> "text/plain"
                filename.endsWith(".html") -> "text/html"
                filename.endsWith(".js") || filename.endsWith(".ts") -> "text/javascript"
                filename.endsWith(".json") -> "application/json"
                filename.endsWith(".py") -> "text/x-python"
                filename.endsWith(".kt") || filename.endsWith(".java") -> "text/plain"
                filename.endsWith(".xml") -> "text/xml"
                filename.endsWith(".css") -> "text/css"
                filename.endsWith(".sh") -> "text/x-shellscript"
                else -> "application/octet-stream"
            }
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
    val timestamp: Long
)

internal enum class SyncType {
    MODIFY, CREATE, DELETE
}
