package com.vscodroid.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.webkit.RenderProcessGoneDetail
import com.vscodroid.webview.redactToken
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

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

    /** How many of the system's exit records a report quotes. */
    private const val RECENT_EXITS = 10

    /** The file [recordRendererDeath] writes, beside `server.log`. */
    private const val RENDERER_LOG = "renderer.log"

    /** How many renderer deaths a report quotes. */
    private const val RENDERER_DEATHS = 20

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
     * The text goes through [redactToken] and [redactSecrets] on the way out.
     * This is the boundary the log crosses: it reaches the dialog
     * `MainActivity.checkPreviousCrash` puts on screen and the
     * `AndroidBridge.getLastCrash` method the page can call, and redacting here
     * covers both without either caller having to remember. Both scrubbers and
     * not only the token, for the reason [generateBugReport] gives: what a text
     * has to be scrubbed for follows from the boundary it crosses, and this one
     * ends up in the same places the report does. `MainActivity` cuts its preview
     * at 500 characters after this returns, so the substitution shifts where that
     * cut lands.
     */
    fun getLastCrash(): String? {
        if (!::crashDir.isInitialized) return null
        return crashDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()
            ?.let { readOrNull(it) }
            ?.let { redactSecrets(redactToken(it)) }
    }

    /**
     * Returns true if there are unread crash logs from a previous session.
     */
    fun hasPendingCrash(): Boolean {
        if (!::crashDir.isInitialized) return false
        return (crashDir.listFiles()?.size ?: 0) > 0
    }

    /**
     * Notes for [generateBugReport] that the editor's renderer process died.
     *
     * Called from both `onRenderProcessGone` overrides, before the WebView is
     * rebuilt. A death the app recovers from shows the user the loading page for
     * a few seconds and then the editor again, and until now it reached logcat
     * and nothing else, so no report could show it. The system's exit records
     * cover this app's own processes; whether they also cover the WebView's
     * sandboxed renderer is not established, so the app keeps its own line.
     *
     * The line holds the time, whether the renderer crashed or the system
     * killed it (most likely for memory, by the platform's account of
     * `didCrash`), and its priority at exit, and nothing about what the page
     * was showing. It is composed here, at the death, and written on a thread of
     * its own, because both callers are on the main thread. Kept beside
     * `server.log` so that Clear Caches removes it with the other logs.
     *
     * @return the thread doing the write, which no caller needs to wait for.
     */
    fun recordRendererDeath(context: Context, detail: RenderProcessGoneDetail): Thread {
        val priority = detail.rendererPriorityAtExit()
        val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()) +
            (if (detail.didCrash()) " crashed" else " killed by the system") +
            ", priority " + RENDERER_PRIORITIES.getOrElse(priority) { "$priority" }
        return thread(name = "renderer-death-note", isDaemon = true) {
            rendererLog(context).append(line)
        }
    }

    /**
     * [ServerLog] reused for a second file rather than imitated: it already
     * caps the file, swallows an I/O failure and makes a reader wait out a
     * writer, which is everything this log needs.
     */
    private fun rendererLog(context: Context) =
        ServerLog(File(Environment.getLogsDir(context), RENDERER_LOG))

    /**
     * Generates a bug report bundle containing:
     * - Device info (model, Android version, app version)
     * - Memory usage
     * - How the system recorded the end of this app's recent processes, from
     *   `ActivityManager.getHistoricalProcessExitReasons`
     * - The editor's renderer deaths, as [recordRendererDeath] noted them
     * - How many crash logs exist, plus the text of the three most recent
     * - The last 200 lines of the Node server's output, from the `server.log`
     *   that [ServerLog] writes off `ProcessManager.startOutputReader`
     *
     * That last section used to be dead: nothing wrote the file, so the block
     * below found nothing and every report shipped without a line of server
     * output. It is written now, in every build, which is the only reason the
     * block is worth keeping.
     *
     * The report is copied to the clipboard and handed back across the JS
     * bridge, so everything read off disk goes through [redactToken] first: the
     * server's connection token authenticates every route but `/version`,
     * `/delay-shutdown` and `/callback`. The containment stops where that
     * function's does, at the literal `tkn=` parameter, so a bare token in no
     * parameter at all still passes through.
     *
     * [redactSecrets] answers a second class of secret, and it runs over both
     * sections rather than only over the server one. The server section is where
     * a leak is demonstrable: it is not this app's own output, because the editor
     * server echoes the extension host's stdout and stderr into its console, so
     * an extension that dumps a failing request writes whatever authenticated it
     * onto the stream that section quotes. A crash log holds the stack trace of
     * an uncaught throwable in this process and nothing is known to put a
     * credential of that shape into one. It is scrubbed all the same, because
     * what a text needs taking out of it follows from where it is going, not from
     * who wrote it, and both sections leave here on the same clipboard. Splitting
     * that rule per section is how one half of a boundary ends up unguarded. A
     * crash log is a whole file rather than the single line the server section is
     * read in, and [redactSecrets] takes it a line at a time for that reason: its
     * patterns are written against one line and would otherwise swallow the stack
     * trace under a match.
     * [ServerLog] takes the same two functions on the way in, so a file written
     * by this build holds neither shape; both run again here, because this is the
     * boundary the text crosses and a file written by an older build is still on
     * the device.
     *
     * Blocking, and not only on its own reads. The exit records are a binder
     * call into the system, three crash files are read whole, and
     * [ServerLog.tail] reads all of `server.log`, up to its 256 KiB cap,
     * under the lock a rotation holds; a rotation is a full read and a full
     * write of that file on the thread draining the server's stdout, so a call
     * that lands during one waits it out. That is the right trade for the report
     * (a read that did not wait would come back short, and a short server
     * section reads as a quiet server), but it makes this a disk-bound call. The
     * bridge calls it from the WebView's interface thread, where blocking is what
     * a synchronous JS call expects. `MainActivity.checkPreviousCrash` reaches it
     * from a dialog button, and a button fires on the main thread, where the same
     * wait is a stall on the one screen shown after a crash: that caller has to
     * hop to `Dispatchers.IO` before calling and come back for the clipboard.
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

        // How the app's recent processes ended, as the system recorded it. The
        // crash logs below come from an uncaught Kotlin exception and from
        // nothing else, so the endings a user calls a freeze or a crash left no
        // trace in a report: an app Android declared not responding (ANR), one
        // killed to free memory (LOW_MEMORY), a native fault (CRASH_NATIVE).
        sb.appendLine("--- Recent Exits (newest first) ---")
        val exits = try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, RECENT_EXITS)
        } catch (_: Exception) {
            null
        }
        when {
            exits == null -> sb.appendLine("(could not be read)")
            exits.isEmpty() -> sb.appendLine("(none recorded)")
            // Through both scrubbers like everything else here: the description
            // is the system's own text, and this report goes to a stranger.
            else -> exits.forEach {
                sb.appendLine(redactSecrets(redactToken(exitLine(it, dateFormat))))
            }
        }
        sb.appendLine()

        // Newest first, as the exits above are, so the two read side by side.
        sb.appendLine("--- Renderer Deaths (newest first) ---")
        sb.appendLine("(each time the process that draws the editor died)")
        val deaths = rendererLog(context).tail(RENDERER_DEATHS)
        if (deaths.isEmpty()) sb.appendLine("(none recorded)")
        deaths.asReversed().forEach { sb.appendLine(redactSecrets(redactToken(it))) }
        sb.appendLine()

        // Crash logs
        if (::crashDir.isInitialized) {
            val logs = crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (logs.isNotEmpty()) {
                sb.appendLine("--- Crash Logs (${logs.size}) ---")
                for (log in logs.take(3)) {
                    val text = readOrNull(log)
                    sb.appendLine()
                    // Named rather than dropped: the count above already promised
                    // this log, so leaving a silent hole in the report says the
                    // crash never happened.
                    sb.appendLine(
                        text?.let { redactSecrets(redactToken(it)) }
                            ?: "(${log.name} could not be read)"
                    )
                    sb.appendLine("---")
                }
            } else {
                sb.appendLine("--- No crash logs ---")
            }
        }
        sb.appendLine()

        // Node.js server log (last 200 lines), written by [ServerLog] from the
        // output reader in `ProcessManager`. Redacted here as well as on the way
        // in: this reader is where the rule belongs, and keeping it means the
        // report stays clean whatever else ever writes to that file.
        //
        // Read through [ServerLog.tail] rather than off the file, so the read
        // takes the same lock a rotation holds. A rotation truncates the file and
        // then writes its tail back, and a read landing between the two returns
        // short or empty -- which is indistinguishable from a server that said
        // nothing, the exact ambiguity the mirror was added to remove.
        //
        // The header is printed whether or not there is anything under it. An
        // absent section and a quiet server used to look the same from the
        // outside, so a report from a device where the mirror never ran said
        // nothing about the mirror at all.
        sb.appendLine("--- Server Log (last 200 lines) ---")
        // The line telling the user what they are about to paste. This section
        // is not the app talking: it carries whatever the editor server and the
        // extension host printed, which is why [redactSecrets] runs over it.
        // "credentials removed" is what this said, and it is a promise the
        // scrubber cannot keep: [ServerLog.redactSecrets] matches known shapes,
        // and a bare secret is structurally indistinguishable from any other
        // word, which is why widening the patterns is not the fix for the
        // sentence. Say what is true and leave the user a reason to look.
        sb.appendLine(
            "(server and extension-host output; known credential shapes replaced, " +
                "check before sharing)"
        )
        val tail = ServerLog(File(Environment.getLogsDir(context), "server.log")).tail(200)
        if (tail.isEmpty()) {
            sb.appendLine("(no server output recorded)")
        } else {
            tail.forEach { sb.appendLine(redactSecrets(redactToken(it))) }
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

            val sw = StringWriter()
            sw.appendLine("Crash at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            sw.appendLine(threadIdentity(thread))
            sw.appendLine("Device: ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})")
            sw.appendLine()
            throwable.printStackTrace(PrintWriter(sw))

            // The name is only as fine-grained as a second, and this handler is
            // the process-wide default, entered by whichever thread faulted. It
            // also runs before the chain to the handler that kills the process,
            // so nothing about the process dying serializes two threads that
            // fault together: the second one arrives on the same name and
            // writeText truncates, which used to lose the first crash, the one
            // that started the cascade.
            // createNewFile is the atomic half of "take this name if nobody has
            // it": the loser of a race gets false rather than a shared file.
            // Giving up after a handful of names costs nothing, since [MAX_LOGS]
            // is all pruneOldLogs would keep anyway. Chosen after the trace is
            // rendered, so a render that fails leaves no empty file behind, which
            // would read as a crash that had nothing to say.
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            var file = File(crashDir, "crash_$timestamp.txt")
            var taken = 1
            while (taken < MAX_LOGS && !file.createNewFile()) {
                file = File(crashDir, "crash_${timestamp}_$taken.txt")
                taken++
            }

            file.writeText(sw.toString())
            Logger.e(TAG, "Crash log written: ${file.name}")
        } catch (_: Throwable) {
            // Last-resort: can't even write the crash log (including OOM)
        }
    }

    /**
     * The text of [log], or null if it cannot be read.
     *
     * Both readers list the directory and then read what the listing gave them,
     * and the two steps are not one operation. The directory is under `cacheDir`,
     * which the OS may evict under storage pressure at any moment, and two of the
     * app's own entry points empty it from other threads with no lock shared with
     * these readers: `AndroidBridge.clearCrashLogs`, and `clearCaches`, whose
     * disk thread deletes this directory outright. An entry that has gone by the
     * time it is read raises FileNotFoundException.
     *
     * That mattered because of who calls. `MainActivity.checkPreviousCrash` reads
     * a crash log on the main thread during `onCreate` and again from the dialog
     * button, and nothing on that path catches anything, so the throw reached the
     * looper and killed the process: a crash raised by the crash reporter, which
     * also loses the report the user was trying to send. Every other disk touch
     * in this object was already guarded, [writeCrashLog] and [pruneOldLogs]
     * both, and these two were the exception.
     */
    private fun readOrNull(log: File): String? =
        try {
            log.readText()
        } catch (_: Exception) {
            null
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
 * here: `coreLibraryDesugaring` is off, and lint did not stop it: `abortOnError`
 * was still false when the call was written. On 33, 34 and 35 the call
 * therefore raises `NoSuchMethodError`, and it sat above `file.writeText` inside
 * a `catch (_: Throwable)`: the write never happened, `hasPendingCrash()` stayed
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

/**
 * One exit record as a line of the report.
 *
 * Status is the exit code or, for a signal, its number; importance is the
 * process's `RunningAppProcessInfo` importance when it ended (100 foreground,
 * 125 foreground service, 400 cached); pss and rss are what it held when last
 * measured. The description is the system's own text, which for an ANR names
 * what timed out.
 */
private fun exitLine(exit: ApplicationExitInfo, dateFormat: SimpleDateFormat): String =
    "${dateFormat.format(Date(exit.timestamp))} ${exit.processName} " +
        "${exitReasonName(exit.reason)} (status ${exit.status}, importance ${exit.importance}, " +
        "pss ${exit.pss / 1024} MB, rss ${exit.rss / 1024} MB)" +
        (exit.description?.let { ": $it" } ?: "")

/**
 * The name of an `ApplicationExitInfo.REASON_*` value. The platform numbers
 * them from 0 with no gap, so the list is indexed by the value, and
 * `CrashReporterTest` holds it to the constants. Written out rather than read
 * off the constants because the last two are API 34 and minSdk is 33; a value
 * newer than the list is printed as its number.
 */
internal fun exitReasonName(reason: Int): String =
    EXIT_REASONS.getOrElse(reason) { "REASON_$reason" }

/** `WebView.RENDERER_PRIORITY_WAIVED`, `_BOUND` and `_IMPORTANT`, which are 0, 1 and 2. */
private val RENDERER_PRIORITIES = listOf("waived", "bound", "important")

private val EXIT_REASONS = listOf(
    "UNKNOWN", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE", "ANR",
    "INITIALIZATION_FAILURE", "PERMISSION_CHANGE", "EXCESSIVE_RESOURCE_USAGE",
    "USER_REQUESTED", "USER_STOPPED", "DEPENDENCY_DIED", "OTHER", "FREEZER",
    "PACKAGE_STATE_CHANGE", "PACKAGE_UPDATED",
)
