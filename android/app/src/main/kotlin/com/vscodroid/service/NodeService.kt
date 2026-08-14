package com.vscodroid.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vscodroid.R
import com.vscodroid.VSCodroidApp
import com.vscodroid.MainActivity
import com.vscodroid.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service that owns the Node.js server process.
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
 *
 * Threading: [restartCount], [isServiceRunning] and [launchJob] are touched only
 * from [serviceScope]'s dispatcher, which is the main thread. Service lifecycle
 * callbacks already arrive there; the one caller that does not is the watchdog,
 * and [setupProcessCallbacks] hands its report to the scope rather than acting on
 * the watchdog thread.
 */
class NodeService : Service() {

    private val tag = "NodeService"
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var processManager: ProcessManager
    private var restartCount = 0
    private var isServiceRunning = false
    private var launchJob: Job? = null

    /** Invoked when the server is healthy and accepting connections. */
    var onServerReady: ((port: Int) -> Unit)? = null

    /** Invoked when the server fails to start or exceeds restart attempts. */
    var onServerError: ((message: String) -> Unit)? = null

    /**
     * Invoked when the user stops the server from the notification.
     *
     * A bound activity is showing an editor whose server is going away, so it is
     * told rather than left on a dead page — and until it acts on this, the
     * binding it holds is what keeps the service from being destroyed at all.
     * See [shutdown].
     */
    var onServerStopped: (() -> Unit)? = null

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
                shutdown()
                return START_NOT_STICKY
            }
        }

        if (!isServiceRunning) {
            ServiceCompat.startForeground(
                this,
                VSCodroidApp.NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
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

    /**
     * The token the server requires on every route except `/version`,
     * `/delay-shutdown` and `/callback`, or null
     * before the server has written it.
     */
    fun getConnectionToken(): String? = processManager.connectionToken

    /** Performs a synchronous health check against the running server. */
    fun isServerHealthy(): Boolean = processManager.isServerHealthy()

    /** Returns `true` if the Node.js process is alive. */
    fun isServerRunning(): Boolean = processManager.isRunning()

    // -- Internal --

    /**
     * Stops the server and this service, in response to the notification's Stop
     * action.
     *
     * Every effect below used to be left to [onDestroy], and that is why Stop did
     * nothing. This service is started *and* bound: `MainActivity` binds with
     * `BIND_AUTO_CREATE` in `startAndBindService()` and unbinds only in its own
     * `onDestroy`. Android does not destroy a started, bound service on
     * `stopSelf()` alone — it waits for the last client to unbind as well. So
     * with the editor open, or merely backgrounded, pressing Stop stopped
     * nothing, removed no notification, and said nothing about it.
     *
     * Cancelling the launch coroutine first matters: a start still inside
     * `waitForReady()`'s thirty-second poll would otherwise go on to report
     * readiness for a server that is being killed, and drive the activity to load
     * a workbench that is not there.
     *
     * [ProcessManager.stopServer] is idempotent — it clears its own process
     * reference — so the call [onDestroy] makes once the activity unbinds costs a
     * log line and nothing else. That second stop is not merely tolerated: it is
     * what reaps a process spawned inside the window below.
     *
     * The client is told last. Everything here runs on the main thread, and
     * `MainActivity` answers this callback with `runOnUiThread`, which on the
     * main thread runs inline — so the notification is taken down and the service
     * is stopped before anything the activity does can re-enter.
     */
    private fun shutdown() {
        Logger.i(tag, "Stop requested from the notification")
        isServiceRunning = false
        launchJob?.cancel()
        launchJob = null
        processManager.stopServer()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        onServerStopped?.invoke()
    }

    /**
     * Launches the server and waits for it to become ready.
     * Notifies [onServerReady] on success or [onServerError] on failure/timeout.
     *
     * The coroutine body runs on [serviceScope]'s main dispatcher and steps out to
     * IO only for the two calls that block. That is what keeps [restartCount] and
     * [launchJob] on a single thread; see [setupProcessCallbacks] for why they
     * need to be.
     */
    private fun launchServer() {
        // A crash during startup restarts the server while the previous attempt is
        // still inside waitForReady()'s 30s poll. Without this the two overlap and
        // both report readiness for the same server.
        launchJob?.cancel()
        launchJob = serviceScope.launch {
            val started = withContext(Dispatchers.IO) { processManager.startServer() }
            if (!started) {
                Logger.e(tag, "Failed to start server process")
                onServerError?.invoke(getString(R.string.error_server_start))
                return@launch
            }

            val ready = withContext(Dispatchers.IO) { processManager.waitForReady() }
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
     * Routes [ProcessManager.onServerCrashed] into [handleServerCrash].
     *
     * The hop onto [serviceScope] is the point. That callback fires on the
     * watchdog thread, and what it reaches for — [restartCount] and [launchJob] —
     * is also read and written by [launchServer]'s coroutine. Neither field was
     * synchronised, so the watchdog could raise the count while the coroutine
     * reset it, and `launchJob?.cancel()` followed by an assignment is a compound
     * action two threads can interleave into an orphaned coroutine that is still
     * polling for readiness on a server nobody is waiting for.
     *
     * `@Volatile` would not have fixed that second one: it makes each access
     * visible, not the pair atomic. Confining both fields to one dispatcher does,
     * and it needs no additional state to say so.
     *
     * A cancelled scope drops this silently, which is exactly what should happen
     * after [onDestroy].
     */
    private fun setupProcessCallbacks() {
        processManager.onServerCrashed = { exitCode ->
            serviceScope.launch { handleServerCrash(exitCode) }
        }
    }

    /**
     * Decides what a crashed server gets: another attempt after a growing pause,
     * or the terminal state.
     */
    private suspend fun handleServerCrash(exitCode: Int) {
        // A report that arrives after the service has stopped describes a process
        // the user asked to be rid of. The watchdog suppresses the expected exit
        // itself, but a crash already on its way to this scope when Stop was
        // pressed still lands here — and reviving it, or rewriting a notification
        // that has just been taken down, would both be wrong.
        if (!isServiceRunning) {
            Logger.i(tag, "Server exit ($exitCode) after the service stopped; nothing to restart")
            return
        }

        Logger.w(tag, "Server crashed (exit=$exitCode), restart #${restartCount + 1}")
        if (!hasRestartBudget(restartCount)) {
            enterTerminalState()
            return
        }

        restartCount++
        delay(restartBackoffMs(restartCount))
        // Checked again on the far side of the pause, which is measured in
        // seconds and grows: Stop can land while it elapses, and restarting the
        // server the user just stopped is the one outcome nothing downstream
        // would explain.
        if (!isServiceRunning) return
        launchServer()
    }

    /**
     * Records, where the user can actually see it, that the server is not coming
     * back.
     *
     * This branch used to log and raise [onServerError], which is null until an
     * activity binds and a Toast afterwards — so on a headless crash loop it
     * reached nobody, and the notification went on reading "VSCodroid is running"
     * over a server that had stopped five attempts ago.
     *
     * The notification is rewritten through `startForeground` rather than posted
     * again, because updating the foreground notification needs no notification
     * permission of its own; a separate `notify()` on API 33+ does. It is then
     * detached rather than removed: it is the only place this state is visible
     * once the activity is gone, so it has to outlive the foreground status
     * instead of disappearing with it.
     *
     * Clearing [isServiceRunning] is what makes the app recoverable. The flag
     * otherwise stays true for the life of the process, and this service outlives
     * the activity — so relaunching would bind to a service that believes it is
     * already running, start nothing, and leave the editor waiting for a
     * readiness callback that can no longer fire.
     *
     * The service deliberately does not stop itself here. Stopping is not what
     * makes the state visible, and staying alive is what lets the next
     * `onStartCommand` recover in place, with the port and the connection token
     * that the workbench's IndexedDB is already bound to.
     */
    private fun enterTerminalState() {
        Logger.e(tag, "Max restarts exceeded ($MAX_RESTARTS)")
        isServiceRunning = false
        launchJob = null
        val stoppedText = getString(R.string.notification_text_stopped)
        ServiceCompat.startForeground(
            this,
            VSCodroidApp.NOTIFICATION_ID,
            createNotification(
                getString(R.string.notification_title_stopped),
                stoppedText,
                serverRunning = false,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        onServerError?.invoke(stoppedText)
    }

    /**
     * Builds the notification shown while the server is running, and — with
     * [serverRunning] cleared and its own wording — the one left behind after it
     * has stopped for good.
     *
     * Always tapping through to [MainActivity]. The Stop action, which sends
     * [ACTION_STOP] here, appears only while there is a server to stop, and both
     * of the things [serverRunning] governs follow from that same fact rather
     * than happening to agree: an ongoing notification is one the user cannot
     * dismiss, which is only fair while something is still running behind it.
     *
     * Withholding the action is also what keeps the terminal notification safe to
     * outlive this service. It is a `PendingIntent.getService`, and sending one
     * at a service that is gone is a background service start — from a
     * notification that, being detached, can still be on screen after the process
     * it belonged to has been reclaimed.
     */
    private fun createNotification(
        title: String = getString(R.string.notification_title),
        text: String = getString(R.string.notification_text),
        serverRunning: Boolean = true,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, VSCodroidApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingOpen)
            .setOngoing(serverRunning)
            .setSilent(true)

        if (serverRunning) {
            val stopIntent = Intent(this, NodeService::class.java).apply {
                action = ACTION_STOP
            }
            val pendingStop = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.action_stop), pendingStop)
        }

        return builder.build()
    }

    companion object {
        /** Intent action to gracefully stop the server and this service. */
        const val ACTION_STOP = "com.vscodroid.action.STOP_SERVER"
    }
}

/** Maximum number of automatic restart attempts before giving up. */
internal const val MAX_RESTARTS = 5

/** Delay before the first restart attempt. Each later attempt doubles it. */
internal const val RESTART_DELAY_MS = 2000L

/** Cap on the doubling, so the wait cannot grow without bound. */
internal const val MAX_BACKOFF_SHIFT = 4

/**
 * Whether a server that has already crashed [restartCount] times gets another
 * attempt.
 *
 * The boundary is the whole content: [MAX_RESTARTS] is a count of attempts, so
 * the fifth is granted and the sixth is not. Written as `<=` it silently becomes
 * six, and the only place that shows is a crash loop lasting a minute longer than
 * anyone intended.
 */
internal fun hasRestartBudget(restartCount: Int, maxRestarts: Int = MAX_RESTARTS): Boolean =
    restartCount < maxRestarts

/**
 * How long to wait before restart number [attempt], counting from one.
 *
 * Doubling, with the shift held inside a band rather than the result. Kotlin
 * masks a shift distance to the low six bits, so an unbounded `1L shl (n - 1)`
 * wraps back to `1 shl 0` at attempt 65 and retries instantly — the opposite of
 * a backoff, at the point where backing off matters most. The lower bound
 * matters for the same reason from the other end: a zero attempt would shift by
 * -1, which masks to 63 and produces a negative delay.
 *
 * Neither bound is reachable through the service today, because
 * [hasRestartBudget] gates the count first. They are here because a function is
 * the thing that gets reused, and a total one cannot be reused wrongly.
 */
internal fun restartBackoffMs(attempt: Int): Long =
    RESTART_DELAY_MS * (1L shl (attempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT))
