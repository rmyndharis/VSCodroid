package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Who may write into a device document, and how many of them there can be.
 *
 * `stopWatching()` waits DRAIN_GRACE_MS for the write-back thread and then, rather than
 * throw away queued saves, leaves it running. Everything the engine started afterwards
 * shared one queue object with that survivor: the thread the next folder started polled
 * it too, so two threads took jobs off one queue and opened one provider stream at once,
 * with `"wt"` truncating the document at open.
 *
 * The queue and the thread belong to a [WatchSession] now, so a drain that outlived its
 * stop keeps the queue it was started with and the folder opened next gets an empty one.
 */
class SafWatchSessionTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine

    /** Held closed until the test lets the provider finish accepting a write. */
    private val released = AtomicBoolean(false)

    /** Documents the engine opened for writing, in order, from any thread. */
    private val openedForWrite: MutableList<String> =
        Collections.synchronizedList(mutableListOf())

    private val documentNames = mutableMapOf<Uri, String>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        resolver = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)

        every { resolver.openOutputStream(any(), "wt") } answers {
            openedForWrite.add(documentNames[firstArg<Uri>()] ?: "unknown")
            blockingStream()
        }
    }

    @AfterEach
    fun tearDown() {
        // However the assertions ended, no test may leave a thread parked in a write.
        released.set(true)
        unmockkAll()
    }

    /**
     * A provider stream that accepts the bytes only once the test says so.
     *
     * Uninterruptible on purpose: `stopWatching()` interrupts the drain, and a wait that
     * gave up on the interrupt would end the very write this has to keep in flight while
     * the grace period expires.
     */
    private fun blockingStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            while (!released.get()) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // Keep waiting: the interrupt is the stop ending the loop, not the
                    // provider refusing the bytes.
                }
            }
        }
    }

    /** A save of [name] in the mirror, addressed to its own device document. */
    private fun saveOf(name: String): SyncJob {
        val local = File(mirror, name).apply { writeText("a save the provider is slow to accept") }
        val docUri = mockk<Uri>(relaxed = true)
        documentNames[docUri] = name
        return SyncJob(
            type = SyncType.MODIFY,
            localPath = local.absolutePath,
            safDocUri = docUri,
            safParentUri = null,
            safTreeUri = null,
            timestamp = 1_700_000_000_000,
        )
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting until $what")
    }

    @Test
    fun `a drain that outlives its stop never takes the next folder's jobs`() {
        val closing = engine.session
        closing.queue.offer(saveOf("slow.txt"))
        val worker = thread(isDaemon = true) { engine.runWriteBackLoop(closing) { closing.running } }
        closing.worker = worker

        // The write has to be in flight before the folder closes: that is the case where
        // stopWatching() cannot end the thread and lets it finish on its own.
        waitUntil("the first write reaches the provider") { openedForWrite.isNotEmpty() }

        val startedAt = System.currentTimeMillis()
        engine.stopWatching()
        val waited = System.currentTimeMillis() - startedAt

        assertTrue(waited >= 2_000, "stopWatching returned without waiting for the drain")
        assertTrue(worker.isAlive, "setup failed: the drain was meant to outlive the stop")

        val opened = engine.session
        assertNotSame(closing, opened, "the next folder was handed the closed folder's queue")

        // The next folder's first save, queued while the previous drain is still going.
        opened.queue.offer(saveOf("next.txt"))
        released.set(true)
        worker.join(5_000)

        assertEquals(
            listOf("slow.txt"), openedForWrite.toList(),
            "the departing drain opened a document of the folder opened after it",
        )
        assertEquals(1, opened.queue.size, "the departing drain took the next folder's job")
    }

    @Test
    fun `the stop ends the drain rather than leaving it polling`() {
        val closing = engine.session
        val worker = thread(isDaemon = true) { engine.runWriteBackLoop(closing) { closing.running } }
        closing.worker = worker

        engine.stopWatching()

        assertFalse(worker.isAlive, "the drain kept polling after its folder was closed")
    }

    @Test
    fun `a stop with no drain running leaves nothing behind for the next folder`() {
        val closing = engine.session
        closing.queue.offer(saveOf("slow.txt"))

        engine.stopWatching()

        assertTrue(closing.queue.isEmpty(), "a stop with nothing draining has to clear the queue")
        assertNotSame(closing, engine.session, "the next folder reuses the closed folder's session")
        assertTrue(engine.session.queue.isEmpty(), "the next folder starts holding old jobs")
    }
}
