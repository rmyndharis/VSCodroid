package com.vscodroid.setup

import android.app.Activity
import android.content.Context
import android.os.StatFs
import androidx.annotation.StringRes
import com.vscodroid.R
import android.system.Os
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
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
import java.security.MessageDigest
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
    /**
     * Progress and outcome for one pack.
     *
     * The fourth argument is why it failed, and it is null for every status that
     * is not a failure. It exists because "Failed" on its own is the same word
     * for a full disk, a dropped connection and a release that never published
     * the file, and the user is the one person who can act on the difference:
     * free space, move to wifi, or wait for a release. The reason was in logcat
     * and nowhere else.
     *
     * Reached through [fail] and [report] rather than invoked directly, so a new
     * failure site cannot forget to say why: [fail] has no overload without one.
     */
    var onStateChange: ((String, Int, Int, ToolchainFailure?) -> Unit)? = null

    /** Progress or success. Never a failure: those go through [fail]. */
    private fun report(packName: String, status: Int, percent: Int) {
        onStateChange?.invoke(packName, status, percent, null)
    }

    /**
     * A failure, and why.
     *
     * There is no overload without a reason, which is the point: every failure
     * site has to name one, and a new one cannot be added without doing the
     * same. Counting them here would only rot; the compiler does the counting.
     */
    private fun fail(packName: String, why: ToolchainFailure) {
        onStateChange?.invoke(packName, AssetPackStatus.FAILED, 0, why)
    }

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
     * A request the release cannot answer, now or on a third attempt.
     *
     * Retryability used to be decided by looking for `"404"` as a substring of
     * the exception message, which was fine while every message was written
     * with that in mind and stopped being fine as soon as the messages carried
     * a URL. Three of the retryable ones do -- a redirect with no `Location`,
     * a non-200 status, and running out of redirect hops -- and after GitHub's
     * redirect the URL is a signed `objects.githubusercontent.com` link full of
     * hex. A 64-character hex string contains `404` about **1.5%** of the time
     * (measured over 400k samples; analytically 62/4096), and such a URL has
     * several hex components. So a small but real slice of transient failures
     * was being classified permanent and given up on after one attempt.
     *
     * A type instead of a spelling. The exception says what it is rather than
     * hoping its prose reads a particular way, and the cost of getting it wrong
     * is one-directional either way: this only ever ends an install sooner than
     * it needed to, never installs something it should not have.
     */
    private class MissingFromRelease(message: String) : IOException(message)

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
        private const val HTTP_TIMEOUT_MS = 30_000     // 30s connect + read timeout
        private const val MAX_RETRIES = 2
        private const val DOWNLOAD_BUFFER_SIZE = 8192
        private const val MAX_REDIRECTS = 5

        /**
         * Ceiling on the digest manifest read into memory.
         *
         * Three lines of `sha256sum` output today, so this is roughly five
         * hundred times what it needs. It is a ceiling rather than a budget: an
         * origin that answers this URL with something enormous should cost a
         * refusal, not the process.
         */
        private const val MANIFEST_MAX_CHARS = 64 * 1024

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

    // -- Install --

    fun install(packName: String) {
        val info = ToolchainRegistry.find(packName)
        if (info == null) {
            Logger.e(tag, "Unknown toolchain: $packName")
            fail(packName, ToolchainFailure.INTERNAL)
            return
        }
        Logger.i(tag, "Requesting install of ${info.displayName} (${info.packName})")

        if (shouldUseHttpFallback()) {
            val url = info.downloadUrl
            if (url == null) {
                Logger.e(tag, "No downloadUrl for ${info.packName} — Play Store required")
                fail(info.packName, ToolchainFailure.PLAY_REQUIRED)
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
     * `toolchain_ruby` both work there. This side matched the persisted short name
     * only, and the form JavaScript actually holds is the pack name --
     * `getAvailableToolchains` hands it out as `packName`. So the natural call,
     * `removeToolchain("toolchain_ruby")`, logged "not found in state" and removed
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
        report("toolchain_$name", AssetPackStatus.NOT_INSTALLED, 0)
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
        //
        // FAILED is held back for a different reason: report() has no way to say
        // why, and this is the only place that knows. It is emitted below through
        // fail() instead, still exactly once and still carrying FAILED, which is
        // what keeps isTerminalPackStatus moving the first-run queue past it. A
        // pack that stops emitting FAILED strands every pack queued behind it.
        if (status != AssetPackStatus.COMPLETED && status != AssetPackStatus.FAILED) {
            report(packName, status, percent)
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
                            fail(packName, ToolchainFailure.INTERNAL)
                        }
                    } catch (e: Exception) {
                        Logger.e(tag, "Failed to process completed pack $packName", e)
                        fail(packName, ToolchainFailure.INTERNAL)
                    }
                }
            }
            AssetPackStatus.FAILED -> {
                val code = state.errorCode()
                Logger.e(tag, "Pack $packName download failed: errorCode=$code")
                fail(packName, toolchainFailureFor(code))
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
            fail(packName, ToolchainFailure.CORRUPT)
            return
        }

        val manifest = JSONObject(manifestFile.readText())
        val name = manifest.optString("name", "")
        if (name.isEmpty()) {
            Logger.e(tag, "Invalid manifest.json in $packName: missing 'name'")
            fail(packName, ToolchainFailure.CORRUPT)
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
            // The record is what the install consists of, as far as everything
            // else is concerned. Without it getInstalledToolchains() does not
            // name the toolchain, the environment below is regenerated from a
            // record that predates it, readState() re-reads the file, so it
            // reads back the old one, and no manifest survives to tell
            // uninstallLocked which files, symlinks and libraries the ~160 MB
            // just copied consists of. Reporting COMPLETED on top of that is the
            // part that makes it silent: the picker shows 100% and Done, the
            // first-run queue moves on, and the card reads "Install" again after
            // the next launch with nothing said.
            if (!writeState(state)) {
                fail(packName, ToolchainFailure.STORAGE)
                return
            }
            regenerateEnvFileLocked()
        }

        Logger.i(tag, "Toolchain $name installed successfully")
        report(packName, AssetPackStatus.COMPLETED, 100)
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
        report(packName, AssetPackStatus.PENDING, 0)

        ioExecutor.execute {
            // Per download, not per class. The finally below deletes this
            // directory whole, and it used to be one constant path shared by
            // every ToolchainManager in the process: five call sites each build
            // their own, ioExecutor is an instance field so it serialises
            // nothing across them, and rotating ToolchainActivity or cancelling
            // the first-run queue leaves an earlier download still running. Two
            // downloads then wrote one zip path and each finally deleted the
            // other's tree, which surfaces as a digest mismatch, the alarm for
            // tampering, fired by a race with ourselves.
            val tempDir = toolchainTempDir(context.cacheDir, packName)
            val zipFile = File(tempDir, "$packName.zip")
            val extractDir = File(tempDir, packName)

            try {
                // Pre-flight disk space check
                val stat = StatFs(context.filesDir.absolutePath)
                val availableBytes = stat.availableBytes
                val requiredBytes = toolchainInstallBytes(estimatedSize)
                if (availableBytes < requiredBytes) {
                    Logger.e(tag, "Not enough disk space: ${availableBytes / 1_000_000} MB available, " +
                            "${requiredBytes / 1_000_000} MB required")
                    fail(packName, ToolchainFailure.STORAGE)
                    return@execute
                }

                tempDir.mkdirs()

                // Asked before the first request rather than only after the
                // download. Every other check of this flag sits past the
                // manifest fetch, so a pack cancelled while queued still spent
                // a request and, on a stalled connection, up to three read
                // timeouts of it -- with the first-run queue waiting behind.
                if (download.cancelled) {
                    Logger.i(tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                // Once, before either request, so the manifest and the payload
                // name the same release however long the transfer takes. Falls
                // back to the unpinned URL, which is what shipped before this.
                val pinnedUrl = pinLatest(url, download)

                // Resolved before the payload, not after. A release that cannot
                // vouch for this ZIP should cost a few hundred bytes and a clear
                // refusal, rather than 179 MB and then a refusal.
                val expectedDigest = publishedDigestFor(pinnedUrl, download)

                // Download
                retrying(packName, download) {
                    downloadFile(packName, pinnedUrl, zipFile, estimatedSize, download)
                }

                if (download.cancelled) {
                    Logger.i(tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                // The check this path did not have. Content-Length says the
                // transfer finished; only this says the bytes are the ones the
                // release published. Everything below it -- extraction, chmod,
                // symlinks into usr/ -- treats the archive as trusted, so this
                // is the last point at which it can be refused.
                val actualDigest = sha256Of(zipFile)
                if (!actualDigest.equals(expectedDigest, ignoreCase = true)) {
                    Logger.e(
                        tag,
                        "Digest mismatch for $packName: the release publishes $expectedDigest, " +
                            "the download hashes to $actualDigest. Not installing it.",
                    )
                    fail(packName, ToolchainFailure.DIGEST)
                    return@execute
                }
                Logger.i(tag, "$packName matches the digest the release publishes")

                // Extract — report as TRANSFERRING (file copy phase)
                report(packName, AssetPackStatus.TRANSFERRING, 90)
                extractDir.deleteRecursively()
                extractDir.mkdirs()
                extractZip(zipFile, extractDir)
                // Before the copy, which is the other half of the peak. The
                // digest was checked above and nothing reads the archive again,
                // so holding it through installFromDirectory buys nothing and
                // costs a device its whole download size in headroom.
                zipFile.delete()

                // Install from extracted directory (same path as Play Asset Delivery)
                installFromDirectory(packName, extractDir)

            } catch (e: MissingFromRelease) {
                // Ahead of the IOException it extends, because they are not the
                // same event to a user: this one will not succeed on a better
                // connection, and only a release changes it.
                Logger.e(tag, "Not published in the release: $packName", e)
                fail(packName, ToolchainFailure.NOT_PUBLISHED)
            } catch (e: IOException) {
                if (download.cancelled) {
                    Logger.i(tag, "HTTP download cancelled for $packName")
                } else {
                    Logger.e(tag, "HTTP download failed for $packName", e)
                    fail(packName, ToolchainFailure.NETWORK)
                }
            } catch (e: SecurityException) {
                Logger.e(tag, "Zip security violation for $packName", e)
                fail(packName, ToolchainFailure.CORRUPT)
            } catch (e: Exception) {
                Logger.e(tag, "Unexpected error downloading $packName", e)
                fail(packName, ToolchainFailure.INTERNAL)
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
     * Runs [attempt] up to MAX_RETRIES + 1 times with exponential backoff.
     *
     * Shared by both things this class fetches, and that is a correctness
     * property rather than deduplication. It was written for the ZIP alone, and
     * putting the manifest fetch in front of it without routing it through
     * meant a zero-tolerance request ran ahead of a fault-tolerant one: on
     * mobile data, a stall that the ZIP would have absorbed ended the whole
     * install after one read timeout, having transferred nothing. Hardening the
     * payload is no good if it lowers the odds of getting one.
     *
     * The one thing it does not retry is [MissingFromRelease], which stays
     * right for both: a ZIP not uploaded to the release will not appear on the
     * third attempt, and neither will a manifest the release does not carry.
     * Everything else gets all three.
     */
    @Throws(IOException::class)
    private fun <T> retrying(what: String, download: HttpDownload, attempt: () -> T): T {
        var lastException: IOException? = null
        for (n in 0..MAX_RETRIES) {
            try {
                if (n > 0) {
                    val backoffMs = (1L shl n) * 1000  // 2s, 4s
                    Logger.i(tag, "Retry $n/$MAX_RETRIES for $what after ${backoffMs}ms")
                    Thread.sleep(backoffMs)
                }
                return attempt()
            } catch (e: MissingFromRelease) {
                // Must precede the IOException catch below, which it extends.
                // The release does not carry this file; asking twice more only
                // spends six seconds arriving at the same answer.
                throw e
            } catch (e: IOException) {
                lastException = e
                if (download.cancelled) throw e
                Logger.w(tag, "Attempt $n failed for $what: ${e.message}")
            }
        }
        throw lastException ?: IOException("$what failed after ${MAX_RETRIES + 1} attempts")
    }

    /**
     * Opens [url], following redirects (GitHub → CDN) up to MAX_REDIRECTS hops,
     * and hands the connected 200 response to [body].
     *
     * Shared by the two things this class fetches, which is the point: they must
     * agree about timeouts, redirects and transfer encoding or the digest one of
     * them publishes describes a body the other one did not receive. [what]
     * names the artifact so a 404 says which of them is missing from the
     * release.
     */
    @Throws(IOException::class)
    private fun <T> withRedirects(url: String, what: String, body: (HttpURLConnection) -> T): T {
        var currentUrl = url
        var redirects = 0

        while (redirects < MAX_REDIRECTS) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "VSCodroid")
                // Asked for explicitly, because the length check in downloadFile
                // is only sound if the body arrives as the header describes it.
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
                    throw MissingFromRelease("404 Not Found: $currentUrl — $what not uploaded to release?")
                }

                if (responseCode != 200) {
                    throw IOException("HTTP $responseCode from $currentUrl")
                }

                return body(conn)
            } finally {
                conn.disconnect()
            }
        }

        throw IOException("Too many redirects ($MAX_REDIRECTS) for $url")
    }

    /**
     * Reads the release's digest manifest.
     *
     * Bounded rather than read whole. The body is a few hundred bytes of
     * `sha256sum` output, and this function exists because the payload it
     * describes is not to be taken on trust -- reading an unbounded remote body
     * into memory on the way to saying so would be the same trust by another
     * name.
     *
     * A body cut at the bound cannot produce a wrong digest, though it is worth
     * being exact about why, because there are two cases and only one of them
     * is the obvious one. A cut landing in the *digest* leaves a field that is
     * not 64 hex characters, which [digestFromManifest] skips. A cut landing in
     * the *name* leaves a perfectly valid digest beside a shortened filename --
     * nothing rejects that line, and nothing needs to: names are compared whole,
     * so it matches no ZIP anyone asked for. Either way the lookup finds
     * nothing and the install is refused. (This said "a line cut mid-way fails
     * the 64-hex test", which is true of the first case only.)
     */
    @Throws(IOException::class)
    private fun fetchManifest(url: String): String =
        withRedirects(url, "digest manifest") { conn ->
            val buffer = CharArray(MANIFEST_MAX_CHARS)
            conn.inputStream.bufferedReader().use { reader ->
                var total = 0
                while (total < buffer.size) {
                    val n = reader.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
                String(buffer, 0, total)
            }
        }

    /**
     * [zipUrl] with `latest` resolved to the release it names right now, or
     * [zipUrl] unchanged when it cannot be resolved.
     *
     * The manifest and the payload are two requests minutes apart, and both used
     * to go through `latest` independently, so a release published between them
     * handed back bytes to check against a digest read from the release before
     * it. Resolving once and building both URLs from the answer is what closes
     * that; [manifestUrlFor] derives the manifest beside the ZIP, so pinning the
     * ZIP pins the manifest for free.
     *
     * One HEAD, one hop, no body. `releases/latest` answers `releases/tag/<tag>`
     * and involves no asset, which is why it is asked rather than the asset URL:
     * that one redirects twice and ends at a signed CDN address carrying no tag.
     *
     * **Falls back to the unpinned URL when the resolution fails, deliberately.**
     * A wrong pin would 404 every toolchain for everyone, while the unpinned URL
     * is what shipped before this existed.
     *
     * That floor covers a failure to resolve and nothing more, which is narrower
     * than this once claimed. Once a pin IS returned, both requests use it and
     * neither falls back: a 404 on the pinned release becomes
     * [MissingFromRelease], which [retrying] rethrows on the first attempt, and
     * the install is refused. So a release published inside the one request
     * between the HEAD and the manifest fetch pins to the older one and fails
     * where the unpinned path would have taken both files from the newer.
     *
     * That window is one request wide against a transfer measured in minutes,
     * it fails closed, and retrying succeeds, so it is the price of closing the
     * wide window rather than a defect in the trade. It is written down because
     * the sentence it replaces asserted the price could not exist, and the next
     * reader would have believed it.
     */
    private fun pinLatest(zipUrl: String, download: HttpDownload): String {
        // Before the request, for the reason the check above this call records: a
        // pack cancelled while queued must not spend one, and this one carries a
        // 30 s connect and a 30 s read timeout that nothing else here would
        // interrupt.
        if (download.cancelled) return zipUrl
        val latestUrl = latestReleaseUrlFor(zipUrl) ?: return zipUrl
        val pinned = try {
            val conn = URL(latestUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.requestMethod = "HEAD"
                conn.setRequestProperty("User-Agent", "VSCodroid")
                val code = conn.responseCode
                val location = if (code in 300..399) conn.getHeaderField("Location") else null
                location?.let { releaseTagFromLocation(it) }?.let { pinnedAssetUrl(zipUrl, it) }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Logger.w(tag, "Could not resolve the latest release: ${e.message}")
            null
        }
        if (pinned == null) {
            Logger.w(
                tag,
                "Falling back to the unpinned release URL; the digest and the payload " +
                    "may come from different releases if one is published mid-download",
            )
            return zipUrl
        }
        Logger.i(tag, "Pinned this install to ${pinned.substringBeforeLast('/')}")
        return pinned
    }

    /**
     * The digest the release publishes for [zipName], or a refusal.
     *
     * Fetched before the ZIP rather than after, so a release that cannot vouch
     * for its payload costs a few hundred bytes instead of 179 MB.
     *
     * There is no fallback, and that is the whole change: silently installing
     * what nothing vouches for is the behaviour being removed, so it cannot be
     * what happens when the manifest is missing. A release without one -- or
     * with one that does not name this ZIP -- fails the install and says why.
     * Releases cannot reach that state unnoticed: the same step in `release.yml`
     * that packages the ZIPs writes this manifest and fails the release if a ZIP
     * named in [ToolchainRegistry] is missing from either.
     *
     * Retried on the same terms as the payload. Being first in the sequence and
     * less tolerant than what follows it is the one way this check could make
     * installs worse rather than safer.
     */
    @Throws(IOException::class)
    private fun publishedDigestFor(zipUrl: String, download: HttpDownload): String {
        val zipName = zipUrl.substringAfterLast('/')
        val manifest = retrying("$zipName digest manifest", download) {
            fetchManifest(manifestUrlFor(zipUrl))
        }
        return digestFromManifest(manifest, zipName)
            ?: throw IOException(
                "The release's digest manifest does not name $zipName; refusing to install a " +
                    "payload nothing vouches for"
            )
    }

    /**
     * Downloads the toolchain ZIP, reporting progress as DOWNLOADING.
     *
     * Refuses a body shorter or longer than the server said it would send, which
     * is worth stating as narrowly as it holds: it catches a transfer that ends
     * early -- a connection dropped mid-stream, a proxy closing a chunked body
     * without finishing it -- and it says nothing at all about whether the bytes
     * are the right ones. A payload that arrives complete and wrong matches its
     * own Content-Length and passes here.
     *
     * That second question is answered by the caller rather than here, against
     * the digest the release publishes; see [publishedDigestFor]. The two checks
     * are kept apart because they fail differently: a short read is a transfer
     * fault and worth retrying, so it is thrown from inside the retry loop,
     * while a complete body whose digest is wrong is not going to become right
     * on the third attempt.
     */
    @Throws(IOException::class)
    private fun downloadFile(
        packName: String,
        url: String,
        destFile: File,
        estimatedSize: Long,
        download: HttpDownload,
    ): Unit = withRedirects(url, "toolchain ZIP") { conn ->
        // Two different numbers, kept apart. The progress denominator may
        // fall back to estimatedSize, which is a constant written into
        // ToolchainRegistry by hand; the completeness check may not,
        // because that constant goes stale the moment a payload is
        // rebuilt and would then fail every download of a healthy file.
        // Only a length the server actually sent is evidence of
        // anything.
        val declaredBytes = conn.contentLengthLong
        val totalBytes = if (declaredBytes > 0) declaredBytes else estimatedSize

        report(packName, AssetPackStatus.DOWNLOADING, 0)

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
                    report(packName, AssetPackStatus.DOWNLOADING, percent)
                }
            }
        }

        if (!isCompleteTransfer(declaredBytes, bytesRead)) {
            // The counts belong in the message. They were held out of it and
            // logged separately, because retryability was decided by looking
            // for "404" as a substring and a byte count that happened to read
            // 404 would have turned a retryable truncation into an immediate
            // failure. That predicate is a type now, so the workaround has
            // nothing left to work around, and the retry loop logs the message
            // on every attempt.
            throw IOException(
                "Incomplete download for $packName: received $bytesRead bytes of $declaredBytes declared"
            )
        }

        Logger.i(tag, "Downloaded $packName: ${destFile.length() / 1_000_000} MB")
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

    private fun copyDirectoryRecursively(src: File, dest: File) =
        copyDirectoryTree(src, dest)

    // -- Environment file generation --

    /**
     * Regenerates ~/.vscodroid/toolchain-env.sh from currently installed toolchains.
     * This file is sourced by .bashrc so new terminal sessions pick up toolchain paths.
     */
    fun regenerateEnvFile() = synchronized(stateLock) { regenerateEnvFileLocked() }

    /**
     * Path to the system dynamic linker, which is the only way a toolchain
     * binary runs at all.
     *
     * SELinux denies `execute_no_trans` on `app_data_file`, so the kernel
     * refuses to `execve` anything whose inode is under `filesDir` -- which is
     * every byte a downloaded toolchain consists of. Measured on an API 37
     * emulator, `user` build, SELinux enforcing, from inside the app's own
     * process: a valid ELF copied there with mode 0755 fails with EACCES, and
     * it fails identically when reached through a symlink, because the check is
     * on the resolved inode's label rather than on the path. `chmod` cannot
     * reach it.
     *
     * Handing the same file to a loader that lives somewhere the app *may*
     * execute from does run it: `/system/bin/linker64 <binary>` returned 0 and
     * the program's own output, arguments and quoting survived, a non-zero exit
     * code came back intact, and `$0` was the binary's path rather than the
     * loader's. It is the shape the bundled Claude Code CLI already relies on.
     */
    private val systemLoader = "/system/bin/linker64"

    /**
     * Caller must hold [stateLock]. This file is derived from `toolchains.json`,
     * so regenerating it from a state another instance is midway through
     * changing produces an environment for a set of toolchains that never
     * existed.
     */
    private fun regenerateEnvFileLocked() {
        val installed = readableState()
        if (installed == null) {
            // Damage is not absence. The state file this could not parse was written
            // by a build that truncated it in place, and the devices carrying one
            // are upgrading from exactly that build. Reading it as "no toolchains"
            // deletes the env file below, the only working record of how to run
            // what is still on disk, and every launch deletes it again. Leaving
            // the last good file is what the bashrc repair does for the same damage.
            Logger.w(tag, "toolchains.json is unreadable; keeping the env file as it stands")
            return
        }
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
                // $HOME is deliberately left alone: the shell expands it when
                // it sources this file, which is what the manifest intends. It
                // used to be "replaced" with itself, which reads as a deliberate
                // transformation and is not one.
                val value = env.getString(key)
                    .replace("\$FILESDIR", "\$PREFIX/..")
                sb.appendLine("export $key=\"$value\"")
            }
            sb.appendLine()

            // Binary wrappers. Nothing under filesDir can be execve'd, so every
            // name PATH would resolve to is shadowed by a shell function that
            // hands the file to the system loader instead. Functions rather than
            // scripts for the same reason npm and npx are functions: a shebang
            // file under filesDir cannot be executed either.
            //
            // Only ELF objects are wrapped here. A manifest's `binaries` list
            // also carries interpreter scripts -- Ruby's `gem` and `rake` are
            // Ruby source -- and those are handled by scriptWrappers below,
            // which routes them through their interpreter. That interpreter is
            // itself one of these functions, so the two layers compose.
            val scriptNames = tc.optJSONObject("scriptWrappers")
                ?.optJSONObject("scripts")?.keys()?.asSequence()?.toSet().orEmpty()
            val binaries = tc.optJSONArray("binaries")
            if (binaries != null) {
                val lines = mutableListOf<String>()
                for (i in 0 until binaries.length()) {
                    val relPath = binaries.getString(i)
                    val command = relPath.substringAfterLast('/')
                    if (command.isEmpty() || command in scriptNames) continue
                    // A name bash cannot use as a function is skipped rather than
                    // written. The manifests are regenerated from upstream packages
                    // at build time, so a future version can introduce a binary
                    // called something like `foo-bar` or `2to3` without anyone here
                    // choosing it -- and this file is sourced by .bashrc, so one
                    // unusable name is a parse error that takes out *every* new
                    // terminal, not just that command. Losing one wrapper is the
                    // smaller failure, and it says so in the log.
                    if (!isShellFunctionName(command)) {
                        Logger.w(tag, "No wrapper for $command: not a name bash can " +
                            "use as a function, so it would break every terminal")
                        continue
                    }
                    if (!isElfFile(File(context.filesDir, relPath))) continue
                    lines.add("$command() { $systemLoader \"\$PREFIX/../$relPath\" \"\$@\"; }")
                }
                if (lines.isNotEmpty()) {
                    sb.appendLine("# $name binaries (SELinux blocks exec under filesDir)")
                    lines.forEach(sb::appendLine)
                    sb.appendLine()
                }
            }

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
     * The execute bit is set on the manifest's `binaries` entries and nowhere
     * else -- the recursive copy grants none, because `copyTo` does not carry
     * modes -- and an earlier Go manifest named `go` and `gofmt` alone, leaving
     * the `pkg/tool` binaries `go` forks without one.
     *
     * **This is necessary and it is not sufficient, and an earlier version of
     * this comment claimed otherwise.** It said the bit was "the difference
     * between working and not" for Go. It is not: SELinux denies
     * `execute_no_trans` on `app_data_file`, so nothing under `filesDir` can be
     * `execve`d whatever its mode. Measured from inside the app's own process on
     * an API 37 emulator, `user` build, enforcing -- a valid ELF there with mode
     * 0755 fails with EACCES, and fails identically through a symlink, because
     * the check is on the resolved inode's label rather than the path. What
     * actually makes a toolchain runnable is the loader indirection
     * [regenerateEnvFileLocked] writes; this pass only removes a second obstacle
     * sitting behind it.
     *
     * The bit is still worth restoring, because the loader will not run a file
     * the mode denies either. The payload is already on disk, so this needs no
     * download and no reinstall: every ELF object under the install root gets
     * the bit, which is the same rule the packaging gates use to decide what is
     * a binary. Scripts are deliberately not included -- they are wrapped in
     * shell functions that route them through their interpreter instead.
     *
     * Runs once per toolchain, recorded in its own state entry. A tree of several
     * thousand files is not something to walk on every launch, and an install
     * that never had the problem is marked without being walked at all.
     */
    fun repairInstalledToolchains() {
        ioExecutor.execute {
            try {
                // First, and before the env file is rewritten below. A retired
                // toolchain's wrappers go with it rather than being written out
                // again, and the repair that follows does not spend the launch
                // walking and chmod-ing several thousand files that are about to
                // be deleted. Go's tree alone is that size.
                removeRetiredToolchainsSync()
            } catch (e: Exception) {
                Logger.w(tag, "Could not remove a retired toolchain: ${e.message}")
            }
            try {
                repairInstalledToolchainsSync()
            } catch (e: Exception) {
                // A failed repair leaves the marker unset, so the next launch
                // tries again. Nothing else depends on it having run.
                Logger.w(tag, "Toolchain repair pass failed: ${e.message}")
            }
            try {
                // Unconditionally, and separately from the repair above, because
                // the two answer different questions. The repair is marked done per
                // toolchain and never runs again; this has to run on every launch,
                // because the env file is otherwise written only by an install or an
                // uninstall. A toolchain installed by an earlier version keeps the
                // file that version wrote -- so the loader wrappers, which are what
                // make a toolchain command runnable at all, reached new installs
                // only. That is the same shape as the bundled-extension defect this
                // release fixed: an improvement that skips exactly the installs that
                // need it.
                //
                // Cheap enough to do every time: it reads toolchains.json, formats a
                // few dozen lines and writes them atomically. With no toolchains
                // installed it deletes the file and returns.
                regenerateEnvFile()
            } catch (e: Exception) {
                Logger.w(tag, "Could not refresh toolchain-env.sh: ${e.message}")
            }
        }
    }

    /**
     * Removes a toolchain the app no longer offers.
     *
     * A toolchain leaves [ToolchainRegistry.available] when it stops being worth
     * its payload, and that alone would strand every install that already has
     * it. The card list is built from the registry
     * ([ToolchainCardState.items]), so the Remove button disappears with the
     * entry, while the install record, the payload and the loader wrappers are
     * all read from `toolchains.json` and carry on. The result is a toolchain
     * the user cannot get rid of and cannot use, which is worse than either.
     *
     * So the removal is done for them, once, on the first launch that carries
     * the retirement. [uninstallLocked] reads the install record rather than the
     * registry, so it can still find what it is deleting.
     *
     * No marker is kept and none is needed: a retired pack has no registry entry
     * and no download URL, so nothing can put it back, and the pass costs one
     * state read on every later launch. That is the same read
     * [regenerateEnvFileLocked] does immediately afterwards.
     */
    private fun removeRetiredToolchainsSync() = synchronized(stateLock) {
        val installed = readState()
        val present = mutableListOf<String>()
        for (i in 0 until installed.length()) {
            val name = installed.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isNotEmpty() && toolchainShortName(name) in RETIRED_TOOLCHAINS) {
                present.add(name)
            }
        }
        for (name in present) {
            Logger.i(tag, "Removing $name: this build no longer offers it")
            uninstallLocked(name)
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
    private fun markExecutablesUnder(root: File): Int = markExecutablesIn(root)

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
     * [readState] for the caller that cannot afford damage to read as absence.
     *
     * Returns null when the file exists and nothing can parse it, and an empty
     * array only when there is genuinely no record: the empty branch of
     * [regenerateEnvFileLocked] deletes the env file, which is right for a
     * machine with nothing installed and wrong for one whose record is a
     * half-written relic of the version that installed everything.
     */
    private fun readableState(): JSONArray? {
        if (!stateFile.exists()) return JSONArray()
        return try {
            JSONArray(stateFile.readText())
        } catch (e: Exception) {
            null
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
     *
     * @return false when the record on disk is still the previous one. Keeping
     *   the old record is the right outcome; reporting the update as if it
     *   landed is not, and a caller whose next act is to tell the user the
     *   toolchain is installed has to ask. [installFromDirectory] is that caller.
     */
    private fun writeState(state: JSONArray): Boolean = synchronized(stateLock) {
        stateFile.parentFile?.mkdirs()
        val written = writeAtomically(stateFile) { it.write(state.toString(2).toByteArray()) }
        if (!written) {
            Logger.e(tag, "Could not write toolchains.json; it still holds the previous record")
        }
        written
    }
}

/**
 * Whether these opening bytes are an ELF object's.
 *
 * The same four bytes the packaging gates read. Separated so the repair pass can
 * be checked against real files rather than a mock that would only agree with
 * the implementation it was written from.
 */
/**
 * Gives the execute bit to every ELF object under [root], returning how many
 * needed it.
 *
 * At file scope and taking its symlink predicate as a parameter so a test can
 * reach it. [isSymlink] uses `Os.lstat`, which cannot run in a JVM unit test --
 * it throws, the catch turns that into "not a link", and every entry then looks
 * like a regular file. Injecting the predicate is what lets the skip-links
 * behaviour be asserted rather than assumed; production still passes
 * [isSymlink].
 *
 * Links are stepped over rather than followed: a toolchain's `usr/lib` is shared
 * with the base install, so a link there can point at a file this pass has no
 * business changing the permissions of.
 *
 * Only ELF objects. Scripts are deliberately excluded -- they are wrapped in
 * shell functions that route them through their interpreter, because nothing
 * under `filesDir` can be executed directly whatever its mode.
 */
/**
 * A staging directory belonging to one download rather than to the class.
 *
 * The path used to be a constant, and the `finally` that cleans up deletes the
 * directory whole. Five call sites each construct their own `ToolchainManager`
 * and `ioExecutor` is an instance field, so it serialises nothing between them:
 * rotating the toolchain screen, or cancelling the first-run queue and reaching
 * the picker again, leaves an earlier download still running against the same
 * path. Each one's cleanup then deleted the other's archive and tree, which
 * surfaces as a digest mismatch, the alarm that exists for tampering, raised by
 * a race with ourselves.
 *
 * A process killed mid-download leaves one directory behind, which Android
 * reclaims under storage pressure. The shared path leaked the same way and was
 * merely overwritten by the next attempt rather than reclaimed.
 */
internal fun toolchainTempDir(cacheDir: File, packName: String): File =
    File(cacheDir, "toolchain-download/$packName-${System.nanoTime()}")

/** Free space asked for beyond what the install itself needs. */
internal const val SPACE_BUFFER = 50_000_000L

/**
 * What an HTTP toolchain install actually needs free, given its unpacked size.
 *
 * Twice the tree, because the install holds two copies at its peak: the one
 * unpacked into the cache and the one being written into `filesDir/usr`. The
 * reservation used to charge for one, which is the product rather than the
 * process, and nothing frees a stage before the next allocates. For Java 17 that
 * asked 196 MB for something that needs about 342, so every device between the
 * two figures downloaded 55 MB and then failed partway through the copy, with
 * the disk error reported as a network problem and the half-copied files left in
 * `usr/` under no manifest, so each retry started from less space than the last.
 *
 * The downloaded archive is deliberately not a third term: it is deleted after
 * extraction, before the copy begins. Charging for it here as well would refuse
 * devices for room they never need at once.
 */
internal fun toolchainInstallBytes(unpackedBytes: Long): Long =
    unpackedBytes * 2 + SPACE_BUFFER

/**
 * Copies a tree, refusing rather than shrugging when a directory cannot be read.
 *
 * `listFiles` answers null both for a directory that is empty of readable
 * entries and for one it could not enumerate at all, and this used to adopt the
 * benign reading with a bare `return`. The caller then wrote the install record
 * and reported the toolchain COMPLETED: a subtree that was never copied, symlinks
 * pointing at files that are not there, a green card, and a command that does not
 * run. The file already states the principle elsewhere, that damage is not
 * absence; this is the one place it was not applied.
 *
 * At file scope with the lister injected so the refusing branch can be driven
 * from a JVM test, which is the only way to reach it: producing a real
 * unreadable directory needs a filesystem fault or a concurrent deletion.
 *
 * @param list how children are enumerated, defaulting to the real filesystem
 * @throws IOException if a directory exists but cannot be listed
 */
internal fun copyDirectoryTree(
    src: File,
    dest: File,
    list: (File) -> Array<File>? = File::listFiles,
) {
    if (src.isDirectory) {
        dest.mkdirs()
        val children = list(src)
            ?: throw IOException(
                "Cannot list ${src.absolutePath}; the toolchain tree is incomplete"
            )
        for (child in children) {
            copyDirectoryTree(child, File(dest, child.name), list)
        }
    } else {
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
    }
}

internal fun markExecutablesIn(root: File, isLink: (File) -> Boolean = ::isSymlink): Int {
    var fixed = 0
    root.walkTopDown()
        .onEnter { !isLink(it) }
        .forEach { file ->
            if (!file.isFile || isLink(file)) return@forEach
            if (!isElfFile(file)) return@forEach
            if (file.canExecute()) return@forEach
            if (file.setExecutable(true, true)) fixed++
        }
    return fixed
}

/**
 * Whether [name] is safe to write into `toolchain-env.sh` as a shell function.
 *
 * `.bashrc` sources that file unconditionally, and one unusable name does not cost
 * one command -- it costs the rest of the file. Measured: sourcing a file holding
 * `good() {...}`, then `a=b() {...}`, then `after() {...}` leaves `good` defined
 * and `after` gone. So a toolchain whose manifest carried a bad name would take
 * out every wrapper written after it, in every new terminal.
 *
 * The boundary is measured against bash rather than assumed, because assuming it
 * was wrong in both directions. An earlier version of this allowed only
 * `[A-Za-z_][A-Za-z0-9_]*`, which is the rule for shell *variables*; bash is far
 * more permissive about function names and happily defines `grpc-tool`, `2to3`,
 * `foo.bar` and even `café`. Refusing those would have dropped working wrappers.
 *
 * What actually goes wrong divides in two, and both are refused:
 *
 *  - `( ) < > = [ $ \ " ' `` ` ``` and whitespace make the definition a parse
 *    error, which is what kills the rest of the file.
 *  - `; | &` are worse than an error: they split the line, so bash defines a
 *    function under a *different* name and reports nothing. The wrapper is simply
 *    absent under the name the user will type.
 *
 * Everything else measured -- `- . + : @ % ^ ! , { } ] * ? # ~ /`, a leading
 * digit, non-ASCII -- defines correctly and is allowed. All fifty command names in
 * the three shipped manifests pass, but those manifests are regenerated from
 * upstream packages at build time, so what they contain is not this repository's
 * choice to make.
 */
internal fun isShellFunctionName(name: String): Boolean {
    if (name.isEmpty()) return false
    return name.none { it in SHELL_UNSAFE || it.isWhitespace() }
}

/**
 * The characters measured to break a function definition or to silently define one
 * under another name. Not a guess at what looks dangerous: each was checked by
 * defining a function and asking `declare -F` whether that name exists.
 */
private const val SHELL_UNSAFE = "()<>=[\$\\\"'`;|&"

/** Whether [file] begins with the four bytes every ELF object starts with. */
internal fun isElfFile(file: File): Boolean = try {
    file.inputStream().use { input ->
        val header = ByteArray(4)
        input.read(header) == 4 && isElfHeader(header)
    }
} catch (e: Exception) {
    false
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
 * Why an install failed, in the terms the person looking at the screen can act in.
 *
 * The screen said "Failed" and nothing else, for every cause, while the reason
 * sat in logcat where no user reads it. These are grouped by WHAT TO DO rather
 * than by where the exception came from: a full disk and a failed state write
 * are both [STORAGE] because both are answered by freeing space, and a truncated
 * archive and a manifest missing its name are both [CORRUPT] because both are
 * answered by retrying.
 *
 * [NOT_PUBLISHED] is deliberately not [NETWORK], although it arrives as an
 * IOException on the same path: retrying on better signal cannot fix a file the
 * release does not contain, and telling someone to check their connection when
 * that is the problem sends them to look in the wrong place.
 */
enum class ToolchainFailure(@StringRes val message: Int) {
    NETWORK(R.string.toolchain_failed_network),
    STORAGE(R.string.toolchain_failed_storage),
    NOT_PUBLISHED(R.string.toolchain_failed_not_published),
    DIGEST(R.string.toolchain_failed_digest),
    CORRUPT(R.string.toolchain_failed_corrupt),
    // Reached from both delivery paths, so its message names what Play will not
    // do rather than which build is running: a sideloaded install with no ZIP for
    // this pack, and a Play install Play does not recognise as one of its own.
    // Naming the build type is wrong for the second, which shouldUseHttpFallback
    // has already classified as a Play install.
    PLAY_REQUIRED(R.string.toolchain_failed_play),
    INTERNAL(R.string.toolchain_failed_internal),
}

/**
 * Play's error code for a failed asset pack, in the terms [ToolchainFailure] speaks.
 *
 * The Play delivery path reported FAILED with no reason at all. The code went to
 * logcat and stopped there, so a Play Store user was left with the bare word that
 * the HTTP path had already stopped showing, for a full disk and a dropped
 * connection alike. Nothing new is said here; the existing vocabulary is simply
 * reached from the other delivery path.
 *
 * Grouped by what the user can do, like the enum itself, and taken from what each
 * code is documented to mean rather than from what its name resembles:
 *
 *  - `NETWORK_ERROR` is "unable to obtain the asset pack details", so
 *    [ToolchainFailure.NETWORK]: a better connection is the answer.
 *  - `INSUFFICIENT_STORAGE` is a download refused for space, so
 *    [ToolchainFailure.STORAGE]: freeing space is the answer.
 *  - `PACK_UNAVAILABLE` is "the asset pack wasn't included in the App Bundle that
 *    was published", which is this app's own release not carrying it, so
 *    [ToolchainFailure.NOT_PUBLISHED]: waiting for an update is the only answer.
 *  - `APP_NOT_OWNED` and `UNRECOGNIZED_INSTALLATION` both say Play does not
 *    recognise this copy of the app as one it delivered, and Play serves asset
 *    packs only to copies it does, so [ToolchainFailure.PLAY_REQUIRED].
 *
 * Everything else is [ToolchainFailure.INTERNAL] on purpose, including codes with
 * an inviting name. `APP_UNAVAILABLE` is "the requesting app is unavailable",
 * which is about the app or the user's access to it and not about the pack, so
 * sending it to `NOT_PUBLISHED` would tell someone this toolchain is missing from
 * a release that carries it. `ACCESS_DENIED`, `API_NOT_AVAILABLE`,
 * `INVALID_REQUEST`, `DOWNLOAD_NOT_FOUND` and `CONFIRMATION_NOT_REQUIRED` name
 * conditions no message in the enum can act on, and the last three are this
 * app's own mistakes rather than the user's.
 *
 * Two documented codes cannot be named here at all: `PLAY_STORE_NOT_FOUND` (-11)
 * and `NETWORK_UNRESTRICTED` (-12) appear in the library's own IntDef listing but
 * are not declared by asset-delivery 2.2.2, so they arrive as numbers this build
 * has no constant for and fall through with the rest. Every code that lands in
 * INTERNAL is still in the log line beside this call, raw.
 */
internal fun toolchainFailureFor(errorCode: Int): ToolchainFailure = when (errorCode) {
    AssetPackErrorCode.NETWORK_ERROR -> ToolchainFailure.NETWORK
    AssetPackErrorCode.INSUFFICIENT_STORAGE -> ToolchainFailure.STORAGE
    AssetPackErrorCode.PACK_UNAVAILABLE -> ToolchainFailure.NOT_PUBLISHED
    AssetPackErrorCode.APP_NOT_OWNED,
    AssetPackErrorCode.UNRECOGNIZED_INSTALLATION -> ToolchainFailure.PLAY_REQUIRED
    else -> ToolchainFailure.INTERNAL
}

/** The digest manifest's filename, as `release.yml` writes it beside the ZIPs. */
/**
 * Toolchains this build removes from any install that still has one.
 *
 * Short names, the form `toolchains.json` records. An entry belongs here when it
 * has left [ToolchainRegistry.available]: see [ToolchainManager.removeRetiredToolchainsSync]
 * for why leaving it alone is the one option that helps nobody.
 *
 * `go` is here because it could not compile. Android refuses to execute a file
 * under the app's data directory, and `go build` and `go run` fork the compiler,
 * assembler and linker themselves, so those forks are refused however the `go`
 * command itself is reached. Measured in the app's own SELinux domain, with a
 * control: a plain shell script placed there and marked executable is refused
 * too. It ran, it printed a version, and it could not build a program, for
 * 179 MB.
 */
/**
 * Toolchains no longer offered, against the space each still occupies on a
 * device that installed one before it was withdrawn.
 *
 * The size is here rather than left to `ToolchainRegistry.find`, which answers
 * null for anything it no longer offers. A caller reading a size through the
 * registry gets 0 for exactly these, and the storage pre-flight is one such
 * caller: 0 makes it believe more of `filesDir` is reusable than is, which is
 * the direction that admits a device it should have refused and then runs out
 * of disk partway through extraction.
 *
 * Keyed by short name, the form [toolchainShortName] produces, so a record
 * written as either `go` or `toolchain_go` resolves.
 */
internal val RETIRED_TOOLCHAINS = mapOf("go" to 179_000_000L)

private const val MANIFEST_NAME = "toolchains.sha256"

/**
 * Where to find the digest manifest for a toolchain ZIP: beside it.
 *
 * Derived from the ZIP's own URL rather than written down separately, which
 * removes a second constant that could drift from the first. It does **not**
 * remove the window underneath, and that window is larger than it looks.
 *
 * Both URLs go through `releases/latest/download/`, which names whichever
 * release is newest at the moment of each request -- and the two requests are
 * not adjacent. The manifest is read first, then the payload transfers, with up
 * to two backed-off retries behind it: minutes for the largest pack, Go at
 * 179 MB, on a phone connection -- not an instant. A release published anywhere
 * in that span hands back new bytes to check against a digest read from the
 * release before it.
 *
 * That fails closed -- the digests disagree, nothing is installed -- so it costs
 * a refused install rather than a bad one, and retrying succeeds. What it costs
 * beyond that is diagnostic: in the log it is indistinguishable from the
 * tampering this check exists to catch.
 *
 * That window is closed before this is called, by [pinnedAssetUrl] and the
 * resolution in front of it: the ZIP URL handed here already names a concrete
 * release, so deriving the manifest beside it inherits the same one. The
 * paragraphs above describe what happens when that resolution fails and the
 * unpinned URL is used as a fallback, which is the old behaviour and the floor
 * this can never drop below.
 */
internal fun manifestUrlFor(zipUrl: String): String =
    zipUrl.substringBeforeLast('/') + "/" + MANIFEST_NAME

/**
 * The release-level `latest` URL an asset URL resolves through, or null when
 * the URL is not of that shape.
 *
 * `https://host/o/r/releases/latest/download/x.zip` gives
 * `https://host/o/r/releases/latest`.
 *
 * Deliberately the RELEASE redirect and not the asset one. Measured 2026-08-16:
 * `releases/latest/download/<asset>` redirects to
 * `releases/download/<tag>/<asset>` and then again to a signed CDN URL that
 * carries no tag, so reading the end of that chain answers nothing and reading
 * its middle means depending on how many hops there are. `releases/latest`
 * redirects once, to `releases/tag/<tag>`, and involves no asset at all.
 */
internal fun latestReleaseUrlFor(assetUrl: String): String? {
    val i = assetUrl.indexOf(LATEST_DOWNLOAD)
    return if (i < 0) null else assetUrl.substring(0, i) + "/releases/latest"
}

/**
 * The tag named by a `releases/latest` redirect, or null.
 *
 * `https://host/o/r/releases/tag/v1.2.3` gives `v1.2.3`.
 *
 * A tag that is not plainly a tag is refused rather than pasted into a URL.
 * Everything downstream falls back to the unpinned URL on null, so refusing
 * costs the old behaviour; accepting something with a slash or a space in it
 * would build a URL that 404s for every toolchain.
 */
internal fun releaseTagFromLocation(location: String): String? {
    val i = location.indexOf(RELEASES_TAG)
    if (i < 0) return null
    val tag = location.substring(i + RELEASES_TAG.length)
        .substringBefore('?')
        .substringBefore('#')
        .trim('/')
    return if (tag.isNotEmpty() && TAG_CHARS.matches(tag)) tag else null
}

/**
 * [assetUrl] with `latest` replaced by a concrete [tag], or null.
 *
 * `.../releases/latest/download/x.zip` and `v1.2.3` give
 * `.../releases/download/v1.2.3/x.zip`.
 *
 * Refuses an asset name containing a slash, because that would mean the URL was
 * not the flat `releases/latest/download/<name>` this is written for and the
 * result would name something else entirely.
 */
internal fun pinnedAssetUrl(assetUrl: String, tag: String): String? {
    val i = assetUrl.indexOf(LATEST_DOWNLOAD)
    if (i < 0) return null
    val asset = assetUrl.substring(i + LATEST_DOWNLOAD.length)
    if (asset.isEmpty() || asset.contains('/')) return null
    if (!TAG_CHARS.matches(tag)) return null
    return assetUrl.substring(0, i) + "/releases/download/" + tag + "/" + asset
}

private const val LATEST_DOWNLOAD = "/releases/latest/download/"
private const val RELEASES_TAG = "/releases/tag/"
private val TAG_CHARS = Regex("""[A-Za-z0-9._-]+""")

/**
 * The digest a `sha256sum` manifest publishes for [fileName], or null when it
 * publishes none it can be trusted to mean.
 *
 * Separated from the network for the same reason as the other decisions in this
 * package, and here the one-directional risk is the sharpest in the file:
 * returning null costs a refused install that a re-release fixes, while
 * returning a digest the manifest did not really state for this file defeats the
 * entire check -- and it defeats it silently, because the comparison downstream
 * then passes.
 *
 * So every doubt resolves to null:
 *
 *  - a line whose first field is not exactly 64 hex characters is not a digest,
 *    including a line the read bound cut in half, and is skipped;
 *  - two lines naming this file with *different* digests are ambiguous, and a
 *    manifest that cannot make up its mind is refused outright rather than
 *    resolved by taking the first or the last;
 *  - no line at all is a refusal, which is what a release published without a
 *    manifest entry for this ZIP produces.
 *
 * Names are compared as bare filenames. `sha256sum` writes whatever path it was
 * handed, and prefixes a `*` in binary mode; both are stripped so a manifest
 * generated from a build directory still matches the flattened asset name a
 * device downloads.
 */
internal fun digestFromManifest(manifest: String, fileName: String): String? {
    var found: String? = null
    for (raw in manifest.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue

        val parts = line.split(WHITESPACE, limit = 2)
        if (parts.size != 2) continue

        val digest = parts[0].lowercase()
        if (!SHA256_HEX.matches(digest)) continue

        val name = parts[1].trim().removePrefix("*").substringAfterLast('/')
        if (name != fileName) continue

        if (found != null && found != digest) return null
        found = digest
    }
    return found
}

private val WHITESPACE = Regex("""\s+""")
private val SHA256_HEX = Regex("""^[0-9a-f]{64}$""")

/**
 * The SHA-256 of [file], lowercase hex.
 *
 * Streamed rather than read whole: the payloads are up to 179 MB and this runs
 * on a phone, where holding one in a byte array to hash it is how a verification
 * step becomes the reason an install dies.
 */
internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    file.inputStream().buffered().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The name a toolchain is persisted under, from either form callers use.
 *
 * `toolchain_ruby` and `ruby` name the same thing: the first is the asset pack and
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
