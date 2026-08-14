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

    /** The port the server is listening on. Only valid after [startServer] returns true. */
    val port: Int get() = _port

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
        if (isRunning()) {
            Logger.w(tag, "Server already running")
            return false
        }

        isShuttingDown = false
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
        }
        Logger.i(tag, "Starting server on port $_port")

        // Ensure TMPDIR exists — Android may clear cache between launches
        val tmpDir = File(context.cacheDir, "tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()

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
    suspend fun waitForReady(timeoutMs: Long = 30_000, pollIntervalMs: Long = 200): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isServerHealthy()) {
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
    private fun startWatchdog() {
        watchdogThread = thread(name = "node-watchdog", isDaemon = true) {
            try {
                val exitCode = serverProcess?.waitFor() ?: return@thread
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
