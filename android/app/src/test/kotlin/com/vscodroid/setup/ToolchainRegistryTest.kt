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
                // Not assertNotNull: these four are non-nullable String, so an
                // assertion that they are not null cannot fail and never could.
                // It read as validation while checking nothing -- with every
                // displayName and description set to "" the whole suite stayed
                // green, measured. Blank is the failure that can actually reach
                // a user, as an unnamed card in the toolchain picker.
                assertTrue(tc.packName.isNotBlank(), "packName is blank")
                assertTrue(tc.displayName.isNotBlank(), "displayName is blank: ${tc.packName}")
                assertTrue(tc.shortLabel.isNotBlank(), "shortLabel is blank: ${tc.packName}")
                assertTrue(tc.description.isNotBlank(), "description is blank: ${tc.packName}")
                assert(tc.estimatedSize > 0) { "estimatedSize must be positive: ${tc.packName}" }
                assert(tc.packName.startsWith("toolchain_")) { "packName must start with 'toolchain_': ${tc.packName}" }
            }
        }

        @Test
        fun `a download is smaller than what it unpacks to`() {
            // The two figures are hand-written and both go stale when a payload
            // is rebuilt, so this cannot check either against reality. What it
            // can check is the one relation that holds by construction: a
            // compressed archive is smaller than its unpacked tree. That is
            // enough to catch the mistake worth catching, which is the two being
            // swapped or one being copied into the other, because the picker
            // shows them side by side and a swap reads as plausible.
            //
            // The single figure was the unpacked size and the picker presented
            // it as the download, so every toolchain was advertised at roughly
            // three times what it costs to fetch.
            for (tc in ToolchainRegistry.available) {
                assert(tc.downloadSize > 0) { "downloadSize must be positive: ${tc.packName}" }
                assert(tc.downloadSize < tc.estimatedSize) {
                    "downloadSize (${tc.downloadSize}) must be under estimatedSize " +
                        "(${tc.estimatedSize}); the ZIP cannot exceed what it unpacks to: ${tc.packName}"
                }
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
