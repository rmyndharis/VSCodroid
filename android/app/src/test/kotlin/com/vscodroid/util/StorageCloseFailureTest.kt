package com.vscodroid.util

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.DirectoryStream
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream

class StorageCloseFailureTest {
    @TempDir lateinit var fixture: File

    @AfterEach fun cleanup() = unmockkAll()

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a directory stream without secure operations keeps the file and logs the refusal`(recursive: Boolean) {
        val file = File(fixture, "endpoint").apply { writeText("owned") }
        val rootPath = fixture.canonicalFile.toPath()
        val opened = Files.newDirectoryStream(rootPath)
        var closed = false
        val plain = object : DirectoryStream<Path> {
            override fun iterator() = opened.iterator()
            override fun close() {
                closed = true
                opened.close()
            }
        }
        val warnings = mutableListOf<String>()
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } answers { warnings += secondArg<String>() }
        mockkStatic(Files::class)
        every { Files.newDirectoryStream(rootPath) } returns plain

        if (recursive) assertEquals(0L, StorageManager.deleteRecursive(file, fixture))
        else assertFalse(StorageManager.deleteFile(file, fixture))

        assertEquals("owned", file.readText())
        assertTrue(closed)
        assertEquals(listOf("Cleanup skipped: secure directory operations are unavailable"), warnings)
        assertTrue(warnings.none { fixture.path in it })
    }

    @ParameterizedTest
    @CsvSource("false,root", "false,first", "false,second", "true,root", "true,first", "true,second")
    fun `a close failure preserves the unlink result and closes every ancestor`(
        recursive: Boolean,
        failedClose: String,
    ) {
        val file = File(fixture, "first/second/endpoint").apply {
            parentFile!!.mkdirs()
            writeText("owned")
        }
        val rootPath = fixture.canonicalFile.toPath()
        val opened = Files.newDirectoryStream(rootPath) as SecureDirectoryStream<Path>
        val closed = mutableListOf<String>()
        val wrapped = CloseFailureStream(opened, "root", failedClose, closed)
        mockkStatic(Files::class)
        every { Files.newDirectoryStream(rootPath) } returns wrapped

        if (recursive) {
            assertEquals(5L, StorageManager.deleteRecursive(file, fixture))
        } else {
            assertTrue(StorageManager.deleteFile(file, fixture))
        }
        assertFalse(file.exists())
        assertEquals(listOf("second", "first", "root"), closed)
    }

    private class CloseFailureStream(
        private val delegate: SecureDirectoryStream<Path>,
        private val name: String,
        private val failedClose: String,
        private val closed: MutableList<String>,
    ) : SecureDirectoryStream<Path> by delegate {
        override fun newDirectoryStream(path: Path, vararg options: LinkOption): SecureDirectoryStream<Path> =
            CloseFailureStream(delegate.newDirectoryStream(path, *options), path.toString(), failedClose, closed)

        override fun close() {
            delegate.close()
            closed.add(name)
            if (name == failedClose) throw IOException("Controlled close failure")
        }
    }
}
