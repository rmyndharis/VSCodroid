package com.vscodroid.bridge

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
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
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
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

    private fun getVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
