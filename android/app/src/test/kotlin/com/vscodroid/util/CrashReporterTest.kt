package com.vscodroid.util

import android.content.Context
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.Runs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for [CrashReporter]: crash log management and lifecycle.
 *
 * Uses JUnit 5 TempDir for isolated file system operations.
 * Tests init() by directly setting the internal crashDir via reflection,
 * since Context is not available in JVM unit tests.
 */
class CrashReporterTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
    }

    /**
     * Without this, [Logger] stayed mocked for the rest of the JVM. Every class
     * that calls `mockkObject(Logger)` afterwards re-mocks an object this one
     * never released, and whichever of them runs first decides what the others
     * see, so a failure lands in a class that did nothing wrong, and reordering
     * the suite moves it somewhere else.
     */
    @AfterEach
    fun tearDown() = unmockkAll()

    private fun initCrashDir(): File {
        val crashDir = File(tempDir, "crash-logs")
        crashDir.mkdirs()
        // Use reflection to set the private crashDir field
        val field = CrashReporter::class.java.getDeclaredField("crashDir")
        field.isAccessible = true
        field.set(CrashReporter, crashDir)
        return crashDir
    }

    @Nested
    inner class BeforeInitTest {

        @Test
        fun `an initialised but empty directory yields no crash`() {
            // Renamed. This was called `getLastCrash returns null before init`
            // and began by calling initCrashDir(), so it described a state it
            // never created: deleting the uninitialised guard left it green.
            // What it does cover is worth keeping, under a name that says so.
            initCrashDir()

            assertNull(CrashReporter.getLastCrash(), "No crash logs should mean null")
        }

        @Test
        fun `asking before init answers instead of throwing`() {
            // The state the old name claimed. crashDir is lateinit, so reading it
            // unset raises UninitializedPropertyAccessException -- and this is
            // reached from AndroidBridge.getLastCrash, a @JavascriptInterface
            // method. An exception there does not land in Logcat as a Kotlin
            // stack: it crosses into the WebView as a JS-side failure on a call
            // the workbench made, which is about as far from the cause as a
            // symptom can travel.
            //
            // The window is real. MainActivity calls checkPreviousCrash() during
            // onCreate, and the bridge is reachable as soon as the page loads.
            uninitialiseCrashDir()

            assertNull(
                CrashReporter.getLastCrash(),
                "the guard must answer, because the caller is across the JS bridge"
            )
        }

        @Test
        fun `every reader survives being asked before init`() {
            // getLastCrash is the one with a bridge caller, but all four share
            // the same lateinit field and the same guard, and a fix applied to
            // one is exactly the kind that gets applied to one.
            uninitialiseCrashDir()

            assertNull(CrashReporter.getLastCrash())
            assertFalse(CrashReporter.hasPendingCrash())
            CrashReporter.clearCrashLogs()
        }

        /** Puts the singleton back into the state a fresh process starts in. */
        private fun uninitialiseCrashDir() {
            CrashReporter::class.java.getDeclaredField("crashDir")
                .apply { isAccessible = true }
                .set(CrashReporter, null)
        }

        @Test
        fun `hasPendingCrash returns false when no crash logs exist`() {
            val crashDir = initCrashDir()
            assertFalse(CrashReporter.hasPendingCrash(), "No crash logs should mean no pending crash")
        }
    }

    @Nested
    inner class CrashLogLifecycleTest {

        @Test
        fun `getLastCrash returns content after writing a crash log`() {
            val crashDir = initCrashDir()
            val crashFile = File(crashDir, "crash_20260214_120000.txt")
            crashFile.writeText("Test crash at 2026-02-14\nThread: main\nNullPointerException")

            val lastCrash = CrashReporter.getLastCrash()
            assertTrue(lastCrash != null, "Should return crash log content")
            assertTrue(lastCrash!!.contains("NullPointerException"), "Should contain exception text")
        }

        @Test
        fun `hasPendingCrash returns true when crash logs exist`() {
            val crashDir = initCrashDir()
            File(crashDir, "crash_20260214_120000.txt").writeText("Crash content")

            assertTrue(CrashReporter.hasPendingCrash(), "Should detect pending crash")
        }

        @Test
        fun `getLastCrash returns most recent crash log`() {
            val crashDir = initCrashDir()

            val older = File(crashDir, "crash_20260213_100000.txt")
            older.writeText("Older crash")
            older.setLastModified(1000L)

            val newer = File(crashDir, "crash_20260214_120000.txt")
            newer.writeText("Newer crash")
            newer.setLastModified(2000L)

            val lastCrash = CrashReporter.getLastCrash()
            assertEquals("Newer crash", lastCrash, "Should return the most recent crash log")
        }

        @Test
        fun `the crash offered on screen does not carry the connection token`() {
            // Two callers share this one return value: the dialog
            // MainActivity.checkPreviousCrash shows, and AndroidBridge.getLastCrash,
            // which hands it to the page.
            val crashDir = initCrashDir()
            val token = "7f3a2b1c-9d8e-4f60-b5a4-2c1d0e9f8a7b"
            val crash = File(crashDir, "crash_20260214_120000.txt")
            crash.writeText(
                "Thread: main (id=1)\n" +
                    "java.io.IOException: GET http://127.0.0.1:41234/?tkn=$token failed\n"
            )
            check(crash.readText().contains(token)) {
                "the fixture has to hold a token, or redacting nothing passes"
            }

            val shown = CrashReporter.getLastCrash()

            assertNotNull(shown, "there is a crash log on disk to read")
            assertFalse(shown!!.contains(token), "the token must not be shown or handed out:\n$shown")
            assertTrue(shown.contains("tkn=<redacted>"), "the parameter stays visible:\n$shown")
            assertTrue(shown.contains("java.io.IOException"), "the failure itself has to survive:\n$shown")
        }

        /**
         * The token is not the only credential this text can carry, and the two
         * places it lands, the dialog and `AndroidBridge.getLastCrash`, are the
         * same places the report goes.
         *
         * NEGATIVE CONTROL: put `getLastCrash` back to `redactToken(it.readText())`
         * without [redactSecrets] and the password below is handed to the page.
         */
        @Test
        fun `the crash offered on screen carries no named credential`() {
            val crashDir = initCrashDir()
            File(crashDir, "crash_20260214_120000.txt").writeText(
                "Thread: main (id=1)\n" +
                    "java.lang.IllegalStateException: spawn failed for " +
                    "DB_PASSWORD=hunter2hunter2\n"
            )

            val shown = CrashReporter.getLastCrash()

            assertNotNull(shown, "there is a crash log on disk to read")
            assertFalse(
                shown!!.contains("hunter2hunter2"),
                "the page can call this method, and the dialog offers it to be pasted:\n$shown",
            )
            assertTrue(
                shown.contains("java.lang.IllegalStateException"),
                "the failure itself has to survive:\n$shown",
            )
        }

        /**
         * The directory is listed and then the newest entry is read, and those are
         * two operations. `cacheDir` is evictable by the OS at any moment, and the
         * app's own `clearCaches` and `clearCrashLogs` empty it from other threads
         * with no lock shared with this reader, so the entry can be gone by the
         * time it is read. `MainActivity.checkPreviousCrash` calls this on the main
         * thread from `onCreate` with no try anywhere on the path, so the throw
         * used to kill the process.
         *
         * A directory stands in for the vanished file, because both arrive here the
         * same way: an IOException out of `readText` on something `listFiles`
         * handed back, and unlike a race it happens on every run.
         *
         * NEGATIVE CONTROL: inline `readOrNull` back to `it.readText()` and this
         * case reddens with FileNotFoundException rather than returning null.
         */
        @Test
        fun `a crash log that cannot be read answers instead of throwing`() {
            val crashDir = initCrashDir()
            File(crashDir, "crash_20260214_120000.txt").mkdirs()

            assertNull(
                CrashReporter.getLastCrash(),
                "an unreadable entry has to read as no crash, not as an exception on " +
                    "the main thread",
            )
        }

        /**
         * A crash log is many lines and [redactSecrets] is written against one, so
         * the text handed to the dialog and to `AndroidBridge.getLastCrash` is
         * where a rule that crosses a newline is first noticed by a user: the
         * dialog offers a preview cut at 500 characters, and a trace that stops at
         * its first frame reads as a crash with nothing to say.
         *
         * NEGATIVE CONTROL: make `redactSecrets` one pass over the whole text
         * again (`redactOneLine(text)` with no split) and everything from
         * `Authorization:` to the end of the file becomes one `<redacted>`, so
         * both frames below are gone and this case reddens.
         */
        @Test
        fun `the crash offered on screen keeps the frames under a redaction`() {
            val crashDir = initCrashDir()
            File(crashDir, "crash_20260214_120000.txt").writeText(
                "Thread: main (id=1)\n" +
                    "java.io.IOException: Authorization: Bearer rejected by the proxy\n" +
                    "\tat com.vscodroid.service.ProcessManager.startServer(ProcessManager.kt:119)\n" +
                    "Caused by: java.net.SocketException: Socket is closed\n"
            )

            val shown = CrashReporter.getLastCrash()

            assertNotNull(shown, "there is a crash log on disk to read")
            assertFalse(
                shown!!.contains("Bearer rejected"),
                "the header value still has to go:\n$shown",
            )
            assertTrue(
                shown.contains("ProcessManager.startServer(ProcessManager.kt:119)") &&
                    shown.contains("Caused by: java.net.SocketException"),
                "the trace under the match was swallowed:\n$shown",
            )
        }
    }

    @Nested
    inner class ClearCrashLogsTest {

        @Test
        fun `clearCrashLogs removes all crash log files`() {
            val crashDir = initCrashDir()
            File(crashDir, "crash_1.txt").writeText("Crash 1")
            File(crashDir, "crash_2.txt").writeText("Crash 2")
            File(crashDir, "crash_3.txt").writeText("Crash 3")

            assertEquals(3, crashDir.listFiles()?.size, "Should have 3 crash logs before clear")

            CrashReporter.clearCrashLogs()

            assertEquals(0, crashDir.listFiles()?.size ?: 0, "Should have 0 crash logs after clear")
            assertNull(CrashReporter.getLastCrash(), "getLastCrash should return null after clear")
            assertFalse(CrashReporter.hasPendingCrash(), "hasPendingCrash should be false after clear")
        }
    }

    @Nested
    inner class PruneOldLogsTest {

        @Test
        fun `pruneOldLogs keeps only MAX_LOGS most recent`() {
            val crashDir = initCrashDir()

            // Create 15 crash logs (MAX_LOGS = 10)
            for (i in 1..15) {
                val file = File(crashDir, "crash_${"%02d".format(i)}.txt")
                file.writeText("Crash $i")
                file.setLastModified(i * 1000L)
            }

            assertEquals(15, crashDir.listFiles()?.size, "Should have 15 logs before prune")

            // Call pruneOldLogs via reflection (private method)
            val method = CrashReporter::class.java.getDeclaredMethod("pruneOldLogs")
            method.isAccessible = true
            method.invoke(CrashReporter)

            val remaining = crashDir.listFiles()?.size ?: 0
            assertEquals(10, remaining, "Should keep only 10 (MAX_LOGS) crash logs after prune")

            // Verify the oldest logs were deleted
            assertFalse(File(crashDir, "crash_01.txt").exists(), "Oldest log should be deleted")
            assertFalse(File(crashDir, "crash_05.txt").exists(), "5th oldest log should be deleted")
            assertTrue(File(crashDir, "crash_06.txt").exists(), "6th log should be kept")
            assertTrue(File(crashDir, "crash_15.txt").exists(), "Newest log should be kept")
        }
    }

    /**
     * The bundle behind **Copy Report**, which `MainActivity.checkPreviousCrash`
     * puts on the clipboard and `AndroidBridge.generateBugReport` hands back to
     * the page. Nothing here reads `android.os.Build`: those fields answer null
     * and 0 on a plain JVM, so asserting on them would describe the stub.
     */
    @Nested
    inner class BugReportTest {

        /**
         * A shape a real crash log carries, not a value the app holds: the URL
         * the WebView was loading when it failed goes into the stack trace.
         */
        private val token = "7f3a2b1c-9d8e-4f60-b5a4-2c1d0e9f8a7b"

        private val context = mockk<Context>(relaxed = true)

        @BeforeEach
        fun pointTheServerLogSomewhereEmpty() {
            mockkObject(Environment)
            every { Environment.getLogsDir(any()) } returns File(tempDir, "logs").absolutePath
        }

        @Test
        fun `the copied bug report does not carry the connection token`() {
            val crashDir = initCrashDir()
            val crash = File(crashDir, "crash_20260214_120000.txt")
            crash.writeText(
                "Crash at 2026-02-14 12:00:00 +0700\n" +
                    "Thread: main (id=1)\n\n" +
                    "java.io.IOException: GET " +
                    "http://127.0.0.1:41234/vscode-remote-resource?tkn=$token failed\n" +
                    "\tat com.vscodroid.webview.VSCodroidWebViewClient.proxy(VSCodroidWebViewClient.kt:554)\n"
            )
            // Without this the test would be green against a report that simply
            // never reached the crash log, which is the failure it exists to rule out.
            check(crash.readText().contains(token)) {
                "the fixture has to hold a token, or redacting nothing passes"
            }

            val report = CrashReporter.generateBugReport(context)

            assertFalse(
                report.contains(token),
                "the clipboard is readable by anything the user pastes into:\n$report",
            )
            assertTrue(
                report.contains("tkn=<redacted>"),
                "the parameter has to stay visible, so the reader knows what was taken out:\n$report",
            )
            assertTrue(
                report.contains(
                    "at com.vscodroid.webview.VSCodroidWebViewClient.proxy(VSCodroidWebViewClient.kt:554)"
                ),
                "this is the only diagnostic channel there is; the trace has to survive:\n$report",
            )
        }

        @Test
        fun `the bug report counts every crash log and embeds the newest three`() {
            val crashDir = initCrashDir()
            for (i in 1..4) {
                File(crashDir, "crash_0$i.txt").apply {
                    writeText("crash number $i")
                    setLastModified(i * 1000L)
                }
            }

            val report = CrashReporter.generateBugReport(context)

            assertTrue(report.contains("--- Crash Logs (4) ---"), "all four exist:\n$report")
            assertTrue(report.contains("crash number 4"), "the newest has to be there:\n$report")
            assertTrue(report.contains("crash number 3"), "$report")
            assertTrue(report.contains("crash number 2"), "$report")
            assertFalse(report.contains("crash number 1"), "only three are embedded:\n$report")
        }

        /**
         * An absent mirror and a quiet server used to read the same, because the
         * whole section was dropped when the file was missing. That is the exact
         * ambiguity [ServerLog] was added to remove, so the header is printed
         * either way and says which of the two it is.
         */
        @Test
        fun `the server section says so when there is no server output`() {
            initCrashDir()

            val report = CrashReporter.generateBugReport(context)

            assertTrue(report.contains("--- Server Log (last 200 lines) ---"), "$report")
            assertTrue(report.contains("(no server output recorded)"), "$report")
        }

        @Test
        fun `the server section carries the output and says what it is`() {
            initCrashDir()
            val log = File(tempDir, "logs/server.log")
            log.parentFile.mkdirs()
            log.writeText("Extension host agent listening on 41003\n")

            val report = CrashReporter.generateBugReport(context)

            assertTrue(
                report.contains("Extension host agent listening on 41003"),
                "the server output did not reach the report:\n$report",
            )
            assertFalse(
                report.contains("(no server output recorded)"),
                "a file with output was reported as empty:\n$report",
            )
            assertTrue(
                report.contains("server and extension-host output"),
                "the section does not say whose output it is, and the user is about " +
                    "to paste it somewhere public:\n$report",
            )
        }

        /**
         * The section is not this app's own output. The editor server echoes the
         * extension host's stdout and stderr into its console, so an extension
         * that dumps a failing request writes whatever authenticated it onto the
         * stream this section quotes.
         *
         * Written straight to the file rather than through [ServerLog], on
         * purpose: that writer redacts on the way in, so a file it wrote holds
         * nothing to find. A device upgrading from a build whose mirror did not
         * redact still has that file, and this is the reader that has to cope.
         */
        @Test
        fun `a credential in the server log does not reach the report`() {
            initCrashDir()
            val log = File(tempDir, "logs/server.log")
            log.parentFile.mkdirs()
            log.writeText(
                "<4242><stderr> POST /v1/messages failed\n" +
                    "<4242><stderr> Authorization: Bearer sk-live-9f8e7d6c5b4a3210\n"
            )

            val report = CrashReporter.generateBugReport(context)

            assertFalse(
                report.contains("sk-live-9f8e7d6c5b4a3210"),
                "the clipboard is readable by anything the user pastes into:\n$report",
            )
            assertTrue(
                report.contains("POST /v1/messages failed"),
                "this is the only diagnostic channel there is; the rest has to survive:\n$report",
            )
        }

        /**
         * The crash section crosses the same boundary as the server section: it is
         * put on the clipboard for the user to paste somewhere public. What has to
         * be taken out of a text follows from where the text is going, so both
         * sections take both scrubbers.
         *
         * NEGATIVE CONTROL: drop [redactSecrets] from the crash-log branch of
         * `generateBugReport`, leaving `redactToken(text)`, and the password below
         * is copied to the clipboard verbatim.
         */
        @Test
        fun `a named credential in a crash log does not reach the report`() {
            val crashDir = initCrashDir()
            File(crashDir, "crash_20260214_120000.txt").writeText(
                "Thread: main (id=1)\n" +
                    "java.lang.IllegalStateException: env was NPM_TOKEN=npm_AAAABBBBCCCCDDDD\n" +
                    "\tat com.vscodroid.service.ProcessManager.startServer(ProcessManager.kt:119)\n"
            )

            val report = CrashReporter.generateBugReport(context)

            assertFalse(
                report.contains("npm_AAAABBBBCCCCDDDD"),
                "the clipboard is readable by anything the user pastes into:\n$report",
            )
            assertTrue(
                report.contains(
                    "at com.vscodroid.service.ProcessManager.startServer(ProcessManager.kt:119)"
                ),
                "this is the only diagnostic channel there is; the trace has to survive:\n$report",
            )
        }

        /**
         * The section the count above promises, kept whole.
         *
         * The crash text goes through the same scrubbers as the server section
         * now, and it is the one that arrives as a whole file. A rule that spans
         * a newline turns the promised log into a single truncated line under a
         * header that still reads `--- Crash Logs (1) ---`, which is worse than a
         * missing section: it reads as a complete crash log that had nothing to
         * say, and the clipboard is the only diagnostic channel there is.
         *
         * NEGATIVE CONTROL: make `redactSecrets` one pass over the whole text
         * again (`redactOneLine(text)` with no split). Everything from
         * `Authorization:` onwards collapses into one `<redacted>`, since a Java
         * stack trace carries no quote, comma or brace to stop at, and this case
         * reddens on the first frame.
         */
        @Test
        fun `a redaction in a crash log does not swallow the trace under it`() {
            val crashDir = initCrashDir()
            File(crashDir, "crash_20260214_120000.txt").writeText(
                "Thread: main (id=1)\n" +
                    "java.io.IOException: Authorization: Bearer rejected by the proxy\n" +
                    "\tat com.vscodroid.webview.DownloadCoordinator.hold(DownloadCoordinator.kt:214)\n" +
                    "\tat com.vscodroid.service.ProcessManager.startServer(ProcessManager.kt:119)\n" +
                    "Caused by: java.net.SocketException: Socket is closed\n"
            )

            val report = CrashReporter.generateBugReport(context)

            assertFalse(
                report.contains("Bearer rejected"),
                "the credential shape still has to go:\n$report",
            )
            for (kept in listOf(
                "at com.vscodroid.webview.DownloadCoordinator.hold(DownloadCoordinator.kt:214)",
                "at com.vscodroid.service.ProcessManager.startServer(ProcessManager.kt:119)",
                "Caused by: java.net.SocketException: Socket is closed",
            )) {
                assertTrue(
                    report.contains(kept),
                    "\"$kept\" was swallowed by the redaction, and the header still " +
                        "promises a whole crash log:\n$report",
                )
            }
        }

        /**
         * The same two-step read as `getLastCrash`, on the path that runs on the
         * main thread behind the dialog's Copy Report button, where an exception
         * kills the process and loses the report the user was trying to send.
         *
         * NEGATIVE CONTROL: inline `readOrNull` back to `log.readText()` and this
         * case reddens with FileNotFoundException instead of producing a report.
         */
        @Test
        fun `a crash log that cannot be read leaves the rest of the report intact`() {
            val crashDir = initCrashDir()
            val readable = File(crashDir, "crash_20260214_120000.txt")
            readable.writeText("the crash that can still be read")
            readable.setLastModified(1000L)
            val unreadable = File(crashDir, "crash_20260214_120001.txt")
            unreadable.mkdirs()
            unreadable.setLastModified(2000L)

            val report = CrashReporter.generateBugReport(context)

            assertTrue(
                report.contains("the crash that can still be read"),
                "one unreadable entry took the whole section with it:\n$report",
            )
            assertTrue(
                report.contains("(crash_20260214_120001.txt could not be read)"),
                "the count above promised this log, so a silent hole reads as no crash:\n$report",
            )
        }

        @Test
        fun `the crash section says so when there is nothing to report`() {
            initCrashDir()

            val report = CrashReporter.generateBugReport(context)

            assertTrue(report.contains("--- No crash logs ---"), "$report")
        }
    }

    /**
     * The handler `init` installs, and the log it writes. Everything else in
     * this file fabricates crash files by hand, so the producer itself has never
     * run: it swallows every throwable, which means a writer that wrote nothing
     * would look exactly like one that worked.
     */
    @Nested
    inner class UncaughtExceptionHandlerTest {

        private var previousDefault: Thread.UncaughtExceptionHandler? = null

        @BeforeEach
        fun rememberDefaultHandler() {
            previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        }

        /**
         * The default handler is JVM-wide and this suite runs in a single JVM
         * with no forking, so one left installed here would still be there for
         * every class that runs afterwards.
         */
        @AfterEach
        fun restoreDefaultHandler() {
            Thread.setDefaultUncaughtExceptionHandler(previousDefault)
        }

        @Test
        fun `an uncaught exception is written down and still reaches the handler it replaced`() {
            val chained = AtomicReference<Throwable>()
            Thread.setDefaultUncaughtExceptionHandler { _, thrown -> chained.set(thrown) }
            val context = mockk<Context>(relaxed = true)
            every { context.cacheDir } returns tempDir

            CrashReporter.init(context)

            val installed = Thread.getDefaultUncaughtExceptionHandler()
            assertNotNull(installed, "init has to install a handler")
            val boom = IllegalStateException("boom")
            installed!!.uncaughtException(Thread("worker-7"), boom)

            val written = File(tempDir, "crash-logs").listFiles()?.toList().orEmpty()
            assertEquals(1, written.size, "one crash, one log: $written")
            val text = written.single().readText()
            assertTrue(text.contains("Thread: worker-7"), "the crashing thread is named: $text")
            assertTrue(
                text.contains("java.lang.IllegalStateException: boom"),
                "the exception has to be in there: $text",
            )
            assertTrue(
                text.contains("at com.vscodroid.util.CrashReporterTest"),
                "the stack trace is what makes the log worth keeping: $text",
            )
            assertSame(
                boom, chained.get(),
                "the handler that was there before still has to run, or the process never dies",
            )
        }

        /**
         * A cascade is two threads faulting in quick succession, and the file name
         * only resolves to a second.
         *
         * This handler is the process-wide default and runs before the chain to the
         * handler that kills the process, so nothing about dying serializes two
         * threads that fault together. Both crashes used to compute the same name
         * and `writeText` truncates, so what was lost was the first one: the crash
         * that started the cascade, leaving the report holding only the downstream
         * symptom.
         *
         * Aligned to the start of a wall-clock second first, so both writes land
         * inside one second, which is the state the defect needs. Without that a
         * run that straddled the boundary would pass on a broken build by picking
         * two names honestly.
         *
         * NEGATIVE CONTROL: put `writeCrashLog` back to
         * `val file = File(crashDir, "crash_$timestamp.txt")` with no
         * `createNewFile` loop; the directory then holds one file and this case
         * reddens on the count, and on the first exception being gone.
         */
        @Test
        fun `a second crash in the same second does not overwrite the first`() {
            val intoTheSecond = System.currentTimeMillis() % 1000
            if (intoTheSecond > 500) Thread.sleep(1000 - intoTheSecond)
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
            val context = mockk<Context>(relaxed = true)
            every { context.cacheDir } returns tempDir

            CrashReporter.init(context)

            val installed = Thread.getDefaultUncaughtExceptionHandler()!!
            installed.uncaughtException(Thread("worker-1"), IllegalStateException("the cause"))
            installed.uncaughtException(Thread("worker-2"), IllegalStateException("the symptom"))

            val written = File(tempDir, "crash-logs").listFiles()?.toList().orEmpty()
            assertEquals(2, written.size, "two crashes, two logs: $written")
            val all = written.joinToString("\n") { it.readText() }
            assertTrue(
                all.contains("the cause"),
                "the crash that started the cascade was overwritten by the one it " +
                    "caused, which is the only one worth reading:\n$all",
            )
            assertTrue(all.contains("the symptom"), "the second crash is missing:\n$all")
        }
    }
}

