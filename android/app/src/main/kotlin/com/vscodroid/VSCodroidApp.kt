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
        WebView(this).destroy()

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
