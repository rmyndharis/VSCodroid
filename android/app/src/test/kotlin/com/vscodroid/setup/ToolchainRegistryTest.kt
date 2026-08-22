package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Tests for [ToolchainRegistry]: the catalog and its lookups.
 */
class ToolchainRegistryTest {

    // ── Catalog ──────────────────────────────────────────────────────────

    @Nested
    inner class CatalogTest {

        @Test
        fun `has exactly 2 toolchains`() {
            assertEquals(2, ToolchainRegistry.available.size)
        }

        @Test
        fun `all toolchains have valid fields`() {
            for (tc in ToolchainRegistry.available) {
                // Not assertNotNull: these three are non-nullable String, so an
                // assertion that they are not null cannot fail and never could.
                // It read as validation while checking nothing -- with every
                // displayName set to "" the whole suite stayed green, measured.
                // Blank is the failure that can actually reach a user, as an
                // unnamed card in the toolchain picker.
                assertTrue(tc.packName.isNotBlank(), "packName is blank")
                assertTrue(tc.displayName.isNotBlank(), "displayName is blank: ${tc.packName}")
                assertTrue(tc.shortLabel.isNotBlank(), "shortLabel is blank: ${tc.packName}")
                // The description is a resource id now, so blankness is not the
                // shape the failure takes any more: an id that resolves to an
                // empty string is a strings.xml defect, and one that resolves to
                // nothing at all does not compile. What is left to catch here is
                // the id never being set, which leaves the card's second line
                // empty exactly as a blank string did.
                assertNotEquals(0, tc.descriptionRes, "descriptionRes unset: ${tc.packName}")
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

        /**
         * The recorded unpacked size is a floor, measured against the tree the
         * release is built from, not an estimate.
         *
         * Three readers key off it, and an understatement is the direction that
         * costs someone something: both install pre-flights shrink toward the
         * 50 MB buffer they are built on, and the card tells the user a smaller
         * number than the install writes. It went wrong exactly that
         * way once already: `download-java.sh` stopped deleting OpenJDK's
         * `legal/` and began dereferencing symlinks on copy, and the constant
         * every gate reads stayed at 146,000,000 for a tree that had grown to
         * 154.8 MB of file bytes.
         *
         * ⚠️ This can only run where the packs have been built. `download-*.sh`
         * populates them and they are not part of a source checkout, so a runner
         * that has not built them skips rather than passing over nothing: the
         * assumption below is what says which of the two happened. Read a green
         * here as evidence only when the count it reports is not zero.
         */
        @Test
        fun `no recorded size is under the tree its pack ships`() {
            var measured = 0
            for (tc in ToolchainRegistry.available) {
                // Unit tests run with the module as the working directory, so the
                // pack modules are one level up beside it.
                val usr = File("../${tc.packName}/src/main/assets/usr")
                if (!usr.isDirectory) continue
                measured++
                val onDisk = usr.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                assertTrue(
                    tc.estimatedSize >= onDisk,
                    "${tc.packName} records ${tc.estimatedSize} bytes for a tree of $onDisk; " +
                        "every space gate and the card the user reads are built on that figure, " +
                        "and understating it is the direction that admits a device it should refuse",
                )
            }
            assumeTrue(
                measured > 0,
                "no toolchain pack has been built in this checkout, so nothing was measured; " +
                    "run scripts/download-java.sh and scripts/download-ruby.sh to reach this",
            )
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
        fun `offers nothing it has retired`() {
            // The catalog is what the picker and the manage screen render, so an
            // entry here is an offer. Go left it because it could not compile,
            // and putting it back would need the sweep in ToolchainManager to go
            // too, or a fresh install would download 179 MB that the next launch
            // deletes.
            val offered = ToolchainRegistry.available.map { it.packName }.toSet()
            assertTrue(
                "toolchain_go" !in offered,
                "Go is offered again; ToolchainManager still sweeps it on launch, " +
                    "so an install would be undone by the next start",
            )
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
        @ValueSource(strings = ["toolchain_ruby", "toolchain_java"])
        fun `finds by full pack name`(packName: String) {
            val result = ToolchainRegistry.find(packName)
            assertNotNull(result, "Should find toolchain by pack name: $packName")
            assertEquals(packName, result!!.packName)
        }

        @ParameterizedTest(name = "find by short name: {0} → toolchain_{0}")
        @CsvSource("ruby,toolchain_ruby", "java,toolchain_java")
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

    // ToolchainRegistry.formatSize was here, and with it a nested class pinning
    // its 1,000,000 divisor. Sizes are formatted by
    // com.vscodroid.util.StorageManager.formatSize now, like every other byte
    // figure the app shows, and the card's use of it is pinned by
    // ToolchainCardStateTest.SizeLine.
}
