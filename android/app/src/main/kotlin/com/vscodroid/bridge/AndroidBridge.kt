package com.vscodroid.bridge

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.browser.customtabs.CustomTabsIntent
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import org.json.JSONArray
import org.json.JSONObject

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
            // Use Chrome Custom Tabs for https URLs — keeps the user in-app,
            // loads faster than an external browser, and handles OAuth redirects
            // back to the app via deep links.
            if (uri.scheme == "https") {
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

    private fun getVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
