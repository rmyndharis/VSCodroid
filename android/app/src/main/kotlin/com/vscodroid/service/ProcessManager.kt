package com.vscodroid.service

import android.app.ActivityManager
import android.content.Context
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.util.PortFinder
import com.vscodroid.util.ServerLog
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Manages the Node.js server process lifecycle.
 *
 * Responsibilities:
 * - Starting and stopping the Node.js server process
 * - Health-checking the server -- see [isServerHealthy] for which endpoint and why
 * - Monitoring process liveness via a watchdog thread
 * - Streaming stdout/stderr output for diagnostics
 *
 * This class does NOT handle restart logic or Android service concerns;
 * those belong to [NodeService].
 */
class ProcessManager(private val context: Context) {

    private val tag = "ProcessManager"

    /**
     * Where the server's own output is kept for a bug report to pick up.
     *
     * Fed directly from [startOutputReader] rather than through [onServerOutput].
     * That seam is public and documented as one, so a consumer assigning it would
     * silently replace this writer, and the symptom would be a report that is
     * empty again with nothing to say why.
     */
    private val serverLog = ServerLog(File(Environment.getLogsDir(context), "server.log"))

    private var serverProcess: Process? = null
    private var watchdogThread: Thread? = null
    private var _port: Int = 0
    @Volatile
    private var isShuttingDown = false

    // Volatile for the same reason isShuttingDown is: the watchdog thread clears
    // it when the process exits, while the main thread reads it to decide whether
    // to navigate the WebView.
    @Volatile
    private var _isReady = false

    /**
     * Whether the server being served is one this instance found rather than
     * started.
     *
     * Volatile for the same reason the two above are: the watch thread clears it
     * when the adopted server stops answering, and the main thread reads it.
     *
     * There is no [Process] behind an adopted server, so everything that reasons
     * from `serverProcess` has to consult this too: the start guard, and
     * [stopServer], which cannot kill what it did not spawn and says so instead
     * of reporting success.
     */
    @Volatile
    private var adopted = false

    /**
     * Whether the process the last [startServer] spawned went onto a port
     * something else was already holding.
     *
     * Recorded at the spawn because that is the only moment the two cases can be
     * told apart. Afterwards the port is occupied either way, and nothing in a
     * later probe distinguishes a server of ours holding its own port from a
     * stranger holding it while our process sits behind it unable to bind.
     *
     * Volatile for the same reason as the flags above: it is written on whichever
     * thread called [startServer] and read from the service's main dispatcher.
     */
    @Volatile
    private var spawnedOntoHeldPort = false

    /**
     * Whether the process the last [startServer] spawned was given a ceiling the
     * user chose rather than one derived from the device.
     *
     * The only consumer is the kill latch in `NodeService.handleServerCrash`, and
     * what it is really recording is attribution: a SIGKILL only counts against a
     * user's number if that number is what the dead process was running with.
     *
     * Volatile for the same reason as the flags above: written on whichever thread
     * called [startServer], read from the service's main dispatcher.
     */
    @Volatile
    private var heapOverrideActive = false

    /** The port the server is listening on. Only valid after [startServer] returns true. */
    val port: Int get() = _port

    /**
     * Whether the running server was started with a user-chosen heap ceiling.
     *
     * False whenever no process of ours is running with one, which covers more
     * than the plain case: a server that was adopted rather than spawned, and a
     * server that has been stopped, both answer false. Both matter, because a
     * crash attributed to a value the dead process never carried would spend the
     * user's budget on something they did not do.
     */
    fun heapOverrideInEffect(): Boolean = heapOverrideActive

    /**
     * Whether the server on the port was adopted rather than started here.
     *
     * Exposed so callers can distinguish "we have a server" from "we control the
     * server", which are the same question only while we started it.
     */
    fun isAdopted(): Boolean = adopted

    /**
     * Whether the running process was spawned onto a port that was already taken,
     * and therefore cannot ever serve.
     *
     * The editor server it forks prints `EADDRINUSE` and then does **not** exit,
     * measured on an API 36 emulator, same pids at 0, 5, 15, 30 and 60 seconds,
     * so the bootstrap does not exit either and [isRunning] answers true for as
     * long as nothing kills it. Liveness therefore says nothing here, and the
     * caller that waits on liveness would wait forever: see
     * `NodeService.awaitLateReadiness`, whose loop is bounded by exactly that.
     *
     * Answered from what was true at the spawn rather than from a fresh probe,
     * because a fresh probe cannot separate the two things it would have to: a
     * held port looks the same whether the holder is the server we started or the
     * stranger our server lost the race to.
     */
    fun spawnedOntoHeldPort(): Boolean = spawnedOntoHeldPort

    /**
     * The connection token the server requires on every route except `/version`,
     * `/delay-shutdown` and `/callback`.
     *
     * The server owns this value, not us: with no connection-token flag on its
     * command line it reads the file [Environment.getConnectionTokenPath] names,
     * generates one if it is absent, and writes it back with mode 0600. Reading
     * that file is therefore the only way to learn it, and it is only there once
     * the server has started, which is why this is read on demand rather than
     * cached at construction.
     *
     * Returns null before the server has written it. Callers that need it are all
     * downstream of readiness, so in practice that is the "server failed to start"
     * path, where a missing token is not the interesting failure.
     */
    val connectionToken: String?
        get() = cachedToken ?: readTokenFile()?.also { cachedToken = it }

    @Volatile
    private var cachedToken: String? = null

    /**
     * Where `/proc` is mounted. A constant in production, and a `var` only so the
     * suite can point it at a fixture: the machines these tests run on have no
     * `/proc` at all, so a test for [portHeldByOurEditorServer] that used the real
     * path could only ever exercise the "process is gone" branch and would report
     * a passing adoption test while never adopting anything.
     */
    private var procDir = File("/proc")

    /**
     * How [reapRecordedEditorServer] signals a process. A `var` for the same reason
     * [procDir] is one: the suite runs on a JVM where `android.os.Process` is a stub, so a
     * test calling through to it could only ever assert that nothing happened.
     */
    internal var killRecordedProcess: (Int) -> Unit = { android.os.Process.killProcess(it) }

    // Cached on the first successful read and never invalidated, which is correct
    // rather than merely convenient: the server generates the token only when the
    // file is absent and otherwise reuses what is there, so the value survives its
    // own restarts. Without the cache this would be a filesystem read on every
    // intercepted request, and the workbench issues hundreds during a cold load.
    private fun readTokenFile(): String? = try {
        File(Environment.getConnectionTokenPath(context))
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Logger.w(tag, "Could not read the connection token: ${e.message}")
        null
    }

    // -- Callbacks --

    /**
     * Invoked on the watchdog thread when the server process exits unexpectedly.
     *
     * The thread matters to the receiver: `NodeService.setupProcessCallbacks()`
     * does nothing here but hand the exit code to its own scope, because the
     * state it needs to touch belongs to that scope's thread.
     *
     * This is the only callback of the pair that ever existed. A sibling named
     * `onServerRestarting` sat beside it with no assignment and no call site, and
     * `onServerReady` sat here too, a name [waitForReady]'s return value already
     * carried, and one shared with a live callback on `NodeService` that
     * `MainActivity` does assign. Two identically named callbacks in adjacent
     * layers, one of them dead, is a trap for whoever wires up the next one; both
     * are gone rather than documented as dead.
     */
    var onServerCrashed: ((exitCode: Int) -> Unit)? = null

    /** Invoked on the output-reader thread for every line of server stdout/stderr. */
    var onServerOutput: ((line: String) -> Unit)? = null

    // -- Lifecycle --

