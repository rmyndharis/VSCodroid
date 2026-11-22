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

    /** The folder with `top.txt` and `sub/a.txt`, opened once and its record lost, as a first open killed before it wrote one leaves it. */
    private fun openedWithoutRecord(): Map<String, List<Child>> {
        val folder = mapOf(
            "root" to listOf(Child("top.txt", contents = "t"), Child("sub", isDirectory = true)),
            "doc:sub" to listOf(Child("a.txt", contents = "a", id = "doc:sub/a.txt")),
        )
        deviceTree(folder)
        sync()
        assertTrue(mirrorFile("sub/a.txt").isFile)
        record.delete()
        sortedMark.delete()
        return folder
    }

    /** The device still holds `sub/a.txt`, and its listing of `sub` fails without a row. */
    private fun subListingFails() = deviceTree(
        mapOf("root" to listOf(Child("top.txt", contents = "t"), Child("sub", isDirectory = true)), "doc:sub" to emptyList()),
        errors = setOf("doc:sub"),
    )

    private fun rowOf(path: String) = record.readLines().drop(1).singleOrNull { SafSyncEngine.unescapeRecordPath(it.substringBefore('\t')) == path }

    /**
     * With no record to read, a listing that failed under one folder gave every copy under it a row without a digest
     * and told of it as a file that may have been deleted in the device folder, which still held it. What the listing
     * could not read stays as it was for an open that can; a file only the app holds where the listing was whole is
     * still kept and told of.
     */
    @Test
    fun `copies under a folder the listing could not read are neither kept nor told of`() {
        val folder = openedWithoutRecord()
        mirrorFile("stray.txt").writeText("only in the app")

        subListingFails()
        sync()

        assertEquals(listOf(1 to "stray.txt"), keptInApp, "the open did not tell of exactly the file it keeps")
        assertEquals(null, rowOf("sub/a.txt"), "a copy under the folder the listing could not read was given a row")
        assertTrue(rowOf("stray.txt")?.let { SafSyncEngine.splitRecordLine(it).second == null } == true)
        assertEquals("a", mirrorFile("sub/a.txt").readText())

        deviceTree(folder)
        sync()

        assertEquals(emptyList<Pair<Int, String>>(), keptInApp, "the open that listed the folder told of a file the device holds")
        assertEquals(emptyList<Pair<String, String>>(), created)
        assertTrue(rowOf("sub/a.txt")?.let { SafSyncEngine.splitRecordLine(it).second != null } == true, "the listed copy was not vouched for")
    }

    /**
     * The other side of leaving them: the device deletes the document before the next open. The open that lists the
     * folder keeps the copy inside the app and tells of it, and puts nothing back on the device.
     */
    @Test
    fun `a copy under a folder the listing could not read is kept and told of once the device lists the folder without it`() {
        openedWithoutRecord()
        subListingFails()
        sync()
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp)

        deviceTree(mapOf("root" to listOf(Child("top.txt", contents = "t"), Child("sub", isDirectory = true)), "doc:sub" to emptyList()))
        sync()

        assertEquals(emptyList<Pair<String, String>>(), created, "a document deleted on the device was put back")
        assertEquals(listOf(1 to "sub/a.txt"), keptInApp, "the copy kept inside the app was not told of")
        assertEquals("a", mirrorFile("sub/a.txt").readText())
        sync()
        assertEquals(emptyList<Pair<String, String>>(), created)
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp, "a later open told of the same copy again")
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
                    Child("x.txt", contents = "older document", id = "older-x"),
                    Child("y.txt", contents = "y"),
                    Child("x.txt", contents = "newer document", id = "newer-x"),
                ),
            )
        )

        sync()

        assertEquals("my edit", mirrorFile("x.txt").readText(), "a document replaced the file under its name")
        assertEquals("y", mirrorFile("y.txt").readText(), "the other documents of the folder were not copied")
        assertEquals(emptyList<Pair<String, String>>(), created, "a document was created for the repeated name")
        assertTrue(written.isEmpty(), "something was uploaded over one of the two documents: $written")
        assertTrue(record.readLines().any { it.startsWith("y.txt\t") }, "the distinct fetched file lost its identity")
        // With no record before this open, the copy at the repeated name may be named, but only
        // without a digest, which proves nothing: it is neither removed nor sent on such a row.
        assertTrue(
            record.readLines().filter { it.contains("x.txt") }.all { SafSyncEngine.splitRecordLine(it).second == null },
            "a repeated name was vouched for",
        )

        sync()

        assertEquals("my edit", mirrorFile("x.txt").readText(), "the next open replaced the file under the repeated name")
        assertEquals(emptyList<Pair<String, String>>(), created, "the next open created a document for the repeated name")
        assertTrue(written.isEmpty(), "the next open uploaded over one of the two documents: $written")
    }
}
