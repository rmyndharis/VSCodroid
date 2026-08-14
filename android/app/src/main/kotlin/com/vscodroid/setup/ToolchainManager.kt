package com.vscodroid.setup

import android.app.Activity
import android.content.Context
import android.os.StatFs
import android.system.Os
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Manages on-demand toolchain installation via Play Asset Delivery or HTTP fallback.
 *
 * Play Store install flow:
 *   1. fetch() → AssetPackManager downloads the pack
 *   2. On COMPLETED → installFromDirectory() copies files to filesDir (off main thread)
 *   3. chmod +x on binaries, create symlinks in usr/bin/
 *   4. Write toolchain-env.sh for bash, persist state to toolchains.json
 *   5. removePack() to free the duplicate asset pack storage
 *
 * HTTP fallback (sideloaded/debug builds):
 *   1. Download ZIP from GitHub Releases via HttpURLConnection
 *   2. Extract ZIP → installFromDirectory() (shared with Play path)
 *   3. Same chmod/symlink/persist steps as above
 */
class ToolchainManager(private val context: Context) {

    private val tag = "ToolchainManager"
    private val assetPackManager = AssetPackManagerFactory.getInstance(context)
    private val stateFile = File(context.filesDir, "home/.vscodroid/toolchains.json")
    private val envFile = File(context.filesDir, "home/.vscodroid/toolchain-env.sh")
    private val filesDir = context.filesDir.absolutePath
    private val homeDir = "$filesDir/home"

