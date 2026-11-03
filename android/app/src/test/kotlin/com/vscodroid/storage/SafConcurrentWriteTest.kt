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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * That two writers never hold one device document open at the same time.
 *
 * `openOutputStream(uri, "wt")` truncates at open. Two writers of one document
 * therefore produce a file that is neither of their inputs, and the user's copy on
 * the device is corrupt in a way nothing detects: it has a plausible size, a
 * plausible timestamp, and content spliced from two streams.
 *
 * Two writers are reachable rather than theoretical. The mirror is named by a hash
 * of the folder rather than by the session, so reopening a folder while the
 * previous session's drain is still streaming puts `initialSync` on the IO
 * dispatcher and that drain on the same document.
 *
 * The same fact holds in the other direction, and had nothing enforcing it: two
 * `initialSync` runs over one mirror wrote the same scratch path beside the same
 * destination, so one writer's rename into place carried the other's open stream
 * with it. Those cases are here too, driven through `initialSync` rather than
 * through the write-back.
 *
 * The exclusion never makes a reset wait, and makes a write wait for another write only
 * for a bounded time. A lock the loser waited on without limit would trade this
 * corruption for an unbounded stall: a `ContentResolver` stream to a network or MTP
 * provider has no timeout, and `initialSync` runs behind a dialog built with
 * `setCancelable(false)`. A sync therefore refuses at once, and a write takes its turn up
 * to a ceiling and is then announced as kept inside the app. What these pin is no
 * overlap, no waiting past the ceiling, and no save dropped for having had to wait.
 */
class SafConcurrentWriteTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var otherMirror: File

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri

    /** How many writers hold a stream open right now, and the worst seen. */
    private val open = AtomicInteger(0)
    private val peak = AtomicInteger(0)

    /**
     * How many streams were opened at all.
     *
     * Separate from [peak] because the two answer different questions, and a test that
     * only asks [peak] passes for an exclusion that claims a document and never lets
     * go: one write happens, so the peak is one and nothing overlaps. Measured, on this
     * very file, before this counter existed.
     */
    private val everOpened = AtomicInteger(0)

    /** Held open until released, so a second writer meets a first that has not finished. */
    private val firstWriterInside = CountDownLatch(1)
    private val releaseFirstWriter = CountDownLatch(1)
    private var blockFirstWriter = false

    /** The same three, for the inbound direction: how many device documents were read. */
    private val fetched = AtomicInteger(0)
    private val firstFetchInside = CountDownLatch(1)
    private val releaseFirstFetch = CountDownLatch(1)
    private var blockFirstFetch = false

    @BeforeEach
    fun setUp() {
        open.set(0)
        peak.set(0)
        everOpened.set(0)
        fetched.set(0)
        blockFirstWriter = false
        blockFirstFetch = false

        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)
        // One document, one identity. Two writers of the same path must resolve to the
        // same URI string or the exclusion would be measuring nothing.
        val doc = mockk<Uri>(relaxed = true)
        every { doc.toString() } returns "content://test/doc/notes.md"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns doc
        every { DocumentsContract.getDocumentId(any()) } returns "doc:notes.md"
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } returns doc

        resolver = mockk(relaxed = true)
        every { resolver.openOutputStream(any(), any()) } answers { countingStream() }

        context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir

        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        releaseFirstWriter.countDown()
        releaseFirstFetch.countDown()
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        File(otherMirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE).delete()
        unmockkAll()
    }

    /**
     * A stream that records how many of its kind are open at once, and optionally
     * holds the first one open until the test lets it go.
     */
    private fun countingStream(): OutputStream {
        val holdThisOne = blockFirstWriter && open.get() == 0
        val now = open.incrementAndGet()
        everOpened.incrementAndGet()
        peak.updateAndGet { maxOf(it, now) }
        if (holdThisOne) {
            firstWriterInside.countDown()
            releaseFirstWriter.await(5, TimeUnit.SECONDS)
        }
        return object : ByteArrayOutputStream() {
            override fun close() {
                open.decrementAndGet()
                super.close()
            }
        }
    }

    /** One device document, already present, so a local edit is a write and not a create. */
    private fun deviceHolding(name: String, reportedSize: Long = 4L, modified: Long = 1_700_000_000_000L) {
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
        every { cursor.getLong(3) } returns reportedSize
        every { cursor.getLong(4) } returns modified
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream("previous provider bytes".toByteArray())
        }
    }

    /**
     * The device document answers with [contents], and the first read of it is held open
     * until the test lets it go, so a second sync of the same folder meets a first that
     * has not finished.
     */
    private fun deviceDocumentReadingAs(contents: String) {
        every { resolver.openInputStream(any()) } answers {
            val holdThisOne = blockFirstFetch && fetched.get() == 0
            fetched.incrementAndGet()
            if (holdThisOne) {
                firstFetchInside.countDown()
                releaseFirstFetch.await(5, TimeUnit.SECONDS)
            }
            ByteArrayInputStream(contents.toByteArray())
        }
    }

    private fun syncNow() = runBlocking {
        SafSyncEngine(context).initialSync(treeUri, mirror) { _, _ -> }
    }

    private fun deliver(entry: String) {
        engine.handleMirrorEvent(FileObserver.MODIFY, File(mirror, entry), mirror, treeUri)
        engine.runWriteBackLoop { false }
    }

    /**
     * The case the exclusion exists for. One writer is held inside its stream while a
     * second is driven at the same document.
     */
    @Test
    fun `two writers never hold one document open at once`() {
        var nowMs = 1_000_000L
        every { SystemClock.elapsedRealtime() } answers { nowMs }
        deviceHolding("notes.md")
        File(mirror, "notes.md").writeText("edited in the editor")
        blockFirstWriter = true

        val secondQueued = CountDownLatch(1)
        val firstWrite = FutureTask { deliver("notes.md") }
        val secondWrite = FutureTask {
            engine.handleMirrorEvent(FileObserver.MODIFY, File(mirror, "notes.md"), mirror, treeUri)
            secondQueued.countDown()
            engine.runWriteBackLoop { false }
        }
        val first = Thread(firstWrite, "saf-first-writer")
        val second = Thread(secondWrite, "saf-second-writer")
        try {
            first.start()
            assertTrue(
                firstWriterInside.await(5, TimeUnit.SECONDS),
                "the first writer never reached its stream, so nothing was contended",
            )
            second.start()
            assertTrue(secondQueued.await(5, TimeUnit.SECONDS), "the second save was never queued")
            second.join(300)
            assertTrue(second.isAlive, "the second writer did not wait for the first")
        } finally {
            releaseFirstWriter.countDown()
            first.join(5_000)
            second.join(5_000)
        }
        assertFalse(first.isAlive, "the first writer did not finish")
        assertFalse(second.isAlive, "the second writer did not finish")
        firstWrite.get(1, TimeUnit.SECONDS)
        secondWrite.get(1, TimeUnit.SECONDS)

        // The mirror lock may release before the provider claim; that save must retry.
        if (everOpened.get() == 1) {
            nowMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
            assertTrue(engine.retryHeldBack(WatchSession(mirror)), "the contending save was not retained")
        }

        assertEquals(1, peak.get(), "two writers held the same document open at once")
        assertEquals(
            2, everOpened.get(),
            "the second save was dropped for meeting the first instead of waiting its turn",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["overlap", "authority", "document"])
    fun `provider mutation admission serializes grants while separate authorities remain independent`(identity: String) {
        deviceHolding("notes.md")
        fun document(tree: String, authority: String = "test", id: String = "shared") = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "content://$authority/tree/$tree/document/$id"
            every { it.authority } returns authority
            every { DocumentsContract.getDocumentId(it) } returns id
        }
        val parentDocument = document("parent")
        val childDocument = document("child", if (identity == "authority") "other" else "test",
            if (identity == "document") "distinct" else "shared")
        val otherTree = mockk<Uri>(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            if (firstArg<Uri>() === treeUri) parentDocument else childDocument
        }
        val secondEngine = SafSyncEngine(context)
        val refused = AtomicInteger()
        secondEngine.onWriteBackFailed = { refused.incrementAndGet() }
        val firstFile = File(mirror, "notes.md").apply { writeText("parent mirror") }
        val secondFile = File(otherMirror, "notes.md").apply { writeText("child mirror") }
        blockFirstWriter = true
        val first = Thread {
            engine.handleMirrorEvent(FileObserver.MODIFY, firstFile, mirror, treeUri)
            engine.runWriteBackLoop { false }
        }.apply { start() }
        try {
            assertTrue(firstWriterInside.await(5, TimeUnit.SECONDS), "the first writer never opened the document")
            secondEngine.handleMirrorEvent(FileObserver.MODIFY, secondFile, otherMirror, otherTree)
            secondEngine.runWriteBackLoop { false }
            assertEquals(if (identity == "authority") 2 else 1, peak.get(), "provider mutation admission used the wrong authority")
            assertEquals(if (identity == "authority") 0 else 1, refused.get(), "the undelivered child mirror save was not reported")
        } finally {
            releaseFirstWriter.countDown()
            first.join(5_000)
        }
        secondEngine.handleMirrorEvent(FileObserver.MODIFY, secondFile, otherMirror, otherTree)
        secondEngine.runWriteBackLoop { false }
        assertEquals(if (identity == "authority") 3 else 2, everOpened.get(), "the provider remained claimed after the first writer finished")
    }

    /**
     * And the second writer does not wait for ever. This is the half that keeps the fix
     * from being a trade: a writer that blocked without limit would stall behind a
     * provider that never answers, and a ceiling is what turns that into a notice.
     */
    @Test
    fun `the second writer waits no longer than its ceiling`() {
        engine.outboundTurnWaitMs = 300
        deviceHolding("notes.md")
        File(mirror, "notes.md").writeText("edited in the editor")
        blockFirstWriter = true

        val first = Thread { deliver("notes.md") }.apply { start() }
        assertTrue(firstWriterInside.await(5, TimeUnit.SECONDS), "nothing was contended")

        val started = System.nanoTime()
        deliver("notes.md")
        val waitedMs = (System.nanoTime() - started) / 1_000_000

        releaseFirstWriter.countDown()
        first.join(5_000)

        assertTrue(
            waitedMs in 250..3_000,
            "the second writer waited ${waitedMs}ms against a ceiling of 300ms",
        )
        assertEquals(1, everOpened.get(), "a writer past its ceiling still reached the document")
    }

    /**
     * The control, and it is what stops the two above from passing because writes stopped
     * happening. An uncontended write still writes.
     */
    @Test
    fun `an uncontended write still reaches the document`() {
        deviceHolding("notes.md")
        File(mirror, "notes.md").writeText("edited in the editor")

        deliver("notes.md")

        assertEquals(1, everOpened.get(), "the write never opened its document")
        assertEquals(0, open.get(), "the stream was left open")
    }

    /**
     * The same rule one direction over, which had nothing enforcing it at all.
     *
     * A mirror is named by a hash of the tree URI rather than by the session, and
     * `initialSync`'s phase-2 loop contains no suspension point, so cancelling the
     * coroutine an activity started does not stop it: an activity destroyed mid-open
     * leaves one sync copying while its replacement opens the same folder and starts
     * another, in a second engine over the same directory. Both wrote the same scratch
     * path beside the destination, so one writer's rename into place carried the other's
     * open stream with it and the rest of that copy landed inside the finished mirror
     * file, which `recordIdentity` had already vouched for. A corrupted file the record
     * calls disposable is what `reconcileDeletions` and `holdsOnlyVouchedCopies` then act
     * on.
     */
    @Test
    fun `two syncs never stream into one mirror file at once`() {
        deviceHolding("notes.md")
        deviceDocumentReadingAs("what the device holds")
        blockFirstFetch = true

        val first = Thread { syncNow() }.apply { start() }
        try {
            assertTrue(
                firstFetchInside.await(5, TimeUnit.SECONDS),
                "the first sync never reached its stream, so nothing was contended",
            )

            // The replacement Activity must retry once the admitted sync finishes.
            assertThrows(FolderBusyException::class.java) { syncNow() }
        } finally {
            releaseFirstFetch.countDown()
            first.join(5_000)
        }

        assertFalse(first.isAlive, "the first sync did not finish")
        assertEquals(
            1, fetched.get(),
            "two syncs read the same document into one mirror file at once",
        )
        assertEquals(
            "what the device holds", File(mirror, "notes.md").readText(),
            "the mirror file is not what either sync fetched",
        )

        assertTrue(File(mirror, "notes.md").delete())
        syncNow()
        assertEquals(2, fetched.get(), "the finished sync did not release the mirror")
        assertEquals("what the device holds", File(mirror, "notes.md").readText())
    }

    /**
     * And the second sync does not wait, for the reason the write-back's loser does not:
     * a `ContentResolver` stream has no timeout, and a folder open sits behind a dialog
     * built with `setCancelable(false)`.
     */
    @Test
    fun `the second sync reports busy rather than waiting`() {
        deviceHolding("notes.md")
        deviceDocumentReadingAs("what the device holds")
        blockFirstFetch = true

        val first = Thread { syncNow() }.apply { start() }
        try {
            assertTrue(firstFetchInside.await(5, TimeUnit.SECONDS), "nothing was contended")

            val started = System.nanoTime()
            assertThrows(FolderBusyException::class.java) { syncNow() }
            val waitedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(
                waitedMs < 1_000,
                "the second sync waited ${waitedMs}ms for the first, which is the stall this " +
                    "design exists to avoid",
            )
        } finally {
            releaseFirstFetch.countDown()
            first.join(5_000)
        }

        assertFalse(first.isAlive, "the first sync did not finish")
        assertEquals("what the device holds", File(mirror, "notes.md").readText())
        // And the mirror is free again: a sync that has nothing left to copy would pass
        // with the claim stuck, so the file is taken away and has to come back.
        assertTrue(File(mirror, "notes.md").delete())
        syncNow()
        assertEquals("what the device holds", File(mirror, "notes.md").readText())
        assertEquals(2, fetched.get(), "the busy sync left the mirror claimed")
    }

    /**
     * The control for the pair above: a sync that finished lets go of the mirror file, so
     * the next one is not refused for ever.
     */
    @Test
    fun `a mirror file is fetchable again once its sync is done`() {
        deviceHolding("notes.md")
        deviceDocumentReadingAs("what the device holds")

        syncNow()
        assertEquals("what the device holds", File(mirror, "notes.md").readText())

        // Gone from the mirror again, so the next sync has to fetch it a second time.
        File(mirror, "notes.md").delete()
        syncNow()

        assertEquals(
            2, fetched.get(),
            "the claim was taken and never released, so the folder stopped syncing",
        )
    }

    /**
     * The second control: a writer that finished releases the document, so the next
     * write is not refused for ever. Without this the exclusion could pass every case
     * above by claiming a document once and never letting go.
     */
    @Test
    fun `a document is writable again once its writer is done`() {
        deviceHolding("notes.md")
        File(mirror, "notes.md").writeText("first edit")
        deliver("notes.md")

        File(mirror, "notes.md").writeText("second edit")
        deliver("notes.md")

        assertEquals(1, peak.get(), "two writers overlapped across separate saves")
        assertEquals(0, open.get(), "a stream was left open")
        assertEquals(
            2, everOpened.get(),
            "the second save never reached the document, so the exclusion is claiming " +
                "it and never letting go rather than serialising writers",
        )
    }

    @Test
    fun `an overlapping tree save sets nothing aside while the first grant streams and retries once it finishes`() {
        var nowMs = 1_000_000L
        every { SystemClock.elapsedRealtime() } answers { nowMs }
        deviceHolding("notes.md")
        fun document(tree: String) = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "content://test/tree/$tree/document/shared"
            every { it.authority } returns "test"
            every { DocumentsContract.getDocumentId(it) } returns "shared"
        }
        val parentDocument = document("parent")
        val childDocument = document("child")
        val otherTree = mockk<Uri>(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            if (firstArg<Uri>() === treeUri) parentDocument else childDocument
        }
        var providerBytes = "winner half-written"
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream(providerBytes.toByteArray())
        }

        val secondEngine = SafSyncEngine(context)
        val refused = AtomicInteger()
        secondEngine.onWriteBackFailed = { refused.incrementAndGet() }
        val firstFile = File(mirror, "notes.md").apply { writeText("winner full text") }
        val secondFile = File(otherMirror, "notes.md").apply { writeText("loser full text") }
        blockFirstWriter = true
        val first = Thread {
            engine.handleMirrorEvent(FileObserver.MODIFY, firstFile, mirror, treeUri)
            engine.runWriteBackLoop { false }
        }.apply { start() }
        val secondSession = WatchSession(otherMirror)
        try {
            assertTrue(firstWriterInside.await(5, TimeUnit.SECONDS), "the first writer never opened the document")
            secondEngine.handleMirrorEvent(FileObserver.MODIFY, secondFile, otherMirror, otherTree)
            secondEngine.runWriteBackLoop { false }

            assertEquals(
                listOf("notes.md"),
                otherMirror.listFiles()?.map { it.name }?.sorted(),
                "a contending save set aside the first grant's half-written bytes",
            )
            assertEquals(1, everOpened.get(), "the contending save opened a second output stream")
            assertEquals(1, refused.get(), "the held-back save was not announced once")

            // A retry while the first grant is still streaming re-holds with a doubled wait,
            // creates no preserved copy, and raises no second notice.
            nowMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
            assertTrue(secondEngine.retryHeldBack(secondSession), "the first retry was not due")
            assertFalse(
                secondEngine.retryHeldBack(secondSession),
                "a retry that re-held the save stayed due with no time passing",
            )
            assertEquals(
                listOf("notes.md"),
                otherMirror.listFiles()?.map { it.name }?.sorted(),
                "a retry while the first grant was still streaming set aside half-written bytes",
            )
            assertEquals(1, refused.get(), "re-holding the same path announced a second notice")
        } finally {
            providerBytes = "winner full text"
            releaseFirstWriter.countDown()
            first.join(5_000)
        }

        // Before the doubled wait elapses, nothing retries.
        nowMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
        assertFalse(secondEngine.retryHeldBack(secondSession), "the retry did not double its wait")

        // Once the doubled wait elapses, the retry sets the finished winner copy aside whole
        // and delivers the loser's save.
        nowMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
        assertTrue(secondEngine.retryHeldBack(secondSession), "the deferred save was not retried")
        assertEquals(2, everOpened.get(), "the retry did not write the deferred save to the document")
        val copies = otherMirror.listFiles()?.filter {
            it.name.startsWith("notes.md${SafSyncEngine.DEVICE_COPY_SUFFIX}")
        }.orEmpty()
        assertEquals(1, copies.size, "expected one preserved copy of the finished first write")
        assertEquals("winner full text", copies.single().readText())
        assertEquals(1, refused.get(), "delivering the deferred save announced another failure")
    }

    @ParameterizedTest
    @ValueSource(strings = ["newer-mirror", "repair"])
    fun `an open arm meeting another grant's in-flight write defers without marking the document unread`(arm: String) {
        deviceHolding("notes.md")
        fun document(tree: String) = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "content://test/tree/$tree/document/shared"
            every { it.authority } returns "test"
            every { DocumentsContract.getDocumentId(it) } returns "shared"
        }
        val parentDocument = document("parent")
        val childDocument = document("child")
        val otherTree = mockk<Uri>(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            if (firstArg<Uri>() === treeUri) parentDocument else childDocument
        }

        val firstFile = File(mirror, "notes.md").apply { writeText("winner full text") }
        val secondFile = File(otherMirror, "notes.md").apply {
            writeText("loser edit kept in mirror")
            setLastModified(1_800_000_000_000L)
        }
        if (arm == "repair") {
            File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE).writeText(secondFile.absolutePath)
        }

        val secondEngine = SafSyncEngine(context)
        blockFirstWriter = true
        val first = Thread {
            engine.handleMirrorEvent(FileObserver.MODIFY, firstFile, mirror, treeUri)
            engine.runWriteBackLoop { false }
        }.apply { start() }
        try {
            assertTrue(firstWriterInside.await(5, TimeUnit.SECONDS), "the first writer never opened the document")
            runBlocking {
                secondEngine.initialSync(otherTree, otherMirror) { _, _ -> }
            }
            assertEquals(
                listOf("notes.md"),
                otherMirror.listFiles()?.map { it.name }?.sorted(),
                "the $arm open arm set aside another grant's half-written bytes",
            )
            if (arm == "repair") {
                assertTrue(
                    secondFile.absolutePath in secondEngine.uploadsInFlight(),
                    "the repair arm consumed the upload journal line when its write was deferred",
                )
            }
        } finally {
            releaseFirstWriter.countDown()
            first.join(5_000)
        }

        secondFile.writeText("saved again after the open")
        secondEngine.handleMirrorEvent(FileObserver.MODIFY, secondFile, otherMirror, otherTree)
        secondEngine.runWriteBackLoop { false }
        assertEquals(
            2, everOpened.get(),
            "the $arm open arm marked the in-flight document unread and refused a later save",
        )
    }

    @Test
    fun `an open during another grant's stream does not copy or vouch for a half-written prefix`() {
        deviceHolding("notes.md")
        fun document(tree: String) = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "content://test/tree/$tree/document/shared"
            every { it.authority } returns "test"
            every { DocumentsContract.getDocumentId(it) } returns "shared"
        }
        val parentDocument = document("parent")
        val childDocument = document("child")
        val otherTree = mockk<Uri>(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            if (firstArg<Uri>() === treeUri) parentDocument else childDocument
        }
        var providerBytes = "half-written prefix"
        val inputStreamsOpened = AtomicInteger(0)
        every { resolver.openInputStream(any()) } answers {
            if (firstArg<Uri>() === childDocument) {
                inputStreamsOpened.incrementAndGet()
            }
            ByteArrayInputStream(providerBytes.toByteArray())
        }

        val firstFile = File(mirror, "notes.md").apply { writeText("winner full text") }
        val secondEngine = SafSyncEngine(context)
        blockFirstWriter = true
        val first = Thread {
            engine.handleMirrorEvent(FileObserver.MODIFY, firstFile, mirror, treeUri)
            engine.runWriteBackLoop { false }
        }.apply { start() }
        try {
            assertTrue(firstWriterInside.await(5, TimeUnit.SECONDS), "the first writer never opened the document")
            runBlocking {
                secondEngine.initialSync(otherTree, otherMirror) { _, _ -> }
            }
            assertFalse(
                File(otherMirror, "notes.md").exists(),
                "an open during another grant's stream copied the half-written prefix into the mirror",
            )
            assertEquals(
                0,
                inputStreamsOpened.get(),
                "an open during another grant's stream opened an input stream on the in-flight document",
            )
            val syncedRecord = File(otherMirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
            assertFalse(
                syncedRecord.isFile && syncedRecord.readText().contains("notes.md"),
                "an open during another grant's stream vouched for the half-written prefix in .synced",
            )
        } finally {
            providerBytes = "winner full text"
            releaseFirstWriter.countDown()
            first.join(5_000)
        }

        runBlocking {
            secondEngine.initialSync(otherTree, otherMirror) { _, _ -> }
        }
        assertEquals("winner full text", File(otherMirror, "notes.md").readText())
        val syncedAfter = File(otherMirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        assertTrue(syncedAfter.isFile && syncedAfter.readText().contains("notes.md"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["save", "failed-save", "prefix", "preserve"])
    fun `a writer arriving during a protected proof waits and preserves the completed version`(read: String) {
        var nowMs = 1_000_000L
        every { SystemClock.elapsedRealtime() } answers { nowMs }
        deviceHolding("notes.md")
        fun document(tree: String) = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "content://test/tree/$tree/document/shared"
            every { it.authority } returns "test"
            every { DocumentsContract.getDocumentId(it) } returns "shared"
        }
        val parentDocument = document("parent")
        val childDocument = document("child")
        val otherTree = mockk<Uri>(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            if (firstArg<Uri>() === treeUri) parentDocument else childDocument
        }
        var providerBytes = "device snapshot".toByteArray()
        every { resolver.openOutputStream(any(), any()) } answers {
            val counted = countingStream()
            providerBytes = byteArrayOf()
            object : ByteArrayOutputStream() {
                override fun close() {
                    providerBytes = toByteArray()
                    counted.close()
                    super.close()
                }
            }
        }
        val firstFile = File(mirror, "notes.md").apply { writeText("deferred writer content") }
        val secondContents = if (read == "prefix") "device snapshot and a local tail" else "proof owner content"
        val secondFile = File(otherMirror, "notes.md").apply {
            writeText(secondContents)
            setLastModified(1_800_000_000_000L)
        }
        if (read == "prefix") File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE).writeText(secondFile.absolutePath)
        val proofOwner = SafSyncEngine(context)
        var attempted = false
        var failRead = read == "failed-save"
        every { resolver.openInputStream(any()) } answers {
            val snapshot = providerBytes
            if (firstArg<Uri>() === childDocument) {
                object : java.io.InputStream() {
                    private var pos = 0
                    override fun read(): Int {
                        if (!attempted) {
                            attempted = true
                            engine.handleMirrorEvent(FileObserver.MODIFY, firstFile, mirror, treeUri)
                            engine.runWriteBackLoop { false }
                            assertEquals(0, everOpened.get(), "a writer entered during another operation's proof")
                        }
                        if (failRead) throw IOException("The device proof is unavailable")
                        return if (pos < snapshot.size) snapshot[pos++].toInt() and 0xff else -1
                    }
                }
            } else ByteArrayInputStream(snapshot)
        }
        if (read == "prefix" || read == "preserve") {
            runBlocking { proofOwner.initialSync(otherTree, otherMirror) { _, _ -> } }
        } else {
            proofOwner.handleMirrorEvent(FileObserver.MODIFY, secondFile, otherMirror, otherTree)
            proofOwner.runWriteBackLoop { false }
        }
        assertTrue(attempted, "the competing writer never arrived during the proof")
        if (failRead) {
            assertEquals("device snapshot", String(providerBytes))
            assertEquals(0, everOpened.get())
            failRead = false
            nowMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
            assertTrue(proofOwner.retryHeldBack(WatchSession(otherMirror)), "the failed proof lost its save")
        }
        assertEquals(secondContents, String(providerBytes))
        assertEquals(secondContents, secondFile.readText())
        nowMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
        assertTrue(engine.retryHeldBack(WatchSession(mirror)), "the competing save was not retained for retry")
        assertEquals("deferred writer content", String(providerBytes))
        assertEquals(2, everOpened.get())
        assertTrue(mirror.listFiles().orEmpty().any {
            it.name.startsWith("notes.md${SafSyncEngine.DEVICE_COPY_SUFFIX}") && it.readText() == secondContents
        }, "the retry discarded the completed proof owner's bytes")
    }

    @Test
    fun `a no-clock open during another grant's stream does not vouch for a matching prefix`() {
        deviceHolding("notes.md")
        fun document(tree: String) = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "content://test/tree/$tree/document/shared"
            every { it.authority } returns "test"
            every { DocumentsContract.getDocumentId(it) } returns "shared"
        }
        val parentDocument = document("parent")
        val childDocument = document("child")
        val otherTree = mockk<Uri>(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            if (firstArg<Uri>() === treeUri) parentDocument else childDocument
        }
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        val prefix = "half-written"
        every { cursor.moveToNext() } answers { ++row == 0 }
        every { cursor.getColumnIndexOrThrow(any()) } answers {
            when (firstArg<String>()) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                else -> 3
            }
        }
        every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns -1
        every { cursor.isNull(any()) } returns false
        every { cursor.getString(0) } returns "shared"
        every { cursor.getString(1) } returns "notes.md"
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } returns prefix.length.toLong()
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream(prefix.toByteArray())
        }

        val firstFile = File(mirror, "notes.md").apply { writeText("winner full text") }
        File(otherMirror, "notes.md").writeText(prefix)
        val secondEngine = SafSyncEngine(context)
        blockFirstWriter = true
        val first = Thread {
            engine.handleMirrorEvent(FileObserver.MODIFY, firstFile, mirror, treeUri)
            engine.runWriteBackLoop { false }
        }.apply { start() }
        try {
            assertTrue(firstWriterInside.await(5, TimeUnit.SECONDS), "the first writer never opened the document")
            runBlocking {
                secondEngine.initialSync(otherTree, otherMirror) { _, _ -> }
            }
            val syncedRecord = File(otherMirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
            assertFalse(
                syncedRecord.isFile && syncedRecord.readText().contains("notes.md"),
                "deviceMatchesUnchangedMirror vouched for a document another grant was still writing",
            )
        } finally {
            releaseFirstWriter.countDown()
            first.join(5_000)
        }
    }

    @Test
    fun `writeLocalToSaf declining for an in-flight write after a prior hold doubles the wait`() {
        var nowMs = 1_000_000L
        every { SystemClock.elapsedRealtime() } answers { nowMs }
        deviceHolding("notes.md")
        fun document(tree: String) = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "content://test/tree/$tree/document/shared"
            every { it.authority } returns "test"
            every { DocumentsContract.getDocumentId(it) } returns "shared"
        }
        val parentDocument = document("parent")
        val childDocument = document("child")
        val otherTree = mockk<Uri>(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            if (firstArg<Uri>() === treeUri) parentDocument else childDocument
        }

        val firstFile = File(mirror, "notes.md").apply { writeText("winner full text") }
        val secondFile = File(otherMirror, "notes.md").apply { writeText("loser full text") }
        val secondEngine = SafSyncEngine(context)
        val secondSession = WatchSession(otherMirror)
        blockFirstWriter = true
        val first = Thread {
            engine.handleMirrorEvent(FileObserver.MODIFY, firstFile, mirror, treeUri)
            engine.runWriteBackLoop { false }
        }.apply { start() }
        try {
            assertTrue(firstWriterInside.await(5, TimeUnit.SECONDS), "the first writer never opened the document")
            // First hold (30s wait) via writeLocalToSaf directly, where keepsDeviceEdit has no
            // deviceSeen entry and returns false before writeLocalToSaf declines on the in-flight write.
            secondEngine.handleMirrorEvent(FileObserver.MODIFY, secondFile, otherMirror, otherTree)
            secondEngine.runWriteBackLoop { false }

            nowMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
            // First retry at +30s while the first writer is still streaming:
            // retryHeldBack -> flushDirtyPaths -> writeLocalToSaf must double the
            // wait to 60s rather than resetting it to 30s.
            assertTrue(secondEngine.retryHeldBack(secondSession))

            nowMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
            assertFalse(
                secondEngine.retryHeldBack(secondSession),
                "writeLocalToSaf reset a prior held-back wait to HELD_BACK_RETRY_FIRST_MS instead of doubling it",
            )
            nowMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
            assertTrue(secondEngine.retryHeldBack(secondSession), "the doubled wait did not become due at 60s")
        } finally {
            releaseFirstWriter.countDown()
            first.join(5_000)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["copy", "match"])
    fun `a write completed inside a read must not accept mixed bytes`(read: String) {
        var nowMs = 1_000_000L
        every { SystemClock.elapsedRealtime() } answers { nowMs }
        deviceHolding("notes.md", reportedSize = 8L, modified = if (read == "match") 0L else 1_700_000_000_000L)
        fun document(tree: String) = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "content://test/tree/$tree/document/shared"
            every { it.authority } returns "test"
            every { DocumentsContract.getDocumentId(it) } returns "shared"
        }
        val parentDocument = document("parent")
        val childDocument = document("child")
        val otherTree = mockk<Uri>(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            if (firstArg<Uri>() === treeUri) parentDocument else childDocument
        }
        var providerBytes = "ABCDEFGH".toByteArray()
        every { resolver.openOutputStream(any(), any()) } answers {
            providerBytes = byteArrayOf()
            object : ByteArrayOutputStream() {
                override fun close() { providerBytes = toByteArray(); super.close() }
            }
        }
        val firstFile = File(mirror, "notes.md").apply { writeText("12345678") }
        val imported = File(otherMirror, "notes.md")
        if (read == "match") imported.writeText("ABCD5678")
        var writerCompleted = false
        every { resolver.openInputStream(any()) } answers {
            if (firstArg<Uri>() === childDocument && !writerCompleted) {
                object : java.io.InputStream() {
                    private var pos = 0
                    override fun read(): Int {
                        if (pos == 4 && !writerCompleted) {
                            writerCompleted = true
                            engine.handleMirrorEvent(FileObserver.MODIFY, firstFile, mirror, treeUri)
                            engine.runWriteBackLoop { false }
                            assertEquals("12345678", String(providerBytes), "contending write did not finish inside the read")
                        }
                        return if (pos < providerBytes.size) providerBytes[pos++].toInt() and 0xff else -1
                    }
                }
            } else ByteArrayInputStream(providerBytes)
        }
        val reader = SafSyncEngine(context)
        runBlocking { reader.initialSync(otherTree, otherMirror) { _, _ -> } }
        assertTrue(writerCompleted)
        when (read) {
            "copy" -> {
                if (imported.exists()) {
                    val actual = imported.readText()
                    assertTrue(actual == "ABCDEFGH" || actual == "12345678", "reader published hybrid bytes: $actual")
                }
                runBlocking { reader.initialSync(otherTree, otherMirror) { _, _ -> } }
                assertEquals("12345678", imported.readText(), "the rejected read prevented a later import")
                assertTrue(reader.holdsOnlyVouchedCopies(otherMirror))
            }
            "match" -> {
                assertEquals("ABCD5678", imported.readText())
                assertFalse(reader.holdsOnlyVouchedCopies(otherMirror), "a matching hybrid was recorded as device bytes")
            }
        }
    }

    @Test
    fun `a pending delete keeps changed bytes and defers a contending save`() {
        var nowMs = 1_000_000L
        every { SystemClock.elapsedRealtime() } answers { nowMs }
        deviceHolding("notes.md", reportedSize = 8L)
        fun document(tree: String) = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "content://test/tree/$tree/document/shared"
            every { it.authority } returns "test"
            every { DocumentsContract.getDocumentId(it) } returns "shared"
        }
        val parentDocument = document("parent")
        val childDocument = document("child")
        val otherTree = mockk<Uri>(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            if (firstArg<Uri>() === treeUri) parentDocument else childDocument
        }
        var providerBytes = "ABCD5678".toByteArray()
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream(providerBytes) }
        val reader = SafSyncEngine(context)
        runBlocking { reader.initialSync(otherTree, otherMirror) { _, _ -> } }
        val imported = File(otherMirror, "notes.md")
        assertEquals("ABCD5678", imported.readText())
        val firstFile = File(mirror, "notes.md").apply { writeText("12345678") }
        every { resolver.openOutputStream(any(), any()) } answers {
            providerBytes = byteArrayOf()
            object : ByteArrayOutputStream() {
                override fun close() {
                    providerBytes = toByteArray()
                    super.close()
                }
            }
        }
        providerBytes = "ABCDEFGH".toByteArray()
        var writerAttempted = false
        every { resolver.openInputStream(any()) } answers {
            if (firstArg<Uri>() === childDocument && !writerAttempted) {
                object : java.io.InputStream() {
                    private var pos = 0
                    override fun read(): Int {
                        if (pos == 4 && !writerAttempted) {
                            writerAttempted = true
                            engine.handleMirrorEvent(FileObserver.MODIFY, firstFile, mirror, treeUri)
                            engine.runWriteBackLoop { false }
                        }
                        return if (pos < providerBytes.size) providerBytes[pos++].toInt() and 0xff else -1
                    }
                }
            } else ByteArrayInputStream(providerBytes)
        }
        var providerDeleted = false
        every { DocumentsContract.deleteDocument(resolver, childDocument) } answers {
            providerDeleted = true
            true
        }
        assertTrue(imported.delete())
        reader.handleMirrorEvent(FileObserver.DELETE, imported, otherMirror, otherTree)
        reader.runWriteBackLoop { false }
        assertTrue(writerAttempted, "the digest read never met the contending save")
        assertFalse(providerDeleted, "a changed device document was deleted")
        assertEquals("ABCDEFGH", String(providerBytes))
        nowMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
        assertTrue(engine.retryHeldBack(WatchSession(mirror)), "the pending delete discarded the contending save")
        assertEquals("12345678", String(providerBytes))
        assertTrue(mirror.listFiles().orEmpty().any {
            it.name.startsWith("notes.md${SafSyncEngine.DEVICE_COPY_SUFFIX}") && it.readText() == "ABCDEFGH"
        }, "the deferred save did not preserve the changed device document")
    }

    @Test
    fun `a restored watcher does not preserve another drain's unfinished device prefix`() {
        var nowMs = 1_000_000L
        every { SystemClock.elapsedRealtime() } answers { nowMs }
        deviceHolding("notes.md")
        val local = File(mirror, "notes.md").apply { writeText("complete mirror edit") }
        every { resolver.openInputStream(any()) } answers {
            if (open.get() > 0) {
                object : ByteArrayInputStream("unfinished prefix".toByteArray()) {
                    override fun close() {
                        releaseFirstWriter.countDown()
                        super.close()
                    }
                }
            } else ByteArrayInputStream("complete mirror edit".toByteArray())
        }
        val restored = SafSyncEngine(context)
        blockFirstWriter = true
        val secondQueued = CountDownLatch(1)
        val firstWrite = FutureTask { deliver("notes.md") }
        val secondWrite = FutureTask {
            restored.handleMirrorEvent(FileObserver.MODIFY, local, mirror, treeUri)
            secondQueued.countDown()
            restored.runWriteBackLoop { false }
        }
        val first = Thread(firstWrite, "saf-closing-drain")
        val second = Thread(secondWrite, "saf-restored-writer")
        try {
            first.start()
            assertTrue(firstWriterInside.await(5, TimeUnit.SECONDS), "the closing drain never opened its output")
            second.start()
            assertTrue(secondQueued.await(5, TimeUnit.SECONDS), "the restored save was never queued")
            second.join(300)
            assertTrue(second.isAlive, "the restored watcher did not wait for its writer's turn")
            assertEquals(
                listOf("notes.md"), mirror.listFiles().orEmpty().map { it.name },
                "the restored watcher preserved a prefix of its own mirror's active writer",
            )
            assertEquals(1, everOpened.get(), "the restored watcher did not defer behind the active writer")
        } finally {
            releaseFirstWriter.countDown()
            first.join(5_000)
            second.join(5_000)
        }
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        firstWrite.get(1, TimeUnit.SECONDS)
        secondWrite.get(1, TimeUnit.SECONDS)
        if (everOpened.get() == 1) {
            nowMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
            assertTrue(restored.retryHeldBack(WatchSession(mirror)), "the restored save was not retained")
        }
        assertEquals(2, everOpened.get(), "the restored save never reached the device")
        assertEquals(listOf("notes.md"), mirror.listFiles().orEmpty().map { it.name })
    }

}
