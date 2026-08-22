package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [toolchainLibsSafeToRemove], which decides what a toolchain
 * uninstall may delete from the shared `usr/lib`.
 *
 * The risk is one-directional: naming one entry too few leaves a file the next
 * install overwrites, while naming one too many deletes a library the base app
 * loads: Ruby's `libffi.so` was the live case, and removing it broke
 * `import ctypes` until the next app update. Most of these therefore assert on
 * what is *not* returned.
 */
class ToolchainLibsTest {

    private val base = setOf("libffi.so", "libpython3.14.so", "libssl.so", "libz.so")

    @Test
    fun `removes libraries only the toolchain ships`() {
        val libs = listOf("libruby.so", "libgmp.so", "libyaml-0.so")
        assertEquals(libs, toolchainLibsSafeToRemove(libs, base))
    }

    @Test
    fun `keeps a library the base install also ships`() {
        // The live case: Ruby's manifest names libffi.so, and Python's _ctypes
        // links the copy the base APK extracted to the same directory.
        val libs = listOf("libruby.so", "libffi.so")
        assertEquals(listOf("libruby.so"), toolchainLibsSafeToRemove(libs, base))
    }

    @Test
    fun `removes nothing when the base listing cannot be read`() {
        // Unknown ownership means nothing is safe: a leftover library is
        // reclaimed by the next install, a deleted base library is not.
        assertTrue(toolchainLibsSafeToRemove(listOf("libruby.so"), null).isEmpty())
    }

    @Test
    fun `an empty base listing removes everything named`() {
        // Distinct from null: an APK that genuinely ships nothing in usr/lib
        // owns none of the toolchain's libraries.
        val libs = listOf("libruby.so", "libffi.so")
        assertEquals(libs, toolchainLibsSafeToRemove(libs, emptySet()))
    }

    @Test
    fun `an empty manifest list removes nothing`() {
        assertTrue(toolchainLibsSafeToRemove(emptyList(), base).isEmpty())
    }
}
