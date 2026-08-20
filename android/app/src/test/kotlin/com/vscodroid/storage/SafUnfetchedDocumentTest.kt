package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.FileObserver
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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * What happens to a device document the sync could not read, when a local file
 * of that name appears afterwards.
 *
 * The sync leaves no trace of a document it skipped, so "absent from the mirror"
 * carries two meanings at once: not on the device, and on the device holding
 * content this app has never seen. Every write-back path used to resolve that
 * the destructive way, and by design rather than by accident: `createOneInSaf`
 * exists precisely to write into an existing document rather than fork it, so
 * the write opens the device's file with truncation. A 60 MB archive skipped for
 * size became whatever the user typed into a new file of that name, and nothing
 * on screen or in a release logcat said so, because the skip was logged at debug.
 *
 * A running watcher is required for any of this, so it does its damage in the
 * sessions the picker opened, which is the opposite population from the folders
 * the workbench opens for itself.
 */
class SafUnfetchedDocumentTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri
    private val uris = mutableMapOf<String, Uri>()

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
        // Needed by the control that uploads a genuinely new file: without it the
        // static answers null, no document exists to write into, and the case
        // would pass by doing nothing, which is the failure it is here to exclude.
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            uris.getOrPut("doc:" + lastArg<String>()) { mockk(relaxed = true) }
        }

        resolver = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        unmockkAll()
    }

    /**
     * One file in the device folder, described through a fake cursor. [size]
     * decides whether the sync fetches it; [readable] decides whether the fetch
     * succeeds.
     */
    private fun deviceHolding(name: String, size: Long, readable: Boolean = true) {
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
        every { cursor.getString(0) } returns "doc:$name"
        every { cursor.getString(1) } returns name
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } returns size
        every { cursor.getLong(4) } returns 1_700_000_000_000
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
        every { resolver.openInputStream(any()) } answers {
            if (readable) ByteArrayInputStream("device contents".toByteArray())
            else throw IOException("the provider refused the read")
        }
    }

    /** One event, and the write-back thread's pass over what it queued. */
    private fun deliver(event: Int, entry: String) {
        engine.handleMirrorEvent(event, File(mirror, entry), mirror, treeUri)
        engine.runWriteBackLoop { false }
    }

    /**
     * The case the guard exists for. The document is past [SafSyncEngine.MAX_FILE_SIZE],
     * so the sync never reads it and the mirror never holds it. A local file of
     * that name then arrives, and the write opens the device's document with
     * truncation.
     */
    @Test
    fun `a document too large to mirror is not replaced by a local file of that name`() {
        deviceHolding("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        File(mirror, "big.zip").writeText("stub")
        deliver(FileObserver.CREATE, "big.zip")

        verify(exactly = 0) { resolver.openOutputStream(any(), "wt") }
    }

    /** The same shape when the copy was attempted and failed rather than skipped. */
    @Test
    fun `a document whose copy failed is not replaced either`() {
        deviceHolding("notes.md", size = 64, readable = false)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        File(mirror, "notes.md").writeText("stub")
        deliver(FileObserver.CREATE, "notes.md")

        verify(exactly = 0) { resolver.openOutputStream(any(), "wt") }
    }

    /**
     * The control, and it is the one that keeps the two above from passing for
     * the wrong reason. A document the sync did read is mirrored, so a later edit
     * to the mirror is an edit to something this app has a copy of, and writing
     * it back is the whole point of the feature.
     */
    @Test
    fun `a document the sync did read is still written back`() {
        deviceHolding("notes.md", size = 64)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        File(mirror, "notes.md").writeText("edited in the editor")
        deliver(FileObserver.MODIFY, "notes.md")

        verify(atLeast = 1) { resolver.openOutputStream(any(), "wt") }
    }

    /**
     * The set is a memory of what this sync could not read, not a permanent
     * refusal. If the device no longer holds a document at that name, the local
     * file is an ordinary new file and belongs on the device.
     */
    @Test
    fun `a name the device no longer holds is uploaded normally`() {
        deviceHolding("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        // The device folder is now empty: the oversized document was deleted
        // there between the sync and the event.
        val empty = mockk<Cursor>(relaxed = true)
        every { empty.moveToNext() } returns false
        every { resolver.query(any(), any(), any(), any(), any()) } returns empty

        File(mirror, "big.zip").writeText("a new file that happens to share the name")
        deliver(FileObserver.CREATE, "big.zip")

        // A name the device does not hold is created and then written, so the
        // write happening at all is what separates this from the guarded cases.
        verify(atLeast = 1) { resolver.openOutputStream(any(), "wt") }
    }

    /**
     * The four cases above pin that the device document survives. These pin the other
     * half, which was missing: that the user is told their edit did not travel.
     *
     * Refusing the write is correct and it is also invisible. The mirror holds the
     * edit, the editor reports the save, the tab goes clean, and the device folder
     * still holds a different document. That is the shape the project's own rule
     * forbids, a save that looks identical to one that worked, and the engine's KDoc
     * names this group as "the user's only copy" while announcing nothing about it.
     *
     * Asserted on the seam rather than on a Toast: the engine has no screen, and
     * `WriteBackNoticeWiringTest` covers the Activity end of the same channel.
     */
    @Test
    fun `a refused write-back tells the user their edit stayed in the app`() {
        val announced = mutableListOf<String>()
        engine.onWriteBackFailed = { announced.add(it.name) }

        deviceHolding("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        File(mirror, "big.zip").writeText("stub")
        deliver(FileObserver.CREATE, "big.zip")

        assertEquals(
            listOf("big.zip"),
            announced,
            "the write was refused and nothing said so, which is the silence this " +
                "guard was supposed to end rather than create",
        )
    }

    /** The same, for a document the sync tried to read and could not. */
    @Test
    fun `a copy that failed is announced too`() {
        val announced = mutableListOf<String>()
        engine.onWriteBackFailed = { announced.add(it.name) }

        deviceHolding("notes.md", size = 64, readable = false)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        File(mirror, "notes.md").writeText("stub")
        deliver(FileObserver.MODIFY, "notes.md")

        assertEquals(listOf("notes.md"), announced, "a failed copy refused the write silently")
    }

    /**
     * The negative control, and the one that stops the two above from passing for the
     * wrong reason. A write that actually lands must announce nothing: an app that
     * warns on every successful save teaches the user to ignore the warning, which
     * costs more than the silence did.
     */
    @Test
    fun `a write-back that lands announces nothing`() {
        val announced = mutableListOf<String>()
        engine.onWriteBackFailed = { announced.add(it.name) }

        deviceHolding("notes.md", size = 64)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        File(mirror, "notes.md").writeText("edited in the editor")
        deliver(FileObserver.MODIFY, "notes.md")

        verify(atLeast = 1) { resolver.openOutputStream(any(), "wt") }
        assertTrue(
            announced.isEmpty(),
            "a save that reached the device folder still warned the user: $announced",
        )
    }

    /**
     * Once per file, not once per save. The refusal fires on every inotify MODIFY, so an
     * editor autosaving reaches it every few seconds, and the notice is throttled by one
     * timer for the whole folder: a wall of the same message would also swallow an
     * unrelated write-back failure for as long as it lasted.
     */
    @Test
    fun `an editor saving the same file again is not announced again`() {
        val announced = mutableListOf<String>()
        engine.onWriteBackFailed = { announced.add(it.name) }

        deviceHolding("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        File(mirror, "big.zip").writeText("stub")

        repeat(3) { deliver(FileObserver.MODIFY, "big.zip") }

        assertEquals(listOf("big.zip"), announced, "one notice per file, not one per save")
    }

    /**
     * The other refusal site cannot be driven from here at all: reaching it means walking
     * into a directory, and delivering a directory event builds a `FileObserver`, whose
     * static initializer needs native code. What holds it to the behaviour above is that
     * there is one refusal in the file, so this reads the source and pins that.
     */
    @Test
    fun `the refusal message has one home, so both sites announce`() {
        val engineSource =
            File("../../android/app/src/main/kotlin/com/vscodroid/storage/SafSyncEngine.kt")
        assertTrue(engineSource.isFile, "SafSyncEngine.kt is not where this test expects it")

        assertEquals(
            1,
            Regex("sync never read, and writing would replace it")
                .findAll(engineSource.readText()).count(),
            "a second copy of the refusal means a site that refuses without saying so",
        )
    }
}
