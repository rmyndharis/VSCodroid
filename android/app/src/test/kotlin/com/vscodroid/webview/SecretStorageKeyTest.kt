package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * The key the workbench's secret storage is sealed with.
 *
 * The workbench deletes every stored secret the first time it cannot decrypt
 * them, so the property that matters is that the key, once made, is the key
 * every later process reads back. A key that changed would not fail loudly: it
 * would sign the user out of everything on the next start.
 */
class SecretStorageKeyTest {

    @TempDir
    lateinit var tmp: File

    private val file get() = File(tmp, SecretStorageKey.FILE_NAME)

    @Test
    fun `a key is made once and kept`() {
        val first = SecretStorageKey(file).bytes()
        assertEquals(32, first.size)
        assertArrayEquals(first, file.readBytes())
        assertArrayEquals(
            first, SecretStorageKey(file).bytes(),
            "a new process must read the same key back, or every stored secret is deleted",
        )
    }

    @Test
    fun `a key of the wrong length is replaced`() {
        // The workbench refuses anything but 32 bytes, so such a file could never
        // have sealed anything: replacing it loses nothing.
        file.writeBytes(ByteArray(7))
        val key = SecretStorageKey(file).bytes()
        assertEquals(32, key.size)
        assertArrayEquals(key, file.readBytes())
    }

    @Test
    fun `the caller cannot change the kept key`() {
        val store = SecretStorageKey(file)
        store.bytes().fill(0)
        assertFalse(store.bytes().all { it == 0.toByte() })
    }

    @Test
    fun `only the owner can read the key`() {
        SecretStorageKey(file).bytes()
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())),
        )
    }
}
