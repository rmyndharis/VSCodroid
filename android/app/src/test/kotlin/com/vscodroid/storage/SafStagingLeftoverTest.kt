package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The scratch copy an earlier build left inside the mirror.
 *
 * Up to 1.5.0 a device document was copied into `<name>.vscodroid-partial` beside its
 * destination and renamed into place, so a copy cut off by the process dying left that file
 * behind. Later builds stage outside the mirror and no longer vouch for such a name (a user
 * can create one too), which left every upgraded mirror that held one with a stray file in
 * the Explorer, a mirror the storage screen calls unsent work, and nothing that would ever
 * clear either.
 *
 * What an open does now is remove it when it can be shown to be what it looks like: the
 * device holds the document it was staging, and its bytes are the start of that document,
 * which is all an interrupted copy can leave. Anything else stays where it is.
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
    fun `a leftover that is the start of the device document is removed at the next open`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("hello w")

        sync()

        assertFalse(mirrorFile("notes.md.vscodroid-partial").exists(), "the interrupted copy stayed")
        assertEquals("hello world", mirrorFile("notes.md").readText())
    }

    @Test
    fun `an empty leftover next to the document it was staging is removed`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("")

        sync()

        assertFalse(mirrorFile("notes.md.vscodroid-partial").exists())
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
    fun `removing the leftover makes the mirror reclaimable again`() {
        openedBefore()
        mirrorFile("notes.md.vscodroid-partial").writeText("hello w")
        assertFalse(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror), "the leftover is not pinning the mirror")

        sync()

        assertTrue(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror))
    }
}
