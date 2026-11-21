package com.vscodroid.storage

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * A device copy an open puts in place of the mirror's, meeting a save that lands at the same name
 * after the open's last look at it.
 *
 * The open looked at the name, judged what stood there replaceable, and renamed the fetched copy
 * over it. A rename replaces whatever stands at its destination, so an editor's save that renamed
 * its file into place in between was replaced by the device's older copy, with no copy of it
 * anywhere. Where a file stands at the name, the copy now moves into the folder first, takes
 * that file aside in one step that cannot replace anything, checks it is what was judged, and
 * goes onto the name in a move that refuses a taken name. A save that took it is kept. A free
 * name is still looked at and then renamed onto.
 *
 * The move that refuses a taken name is the app's helper on a device; here it is stood in for by
 * one that answers as it does, and the save lands from inside it, at the step it races.
 */
class SafInstallRaceTest : SafInitialSyncTestBase() {

    private fun deviceHolds(name: String, contents: String, modified: Long = DEVICE_TIME) {
        deviceTree(mapOf("root" to listOf(Child(name, contents = contents, modified = modified))))
    }

    /** An editor's save that writes a file beside the name and renames it into place. */
    private fun saveByRename(file: File, text: String) {
        val written = File(file.parentFile, ".save-in-progress").apply { writeText(text) }
        Files.move(written.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * An engine whose exclusive move refuses a taken name as the helper does, and runs [before] with
     * the two names just before it moves.
     */
    private fun engineWithExclusiveMove(before: (directory: File, from: String, to: String) -> Unit): SafSyncEngine =
        SafSyncEngine(context).also { engine ->
            engine.onWriteBackFailed = { lost += it.name }
            engine.exclusiveMove = { directory, _, from, to ->
                before(directory, from, to)
                val target = File(directory, to)
                if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    ExclusiveMove.Outcome.TAKEN
                } else {
                    Files.move(File(directory, from).toPath(), target.toPath())
                    ExclusiveMove.Outcome.MOVED
                }
            }
        }

    private fun open(engine: SafSyncEngine) = runBlocking { engine.initialSync(named("tree"), mirror) { _, _ -> } }

    private fun leftovers() = mirror.list()!!.filter { it.endsWith(SafSyncEngine.PARTIAL_SUFFIX) || it.startsWith(".save") }

    @Test
    fun `a save that takes the name while the newer device copy is put in place is kept`() {
        deviceHolds("notes.md", "first")
        sync()
        deviceHolds("notes.md", "newer on the device", modified = DEVICE_TIME + 60_000)
        var saved = false
        val engine = engineWithExclusiveMove { directory, _, to ->
            if (to == "notes.md" && !saved) {
                saved = true
                saveByRename(File(directory, to), "saved while the folder opened")
            }
        }

        open(engine)

        assertTrue(saved, "the copy was put in place without a move that refuses a taken name")
        assertEquals("saved while the folder opened", mirrorFile("notes.md").readText(), "the save was replaced by the device's copy")
        assertEquals(emptyList<String>(), leftovers(), "the install left a file of its own in the folder")
        assertTrue(written.isEmpty(), "the save went to the device although the open had not read it")
    }

    @Test
    fun `a save that replaces the name just before it is taken aside is put back`() {
        deviceHolds("notes.md", "first")
        sync()
        deviceHolds("notes.md", "newer on the device", modified = DEVICE_TIME + 60_000)
        var saved = false
        val engine = engineWithExclusiveMove { directory, from, _ ->
            if (from == "notes.md" && !saved) {
                saved = true
                saveByRename(File(directory, from), "saved while the folder opened")
            }
        }

        open(engine)

        assertTrue(saved, "what stood at the name was never taken aside")
        assertEquals("saved while the folder opened", mirrorFile("notes.md").readText(), "the save was replaced by the device's copy")
        assertEquals(emptyList<String>(), leftovers(), "the install left a file of its own in the folder")
    }

    /** A move onto the name that fails leaves the name holding what stood there, not empty. */
    @Test
    fun `a copy whose last move fails leaves what stood at the name in place`() {
        deviceHolds("notes.md", "first")
        sync()
        deviceHolds("notes.md", "newer on the device", modified = DEVICE_TIME + 60_000)
        val engine = engineWithExclusiveMove { _, from, to ->
            if (to == "notes.md" && from.startsWith("copy-")) throw IOException("the move failed")
        }

        open(engine)

        assertTrue(mirrorFile("notes.md").isFile, "the name was left without its file")
        assertEquals("first", mirrorFile("notes.md").readText())
        assertEquals(emptyList<String>(), leftovers())
    }

    /**
     * What the helper costs an open: nothing for a file that lands on a free name, which is every
     * file of a first open, and two processes for a file whose newer device copy replaces the one
     * the mirror holds, one to take it aside and one to put the copy on the name.
     */
    @Test
    fun `an install onto a free name asks no helper, and a replacement asks two`() {
        deviceTree(mapOf("root" to listOf(Child("a.md", contents = "a"), Child("b.md", contents = "b"), Child("c.md", contents = "c"))))
        var asked = 0
        val engine = engineWithExclusiveMove { _, _, _ -> asked++ }

        open(engine)
        assertEquals(listOf("a", "b", "c"), listOf("a.md", "b.md", "c.md").map { mirrorFile(it).readText() })
        assertEquals(0, asked, "a first open asked the helper to put copies on free names")

        deviceTree(mapOf("root" to listOf(
            Child("a.md", contents = "a, newer", modified = DEVICE_TIME + 60_000),
            Child("b.md", contents = "b, newer", modified = DEVICE_TIME + 60_000),
            Child("c.md", contents = "c"),
        )))
        open(engine)

        assertEquals("a, newer", mirrorFile("a.md").readText())
        assertEquals("b, newer", mirrorFile("b.md").readText())
        assertEquals(4, asked, "two changed files did not cost two helper processes each")
        assertEquals(emptyList<String>(), leftovers())
    }

    /** Where no helper can say, the copy goes in as it always did, and nothing is left beside it. */
    @Test
    fun `without the helper the newer device copy still replaces the mirror's and leaves nothing behind`() {
        deviceHolds("notes.md", "first")
        sync()
        deviceHolds("notes.md", "newer on the device", modified = DEVICE_TIME + 60_000)

        sync()

        assertEquals("newer on the device", mirrorFile("notes.md").readText())
        assertEquals(emptyList<String>(), leftovers())
    }
}
