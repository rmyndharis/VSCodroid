package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A name the device holds as one kind of document and the mirror holds as the other.
 *
 * It is reachable without anything going wrong. The Explorer deletes a folder by moving it
 * out of the mirror, and a folder the device keeps is still on the device when a file of
 * the same name is made in its place; or the device replaces a file with a folder while
 * the mirror still has the file. Matching by name alone, the open then tried to make a
 * directory where a file stood, failed on every child, and said "N could not be copied" at
 * every open for as long as the file lived, while the journal line of the file's failed
 * save kept the whole mirror from ever being reclaimed.
 *
 * What an open does instead is what it already does when both sides changed a file: the
 * device keeps the name, the mirror's entry is set aside under a `.local-` name, and the
 * put-back pass sends it across. Both are kept, nothing waits for the user, and the one
 * exception is a file the last sync can vouch for, which is this app's own unchanged copy
 * of what the device replaced and so follows the device as it does for a deletion.
 */
class SafKindConflictTest : SafInitialSyncTestBase() {

    /**
     * An earlier open, which leaves the record beside the mirror. The put-back pass acts
     * only against a record it can read, because that is what tells a stranded save from a
     * deletion made on the device, so a folder that was never opened before sends nothing.
     */
    private fun openedBefore() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "kept"))))
        sync()
        created.clear()
    }

    private fun setAsideCopies(prefix: String) =
        mirror.listFiles().orEmpty().filter { it.name.startsWith("$prefix.local-") }

    @Test
    fun `a file made where the device holds a folder gives way and is kept beside it`() {
        openedBefore()
        deviceTree(
            mapOf(
                "root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", isDirectory = true)),
                "doc:foo" to listOf(Child("kid.txt", contents = "from the device")),
            )
        )
        mirrorFile("foo").writeText("my notes")

        sync()

        assertTrue(mirrorFile("foo").isDirectory, "the device folder never took its name back")
        assertEquals("from the device", mirrorFile("foo/kid.txt").readText())
        val aside = setAsideCopies("foo")
        assertEquals(1, aside.size, "the user's file was not kept beside the folder: ${mirror.list()?.toList()}")
        assertEquals("my notes", aside.single().readText())
        assertEquals(0, notCopied, "the open still reports documents it could not copy")
        assertEquals(
            listOf(aside.single().name), created.map { it.second },
            "the kept file was not put across to the device",
        )
        assertEquals("my notes", written["doc-uri:doc:${aside.single().name}"]?.toString())
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `the next open of a resolved conflict changes nothing`() {
        openedBefore()
        deviceTree(
            mapOf(
                "root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", isDirectory = true)),
                "doc:foo" to listOf(Child("kid.txt", contents = "from the device")),
            )
        )
        mirrorFile("foo").writeText("my notes")
        sync()
        val kept = setAsideCopies("foo").single().name
        created.clear()

        // The device now holds the copy that was put across.
        deviceTree(
            mapOf(
                "root" to listOf(
                    Child("keep.txt", contents = "kept"),
                    Child("foo", isDirectory = true),
                    Child(kept, contents = "my notes"),
                ),
                "doc:foo" to listOf(Child("kid.txt", contents = "from the device")),
            )
        )
        sync()

        assertEquals(listOf(kept), setAsideCopies("foo").map { it.name })
        assertEquals(emptyList<Pair<String, String>>(), created, "a second open put something across again")
        assertEquals(0, notCopied)
    }

    @Test
    fun `a file this app copied and the device then replaced with a folder follows the device`() {
        deviceTree(mapOf("root" to listOf(Child("foo", contents = "old"))))
        sync()
        assertEquals("old", mirrorFile("foo").readText())

        deviceTree(
            mapOf(
                "root" to listOf(Child("foo", isDirectory = true)),
                "doc:foo" to listOf(Child("kid.txt", contents = "k")),
            )
        )
        sync()

        assertTrue(mirrorFile("foo").isDirectory)
        assertEquals("k", mirrorFile("foo/kid.txt").readText())
        assertEquals(emptyList<File>(), setAsideCopies("foo"), "an unchanged copy was kept as if it were the user's work")
        assertEquals(emptyList<Pair<String, String>>(), created, "an unchanged copy was put back on the device")
    }

    @Test
    fun `a folder made where the device holds a file gives way and is kept beside it`() {
        openedBefore()
        deviceTree(
            mapOf("root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", contents = "device file")))
        )
        mirrorFile("foo").mkdirs()
        mirrorFile("foo/a.txt").writeText("mine")

        sync()

        assertTrue(mirrorFile("foo").isFile, "the device file never took its name back")
        assertEquals("device file", mirrorFile("foo").readText())
        val aside = setAsideCopies("foo")
        assertEquals(1, aside.size, "the user's folder was not kept beside the file: ${mirror.list()?.toList()}")
        assertTrue(aside.single().isDirectory)
        assertEquals("mine", File(aside.single(), "a.txt").readText())
        assertEquals(0, notCopied)
        assertTrue(
            created.any { it.second == "a.txt" },
            "the kept folder's file was not put across: $created",
        )
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `a set-aside leaves no upload record behind that pins the mirror`() {
        openedBefore()
        deviceTree(
            mapOf(
                "root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", isDirectory = true)),
                "doc:foo" to listOf(Child("kid.txt", contents = "from the device")),
            )
        )
        mirrorFile("foo").writeText("my notes")
        // What the failed save of that file left behind while the folder was open.
        File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE).writeText(mirrorFile("foo").absolutePath + "\n")

        sync()

        assertEquals(
            emptySet<String>(), SafSyncEngine(context).uploadsInFlight(),
            "the failed save's line stayed and pins the mirror against reclaim for good",
        )
    }

    @Test
    fun `a folder set aside takes the upload records of its files with it`() {
        openedBefore()
        deviceTree(
            mapOf("root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", contents = "device file")))
        )
        mirrorFile("foo").mkdirs()
        mirrorFile("foo/a.txt").writeText("mine")
        // A save of a file in that folder that never landed, as the journal keeps it.
        File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
            .writeText(mirrorFile("foo/a.txt").absolutePath + "\n")

        sync()

        assertEquals(
            emptySet<String>(), SafSyncEngine(context).uploadsInFlight(),
            "a record still names a path under the folder that moved away",
        )
    }
}
