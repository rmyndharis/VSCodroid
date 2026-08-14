package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

/**
 * `ShouldOverwriteMirrorTest` pins the decision. This pins the *call to it*: the guard
 * could be deleted from [SafSyncEngine.initialSync] with all seven of those methods
 * green, which is how the fix for #84 shipped without anything holding it in place.
 *
 * These run `initialSync` itself. No production seam was added for them — the engine
 * already takes a `Context`, and its far end is `ContentResolver.openInputStream`:
 * called means it copied, not called means it kept what was there. The mirror is a real
 * directory, so `exists`, `lastModified` and `length` are answered by the filesystem.
 *
 * Fixture values are chosen so the broken path cannot produce them. A local edit here is
 * both *newer* and a *different length* than the source — the combination the first
 * version of the guard got wrong, because it compared sizes before timestamps.
 */
class InitialSyncWiringTest {

    @TempDir
    lateinit var mirror: File

    /**
     * The sync now records what it copied, in a file beside the mirror rather than inside
     * it — so it lands outside the directory `@TempDir` cleans up, and each test method
     * gets a fresh directory and therefore a fresh name. Removed here so the runs do not
     * accumulate one small file apiece in the system temp root.
     */
    @AfterEach
    fun removeSyncRecord() {
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
    }

    /** mockkStatic replaces the class process-wide; see BridgeTokenUniformityTest.tearDown. */
    @AfterEach
    fun tearDown() = unmockkAll()

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private var openedDocuments = mutableListOf<String>()

    /** One file in the device folder, described to the engine through a fake cursor. */
    private fun deviceFolderHolding(name: String, contents: String, modified: Long) {
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
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } returns contents.toByteArray().size.toLong()
        every { cursor.getLong(4) } returns modified

        every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
        every { resolver.openInputStream(any()) } answers {
            openedDocuments.add(name)
            ByteArrayInputStream(contents.toByteArray())
        }
    }

    private fun mirrorHolding(name: String, contents: String, modified: Long): File =
        File(mirror, name).apply {
            writeText(contents)
            setLastModified(modified)
        }

    private fun sync() = runBlocking {
        SafSyncEngine(context).initialSync(mockk<Uri>(relaxed = true), mirror) { _, _ -> }
    }

    @BeforeEach
    fun setUp() {
        openedDocuments = mutableListOf()

        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns mockk(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
    }

    @Test
    fun `an unsaved local edit survives reopening the folder`() {
        // Newer AND a different length — the pairing the size-first rule overwrote.
        val local = mirrorHolding("notes.txt", "edited in the editor!", 1_700_000_060_000)
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)

        sync()

        assertEquals("edited in the editor!", local.readText(), "the local edit was overwritten")
        assertEquals(emptyList<String>(), openedDocuments, "the engine read the document it should have left alone")
    }

    @Test
    fun `an edit made on the device reaches the mirror`() {
        val local = mirrorHolding("notes.txt", "stale copy", 1_700_000_000_000)
        deviceFolderHolding("notes.txt", "changed on the phone", 1_700_000_060_000)

        sync()

        assertEquals("changed on the phone", local.readText())
        assertEquals(listOf("notes.txt"), openedDocuments)
    }

    @Test
    fun `a file the mirror does not have is fetched`() {
        deviceFolderHolding("fresh.txt", "brand new", 1_700_000_000_000)

        sync()

        assertEquals("brand new", File(mirror, "fresh.txt").readText())
        assertEquals(listOf("fresh.txt"), openedDocuments)
    }

    @Test
    fun `a mirror copy carries the source timestamp so later syncs compare one clock`() {
        deviceFolderHolding("stamped.txt", "content", 1_700_000_000_000)

        sync()

        assertEquals(1_700_000_000_000, File(mirror, "stamped.txt").lastModified())
    }
}
