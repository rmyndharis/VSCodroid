package com.vscodroid.util

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Tracks disk usage per component and provides cache-clearing operations.
 *
 * Components tracked:
 * - VS Code Server (vscode-reh + vscode-web)
 * - Extensions (marketplace + bundled)
 * - User data (settings, state, logs)
 * - Tools (usr/ — bash, git, python, npm, etc.)
 * - Cache (npm-cache, tmp, crash-logs)
 */
object StorageManager {
    private const val TAG = "StorageManager"

    /**
     * Returns a JSON object with per-component disk usage in bytes.
     */
    fun getStorageBreakdown(context: Context): JSONObject {
        val filesDir = context.filesDir
        val cacheDir = context.cacheDir

        return JSONObject().apply {
            put("vscode_server", dirSize(File(filesDir, "server")))
            put("extensions", dirSize(File(filesDir, "home/.vscodroid/extensions")))
            put("user_data", dirSize(File(filesDir, "home/.vscodroid/User")))
            put("logs", dirSize(File(filesDir, "home/.vscodroid/data/logs")))
            put("tools", dirSize(File(filesDir, "usr")))
            put("saf_mirrors", dirSize(File(filesDir, "saf-mirrors")))
            put("cache", dirSize(cacheDir))
            put("total", dirSize(filesDir) + dirSize(cacheDir))
        }
    }

    /**
     * Returns a human-readable storage summary string.
     */
    fun getStorageSummary(context: Context): String {
        val breakdown = getStorageBreakdown(context)
        val sb = StringBuilder()
        sb.appendLine("Storage Usage:")
        sb.appendLine("  VS Code Server: ${formatSize(breakdown.getLong("vscode_server"))}")
        sb.appendLine("  Extensions:     ${formatSize(breakdown.getLong("extensions"))}")
        sb.appendLine("  User Data:      ${formatSize(breakdown.getLong("user_data"))}")
        sb.appendLine("  Logs:           ${formatSize(breakdown.getLong("logs"))}")
        sb.appendLine("  Tools:          ${formatSize(breakdown.getLong("tools"))}")
        sb.appendLine("  SAF Mirrors:    ${formatSize(breakdown.getLong("saf_mirrors"))}")
        sb.appendLine("  Cache:          ${formatSize(breakdown.getLong("cache"))}")
        sb.appendLine("  ─────────────────────")
        sb.appendLine("  Total:          ${formatSize(breakdown.getLong("total"))}")
        return sb.toString()
    }

    /**
     * Clears caches: npm-cache, tmp dir, crash logs, VS Code logs.
     * Returns the number of bytes freed.
     */
    fun clearCaches(context: Context): Long {
        var freed = 0L

        // npm cache
        val npmCache = File(context.cacheDir, "npm-cache")
        freed += deleteRecursive(npmCache)

        // tmp dir
        val tmpDir = File(context.cacheDir, "tmp")
        freed += deleteRecursive(tmpDir)
        tmpDir.mkdirs() // recreate (needed at runtime)

        // Crash logs
        val crashLogs = File(context.cacheDir, "crash-logs")
        freed += deleteRecursive(crashLogs)

        // VS Code logs
        val vscodeLogs = File(context.filesDir, "home/.vscodroid/data/logs")
        freed += deleteRecursive(vscodeLogs)
        vscodeLogs.mkdirs() // recreate

        Logger.i(TAG, "Caches cleared: ${formatSize(freed)} freed")
        return freed
    }

    /**
     * Clears SAF mirror directories (synced copies of external folders).
     * Users should close any SAF folder first.
     */
    fun clearSafMirrors(context: Context): Long {
        val mirrorsDir = File(context.filesDir, "saf-mirrors")
        val freed = deleteRecursive(mirrorsDir)
        mirrorsDir.mkdirs()
        Logger.i(TAG, "SAF mirrors cleared: ${formatSize(freed)} freed")
        return freed
    }

    /**
     * Returns available storage on the filesystem where filesDir resides.
     */
    fun getAvailableStorage(context: Context): Long {
        return context.filesDir.usableSpace
    }

    /**
     * Returns true if available storage is critically low (<100 MB).
     */
    fun isStorageLow(context: Context): Boolean {
        return getAvailableStorage(context) < 100 * 1_048_576L
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var size = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (f.isFile) {
                size += f.length()
            } else if (f.isDirectory) {
                f.listFiles()?.forEach { stack.addLast(it) }
            }
        }
        return size
    }

    private fun deleteRecursive(dir: File): Long {
        if (!dir.exists()) return 0
        var freed = 0L
        val stack = ArrayDeque<File>()
        val dirs = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (f.isFile) {
                freed += f.length()
                f.delete()
            } else if (f.isDirectory) {
                dirs.addFirst(f)
                f.listFiles()?.forEach { stack.addLast(it) }
            }
        }
        // Delete directories bottom-up (children first)
        dirs.forEach { it.delete() }
        return freed
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
            else -> "$bytes B"
        }
    }
}
