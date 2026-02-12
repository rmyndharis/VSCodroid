package com.vscodroid.bridge

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.browser.customtabs.CustomTabsIntent
import com.vscodroid.setup.ToolchainManager
import com.vscodroid.setup.ToolchainRegistry
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AndroidBridge(
    private val context: Context,
    private val security: SecurityManager,
    private val clipboard: ClipboardBridge,
    private val onBackPressed: () -> Boolean,
    private val onMinimize: () -> Unit,
    private val onOpenFolderPicker: () -> Unit = {},
    private val onOpenRecentFolder: (Uri) -> Unit = {},
    private val safManager: SafStorageManager? = null
) {
    private val tag = "AndroidBridge"

    @JavascriptInterface
    fun copyToClipboard(text: String): Boolean {
        return clipboard.copyToClipboard(text)
    }

    @JavascriptInterface
    fun readFromClipboard(authToken: String): String? {
        if (!security.validateToken(authToken)) return null
        return clipboard.readFromClipboard()
    }

    @JavascriptInterface
    fun hasClipboardText(): Boolean {
        return clipboard.hasClipboardText()
    }

    @JavascriptInterface
    fun openExternalUrl(url: String, authToken: String) {
        if (!security.validateToken(authToken)) return
        if (!security.isAllowedUrl(url)) return
        try {
            val uri = Uri.parse(url)
            val isLocalhost = uri.host == "127.0.0.1" || uri.host == "localhost"
            // Use system browser for localhost URLs (dev server preview needs full browser),
            // Chrome Custom Tabs for https (keeps user in-app, handles OAuth redirects).
            if (uri.scheme == "https" && !isLocalhost) {
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                customTabsIntent.launchUrl(context, uri)
            } else {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Logger.e(tag, "Failed to open URL: $url", e)
        }
    }

    @JavascriptInterface
    fun onBackPressed(): Boolean {
        return onBackPressed.invoke()
    }

    @JavascriptInterface
    fun minimizeApp() {
        onMinimize()
    }

    @JavascriptInterface
    fun getDeviceInfo(authToken: String): String {
        if (!security.validateToken(authToken)) return "{}"
        val displayMetrics = context.resources.displayMetrics
        return JSONObject().apply {
            put("model", Build.MODEL)
            put("android", Build.VERSION.SDK_INT)
            put("api", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER)
            put("vscodroid_version", getVersionName())
            put("screen_width", displayMetrics.widthPixels)
            put("screen_height", displayMetrics.heightPixels)
            put("screen_density", displayMetrics.density)
            put("orientation", if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
        }.toString()
    }

    @JavascriptInterface
    fun getThemeMode(): String {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }

    @JavascriptInterface
    fun logToNative(level: String, tag: String, message: String) {
        when (level) {
            "debug" -> Logger.d(tag, message)
            "info" -> Logger.i(tag, message)
            "warn" -> Logger.w(tag, message)
            "error" -> Logger.e(tag, message)
            else -> Logger.d(tag, message)
        }
    }

    // -- SAF (Storage Access Framework) --

    /**
     * Opens the Android SAF folder picker to select any folder on the device.
     * The result is handled by MainActivity and triggers a folder sync + reload.
     */
    @JavascriptInterface
    fun openFolderPicker(authToken: String) {
        if (!security.validateToken(authToken)) return
        Logger.i(tag, "Opening SAF folder picker")
        onOpenFolderPicker()
    }

    /**
     * Returns a JSON array of recently opened SAF folders.
     * Each entry has: uri, name, lastOpened.
     */
    @JavascriptInterface
    fun getRecentFolders(authToken: String): String {
        if (!security.validateToken(authToken)) return "[]"
        val manager = safManager ?: return "[]"
        val folders = manager.getPersistedFolders()
        return JSONArray().apply {
            folders.forEach { f ->
                put(JSONObject().apply {
                    put("uri", f.uri.toString())
                    put("name", f.displayName)
                    put("lastOpened", f.lastOpened)
                })
            }
        }.toString()
    }

    /**
     * Opens a previously selected SAF folder by its URI string.
     */
    @JavascriptInterface
    fun openRecentFolder(authToken: String, uriString: String) {
        if (!security.validateToken(authToken)) return
        val uri = Uri.parse(uriString)
        Logger.i(tag, "Opening recent SAF folder: $uri")
        onOpenRecentFolder(uri)
    }

    // -- Storage Management --

    /**
     * Returns per-component storage breakdown as JSON.
     * Keys: vscode_server, extensions, user_data, logs, tools, saf_mirrors, cache, total
     * Values in bytes.
     */
    @JavascriptInterface
    fun getStorageBreakdown(authToken: String): String {
        if (!security.validateToken(authToken)) return "{}"
        return StorageManager.getStorageBreakdown(context).toString()
    }

    /**
     * Clears caches (npm, tmp, crash logs, VS Code logs). Returns bytes freed.
     */
    @JavascriptInterface
    fun clearCaches(authToken: String): Long {
        if (!security.validateToken(authToken)) return 0
        return StorageManager.clearCaches(context)
    }

    /**
     * Returns available storage in bytes.
     */
    @JavascriptInterface
    fun getAvailableStorage(authToken: String): Long {
        if (!security.validateToken(authToken)) return 0
        return StorageManager.getAvailableStorage(context)
    }

    // -- Crash Reporting --

    /**
     * Returns the last crash log text, or null if no crashes recorded.
     */
    @JavascriptInterface
    fun getLastCrash(authToken: String): String? {
        if (!security.validateToken(authToken)) return null
        return CrashReporter.getLastCrash()
    }

    /**
     * Generates a full bug report (device info + crash logs + server logs).
     */
    @JavascriptInterface
    fun generateBugReport(authToken: String): String {
        if (!security.validateToken(authToken)) return ""
        return CrashReporter.generateBugReport(context)
    }

    /**
     * Clears stored crash logs.
     */
    @JavascriptInterface
    fun clearCrashLogs(authToken: String) {
        if (!security.validateToken(authToken)) return
        CrashReporter.clearCrashLogs()
    }

    // -- GitHub OAuth --

    /**
     * Opens a GitHub OAuth URL via Chrome Custom Tabs.
     * The callback deep link (vscodroid://oauth/github?code=...&state=...)
     * is handled by MainActivity and forwarded to VS Code via JS callback.
     */
    @JavascriptInterface
    fun startGitHubAuth(authUrl: String, authToken: String) {
        if (!security.validateToken(authToken)) return
        try {
            val uri = Uri.parse(authUrl)
            // Only allow GitHub OAuth URLs to prevent misuse
            if (uri.scheme != "https" || uri.host != "github.com") {
                Logger.w(tag, "Blocked non-GitHub OAuth URL: ${uri.host}")
                return
            }
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
            Logger.i(tag, "GitHub OAuth started")
        } catch (e: Exception) {
            Logger.e(tag, "Failed to start GitHub OAuth", e)
        }
    }

    // -- Toolchain Settings --

    /**
     * Opens the ToolchainActivity settings screen for managing toolchains.
     */
    @JavascriptInterface
    fun openToolchainSettings(authToken: String) {
        if (!security.validateToken(authToken)) return
        Logger.i(tag, "Opening toolchain settings")
        val intent = Intent(context, com.vscodroid.ToolchainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // -- Toolchain Management --

    private val toolchainManager by lazy { ToolchainManager(context) }

    /**
     * Returns JSON array of all available toolchains (installed or not).
     * Each entry: { packName, displayName, description, estimatedSize, installed }
     */
    @JavascriptInterface
    fun getAvailableToolchains(authToken: String): String {
        if (!security.validateToken(authToken)) return "[]"
        val installed = toolchainManager.getInstalledToolchains()
        return JSONArray().apply {
            ToolchainRegistry.available.forEach { tc ->
                put(JSONObject().apply {
                    put("packName", tc.packName)
                    put("displayName", tc.displayName)
                    put("description", tc.description)
                    put("estimatedSize", tc.estimatedSize)
                    put("installed", installed.contains(tc.packName.removePrefix("toolchain_")))
                })
            }
        }.toString()
    }

    /**
     * Returns JSON array of installed toolchain names (e.g. ["go", "ruby"]).
     */
    @JavascriptInterface
    fun getInstalledToolchains(authToken: String): String {
        if (!security.validateToken(authToken)) return "[]"
        return JSONArray(toolchainManager.getInstalledToolchains()).toString()
    }

    /**
     * Starts async download + install of a toolchain by pack name or short name.
     */
    @JavascriptInterface
    fun installToolchain(name: String, authToken: String) {
        if (!security.validateToken(authToken)) return
        Logger.i(tag, "JS requested toolchain install: $name")
        toolchainManager.install(name)
    }

    /**
     * Removes a toolchain (deletes files, symlinks, env vars).
     */
    @JavascriptInterface
    fun removeToolchain(name: String, authToken: String) {
        if (!security.validateToken(authToken)) return
        Logger.i(tag, "JS requested toolchain removal: $name")
        toolchainManager.uninstall(name)
    }

    /**
     * Cancels an in-progress toolchain download.
     */
    @JavascriptInterface
    fun cancelToolchainInstall(name: String, authToken: String) {
        if (!security.validateToken(authToken)) return
        toolchainManager.cancel(name)
    }

    // -- SSH Key Management --

    /**
     * Generates an ed25519 SSH key pair in ~/.ssh/.
     * Returns JSON: {success: boolean, publicKey?: string, error?: string}
     *
     * Uses the bundled ssh-keygen binary (libssh-keygen.so) via ProcessBuilder.
     * Empty passphrase for mobile UX — keys are protected by app sandbox.
     */
    @JavascriptInterface
    fun generateSshKey(authToken: String, comment: String): String {
        if (!security.validateToken(authToken)) return """{"success":false,"error":"unauthorized"}"""
        val result = JSONObject()
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val homeDir = "${context.filesDir}/home"
            val sshDir = File(homeDir, ".ssh")
            sshDir.mkdirs()
            val keyFile = File(sshDir, "id_ed25519")

            // Don't overwrite existing key
            if (keyFile.exists()) {
                val pubKey = File("${keyFile.absolutePath}.pub").readText().trim()
                result.put("success", true)
                result.put("publicKey", pubKey)
                result.put("existed", true)
                return result.toString()
            }

            val keyComment = if (comment.isBlank()) "vscodroid@android" else comment

            val process = ProcessBuilder(
                "$nativeLibDir/libssh-keygen.so",
                "-t", "ed25519",
                "-f", keyFile.absolutePath,
                "-N", "",  // empty passphrase
                "-C", keyComment
            ).apply {
                environment()["HOME"] = homeDir
                environment()["LD_LIBRARY_PATH"] = "$nativeLibDir:${context.filesDir}/usr/lib"
                redirectErrorStream(true)
            }.start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0 && keyFile.exists()) {
                // Set correct permissions (600 for private key, 644 for public)
                try {
                    android.system.Os.chmod(keyFile.absolutePath, 384)  // 0600
                    android.system.Os.chmod("${keyFile.absolutePath}.pub", 420)  // 0644
                } catch (e: Exception) {
                    Logger.d(tag, "Failed to chmod SSH key: ${e.message}")
                }

                val pubKey = File("${keyFile.absolutePath}.pub").readText().trim()
                result.put("success", true)
                result.put("publicKey", pubKey)
            } else {
                result.put("success", false)
                result.put("error", output.trim().ifEmpty { "ssh-keygen exited with code $exitCode" })
            }
        } catch (e: Exception) {
            Logger.e(tag, "SSH key generation failed", e)
            result.put("success", false)
            result.put("error", e.message ?: "unknown error")
        }
        return result.toString()
    }

    /**
     * Reads the SSH public key (~/.ssh/id_ed25519.pub).
     * Returns the key contents or empty string if not found.
     */
    @JavascriptInterface
    fun getSshPublicKey(authToken: String): String {
        if (!security.validateToken(authToken)) return ""
        val pubKeyFile = File("${context.filesDir}/home/.ssh/id_ed25519.pub")
        return if (pubKeyFile.exists()) pubKeyFile.readText().trim() else ""
    }

    /**
     * Lists all SSH keys in ~/.ssh/.
     * Returns JSON array of {name, type, fingerprint} for each key pair.
     */
    @JavascriptInterface
    fun listSshKeys(authToken: String): String {
        if (!security.validateToken(authToken)) return "[]"
        val sshDir = File("${context.filesDir}/home/.ssh")
        if (!sshDir.exists()) return "[]"

        val keys = JSONArray()
        val allFiles: Array<File> = sshDir.listFiles() ?: emptyArray()
        val pubFiles = allFiles.filter { f -> f.name.endsWith(".pub") }
        for (pubFile in pubFiles) {
            try {
                val content = pubFile.readText().trim()
                val parts = content.split(" ", limit = 3)
                keys.put(JSONObject().apply {
                    put("name", pubFile.name.removeSuffix(".pub"))
                    put("type", if (parts.isNotEmpty()) parts[0] else "unknown")
                    put("comment", if (parts.size > 2) parts[2] else "")
                })
            } catch (e: Exception) {
                Logger.d(tag, "Failed to read SSH key ${pubFile.name}: ${e.message}")
            }
        }
        return keys.toString()
    }

    private fun getVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
