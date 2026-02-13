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
    @Volatile private var httpCancelled = false

    companion object {
        private const val SPACE_BUFFER = 50_000_000L  // 50 MB free space buffer
        private const val HTTP_TIMEOUT_MS = 30_000     // 30s connect + read timeout
        private const val MAX_RETRIES = 2
        private const val DOWNLOAD_BUFFER_SIZE = 8192
        private const val MAX_REDIRECTS = 5
    }

    private val listener = AssetPackStateUpdateListener { state ->
        handleStateUpdate(state)
    }

    // -- Lifecycle --

    fun registerListener() {
        assetPackManager.registerListener(listener)
    }

    fun unregisterListener() {
        assetPackManager.unregisterListener(listener)
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
        // Signal HTTP download to stop (checked every 8KB in download loop)
        httpCancelled = true
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

    private fun uninstallSync(name: String) {
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

        // Delete libs that were copied to usr/lib/
        val libs = manifestObj.optJSONArray("libs")
        if (libs != null) {
            for (i in 0 until libs.length()) {
                val lib = File(context.filesDir, "usr/lib/${libs.getString(i)}")
                if (lib.exists()) lib.delete()
            }
        }

        // Remove from state
        state.remove(idx)
        writeState(state)
        regenerateEnvFile()

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
        val manifestFile = File(assetsDir, "$packName.json")
        if (!manifestFile.exists()) {
            Logger.e(tag, "No $packName.json in asset pack $packName")
            return
        }

        val manifest = JSONObject(manifestFile.readText())
        val name = manifest.optString("name", "")
        if (name.isEmpty()) {
            Logger.e(tag, "Invalid manifest.json in $packName: missing 'name'")
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

        // Persist state
        val state = readState()
        // Remove any existing entry for this toolchain
        for (i in state.length() - 1 downTo 0) {
            if (state.optJSONObject(i)?.optString("name") == name) {
                state.remove(i)
            }
        }
        state.put(manifest)
        writeState(state)
        regenerateEnvFile()

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
        httpCancelled = false
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
                downloadWithRetries(packName, url, zipFile, estimatedSize)

                if (httpCancelled) {
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
                if (httpCancelled) {
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
    private fun downloadWithRetries(packName: String, url: String, destFile: File, estimatedSize: Long) {
        var lastException: IOException? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                if (attempt > 0) {
                    val backoffMs = (1L shl attempt) * 1000  // 2s, 4s
                    Logger.i(tag, "Retry $attempt/$MAX_RETRIES for $packName after ${backoffMs}ms")
                    Thread.sleep(backoffMs)
                }
                downloadFile(packName, url, destFile, estimatedSize)
                return  // Success
            } catch (e: IOException) {
                lastException = e
                if (httpCancelled) throw e
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
     */
    @Throws(IOException::class)
    private fun downloadFile(packName: String, url: String, destFile: File, estimatedSize: Long) {
        var currentUrl = url
        var redirects = 0

        while (redirects < MAX_REDIRECTS) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "VSCodroid")

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

                val totalBytes = conn.contentLengthLong.let {
                    if (it > 0) it else estimatedSize
                }

                onStateChange?.invoke(packName, AssetPackStatus.DOWNLOADING, 0)

                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var bytesRead: Long = 0
                        var len: Int

                        while (input.read(buffer).also { len = it } != -1) {
                            if (httpCancelled) {
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
    fun regenerateEnvFile() {
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
            // on Android (noexec /data). Invokes scripts via their interpreter instead.
            val scriptWrappers = tc.optJSONObject("scriptWrappers")
            if (scriptWrappers != null) {
                val interpreter = scriptWrappers.optString("interpreter", "")
                val scripts = scriptWrappers.optJSONObject("scripts")
                if (interpreter.isNotEmpty() && scripts != null) {
                    sb.appendLine("# $name script wrappers (Android noexec)")
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
        envFile.writeText(sb.toString())
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

    private fun writeState(state: JSONArray) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(state.toString(2))
    }
}
