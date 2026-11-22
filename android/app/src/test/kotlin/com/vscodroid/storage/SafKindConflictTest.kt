package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

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

    @Test
    fun `an unrecorded file is preserved when the device holds a directory under its name`() {
        mirrorFile("foo").writeText("my only copy")
        deviceTree(mapOf("root" to listOf(Child("foo", isDirectory = true)), "doc:foo" to emptyList()))

        sync()

        assertTrue(mirrorFile("foo").isDirectory)
        assertEquals("my only copy", setAsideCopies("foo").single().readText())
    }

    /**
     * A name too long to take `.local-<time>` within the 255 bytes of a name is cut to fit. The
     * whole name failed the rename, so the device's folder could not be made at every open.
     */
    @Test
    fun `a file with a long name where the device holds a folder is kept beside it under its name cut to fit`() {
        val name = "f".repeat(240)
        mirrorFile(name).writeText("my only copy")
        deviceTree(mapOf("root" to listOf(Child(name, isDirectory = true)), "doc:$name" to emptyList()))

        sync()

        assertTrue(mirrorFile(name).isDirectory, "the device folder never took its name")
        val aside = mirror.listFiles()!!.single { it.isFile }
        assertEquals("my only copy", aside.readText())
        val stem = aside.name.substringBefore(SafSyncEngine.LOCAL_COPY_SUFFIX)
        assertTrue(
            stem.isNotEmpty() && name.startsWith(stem) && aside.name.toByteArray(Charsets.UTF_8).size <= 255,
            "the file was not kept under its name cut to fit, then the suffix: ${aside.name}",
        )
    }

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

    /**
     * A copy the record names without a digest cannot be shown to be the device's old bytes or the person's
     * work. Where the device deletes such a file, the open keeps the copy inside the app and does not send it;
     * where the device replaces it with a folder, the copy set aside beside the folder was sent to the device,
     * whose owner had replaced the file. It is now kept the same way, and the person is told.
     */
    @Test
    fun `a copy the record names without a digest is kept inside the app when the device replaces it with a folder`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", contents = "first draft"))))
        sync()
        created.clear()
        record.writeText(record.readLines().joinToString("\n") {
            if (it.startsWith("foo\t")) it.substringBeforeLast('\t') else it
        })
        deviceTree(
            mapOf(
                "root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", isDirectory = true)),
                "doc:foo" to emptyList(),
            ),
        )

        sync()

        assertTrue(mirrorFile("foo").isDirectory, "the device folder never took its name")
        assertEquals("first draft", setAsideCopies("foo").single().readText(), "the copy was not kept beside the folder")
        assertEquals(emptyList<String>(), created.map { it.second }, "a copy nothing proves is work was sent to the device")
        assertEquals(listOf(1 to setAsideCopies("foo").single().name), keptInApp, "the copy kept inside the app was not told of by name")
        assertEquals(emptyList<Int>(), didNotReach, "the copy kept inside the app was told of as one the device refused")

        sync()

        assertEquals(emptyList<String>(), created.map { it.second }, "the next open sent the kept copy")
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp, "the next open told of the same copy again")
        assertEquals("first draft", setAsideCopies("foo").single().readText())
    }

    /**
     * The same copy is told of once. It was also told of as an app edit kept beside its file, which reads as work on its
     * way to the device, while the notice for files kept inside the app says it stays there.
     */
    @Test
    fun `a copy kept inside the app beside the folder that replaced it is told of once`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", contents = "first draft"))))
        sync()
        record.writeText(record.readLines().joinToString("\n") {
            if (it.startsWith("foo\t")) it.substringBeforeLast('\t') else it
        })
        deviceTree(
            mapOf(
                "root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", isDirectory = true)),
                "doc:foo" to emptyList(),
            ),
        )

        sync()

        val aside = setAsideCopies("foo").single().name
        assertEquals(listOf(1 to aside), keptInApp, "the copy kept inside the app was not told of by name")
        assertEquals(emptyList<String>(), localCopies, "the copy kept inside the app was also told of as an app edit kept beside its file")
        assertEquals(1, record.readLines().count { it.startsWith(aside + "\t") }, "the copy kept inside the app has more than one row")
    }

    /** The same over a record an earlier build wrote, which the open sorts: still one row and one notice. */
    @Test
    fun `a copy kept inside the app beside the folder that replaced it over an earlier build's record is told of once`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", contents = "first draft"))))
        sync()
        record.writeText(record.readLines().joinToString("\n") {
            if (it.startsWith("foo\t")) it.substringBeforeLast('\t') else it
        })
        recordLeftByEarlierBuild()
        deviceTree(
            mapOf(
                "root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", isDirectory = true)),
                "doc:foo" to emptyList(),
            ),
        )

        sync()

        val aside = setAsideCopies("foo").single().name
        assertEquals(listOf(1 to aside), keptInApp, "the copy kept inside the app was not told of by name")
        assertEquals(emptyList<String>(), localCopies, "the copy kept inside the app was also told of as an app edit kept beside its file")
        assertEquals(1, record.readLines().count { it.startsWith(aside + "\t") }, "the copy kept inside the app has more than one row")
        assertEquals(emptyList<String>(), created.map { it.second }, "a copy nothing proves is work was sent to the device")
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

    /**
     * A copy that cannot be read shows neither that it is the copy the device replaced nor an edit of the
     * person's. It is left where it is: set aside as work it would be sent back to a device whose owner
     * replaced the file with a folder, which the vouched case above exists to prevent.
     */
    @Test
    fun `a recorded copy that cannot be read is left where it is and is not put back on the device`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", contents = "first draft"))))
        sync()
        created.clear()
        val copy = mirrorFile("foo")
        copy.setReadable(false)
        try {
            assumeFalse(Files.isReadable(copy.toPath()), "This account bypasses file read permissions")
            deviceTree(
                mapOf(
                    "root" to listOf(Child("keep.txt", contents = "kept"), Child("foo", isDirectory = true)),
                    "doc:foo" to emptyList(),
                ),
            )

            sync()

            assertTrue(copy.isFile, "the copy was moved although it could not be read")
            assertEquals(emptyList<String>(), setAsideCopies("foo").map { it.name })
            assertEquals(emptyList<String>(), created.map { it.second }, "the copy was put back on the device")
        } finally {
            copy.setReadable(true)
        }
    }
}
