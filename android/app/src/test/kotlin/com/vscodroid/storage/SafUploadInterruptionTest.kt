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
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import java.io.ByteArrayInputStream
import java.io.File

/**
 * What an interrupted upload leaves behind, and what the next sync does with it.
 *
 * A write-back opens its document with `"wt"`, which truncates at open, and a
 * provider stamps a fresh modification time on the truncated bytes. When the copy
 * dies partway -- the low-memory killer, a swipe-away during the drain, or any
 * provider error -- the device holds a *newer, shorter* copy of a file whose only
 * complete version is the mirror's. That is precisely the shape
 * [SafSyncEngine.shouldOverwriteMirror] treats as "the device edited this", so
 * the next reopen copied the truncation over the edit and the work was gone from
 * both sides. Nothing in the comparison can tell the two apart on its own: the
 * difference is whether this app was mid-upload, and only this app can know that.
 *
 * The journal here is that memory: a path is recorded before its stream opens
 * and removed once the copy lands, so a path still listed at the next sync is one
 * whose device copy must not be trusted, whatever its clock says. The mirror is
 * kept instead and the upload attempted again, which is also the repair: a
 * journal entry that outlived its write can only mean the write never finished.
 */
class SafUploadInterruptionTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri
    private val uris = mutableMapOf<String, Uri>()

    /** The journal, as the engine names it beside the mirrors it serves. */
    private val journal: File get() = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)

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
        every { DocumentsContract.getDocumentId(any()) } answers {
            uris.entries.first { it.value === firstArg<Uri>() }.key
        }

        resolver = mockk(relaxed = true)
        context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * The device folder answers for one file, the way the write-back path needs:
     * a child query that names it, so the event resolves a document to write into.
     */
    private fun deviceHolding(name: String) {
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        every { cursor.moveToNext() } answers { ++row == 0 }
        every { cursor.getColumnIndexOrThrow(any()) } answers {
            if (firstArg<String>() == DocumentsContract.Document.COLUMN_DOCUMENT_ID) 0 else 1
        }
        every { cursor.getString(0) } answers { "doc:$name" }
        every { cursor.getString(1) } answers { name }
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
    }

    /** One event, and the write-back thread's pass over what it queued. */
    private fun deliver(event: Int, entry: String) {
        engine.handleMirrorEvent(event, File(mirror, entry), mirror, treeUri)
        engine.runWriteBackLoop { false }
    }

    @Test
    fun `a write-back that cannot finish leaves its path recorded`() {
        deviceHolding("notes.txt")
        File(mirror, "notes.txt").writeText("the whole edit")
        every { resolver.openOutputStream(any(), any()) } throws java.io.IOException("cut short")

        deliver(android.os.FileObserver.MODIFY, "notes.txt")

        assertEquals(
            listOf(File(mirror, "notes.txt").absolutePath),
            journal.readLines(),
            "the one fact that separates a truncated copy from a device edit is " +
                "whether an upload to it never finished, and that fact lives here"
        )
    }

    @Test
    fun `a write-back that lands removes the record`() {
        deviceHolding("notes.txt")
        File(mirror, "notes.txt").writeText("the whole edit")
        every { resolver.openOutputStream(any(), any()) } returns
            java.io.ByteArrayOutputStream()

        deliver(android.os.FileObserver.MODIFY, "notes.txt")

        assertFalse(journal.isFile, "a finished upload must not distrust its own document")
    }

    @Test
    fun `a sync prefers the mirror of a file whose upload was cut short`() {
        // The device's copy is newer and shorter: exactly what a truncation looks
        // like, and exactly what an edit made on the device looks like. The
        // journal is the only thing that can tell them apart.
        val local = File(mirror, "notes.txt").apply { writeText("the whole edit") }
        local.setLastModified(1_000_000_000_000L)
        journal.writeText(local.absolutePath)
        val byDocId = mapOf("doc:notes.txt" to "TRUNC")

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
        every { cursor.getString(0) } returns "doc:notes.txt"
        every { cursor.getString(1) } returns "notes.txt"
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } returns 5L
        every { cursor.getLong(4) } returns 2_000_000_000_000L
        every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream(byDocId.getValue("doc:notes.txt").toByteArray())
        }

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(
            "the whole edit", local.readText(),
            "the device's newer mtime was this app's own interrupted upload; the " +
                "edit existed only here"
        )
        // A missing journal is the emptied one: the sync deleted it on consuming
        // the last line, and reading the file would mistake repair for failure.
        val listed = if (journal.isFile) journal.readLines() else emptyList()
        assertFalse(
            listed.contains(local.absolutePath),
            "the record was consumed by the sync that acted on it"
        )
        // Delivery, not enqueueing: the write-back queue belongs to the watcher's
        // lifecycle, and startWatching opens by clearing it, so a job offered
        // between the sync and the thread that would run it never executes. The
        // repair has to have reached the document inside the sync itself.
        verify(exactly = 1) { resolver.openOutputStream(any(), "wt") }
    }

    @Test
    fun `reclaiming a mirror takes its journal entries with it`() {
        val keptMirror = File(mirror, "kept").apply { mkdirs() }
        val gone = File(filesDir, "saf-mirrors/gone-hash").apply { mkdirs() }
        File(gone, "notes.txt").writeText("x")
        journal.writeText(
            listOf(
                File(gone, "notes.txt").absolutePath,
                File(keptMirror, "other.txt").absolutePath
            ).joinToString("\n")
        )

        engine.clearUploadsUnder(gone)

        val listed = if (journal.isFile) journal.readLines() else emptyList()
        assertEquals(
            listOf(File(keptMirror, "other.txt").absolutePath), listed,
            "entries under a mirror being reclaimed must not outlive it; the " +
                "same folder re-granted weeks later hashes back to the same path, " +
                "and a surviving entry would throw away the device edits that " +
                "followed"
        )
    }

    /**
     * Two write-backs of one file overlap: a drain that outlived the folder it belongs
     * to is still streaming when the next sync writes the same path. Both bracket the
     * copy with the journal, and the journal has one line per path, so the writer that
     * finished first removed the bracket the other was still inside. A death in that
     * window then left a device copy that is newer, shorter and unrecorded, which the
     * next sync reads as an edit made on the device and copies over the mirror.
     */
    @Test
    fun `a path stays recorded while another write to it is still in flight`() {
        deviceHolding("notes.txt")
        val file = File(mirror, "notes.txt").apply { writeText("the whole edit") }
        val released = AtomicBoolean(false)
        val firstWriteStarted = CountDownLatch(1)
        var opens = 0
        every { resolver.openOutputStream(any(), any()) } answers {
            if (opens++ == 0) {
                object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        firstWriteStarted.countDown()
                        // Uninterruptible: the point is a write still in flight.
                        while (!released.get()) {
                            try { Thread.sleep(10) } catch (_: InterruptedException) { }
                        }
                    }
                }
            } else {
                ByteArrayOutputStream()
            }
        }

        // The first writer: this engine's drain, still streaming.
        engine.handleMirrorEvent(FileObserver.MODIFY, file, mirror, treeUri)
        val drain = thread(isDaemon = true) { engine.runWriteBackLoop { false } }
        assertTrue(
            firstWriteStarted.await(5, TimeUnit.SECONDS),
            "setup failed: the first write never reached the provider",
        )

        // The second: a different engine over the same journal, which is what a
        // recreated Activity builds, writing the same file and finishing at once.
        val second = SafSyncEngine(context)
        second.handleMirrorEvent(FileObserver.MODIFY, file, mirror, treeUri)
        second.runWriteBackLoop { false }

        assertEquals(
            listOf(file.absolutePath), engine.uploadsInFlight().toList(),
            "the writer that landed first cleared the record the other one is inside",
        )

        released.set(true)
        drain.join(5_000)
        assertFalse(journal.isFile, "the record outlived the last writer")
    }

    /**
     * The record a dead process left is consumed without touching a live writer's claim.
     *
     * `initialSync` clears the record for a path it is about to write itself, and that is
     * a different operation from a writer releasing its own claim. Spelling it as one
     * would decrement a live writer to zero and drop the line while that writer was still
     * streaming, which is the defect the count exists to prevent, reached by another door.
     * Reopening the same folder is what puts a live writer and a stale record on one path,
     * because the mirror is named by a hash of the folder rather than by the session.
     */
    @Test
    fun `consuming a stale record leaves a live writer's claim alone`() {
        deviceHolding("notes.txt")
        val file = File(mirror, "notes.txt").apply { writeText("the whole edit") }
        val released = AtomicBoolean(false)
        val firstWriteStarted = CountDownLatch(1)
        // Only the drain's write blocks. The sync writes the same file itself right
        // after consuming the record, and blocking that one would hang the test on the
        // very call it is here to observe.
        var opens = 0
        every { resolver.openOutputStream(any(), any()) } answers {
            if (opens++ == 0) {
                object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        firstWriteStarted.countDown()
                        while (!released.get()) {
                            try { Thread.sleep(10) } catch (_: InterruptedException) { }
                        }
                    }
                }
            } else {
                ByteArrayOutputStream()
            }
        }

        engine.handleMirrorEvent(FileObserver.MODIFY, file, mirror, treeUri)
        val drain = thread(isDaemon = true) { engine.runWriteBackLoop { false } }
        assertTrue(
            firstWriteStarted.await(5, TimeUnit.SECONDS),
            "setup failed: the write never reached the provider",
        )

        // What initialSync does when it finds that path in the journal it read at start.
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(
            listOf(file.absolutePath), engine.uploadsInFlight().toList(),
            "the sync consumed a record belonging to a writer that is still streaming",
        )

        released.set(true)
        drain.join(5_000)
    }
}
