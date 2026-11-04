package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class AnchoredFileDeletionTest {
    @TempDir lateinit var fixture: File

    @Test
    fun `a file inside the app root is unlinked`() {
        val app = File(fixture, "app").apply { mkdirs() }
        val file = File(app, "files/endpoint").apply { parentFile!!.mkdirs(); writeText("owned") }

        assertTrue(StorageManager.deleteFile(file, app))
        assertFalse(file.exists())
    }

    @Test
    fun `an app data alias still permits its own entry deletion`() {
        val app = File(fixture, "app").apply { mkdirs() }
        val file = File(app, "files/endpoint").apply { parentFile!!.mkdirs(); writeText("owned") }
        val alias = File(fixture, "alias")
        Files.createSymbolicLink(alias.toPath(), app.toPath())

        assertTrue(StorageManager.deleteFile(File(alias, "files/endpoint"), alias))
        assertFalse(file.exists())
    }

    @Test
    fun `a redirected parent never deletes an outside entry`() {
        val app = File(fixture, "app").apply { mkdirs() }
        val outside = File(fixture, "outside").apply { mkdirs() }
        val retained = File(outside, "endpoint").apply { writeText("keep") }
        val files = File(app, "files")
        Files.createSymbolicLink(files.toPath(), outside.toPath())

        assertFalse(StorageManager.deleteFile(File(files, "endpoint"), app))
        assertEquals("keep", retained.readText())
    }

    @Test
    fun `an endpoint directory is never traversed or removed`() {
        val app = File(fixture, "app").apply { mkdirs() }
        val endpoint = File(app, "files/endpoint").apply { mkdirs() }
        val retained = File(endpoint, "keep").apply { writeText("keep") }

        assertFalse(StorageManager.deleteFile(endpoint, app))
        assertEquals("keep", retained.readText())
    }
}
