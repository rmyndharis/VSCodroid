package com.vscodroid.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * Tracks disk usage per component and provides cache-clearing operations.
 *
 * Components tracked:
 * - VS Code Server (vscode-reh — the reh-web build, workbench included)
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
            // data/User, not the sibling User/: the server keeps globalStorage,
            // History and workspaceStorage there. The directory this used to
            // measure was this app's own and is now empty. Logs are counted
            // separately below and live in data/logs, so this stays on data/User
            // rather than data/ to avoid counting them twice.
            put("user_data", dirSize(File(filesDir, "home/.vscodroid/data/User")))
            put("logs", dirSize(File(filesDir, "home/.vscodroid/data/logs")))
            put("tools", dirSize(File(filesDir, "usr")))
            put("saf_mirrors", dirSize(File(filesDir, "saf-mirrors")))
            put("cache", dirSize(cacheDir))
            put("total", dirSize(filesDir) + dirSize(cacheDir))
            // Which of the keys above the clear action can reach. Sent rather
            // than duplicated on the JavaScript side: the two live in different
            // files with different release cadences, and a second copy is how
            // the recent list and the permission set drifted apart.
            put("clearable", JSONArray(CLEARABLE_KEYS.toList()))
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
    /**
     * The breakdown keys [clearCaches] can actually free.
     *
     * Declared here because this is the only place that knows. The storage
     * screen offered every row to the same action, so choosing the device-folder
     * mirrors, the server tree, the extensions or the installed tools ran a
     * cache clear that cannot touch any of them and then reported success or
     * "nothing to clear", neither of which was about the row picked. Five of the
     * seven rows were unactionable and the screen said nothing.
     */
    internal val CLEARABLE_KEYS = setOf("logs", "cache")

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
            // A link contributes nothing and is not descended into: its target's bytes
            // are not in the directory being measured. usr/ is where this shows: every
            // launch relinks the tools into nativeLibraryDir, git-core alone is 146
            // links to one libgit.so, and counting each target charged "tools" with
            // several hundred MB of APK payload that does not sit in filesDir at all,
            // several times its real size, and put the same figure into "total".
            if (isLink(f)) continue
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
            // A link is unlinked, never followed. cacheDir/tmp is TMPDIR and
            // TMUX_TMPDIR for every terminal and for the server, so `ln -s
            // ~/projects/app $TMPDIR/app` is an ordinary thing for a person or a build
            // tool to leave there, and isDirectory/listFiles answer for the target, so
            // clearing the cache emptied ~/projects/app and reported its bytes as cache
            // freed. Unlinking also clears a dangling link, which the branches below
            // match neither of and which kept its directory from being removed.
            if (isLink(f)) {
                f.delete()
            } else if (f.isFile) {
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

    /**
     * Whether [f] is a symbolic link, asked without following it.
     *
     * `isFile`, `isDirectory`, `length` and `listFiles` all resolve the target, so every
     * one of them answers for something that may sit outside the directory being
     * measured or cleared. A path this cannot answer for counts as a link: measuring
     * then skips it and clearing then unlinks rather than descends, which is the
     * direction to be wrong in.
     */
    private fun isLink(f: File): Boolean = try {
        Files.isSymbolicLink(f.toPath())
    } catch (e: Exception) {
        true
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
