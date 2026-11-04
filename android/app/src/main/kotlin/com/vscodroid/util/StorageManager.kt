package com.vscodroid.util

import android.content.Context
import com.vscodroid.setup.ToolchainManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
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
 * - Cache (npm-cache, pip, tmp, crash-logs, Python bytecode, toolchain staging directories)
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
        listOf("npm-cache", "tmp", "crash-logs", "toolchain-download", "pycache", "pip")

    /**
     * Clears caches: npm-cache, pip's cache, tmp dir, crash logs, Python bytecode, VS Code
     * logs, and the toolchain staging directories no download is using.
     * Returns the number of bytes freed.
     *
     * The cache directories below are spelled out rather than looped over
     * [CLEARABLE_CACHE_DIRS], because each is emptied differently: `tmp` is
     * recreated because the runtime needs it, `pycache` is not because Python
     * recreates it, and the toolchain staging tree keeps whatever a running
     * download owns. They are the same six, and `ClearableStorageTest`
     * fails if the row starts counting one this does not reach.
     */
    fun clearCaches(context: Context): Long {
        var freed = 0L

        // npm cache
        val npmCache = File(context.cacheDir, "npm-cache")
        freed += deleteRecursive(npmCache, context.filesDir.parentFile)

        // pip's download and wheel cache, which PIP_CACHE_DIR in [Environment]
        // points here. Every wheel it holds can be fetched again.
        freed += deleteRecursive(File(context.cacheDir, "pip"), context.filesDir.parentFile)

        // Python bytecode. PYTHONPYCACHEPREFIX in [Environment] points the
        // interpreter here, so this is the whole of what it writes; the next
        // import of each module pays its compile again and nothing is lost.
        // It is also the remedy for a `.pyc` left with an intact header and a
        // damaged body by a crash or power loss mid-write (CPython does not
        // fsync), which raises out of the import rather than recompiling.
        freed += deleteRecursive(File(context.cacheDir, "pycache"), context.filesDir.parentFile)

        // tmp dir
        val tmpDir = File(context.cacheDir, "tmp")
        freed += deleteRecursive(tmpDir, context.filesDir.parentFile)
        tmpDir.mkdirs() // recreate (needed at runtime)

        // Crash logs
        val crashLogs = File(context.cacheDir, "crash-logs")
        freed += deleteRecursive(crashLogs, context.filesDir.parentFile)

        // VS Code logs
        val vscodeLogs = File(context.filesDir, "home/.vscodroid/data/logs")
        freed += deleteRecursive(vscodeLogs, context.filesDir.parentFile)
        vscodeLogs.mkdirs() // recreate

        // Toolchain staging directories
        freed += clearAbandonedToolchainDownloads(context)

        Logger.i(TAG, "Caches cleared: ${formatSize(freed)} freed")
        return freed
    }

    /**
     * Removes the bytecode of Python sources that lived under the cache directory.
     *
     * The bytecode prefix mirrors each source's absolute path and nothing prunes
     * it, so a source tree that is gone leaves its bytecode behind. Under
     * `cacheDir` that happens by construction: every `pip install` that builds
     * from source imports its build backend from a fresh `pip-build-env-*` under
     * TMPDIR, which pip deletes afterwards, and the `.pyc` of that import would
     * otherwise stay, at a path that never repeats. Called on every server start;
     * a build still running loses nothing but a recompile.
     *
     * Both spellings of the cache directory are pruned. pip realpaths its
     * temporary directories, and in the app process `/data/user/0` resolves to
     * `/data/data`, so a build's bytecode is filed under the canonical path while
     * [Context.getCacheDir] reports the other one. Measured on an API 33
     * emulator: the prefix held `data/data/<package>/...`.
     *
     * Bytecode of a deleted venv or a discarded folder mirror is not reached
     * here, and [clearCaches] frees it with the rest.
     */
    fun pruneTemporaryBytecode(context: Context): Long {
        val prefix = File(context.cacheDir, "pycache")
        val cache = context.cacheDir
        return listOfNotNull(cache.absolutePath, runCatching { cache.canonicalPath }.getOrNull())
            .distinct()
            .sumOf { deleteRecursive(File(prefix, it.trimStart('/')), context.filesDir.parentFile) }
    }

    /**
     * Removes the pip cache from where pip kept it before PIP_CACHE_DIR moved it
     * under the cache directory: `~/.cache/pip`, in files, which no storage row
     * counts and Android cannot reclaim. Called on every server start; once it
     * is gone this costs one failed lookup.
     */
    fun removeLegacyPipCache(context: Context): Long =
        deleteRecursive(File(context.filesDir, "home/.cache/pip"), context.filesDir.parentFile)

    /**
     * Removes the toolchain staging directories no download is using, and reports
     * the bytes that went.
     *
     * These sit under `cacheDir`, so [getStorageBreakdown] has always counted them
     * in the `cache` row, and [CLEARABLE_KEYS] offers that row to [clearCaches].
     * Leaving them out meant the Clear action reported success and left the figure
     * on the same screen unmoved, and it is by some way the largest thing here: an
     * abandoned Java download is roughly 180 MB of expanded tree, against a few MB
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
            freed += deleteRecursive(entry, context.filesDir.parentFile)
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
     * The line below which available storage is critically low, and so also the
     * reserve an open of a device folder leaves untouched.
     *
     * Decimal, because the caller formats the same quantity with [formatSize] and
     * prints it beside this warning. A binary threshold under a decimal formatter
     * lets the toast announce "104.8 MB available" under a sentence that says 100.
     */
    internal const val LOW_STORAGE_BYTES = 100_000_000L

    /** Returns true if available storage is critically low (below [LOW_STORAGE_BYTES]). */
    fun isStorageLow(context: Context): Boolean {
        return getAvailableStorage(context) < LOW_STORAGE_BYTES
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

    /** Unlinks one entry without descending into directories or following parent links. */
    internal fun deleteFile(file: File, appRoot: File?): Boolean {
        val root = appRoot?.toPath()?.toAbsolutePath()?.normalize() ?: return false
        val canonicalRoot = try { appRoot.canonicalFile.toPath() } catch (_: IOException) { return false }
        val target = file.toPath().toAbsolutePath().normalize()
        if (target == root || target == canonicalRoot) return false
        val relative = when {
            target.startsWith(root) -> root.relativize(target)
            target.startsWith(canonicalRoot) -> canonicalRoot.relativize(target)
            else -> return false
        }
        val parents = mutableListOf<DirectoryStream<Path>>()
        return try {
            val opened = Files.newDirectoryStream(canonicalRoot).also { parents.add(it) }
            val anchored = opened as? SecureDirectoryStream<Path> ?: return false
            var parent = anchored
            for (i in 0 until relative.nameCount - 1) {
                parent = parent.newDirectoryStream(relative.getName(i), NOFOLLOW_LINKS)
                parents.add(parent)
            }
            parent.deleteFile(relative.fileName)
            true
        } catch (_: IOException) {
            false
        } finally {
            parents.asReversed().forEach { try { it.close() } catch (_: IOException) {} }
        }
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
    internal fun deleteRecursive(dir: File, appRoot: File?): Long {
        // Context supplies the app data root; Android owns its parent and prevents its replacement.
        val root = appRoot?.toPath()?.toAbsolutePath()?.normalize() ?: return 0
        val canonicalRoot = try { appRoot.canonicalFile.toPath() } catch (_: IOException) { return 0 }
        val target = dir.toPath().toAbsolutePath().normalize()
        if (target == root || target == canonicalRoot) return 0
        val relative = when {
            target.startsWith(root) -> root.relativize(target)
            target.startsWith(canonicalRoot) -> canonicalRoot.relativize(target)
            else -> return 0
        }

        fun remove(parent: SecureDirectoryStream<Path>, name: Path): Long {
            class Pending(val parent: SecureDirectoryStream<Path>, val name: Path) {
                var directory: SecureDirectoryStream<Path>? = null
                var entries: Iterator<Path>? = null
            }
            val pending = ArrayDeque<Pending>()
            pending.addLast(Pending(parent, name))
            var freed = 0L
            while (pending.isNotEmpty()) {
                val item = pending.last()
                try {
                    val entries = item.entries
                    if (entries != null) {
                        if (entries.hasNext()) {
                            pending.addLast(Pending(item.directory!!, entries.next().fileName))
                            continue
                        }
                        item.directory?.close()
                        item.directory = null
                        item.parent.deleteDirectory(item.name)
                    } else {
                        val attrs = item.parent.getFileAttributeView(
                            item.name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS,
                        )?.readAttributes()
                        if (attrs?.isDirectory == true) {
                            val directory = item.parent.newDirectoryStream(item.name, NOFOLLOW_LINKS)
                            item.directory = directory
                            item.entries = directory.iterator()
                            continue
                        }
                        if (attrs != null) {
                            item.parent.deleteFile(item.name)
                            if (attrs.isRegularFile) freed += attrs.size()
                        }
                    }
                } catch (_: IOException) {
                    // A replaced or unavailable entry is left for a later clear.
                } catch (_: DirectoryIteratorException) {
                    // Keep the bytes already removed even if enumeration stops.
                }
                runCatching { item.directory?.close() }
                pending.removeLast()
            }
            return freed
        }

        val parents = mutableListOf<DirectoryStream<Path>>()
        return try {
            val opened = Files.newDirectoryStream(canonicalRoot).also { parents.add(it) }
            // A pathname fallback would follow a directory replaced during cleanup.
            val anchored = opened as? SecureDirectoryStream<Path> ?: return 0
            var parent = anchored
            for (i in 0 until relative.nameCount - 1) {
                parent = parent.newDirectoryStream(relative.getName(i), NOFOLLOW_LINKS)
                parents.add(parent)
            }
            remove(parent, relative.fileName)
        } catch (_: IOException) {
            0L
        } finally {
            parents.asReversed().forEach { try { it.close() } catch (_: IOException) {} }
        }
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
     * localized: `KB`, `MB` and `GB` are written here in Kotlin rather than in
     * `res/`, so they stay English on a device whose screens are translated, and
     * letting the number follow the device locale renders `1,0 GB`, half of one
     * convention and half of another, on every device whose region uses a comma.
     * The module has shipped translated resources since the languages were
     * added; these two literals are what has not moved with them. It also made the same code print
     * two different strings on two JDKs, because the CLDR data behind a region
     * moves between releases.
     *
     * The divisor is decimal, and this is the one place that decides it for the
     * whole app: every size a user reads on a native screen comes through here.
     * `android.text.format.Formatter.formatFileSize` has been SI since API 26 and
     * this module's `minSdk` is 30, so Settings > Storage, the Files app and the
     * Play listing are decimal on every device this ships to, and those are the
     * figures a user checks ours against. `FirstRunSetup.storageToFreeMb` already
     * answers in the same unit, for the same reason: it asks the user to free an
     * amount, and asking in a unit their storage screen does not use left them
     * about 5 percent short of what the app then refused them for.
     *
     * Not this function's business, and not to be swept into a units pass: heap
     * ceilings are mebibytes by V8's and the JVM's own definition, so
     * `ProcessManager.heapCeilingForDevice`, `CrashReporter` and the
     * `vscodroid.server.heapCeilingMb` setting keep their 1,048,576 divisors.
     */
    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(Locale.US, bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(Locale.US, bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(Locale.US, bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
