package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
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
import java.io.ByteArrayInputStream
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
 * and something they cannot.
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
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        unmockkAll()
    }

    /** One document in the device folder, as the sync will see it. */
    private data class Doc(val name: String, val size: Long, val readable: Boolean = true)

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
        every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns 4
        every { cursor.isNull(any()) } returns false
        every { cursor.getString(0) } answers { "doc:" + docs[row].name }
        every { cursor.getString(1) } answers { docs[row].name }
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } answers { docs[row].size }
        every { cursor.getLong(4) } returns 1_700_000_000_000
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
        every { resolver.openInputStream(any()) } answers {
            val name = uris.entries.first { it.value === firstArg<Uri>() }.key.removePrefix("doc:")
            val doc = docs.first { it.name == name }
            if (doc.readable) ByteArrayInputStream("device contents".toByteArray())
            else throw IOException("the provider refused the read")
        }
    }

    /** A folder with room to spare, so nothing here turns on the pre-flight. */
    private fun withRoomToSpare() {
        engine.usableSpaceOf = { Long.MAX_VALUE }
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
        engine.usableSpaceOf = { 8 }
        deviceHolding(Doc("notes.md", 64, readable = false), Doc("readme.md", 64))

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(listOf(1 to true), notices)
    }

    /**
     * The pre-flight decides the wording, never whether the folder opens. A device
     * that is genuinely out of room still gets its readable documents copied, because
     * the reported sizes are a claim and a folder that opens beats a prediction.
     */
    @Test
    fun `no room left does not stop the copy`() {
        engine.usableSpaceOf = { 0 }
        deviceHolding(Doc("readme.md", 64))

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals("device contents", File(mirror, "readme.md").readText())
        assertTrue(notices.isEmpty(), "announced $notices when every copy succeeded")
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
        engine.usableSpaceOf = { 0 }
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
}
