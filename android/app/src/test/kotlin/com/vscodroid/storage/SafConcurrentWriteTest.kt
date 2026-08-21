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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * That two writers never hold one device document open at the same time.
 *
 * `openOutputStream(uri, "wt")` truncates at open. Two writers of one document
 * therefore produce a file that is neither of their inputs, and the user's copy on
 * the device is corrupt in a way nothing detects: it has a plausible size, a
 * plausible timestamp, and content spliced from two streams.
 *
 * Two writers are reachable rather than theoretical. The mirror is named by a hash
 * of the folder rather than by the session, so reopening a folder while the
 * previous session's drain is still streaming puts `initialSync` on the IO
 * dispatcher and that drain on the same document.
 *
 * The exclusion is deliberately non-blocking. A lock the loser waits on would trade
 * this corruption for an unbounded stall: a `ContentResolver` stream to a network
 * or MTP provider has no timeout, and `initialSync` runs behind a dialog built with
 * `setCancelable(false)`. What these pin is both halves of that: no overlap, and no
 * waiting.
 */
class SafConcurrentWriteTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri

    /** How many writers hold a stream open right now, and the worst seen. */
    private val open = AtomicInteger(0)
    private val peak = AtomicInteger(0)

    /**
     * How many streams were opened at all.
     *
     * Separate from [peak] because the two answer different questions, and a test that
     * only asks [peak] passes for an exclusion that claims a document and never lets
     * go: one write happens, so the peak is one and nothing overlaps. Measured, on this
     * very file, before this counter existed.
     */
    private val everOpened = AtomicInteger(0)

    /** Held open until released, so a second writer meets a first that has not finished. */
    private val firstWriterInside = CountDownLatch(1)
    private val releaseFirstWriter = CountDownLatch(1)
    private var blockFirstWriter = false

    @BeforeEach
    fun setUp() {
        open.set(0)
        peak.set(0)
        everOpened.set(0)
        blockFirstWriter = false

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
        // One document, one identity. Two writers of the same path must resolve to the
        // same URI string or the exclusion would be measuring nothing.
        val doc = mockk<Uri>(relaxed = true)
        every { doc.toString() } returns "content://test/doc/notes.md"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns doc
        every { DocumentsContract.getDocumentId(any()) } returns "doc:notes.md"
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } returns doc

        resolver = mockk(relaxed = true)
        every { resolver.openOutputStream(any(), any()) } answers { countingStream() }

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir

        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        releaseFirstWriter.countDown()
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        unmockkAll()
    }

    /**
     * A stream that records how many of its kind are open at once, and optionally
     * holds the first one open until the test lets it go.
     */
    private fun countingStream(): OutputStream {
        val holdThisOne = blockFirstWriter && open.get() == 0
        val now = open.incrementAndGet()
        everOpened.incrementAndGet()
        peak.updateAndGet { maxOf(it, now) }
        if (holdThisOne) {
            firstWriterInside.countDown()
            releaseFirstWriter.await(5, TimeUnit.SECONDS)
        }
        return object : ByteArrayOutputStream() {
            override fun close() {
                open.decrementAndGet()
                super.close()
            }
        }
    }

    /** One device document, already present, so a local edit is a write and not a create. */
    private fun deviceHolding(name: String) {
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
        every { cursor.getLong(3) } returns 4
        every { cursor.getLong(4) } returns 1_700_000_000_000
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
    }

    private fun deliver(entry: String) {
        engine.handleMirrorEvent(FileObserver.MODIFY, File(mirror, entry), mirror, treeUri)
        engine.runWriteBackLoop { false }
    }

    /**
     * The case the exclusion exists for. One writer is held inside its stream while a
     * second is driven at the same document.
     */
    @Test
    fun `two writers never hold one document open at once`() {
        deviceHolding("notes.md")
        File(mirror, "notes.md").writeText("edited in the editor")
        blockFirstWriter = true

        val first = Thread { deliver("notes.md") }.apply { start() }
        assertTrue(
            firstWriterInside.await(5, TimeUnit.SECONDS),
            "the first writer never reached its stream, so nothing was contended",
        )

        // The second writer meets a document that is being streamed into right now.
        deliver("notes.md")

        releaseFirstWriter.countDown()
        first.join(5_000)

        assertEquals(1, peak.get(), "two writers held the same document open at once")
    }

    /**
     * And the second writer does not wait for the first. This is the half that keeps
     * the fix from being a trade: a writer that blocked here would stall `initialSync`
     * for as long as a network provider takes, behind a dialog that cannot be
     * cancelled.
     */
    @Test
    fun `the second writer returns rather than waiting`() {
        deviceHolding("notes.md")
        File(mirror, "notes.md").writeText("edited in the editor")
        blockFirstWriter = true

        val first = Thread { deliver("notes.md") }.apply { start() }
        assertTrue(firstWriterInside.await(5, TimeUnit.SECONDS), "nothing was contended")

        val started = System.nanoTime()
        deliver("notes.md")
        val waitedMs = (System.nanoTime() - started) / 1_000_000

        releaseFirstWriter.countDown()
        first.join(5_000)

        assertTrue(
            waitedMs < 1_000,
            "the second writer waited ${waitedMs}ms for the first, which is the stall " +
                "this design exists to avoid",
        )
    }

    /**
     * The control, and it is what stops the two above from passing because writes stopped
     * happening. An uncontended write still writes.
     */
    @Test
    fun `an uncontended write still reaches the document`() {
        deviceHolding("notes.md")
        File(mirror, "notes.md").writeText("edited in the editor")

        deliver("notes.md")

        assertEquals(1, everOpened.get(), "the write never opened its document")
        assertEquals(0, open.get(), "the stream was left open")
    }

    /**
     * The second control: a writer that finished releases the document, so the next
     * write is not refused for ever. Without this the exclusion could pass every case
     * above by claiming a document once and never letting go.
     */
    @Test
    fun `a document is writable again once its writer is done`() {
        deviceHolding("notes.md")
        File(mirror, "notes.md").writeText("first edit")
        deliver("notes.md")

        File(mirror, "notes.md").writeText("second edit")
        deliver("notes.md")

        assertEquals(1, peak.get(), "two writers overlapped across separate saves")
        assertEquals(0, open.get(), "a stream was left open")
        assertEquals(
            2, everOpened.get(),
            "the second save never reached the document, so the exclusion is claiming " +
                "it and never letting go rather than serialising writers",
        )
    }
}
