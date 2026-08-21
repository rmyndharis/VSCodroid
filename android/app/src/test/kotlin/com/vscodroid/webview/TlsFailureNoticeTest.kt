package com.vscodroid.webview

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * What the WebView says when it refuses a certificate.
 *
 * It said nothing at all. `onReceivedSslError` had no override, so the platform
 * default ran, and the javadoc describes that default in one line: cancel the
 * resource load. Nothing else in the app turns a failed load into anything a user
 * can see, because the only page-level failure state is driven by the server's
 * startup and not by a load error. So a dev server previewed in the Simple Browser
 * over https with a self-signed certificate came up as an empty tab, and the
 * developer had nothing to distinguish that from a dev server that was not running.
 *
 * The other half was worse: `onReceivedSslError` is reached only for RECOVERABLE
 * certificate errors, and the javadoc says a non-recoverable one is delivered to
 * `onReceivedError` with `ERROR_FAILED_SSL_HANDSHAKE`. That callback gated its one
 * log line on `isForMainFrame`, and every https load in this app is a subframe or
 * a subresource, since the workbench itself is plain http on loopback. That half
 * reached no channel at all, not even logcat.
 *
 * The rule these cases pin is report, never proceed. Two of them exist to keep it
 * that way rather than to describe it: nothing may call `proceed()`, and an
 * ordinary load error must stay silent, which is what keeps the notice from
 * becoming a toast on every offline fetch and every connection-refused page.
 *
 * `SslError` and `SslErrorHandler` are mocked because the stub `android.jar`
 * answers neither. What is NOT mocked is every decision under test: the real
 * `tlsHostLabel` parses, the real `tlsReasonOf` maps and the real
 * `tlsFailureToAnnounce` deduplicates.
 */
class TlsFailureNoticeTest {

    private lateinit var view: WebView
    private lateinit var client: VSCodroidWebViewClient

    /** Every refusal the client announced, in order. */
    private val announced = mutableListOf<TlsFailure>()

    @BeforeEach
    fun setUp() {
        announced.clear()
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        view = mockk(relaxed = true)

        client = VSCodroidWebViewClient(
            allowedPort = 13337,
            resourceRoots = emptyList(),
            sensitiveLocations = emptyList(),
            openFolder = { null },
            connectionToken = { null },
            onCrash = {},
            onPageLoaded = {},
            onRetryServer = {},
            onTlsFailure = { announced += it },
        )
    }

    private fun sslError(url: String, primary: Int): SslError {
        val error = mockk<SslError>(relaxed = true)
        every { error.url } returns url
        every { error.primaryError } returns primary
        return error
    }

