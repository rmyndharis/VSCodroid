package com.vscodroid.util

import android.content.Context
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
     */
    fun getLastCrash(): String? {
        if (!::crashDir.isInitialized) return null
        return crashDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()
            ?.readText()
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
     * - Recent crash logs
     * - Last 200 lines of Node.js server output
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
                    sb.appendLine(log.readText())
                    sb.appendLine("---")
                }
            } else {
                sb.appendLine("--- No crash logs ---")
            }
        }
        sb.appendLine()

        // Node.js server log (last 200 lines)
        val serverLog = File(Environment.getLogsDir(context), "server.log")
        if (serverLog.exists()) {
            sb.appendLine("--- Server Log (last 200 lines) ---")
            val lines = serverLog.readLines()
            val tail = if (lines.size > 200) lines.takeLast(200) else lines
            tail.forEach { sb.appendLine(it) }
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
            sw.appendLine("Thread: ${thread.name} (id=${thread.threadId()})")
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
