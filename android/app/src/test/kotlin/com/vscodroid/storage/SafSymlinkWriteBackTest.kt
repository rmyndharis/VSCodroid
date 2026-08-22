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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

/**
 * What a symbolic link inside a mirror does to the device folder.
 *
 * The engine states this policy in its own words in three places: `uploadPlan` and
 * `watchableDirectories` refuse a link outright, and `holdsOnlyVouchedCopies` treats one
 * as unvouched, each explaining that a mirror is routinely a checked-out repository and a
 * link inside one is attacker-supplied in the ordinary case. The single-entry path the
 * observer drives had no such test at all, and every stream it opens reads *through* the
 * link, so a create or a save of `notes.md -> /data/data/<app>/files/home/.ssh/id_ed25519`
 * opened the device's `notes.md` with `"wt"` and copied the private key into it. The
 * target can be anywhere this app can read, which includes its own private storage, and
 * the destination is shared storage the user browses with a file manager.
 *
 * Driven through `handleMirrorEvent` rather than an observer: constructing a
 * `FileObserver` runs a static initializer that reaches native code a JVM test cannot
 * satisfy.
 */
class SafSymlinkWriteBackTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var elsewhere: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri

    /** Every byte any write-back handed to the provider, in order. */
    private val written = mutableListOf<ByteArrayOutputStream>()

    @BeforeEach
    fun setUp() {
        written.clear()

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
        every { doc.toString() } returns "content://test/doc/notes.md"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns doc
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } returns doc
        every { DocumentsContract.deleteDocument(any(), any()) } returns true

        resolver = mockk(relaxed = true)
        every { resolver.openOutputStream(any(), any()) } answers {
            ByteArrayOutputStream().also { written += it } as OutputStream
        }

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir

        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** The device folder answers for one child called [name], so a path resolves. */
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

    /** A link in the mirror root pointing at a file the user never put in the folder. */
    private fun linkToSecret(name: String): File {
        val secret = File(elsewhere, "id_ed25519").apply { writeText("PRIVATE KEY MATERIAL") }
        val link = File(mirror, name)
        Files.createSymbolicLink(link.toPath(), secret.toPath())
        assertTrue(
            Files.isSymbolicLink(link.toPath()) && link.readText() == "PRIVATE KEY MATERIAL",
            "the link was not created, so nothing below is being tested",
        )
        return link
    }

    private fun allWritten(): String = written.joinToString("") { it.toString(Charsets.UTF_8) }

    @Test
    fun `a save on a symbolic link sends no bytes to the device folder`() {
        deviceHolding("notes.md")
        linkToSecret("notes.md")

        deliver(FileObserver.MODIFY, "notes.md")

        assertFalse(
            allWritten().contains("PRIVATE KEY MATERIAL"),
            "the link's target was copied into the user's device folder",
        )
        assertEquals(0, written.size, "the device document was opened for writing at all")
    }

    @Test
    fun `a symbolic link appearing in the mirror sends no bytes to the device folder`() {
        deviceHolding("notes.md")
        linkToSecret("notes.md")

        deliver(FileObserver.CREATE, "notes.md")

        assertFalse(
            allWritten().contains("PRIVATE KEY MATERIAL"),
            "the link's target was copied into the user's device folder",
        )
        assertEquals(0, written.size, "the device document was opened for writing at all")
    }

    /**
     * The control. Without it every case above passes for a write-back that stopped
     * writing anything at all, which is the shape of an over-tight guard.
     */
    @Test
    fun `an ordinary file at the same name is still written back`() {
        deviceHolding("notes.md")
        File(mirror, "notes.md").writeText("edited in the editor")

        deliver(FileObserver.MODIFY, "notes.md")

        assertEquals(1, written.size, "the ordinary save never reached its document")
        assertEquals("edited in the editor", allWritten())
    }

    /**
     * Deleting a link still reaches the device, and it has to. By the time a DELETE
     * arrives the entry is gone, so nothing can be asked whether it was a link, while the
     * document the user is deleting is still there. A refusal placed where it could see
     * this event would leave the device holding files the user removed.
     */
    @Test
    fun `deleting a symbolic link still removes the device document`() {
        deviceHolding("notes.md")
        val link = linkToSecret("notes.md")
        link.delete()

        deliver(FileObserver.DELETE, "notes.md")

        verify(exactly = 1) { DocumentsContract.deleteDocument(any(), any()) }
    }
}
