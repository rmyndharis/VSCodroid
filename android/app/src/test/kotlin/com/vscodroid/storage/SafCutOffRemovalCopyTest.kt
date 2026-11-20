package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * The copy of a deleted document that a removal took from its name, when the process died before it
 * could delete it.
 *
 * The removal moves the copy to a machine-temporary name while it proves the bytes. A kill in between
 * left that entry for good: the row that named the copy was dropped at the next open because nothing
 * was at the name, and nothing looked for the entry. Builds up to 1.9.0 gave it an ordinary name,
 * `removed.local-<uuid>`, and the next open created it on the device as work only this app had.
 *
 * What may be removed is only what is proved to be the bytes of a document the device deleted: the
 * length and the digest of a row whose document the device no longer lists and the mirror no longer holds.
 */
class SafCutOffRemovalCopyTest : SafInitialSyncTestBase() {

    private fun names() = created.map { it.second }

    private fun newName() = SafSyncEngine.removalCaptureName()

    private fun olderName() = "removed${SafSyncEngine.LOCAL_COPY_SUFFIX}${UUID.randomUUID()}"

    /** The device holds [kept] and what is named in [documents] besides, and the first open records them all. */
    private fun firstOpen(vararg documents: Child) {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k")) + documents))
        sync()
    }

    /** The document is deleted on the device, and the open is cut off with its copy taken from its name. */
    private fun cutOff(path: String, captureName: String): File {
        val from = mirrorFile(path)
        val to = File(from.parentFile, captureName)
        assertTrue(from.renameTo(to), "could not stage the cut-off removal of $path")
        return to
    }

    private fun deviceKeepsOnly(vararg documents: Child) {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k")) + documents))
    }

    @Test
    fun `the shapes of the names a removal took a copy under`() {
        assertTrue(SafSyncEngine.isRemovalCaptureName(newName()))
        assertTrue(SafSyncEngine.isRemovalCaptureName(olderName()))
        for (other in listOf(
            "notes.txt",
            "removed-1234.vscodroid-partial",
            "removed-${UUID.randomUUID()}",
            "removed-${UUID.randomUUID()}.vscodroid-partial.bak",
            "x${newName()}",
            "removed.local-${UUID.randomUUID()}.txt",
            "removed.local-${UUID.randomUUID()}".uppercase(),
            "notes.txt.local-1700000000000",
        )) {
            assertFalse(SafSyncEngine.isRemovalCaptureName(other), "$other was taken for a copy a removal made")
        }
    }

    @Test
    fun `the copy a killed removal left is removed at the next open`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val capture = cutOff("notes.txt", newName())
        deviceKeepsOnly()

        sync()

