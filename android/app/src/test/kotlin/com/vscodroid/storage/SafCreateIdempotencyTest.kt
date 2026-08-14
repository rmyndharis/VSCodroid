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
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * What happens when a create arrives for a name the device folder already has.
 *
 * `DocumentsContract.createDocument` does not merge. Handed an existing name a provider
 * invents "notes (1).txt", so the user's file forks and neither copy is the one the
 * editor is showing. Two ordinary things arrive here as a create, and both were
 * reaching it unchecked:
 *
 * - the initial sync's own rename. It writes a partial beside the destination and moves
 *   it into place; the move is a MOVED_TO, which maps to CREATE.
 * - any local rename over an existing name, which is what `mv` and a git checkout do.
 *
 * Resolving the name first is also what makes the "MODIFY on unknown file, treating as
 * CREATE" fallback safe to keep.
 */
class SafCreateIdempotencyTest {

    @TempDir
    lateinit var workspace: File

    private lateinit var engine: SafSyncEngine
    private lateinit var resolver: ContentResolver
    private lateinit var written: ByteArrayOutputStream

    private val parentUri = mockk<Uri>(relaxed = true)
    private val treeUri = mockk<Uri>(relaxed = true)
    private val childrenUri = mockk<Uri>(relaxed = true)
    private val resolvedUri = mockk<Uri>(relaxed = true)
    private val createdUri = mockk<Uri>(relaxed = true)

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
        every { DocumentsContract.getDocumentId(parentUri) } returns "parent-doc"
        every { DocumentsContract.getDocumentId(resolvedUri) } returns "resolved-doc"
        every { DocumentsContract.getDocumentId(createdUri) } returns "created-doc"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns childrenUri
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns resolvedUri
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } returns createdUri

        written = ByteArrayOutputStream()
        resolver = mockk(relaxed = true)
        every { resolver.openOutputStream(any(), "wt") } returns written

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        engine = SafSyncEngine(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** A cursor over a folder holding exactly [names]. */
    private fun folderHolding(vararg names: String): Cursor {
        var index = -1
        return mockk<Cursor>(relaxed = true) {
            every {
                getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            } returns 0
            every {
                getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            } returns 1
            every { moveToNext() } answers { ++index < names.size }
            every { getString(0) } answers { "doc-${names[index]}" }
            every { getString(1) } answers { names[index] }
            every { close() } just Runs
        }
    }

    /** Runs the real create for a local file of [contents] named [name]. */
    private fun create(name: String, contents: String) {
        val localFile = File(workspace, name).apply { writeText(contents) }
        SafSyncEngine::class.java
            .getDeclaredMethod(
                "createInSaf", File::class.java, Uri::class.java, Uri::class.java
            )
            .apply { isAccessible = true }
            .invoke(engine, localFile, parentUri, treeUri)
    }

    @Test
    fun `a create for a name the folder already has writes into it`() {
        every { resolver.query(childrenUri, any(), any(), any(), any()) } returns
            folderHolding("notes.txt")

        create("notes.txt", "the only copy")

        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
        assertEquals(
            "the only copy", written.toString(),
            "the contents have to land in the document that is already there"
        )
    }

    @Test
    fun `a create for a name the folder does not have makes the document`() {
        // The control for the case above: an absence is also what a fixture that never
        // reaches createDocument at all produces. This shows the path is live.
        every { resolver.query(childrenUri, any(), any(), any(), any()) } returns
            folderHolding("something-else.txt")

        create("notes.txt", "brand new")

        verify(exactly = 1) {
            DocumentsContract.createDocument(any(), any(), "text/plain", "notes.txt")
        }
        assertEquals("brand new", written.toString())
    }

    @Test
    fun `an empty folder still makes the document`() {
        every { resolver.query(childrenUri, any(), any(), any(), any()) } returns folderHolding()

        create("notes.txt", "first file")

        verify(exactly = 1) { DocumentsContract.createDocument(any(), any(), any(), "notes.txt") }
    }

    @Test
    fun `a lookup that fails still creates rather than dropping the file`() {
        // A failed lookup reads the same as a real absence, so this can duplicate a
        // document from a transient provider error. The alternative -- refusing to
        // create -- loses a genuinely new file instead. Keeping the data is the
        // direction to fail in, and the choice is pinned here so it stays a choice.
        every { resolver.query(childrenUri, any(), any(), any(), any()) } throws
            RuntimeException("provider went away")

        create("notes.txt", "still mine")

        verify(exactly = 1) { DocumentsContract.createDocument(any(), any(), any(), "notes.txt") }
    }
}
