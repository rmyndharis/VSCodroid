package com.vscodroid.util

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared helper for instrumented tests that need the VS Code server to be running.
 *
 * Provides:
 * - [waitForServerPort] — polls logcat for the server-ready message and returns the port.
 * - [markSetupComplete] — writes the setup_version pref so SplashActivity skips extraction.
 * - [healthCheck] — verifies the server responds to HTTP on the given port.
 */
object ServerReadyHelper {

    // FirstRunSetup uses "vscodroid_setup" for setup_version
    private const val SETUP_PREFS = "vscodroid_setup"
    // SplashActivity uses "vscodroid" for toolchain_picker_shown
    private const val APP_PREFS = "vscodroid"
    private const val KEY_SETUP_VERSION = "setup_version"
    private const val KEY_PICKER_SHOWN = "toolchain_picker_shown"

    /**
     * Pre-populates SharedPreferences so [com.vscodroid.SplashActivity] considers
     * first-run setup already done and jumps straight to [com.vscodroid.MainActivity].
     *
     * Writes to both prefs files to match how the production code uses them:
     * - `vscodroid_setup` for `setup_version` (read by [com.vscodroid.setup.FirstRunSetup])
     * - `vscodroid` for `toolchain_picker_shown` (read by [com.vscodroid.SplashActivity])
     *
     * Call this in a @Before method for tests that only need MainActivity.
     */
    fun markSetupComplete(context: Context) {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            "test"
        }
        context.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SETUP_VERSION, versionName)
            .commit()
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PICKER_SHOWN, true)
            .commit()
    }

    /**
     * Clears the setup_version pref so the next SplashActivity launch runs
     * first-run extraction. Useful for SplashActivity tests.
     */
    fun clearSetupState(context: Context) {
        context.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SETUP_VERSION)
            .commit()
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PICKER_SHOWN)
            .commit()
    }

    /**
     * Polls the server's HTTP endpoint until it responds with a 200-499 status code.
     *
     * @param port Server port to check.
     * @param timeoutMs Maximum time to wait.
     * @return `true` if the server responded within the timeout.
     */
    fun healthCheck(port: Int, timeoutMs: Long = 60_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..499) return true
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
            try {
                java.net.Socket("127.0.0.1", port).use { return true }
            } catch (_: Exception) {
                // Not yet
            }
            Thread.sleep(500)
        }
        return false
    }
}