    /** Single-thread executor for heavy file I/O (copy, chmod, symlink). */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "toolchain-io").apply { isDaemon = true }
    }

    /** Callback for progress/state updates: (packName, status, percentDone) */
    var onStateChange: ((String, Int, Int) -> Unit)? = null

    // -- HTTP fallback state --

    /**
     * One download's cancellation flag.
     *
     * There was a single `@Volatile httpCancelled` here for every pack, and a
     * shared flag cannot express "cancel this one". Two ways out of it were
     * reachable from the toolchain screen. Cancelling Go while Ruby sat queued
     * behind it aborted Ruby too, at its first check, before it had transferred
     * a byte -- the executor serialises the tasks, not the flag they read.
     * Starting a third install while the first was mid-transfer ran the reset on
     * the *calling* thread and cleared a cancellation the running download had
     * not looked at yet, so the pack the user cancelled carried on downloading.
     *
     * A flag per request rather than per pack name: the token is created when
     * the download is asked for and handed to the task that performs it, so
     * whatever happens to the map afterwards, that task keeps reading the flag
     * its own caller can set.
     */
    private class HttpDownload {
        @Volatile var cancelled = false
    }

    /**
     * The token for each pack with a download outstanding, so [cancel] can find
     * it by name.
     *
     * Entries are put in on the calling thread and removed by the task itself,
     * which is what lets a cancellation arriving while the pack is still queued
     * be seen once it starts.
     *
     * Ceiling: keyed by pack name, so asking for the same pack twice before the
     * first finishes leaves the earlier task holding a token this map no longer
     * points at, and cancelling then reaches only the later one. The UI does not
     * offer that -- a pack showing DOWNLOADING has no install button -- and the
     * earlier task still terminates on its own.
     */
    private val httpDownloads = ConcurrentHashMap<String, HttpDownload>()

    companion object {
        private const val SPACE_BUFFER = 50_000_000L  // 50 MB free space buffer
        private const val HTTP_TIMEOUT_MS = 30_000     // 30s connect + read timeout
        private const val MAX_RETRIES = 2
        private const val DOWNLOAD_BUFFER_SIZE = 8192
        private const val MAX_REDIRECTS = 5

        /**
         * Records that a toolchain's binaries have had their execute bit checked.
         * Kept in the toolchain's own state entry rather than as one global flag,
         * so a toolchain installed later is marked without being walked, and a
         * repair that fails partway is retried only for what it did not finish.
         */
        private const val KEY_EXEC_REPAIRED = "execBitsChecked"

        /**
         * Serialises every read-modify-write cycle on `toolchains.json` and the
         * env file generated from it.
         *
         * Deliberately on the companion rather than on the instance, because an
         * instance field would serialise nothing here. Five call sites each
         * build their own [ToolchainManager] -- `SplashActivity` twice,
         * `ToolchainActivity`, `AndroidBridge` and `Environment` -- each with
         * its own single-thread executor, so the executors serialise their own
         * work and nothing serialises theirs against each other. Two of them
         * overlapping on one file is not hypothetical: the repair pass fires
         * from `SplashActivity` on every launch and runs `readState` ->
         * mutate -> `writeState`, while an install queued from the same screen
         * runs the same cycle on the same file. Whichever writes last wins with
         * a copy of the array it read before the other one changed it, and the
         * loser's toolchain is simply not in the file any more.
         *
         * Ceiling: one process. The file lives in `filesDir`, so any other
         * process that opened it -- the Node server, a terminal -- is outside
         * this lock entirely. Nothing does today, and the atomic rename below is
         * what keeps such a reader from ever seeing a half-written file even so.
         */
        private val stateLock = Any()
    }

    private val listener = AssetPackStateUpdateListener { state ->
        handleStateUpdate(state)
    }

    // -- Lifecycle --

    /**
     * Whether this instance's [listener] is currently registered with Play Core.
     *
     * Two callers reach [registerListener] on one instance without knowing about
     * each other: the activities call it from `onStart`, and [install] calls it
     * again before every `fetch`. So a registration and its removal are made by
     * different code for different reasons, and whether they balance depends on
     * what registering twice means.
     *
     * Play Core's own registry is a `Set` -- `AssetPackManager.registerListener`
     * delegates to a class holding `protected final java.util.Set b`, verified
     * by decompiling asset-delivery 2.2.2 rather than assumed -- so registering
     * the same object twice is already one entry, and the duplicate deliveries
     * that would otherwise follow do not happen. This flag is therefore not
     * fixing a live double-delivery bug, and saying so matters: an earlier
     * draft of this comment claimed it was.
     *
     * It is kept because that behaviour is an implementation detail of a
     * minified third-party class, promised nowhere in the API. Making the
     * pairing explicit here means [unregisterListener] can be called from a
     * terminal state without the caller knowing whether [install] took the Play
     * branch or the HTTP one -- which never registers -- and our correctness
     * does not rest on a `Set` we had to decompile to find.
     *
     * Guarded rather than volatile because check-and-act is the whole point, and
     * the callers are not on one thread: the activities are on the main thread,
     * while the bridge reaches [install] from the WebView's own thread.
     */
    private var listenerRegistered = false

    @Synchronized
    fun registerListener() {
        if (listenerRegistered) return
        assetPackManager.registerListener(listener)
        listenerRegistered = true
    }

    @Synchronized
    fun unregisterListener() {
        if (!listenerRegistered) return
        assetPackManager.unregisterListener(listener)
        listenerRegistered = false
    }

    // -- Query state --

    fun getInstalledToolchains(): List<String> {
        val state = readState()
        val result = mutableListOf<String>()
        for (i in 0 until state.length()) {
            val obj = state.optJSONObject(i) ?: continue
            result.add(obj.optString("name", ""))
        }
        return result.filter { it.isNotEmpty() }
    }

    fun isInstalled(name: String): Boolean =
        getInstalledToolchains().contains(name)

    fun getToolchainEnv(name: String): Map<String, String> {
        val state = readState()
        for (i in 0 until state.length()) {
            val obj = state.optJSONObject(i) ?: continue
            if (obj.optString("name") == name) {
                val env = obj.optJSONObject("env") ?: return emptyMap()
                return env.keys().asSequence().associateWith { env.getString(it) }
            }
        }
        return emptyMap()
    }

    fun getToolchainPathDirs(name: String): List<String> {
        val state = readState()
        for (i in 0 until state.length()) {
            val obj = state.optJSONObject(i) ?: continue
            if (obj.optString("name") == name) {
                val arr = obj.optJSONArray("pathDirs") ?: return emptyList()
                return (0 until arr.length()).map { arr.getString(it) }
            }
        }
        return emptyList()
    }

    // -- Install --

    fun install(packName: String) {
        val info = ToolchainRegistry.find(packName)
        if (info == null) {
            Logger.e(tag, "Unknown toolchain: $packName")
            onStateChange?.invoke(packName, AssetPackStatus.FAILED, 0)
            return
        }
        Logger.i(tag, "Requesting install of ${info.displayName} (${info.packName})")

        if (shouldUseHttpFallback()) {
            val url = info.downloadUrl
            if (url == null) {
                Logger.e(tag, "No downloadUrl for ${info.packName} — Play Store required")
                onStateChange?.invoke(info.packName, AssetPackStatus.FAILED, 0)
                return
            }
            downloadViaHttp(info.packName, url, info.estimatedSize)
        } else {
            // Ensure listener is registered before fetching
            registerListener()
            assetPackManager.fetch(listOf(info.packName))
        }
    }

    fun cancel(packName: String) {
        val info = ToolchainRegistry.find(packName) ?: return
        // Signals only this pack's download to stop (checked every 8KB in the
        // download loop). A pack with nothing outstanding has no token, and
        // cancelling it must not reach a download that is running for another.
        httpDownloads[info.packName]?.cancelled = true
        assetPackManager.cancel(listOf(info.packName))
        Logger.i(tag, "Cancelled download of ${info.packName}")
    }

    /**
     * Shows the Play Store cellular data confirmation dialog for large downloads.
     * Called when AssetPackStatus.REQUIRES_USER_CONFIRMATION is received.
     */
    @Suppress("DEPRECATION")
    fun showConfirmationDialog(activity: Activity) {
        try {
            assetPackManager.showCellularDataConfirmation(activity)
                .addOnSuccessListener {
                    Logger.i(tag, "User confirmed cellular data download")
                }
                .addOnFailureListener { e ->
                    Logger.e(tag, "Cellular data confirmation failed", e)
                }
        } catch (e: Exception) {
            Logger.e(tag, "Failed to show confirmation dialog", e)
        }
    }

    // -- Uninstall (runs on IO thread) --

    fun uninstall(name: String) {
        ioExecutor.execute {
            try {
                uninstallSync(name)
            } catch (e: Exception) {
                Logger.e(tag, "Failed to uninstall $name", e)
            }
        }
    }

    /**
     * Accepts either name form, because the two entry points into this class
     * disagreed about which one they take and only one of them said so.
     *
     * [install] resolves through [ToolchainRegistry.find], so `go` and
     * `toolchain_go` both work there. This side matched the persisted short name
     * only, and the form JavaScript actually holds is the pack name --
     * `getAvailableToolchains` hands it out as `packName`. So the natural call,
     * `removeToolchain("toolchain_go")`, logged "not found in state" and removed
     * nothing, while the same string passed to `installToolchain` worked.
     * `ToolchainActivity` never hit it because it strips the prefix itself
     * before calling; the bridge did not.
     */
    private fun uninstallSync(nameOrPack: String) =
        synchronized(stateLock) { uninstallLocked(toolchainShortName(nameOrPack)) }

    /**
     * Caller must hold [stateLock]. The manifest read at the top names the
     * files, symlinks and libraries deleted below it, so another instance
     * rewriting the record midway through leaves this pass deleting from one
     * version of the truth and recording against another.
     */
    private fun uninstallLocked(name: String) {
        val state = readState()
        var manifestObj: JSONObject? = null
        var idx = -1

        for (i in 0 until state.length()) {
            val obj = state.optJSONObject(i) ?: continue
            if (obj.optString("name") == name) {
                manifestObj = obj
                idx = i
                break
            }
        }

        if (manifestObj == null) {
            Logger.w(tag, "Toolchain $name not found in state")
            return
        }

        // Delete symlinks from usr/bin/ (use Os.lstat to catch dangling symlinks)
        val symlinks = manifestObj.optJSONObject("symlinks")
        if (symlinks != null) {
            val binDir = File(context.filesDir, "usr/bin")
            for (key in symlinks.keys()) {
                val link = File(binDir, key)
                val linkExists = try { Os.lstat(link.absolutePath); true } catch (e: Exception) { false }
                if (linkExists) {
                    link.delete()
                    Logger.d(tag, "Removed symlink: $key")
                }
            }
        }

        // Delete individual binary files (for toolchains like Ruby that place
        // binaries directly in usr/bin/ rather than an isolated directory)
        val binaries = manifestObj.optJSONArray("binaries")
        if (binaries != null) {
            for (i in 0 until binaries.length()) {
                val binFile = File(context.filesDir, binaries.getString(i))
                if (binFile.exists()) {
                    binFile.delete()
                    Logger.d(tag, "Removed binary: ${binaries.getString(i)}")
                }
            }
        }

        // Delete the toolchain's isolated install root (NOT shared dirs like usr/bin/)
        val installRoot = manifestObj.optString("installRoot", "")
        if (installRoot.isNotEmpty()) {
            val dir = File(context.filesDir, installRoot)
            if (dir.exists()) {
                dir.deleteRecursively()
                Logger.d(tag, "Deleted install root: $installRoot")
            }
        }

        // Delete library symlinks (versioned sonames)
        val libSymlinks = manifestObj.optJSONObject("libSymlinks")
        if (libSymlinks != null) {
            for (linkName in libSymlinks.keys()) {
                val linkFile = File(context.filesDir, "usr/lib/$linkName")
                val linkExists = try { Os.lstat(linkFile.absolutePath); true } catch (e: Exception) { false }
                if (linkExists) {
                    linkFile.delete()
                    Logger.d(tag, "Removed lib symlink: $linkName")
                }
            }
        }

        // Delete libs that were copied to usr/lib/ -- but usr/lib is shared with
        // the base install, and a manifest may name a library the base APK also
        // ships. Ruby's libffi.so is the live case: Python's _ctypes links it, so
        // deleting it here broke `import ctypes` until the next app update, with
        // nothing pointing at the uninstall that caused it. What the base ships
        // is read from the APK's own assets rather than kept as a list here; if
        // that listing cannot be read, nothing is deleted -- a leftover library
        // is reclaimed by the next install, a deleted base library is not.
        val libs = manifestObj.optJSONArray("libs")
        if (libs != null) {
            val baseShipped = try {
                context.assets.list("usr/lib")?.toSet()
            } catch (e: Exception) {
                Logger.w(tag, "Cannot list base usr/lib assets; keeping all libs: ${e.message}")
                null
            }
            val names = (0 until libs.length()).map { libs.getString(it) }
            val keep = names.toSet() - toolchainLibsSafeToRemove(names, baseShipped).toSet()
            for (name in keep) {
                Logger.i(tag, "Keeping $name: the base install ships it too")
            }
            for (name in toolchainLibsSafeToRemove(names, baseShipped)) {
                val lib = File(context.filesDir, "usr/lib/$name")
                if (lib.exists()) lib.delete()
            }
        }

        // Remove from state
        state.remove(idx)
        writeState(state)
        regenerateEnvFileLocked()

        Logger.i(tag, "Uninstalled toolchain: $name")
        onStateChange?.invoke("toolchain_$name", AssetPackStatus.NOT_INSTALLED, 0)
    }

    // -- Asset pack state handling --

    private fun handleStateUpdate(state: AssetPackState) {
        val packName = state.name()
        val status = state.status()
        val totalBytes = state.totalBytesToDownload()
        val downloaded = state.bytesDownloaded()
        val percent = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0

        Logger.d(tag, "Pack $packName: status=$status, $downloaded/$totalBytes ($percent%)")

        // Don't fire onStateChange for COMPLETED here — the real COMPLETED fires
        // after copyFromAssetPack() finishes extraction (line in copyFromAssetPack).
        // Firing it twice would cause downloadNext() to be called twice, skipping packs.
        if (status != AssetPackStatus.COMPLETED) {
            onStateChange?.invoke(packName, status, percent)
        }

        when (status) {
            AssetPackStatus.COMPLETED -> {
                // Heavy I/O: copy files, chmod, symlinks — run off main thread
                ioExecutor.execute {
                    try {
                        val location = assetPackManager.getPackLocation(packName)
                        val assetsPath = location?.assetsPath()
                        if (assetsPath != null) {
                            installFromDirectory(packName, File(assetsPath))
                            assetPackManager.removePack(packName)
                            Logger.i(tag, "Removed asset pack $packName (freed duplicate storage)")
                        } else {
                            Logger.e(tag, "No assetsPath for completed pack $packName")
                            onStateChange?.invoke(packName, AssetPackStatus.FAILED, 0)
                        }
                    } catch (e: Exception) {
                        Logger.e(tag, "Failed to process completed pack $packName", e)
                        onStateChange?.invoke(packName, AssetPackStatus.FAILED, 0)
                    }
                }
            }
            AssetPackStatus.FAILED -> {
                Logger.e(tag, "Pack $packName download failed: errorCode=${state.errorCode()}")
            }
            AssetPackStatus.REQUIRES_USER_CONFIRMATION -> {
                // Downloads exceeding 200MB or Play-determined thresholds need user
                // confirmation via a system dialog. We need an Activity reference for
                // this — for now, log and report the state so the UI can prompt the user.
                Logger.w(tag, "Pack $packName requires user confirmation")
            }
            else -> { /* DOWNLOADING, PENDING, WAITING_FOR_WIFI, etc. — just report progress */ }
        }
    }

    // -- File operations --

    private fun installFromDirectory(packName: String, assetsDir: File) {
        // Both of these report FAILED before returning, and that is load-bearing
        // rather than tidy. Every other way out of this function ends in a state
        // being reported -- success at the end, and the caller's catch on a throw
        // -- but a plain return reported nothing, and the first-run queue is
        // driven entirely by those reports. A pack whose manifest was missing or
        // malformed therefore stalled setup on the progress screen instead of
        // being skipped, which is a far worse outcome than one absent toolchain.
        val manifestFile = File(assetsDir, "$packName.json")
        if (!manifestFile.exists()) {
            Logger.e(tag, "No $packName.json in asset pack $packName")
            onStateChange?.invoke(packName, AssetPackStatus.FAILED, 0)
            return
        }

        val manifest = JSONObject(manifestFile.readText())
        val name = manifest.optString("name", "")
        if (name.isEmpty()) {
            Logger.e(tag, "Invalid manifest.json in $packName: missing 'name'")
            onStateChange?.invoke(packName, AssetPackStatus.FAILED, 0)
            return
        }
        Logger.i(tag, "Installing toolchain: $name (from $packName)")

        // Copy all files from usr/ to filesDir/usr/
        val usrSrc = File(assetsDir, "usr")
        if (usrSrc.exists()) {
            copyDirectoryRecursively(usrSrc, File(context.filesDir, "usr"))
        }

        // chmod +x on binaries
        val binaries = manifest.optJSONArray("binaries")
        if (binaries != null) {
            for (i in 0 until binaries.length()) {
                val binPath = binaries.getString(i)
                val binFile = File(context.filesDir, binPath)
                if (binFile.exists()) {
                    binFile.setExecutable(true, true)
                    Logger.d(tag, "chmod +x: $binPath")
                }
            }
        }

        // Create symlinks in usr/bin/
        val symlinks = manifest.optJSONObject("symlinks")
        if (symlinks != null) {
            val binDir = File(context.filesDir, "usr/bin")
            binDir.mkdirs()
            for (linkName in symlinks.keys()) {
                val target = symlinks.getString(linkName)
                val targetAbsolute = "$filesDir/$target"
                val linkFile = File(binDir, linkName)

                // Remove existing link if stale
                val linkExists = try { Os.lstat(linkFile.absolutePath); true } catch (e: Exception) { false }
                if (linkExists) {
                    linkFile.delete()
                }

                try {
                    Os.symlink(targetAbsolute, linkFile.absolutePath)
                    Logger.d(tag, "Symlink: $linkName -> $target")
                } catch (e: Exception) {
                    Logger.w(tag, "Failed to create symlink $linkName: ${e.message}")
                }
            }
        }

        // Create library symlinks (versioned sonames like libruby.so.3.4 → libruby.so)
        // Android assets can't contain symlinks, so versioned sonames are created at install time.
        val libSymlinks = manifest.optJSONObject("libSymlinks")
        if (libSymlinks != null) {
            val libDir = File(context.filesDir, "usr/lib")
            for (linkName in libSymlinks.keys()) {
                val targetName = libSymlinks.getString(linkName)
                val targetFile = File(libDir, targetName)
                val linkFile = File(libDir, linkName)
                if (targetFile.exists()) {
                    val linkExists = try { Os.lstat(linkFile.absolutePath); true } catch (e: Exception) { false }
                    if (linkExists) linkFile.delete()
                    try {
                        Os.symlink(targetFile.absolutePath, linkFile.absolutePath)
                        Logger.d(tag, "Lib symlink: $linkName -> $targetName")
                    } catch (e: Exception) {
                        Logger.w(tag, "Failed to create lib symlink $linkName: ${e.message}")
                    }
                }
            }
        }

        // Persist state. Read, mutate and write are one unit: on its own, each
        // is safe, and interleaved with another instance's cycle the write puts
        // back an array that predates whatever the other one added.
        //
        // Only this tail is held, not the copy above it. The copy moves up to
        // 160 MB and holds nothing anyone else needs; the record is four lines
        // and is what everything else reads.
        synchronized(stateLock) {
            val state = readState()
            // Remove any existing entry for this toolchain
            for (i in state.length() - 1 downTo 0) {
                if (state.optJSONObject(i)?.optString("name") == name) {
                    state.remove(i)
                }
            }
            // Installed by this version, so its binaries already carry the execute
            // bit and the repair pass has nothing to do here. Marking it now is what
            // keeps that pass from walking a several-thousand-file tree to confirm it.
            manifest.put(KEY_EXEC_REPAIRED, true)
            state.put(manifest)
            writeState(state)
            regenerateEnvFileLocked()
        }

        Logger.i(tag, "Toolchain $name installed successfully")
        onStateChange?.invoke(packName, AssetPackStatus.COMPLETED, 100)
    }

    // -- HTTP fallback (sideloaded installs) --

    /**
     * Returns true if the app was NOT installed via Play Store.
     * On sideloaded/debug builds, Play Asset Delivery silently fails,
     * so we download toolchain ZIPs from GitHub Releases instead.
     */
    private fun shouldUseHttpFallback(): Boolean {
        return try {
            val source = context.packageManager.getInstallSourceInfo(context.packageName)
            val installer = source.installingPackageName
            Logger.d(tag, "Install source: $installer")
            installer != "com.android.vending"
        } catch (e: Exception) {
            Logger.w(tag, "Could not determine install source, using HTTP fallback: ${e.message}")
            true
        }
    }

    /**
     * Downloads a toolchain ZIP from GitHub Releases, extracts it, and installs.
     * Runs entirely on ioExecutor. Fires onStateChange with AssetPackStatus constants.
     */
    private fun downloadViaHttp(packName: String, url: String, estimatedSize: Long) {
        // Published before the task is queued rather than reset once it starts.
        // A cancellation can arrive while this pack is still waiting behind
        // another one, and it has to survive the wait: resetting at task start
        // would discard it just as reliably as the shared flag did.
        val download = HttpDownload()
        httpDownloads[packName] = download
        onStateChange?.invoke(packName, AssetPackStatus.PENDING, 0)

        ioExecutor.execute {
            val tempDir = File(context.cacheDir, "toolchain-download")
            val zipFile = File(tempDir, "$packName.zip")
            val extractDir = File(tempDir, packName)

            try {
                // Pre-flight disk space check
                val stat = StatFs(context.filesDir.absolutePath)
                val availableBytes = stat.availableBytes
                val requiredBytes = estimatedSize + SPACE_BUFFER
                if (availableBytes < requiredBytes) {
                    Logger.e(tag, "Not enough disk space: ${availableBytes / 1_000_000} MB available, " +
                            "${requiredBytes / 1_000_000} MB required")
                    onStateChange?.invoke(packName, AssetPackStatus.FAILED, 0)
                    return@execute
                }

                tempDir.mkdirs()

                // Download
                downloadWithRetries(packName, url, zipFile, estimatedSize, download)

                if (download.cancelled) {
                    Logger.i(tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                // Extract — report as TRANSFERRING (file copy phase)
                onStateChange?.invoke(packName, AssetPackStatus.TRANSFERRING, 90)
                extractDir.deleteRecursively()
                extractDir.mkdirs()
                extractZip(zipFile, extractDir)

                // Install from extracted directory (same path as Play Asset Delivery)
                installFromDirectory(packName, extractDir)

            } catch (e: IOException) {
                if (download.cancelled) {
                    Logger.i(tag, "HTTP download cancelled for $packName")
                } else {
                    Logger.e(tag, "HTTP download failed for $packName", e)
                    onStateChange?.invoke(packName, AssetPackStatus.FAILED, 0)
                }
            } catch (e: SecurityException) {
                Logger.e(tag, "Zip security violation for $packName", e)
                onStateChange?.invoke(packName, AssetPackStatus.FAILED, 0)
            } catch (e: Exception) {
                Logger.e(tag, "Unexpected error downloading $packName", e)
                onStateChange?.invoke(packName, AssetPackStatus.FAILED, 0)
            } finally {
                // Two-argument remove: a later request for the same pack has
                // already replaced this entry, and dropping its token would
                // leave that download uncancellable.
                httpDownloads.remove(packName, download)
                // Clean up temp files
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * Retries download up to MAX_RETRIES times with exponential backoff.
     * Does not retry on 404 (zips not uploaded yet) — fails immediately.
     */
    @Throws(IOException::class)
    private fun downloadWithRetries(
        packName: String,
        url: String,
        destFile: File,
        estimatedSize: Long,
        download: HttpDownload,
    ) {
        var lastException: IOException? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                if (attempt > 0) {
                    val backoffMs = (1L shl attempt) * 1000  // 2s, 4s
                    Logger.i(tag, "Retry $attempt/$MAX_RETRIES for $packName after ${backoffMs}ms")
                    Thread.sleep(backoffMs)
                }
                downloadFile(packName, url, destFile, estimatedSize, download)
                return  // Success
            } catch (e: IOException) {
                lastException = e
                if (download.cancelled) throw e
                // Don't retry on 404
                if (e.message?.contains("404") == true) throw e
                Logger.w(tag, "Download attempt $attempt failed for $packName: ${e.message}")
            }
        }
        throw lastException ?: IOException("Download failed after ${MAX_RETRIES + 1} attempts")
    }

    /**
     * Downloads a file from the given URL using HttpURLConnection.
     * Manually follows redirects (GitHub → CDN) up to MAX_REDIRECTS hops.
     * Reports progress via onStateChange as DOWNLOADING status.
     *
     * Refuses a body shorter or longer than the server said it would send. That
     * is a narrow guarantee and worth stating as narrowly as it holds: it
     * catches a transfer that ends early -- a connection dropped mid-stream, a
     * proxy that closes a chunked body without finishing it -- which otherwise
     * reaches [extractZip] as a truncated archive and can install as a toolchain
     * missing whatever came after the cut. It is *not* an integrity check. A
     * payload that arrives complete and wrong, from a release that was built
     * wrong or an origin that was tampered with, matches its own Content-Length
     * and passes here. Only a published digest answers that, and there is no
     * manifest to read one from.
     */
    @Throws(IOException::class)
    private fun downloadFile(
        packName: String,
        url: String,
        destFile: File,
        estimatedSize: Long,
        download: HttpDownload,
    ) {
        var currentUrl = url
        var redirects = 0

        while (redirects < MAX_REDIRECTS) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "VSCodroid")
                // Asked for explicitly, because the length check below is only
                // sound if the body arrives as the header describes it.
                // HttpURLConnection otherwise advertises gzip on its own and
                // decodes it transparently, which leaves Content-Length
                // measuring the compressed stream while the loop counts the
                // decompressed one -- every download would then look truncated.
                // Costs nothing here: these payloads are ZIPs, which no origin
                // usefully compresses a second time.
                conn.setRequestProperty("Accept-Encoding", "identity")

                val responseCode = conn.responseCode

                if (responseCode in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect with no Location header from $currentUrl")
                    currentUrl = if (location.startsWith("http")) location
                                 else URL(URL(currentUrl), location).toString()
                    redirects++
                    conn.disconnect()
                    continue
                }

                if (responseCode == 404) {
                    throw IOException("404 Not Found: $currentUrl — toolchain ZIP not uploaded to release?")
                }

                if (responseCode != 200) {
                    throw IOException("HTTP $responseCode from $currentUrl")
                }

                // Two different numbers, kept apart. The progress denominator may
                // fall back to estimatedSize, which is a constant written into
                // ToolchainRegistry by hand; the completeness check may not,
                // because that constant goes stale the moment a payload is
                // rebuilt and would then fail every download of a healthy file.
                // Only a length the server actually sent is evidence of
                // anything.
                val declaredBytes = conn.contentLengthLong
                val totalBytes = if (declaredBytes > 0) declaredBytes else estimatedSize

                onStateChange?.invoke(packName, AssetPackStatus.DOWNLOADING, 0)

                var bytesRead = 0L
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var len: Int

                        while (input.read(buffer).also { len = it } != -1) {
                            if (download.cancelled) {
                                throw IOException("Download cancelled")
                            }
                            output.write(buffer, 0, len)
                            bytesRead += len
                            val percent = if (totalBytes > 0) {
                                ((bytesRead * 85) / totalBytes).toInt().coerceAtMost(85)
                            } else 0
                            onStateChange?.invoke(packName, AssetPackStatus.DOWNLOADING, percent)
                        }
                    }
                }

                if (!isCompleteTransfer(declaredBytes, bytesRead)) {
                    // The counts are logged rather than put in the message, and
                    // that is not a style choice: downloadWithRetries decides
                    // whether an error is retryable by looking for "404" as a
                    // substring of the message, so any byte count printed there
                    // is a number that can turn a retryable truncation into an
                    // immediate failure by coincidence.
                    Logger.w(tag, "Short read for $packName: $bytesRead bytes of $declaredBytes declared")
                    throw IOException("Incomplete download for $packName; the connection ended early")
                }

                Logger.i(tag, "Downloaded $packName: ${destFile.length() / 1_000_000} MB")
                return  // Success

            } finally {
                conn.disconnect()
            }
        }

        throw IOException("Too many redirects ($MAX_REDIRECTS) for $url")
    }

    /**
     * Extracts a ZIP archive to the destination directory.
     * Includes zip-slip protection (rejects entries that escape destDir).
     */
    @Throws(IOException::class, SecurityException::class)
    private fun extractZip(zipFile: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                val canonicalOut = outFile.canonicalPath

                if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                    throw SecurityException("Zip slip detected: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        zis.copyTo(out)
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        Logger.i(tag, "Extracted ${zipFile.name} to ${destDir.absolutePath}")
    }

    // -- Shared file operations --

    private fun copyDirectoryRecursively(src: File, dest: File) {
        if (src.isDirectory) {
            dest.mkdirs()
            val children = src.listFiles() ?: return
            for (child in children) {
                copyDirectoryRecursively(child, File(dest, child.name))
            }
        } else {
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
    }

    // -- Environment file generation --

    /**
     * Regenerates ~/.vscodroid/toolchain-env.sh from currently installed toolchains.
     * This file is sourced by .bashrc so new terminal sessions pick up toolchain paths.
     */
    fun regenerateEnvFile() = synchronized(stateLock) { regenerateEnvFileLocked() }

    /**
     * Caller must hold [stateLock]. This file is derived from `toolchains.json`,
     * so regenerating it from a state another instance is midway through
     * changing produces an environment for a set of toolchains that never
     * existed.
     */
    private fun regenerateEnvFileLocked() {
        val installed = readState()
        if (installed.length() == 0) {
            if (envFile.exists()) envFile.delete()
            return
        }

        val sb = StringBuilder()
        sb.appendLine("# Auto-generated by ToolchainManager — do not edit")
        sb.appendLine("# Sourced by .bashrc for toolchain environment variables")
        sb.appendLine()

        val extraPaths = mutableListOf<String>()

        for (i in 0 until installed.length()) {
            val tc = installed.optJSONObject(i) ?: continue
            val name = tc.optString("name", "unknown")
            val env = tc.optJSONObject("env") ?: continue

            sb.appendLine("# $name toolchain")
            for (key in env.keys()) {
                val value = env.getString(key)
                    .replace("\$FILESDIR", "\$PREFIX/..")
                    .replace("\$HOME", "\$HOME")
                sb.appendLine("export $key=\"$value\"")
            }
            sb.appendLine()

            // Script wrappers — bash functions for scripts that can't execute directly
            // on Android: SELinux denies execute_no_trans under filesDir, so a shebang
            // never runs. Invokes scripts via their interpreter instead.
            val scriptWrappers = tc.optJSONObject("scriptWrappers")
            if (scriptWrappers != null) {
                val interpreter = scriptWrappers.optString("interpreter", "")
                val scripts = scriptWrappers.optJSONObject("scripts")
                if (interpreter.isNotEmpty() && scripts != null) {
                    sb.appendLine("# $name script wrappers (SELinux blocks exec under filesDir)")
                    for (scriptName in scripts.keys()) {
                        val scriptPath = scripts.getString(scriptName)
                        sb.appendLine("$scriptName() { $interpreter \"\$PREFIX/../$scriptPath\" \"\$@\"; }")
                    }
                    sb.appendLine()
                }
            }

            // Collect extra PATH dirs
            val pathDirs = tc.optJSONArray("pathDirs")
            if (pathDirs != null) {
                for (j in 0 until pathDirs.length()) {
                    extraPaths.add("\$PREFIX/../${pathDirs.getString(j)}")
                }
            }
        }

        if (extraPaths.isNotEmpty()) {
            val paths = extraPaths.joinToString(":")
            sb.appendLine("# Toolchain PATH additions")
            sb.appendLine("export PATH=\"$paths:\$PATH\"")
        }

        envFile.parentFile?.mkdirs()
        // Atomic for the same reason as the state file, with a different cost on
        // failure: `.bashrc` sources this unconditionally, so a truncated copy is
        // not an absent environment but a sourcing error printed into every new
        // terminal, possibly with PATH half-built.
        if (!writeAtomically(envFile) { it.write(sb.toString().toByteArray()) }) {
            Logger.e(tag, "Could not write toolchain-env.sh; it still holds the previous environment")
            return
        }
        Logger.i(tag, "Regenerated toolchain-env.sh (${installed.length()} toolchains)")
    }

    /**
     * Returns resolved environment variables for all installed toolchains.
     * Used by Environment.kt to include in the Node.js server process env.
     */
    fun getAllToolchainEnv(): Map<String, String> {
        val installed = readState()
        val env = mutableMapOf<String, String>()
        val extraPaths = mutableListOf<String>()

        for (i in 0 until installed.length()) {
            val tc = installed.optJSONObject(i) ?: continue
            val tcEnv = tc.optJSONObject("env") ?: continue

            for (key in tcEnv.keys()) {
                val value = tcEnv.getString(key)
                    .replace("\$FILESDIR", filesDir)
                    .replace("\$HOME", homeDir)
                env[key] = value
            }

            val pathDirs = tc.optJSONArray("pathDirs")
            if (pathDirs != null) {
                for (j in 0 until pathDirs.length()) {
                    extraPaths.add("$filesDir/${pathDirs.getString(j)}")
                }
            }
        }

        if (extraPaths.isNotEmpty()) {
            env["__TOOLCHAIN_EXTRA_PATH"] = extraPaths.joinToString(":")
        }

        return env
    }

    // -- Repair of installs from earlier app versions --

    /**
     * Gives back the execute bit to binaries an older install left without one.
     *
     * A toolchain keeps the manifest it was installed with: it is persisted into
     * `toolchains.json`, `filesDir` survives app updates, and nothing rewrites it
     * at launch. So a packaging fix reaches new installs only, and the install
     * that was already there stays exactly as wrong as it was.
     *
     * For Go that is the difference between working and not. The execute bit is
     * set on the manifest's `binaries` entries and nowhere else -- the recursive
     * copy grants none, because `copyTo` does not carry modes -- and an earlier
     * manifest named `go` and `gofmt` alone. `go` compiles nothing by itself; it
     * forks `compile`, `link` and `asm` out of `pkg/tool`, and those arrived
     * unrunnable. The user sees a permission error naming a path, with nothing
     * connecting it to the version they installed under.
     *
     * The payload is already on disk, so this needs no download and no reinstall:
     * every ELF object under the install root gets the bit, which is the same
     * rule the packaging gates use to decide what is a binary. Scripts are
     * deliberately not included -- SELinux refuses to execute anything under
     * `filesDir` that is not loaded as a library, which is why the manifests
     * wrap scripts in shell functions instead.
     *
     * Runs once per toolchain, recorded in its own state entry. A tree of several
     * thousand files is not something to walk on every launch, and an install
     * that never had the problem is marked without being walked at all.
     */
    fun repairInstalledToolchains() {
        ioExecutor.execute {
            try {
                repairInstalledToolchainsSync()
            } catch (e: Exception) {
                // A failed repair leaves the marker unset, so the next launch
                // tries again. Nothing else depends on it having run.
                Logger.w(tag, "Toolchain repair pass failed: ${e.message}")
            }
        }
    }

    /**
     * Holds [stateLock] across the tree walk as well as the record update, and
     * that is the deliberate cheaper half of a trade. The walk is bounded and
     * one-time -- each entry is marked afterwards and never walked again -- so
     * the contention it can cause is a background install's record write waiting
     * a fraction of a second, once, after a download that took minutes. Splitting
     * it into read-walk-relock would buy that back at the cost of reasoning
     * about a state that changed underneath the walk. Readers are unaffected
     * either way: [readState] does not take the lock.
     */
    private fun repairInstalledToolchainsSync() = synchronized(stateLock) {
        val state = readState()
        var changed = false

        for (i in 0 until state.length()) {
            val entry = state.optJSONObject(i) ?: continue
            if (entry.optBoolean(KEY_EXEC_REPAIRED, false)) continue

            val name = entry.optString("name", "?")
            val installRoot = entry.optString("installRoot", "")
            val root = if (installRoot.isEmpty()) null else File(context.filesDir, installRoot)

            if (root != null && root.isDirectory) {
                val fixed = markExecutablesUnder(root)
                if (fixed > 0) {
                    Logger.i(tag, "Restored the execute bit on $fixed binaries in $name")
                }
            }
            // Marked either way: a toolchain with no install root, or one whose
            // tree is gone, has nothing this pass can do for it now or later.
            entry.put(KEY_EXEC_REPAIRED, true)
            changed = true
        }

        if (changed) writeState(state)
    }

    /**
     * Marks every ELF object under [root] executable, returning how many needed it.
     *
     * Symlinks are stepped over rather than followed: the link's target may sit
     * outside this toolchain -- `usr/lib` is shared with the base install -- and
     * a repair has no business changing permissions there.
     */
    private fun markExecutablesUnder(root: File): Int {
        var fixed = 0
        root.walkTopDown()
            .onEnter { !isSymlink(it) }
            .forEach { file ->
                if (!file.isFile || isSymlink(file)) return@forEach
                if (!isElf(file)) return@forEach
                if (file.canExecute()) return@forEach
                if (file.setExecutable(true, true)) fixed++
            }
        return fixed
    }

    private fun isElf(file: File): Boolean = try {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 && isElfHeader(header)
        }
    } catch (e: Exception) {
        false
    }

    // -- State persistence --

    private fun readState(): JSONArray {
        if (!stateFile.exists()) return JSONArray()
        return try {
            JSONArray(stateFile.readText())
        } catch (e: Exception) {
            Logger.w(tag, "Failed to read toolchains.json: ${e.message}")
            JSONArray()
        }
    }

    /**
     * Replaces `toolchains.json` in one step, or leaves the previous record
     * exactly where it was.
     *
     * `writeText` truncates before it writes, so a process killed in that window
     * left a zero-length or half-written file -- and [readState] answers a
     * malformed file with an empty array, on the reasonable-looking grounds that
     * it cannot do better. The two together are silent: every installed
     * toolchain disappears from the app's view while its several hundred MB stay
     * on disk, and the manifest naming the files, symlinks and libraries an
     * uninstall would remove is gone with it. There is no way back from that
     * short of reinstalling each toolchain over itself.
     *
     * [writeAtomically] writes a sibling and renames it, and rename is the
     * operation that either happened or did not. A failure now costs the update
     * rather than the record.
     */
    private fun writeState(state: JSONArray) = synchronized(stateLock) {
        stateFile.parentFile?.mkdirs()
        if (!writeAtomically(stateFile) { it.write(state.toString(2).toByteArray()) }) {
            Logger.e(tag, "Could not write toolchains.json; it still holds the previous record")
        }
    }
}

