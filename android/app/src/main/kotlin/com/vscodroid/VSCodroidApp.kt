package com.vscodroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.webkit.WebView
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.Logger
import java.io.File

class VSCodroidApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WebView.setDataDirectorySuffix("vscodroid")
        Logger.init(this)
        CrashReporter.init(this)

        // Pre-warm: loading the Chromium library is the slowest part of
        // WebView creation (~200-400ms). Creating and immediately destroying
        // a throwaway WebView triggers the library load so the real WebView
        // in MainActivity starts faster.
        preWarmWebView { WebView(this).destroy() }

        createNotificationChannel()
        Logger.i("VSCodroidApp", "Application created")
    }

    /**
     * Reports memory pressure for the whole process, not for one Activity.
     *
     * MainActivity overrides this too and keeps doing so: it also logs and tells
     * the page, both of which need the Activity. But the server the process
     * monitor watches outlives the Activity (NodeService returns START_STICKY
     * and the manifest declares no `android:stopWithTask`), so with the Activity
     * as the only writer the monitor read nothing from the moment the task was
     * swiped away, which is exactly when the system starts reclaiming from us.
     * Application is a ComponentCallbacks2 for the whole process lifetime, so
     * this fires whether or not an Activity exists.
     *
     * The two writers overlap harmlessly: same path, same word, and
     * `readMemoryPressure` in process-monitor.js unlinks the file as it reads.
     *
     * Nothing here branches on the level. [applyMemoryPressure] maps it, and a
     * comparison in place of that map is what once killed every idle language
     * server on every app switch.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        applyMemoryPressure(File(cacheDir, "tmp"), level)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "vscodroid_server"
        const val NOTIFICATION_ID = 1
    }
}

/**
 * Runs the WebView pre-warm, and survives a device that cannot give us one.
 *
 * Constructing a `WebView` throws when no WebView provider is installed, enabled
 * or resolvable: `WebViewFactory` raises `MissingWebViewPackageException` wrapped
 * in an `AndroidRuntimeException`, and a provider whose native library will not
 * load raises an `Error` rather than an exception. The common case is not a
 * broken device at all, it is a transient one: Play swapping the Android System
 * WebView package while the app is launched.
 *
 * Unguarded, that killed the process in `Application.onCreate`, which is the
 * earliest and least recoverable point there is. Nothing had run yet, so a
 * launch during a provider swap cost the user first-run setup, the symlink
 * rebuild every reinstall depends on, the toolchain repairs and the reclaim of
 * revoked SAF mirrors, none of which need a WebView to succeed. The saving this
 * is here for is 200-400 ms of Chromium load, which is not a price worth paying
 * with the whole launch.
 *
 * It does NOT promise the app will work without a WebView provider: `MainActivity`
 * inflates a real one, and if the provider is still missing by then that inflation
 * throws where an Activity exists to be blamed for it. What it buys is that a
 * provider which is missing for a moment is not fatal, and that everything which
 * does not need Chromium has already run by the time anything asks for it.
 *
 * `Throwable` rather than `Exception`, for the reason above: the failure arrives
 * as an `Error` on the path where the provider is present and its library will
 * not load, and this whole statement is an optimisation. There is nothing it can
 * fail at that is worth ending the process for.
 */
internal fun preWarmWebView(warm: () -> Unit) {
    try {
        warm()
    } catch (t: Throwable) {
        Logger.w("VSCodroidApp", "WebView pre-warm failed; continuing without it: ${t.message}")
    }
}
