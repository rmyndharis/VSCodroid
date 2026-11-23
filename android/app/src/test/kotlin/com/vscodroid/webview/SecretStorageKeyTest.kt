package com.vscodroid.webview

import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
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

    @Test
    fun `create fsyncs parent directory after moving staged key into place`() {
        val syncedDirs = mutableListOf<File>()
        var keyPresentDuringSync = false
        var stagedAbsentDuringSync = false
        val store = SecretStorageKey(file) { dir ->
            keyPresentDuringSync = file.isFile && file.length() == SecretStorageKey.KEY_BYTES.toLong()
            stagedAbsentDuringSync = !File(dir, "${file.name}.tmp").exists()
            SecretStorageKey.fsyncDirectory(dir)
            syncedDirs += dir
        }

        val created = store.bytes()
        val reread = store.bytes()

        assertArrayEquals(created, reread)
        assertEquals(listOf(file.parentFile), syncedDirs)
        assertTrue(keyPresentDuringSync, "parent directory sync must run after rename into place")
        assertTrue(stagedAbsentDuringSync, "staged temp file must already be renamed away before directory sync")
    }

    @Test
    fun `a directory sync failure still keeps and returns the created key`(@TempDir tempDir: File) {
        val dir = File(tempDir, "noBackupFilesDir").apply { mkdirs() }
        val file = File(dir, SecretStorageKey.FILE_NAME)
        val loggedMessage = slot<String>()
        mockkObject(Logger)
        try {
            every { Logger.w("SecretStorageKey", capture(loggedMessage)) } just Runs
            every { Logger.w("SecretStorageKey", capture(loggedMessage), any()) } just Runs
            val key = SecretStorageKey(file) { throw IOException("sync failed") }

            val created = key.bytes()

            assertEquals(SecretStorageKey.KEY_BYTES, created.size)
            assertTrue(file.isFile)
            assertArrayEquals(created, file.readBytes())
            assertArrayEquals(created, SecretStorageKey(file).bytes())
            assertTrue(loggedMessage.isCaptured)
            assertTrue(loggedMessage.captured.contains(file.name))
            assertTrue(loggedMessage.captured.contains("IOException"))
            assertFalse(loggedMessage.captured.contains(dir.absolutePath))
            assertFalse(loggedMessage.captured.contains(dir.name))
        } finally {
            unmockkObject(Logger)
        }
    }

    @Test
    fun `staged key file is created atomically with owner-only permissions`() {
        val staged = File(tmp, "${SecretStorageKey.FILE_NAME}.tmp")
        var atomicCreateUsed = false
        io.mockk.mockkStatic(Files::class)
        try {
            every {
                Files.newByteChannel(
                    eq(staged.toPath()),
                    any<Set<java.nio.file.OpenOption>>(),
                    *anyVararg<java.nio.file.attribute.FileAttribute<*>>(),
                )
            } answers {
                val options = secondArg<Set<java.nio.file.OpenOption>>()
                val attrs = thirdArg<Array<java.nio.file.attribute.FileAttribute<*>>>()
                assertTrue(java.nio.file.StandardOpenOption.CREATE_NEW in options, "must use CREATE_NEW")
                assertTrue(
                    attrs.any { attr ->
                        val perms = attr.value() as? Set<*>
                        perms != null && PosixFilePermissions.toString(
                            perms.filterIsInstance<java.nio.file.attribute.PosixFilePermission>().toSet()
                        ) == "rw-------"
                    },
                    "staged file must be created with rw------- attribute",
                )
                atomicCreateUsed = true
                callOriginal()
            }

            val created = SecretStorageKey(file).bytes()

            assertTrue(atomicCreateUsed, "SecretStorageKey did not create its staged file atomically via Files.newByteChannel")
            assertEquals(SecretStorageKey.KEY_BYTES, created.size)
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        } finally {
            io.mockk.unmockkStatic(Files::class)
        }
    }
}
