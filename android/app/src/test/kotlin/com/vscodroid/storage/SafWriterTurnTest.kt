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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * What a write-back does when another write to the same mirror is still in flight.
 *
 * Two drains can be current on one mirror at once: a folder switch leaves the closing
 * drain finishing a long write, and a switch that fails puts the same folder's watcher
 * back on the same generation, so the restored session's drain meets the old one's call.
 * Both are entitled to write, one after the other. A writer that finds the mirror taken
 * by a reset, or that was admitted under an older generation, is another matter: that
 * one has nothing left to say to the device and answers at once.
 *
 * Driven through two [WatchSession]s over one mirror, each drained on its own thread
 * against a device folder whose first write is held open until the test lets it go.
 */
class SafWriterTurnTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri

    /** The device folder: file name to bytes. Flat, which is all these cases need. */
    private val device = ConcurrentHashMap<String, ByteArray>()

    /** Files announced as existing only inside the app. */
    private val lost = CopyOnWriteArrayList<String>()

    /** Names in the order their documents were opened for writing. */
    private val opened = CopyOnWriteArrayList<String>()

    /** Whether the thread that opened each document was carrying an interrupt. */
    private val interruptedAtOpen = ConcurrentHashMap<String, Boolean>()

    private val open = AtomicInteger(0)
    private val peak = AtomicInteger(0)

    /** The document whose write is held open, and the latches that hold and free it. */
    private val holding = AtomicReference<String?>(null)
    private val holdingInside = CountDownLatch(1)
    private val release = CountDownLatch(1)

    private fun docId(name: String) = if (name.isEmpty()) "root" else "doc:$name"

    private fun nameOf(uri: Uri) = uri.toString().removePrefix("doc-uri:").removePrefix("doc:")

    private fun docUri(docId: String): Uri =
        mockk<Uri>(relaxed = true).also { every { it.toString() } returns "doc-uri:$docId" }

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
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            mockk<Uri>(relaxed = true).also {
                every { it.toString() } returns "children:${secondArg<String>()}"
            }
        }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            docUri(secondArg())
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            firstArg<Uri>().toString().removePrefix("doc-uri:")
        }

        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val target = firstArg<Uri>().toString()
            val rows = if (target == "children:root") device.keys.sorted() else emptyList()
            val cursor = mockk<Cursor>(relaxed = true)
            var row = -1
            every { cursor.moveToNext() } answers { ++row < rows.size }
            every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
            every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
            every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE) } returns 2
            every { cursor.getString(0) } answers { docId(rows[row]) }
            every { cursor.getString(1) } answers { rows[row] }
            every { cursor.getString(2) } returns "application/octet-stream"
            every { cursor.extras } returns null
            cursor
        }
        every { resolver.openOutputStream(any(), any()) } answers {
            val name = nameOf(firstArg())
            if (!device.containsKey(name)) throw FileNotFoundException(name)
            interruptedAtOpen[name] = Thread.currentThread().isInterrupted
            val now = open.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            if (name == holding.get()) {
                holdingInside.countDown()
                release.await(15, TimeUnit.SECONDS)
            }
            opened += name
            object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    device[name] = toByteArray()
                    open.decrementAndGet()
                }
            } as OutputStream
        }

        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir

        engine = SafSyncEngine(context)
        engine.onWriteBackFailed = { lost += it.name }
        treeUri = mockk(relaxed = true)
        engine.resetCache(treeUri)
        SafSyncEngine::class.java
            .getDeclaredMethod("cacheDocId", Uri::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(engine, treeUri, "", "root")
    }

    @AfterEach
    fun tearDown() {
        release.countDown()
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        unmockkAll()
    }

    private fun bothSidesHold(name: String, onDevice: String, inMirror: String) {
        device[name] = onDevice.toByteArray()
        File(mirror, name).writeText(inMirror)
    }

    /**
     * A session over the mirror holding the saves of [names], as the drain of a folder that
     * was watched when they were made would hold them. The engine only queues into its own
     * current session, so each job is taken out of that one and put into this.
     */
    private fun sessionHolding(vararg names: String): WatchSession {
        val session = WatchSession(mirror)
        for (name in names) {
            engine.handleMirrorEvent(FileObserver.MODIFY, File(mirror, name), mirror, treeUri)
            session.queue.add(checkNotNull(engine.session.queue.poll()) { "no job was queued for $name" })
        }
        return session
    }

    private fun text(name: String) = device[name]?.toString(Charsets.UTF_8)

    private fun drain(session: WatchSession, running: () -> Boolean = { false }) =
        Thread { engine.runWriteBackLoop(session, running) }.apply { isDaemon = true }

    /** Two sessions over one mirror, as a restored watcher leaves them, and a held write in the first. */
    private fun twoDrainsOneHeld(
        first: String = "a.txt",
        second: String = "b.txt",
    ): Triple<WatchSession, WatchSession, Thread> {
        bothSidesHold(first, "A0", "A1")
        bothSidesHold(second, "B0", "B1")
        val closing = sessionHolding(first)
        val restored = sessionHolding(second)
        holding.set(first)
        val oldDrain = drain(closing).also { it.start() }
        assertTrue(holdingInside.await(5, TimeUnit.SECONDS), "the first drain never reached its write")
        return Triple(closing, restored, oldDrain)
    }

    @Test
    fun `a write behind another drain's write in flight waits and is then sent`() {
        val (_, restored, oldDrain) = twoDrainsOneHeld()

        val newDrain = drain(restored).also { it.start() }
        // The second drain has reached its job and is waiting for its turn, not finished.
        newDrain.join(400)
        assertTrue(newDrain.isAlive, "the second drain gave up at once instead of waiting its turn")
        assertEquals("B0", text("b.txt"), "the second write overtook the first")

        release.countDown()
        oldDrain.join(5_000)
        newDrain.join(5_000)

        assertFalse(oldDrain.isAlive)
        assertFalse(newDrain.isAlive)
        assertEquals("A1", text("a.txt"))
        assertEquals("B1", text("b.txt"), "the second save was dropped while the first was in flight")
        assertEquals(emptyList<String>(), lost, "a save that only had to wait was announced as lost")
        assertEquals(1, peak.get(), "two writes held documents open at once")
        assertEquals(listOf("a.txt", "b.txt"), opened)
    }

    @Test
    fun `a write that waits longer than the ceiling is announced as kept inside the app`() {
        engine.outboundTurnWaitMs = 400
        val (_, restored, oldDrain) = twoDrainsOneHeld()

        val started = System.nanoTime()
        val newDrain = drain(restored).also { it.start() }
        newDrain.join(5_000)
        val waitedMs = (System.nanoTime() - started) / 1_000_000

        assertFalse(newDrain.isAlive, "the second drain waited past its ceiling")
        assertTrue(waitedMs >= 350, "the second drain gave up after ${waitedMs}ms instead of waiting its turn")
        assertEquals(listOf("b.txt"), lost.toList())
        assertEquals("B0", text("b.txt"))

        release.countDown()
        oldDrain.join(5_000)
        assertEquals("A1", text("a.txt"))
    }

    @Test
    fun `a stop that arrives while a write waits for its turn does not drop it`() {
        val (_, restored, oldDrain) = twoDrainsOneHeld()
        val running = AtomicBoolean(true)

        val newDrain = drain(restored) { running.get() }.also { it.start() }
        newDrain.join(300)
        assertTrue(newDrain.isAlive, "the second drain did not wait")

        // What stopWatching does to a worker: say it is done and wake it.
        running.set(false)
        newDrain.interrupt()
        newDrain.join(300)
        assertTrue(newDrain.isAlive, "the interrupt cut the wait short and dropped the polled save")

        release.countDown()
        oldDrain.join(5_000)
        newDrain.join(5_000)

        assertFalse(newDrain.isAlive)
        assertEquals("B1", text("b.txt"), "the save polled before the stop was dropped")
        assertEquals(emptyList<String>(), lost)
        assertEquals(
            false, interruptedAtOpen["b.txt"],
            "the stream was opened on a thread still carrying the interrupt, which fails the copy",
        )
    }

    @Test
    fun `a save that finds an interrupt pending opens its stream without it`() {
        bothSidesHold("a.txt", "A0", "A1")
        val session = sessionHolding("a.txt")
        val turns = AtomicInteger(0)

        // The window between a worker polling its job and the stop's interrupt arriving.
        val drain = Thread {
            Thread.currentThread().interrupt()
            engine.runWriteBackLoop(session) { turns.getAndIncrement() == 0 }
        }.apply { isDaemon = true; start() }
        drain.join(5_000)

        assertFalse(drain.isAlive)
        assertEquals("A1", text("a.txt"))
        assertEquals(
            false, interruptedAtOpen["a.txt"],
            "the stream was opened on a thread still carrying the interrupt, which fails the copy",
        )
    }

    @Test
    fun `a write meeting a reset does not wait for it`() {
        // A sync of the same mirror holds it, reading the device document slowly.
        bothSidesHold("notes.md", "device", "mirror edit")
        val fetchInside = CountDownLatch(1)
        val fetchRelease = CountDownLatch(1)
        every { resolver.openInputStream(any()) } answers {
            fetchInside.countDown()
            fetchRelease.await(10, TimeUnit.SECONDS)
            ByteArrayInputStream("device".toByteArray())
        }
        File(mirror, "notes.md").delete()
        val syncing = Thread {
            try {
                runBlocking { SafSyncEngine(context).initialSync(treeUri, mirror) { _, _ -> } }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(fetchInside.await(5, TimeUnit.SECONDS), "the sync never reached its read")
            File(mirror, "notes.md").writeText("saved during the sync")
            val session = sessionHolding("notes.md")

            val started = System.nanoTime()
            drain(session).also { it.start() }.join(5_000)
            val waitedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(waitedMs < 2_000, "a write waited ${waitedMs}ms behind a reset that supersedes it")
        } finally {
            fetchRelease.countDown()
            syncing.join(5_000)
        }
    }

    @Test
    fun `a reopen is still refused at once while a write is in flight`() {
        val (_, _, oldDrain) = twoDrainsOneHeld()

        val started = System.nanoTime()
        assertThrows(FolderBusyException::class.java) {
            runBlocking { SafSyncEngine(context).initialSync(treeUri, mirror) { _, _ -> } }
        }
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(waitedMs < 2_000, "the reopen waited ${waitedMs}ms for a write, behind a dialog that cannot be cancelled")

        release.countDown()
        oldDrain.join(5_000)
    }
}
