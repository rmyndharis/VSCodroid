package com.vscodroid.setup

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.system.Os
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.webview.redactToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Manages on-demand toolchain installation via Play Asset Delivery or HTTP fallback.
 *
 * Play Store install flow:
 *   1. fetch() -> AssetPackManager downloads the pack
 *   2. On COMPLETED -> installFromDirectory() copies files to filesDir (off main thread)
 *   3. chmod +x on binaries, create symlinks in usr/bin/
 *   4. Persist state to toolchains.json, then regenerate toolchain-env.sh and
 *      toolchain-exec.tsv from it
 *   5. removePack() to free the duplicate asset pack storage, only once that
 *      record is written
 *
 * HTTP fallback (sideloaded/debug builds):
 *   1. Download ZIP from GitHub Releases via [ToolchainHttpDownloader]
 *   2. Extract ZIP -> installFromDirectory() (shared with Play path)
 *   3. Same chmod/symlink/persist steps as above
 */
class ToolchainManager(internal val context: Context) {

    internal val tag = "ToolchainManager"
    private val assetPackManager = AssetPackManagerFactory.getInstance(context)
    internal val stateFile = File(context.filesDir, "home/.vscodroid/toolchains.json")
    internal val envFile = File(context.filesDir, "home/.vscodroid/toolchain-env.sh")

    /** Where an install records the tree it is about to write before copying bytes into `usr/`. */
    internal val installMarkerDir = File(context.filesDir, "home/.vscodroid/toolchain-installing")

    internal fun installMarkerFile(packName: String) = File(installMarkerDir, "$packName.json")

    /**
     * Persists a lightweight marker when a Play-delivered toolchain is uninstalled so launch
     * reconciliation retries pack removal instead of reinstalling a pack Play kept on disk.
     */
    internal val packReleaseMarkerDir = File(context.filesDir, "home/.vscodroid/toolchain-uninstalled")

    /**
     * The two derived files that make a toolchain command reachable from something that is not bash.
     */
    internal val execTable = File(Environment.getExecTablePath(context))
    internal val tcBinDir = File(Environment.getTrampolineBinDir(context))

    internal val filesDir = context.filesDir.absolutePath
    internal val homeDir = "$filesDir/home"

    /**
     * Single-thread executor for heavy file I/O (copy, chmod, symlink).
     * Core thread times out when idle via [toolchainIoExecutor].
     */
    internal val ioExecutor = toolchainIoExecutor()

    private val httpDownloader = ToolchainHttpDownloader(this)
    private val derivedFiles = ToolchainDerivedFiles(this)
    private val uninstaller = ToolchainUninstaller(this)

    /**
     * Progress and outcome for one pack: `(packName, status, percent, failureReason, transferId)`.
     */
    var onStateChange: ((String, Int, Int, ToolchainFailure?, Long?) -> Unit)? = null

    /** Progress or success. Never a failure: those go through [fail]. */
    private fun report(packName: String, status: Int, percent: Int, transferId: Long? = null) {
        if (status == AssetPackStatus.COMPLETED || status == AssetPackStatus.CANCELED) {
            synchronized(this) { playRequests.remove(packName) }
        }
        onStateChange?.invoke(packName, status, percent, null, transferId)
    }

    internal fun reportState(packName: String, status: Int, percent: Int, transferId: Long? = null) =
        report(packName, status, percent, transferId)

    /** A failure, and why. Every failure site names a [ToolchainFailure]. */
    private fun fail(packName: String, why: ToolchainFailure, transferId: Long? = null) {
        Logger.e(tag, "Toolchain ${redactToken(packName)} failed: $why")
        synchronized(this) { playRequests.remove(packName) }
        onStateChange?.invoke(packName, AssetPackStatus.FAILED, 0, why, transferId)
    }

    internal fun failState(packName: String, why: ToolchainFailure, transferId: Long? = null) =
        fail(packName, why, transferId)

    /** A Remove that did not happen, reported as UNKNOWN with [ToolchainFailure.REMOVAL]. */
    private fun failRemoval(packName: String) {
        Logger.e(tag, "Removing ${redactToken(packName)} failed")
        onStateChange?.invoke(packName, AssetPackStatus.UNKNOWN, 0, ToolchainFailure.REMOVAL, null)
    }

    internal fun failRemovalState(packName: String) = failRemoval(packName)

    /** The record exists and cannot be parsed; leave it in place and report INTERNAL. */
    private fun failUnreadableRecord(packName: String, transferId: Long? = null) {
        Logger.e(tag, "${stateFile.path} exists but cannot be read; leaving it as it is")
        fail(packName, ToolchainFailure.INTERNAL, transferId)
    }

    /**
     * Per-request cancellation and progress token for an HTTP toolchain download.
     */
    internal class HttpDownload {
        val transferId = nextHttpTransfer.incrementAndGet()
        @Volatile var cancelled = false
        @Volatile var percent = 0
        @Volatile var activeConnection: HttpURLConnection? = null

