package com.vscodroid.webview

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * That nothing on the UI thread waits for a download's provider.
 *
 * The document a download is written into comes from
 * `contentResolver.openOutputStream`, and a stream to a cloud or MTP provider has
 * no timeout: this repository has already measured that once, in `SafSyncEngine`,
 * where the note beside it reads "a trade of corruption for a hang is not a fix".
 * The bytes arrive on the WebView's bridge thread, while `onPageGone` is called
 * from `onDestroy`, from every finished main-frame load and from
 * `recreateWebView`, all on the UI thread. One monitor held across the write puts
 * the second behind the first, and a save that is merely slow becomes an ANR.
 *
 * `DownloadCoordinatorTest` cannot see any of this. Every case there drives the
 * coordinator from one thread with a stream that returns immediately, so the
 * scope of the monitor is invisible to all of them and reverting the split
 * wholesale leaves that file green. So these cases contend on purpose: a stream
 * that stops inside `write` or `close` until the test lets it go, a second thread
 * driving the teardown, and an elapsed time as the assertion.
 *
 * The two halves are pinned together deliberately. Not waiting is easy on its
 * own, by simply not guarding anything; what makes the split honest is that the
 * download is claimed for the length of the call, so the file the teardown could
 * not remove is removed by the thread that was inside the provider, and a save
 * that finished during a teardown is kept rather than deleted.
 */
class DownloadWriteBlockingTest {

    /** What the fake picker was asked to create, one entry per request. */
    private val asked = mutableListOf<String>()

    /** The request each of those pickers was opened for, in the same order. */
    private val pickerIds = mutableListOf<String>()

    /** Documents the fake picker created and did not delete. */
    private val documents = mutableMapOf<Uri, BlockingStream>()

    /** Documents removed through [DownloadHost.discardDestination]. */
    private val discarded = mutableListOf<Uri>()

    /** Every outcome reported, in order. */
    private val reported = mutableListOf<Pair<DownloadOutcome, String>>()

    /** URLs the page was asked to read, paired with the request id. */
    private val readRequests = mutableListOf<Pair<String, String>>()

    /** The stream behind the document opened most recently. */
    private lateinit var stream: BlockingStream

    /** Set to make writes to the opened document stop until [release]. */
    @Volatile
    private var blockWrites = false

    /** Set to make closing the opened document stop until [release]. */
    @Volatile
    private var blockCloses = false

    /** Counted down by whichever call is holding the provider. */
    private val insideCall = CountDownLatch(1)

    /** Counted down by the test to let that call return. */
    private val release = CountDownLatch(1)

    private lateinit var coordinator: DownloadCoordinator

