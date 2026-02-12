package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for [ToolchainRegistry] — catalog lookups and size formatting.
 */
class ToolchainRegistryTest {

    // ── Catalog ──────────────────────────────────────────────────────────

    @Nested
    inner class CatalogTest {

        @Test
        fun `has exactly 3 toolchains`() {
            assertEquals(3, ToolchainRegistry.available.size)
        }

        @Test
        fun `all toolchains have valid fields`() {
            for (tc in ToolchainRegistry.available) {
                assertNotNull(tc.packName, "packName must not be null")
                assertNotNull(tc.displayName, "displayName must not be null")
                assertNotNull(tc.shortLabel, "shortLabel must not be null")
                assertNotNull(tc.description, "description must not be null")
                assert(tc.estimatedSize > 0) { "estimatedSize must be positive: ${tc.packName}" }
                assert(tc.packName.startsWith("toolchain_")) { "packName must start with 'toolchain_': ${tc.packName}" }
            }
        }

        @Test
        fun `all toolchains have HTTPS download URLs`() {
            for (tc in ToolchainRegistry.available) {
                assertNotNull(tc.downloadUrl, "downloadUrl must not be null: ${tc.packName}")
                assertTrue(tc.downloadUrl!!.startsWith("https://"), "downloadUrl must be HTTPS: ${tc.packName}")
                assertTrue(tc.downloadUrl!!.endsWith(".zip"), "downloadUrl must end with .zip: ${tc.packName}")
            }
        }

        @Test
        fun `contains Go toolchain`() {
            val go = ToolchainRegistry.available.find { it.shortLabel == "Go" }
            assertNotNull(go)
            assertEquals("toolchain_go", go!!.packName)
        }

        @Test
        fun `contains Ruby toolchain`() {
            val ruby = ToolchainRegistry.available.find { it.shortLabel == "Ruby" }
            assertNotNull(ruby)
            assertEquals("toolchain_ruby", ruby!!.packName)
        }

        @Test
        fun `contains Java toolchain`() {
            val java = ToolchainRegistry.available.find { it.shortLabel == "Java 17" }
            assertNotNull(java)
            assertEquals("toolchain_java", java!!.packName)
        }
    }

    // ── find() ───────────────────────────────────────────────────────────

    @Nested
    inner class FindTest {

        @ParameterizedTest(name = "find by full pack name: {0}")
        @ValueSource(strings = ["toolchain_go", "toolchain_ruby", "toolchain_java"])
        fun `finds by full pack name`(packName: String) {
            val result = ToolchainRegistry.find(packName)
            assertNotNull(result, "Should find toolchain by pack name: $packName")
            assertEquals(packName, result!!.packName)
        }

        @ParameterizedTest(name = "find by short name: {0} → toolchain_{0}")
        @CsvSource("go,toolchain_go", "ruby,toolchain_ruby", "java,toolchain_java")
        fun `finds by short name`(shortName: String, expectedPack: String) {
            val result = ToolchainRegistry.find(shortName)
            assertNotNull(result, "Should find toolchain by short name: $shortName")
            assertEquals(expectedPack, result!!.packName)
        }

        @ParameterizedTest(name = "find returns null for unknown: {0}")
        @ValueSource(strings = ["rust", "clang", "python", "toolchain_rust", ""])
        fun `returns null for unknown toolchains`(name: String) {
            assertNull(ToolchainRegistry.find(name), "Should return null for unknown: $name")
        }
    }

    // ── formatSize() ─────────────────────────────────────────────────────

    @Nested
    inner class FormatSizeTest {

        @Test
        fun `formats bytes`() {
            assertEquals("500 B", ToolchainRegistry.formatSize(500))
        }

        @Test
        fun `formats kilobytes`() {
            assertEquals("1 KB", ToolchainRegistry.formatSize(1_000))
            assertEquals("512 KB", ToolchainRegistry.formatSize(512_000))
        }

        @Test
        fun `formats megabytes`() {
            assertEquals("1 MB", ToolchainRegistry.formatSize(1_000_000))
            assertEquals("179 MB", ToolchainRegistry.formatSize(179_000_000))
        }

        @Test
        fun `formats gigabytes`() {
            assertEquals("1 GB", ToolchainRegistry.formatSize(1_000_000_000))
            assertEquals("2 GB", ToolchainRegistry.formatSize(2_500_000_000))
        }

        @Test
        fun `formats zero`() {
            assertEquals("0 B", ToolchainRegistry.formatSize(0))
        }
    }
}
