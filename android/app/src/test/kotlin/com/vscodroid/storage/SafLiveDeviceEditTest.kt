package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver
import android.os.SystemClock
import android.provider.DocumentsContract
import com.vscodroid.SourceScan
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * by hand, and for a case about the open's reads a second, `other.txt`, that nothing
 * changes. A write through `"wt"` lands like the platform provider's: it replaces the text
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

    /** False for a provider that will not open the document for reading, as offline. */
    private var deviceReadable = true

    /** What the engine's held-back saves are timed by, `SystemClock.elapsedRealtime()`. */
    private var clock = 0L
    private val failed = mutableListOf<File>()

    /** What the engine said it kept in the device folder when a delete would have taken it. */
    private val kept = mutableListOf<File>()

    /**
     * For each of [kept], whether it was said to be kept because the device's version may have
     * changed since the editor read it, rather than because the editor never had it.
     */
    private val keptAsChanged = mutableListOf<Boolean>()

    /** Whether the device holds `notes.txt` at all; the editor can delete and make it again. */
    private var deviceHasDocument = true

    /** The id the device gives `notes.txt` now. */
    private var docId = "doc:notes.txt"

    /**
     * Documents another app deleted, on a provider whose ids are not paths: a query finds no
     * row, nothing opens them, and the document a create makes next gets an id of its own.
     */
    private val gone = mutableSetOf<Uri>()

    /** Set to make the provider fail the first query after the next write lands. */
    private var failStampAfterNextWrite = false
    private var failNextQuery = false

    /** How many of the next `"wt"` streams fail at their first byte, before anything lands. */
    private var failWriteStreams = 0

    /** How the provider reports a write; null for one whose stamp is final at close(). */
    private var lateStamp: LateStamp? = null

    /** What a [lateStamp] provider reports once it has finished the last write. */
    private var settled: Pair<Long, Long>? = null

    /** A second document, `other.txt`, listed after `notes.txt` when set, at [otherModified]. */
    private var otherText: String? = null
    private var otherModified = OPENED_AT

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

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { clock }

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
            if (uris[docId] in gone) docId += "+"
            uris.getOrPut(docId) { mockk(relaxed = true) }
        }

        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            if (firstArg<Uri>() in gone) return@answers mockk<Cursor>(relaxed = true)
            if (failNextQuery) {
                failNextQuery = false
                throw IllegalStateException("the provider did not answer")
            }
            deviceCursor(listing = firstArg<Uri>() !in uris.values)
        }
        // A real stream: a relaxed one answers 0 from `read`, and `copyTo` spins on it.
        every { resolver.openInputStream(any()) } answers {
            reads++
            if (firstArg<Uri>() in gone) throw FileNotFoundException("deleted by another app")
            if (!deviceReadable) throw IOException("offline")
            val text = if (firstArg<Uri>() === uris[OTHER_ID]) otherText!! else deviceText
            object : ByteArrayInputStream(text.toByteArray()) {
                override fun read(b: ByteArray, off: Int, len: Int) =
                    super.read(b, off, len).also { if (it > 0) bytesRead += it }

                override fun read() = super.read().also { if (it >= 0) bytesRead++ }
            }
        }
        every { resolver.openOutputStream(any(), "wt") } answers {
            if (firstArg<Uri>() in gone) throw FileNotFoundException("deleted by another app")
            if (failWriteStreams > 0) {
                failWriteStreams--
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
        engine.onKeptOnDevice = { file, _, changed ->
            kept += file
            keptAsChanged += changed
        }
        treeUri = mockk(relaxed = true)
        mirror = File(root, "mirror-a").apply { mkdirs() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * One row for `notes.txt`, answering both the enumeration and a single-document query,
     * and in a [listing] a second for [otherText] where it is set. With no clock the time
     * column is missing, as some providers leave it.
     */
    private fun deviceCursor(listing: Boolean): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        val other = listing && otherText != null
        val rows = (if (deviceHasDocument) 1 else 0) + (if (other) 1 else 0)
        val onOther = { other && row == rows - 1 }
        every { cursor.moveToNext() } answers { ++row < rows }
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
        every { cursor.getString(0) } answers { if (onOther()) OTHER_ID else docId }
        every { cursor.getString(1) } answers { if (onOther()) "other.txt" else "notes.txt" }
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } answers {
            when {
                onOther() -> otherText!!.length.toLong()
                deviceHasSize -> deviceSize
                else -> 0L
            }
        }
        every { cursor.getLong(4) } answers { if (onOther()) otherModified else deviceModified }
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

    private fun open(
        mirrorDir: File = mirror,
        tree: Uri = treeUri,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) = runBlocking { engine.initialSync(tree, mirrorDir, onProgress) }

    /** An editor save: the mirror file is written in place, and the watcher's job runs. */
    private fun save(text: String, mirrorDir: File = mirror, tree: Uri = treeUri) {
        val file = File(mirrorDir, "notes.txt").apply { writeText(text) }
        engine.handleMirrorEvent(FileObserver.MODIFY, file, mirrorDir, tree)
        engine.runWriteBackLoop { false }
    }

    /** One turn of the write-back loop of a watcher on [mirrorDir], then its drain. */
    private fun retryWhileWatching(mirrorDir: File = mirror) {
        var turns = 0
        engine.runWriteBackLoop(WatchSession(mirrorDir)) { turns++ == 0 }
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

    private val record: File get() = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)

    /**
     * The record as a build before the digests wrote it, the one an update finds: the same
     * lines without the digest after each, so the next open has a kept copy to read.
     */
    private fun recordWithoutDigests() {
        record.writeText(record.readLines().joinToString("\n") { SafSyncEngine.splitRecordLine(it).first })
    }

    /** The digest the record's line for `notes.txt` carries, or null where it carries none. */
    private fun recordedDigest(): ByteArray? =
        SafSyncEngine.splitRecordLine(record.readLines().single { it.startsWith("notes.txt\t") }).second

    /**
     * Runs [onUpdate] with the length of every chunk the engine's SHA-256s are handed from
     * now on, and [onDigest] as each of them finishes, inside the engine's own call: the one
     * step a test can stand in, between the reads that feed a digest and what is done with it.
     */
    private fun duringDigests(onUpdate: (Int) -> Unit = {}, onDigest: () -> Unit = {}) {
        mockkStatic(MessageDigest::class)
        every { MessageDigest.getInstance("SHA-256") } answers {
            val real = callOriginal()
            object : MessageDigest("SHA-256") {
                override fun engineUpdate(input: Byte) {
                    onUpdate(1)
                    real.update(input)
                }

                override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
                    onUpdate(len)
                    real.update(input, offset, len)
                }

                override fun engineReset() = real.reset()
                override fun engineDigest(): ByteArray {
                    onDigest()
                    return real.digest()
                }
            }
        }
    }

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
        duringDigests(onDigest = {
            if (!editorSaved && file.isFile) {
                editorSaved = true
                file.writeText("x")
            }
        })
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

    /**
     * A copy a reopen kept rather than fetched, under a stamp the provider moves afterwards
     * with the bytes unchanged, as a FAT card behind the FUSE cache does once its cached
     * inode is evicted. The document is the one the editor shows, so the first save must not
     * keep a copy of it, and nothing but that reopen read it.
     */
    @Test
    fun `a stamp moved after a reopen over a kept copy leaves no device copy`() {
        open()
        val readsBeforeReopen = reads
        open()
        assertEquals(readsBeforeReopen, reads, "the reopen fetched the document instead of keeping it")
        deviceModified -= 337

        save("typed in the editor")

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "the copy the reopen kept was taken for another app's edit",
        )
        assertEquals("typed in the editor", deviceText)
    }

    /**
     * What bounds the reads an open spends on the kept copies the record holds no digest for,
     * as after an update from a build that recorded none: one larger than the budget is not
     * read, so a stamp moved over it still keeps one spare copy of the unchanged document.
     */
    @Test
    fun `a kept copy the record holds no digest for is not read past the open's budget`() {
        open()
        recordWithoutDigests()
        engine.keptCopyDigestBytes = 1
        open()
        deviceModified -= 337

        save("typed in the editor")

        assertEquals(listOf("v1"), deviceCopies().values.toList(), "a kept copy past the budget was read")
    }

    /**
     * The budget is the whole open's: a kept copy that would fit in it alone is not read once
     * the copies read before it have spent it, or such an open read the whole folder. The
     * newer copy goes first, because a provider moves a stamp after a write and the recent
     * copies are the ones it moves.
     */
    @Test
    fun `kept copies the record holds no digest for share one budget, newest first`() {
        deviceText = "x".repeat(1_000)
        deviceSize = 1_000
        otherText = "y".repeat(1_200)
        otherModified = OPENED_AT + 60_000
        open()
        recordWithoutDigests()
        engine.keptCopyDigestBytes = 1_500
        var hashed = 0L
        duringDigests(onUpdate = { hashed += it })

        open()

        assertEquals(1_200L, hashed, "the open did not read the newer copy alone within its budget")
    }

    /**
     * The budget is the figure the user guide and the changelog give for it, in the decimal
     * megabytes every size a user reads is written in (`StorageManager.formatSize`). It was
     * 64 MiB under a "64 MB", which no reader of either could tell.
     */
    @Test
    fun `the kept-copy budget is the figure the guide and the changelog give`() {
        for (path in listOf("../../docs/USER_GUIDE.md", "../../CHANGELOG.md")) {
            val text = SourceScan.read(path).replace(Regex("""\s+"""), " ")
            val megabytes = Regex("""(\d+) MB per opening""").find(text)?.groupValues?.get(1)?.toLong()
            assertEquals(
                SafSyncEngine.KEPT_COPY_DIGEST_BYTES, megabytes?.times(1_000_000),
                "$path gives another figure, or none, for what an open reads of the kept copies",
            )
        }
    }

    /**
     * The digest a fetch takes goes onto the copy's line in the record, and stays there
     * while later opens keep the copy, so they take it rather than read the copy again. With
     * no budget left to read anything, a stamp moved two opens later still leaves no spare
     * copy. Every open used to read every kept copy again, which for a source tree is the
     * whole folder.
     */
    @Test
    fun `a digest the record holds serves every open that keeps its copy`() {
        open()
        engine.keptCopyDigestBytes = 0
        open()
        open()
        deviceModified -= 337

        save("typed in the editor")

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "the reopen had no digest for the copy an earlier open fetched",
        )
        assertEquals("typed in the editor", deviceText)
    }

    /**
     * After an update from a build that recorded no digests, the first open reads a kept copy
     * within its budget and records what it read, so the next open does not read it again.
     */
    @Test
    fun `a kept copy read by one open is not read by the next`() {
        open()
        recordWithoutDigests()
        open()
        engine.keptCopyDigestBytes = 0
        open()
        deviceModified -= 337

        save("typed in the editor")

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "the digest an open read was not there for the open after it",
        )
    }

    /**
     * A kept copy an editor shortens while the open reads it: what the read hashed is part of
     * the copy the record line names, and as that copy's digest it would be wrong for as long
     * as the line stands.
     */
    @Test
    fun `a kept copy that shrinks while the open reads it leaves no digest on its line`() {
        deviceText = "x".repeat(20_000)
        deviceSize = 20_000
        open()
        recordWithoutDigests()
        val file = File(mirror, "notes.txt")
        var shortened = false
        duringDigests(onUpdate = {
            if (!shortened) {
                shortened = true
                RandomAccessFile(file, "rw").use { it.setLength(100) }
            }
        })

        open()

        assertTrue(shortened, "the open did not read the kept copy")
        assertNull(recordedDigest(), "a digest of part of the copy was recorded as the copy's")
    }

    /**
     * The same copy lengthened while the open reads it, as a log being written is. The read
     * stops a buffer past the length the line names, which is what the open's budget was
     * charged for it, rather than following the file for as long as it grows.
     */
    @Test
    fun `a kept copy that grows while the open reads it is hashed no further than its length`() {
        deviceText = "x".repeat(20_000)
        deviceSize = 20_000
        open()
        recordWithoutDigests()
        val file = File(mirror, "notes.txt")
        var hashed = 0L
        duringDigests(onUpdate = { length ->
            if (hashed == 0L) file.appendText("y".repeat(100_000))
            hashed += length
        })

        open()

        assertTrue(hashed > 0, "the open did not read the kept copy")
        assertTrue(hashed <= 20_000, "the open hashed $hashed bytes of a copy recorded at 20000")
        assertNull(recordedDigest(), "a digest of more than the copy was recorded as the copy's")
    }

    /**
     * A write that lands while the open is digesting a kept copy, the closing folder's drain
     * finishing a save: the entry that write leaves is what the next save has to compare
     * against. Replaced by the digest of the copy the open kept, under the stamp the open
     * listed, the next save found the stamp the write moved and kept a spare copy of it.
     */
    @Test
    fun `a write landing while the open digests a kept copy keeps the entry it left`() {
        open()
        recordWithoutDigests()
        var landed = false
        duringDigests(onDigest = {
            if (!landed) {
                landed = true
                save("saved as the folder opened")
            }
        })
        open()
        assertTrue(landed, "the open did not read the kept copy")

        save("typed in the editor")

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "the open's digest of the kept copy replaced what the write left",
        )
        assertEquals("typed in the editor", deviceText)
    }

    /**
     * A copy the reopen vouched for that another sync over the same mirror replaces before
     * it is digested, the two syncs an activity recreated mid-open leaves. The newer device
     * copy that sync fetched is not the kept one, and its digest taken for the kept copy's
     * let the next save replace another app's edit without keeping it.
     */
    @Test
    fun `a newer copy another sync renames in during a reopen is not taken for the kept one`() {
        open()
        var replaced = false
        open { _, _ ->
            if (replaced) return@open
            replaced = true
            editOnDevice("changed by another app")
            File(mirror, "notes.txt").apply {
                writeText(deviceText)
                setLastModified(deviceModified)
            }
        }

        save("typed in the editor")

        assertEquals(
            listOf("changed by another app"), deviceCopies().values.toList(),
            "another app's edit was taken for the copy the reopen kept and replaced",
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

    /**
     * A save held back because its device copy could not be read is tried again by itself,
     * and lands once the copy can be read. It waited for the next save of that file or the
     * next open of the folder, so on Nextcloud the save stayed in the app after the network
     * came back for as long as the user did neither.
     */
    @Test
    fun `a held-back save lands once its device copy can be read again`() {
        lateStamp = LateStamp.WHOLE_SECONDS
        open()
        save("first save")
        settle()
        deviceReadable = false
        save("second save")
        assertEquals(1, writes, "a save went over a device copy nothing could read")
        deviceReadable = true
        clock += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS

        retryWhileWatching()

        assertEquals(2, writes, "the held-back save did not land once its device copy could be read")
        assertEquals("second save", deviceText)
        assertEquals(emptyMap<String, String>(), deviceCopies())
    }

    /** The try is the guard's own question, so another app's edit made meanwhile is kept. */
    @Test
    fun `a held-back save that lands over another app's edit keeps that edit`() {
        lateStamp = LateStamp.WHOLE_SECONDS
        open()
        save("first save")
        settle()
        deviceReadable = false
        save("second save")
        editOnDevice("changed by another app")
        deviceReadable = true
        clock += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS

        retryWhileWatching()

        assertEquals(listOf("changed by another app"), deviceCopies().values.toList())
        assertEquals("second save", deviceText)
    }

    /**
     * What a provider that stays unreadable costs: one try per held-back save per wait, the
     * wait doubling from the first to the longest and staying there.
     */
    @Test
    fun `a held-back save is tried only once its wait is over, and waits longer each time`() {
        lateStamp = LateStamp.WHOLE_SECONDS
        open()
        save("first save")
        settle()
        deviceReadable = false
        save("second save")

        var wait = SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
        repeat(6) {
            clock += wait - 1
            val before = reads
            retryWhileWatching()
            assertEquals(before, reads, "tried before its wait of $wait ms was over")
            clock += 1
            retryWhileWatching()
            assertEquals(before + 2, reads, "not hashed and fetched once its wait of $wait ms was over")
            wait = minOf(wait * 2, SafSyncEngine.HELD_BACK_RETRY_MAX_MS)
        }
        assertEquals(1, writes)
    }

    /**
     * Held-back saves that are due are tried one per idle turn of the loop, which polls its
     * queue before it asks again. Tried all at once, a save made meanwhile waited behind
     * every try that was due, each a stamp query and the reads that fail. Here a second
     * file, made in the editor, goes to the same device document.
     */
    @Test
    fun `held-back saves that are due are tried one per idle turn`() {
        open()
        val other = File(mirror, "other.txt").apply { writeText("other") }
        engine.handleMirrorEvent(FileObserver.CREATE, other, mirror, treeUri)
        engine.runWriteBackLoop { false }
        deviceReadable = false
        editOnDevice("changed by another app")
        save("typed in the editor")
        other.writeText("other, typed in the editor")
        engine.handleMirrorEvent(FileObserver.MODIFY, other, mirror, treeUri)
        engine.runWriteBackLoop { false }
        assertEquals(1, writes, "a save went over a device copy nothing could read")
        clock += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
        val before = reads

        retryWhileWatching()
        assertEquals(before + 2, reads, "more than one held-back save was tried in one turn")
        retryWhileWatching()
        assertEquals(before + 4, reads, "the other held-back save was not tried at the next turn")
    }

    /** Only the loop watching the save's own folder tries it, so a closed folder is not written. */
    @Test
    fun `a held-back save is not tried by a watcher on another folder`() {
        lateStamp = LateStamp.WHOLE_SECONDS
        open()
        save("first save")
        settle()
        deviceReadable = false
        save("second save")
        deviceReadable = true
        clock += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS

        retryWhileWatching(File(root, "mirror-b").apply { mkdirs() })

        assertEquals(1, writes, "a save was written into a folder no watcher is on")
    }

    /**
     * A file deleted in the editor and made again is a new document, which its own create
     * writes. The save held back for the deleted one has nothing left to send, and a try of
     * it, which the watcher's loop makes whenever its queue is empty and so can make before
     * the create's event arrives, went to the deleted document: here that is the same one,
     * so it only writes again, while a provider whose ids are not paths fails the write and
     * reports a save as lost.
     */
    @Test
    fun `a held-back save of a file deleted and made again is not tried`() {
        lateStamp = LateStamp.WHOLE_SECONDS
        open()
        save("first save")
        settle()
        deviceReadable = false
        save("second save")
        // Readable again for the delete, which a moved stamp over bytes it cannot read holds
        // back, so the file made again is a new document.
        deviceReadable = true
        val file = File(mirror, "notes.txt").apply { delete() }
        engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, treeUri)
        engine.runWriteBackLoop { false }
        file.writeText("brand new")
        val writesBefore = writes
        clock += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS

        retryWhileWatching()

        assertEquals(writesBefore, writes, "the deleted file's held-back save was tried")
        engine.handleMirrorEvent(FileObserver.CREATE, file, mirror, treeUri)
        engine.runWriteBackLoop { false }
        assertEquals("brand new", deviceText)
    }

    /**
     * A reopen of the folder while its closing drain is inside a read the provider takes
     * longer than the stop waits to refuse, as a server slow to fail does offline. The
     * reopen can neither read nor keep the device copy either, so it refuses the file's saves
     * until an open can, and it takes the device's listed stamp as what it last saw. The
     * drain went on from there: the hold it recorded after the reopen had cleared the holds
     * was tried, and a save queued behind the read ran, each finding that stamp unchanged
     * and writing over a device copy nothing had read.
     */
    @ParameterizedTest(name = "another save queued behind the read: {0}")
    @ValueSource(booleans = [false, true])
    fun `a reopen during a slow failing read leaves no save to go over what it could not read`(queuedBehind: Boolean) {
        open()
        save("first save")
        editOnDevice("changed by another app")
        val file = File(mirror, "notes.txt").apply { writeText("second save") }
        engine.handleMirrorEvent(FileObserver.MODIFY, file, mirror, treeUri)
        val closing = engine.session
        val inRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drain = Thread { engine.runWriteBackLoop(closing) { false } }
        every { resolver.openInputStream(any()) } answers {
            reads++
            if (Thread.currentThread() === drain && inRead.count > 0) {
                inRead.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            throw IOException("offline")
        }
        drain.start()
        assertTrue(inRead.await(5, TimeUnit.SECONDS), "the drain never reached the device copy")
        if (queuedBehind) {
            file.writeText("third save")
            engine.handleMirrorEvent(FileObserver.MODIFY, file, mirror, treeUri)
        }
        open { _, _ ->
            release.countDown()
            drain.join(5_000)
        }
        clock += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS

        retryWhileWatching()

        assertEquals(
            "changed by another app", deviceText,
            "a save went over a device copy the reopen could neither read nor keep",
        )
        assertEquals(1, writes)
        assertEquals(false, engine.retryHeldBack(WatchSession(mirror)), "a refused save was left to be tried again")
    }

    /**
     * A reopen settles a save held back before it as it settles any save the watcher did
     * not deliver, and starts the folder's holds afresh. Here the device keeps no times, so
     * the reopen, finding the two copies different, leaves both as they are. A hold left
     * over from before it was tried against the device's size the reopen listed, matched
     * it, and wrote over the device copy without keeping it. What this pins is that a hold
     * decided before that open is not tried at all; the next save of the file is what
     * decides, and keeps the device copy (see the no-clock reopen cases below).
     */
    @Test
    fun `a held-back save from before a reopen is not tried against what that open found`() {
        deviceHasClock = false
        open()
        save("first save")
        editOnDevice("changed by another app")
        deviceReadable = false
        save("second save")
        assertEquals(1, writes, "precondition: the second save was held back")
        deviceReadable = true
        open()
        clock += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS

        retryWhileWatching()

        assertEquals(
            "changed by another app", deviceText,
            "a save held back before the reopen was tried against what the reopen found",
        )
    }

    /** A save whose file has left the mirror has nothing to send, and queuing it spun the loop. */
    @Test
    fun `a held-back save whose file is gone is dropped rather than tried`() {
        lateStamp = LateStamp.WHOLE_SECONDS
        open()
        save("first save")
        settle()
        deviceReadable = false
        save("second save")
        File(mirror, "notes.txt").delete()
        clock += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS

        assertEquals(false, engine.retryHeldBack(WatchSession(mirror)), "a save with no file was tried")
        File(mirror, "notes.txt").writeText("made again")
        assertEquals(false, engine.retryHeldBack(WatchSession(mirror)), "a dropped save came back")
    }

    /**
     * A save held back over a document another app then deletes, a delete on the server
     * that Nextcloud syncs down among them, before the editor's file is replaced by a
     * rename, as git checkout, git stash and mv do. The create writes the file into a new
     * document, and that write is the save, whether it lands or is reported as failed. A
     * hold left standing was tried against the deleted document: it reported the save as
     * lost again, kept a journal line that refuses the mirror's reclaim, and dropped what
     * the new document was read as, so the next save replaced another app's edit of it
     * with no copy kept.
     */
    @ParameterizedTest(name = "the create lands: {0}")
    @ValueSource(booleans = [true, false])
    fun `a held-back save ends when its file is written into a new document`(createLands: Boolean) {
        lateStamp = LateStamp.WHOLE_SECONDS
        open()
        save("first save")
        settle()
        deviceReadable = false
        save("second save")
        assertEquals(1, writes, "a save went over a device copy nothing could read")
        gone += uris.getValue(docId)
        deviceHasDocument = false
        deviceReadable = true
        val file = File(mirror, "notes.txt")
        File(mirror, "notes.txt.tmp").apply { writeText("third") }.renameTo(file)
        if (!createLands) failWriteStreams = 2
        engine.handleMirrorEvent(FileObserver.MOVED_TO, file, mirror, treeUri)
        engine.runWriteBackLoop { false }
        val lost = failed.size
        clock += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS

        retryWhileWatching()

        assertEquals(lost, failed.size, "the hold was tried against the deleted document")
        if (createLands) {
            assertEquals(emptySet<String>(), engine.uploadsInFlight(), "a delivered save is on record as not")
        }
        editOnDevice("changed by another app")
        save("fourth")
        assertEquals(
            listOf("changed by another app"), deviceCopies().values.toList(),
            "another app's edit of the new document was replaced with no copy kept",
        )
        assertEquals("fourth", deviceText)
    }

    /**
     * The same create, arriving between the loop finding its queue empty and its try of the
     * hold. The try was queued behind the create with a later time, so the debounce dropped
     * the create, and the try failed against the document another app deleted: the file
     * reached no document, and every later save went to the deleted one.
     */
    @Test
    fun `a held-back save tried as a create of its file arrives does not drop the create`() {
        lateStamp = LateStamp.WHOLE_SECONDS
        open()
        save("first save")
        settle()
        deviceReadable = false
        save("second save")
        gone += uris.getValue(docId)
        deviceHasDocument = false
        deviceReadable = true
        val file = File(mirror, "notes.txt")
        File(mirror, "notes.txt.tmp").apply { writeText("third") }.renameTo(file)
        // The create's job waits in the queue of the watcher whose loop makes the try.
        val watching = WatchSession(mirror)
        engine.handleMirrorEvent(FileObserver.MOVED_TO, file, mirror, treeUri)
        watching.queue.addAll(engine.session.queue)
        engine.session.queue.clear()
        // The try comes a moment after the create was stamped, as it does in the loop.
        val createdAt = System.currentTimeMillis()
        while (System.currentTimeMillis() == createdAt) Thread.onSpinWait()
        clock += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
        val lost = failed.size

        assertTrue(engine.retryHeldBack(watching), "the held-back save was not tried")
        engine.runWriteBackLoop(watching) { false }

        assertEquals("third", deviceText, "the create of the file was dropped for the try")
        assertEquals(lost, failed.size, "the try was sent to the deleted document")
        editOnDevice("changed by another app")
        save("fourth")
        assertEquals(listOf("changed by another app"), deviceCopies().values.toList())
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
     * Past the copy limit, a length other than the one this app last wrote or fetched is not
     * read to find out what it holds: no set-aside could keep the document, so the save is
     * held back whatever its bytes hash to. A document grown that far is held back at every
     * save, and was read in full at each one.
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
     * A file deleted in the editor after another app changed its device document while the
     * folder was open. A save in its place keeps that edit; the delete asked nothing, so the
     * edit went with the file.
     */
    @Test
    fun `a delete of a document another app changed since it was read is declined`() {
        open()
        editOnDevice("changed by another app")
        val file = File(mirror, "notes.txt").apply { delete() }

        engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, treeUri)
        engine.runWriteBackLoop { false }

        assertTrue(deviceHasDocument, "the delete took another app's edit with the file")
        assertEquals("changed by another app", deviceText)
        assertEquals(listOf(file.absolutePath), kept.map { it.absolutePath }, "nothing said it was kept")
        assertEquals(
            listOf(true), keptAsChanged,
            "the notice told the user the editor never had the file they had just deleted in it",
        )
    }

    /**
     * The bytes decide a delete as they decide a save, so this app's own write settling after
     * its stream closed does not hold the delete of that file back.
     */
    @ParameterizedTest(name = "a delete after a settling write goes through: {0}")
    @EnumSource(LateStamp::class)
    fun `a delete after this app's own write settles goes through`(shape: LateStamp) {
        lateStamp = shape
        open()
        save("first save")
        settle()
        val file = File(mirror, "notes.txt").apply { delete() }

        engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, treeUri)
        engine.runWriteBackLoop { false }

        assertEquals(false, deviceHasDocument, "a delete of this app's own settled write was held back")
        assertEquals(emptyList<File>(), kept)
    }

    /**
     * A delete the closing folder's drain sends after a reopen, its queue having outlived the
     * stop behind a slow provider. The reopen could not read the document, which by then held
     * another app's edit, and listed its stamp as what it saw, so only the guard for what an
     * open could not read stands between the delete and that edit, and it was asked only when
     * the delete was queued.
     */
    @Test
    fun `a delete sent after a reopen that could not read the document is declined`() {
        open()
        val file = File(mirror, "notes.txt").apply { delete() }
        engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, treeUri)
        editOnDevice("changed by another app")
        deviceReadable = false
        open()

        engine.runWriteBackLoop { false }

        assertTrue(deviceHasDocument, "the delete removed a device document the reopen could not read")
        assertEquals("changed by another app", deviceText)
        assertEquals(listOf(file.absolutePath), kept.map { it.absolutePath }, "nothing said it was kept")
        assertEquals(
            listOf(true), keptAsChanged,
            "the notice told the user the editor never had the file they had just deleted in it",
        )
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
        failWriteStreams = 1
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
     * Opened again on a provider with no clock, a file whose device copy differs from the
     * mirror's is left as it is on both sides, with no time to say which is newer. Two
     * states look like that: another app changed a file this app saved in the session
     * before, or a save the watcher never delivered, the app killed before its drain ran,
     * sits over a device copy nobody touched. The open took the size it listed as what it
     * last saw, so the next save went over another app's edit with no copy kept. The device
     * copy is read at that save now: the copy the record vouched for, the second state, is
     * replaced as it stands, and anything else is kept.
     */
    @ParameterizedTest(name = "another app changed the file: {0}")
    @ValueSource(booleans = [true, false])
    fun `a save after a reopen with no clock keeps another app's edit and nothing else`(anotherApp: Boolean) {
        deviceHasClock = false
        open()
        if (anotherApp) {
            save("first save")
            editOnDevice("changed by another app")
        } else {
            File(mirror, "notes.txt").writeText("saved, never delivered")
        }
        open()

        save("typed after the reopen")

        assertEquals(
            if (anotherApp) listOf("changed by another app") else emptyList(),
            deviceCopies().values.toList(),
            if (anotherApp) "the save replaced another app's edit with no copy of it anywhere"
            else "the copy the record vouched for was kept as if another app had written it",
        )
        assertEquals("typed after the reopen", deviceText)
    }

    /** The same two states meeting a delete, which the bytes decide as they decide a save. */
    @ParameterizedTest(name = "another app changed the file: {0}")
    @ValueSource(booleans = [true, false])
    fun `a delete after a reopen with no clock is declined only over another app's edit`(anotherApp: Boolean) {
        deviceHasClock = false
        open()
        if (anotherApp) {
            save("first save")
            editOnDevice("changed by another app")
        } else {
            File(mirror, "notes.txt").writeText("saved, never delivered")
        }
        open()
        val file = File(mirror, "notes.txt").apply { delete() }

        engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, treeUri)
        engine.runWriteBackLoop { false }

        assertEquals(
            anotherApp, deviceHasDocument,
            if (anotherApp) "the delete took another app's edit with the file"
            else "the delete was declined over the copy the record vouched for",
        )
        assertEquals(if (anotherApp) listOf(file.absolutePath) else emptyList(), kept.map { it.absolutePath })
        assertEquals(if (anotherApp) listOf(true) else emptyList(), keptAsChanged)
    }

    /**
     * The reopen on a provider that reports no size either, every length 0. A save the
     * watcher delivered leaves the device holding the mirror's bytes, and no length can say
     * so, so the open reads them; taken as a difference, the next save kept a copy of this
     * app's own earlier save.
     */
    @Test
    fun `a save after a reopen with neither column keeps no copy of a delivered save`() {
        deviceHasClock = false
        deviceHasSize = false
        open()
        save("first save")
        open()

        save("typed after the reopen")

        assertEquals(
            emptyMap<String, String>(), deviceCopies(),
            "this app's own delivered save was kept as if another app had written it",
        )
        assertEquals("typed after the reopen", deviceText)
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
        const val OTHER_ID = "doc:other.txt"
    }
}
