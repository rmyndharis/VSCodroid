package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for [isElfHeader], which decides what the toolchain repair pass marks
 * executable.
 *
 * The risk runs one way. Missing an ELF object leaves a binary unrunnable —
 * recoverable, and the same state the user was already in. Claiming something is
 * an ELF that is not hands the execute bit to a data file, which is a permission
 * granted for no reason and can never be taken back by this pass, since it only
 * ever adds. Most of these therefore assert on what is *not* matched.
 *
 * Real bytes on disk rather than a mocked reader: the header check exists to
 * describe files that actually exist, and a fake would only agree with the
 * implementation it was copied from.
 */
class ElfHeaderTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val elf = bytes(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01)

    @Test
    fun `recognises an ELF header`() {
        assertTrue(isElfHeader(elf))
    }

    @Test
    fun `rejects a shell script`(@TempDir dir: File) {
        // The common case in these trees, and the one that must not be marked:
        // a script under filesDir cannot be executed at all — SELinux refuses —
        // which is why the manifests wrap scripts in shell functions instead.
        val script = File(dir, "gem").apply { writeText("#!/usr/bin/env ruby\nputs 1\n") }
        assertFalse(isElfHeader(script.readBytes().copyOf(4)))
    }

    @Test
    fun `rejects other file types that live in these trees`() {
        assertFalse(isElfHeader("PK".toByteArray()))      // a jar or zip
        assertFalse(isElfHeader("{\"a\":1}".toByteArray()))            // a manifest
        assertFalse(isElfHeader(bytes(0xCF, 0xFA, 0xED, 0xFE)))        // a Mach-O
    }

    @Test
    fun `rejects a file too short to have a header`() {
        assertFalse(isElfHeader(bytes(0x7F, 0x45, 0x4C)))
        assertFalse(isElfHeader(ByteArray(0)))
    }

    @Test
    fun `rejects the magic appearing anywhere but the start`() {
        assertFalse(isElfHeader(bytes(0x00, 0x7F, 0x45, 0x4C, 0x46)))
    }

    @Test
    fun `reads the same verdict from a real file as from its bytes`(@TempDir dir: File) {
        val binary = File(dir, "compile").apply { writeBytes(elf + ByteArray(64)) }
        val header = binary.inputStream().use { it.readNBytes(4) }
        assertTrue(isElfHeader(header))
    }
}
