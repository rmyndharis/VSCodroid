package com.vscodroid.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import com.vscodroid.util.Logger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * How the write-back loop ends.
 *
 * It ends by being interrupted: `stopWatching()` clears the flag the loop tests and
 * then calls `interrupt()`, and an idle loop is almost always inside its 200 ms sleep
 * when that lands. The loop body carried no handler, so `InterruptedException` left the
 * thread lambda, reached the default uncaught handler, and on Android that ends the
 * process. `stopWatching()` runs on every folder switch and in `onDestroy`.
 *
 * The same escape also skipped the block that flushes whatever is still queued, so the
 * writes a user had just made were dropped on the way out.
 */
class SafWriteBackLoopTest {

    private lateinit var engine: SafSyncEngine
    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        engine = SafSyncEngine(context)
    }

    @AfterEach
    fun tearDown() {
        // A flag left set on the JUnit thread would make the next test's first blocking
        // call throw, so clear it here as well as in the test that sets it.
        Thread.interrupted()
        unmockkAll()
    }

    /** Puts [job] on the engine's queue without a live watcher to produce it. */
    @Suppress("UNCHECKED_CAST")
    private fun enqueue(job: SyncJob) {
        val field = SafSyncEngine::class.java.getDeclaredField("writeBackQueue")
            .apply { isAccessible = true }
        (field.get(engine) as ConcurrentLinkedQueue<SyncJob>).offer(job)
    }

    @Test
    fun `an interrupt ends the loop instead of the process`() {
        // isRunning never goes false, so the interrupt is the only way out -- which is
        // the situation stopWatching() creates, because it interrupts a thread already
        // asleep. Without the handler the exception leaves the lambda and is recorded
        // here; on a device it would be recorded by the handler that kills the app.
        val escaped = AtomicReference<Throwable?>(null)
        val worker = Thread { engine.runWriteBackLoop { true } }.apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, thrown -> escaped.set(thrown) }
        }

        worker.start()
        // Not load-bearing: an interrupt delivered before the sleep sets the flag, and
        // the sleep then throws immediately. This only makes the common case the one
        // being exercised.
        Thread.sleep(50)
        worker.interrupt()
        worker.join(2_000)

        assertNull(escaped.get(), "the interrupt escaped the loop: on Android that ends the process")
        assertFalse(worker.isAlive, "the loop did not stop when it was interrupted")
    }

    @Test
    fun `queued writes still go out after the loop is told to stop`() {
        // The drain sits after the loop. When the exception escaped, it was
        // unreachable, and stopWatching() then cleared the queue -- so a save made a
        // moment before switching folders reached neither the device nor a log line.
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.deleteDocument(any(), any()) } returns true

        enqueue(
            SyncJob(
                type = SyncType.DELETE,
                localPath = "/mirror/notes.txt",
                safDocUri = mockk<Uri>(relaxed = true),
                safParentUri = null,
                safTreeUri = null,
                timestamp = 1_700_000_000_000
            )
        )

        engine.runWriteBackLoop { false }

        verify(exactly = 1) { DocumentsContract.deleteDocument(any(), any()) }
    }

    @Test
    fun `the drain runs with the interrupt status clear and restores it afterwards`() {
        // The flag matters here and nowhere else: the drain's work is stream copies,
        // and a stream on a thread that still carries the flag throws
        // InterruptedIOException at once. Draining under a set flag would look like a
        // drain and move nothing.
        //
        // Driven on this thread with the flag set up front, because that is the one
        // arrangement where "was it cleared first" has a deterministic answer.
        mockkStatic(DocumentsContract::class)
        val flagDuringDrain = AtomicBoolean(true)
        every { DocumentsContract.deleteDocument(any(), any()) } answers {
            flagDuringDrain.set(Thread.currentThread().isInterrupted)
            true
        }

        enqueue(
            SyncJob(
                type = SyncType.DELETE,
                localPath = "/mirror/notes.txt",
                safDocUri = mockk<Uri>(relaxed = true),
                safParentUri = null,
                safTreeUri = null,
                timestamp = 1_700_000_000_000
            )
        )

        Thread.currentThread().interrupt()
        engine.runWriteBackLoop { false }

        assertFalse(flagDuringDrain.get(), "the drain ran on an interrupted thread")
        // Also clears it, which is what keeps the flag out of the next test.
        assertTrue(Thread.interrupted(), "the interrupt status was swallowed rather than restored")
    }
}
