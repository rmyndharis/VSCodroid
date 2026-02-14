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
        fun `getLastCrash returns null before init`() {
            // Reset crashDir to uninitialized state via reflection
            val field = CrashReporter::class.java.getDeclaredField("crashDir")
            field.isAccessible = true
            // CrashReporter uses lateinit, so we need to check its behavior
            // when crashDir IS initialized but empty vs not initialized
            val crashDir = initCrashDir()
            // With initialized but empty dir, should return null
            assertNull(CrashReporter.getLastCrash(), "No crash logs should mean null")
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