/**
 * The line the crash log opens with, and the API level it is allowed to need.
 *
 * `minSdk` is 33 and `Thread.threadId()` first appears in the android-36 stub:
 * `javap` on `java/lang/Thread.class` from `platforms/android-33`, `-34` and
 * `-35` shows `getId()` and nothing else. Nothing backports it here:
 * `coreLibraryDesugaring` is not enabled, and `abortOnError = false` means lint
 * cannot stop a build over it either.
 *
 * The consequence was not a visible crash. The call sat above `file.writeText`
 * inside `catch (_: Throwable)`, so on 33, 34 and 35 the `NoSuchMethodError` was
 * swallowed and no crash log was ever written: `hasPendingCrash()` stayed false,
 * the dialog in `MainActivity.checkPreviousCrash` never appeared, and the crash
 * section of a bug report was empty on every device that had one to report.
 */
class ThreadIdentityTest {

    private val source = File("src/main/kotlin/com/vscodroid/util/CrashReporter.kt")

    @Test
    fun `the crashing thread is named and numbered`() {
        val thread = Thread("worker-7")

        val line = threadIdentity(thread)

        assertTrue(line.contains("worker-7"), "the thread name has to survive: $line")
        assertTrue(
            line.contains("id=${thread.id}"),
            "the identity has to be in there to correlate with logcat: $line",
        )
    }

    @Test
    fun `the identity is not read through an API newer than minSdk`() {
        // A behavioural test cannot catch this one. Every JVM these tests run on
        // is Java 19 or later, where Thread.threadId() exists and answers
        // correctly, so restoring the call leaves the assertion above green and
        // the app broken on three of the four supported API levels. What
        // distinguishes them is which method name is compiled, so that is what
        // this reads.
        check(source.isFile) {
            "CrashReporter.kt not found at ${source.absolutePath}; this test " +
                "would otherwise pass by looking at nothing"
        }

        val offenders = source.readLines()
            .withIndex()
            .filter { (_, line) -> Regex("""\bthreadId\s*\(""").containsMatchIn(line) }
            .filterNot { (_, line) -> line.trimStart().startsWith("*") }
            .map { (i, line) -> "CrashReporter.kt:${i + 1}: ${line.trim()}" }

        assertEquals(
            emptyList<String>(), offenders,
            "Thread.threadId() is API 36 and minSdk is 33, with no desugaring to " +
                "bridge the gap. Use Thread.getId(), which has been there since API 1.",
        )
    }
}
