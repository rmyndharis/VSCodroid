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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

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
 * by hand. A write through `"wt"` lands like the platform provider's: it replaces the text
 * and advances the time before close() returns. [LateStamp] is the other kind of provider,
 * which reports a write's final time and size only after that.
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

    /** False for a provider whose size column is null, which reads back as 0. */
    private var deviceHasSize = true
    private var writes = 0

    /** How many times the device document was opened for reading. */
    private var reads = 0

    /** How many bytes were read from the device document, over all opens. */
    private var bytesRead = 0L
    private val failed = mutableListOf<File>()

    /** Whether the device holds `notes.txt` at all; the editor can delete and make it again. */
    private var deviceHasDocument = true

    /** Set to make the provider fail the first query after the next write lands. */
    private var failStampAfterNextWrite = false
    private var failNextQuery = false

    /** Set to make the next `"wt"` stream fail at its first byte, before anything lands. */
    private var failNextWriteStream = false

    /** How the provider reports a write; null for one whose stamp is final at close(). */
    private var lateStamp: LateStamp? = null

    /** What a [lateStamp] provider reports once it has finished the last write. */
    private var settled: Pair<Long, Long>? = null

    /**
     * Providers that finish what they report for a write after its stream has closed. Each
     * entry says what is reported right after close() and once the provider is done; no
     * other app writes in between. A FAT card behind the FUSE cache rounds the time the
     * way [WHOLE_SECONDS] does, and Round Sync's cache moves the way [ROW] does.
     */
    enum class LateStamp {
        /** MTP: the row, time and size, is rewritten once the object reaches the device. */
        ROW,

        /** Nextcloud: the close stamps milliseconds, the finished upload whole seconds. */
        WHOLE_SECONDS,

        /** MTP to an Android phone, which keeps the old time: only the size moves, later. */
        SIZE,

        /** The time moves first and the length later, and the next save comes in between. */
        TIME_BEFORE_SIZE,
    }

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
        every { DocumentsContract.deleteDocument(any(), any()) } answers {
            deviceHasDocument = false
            true
        }
        // A new document is empty and stamped with the time it was made.
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            deviceHasDocument = true
            deviceText = ""
            deviceSize = 0
            deviceModified = CREATED_AT
            uris.getOrPut("doc:notes.txt") { mockk(relaxed = true) }
        }

        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            if (failNextQuery) {
                failNextQuery = false
                throw IllegalStateException("the provider did not answer")
            }
            deviceCursor()
        }
        // A real stream: a relaxed one answers 0 from `read`, and `copyTo` spins on it.
        every { resolver.openInputStream(any()) } answers {
            reads++
            object : ByteArrayInputStream(deviceText.toByteArray()) {
                override fun read(b: ByteArray, off: Int, len: Int) =
                    super.read(b, off, len).also { if (it > 0) bytesRead += it }

                override fun read() = super.read().also { if (it >= 0) bytesRead++ }
            }
        }
        every { resolver.openOutputStream(any(), "wt") } answers {
            if (failNextWriteStream) {
                failNextWriteStream = false
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("cut short")
                }
            } else {
                object : ByteArrayOutputStream() {
                    override fun close() = land(toByteArray())
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
        every { cursor.moveToNext() } answers { deviceHasDocument && ++row == 0 }
        every { cursor.moveToFirst() } answers { deviceHasDocument }
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
        every { cursor.getLong(3) } answers { if (deviceHasSize) deviceSize else 0L }
        every { cursor.getLong(4) } answers { deviceModified }
        return cursor
    }

    /** A write reaching the device, reported the way [lateStamp] says. */
    private fun land(bytes: ByteArray) {
        deviceText = String(bytes)
        writes++
        val size = bytes.size.toLong()
        when (lateStamp) {
            null -> {
                deviceSize = size
                deviceModified += 1_000
            }
            LateStamp.ROW -> settled = deviceModified + 1_000 to size
            LateStamp.WHOLE_SECONDS -> {
                deviceSize = size
                deviceModified += 1_234
                settled = deviceModified / 1_000 * 1_000 to size
            }
            LateStamp.SIZE -> settled = deviceModified to size
            LateStamp.TIME_BEFORE_SIZE -> settled = deviceModified + 1_000 to deviceSize
        }
        if (failStampAfterNextWrite) {
            failStampAfterNextWrite = false
            failNextQuery = true
        }
    }

    /** The provider finishes the last write; nothing else touches the document. */
    private fun settle() {
        settled?.let { (time, size) ->
            deviceModified = time
            deviceSize = size
        }
        settled = null
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

    /**
     * The same saves on a provider that finishes a write after its stream has closed, so
     * the time and size read right after this app's write are not the ones the next save
     * sees. The movement is this app's own write settling, and a copy of it is a duplicate
     * of the previous save, put into the user's folder on every save.
     */
    @ParameterizedTest(name = "own saves leave no copy: {0}")
    @EnumSource(LateStamp::class)
    fun `saves to a provider that settles its stamp late leave no device copy`(shape: LateStamp) {
        lateStamp = shape
        open()

        save("first save")
        settle()
        save("second save, longer")
        settle()
        save("third")
        settle()

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "this app's own earlier saves were kept as if another app had written them",
        )
        assertEquals("third", deviceText)
        assertEquals(3, writes)
    }

    /**
     * The first save after an open that fetched a write the provider had not finished: this
     * app's save from an earlier session, or a file another app has just put there. The
     * stamp then settles over the very copy the editor shows, and nothing but the open
     * read it, so the bytes the open fetched have to vouch for it as a write's do.
     */
    @ParameterizedTest(name = "first save after an open leaves no copy: {0}")
    @EnumSource(LateStamp::class)
    fun `the first save after an open that fetched a settling write leaves no device copy`(shape: LateStamp) {
        lateStamp = shape
        land("written just before the folder was opened".toByteArray())
        open()
        settle()

        save("first save after the open")

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "the copy the open fetched was kept as if another app had written it",
        )
        assertEquals("first save after the open", deviceText)
    }

    /**
     * The same saves with free space below the floor, where no device copy can be fetched
     * into the app's storage. Telling this app's own write apart must not need one, or on
     * such a provider every save after the first stays inside the app for the session.
     */
    @Test
    fun `saves to a provider that settles its stamp late still land below the space floor`() {
        lateStamp = LateStamp.ROW
        open()
        engine.usableSpaceOf = { SafSyncEngine.OPEN_SPACE_FLOOR_BYTES - 1 }

        save("first save")
        settle()
        save("second save, longer")
        settle()
        save("third")

        assertEquals(3, writes, "a save of this app's own settling write was held back")
        assertEquals("third", deviceText)
        assertEquals(emptyList<File>(), failed)
    }

    /**
     * A provider whose size column is null reports every length as 0, which is never the
     * length this app digested, so a stamp it settles late has to be read at that length
     * too, or each save after the first keeps a copy of the one before.
     */
    @Test
    fun `saves to a provider with no size column that settles late leave no device copy`() {
        deviceHasSize = false
        lateStamp = LateStamp.WHOLE_SECONDS
        open()

        save("first save")
        settle()
        save("second save, longer")
        settle()
        save("third")

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "this app's own earlier saves were kept as if another app had written them",
        )
        assertEquals(3, writes)
    }

    /**
     * The stamp a moved document was matched to this app's bytes at is what the next save
     * compares, whether or not a write followed the match. None follows where the mirror
     * file is a link, which is never written out, and without the match being kept each
     * later save read the document again to match the same bytes.
     */
    @Test
    fun `a device copy matched to this app's bytes is not read again at the same stamp`() {
        lateStamp = LateStamp.WHOLE_SECONDS
        open()
        save("first save")
        settle()
        val file = File(mirror, "notes.txt").apply { delete() }
        Files.createSymbolicLink(
            file.toPath(),
            File(root, "elsewhere.txt").apply { writeText("outside the folder") }.toPath(),
        )
        engine.handleMirrorEvent(FileObserver.MODIFY, file, mirror, treeUri)
        engine.runWriteBackLoop { false }
        val readsAfterMatch = reads
        file.delete()

        save("second save")

        assertEquals(readsAfterMatch, reads, "the device copy was read again at a stamp it was matched at")
        assertEquals("second save", deviceText)
        assertEquals(emptyMap<String, String>(), deviceCopies())
    }

    /**
     * What the open records for a fetched copy is the copy as it landed. Read off the mirror
     * file a moment later, it was whatever an editor saved into the file in that instant:
     * the record then vouched for an edit the device never received, as if the mirror could
     * be reclaimed, and the read at the next save stopped at the edit's shorter length, so
     * it missed the copy the open had fetched and kept a duplicate of it. The editor's save
     * is simulated in the digest the fetch takes right then, the one step in between.
     */
    @Test
    fun `a fetched copy is recorded as it landed when an editor saves at once`() {
        val file = File(mirror, "notes.txt")
        var editorSaved = false
        mockkStatic(MessageDigest::class)
        every { MessageDigest.getInstance("SHA-256") } answers {
            val real = callOriginal()
            object : MessageDigest("SHA-256") {
                override fun engineUpdate(input: Byte) = real.update(input)
                override fun engineUpdate(input: ByteArray, offset: Int, len: Int) =
                    real.update(input, offset, len)

                override fun engineReset() = real.reset()
                override fun engineDigest(): ByteArray {
                    if (!editorSaved && file.isFile) {
                        editorSaved = true
                        file.writeText("x")
                    }
                    return real.digest()
                }
            }
        }
        open()
        assertEquals(true, editorSaved, "the simulated save never ran")
        assertEquals(
            false, engine.holdsOnlyVouchedCopies(mirror),
            "the record vouched for an edit the device never received",
        )
        // The same bytes at another time, as a FAT card reports once its cached inode goes.
        deviceModified -= 337

        save("typed in the editor")

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "the copy the open fetched was kept as if another app had written it",
        )
        assertEquals("typed in the editor", deviceText)
    }

    /** What the guard is for still holds on such a provider: another app's edit is kept. */
    @ParameterizedTest(name = "a device edit is kept: {0}")
    @EnumSource(LateStamp::class)
    fun `a device edit is still set aside on a provider that settles its stamp late`(shape: LateStamp) {
        lateStamp = shape
        open()
        save("first save")
        settle()
        editOnDevice("changed by another app")

        save("typed in the editor")

        assertEquals(
            listOf("changed by another app"), deviceCopies().values.toList(),
            "another app's edit was taken for this app's own write and replaced",
        )
        assertEquals("typed in the editor", deviceText)
    }

    /**
     * Without the device copy in hand a moved stamp cannot be told from another app's edit,
     * so the save is held back even where the movement may be this app's own write still
     * settling. Nextcloud offline is the case: a read asks its server first.
     */
    @Test
    fun `a moved stamp whose device copy cannot be read holds the save back`() {
        lateStamp = LateStamp.WHOLE_SECONDS
        open()
        save("first save")
        settle()
        every { resolver.openInputStream(any()) } throws IOException("offline")

        save("second save")

        assertEquals(1, writes, "a save went over a device copy nothing could read")
        assertEquals("first save", deviceText)
        assertEquals(listOf(File(mirror, "notes.txt").absolutePath), failed.map { it.absolutePath })
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

    /**
     * A length other than the one this app last wrote or fetched is another app's edit
     * whatever its bytes hash to, so the guard does not read the document to find out. A
     * document grown past the copy limit is held back at every save, and was read in full
     * at each one.
     */
    @Test
    fun `a device edit grown past the copy limit is not read at each save`() {
        open()
        editOnDevice("too large to copy", size = SafSyncEngine.MAX_FILE_SIZE + 1)
        val readsBefore = reads

        save("typed in the editor")
        save("typed in the editor, and more")

        assertEquals(readsBefore, reads, "a device copy of another length was read to hash it")
        assertEquals(0, writes, "the save overwrote a device edit it could not keep")
        assertEquals(listOf(File(mirror, "notes.txt").absolutePath), failed.map { it.absolutePath })
    }

    /**
     * A moved document is hashed only as far as the bytes this app last wrote and a buffer
     * more, since a longer one cannot be that write. Another app's edit that grew it is then
     * read in full once, by the set-aside that keeps it, rather than twice.
     */
    @Test
    fun `a device edit that grew the document is read no further than this app's own bytes`() {
        open()
        save("first save")
        val grown = "x".repeat(100_000)
        editOnDevice(grown)
        bytesRead = 0

        save("typed in the editor")

        assertEquals(listOf(grown), deviceCopies().values.toList())
        assertTrue(bytesRead < 2L * grown.length, "the grown document was read twice in full: $bytesRead bytes")
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
     * A file deleted in the editor and then made again gets a new, empty document, while
     * the entry the deleted one left still stands. Asked about the new document, the guard
     * would read its emptiness as a device edit and set it aside, which is why a document
     * the write itself has just created is not asked about at all.
     */
    @Test
    fun `a file deleted and made again in the editor leaves no device copy`() {
        open()
        val file = File(mirror, "notes.txt")
        file.delete()
        engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, treeUri)
        engine.runWriteBackLoop { false }

        file.writeText("brand new")
        engine.handleMirrorEvent(FileObserver.CREATE, file, mirror, treeUri)
        engine.runWriteBackLoop { false }

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "the empty document the save itself created was kept as another app's edit",
        )
        assertEquals("brand new", deviceText)
    }

    /**
     * A write whose stamp cannot be read back leaves no entry, so the next save fails open.
     * Keeping the entry from before the write instead reads this app's own write as a device
     * edit at the next save, and keeps a copy of it.
     */
    @Test
    fun `a save after a write whose stamp could not be read leaves no device copy`() {
        open()
        failStampAfterNextWrite = true
        save("first save")

        save("second save")

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "this app's own earlier save was kept as if another app had written it",
        )
        assertEquals("second save", deviceText)
    }

    /**
     * A write that lands on its second attempt is vouched for by what that attempt
     * streamed. The failed first attempt had already read the mirror file, so a digest
     * carried over from it holds the bytes twice, matches nothing the device can hold, and
     * the next save after the stamp settles keeps a copy of this app's own write.
     */
    @Test
    fun `a write that lands on its second attempt leaves no device copy`() {
        lateStamp = LateStamp.ROW
        open()
        failNextWriteStream = true
        save("first save")
        settle()

        save("second save")

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "this app's own retried save was kept as if another app had written it",
        )
        assertEquals("second save", deviceText)
        assertEquals(2, writes)
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
        const val CREATED_AT = 1_800_000_000_000L
    }
}
