package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * Tests for [writeAtomically], the mechanism that stops a failed extraction from
 * leaving a partial file under the name everything else checks for.
 *
 * These use real files rather than mocks: the property under test is what the
 * filesystem holds after a failure, which a mock would only assert about itself.
 */
class WriteAtomicallyTest {

    @TempDir
    lateinit var dir: File

    private fun tempFiles() = dir.listFiles()?.filter { it.name.endsWith(".tmp~") } ?: emptyList()

    @Test
    fun `writes the content and leaves no temporary file behind`() {
        val dest = File(dir, "payload.so")
        assertTrue(writeAtomically(dest) { it.write("hello".toByteArray()) })
        assertEquals("hello", dest.readText())
        assertTrue(tempFiles().isEmpty(), "a temporary file survived a successful write")
    }

    @Test
    fun `a failed write leaves the previous contents in place`() {
        // The reason this function exists. Before it, the bytes written ahead of
        // the failure stayed under the destination's own name, and exists()
        // accepted them.
        val dest = File(dir, "payload.so")
        dest.writeText("the version that works")

        val ok = writeAtomically(dest) {
            it.write("half of a replace".toByteArray())
            throw IOException("no space left on device")
        }

        assertFalse(ok, "a throwing writer reported success")
        assertEquals("the version that works", dest.readText())
        assertTrue(tempFiles().isEmpty(), "the partial write was left on disk")
    }

    @Test
    fun `a failed write leaves an absent destination absent`() {
        // The case the Python reconciliation depends on: the trigger is the
        // runtime's absence, so a failure has to preserve that absence for the
        // next launch to retry.
        val dest = File(dir, "payload.so")

        val ok = writeAtomically(dest) {
            it.write("partial".toByteArray())
            throw IOException("no space left on device")
        }

        assertFalse(ok)
        assertFalse(dest.exists(), "a failed write created the file it failed to write")
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `replaces an existing destination on success`() {
        val dest = File(dir, "payload.so")
        dest.writeText("the previous version")
        assertTrue(writeAtomically(dest) { it.write("the new version".toByteArray()) })
        assertEquals("the new version", dest.readText())
    }

    @Test
    fun `writes bytes unchanged`() {
        // Binaries go through here; a text-mode assumption would corrupt them.
        val dest = File(dir, "payload.so")
        val bytes = byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x00, -1, 0x0a, 0x0d)
        assertTrue(writeAtomically(dest) { it.write(bytes) })
        assertArrayEquals(bytes, dest.readBytes())
    }

    @Test
    fun `overwrites a stray temporary file from an earlier failure`() {
        // The name is derived from the destination rather than randomised, so a
        // copy killed outright leaves at most one stray per destination and the
        // next attempt reclaims it. That is the reason there is no sweep.
        val dest = File(dir, "payload.so")
        File(dir, "payload.so.tmp~").writeText("orphan from a killed process")

        assertTrue(writeAtomically(dest) { it.write("fresh".toByteArray()) })
        assertEquals("fresh", dest.readText())
        assertTrue(tempFiles().isEmpty(), "the stray was not reclaimed")
    }
}
