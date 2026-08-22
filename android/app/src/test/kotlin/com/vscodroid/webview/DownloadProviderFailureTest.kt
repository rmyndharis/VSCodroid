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
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.Executor

/**
 * That what the provider throws ends the download and nothing else.
 *
 * Every call this class makes on a document is a binder call into another app,
 * and another app is free to be missing. A provider force-stopped, updated or
 * revoked between the picker answering and the document being opened answers
 * with `SecurityException` or `IllegalArgumentException`, neither of which is an
 * `IOException`; a provider whose process dies mid-transfer can raise either
 * from a write or from a close.
 *
 * Two different costs, and both are worse than the failed download they hide
 * inside. `onDestinationChosen` runs bare in the activity-result callback with
 * nothing above it to catch anything, so a throw there is the app disappearing
 * while the user watches a save. A throw out of `onBytes` or `onComplete` costs
 * something quieter and longer lived: those two give the claim on the stream
 * back on their way out, so an exception that skips that leaves the download
 * marked busy for ever. Every teardown then defers to a writer that is gone,
 * the document is never removed, and the queue behind it never moves again, so
 * the feature is dead for the life of the Activity.
 *
 * Each case therefore asserts the download failed *and* that the coordinator is
 * still able to take the next one.
 */
class DownloadProviderFailureTest {

    /** What the fake picker was asked to create, one entry per request. */
    private val asked = mutableListOf<String>()

    /** The request each of those pickers was opened for, in the same order. */
    private val pickerIds = mutableListOf<String>()

    /** Documents removed through [DownloadHost.discardDestination]. */
    private val discarded = mutableListOf<Uri>()

    /** Every outcome reported, in order. */
    private val reported = mutableListOf<Pair<DownloadOutcome, String>>()

    /** URLs the page was asked to read, paired with the request id. */
    private val readRequests = mutableListOf<Pair<String, String>>()

    /** What [DownloadHost.openDestination] throws, or null to answer a stream. */
    private var openThrows: Exception? = null

    /** What a write to the opened document throws, or null to accept it. */
    private var writeThrows: Exception? = null

    /** What closing the opened document throws, or null to accept it. */
    private var closeThrows: Exception? = null

    private lateinit var coordinator: DownloadCoordinator

    private inner class FailingStream : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            writeThrows?.let { throw it }
        }

        override fun close() {
            closeThrows?.let { throw it }
        }
    }

    private val host = object : DownloadHost {
        override fun askDestination(requestId: String, fileName: String) {
            asked += fileName
            pickerIds += requestId
        }

        override fun openDestination(destination: Uri): OutputStream {
            openThrows?.let { throw it }
            return FailingStream()
        }

        override fun discardDestination(destination: Uri) {
            discarded += destination
        }

        override fun requestBytes(requestId: String, url: String) {
            readRequests += requestId to url
        }

        override fun releaseBytes(url: String) = Unit

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
        // Driven where it is handed over, so a document discarded by the
        // teardown has been discarded by the time a case reads the list.
        // DownloadWriteBlockingTest owns the question of which thread it runs on.
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

    /** Starts a download and answers its picker with [target]. */
    private fun startAndChoose(target: Uri, url: String = "blob:x"): String {
        coordinator.onDownloadStart(url, null)
        coordinator.onDestinationChosen(pickerIds.last(), target)
        return pickerIds.last()
    }

    /**
     * NEGATIVE CONTROL: narrow the catch around `host.openDestination` in
     * `onDestinationChosen` back to `catch (e: IOException)`. The
     * `SecurityException` below then leaves the coordinator, and this case fails
     * carrying it, which is what the user gets in the activity-result callback
     * as well: no toast, no download, no app.
     */
    @Test
    fun `a provider that refuses to open the document fails the download, not the app`() {
        val target = destination()
        openThrows = SecurityException("Permission Denial: opening provider")

        startAndChoose(target)

        assertEquals(
            listOf(DownloadOutcome.FAILED to FALLBACK_DOWNLOAD_NAME), reported,
            "a download that cannot be opened has to be reported like any other failure",
        )
        assertEquals(
            listOf(target), discarded,
            "the picker already created the file, so it is sitting in the user's folder " +
                "empty and wearing the name of the one they wanted",
        )
        assertEquals(emptyList<Pair<String, String>>(), readRequests)
    }

    /**
     * NEGATIVE CONTROL: narrow the catch around `chunk.stream.write` in
     * `onBytes` back to `catch (e: IOException)`. The `IllegalStateException`
     * then escapes before `request.busy` is cleared, so this case fails carrying
     * it, and the two assertions after the throw are the ones that describe what
     * that costs.
     */
    @Test
    fun `a write that throws something other than IOException gives the claim back`() {
        val target = destination()
        writeThrows = IllegalStateException("the provider process is gone")

        val id = startAndChoose(target)
        val accepted = coordinator.onBytes(id, encode("payload"))

        assertFalse(accepted, "the page has to be told to stop reading a download that failed")
        assertEquals(listOf(DownloadOutcome.FAILED to FALLBACK_DOWNLOAD_NAME), reported)
        assertEquals(listOf(target), discarded, "and the file it was filling goes with it")

        coordinator.onDownloadStart("blob:next", null)
        assertEquals(
            2, asked.size,
            "the next download never got a picker, so the claim was never given back: " +
                "every teardown from here defers to a writer that is gone and every " +
                "later download queues behind a download that can never end",
        )
    }

    /**
     * NEGATIVE CONTROL: narrow the catch around `request.stream?.close()` in
     * `onComplete` back to `catch (e: IOException)`, with the same shape of
     * failure as the case above.
     */
    @Test
    fun `a close that throws something other than IOException gives the claim back`() {
        val target = destination()
        closeThrows = SecurityException("the grant was revoked")

        val id = startAndChoose(target)
        coordinator.onBytes(id, encode("payload"))
        coordinator.onComplete(id, null)

        assertEquals(
            listOf(DownloadOutcome.FAILED to FALLBACK_DOWNLOAD_NAME), reported,
            "the close is what commits the bytes, so a close that failed is a file with " +
                "a hole in it and must never be reported as a save",
        )
        assertEquals(listOf(target), discarded)

        coordinator.onDownloadStart("blob:next", null)
        assertEquals(2, asked.size, "the claim taken for the close was never given back")
    }

    /**
     * The control for all three: nothing about this fake refuses downloads by
     * itself, so a coordinator that failed every one of them would satisfy the
     * cases above without any provider throwing anything.
     */
    @Test
    fun `a provider that throws nothing saves the file`() {
        val target = destination()

        val id = startAndChoose(target)
        coordinator.onBytes(id, encode("payload"))
        coordinator.onComplete(id, null)

        assertEquals(listOf(DownloadOutcome.SAVED to FALLBACK_DOWNLOAD_NAME), reported)
        assertTrue(discarded.isEmpty(), "a completed download keeps its file")
    }
}
