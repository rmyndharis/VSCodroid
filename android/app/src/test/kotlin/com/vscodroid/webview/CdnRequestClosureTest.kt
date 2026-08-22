package com.vscodroid.webview

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * That a request naming a CDN host is answered here and never on the network.
 *
 * `null` from `shouldInterceptRequest` is not "nothing to do": it is the
 * documented instruction to the WebView to load the resource itself. The
 * workbench's asset URLs are hardcoded to `*.vscode-cdn.net` in `workbench.js`
 * and cannot be pointed elsewhere through `product.json`, so this interception
 * is the only thing between them and a DNS lookup plus a TLS connection to a
 * host this app is built not to contact.
 *
 * Three paths past the host test used to answer null, and the one that matters
 * is not exotic: the proxy returns null whenever the local server does not
 * answer, which is every request the page makes during the seconds around a
 * server restart. The asset is lost either way; what the null added was an
 * attempt to fetch it from the real CDN, invisible except for one warning.
 *
 * `WebResourceResponse` cannot be constructed under the stub `android.jar`, so
 * its constructor is mocked purely to let the function run to its end. Nothing
 * is asserted about it: non-null means a branch that builds a response was
 * reached, which is exactly the question here.
 */
class CdnRequestClosureTest {

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs

        mockkConstructor(WebResourceResponse::class)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** A port nothing is listening on, so the proxy fails the way a restart makes it fail. */
    private fun deadPort(): Int =
        ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun request(host: String, path: String?, query: String? = null): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns host
        every { uri.path } returns path
        every { uri.query } returns query
        every { uri.toString() } returns
            "https://$host${path ?: ""}" + if (query != null) "?$query" else ""
        val request = mockk<WebResourceRequest>(relaxed = true)
        every { request.url } returns uri
        every { request.method } returns "GET"
        return request
    }

    private fun intercept(request: WebResourceRequest, port: Int) =
        VSCodroidWebViewClient.interceptCdnRequest(
            request, port, null, emptyList(), emptyList(), { null }
        )

    /**
     * The live one. A workbench asset requested while the server is down, which
     * is what the watchdog's restart looks like from the page's side.
     */
    @Test
    fun `a static asset is answered here when the local server is not listening`() {
        assertNotNull(
            intercept(
                request("abc123.vscode-cdn.net", "/stable/deadbeef/out/vs/workbench/workbench.js"),
                deadPort(),
            ),
            "a failed proxy handed the request back to the WebView, which then fetches it " +
                "from the real CDN over the device's network",
        )
    }

    /** A path with nothing to split on, which the rewrite cannot turn into a local URL. */
    @Test
    fun `a CDN path too short to rewrite is answered here`() {
        assertNotNull(
            intercept(request("abc123.vscode-cdn.net", "/onlyonesegment"), 41234),
            "an unrewritable CDN URL was handed back to the WebView to fetch",
        )
    }

    /** And an address with no path at all, which is the third null. */
    @Test
    fun `a CDN request carrying no path is answered here`() {
        assertNotNull(
            intercept(request("abc123.vscode-cdn.net", null), 41234),
            "a CDN URL with no path was handed back to the WebView to fetch",
        )
    }

    /**
     * The control, and without it the three above are satisfied by a function
     * that answers everything.
     *
     * Every request the workbench makes to its own server arrives here too, and
     * those must be left alone: answering them would put a 404 in front of the
     * editor rather than the page it asked for.
     */
    @Test
    fun `a request that names no CDN host is left to the WebView`() {
        assertNull(
            intercept(request("127.0.0.1", "/stable-deadbeef/static/out/workbench.js"), 41234),
            "the interception answered a request for the app's own server",
        )
    }
}
