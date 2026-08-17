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
 */
class DownloadCoordinatorTest {

    /** What the fake picker was asked to create, one entry per request. */
    private val asked = mutableListOf<String>()

    /** Documents the fake picker created and did not delete. */
    private val documents = mutableMapOf<Uri, RecordingStream>()

    /** Documents removed through [DownloadHost.discardDestination]. */
    private val discarded = mutableListOf<Uri>()

    /** Every outcome reported, in order. */
    private val reported = mutableListOf<Pair<DownloadOutcome, String>>()

    /** URLs the page was asked to read, paired with the request id. */
    private val readRequests = mutableListOf<Pair<String, String>>()

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
        override fun askDestination(fileName: String): Boolean {
            asked += fileName
            return pickerOpens
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

        override fun report(outcome: DownloadOutcome, fileName: String, detail: String?) {
            reported += outcome to fileName
        }
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        asked.clear()
        documents.clear()
        discarded.clear()
        reported.clear()
        readRequests.clear()
        pickerOpens = true
        destinationOpens = true
        writesFail = false
        closeFails = false
        coordinator = DownloadCoordinator(host)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun destination(name: String = "doc"): Uri = mockk<Uri>(relaxed = true, name = name)

    private fun encode(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    /** The id the page is told to answer under, for the request just started. */
    private fun liveRequestId(): String = readRequests.last().first

    /** Drives one whole download to the point where the page is reading it. */
    private fun startAndChoose(url: String = "blob:x", target: Uri = destination()): String {
        coordinator.onDownloadStart(url, null)
        coordinator.onDestinationChosen(target)
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

        coordinator.onDestinationChosen(null)

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

        coordinator.onDestinationChosen(target)

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
        coordinator.onDestinationChosen(destination())
        assertEquals(1, reported.size, "the abandoned request must not be revived by a stray result")
    }

    /**
     * Bytes belonging to a download that has already been displaced.
     *
     * The page is told which request it is answering precisely so this can be
     * refused. Without the check, a slow first download and a second one started
     * over it would interleave into the file the user is currently waiting for,
     * and the result would be a corrupt file reported as saved.
     */
    @Test
    fun `bytes from a stale request never reach the live file`() {
        val first = destination("first")
        val firstId = startAndChoose("blob:one", first)

        val second = destination("second")
        coordinator.onDownloadStart("blob:two", null)
        coordinator.onDestinationChosen(second)
        val secondId = liveRequestId()

        assertEquals(false, coordinator.onBytes(firstId, encode("stale")),
            "an answer for a request that is over is refused")
        coordinator.onBytes(secondId, encode("fresh"))
        coordinator.onComplete(secondId, null)

        assertEquals("fresh", documents[second]!!.written.toString(),
            "only the live download's bytes reach its file")
    }

    /**
     * Starting a second download over one still in flight. The first cannot be
     * kept: there is one picker result to claim and the second has just claimed
     * it. So its file has to go, and it must not be reported as saved.
     */
    @Test
    fun `a second download removes the file the first was filling`() {
        val first = destination("first")
        val firstId = startAndChoose("blob:one", first)
        coordinator.onBytes(firstId, encode("partial"))

        coordinator.onDownloadStart("blob:two", null)

        assertEquals(listOf(first), discarded, "the displaced download's file must not survive")
        assertTrue(reported.none { it.first == DownloadOutcome.SAVED },
            "a download that was cut short is not a saved one")
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
        assertEquals(emptyList<Pair<DownloadOutcome, String>>(), reported)
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
        coordinator.onDestinationChosen(null)
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
