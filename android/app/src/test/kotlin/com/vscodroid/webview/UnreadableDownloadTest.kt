package com.vscodroid.webview

import android.net.Uri
import com.vscodroid.SourceScan
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
import java.util.concurrent.Executor

/**
 * That a download the editor page cannot read is refused before the picker opens.
 *
 * The bytes of every download come from the page: Android hands the coordinator a URL, the page
 * is asked to read it and pushes the bytes back. The workbench reads a blob it made itself off
 * the object it holds and fetches anything else, under a policy that lists neither `blob:` nor
 * `data:` and leaves another origin to that origin's CORS answer. So a blob a webview made, a
 * data URL or a file on a remote site reached the picker, the person named a file, and then read
 * that it was not saved while the document they had just created was deleted again. A remote page
 * framed in the editor could start one after another, each with its own picker.
 */
class UnreadableDownloadTest {

    private val port = 5000

    private val asked = mutableListOf<String>()
    private val pickerIds = mutableListOf<String>()
    private val reported = mutableListOf<Pair<DownloadOutcome, String>>()
    private val readRequests = mutableListOf<String>()
    private var now = 1_000_000L
    private lateinit var coordinator: DownloadCoordinator

    private val host = object : DownloadHost {
        override fun askDestination(requestId: String, fileName: String) {
            asked += fileName
            pickerIds += requestId
        }

        override fun measureDestination(destination: Uri) = DocumentMeasure(length = 0L)

        override fun openDestination(destination: Uri, mode: String) = OpenDocument(ByteArrayOutputStream())

        override fun discardDestination(destination: Uri) = Unit

        override fun requestBytes(requestId: String, url: String) {
            readRequests += url
        }

        override fun releaseBytes(url: String) = Unit

        override fun report(outcome: DownloadOutcome, fileName: String, detail: String?) {
            reported += outcome to fileName
        }

        override fun pageCanRead(url: String) = pageCanReadDownload(url, port)
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        coordinator = DownloadCoordinator(
            host,
            providerWork = Executor { it.run() },
            mainThread = Executor { it.run() },
            monotonicMs = { now },
        )
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `the page reads a blob it made and a file of its own origin`() {
        for (url in listOf(
            "blob:http://127.0.0.1:5000/6f1d2c30-9a4b-4c1e-8f77-2b0a5d3e91cc",
            "http://127.0.0.1:5000/vscode-remote-resource?path=%2Fhome%2Fu%2Freport.txt",
            // Left as Chromium leaves a query: a parser that refuses the space or the bar
            // would refuse a download that works.
            "http://127.0.0.1:5000/vscode-remote-resource?path=/home/u/a b|c.txt",
            // The interceptor answers the editor's origin there with CORS.
            "https://file+.vscode-resource.vscode-cdn.net/data/user/0/com.vscodroid/files/home/x.zip",
        )) {
            assertTrue(pageCanReadDownload(url, port), url)
        }
    }

    @Test
    fun `the page cannot read another frame's blob, a data URL or another origin`() {
        for (url in listOf(
            "blob:https://abc.vscode-cdn.net/u",
            "blob:null/6f1d2c30",
            "blob:http://127.0.0.1:5001/u",
            "http://127.0.0.1:5001/x",
            "http://127.0.0.1:50000/x",
            "http://127.0.0.1:5000.example.com/x",
            "http://127.0.0.1:5000@example.com/x",
            "http://localhost:5000/x",
            "https://127.0.0.1:5000/x",
            "data:text/plain;base64,aGk=",
            "https://example.com/file.zip",
            "https://abc.vscode-cdn.net/x.zip",
            "https://vscode-resource.vscode-cdn.net.example.com/x",
            "https://a.vscode-resource.vscode-cdn.net@example.com/x",
            "https://a.vscode-resource.vscode-cdn.net:444/x",
            "http://a.vscode-resource.vscode-cdn.net/x",
            "filesystem:http://127.0.0.1:5000/temporary/x",
        )) {
            assertFalse(pageCanReadDownload(url, port), url)
        }
        assertFalse(
            pageCanReadDownload("blob:http://127.0.0.1:0/u", 0),
            "with no editor page there is nothing to read a download",
        )
    }

    @Test
    fun `a download the page cannot read never opens the picker`() {
        coordinator.onDownloadNamed("blob:https://abc.vscode-cdn.net/u", "plot.png")
        coordinator.onDownloadStart("blob:https://abc.vscode-cdn.net/u", null)

        assertEquals(emptyList<String>(), asked, "the picker opened for a download nothing can read")
        assertEquals(emptyList<String>(), readRequests)
        assertEquals(
            listOf(DownloadOutcome.FAILED to "plot.png"), reported,
            "the refusal is said, under the name the page gave it",
        )
    }

    @Test
    fun `a run of refusals is said once per interval`() {
        coordinator.onDownloadStart("data:text/plain;base64,aGk=", null)
        now += UNREADABLE_NOTICE_MS - 1
        coordinator.onDownloadStart("https://example.com/a.zip", null)
        coordinator.onDownloadStart("https://example.com/b.zip", null)

        assertEquals(1, reported.size, "a page starting downloads in a loop puts a notice on screen for each: $reported")
        assertEquals(emptyList<String>(), asked)

        now += 1
        coordinator.onDownloadStart("https://example.com/c.zip", null)
        assertEquals(
            listOf(DownloadOutcome.FAILED to "download", DownloadOutcome.FAILED to "c.zip"), reported,
            "a refusal a whole interval later is not said",
        )
    }

    @Test
    fun `downloads the page can read still reach the picker`() {
        coordinator.onDownloadNamed("blob:http://127.0.0.1:5000/u1", "App.kt")
        coordinator.onDownloadStart("blob:http://127.0.0.1:5000/u1", null)
        assertEquals(listOf("App.kt"), asked)
        coordinator.onDestinationChosen(pickerIds.last(), mockk(relaxed = true))
        coordinator.onComplete(pickerIdsRead(), null)

        coordinator.onDownloadStart("http://127.0.0.1:5000/vscode-remote-resource?path=%2Fhome%2Fu%2Freport.txt", null)
        assertEquals(listOf("App.kt", "report.txt"), asked)
        assertEquals(listOf(DownloadOutcome.SAVED to "App.kt"), reported)
    }

    @Test
    fun `a refusal leaves the download in flight and the queue alone`() {
        coordinator.onDownloadStart("blob:http://127.0.0.1:5000/first", null)
        coordinator.onDownloadStart("blob:http://127.0.0.1:5000/second", null)
        coordinator.onDownloadStart("blob:https://abc.vscode-cdn.net/u", null)

        assertEquals(listOf("download"), asked, "only the first download has its picker")
        coordinator.onDestinationChosen(pickerIds.last(), mockk(relaxed = true))
        assertEquals(listOf("blob:http://127.0.0.1:5000/first"), readRequests)
        coordinator.onComplete(pickerIdsRead(), null)
        assertEquals(2, asked.size, "the download queued behind the first one lost its turn")
        assertEquals(
            listOf(DownloadOutcome.FAILED to "download", DownloadOutcome.SAVED to "download"), reported,
        )
    }

    /** The request id the page was last asked to read under: the coordinator numbers them. */
    private fun pickerIdsRead(): String = pickerIds.last()

    /**
     * Read off the source, because the host is an anonymous object inside the Activity: that the
     * Activity answers with the rule above for the port the editor page was loaded from.
     */
    @Test
    fun `the editor answers with the rule for its own port`() {
        val main = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))
        val downloads = main.substringAfter("private val downloads: DownloadCoordinator = DownloadCoordinator(", "")
        assertTrue(downloads.isNotEmpty(), "the download coordinator is gone from MainActivity.kt")
        assertTrue(
            Regex("""override fun pageCanRead\(url: String\)(: Boolean)? = pageCanReadDownload\(url, serverPort\)""")
                .containsMatchIn(downloads.substringBefore("private val serviceConnection")),
            "the editor's download host does not refuse what its page cannot read, so the picker opens for a " +
                "download that is then not saved",
        )
    }
}
