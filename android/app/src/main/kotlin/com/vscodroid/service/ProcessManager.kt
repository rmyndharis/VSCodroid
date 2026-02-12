package com.vscodroid.service

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
 * - Starting and stopping the Node.js code-server process
 * - Health-checking the server via /healthz endpoint
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

    // -- Callbacks --

    /** Invoked on the caller's coroutine when the server responds to a health check. */
    var onServerReady: (() -> Unit)? = null

    /** Invoked on the watchdog thread when the server process exits unexpectedly. */
    var onServerCrashed: ((exitCode: Int) -> Unit)? = null

    /** Invoked on the watchdog thread before an automatic restart attempt. */
    var onServerRestarting: (() -> Unit)? = null

    /** Invoked on the output-reader thread for every line of server stdout/stderr. */
    var onServerOutput: ((line: String) -> Unit)? = null

    // -- Lifecycle --

    /**
     * Starts the Node.js code-server process.
     *
     * Allocates an available port, builds the command line from [Environment] paths,
     * spawns the process, and begins output reading and watchdog monitoring.
     *
     * @return `true` if the process was spawned successfully, `false` on error or
     *         if a process is already running.
     */
    fun startServer(): Boolean {
        if (serverProcess != null) {
            Logger.w(tag, "Server already running")
            return false
        }

        isShuttingDown = false
        _port = PortFinder.findAvailablePort()
        Logger.i(tag, "Starting server on port $_port")

        // Ensure TMPDIR exists — Android may clear cache between launches
        val tmpDir = File(context.cacheDir, "tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()

        val nodePath = Environment.getNodePath(context)
        val serverScript = Environment.getServerScript(context)
        val env = Environment.buildProcessEnvironment(context, _port)

        val command = listOf(
            nodePath,
            "--max-old-space-size=512",
            serverScript,
            "--host=127.0.0.1",
            "--port=$_port",
            "--without-connection-token",
            "--extensions-dir=${Environment.getExtensionsDir(context)}",
            "--user-data-dir=${Environment.getUserDataDir(context)}",
            "--server-data-dir=${Environment.getUserDataDir(context)}",
            "--logsPath=${Environment.getLogsDir(context)}",
            "--accept-server-license-terms",
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
            Logger.i(tag, "Server process started with PID ${getServerPid()}")
            true
        } catch (e: Exception) {
            Logger.e(tag, "Failed to start server", e)
            false
        }
    }

    /**
     * Suspends until the server responds to a health check or the timeout elapses.
     *
     * Polls [isServerHealthy] at [pollIntervalMs] intervals. On success, invokes
     * [onServerReady] and returns `true`. On timeout, returns `false`.
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
                onServerReady?.invoke()
                return true
            }
            delay(pollIntervalMs)
        }
        Logger.e(tag, "Server failed to become ready within ${timeoutMs}ms")
        return false
    }

    /**
     * Performs a synchronous HTTP GET to `http://127.0.0.1:{port}/`.
     *
     * Accepts any non-server-error response (< 500) as healthy.
     * VS Code Server returns 200 for the web UI; our fallback server
     * returns 200 for /healthz. Both paths are covered.
     */
    fun isServerHealthy(): Boolean {
        return try {
            val url = URL("http://127.0.0.1:$_port/")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..499
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
    fun stopServer() {
        isShuttingDown = true
        Logger.i(tag, "Stopping server...")
        serverProcess?.let { process ->
            try {
                process.destroy()
                val exited = process.waitFor(5, TimeUnit.SECONDS)
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
                when (exitCode) {
                    0 -> Logger.i(tag, "Server exited cleanly")
                    137 -> Logger.w(tag, "Server killed (OOM or phantom limit)")
                    else -> Logger.e(tag, "Server crashed with exit code $exitCode")
                }
                onServerCrashed?.invoke(exitCode)
            } catch (e: InterruptedException) {
                Logger.d(tag, "Watchdog interrupted")
            }
        }
    }
}
