package com.vscodroid.storage

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * A device copy an open puts in place of the mirror's, meeting a save made into that file after
 * the open judged it.
 *
 * The open looked at the file, judged it replaceable, and renamed the fetched copy over it. A
 * rename replaces whatever stands at its destination, so an editor's save into the file in
 * between went with it, with no copy of it anywhere. The file is now held open while the copy
 * goes in, and a save found in it afterwards is kept beside the file as a `.local-` copy, with
 * the notice every kept copy gets. No process is started for it, and a free name is still looked
 * at and then renamed onto.
 */
class SafInstallRaceTest : SafInitialSyncTestBase() {

    private val keptNotices = mutableListOf<List<String>>()

    private fun deviceHolds(name: String, contents: String, modified: Long = DEVICE_TIME) {
        deviceTree(mapOf("root" to listOf(Child(name, contents = contents, modified = modified))))
    }

    /** An engine that counts the move helper processes it would start, which none of these cases may. */
    private fun engineCountingHelpers(count: () -> Unit = {}): SafSyncEngine =
        SafSyncEngine(context).also { engine ->
            engine.onWriteBackFailed = { lost += it.name }
            engine.onLocalCopiesPreserved = { files -> keptNotices += files.map { it.name } }
            engine.exclusiveMove = { directory, _, from, to ->
                count()
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

    private fun leftovers() = mirror.list()!!.filter { it.endsWith(SafSyncEngine.PARTIAL_SUFFIX) }

    private fun keptCopies() = mirror.list()!!.filter { it.startsWith("notes.md" + SafSyncEngine.LOCAL_COPY_SUFFIX) }

    /**
     * The editor saves into the file itself, which is how it saves, between the open's judgement
     * of the file and the rename that puts the device's copy in its place.
     */
    @Test
    fun `a save written into the file as the newer device copy replaces it is kept beside it`() {
        deviceHolds("notes.md", "first")
        sync()
        deviceHolds("notes.md", "newer on the device", modified = DEVICE_TIME + 60_000)
        var saved = false
        val engine = engineCountingHelpers()
        engine.replaceName = { dest, rename ->
            if (dest.name == "notes.md" && !saved) {
                saved = true
                dest.writeText("saved while the folder opened")
            }
            rename()
        }

        open(engine)

        assertTrue(saved, "the copy was not renamed into place through the seam")
        assertEquals("newer on the device", mirrorFile("notes.md").readText())
        assertEquals(1, keptCopies().size, "the save was not kept beside the file: ${mirror.list()!!.toList()}")
        assertEquals("saved while the folder opened", mirrorFile(keptCopies().single()).readText())
        assertEquals(listOf(keptCopies()), keptNotices, "the kept save was not announced once")
        assertTrue(created.any { it.second == keptCopies().single() }, "the kept save did not go to the device folder")
        assertEquals(emptyList<String>(), leftovers())
    }

    /** A file nobody touched is replaced with nothing kept and nothing said. */
    @Test
    fun `a file nobody saved into is replaced with nothing kept beside it`() {
        deviceHolds("notes.md", "first")
        sync()
        deviceHolds("notes.md", "newer on the device", modified = DEVICE_TIME + 60_000)
        val engine = engineCountingHelpers()

        open(engine)

        assertEquals("newer on the device", mirrorFile("notes.md").readText())
        assertEquals(emptyList<String>(), keptCopies())
        assertTrue(keptNotices.isEmpty())
    }

    /**
     * What putting copies in place costs in processes: none, for a first open, whose files all land
     * on free names, and for an update, whose newer device copies replace the mirror's. A helper
     * process per replaced file, at about 65 ms each on an emulator, made a reopen with 200 changed
     * files take 30 s.
     */
    @Test
    fun `an open starts no process to put copies in place, on free names or over the mirror's`() {
        deviceTree(mapOf("root" to listOf(Child("a.md", contents = "a"), Child("b.md", contents = "b"), Child("c.md", contents = "c"))))
        var asked = 0
        val engine = engineCountingHelpers { asked++ }

        open(engine)
        assertEquals(listOf("a", "b", "c"), listOf("a.md", "b.md", "c.md").map { mirrorFile(it).readText() })
        assertEquals(0, asked, "a first open started a process to put copies on free names")

        deviceTree(mapOf("root" to listOf(
            Child("a.md", contents = "a, newer", modified = DEVICE_TIME + 60_000),
            Child("b.md", contents = "b, newer", modified = DEVICE_TIME + 60_000),
            Child("c.md", contents = "c"),
        )))
        open(engine)

        assertEquals("a, newer", mirrorFile("a.md").readText())
        assertEquals("b, newer", mirrorFile("b.md").readText())
        assertEquals(0, asked, "replacing two changed files started a process")
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
