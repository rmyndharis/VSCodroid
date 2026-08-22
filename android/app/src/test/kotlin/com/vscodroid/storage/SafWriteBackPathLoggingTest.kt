package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException

/**
 * That a write-back the provider refuses does not put the user's directory into logcat.
 *
 * The sibling of [SafFolderPathLoggingTest], for the other direction and for the reason
 * the leak got in: that suite drives opening a folder, so the copy path is redacted and
 * asserted while the four calls that carry a change back to the device were never
 * exercised. A document id is the user's own path on the platform provider, a
 * `FileNotFoundException` or `SecurityException` from these calls quotes the whole
 * document URI in its own message, and `Logger.w` is not gated on a debuggable build, so
 * interpolating `e.message` ships that string.
 *
 * Every case here is an everyday provider failure rather than an exotic one: the document
 * was deleted on the device, the grant was revoked in Settings, the volume is full or
 * read-only, the card was ejected.
 *
 * Asserted at `android.util.Log`, like the suite it is modelled on, because there are two
 * routes for the same string and only the sink sees both.
 */
class SafWriteBackPathLoggingTest {

    @TempDir
    lateinit var mirror: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine

    private val treeUriText =
        "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FClientProject"

    /** The part of that URI a reader could turn back into a place on the device. */
    private val folderName = "ClientProject"

    /** What a provider puts in its own exception when it cannot answer for a document. */
    private val providerRefusal = FileNotFoundException(
        "open failed for $treeUriText/document/primary%3ADocuments%2FClientProject%2Fnotes.txt"
    )

    private val treeUri = mockk<Uri>(relaxed = true)
    private val docUri = mockk<Uri>(relaxed = true)
    private val parentUri = mockk<Uri>(relaxed = true)
    private val sourceParentUri = mockk<Uri>(relaxed = true)

    /** Every string the app handed the log, message and throwable alike. */
    private val emitted = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        emitted.clear()

        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } answers { note(args); 0 }
        every { android.util.Log.d(any(), any()) } answers { note(args); 0 }
        every { android.util.Log.w(any(), any<String>()) } answers { note(args); 0 }
        every { android.util.Log.w(any(), any<String>(), any<Throwable>()) } answers { note(args); 0 }
        every { android.util.Log.e(any(), any()) } answers { note(args); 0 }
        every { android.util.Log.e(any(), any<String>(), any<Throwable>()) } answers { note(args); 0 }

        mockkStatic(DocumentsContract::class)
        every { treeUri.toString() } returns treeUriText
        every { DocumentsContract.getTreeDocumentId(any()) } returns
            "primary:Documents/$folderName"
        every { DocumentsContract.getDocumentId(any()) } returns
            "primary:Documents/$folderName"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns docUri

        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } returns null

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns mirror
        engine = SafSyncEngine(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * Records a log call's arguments, a throwable included as the text it would print.
     * The tag is recorded too: it is written by the same statement and is as capable of
     * carrying an interpolation.
     */
    private fun note(args: List<Any?>) {
        args.forEach { arg ->
            emitted += when (arg) {
                is Throwable -> arg.toString() + (arg.message ?: "")
                else -> arg.toString()
            }
        }
    }

    /**
     * That the line under test was actually written, and that what it carries instead of
     * the provider's message is the exception's class.
     *
     * Without it every case here would pass just as well against a write-back that never
     * reached the call at all, which is how the first version of the guard this suite
     * extends came to cover four sites it never ran.
     */
    private fun assertSaid(fragment: String) {
        assertTrue(
            emitted.any { it.contains(fragment) && it.contains("FileNotFoundException") },
            "no line said \"$fragment\", so this case is asserting nothing: $emitted",
        )
    }

    private fun assertFolderNotNamed() {
        assertTrue(
            emitted.isNotEmpty(),
            "nothing reached the log at all, so this run proves nothing about what does",
        )
        val leaked = emitted.filter { it.contains(folderName) || it.contains("tree/primary") }
        assertTrue(leaked.isEmpty(), "the user's device folder reached release logcat: $leaked")
    }

    /** Puts one job through the real write-back, the way the drain does. */
    private fun writeBack(job: SyncJob) {
        engine.session.queue.offer(job)
        engine.runWriteBackLoop { false }
    }

    @Test
    fun `a create the provider refuses does not repeat its message`() {
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } throws
            providerRefusal
        File(mirror, "notes.txt").writeText("the only copy")

        writeBack(
            SyncJob(
                type = SyncType.CREATE,
                localPath = File(mirror, "notes.txt").absolutePath,
                safDocUri = null,
                safParentUri = parentUri,
                safTreeUri = treeUri,
                timestamp = 1L,
                relativePath = "notes.txt",
            )
        )

        assertFolderNotNamed()
        assertSaid("Failed to create notes.txt in SAF")
    }

    @Test
    fun `a delete the provider refuses does not repeat its message`() {
        every { DocumentsContract.deleteDocument(any(), any()) } throws providerRefusal

        writeBack(
            SyncJob(
                type = SyncType.DELETE,
                localPath = File(mirror, "notes.txt").absolutePath,
                safDocUri = docUri,
                safParentUri = null,
                safTreeUri = treeUri,
                timestamp = 1L,
                relativePath = "notes.txt",
            )
        )

        assertFolderNotNamed()
        assertSaid("Failed to delete from SAF")
    }

    /**
     * The local file is deliberately absent, so the create fallback a refused rename
     * falls into is not taken: what is under test is the rename's own line.
     */
    @Test
    fun `a rename the provider refuses does not repeat its message`() {
        every { DocumentsContract.renameDocument(any(), any(), any()) } throws providerRefusal

        writeBack(
            SyncJob(
                type = SyncType.RENAME,
                localPath = File(mirror, "renamed.txt").absolutePath,
                safDocUri = docUri,
                safParentUri = parentUri,
                safTreeUri = treeUri,
                timestamp = 1L,
                previousName = "notes.txt",
                relativePath = "renamed.txt",
            )
        )

        assertFolderNotNamed()
        assertSaid("Could not rename a document to renamed.txt")
    }

    @Test
    fun `a move the provider refuses does not repeat its message`() {
        every { DocumentsContract.moveDocument(any(), any(), any(), any()) } throws providerRefusal

        writeBack(
            SyncJob(
                type = SyncType.RENAME,
                localPath = File(mirror, "legacy/util").absolutePath,
                safDocUri = docUri,
                safParentUri = parentUri,
                safTreeUri = treeUri,
                timestamp = 1L,
                safSourceParentUri = sourceParentUri,
                previousName = "util",
                relativePath = "legacy/util",
            )
        )

        assertFolderNotNamed()
        assertSaid("Could not move a document between directories")
    }

    /**
     * The control, in the same spirit as the one in [SafFolderPathLoggingTest]. Redaction
     * that leaves nothing behind is deletion: a bug report still has to be able to say
     * which file the device folder would not take.
     */
    @Test
    fun `a refused create still says which file it was`() {
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } throws
            providerRefusal
        File(mirror, "notes.txt").writeText("the only copy")

        writeBack(
            SyncJob(
                type = SyncType.CREATE,
                localPath = File(mirror, "notes.txt").absolutePath,
                safDocUri = null,
                safParentUri = parentUri,
                safTreeUri = treeUri,
                timestamp = 1L,
                relativePath = "notes.txt",
            )
        )

        assertTrue(
            emitted.any { it.contains("notes.txt") },
            "the redaction removed the record rather than the secret: $emitted",
        )
    }
}
