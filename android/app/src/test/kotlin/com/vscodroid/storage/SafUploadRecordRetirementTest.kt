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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * What becomes of an upload record once the file it names is gone.
 *
 * A journal line means "an edit of this mirror file never reached the device", and it is
 * left standing on purpose when a write-back fails, so the next sync retries it. Nothing
 * used to retire one when the file itself went away, and a file rename arrives at the
 * write-back as a delete of the old name, so one refused save followed by renaming the
 * file in the editor stranded a line for ever.
 *
 * A stranded line is not inert. [SafStorageManager.mayReclaim] reads any line under a
 * mirror as that mirror holding a write the device never received, so from then on the
 * launch pass keeps the mirror, the device-folder screen describes it as holding work
 * that exists nowhere else, and the removal the user asks for is refused with a sentence
 * about files that are not there. What is pinned here is the consequence rather than the
 * bookkeeping: the gate that reads the journal.
 */
class SafUploadRecordRetirementTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri
    private lateinit var mirrorsRoot: File
    private lateinit var mirror: File
    private lateinit var journal: File

    private val hash = "abc123def456"

    /** Set to have every write-back attempt fail the way a provider out of space does. */
    private var refuseWrites = false

    @BeforeEach
    fun setUp() {
        refuseWrites = false

        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.getDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)
        val doc = mockk<Uri>(relaxed = true)
        every { doc.toString() } returns "content://test/doc/App.kt"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns doc
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } returns doc
        every { DocumentsContract.deleteDocument(any(), any()) } returns true

        resolver = mockk(relaxed = true)
        every { resolver.openOutputStream(any(), any()) } answers {
            if (refuseWrites) throw IOException("no space left on device")
            java.io.ByteArrayOutputStream()
        }
        deviceHolding("App.kt")

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir

        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)

        mirrorsRoot = File(filesDir, "saf-mirrors").apply { mkdirs() }
        mirror = File(mirrorsRoot, hash).apply { mkdirs() }
        journal = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun deviceHolding(name: String) {
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        every { cursor.moveToNext() } answers { ++row == 0 }
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        } returns 0
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        } returns 1
        every { cursor.getString(0) } returns "doc:$name"
        every { cursor.getString(1) } returns name
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
    }

    private fun deliver(event: Int, entry: String) {
        engine.handleMirrorEvent(event, File(mirror, entry), mirror, treeUri)
        engine.runWriteBackLoop { false }
    }

    private fun mayReclaim(): Boolean =
        SafStorageManager.mayReclaim(hash, engine.uploadsInFlight(), mirrorsRoot.absolutePath)

    /** A save the provider refuses, which is what puts a line in the journal at all. */
    private fun strandOneWrite() {
        File(mirror, "App.kt").writeText("edited in the editor")
        refuseWrites = true
        deliver(FileObserver.MODIFY, "App.kt")
        refuseWrites = false
        assertTrue(
            File(mirror, "App.kt").absolutePath in engine.uploadsInFlight(),
            "no record was stranded, so nothing below is being tested",
        )
        assertFalse(mayReclaim(), "the stranded record did not reach the gate")
    }

    @Test
    fun `deleting the file a stranded record names lets its mirror be reclaimed again`() {
        strandOneWrite()

        File(mirror, "App.kt").delete()
        deliver(FileObserver.DELETE, "App.kt")

        assertTrue(
            mayReclaim(),
            "the record outlived the file it names, so this mirror can never be " +
                "reclaimed and the storage screen reports it as holding work the " +
                "device folder does not",
        )
    }

    /**
     * The same, arriving the way a rename does. inotify reports renaming a file as
     * MOVED_FROM on the old name, which the engine treats as a delete, so this is the
     * ordinary route into the stranded state rather than an exotic one.
     */
    @Test
    fun `renaming the file away lets its mirror be reclaimed again`() {
        strandOneWrite()

        File(mirror, "App.kt").renameTo(File(mirror, "App2.kt"))
        deliver(FileObserver.MOVED_FROM, "App.kt")

        assertTrue(mayReclaim(), "the record survived the rename that emptied its path")
    }

    /**
     * The first control: a record for a file that is still there is still respected. The
     * whole point of a journal line is to survive a failed write-back, and a retirement
     * that fired on any delete event would throw away the distrust that keeps the next
     * sync from copying a truncated device document over the only complete copy.
     */
    @Test
    fun `a stranded record for a file that is still there stands`() {
        strandOneWrite()

        File(mirror, "other.txt").writeText("unrelated")
        File(mirror, "other.txt").delete()
        deliver(FileObserver.DELETE, "other.txt")

        assertFalse(
            mayReclaim(),
            "an unrelated delete retired a record that still names a file holding the " +
                "user's only copy",
        )
    }

    /** The second control: records outside this mirror are none of a delete's business. */
    @Test
    fun `a record under another mirror is untouched`() {
        val otherMirror = File(mirrorsRoot, "0123456789ab").apply { mkdirs() }
        val otherPath = File(otherMirror, "App.kt").absolutePath
        strandOneWrite()
        journal.writeText(journal.readText().trimEnd() + "\n" + otherPath + "\n")

        File(mirror, "App.kt").delete()
        deliver(FileObserver.DELETE, "App.kt")

        assertTrue(
            otherPath in engine.uploadsInFlight(),
            "a delete in one mirror retired another mirror's record",
        )
    }
}
