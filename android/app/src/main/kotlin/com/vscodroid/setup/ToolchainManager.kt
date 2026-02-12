package com.vscodroid.setup

import android.app.Activity
import android.content.Context
import android.system.Os
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Manages on-demand toolchain installation via Play Asset Delivery.
 *
 * Install flow:
 *   1. fetch() → AssetPackManager downloads the pack
 *   2. On COMPLETED → copyFromAssetPack() copies files to filesDir (off main thread)
 *   3. chmod +x on binaries, create symlinks in usr/bin/
 *   4. Write toolchain-env.sh for bash, persist state to toolchains.json
 *   5. removePack() to free the duplicate asset pack storage
 *
 * For sideloaded installs (no Play Store), a future HTTP fallback can be
 * added by checking isPlayStoreAvailable() before calling fetch().
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
            return
        }
        Logger.i(tag, "Requesting install of ${info.displayName} (${info.packName})")
        // Ensure listener is registered before fetching
        registerListener()
        assetPackManager.fetch(listOf(info.packName))
    }

    fun cancel(packName: String) {
        val info = ToolchainRegistry.find(packName) ?: return
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
                            copyFromAssetPack(packName, assetsPath)
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

    private fun copyFromAssetPack(packName: String, assetsPath: String) {
        val assetsDir = File(assetsPath)
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
                    binFile.setExecutable(true, false)
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
