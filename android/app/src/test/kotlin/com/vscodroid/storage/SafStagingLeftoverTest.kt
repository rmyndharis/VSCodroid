package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Legacy staging names can also belong to workspace files. A name and matching prefix
 * do not establish ownership, so reopening retains these entries.
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
    fun `a local entry matching the start of the device document is retained`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("hello w")

        sync()

        assertEquals("hello w", mirrorFile("notes.md.vscodroid-partial").readText())
        assertEquals("hello world", mirrorFile("notes.md").readText())
    }

    @Test
    fun `an empty local entry beside a device document is retained`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("")

        sync()

        assertTrue(mirrorFile("notes.md.vscodroid-partial").isFile)
        assertEquals(0L, mirrorFile("notes.md.vscodroid-partial").length())
    }

    @Test
    fun `a leftover that is not the start of the device document is kept`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("X")

        sync()

        assertEquals("X", mirrorFile("notes.md.vscodroid-partial").readText())
    }

    @Test
    fun `a leftover longer than the device document is kept`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("hello world and more")

        sync()

        assertTrue(mirrorFile("notes.md.vscodroid-partial").exists())
    }

    @Test
    fun `a leftover beside no device document is kept`() {
        openedBefore()
        mirrorFile("other.md.vscodroid-partial").writeText("hello w")

        sync()

        assertTrue(mirrorFile("other.md.vscodroid-partial").exists(), "nothing shows it was a copy of anything")
    }

    @Test
    fun `a leftover the sync cannot compare is kept`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("hello w")
        // The provider will not hand the document over.
        io.mockk.every { resolver.openInputStream(any()) } throws java.io.IOException("offline")

        sync()

        assertTrue(mirrorFile("notes.md.vscodroid-partial").exists())
    }

    @Test
    fun `an unrecorded suffix entry continues to protect the mirror from reclaim`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("hello w")
        assertFalse(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror), "the leftover is not pinning the mirror")

        sync()

        assertFalse(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror))
    }
}
