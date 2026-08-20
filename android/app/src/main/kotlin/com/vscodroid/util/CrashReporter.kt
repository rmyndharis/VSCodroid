package com.vscodroid.util

import android.content.Context
import com.vscodroid.webview.redactToken
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions and writes them to a local crash log.
 *
 * Privacy-respecting: no data leaves the device. Crash logs are stored in
 * the app's private cache directory and can be included in user-initiated
 * bug reports via [generateBugReport].
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val CRASH_DIR = "crash-logs"
    private const val MAX_LOGS = 10
    private lateinit var crashDir: File
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        crashDir = File(context.cacheDir, CRASH_DIR)
        crashDir.mkdirs()

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrashLog(thread, throwable)
            // Chain to the default handler (usually Android's kill-the-process handler)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Clean old logs on init
        pruneOldLogs()
    }

    /**
     * Returns the most recent crash log, or null if no crashes have been recorded.
     *
     * The text goes through [redactToken] on the way out. This is the boundary
     * the log crosses: it reaches the dialog `MainActivity.checkPreviousCrash`
     * puts on screen and the `AndroidBridge.getLastCrash` method the page can
     * call, and redacting here covers both without either caller having to
     * remember. `MainActivity` cuts its preview at 500 characters after this
     * returns, so the substitution shifts where that cut lands.
     */
    fun getLastCrash(): String? {
        if (!::crashDir.isInitialized) return null
        return crashDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()
            ?.let { redactToken(it.readText()) }
    }

    /**
     * Returns true if there are unread crash logs from a previous session.
     */
    fun hasPendingCrash(): Boolean {
        if (!::crashDir.isInitialized) return false
        return (crashDir.listFiles()?.size ?: 0) > 0
    }

    /**
     * Generates a bug report bundle containing:
     * - Device info (model, Android version, app version)
     * - Memory usage
     * - How many crash logs exist, plus the text of the three most recent
     *
     * Not the server's output: the block below reads a `server.log` that
     * nothing writes, so that section never appears. `docs/05-API_SPEC.md`
     * and the logging table in `docs/03-ARCHITECTURE.md` say the same.
     *
     * The report is copied to the clipboard and handed back across the JS
     * bridge, so everything read off disk goes through [redactToken] first: the
     * server's connection token authenticates every route but `/version`,
     * `/delay-shutdown` and `/callback`. The containment stops where that
     * function's does, at the literal `tkn=` parameter, so a bare token in no
     * parameter at all still passes through.
     */
    fun generateBugReport(context: Context): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)

        sb.appendLine("=== VSCodroid Bug Report ===")
        sb.appendLine("Generated: ${dateFormat.format(Date())}")
        sb.appendLine()

        // Device info
        sb.appendLine("--- Device Info ---")
        sb.appendLine("Model: ${android.os.Build.MODEL}")
        sb.appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            sb.appendLine("App: ${pkgInfo.versionName} (${pkgInfo.longVersionCode})")
        } catch (_: Exception) {}
        sb.appendLine("Package: ${context.packageName}")
        sb.appendLine()

        // Runtime info
        val rt = Runtime.getRuntime()
        sb.appendLine("--- Memory ---")
        sb.appendLine("Max heap: ${rt.maxMemory() / 1_048_576} MB")
        sb.appendLine("Used: ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB")
        sb.appendLine()

        // Crash logs
        if (::crashDir.isInitialized) {
            val logs = crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (logs.isNotEmpty()) {
                sb.appendLine("--- Crash Logs (${logs.size}) ---")
                for (log in logs.take(3)) {
                    sb.appendLine()
                    sb.appendLine(redactToken(log.readText()))
                    sb.appendLine("---")
                }
            } else {
                sb.appendLine("--- No crash logs ---")
            }
        }
        sb.appendLine()

        // Node.js server log (last 200 lines). Nothing in this repository writes
        // that file, so the block never fires today; it is redacted with the
        // rest so it is not the one raw path left if something starts to.
        val serverLog = File(Environment.getLogsDir(context), "server.log")
        if (serverLog.exists()) {
            sb.appendLine("--- Server Log (last 200 lines) ---")
            val lines = serverLog.readLines()
            val tail = if (lines.size > 200) lines.takeLast(200) else lines
            tail.forEach { sb.appendLine(redactToken(it)) }
        }

        return sb.toString()
    }

    /**
     * Clears all stored crash logs. Called after user views or exports them.
     */
    fun clearCrashLogs() {
        if (!::crashDir.isInitialized) return
        crashDir.listFiles()?.forEach { it.delete() }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        try {
            if (!::crashDir.isInitialized) return
            crashDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(crashDir, "crash_$timestamp.txt")

            val sw = StringWriter()
            sw.appendLine("Crash at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            sw.appendLine(threadIdentity(thread))
            sw.appendLine("Device: ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})")
            sw.appendLine()
            throwable.printStackTrace(PrintWriter(sw))

            file.writeText(sw.toString())
            Logger.e(TAG, "Crash log written: ${file.name}")
        } catch (_: Throwable) {
            // Last-resort: can't even write the crash log (including OOM)
        }
    }

    private fun pruneOldLogs() {
        try {
            val logs = crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            if (logs.size > MAX_LOGS) {
                logs.drop(MAX_LOGS).forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }
}

/**
 * The crashing thread, named and numbered, using the accessor every supported
 * API level has.
 *
 * `Thread.threadId()` is API 36 and `minSdk` is 33, and nothing backports it
 * here — `coreLibraryDesugaring` is off, and `abortOnError = false` means lint
 * cannot stop a build over it either. On 33, 34 and 35 the call therefore raises
 * `NoSuchMethodError`, and it sat above `file.writeText` inside a
 * `catch (_: Throwable)`: the write never happened, `hasPendingCrash()` stayed
 * false, the dialog in `MainActivity.checkPreviousCrash` never appeared, and the
 * crash section of `generateBugReport` was permanently empty. A crash reporter
 * that reports nothing is worse than none, because the empty report reads as
 * "no crashes".
 *
 * `Thread.getId()` has been there since API 1 and is not deprecated in the
 * android-36 stub, so this needs no suppression and no desugaring. Verified by
 * dumping `java/lang/Thread.class` out of `platforms/android-36/android.jar`:
 * `getId()` carries no deprecation attribute, and `threadId()` is absent from
 * the android-33 jar entirely.
 *
 * ⚠️ The Kotlin compiler warns here anyway, "'val id: Long' is deprecated.
 * Deprecated in Java", and that warning is about the JDK's own `Thread`, not
 * about the platform this ships against. Do not silence it by switching to
 * `threadId()`. That is the exact call this function exists to avoid, and the
 * paragraph above is what it costs on 33, 34 and 35.
 */
internal fun threadIdentity(thread: Thread): String =
    "Thread: ${thread.name} (id=${thread.id})"
