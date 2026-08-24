package com.vscodroid.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

/**
 * Tests for [StorageManager]: the pure [StorageManager.formatSize] function, and
 * the workspace row of the breakdown.
 *
 * The rest of what takes a [android.content.Context] is covered by [StorageSymlinkTest],
 * which hands them a mock whose `filesDir` and `cacheDir` are real temporary directories.
 * This file used to claim they belonged in an instrumented test; they do not, and saying
 * so left the two directory walks with no test at all.
 */
class StorageManagerTest {

    /**
     * The workspace row, and where it counts.
     *
     * `getProjectsDir` answers one of two places: `filesDir/projects` on a new
     * install, or `Android/data/<pkg>/files/projects` on an install from a
     * release that kept the workspace on shared storage. The walk that builds
     * `total` covers the first and not the second, so a legacy install's `total`
     * disagreed with Android's app-info figure by the size of the user's work,
     * and a fresh install had the same bytes inside `total` and in no row. Real
     * directories, because the decision is made of `isDirectory` questions.
     */
    @Nested
    inner class ProjectsRowTest {

        @TempDir
        lateinit var root: File

        private lateinit var context: Context
        private lateinit var filesDir: File
        private lateinit var externalDir: File

        @BeforeEach
        fun stubStorage() {
            filesDir = File(root, "files").apply { mkdirs() }
            externalDir = File(root, "external").apply { mkdirs() }
            context = mockk(relaxed = true)
            every { context.filesDir } returns filesDir
            every { context.cacheDir } returns File(root, "cache").apply { mkdirs() }
            every { context.getExternalFilesDir(null) } returns externalDir
        }

        private fun fill(dir: File, bytes: Int) {
            dir.mkdirs()
            File(dir, "payload.bin").writeBytes(ByteArray(bytes))
        }

        @Test
        fun `a workspace on shared storage has a row and is counted in total`() {
            fill(File(filesDir, "server"), 10)
            fill(File(externalDir, "projects"), 30)

            val breakdown = StorageManager.getStorageBreakdown(context)

            assertEquals(30L, breakdown.getLong("projects"), "the workspace has no row")
            assertEquals(
                40L, breakdown.getLong("total"),
                "total leaves out a workspace that lives outside filesDir",
            )
        }

        @Test
        fun `a workspace under filesDir has a row and is not counted twice`() {
            fill(File(filesDir, "server"), 10)
            fill(File(filesDir, "projects"), 30)

            val breakdown = StorageManager.getStorageBreakdown(context)

            assertEquals(30L, breakdown.getLong("projects"), "the workspace has no row")
            assertEquals(
                40L, breakdown.getLong("total"),
                "a workspace already inside the filesDir walk was added to total again",
            )
        }

        /** The row is a figure, not an offer: nothing here may delete the user's work. */
        @Test
        fun `the workspace row is never offered to the clear action`() {
            fill(File(filesDir, "projects"), 30)

            val clearable = StorageManager.getStorageBreakdown(context).getJSONArray("clearable")
            val keys = (0 until clearable.length()).map { clearable.getString(it) }

            assertFalse(keys.contains("projects"), "the workspace was offered to the clear action")
        }
    }

    @Nested
    inner class FormatSizeTest {

        @Test
        fun `formats 0 bytes`() {
            assertEquals("0 B", StorageManager.formatSize(0))
        }

        @Test
        fun `formats small byte values`() {
            assertEquals("1 B", StorageManager.formatSize(1))
            assertEquals("512 B", StorageManager.formatSize(512))
            assertEquals("1023 B", StorageManager.formatSize(1023))
        }

        @Test
        fun `formats kilobytes`() {
            assertEquals("1.0 KB", StorageManager.formatSize(1_024))
            assertEquals("1.5 KB", StorageManager.formatSize(1_536))
            assertEquals("10.0 KB", StorageManager.formatSize(10_240))
        }

        @Test
        fun `formats megabytes`() {
            assertEquals("1.0 MB", StorageManager.formatSize(1_048_576))
            assertEquals("500.0 MB", StorageManager.formatSize(524_288_000))
        }

        @Test
        fun `formats gigabytes`() {
            assertEquals("1.0 GB", StorageManager.formatSize(1_073_741_824))
            assertEquals("2.5 GB", StorageManager.formatSize(2_684_354_560))
        }

        @ParameterizedTest(name = "boundary at {0} bytes = {1}")
        @CsvSource(
            "1023, 1023 B",
            "1024, 1.0 KB",
            "1048575, 1024.0 KB",
            "1048576, 1.0 MB",
            "1073741823, 1024.0 MB",
            "1073741824, 1.0 GB"
        )
        fun `handles unit boundaries correctly`(bytes: Long, expected: String) {
            assertEquals(expected, StorageManager.formatSize(bytes))
        }

        @Test
        fun `formats large GB values`() {
            // 128 GB
            val size = 128L * 1_073_741_824L
            val result = StorageManager.formatSize(size)
            assertTrue(result.endsWith("GB"), "Large values should be in GB: $result")
            assertEquals("128.0 GB", result)
        }
    }
}
