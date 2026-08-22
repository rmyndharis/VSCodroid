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
