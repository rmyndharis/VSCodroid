package com.vscodroid.storage

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

    /** The files whose save, caught as the device's copy went in, could not be kept whole, by name. */
    private val incompleteNotices = mutableListOf<String>()

    private fun deviceHolds(name: String, contents: String, modified: Long = DEVICE_TIME) {
        deviceTree(mapOf("root" to listOf(Child(name, contents = contents, modified = modified))))
    }

    /** An engine that counts the move helper processes it would start, which none of these cases may. */
    private fun engineCountingHelpers(count: () -> Unit = {}): SafSyncEngine =
        SafSyncEngine(context).also { engine ->
            engine.onWriteBackFailed = { lost += it.name }
            engine.onLocalCopiesPreserved = { files -> keptNotices += files.map { it.name } }
            engine.onDocumentsNotCopied = { count, _ -> notCopied += count }
            engine.onHeldSaveIncomplete = { incompleteNotices += it.name }
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
        assertEquals(emptyList<String>(), incompleteNotices, "a save kept whole was said to be incomplete")
        assertEquals(emptyList<String>(), leftovers())
    }

    /** The row the record gives `notes.md`, with whether it carries a digest. */
    private fun notesRowHasDigest() = record.readLines().drop(1).single { it.startsWith("notes.md\t") }
        .let { SafSyncEngine.splitRecordLine(it).second != null }

    /**
     * Once the device's copy has been renamed over the name, the save lives only in the replaced file, held open. A
     * write into its `.local-` copy that failed, a full disk or an I/O error, threw out of the install: the held file
     * was closed and freed, nothing said the copy beside the file was a part of the save, and the open counted the
     * file as not copied though the editor's copy now held the device's version.
     */
    @Test
    fun `a save whose kept copy cannot be written in full is kept as far as it got and said to be incomplete`() {
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
        // The first copy of the save is written; the disk fills before the second confirms it.
        engine.heldSaveSettle = { throw IOException("No space left on device") }

        open(engine)

        assertTrue(saved, "the copy was not renamed into place through the seam")
        assertEquals("newer on the device", mirrorFile("notes.md").readText())
        assertEquals(1, keptCopies().size, "what was written of the save was not kept: ${mirror.list()!!.toList()}")
        assertEquals("saved while the folder opened", mirrorFile(keptCopies().single()).readText())
        assertEquals(listOf(keptCopies()), keptNotices, "the kept copy was not announced")
        assertEquals(listOf("notes.md"), incompleteNotices, "nothing said the kept copy may be incomplete")
        assertEquals(0, notCopied, "the device's copy, which went in, was counted as not copied")
        assertTrue(notesRowHasDigest(), "the device's copy the editor now holds was not recorded")
        assertEquals(emptyList<String>(), leftovers())
    }

    /** Where not even the kept copy can be made, nothing of the save is kept, and that is said. */
    @Test
    fun `a save whose kept copy cannot be made is said to be lost and the device's copy stays in place`() {
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
        engine.createKeptSave = { _, _ -> throw IOException("No space left on device") }

        open(engine)

        assertTrue(saved, "the copy was not renamed into place through the seam")
        assertEquals("newer on the device", mirrorFile("notes.md").readText())
        assertEquals(emptyList<String>(), keptCopies())
        assertTrue(keptNotices.isEmpty(), "a kept copy that was never made was announced")
        assertEquals(listOf("notes.md"), incompleteNotices, "nothing said the save could not be kept")
        assertEquals(0, notCopied, "the device's copy, which went in, was counted as not copied")
        assertTrue(notesRowHasDigest(), "the device's copy the editor now holds was not recorded")
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

    /**
     * The editor saves by cutting the file and writing it again, and had written part of the save when the device's
     * copy went in; the rest lands in the file the copy replaced. The save was kept as the first look at it found
     * it, half a file, and the editor, reloading, showed the device's copy: the rest of the save was nowhere.
     */
    @Test
    fun `a save still being written as the newer device copy replaces it is kept whole`() {
        deviceHolds("notes.md", "first")
        sync()
        deviceHolds("notes.md", "newer on the device", modified = DEVICE_TIME + 60_000)
        val save = "the first half of a save, and then the second half of it"
        val half = save.length / 2
        var writer: FileOutputStream? = null
        val engine = engineCountingHelpers()
        engine.replaceName = { dest, rename ->
            if (dest.name == "notes.md" && writer == null) writer = FileOutputStream(dest).apply { write(save.take(half).toByteArray()) }
            rename()
        }
        engine.heldSaveSettle = {
            writer?.run {
                write(save.drop(half).toByteArray())
                close()
            }
            writer = null
        }

        try {
            open(engine)
        } finally {
            writer?.close()
        }

        assertEquals("newer on the device", mirrorFile("notes.md").readText())
        assertEquals(1, keptCopies().size, "the save was not kept beside the file: ${mirror.list()!!.toList()}")
        assertEquals(save, mirrorFile(keptCopies().single()).readText(), "the kept save holds only what was written before it was copied")
    }

    /** A save that ends shorter than what the first look found leaves no tail of the longer state behind. */
    @Test
    fun `a save that shrinks while it is kept leaves no tail behind`() {
        deviceHolds("notes.md", "first")
        sync()
        deviceHolds("notes.md", "newer on the device", modified = DEVICE_TIME + 60_000)
        var writer: FileOutputStream? = null
        val engine = engineCountingHelpers()
        engine.replaceName = { dest, rename ->
            if (dest.name == "notes.md" && writer == null) writer = FileOutputStream(dest).apply { write("a longer state of the save".toByteArray()) }
            rename()
        }
        engine.heldSaveSettle = {
            writer?.run {
                channel.truncate(0)
                channel.position(0)
                write("the end".toByteArray())
                close()
            }
            writer = null
        }

        try {
            open(engine)
        } finally {
            writer?.close()
        }

        assertEquals("the end", mirrorFile(keptCopies().single()).readText())
    }

    /** A writer that never stops is not waited for without end: what it holds at the last look is kept. */
    @Test
    fun `a save that keeps changing is kept as the last look found it, within a bound`() {
        deviceHolds("notes.md", "first")
        sync()
        deviceHolds("notes.md", "newer on the device", modified = DEVICE_TIME + 60_000)
        var writer: FileOutputStream? = null
        val engine = engineCountingHelpers()
        engine.replaceName = { dest, rename ->
            if (dest.name == "notes.md" && writer == null) writer = FileOutputStream(dest).apply { write("start".toByteArray()) }
            rename()
        }
        engine.heldSaveSettle = { writer?.write('.'.code) }

        val started = System.nanoTime()
        try {
            open(engine)
        } finally {
            writer?.close()
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(tookMs < 10_000, "the open waited ${tookMs}ms for a writer that never stops")
        assertTrue(mirrorFile(keptCopies().single()).readText().startsWith("start."), "the kept copy lost what was written")
    }

    /**
     * What a replaced copy costs in reads of the old one. Each read of a copy before it is replaced is a read of the
     * whole file on the device's storage, and a checkout on the device that changes many files pays it per file.
     *
     * Exactly, not at most: every read is one of the looks [EXPECTED_OLD_COPY_READS] names, so a count that drops is a
     * check that went. A change that needs fewer reads lowers the number and says which look it removed.
     */
    @Test
    fun `replacing a copy reads the old one in full exactly as often as its checks need`() {
        deviceHolds("notes.md", "first")
        sync()
        deviceHolds("notes.md", "newer on the device", modified = DEVICE_TIME + 60_000)
        var reads = 0
        afterReading("first") { reads++ }

        open(engineCountingHelpers())

        assertEquals("newer on the device", mirrorFile("notes.md").readText())
        assertEquals(
            EXPECTED_OLD_COPY_READS, reads,
            "the old copy was read in full $reads times: more costs a read of the whole file per replaced copy, " +
                "fewer means one of the looks the count names was dropped",
        )
    }

    private companion object {
        /**
         * The look before the fetch, the look once it has landed, two by the check of the old copy against its row
         * ([SafSyncEngine] `setAsideDivergedMirror`), the look after that check, and the look at the replaced file
         * once the copy is in place. The open descriptor is no longer read through once more before the rename: the
         * look a moment before had read it, and its attributes and length say it is the same file, unchanged.
         */
        const val EXPECTED_OLD_COPY_READS = 6
    }
}