    /**
     * A document that can be told to stop inside `write` or inside `close`.
     *
     * The bytes are recorded after the wait rather than before it, so a chunk
     * that was refused while another was outstanding cannot reach the file by
     * having been written on the way in.
     */
    private inner class BlockingStream : OutputStream() {
        val written = ByteArrayOutputStream()

        @Volatile
        var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (blockWrites) {
                insideCall.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            synchronized(written) { written.write(b, off, len) }
        }

        override fun close() {
            if (blockCloses) {
                insideCall.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            closed = true
        }
    }

    private val host = object : DownloadHost {
        override fun askDestination(requestId: String, fileName: String) {
            synchronized(asked) {
                asked += fileName
                pickerIds += requestId
            }
        }

        override fun openDestination(destination: Uri): OutputStream {
            stream = BlockingStream()
            documents[destination] = stream
            return stream
        }

        override fun discardDestination(destination: Uri) {
            synchronized(discarded) {
                discarded += destination
                documents.remove(destination)
            }
        }

        override fun requestBytes(requestId: String, url: String) {
            readRequests += requestId to url
        }

        override fun report(outcome: DownloadOutcome, fileName: String, detail: String?) {
            synchronized(reported) { reported += outcome to fileName }
        }
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        asked.clear()
        pickerIds.clear()
        documents.clear()
        discarded.clear()
        reported.clear()
        readRequests.clear()
        blockWrites = false
        blockCloses = false
        coordinator = DownloadCoordinator(host)
    }

    @AfterEach
    fun tearDown() {
        release.countDown()
        unmockkAll()
    }

    private fun destination(name: String = "doc"): Uri = mockk<Uri>(relaxed = true, name = name)

    private fun encode(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    /** Drives one whole download to the point where the page is reading it. */
    private fun startAndChoose(target: Uri, name: String = "report.pdf"): String {
        coordinator.onDownloadNamed("blob:x", name)
        coordinator.onDownloadStart("blob:x", null)
        coordinator.onDestinationChosen(pickerIds.last(), target)
        return readRequests.last().first
    }

    /**
     * Asserts the contention actually happened. Without this every timing case
     * below would pass over a stream that never blocked at all, which is the
     * shape of a test that measures nothing.
     */
    private fun awaitInsideProvider() = assertTrue(
        insideCall.await(5, TimeUnit.SECONDS),
        "nothing ever reached the blocking stream, so no call was contended and " +
            "the timing below would be measuring an idle coordinator",
    )

    /** How long [work] took, in milliseconds. */
    private fun timed(work: () -> Unit): Long {
        val started = System.nanoTime()
        work()
        return (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun `a teardown does not wait for a write inside the provider`() {
        val target = destination()
        val id = startAndChoose(target)
        blockWrites = true

        val writer = Thread { coordinator.onBytes(id, encode("payload")) }.apply { start() }
        awaitInsideProvider()

        val waitedMs = timed { coordinator.onPageGone() }

        release.countDown()
        writer.join(5_000)
        assertTrue(
            waitedMs < 1_000,
            "the teardown waited ${waitedMs}ms for a provider that had not returned. " +
                "onPageGone is called from onDestroy, from every finished main-frame " +
                "load and from recreateWebView, all on the UI thread, so that wait is " +
                "an ANR whenever the document lives on a slow provider",
        )
    }

    @Test
    fun `the document a blocked write was filling is removed when that write returns`() {
        val target = destination()
        val id = startAndChoose(target)
        blockWrites = true

        val writer = Thread { coordinator.onBytes(id, encode("payload")) }.apply { start() }
        awaitInsideProvider()
        coordinator.onPageGone()

        release.countDown()
        writer.join(5_000)

        assertEquals(
            listOf(target), discarded,
            "the teardown could not free the stream, so the thread inside the provider " +
                "has to. Left undone, the picker's file stays in the user's folder, " +
                "part-written, wearing the name of the file they asked for",
        )
        assertTrue(stream.closed, "the stream was left open on a download that is over")
    }

    @Test
    fun `a close that blocks does not hold the teardown either`() {
        val target = destination()
        val id = startAndChoose(target)
        coordinator.onBytes(id, encode("payload"))
        blockCloses = true

        val closer = Thread { coordinator.onComplete(id, null) }.apply { start() }
        awaitInsideProvider()

        val waitedMs = timed { coordinator.onPageGone() }

        release.countDown()
        closer.join(5_000)
        assertTrue(
            waitedMs < 1_000,
            "the teardown waited ${waitedMs}ms for a close to commit. A close to a " +
                "provider blocks for as long as a write to one does, so guarding it " +
                "costs exactly what guarding the write costs",
        )
    }

    @Test
    fun `a finished save is not deleted by a teardown that arrived during its commit`() {
        val target = destination()
        val id = startAndChoose(target)
        coordinator.onBytes(id, encode("payload"))
        blockCloses = true

        val closer = Thread { coordinator.onComplete(id, null) }.apply { start() }
        awaitInsideProvider()
        coordinator.onPageGone()

        release.countDown()
        closer.join(5_000)

        assertEquals(
            emptyList<Uri>(), discarded,
            "the bytes were committed before the page went away, so the file is whole. " +
                "Deleting it here would take a finished save away from the user because " +
                "their page reloaded a moment later",
        )
        assertTrue(documents.containsKey(target), "the finished file did not survive")
        assertFalse(
            reported.contains(DownloadOutcome.SAVED to "report.pdf"),
            "the page went away before the save could be reported, and a toast for a " +
                "download the user is no longer watching is the noise onPageGone is " +
                "silent to avoid",
        )
    }

    @Test
    fun `a completion that arrives during a write ends the download instead of closing under it`() {
        val target = destination()
        val id = startAndChoose(target)
        blockWrites = true

        val writer = Thread { coordinator.onBytes(id, encode("payload")) }.apply { start() }
        awaitInsideProvider()

        coordinator.onComplete(id, null)

        release.countDown()
        writer.join(5_000)

        assertEquals(
            listOf(DownloadOutcome.FAILED to "report.pdf"), reported,
            "a close cannot run under a write of the same stream, so the download ends " +
                "as a failure rather than being reported saved over a file whose last " +
                "piece was still in the provider",
        )
        assertEquals(listOf(target), discarded, "and the file it was filling goes with it")
    }

    @Test
    fun `a second writer is refused and does not interleave`() {
        val target = destination()
        val id = startAndChoose(target)
        blockWrites = true

        val writer = Thread { coordinator.onBytes(id, encode("first")) }.apply { start() }
        awaitInsideProvider()

        val accepted = coordinator.onBytes(id, encode("second"))

        release.countDown()
        writer.join(5_000)

        assertEquals(false, accepted, "the second writer was let into a stream already in use")
        assertEquals(
            "first", synchronized(stream.written) { stream.written.toString() },
            "two writers reached one stream, so the file holds their pieces spliced " +
                "together in whatever order the provider finished them",
        )
    }

    /**
     * The control that stops every case above from passing over a coordinator
     * that stopped writing altogether.
     */
    @Test
    fun `an uncontended download still writes, closes and reports`() {
        val target = destination()
        val id = startAndChoose(target)

        coordinator.onBytes(id, encode("fun main"))
        coordinator.onBytes(id, encode("() {}"))
        coordinator.onComplete(id, null)

        assertEquals("fun main() {}", stream.written.toString())
        assertTrue(stream.closed, "the document is closed, which is what commits it")
        assertEquals(listOf(DownloadOutcome.SAVED to "report.pdf"), reported)
        assertEquals(emptyList<Uri>(), discarded, "a completed download keeps its file")
    }

    /**
     * The second control: the claim is released rather than held for ever. A
     * claim left set would wedge the coordinator, and every case above would
     * still pass because each drives one download.
     */
    @Test
    fun `the coordinator takes a new download once a blocked one is released`() {
        val target = destination()
        val id = startAndChoose(target)
        blockWrites = true
        val writer = Thread { coordinator.onBytes(id, encode("payload")) }.apply { start() }
        awaitInsideProvider()
        coordinator.onPageGone()
        release.countDown()
        writer.join(5_000)

        coordinator.onDownloadStart("blob:next", null)

        assertEquals(
            2, asked.size,
            "the next download never got a picker, so the claim from the blocked write " +
                "was never given back and every later download queues behind it",
        )
    }
}
