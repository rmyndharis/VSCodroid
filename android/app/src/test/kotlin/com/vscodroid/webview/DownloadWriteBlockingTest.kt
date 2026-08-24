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
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
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
 *
 * The claim covers only the window in which a write or a close is outstanding,
 * and that is a minority of a transfer's wall time: the page yields between
 * pieces, and nothing at all is claimed between the picker answering and the
 * first piece arriving. On that far larger half the teardown does the work
 * itself, so the close that commits the bytes and the delete that follows it
 * have to leave the caller's thread outright. That is what the coordinator's
 * own executor is for, and why the cases here drive a real one on a thread of
 * its own rather than a direct executor: with the hand-off run inline, "the
 * caller waited" and "the caller was quick" are the same measurement.
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

    /** Set to make deleting a document stop until [release]. */
    @Volatile
    private var blockDiscards = false

    /** Set to make opening the chosen document stop until [release]. */
    @Volatile
    private var blockOpens = false

    /**
     * Whether the stream to a document had been closed when it was deleted.
     *
     * One entry per delete. The order matters on its own: a document deleted
     * while a stream to it is still open is a delete racing a provider that has
     * not finished with the file, which is what the claim over the open exists
     * to rule out.
     */
    private val closedWhenDiscarded = mutableListOf<Boolean>()

    /**
     * Where the coordinator's answer to an open is applied, held when
     * [holdMainThread] is set so a case can run a teardown inside the hand-off.
     */
    private val mainQueue = LinkedBlockingQueue<Runnable>()

    /** Set to divert the coordinator's main-thread hand-offs into [mainQueue]. */
    @Volatile
    private var holdMainThread = false

    /**
     * The coordinator's own provider thread, real rather than direct.
     *
     * Single, as the one it ships with is, so a close still precedes the delete
     * of the same document. [awaitProviderIdle] is how a case waits for what was
     * handed to it, and every case that asserts on a discarded document calls it
     * first: without that the assertion races the hand-off and passes or fails
     * on the runner's mood.
     */
    private lateinit var providerWork: ExecutorService

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
            if (blockOpens) {
                insideCall.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            stream = BlockingStream()
            documents[destination] = stream
            return stream
        }

        override fun discardDestination(destination: Uri) {
            if (blockDiscards) {
                insideCall.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            synchronized(discarded) {
                discarded += destination
                closedWhenDiscarded += documents[destination]?.closed ?: false
                documents.remove(destination)
            }
        }

        override fun requestBytes(requestId: String, url: String) {
            readRequests += requestId to url
        }

        override fun releaseBytes(url: String) = Unit

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
        closedWhenDiscarded.clear()
        mainQueue.clear()
        blockWrites = false
        blockCloses = false
        blockDiscards = false
        blockOpens = false
        holdMainThread = false
        providerWork = Executors.newSingleThreadExecutor()
        // Inline unless a case says otherwise: the coordinator brings the answer
        // to an open back to the thread the picker answered on, and running that
        // where it is handed over keeps every case below reading state that has
        // settled. `an open whose answer is held` is the one that drives it.
        coordinator = DownloadCoordinator(
            host,
            providerWork = providerWork,
            mainThread = Executor { work -> if (holdMainThread) mainQueue.put(work) else work.run() },
        )
    }

    @AfterEach
    fun tearDown() {
        release.countDown()
        providerWork.shutdownNow()
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
        // The document is opened on the provider thread now, so the page has not
        // been asked for anything yet when the picker's answer returns.
        awaitProviderIdle()
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

    /**
     * Waits for everything handed to the provider thread up to this point.
     *
     * A barrier rather than a sleep: the thread is single, so work queued behind
     * the hand-off cannot run in front of it. Every case that asserts on a
     * discarded document goes through here first, or the assertion races the
     * hand-off and answers differently on a loaded runner.
     */
    private fun awaitProviderIdle() = assertTrue(
        // Drained twice, and the second pass is the one that matters. The thread
        // is single and FIFO, so one barrier proves only that the work queued
        // BEFORE it has run -- and the work this waits for is queued by a task
        // that is still running when the barrier goes in. The open runs on this
        // thread, hands `onDestinationOpened` straight back on it (the fixture's
        // mainThread runs inline), and that is what queues the discard. So the
        // queue reads [barrier, discard], the barrier answers first, and the
        // case reads `discarded` before the discard it is about has happened.
        // Measured: green here and on five runs under load, red once on a CI
        // runner, which is what losing that race looks like. The second barrier
        // is queued once the first has answered, by which time the discard is
        // already on the queue ahead of it.
        (1..2).all {
            CountDownLatch(1).let { drained ->
                providerWork.execute { drained.countDown() }
                drained.await(5, TimeUnit.SECONDS)
            }
        },
        "the provider thread never got through what it was handed, so what follows " +
            "would be reading the state of a teardown that has not happened yet",
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
        awaitProviderIdle()

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
        awaitProviderIdle()

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
        awaitProviderIdle()

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
     * The teardown with nothing claimed, which is most of a transfer's wall
     * time.
     *
     * The claim only covers a write or a close that is actually outstanding.
     * Between two pieces the page yields, and between the picker answering and
     * the first piece there is a whole trip through the bridge, so a teardown
     * arriving at any of those moments finds `busy` false and does the work
     * itself: the close that commits the buffered bytes to the provider, and
     * the delete behind it. On the UI thread that is the same ANR the claim was
     * written to remove, reached by the other half of the same window.
     *
     * NEGATIVE CONTROL: in `closeAndDiscard`, replace the
     * `offThread("close") { it.close() }` hand-off with a plain `it.close()`.
     * This case then waits the blocked close out and fails on the elapsed time.
     */
    @Test
    fun `a teardown between two pieces does not wait for the close that commits them`() {
        val target = destination()
        val id = startAndChoose(target)
        // Returns, so nothing is claimed when the teardown arrives. This is the
        // ordinary state of a transfer, not a corner of one.
        coordinator.onBytes(id, encode("payload"))
        blockCloses = true

        val waitedMs = timed { coordinator.onPageGone() }
        awaitInsideProvider()

        assertTrue(
            waitedMs < 1_000,
            "the teardown waited ${waitedMs}ms for the close to commit. Closing a " +
                "content:// stream is what pushes the buffered bytes into the provider, " +
                "so it costs whatever the provider costs, and onPageGone runs on the UI " +
                "thread from onDestroy, from every finished main-frame load and from " +
                "recreateWebView",
        )
    }

    /**
     * The other half of the case above: not waiting is easy by doing nothing.
     *
     * NEGATIVE CONTROL: drop either hand-off from `closeAndDiscard` entirely and
     * this goes red on the stream or on the document.
     */
    @Test
    fun `the document a teardown handed over is still closed and removed`() {
        val target = destination()
        val id = startAndChoose(target)
        coordinator.onBytes(id, encode("payload"))
        blockCloses = true

        coordinator.onPageGone()
        awaitInsideProvider()
        release.countDown()
        awaitProviderIdle()

        assertTrue(stream.closed, "the stream was left open on a download that is over")
        assertEquals(
            listOf(target), discarded,
            "the picker's file is still in the user's folder, part-written and wearing " +
                "the name of the file they asked for, which is indistinguishable from a " +
                "finished save until they open it",
        )
    }

    /**
     * The delete, which is a second trip into the same provider and just as
     * unbounded as the close.
     *
     * NEGATIVE CONTROL: replace the `offThread("discard")` hand-off in
     * `closeAndDiscard` with a direct `host.discardDestination(it)`. The close
     * being handed over does not save it: this case leaves the close free to
     * return and blocks only the delete.
     */
    @Test
    fun `a teardown does not wait for the document to be deleted either`() {
        val target = destination()
        val id = startAndChoose(target)
        coordinator.onBytes(id, encode("payload"))
        blockDiscards = true

        val waitedMs = timed { coordinator.onPageGone() }
        awaitInsideProvider()

        assertTrue(
            waitedMs < 1_000,
            "the teardown waited ${waitedMs}ms inside DocumentsContract.deleteDocument, " +
                "which is a synchronous call into the provider that owns the document",
        )
    }

    /**
     * The picker outliving the download it was opened for, which is the one
     * path here that needs no second thread at all: the result arrives on the
     * UI thread, the document it created belongs to nobody, and the delete of
     * it used to run right there.
     *
     * NEGATIVE CONTROL: restore the direct `host.discardDestination(it)` in the
     * "belongs to no download in flight" branch of `onDestinationChosen`.
     */
    @Test
    fun `a picker answering for a download that is gone deletes off the caller's thread`() {
        val target = destination()
        coordinator.onDownloadNamed("blob:x", "report.pdf")
        coordinator.onDownloadStart("blob:x", null)
        val requestId = pickerIds.last()
        // The renderer died under the picker. The result still arrives, having
        // already created a document nobody now owns.
        coordinator.onPageGone()
        blockDiscards = true

        val waitedMs = timed { coordinator.onDestinationChosen(requestId, target) }
        awaitInsideProvider()

        assertTrue(
            waitedMs < 1_000,
            "the activity result callback waited ${waitedMs}ms deleting a document, on " +
                "the UI thread, with no other thread involved at all",
        )
    }

    /**
     * Opening the chosen document is the first trip into the provider and was
     * the last one still made on the UI thread, under the monitor.
     *
     * `onDestinationChosen` runs in the activity-result callback, so a provider
     * whose process has to be cold-started, or that answers over a network,
     * froze the editor from the moment the picker closed; past five seconds the
     * system offers to close the app, taking the editor session with it.
     *
     * NEGATIVE CONTROL: put the open back inline, replacing the
     * `providerWork.execute { ... }` hand-off in `onDestinationChosen` with the
     * open and a direct call to `onDestinationOpened`. This goes red on the
     * elapsed time.
     */
    @Test
    fun `the picker's answer does not wait for the document to open`() {
        val target = destination()
        coordinator.onDownloadNamed("blob:x", "report.pdf")
        coordinator.onDownloadStart("blob:x", null)
        blockOpens = true

        val waitedMs = timed { coordinator.onDestinationChosen(pickerIds.last(), target) }
        awaitInsideProvider()

        release.countDown()
        assertTrue(
            waitedMs < 1_000,
            "the activity result callback waited ${waitedMs}ms inside the provider, on " +
                "the UI thread. openOutputStream cold-starts the provider's process and " +
                "has no timeout, so that wait is the editor frozen and then an ANR",
        )
    }

    /**
     * And the monitor is not held across it either, which is the half the timing
     * above cannot see: a caller that does not wait for the provider is worth
     * nothing if every other caller waits for it instead.
     *
     * NEGATIVE CONTROL: the same restoration as the case above. The open then
     * runs under `@Synchronized`, so this teardown waits for the whole of it.
     */
    @Test
    fun `a teardown does not wait for a document that is still opening`() {
        val target = destination()
        coordinator.onDownloadNamed("blob:x", "report.pdf")
        coordinator.onDownloadStart("blob:x", null)
        blockOpens = true
        // On a thread of its own, so that restoring the inline open leaves this
        // case measuring a monitor a blocked caller is holding rather than a
        // provider that has already returned.
        val chooser = Thread { coordinator.onDestinationChosen(pickerIds.last(), target) }
            .apply { start() }
        awaitInsideProvider()

        val waitedMs = timed { coordinator.onPageGone() }

        release.countDown()
        chooser.join(5_000)
        awaitProviderIdle()
        assertTrue(
            waitedMs < 1_000,
            "the teardown waited ${waitedMs}ms for a document to open. onPageGone is " +
                "called from onDestroy, from every finished main-frame load and from " +
                "recreateWebView, all on the UI thread",
        )
        assertEquals(
            listOf(target), discarded,
            "the download was abandoned while its document was opening, and the empty " +
                "file the picker created for it stayed in the user's folder wearing the " +
                "name of the file they asked for",
        )
    }

    /**
     * The claim over the open, which is what orders the delete after the close.
     *
     * A teardown that lands while the answer to an open is still in the air
     * finds the download claimed and leaves the document to the thread that
     * opened it, so the stream is closed before the delete goes in. Without the
     * claim the teardown deletes first and the close follows it, which is a
     * delete racing a provider that has not finished with the file.
     *
     * NEGATIVE CONTROL: delete `request.busy = true` from `onDestinationChosen`.
     * The teardown below then discards immediately, with no stream to close yet,
     * and this goes red on `closedWhenDiscarded`.
     */
    @Test
    fun `a document opened into a teardown is closed before it is deleted`() {
        val target = destination()
        coordinator.onDownloadNamed("blob:x", "report.pdf")
        coordinator.onDownloadStart("blob:x", null)
        // Held so the teardown lands in the gap between the provider answering
        // and the coordinator acting on the answer.
        holdMainThread = true
        coordinator.onDestinationChosen(pickerIds.last(), target)
        awaitProviderIdle()
        assertTrue(
            readRequests.isEmpty(),
            "the page was asked for the bytes from the provider thread. requestBytes " +
                "reaches the page through evaluateJavascript, which the WebView refuses " +
                "from any thread but its own",
        )

        coordinator.onPageGone()
        holdMainThread = false
        mainQueue.poll(5, TimeUnit.SECONDS)?.run()
        awaitProviderIdle()

        assertEquals(listOf(target), discarded, "the abandoned document was left behind")
        assertEquals(
            listOf(true), closedWhenDiscarded,
            "the document was deleted with the stream to it still open, so the delete " +
                "raced a provider that had not finished with the file",
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
        awaitProviderIdle()

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
