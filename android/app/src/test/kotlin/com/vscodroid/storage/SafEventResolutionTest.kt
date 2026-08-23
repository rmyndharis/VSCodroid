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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * What one inotify event costs the provider before anything is queued.
 *
 * `handleMirrorEvent` runs inline on `FileObserver`'s single, process-wide ObserverThread,
 * which is also the thread that has to keep up with every inotify event for the whole
 * mirror. Anything it does per event is paid there, and a name the mirror has just gained
 * misses `docIdCache` by construction, so resolving it is a `ContentResolver.query` per
 * path segment and a full linear scan of each directory's cursor. A `git checkout` adding
 * two thousand files paid that two thousand times.
 *
 * For a file arriving as CREATE the answer had no reader at all: the rename claim gate is
 * reached only by a directory, and the write-back's CREATE arm takes the parent and the
 * tree and asks the provider for the child itself. So what is pinned here is the absence
 * of the walk on that one shape, with the shapes that genuinely need it as the controls.
 *
 * Driven through `handleMirrorEvent` rather than an observer: constructing a
 * `FileObserver` runs a static initializer that reaches native code a JVM test cannot
 * satisfy.
 */
class SafEventResolutionTest {

    /** inotify's "the entry this event is about is a directory", as the kernel sends it. */
    private val isDirFlag = 0x40000000

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri

    /** How many child listings the provider was asked for while an event was handled. */
    private val queries = AtomicInteger(0)

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.getDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            queries.incrementAndGet()
            folderHolding("notes.txt")
        }

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** A cursor over a device folder holding exactly [names]. */
    private fun folderHolding(vararg names: String): Cursor {
        var row = -1
        return mockk<Cursor>(relaxed = true) {
            every { moveToNext() } answers { ++row < names.size }
            every {
                getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            } returns 0
            every {
                getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            } returns 1
            every { getString(0) } answers { "doc:${names[row]}" }
            every { getString(1) } answers { names[row] }
        }
    }

    /** Delivers one event, without letting the write-back queue run. */
    private fun observe(event: Int, entry: String) {
        queries.set(0)
        engine.handleMirrorEvent(event, File(mirror, entry), mirror, treeUri)
    }

    @Test
    fun `a file created in the mirror is not resolved against the provider`() {
        File(mirror, "fresh.txt").writeText("new")

        observe(FileObserver.CREATE, "fresh.txt")

        assertEquals(
            0, queries.get(),
            "the create walked the provider for a document URI the write-back never reads",
        )
    }

    /** A file's MOVED_TO maps to CREATE too, so it must not pay the walk either. */
    @Test
    fun `a file moved into the mirror is not resolved against the provider`() {
        File(mirror, "fresh.txt").writeText("new")

        observe(FileObserver.MOVED_TO, "fresh.txt")

        assertEquals(0, queries.get(), "a file's MOVED_TO paid the walk a create does not")
    }

    /**
     * The first control. A MODIFY writes into the document the path already names, so the
     * resolution is the whole of what makes the job do anything, and skipping it would
     * turn every save into a create.
     */
    @Test
    fun `a modified file is still resolved`() {
        File(mirror, "notes.txt").writeText("edited")

        observe(FileObserver.MODIFY, "notes.txt")

        assertEquals(1, queries.get(), "the save no longer resolves the document it writes into")
    }

    /**
     * The second control, and the one the skip has to stay narrow for. A directory's
     * MOVED_TO decides whether it may claim a directory that just left the mirror, and
     * that decision reads the resolution's nullness: a null answer means the device has
     * nothing under the new name, which is what makes the claim safe.
     */
    @Test
    fun `a directory moved into the mirror is still resolved`() {
        // Deliberately absent from disk: a directory that is there makes `watchTree`
        // construct a `FileObserver`, whose static initializer reaches native code no
        // JVM test can satisfy. The event carries IN_ISDIR, which is what the handler
        // reads, so the shape under test is unchanged.
        observe(FileObserver.MOVED_TO or isDirFlag, "widgets")

        assertEquals(
            1, queries.get(),
            "the claim gate lost the answer it reads, so a rename can no longer tell " +
                "an empty destination from an occupied one",
        )
    }

    /** A delete removes the document the path names, so it needs the same answer. */
    @Test
    fun `a deleted file is still resolved`() {
        observe(FileObserver.DELETE, "notes.txt")

        assertEquals(1, queries.get(), "the delete no longer resolves the document it removes")
    }
}
