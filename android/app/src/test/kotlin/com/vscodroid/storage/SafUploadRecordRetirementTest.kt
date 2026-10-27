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
 * A line must follow its file while that file exists and retire when no write can
 * consume it. These cases observe the real journal independently of removal consent.
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

    /** inotify's "the entry this event is about is a directory", as the kernel sends it. */
    private val isDirFlag = 0x40000000

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
        every { resolver.openInputStream(any()) } answers {
            java.io.ByteArrayInputStream(byteArrayOf())
        }
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

    /**
     * A device folder that answers for every one of [names], whatever directory it is
     * asked about.
     *
     * Crude on purpose: what a write-back needs from the provider here is a document to
     * fail to write into, and every name in a path has to resolve for the deepest one to
     * be reached at all.
     */
    private fun deviceHolding(vararg names: String) {
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        every { cursor.moveToNext() } answers { ++row < names.size }
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        } returns 0
        every {
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        } returns 1
        every { cursor.getString(0) } answers { "doc:${names[row]}" }
        every { cursor.getString(1) } answers { names[row] }
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
    }

    private fun deliver(event: Int, entry: String) {
        engine.handleMirrorEvent(event, File(mirror, entry), mirror, treeUri)
        engine.runWriteBackLoop { false }
    }

    private fun noUploadsUnderMirror(): Boolean =
        engine.uploadsInFlight().none { it.startsWith(mirror.absolutePath + File.separator) }

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
        assertFalse(noUploadsUnderMirror(), "the stranded record did not reach the mirror's journal prefix")
    }

    @Test
    fun `deleting the file a stranded record names retires that record`() {
        strandOneWrite()

        File(mirror, "App.kt").delete()
        deliver(FileObserver.DELETE, "App.kt")

        assertTrue(
            noUploadsUnderMirror(),
            "the record outlived the deleted file it names, so no later write can consume it",
        )
    }

    /**
     * The same, arriving the way a rename does. inotify reports renaming a file as
     * MOVED_FROM on the old name, which the engine treats as a delete, so this is the
     * ordinary route into the stranded state rather than an exotic one.
     */
    @Test
    fun `renaming the file away retires the record under its old name`() {
        strandOneWrite()

        File(mirror, "App.kt").renameTo(File(mirror, "App2.kt"))
        deliver(FileObserver.MOVED_FROM, "App.kt")

        assertTrue(noUploadsUnderMirror(), "the record survived the rename that emptied its path")
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
            noUploadsUnderMirror(),
            "an unrelated delete retired a record that still names a file holding the " +
                "user's only copy",
        )
    }

    /** A save the provider refuses, for a file one directory down. */
    private fun strandOneWriteUnder(directory: String, name: String) {
        deviceHolding("App.kt", *directory.split("/").toTypedArray(), name)
        val file = File(File(mirror, directory).apply { mkdirs() }, name)
        file.writeText("edited in the editor")
        refuseWrites = true
        deliver(FileObserver.MODIFY, "$directory/$name")
        refuseWrites = false
        assertTrue(
            file.absolutePath in engine.uploadsInFlight(),
            "no record was stranded, so nothing below is being tested",
        )
        assertFalse(noUploadsUnderMirror(), "the stranded record did not reach the mirror's journal prefix")
    }

    /**
     * The case a delete never arrives for.
     *
     * A directory that leaves the mirror is held back for a rename to claim rather than
     * queued as a delete, and inotify raises nothing at all for the entries beneath it, so
     * the one live retirement point is never reached for any of them. Nothing else could
     * reach them either, so a line could survive permanently under a departed path.
     *
     * NEGATIVE CONTROL: drop the `dropVanished { true }` from `stopWatching` back to
     * `synchronized(vanishedLock) { vanishedDirectories.clear() }` and this goes red,
     * while the two cases below it stay green.
     */
    @Test
    fun `moving a directory away retires records beneath its departed path`() {
        strandOneWriteUnder("src/util", "helper.ts")

        File(mirror, "src/util").deleteRecursively()
        engine.handleMirrorEvent(
            FileObserver.MOVED_FROM or isDirFlag, File(mirror, "src/util"), mirror, treeUri
        )
        engine.stopWatching()

        assertTrue(
            noUploadsUnderMirror(),
            "the records under a departed directory survived with no file left to retry",
        )
    }

    /**
     * The same, retired by the session rather than by its end, and the first half of it is
     * the complement: while the rename can still be claimed the records have to stand,
     * because a claim is what moves them to the directory's new path.
     *
     * NEGATIVE CONTROL: put the prune in `hasVanishedCandidate` back on the list itself
     * (`vanishedDirectories.removeAll { ... }`) and the last assertion goes red while the
     * first stays green.
     */
    @Test
    fun `a departure nobody claimed retires its records when its window closes`() {
        strandOneWriteUnder("src/util", "helper.ts")

        File(mirror, "src/util").deleteRecursively()
        engine.handleMirrorEvent(
            FileObserver.MOVED_FROM or isDirFlag, File(mirror, "src/util"), mirror, treeUri
        )
        // Read after the event, never before it: the entry is stamped from the engine's
        // own clock inside the call, so a bound taken on the other side of it is short by
        // however long the call took and the expiry never happens. Measured here once,
        // as SafVanishedCandidateWindowTest records measuring it.
        val stampedBy = System.currentTimeMillis()
        assertFalse(
            noUploadsUnderMirror(),
            "the records were retired while a rename could still have claimed the " +
                "directory, so nothing would have moved them to its new path",
        )

        engine.hasVanishedCandidate(stampedBy + SafSyncEngine.RENAME_PAIR_WINDOW_MS + 1)

        assertTrue(noUploadsUnderMirror(), "the expired departure left its records standing")
    }

    /**
     * The control for the one thing that keeps a line: the file being there.
     *
     * A directory that left and came back under the same name (a checkout, a swap that
     * lost its pairing) holds files again, and a line for one of them still records an
     * edit the device never received. Retiring on the event alone rather than on the path
     * being empty would throw that away.
     */
    @Test
    fun `a record whose file is still there survives its directory leaving`() {
        strandOneWriteUnder("src/util", "helper.ts")

        engine.handleMirrorEvent(
            FileObserver.MOVED_FROM or isDirFlag, File(mirror, "src/util"), mirror, treeUri
        )
        engine.stopWatching()

        assertFalse(
            noUploadsUnderMirror(),
            "a record naming a file that is still in the mirror was retired, so the " +
                "next sync will copy the device's own interrupted upload over it",
        )
    }

    /**
     * The other side of that deferral: the writer it defers to.
     *
     * The pass above keeps a line some writer holds a claim on, because taking it would
     * be the defect the claims exist to prevent. That leaves the writer holding the only
     * knowledge that the line is unowned, and a write-back which fails is exactly where
     * this arrives: the directory left the mirror while the stream ran, so the two
     * attempts find nothing to read and the writer gives up. Its release used to drop the
     * claim and leave the line, deliberately, so that the next sync retries the edit; but
     * the file holding that edit has gone, there is no edit to retry, and by then the
     * vanished entry has been dropped too, so no later pass revisits the prefix.
     *
     * A later sync could consume the stale line after copying the device document
     * back. When that document is not enumerated or the folder is never reopened,
     * nothing else can retire the line, so the writer must do it when its file is gone.
     *
     * NEGATIVE CONTROL, measured both ways: put `releaseUploadClaim` back to
     * `uploadClaims.remove` alone and this case is the only one of the eight here that
     * goes red. Drop the file test instead, retiring on every release, and the other
     * seven go red while this one stays green, which is the complement: a failed write
     * whose file is still in the mirror has to keep its line, and those cases all begin
     * by stranding one.
     */
    @Test
    fun `a write that fails onto a departed directory retires its own record`() {
        deviceHolding("App.kt", "src", "util", "helper.ts")
        val directory = File(mirror, "src/util").apply { mkdirs() }
        val file = File(directory, "helper.ts")
        file.writeText("edited in the editor")

        // Read from inside the provider, which is the only place the deferral is
        // observable: the claim is live exactly while this write is in the provider.
        var deferred = false
        every { resolver.openOutputStream(any(), any()) } answers {
            // The directory leaves the mirror mid-write, and nothing claims the
            // departure as the other half of a rename.
            directory.deleteRecursively()
            engine.handleMirrorEvent(
                FileObserver.MOVED_FROM or isDirFlag, directory, mirror, treeUri
            )
            // The window closes, which is one of the three ways the entry is dropped and
            // the one that needs no folder switch. Its retirement pass runs here, and
            // keeps this file's line because the claim below it is live.
            engine.hasVanishedCandidate(
                System.currentTimeMillis() + SafSyncEngine.RENAME_PAIR_WINDOW_MS + 1
            )
            // Both halves, because either one alone would be satisfied by the pass
            // never running at all: the departure is consumed, and the line it would
            // have retired is still standing.
            val consumed = !engine.hasVanishedCandidate(System.currentTimeMillis())
            deferred = consumed && file.absolutePath in engine.uploadsInFlight()
            throw IOException("no space left on device")
        }

        deliver(FileObserver.MODIFY, "src/util/helper.ts")

        assertTrue(
            deferred,
            "the retirement pass never ran, or did not defer to this writer, so " +
                "nothing below is testing what happens when the writer it deferred " +
                "to fails",
        )
        assertTrue(
            noUploadsUnderMirror(),
            "the failed write kept a record under a departed path that no later write can consume",
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