    /**
     * Starts the Node.js server process.
     *
     * Resolves the port on the first call and keeps it for the lifetime of this
     * instance, builds the command line from [Environment] paths, spawns the
     * process, and begins output reading and watchdog monitoring.
     *
     * @return `true` if the process was spawned successfully, `false` on error or
     *         if a live process is already running.
     */
    fun startServer(): Boolean {
        // Liveness, not nullity: the crash path leaves the dead Process referenced,
        // so a null check here refused every automatic restart attempt (issue #3).
        //
        // `adopted` is the second half of the same question. There is no Process
        // behind an adopted server, so `isRunning()` answers false for one that is
        // serving perfectly well, and without this a restart would spawn a second
        // server onto a port the first still holds.
        if (isRunning() || adopted) {
            Logger.w(tag, "Server already running")
            return false
        }

        isShuttingDown = false
        // A server that is starting is not serving, and this is the assignment
        // that makes a restart honest: the flag survives in this instance across
        // restarts exactly as the port does, so leaving it set would report the
        // previous server's readiness for the new one during the seconds it takes
        // to bind.
        _isReady = false
        // Keep the port across restarts. The WebView's loaded URL and the WebViewClient
        // are both bound to it, and neither is rebuilt on restart: initBridge() guards on
        // bridgeInitialized (MainActivity.kt:494), so the client keeps the port it was
        // constructed with, and it is what CDN interception and the localhost check read
        // (VSCodroidWebViewClient.kt:59,89). This used to cite the bridge's allowed-origin
        // check as the second binding; that check was removed in #144 because nothing
        // called it.
        // Across cold starts it is the workbench's IndexedDB that is bound to it:
        // see PortFinder.getOrAllocatePort.
        //
        // Whether it is free is asked once and held, because two decisions turn
        // on that one answer: whether to adopt, and whether the spawn further
        // down has any chance of binding. Asking twice would let the port change
        // hands in between and record a doomed spawn as a healthy one. A port
        // this call allocates is free by construction, PortFinder either
        // verified the remembered one or scanned for another.
        val portIsFree = if (_port == 0) {
            _port = PortFinder.getOrAllocatePort(context)
            true
        } else {
            PortFinder.isPortAvailable(_port)
        }
        if (!portIsFree && portHeldByOurEditorServer() && recordedServerIsServing()) {
            // A server of ours is already on the port. Serve it instead of
            // spawning a second one that cannot bind.
            //
            // This is the case the long note below is about, and it is the common
            // one rather than the exotic one: `assets/server.js` forks the editor
            // server and forwards SIGTERM, but a SIGKILLed `server.js` -- routine
            // here, it is what the watchdog's 137 branch exists for -- forwards
            // nothing and `fork()` sets no PDEATHSIG, so the child outlives its
            // parent still holding the socket. It is not wreckage: it is a live,
            // healthy editor server, the one the open WebView is still talking to.
            //
            // Measured on an API 36 emulator: spawning anyway produced a parent
            // that never becomes the server. Its child printed
            // `code: 'EADDRINUSE'` and did NOT exit, so this class ended up
            // watching a process whose death means nothing while the process that
            // serves the user was untracked. Adopting removes that second process
            // entirely and puts the watch on the one that matters.
            //
            // The ownership test is what makes this safe to do at all. Anything
            // can hold a loopback port on Android, so the holder cannot be the one
            // asked: [portHeldByOurEditorServer] reads a note the bootstrap wrote
            // into app-private storage naming the pid it forked, and checks that
            // process is still alive and still that server. A holder we have no
            // note for falls through and is spawned over, which fails the way it
            // always did rather than the way a refusal would.
            //
            // Ownership is only half of it, and the note cannot supply the other
            // half: it is written at the fork, so it names a process that was
            // asked to bind the port rather than one that did.
            // [recordedServerIsServing] asks the port itself before this branch is
            // taken, see there for what a note alone lets through, and why the
            // two questions are not interchangeable.
            Logger.i(tag, "Port $_port already served by a server of ours; adopting it")
            // Said out loud because nothing else in the app can see it, and the
            // symptom it produces points nowhere near here. `assets/dns-proxy.js`
            // runs INSIDE the bootstrap and hands the child its address once, at
            // fork time (`assets/server.js`); reaching this branch means that
            // bootstrap is gone, a start is refused while ours is alive, so an
            // adopted server is by construction one whose parent died. The proxy
            // died with it, and the survivor still has the dead address in its
            // environment, which nothing can change in a running process. So
            // everything in it that honours HTTPS_PROXY, the Open VSX gallery,
            // the agent host, and git, npm and curl in every terminal, since they
            // inherit that environment, fails to reach the network for as long
            // as this session lasts, while the workbench itself looks perfectly
            // healthy. Restarting the app is the only cure, and this line is the
            // only way to tell that is what is wrong. Not fixable from here: it
            // would take a proxy whose lifetime is not the bootstrap's.
            Logger.w(
                tag,
                "The adopted server inherited the DNS proxy of the bootstrap that forked " +
                    "it, and that bootstrap is gone; outbound requests honouring " +
                    "HTTPS_PROXY will fail until the app is restarted",
            )
            adopted = true
            _isReady = false
            isShuttingDown = false
            // Nothing was spawned, so the flag describes nothing. Cleared rather
            // than left, because it survives in this instance across attempts and
            // a value left over from a previous one is not about this server.
            spawnedOntoHeldPort = false
            // The same reasoning, and a sharper consequence. An adopted server was
            // spawned by a bootstrap that is gone, and it carries whatever ceiling
            // that bootstrap gave it -- which is unknowable from here and is not
            // whatever this instance last read out of settings.json. Left set, the
            // user's number would be charged for a kill of a process that never
            // ran with it, and three of those disable a value that was never tried.
            heapOverrideActive = false
            startAdoptionWatch()
            return true
        }
        // An editor server of ours that is alive, holds the port, and is not one this
        // start can adopt is the one shape worth ending before spawning over it. Adoption
        // already declined it just above, and leaving it there guarantees the spawn below
        // hits EADDRINUSE: that child does not exit, so the run ends in CANNOT_BIND with
        // two processes where the user wanted one, and the survivor outlives every retry.
        //
        // Three declines reach here and only one of them is silence, so the reason is not
        // repeated in the reap: [recordedServerIsServing] has already logged which of them
        // it was. `/version` did not answer within a second, which a server still starting
        // up could fail; or it answered as a different build, which an orphan left by an
        // earlier app version does; or this build could not read its own commit, so the
        // port was never asked at all.
        //
        // The last two end a process that may be serving perfectly well, and that is a
        // decision rather than an oversight. What it is serving is not this build, or
        // cannot be shown to be, and the alternative is not "leave the user's editor
        // alone" but a launch that fails for as long as the holder lives, because nothing
        // here can bind the port while it is held and `_port` is never re-derived. A
        // server ended a second early is restarted by the line below; one left alone is
        // not.
        var reapedThisStart = false
        if (!portIsFree && portHeldByOurEditorServer()) {
            reapedThisStart =
                reapRecordedEditorServer("not adoptable by this start, for the reason logged above")
        }

        // Reaching here means the port was free, or was held by something that is
        // not ours. Nothing refuses to start in either case, and that is a
        // decision rather than an omission: a refusal was tried and removed. What
        // follows is what it cost and what the real problem was, because the idea
        // is an obvious one to have again.
        //
        // The situation it aimed at: `assets/server.js` forks the editor server
        // and forwards SIGTERM to it, but a `server.js` that is SIGKILLed --
        // routine here, it is what the watchdog's 137 branch exists for --
        // forwards nothing, and `fork()` sets no PDEATHSIG. So the child outlives
        // its parent still holding the port. `assets/process-monitor.js` does not
        // reap it: it only kills idle language servers, and it runs inside the
        // parent it would have to outlive.
        //
        // The trap is what that child IS. It is not a stranger and it is not
        // wreckage -- it is a live, healthy editor server, the one the open
        // WebView is still talking to. Refusing to start because the port is held
        // therefore takes a working editor away from the user, in the common case,
        // to fix bookkeeping. It also cannot recover: `_port` is written only
        // while it is zero, so nothing re-derives it, and a refusal repeats for
        // the life of this instance.
        //
        // What IS wrong is that nothing distinguishes the survivor from a server
        // this class started. The health probe reads a response code and nothing
        // else, so the survivor satisfies readiness, `NodeService.announceReady`
        // fires, and the WebView is pointed at it. That much works -- the token
        // matches, because `server.js` reuses the token file -- which is why
        // nothing downstream notices. The cost is everywhere the app assumes the
        // server it is serving is the one it spawned:
        //
        //  - `serverProcess` refers to the process spawned here, not the survivor.
        //    So [stopServer] destroys the wrong one. Stop takes the notification
        //    down, reports success, and `MainActivity`'s `onServerStopped` closes
        //    the editor with `finishAndRemoveTask()` -- while the survivor is
        //    still running, still serving, still holding the port, and now with no
        //    foreground service tracking it at all.
        //  - the next cold start finds the port taken, and
        //    `PortFinder.getOrAllocatePort` moves to another one. It says so
        //    itself: "Workbench storage keyed to the old origin is lost." That is
        //    IndexedDB -- signed-out sessions, every extension's globalState,
        //    secret storage -- discarded with no user-visible cause.
        //  - and the restart budget is refilled by the survivor answering, so this
        //    class can never conclude that anything is wrong. Note what that does
        //    and does not mean: an earlier version of this note said the children
        //    that cannot bind "respawn without bound", and the measurement below
        //    refutes it -- they do not exit, so nothing respawns. The cost is not
        //    churn, it is that the terminal state becomes unreachable while the
        //    condition lasts.
        //
        // And the process spawned here never becomes the server. Measured on an
        // API 36 emulator, killing the parent and leaving the child: the survivor
        // was still LISTENING on the port after sixty seconds, reparented to init;
        // the child of the newly spawned parent printed
        // `code: 'EADDRINUSE', syscall: 'listen'` and then did NOT exit. Same pids
        // at 0, 5, 15, 30 and 60 seconds -- no churn, no respawn -- and the
        // workbench on screen was never reloaded, which its hour-old unsent chat
        // text proved better than any log could.
        //
        // So the steady state is two servers with the roles swapped. The one this
        // class manages is alive, watched, and serving nothing, forever. The one
        // serving the user is untracked. `stopServer` can only reach the first,
        // which makes the second unkillable by any route the app has, and the
        // first immortal. The watchdog watches a process whose death would mean
        // nothing and cannot see the one whose death would take the editor away.
        //
        // Worth knowing before designing the fix: the app already SEES this
        // happen. `startOutputReader` receives that EADDRINUSE line and now
        // writes it to `server.log`, so a bug report carries it. The same line
        // also goes to `Logger.d`, which in a release build is nowhere, and to
        // [onServerOutput], which still has no production consumer.
        //
        // What the spawn below now does NOT do is last forever. A pair that
        // cannot bind stays alive indefinitely, the measurement above is the
        // proof, so every question the app asks about it answers "still
        // starting", and the launch path waits on liveness. [spawnedOntoHeldPort]
        // is what ends that: the service reads it when the start poll gives up on
        // a live process, kills the pair and spends a restart instead of watching
        // it for the life of the app. The port is deliberately not re-derived
        // here, because the WebView and its client are built around the one this
        // instance already published; a start that keeps failing therefore ends
        // in the terminal state, which says so on the notification.
        //
        // Those are the defects worth fixing, and none of them is fixed by
        // refusing to start. Do not read the list as an argument for putting the
        // refusal back: refusing produced a state the user could not reach the
        // editor from at all, which is worse than every line above.
        //
        // ALL THREE ARE ADDRESSED BY THE ADOPTION BRANCH ABOVE, which is why the
        // list above is written in the past tense of a defect rather than as a
        // standing one. Adoption makes `serverProcess` and the thing on the port
        // the same subject again -- by having no second subject at all -- and that
        // is where all three came from.
        //
        // What it does NOT fix, and this is the honest residue: an adopted server
        // still cannot be killed by this app, because there is no handle to it.
        // [stopServer] says so now instead of destroying the wrong process and
        // reporting success, which is a smaller claim than "Stop works" and a true
        // one. And the IndexedDB loss on a later cold start is unchanged -- that
        // happens when a fresh instance finds the port taken and `PortFinder`
        // moves, and adoption does not reach across process lifetimes.
        //
        // The objection that kept adoption out until now was real and had to be
        // answered rather than accepted: an adopted server has no `Process`, so no
        // watchdog, so its death would go unnoticed -- trading the loud failure
        // the refusal was removed for against a silent one, in the other
        // direction. [startAdoptionWatch] is that answer. It polls, because a
        // signal a `Process` gives for free has to be asked for here, and reports
        // through the same `onServerCrashed` the watchdog uses, so recovery is the
        // path that already exists rather than a second one.
        //
        // If that watch is ever removed or weakened, adoption stops being safe and
        // the trade comes back. It is the load-bearing half.
        //
        // Recorded before the spawn, because this is the last moment it can be
        // known: from here on the port is occupied whichever case this is. It is
        // what lets the service tell a slow server from one that will never
        // answer, see [spawnedOntoHeldPort] and `NodeService.awaitLateReadiness`.
        // The reap is subtracted only when the port agrees it is free, because
        // a signalled pid is not a released socket: the kill returns when the
        // signal is sent, and the holder can also be a foreign process the reap
        // never touched, the note naming a wedged child that never bound while
        // someone else took the port. Answering from the signal alone would call
        // that spawn healthy while it wedges on EADDRINUSE without exiting, and
        // the liveness-bounded wait behind the flag would never end: the failure
        // the restart budget used to report, become a "still starting" that
        // says nothing. The probe is the same loopback bind test [PortFinder]
        // already answers, and its residual race, a port freed just after it
        // reads held, is the one the free-at-first-ask path has always accepted.
        spawnedOntoHeldPort = !portIsFree &&
            !(reapedThisStart && PortFinder.isPortAvailable(_port))
        Logger.i(tag, "Starting server on port $_port")

        // Ensure TMPDIR is a usable directory: Android may clear cache between
        // launches.
        //
        // `isDirectory` rather than `exists`, and the return value of `mkdirs` is
        // read rather than discarded. Both halves are the same mistake: the test
        // asked whether *something* was at that path while the code meant whether
        // a *directory* was, and they part company exactly when a file sits there,
        // at which point `mkdirs` cannot succeed and said so to nobody. This
        // path is TMPDIR and TMUX_TMPDIR for the server (Environment), so the
        // failure would surface later as temporary-file errors with no visible
        // connection to it.
        //
        // Not fatal to a start. A server with a broken TMPDIR is worse than one
        // with a working one and far better than none, and the log line is what
        // makes the eventual symptom traceable.
        val tmpDir = File(context.cacheDir, "tmp")
        if (!tmpDir.isDirectory && !tmpDir.mkdirs()) {
            Logger.w(tag, "Could not create TMPDIR at ${tmpDir.path}; " +
                "the server may fail on temporary files")
        }

        val nodePath = Environment.getNodePath(context)
        val serverScript = Environment.getServerScript(context)
        val env = Environment.buildProcessEnvironment(context, _port)

        // These arguments reach server.js, not the editor server it forks. server.js
        // reads host, port and log as defaults and forwards exactly four keys --
        // extensions-dir, user-data-dir, server-data-dir and logsPath -- while
        // building the rest of the command itself. Two flags that used to sit here,
        // --without-connection-token and --accept-server-license-terms, were read by
        // nobody: server.js writes its own. They are gone rather than corrected,
        // because an argument that looks decisive and does nothing is worse than a
        // missing one, and the next person to change authentication would have
        // started here.
        val heapMb = heapCeilingForDevice()
        val command = listOf(
            nodePath,
            "--max-old-space-size=$heapMb",
            serverScript,
            "--host=127.0.0.1",
            "--port=$_port",
            "--extensions-dir=${Environment.getExtensionsDir(context)}",
            "--user-data-dir=${Environment.getUserDataDir(context)}",
            "--server-data-dir=${Environment.getUserDataDir(context)}",
            "--logsPath=${Environment.getLogsDir(context)}",
            "--log=info"
        )

        return try {
            val processBuilder = ProcessBuilder(command).apply {
                environment().putAll(env)
                redirectErrorStream(true)
                directory(context.filesDir)
            }
            serverProcess = processBuilder.start().also { it.outputStream.close() }
            startOutputReader()
            startWatchdog()
            // The source is named, not only the number. A ceiling in a bug report
            // that could be either a derived value or a user's is a number nobody
            // can act on.
            val summary =
                "Server process started with PID ${getServerPid()}, heap ceiling ${heapMb}MB " +
                    "(${if (heapOverrideActive) "user override, clamped" else "derived from device RAM"})"
            Logger.i(tag, summary)
            // And said twice on purpose. A bug report carries `server.log`, which
            // only [ServerLog] writes, while `Logger.i` reaches logcat and in a
            // release build stops there. Mirroring the one line makes the figure
            // reachable from the report, next to the server output it explains.
            // The output reader is already appending on its own thread by now;
            // each append is a separate open in append mode, so the line lands
            // whole either side of whatever the server has printed.
            serverLog.append(summary)
            true
        } catch (e: Exception) {
            Logger.e(tag, "Failed to start server", e)
            false
        }
    }

