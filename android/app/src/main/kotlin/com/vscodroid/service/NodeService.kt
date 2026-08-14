package com.vscodroid.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
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
    private var startupNotice: String? = null

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
        // Scope first, then the process — the same order [shutdown] uses, for the
        // reason it writes down: a coroutine still inside the readiness poll
        // would otherwise get a window in which it can observe a server being
        // killed and report it ready. Nothing observes that today, because this
        // instance dies with the service, but the two teardown paths disagreeing
        // is how the next reader learns the wrong order.
        serviceScope.cancel()
        processManager.stopServer()
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

    /**
     * Whether the server has answered a health check and has not stopped since.
     *
     * The only server-state question this class answers, and the narrowness is
     * deliberate. Two other wrappers stood here — one returning `Process.isAlive`
     * and one forwarding the blocking health probe — and both are gone: see
     * [ProcessManager.isReady] for what separates a process that exists from a
     * server that serves, and why asking the first while meaning the second put a
     * connection-refused page in front of users.
     *
     * Costs no I/O, so it is safe on the main thread. That is the property the
     * removed pair could not offer, and the reason a caller should not have to
     * choose between three names to find it.
     */
    fun isServerReady(): Boolean = processManager.isReady()

    /**
     * The most recent thing worth saying about the start, or null if there is
     * nothing.
     *
     * A notice rather than a failure, which the name and the strings both have
     * to keep saying. Two of the three messages that reach here are terminal —
     * a start that could not spawn, and a restart budget spent — and the third
     * is `status_server_slow_start`, said while the server is still being waited
     * for and may still come up. Reading them all as failures is what makes a
     * slow start indistinguishable from a dead one to everything downstream,
     * which is the thing this whole path exists to avoid.
     *
     * Exists because [onServerError] is a callback and a callback can be raised
     * at a moment when nobody is listening. A start that outlives the activity
     * that began it reports into a null field and is gone; the next activity to
     * bind then finds a service that is not ready and waits, with nothing said.
     *
     * Read on the main thread by a newly bound client and written on the main
     * thread by [launchServer], [awaitLateReadiness] and [enterTerminalState],
     * so it needs no synchronisation of its own — the same confinement
     * [restartCount] and [launchJob] rely on.
     *
     * Cleared whenever a start begins and whenever one succeeds, so a reader
     * that finds something here is looking at the current attempt.
     */
    fun lastStartupNotice(): String? = startupNotice

    /**
     * Posts the foreground notification again.
     *
     * There is exactly one reason to need this, and it is not a refresh of stale
     * content. `MainActivity.onCreate` asks for `POST_NOTIFICATIONS` and starts
     * this service on the next line, and the ask is a launcher call that returns
     * immediately — so `startForeground` below runs while the permission dialog
     * is still on screen and the answer is still "no". On Android 13+ the system
     * accepts the foreground promotion and drops the notification: the service
     * record ends up holding a notification that was never enqueued, which is
     * measurable as `isForeground=true` with no `NotificationRecord` anywhere.
     * Granting the permission afterwards posts nothing, because posting already
     * happened.
     *
     * Measured on an API 36 emulator: with the permission granted before launch
     * the record exists and the card is in the shade; with it granted from the
     * dialog mid-launch, neither. The only difference between the two runs is
     * when the answer arrived.
     *
     * The consequence was not a missing card. It was that the Stop action lives
     * on that card, so on a fresh install there was no way to stop the server at
     * all.
     *
     * Posting with the same id is an update rather than a second notification.
     * Refused unless this service is already in the foreground: promoting from
     * the background is what Android 12+ throws over, and in the terminal state
     * the running notification would be a false statement.
     *
     * One case is deliberately left uncovered, so that it is not solved twice.
     * A user who denies the dialog and grants the permission later from Settings
     * gets no card until the next cold start — the launcher callback that leads
     * here fires once, for the dialog. That case repairs itself: by the next
     * start the permission is already granted when `startForeground` runs, which
     * is the state this whole method exists to compensate for the absence of.
     * Android broadcasts nothing when a permission changes, so covering it would
     * mean re-checking on every `onStart` — a permanent cost for something that
     * is already temporary.
     */
    fun refreshNotification() {
        if (!isServiceRunning) {
            Logger.i(tag, "Not foreground; nothing to re-post")
            return
        }
        Logger.i(tag, "Posting the foreground notification again")
        ServiceCompat.startForeground(
            this,
            VSCodroidApp.NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

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
     *
     * **Precondition: the server process must not be alive when this is called.**
     * Not a style preference — it is what holds up a sentence shown to users.
     * [ProcessManager.startServer] answers `false` for a process that is already
     * running, so re-entering here cancels [awaitLateReadiness] through
     * `launchJob?.cancel()`, clears the notice, takes the `!started` branch and
     * returns. That leaves a live process with nothing polling it, and
     * `status_server_slow_start` has already promised the reader that the editor
     * will open as soon as the server is ready. Nothing would be asking.
     *
     * Every route in is closed today, but by bookkeeping that belongs to a
     * different concern and does not know it is load-bearing here:
     * [onStartCommand] guards on [isServiceRunning], [handleServerCrash] returns
     * early and reaches this only with the process dead, and [shutdown] and
     * [onDestroy] both kill it first. A "restart the server" action added later
     * that calls this directly would satisfy none of them, break nothing that
     * compiles or tests, and make that sentence false.
     *
     * So: stop the process before calling this, or add the guard here rather
     * than relying on the caller having read this paragraph.
     */
    private fun launchServer() {
        // A crash during startup restarts the server while the previous attempt is
        // still inside waitForReady()'s 30s poll. Without this the two overlap and
        // both report readiness for the same server.
        launchJob?.cancel()
        // A fresh attempt is not a failed one. Cleared here rather than on
        // success, so that the window in which a client can bind and read a
        // stale verdict from the previous attempt does not exist.
        startupNotice = null
        launchJob = serviceScope.launch {
            val started = withContext(Dispatchers.IO) { processManager.startServer() }
            if (!started) {
                Logger.e(tag, "Failed to start server process")
                reportStartupNotice(getString(R.string.error_server_start))
                return@launch
            }

            val ready = withContext(Dispatchers.IO) { processManager.waitForReady() }
            if (ready) {
                announceReady()
                return@launch
            }

            // The poll is bounded; the server is not obliged to agree. A process
            // that is still alive here has not failed at anything -- it is slow,
            // and this project's own device harness budgets 120 seconds for the
            // same event that this gives up on at 30 (scripts/device-test.sh,
            // TIMEOUT=120). Reporting a failure for it would be a false alarm,
            // and, worse, stopping here used to make it permanent: nothing else
            // in the app ever probes again, so a server that bound its port at
            // t=35s stayed unreachable for as long as it ran.
            if (!processManager.isRunning()) {
                Logger.e(tag, "Server timeout and the process is gone")
                reportStartupNotice(getString(R.string.error_server_timeout))
                return@launch
            }

            Logger.w(tag, "Server has not answered yet; still watching a live process")
            awaitLateReadiness()
        }
    }

    /** Records a ready server and tells whoever is listening. */
    private fun announceReady() {
        // Recovery succeeded; future crashes should get a fresh retry budget.
        restartCount = 0
        startupNotice = null
        Logger.i(tag, "Server is ready on port ${processManager.port}")
        onServerReady?.invoke(processManager.port)
    }

    /**
     * Keeps asking after the start poll has given up.
     *
     * The bound on [ProcessManager.waitForReady] is patience, not a verdict, and
     * separating the two is the whole of this function. Without it the app had a
     * cliff: a start slower than the poll left `isReady()` false permanently,
     * both call sites in `MainActivity` refused, `onServerReady` could never fire
     * because the coroutine that fires it had already returned, and the only ways
     * out were a crash or a kill. A server that was running and serving could not
     * be reached.
     *
     * Bounded by liveness rather than by time, which is the honest bound: while
     * the process is alive the answer can still change, and when it dies the
     * watchdog takes over -- `onServerCrashed` drives the restart, so this loop
     * leaves quietly rather than reporting a failure the crash path is about to
     * report properly.
     *
     * Separating patience from a verdict is only half of it, though, and the
     * first version of this stopped there. A process that stays alive and never
     * answers then produced *no* verdict at all: nothing raised, nothing recorded
     * for the next activity to read, nothing on the notification, and a user in
     * front of a placeholder that is indistinguishable from still-loading. That
     * traded a wrong answer for no answer, and no answer is the harder one to
     * act on. So the verdict is separated from the *informing* as well: the loop
     * never gives up, and at [LATE_READY_NOTICE_MS] it says so once.
     *
     * That state is not hypothetical in kind. [ProcessManager.isServerHealthy]
     * accepts only 200 from `/version`, and its own documentation records why it
     * is that route: `/` began answering 403 once the server required a
     * connection token, which made every probe against it wrong while the server
     * was serving perfectly. The same change one endpoint over leaves a process
     * that is alive, bound and serving while [ProcessManager.probeReadiness]
     * answers false forever.
     *
     * The interval widens rather than the loop ending, which bounds the cost
     * without bounding the time: two seconds while an answer is still plausible,
     * a slow heartbeat after that. Waiting is free; asking is not.
     */
    private suspend fun awaitLateReadiness() {
        val startedAt = SystemClock.elapsedRealtime()
        var noticed = false

        while (processManager.isRunning()) {
            delay(lateReadinessPollMs(SystemClock.elapsedRealtime() - startedAt))

            val ready = withContext(Dispatchers.IO) { processManager.probeReadiness() }
            if (ready) {
                Logger.i(tag, "Server answered after the start poll had given up")
                announceReady()
                return
            }

            if (!noticed && SystemClock.elapsedRealtime() - startedAt >= LATE_READY_NOTICE_MS) {
                noticed = true
                Logger.w(tag, "Server still has not answered; saying so while continuing to ask")
                // Not error_server_timeout, which is what the branch above says
                // when the process is gone. This one is said while the server is
                // still being waited for, so it has to read as a status rather
                // than an ending -- and a message that reads as an ending is how
                // the whole of this loop stops meaning anything to the person
                // reading it.
                reportStartupNotice(getString(R.string.status_server_slow_start))
            }
        }
        Logger.w(tag, "Server process exited before it ever answered")
    }

    /**
     * Says something about the start, now and later.
     *
     * Both halves matter and only the first used to happen. The callback reaches
     * an activity that is bound *now*; the field reaches the next one to bind,
     * which on a start that outlives its activity is the only one there is. See
     * [lastStartupNotice] for why neither is called a failure.
     */
    private fun reportStartupNotice(message: String) {
        startupNotice = message
        onServerError?.invoke(message)
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
        // Recorded as well as raised: this is the terminal state, so an activity
        // that binds after it has to be told too. The notification says the same
        // thing, but the notification is not on screen while the editor is.
        reportStartupNotice(stoppedText)
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
            // A stated intent, not a fix for anything measured here. Android 12+
            // is documented to hold a foreground-service notification back for
            // about ten seconds when its channel sits below IMPORTANCE_DEFAULT,
            // which this one does at IMPORTANCE_LOW — but the delay did not
            // reproduce: on an API 36 emulator the NotificationRecord was already
            // present three seconds after launch both with this call and without
            // it. Whether the emulator does not apply the deferral, or the
            // deferral needs conditions that run did not create, is not known.
            //
            // Kept because it is the documented way to say "show this now" and
            // costs nothing, and because a server the user cannot see is also a
            // server the user cannot stop — the Stop action lives on this card.
            // Do not write it up as having fixed a delay; nobody has seen one.
            //
            // Not in tension with setSilent above: that governs whether it makes
            // a sound, this governs whether it waits.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

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
 * How often a server is asked again once the start poll has given up on it.
 *
 * Slower than the start poll's 200 ms because the urgency has passed and the
 * process may sit here for as long as it lives; fast enough that a server which
 * binds late is found within a couple of seconds of binding rather than at the
 * next thing that happens to ask.
 */
internal const val LATE_READY_POLL_MS = 2_000L

/**
 * How often it is asked once even a late answer has stopped being likely.
 *
 * The loop still never ends — a server can answer at any point while its process
 * lives — but by this stage the cost of asking matters more than the seconds
 * between an answer and noticing it. Two seconds forever is a probe every two
 * seconds for as long as a wedged process is alive, on a battery.
 */
internal const val LATE_READY_SLOW_POLL_MS = 30_000L

/**
 * How long the late poll runs before the app says the start is taking too long.
 *
 * Ninety seconds here, on top of the thirty the start poll has already spent:
 * two minutes in total, which is what `scripts/device-test.sh` budgets for this
 * same event (`TIMEOUT=120`). The app had been disagreeing with its own harness
 * by a factor of four. If `ProcessManager.waitForReady`'s default timeout moves,
 * this is the other half of that sum.
 *
 * It is a moment to speak, not a moment to stop. The message is raised and
 * recorded once and the loop carries on, so a server that answers afterwards
 * still opens the editor.
 */
internal const val LATE_READY_NOTICE_MS = 90_000L

/**
 * How long to wait before the next probe, given how long the late poll has run.
 *
 * A step rather than a curve, because the only thing it has to get right is that
 * the interval never reaches zero — the loop has no other brake, so an interval
 * of zero is a spin on the IO dispatcher for as long as a wedged process lives.
 */
internal fun lateReadinessPollMs(elapsedMs: Long): Long =
    if (elapsedMs < LATE_READY_NOTICE_MS) LATE_READY_POLL_MS else LATE_READY_SLOW_POLL_MS

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
