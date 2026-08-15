package com.vscodroid.service

import android.app.ActivityManager
import android.content.Context
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.util.PortFinder
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
     * from `serverProcess` has to consult this too — the start guard, and
     * [stopServer], which cannot kill what it did not spawn and says so instead
     * of reporting success.
     */
    @Volatile
    private var adopted = false

    /** The port the server is listening on. Only valid after [startServer] returns true. */
    val port: Int get() = _port

    /**
     * Whether the server on the port was adopted rather than started here.
     *
     * Exposed so callers can distinguish "we have a server" from "we control the
     * server", which are the same question only while we started it.
     */
    fun isAdopted(): Boolean = adopted

    /**
     * The connection token the server requires on every route except `/version`,
     * `/delay-shutdown` and `/callback`.
     *
     * The server owns this value, not us: with no connection-token flag on its
     * command line it reads the file [Environment.getConnectionTokenPath] names,
     * generates one if it is absent, and writes it back with mode 0600. Reading
     * that file is therefore the only way to learn it, and it is only there once
     * the server has started — which is why this is read on demand rather than
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
     * `onServerReady` sat here too — a name [waitForReady]'s return value already
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
        // Across cold starts it is the workbench's IndexedDB that is bound to it —
        // see PortFinder.getOrAllocatePort.
        if (_port == 0) {
            _port = PortFinder.getOrAllocatePort(context)
        } else if (!PortFinder.isPortAvailable(_port) && portHolderAcceptsOurToken()) {
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
            // can hold a loopback port on Android; only our own processes can read
            // the token file that [portHolderAcceptsOurToken] presents. A holder
            // that refuses it falls through and is spawned over, which fails the
            // way it always did rather than the way a refusal would.
            Logger.i(tag, "Port $_port already served by a server of ours; adopting it")
            adopted = true
            _isReady = false
            isShuttingDown = false
            startAdoptionWatch()
            return true
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
        // happen. `startOutputReader` receives that EADDRINUSE line, and
        // [onServerOutput] is a seam it is forwarded to -- with no production
        // consumer, so the line reaches `Logger.d` and stops, which in a release
        // build is nowhere at all.
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
        Logger.i(tag, "Starting server on port $_port")

        // Ensure TMPDIR is a usable directory — Android may clear cache between
        // launches.
        //
        // `isDirectory` rather than `exists`, and the return value of `mkdirs` is
        // read rather than discarded. Both halves are the same mistake: the test
        // asked whether *something* was at that path while the code meant whether
        // a *directory* was, and they part company exactly when a file sits there
        // — at which point `mkdirs` cannot succeed and said so to nobody. This
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
            Logger.i(tag, "Server process started with PID ${getServerPid()}, heap ceiling ${heapMb}MB")
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
     * Performs a synchronous HTTP GET to `http://127.0.0.1:{port}/version`.
     *
     * `/version` is answered before the connection-token check — the server
     * handles it and returns, then gates everything else — so this stays a pure
     * liveness probe and does not need the token.
     *
     * It also has to be `/version` rather than `/`. This accepted anything below
     * 500, which was fine while every route answered 200, and became wrong the
     * moment the server started requiring a token: `/` then answers 403, and a
     * readiness check that counts 403 as healthy reports a successful startup for
     * a server that will serve the user nothing but Forbidden.
     */
    /**
     * Whether whatever is listening on our port accepts our connection token.
     *
     * The ownership question, and it is answerable only because the server
     * requires a token at all. That token is generated by the server into
     * `<server-data-dir>/data/token` with mode 0600 inside app-private storage,
     * so a process that accepts it either is one of ours or has read a file
     * nothing outside this app can.
     *
     * A DIFFERENT REQUEST FROM THE READINESS PROBE, deliberately, and the
     * distinction is load-bearing rather than stylistic. [isServerHealthy] asks
     * `/version`, which the server answers BEFORE the token gate — that is what
     * keeps it a pure liveness probe and it must stay that way. This asks `/`,
     * which is behind the gate, and carries the token. Same server, different
     * question, different moment.
     *
     * Judged by what it is NOT rather than by a single expected code. A server
     * that rejects the token answers 403; one that accepts it answers 200, or
     * redirects while it turns the token into the `vscode-tkn` cookie, and
     * pinning either would break on the other. Anything that is not a refusal
     * counts as acceptance, and an unreachable port counts as no.
     */
    fun portHolderAcceptsOurToken(): Boolean {
        val token = connectionToken
        if (token.isNullOrEmpty()) {
            Logger.w(tag, "No connection token; cannot tell whose server holds port $_port")
            return false
        }
        return try {
            val encoded = URLEncoder.encode(token, "UTF-8")
            val url = URL("http://127.0.0.1:$_port/?tkn=$encoded")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode != HTTP_FORBIDDEN
        } catch (e: Exception) {
            false
        }
    }

    fun isServerHealthy(): Boolean {
        return try {
            val url = URL("http://127.0.0.1:$_port/version")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Stops the server process.
     *
     * Attempts a graceful shutdown via [Process.destroy]. If the process does not
     * exit, falls back to [Process.destroyForcibly]. Sets [isShuttingDown] to
     * suppress watchdog crash callbacks.
     */
    /**
     * Stops the server, waiting briefly for it to go before killing it.
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
        if (adopted) {
            // Said plainly rather than passed over. This class did not spawn the
            // server on the port and holds no handle to it, so there is nothing
            // here that can end it — and the honest version of that is a log line
            // saying so, not a silent return that leaves the caller believing the
            // stop succeeded. Before adoption existed this case still arrived, and
            // it was worse: `serverProcess` referenced a process that never served
            // anything, so the stop destroyed the wrong one and reported success.
            Logger.w(
                tag,
                "The server on port $_port was adopted, not started here, so stopping " +
                    "this service cannot end it. It keeps running until the system " +
                    "reclaims it.",
            )
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
     */
    private fun heapCeilingForDevice(): Int = try {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        heapCeilingMb(info.totalMem / 1_048_576, am.isLowRamDevice)
    } catch (e: Exception) {
        // Reading it is not worth failing a start over, and the old literal is
        // the right thing to fall back to: it is what every device ran until now.
        Logger.w(tag, "Could not read device memory, using the default ceiling: ${e.message}")
        HEAP_CEILING_DEFAULT_MB
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
                // returns null — do not assert on it from a JVM unit test.
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
     * every start — the process is spawned in milliseconds and the editor server
     * it forks takes seconds to bind its port — and every restart after a crash.
     * A caller that navigates a WebView on `isRunning` therefore points it at a
     * port with nothing listening, and gets a connection-refused page.
     *
     * The answer costs nothing to read, which is the other half of why this
     * exists. [isServerHealthy] is the real probe and it blocks on HTTP, so it
     * cannot be called from the main thread — that constraint is what pushed the
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
     * rather than anything the app waits on — before this flag existed the same
     * caller was wrong for the whole of a restart, so the window shrank by
     * several orders of magnitude — but it did not close, and a caller that must
     * not be wrong even briefly wants [probeReadiness] instead.
     */
    fun isReady(): Boolean = _isReady

    /**
     * Asks the server once, and records the answer if it is yes.
     *
     * Blocking — it is [isServerHealthy] underneath, so it belongs off the main
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
     * bounded loop, that second later was unreachable — the flag stayed false for
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
     * Starts a daemon thread that waits for the server process to exit.
     *
     * If [isShuttingDown] is `true`, the exit is expected and no callback fires.
     * Otherwise, [onServerCrashed] is invoked with the exit code.
     *
     * Exit code interpretation:
     * - 0: clean exit
     * - 137 (SIGKILL): typically OOM killer or phantom process limit
     * - other: unexpected crash
     */
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
     * connection is not evidence of death — the same reasoning [probeReadiness]
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
 * Slower than a readiness poll because nothing is waiting on the answer — this
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

/** The refusal an unauthenticated request gets from the editor server. */
internal const val HTTP_FORBIDDEN = 403

/** What every device ran before the ceiling was derived, and the fallback. */
internal const val HEAP_CEILING_DEFAULT_MB = 512
internal const val HEAP_CEILING_MIN_MB = 256
internal const val HEAP_CEILING_MAX_MB = 768

/**
 * An eighth of RAM, held inside a band.
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
 */
internal fun heapCeilingMb(totalRamMb: Long, isLowRam: Boolean): Int {
    if (isLowRam) return HEAP_CEILING_MIN_MB
    // Guard the unreadable case rather than trusting it: totalMem has been seen
    // to report 0 on emulators, and 0/8 would silently become the floor while
    // looking like a considered decision.
    if (totalRamMb <= 0) return HEAP_CEILING_DEFAULT_MB
    return (totalRamMb / 8).coerceIn(
        HEAP_CEILING_MIN_MB.toLong(), HEAP_CEILING_MAX_MB.toLong()
    ).toInt()
}
