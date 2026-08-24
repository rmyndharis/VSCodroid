package com.vscodroid.util

import android.content.Context
import com.vscodroid.setup.ToolchainManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.Locale
import android.annotation.SuppressLint

/**
 * Tracks disk usage per component and provides cache-clearing operations.
 *
 * Components tracked:
 * - VS Code Server (vscode-reh, the reh-web build, workbench included)
 * - Extensions (marketplace + bundled)
 * - User data (settings, state, logs)
 * - Tools (usr/: bash, git, python, npm, etc.)
 * - Projects (the default workspace, wherever this install keeps it)
 * - Cache (npm-cache, tmp, crash-logs, toolchain staging directories)
 */
// UsableSpace: this reports what is free, which is what the storage screen and
// the extraction pre-flight both need. getAllocatableBytes would add space the
// platform would reclaim from other apps' caches, and that is not space this
// app can count on having when it writes.
@SuppressLint("UsableSpace")
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
            // The user's own work, routinely the largest thing here and until now
            // in no row. An install that keeps it under filesDir had it inside
            // `total` with nothing to say so, so the rows did not add up to the
            // figure above them; an install from a release that put it on shared
            // storage had it in neither, so `total` disagreed with Android's own
            // app-info screen by the size of the workspace. Environment decides
            // which of the two this install uses, and nothing here second-guesses
            // it.
            val projectsDir = File(Environment.getProjectsDir(context))
            val projectsBytes = dirSize(projectsDir)
            put("projects", projectsBytes)
            put("cache", CLEARABLE_CACHE_DIRS.sumOf { dirSize(File(cacheDir, it)) })
            // The whole cache directory, unlike the row above it: this is the disk
            // the app occupies rather than a figure anyone is offered a button for.
            // The workspace is added only when it lies outside the two walks, or a
            // fresh install would be charged for it twice.
            val outsideFilesDir =
                !projectsDir.absolutePath.startsWith(filesDir.absolutePath + File.separator)
            put(
                "total",
                dirSize(filesDir) + dirSize(cacheDir) + if (outsideFilesDir) projectsBytes else 0L,
            )
            // Which of the keys above the clear action can reach. Sent rather
            // than duplicated on the JavaScript side: the two live in different
            // files with different release cadences, and a second copy is how
            // the recent list and the permission set drifted apart.
            put("clearable", JSONArray(CLEARABLE_KEYS.toList()))
        }
    }

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

    /**
     * The subtrees of `cacheDir` this app writes, which is what the `cache` row
     * measures and what [clearCaches] empties.
     *
     * The row used to be the whole cache directory, and that directory is not this
     * app's alone. The platform WebView keeps its own store in it, under the name
     * `WebView.setDataDirectorySuffix("vscodroid")` gives it, and Chromium holds
     * that store open in a live renderer, so it is neither ours to delete nor
     * something the clear action has ever touched. Measured on a debug install
     * after one short editor session: 9428 KiB in the cache directory, of which
     * 9356 KiB was `webview_vscodroid` and 40 KiB was reachable by the action.
     * [CLEARABLE_KEYS] offers this row to that action, so a user out of disk tapped
     * Clear, was told a small number had been freed, and watched the figure they
     * had picked stay where it was: the same "the report is not about the row you
     * picked" failure that set survives inside the one row still offered.
     *
     * Those bytes are not hidden: `total` still counts the whole directory, because
     * that question is how much disk the app occupies rather than what a button can
     * give back.
     */
    internal val CLEARABLE_CACHE_DIRS =
        listOf("npm-cache", "tmp", "crash-logs", "toolchain-download")

    /**
     * Clears caches: npm-cache, tmp dir, crash logs, VS Code logs, and the
     * toolchain staging directories no download is using.
     * Returns the number of bytes freed.
     *
     * The cache directories below are spelled out rather than looped over
     * [CLEARABLE_CACHE_DIRS], because each is emptied differently: `tmp` is
     * recreated because the runtime needs it, and the toolchain staging tree keeps
     * whatever a running download owns. They are still the same four, and
     * `ClearableStorageTest` fails if the row starts counting one this does not
     * reach.
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

        // Toolchain staging directories
        freed += clearAbandonedToolchainDownloads(context)

        Logger.i(TAG, "Caches cleared: ${formatSize(freed)} freed")
        return freed
    }

    /**
     * Removes the toolchain staging directories no download is using, and reports
     * the bytes that went.
     *
     * These sit under `cacheDir`, so [getStorageBreakdown] has always counted them
     * in the `cache` row, and [CLEARABLE_KEYS] offers that row to [clearCaches].
     * Leaving them out meant the Clear action reported success and left the figure
     * on the same screen unmoved, and it is by some way the largest thing here: an
     * abandoned Java download is roughly 155 MB of expanded tree, against a few MB
     * for the other four directories put together. The user who opens that screen
     * is the one who is out of space.
     *
     * A directory belonging to a running download is left alone, and that is the
     * whole reason this is not one more line beside the others. `toolchainTempDir`
     * gives each transfer a directory of its own and the download writes its
     * archive, expands it and copies out of it there; pulling it out mid-flight
     * fails the transfer at best, and at worst leaves the copy into `usr/` reading
     * a tree that is being deleted underneath it. The name carries the pack and a
     * timestamp only the download itself knows, so the test is on the pack: every
     * directory of a pack being fetched stays, including an older abandoned one.
     * That keeps at most one extra directory for the length of one download, and
     * the launch sweep takes it a day later.
     *
     * The listing is taken before the in-flight set is read, not after. A download
     * publishes its pack name before it queues the task that creates the directory,
     * so anything already on disk at listing time is named in a set read afterwards.
     * The other order has a window: a download starting in between would have its
     * directory in the listing and its name in neither set.
     *
     * `ToolchainManager` also sweeps these on every launch, but only once they are a
     * day old, deliberately: nothing there can tell a stalled download from a
     * finished one. This is the user asking, so it takes everything it safely can.
     */
    private fun clearAbandonedToolchainDownloads(context: Context): Long {
        val root = File(context.cacheDir, "toolchain-download")
        val entries = root.listFiles() ?: return 0
        val inFlight = ToolchainManager.packsDownloading().keys
        var freed = 0L
        for (entry in entries) {
            if (inFlight.any { entry.name.startsWith("$it-") }) {
                Logger.i(TAG, "Keeping ${entry.name}: a download is still using it")
                continue
            }
            freed += deleteRecursive(entry)
        }
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

    /**
     * The bytes [dir] occupies, links excluded.
     *
     * Shared rather than private because the device-folder screen sizes each mirror
     * with it, and a second implementation is how the mirrors would be mis-sized: a
     * mirror is routinely a checked-out repository, so a link inside one is ordinary,
     * and the rule below is what keeps a link's target from being charged to a
     * directory that does not hold it.
     */
    internal fun dirSize(dir: File): Long {
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

    /**
     * Deletes [dir] and everything under it, unlinking links rather than following
     * them, and reports the bytes the filesystem accepted the unlink of.
     *
     * "Accepted the unlink of" rather than "released": a file another process still
     * holds open keeps its blocks until that descriptor closes, and the running server
     * is appending to `data/logs/server.log` while this walks it. That part cannot be
     * answered from here and the figure is a ceiling to that extent. What it is no
     * longer is a count of what was merely attempted.
     *
     * Shared rather than private for the reason [dirSize] is, and here the cost of a
     * second implementation is the user's files rather than a wrong number. The mirror
     * reclaim in [com.vscodroid.storage.SafStorageManager] used `File.deleteRecursively`,
     * which asks `isDirectory` and `listFiles` and so descends through a link out of the
     * mirror. That was harmless only because the gate in front of it refuses any mirror
     * containing a link at all; a reclaim the user confirms has no such gate, so the
     * link-aware rule below is what stands between a forced removal and a directory
     * somewhere else on the device.
     */
    internal fun deleteRecursive(dir: File): Long {
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
                // Counted only once the unlink succeeded, and the length is taken
                // first because there is nothing left to measure afterwards. Adding it
                // unconditionally charged every file the walk reached to the total
                // whether or not it went, so a clear that removed nothing still
                // reported the whole tree as freed. The screen this number reaches is
                // the one a person opens because they are out of disk, and a confident
                // "Freed 187.4 MB" that changes nothing is worse than a small figure
                // they can act on.
                val length = f.length()
                if (f.delete()) freed += length
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

    /**
     * The separator is pinned to [Locale.US] because the unit beside it is not
     * localized: `KB`, `MB` and `GB` are written here in English and the module
     * ships a single `res/values`, so letting the number follow the device
     * locale renders `1,0 GB`, half of one convention and half of another, on
     * every device whose region uses a comma. It also made the same code print
     * two different strings on two JDKs, because the CLDR data behind a region
     * moves between releases.
     */
    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(Locale.US, bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
            bytes >= 1_024 -> "%.1f KB".format(Locale.US, bytes / 1_024.0)
            else -> "$bytes B"
        }
    }
}
