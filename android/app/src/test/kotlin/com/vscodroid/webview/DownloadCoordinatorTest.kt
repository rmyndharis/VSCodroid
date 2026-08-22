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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.Executor

/**
 * That a download either arrives complete or is visibly not there.
 *
 * The failure this replaces was a menu entry that did nothing and reported
 * nothing, so silence is the defect and every case below asserts what the user
 * was told as well as what was written. The second half is the one with teeth:
 * the picker creates the file as soon as the user confirms a name, so every
 * path that ends badly starts from a real file already sitting in their folder
 * wearing the name of the one they wanted. Left behind, that file is a download
 * that looks finished until it is opened, which is worse than the original bug
 * because the user has no reason to retry.
 *
 * So these drive outcomes, not calls. Each case asserts three things together:
 * what reached the file, whether the file survived, and what was reported. Any
 * one of them alone is satisfiable by an implementation that is wrong about the
 * other two.
 *
 * The provider work is driven where it is handed over, which is the one thing
 * here that is not how the coordinator ships: closing a document and deleting it
 * go to a thread of its own, because both are unbounded calls into another app
 * and every teardown decides on the UI thread. That property is not this file's
 * to check and cannot be checked from a single-threaded case at all;
 * `DownloadWriteBlockingTest` owns it. What is wanted here is for the outcome to
 * have finished by the time a case asserts on it.
 */
class DownloadCoordinatorTest {

    /** What the fake picker was asked to create, one entry per request. */
    private val asked = mutableListOf<String>()

    /** The request each of those pickers was opened for, in the same order. */
    private val pickerIds = mutableListOf<String>()

    /** Documents the fake picker created and did not delete. */
    private val documents = mutableMapOf<Uri, RecordingStream>()

    /** Documents removed through [DownloadHost.discardDestination]. */
    private val discarded = mutableListOf<Uri>()

    /** Every outcome reported, in order. */
    private val reported = mutableListOf<Pair<DownloadOutcome, String>>()

    /** URLs the page was asked to read, paired with the request id. */
    private val readRequests = mutableListOf<Pair<String, String>>()

    /** URLs the page was told it may stop holding the bytes of, in order. */
    private val released = mutableListOf<String>()

    /** Whether the fake picker reports that it opened. */
    private var pickerOpens = true

    /** Set to make the next [DownloadHost.openDestination] answer null. */
    private var destinationOpens = true

    /** Set to make writes to the next opened document throw. */
    private var writesFail = false

    /** Set to make closing the next opened document throw. */
    private var closeFails = false

    private lateinit var coordinator: DownloadCoordinator

    /**
     * A document that remembers its bytes and whether it was closed, and can be
     * told to fail on either.
     *
     * `closed` is tracked because a stream left open is a file the provider may
     * never flush, and nothing else in the suite would notice: the bytes are
     * already in this object by then, so an assertion on content alone passes
     * over a coordinator that never closes anything.
     */
    private inner class RecordingStream : OutputStream() {
        val written = ByteArrayOutputStream()
        var closed = false

        override fun write(b: Int) {
            if (writesFail) throw IOException("no space")
            written.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (writesFail) throw IOException("no space")
            written.write(b, off, len)
        }

        override fun close() {
            closed = true
            if (closeFails) throw IOException("commit failed")
        }
    }

    private val host = object : DownloadHost {
        override fun askDestination(requestId: String, fileName: String) {
            asked += fileName
            pickerIds += requestId
            if (!pickerOpens) coordinator.onDestinationUnavailable(requestId)
        }

        override fun openDestination(destination: Uri): OutputStream? {
            if (!destinationOpens) return null
            val stream = RecordingStream()
            documents[destination] = stream
            return stream
        }

        override fun discardDestination(destination: Uri) {
            discarded += destination
            documents.remove(destination)
        }

        override fun requestBytes(requestId: String, url: String) {
            readRequests += requestId to url
        }

        override fun releaseBytes(url: String) {
            released += url
        }

        override fun report(outcome: DownloadOutcome, fileName: String, detail: String?) {
            reported += outcome to fileName
        }
    }

