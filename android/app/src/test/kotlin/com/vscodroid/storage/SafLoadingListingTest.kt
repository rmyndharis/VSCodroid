package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A device folder listing the provider has not finished.
 *
 * A provider backed by a network answers a listing with the rows it has and the cursor
 * flag `EXTRA_LOADING`: more are coming. Read as the whole folder, the rows missing from it
 * look like documents the user deleted on the device, which is what the deletion pass
 * acts on, and the mirror copies of documents that were never gone were removed from the
 * editor until the next open. A listing that is still loading is treated as the listing
 * that failed: what it did not name is neither deleted nor taken for new, and whatever it
 * could not show stays guarded.
 */
class SafLoadingListingTest : SafInitialSyncTestBase() {

    private fun twoFiles() = mapOf(
        "root" to listOf(Child("a.txt", contents = "a"), Child("b.txt", contents = "b")),
    )

    @Test
    fun `a listing still loading does not remove the copy of a document it has not listed yet`() {
        deviceTree(twoFiles())
        sync()
        assertTrue(mirrorFile("b.txt").isFile)

        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "a"))), loading = setOf("root"))
        sync()

        assertTrue(mirrorFile("b.txt").isFile, "a copy was removed on a listing that was not finished")
        assertEquals("a", mirrorFile("a.txt").readText())
    }

    @Test
    fun `a finished listing without the document still removes its copy`() {
        deviceTree(twoFiles())
        sync()

        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "a"))))
        sync()

        assertFalse(mirrorFile("b.txt").exists(), "the deletion made on the device did not take")
    }

    @Test
    fun `a listing still loading puts nothing across for a file it did not show`() {
        deviceTree(twoFiles())
        sync()
        created.clear()

        // The user's own file is in the mirror and the provider has not yet listed the
        // document it was written back to; creating it again would make a second one.
        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "a"))), loading = setOf("root"))
        mirrorFile("fresh.txt").writeText("new work")
        sync()

        assertEquals(emptyList<Pair<String, String>>(), created, "something was created on an unfinished listing")
        assertEquals("new work", mirrorFile("fresh.txt").readText())
    }

    /**
     * A drive that lists two documents under one name in a folder. The mirror holds one file per
     * path, so an open copied the later row over the earlier, and a mirror newer than the older
     * document uploaded the other document's bytes over it with no copy kept.
     */
    @Test
    fun `two documents under one name are left alone, in both directions`() {
        mirror.mkdirs()
        mirrorFile("x.txt").writeText("my edit")
        deviceTree(
            mapOf(
                "root" to listOf(
                    Child("x.txt", contents = "older document"),
                    Child("y.txt", contents = "y"),
                    Child("x.txt", contents = "newer document"),
                ),
            )
        )

        sync()

        assertEquals("my edit", mirrorFile("x.txt").readText(), "a document replaced the file under its name")
        assertEquals("y", mirrorFile("y.txt").readText(), "the other documents of the folder were not copied")
        assertEquals(emptyList<Pair<String, String>>(), created, "a document was created for the repeated name")
        assertTrue(written.isEmpty(), "something was uploaded over one of the two documents: $written")
        assertFalse(record.exists(), "an open that left a name alone vouched for the listing as a whole")
    }
}
