package com.vscodroid.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

@RunWith(AndroidJUnit4::class)
class StorageAnchoredCleanupTest {
    private lateinit var fixture: File
    private lateinit var appRoot: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        appRoot = checkNotNull(context.filesDir.parentFile)
        fixture = Files.createTempDirectory(context.cacheDir.toPath(), "anchored-cleanup-").toFile()
    }

    @After
    fun tearDown() {
        Files.walkFileTree(fixture.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, failure: java.io.IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    @Test
    fun cleanupAcceptsTheCanonicalTargetOfTheContextRoot() {
        val owned = File(fixture, "owned").apply { mkdir() }
        File(owned, "data").writeText("owned bytes")

        assertEquals(11L, StorageManager.deleteRecursive(owned.canonicalFile, appRoot))
        assertFalse(owned.exists())
    }

    @Test
    fun cleanupAcceptsBothTargetSpellingsUnderATrustedRootAlias() {
        val data = File(fixture, "data/data").apply { mkdirs() }
        val user = File(fixture, "data/user").apply { mkdirs() }
        Files.createSymbolicLink(File(user, "0").toPath(), data.toPath())
        val trusted = File(user, "0/com.example.app").apply { mkdir() }
        for (canonical in listOf(false, true)) {
            val owned = File(trusted, "files/owned").apply { mkdirs() }
            File(owned, "data").writeText("owned bytes")

            val target = if (canonical) owned.canonicalFile else owned
            assertEquals(11L, StorageManager.deleteRecursive(target, trusted))
            assertFalse(owned.exists())
        }
    }

    @Test
    fun cleanupRejectsARedirectedAncestorInsideTheAppRoot() {
        val outside = File(fixture, "projects").apply { mkdir() }
        val work = File(outside, "pip/work.txt").apply { parentFile.mkdirs(); writeText("keep this work") }
        val cache = File(fixture, "cache").apply { mkdir() }
        Files.createSymbolicLink(File(cache, "redirected").toPath(), outside.toPath())

        assertEquals(0L, StorageManager.deleteRecursive(File(cache, "redirected/pip"), appRoot))
        assertEquals("keep this work", work.readText())
    }

    @Test
    fun anOpenedDirectoryStaysAnchoredAfterItsPathBecomesALink() {
        val outside = File(fixture, "projects").apply { mkdir() }
        val work = File(outside, "work.txt").apply { writeText("keep this work") }
        val parent = File(fixture, "cache").apply { mkdir() }
        val selected = File(parent, "selected").apply { mkdir() }
        val nested = File(selected, "nested").apply { mkdir() }
        File(nested, "owned.txt").writeText("discarded bytes")
        val moved = File(parent, "moved")

        Files.newDirectoryStream(parent.toPath()).use { opened ->
            assertTrue("The Android provider must support descriptor-relative cleanup", opened is SecureDirectoryStream<*>)
            @Suppress("UNCHECKED_CAST")
            val anchored = opened as SecureDirectoryStream<Path>
            anchored.newDirectoryStream(File("selected").toPath(), NOFOLLOW_LINKS).use { child ->
                Files.move(selected.toPath(), moved.toPath())
                Files.createSymbolicLink(selected.toPath(), outside.toPath())
                child.newDirectoryStream(File("nested").toPath(), NOFOLLOW_LINKS).use { leaf ->
                    leaf.deleteFile(File("owned.txt").toPath())
                }
                child.deleteDirectory(File("nested").toPath())
            }
        }

        assertEquals(0L, StorageManager.deleteRecursive(selected, appRoot))
        assertFalse(Files.exists(selected.toPath(), NOFOLLOW_LINKS))
        assertFalse(File(moved, "nested").exists())
        assertEquals("keep this work", work.readText())
    }

    @Test
    fun cleanupKeepsTheRemovedByteCountWhenOneSubtreeIsUnreadable() {
        val owned = File(fixture, "owned").apply { mkdir() }
        val blocked = File(owned, "blocked").apply { mkdir() }
        val retained = File(blocked, "data").apply { writeText("keep for a later clear") }
        val removed = File(owned, "removable").apply { writeText("removed bytes") }
        Files.setPosixFilePermissions(blocked.toPath(), emptySet())
        try {
            assertEquals(13L, StorageManager.deleteRecursive(owned, appRoot))
            assertFalse(removed.exists())
            assertTrue(blocked.exists())
        } finally {
            Files.setPosixFilePermissions(blocked.toPath(), setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ))
        }
        assertEquals("keep for a later clear", retained.readText())
    }
}
