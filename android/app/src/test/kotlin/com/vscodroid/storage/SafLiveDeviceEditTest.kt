package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver
import android.os.SystemClock
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
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
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

    /** False for a provider that will not open the document for reading, as offline. */
    private var deviceReadable = true

    /** What the engine's held-back saves are timed by, `SystemClock.elapsedRealtime()`. */
    private var clock = 0L
    private val failed = mutableListOf<File>()

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
            deviceCursor()
        }
        // A real stream: a relaxed one answers 0 from `read`, and `copyTo` spins on it.
        every { resolver.openInputStream(any()) } answers {
            reads++
            if (firstArg<Uri>() in gone) throw FileNotFoundException("deleted by another app")
            if (!deviceReadable) throw IOException("offline")
            object : ByteArrayInputStream(deviceText.toByteArray()) {
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
        every { cursor.getString(0) } answers { docId }
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
     * What bounds the reads an open spends on the copies it kept: one past the budget is
     * not read, so a stamp moved over it still keeps one spare copy of the unchanged
     * document. Without the bound every open read the whole folder.
     */
    @Test
    fun `a kept copy past the open's digest budget is not read`() {
        open()
        engine.keptCopyDigestBytes = 1
        open()
        deviceModified -= 337

        save("typed in the editor")

        assertEquals(listOf("v1"), deviceCopies().values.toList(), "a kept copy past the budget was read")
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
        val file = File(mirror, "notes.txt").apply { delete() }
        engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, treeUri)
        engine.runWriteBackLoop { false }
        file.writeText("brand new")
        val writesBefore = writes
        deviceReadable = true
        clock += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS

        retryWhileWatching()

        assertEquals(writesBefore, writes, "the deleted file's held-back save was tried")
        engine.handleMirrorEvent(FileObserver.CREATE, file, mirror, treeUri)
        engine.runWriteBackLoop { false }
        assertEquals("brand new", deviceText)
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

        assertEquals(false, engine.retryHeldBack(WatchSession(mirror)), "a save with no file was queued")
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
