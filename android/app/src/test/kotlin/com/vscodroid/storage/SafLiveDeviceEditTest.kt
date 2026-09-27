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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A save while the folder is open, meeting an edit another app made to the same document
 * since this engine last read or wrote it.
 *
 * The device is read only when the folder is opened, and a save opens the document with
 * `"wt"`, which truncates at open. So an edit made on the device while the folder was open
 * was replaced by the next save of that file, with no copy of it anywhere. The engine now
 * remembers what the device reported for each document (time and size) and, when that has
 * moved, keeps the device copy beside the mirror file as `.device-<time>` before writing.
 *
 * The device here is one document, `notes.txt`, whose text, time and size the cases move
 * by hand. A write through `"wt"` lands like a real provider's: it replaces the text and
 * advances the time.
 */
class SafLiveDeviceEditTest {

    @TempDir
    lateinit var root: File

    private lateinit var mirror: File
    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri
    private val uris = mutableMapOf<String, Uri>()

    private var deviceText = "v1"
    private var deviceModified = OPENED_AT
    private var deviceSize = 2L
    private var deviceHasClock = true
    private var writes = 0
    private val failed = mutableListOf<File>()

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

        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } answers { deviceCursor() }
        // A real stream: a relaxed one answers 0 from `read`, and `copyTo` spins on it.
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream(deviceText.toByteArray())
        }
        every { resolver.openOutputStream(any(), "wt") } answers {
            object : ByteArrayOutputStream() {
                override fun close() {
                    deviceText = String(toByteArray())
                    deviceSize = size().toLong()
                    deviceModified += 1_000
                    writes++
                }
            }
        }

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns File(root, "files").apply { mkdirs() }
        engine = SafSyncEngine(context)
        engine.onWriteBackFailed = { failed += it }
        treeUri = mockk(relaxed = true)
        mirror = File(root, "mirror-a").apply { mkdirs() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * One row for `notes.txt`, answering both the enumeration and a single-document query.
     * With no clock the time column is missing, as some providers leave it.
     */
    private fun deviceCursor(): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        every { cursor.moveToNext() } answers { ++row == 0 }
        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndexOrThrow(any()) } answers {
            when (firstArg<String>()) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                else -> 3
            }
        }
        every { cursor.getColumnIndex(any()) } answers {
            when (firstArg<String>()) {
                DocumentsContract.Document.COLUMN_SIZE -> 3
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> if (deviceHasClock) 4 else -1
                else -> -1
            }
        }
        every { cursor.isNull(any()) } returns false
        every { cursor.getString(0) } returns "doc:notes.txt"
        every { cursor.getString(1) } returns "notes.txt"
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } answers { deviceSize }
        every { cursor.getLong(4) } answers { deviceModified }
        return cursor
    }

    private fun open(mirrorDir: File = mirror, tree: Uri = treeUri) =
        runBlocking { engine.initialSync(tree, mirrorDir) { _, _ -> } }

    /** An editor save: the mirror file is written in place, and the watcher's job runs. */
    private fun save(text: String, mirrorDir: File = mirror, tree: Uri = treeUri) {
        val file = File(mirrorDir, "notes.txt").apply { writeText(text) }
        engine.handleMirrorEvent(FileObserver.MODIFY, file, mirrorDir, tree)
        engine.runWriteBackLoop { false }
    }

    private fun editOnDevice(text: String, size: Long = text.length.toLong()) {
        deviceText = text
        deviceSize = size
        deviceModified += 60_000
    }

    /** The `.device-` copies in [mirrorDir], by name, with what each holds. */
    private fun deviceCopies(mirrorDir: File = mirror): Map<String, String> =
        mirrorDir.listFiles()!!
            .filter { it.name.startsWith("notes.txt" + SafSyncEngine.DEVICE_COPY_SUFFIX) }
            .associate { it.name to it.readText() }

    @Test
    fun `a device edit made while the folder is open is set aside before a save replaces it`() {
        open()
        editOnDevice("changed by another app")
        val editedAt = deviceModified

        save("typed in the editor")

        assertEquals(
            mapOf("notes.txt" + SafSyncEngine.DEVICE_COPY_SUFFIX + editedAt to "changed by another app"),
            deviceCopies(),
            "the save replaced another app's edit with no copy of it anywhere",
        )
        assertEquals("typed in the editor", deviceText, "the save did not reach the device")
    }

    @Test
    fun `saves with no device edit in between leave no device copy`() {
        open()

        save("first save")
        save("second save")

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "this app's own earlier save was kept as if another app had written it",
        )
        assertEquals("second save", deviceText)
    }

    @Test
    fun `a device edit that cannot be set aside holds the save back`() {
        open()
        editOnDevice("too large to copy", size = SafSyncEngine.MAX_FILE_SIZE + 1)

        save("typed in the editor")

        assertEquals(0, writes, "the save overwrote a device edit it could not keep")
        assertEquals("too large to copy", deviceText)
        assertEquals(
            listOf(File(mirror, "notes.txt").absolutePath), failed.map { it.absolutePath },
            "the save stayed inside the app and nothing said so",
        )
        assertEquals("typed in the editor", File(mirror, "notes.txt").readText())
    }

    /** `mv` over the name arrives as MOVED_TO, which writes into the existing document. */
    @Test
    fun `a file replaced by rename keeps a device edit`() {
        open()
        editOnDevice("changed by another app")
        val file = File(mirror, "notes.txt").apply { writeText("moved over it") }

        engine.handleMirrorEvent(FileObserver.MOVED_TO, file, mirror, treeUri)
        engine.runWriteBackLoop { false }

        assertEquals(listOf("changed by another app"), deviceCopies().values.toList())
        assertEquals("moved over it", deviceText)
    }

    /**
     * Without a clock every copy is named `.device-0`, and a set-aside replaces its
     * destination, so the second one of a session used to destroy the first.
     */
    @Test
    fun `a provider with no clock keeps both foreign edits of one session`() {
        deviceHasClock = false
        open()
        editOnDevice("first foreign edit")
        save("editor one")
        editOnDevice("second foreign edit, longer")
        save("editor two")

        assertEquals(
            setOf("first foreign edit", "second foreign edit, longer"),
            deviceCopies().values.toSet(),
            "one device edit was lost: ${deviceCopies()}",
        )
        assertEquals("editor two", deviceText)
    }

    /**
     * A folder switch that fails partway restores the previous folder's watcher on the
     * same engine, so opening another folder must not drop the first one's memory.
     */
    @Test
    fun `opening a second folder keeps the first folder's guard`() {
        open()
        open(File(root, "mirror-b").apply { mkdirs() }, mockk(relaxed = true))
        editOnDevice("changed by another app")

        save("typed in the editor")

        assertEquals(listOf("changed by another app"), deviceCopies().values.toList())
    }

    private companion object {
        const val OPENED_AT = 1_700_000_000_000L
    }
}
