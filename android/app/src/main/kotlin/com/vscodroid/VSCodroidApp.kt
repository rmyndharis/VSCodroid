package com.vscodroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.webkit.WebView
import com.vscodroid.util.Logger

class VSCodroidApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WebView.setDataDirectorySuffix("vscodroid")
        createNotificationChannel()
        Logger.init(this)
        Logger.i("VSCodroidApp", "Application created")
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