        assertFalse(capture.exists(), "the copy of a deleted document stayed in the mirror for good")
        assertFalse(mirrorFile("notes.txt").exists())
        assertEquals(emptyList<String>(), names(), "a document deleted on the device was created again")
    }

    @Test
    fun `the copy an older build left under an ordinary name is removed and not created on the device`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val capture = cutOff("notes.txt", olderName())
        deviceKeepsOnly()

        sync()

        assertEquals(emptyList<String>(), names(), "the copy of a deleted document was created on the device under a made-up name")
        assertFalse(capture.exists(), "the copy of a deleted document stayed in the mirror")
    }

    @Test
    fun `a copy below a folder is looked for in its own directory`() {
        deviceTree(mapOf(
            "root" to listOf(Child("keep.txt", contents = "k"), Child("sub", isDirectory = true)),
            "doc:sub" to listOf(Child("notes.txt", contents = "first draft")),
        ))
        sync()
        val capture = cutOff("sub/notes.txt", newName())
        deviceTree(mapOf(
            "root" to listOf(Child("keep.txt", contents = "k"), Child("sub", isDirectory = true)),
            "doc:sub" to emptyList(),
        ))

        sync()

        assertFalse(capture.exists(), "the copy below the folder stayed")
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun `an entry of that name with other bytes of the same length is kept`() {
        firstOpen(Child("notes.txt", contents = "old"))
        val capture = cutOff("notes.txt", newName())
        capture.writeText("NEW")
        deviceKeepsOnly()

        sync()

        assertEquals("NEW", capture.readText(), "bytes that are not the deleted document's were removed")
    }

    @Test
    fun `an entry of that name that is not the length of any row is kept`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val capture = cutOff("notes.txt", olderName())
        capture.writeText("first draft, and more the person wrote")
        deviceKeepsOnly()

        sync()

        assertTrue(capture.exists(), "bytes that are not the deleted document's were removed")
    }

    /**
     * A document the device still lists and the open does not copy, here for being too large, has no copy
     * in the mirror either. The entry beside it is not the copy of a deleted document, whatever it holds.
     */
    @Test
    fun `an entry of a document the device still lists is kept`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val capture = cutOff("notes.txt", newName())
        deviceKeepsOnly(Child("notes.txt", contents = "first draft", size = SafSyncEngine.MAX_FILE_SIZE + 1))

        sync()

        assertTrue(capture.exists(), "an entry whose document the device still has was removed as the copy of a deleted one")
    }

    @Test
    fun `an entry in another directory than the row is kept`() {
        deviceTree(mapOf(
            "root" to listOf(Child("keep.txt", contents = "k"), Child("notes.txt", contents = "first draft"), Child("sub", isDirectory = true)),
            "doc:sub" to emptyList(),
        ))
        sync()
        val elsewhere = File(mirrorFile("sub"), newName()).apply { writeText("first draft") }
        mirrorFile("notes.txt").delete()
        deviceTree(mapOf(
            "root" to listOf(Child("keep.txt", contents = "k"), Child("sub", isDirectory = true)),
            "doc:sub" to emptyList(),
        ))

        sync()

        assertTrue(elsewhere.exists(), "an entry beside no row of a deleted document was removed")
    }

    @Test
    fun `an entry beside a row whose copy the mirror still holds is kept`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val twin = File(mirror, newName()).apply { writeText("first draft") }
        deviceKeepsOnly()

        sync()

        assertTrue(twin.exists(), "an entry was taken for the copy of a removal that no removal had cut off")
    }

    @Test
    fun `a file that is not named like one is kept whatever bytes it holds`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val backup = File(mirror, "backup.txt").apply { writeText("first draft") }
        deviceKeepsOnly()

        sync()

        assertEquals("first draft", backup.readText(), "a file of the person's was removed for holding a deleted document's bytes")
    }

    @Test
    fun `a link named like one is neither followed nor removed`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val target = File(mirror, "notes.txt")
        val moved = File(mirror, "elsewhere.bin")
        assertTrue(target.renameTo(moved))
        val link = File(mirror, newName())
        Files.createSymbolicLink(link.toPath(), moved.toPath())
        deviceKeepsOnly()

        sync()

        assertTrue(Files.isSymbolicLink(link.toPath()), "a link was removed")
        assertEquals("first draft", moved.readText())
    }

    @Test
    fun `nothing is removed while the device lists nothing`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val capture = cutOff("notes.txt", newName())
        deviceTree(mapOf("root" to emptyList()))

        sync()

        assertTrue(capture.exists(), "an empty listing was taken for a folder whose documents were all deleted")
    }

    /**
     * An older build sent its copy to the device under that name, where it is a document like any other. It
     * has the bytes of a deleted document here, and the device still lists it, so it is not a leftover.
     */
    @Test
    fun `an entry the device lists is kept though its bytes are a deleted document's`() {
        val listed = olderName()
        deviceTree(mapOf("root" to listOf(
            Child("keep.txt", contents = "k"), Child("old.txt", contents = "same bytes"), Child(listed, contents = "same bytes"),
        )))
        sync()
        mirrorFile("old.txt").delete()
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child(listed, contents = "same bytes"))))

        sync()

        assertEquals("same bytes", mirrorFile(listed).readText(), "the mirror copy of a document the device lists was removed")
    }

    @Test
    fun `a row that cannot be made a path of does not stop the open`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val capture = cutOff("notes.txt", newName())
        val bad = SafSyncEngine.recordLine("${SafSyncEngine.escapeRecordPath("bad\u0000.txt")}\t$DEVICE_TIME\t5", ByteArray(32))
        val lines = record.readLines()
        record.writeText((listOf(lines.first(), bad) + lines.drop(1)).joinToString("\n"))
        deviceKeepsOnly()

        sync()

        assertFalse(capture.exists(), "a row of an unusable path kept the copy of a deleted document")
    }

    @Test
    fun `a folder with a tab in its name is found through the escaped path of the row`() {
        val folder = "we\tird"
        deviceTree(mapOf(
            "root" to listOf(Child("keep.txt", contents = "k"), Child(folder, isDirectory = true)),
            "doc:$folder" to listOf(Child("notes.txt", contents = "first draft")),
        ))
        sync()
        val capture = cutOff("$folder/notes.txt", newName())
        deviceTree(mapOf(
            "root" to listOf(Child("keep.txt", contents = "k"), Child(folder, isDirectory = true)),
            "doc:$folder" to emptyList(),
        ))

        sync()

        assertFalse(capture.exists(), "the copy of a deleted document below a folder whose name holds a tab stayed")
    }

    @Test
    fun `nothing is removed while the listing is still loading`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val capture = cutOff("notes.txt", newName())
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))), loading = setOf("root"))

        sync()

        assertTrue(capture.exists(), "a partial listing was taken for the whole folder")
    }

    @Test
    fun `nothing is removed when the record cannot be read`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val capture = cutOff("notes.txt", newName())
        record.writeText(record.readLines().drop(1).joinToString("\n"))
        deviceKeepsOnly()

        sync()

        assertTrue(capture.exists(), "an entry was removed on the word of a record this build cannot read")
    }

    @Test
    fun `an entry of a row without a digest is kept`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val capture = cutOff("notes.txt", newName())
        record.writeText(record.readLines().joinToString("\n") {
            if (it.startsWith("notes.txt\t")) it.substringBeforeLast('\t') else it
        })
        deviceKeepsOnly()

        sync()

        assertTrue(capture.exists(), "bytes no digest proves were removed")
    }

    /**
     * A file the device replaced with a folder is taken from its name by the same removal, and a kill
     * after that leaves the same entry. A folder stands at the row's path by then, and the device lists
     * it, so neither held the entry back: only a file at that path is the copy a row names.
     */
    @Test
    fun `the copy a killed removal left of a file the device replaced with a folder is removed`() {
        firstOpen(Child("foo", contents = "first draft"))
        val capture = cutOff("foo", newName())
        deviceTree(mapOf(
            "root" to listOf(Child("keep.txt", contents = "k"), Child("foo", isDirectory = true)),
            "doc:foo" to emptyList(),
        ))

        sync()

        assertFalse(capture.exists(), "the copy of the file the device replaced with a folder stayed for good")
        assertTrue(mirrorFile("foo").isDirectory)
        assertEquals(emptyList<String>(), names(), "the copy was created on the device")
    }

    /**
     * Something made the path again between the kill and the open. What stands there is not the copy the
     * row names, so it does not hold the entry back, and the new file is the person's and stays.
     */
    @Test
    fun `the copy a killed removal left is removed though the path was made again with other bytes`() {
        firstOpen(Child("notes.txt", contents = "first draft"))
        val capture = cutOff("notes.txt", newName())
        mirrorFile("notes.txt").writeText("new work of the person")
        deviceKeepsOnly()

        sync()

        assertFalse(capture.exists(), "the copy of a deleted document stayed because its path was made again")
        assertTrue(mirror.walk().any { it.isFile && it.readText() == "new work of the person" }) {
            "the file made after the kill was lost"
        }
    }
}