        /** Sleeps up to [backoffMs] while not cancelled, waking immediately on cancellation. */
        fun waitBackoff(backoffMs: Long) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoffMs)
            synchronized(this) {
                while (!cancelled) {
                    val remainingNanos = deadline - System.nanoTime()
                    if (remainingNanos <= 0L) break
                    val waitMs = remainingNanos / 1_000_000L
                    val waitNanos = (remainingNanos % 1_000_000L).toInt()
                    try {
                        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                        (this as Object).wait(waitMs, waitNanos)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
    }

    companion object {
        /** The installer names that mean Play delivered this app and can serve its asset packs. */
        private val PLAY_INSTALLERS = setOf(
            "com.android.vending",
            "com.google.android.feedback",
        )

        internal const val KEY_EXEC_REPAIRED = "execBitsChecked"
        internal const val KEY_INSTALL_ID = "installId"
        internal const val KEY_EXEC_RESET_FOR = "execBitsResetFor"

        /**
         * Environment a toolchain's manifest carries today and an install made by an
         * earlier build does not, by toolchain name.
         */
        private val ENV_BACKFILL = mapOf(
            "ruby" to mapOf("RUBYOPT" to "-rrubygems"),
        )

        /**
         * The most arguments an exec-table row can carry after its target.
         * `ExecTrampolineRowLimitTest` holds this equal to `scripts/exec-trampoline.c`.
         */
        internal const val MAX_ROW_ARGS = 8

        /** Serialises every read-modify-write cycle on `toolchains.json` and derived files. */
        internal val stateLock = Any()

        /** Process-wide coordination across multiple [ToolchainManager] instances. */
        internal val installsInFlight = ConcurrentHashMap.newKeySet<String>()
        internal val packOperationLock = Any()
        internal val packRemovalsInFlight = mutableSetOf<String>()
        internal val pendingPackReleases = mutableMapOf<String, () -> Unit>()
        internal val packReleaseGenerations = mutableMapOf<String, Long>()
        internal val nextPackReleaseGeneration = AtomicLong()

        /** Process-wide active HTTP transfers, keyed by pack name. */
        internal val httpDownloads = ConcurrentHashMap<String, HttpDownload>()
        internal val nextHttpTransfer = AtomicLong()

        /** Every pack with an HTTP transfer outstanding right now, and its percent progress. */
        internal fun packsDownloading(): Map<String, Int> =
            httpDownloads.mapValues { (_, download) -> download.percent }

        internal fun downloadProgress(): Map<String, ToolchainDownloadProgress> =
            httpDownloads.mapValues { (_, download) ->
                ToolchainDownloadProgress(download.transferId, download.percent)
            }

        /** Whether some install in this process holds [packName] right now. */
        internal fun packIsBeingInstalled(packName: String): Boolean =
            packName in installsInFlight || httpDownloads.containsKey(packName)
    }

    private val listener = AssetPackStateUpdateListener { state ->
        handleStateUpdate(state)
    }

    @Volatile
    private var installSourceIsPlay: Boolean? = null

    @Volatile private var listenerRegistered = false
    @Volatile private var listenerWanted = false
    private val listenerLock = ReentrantLock()
    private val playRequests = mutableMapOf<String, Any>()

    // -- Lifecycle --

    fun registerListener() {
        listenerWanted = true
        applyListener()
    }

    fun unregisterListener() {
        listenerWanted = false
        applyListener()
    }

    fun unregisterListenerIfIdle() {
        val idle = synchronized(this) { playRequests.isEmpty().also { if (it) listenerWanted = false } }
        if (idle) applyListener()
    }

    private fun applyListener() {
        do {
            if (!listenerLock.tryLock()) return
            try {
                while (listenerRegistered != listenerWanted) {
                    if (listenerWanted) assetPackManager.registerListener(listener)
                    else assetPackManager.unregisterListener(listener)
                    listenerRegistered = !listenerRegistered
                }
            } finally {
                listenerLock.unlock()
            }
        } while (listenerRegistered != listenerWanted)
    }

    // -- Query state --

    fun readPlayDownloads(onState: (packName: String, status: Int, percent: Int) -> Unit) {
        try {
            assetPackManager.getPackStates(ToolchainRegistry.available.map { it.packName })
                .addOnSuccessListener { states ->
                    for (state in states.packStates().values) {
                        onState(
                            state.name(),
                            state.status(),
                            packDownloadPercent(
                                state.bytesDownloaded(),
                                state.totalBytesToDownload(),
                            ),
                        )
                    }
                }
                .addOnFailureListener { e ->
                    Logger.d(tag, "Play has nothing to say about the toolchain packs: ${e.message}")
                }
        } catch (e: Exception) {
            Logger.d(tag, "Could not ask Play about the toolchain packs: ${e.message}")
        }
    }

    fun getInstalledToolchains(): List<String> {
        val state = readState()
        val result = mutableListOf<String>()
        for (i in 0 until state.length()) {
            val obj = state.optJSONObject(i) ?: continue
            result.add(obj.optString("name", ""))
        }
        return result.filter { it.isNotEmpty() }
    }

    /** Interrupted copies still own their files, but need an explicit Retry without a delivery. */
    internal fun getIncompleteToolchains(): List<String> = ToolchainRegistry.available
        .map { it.packName }
        .filter { !packIsBeingInstalled(it) && installIsPending(it) }

    private fun installIsPending(packName: String): Boolean {
        val marker = installMarkerFile(packName)
        if (!marker.exists() && !Files.isSymbolicLink(marker.toPath())) return false
        val pending = runCatching { JSONObject(marker.readText()) }.getOrNull() ?: return true
        val installId = pending.optString(KEY_INSTALL_ID)
        if (installId.isEmpty()) return true
        val state = readableState() ?: return true
        return (0 until state.length()).none {
            val installed = state.optJSONObject(it)
            installed != null && installed.optString("name") == pending.optString("name") &&
                installed.optString(KEY_INSTALL_ID) == installId
        }
    }

    fun installedLabels(): Map<String, String> {
        val state = readState()
        val labels = mutableMapOf<String, String>()
        for (i in 0 until state.length()) {
            val entry = state.optJSONObject(i) ?: continue
            val pack = "toolchain_${entry.optString("name")}"
            val recorded = entry.optString("displayName")
            val current = ToolchainRegistry.find(pack)?.displayName ?: continue
            if (recorded.isNotEmpty() && recorded != current) labels[pack] = recorded
        }
        return labels
    }

    // -- Install --

    fun install(packName: String) {
        val info = ToolchainRegistry.find(packName)
        if (info == null) {
            Logger.e(tag, "Unknown toolchain: ${redactToken(packName)}")
            fail(packName, ToolchainFailure.INTERNAL)
            return
        }
        Logger.i(tag, "Requesting install of ${info.displayName} (${info.packName})")

        if (readableState() == null) {
            failUnreadableRecord(info.packName)
            return
        }

        // An explicit install supersedes any pending release or deferred removePack from an earlier uninstall.
        uninstaller.clearPackReleaseMarker(info.packName, dropPendingRelease = true)

        if (shouldUseHttpFallback()) {
            val url = info.downloadUrl
            if (url == null) {
                Logger.e(tag, "No downloadUrl for ${info.packName}: Play Store required")
                fail(info.packName, ToolchainFailure.PLAY_REQUIRED)
                return
            }
            httpDownloader.downloadViaHttp(info.packName, url, info.estimatedSize, info.downloadSize)
        } else {
            val request = synchronized(this) {
                if (info.packName in playRequests) return
                listenerWanted = true
                Any().also { playRequests[info.packName] = it }
            }
            try {
                applyListener()
            } catch (e: Throwable) {
                synchronized(this) { playRequests.remove(info.packName) }
                throw e
            }
            try {
                assetPackManager.fetch(listOf(info.packName))
                    .addOnFailureListener { e ->
                        if (takeRequest(info.packName, request)) {
                            Logger.e(tag, "Play refused to fetch ${info.packName}: ${e.message}")
                            fail(info.packName, playFetchFailure(e))
                        }
                    }
            } catch (e: Exception) {
                if (takeRequest(info.packName, request)) {
                    fail(info.packName, playFetchFailure(e))
                }
            }
        }
    }

    private fun takeRequest(packName: String, request: Any): Boolean = synchronized(this) {
        (playRequests[packName] === request).also { if (it) playRequests.remove(packName) }
    }

    /**
     * Stops [packName]'s download, and returns whether the end of the download it stopped is still to be reported.
     */
    fun cancel(packName: String): Boolean {
        val info = ToolchainRegistry.find(packName) ?: return false
        val transfer = httpDownloads[info.packName]
        var connToClose: HttpURLConnection? = null
        val transferReportsItsEnd = transfer != null && synchronized(transfer) {
            transfer.cancelled = true
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (transfer as Object).notifyAll()
            connToClose = transfer.activeConnection
            httpDownloads[info.packName] === transfer
        }
        connToClose?.let(httpDownloader::disconnectAsync)
        synchronized(this) { playRequests.remove(info.packName) }
        val answer = assetPackManager.cancel(listOf(info.packName))
        val viaPlay = !shouldUseHttpFallback()
        if (viaPlay) {
            ioExecutor.execute { releasePack(info.packName) }
        }
        Logger.i(tag, "Cancelled download of ${info.packName}")
        return transferReportsItsEnd ||
            (viaPlay && answer?.packStates()?.get(info.packName)?.status() == AssetPackStatus.CANCELED)
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

    // -- Uninstall (delegated to ToolchainUninstaller) --

    fun uninstall(name: String) {
        ioExecutor.execute {
            try {
                uninstallSync(name)
            } catch (e: Exception) {
                Logger.e(tag, "Failed to uninstall ${redactToken(name)}", e)
                failRemoval("toolchain_${toolchainShortName(name)}")
            }
        }
    }

    private fun uninstallSync(nameOrPack: String) = uninstaller.uninstallSync(nameOrPack)

    private fun packReleaseMarker(packName: String): File? = uninstaller.packReleaseMarker(packName)

    private fun markPackReleasePending(packName: String): Long = uninstaller.markPackReleasePending(packName)

    // -- Asset pack state handling --

    private fun handleStateUpdate(state: AssetPackState) {
        val packName = state.name()
        val status = state.status()
        val totalBytes = state.totalBytesToDownload()
        val downloaded = state.bytesDownloaded()
        val percent = packDownloadPercent(downloaded, totalBytes)

        Logger.d(tag, "Pack $packName: status=$status, $downloaded/$totalBytes ($percent%)")

        if (status != AssetPackStatus.COMPLETED && status != AssetPackStatus.FAILED) {
            report(packName, status, percent)
        }

        @SuppressLint("SwitchIntDef")
        when (status) {
            AssetPackStatus.COMPLETED -> {
                ioExecutor.execute {
                    try {
                        val location = assetPackManager.getPackLocation(packName)
                        val assetsPath = location?.assetsPath()
                        if (assetsPath != null) {
                            installDeliveredPack(packName, File(assetsPath))
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
                Logger.w(tag, "Pack $packName requires user confirmation")
            }
            else -> { /* DOWNLOADING, PENDING, WAITING_FOR_WIFI, etc., just report progress */ }
        }
    }

    private fun installDeliveredPack(packName: String, assetsDir: File) {
        val unpacked = packUnpackedBytes(packName)
        if (unpacked == null) {
            Logger.w(
                tag,
                "No recorded size for $packName, so the space pre-flight is skipped; " +
                    "a full device will fail during the copy instead of before it",
            )
        } else {
            val space = toolchainCopySpace(File(assetsDir, "usr"), File(context.filesDir, "usr"))
            val required = toolchainCopyBytes(unpacked, space)
            val available = httpDownloader.freeBytesFor(required)
            if (available < required) {
                Logger.e(
                    tag,
                    "Not enough disk space for $packName: " +
                        "${available / 1_000_000} MB available, " +
                        "${required / 1_000_000} MB required",
                )
                fail(packName, ToolchainFailure.STORAGE)
                return
            }
        }
        if (installFromDirectory(packName, assetsDir)) {
            releasePack(packName)
        }
    }

    /**
     * Finishes any Play delivery that completed while nothing was listening for it,
     * and reclaims delivered packs for already-installed, uninstalled, or retired toolchains.
     */
    fun reconcileDeliveredPacks() {
        ioExecutor.execute {
            if (shouldUseHttpFallback()) return@execute
            try {
                val installed = getInstalledToolchains()
                ToolchainRegistry.available.forEach { info ->
                    try {
                        reconcileOnePack(info, installed)
                    } catch (e: Exception) {
                        Logger.w(tag, "Could not reconcile ${info.packName}: ${e.message}")
                    }
                }
                RETIRED_TOOLCHAINS.keys.forEach { name ->
                    val packName = "toolchain_$name"
                    try {
                        val genBefore = synchronized(packOperationLock) { packReleaseGenerations[packName] }
                        if (assetPackManager.getPackLocation(packName) == null) {
                            uninstaller.clearPackReleaseMarkerIfUnchanged(packName, genBefore)
                            return@forEach
                        }
                        Logger.i(tag, "Reclaiming a delivered pack for a retired toolchain: $packName")
                        releasePack(packName)
                    } catch (e: Exception) {
                        Logger.w(tag, "Could not ask Play about $packName: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Logger.w(tag, "Could not reconcile delivered packs: ${e.message}")
            }
        }
    }

    private fun reconcileOnePack(
        info: ToolchainRegistry.ToolchainInfo,
        installed: List<String>,
    ) {
        val generationBefore = synchronized(packOperationLock) { packReleaseGenerations[info.packName] }
        val location = try {
            assetPackManager.getPackLocation(info.packName)
        } catch (e: Exception) {
            Logger.w(tag, "Could not ask Play about ${info.packName}: ${e.message}")
            return
        }
        if (location == null) {
            uninstaller.clearPackReleaseMarkerIfUnchanged(info.packName, generationBefore)
            return
        }
        if (info.packName.removePrefix("toolchain_") in installed && !installIsPending(info.packName)) {
            if (info.packName in installsInFlight) return
            Logger.i(tag, "Reclaiming a delivered pack for an installed toolchain: ${info.packName}")
            releasePack(info.packName)
            return
        }
        if (uninstaller.releasePackIfPending(info.packName)) {
            return
        }
        val assetsPath = location.assetsPath() ?: return
        Logger.i(tag, "Finishing a delivery nothing was listening for: ${info.packName}")
        installDeliveredPack(info.packName, File(assetsPath))
    }

    private fun claimPack(packName: String, readsDeliveredPack: Boolean = true): Boolean =
        synchronized(packOperationLock) {
            (!readsDeliveredPack || packName !in packRemovalsInFlight) &&
                installsInFlight.add(packName)
        }

    internal fun claimPackInternal(packName: String, readsDeliveredPack: Boolean = true): Boolean =
        claimPack(packName, readsDeliveredPack)

    private fun releasePackClaim(packName: String) {
        val pendingRelease = synchronized(packOperationLock) {
            installsInFlight.remove(packName)
            pendingPackReleases.remove(packName)
        }
        pendingRelease?.invoke()
    }

    internal fun releasePackClaimInternal(packName: String) = releasePackClaim(packName)

    private fun releasePack(packName: String, expectedGeneration: Long? = null) {
        val releaseGeneration = synchronized(packOperationLock) {
            if (expectedGeneration != null && packReleaseGenerations[packName] != expectedGeneration) return
            if (packName in packRemovalsInFlight) return
            if (packName in installsInFlight) {
                val deferredGeneration = expectedGeneration
                pendingPackReleases[packName] = { releasePack(packName, deferredGeneration) }
                return
            }
            packRemovalsInFlight.add(packName)
            packReleaseGenerations[packName] ?: nextPackReleaseGeneration.incrementAndGet().also {
                packReleaseGenerations[packName] = it
            }
        }
        startRemovePack(packName, releaseGeneration)
    }

    internal fun releasePackInternal(packName: String, expectedGeneration: Long? = null) =
        releasePack(packName, expectedGeneration)

    private fun startRemovePack(packName: String, releaseGeneration: Long) {
        try {
            assetPackManager.removePack(packName)
                .addOnSuccessListener {
                    Logger.i(tag, "Removed asset pack $packName (freed duplicate storage)")
                }
                .addOnFailureListener { e ->
                    Logger.w(
                        tag,
                        "Play did not remove asset pack $packName; it stays on disk " +
                            "until the next launch reclaims it",
                        e,
                    )
                }
                .addOnCompleteListener(Executor { it.run() }) { task ->
                    val succeeded = runCatching { task.isSuccessful }.getOrDefault(false)
                    synchronized(packOperationLock) {
                        packRemovalsInFlight.remove(packName)
                        if (succeeded && packReleaseGenerations[packName] == releaseGeneration) {
                            val marker = uninstaller.packReleaseMarker(packName)
                            if (marker != null && (marker.exists() || Files.isSymbolicLink(marker.toPath()))) {
                                marker.delete()
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            synchronized(packOperationLock) { packRemovalsInFlight.remove(packName) }
            Logger.w(tag, "Could not remove asset pack $packName: ${e.message}")
        }
    }

    internal fun startRemovePackInternal(packName: String, releaseGeneration: Long) =
        startRemovePack(packName, releaseGeneration)

    // -- File installation (shared between Play Asset Delivery and HTTP fallback) --

    private fun installFromDirectory(packName: String, assetsDir: File): Boolean =
        installFromDirectory(packName, assetsDir, readsDeliveredPack = true)

    private fun installFromDirectory(
        packName: String,
        assetsDir: File,
        readsDeliveredPack: Boolean,
    ): Boolean = installFromDirectory(packName, assetsDir, readsDeliveredPack, null)

    private fun installFromDirectory(
        packName: String,
        assetsDir: File,
        readsDeliveredPack: Boolean,
        transferId: Long?,
    ): Boolean {
        if (!claimPack(packName, readsDeliveredPack)) {
            Logger.i(tag, "Another install already holds $packName; leaving it to that one")
            if (readsDeliveredPack) synchronized(this) { playRequests.remove(packName) }
            report(packName, AssetPackStatus.UNKNOWN, 0, transferId)
            return false
        }
        return try {
            installFromDirectoryHoldingPack(packName, assetsDir, transferId)
        } finally {
            releasePackClaim(packName)
        }
    }

    internal fun installFromStaging(packName: String, extractDir: File, transferId: Long?): Boolean =
        installFromDirectory(packName, extractDir, readsDeliveredPack = false, transferId = transferId)

    /**
     * The install itself, with the pack already claimed by the caller.
     * Flushes writes to media before recording completion in `toolchains.json`.
     */
    private fun installFromDirectoryHoldingPack(
        packName: String,
        assetsDir: File,
        transferId: Long?,
    ): Boolean {
        val manifestFile = File(assetsDir, "$packName.json")
        if (!manifestFile.exists()) {
            Logger.e(tag, "No $packName.json in asset pack $packName")
            fail(packName, ToolchainFailure.CORRUPT, transferId)
            return true
        }

        val manifest = JSONObject(manifestFile.readText())
        val name = manifest.optString("name", "")
        if (name.isEmpty()) {
            Logger.e(tag, "Invalid manifest.json in $packName: missing 'name'")
            fail(packName, ToolchainFailure.CORRUPT, transferId)
            return true
        }
        val usrSrc = File(assetsDir, "usr")
        if (!usrSrc.isDirectory) {
            Logger.e(tag, "No usr/ payload in asset pack $packName")
            fail(packName, ToolchainFailure.CORRUPT, transferId)
            return true
        }
        val installRoot = manifest.optString("installRoot", "")
        if (installRoot.isNotEmpty()) {
            if (!isOwnedPathName(installRoot)) {
                Logger.e(tag, "Refusing installRoot outside managed usr/ subtree in $packName: ${redactToken(installRoot)}")
                fail(packName, ToolchainFailure.CORRUPT, transferId)
                return true
            }
            if (confinedUsrPath(context.filesDir, installRoot) == null) {
                Logger.e(tag, "Refusing installRoot with redirected ancestor in $packName: ${redactToken(installRoot)}")
                fail(packName, ToolchainFailure.INTERNAL, transferId)
                return false
            }
        }
        val manifestLibs = manifest.optJSONArray("libs")
        if (manifestLibs != null) {
            for (i in 0 until manifestLibs.length()) {
                val libName = manifestLibs.getString(i)
                if (!isSinglePathSegment(libName)) {
                    Logger.e(tag, "Refusing lib entry outside usr/lib/ in $packName: ${redactToken(libName)}")
                    fail(packName, ToolchainFailure.CORRUPT, transferId)
                    return true
                }
                if (confinedUsrPath(context.filesDir, "usr/lib/$libName") == null) {
                    Logger.e(tag, "Refusing lib entry with redirected ancestor in $packName: ${redactToken(libName)}")
                    fail(packName, ToolchainFailure.INTERNAL, transferId)
                    return false
                }
            }
        }
        val binaries = manifest.optJSONArray("binaries")
        if (binaries != null) {
            for (i in 0 until binaries.length()) {
                val path = binaries.getString(i)
                if (!isLexicalUsrPath(path)) {
                    Logger.e(tag, "Refusing binary path outside usr/ in $packName: ${redactToken(path)}")
                    fail(packName, ToolchainFailure.CORRUPT, transferId)
                    return true
                }
                if (confinedUsrPath(context.filesDir, path) == null) {
                    Logger.e(tag, "Refusing binary path with redirected ancestor in $packName: ${redactToken(path)}")
                    fail(packName, ToolchainFailure.INTERNAL, transferId)
                    return false
                }
                if (!File(assetsDir, path).isFile) {
                    Logger.e(tag, "Missing required binary $path in asset pack $packName")
                    fail(packName, ToolchainFailure.CORRUPT, transferId)
                    return true
                }
            }
        }
        val manifestSymlinks = manifest.optJSONObject("symlinks")
        if (manifestSymlinks != null) {
            for (linkName in manifestSymlinks.keys()) {
                val target = manifestSymlinks.getString(linkName)
                if (!isSinglePathSegment(linkName) || !isLexicalUsrPath(target)) {
                    Logger.e(tag, "Refusing symlink outside usr/ in $packName: ${redactToken(linkName)} -> ${redactToken(target)}")
                    fail(packName, ToolchainFailure.CORRUPT, transferId)
                    return true
                }
                if (confinedUsrPath(context.filesDir, "usr/bin/$linkName") == null ||
                    !isConfinedUsrTarget(context.filesDir, target)
                ) {
                    Logger.e(tag, "Refusing symlink with redirected ancestor in $packName: ${redactToken(linkName)}")
                    fail(packName, ToolchainFailure.INTERNAL, transferId)
                    return false
                }
            }
        }
        val manifestLibSymlinks = manifest.optJSONObject("libSymlinks")
        if (manifestLibSymlinks != null) {
            for (linkName in manifestLibSymlinks.keys()) {
                val targetName = manifestLibSymlinks.getString(linkName)
                if (!isSinglePathSegment(linkName) || !isLexicalUsrPath("usr/lib/$targetName")) {
                    Logger.e(tag, "Refusing lib symlink outside usr/ in $packName: ${redactToken(linkName)} -> ${redactToken(targetName)}")
                    fail(packName, ToolchainFailure.CORRUPT, transferId)
                    return true
                }
                if (confinedUsrPath(context.filesDir, "usr/lib/$linkName") == null ||
                    !isConfinedUsrTarget(context.filesDir, "usr/lib/$targetName")
                ) {
                    Logger.e(tag, "Refusing lib symlink with redirected ancestor in $packName: ${redactToken(linkName)}")
                    fail(packName, ToolchainFailure.INTERNAL, transferId)
                    return false
                }
            }
        }
        firstPathInTheWay(usrSrc, File(context.filesDir, "usr"))?.let { blocked ->
            Logger.e(tag, "Not installing $packName: ${blocked.message}")
            fail(packName, ToolchainFailure.INTERNAL, transferId)
            return false
        }
        synchronized(stateLock) {
            if (readableState() == null) {
                failUnreadableRecord(packName, transferId)
                return false
            }
            val previousMarker = installMarkerFile(packName)
            if (previousMarker.exists() || Files.isSymbolicLink(previousMarker.toPath())) {
                val previous = runCatching { JSONObject(previousMarker.readText()) }.getOrNull()
                if (previous == null || previous.optString("name") != name ||
                    !uninstaller.toolchainPathsAreOwned(previous, manifest) &&
                    !uninstaller.reclaimPartialCopy(name, previous, reclaimSameRootOrphans = true)
                ) {
                    Logger.w(tag, "Keeping the previous install record for $packName until its files are settled")
                    fail(packName, ToolchainFailure.INTERNAL, transferId)
                    return false
                }
            }
        }
        installMarkerDir.mkdirs()
        manifest.put(KEY_INSTALL_ID, UUID.randomUUID().toString())
        if (!writeAtomically(installMarkerFile(packName)) { it.write(manifest.toString().toByteArray()) }) {
            Logger.e(tag, "Could not record the pending install of $packName; leaving its files unchanged")
            fail(packName, ToolchainFailure.STORAGE, transferId)
            return false
        }
        Logger.i(tag, "Installing toolchain: $name (from $packName)")

        try {
            copyDirectoryTree(usrSrc, File(context.filesDir, "usr"))
        } catch (e: IOException) {
            Logger.e(tag, "Could not copy $packName into usr/", e)
            if (uninstaller.reclaimPartialCopy(name, manifest)) installMarkerFile(packName).delete()
            fail(packName, if (e is PathOccupied) ToolchainFailure.INTERNAL else ToolchainFailure.STORAGE, transferId)
            return false
        }

        val binariesConfined = binaries == null || (0 until binaries.length()).all { i ->
            val binFile = confinedUsrPath(context.filesDir, binaries.getString(i))
            binFile != null && !Files.isSymbolicLink(binFile.toPath())
        }
        if (!binariesConfined) {
            Logger.e(tag, "Refusing $packName: binary path escaped usr/ during install")
            if (uninstaller.reclaimPartialCopy(name, manifest)) installMarkerFile(packName).delete()
            fail(packName, ToolchainFailure.INTERNAL, transferId)
            return false
        }

        val execBitsSet = applyManifestExecBits(manifest)

        var failedLink: ToolchainFailure? = null
        val symlinks = manifest.optJSONObject("symlinks")
        if (symlinks != null) {
            val binDir = File(context.filesDir, "usr/bin")
            binDir.mkdirs()
            for (linkName in symlinks.keys()) {
                val target = symlinks.getString(linkName)
                if (!isSinglePathSegment(linkName) ||
                    confinedUsrPath(context.filesDir, "usr/bin/$linkName") == null ||
                    !isConfinedUsrTarget(context.filesDir, target)
                ) {
                    failedLink = ToolchainFailure.INTERNAL
                    Logger.w(tag, "Refusing symlink outside usr/: ${redactToken(linkName)} -> ${redactToken(target)}")
                    continue
                }
                val targetAbsolute = "$filesDir/$target"
                val linkFile = File(binDir, linkName)

                val linkExists = try { Os.lstat(linkFile.absolutePath); true } catch (e: Exception) { false }
                if (linkExists) {
                    linkFile.delete()
                }

                try {
                    Os.symlink(targetAbsolute, linkFile.absolutePath)
                    Logger.d(tag, "Symlink: $linkName -> $target")
                } catch (e: Exception) {
                    if (failedLink != ToolchainFailure.INTERNAL) failedLink = linkFailure(e)
                    Logger.w(tag, "Failed to create symlink $linkName: ${e.message}")
                }
            }
        }

        val libSymlinks = manifest.optJSONObject("libSymlinks")
        if (libSymlinks != null) {
            val libDir = File(context.filesDir, "usr/lib")
            for (linkName in libSymlinks.keys()) {
                val targetName = libSymlinks.getString(linkName)
                if (!isSinglePathSegment(linkName) ||
                    confinedUsrPath(context.filesDir, "usr/lib/$linkName") == null ||
                    !isConfinedUsrTarget(context.filesDir, "usr/lib/$targetName")
                ) {
                    failedLink = ToolchainFailure.INTERNAL
                    Logger.w(tag, "Refusing lib symlink outside usr/: ${redactToken(linkName)} -> ${redactToken(targetName)}")
                    continue
                }
                val targetFile = File(libDir, targetName)
                val linkFile = File(libDir, linkName)
                if (targetFile.exists()) {
                    val linkExists = try { Os.lstat(linkFile.absolutePath); true } catch (e: Exception) { false }
                    if (linkExists) linkFile.delete()
                    try {
                        Os.symlink(targetFile.absolutePath, linkFile.absolutePath)
                        Logger.d(tag, "Lib symlink: $linkName -> $targetName")
                    } catch (e: Exception) {
                        if (failedLink != ToolchainFailure.INTERNAL) failedLink = linkFailure(e)
                        Logger.w(tag, "Failed to create lib symlink $linkName: ${e.message}")
                    }
                }
            }
        }
        failedLink?.let { why ->
            Logger.e(tag, "Could not link $packName into usr/")
            if (uninstaller.reclaimPartialCopy(name, manifest)) installMarkerFile(packName).delete()
            fail(packName, why, transferId)
            return false
        }

        flushWritesToMedia()

        var replaced: JSONObject? = null
        synchronized(stateLock) {
            val state = readableState() ?: run {
                failUnreadableRecord(packName, transferId)
                return false
            }
            for (i in state.length() - 1 downTo 0) {
                val entry = state.optJSONObject(i)
                if (entry?.optString("name") == name) {
                    replaced = entry
                    state.remove(i)
                }
            }
            manifest.put(KEY_EXEC_REPAIRED, execBitsSet)
            state.put(manifest)
            backfillToolchainEnv(state)
            if (!writeState(state)) {
                if (uninstaller.reclaimPartialCopy(name, manifest)) installMarkerFile(packName).delete()
                fail(packName, ToolchainFailure.STORAGE, transferId)
                return false
            }
            derivedFiles.regenerateDerivedFilesLocked()
            uninstaller.clearPackReleaseMarker(packName)
        }

        uninstaller.removeSupersededTree(replaced, manifest)

        installMarkerFile(packName).delete()
        Logger.i(tag, "Toolchain $name installed successfully")
        report(packName, AssetPackStatus.COMPLETED, 100, transferId)
        return true
    }

    /**
     * Gives the execute bit to every binary [manifest] names, and says whether they all have it now.
     */
    private fun applyManifestExecBits(manifest: JSONObject): Boolean {
        val binaries = manifest.optJSONArray("binaries") ?: return true
        var allExecutable = true
        for (i in 0 until binaries.length()) {
            val binPath = binaries.getString(i)
            val binFile = confinedUsrPath(context.filesDir, binPath)
            if (binFile == null) {
                Logger.w(tag, "Refusing to set execute bit outside usr/: ${redactToken(binPath)}")
                allExecutable = false
                continue
            }
            if (Files.isSymbolicLink(binFile.toPath())) {
                Logger.w(tag, "Refusing to set execute bit on symlink: ${redactToken(binPath)}")
                allExecutable = false
                continue
            }
            if (!binFile.exists() || binFile.canExecute()) continue
            if (binFile.setExecutable(true, true)) {
                Logger.d(tag, "chmod +x: $binPath")
            } else {
                Logger.w(tag, "Could not set the execute bit on $binPath")
                allExecutable = false
            }
        }
        return allExecutable
    }

    /**
     * Returns true if the app was NOT installed via Play Store.
     */
    private fun shouldUseHttpFallback(): Boolean {
        installSourceIsPlay?.let { return !it }
        val isPlay = try {
            // Android 10 is the one supported release without InstallSourceInfo.
            val installer = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            } else {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            }
            Logger.d(tag, "Install source: $installer")
            installer in PLAY_INSTALLERS
        } catch (e: Exception) {
            Logger.w(tag, "Could not determine install source, using HTTP fallback: ${e.message}")
            false
        }
        installSourceIsPlay = isPlay
        return !isPlay
    }

    internal fun usesHttpFallback(): Boolean = shouldUseHttpFallback()

    // -- Derived files (delegated to ToolchainDerivedFiles) --

    fun regenerateDerivedFiles() = synchronized(stateLock) { derivedFiles.regenerateDerivedFilesLocked() }

    internal fun regenerateDerivedFilesInternal() = derivedFiles.regenerateDerivedFilesLocked()

    fun getAllToolchainEnv(): Map<String, String> = derivedFiles.getAllToolchainEnv()

    // -- Repair of installs from earlier app versions --

    fun repairInstalledToolchains() {
        ioExecutor.execute {
            try {
                removeRetiredToolchainsSync()
            } catch (e: Exception) {
                Logger.w(tag, "Could not remove a retired toolchain: ${e.message}")
            }
            try {
                httpDownloader.sweepAbandonedDownloadsSync()
            } catch (e: Exception) {
                Logger.w(tag, "Could not sweep abandoned toolchain downloads: ${e.message}")
            }
            try {
                reclaimInterruptedInstallsSync()
            } catch (e: Exception) {
                Logger.w(tag, "Could not reclaim an interrupted toolchain install: ${e.message}")
            }
            try {
                repairInstalledToolchainsSync()
            } catch (e: Exception) {
                Logger.w(tag, "Toolchain repair pass failed: ${e.message}")
            }
            try {
                backfillToolchainEnvSync()
            } catch (e: Exception) {
                Logger.w(tag, "Could not add a toolchain's new environment: ${e.message}")
            }
            try {
                regenerateDerivedFiles()
            } catch (e: Exception) {
                Logger.w(tag, "Could not refresh the toolchain environment: ${e.message}")
            }
        }
    }

    private fun reclaimInterruptedInstallsSync() = uninstaller.reclaimInterruptedInstallsSync()

    private fun removeRetiredToolchainsSync() = uninstaller.removeRetiredToolchainsSync()

    private fun repairInstalledToolchainsSync() = synchronized(stateLock) {
        val state = readState()
        var changed = false

        for (i in 0 until state.length()) {
            val entry = state.optJSONObject(i) ?: continue
            if (entry.optBoolean(KEY_EXEC_REPAIRED, false)) continue

            val name = entry.optString("name", "?")
            val installRoot = entry.optString("installRoot", "")
            val root = if (installRoot.isEmpty()) {
                null
            } else if (!isOwnedPathName(installRoot)) {
                Logger.w(tag, "Skipping repair walk for $name: installRoot is outside managed usr/ subtree")
                null
            } else {
                confinedUsrPath(context.filesDir, installRoot).also {
                    if (it == null) {
                        Logger.w(tag, "Skipping repair walk for $name: installRoot has a redirected ancestor")
                    }
                }
            }

            if (root != null && !Files.isSymbolicLink(root.toPath()) && root.isDirectory) {
                val fixed = markExecutablesIn(root)
                if (fixed > 0) {
                    Logger.i(tag, "Restored the execute bit on $fixed binaries in $name")
                }
            }
            val binariesRepaired = applyManifestExecBits(entry)
            if (binariesRepaired) {
                entry.put(KEY_EXEC_REPAIRED, true)
                changed = true
            }
        }

        if (changed) writeState(state)
    }

    private fun backfillToolchainEnvSync() = synchronized(stateLock) {
        val state = readState()
        if (backfillToolchainEnv(state)) writeState(state)
    }

    private fun backfillToolchainEnv(state: JSONArray): Boolean {
        var changed = false
        for (i in 0 until state.length()) {
            val entry = state.optJSONObject(i) ?: continue
            val wanted = ENV_BACKFILL[entry.optString("name")] ?: continue
            val env = entry.optJSONObject("env") ?: JSONObject().also { entry.put("env", it) }
            for ((key, value) in wanted) {
                if (!env.has(key)) {
                    env.put(key, value)
                    changed = true
                }
            }
            val legacyGemPath = "\$HOME/.gem/ruby:\$FILESDIR/usr/lib/ruby/gems"
            if (entry.optString("name") == "ruby" && env.optString("GEM_PATH") == legacyGemPath) {
                val rubyLib = env.optString("RUBYLIB").substringBefore(':')
                val abi = rubyLib.removePrefix("\$FILESDIR/usr/lib/ruby/")
                if (abi != rubyLib && Regex("""\d+\.\d+\.\d+""").matches(abi)) {
                    env.put("GEM_PATH", "$legacyGemPath/$abi")
                    changed = true
                }
            }
        }
        return changed
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

    internal fun readStateFile(): JSONArray = readState()

    private fun readableState(): JSONArray? {
        if (!stateFile.exists() && !Files.isSymbolicLink(stateFile.toPath())) return JSONArray()
        return try {
            JSONArray(stateFile.readText())
        } catch (e: Exception) {
            null
        }
    }

    internal fun readableStateFile(): JSONArray? = readableState()

    private fun writeState(state: JSONArray): Boolean = synchronized(stateLock) {
        stateFile.parentFile?.mkdirs()
        val written = writeAtomically(stateFile) { it.write(state.toString(2).toByteArray()) }
        if (!written) {
            Logger.e(tag, "Could not write toolchains.json; it still holds the previous record")
        }
        written
    }

    internal fun writeStateFile(state: JSONArray): Boolean = writeState(state)
}

/** Free space asked for beyond what the install itself needs. */
internal const val SPACE_BUFFER = 50_000_000L

/**
 * Toolchains no longer offered, against the space each still occupies on a
 * device that installed one before it was withdrawn.
 */
internal val RETIRED_TOOLCHAINS = mapOf("go" to 179_000_000L)

/** Emits a bash wrapper line for an ELF toolchain binary into `toolchain-env.sh`. */
internal fun appendToolchainBinaryWrapperLine(
    lines: MutableList<String>,
    command: String,
    systemLoader: String,
    relPath: String,
    toolFlags: String,
    properties: String?,
) {
    if (properties != null) {
        lines.add("$command() { $systemLoader \"\$PREFIX/../$relPath\" $toolFlags$properties \"\$@\"; }")
    } else {
        lines.add("$command() { $systemLoader \"\$PREFIX/../$relPath\" $toolFlags\"\$@\"; }")
    }
}

/** Emits a bash wrapper line for an interpreted toolchain script into `toolchain-env.sh`. */
internal fun appendToolchainScriptWrapperLine(
    lines: MutableList<String>,
    scriptName: String,
    interpreter: String,
    scriptPath: String,
) {
    lines.add("$scriptName() { $interpreter \"\$PREFIX/../$scriptPath\" \"\$@\"; }")
}
