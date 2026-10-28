package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * A staging name can also belong to a workspace file, so a name alone is never taken as
 * proof that an entry is leftover. What is removed is the one entry whose every byte is
 * already held by the file beside it: nothing is lost by dropping it, whoever made it.
 * Anything else is kept.
 */
class SafStagingLeftoverTest : SafInitialSyncTestBase() {

    private fun device(contents: String) {
        deviceTree(mapOf("root" to listOf(Child("notes.md", contents = contents))))
    }

    private fun openedBefore(contents: String = "hello world") {
        device(contents)
        sync()
        assertEquals(contents, mirrorFile("notes.md").readText())
    }

    @Test
    fun `a leftover that is the start of the file beside it is removed`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("hello w")

        sync()

        assertFalse(mirrorFile("notes.md.vscodroid-partial").exists())
        assertEquals("hello world", mirrorFile("notes.md").readText())
        assertTrue(created.isEmpty() && written.isEmpty() && lost.isEmpty(), "the removal reached the device")
    }

    @Test
    fun `an empty leftover beside a document is removed`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("")

        sync()

        assertFalse(mirrorFile("notes.md.vscodroid-partial").exists())
    }

    @Test
    fun `a leftover holding the whole file beside it is removed`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("hello world")

        sync()

        assertFalse(mirrorFile("notes.md.vscodroid-partial").exists())
        assertEquals("hello world", mirrorFile("notes.md").readText())
    }

    @Test
    fun `a leftover is removed once the sync has fetched the file it belongs to`() {
        device("hello world")
        mirrorFile("notes.md.vscodroid-partial").apply { parentFile.mkdirs() }.writeText("hello w")

        sync()

        assertEquals("hello world", mirrorFile("notes.md").readText())
        assertFalse(mirrorFile("notes.md.vscodroid-partial").exists())
    }

    @Test
    fun `a leftover that is not the start of the file beside it is kept`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("X")

        sync()

        assertEquals("X", mirrorFile("notes.md.vscodroid-partial").readText())
    }

    @Test
    fun `a leftover longer than the file beside it is kept`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("hello world and more")

        sync()

        assertTrue(mirrorFile("notes.md.vscodroid-partial").exists())
    }

    @Test
    fun `a leftover beside no document is kept`() {
        openedBefore()
        mirrorFile("other.md.vscodroid-partial").writeText("hello w")

        sync()

        assertTrue(mirrorFile("other.md.vscodroid-partial").exists(), "nothing holds its bytes")
    }

    @Test
    fun `a leftover beside a link is kept`() {
        openedBefore()
        val target = mirrorFile("elsewhere.txt").apply { writeText("hello world") }
        mirrorFile("notes.md").delete()
        Files.createSymbolicLink(mirrorFile("notes.md").toPath(), target.toPath())
        mirrorFile("notes.md.vscodroid-partial").writeText("hello w")

        sync()

        assertTrue(mirrorFile("notes.md.vscodroid-partial").exists(), "a link is not the file it points at")
    }

    @Test
    fun `a leftover that is a link is kept and its target is untouched`() {
        openedBefore()
        val target = mirrorFile("elsewhere.txt").apply { writeText("hello w") }
        Files.createSymbolicLink(mirrorFile("notes.md.vscodroid-partial").toPath(), target.toPath())

        sync()

        assertTrue(Files.isSymbolicLink(mirrorFile("notes.md.vscodroid-partial").toPath()))
        assertEquals("hello w", target.readText())
    }

    @Test
    fun `a directory with a leftover name is kept`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").mkdirs()
        mirrorFile("notes.md.vscodroid-partial/inner.txt").writeText("hello w")

        sync()

        assertEquals("hello w", mirrorFile("notes.md.vscodroid-partial/inner.txt").readText())
    }

    @Test
    fun `an unrecorded suffix entry continues to protect the mirror from reclaim`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("X")
        assertFalse(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror), "the entry is not pinning the mirror")

        sync()

        assertFalse(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror))
    }
}
