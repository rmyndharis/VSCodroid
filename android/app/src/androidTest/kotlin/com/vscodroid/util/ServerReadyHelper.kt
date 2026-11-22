package com.vscodroid.util

import android.content.Context
import com.vscodroid.service.EditorGateway
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared helper for instrumented tests that need the VS Code server to be running.
 *
 * Provides:
 * - [markSetupComplete] writes the setup version and package timestamp prefs so
 *   SplashActivity skips extraction.
 * - [clearSetupState] removes them again, plus the toolchain picker flag.
 * - [healthCheck] asks the server for /version on a port and says whether it answered.
 * - [version] says what /version answered on a port, or null.
 * - [waitForPort] polls that same check until the port answers or the timeout runs out.
 * - [waitForServer] does the same on the port the app's editor gateway holds, and says
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
    private const val KEY_SETUP_PACKAGE_UPDATE_TIME = "setup_package_update_time"
    private const val KEY_PICKER_SHOWN = "toolchain_picker_shown"

    /**
     * Pre-populates SharedPreferences so [com.vscodroid.SplashActivity] considers
     * first-run setup already done and jumps straight to [com.vscodroid.MainActivity].
     *
     * Writes to both prefs files to match how the production code uses them:
     * - `vscodroid_setup` for the version and package timestamp identity
     *   (read by [com.vscodroid.setup.FirstRunSetup])
     * - `vscodroid` for `toolchain_picker_shown` (read by [com.vscodroid.SplashActivity])
     *
     * All identity fields match a completed setup so this helper also skips the
     * one-time timestamp initialization required for legacy completion records.
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
            .putLong(KEY_SETUP_PACKAGE_UPDATE_TIME, info?.lastUpdateTime ?: 0)
            .commit()
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PICKER_SHOWN, true)
            .commit()
    }

    /**
     * Clears the setup version and package timestamp prefs so the next
     * SplashActivity launch runs first-run extraction. Useful for SplashActivity
     * tests.
     *
     * Clear the entire identity so later tests cannot inherit a partial record.
     */
    fun clearSetupState(context: Context) {
        context.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SETUP_VERSION)
            .remove(KEY_SETUP_VERSION_CODE)
            .remove(KEY_SETUP_PACKAGE_UPDATE_TIME)
            .commit()
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PICKER_SHOWN)
            .commit()
    }

    /**
     * Polls the server's `/version` endpoint until it responds with 200.
     *
     * `/version` is answered before the connection-token check, so
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
     * What `GET /version` answers on [port], trimmed, or null unless it answered 200.
     *
     * The server answers with the commit its tree was built from, which is also what
     * `vscode-reh/product.json` records: `server.js` rewrites that file on every start,
     * and the commit is not among what it overrides.
     */
    fun version(port: Int): String? = try {
        val conn = URL("http://127.0.0.1:$port/version").openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText().trim() } else null
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
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
     * The port is the one the app's [EditorGateway] has bound, read from the
     * gateway itself. The service runs in the app's own process, which is the
     * process this test runs in, so [EditorGateway.instance] is the very listener
     * `startServer` bound. [PortFinder]'s record is not enough on its own: it holds
     * the install's home port, and a start that finds home busy binds another
     * port for that process only and never records it, so a probe of the record
     * sent every case red on a device where something held home, while the server
     * was healthy on the port the app moved to. The record is read only until the
     * gateway is bound, which on a clean install is after the activity the caller
     * has just launched reaches `startServer`; it is read on every pass for that
     * reason.
     *
     * Each pass demands `GET /version` answering 200 rather than a bare connect:
     * before the gateway is bound the record may name a port a stranger holds,
     * which accepts a connect but does not answer `/version` with 200, and once it
     * is bound the gateway accepts connections before a backend is selected and
     * answers 503 until one is.
     */
    fun waitForServer(context: Context, timeoutMs: Long = 60_000L): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val port = EditorGateway.instance.boundPort ?: PortFinder.rememberedPort(context)
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
