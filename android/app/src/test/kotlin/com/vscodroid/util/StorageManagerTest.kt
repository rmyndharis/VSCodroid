package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Tests for [StorageManager] — specifically the pure [formatSize] function.
 *
 * Methods that require Android [Context] (getStorageBreakdown, clearCaches, etc.)
 * are better tested as instrumented tests. This test covers the platform-independent logic.
 */
class StorageManagerTest {

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