    private fun loadError(errorCode: Int, url: String, fromMainFrame: Boolean): Pair<WebResourceRequest, WebResourceError> {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.toString() } returns url
        val request = mockk<WebResourceRequest>(relaxed = true)
        every { request.url } returns uri
        // Stated rather than left to the relaxed mock, which answers false and
        // would quietly make every case here a subframe case.
        every { request.isForMainFrame } returns fromMainFrame
        val error = mockk<WebResourceError>(relaxed = true)
        every { error.errorCode } returns errorCode
        every { error.description } returns "load failed"
        return request to error
    }

    /**
     * The contract this override newly depends on: exactly one of `cancel()` and
     * `proceed()` per handler.
     *
     * An override that returns having called neither leaves that request's
     * certificate decision outstanding for ever, which is a worse silence than the
     * one being closed. `cancel()` is therefore the first statement, with nothing
     * above it that can throw.
     *
     * The `proceed()` half is a durable guard rather than a note in a review.
     * Proceeding would trust a certificate nothing validated, which is what the
     * WebView javadoc tells applications never to do and what Google Play's policy
     * refuses outright.
     *
     * The announcement assertion is the sentinel. Without it a fixture that never
     * entered the method at all would satisfy `exactly = 0`.
     */
    @Test
    fun `the handler is cancelled and never proceeded`() {
        val handler = mockk<SslErrorHandler>(relaxed = true)

        client.onReceivedSslError(view, handler, sslError("https://192.168.1.50:5173/", SslError.SSL_UNTRUSTED))

        verify(exactly = 1) { handler.cancel() }
        verify(exactly = 0) { handler.proceed() }
        assertEquals(
            1, announced.size,
            "the method has to have run at all for the proceed() assertion above to mean anything",
        )
    }

    /**
     * The whole path, once: the address the WebView refused becomes a host and a
     * reason, and nothing more of it survives.
     *
     * The address is deliberately absent from what is reported. A dev server URL
     * can carry an OAuth code or an API key in its query, and this value reaches
     * both a toast and logcat.
     */
    @Test
    fun `the reported failure names the host and the reason`() {
        client.onReceivedSslError(
            view,
            mockk<SslErrorHandler>(relaxed = true),
            sslError("https://192.168.1.50:5173/preview?token=s3cret", SslError.SSL_UNTRUSTED),
        )

        assertEquals(
            listOf(TlsFailure("192.168.1.50:5173", TlsFailureReason.UNTRUSTED)),
            announced,
        )
    }

    /**
     * Every code the WebView can actually produce reaches a distinct reason.
     *
     * `SslError.SslErrorFromChromiumErrorCode` builds errors with only four of the
     * six codes, so those four are what has to be right. Collapsing them to one
     * reason would tell a developer with an expired certificate that it is
     * untrusted, and send them to install a CA that would not have helped.
     */
    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource(
        "3, UNTRUSTED",   // SSL_UNTRUSTED
        "2, HOSTNAME",    // SSL_IDMISMATCH
        "4, DATE",        // SSL_DATE_INVALID
        "5, INVALID",     // SSL_INVALID
        "1, DATE",        // SSL_EXPIRED, legacy on this path
        "0, DATE",        // SSL_NOTYETVALID, legacy on this path
        "-1, INVALID",    // nothing the platform defines
    )
    fun `every SslError code the WebView produces maps to a reason`(code: Int, expected: TlsFailureReason) {
        assertEquals(expected, tlsReasonOf(code))
    }

    /**
     * The host label, over the real parse and with no mocks at all.
     *
     * This is the only new parsing in the change and it runs on input a remote page
     * chooses, so its failure modes are worth stating one at a time. The default
     * port has to disappear rather than print as -1, and the two shapes of
     * unreadable address arrive by different routes: one returns a null host, the
     * other throws.
     */
    @Test
    fun `the host label carries a non-default port and nothing else`() {
        assertEquals("192.168.1.50:5173", tlsHostLabel("https://192.168.1.50:5173/x"))
        assertEquals(
            "dev.example.com", tlsHostLabel("https://dev.example.com/x"),
            "a default port must not be printed; URI.getPort() answers -1 for it",
        )
        assertNull(tlsHostLabel(""), "two of SslError's constructors set the url to an empty string")
        assertNull(tlsHostLabel("nonsense"), "a bare word parses without throwing and has no host")
        assertNull(tlsHostLabel("not a url"), "a space makes URI throw rather than answer null")
        assertNull(tlsHostLabel(null))
    }

    /**
     * One page failing many requests to one host is one message.
     *
     * Toasts stack rather than replace, so a markdown preview pulling a dozen
     * images from a host with a self-signed certificate would hold the bottom of
     * the editor for the better part of a minute.
     */
    @Test
    fun `the same failure twice is announced once`() {
        val said = mutableSetOf<TlsFailure>()
        val failure = TlsFailure("dev.example.com", TlsFailureReason.UNTRUSTED)

        assertEquals(failure, tlsFailureToAnnounce(failure, said))
        assertNull(
            tlsFailureToAnnounce(failure, said),
            "a second image from the same host adds nothing the user can act on",
        )
    }

    /**
     * The half that makes the rule above a filter rather than a mute.
     *
     * Keyed on host and reason together: a second host is a second fact, and so is
     * the same host failing a different way, since the two need different answers.
     */
    @Test
    fun `a different host or a different reason is still announced`() {
        val said = mutableSetOf<TlsFailure>()
        tlsFailureToAnnounce(TlsFailure("dev.example.com", TlsFailureReason.UNTRUSTED), said)

        assertEquals(
            TlsFailure("other.example.com", TlsFailureReason.UNTRUSTED),
            tlsFailureToAnnounce(TlsFailure("other.example.com", TlsFailureReason.UNTRUSTED), said),
            "a different host is a fact the user has not been told",
        )
        assertEquals(
            TlsFailure("dev.example.com", TlsFailureReason.DATE),
            tlsFailureToAnnounce(TlsFailure("dev.example.com", TlsFailureReason.DATE), said),
            "the same host failing a different way needs a different answer from the reader",
        )
    }

    /**
     * Neither the record nor the number of toasts can grow without bound.
     *
     * The hosts are chosen by whatever page is open, including a remote site in the
     * bundled simple browser, so that page picks the size of the set and the number
     * of messages alike. Clearing the set at the cap answered only the first: every
     * fresh hostname was a fact never told before and got its own toast, and the
     * clear also handed back the hosts already announced.
     *
     * Driven with far more distinct hosts than the cap, because at or just past it
     * the two rules agree and the case would pass either way.
     */
    @Test
    fun `the number of messages is bounded, not only the record`() {
        val said = mutableSetOf<TlsFailure>()
        val shown = mutableListOf<TlsFailure>()
        val first = TlsFailure("host0.example.com", TlsFailureReason.UNTRUSTED)

        for (i in 0 until MAX_TLS_FAILURES_ANNOUNCED * 4) {
            tlsFailureToAnnounce(
                TlsFailure("host$i.example.com", TlsFailureReason.UNTRUSTED), said,
            )?.let { shown += it }
        }

        assertEquals(
            MAX_TLS_FAILURES_ANNOUNCED, shown.size,
            "a page minting fresh hostnames got a toast for each one, and every toast " +
                "holds the bottom of the editor for about three and a half seconds",
        )
        assertEquals(
            first, shown.first(),
            "the messages that got through are the first ones, which is what makes the " +
                "count above a cap rather than a mute that started somewhere else",
        )
        assertNull(
            tlsFailureToAnnounce(first, said),
            "a host already announced was announced a second time, so the record was " +
                "forgotten rather than closed",
        )
        assertTrue(
            said.size <= MAX_TLS_FAILURES_ANNOUNCED,
            "the record grew past the cap on hosts a remote page chooses: ${said.size}",
        )
    }

    /**
     * The non-recoverable half of TLS failure, which reached nothing at all.
     *
     * `onReceivedSslError` is never called for it. It arrives here instead, and the
     * `isForMainFrame` gate below it dropped it: the workbench is plain http on
     * loopback, so every https load in this app is a subframe or a subresource.
     *
     * The subframe is what the case drives, deliberately. Gating the new branch on
     * `isForMainFrame` would restore the whole defect while a main-frame case
     * stayed green.
     */
    @Test
    fun `a TLS handshake failure in a subframe is reported`() {
        val (request, error) = loadError(
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, "https://dev.example.com:8443/", fromMainFrame = false
        )

        client.onReceivedError(view, request, error)

        assertEquals(
            listOf(TlsFailure("dev.example.com:8443", TlsFailureReason.HANDSHAKE)),
            announced,
        )
    }

    /**
     * The control, and the one that keeps this from fighting the server-gave-up
     * page.
     *
     * A connection refused on loopback is what the gave-up page owns, and it is
     * `ERROR_CONNECT`, not a handshake failure. Widening the branch to all error
     * codes would turn every offline fetch and every connection-refused page into a
     * toast, and without this case nothing else in the suite would object.
     */
    @Test
    fun `an ordinary load error is not announced`() {
        val (request, error) = loadError(
            WebViewClient.ERROR_CONNECT, "http://127.0.0.1:13337/", fromMainFrame = true
        )

        client.onReceivedError(view, request, error)

        assertTrue(announced.isEmpty(), "announced $announced for a plain connection failure")
    }

    /**
     * The existing main-frame log line survives a handshake failure.
     *
     * The new branch has no early return for exactly this reason: the branch sits
     * above the gate, so a main-frame handshake failure produces both. An early
     * return would delete the one record the page-load path has always had.
     */
    @Test
    fun `a main-frame handshake failure is both announced and logged`() {
        val (request, error) = loadError(
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, "https://dev.example.com/", fromMainFrame = true
        )

        client.onReceivedError(view, request, error)

        assertEquals(
            listOf(TlsFailure("dev.example.com", TlsFailureReason.HANDSHAKE)),
            announced,
        )
        verify { Logger.e("WebViewClient", match { it.startsWith("Page load error:") }, null) }
    }
}
