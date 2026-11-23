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
import java.nio.file.attribute.BasicFileAttributes
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
        val projectsDir = File(Environment.getProjectsDir(context))
        val filesPrefix = filesDir.absolutePath + File.separator
        val outsideFilesDir = !projectsDir.absolutePath.startsWith(filesPrefix)
        val projectsRel = if (!outsideFilesDir) {
            projectsDir.absolutePath
                .removePrefix(filesPrefix)
                .replace(File.separatorChar, '/')
                .trimEnd('/')
        } else {
            null
        }

        // Map each tracked subtree under filesDir to its JSON key so one traversal produces
        // both the per-category sizes and the overall filesDir total without re-walking subtrees.
        // data/User rather than data/: logs are counted separately in data/logs.
        val filesBuckets = LinkedHashMap<String, MutableList<String>>()
        fun addFileBucket(relativePath: String, key: String) {
            filesBuckets.getOrPut(relativePath) { ArrayList(1) }.add(key)
        }
        addFileBucket("server", "vscode_server")
        addFileBucket("home/.vscodroid/extensions", "extensions")
        addFileBucket("home/.vscodroid/data/User", "user_data")
        addFileBucket("home/.vscodroid/data/logs", "logs")
        addFileBucket("usr", "tools")
        addFileBucket("saf-mirrors", "saf_mirrors")
        if (!projectsRel.isNullOrEmpty()) {
            addFileBucket(projectsRel, "projects")
        }

        val filesTotals = walkSizes(filesDir, filesBuckets)
        val cacheBuckets = CLEARABLE_CACHE_DIRS.associateWith { listOf("cache") }
        val cacheTotals = walkSizes(cacheDir, cacheBuckets)
        val projectsBytes = when {
            outsideFilesDir -> dirSize(projectsDir)
            projectsRel.isNullOrEmpty() -> filesTotals.total
            else -> filesTotals.buckets["projects"] ?: 0L
        }

        return JSONObject().apply {
            put("vscode_server", filesTotals.buckets["vscode_server"] ?: 0L)
            put("extensions", filesTotals.buckets["extensions"] ?: 0L)
            put("user_data", filesTotals.buckets["user_data"] ?: 0L)
            put("logs", filesTotals.buckets["logs"] ?: 0L)
            put("tools", filesTotals.buckets["tools"] ?: 0L)
            put("saf_mirrors", filesTotals.buckets["saf_mirrors"] ?: 0L)
            put("projects", projectsBytes)
            put("cache", cacheTotals.buckets["cache"] ?: 0L)
            put(
                "total",
                filesTotals.total + cacheTotals.total + if (outsideFilesDir) projectsBytes else 0L,
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

        // tmp dir. It is the running server's TMPDIR, where the editor and the terminals keep the
        // sockets the git extension's credential prompts and the `vscodroid` command connect to, and
        // tmux its own: a socket's path unlinked is a socket nothing can reach again until the process
        // that made it restarts, so sockets stay. They hold no bytes, and the files around them go.
        val tmpDir = File(context.cacheDir, "tmp")
        freed += deleteRecursive(tmpDir, context.filesDir.parentFile, keepSockets = true)
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
     * `ToolchainManager` also keeps active packs when it sweeps on launch, but
     * waits until an unused directory is a day old. This is the user asking, so
     * it takes everything it safely can.
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
    internal fun dirSize(dir: File): Long = walkSizes(dir, emptyMap()).total

    private class WalkTotals(val total: Long, val buckets: Map<String, Long>)

    /**
     * Walks [root] once without following symbolic links, summing regular-file bytes into
     * [WalkTotals.total] and into every matching bucket declared in [bucketRoots].
     *
     * Uses [SecureDirectoryStream] when the filesystem provides it so subdirectory traversal
     * stays anchored to open directory descriptors without following symlinks, and falls back
     * to single-pass [Files.newDirectoryStream] + [NOFOLLOW_LINKS] attribute reads on hosts
     * without [SecureDirectoryStream].
     */
    private fun walkSizes(root: File, bucketRoots: Map<String, List<String>>): WalkTotals {
        val rootPath = root.toPath()
        val rootAttrs = try {
            Files.readAttributes(rootPath, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return WalkTotals(0L, emptyMap())
        }
        // A link contributes nothing and is not descended into: its target's bytes
        // are not in the directory being measured (e.g. usr/bin symlinks into nativeLibraryDir).
        if (rootAttrs.isSymbolicLink) return WalkTotals(0L, emptyMap())
        if (rootAttrs.isRegularFile) return WalkTotals(rootAttrs.size(), emptyMap())
        if (!rootAttrs.isDirectory) return WalkTotals(0L, emptyMap())

        val ancestorPaths = buildSet {
            for (path in bucketRoots.keys) {
                var slash = path.indexOf('/')
                while (slash >= 0) {
                    add(path.substring(0, slash))
                    slash = path.indexOf('/', slash + 1)
                }
            }
        }
        val bucketTotals = HashMap<String, Long>()
        var total = 0L

        fun recordSize(size: Long, buckets: List<String>) {
            total += size
            for (bucket in buckets) {
                bucketTotals[bucket] = (bucketTotals[bucket] ?: 0L) + size
            }
        }

        fun resolveBuckets(
            parentRel: String?,
            parentBuckets: List<String>,
            childName: String,
        ): Pair<String?, List<String>> {
            if (parentRel == null) return null to parentBuckets
            val childRel = if (parentRel.isEmpty()) childName else "$parentRel/$childName"
            val matched = bucketRoots[childRel]
            val buckets = if (matched != null) parentBuckets + matched else parentBuckets
            val nextRel = if (childRel in ancestorPaths) childRel else null
            return nextRel to buckets
        }

        val rootStream = try {
            Files.newDirectoryStream(rootPath)
        } catch (_: Exception) {
            return WalkTotals(0L, emptyMap())
        }

        if (rootStream is SecureDirectoryStream<Path>) {
            class Frame(
                val stream: SecureDirectoryStream<Path>,
                val entries: Iterator<Path>,
                val relPath: String?,
                val buckets: List<String>,
            )
            val stack = ArrayDeque<Frame>()
            try {
                stack.addLast(Frame(rootStream, rootStream.iterator(), "", emptyList()))
                while (stack.isNotEmpty()) {
                    val frame = stack.last()
                    val entry = try {
                        if (frame.entries.hasNext()) frame.entries.next() else null
                    } catch (_: DirectoryIteratorException) {
                        null
                    }
                    if (entry == null) {
                        runCatching { frame.stream.close() }
                        stack.removeLast()
                        continue
                    }
                    val childName = entry.fileName ?: continue
                    val attrs = try {
                        frame.stream.getFileAttributeView(
                            childName, BasicFileAttributeView::class.java, NOFOLLOW_LINKS,
                        )?.readAttributes()
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    if (attrs.isSymbolicLink) continue
                    val (nextRel, childBuckets) = resolveBuckets(
                        frame.relPath, frame.buckets, childName.toString(),
                    )
                    if (attrs.isRegularFile) {
                        recordSize(attrs.size(), childBuckets)
                    } else if (attrs.isDirectory) {
                        val sub = try {
                            frame.stream.newDirectoryStream(childName, NOFOLLOW_LINKS)
                        } catch (_: Exception) {
                            null
                        } ?: continue
                        stack.addLast(Frame(sub, sub.iterator(), nextRel, childBuckets))
                    }
                }
            } finally {
                while (stack.isNotEmpty()) {
                    runCatching { stack.removeLast().stream.close() }
                }
            }
        } else {
            class FallbackDir(
                val path: Path,
                val relPath: String?,
                val buckets: List<String>,
            )
            val stack = ArrayDeque<FallbackDir>()
            fun drainStream(stream: DirectoryStream<Path>, relPath: String?, buckets: List<String>) {
                stream.use { dir ->
                    try {
                        for (child in dir) {
                            val childName = child.fileName?.toString() ?: continue
                            val attrs = try {
                                Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                            } catch (_: Exception) {
                                continue
                            }
                            if (attrs.isSymbolicLink) continue
                            val (nextRel, childBuckets) = resolveBuckets(relPath, buckets, childName)
                            if (attrs.isRegularFile) {
                                recordSize(attrs.size(), childBuckets)
                            } else if (attrs.isDirectory) {
                                stack.addLast(FallbackDir(child, nextRel, childBuckets))
                            }
                        }
                    } catch (_: DirectoryIteratorException) {
                        // Keep the bytes already summed if enumeration stops mid-directory.
                    }
                }
            }
            drainStream(rootStream, "", emptyList())
            while (stack.isNotEmpty()) {
                val next = stack.removeLast()
                val stream = try {
                    Files.newDirectoryStream(next.path)
                } catch (_: Exception) {
                    continue
                }
                drainStream(stream, next.relPath, next.buckets)
            }
        }

        return WalkTotals(total, bucketTotals)
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
            val anchored = opened as? SecureDirectoryStream<Path> ?: run {
                Logger.w(TAG, "Cleanup skipped: secure directory operations are unavailable")
                return false
            }
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
    internal fun deleteRecursive(dir: File, appRoot: File?, keepSockets: Boolean = false): Long {
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
                        // A socket (or any entry that is neither a file, a directory nor a link) is left when
                        // asked, and the directory around it then stays, as one with anything left in it does.
                        if (attrs != null && !(keepSockets && attrs.isOther)) {
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
            val anchored = opened as? SecureDirectoryStream<Path> ?: run {
                Logger.w(TAG, "Cleanup skipped: secure directory operations are unavailable")
                return 0
            }
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
     * [bytes] as a person reads it in [locale]: that language's decimal separator
     * and its unit symbols, for the languages this app has screens in. Every caller
     * that puts the figure in a sentence passes the locale of the screen it is
     * shown on; the default, [Locale.US], is the English figure, which log lines
     * write and which English screens and every untranslated language get too,
     * because a phone in a language the app has no screens for reads the English
     * sentences, and `1,0 GB` inside one is half of one convention and half of
     * another.
     *
     * Only French and Russian write the units differently ([SIZE_UNITS]), as their
     * own storage screens do, and each language's symbol for a megabyte is the one
     * its `error_storage_full` already asks the user to free, which
     * `LocaleCoverageTest` holds it to. `%.1f` writes no grouping separator, the
     * part of a locale's number style that has moved between CLDR releases (French
     * went from a no-break space to a narrow one), so the same call prints the same
     * string on every JDK and Android release.
     *
     * The divisor is decimal, and this is the one place that decides it for the
     * whole app: every size a user reads on a native screen comes through here.
     * `android.text.format.Formatter.formatFileSize` has been SI since API 26 and
     * this module's `minSdk` is 29, so Settings > Storage, the Files app and the
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
    fun formatSize(bytes: Long, locale: Locale = Locale.US): String {
        val units = SIZE_UNITS[locale.language]
        val numbers = if (units == null) Locale.US else locale
        val (byte, kilo, mega, giga) = units ?: ENGLISH_UNITS
        return when {
            bytes >= 1_000_000_000 -> "%.1f %s".format(numbers, bytes / 1_000_000_000.0, giga)
            bytes >= 1_000_000 -> "%.1f %s".format(numbers, bytes / 1_000_000.0, mega)
            bytes >= 1_000 -> "%.1f %s".format(numbers, bytes / 1_000.0, kilo)
            else -> "$bytes $byte"
        }
    }

    private val ENGLISH_UNITS = listOf("B", "KB", "MB", "GB")

    /**
     * Byte, kilobyte, megabyte and gigabyte for each translated language, keyed by
     * language code. A language added to `res/` needs an entry here, or its sizes
     * stay in the English figure; `LocaleCoverageTest` catches the omission for a
     * language that writes a decimal comma or units of its own.
     * English has none on purpose: English screens keep the figure they always
     * printed, rather than `2,5 GB` on a phone set to English in a region that
     * writes a decimal comma.
     */
    private val SIZE_UNITS: Map<String, List<String>> =
        listOf("cs", "de", "es", "it", "ja", "ko", "pl", "pt", "tr", "zh").associateWith { ENGLISH_UNITS } +
            mapOf(
                "fr" to listOf("o", "ko", "Mo", "Go"),
                "ru" to listOf("Б", "кБ", "МБ", "ГБ"),
            )
}