    /**
     * Suspends until the server responds to a health check or the timeout elapses.
     *
     * Polls [isServerHealthy] at [pollIntervalMs] intervals and answers with the
     * outcome. The answer is the whole contract: there is no readiness callback
     * here, because a suspending function that returns the result already told
     * the caller, and the one that used to sit here was assigned by nobody.
     *
     * @param timeoutMs  Maximum time to wait for the server to become ready.
     * @param pollIntervalMs  Interval between health check attempts.
     * @return `true` if the server became ready within the timeout.
     */
    suspend fun waitForReady(
        timeoutMs: Long = READY_POLL_TIMEOUT_MS,
        pollIntervalMs: Long = 200,
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (probeReadiness()) {
                Logger.i(tag, "Server ready after ${System.currentTimeMillis() - startTime}ms")
                return true
            }
            delay(pollIntervalMs)
        }
        Logger.e(tag, "Server failed to become ready within ${timeoutMs}ms")
        return false
    }

    /**
     * Whether the process holding our port is an editor server this app started.
     *
     * Answered from a note the bootstrap left, not by asking the port holder.
     * That direction is the whole point, and the earlier version had it backwards:
     * it built `http://127.0.0.1:$port/?tkn=<token>` and treated any answer other
     * than 403 as proof of ownership. Two things were wrong with it, and the
     * second follows from the first.
     *
     * It handed the connection token to whoever held the port. Binding a loopback
     * port on Android needs no permission at all, so a process that has just taken
     * the port from a killed server is precisely the party that must not be given
     * the token, and it was given the token before anything about it was known.
     * The doc claimed the opposite ("a process that accepts it either is one of
     * ours or has read a file nothing outside this app can"), which is only true
     * of a test that asks the holder to PRODUCE the token; this one presented it.
     *
     * And "not a refusal" is not a test. 200, 302, 404 and 500 all passed it, so
     * anything that accepts a TCP connection and answers something was adopted,
     * after which readiness is a bare 200 from `/version`, and the WebView is
     * pointed at it with the bridge attached.
     *
     * So ownership is established where it is actually known. `assets/server.js`
     * writes the pid of the editor server it forked, with the port, into
     * app-private storage; this reads it back and checks the process is still
     * alive and still that server. Nothing is sent anywhere, and a stranger cannot
     * forge the answer, because the answer never leaves this app's own storage.
     *
     * Three ways this says no, all of them the safe direction: no note, a note for
     * a different port, or a pid that is gone or is now some other program. Each
     * falls through to the ordinary spawn, which is what happened before adoption
     * existed.
     *
     * What it does NOT establish is that the recorded process is the one holding
     * the socket, that would need /proc/net/tcp, which SELinux denies an app
     * outright (measured: untrusted_app against proc_net_tcp_udp returns an empty
     * mask). Nor does it establish that the recorded process ever held it: the
     * note is written at the fork, before the child has had the chance to listen
     * and whether or not it ever will.
     *
     * So this answers one question of the two, and [recordedServerIsServing]
     * answers the other before [startServer] adopts anything. This doc used to
     * end by calling the gap narrow and saying [startAdoptionWatch] caught what
     * fell through it; the watch does notice, and noticing is not catching,
     * it reports the server lost, the restart adopts the same note again, and the
     * budget is spent on a loop. Neither question is sufficient alone.
     */
    fun portHeldByOurEditorServer(): Boolean {
        val note = File(Environment.getServerDir(context), EDITOR_PID_FILE)
        val recorded = try {
            if (!note.isFile) return false
            JSONObject(note.readText())
        } catch (e: Exception) {
            Logger.w(tag, "Could not read the editor server note: ${e.message}")
            return false
        }

        val pid = recorded.optInt("pid", 0)
        val port = recorded.optInt("port", 0)
        if (pid <= 0 || port != _port) {
            Logger.i(tag, "Editor server note does not describe port $_port; not adopting")
            return false
        }

        // /proc/<pid> is readable for this app's own processes, the same access
        // process-monitor.js already relies on, and unreadable for anyone
        // else's, so a pid that has been recycled into another app's process
        // reads as absent rather than as a match.
        val cmdline = try {
            File(File(procDir, pid.toString()), "cmdline").takeIf { it.isFile }?.readText()
        } catch (e: Exception) {
            null
        }
        if (cmdline == null) {
            Logger.i(tag, "Editor server $pid is gone; not adopting port $_port")
            return false
        }

        // The kernel separates argv with NULs; substring matching over the raw
        // bytes is enough and avoids caring how many arguments there are.
        val isOurServer = cmdline.contains(EDITOR_ENTRY_POINT)
        if (!isOurServer) {
            Logger.w(tag, "Pid $pid is no longer an editor server; not adopting port $_port")
        }
        return isOurServer
    }

    /**
     * Ends the editor server the note names, when it is still one of ours.
     *
     * The gap this closes: `assets/server.js` forwards SIGTERM to the editor server it
     * forked, but a SIGKILLed bootstrap forwards nothing and `fork()` sets no PDEATHSIG,
     * so the child outlives its parent. [stopServer] cannot end that one, because it holds
     * a `Process` handle only for a child it spawned itself. The survivor then keeps its
     * heap for the life of the app and counts against the 32-process limit
     * `assets/process-monitor.js` exists to stay under.
     *
     * Killing by a recorded pid has an obvious hazard: pids are recycled, so the process
     * at that number may no longer be the one the note described. Three things bound it,
     * and the first is doing most of the work:
     *
     *  - **The kernel refuses across uids.** `kill(2)` needs a matching uid, and every
     *    other app on the device runs as a different one, so a pid recycled outside this
     *    app cannot be killed here at all: the call fails rather than hitting a stranger.
     *    What remains reachable is this app's own processes, which is a much smaller set.
     *  - **The cmdline is re-read immediately before the signal**, so a pid recycled into
     *    one of our terminals or a language server does not match [EDITOR_ENTRY_POINT] and
     *    is left alone. The window between that read and the signal is what cannot be
     *    closed from userspace; it is microseconds wide and needs a recycle landing inside
     *    it onto another editor server of ours.
     *  - **The note is consumed either way**, so a pid this declines to kill is not
     *    reconsidered on the next call.
     *
     * @return whether a process was signalled. False covers no note, a note for another
     *   port, a pid already gone, and a pid that is no longer an editor server.
     */
    internal fun reapRecordedEditorServer(reason: String): Boolean {
        val note = File(Environment.getServerDir(context), EDITOR_PID_FILE)
        val recorded = try {
            if (!note.isFile) return false
            JSONObject(note.readText())
        } catch (e: Exception) {
            Logger.w(tag, "Could not read the editor server note: ${e.message}")
            return false
        }
        val pid = recorded.optInt("pid", 0)
        val port = recorded.optInt("port", 0)
        if (pid <= 0 || port != _port) return false

        // Deliberately the last thing before the signal. Re-reading here rather than
        // trusting the check adoption already made is the whole mitigation: that one may
        // be minutes old, and this one is the only thing standing between a recycled pid
        // and a process of ours that has nothing to do with the editor.
        val cmdline = try {
            File(File(procDir, pid.toString()), "cmdline").takeIf { it.isFile }?.readText()
        } catch (e: Exception) {
            null
        }
        note.delete()
        if (cmdline == null) {
            Logger.i(tag, "Editor server $pid is already gone; nothing to reap ($reason)")
            return false
        }
        if (!cmdline.contains(EDITOR_ENTRY_POINT)) {
            Logger.w(tag, "Pid $pid is no longer an editor server; leaving it alone ($reason)")
            return false
        }
        return try {
            killRecordedProcess(pid)
            Logger.i(tag, "Ended the editor server $pid still holding port $_port ($reason)")
            true
        } catch (e: Exception) {
            Logger.w(tag, "Could not end the editor server $pid: ${e.message}")
            false
        }
    }

    /**
     * Whether the port is answering, and answering as this build.
     *
     * The second half of the adoption test, and it is not a restatement of the
     * first. [portHeldByOurEditorServer] proves the recorded process is alive and
     * is still an editor server of ours; nothing in the note says it ever bound
     * the port, because `assets/server.js` writes it at the fork.
     *
     * That gap has a process that fits it exactly. A server spawned onto a port
     * something else holds prints `EADDRINUSE` and then does not exit, measured
     * on an API 36 emulator, same pids at 0, 5, 15, 30 and 60 seconds, so a
     * bootstrap SIGKILLed by the OOM killer or the phantom-process limit, which
     * is the case adoption exists for, can leave behind a child that is alive,
     * is an editor server, matches the note, and has never held the port. Adopting
     * it produces a session that never answers: [startAdoptionWatch] calls it lost
     * after two missed probes, the restart reads the same note and adopts the same
     * process, and five rounds later the app is in the terminal state having never
     * attempted the spawn that would have worked. Nothing clears that note, so it
     * survives cold starts too, for as long as the orphan does.
     *
     * Asking the port is safe in the way the ownership test that preceded it was
     * not: `/version` is answered before the connection-token check, so the probe
     * carries no token and discloses nothing to whoever is on the other end. That
     * direction is not negotiable. The credential is what the WebView is about to
     * carry to this port, and handing it to the party the test exists to identify
     * is the mistake that was taken out of here once already.
     *
     * What the port is asked for is its identity, not merely a status line. The
     * route answers `productService.commit` as `text/plain`, so a holder that is
     * this build's editor server returns the commit
     * `<server-dir>/vscode-reh/product.json` records, and one that is not returns
     * something else, or nothing, or 403. A bare 200 was the whole test until now,
     * which every process that accepts a connection and answers anything at all
     * satisfies, and binding a loopback port on Android needs no permission.
     *
     * Read what this does and does not settle, because the difference decides
     * what may be built on it. It settles which VS Code source the holder was
     * built from, and no more than that: the value is `microsoft/vscode`'s tag SHA
     * for the pinned version, the same one the tracked `VSCODE_COMMIT` file
     * records, so every VSCodroid build at that pin answers identically and the
     * value is app-private in no sense at all. That excludes an ordinary stranger
     * on the port, which is what adoption needs, and nothing stronger may be built
     * on it: it does not tell two builds of this app apart. It does NOT settle
     * that the holder is the recorded pid either: the commit is public, so a
     * process written to imitate this app can answer it, and attributing a socket
     * to a pid would need the `/proc/net/tcp` read SELinux refuses an app
     * outright. The two halves therefore still narrow rather than prove, and the
     * note stays the ownership half.
     *
     * Refusing here is not final and deletes nothing, so a recorded server that
     * was merely slow to answer is adopted by a later attempt. Being wrong in this
     * direction costs one spawn that cannot bind, which is what a start onto a
     * held port did before adoption existed; being wrong in the other costs the
     * session.
     */
    private fun recordedServerIsServing(): Boolean {
        // Answered first, because a build that cannot say what it is cannot
        // recognise itself on a port either. That is the fail-closed direction:
        // adoption is an optimisation, and declining it costs a spawn.
        val expected = bundledServerCommit()
        if (expected == null) {
            Logger.w(
                tag,
                "The packaged server records no commit, so nothing answering on port " +
                    "$_port can be identified as this build; spawning rather than adopting",
            )
            return false
        }

        val served = probeVersion()
        if (served == null) {
            Logger.w(
                tag,
                "An editor server of ours is recorded on port $_port but nothing is " +
                    "answering there; spawning rather than adopting it",
            )
            return false
        }
        if (served != expected) {
            Logger.w(
                tag,
                "Port $_port is held by something reporting \"$served\" rather than this " +
                    "build's $expected; spawning rather than adopting it",
            )
            return false
        }
        return true
    }

    /**
     * The commit the packaged server tree was built from, or null if it does not
     * say.
     *
     * The same file the server itself answers `/version` from: `server.js`
     * rewrites `product.json` on every start, but its overrides do not include
     * `commit`, so what is on disk is what the running server reports.
     *
     * Null covers an absent file, unreadable JSON, and a tree built without a
     * commit. All three mean the same thing here, that this build cannot be told
     * apart from anything else, and all three decline adoption rather than falling
     * back to accepting whoever answers.
     */
    private fun bundledServerCommit(): String? = try {
        File(Environment.getServerDir(context), REH_PRODUCT_FILE)
            .takeIf { it.isFile }
            ?.let { JSONObject(it.readText()).optString("commit") }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Logger.w(tag, "Could not read the packaged server's commit: ${e.message}")
        null
    }

    /**
     * Whether `http://127.0.0.1:{port}/version` answers 200.
     *
     * `/version` is answered before the connection-token check: the server handles
     * it and returns, then gates everything else, so this stays a pure liveness
     * probe and does not need the token.
     *
     * It also has to be `/version` rather than `/`. This accepted anything below
     * 500, which was fine while every route answered 200, and became wrong the
     * moment the server started requiring a token: `/` then answers 403, and a
     * readiness check that counts 403 as healthy reports a successful startup for
     * a server that will serve the user nothing but Forbidden.
     *
     * Liveness only, deliberately. Which build answered is [probeVersion]'s
     * business and only adoption asks it, because a readiness poll runs every
     * 200 ms for thirty seconds and has no use for the answer.
     */
    fun isServerHealthy(): Boolean = probeVersion() != null

    /**
     * What `/version` answers, or null if the port did not answer 200.
     *
     * One request serves both questions the class asks of the port. Liveness only
     * needs the status, so [isServerHealthy] discards the body; adoption needs to
     * know which build answered, and reading it costs one more read on a socket
     * that is already open.
     *
     * The body is taken up to [VERSION_BODY_MAX_CHARS] and no further. The read
     * timeout bounds each read rather than the total, so an unbounded reader would
     * let whatever holds the port keep this thread as long as it kept sending, and
     * the point of the call is that the holder may be a stranger.
     */
    private fun probeVersion(): String? = try {
        val connection = (URL("http://127.0.0.1:$_port/version").openConnection()
            as HttpURLConnection).apply {
            connectTimeout = 1000
            readTimeout = 1000
            requestMethod = "GET"
            instanceFollowRedirects = false
        }
        try {
            if (connection.responseCode == 200) {
                connection.inputStream.reader().use { readBounded(it) }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun readBounded(reader: Reader): String {
        val buffer = CharArray(VERSION_BODY_MAX_CHARS)
        var filled = 0
        while (filled < buffer.size) {
            val read = reader.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return String(buffer, 0, filled).trim()
    }

    /**
     * Stops the server, waiting briefly for it to go before killing it.
     *
     * [isShuttingDown] is set first, and that is what keeps [startWatchdog]
     * quiet: the exit that follows is expected, so no crash callback fires and
     * nothing restarts the server behind this call. It was the one thing a
     * second, older copy of this documentation said that this one did not.
     *
     * The wait is short because it is paid on whichever thread calls this, and
     * the Stop action on the notification calls it from Service.onDestroy --
     * the main thread. A five-second wait there does not trip the service ANR
     * timeout, which is far longer, but the main thread is shared: any input
     * dispatched behind it waits too, and that timeout is five seconds. The
     * freeze users reported is input starving, not the service stalling.
     *
     * It is not moved to a background thread, and that is deliberate. Android
     * will not kill the process while a lifecycle callback is running, so
     * waiting here is what keeps [Process.destroyForcibly] reachable. Hand the
     * wait to another thread and a process killed the moment onDestroy returns
     * leaves Node reparented to init and still running -- trading a freeze for
     * an orphan, which is worse and much harder to notice.
     *
     * A second is enough because it is not the real mechanism: server.js
     * forwards SIGTERM to the editor server it forked (assets/server.js:215),
     * so a healthy shutdown finishes in milliseconds. The budget exists for a
     * server that has stopped responding to signals, and for that case the
     * forcible kill below is the answer rather than a longer wait.
     */
    fun stopServer() {
        isShuttingDown = true
        _isReady = false
        // Nothing of ours is running with the user's number after this, so a crash
        // report still on its way to the service must not be charged against it.
        heapOverrideActive = false
        if (adopted) {
            // No `Process` handle exists for a server this class did not spawn, which is
            // why this used to be a log line saying the stop could not end it. The note
            // the bootstrap wrote is the way in: it names the pid, and killing by pid is
            // what [reapRecordedEditorServer] bounds. Leaving it running cost an idle Node
            // process for the life of the app, holding its heap and one of the 32 slots
            // the phantom-process limit allows.
            //
            // Still reported when it cannot be ended, because that outcome has not gone
            // away: a note that has been consumed, a pid already recycled, or a kill the
            // kernel refuses all land here, and a silent return would leave the caller
            // believing the stop succeeded.
            if (!reapRecordedEditorServer("service stopping")) {
                Logger.w(
                    tag,
                    "The server on port $_port was adopted, not started here, and could " +
                        "not be ended from its recorded pid. It keeps running until the " +
                        "system reclaims it.",
                )
            }
            adopted = false
        }
        Logger.i(tag, "Stopping server...")
        serverProcess?.let { process ->
            try {
                process.destroy()
                val exited = process.waitFor(GRACEFUL_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (exited) {
                    Logger.i(tag, "Server stopped with exit code ${process.exitValue()}")
                } else {
                    Logger.w(tag, "Graceful shutdown timed out, force killing")
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                Logger.w(tag, "Shutdown failed, force killing", e)
                process.destroyForcibly()
            }
        }
        serverProcess = null
        watchdogThread?.interrupt()
        watchdogThread = null
    }


    /**
     * The V8 heap ceiling for this device.
     *
     * Reading the device is the point: the flag was a literal 512 regardless of
     * whether the phone had 2 GB or 16 GB. On a small device that left no
     * headroom -- the flag caps the V8 heap, not process RSS, so the native
     * heap, ICU data and loaded addons all sit outside it, and what actually
     * ends the process is Android's low-memory killer, which does not read
     * flags. On a large device the extra RAM went unused.
     *
     * It reaches the worker hosts as well, which is worth writing down because
     * the code invites the opposite conclusion: the flag is on the command line
     * of the main isolate only, while the Extension Host and the Pty Host run as
     * `worker_threads` Workers, so handing it to them as a Worker resource limit
     * looks like a missing step. It is not one, twice over. Measured on Node
     * 22.17.1, which is a major behind the runtime that ships here:
     * `--max-old-space-size` is applied process-wide in V8, so a Worker created
     * with no resource limits at all still died at the ceiling, and a Worker given
     * an explicit, smaller limit ran past it to the ceiling instead, because the
     * command-line flag overrides `resourceLimits` rather than the other way
     * round. Separately, `fork()` passes the parent's `execArgv` on by default,
     * so the flag is already in `process.execArgv` at both places a host Worker
     * is created, and `workerAsChildProcess.ts` turns it into a resource limit
     * there. Adding a pass-through would change no number.
     *
     * READ THE PARAGRAPH ABOVE AS "EACH ISOLATE IS CAPPED AT THIS NUMBER", NOT AS
     * "THE ISOLATES SHARE ONE HEAP OF THIS SIZE". The word process-wide is about
     * where the flag applies, not about what is being divided, and taking it the
     * other way is what makes the arithmetic invisible: a Worker dying AT the
     * ceiling rather than at the ceiling minus what the main isolate already held
     * is what a per-isolate limit looks like. So the number MULTIPLIES rather than
     * divides. Today's 768 authorises about 768 in the bootstrap isolate, 768 in
     * the editor server's main isolate, 768 in the Extension Host worker, 768 in
     * the Pty Host worker and 768 in the still-forked file watcher. Anyone raising
     * this number is taking a larger step than it looks, which is the reason
     * [heapOverrideMaxMb] is as conservative as it is.
     *
     * NOT THE LARGEST V8 HEAP ON THE DEVICE, and this is the other thing the
     * number invites a reader to assume. `typescript-language-features` builds
     * tsserver's own `--max-old-space-size` from `tsserver.maxMemory`, whose
     * upstream default is 3072 with no reference to device RAM, and tsserver is
     * forked by the Extension Host so nothing here is on its path. On a 4 GB phone
     * the editor server gets 462 MB from this function while one language server
     * gets 3072. Whether that number should be clamped, pinned or left is a
     * product decision and not settled here; it is written down so the next reader
     * does not conclude this function bounds the device.
     */
    private fun heapCeilingForDevice(): Int = try {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = info.totalMem / 1_048_576
        val requested = requestedHeapCeiling()
        // "was it taken", not "was one present". See [heapOverrideHonoured]: the
        // two part company on exactly the devices that ignore the request, and the
        // flag decides whose budget the next kill spends.
        heapOverrideActive = heapOverrideHonoured(totalMb, am.isLowRamDevice, requested)
        heapCeilingMb(totalMb, am.isLowRamDevice, requested)
    } catch (e: Exception) {
        // Reading it is not worth failing a start over, and the old literal is
        // the right thing to fall back to: it is what every device ran until now.
        heapOverrideActive = false
        Logger.w(tag, "Could not read device memory, using the default ceiling: ${e.message}")
        HEAP_CEILING_DEFAULT_MB
    }

    /**
     * The ceiling the user has asked for, or null when there is none to honour.
     *
     * Read on every [startServer] rather than cached, and that placement is not an
     * accident: [ProcessManager] is constructed once in `NodeService.onCreate` and
     * lives for the whole service, which spans every crash restart, so a value
     * resolved in a field initialiser or a constructor would be frozen for longer
     * than the user would ever guess.
     *
     * Returns null in four cases that are deliberately not distinguished here,
     * because the answer to all four is the derived ceiling: no settings file, no
     * key in it, a value that is not a bare integer, and a value whose budget of
     * [HEAP_OVERRIDE_KILL_BUDGET] SIGKILLs is spent.
     *
     * The write side of the latch lives here rather than beside the counter for a
     * reason worth keeping: recording the value SEEN at the moment it is honoured
     * is what lets [heapKillsForValue] tell a value that has been killing this
     * device from one the user has since changed. Written with `commit()` and not
     * `apply()`, on IO where this already runs, because the very next thing this
     * record has to survive is a SIGKILL of this app's process, and `apply()`'s
     * deferred write can lose that race.
     */
    // ApplySharedPref: deliberate, for the reason the KDoc gives. What this record
    // has to survive is a SIGKILL of this app's process, which is the one case
    // apply()'s deferred write does not.
    @Suppress("ApplySharedPref")
    private fun requestedHeapCeiling(): Int? = try {
        val settings = File(Environment.getMachineSettingsPath(context))
        val asked = settings.takeIf { it.isFile }?.let { heapOverrideFromSettings(it.readText()) }
        if (asked == null) null else {
            val prefs = context.getSharedPreferences(HEAP_PREFS_NAME, Context.MODE_PRIVATE)
            val kills = heapKillsForValue(
                prefs.getInt(PREF_HEAP_VALUE_SEEN, 0), prefs.getInt(PREF_HEAP_KILLS, 0), asked
            )
            prefs.edit().putInt(PREF_HEAP_VALUE_SEEN, asked).putInt(PREF_HEAP_KILLS, kills).commit()
            if (heapOverrideSuspended(kills)) {
                Logger.w(
                    tag,
                    "The heap ceiling of ${asked}MB in settings.json was followed by $kills " +
                        "kills and is being ignored; change the value to try it again",
                )
                null
            } else asked
        }
    } catch (e: Exception) {
        // A settings file that cannot be read is not a reason to refuse a start.
        // The derived ceiling is what the device ran before the key existed.
        Logger.w(tag, "Could not read the heap ceiling from settings: ${e.message}")
        null
    }

    /**
     * Returns the PID of the running server process, or `null` if not running.
     */
    fun getServerPid(): Long? {
        val process = serverProcess ?: return null
        return try {
            // Prefer the public Java 9+ Process.pid() method when available.
            val pidMethod = process.javaClass.methods.firstOrNull {
                it.name == "pid" && it.parameterCount == 0
            }
            if (pidMethod != null) {
                (pidMethod.invoke(process) as? Long)
            } else {
                // Fallback for runtimes that only expose an internal pid field.
                // This is the branch Android takes: its java.lang.Process has no
                // pid(). On a desktop JVM the method is found but belongs to the
                // package-private ProcessImpl, so invoking it fails and this
                // returns null; do not assert on it from a JVM unit test.
                val pidField = process.javaClass.getDeclaredField("pid")
                pidField.isAccessible = true
                pidField.getInt(process).toLong()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Returns `true` if the server process is alive. */
    fun isRunning(): Boolean = serverProcess?.isAlive == true

    /**
     * Whether the server has answered a health check and has not stopped since.
     *
     * Not the same question as [isRunning], and the gap between them is wide
     * enough to have shipped a bug. `isRunning` asks whether a process exists;
     * this asks whether the thing inside it is serving. Between the two lies
     * every start (the process is spawned in milliseconds and the editor server
     * it forks takes seconds to bind its port) and every restart after a crash.
     * A caller that navigates a WebView on `isRunning` therefore points it at a
     * port with nothing listening, and gets a connection-refused page.
     *
     * The answer costs nothing to read, which is the other half of why this
     * exists. [isServerHealthy] is the real probe and it blocks on HTTP, so it
     * cannot be called from the main thread; that constraint is what pushed the
     * navigation decision onto `isRunning` in the first place. Recording the
     * probe's own result where it already runs gives the main thread the true
     * answer without the I/O.
     *
     * Set by [probeReadiness] and cleared by [startServer], [stopServer], and the
     * watchdog when the process exits. It is deliberately not re-derived on read:
     * a server that was serving a moment ago and has since died clears this
     * through the watchdog, not through a fresh probe nobody on the main thread
     * is in a position to run.
     *
     * That leaves a window worth naming rather than implying away. Between the
     * process dying and the watchdog's `waitFor()` returning, this still answers
     * true while the port is already dead. It is the gap between two statements
     * rather than anything the app waits on (before this flag existed the same
     * caller was wrong for the whole of a restart, so the window shrank by
     * several orders of magnitude), but it did not close, and a caller that must
     * not be wrong even briefly wants [probeReadiness] instead.
     */
    fun isReady(): Boolean = _isReady

    /**
     * Asks the server once, and records the answer if it is yes.
     *
     * Blocking: it is [isServerHealthy] underneath, so it belongs off the main
     * thread like every other caller of that. Blocking also means cancellation
     * does not reach it: a coroutine cancelled while this is in flight cannot act
     * on the result, because it resumes by throwing, but the request itself runs
     * to completion. The ceiling on that is the connect and read timeouts, a
     * little over two seconds, and it is worth knowing rather than rediscovering.
     *
     * The single place `_isReady` is ever set true, which is the point of it
     * existing rather than being written inline in [waitForReady]. [waitForReady]
     * is a *bounded* poll, and something has to keep asking after that bound is
     * spent: a server the poll gave up on is not a server that failed, and it can
     * still bind its port a second later. When the only writer lived inside the
     * bounded loop, that second later was unreachable: the flag stayed false for
     * as long as the process lived, and every caller reading it refused a server
     * that was serving perfectly well.
     */
    fun probeReadiness(): Boolean = isServerHealthy().also { if (it) _isReady = true }

    // -- Internal --

    /**
     * Starts a daemon thread that reads every line from the process stdout/stderr
     * (merged via redirectErrorStream) and forwards it to [onServerOutput] and the
     * debug log.
     */
    private fun startOutputReader() {
        val process = serverProcess ?: return
        thread(name = "node-stdout", isDaemon = true) {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        Logger.d(tag, "[node] $line")
                        serverLog.append(line)
                        onServerOutput?.invoke(line)
                    }
                }
            } catch (e: Exception) {
                if (!isShuttingDown) {
                    Logger.w(tag, "Output reader stopped", e)
                }
            }
        }
    }

    /**
     * Watches a server nobody here spawned.
     *
     * This is the whole answer to the objection that kept adoption out of the
     * codebase: an adopted server has no [Process], so [startWatchdog]'s
     * `waitFor()` has nothing to wait on, and its death would go unnoticed while
     * this class reported it healthy forever. Trading a loud failure for a silent
     * one is what the removal of the port refusal was avoiding, and adopting
     * without this would have reintroduced it from the other side.
     *
     * Polling because there is nothing else available. The signal a `Process`
     * gives for free has to be asked for here, and asking is what
     * [isServerHealthy] already does.
     *
     * Two consecutive failures rather than one, because a single refused
     * connection is not evidence of death, the same reasoning [probeReadiness]
     * gives for not clearing readiness on one bad answer. The cost of being wrong
     * in this direction is a restart the user did not need; in the other it is an
     * editor that stopped working with nothing said.
     *
     * Reports through [onServerCrashed] rather than a channel of its own, so the
     * recovery is the one that already exists. By the time it fires the port is
     * free, so the restart it triggers spawns a real server rather than another
     * one that cannot bind.
     */
    private fun startAdoptionWatch() {
        watchdogThread = thread(name = "adopted-watch", isDaemon = true) {
            var misses = 0
            try {
                while (!isShuttingDown && adopted) {
                    Thread.sleep(ADOPTED_WATCH_INTERVAL_MS)
                    if (isShuttingDown || !adopted) return@thread
                    if (isServerHealthy()) {
                        misses = 0
                        continue
                    }
                    if (++misses < ADOPTED_WATCH_MISSES) continue

                    _isReady = false
                    adopted = false
                    Logger.w(tag, "Adopted server stopped answering; treating it as gone")
                    onServerCrashed?.invoke(ADOPTED_SERVER_LOST)
                    return@thread
                }
            } catch (e: InterruptedException) {
                Logger.d(tag, "Adoption watch interrupted")
            }
        }
    }

    /**
     * Starts a daemon thread that waits for the server process to exit.
     *
     * Readiness is cleared whatever the reason and before the shutdown branch,
     * because a process that has exited is not serving.
     *
     * If [isShuttingDown] is `true`, the exit is expected and no callback fires.
     * Otherwise, [onServerCrashed] is invoked with the exit code.
     *
     * Exit code interpretation:
     * - 0: clean exit
     * - 137: SIGKILL, typically the OOM killer or the phantom process limit
     * - 129 to 192: killed by some other signal, named through [signalName].
     *   This said "unexpected crash" while the bootstrap collapsed every signal
     *   to a clean zero, which made even the 137 above unreachable
     * - anything else: unexpected crash
     */
    private fun startWatchdog() {
        watchdogThread = thread(name = "node-watchdog", isDaemon = true) {
            try {
                val exitCode = serverProcess?.waitFor() ?: return@thread
                // Before the shutdown branch, not inside it. A process that has
                // exited is not serving whatever the reason, and this is the only
                // thread that learns about a crash -- leaving the flag set here
                // would have the main thread navigate to a dead port for the
                // whole restart, which is the case this flag exists for.
                _isReady = false
                if (isShuttingDown) {
                    Logger.i(tag, "Server shut down gracefully")
                    return@thread
                }
                when {
                    exitCode == 0 -> Logger.i(tag, "Server exited cleanly")
                    exitCode == 137 -> Logger.w(tag, "Server killed (OOM or phantom limit)")
                    // The bootstrap reports a killed child as 128 + signal, so any
                    // other signal is named rather than printed as a bare number.
                    // Before, none of these could arrive: the bootstrap collapsed
                    // every signal to a clean zero, so even the 137 branch above
                    // was unreachable.
                    exitCode in 129..192 -> Logger.w(
                        tag, "Server killed by ${signalName(exitCode - 128)}"
                    )
                    else -> Logger.e(tag, "Server crashed with exit code $exitCode")
                }
                onServerCrashed?.invoke(exitCode)
            } catch (e: InterruptedException) {
                Logger.d(tag, "Watchdog interrupted")
            }
        }
    }
}

/**
 * The POSIX signal name for a number, for the few that actually end a process
 * here. Named rather than numbered because "killed by SIGKILL" and "killed by
 * SIGSEGV" send a reader to different places, while "exit code 139" sends them
 * to a search engine.
 */
internal fun signalName(signum: Int): String = when (signum) {
    2 -> "SIGINT"
    6 -> "SIGABRT"
    9 -> "SIGKILL"
    11 -> "SIGSEGV"
    15 -> "SIGTERM"
    else -> "signal $signum"
}

/**
 * How long a deliberate stop waits before killing the server outright.
 *
 * Short on purpose: this is paid on the caller's thread, and the notification's
 * Stop action calls it on the main thread. See [ProcessManager.stopServer].
 */
internal const val GRACEFUL_STOP_TIMEOUT_MS = 1_000L

/**
 * How long [ProcessManager.waitForReady] polls before giving up.
 *
 * A named constant rather than a literal default because two other numbers are
 * measured against it and could not previously say so: `LATE_READY_NOTICE_MS`
 * documents itself as "the other half of that sum", and the restart backoff has
 * to be shorter than this or a launch attempt outlives the crash path that
 * superseded it.
 *
 * Note the poll runs to the end whatever the process does -- it asks the port,
 * not the process -- so a server that dies one second in is still polled for the
 * remaining twenty-nine.
 */
internal const val READY_POLL_TIMEOUT_MS = 30_000L

/**
 * How often an adopted server is asked whether it is still there.
 *
 * Slower than a readiness poll because nothing is waiting on the answer: this
 * is a heartbeat over the life of a session, not a startup check, and it runs
 * for as long as the adopted server does.
 */
internal const val ADOPTED_WATCH_INTERVAL_MS = 5_000L

/**
 * How many consecutive unanswered probes count as gone.
 *
 * Two rather than one, for the reason [ProcessManager.probeReadiness] gives for
 * not clearing readiness on a single failure: one refused connection is not
 * evidence of death. Being wrong this way costs a restart nobody needed; being
 * wrong the other way costs an editor that stopped working with nothing said.
 */
internal const val ADOPTED_WATCH_MISSES = 2

/**
 * The exit code reported when an adopted server stops answering.
 *
 * Negative so it cannot collide with a real process exit status, which is what
 * every other value reaching `onServerCrashed` is. Nothing branches on it today;
 * it exists so a log line naming it is not mistaken for a signal.
 */
internal const val ADOPTED_SERVER_LOST = -1

/**
 * Where `assets/server.js` records the pid of the editor server it forked.
 *
 * Inside the server directory rather than beside the connection token: this is
 * disposable state about one run, and the token is a credential the backup rules
 * are written to keep off any cloud. Keeping them apart means a future rule about
 * one cannot silently pick up the other.
 */
internal const val EDITOR_PID_FILE = "editor-server.pid"

/**
 * The argv fragment that identifies a recorded pid as still being our server.
 *
 * A pid on its own proves nothing after it has been recycled, and Android reuses
 * them freely. This is checked against `/proc/<pid>/cmdline`, which is readable
 * for this app's own processes and not for anyone else's, so another app's
 * process reads as absent rather than as a match, and the check fails closed.
 */
internal const val EDITOR_ENTRY_POINT = "server-main.js"

/**
 * Where the packaged server records which build it is, relative to the server
 * directory.
 *
 * Read for one purpose: `/version` answers `productService.commit` from this
 * file, so it is the value a holder of the port has to produce before adoption
 * will hand it the WebView. Nothing here writes it; the build does, and
 * `assets/server.js` leaves the key alone when it rewrites the file.
 */
internal const val REH_PRODUCT_FILE = "vscode-reh/product.json"

/**
 * How much of a `/version` answer is read before the reader stops.
 *
 * A commit is forty characters; this is room for that and a newline several times
 * over. It is a bound rather than a size because the party answering may be the
 * one adoption is trying to rule out, and the socket's read timeout limits each
 * read rather than the total, so nothing else stops a holder that keeps sending.
 */
internal const val VERSION_BODY_MAX_CHARS = 128

/** What every device ran before the ceiling was derived, and the fallback. */
internal const val HEAP_CEILING_DEFAULT_MB = 512
internal const val HEAP_CEILING_MIN_MB = 256
internal const val HEAP_CEILING_MAX_MB = 768

/**
 * The divisor the override arm is bounded by, against the eighth the derived arm uses.
 *
 * A quarter is the largest fraction that still leaves the other isolates
 * somewhere to live. See [heapOverrideMaxMb] for the count of them, which is the
 * reason the fraction cannot be raised much further whatever the device holds.
 */
internal const val HEAP_OVERRIDE_DIVISOR = 4

/**
 * The hard stop on a user-chosen ceiling, whatever the device reports.
 *
 * NOT MEASURED, and it should not be presented as though it were. It is twice the
 * largest number any device gets today, which is the largest step that can be
 * argued for from the reasoning in [heapOverrideMaxMb] alone. Settling it needs a
 * device run that nobody has done: spawn the server with a raised ceiling on an
 * 8 GB device, drive a large workspace, and read VmHWM for the node child out of
 * `/proc`, which this app may do for its own children.
 */
internal const val HEAP_OVERRIDE_ABS_MAX_MB = 1536

/**
 * The `settings.json` key a user sets to override the derived ceiling.
 *
 * Duplicated in the bundled process-monitor extension's `contributes.configuration`,
 * which is what puts it in the workbench Settings UI. The two are held together by
 * `HeapSettingManifestParityTest`, because drifting them apart produces a setting
 * that appears in the UI and does nothing, with nothing failing anywhere.
 */
internal const val HEAP_SETTING_KEY = "vscodroid.server.heapCeilingMb"

/**
 * How many SIGKILLs a user-chosen ceiling is allowed before it is disabled.
 *
 * See [heapKillsAfter] for why a SIGKILL is the event counted and what that
 * deliberately over-counts.
 */
internal const val HEAP_OVERRIDE_KILL_BUDGET = 3

/**
 * Where the kill count and the value it was counted against are kept.
 *
 * SharedPreferences and not a field, because the thing being bounded outlives the
 * process. `NodeService.restartCount` is in-memory and resets on every
 * `onCreate`, so a counter kept there would hand a fatal value a fresh budget of
 * five on every relaunch and repeat forever, which is the exact loop this latch
 * exists to end.
 */
internal const val PREF_HEAP_KILLS = "heap_override_kills"
internal const val PREF_HEAP_VALUE_SEEN = "heap_override_value_seen"

/** The preferences file `PortFinder` and `SplashActivity` already share. */
internal const val HEAP_PREFS_NAME = "vscodroid"

/**
 * The largest ceiling a user may ask for on a device of this size.
 *
 * ```
 * heapOverrideMaxMb(T) = clamp(T / 4, 768, 1536)
 * ```
 *
 * Each bound answers a different failure, and none of the three is decorative.
 *
 * The fraction, T/4, bounds the request against the device rather than against a
 * literal. The derived arm spends an eighth because the editor server is one of
 * several processes this app is responsible for; a quarter is the most that can be
 * spent on it while the Extension Host isolate, the Pty Host isolate, the forked
 * file watcher, tsserver, the app process and the Chromium renderer still have
 * somewhere to live. Note what makes that count matter: the flag is a PER-ISOLATE
 * limit, not one heap shared between them, so a request of R authorises roughly 3R
 * of V8 old space inside the server process family before anything native is
 * counted. See [heapCeilingForDevice] for the measurement behind that.
 *
 * The absolute cap, 1536, exists because the fraction stops protecting once T is
 * large: on a 16 GiB tablet T/4 is about 3900, and three isolates of that is more
 * V8 old space than the device can hold beside the renderer. Without this bound the
 * knob would let the app destabilise the DEVICE rather than only the editor, and
 * that failure is invisible from inside the app: Android's low-memory killer works
 * from `oom_score_adj` and does not read V8 flags, so with VSCodroid in the
 * foreground the processes it reaches first belong to somebody else.
 *
 * The floor, 768, is [HEAP_CEILING_MAX_MB]: the override ceiling can never sit
 * below what the derived arm would have handed the same device. Without it, a 2 GB
 * phone would compute an override maximum of 500 and a user asking for more than
 * that would be clamped BELOW the 768 the same device could have reached by
 * setting nothing at all, which reads as the setting having broken something.
 */
internal fun heapOverrideMaxMb(totalRamMb: Long): Int =
    (totalRamMb / HEAP_OVERRIDE_DIVISOR)
        .coerceIn(HEAP_CEILING_MAX_MB.toLong(), HEAP_OVERRIDE_ABS_MAX_MB.toLong())
        .toInt()

/**
 * An eighth of RAM, held inside a band, unless the user has asked for a number.
 *
 * ```
 * heapCeilingMb(T, lowRam, R) =
 *     256                                       if lowRam
 *     512                                       if T <= 0
 *     clamp(R, 256, heapOverrideMaxMb(T))       if R is present
 *     clamp(T / 8, 256, 768)                    otherwise
 * ```
 *
 * The eighth is a budget rather than a measurement: the editor server is one of
 * several processes this app is responsible for, and the flag governs only the
 * V8 heap inside one of them. The floor exists because below it the editor
 * cannot open a real project at all, so a device that cannot afford the floor is
 * going to struggle whatever number is chosen. The ceiling exists because past
 * it the limit stops being what ends the process -- Android's low-memory killer
 * does, and it does not read flags.
 *
 * A device the manufacturer flagged as low-RAM gets the floor whatever its
 * total says, because that flag is the OEM stating the device is constrained in
 * ways totalMem does not show.
 *
 * THE ORDER OF THE FOUR ARMS IS THE SAFETY ARGUMENT, so it is written down rather
 * than left to the reader. Both short-circuits sit ABOVE the override on purpose.
 * The low-RAM flag is the manufacturer stating that totalMem overstates what this
 * device can spare, and a user cannot know better than the OEM about their own
 * hardware, so their number is not consulted. The unreadable-total case is above it
 * for a different reason: the override's whole protection is a clamp computed from
 * T, so with no T there is nothing to clamp a request against, and honouring one
 * there would be honouring it unbounded.
 *
 * The floor applies to a request as well, and in the RAISING direction. Below 256
 * the editor cannot open a real project, which is the existing reason for the
 * floor; a user who asks for less gains nothing and loses the editor.
 *
 * A request may lower as well as raise. That is deliberate and is worth stating,
 * because the obvious defensive move -- refusing to go below the derived value --
 * would take away the only thing a user on a struggling device can do from here.
 */
internal fun heapCeilingMb(totalRamMb: Long, isLowRam: Boolean, requestedMb: Int? = null): Int {
    if (heapOverrideHonoured(totalRamMb, isLowRam, requestedMb)) {
        return requestedMb!!.coerceIn(HEAP_CEILING_MIN_MB, heapOverrideMaxMb(totalRamMb))
    }
    if (isLowRam) return HEAP_CEILING_MIN_MB
    // Guard the unreadable case rather than trusting it: totalMem has been seen
    // to report 0 on emulators, and 0/8 would silently become the floor while
    // looking like a considered decision.
    if (totalRamMb <= 0) return HEAP_CEILING_DEFAULT_MB
    return (totalRamMb / 8).coerceIn(
        HEAP_CEILING_MIN_MB.toLong(), HEAP_CEILING_MAX_MB.toLong()
    ).toInt()
}

/**
 * Whether a request is the arm [heapCeilingMb] will actually take.
 *
 * Split out rather than left implicit in the ordering of [heapCeilingMb]'s arms,
 * because two callers need the same answer and a second copy of the condition is
 * a copy that can drift.
 *
 * The second caller is the kill latch, and the drift would be silent in the worst
 * direction. `heapOverrideActive` records whether the running server was given the
 * user's number, and it is what decides whose budget a `SIGKILL` spends. Answering
 * it with "a request was present" rather than "a request was taken" charges a
 * low-RAM device's kills, and an unreadable-total device's kills, to a value that
 * those devices never ran with, and three of those disable a setting the user
 * never actually got to try.
 */
internal fun heapOverrideHonoured(totalRamMb: Long, isLowRam: Boolean, requestedMb: Int?): Boolean =
    requestedMb != null && !isLowRam && totalRamMb > 0

/**
 * The requested ceiling, read out of a `settings.json` document, or null.
 *
 * The `(?m)^\s*` anchor is load-bearing rather than tidiness. settings.json is
 * JSONC and the documents this app writes carry comments; without the anchor a
 * commented-out `// "vscodroid.server.heapCeilingMb": 8192` sitting in the user's
 * file as an example would be honoured as though they had set it, and nothing
 * would look wrong until the device started dying. This repository has been bitten
 * by exactly that blindness before.
 *
 * A regex over the text rather than a parse, for the reason
 * `FirstRunSetup.refreshManagedPaths` documents at length: parsing the document to
 * re-serialise it would strip the user's comments, escape every slash and turn
 * `["-i",]` into `["-i", null]`. This one only reads, so it does not even risk
 * that -- but the same reasoning says a JSON parser has no business being pointed
 * at a document it cannot faithfully reproduce.
 *
 * `\d+` and not something looser, so a quoted `"1024"` reads as absent rather than
 * as a number. A setting written with the wrong type is a mistake, and falling back
 * to the derived value is the safe reading of one.
 */
internal fun heapOverrideFromSettings(content: String): Int? =
    HEAP_SETTING_PATTERN.find(content)?.groupValues?.get(1)?.toIntOrNull()

private val HEAP_SETTING_PATTERN =
    Regex("""(?m)^\s*"${Regex.escape(HEAP_SETTING_KEY)}"\s*:\s*(\d+)""")

/**
 * Whether a user-chosen ceiling has spent its budget and must be ignored.
 *
 * A separate counter from `NodeService.restartCount` and not folded into it: that
 * one is a per-run restart budget with its own documented boundary, and reusing it
 * would change what every crash cause means, not only this one.
 */
internal fun heapOverrideSuspended(kills: Int, budget: Int = HEAP_OVERRIDE_KILL_BUDGET) =
    kills >= budget

/**
 * The kill count that applies to [currentValue], given what was last recorded.
 *
 * Changing the number is a fresh decision by the user and gets a fresh budget.
 * Without this the count would be permanent: a value disabled after three kills
 * could never be re-enabled by lowering it, because the counter it is judged
 * against would still be full, and the only way out would be clearing app data --
 * which destroys `filesDir`, and with it the user's projects, their installed
 * toolchains and their extensions.
 */
internal fun heapKillsForValue(storedValue: Int, storedKills: Int, currentValue: Int) =
    if (storedValue == currentValue) storedKills else 0

/**
 * The kill count after an exit, counting only what a too-high ceiling produces.
 *
 * 137 is `128 + SIGKILL`, which `assets/server.js` produces from its child's signal
 * and the watchdog already names. It is the right event to count and a deliberate
 * over-count, and both halves need saying.
 *
 * Right, because a ceiling set higher than the device can hold does not fail at
 * START. V8 reserves virtual address space rather than committing it, so a node
 * spawned with `--max-old-space-size=4096` on a 4 GB phone comes up normally; the
 * failure is deferred and load-dependent, and arrives as the low-memory killer.
 * A latch that asked "did the last start survive" would therefore never fire.
 *
 * Over-counting, because the phantom-process limit produces the same 137 and the
 * app cannot tell the two apart. Nothing here reads exit reasons or memory state,
 * and both arrive through the same path. The direction is chosen rather than
 * conceded: a false positive costs the user one notification and a setting to
 * re-enter, while a false negative is an app that crash-loops across every relaunch
 * with no way back in, because `restartCount` resets each launch and the terminal
 * state therefore does not stick.
 *
 * What is deliberately NOT counted is exit 134, `128 + SIGABRT`, which is what V8's
 * own heap-limit abort produces along with a `FATAL ERROR: JavaScript heap out of
 * memory` line that `startOutputReader` puts into `server.log`. That is the ceiling
 * working as intended, and disabling a value for doing its job would be backwards.
 * `ADOPTED_SERVER_LOST` is not counted for a plainer reason: an adopted server never
 * ran with this value at all.
 */
internal fun heapKillsAfter(exitCode: Int, overrideInEffect: Boolean, current: Int) =
    if (overrideInEffect && exitCode == HEAP_OVERRIDE_FATAL_EXIT) current + 1 else current

/** `128 + SIGKILL`, as `assets/server.js` reports a signalled child. */
internal const val HEAP_OVERRIDE_FATAL_EXIT = 137
