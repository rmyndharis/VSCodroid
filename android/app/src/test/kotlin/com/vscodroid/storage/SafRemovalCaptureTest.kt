package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A copy of a document the device deleted is renamed out of the way before its bytes are proved and removed.
 * A process that dies in between leaves it under that name, and the name decides what the next open does with
 * it: under an ordinary one it is a file only this app has, and it was created on the device again.
 */
class SafRemovalCaptureTest : SafInitialSyncTestBase() {

    @Test
    fun `the name of a copy taken for deletion is a machine-temporary one`() {
        val name = SafSyncEngine.removalCaptureName()

        assertFalse(SafSyncEngine.shouldWriteBack("dir/$name", isDirectory = false), "a change to it would be written back")
        assertTrue(name.endsWith(SafSyncEngine.PARTIAL_SUFFIX))
    }

    @Test
    fun `an upload plan leaves it out and keeps the files around it`() {
        File(mirror, "dir").mkdirs()
        File(mirror, "dir/kept.txt").writeText("work")
        File(mirror, "dir/${SafSyncEngine.removalCaptureName()}").writeText("bytes of a deleted document")

        val plan = SafSyncEngine.uploadPlan(mirror, limit = 10)

        assertEquals(setOf("dir", "dir/kept.txt"), plan.entries.map { it.relativeTo(mirror).path }.toSet())
    }

    @Test
    fun `each copy gets a name of its own`() {
        assertNotEquals(SafSyncEngine.removalCaptureName(), SafSyncEngine.removalCaptureName())
    }

    /**
     * What the mirror holds, besides keep.txt, each time a copy holding [contents] has been read through while
     * [name] is not at its place: the moments the copy is away from its name.
     */
    private fun heldWhileAway(name: String, contents: String): List<String> {
        val seen = mutableListOf<String>()
        afterReading(contents) {
            if (!mirrorFile(name).isFile) seen += mirror.list().orEmpty().filter { it != "keep.txt" && it != name }
        }
        return seen
    }

    private fun assertNeverSent(held: List<String>) {
        assertTrue(held.isNotEmpty(), "the copy was proved where it stood and removed after")
        for (name in held) {
            assertTrue(SafSyncEngine.isRemovalCaptureName(name), "the copy was held under $name, which is no removal's name")
            assertFalse(SafSyncEngine.shouldWriteBack(name, isDirectory = false), "the copy was held under $name, which an open sends")
        }
    }

    @Test
    fun `a deletion holds the copy under that name while it proves the bytes`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("notes.txt", contents = "first draft"))))
        sync()
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        val held = heldWhileAway("notes.txt", "first draft")

        sync()

        assertNeverSent(held)
        assertEquals(listOf("keep.txt"), mirror.list()?.sorted(), "the copy of the deleted document was not removed")
        assertEquals(emptyList<Pair<String, String>>(), created)
    }

    @Test
    fun `a file the device replaced with a folder is held under that name while its bytes are proved`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("foo", contents = "first draft"))))
        sync()
        deviceTree(
            mapOf(
                "root" to listOf(Child("keep.txt", contents = "k"), Child("foo", isDirectory = true)),
                "doc:foo" to emptyList(),
            ),
        )
        val held = heldWhileAway("foo", "first draft")

        sync()

        assertNeverSent(held)
        assertTrue(mirrorFile("foo").isDirectory, "the device folder never took its name")
        assertEquals(listOf("foo", "keep.txt"), mirror.list()?.sorted(), "the copy the folder replaced was not removed")
        assertEquals(emptyList<Pair<String, String>>(), created)
    }
}
