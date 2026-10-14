package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * What the user is told when a folder opens without all of it arriving.
 *
 * A copy that fails is caught, its half-written mirror file deleted, and the run
 * continues; the count reaches only the summary line at the end of [SafSyncEngine.initialSync],
 * which is `Logger.i` and therefore gone from a release build. So the folder opens,
 * the editor shows a tree with holes in it, and nothing distinguishes a file the
 * device never had from one that did not make it across. The user's next move is
 * to create the missing file, which is the write the unread-document guard then
 * has to refuse, and that refusal is the first thing they ever hear about it.
 *
 * The notice carries whether the sizes the provider reported fit in the space that
 * was left, because that is the difference between something the user can act on
 * and something they cannot. The same flag is set when free space fell below
 * [SafSyncEngine.OPEN_SPACE_FLOOR_BYTES], the floor under which no fetch is attempted.
 *
 * A fetch the floor held back also has to leave the folder as the next open needs it:
 * the record still naming a mirror copy an earlier sync wrote, and a file whose device
 * copy already holds the mirror's bytes neither announced nor refused its saves.
 */
class SafOpenShortfallTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri
    private val uris = mutableMapOf<String, Uri>()

    /** Every notice the engine raised, in order. */
    private val notices = mutableListOf<Pair<Int, Boolean>>()

    /** Files a save was refused for, by name. */
    private val lost = mutableListOf<String>()

    /** What saves wrote into each document, by name. */
    private val written = mutableMapOf<String, String>()

    /** Whether the provider reports `COLUMN_LAST_MODIFIED`, which MTP and some others do not. */
    private var hasClock = true

    /** Free space as every reader of the seam sees it, for the cases that move it between opens. */
    private var space = Long.MAX_VALUE

    /** How many times a device document was opened for reading. */
    private var reads = 0

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            uris.getOrPut(secondArg()) { mockk(relaxed = true) }
        }

        resolver = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
        engine.onDocumentsNotCopied = { count, outOfRoom -> notices += count to outOfRoom }
        engine.onWriteBackFailed = { lost += it.name }
        engine.usableSpaceOf = { space }
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        unmockkAll()
    }

    /** One document in the device folder, as the sync will see it. */
    private data class Doc(
        val name: String,
        val size: Long,
        val readable: Boolean = true,
        val text: String = "device contents",
        val modified: Long = OPENED_AT,
    )

    /** A readable document holding [text], reported at its real length. */
    private fun doc(name: String, text: String, modified: Long = OPENED_AT) =
        Doc(name, text.length.toLong(), text = text, modified = modified)

    /**
     * Describes [docs] through a fake cursor, and reads back whatever a readable one
     * holds. An unreadable one throws where the real provider would, which is the
     * failure this notice counts.
     */
    private fun deviceHolding(vararg docs: Doc) {
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        every { cursor.moveToNext() } answers { ++row < docs.size }
        every { cursor.getColumnIndexOrThrow(any()) } answers {
            when (firstArg<String>()) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                else -> 3
            }
        }
        every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } answers {
            if (hasClock) 4 else -1
        }
        every { cursor.isNull(any()) } returns false
        every { cursor.getString(0) } answers { "doc:" + docs[row].name }
        every { cursor.getString(1) } answers { docs[row].name }
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } answers { docs[row].size }
        every { cursor.getLong(4) } answers { docs[row].modified }
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
        every { resolver.openInputStream(any()) } answers {
            reads++
            val doc = docs.first { it.name == nameOf(firstArg()) }
            if (doc.readable) ByteArrayInputStream(doc.text.toByteArray())
            else throw IOException("the provider refused the read")
        }
        every { resolver.openOutputStream(any(), "wt") } answers {
            val name = nameOf(firstArg())
            object : ByteArrayOutputStream() {
                override fun close() {
                    written[name] = String(toByteArray())
                }
            }
        }
    }

    private fun nameOf(uri: Uri): String =
        uris.entries.first { it.value === uri }.key.removePrefix("doc:")

    private fun open() = runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

    /** An editor save of [name], carried to the device by the write-back. */
    private fun save(name: String, text: String) {
        val file = File(mirror, name).apply { writeText(text) }
        engine.handleMirrorEvent(FileObserver.MODIFY, file, mirror, treeUri)
        engine.runWriteBackLoop { false }
    }

    /** The identities the `.synced` record holds after its header, without their digests. */
    private fun recordLines(): List<String> =
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).readLines().drop(1)
            .map { SafSyncEngine.splitRecordLine(it).first }

    private fun belowTheFloor() {
        space = SafSyncEngine.OPEN_SPACE_FLOOR_BYTES - 1
    }

    /** A folder with room to spare, so nothing here turns on the pre-flight. */
    private fun withRoomToSpare() {
        engine.usableSpaceOf = { Long.MAX_VALUE }
    }

    /**
     * The pre-flight sees [room] and the floor sees plenty, so a case about the wording
     * does not turn on whether copies were held back. The pre-flight in
     * [SafSyncEngine.initialSync] is the seam's first reader.
     */
    private fun preflightSees(room: Long) {
        var calls = 0
        engine.usableSpaceOf = { if (calls++ == 0) room else Long.MAX_VALUE }
    }

    /**
     * The case this exists for. One document could not be read, and the user is told
     * that once, with the count.
     */
    @Test
    fun `a document that could not be copied is announced`() {
        withRoomToSpare()
        deviceHolding(Doc("notes.md", 64, readable = false), Doc("readme.md", 64))

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(listOf(1 to false), notices)
    }

    /**
     * The control, and the one that keeps the case above from passing because the
     * seam fires unconditionally. Everything arrived, so there is nothing to say,
     * and a toast on every successful open would train the user to ignore it.
     */
    @Test
    fun `a folder that opens whole announces nothing`() {
        withRoomToSpare()
        deviceHolding(Doc("notes.md", 64), Doc("readme.md", 64))

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertTrue(notices.isEmpty(), "announced $notices for a folder that arrived whole")
    }

    /**
     * A document past [SafSyncEngine.MAX_FILE_SIZE] is not a shortfall. It is a
     * permanent, expected condition of that folder, so announcing it would fire on
     * every open forever, and the unread-document guard already keeps a local file
     * of that name from replacing it.
     */
    @Test
    fun `a document skipped for size is not announced`() {
        withRoomToSpare()
        deviceHolding(Doc("big.zip", SafSyncEngine.MAX_FILE_SIZE + 1), Doc("readme.md", 64))

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertTrue(notices.isEmpty(), "announced $notices for a document skipped by policy")
    }

    /**
     * The same failure, when what the provider reported did not fit in what was left.
     * Same count, different flag, and the flag is the whole reason the pre-flight runs:
     * it is what turns "something went wrong" into a sentence naming free space.
     */
    @Test
    fun `a shortfall with no room left says so`() {
        preflightSees(8)
        deviceHolding(Doc("notes.md", 64, readable = false), Doc("readme.md", 64))

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(listOf(1 to true), notices)
    }

    /**
     * The pre-flight decides the wording, never whether the folder opens. An estimate
     * that says nothing fits still lets every readable document be copied while the
     * disk itself has room, because the reported sizes are a claim and a folder that
     * opens beats a prediction.
     */
    @Test
    fun `the pre-flight estimate does not stop the copy`() {
        preflightSees(0)
        deviceHolding(Doc("readme.md", 64))

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals("device contents", File(mirror, "readme.md").readText())
        assertTrue(notices.isEmpty(), "announced $notices when every copy succeeded")
    }

    /**
     * What does stop a copy is the disk. Once free space falls under the floor, the
     * rest of the folder is held back as failed copies, what already arrived is kept,
     * and the user is told once, with the free-space wording even though the pre-flight
     * saw room.
     */
    @Test
    fun `a fetch below the space floor is held back and announced as a space shortfall`() {
        engine.usableSpaceOf = {
            if (File(mirror, "a.md").exists()) SafSyncEngine.OPEN_SPACE_FLOOR_BYTES - 1
            else Long.MAX_VALUE
        }
        deviceHolding(Doc("a.md", 64), Doc("b.md", 64), Doc("c.md", 64))

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals("device contents", File(mirror, "a.md").readText())
        assertFalse(File(mirror, "b.md").exists(), "b.md was fetched below the floor")
        assertFalse(File(mirror, "c.md").exists(), "c.md was fetched below the floor")
        assertEquals(listOf(2 to true), notices)
    }

    /**
     * A document changed on the device since the last open, held back by the floor while
     * the mirror still holds the copy that open wrote. That copy is exactly what the record
     * vouched for, and the record has to go on saying so: it is what the reclaim and the
     * storage screen read to call the copy disposable rather than work the device folder
     * lacks, and what the next open reads to replace it rather than set it aside.
     */
    @Test
    fun `a changed document held back below the floor keeps its record line`() {
        deviceHolding(doc("notes.md", "v1"), doc("readme.md", "readme"))
        open()
        val vouched = engine.identityLine("notes.md", File(mirror, "notes.md"))
        deviceHolding(doc("notes.md", "v2, edited on a PC", EDITED_AT), doc("readme.md", "readme"))
        belowTheFloor()

        open()

        assertEquals("v1", File(mirror, "notes.md").readText(), "setup: the fetch was not held back")
        assertEquals(listOf(1 to true), notices)
        assertTrue(
            vouched in recordLines(),
            "the record dropped a mirror copy the last sync wrote and nothing has changed since: " +
                recordLines(),
        )
        assertTrue(
            engine.holdsOnlyVouchedCopies(mirror),
            "a stale copy of a device document was called work the device folder does not have",
        )
    }

    /**
     * What the notice invites: free some space and open the folder again. The held-back
     * document arrives, and the older copy it replaces is not set aside as a `.local-`
     * file, which phase 2b would then have put into the device folder for good.
     */
    @Test
    fun `a document held back below the floor is replaced at the next open without a local copy`() {
        deviceHolding(doc("notes.md", "v1"), doc("readme.md", "readme"))
        open()
        deviceHolding(doc("notes.md", "v2, edited on a PC", EDITED_AT), doc("readme.md", "readme"))
        belowTheFloor()
        open()
        space = Long.MAX_VALUE

        open()

        assertEquals(
            listOf("notes.md", "readme.md"), mirror.list()!!.sorted(),
            "the copy an earlier sync wrote was set aside as if it were an edit nobody synced",
        )
        assertEquals("v2, edited on a PC", File(mirror, "notes.md").readText())
        assertTrue(engine.identityLine("notes.md", File(mirror, "notes.md")) in recordLines())
    }

    /**
     * A provider with no clock gets every file the record vouches for fetched again on
     * every open, so below the floor every one of them was held back: the folder was
     * announced as not copied while the editor showed each file as the device holds it,
     * and every save in it was refused for the session. Where the device copy holds the
     * mirror's bytes, reading it settles the file without spending any disk.
     */
    @Test
    fun `a folder with no clock opened below the floor stays writable where nothing changed`() {
        hasClock = false
        deviceHolding(doc("notes.md", "v1"), doc("readme.md", "readme"))
        open()
        belowTheFloor()

        open()

        assertTrue(notices.isEmpty(), "files the editor shows as they are were announced: $notices")
        assertTrue(engine.holdsOnlyVouchedCopies(mirror), "an unchanged mirror was left unvouched")
        save("notes.md", "v1 + typed in the editor")
        assertTrue(lost.isEmpty(), "a save of an unchanged file was refused: $lost")
        assertEquals(mapOf("notes.md" to "v1 + typed in the editor"), written)
    }

    /**
     * The same provider, with the document changed on the device. Held back, it has to be
     * fetched at the next open with room: left out of the record it was kept as an edit of
     * the user's on every open after, the editor showed the old text for good, and the next
     * save of it replaced the device's version with no copy kept anywhere.
     */
    @Test
    fun `a device edit held back below the floor with no clock reaches the editor at the next open`() {
        hasClock = false
        deviceHolding(doc("notes.md", "v1"), doc("readme.md", "readme"))
        open()
        deviceHolding(doc("notes.md", "v2 written on a PC"), doc("readme.md", "readme"))
        belowTheFloor()
        open()
        val heldBack = notices.toList()
        space = Long.MAX_VALUE

        open()
        save("notes.md", File(mirror, "notes.md").readText() + " + typed in the editor")

        assertEquals(
            "v2 written on a PC + typed in the editor", written["notes.md"],
            "the device's edit was replaced by a save of the copy it had replaced",
        )
        assertEquals(listOf(1 to true), heldBack, "more than the changed document was held back")
    }

    /**
     * A file saved in the editor and delivered by the write-back, which the next open
     * fetches again whichever way the device's clock puts that write-back. Stamped after
     * the save, the device copy reads newer and the open copies it; rounded to before the
     * save by a clock coarser than the mirror's, as a FAT card's two seconds or a cloud
     * folder's whole seconds can, the mirror reads newer and the open fetches the device
     * copy to set it aside before writing the mirror back. Below the floor either fetch is
     * held back. Both sides hold the same bytes, so the file the user was working on must
     * neither be announced nor have its saves refused.
     */
    @ParameterizedTest(name = "a saved file stays writable below the floor: device time {0} ms from the save")
    @ValueSource(longs = [1_000, -1_000])
    fun `a saved file held back below the floor stays writable when the device holds the same bytes`(
        stampedAfterSave: Long,
    ) {
        deviceHolding(doc("notes.md", "v1"))
        open()
        File(mirror, "notes.md").apply {
            writeText("v1, saved")
            setLastModified(SAVED_AT)
        }
        deviceHolding(doc("notes.md", "v1, saved", SAVED_AT + stampedAfterSave))
        belowTheFloor()

        open()

        assertTrue(notices.isEmpty(), "a file the device already holds was announced: $notices")
        assertTrue(lost.isEmpty(), "a file the device already holds was announced as kept in the app: $lost")
        save("notes.md", "v1, saved again")
        assertTrue(lost.isEmpty(), "a save of a file the device already holds was refused: $lost")
        assertEquals(mapOf("notes.md" to "v1, saved again"), written)
    }

    /**
     * Why that settle stamps the mirror file with the device's time: left with its own, the
     * next open with room reads the two sides as differing again, and fetches the document
     * once more or writes the identical mirror copy back over it, which on MTP or a network
     * folder is a transfer for nothing.
     */
    @ParameterizedTest(name = "a settled file is left alone at the next open: device time {0} ms from the save")
    @ValueSource(longs = [1_000, -1_000])
    fun `a file settled below the floor is neither fetched nor written at the next open with room`(
        stampedAfterSave: Long,
    ) {
        deviceHolding(doc("notes.md", "v1"))
        open()
        File(mirror, "notes.md").apply {
            writeText("v1, saved")
            setLastModified(SAVED_AT)
        }
        deviceHolding(doc("notes.md", "v1, saved", SAVED_AT + stampedAfterSave))
        belowTheFloor()
        open()
        space = Long.MAX_VALUE
        val readsBefore = reads

        open()

        assertEquals(readsBefore, reads, "a document both sides already hold was read again")
        assertTrue(written.isEmpty(), "a document the device already holds was written back over it: $written")
    }

    /**
     * The control for the case above, and the reason it reads the device copy: an edit the
     * watcher never delivered, newer than one another app made on the device before it.
     * Below the floor that device copy cannot be set aside, and the read finds other bytes,
     * so nothing settles it. It is left alone and the save is refused until an open with
     * room can keep both.
     */
    @Test
    fun `an undelivered edit below the floor is held back where the device copy differs`() {
        deviceHolding(doc("notes.md", "v1"))
        open()
        deviceHolding(doc("notes.md", "v2, edited on a PC", SAVED_AT - 60_000))
        File(mirror, "notes.md").apply {
            writeText("v1 + typed in the editor")
            setLastModified(SAVED_AT)
        }
        belowTheFloor()

        open()
        save("notes.md", "v1 + typed in the editor, and more")

        assertTrue(written.isEmpty(), "the device's edit was written over with no copy kept: $written")
        assertEquals(listOf("notes.md"), lost, "a file held back with its edit was not announced")
    }

    /**
     * `COLUMN_SIZE` is optional, and a provider that withholds it says so in two ways:
     * a null, which `cursor.getLong` flattens to 0, or an explicit -1. The first is
     * harmless because it adds nothing. The second is not: summed as given it would
     * subtract from the estimate, so a folder of documents whose sizes are unknown
     * would come out negative and read as more free space than there is.
     */
    @Test
    fun `a size the provider says it does not know cannot shrink the estimate`() {
        val docs = listOf(
            DocumentInfo(mockk(), "doc:a", "a.md", isDirectory = false, size = -1),
            DocumentInfo(mockk(), "doc:b", "b.md", isDirectory = false, size = 64),
        )

        assertEquals(64L, SafSyncEngine.bytesToFetch(docs, mirror))
    }

    /**
     * Phase 2 refuses anything past [SafSyncEngine.MAX_FILE_SIZE] outright, so those
     * bytes are never fetched and charging the estimate for them would predict a
     * shortfall that cannot happen. One 60 MB archive in the folder would otherwise
     * word every notice as an out-of-space problem the user cannot fix.
     */
    @Test
    fun `a document too large to fetch is not counted`() {
        val docs = listOf(
            DocumentInfo(
                mockk(), "doc:big", "big.zip", isDirectory = false,
                size = SafSyncEngine.MAX_FILE_SIZE + 1,
            ),
            DocumentInfo(mockk(), "doc:b", "b.md", isDirectory = false, size = 64),
        )

        assertEquals(64L, SafSyncEngine.bytesToFetch(docs, mirror))
    }

    /**
     * The same withholding seen from the engine, and the reason the notice can be
     * trusted. A provider that reports nothing makes the estimate 0, which fits in any
     * amount of room, so the shortfall is worded as the provider error it is rather
     * than as a space problem that would send the user deleting files for nothing.
     */
    @Test
    fun `a folder whose sizes were withheld is not blamed on free space`() {
        preflightSees(0)
        deviceHolding(Doc("notes.md", 0, readable = false))

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(listOf(1 to false), notices)
    }

    /**
     * Phase 2 skips a mirror file that already matches the device document, so
     * counting it would inflate the estimate on every reopen of a folder that is
     * fully synced, which is the common case.
     */
    @Test
    fun `a document already mirrored is not counted`() {
        File(mirror, "a.md").writeText("0123456789")
        File(mirror, "a.md").setLastModified(1_700_000_000_000)
        val docs = listOf(
            DocumentInfo(
                mockk(), "doc:a", "a.md", isDirectory = false,
                size = 10, lastModified = 1_700_000_000_000,
            ),
        )

        assertEquals(0L, SafSyncEngine.bytesToFetch(docs, mirror))
    }

    /**
     * A directory has no bytes to fetch and providers report sizes for them that mean
     * nothing, so counting one would charge the estimate for something never copied.
     */
    @Test
    fun `a directory is not counted`() {
        val docs = listOf(
            DocumentInfo(mockk(), "doc:d", "sub", isDirectory = true, size = 4096),
        )

        assertFalse(SafSyncEngine.bytesToFetch(docs, mirror) > 0)
    }

    private companion object {
        const val OPENED_AT = 1_700_000_000_000L
        const val SAVED_AT = 1_700_000_300_000L
        const val EDITED_AT = 1_700_000_500_000L
    }
}
