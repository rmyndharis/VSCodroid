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

/**
 * Deletions on the device, on their way into the mirror.
 *
 * Reopening a folder only ever created and overwrote, so a file deleted on the device
 * came straight back out of the stale mirror. Editing that ghost then made it real
 * again: the write-back looks the file up, the tree no longer knows it, and the
 * "MODIFY on unknown file, treating as CREATE" fallback put it back on the device.
 *
 * The pass that fixes it can destroy work if it is wrong, so what it is *not* allowed
 * to remove is pinned here as carefully as what it is. It removes only files a previous
 * complete enumeration recorded and this one did not return.
 */
class SafReconcileDeletionsTest {

    @TempDir
    lateinit var mirror: File

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private val uris = mutableMapOf<String, Uri>()

    /** The record the engine keeps beside the mirror, not inside it. */
    private val record: File get() = File(mirror.path + ".synced")

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
            uriFor(secondArg<String>())
        }

        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
    }

    @AfterEach
    fun tearDown() {
        record.delete()
        unmockkAll()
    }

    private fun uriFor(docId: String): Uri = uris.getOrPut(docId) { mockk(relaxed = true) }

    /**
     * Describes the device folder as holding exactly [files], each a name to contents,
     * all carrying the same old timestamp so nothing is kept for being newer.
     */
    private fun deviceFolderHolding(vararg files: Pair<String, String>) {
        val byDocId = files.associate { (name, contents) -> "doc:$name" to contents }
        var row = -1

        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToNext() } answers { ++row < files.size }
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
        every { cursor.getString(0) } answers { "doc:${files[row].first}" }
        every { cursor.getString(1) } answers { files[row].first }
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } answers { files[row].second.toByteArray().size.toLong() }
        every { cursor.getLong(4) } returns DEVICE_TIME

        every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
        every { resolver.openInputStream(any()) } answers {
            val wanted = firstArg<Uri>()
            val docId = uris.entries.first { it.value === wanted }.key
            ByteArrayInputStream(byDocId.getValue(docId).toByteArray())
        }
    }

    /**
     * A cursor holding one directory row, for driving the walk one level deeper. Does not
     * install itself on the resolver: the caller decides which query gets it.
     */
    private fun directoryRow(name: String): Cursor {
        var row = -1
        val cursor = mockk<Cursor>(relaxed = true)
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
        every { cursor.getString(2) } returns DocumentsContract.Document.MIME_TYPE_DIR
        every { cursor.getLong(3) } returns 0L
        every { cursor.getLong(4) } returns DEVICE_TIME
        return cursor
    }

    private fun sync() = runBlocking {
        SafSyncEngine(context).initialSync(mockk<Uri>(relaxed = true), mirror) { _, _ -> }
    }

    private fun mirrored(name: String) = File(mirror, name)

    @Test
    fun `a document deleted on the device is dropped from the mirror`() {
        deviceFolderHolding("a.txt" to "kept", "b.txt" to "doomed")
        sync()
        assertTrue(mirrored("b.txt").isFile, "the first sync has to bring both down")

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertFalse(mirrored("b.txt").exists(), "a file the device no longer has must go")
        assertEquals("kept", mirrored("a.txt").readText(), "the surviving file must not be touched")
    }

    @Test
    fun `nothing is removed on the first sync of a folder`() {
        // There is no record yet, so nothing has been proven absent -- including
        // whatever a previous install or a crashed sync left behind.
        mirrored("stranger.txt").writeText("no idea where this came from")

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertTrue(mirrored("stranger.txt").isFile, "an unrecorded file is not a candidate")
    }

    @Test
    fun `a file made in the editor and never written back survives`() {
        // The case that would destroy work: write-back failed, or the process died
        // before it ran, so the mirror holds the only copy and the device has never
        // heard of it. It is not in the record, so it is never examined.
        deviceFolderHolding("a.txt" to "kept")
        sync()

        val local = mirrored("draft.md").apply {
            writeText("an hour of writing")
            // Older than the record, so only the record rule can be what saves it.
            setLastModified(DEVICE_TIME)
        }

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertEquals("an hour of writing", local.readText(), "local-only work was deleted")
    }

    @Test
    fun `a recorded file edited since the last sync survives its removal on the device`() {
        deviceFolderHolding("a.txt" to "kept", "notes.txt" to "from the device")
        sync()

        val edited = mirrored("notes.txt").apply {
            writeText("edited here after the last sync")
            setLastModified(record.lastModified() + 60_000)
        }

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertEquals(
            "edited here after the last sync", edited.readText(),
            "the device dropped it, but the newer local edit is the only copy of that work"
        )
    }

    @Test
    fun `a provider that stopped answering entirely removes nothing`() {
        // Named for what it covers. It does not reach the enumerationComplete guard:
        // every query fails, so `documents` is empty as well, and the guard beside it
        // returns first. The test below is the one that pins the partial case.
        deviceFolderHolding("a.txt" to "kept", "b.txt" to "still here")
        sync()
        val recordedBefore = record.readText()

        every { resolver.query(any(), any(), any(), any(), any()) } throws
            RuntimeException("provider went away")
        sync()

        assertTrue(mirrored("b.txt").isFile, "a dead provider proves nothing is absent")
        assertEquals(
            recordedBefore, record.readText(),
            "the last complete record must survive so the next good sync can still use it"
        )
    }

    @Test
    fun `an enumeration that failed inside a subdirectory removes nothing`() {
        // walkTree logs and carries on when a provider query fails, so a folder that
        // answered for one directory out of twenty would otherwise read as nineteen
        // directories' worth of deletions.
        //
        // The root answers here -- with a directory -- and only the walk into that
        // directory fails. That is what makes `documents` non-empty, and it is the only
        // arrangement that reaches the enumerationComplete guard rather than the
        // empty-enumeration one beside it. Drop `!enumerationComplete ||` from that
        // condition and every recorded file is deleted.
        deviceFolderHolding("a.txt" to "kept", "b.txt" to "still here")
        sync()
        val recordedBefore = record.readText()

        var call = 0
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            if (call++ == 0) directoryRow("src") else throw RuntimeException("provider went away")
        }
        sync()

        assertTrue(mirrored("a.txt").isFile, "a partial enumeration proves nothing is absent")
        assertTrue(mirrored("b.txt").isFile, "a partial enumeration proves nothing is absent")
        assertEquals(
            recordedBefore, record.readText(),
            "the last complete record must survive so the next good sync can still use it"
        )
    }

    @Test
    fun `a local edit that never reached the device survives a reopen and then its removal`() {
        // The longer of the two shapes that lose work, and the one the mtime rule does
        // not cover. The edit is made, the write-back never lands -- a swallowed
        // provider error, a killed process, a directory past the watch limit -- the
        // folder is reopened once, which is simply how a person gets back into it, and
        // only then does the file go away on the device.
        //
        // Every reopen rewrites the record. Recording a path the sync just decided NOT
        // to overwrite moves the record's timestamp past the local edit, so the mtime
        // rule stops protecting it, and the next sync after the device-side removal
        // deletes the only copy that edit ever had.
        deviceFolderHolding("a.txt" to "kept", "notes.txt" to "from the device")
        sync()

        // Newer than the device copy, and older than the record any later sync writes.
        // Both halves are needed: the first is what makes the engine keep it, the second
        // is what takes it out of the mtime rule's reach.
        val edited = mirrored("notes.txt").apply {
            writeText("an hour of writing")
            setLastModified(DEVICE_TIME + 1_000)
        }

        // Reopened while the device still has the file. The engine keeps the local copy.
        deviceFolderHolding("a.txt" to "kept", "notes.txt" to "from the device")
        sync()
        assertEquals("an hour of writing", edited.readText(), "the reopen overwrote the local edit")

        // Now it goes away on the device.
        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertEquals(
            "an hour of writing", edited.readText(),
            "the reopen re-armed the file and the next sync deleted the only copy of that work"
        )
    }

    @Test
    fun `an enumeration that succeeded and returned nothing removes nothing`() {
        // A folder the user emptied and a provider answering for a volume that is no
        // longer mounted are the same two rows of nothing from here. Acting on it costs
        // the entire mirror in the second case; declining costs some stale copies in the
        // first, and those can be deleted in the editor, which propagates outward.
        deviceFolderHolding("a.txt" to "kept", "b.txt" to "also kept")
        sync()
        val recordedBefore = record.readText()

        deviceFolderHolding()
        sync()

        assertTrue(mirrored("a.txt").isFile, "an empty answer is not proof the folder is empty")
        assertTrue(mirrored("b.txt").isFile, "an empty answer is not proof the folder is empty")
        assertEquals(recordedBefore, record.readText(), "nothing was learned, so nothing is recorded")
    }

    @Test
    fun `the record is kept beside the mirror, not inside it`() {
        // Anything inside the mirror is inside the folder VS Code opens: it shows up in
        // the explorer, in search, and in the write-back's own event stream.
        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertTrue(record.isFile, "the pass needs somewhere to record what it found")
        assertEquals(
            listOf("a.txt"), mirror.list()?.sorted(),
            "the mirror must hold the workspace and nothing else"
        )
    }

    private companion object {
        /** Old enough that no mirror file is ever kept merely for being newer. */
        const val DEVICE_TIME = 1_700_000_000_000L
    }
}