    /**
     * Every warning the coordinator logged, in order.
     *
     * `Logger.w` is not gated on a debuggable build, so these ship, and logcat is
     * readable by anything holding `READ_LOGS`.
     */
    private val warnings = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } answers { warnings += secondArg<String>() }
        every { Logger.w(any(), any(), any()) } answers { warnings += secondArg<String>() }
        warnings.clear()
        asked.clear()
        pickerIds.clear()
        documents.clear()
        discarded.clear()
        reported.clear()
        readRequests.clear()
        released.clear()
        pickerOpens = true
        destinationOpens = true
        writesFail = false
        closeFails = false
        // Both executors run where they are handed over, so the whole of a
        // download settles inside the call that started it and every case below
        // reads state that has stopped moving. Which thread each one really is
        // belongs to DownloadWriteBlockingTest.
        coordinator = DownloadCoordinator(
            host,
            providerWork = Executor { it.run() },
            mainThread = Executor { it.run() },
        )
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun destination(name: String = "doc"): Uri = mockk<Uri>(relaxed = true, name = name)

    private fun encode(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    /** The id the page is told to answer under, for the request just started. */
    private fun liveRequestId(): String = readRequests.last().first

    /** Answers the picker on screen the way the Activity does, naming its request. */
    private fun chooseDestination(target: Uri?) =
        coordinator.onDestinationChosen(pickerIds.last(), target)

    /** Drives one whole download to the point where the page is reading it. */
    private fun startAndChoose(url: String = "blob:x", target: Uri = destination()): String {
        coordinator.onDownloadStart(url, null)
        chooseDestination(target)
        return liveRequestId()
    }

    /**
     * The ordinary success, asserted end to end because it is the baseline every
     * failure case is measured against.
     */
    @Test
    fun `a completed download writes its bytes and says so`() {
        val target = destination()
        coordinator.onDownloadNamed("blob:x", "App.kt")
        val id = startAndChoose("blob:x", target)

        assertEquals(listOf("App.kt"), asked, "the picker is offered the name the page reported")
        coordinator.onBytes(id, encode("fun main"))
        coordinator.onBytes(id, encode("() {}"))
        coordinator.onComplete(id, null)

        val document = documents[target]!!
        assertEquals("fun main() {}", document.written.toString(),
            "every piece reaches the file, in the order the page sent them")
        assertTrue(document.closed, "the document is closed, which is what commits it")
        assertEquals(listOf(DownloadOutcome.SAVED to "App.kt"), reported)
        assertEquals(emptyList<Uri>(), discarded, "a completed download keeps its file")
    }

    /**
     * Backing out of the picker. The common case, and the one where doing
     * nothing looks most like working correctly: there is no file to clean up,
     * so an implementation that simply forgets the request passes every
     * assertion except the report.
     */
    @Test
    fun `cancelling says so and leaves nothing behind`() {
        coordinator.onDownloadStart("blob:x", null)

        chooseDestination(null)

        assertEquals(listOf(DownloadOutcome.CANCELLED to FALLBACK_DOWNLOAD_NAME), reported,
            "a cancellation the user cannot see is the failure this replaces")
        assertEquals(emptyList<Pair<String, String>>(), readRequests,
            "nothing is read for a download with nowhere to go")
        assertEquals(emptyList<Uri>(), discarded,
            "the picker creates nothing until the user confirms, so there is nothing to remove")
    }

    /**
     * A write that fails midway. The partial file is the whole point: it exists,
     * it has the right name, and it is short. Anything that leaves it there has
     * turned a failed download into one the user will not know to repeat.
     */
    @Test
    fun `a write that fails removes the half-written file`() {
        val target = destination()
        coordinator.onDownloadNamed("blob:x", "notes.md")
        val id = startAndChoose("blob:x", target)
        coordinator.onBytes(id, encode("first half"))

        writesFail = true
        val accepted = coordinator.onBytes(id, encode("second half"))

        assertEquals(false, accepted, "the page has to be told to stop reading")
        assertEquals(listOf(target), discarded, "the partial file must not survive")
        assertEquals(listOf(DownloadOutcome.FAILED to "notes.md"), reported)
    }

    /**
     * The same shape reported from the page's end rather than found here: the
     * fetch failed, so the bytes already written are all there will ever be.
     */
    @Test
    fun `an error from the page removes the file it was filling`() {
        val target = destination()
        val id = startAndChoose(target = target)
        coordinator.onBytes(id, encode("partial"))

        coordinator.onComplete(id, "status 404")

        assertEquals(listOf(target), discarded)
        assertEquals(listOf(DownloadOutcome.FAILED to FALLBACK_DOWNLOAD_NAME), reported)
    }

    /**
     * Closing is part of the write, not cleanup after it. A `content://` stream
     * can hold everything until close, so a close that throws means the file was
     * never written, and reporting success there is the one failure the user has
     * no way at all to detect.
     */
    @Test
    fun `a file that will not close is a failure, not a save`() {
        val target = destination()
        val id = startAndChoose(target = target)
        coordinator.onBytes(id, encode("everything"))

        closeFails = true
        coordinator.onComplete(id, null)

        assertEquals(listOf(DownloadOutcome.FAILED to FALLBACK_DOWNLOAD_NAME), reported,
            "bytes that never reached storage are not a saved file")
        assertEquals(listOf(target), discarded)
    }

    /** A document the picker created but nothing can write to. */
    @Test
    fun `a destination that will not open is reported and removed`() {
        val target = destination()
        destinationOpens = false
        coordinator.onDownloadStart("blob:x", null)

        chooseDestination(target)

        assertEquals(listOf(DownloadOutcome.FAILED to FALLBACK_DOWNLOAD_NAME), reported)
        assertEquals(listOf(target), discarded,
            "an empty file wearing the wanted name is the failure being avoided")
        assertEquals(emptyList<Pair<String, String>>(), readRequests)
    }

    /**
     * A device with no document creator. Nothing downstream will ever run, so
     * the refusal has to be reported from here or not at all.
     */
    @Test
    fun `a picker that cannot open reports immediately`() {
        pickerOpens = false

        coordinator.onDownloadStart("blob:x", null)

        assertEquals(listOf(DownloadOutcome.FAILED to FALLBACK_DOWNLOAD_NAME), reported)
        // Nothing is left waiting: a later result would otherwise be taken as
        // this request's answer and write into a file nobody asked for.
        val stray = destination("stray")
        chooseDestination(stray)
        assertEquals(1, reported.size, "the abandoned request must not be revived by a stray result")
        assertEquals(listOf(stray), discarded, "and the document it created does not stay behind")
    }

    /**
     * Multi-select download: one download per file, arriving before the user has
     * answered anything.
     *
     * The measured failure was two pickers open at once, whose results are two
     * `Uri`s and nothing else. Matched by arrival they cross over, and the file
     * created under the first name is filled with the second file's bytes while
     * an empty one keeps the other name. So each file has to reach the
     * destination chosen for it, and that is what is asserted here, not the
     * order the pickers happened to open in.
     */
    @Test
    fun `two downloads at once each land in the destination chosen for them`() {
        coordinator.onDownloadNamed("blob:one", "first.txt")
        coordinator.onDownloadNamed("blob:two", "second.txt")
        coordinator.onDownloadStart("blob:one", null)
        coordinator.onDownloadStart("blob:two", null)

        assertEquals(listOf("first.txt"), asked,
            "a second picker cannot be told apart from the first, so it must not be opened")

        val first = destination("first")
        chooseDestination(first)
        val firstId = liveRequestId()
        coordinator.onBytes(firstId, encode("one"))
        coordinator.onComplete(firstId, null)

        assertEquals(listOf("first.txt", "second.txt"), asked,
            "the download that waited gets its turn, under its own name")
        val second = destination("second")
        chooseDestination(second)
        val secondId = liveRequestId()
        coordinator.onBytes(secondId, encode("two"))
        coordinator.onComplete(secondId, null)

        assertEquals("one", documents[first]!!.written.toString())
        assertEquals("two", documents[second]!!.written.toString())
        assertEquals(
            listOf(DownloadOutcome.SAVED to "first.txt", DownloadOutcome.SAVED to "second.txt"),
            reported,
        )
        assertEquals(emptyList<Uri>(), discarded, "neither file was left empty or removed")
    }

    /**
     * A queued download still gets a picker after the one ahead of it failed.
     *
     * The queue is drained from the paths that end a download, and the ones that
     * end badly are the easy half to forget. Forgetting them leaves the page
     * waiting on a file it will never be asked to read, with nothing said.
     */
    @Test
    fun `a download waiting behind a failed one still gets its turn`() {
        coordinator.onDownloadNamed("blob:two", "second.txt")
        val firstId = startAndChoose("blob:one", destination("first"))
        coordinator.onDownloadStart("blob:two", null)

        coordinator.onComplete(firstId, "the page could not read it")

        assertEquals(listOf(FALLBACK_DOWNLOAD_NAME, "second.txt"), asked)
        val second = destination("second")
        chooseDestination(second)
        coordinator.onBytes(liveRequestId(), encode("two"))
        coordinator.onComplete(liveRequestId(), null)
        assertEquals("two", documents[second]!!.written.toString())
    }

    /**
     * The picker outlives the download that opened it. A renderer crash drops
     * the request while the user is still choosing a folder, and their answer
     * arrives afterwards having already created the file.
     *
     * Taking it would fill a document named for a file the page can no longer
     * read; leaving it would leave that document empty in the user's folder.
     */
    @Test
    fun `a destination chosen for a download that is gone is not adopted`() {
        coordinator.onDownloadStart("blob:one", null)
        coordinator.onPageGone()

        val orphan = destination("orphan")
        chooseDestination(orphan)

        assertEquals(listOf(orphan), discarded)
        assertEquals(emptyList<Pair<String, String>>(), readRequests,
            "there is no page left to read anything")
        // And the next download is not locked out by the picker that answered
        // too late.
        coordinator.onDownloadStart("blob:two", null)
        assertEquals(2, asked.size)
    }

    /**
     * A result carrying an id that is not the one the picker was opened for.
     *
     * The download in flight is left exactly as it was, because its own answer
     * is still coming, and the document the stray result created is removed.
     */
    @Test
    fun `a destination that names another request never displaces the live one`() {
        coordinator.onDownloadStart("blob:one", null)
        val stray = destination("stray")

        coordinator.onDestinationChosen("dl-not-this-one", stray)

        assertEquals(listOf(stray), discarded)
        assertEquals(emptyList<Pair<DownloadOutcome, String>>(), reported)
        val target = destination("target")
        chooseDestination(target)
        val id = liveRequestId()
        coordinator.onBytes(id, encode("mine"))
        coordinator.onComplete(id, null)
        assertEquals("mine", documents[target]!!.written.toString(),
            "the download that was waiting still gets the destination it was promised")
    }

    /**
     * The queue is bounded, and the refusal is reported. What it protects is not
     * a size: every waiting download is a file the page is holding in memory for
     * it, so an unbounded queue is unbounded memory driven by page clicks.
     */
    @Test
    fun `downloads beyond what the queue holds are refused out loud`() {
        coordinator.onDownloadStart("blob:live", null)
        repeat(8) { coordinator.onDownloadStart("blob:queued$it", null) }

        coordinator.onDownloadStart("blob:overflow", null)

        assertEquals(listOf(DownloadOutcome.FAILED to FALLBACK_DOWNLOAD_NAME), reported,
            "a download that will never be started has to say so")
        assertEquals(1, asked.size, "and nothing ahead of it in the queue was disturbed")
    }

    /**
     * The bytes the page is left holding for a download that will never be read.
     *
     * The page pins a download's blob from the click that started it and keeps
     * it for the whole hold budget, because up to `MAX_QUEUED` pickers can stand
     * between that click and anyone asking for the bytes. Nothing else can free
     * it: the only reason the capture script gives up a hold on its own is that
     * the bytes are being read, and these three downloads are exactly the ones
     * that are never read. So a multi-select whose tail the queue turns away
     * pins every one of those files in the renderer for minutes after the user
     * was told they are not coming.
     *
     * NEGATIVE CONTROL for the three cases below, one each: delete
     * `host.releaseBytes(request.url)` from the `MAX_QUEUED` refusal in
     * `onDownloadStart`, from the cancelled branch of `onDestinationChosen`, or
     * from `fail`.
     */
    @Test
    fun `a download refused for being one too many lets go of its bytes`() {
        coordinator.onDownloadStart("blob:live", null)
        repeat(MAX_QUEUED) { coordinator.onDownloadStart("blob:queued$it", null) }

        coordinator.onDownloadStart("blob:overflow", null)

        assertEquals(listOf("blob:overflow"), released,
            "only the refused download is done with. The one at the picker and the " +
                "eight behind it are still going to be asked for their bytes, and a " +
                "hold released under them is a download that fails when its turn comes")
    }

    @Test
    fun `cancelling at the picker lets go of the bytes it was holding`() {
        coordinator.onDownloadStart("blob:x", null)

        chooseDestination(null)

        assertEquals(listOf("blob:x"), released,
            "the user said no, so nothing will ever read this blob and the page is " +
                "holding a file the download it belonged to is over")
    }

    @Test
    fun `a download that fails before it is read lets go of its bytes`() {
        destinationOpens = false
        coordinator.onDownloadStart("blob:x", null)

        chooseDestination(destination())

        assertEquals(listOf(DownloadOutcome.FAILED to FALLBACK_DOWNLOAD_NAME), reported,
            "the control: a failure that was not reported would satisfy the line below " +
                "by never having been a download at all")
        assertEquals(listOf("blob:x"), released,
            "the document would not open, so the page was never asked to read anything " +
                "and its hold has nothing left to wait for")
    }

    /**
     * Bytes belonging to a download that is already over.
     *
     * The page is told which request it is answering precisely so this can be
     * refused. The page reads on its own schedule and is only asked to stop
     * between pieces, so a download that failed here can still have pieces in
     * flight when the next one opens its file. Without the check they would
     * land in it, and the result would be a corrupt file reported as saved.
     */
    @Test
    fun `bytes from a stale request never reach the live file`() {
        val firstId = startAndChoose("blob:one", destination("first"))
        coordinator.onComplete(firstId, "the read failed")

        val second = destination("second")
        val secondId = startAndChoose("blob:two", second)

        assertEquals(false, coordinator.onBytes(firstId, encode("stale")),
            "an answer for a request that is over is refused")
        coordinator.onBytes(secondId, encode("fresh"))
        coordinator.onComplete(secondId, null)

        assertEquals("fresh", documents[second]!!.written.toString(),
            "only the live download's bytes reach its file")
    }

    /**
     * A second download started over one still being written.
     *
     * The first is not displaced. It has a document open and a page reading into
     * it, and the second has nothing yet, so the second waits: displacing here
     * was what left a file behind under the wrong name.
     */
    @Test
    fun `a second download waits rather than taking the first one's place`() {
        val first = destination("first")
        val firstId = startAndChoose("blob:one", first)
        coordinator.onBytes(firstId, encode("partial"))

        coordinator.onDownloadStart("blob:two", null)

        assertEquals(1, asked.size, "the second download has no picker yet")
        assertEquals(emptyList<Uri>(), discarded, "and the first one keeps the file it is filling")
        coordinator.onComplete(firstId, null)
        assertEquals("partial", documents[first]!!.written.toString())
        assertTrue(reported.contains(DownloadOutcome.SAVED to FALLBACK_DOWNLOAD_NAME))
    }

    /**
     * The renderer died under a download. The page that owed the bytes is gone,
     * so nothing will ever arrive, and the file has to go with it.
     *
     * Silent on purpose, and that is asserted rather than assumed: the user is
     * watching a crash recover, and a download toast on top of it explains
     * nothing about either event.
     */
    @Test
    fun `a page that goes away takes its unfinished file with it`() {
        val target = destination()
        val id = startAndChoose(target = target)
        coordinator.onBytes(id, encode("partial"))

        coordinator.onPageGone()

        assertEquals(listOf(target), discarded)
        assertEquals(emptyList<Pair<DownloadOutcome, String>>(), reported,
            "and the silence is only for the one in flight: with nothing queued behind " +
                "it there is nothing else to say, which is what stops the case below " +
                "from being satisfied by reporting everything")
    }

    /**
     * The queue behind a lost download, which is not the same case as the
     * download itself and does not get the same answer.
     *
     * The silence above is argued for a renderer crash: the user is watching a
     * recovery and a toast about a download explains neither event. That reason
     * does not reach the queue, and the placement of the clear extended it there
     * anyway. `onPageGone` runs on every finished main-frame load as well, so a
     * multi-select download crossing a folder switch is the ordinary way to
     * reach it: the user taps Download on five files, receives one, and hears
     * nothing at all about the other four.
     */
    @Test
    fun `downloads still waiting when the page goes are said out loud`() {
        coordinator.onDownloadNamed("blob:one", "first.txt")
        coordinator.onDownloadNamed("blob:two", "second.txt")
        coordinator.onDownloadNamed("blob:three", "third.txt")
        startAndChoose("blob:one", destination("first"))
        coordinator.onDownloadStart("blob:two", null)
        coordinator.onDownloadStart("blob:three", null)

        coordinator.onPageGone()

        assertEquals(
            listOf(DownloadOutcome.FAILED to "second.txt", DownloadOutcome.FAILED to "third.txt"),
            reported,
            "each download the user asked for and is not getting has to say so, in the " +
                "order they were asked for, and the one in flight stays silent",
        )
    }

    /**
     * And saying so is all that changes. A queued download never reached a
     * picker, so it owns no document, and discarding one for it would be
     * deleting a file that belongs to something else.
     */
    @Test
    fun `a queue reported as gone does not also leave documents behind`() {
        val target = destination("first")
        coordinator.onDownloadNamed("blob:two", "second.txt")
        startAndChoose("blob:one", target)
        coordinator.onDownloadStart("blob:two", null)

        coordinator.onPageGone()

        assertEquals(listOf(target), discarded,
            "only the download that had a document open loses one")
    }

    /**
     * A name reported by the page belongs to the one download that follows it.
     *
     * Held in a map keyed by URL, so leaving it there would let a later download
     * of the same URL that the page never named inherit this name. The editor
     * mints a fresh blob URL per download, so that reuse is not what the editor
     * does; it is what any other page in the WebView could do.
     */
    @Test
    fun `a reported name is used once and not inherited`() {
        coordinator.onDownloadNamed("blob:x", "report.pdf")

        coordinator.onDownloadStart("blob:x", null)
        chooseDestination(null)
        coordinator.onDownloadStart("blob:x", null)

        assertEquals(listOf("report.pdf", FALLBACK_DOWNLOAD_NAME), asked,
            "the second download of the same URL was never named by the page")
    }

    /**
     * The page reports a name; a real `Content-Disposition` header is the next
     * source down. Both arriving at once has to resolve to the page's, because
     * it is the only one that saw the anchor the user clicked.
     */
    @Test
    fun `the name the page reported outranks the header`() {
        coordinator.onDownloadNamed("blob:x", "chosen.txt")

        coordinator.onDownloadStart("blob:x", "attachment; filename=\"other.txt\"")

        assertEquals(listOf("chosen.txt"), asked)
    }

    /**
     * The page fills the name map and the platform empties it, so a page that
     * names clicks the platform never turns into downloads fills it alone.
     *
     * Asserted from the outside, because what the bound protects is not a size
     * but the absence of unbounded growth driven by page-controlled input. The
     * newest name still works, which is what stops a bound from being satisfied
     * by a map that forgets everything.
     */
    @Test
    fun `names the page reports for downloads that never start do not accumulate`() {
        repeat(64) { coordinator.onDownloadNamed("blob:$it", "file$it.txt") }

        coordinator.onDownloadStart("blob:0", null)
        chooseDestination(null)
        coordinator.onDownloadStart("blob:63", null)

        assertEquals(listOf(FALLBACK_DOWNLOAD_NAME, "file63.txt"), asked,
            "the oldest names are dropped while the newest one is still usable")
    }

    /** Exactly once, in the other direction: a repeated completion has nobody left to answer. */
    @Test
    fun `a download that completes twice is reported once`() {
        val id = startAndChoose()

        coordinator.onComplete(id, null)
        coordinator.onComplete(id, null)

        assertEquals(1, reported.size)
    }

    /** Bytes with nothing outstanding are refused rather than thrown on. */
    @Test
    fun `bytes with no download in flight are harmless`() {
        assertEquals(false, coordinator.onBytes("dl-1", encode("stray")))
        assertEquals(emptyList<Pair<DownloadOutcome, String>>(), reported)
    }

    /**
     * Text the page sends that is not base64 at all. It reaches the decoder
     * before anything else can judge it, and an unhandled throw there would
     * escape into the bridge thread with the document still open.
     */
    @Test
    fun `bytes that are not base64 fail the download rather than escaping`() {
        val target = destination()
        val id = startAndChoose(target = target)

        assertEquals(false, coordinator.onBytes(id, "not base64 at all!!"))

        assertEquals(listOf(target), discarded)
        assertEquals(listOf(DownloadOutcome.FAILED to FALLBACK_DOWNLOAD_NAME), reported)
    }

    /**
     * The file name is the page's to write, and every statement here that prints
     * one has to say so.
     *
     * `MainActivity`'s `DownloadHost.report` redacts the same value where it
     * reports the outcome, and the comment there says why: the page on the other
     * side of the bridge is the workbench, which holds the connection token, and
     * these lines are not gated on a debuggable build. The three sites in this
     * class hold the same string, so redacting only at the reporting site left it
     * in logcat by another route while the code read as if it were contained.
     *
     * All three paths are driven in one case because the value and the reason are
     * one: a guard covering two of them is the shape that let this through.
     */
    @Test
    fun `the name the page chose does not reach the log in the clear`() {
        val secret = "3f9a1c77-not-a-real-token"
        val named = "notes tkn=$secret.txt"

        // No picker opened for it.
        pickerOpens = false
        coordinator.onDownloadNamed("blob:a", named)
        coordinator.onDownloadStart("blob:a", null)

        // The page went away with a transfer in flight.
        pickerOpens = true
        coordinator.onDownloadNamed("blob:b", named)
        startAndChoose("blob:b")
        coordinator.onPageGone()

        // And one more download than the queue will hold.
        repeat(10) {
            coordinator.onDownloadNamed("blob:q$it", named)
            coordinator.onDownloadStart("blob:q$it", null)
        }

        listOf("No create-document picker started", "Abandoning the download", "Refusing")
            .forEach { site ->
                assertTrue(
                    warnings.any { it.contains(site) },
                    "no line was logged for \"$site\", so nothing about it was checked. " +
                        "Logged:\n" + warnings.joinToString("\n") { "  $it" },
                )
            }
        val leaks = warnings.filter { it.contains(secret) }
        assertTrue(
            leaks.isEmpty(),
            "a name the page chose reached a shipping log line in the clear:\n" +
                leaks.joinToString("\n") { "  $it" },
        )
        assertTrue(
            warnings.count { it.contains("tkn=<redacted>") } >= 3,
            "the name is not in the lines at all, so this case would pass on statements " +
                "that dropped it rather than ones that redacted it:\n" +
                warnings.joinToString("\n") { "  $it" },
        )
    }

    /** Binary content survives the encode and decode unchanged, byte for byte. */
    @Test
    fun `bytes outside the printable range arrive unchanged`() {
        val target = destination()
        val payload = ByteArray(256) { it.toByte() }
        val id = startAndChoose(target = target)

        coordinator.onBytes(id, Base64.getEncoder().encodeToString(payload))
        coordinator.onComplete(id, null)

        assertArrayEquals(payload, documents[target]!!.written.toByteArray())
    }
}