/**
 * Whether these opening bytes are an ELF object's.
 *
 * The same four bytes the packaging gates read. Separated so the repair pass can
 * be checked against real files rather than a mock that would only agree with
 * the implementation it was written from.
 */
internal fun isElfHeader(header: ByteArray): Boolean =
    header.size >= 4 &&
        header[0] == 0x7F.toByte() &&
        header[1] == 'E'.code.toByte() &&
        header[2] == 'L'.code.toByte() &&
        header[3] == 'F'.code.toByte()

/**
 * Whether a transfer that ended may be treated as the whole file.
 *
 * Separated from the socket for the same reason as the other decisions in this
 * package: the risk runs one way. Judging a complete download incomplete costs a
 * retry and then a visible failure, while judging an incomplete one complete
 * hands [ToolchainManager.extractZip] a truncated archive -- and a ZIP is read
 * entry by entry, so a cut one extracts happily up to the cut. What installs is
 * a toolchain that is simply missing whatever came after it, with the manifest
 * claiming it whole.
 *
 * @param declaredBytes the `Content-Length` the server sent, or a value <= 0
 *   when it sent none. Absence has to be accepted rather than treated as zero:
 *   the header is optional, a chunked response omits it, and refusing those
 *   would fail-closed on healthy downloads.
 * @param receivedBytes what the read loop actually counted
 */
