package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [supersededPythonEntries], which decides what gets deleted from
 * `usr/lib` when the bundled Python changes.
 *
 * The risk is one-directional, as with [supersededExtensionDirs]: naming one
 * entry too few wastes disk, while naming one too many deletes the stdlib the
 * current interpreter needs and leaves Python broken in a way that looks like
 * the bug this was written to fix. Most of these therefore assert on what is
 * *not* returned.
 */
class SupersededPythonTest {

    private val runtime = "libpython3.13.so"

    @Test
    fun `names the stdlib of a version no longer shipped`() {
        val present = listOf("python3.12", "python3.13", "libpython3.13.so")
        assertEquals(listOf("python3.12"), supersededPythonEntries(present, runtime))
    }

    @Test
    fun `names a runtime left behind by an earlier build`() {
        val present = listOf("libpython3.12.so", "libpython3.13.so", "python3.13")
        assertEquals(listOf("libpython3.12.so"), supersededPythonEntries(present, runtime))
    }

    @Test
    fun `keeps the stdlib and runtime that match`() {
        val present = listOf("python3.13", "libpython3.13.so")
        assertTrue(supersededPythonEntries(present, runtime).isEmpty())
    }

    @Test
    fun `leaves everything that is not Python alone`() {
        // usr/lib holds the shared libraries of every bundled tool. Deleting by
        // loose resemblance here would take out Node, git or the shim.
        val present = listOf(
            "libssl.so", "libcrypto.so", "libicudata.so", "libc++_shared.so",
            "git-core", "node_modules", "terminfo", "libpython.so",
        )
        assertTrue(supersededPythonEntries(present, runtime).isEmpty())
    }

    @Test
    fun `does not claim the launcher itself`() {
        // libpython.so is the interpreter, and it lives in nativeLibraryDir
        // rather than here -- but a loose pattern would match the name anywhere
        // it appeared, and deleting it would remove Python entirely.
        assertTrue(supersededPythonEntries(listOf("libpython.so"), runtime).isEmpty())
    }

    @Test
    fun `does not claim a versioned soname`() {
        // The anchor earns its place here: libpython3.13.so.1.0 is the same
        // library under the name upstream sometimes ships, not a stale one.
        val present = listOf("libpython3.13.so.1.0", "libpython3.13.so")
        assertTrue(supersededPythonEntries(present, runtime).isEmpty())
    }

    @Test
    fun `does not claim directories that merely start like a stdlib`() {
        val present = listOf("python3.13-config", "python3.13/site-packages", "python3")
        assertTrue(supersededPythonEntries(present, runtime).isEmpty())
    }

    @Test
    fun `returns nothing when the shipped runtime cannot be read`() {
        // Called with a name that carries no version, the honest answer is "no
        // opinion". Returning everything would wipe a working install.
        val present = listOf("python3.12", "python3.13", "libpython3.13.so")
        assertTrue(supersededPythonEntries(present, "libpython.so").isEmpty())
        assertTrue(supersededPythonEntries(present, "").isEmpty())
    }

    @Test
    fun `names every superseded version when several have accumulated`() {
        // Observed on a device: two stdlib trees side by side, only one usable.
        val present = listOf("python3.11", "python3.12", "python3.13", "libpython3.11.so", "libpython3.13.so")
        assertEquals(
            listOf("python3.11", "python3.12", "libpython3.11.so"),
            supersededPythonEntries(present, runtime),
        )
    }
}
