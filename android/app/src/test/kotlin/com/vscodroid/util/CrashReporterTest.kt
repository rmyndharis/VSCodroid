package com.vscodroid.util

import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.Runs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for [CrashReporter] — crash log management and lifecycle.
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
}
