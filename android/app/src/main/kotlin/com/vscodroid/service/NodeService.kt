package com.vscodroid.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vscodroid.R
import com.vscodroid.VSCodroidApp
import com.vscodroid.MainActivity
import com.vscodroid.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground Service that owns the Node.js code-server process.
 *
 * Responsibilities:
 * - Promoting itself to a foreground service with a persistent notification
 *   (specialUse FGS type for local dev server)
 * - Delegating process lifecycle to [ProcessManager]
 * - Automatically restarting the server on unexpected crashes (up to [MAX_RESTARTS])
 * - Exposing server state to bound clients (typically [MainActivity])
 *
 * Binding pattern: Activities bind to this service to receive the port number
 * and server readiness callbacks. The service remains alive independently of
 * any bound clients because it is started as a foreground service.
 */
class NodeService : Service() {

    private val tag = "NodeService"
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var processManager: ProcessManager
    private var restartCount = 0
    private var isServiceRunning = false

    /** Invoked when the server is healthy and accepting connections. */
    var onServerReady: ((port: Int) -> Unit)? = null

    /** Invoked when the server fails to start or exceeds restart attempts. */
    var onServerError: ((message: String) -> Unit)? = null

    // -- Binder --

    inner class LocalBinder : Binder() {
        fun getService(): NodeService = this@NodeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // -- Service Lifecycle --

    override fun onCreate() {
        super.onCreate()
        Logger.i(tag, "Service created")
        processManager = ProcessManager(this)
        setupProcessCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!isServiceRunning) {
            startForeground(VSCodroidApp.NOTIFICATION_ID, createNotification())
            isServiceRunning = true
            launchServer()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Logger.i(tag, "Service destroying")
        isServiceRunning = false
        processManager.stopServer()
        serviceScope.cancel()
        super.onDestroy()
    }

    // -- Public API for bound clients --

    /** Returns the port the server is listening on, or 0 if not yet started. */
    fun getPort(): Int = processManager.port

    /** Performs a synchronous health check against the running server. */
    fun isServerHealthy(): Boolean = processManager.isServerHealthy()

    /** Returns `true` if the Node.js process is alive. */
    fun isServerRunning(): Boolean = processManager.isRunning()

    // -- Internal --

    /**
     * Launches the server on the IO dispatcher and waits for it to become ready.
     * Notifies [onServerReady] on success or [onServerError] on failure/timeout.
     */
    private fun launchServer() {
        serviceScope.launch(Dispatchers.IO) {
            val started = processManager.startServer()
            if (!started) {
                Logger.e(tag, "Failed to start server process")
                onServerError?.invoke(getString(R.string.error_server_start))
                return@launch
            }

            val ready = processManager.waitForReady()
            if (ready) {
                // Recovery succeeded; future crashes should get a fresh retry budget.
                restartCount = 0
                Logger.i(tag, "Server is ready on port ${processManager.port}")
                onServerReady?.invoke(processManager.port)
            } else {
                Logger.e(tag, "Server timeout")
                onServerError?.invoke(getString(R.string.error_server_timeout))
            }
        }
    }

    /**
     * Wires up the [ProcessManager.onServerCrashed] callback to trigger automatic
     * restarts with a backoff delay, up to [MAX_RESTARTS] attempts.
     */
    private fun setupProcessCallbacks() {
        processManager.onServerCrashed = { exitCode ->
            Logger.w(tag, "Server crashed (exit=$exitCode), restart #${restartCount + 1}")
            if (isServiceRunning && restartCount < MAX_RESTARTS) {
                restartCount++
                serviceScope.launch(Dispatchers.IO) {
                    val backoffShift = (restartCount - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
                    val delayMs = RESTART_DELAY_MS * (1L shl backoffShift)
                    delay(delayMs)
                    launchServer()
                }
            } else {
                Logger.e(tag, "Max restarts exceeded ($MAX_RESTARTS)")
                onServerError?.invoke("Server crashed repeatedly. Please restart the app.")
            }
        }
    }

    /**
     * Builds the persistent foreground notification shown while the server is running.
     *
     * Includes:
     * - Tap action: opens [MainActivity]
     * - Stop action: sends [ACTION_STOP] to this service
     */
    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NodeService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, VSCodroidApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingOpen)
            .addAction(0, getString(R.string.action_stop), pendingStop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        /** Intent action to gracefully stop the server and this service. */
        const val ACTION_STOP = "com.vscodroid.action.STOP_SERVER"

        /** Maximum number of automatic restart attempts before giving up. */
        private const val MAX_RESTARTS = 5

        /** Delay in milliseconds before each restart attempt. */
        private const val RESTART_DELAY_MS = 2000L

        /** Cap backoff growth to avoid unbounded delays. */
        private const val MAX_BACKOFF_SHIFT = 4
    }
}