internal fun isCompleteTransfer(declaredBytes: Long, receivedBytes: Long): Boolean =
    declaredBytes <= 0L || receivedBytes == declaredBytes

/**
 * The name a toolchain is persisted under, from either form callers use.
 *
 * `toolchain_go` and `go` name the same thing: the first is the asset pack and
 * the URL, the second is what goes in `toolchains.json` and what the shell
 * environment is built from. [ToolchainRegistry.find] already accepts both, so
 * this asks it and then takes the recorded form, rather than stripping the
 * prefix as a string operation -- a name that is not a known toolchain comes
 * back unchanged instead of being silently reshaped.
 *
 * That last part is why the fallback is the input rather than null: an entry can
 * outlive its registry row. A toolchain dropped from [ToolchainRegistry] in some
 * later release is still installed on the devices that have it, and its
 * uninstall has to keep working -- the alternative is files that nothing can
 * remove.
 */
internal fun toolchainShortName(nameOrPack: String): String =
    ToolchainRegistry.find(nameOrPack)?.packName?.removePrefix("toolchain_") ?: nameOrPack

/**
 * Names the manifest `libs` entries an uninstall may delete from the shared
 * `usr/lib`, given what the base APK ships there.
 *
 * Separated from the filesystem because the risk is one-directional, the same
 * shape as [supersededPythonEntries]: removing one entry too few leaves a file
 * the next install overwrites, while removing one too many deletes a library
 * the base app loads -- and that stays broken until the next version bump
 * re-extracts assets, with the error naming the library rather than the
 * uninstall that removed it.
 *
 * @param libs the manifest's `libs` entries
 * @param baseShipped names the base APK ships in `usr/lib`, or null when the
 *   listing could not be read -- in which case nothing is safe to remove
 */
internal fun toolchainLibsSafeToRemove(libs: List<String>, baseShipped: Set<String>?): List<String> {
    if (baseShipped == null) return emptyList()
    return libs.filter { it !in baseShipped }
}
