package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.io.File
import java.io.IOException

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

    @TempDir
    lateinit var filesDir: File

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
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("the only copy".toByteArray()) }

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** A cursor over a folder holding exactly [names]. */
    private fun folderHolding(vararg names: String, loading: Boolean = false, error: Boolean = false): Cursor {
        var index = -1
        return mockk<Cursor>(relaxed = true) {
            every { extras } returns mockk<Bundle>(relaxed = true) {
                every { getBoolean(DocumentsContract.EXTRA_LOADING) } returns loading
                every { containsKey(DocumentsContract.EXTRA_ERROR) } returns error
            }
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
        SafSyncEngine::class.java.declaredMethods
            .single { it.name == "createInSaf" }
            .apply { isAccessible = true }
            .invoke(engine, localFile, parentUri, treeUri, workspace, null)
    }

    @Test
    fun `an unread path stays held when its parent listing is incomplete`() {
        every { DocumentsContract.getTreeDocumentId(treeUri) } returns "root"
        val holds = SafSyncEngine::class.java.getDeclaredMethod("providerHolds", Uri::class.java, String::class.java)
            .apply { isAccessible = true }
        val guard = SafSyncEngine::class.java.getDeclaredMethod("providerHoldsDocument", Uri::class.java, String::class.java)
            .apply { isAccessible = true }
        val resolve = SafSyncEngine::class.java.declaredMethods.single { it.name == "resolveDocumentUri" }
            .apply { isAccessible = true }
        for (listing in listOf(folderHolding(loading = true), folderHolding(error = true))) {
            every { resolver.query(childrenUri, any(), any(), any(), any()) } returns listing
            assertNull(holds.invoke(engine, treeUri, "notes.txt"))
            assertEquals(true, guard.invoke(engine, treeUri, "notes.txt"))
            assertNull(resolve.invoke(engine, treeUri, "notes.txt", null))
        }
    }

    @Test
    fun `a matching row in a partial listing is not permission to replace it`() {
        every { resolver.query(childrenUri, any(), any(), any(), any()) } returns
            folderHolding("notes.txt", loading = true)
        assertUnknownListingRefusesCreate()
    }

    @Test
    fun `duplicate device names are not permission to replace either document`() {
        every { resolver.query(childrenUri, any(), any(), any(), any()) } returns
            folderHolding("notes.txt", "notes.txt")
        assertUnknownListingRefusesCreate()
    }

    @Test
    fun `a refused create retires intent when its local file vanished`() {
        assertVanishedCreationRetiresIntent("null")
    }

    @Test
    fun `a throwing create retires intent when its local file vanished`() {
        assertVanishedCreationRetiresIntent("throw")
    }

    @Test
    fun `an accepted create retires intent when its local file vanished`() {
        assertVanishedCreationRetiresIntent("accepted")
    }

    private fun assertVanishedCreationRetiresIntent(outcome: String) {
        every { resolver.query(childrenUri, any(), any(), any(), any()) } returns folderHolding()
        val other = File(workspace, "other.txt").apply { writeText("another pending edit") }
        val journal = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
        journal.writeText(other.absolutePath)
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            assertTrue(File(workspace, "notes.txt").delete())
            when (outcome) {
                "null" -> null
                "throw" -> throw IOException("provider unavailable")
                else -> createdUri
            }
        }

        create("notes.txt", "the only copy")

        assertEquals(listOf(other.absolutePath), journal.readLines())
        assertEquals("another pending edit", other.readText())
    }

    @Test
    fun `a refused create retains intent while its local file survives`() {
        every { resolver.query(childrenUri, any(), any(), any(), any()) } returns folderHolding()
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } returns null

        create("notes.txt", "the only copy")

        val local = File(workspace, "notes.txt")
        assertEquals("the only copy", local.readText())
        assertEquals(
            listOf(local.absolutePath),
            File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE).readLines(),
        )
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
            DocumentsContract.createDocument(
                any(), any(), SafSyncEngine.CREATED_FILE_MIME_TYPE, "notes.txt"
            )
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
    fun `a lookup that throws keeps the file in the mirror and reports it`() {
        every { resolver.query(childrenUri, any(), any(), any(), any()) } throws
            RuntimeException("provider went away")
        assertUnknownListingRefusesCreate()
    }

    @Test
    fun `a null listing keeps the file in the mirror and reports it`() {
        every { resolver.query(childrenUri, any(), any(), any(), any()) } returns null
        assertUnknownListingRefusesCreate()
    }

    @Test
    fun `a loading listing keeps the file in the mirror and reports it`() {
        every { resolver.query(childrenUri, any(), any(), any(), any()) } returns
            folderHolding(loading = true)
        assertUnknownListingRefusesCreate()
    }

    @Test
    fun `an error listing keeps the file in the mirror and reports it`() {
        every { resolver.query(childrenUri, any(), any(), any(), any()) } returns
            folderHolding(error = true)
        assertUnknownListingRefusesCreate()
    }

    private fun assertUnknownListingRefusesCreate() {
        val lost = mutableListOf<String>()
        engine.onWriteBackFailed = { lost += it.name }
        create("notes.txt", "still mine")
        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
        assertEquals("", written.toString())
        assertEquals("still mine", File(workspace, "notes.txt").readText())
        assertEquals(listOf("notes.txt"), lost)
    }

    @Test
    fun `an existing document unknown to the session is preserved before replacement`() {
        every { resolver.query(childrenUri, any(), any(), any(), any()) } returns folderHolding("notes.txt")
        every { resolver.openInputStream(resolvedUri) } answers {
            ByteArrayInputStream("foreign device edit".toByteArray())
        }
        create("notes.txt", "my new file")
        assertEquals("my new file", written.toString())
        val copies = workspace.listFiles()!!.filter { it.name.startsWith("notes.txt.device-") }
        assertEquals(1, copies.size, "the foreign device document was overwritten without a preserved copy")
        assertEquals("foreign device edit", copies.single().readText())
    }

    @Test
    fun `a symlink create has no lost-file notice`() {
        val target = File(workspace, "target").apply { writeText("private") }
        val link = File(workspace, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val lost = mutableListOf<File>()
        engine.onWriteBackFailed = { lost += it }
        SafSyncEngine::class.java.declaredMethods.single { it.name == "createInSaf" }
            .apply { isAccessible = true }.invoke(engine, link, parentUri, treeUri, workspace, null)
        assertTrue(lost.isEmpty())
        assertEquals("", written.toString())
    }
}
