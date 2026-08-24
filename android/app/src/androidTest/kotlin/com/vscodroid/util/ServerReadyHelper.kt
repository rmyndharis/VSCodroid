package com.vscodroid.util

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared helper for instrumented tests that need the VS Code server to be running.
 *
 * Provides:
 * - [markSetupComplete] writes the setup_version and setup_version_code prefs so
 *   SplashActivity skips extraction.
 * - [clearSetupState] removes them again, plus the toolchain picker flag.
 * - [healthCheck] asks the server for /version on a port and says whether it answered.
 * - [waitForPort] polls that same check until the port answers or the timeout runs out.
 * - [waitForServer] does the same on whichever port `PortFinder` recorded, and says
 *   which one it was.
 */
object ServerReadyHelper {

    // FirstRunSetup uses "vscodroid_setup" for setup_version
    private const val SETUP_PREFS = "vscodroid_setup"
    // SplashActivity uses "vscodroid" for toolchain_picker_shown
    private const val APP_PREFS = "vscodroid"
    private const val KEY_SETUP_VERSION = "setup_version"

    /**
     * The other half of the staleness test, and the half that was missing.
     *
     * `FirstRunSetup.isFirstRun` asks `setupIsStale(storedName, storedCode, ...)`,
     * which is an OR: either one differing re-runs the whole of setup. Writing
     * only the name left the code at its default 0 against a real versionCode, so
     * on any install where setup had never actually completed, this helper could
     * not make `isFirstRun()` false and every test that called it to skip
     * extraction ran against a false premise. On a device that had completed setup
     * once it appeared to work, because the value it did not write was already
     * right, which is why it went unnoticed: `adb shell pm clear` is what parts the
     * two, and that is exactly what a clean instrumented run starts from.
     */
    private const val KEY_SETUP_VERSION_CODE = "setup_version_code"
    private const val KEY_PICKER_SHOWN = "toolchain_picker_shown"

    /**
     * Pre-populates SharedPreferences so [com.vscodroid.SplashActivity] considers
     * first-run setup already done and jumps straight to [com.vscodroid.MainActivity].
     *
     * Writes to both prefs files to match how the production code uses them:
     * - `vscodroid_setup` for `setup_version` and `setup_version_code`
     *   (both read by [com.vscodroid.setup.FirstRunSetup])
     * - `vscodroid` for `toolchain_picker_shown` (read by [com.vscodroid.SplashActivity])
     *
     * Both version keys, because the production test is an OR over the pair. See
     * [KEY_SETUP_VERSION_CODE].
     *
     * Call this in a @Before method for tests that only need MainActivity.
     */
    fun markSetupComplete(context: Context) {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
        context.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SETUP_VERSION, info?.versionName ?: "test")
            // Read back with getInt, and narrowed the same way FirstRunSetup
            // narrows it, so the two comparands are the same number.
            .putInt(KEY_SETUP_VERSION_CODE, info?.longVersionCode?.toInt() ?: 0)
            .commit()
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PICKER_SHOWN, true)
            .commit()
    }

    /**
     * Clears the setup_version and setup_version_code prefs so the next
     * SplashActivity launch runs first-run extraction. Useful for SplashActivity
     * tests.
     *
     * Removing only one of the pair would still force extraction, because the
     * staleness test is an OR, but it would leave the other behind for the next
     * test in the same run to read.
     */
    fun clearSetupState(context: Context) {
        context.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SETUP_VERSION)
            .remove(KEY_SETUP_VERSION_CODE)
            .commit()
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PICKER_SHOWN)
            .commit()
    }

    /**
     * Polls the server's `/version` endpoint until it responds with 200.
     *
     * `/version` is answered before the connection-token check -- as are
     * `/delay-shutdown` and `/callback` -- so
     * it stays a pure liveness probe. Probing `/` instead would report a healthy
     * server on the strength of a 403.
     *
     * @param port Server port to check.
     * @param timeoutMs Maximum time to wait.
     * @return `true` if the server responded within the timeout.
     */
    fun healthCheck(port: Int, timeoutMs: Long = 60_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = URL("http://127.0.0.1:$port/version").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) return true
            } catch (_: Exception) {
                // Server not ready yet
            }
            Thread.sleep(1000)
        }
        return false
    }

    /**
     * Waits for a port to become connectable (TCP connect succeeds).
     *
     * @param port TCP port to probe.
     * @param timeoutMs Maximum time to wait.
     * @return `true` if a connection was established within the timeout.
     */
    fun waitForPort(port: Int, timeoutMs: Long = 60_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (connects(port)) return true
            Thread.sleep(500)
        }
        return false
    }

    /**
     * Waits for the server this install serves on to accept a connection, and
     * says which port that was; 0 when the deadline passed first.
     *
     * The port is read from where the app records it, not assumed. The app scans
     * up from 13337 and remembers its choice through [PortFinder], moving whenever
     * something else holds the remembered port, so a probe of the literal turned
     * every device where 13337 had ever been taken into red cases that said
     * nothing about the server. It is read on every pass rather than once: on a
     * clean install the record is written by `startServer`, which the activity
     * the caller has just launched is still on its way to.
     *
     * Each pass demands `GET /version` answering 200 rather than a bare connect.
     * The record is rewritten only inside `startServer`, after the activity the
     * caller launched has bound the service, so on the first passes it still
     * names the port the previous run used; a stranger holding that port
     * accepts a connect, and a helper satisfied by one returned before the app
     * had moved, sending the probe cases red on the stranger's port while the
     * server was healthy on the new one. A stranger does not answer `/version`
     * with 200, so the loop keeps reading the record until the app has moved.
     */
    fun waitForServer(context: Context, timeoutMs: Long = 60_000L): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val port = PortFinder.rememberedPort(context)
            if (port != 0 && healthCheck(port, timeoutMs = 1_000L)) return port
            Thread.sleep(500)
        }
        return 0
    }

    private fun connects(port: Int): Boolean = try {
        java.net.Socket("127.0.0.1", port).use { true }
    } catch (_: Exception) {
        false
    }
}
