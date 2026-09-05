package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
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
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections

/**
 * What a sync does with the device copy of a file whose upload it has to repair.
 *
 * The branch reached here is phase 2's repair: the journal says an upload to this
 * document started and never finished, so the mirror holds the only complete copy and is
 * written back over it. That is safe only while the document really is this app's own
 * truncated write, and where the bytes say otherwise the device copy is fetched beside
 * the mirror as `.device-<time>` first.
 *
 * Two properties of that fetch are what these cases hold to. It lands in `filesDir`, this
 * app's own storage and the thing that runs out, so it obeys the same ceiling every other
 * inbound copy obeys, even though the branch runs above the gate that applies it. And it
 * ends three ways rather than two: a copy kept, a copy deleted again for being identical
 * to the mirror, and a document that could not be brought over at all. Only the first is
 * a set-aside, and the count and the log line a maintainer reads to account for
 * `.device-` files in a user's folder are the reason that difference has to survive the
 * return.
 */
class SafDeviceCopySetAsideTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri

    /** Every string the app handed the log. */
    private val emitted: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The journal, as the engine names it beside the mirrors it serves. */
    private val journal: File get() = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)

    @BeforeEach
    fun setUp() {
        emitted.clear()

        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } answers { emitted += secondArg<String>(); 0 }
        every { android.util.Log.d(any(), any()) } answers { emitted += secondArg<String>(); 0 }
        every { android.util.Log.w(any(), any<String>()) } answers { emitted += secondArg<String>(); 0 }
        every { android.util.Log.e(any(), any()) } answers { emitted += secondArg<String>(); 0 }

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * The device folder answers for one document: [name], holding [bytes], reported as
     * [size] bytes and modified at [modified].
     *
     * [size] is a parameter rather than the length of [bytes] because that is the case
     * the ceiling exists for: what phase 2 judges is what `COLUMN_SIZE` says, and no test
     * can afford to write the several gigabytes a real one would name. The stream is real
     * rather than a relaxed mock for the reason the sibling suite gives: `read` answering
     * 0 makes `copyTo` spin for ever, and the repair reads the document before it decides
     * anything.
     */
    private fun deviceHolding(name: String, bytes: String, size: Long, modified: Long) {
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        every { cursor.moveToNext() } answers { ++row == 0 }
        every { cursor.getColumnIndexOrThrow(any()) } answers {
            when (firstArg<String>()) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                else -> 3
            }
        }
        every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns 4
        every { cursor.isNull(any()) } returns false
        every { cursor.getString(0) } returns "doc:$name"
        every { cursor.getString(1) } returns name
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } returns size
        every { cursor.getLong(4) } returns modified
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream(bytes.toByteArray()) }
    }

    /** A mirror file an unfinished upload left the journal naming. */
    private fun strandedUploadOf(name: String, text: String): File =
        File(mirror, name).apply {
            writeText(text)
            setLastModified(1_000_000_000_000L)
            journal.writeText(absolutePath)
        }

    private fun namesInMirror(): List<String> = mirror.list()!!.sorted()

    /**
     * A document the mirror could never have held is not pulled into `filesDir` by the
     * repair.
     *
     * The gate that keeps a large device document out of this app's own storage sits
     * below this branch and has not judged the document yet, so the fetch used to run
     * whatever its size: a file the user grew to gigabytes in the terminal and whose
     * write-back failed had its device copy streamed into internal storage behind a
     * dialog nobody can cancel, and a process death in the middle left the multi-gigabyte
     * scratch file behind.
     *
     * Held back rather than skipped, which is why the gate cannot simply move above the
     * branch: skipping would leave the interrupted upload unrepaired with its journal
     * line standing, so the mirror keeps the only complete copy and the next open tries
     * the whole repair again.
     */
    @Test
    fun `a device copy too large for this app's storage is not fetched to set aside`() {
        val local = strandedUploadOf("dump.sql", "the whole export, edited here")
        deviceHolding(
            "dump.sql",
            "an export written on the device",
            size = SafSyncEngine.MAX_FILE_SIZE + 1,
            modified = 2_000_000_000_000L,
        )

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(
            listOf("dump.sql"), namesInMirror(),
            "the repair fetched a device document into this app's own storage that the " +
                "size gate exists to keep out",
        )
        assertEquals(
            listOf(local.absolutePath), engine.uploadsInFlight().toList(),
            "the repair was withheld, so its journal line has to stand for the next open " +
                "to try again",
        )
        assertEquals(
            "the whole export, edited here", local.readText(),
            "the mirror held the only complete copy and the sync overwrote it",
        )
    }

    /**
     * The count and the sentence that account for `.device-` files say nothing when there
     * is no such file.
     *
     * Both sides holding the same bytes is the ordinary end of a delivered write-back,
     * not an exotic case: the record never learns the time that write left on the device,
     * and a provider with coarse stamps reports one below the mirror's, so a folder of
     * saved files arrives here on every reopen. The fetched copy is deleted again exactly
     * so no duplicate reaches the user's folder, and counting it as set aside sent a
     * maintainer looking for hundreds of files that were never created.
     */
    @Test
    fun `a device copy identical to the mirror is not counted as set aside`() {
        strandedUploadOf("notes.txt", "the whole edit")
        deviceHolding("notes.txt", "the whole edit", size = 14L, modified = 2_000_000_000_000L)

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(
            listOf("notes.txt"), namesInMirror(),
            "setup failed: this case is the one where no copy is kept",
        )
        assertTrue(
            emitted.any { it.contains("0 set aside") },
            "the summary claims a file was set aside that this sync deleted again for " +
                "being a duplicate: $emitted",
        )
        assertFalse(
            emitted.any { it.contains("was preserved first") },
            "the line a maintainer reads to explain a .device- file was written for a " +
                "file that does not exist: $emitted",
        )
    }

    /**
     * The control, in the case the guard exists for: a device copy that differs is kept,
     * counted and named.
     */
    @Test
    fun `a device copy that differs is preserved, counted and named`() {
        strandedUploadOf("notes.txt", "the whole edit")
        deviceHolding("notes.txt", "written on the device", size = 21L, modified = 2_000_000_000_000L)

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(
            listOf("notes.txt", "notes.txt" + SafSyncEngine.DEVICE_COPY_SUFFIX + "2000000000000"),
            namesInMirror(),
            "the device's own bytes were replaced by the mirror with no copy kept",
        )
        assertTrue(
            emitted.any { it.contains("1 set aside") },
            "a copy was left in the user's folder and the summary does not account for " +
                "it: $emitted",
        )
    }
}
