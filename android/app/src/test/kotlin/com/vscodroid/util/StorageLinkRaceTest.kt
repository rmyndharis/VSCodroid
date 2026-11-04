package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class StorageLinkRaceTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `a directory swapped for a link cannot delete outside files`() {
        val root = File(temp, "cache").apply { mkdirs() }
        val outside = File(temp, "projects").apply { mkdirs() }
        val precious = File(outside, "work.txt").apply { writeText("keep this work") }
        val moved = File(temp, "discarded")
        val swapped = AtomicBoolean(false)
        val checked = object : File(root.path) {
            override fun isDirectory(): Boolean {
                if (swapped.compareAndSet(false, true)) {
                    Files.move(root.toPath(), moved.toPath())
                    Files.createSymbolicLink(root.toPath(), outside.toPath())
                }
                return super.isDirectory()
            }
        }

        StorageManager.deleteRecursive(checked, temp)

        assertTrue(precious.isFile, "clearing the directory followed a replacement link")
    }
    @Test
    fun `a redirected parent cannot lead cleanup outside the app directory`() {
        val outside = File(temp, "projects").apply { mkdirs() }
        val work = File(outside, "pip/work.txt").apply { parentFile.mkdirs(); writeText("keep this work") }
        val cache = File(temp, "cache").apply { mkdir() }
        Files.createSymbolicLink(File(cache, "redirected").toPath(), outside.toPath())

        StorageManager.deleteRecursive(File(cache, "redirected/pip"), temp)

        assertTrue(work.isFile, "cleanup followed a redirected parent")
    }

    @Test
    fun `cleanup unlinks a hard link without changing its other name`() {
        val owned = File(temp, "owned").apply { mkdir() }
        val contents = File(owned, "data").apply { writeText("shared bytes") }
        val remaining = File(temp, "user-copy")
        Files.createLink(remaining.toPath(), contents.toPath())

        assertEquals(12L, StorageManager.deleteRecursive(owned, temp))
        assertFalse(owned.exists())
        assertEquals("shared bytes", remaining.readText())
    }
}
