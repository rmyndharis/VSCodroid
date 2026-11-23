package com.vscodroid.webview

import android.net.Uri
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.vscodroid.service.mockEditorGateway
import com.vscodroid.util.Logger
import io.mockk.ConstantMatcher
import io.mockk.EqMatcher
import io.mockk.FunctionMatcher
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Cross-port loopback isolation for `127.0.0.1:<otherPort>`.
 *
 * RFC 6265 cookies are port-agnostic per host, so cookies scoped to `127.0.0.1`
 * (including `vscode-tkn`) would be sent to any other port on `127.0.0.1` and
 * exposed to `document.cookie` inside an `allow-same-origin` iframe. Android's
 * `WebResourceResponse` throws `IllegalArgumentException` for any 3xx status
 * code, so safe subframe `http` `GET` document navigations return a `200 OK`
 * static meta-refresh document (`Content-Security-Policy: default-src 'none'`)
 * moving the frame to `http://localhost:<port>`, while every other cross-port
 * `127.0.0.1` request fails closed with `403 Forbidden`.
 */
class CrossPortLoopbackIsolationTest {

    private val view = mockk<WebView>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockEditorGateway().bind(EDITOR_PORT)
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkConstructor(WebResourceResponse::class)
        stubResponses()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun anything() = ConstantMatcher<Any>(true)

    private fun stubResponses() {
        every { anyConstructed<WebResourceResponse>().statusCode } returns -1
        every { anyConstructed<WebResourceResponse>().reasonPhrase } returns "OTHER"
        every { anyConstructed<WebResourceResponse>().mimeType } returns ""
        every { anyConstructed<WebResourceResponse>().responseHeaders } returns emptyMap()

        // Android's WebResourceResponse rejects [300, 399] with IllegalArgumentException.
        val redirectRangeMatcher = FunctionMatcher<Int>({ it in 300..399 }, Int::class)
        every {
            constructedWith<WebResourceResponse>(
                anything(),
                anything(),
                redirectRangeMatcher,
                anything(),
                anything(),
                anything(),
            ).statusCode
        } throws IllegalArgumentException("statusCode can't be in the [300, 399] range.")
        every {
            constructedWith<WebResourceResponse>(
                anything(),
                anything(),
                redirectRangeMatcher,
                anything(),
                anything(),
                anything(),
            ).reasonPhrase
        } throws IllegalArgumentException("statusCode can't be in the [300, 399] range.")

        // 403 Forbidden with Cache-Control: no-store and empty body.
        val emptyBodyMatcher = FunctionMatcher<InputStream>(
            { stream ->
                stream.mark(16)
                val bytes = stream.readBytes()
                stream.reset()
                bytes.isEmpty()
            },
            InputStream::class,
        )
        every {
            constructedWith<WebResourceResponse>(
                EqMatcher("text/plain"),
                EqMatcher("utf-8"),
                EqMatcher(403),
                EqMatcher("Forbidden"),
                EqMatcher(mapOf("Cache-Control" to "no-store")),
                emptyBodyMatcher,
            ).statusCode
        } returns 403
        every {
            constructedWith<WebResourceResponse>(
                EqMatcher("text/plain"),
                EqMatcher("utf-8"),
                EqMatcher(403),
                EqMatcher("Forbidden"),
                EqMatcher(mapOf("Cache-Control" to "no-store")),
                emptyBodyMatcher,
            ).reasonPhrase
        } returns "Forbidden"
    }

    private fun expectRedirectDocument(expectedTargetUrlEscaped: String) {
        val expectedBody = "<!doctype html><meta http-equiv=\"refresh\" content=\"0;url=$expectedTargetUrlEscaped\">"
        val expectedHeaders = mapOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to "default-src 'none'",
            "Referrer-Policy" to "no-referrer",
            "X-Content-Type-Options" to "nosniff",
        )
        val bodyMatcher = FunctionMatcher<InputStream>(
            { stream ->
                stream.mark(4096)
                val text = String(stream.readBytes(), StandardCharsets.UTF_8)
                stream.reset()
                text == expectedBody
            },
            InputStream::class,
        )
        every {
            constructedWith<WebResourceResponse>(
                EqMatcher("text/html"),
                EqMatcher("utf-8"),
                EqMatcher(200),
                EqMatcher("OK"),
                EqMatcher(expectedHeaders),
                bodyMatcher,
            ).statusCode
        } returns 200
        every {
            constructedWith<WebResourceResponse>(
                EqMatcher("text/html"),
                EqMatcher("utf-8"),
                EqMatcher(200),
                EqMatcher("OK"),
                EqMatcher(expectedHeaders),
                bodyMatcher,
            ).reasonPhrase
        } returns "REDIRECT_DOC_OK"
    }

    private fun client(port: Int = EDITOR_PORT) = VSCodroidWebViewClient(
        allowedPort = { port },
        resourceRules = noResourceRules(),
        openFolder = { null },
        connectionToken = { null },
        onCrash = {},
        onPageLoaded = {},
        onRetryServer = {},
    )

    private fun serviceWorkerClient(port: Int? = EDITOR_PORT): ServiceWorkerClient {
        val controller = mockk<ServiceWorkerController>(relaxed = true)
        val registered = slot<ServiceWorkerClient>()
        every { controller.setServiceWorkerClient(capture(registered)) } just Runs
        mockkStatic(ServiceWorkerController::class)
        every { ServiceWorkerController.getInstance() } returns controller
        mockkConstructor(ServiceWorkerClient::class)
        VSCodroidWebViewClient.setupServiceWorkerInterception(
            allowedPort = { port },
            resourceRoots = { emptyList() },
            sensitiveLocations = { emptyList() },
            openFolder = { null },
            workspaceMembers = { null },
            staticResourceRoots = { emptyList() },
            connectionToken = { null },
        )
        return registered.captured
    }

    private fun request(
        scheme: String = "http",
        host: String = "127.0.0.1",
        port: Int = 3000,
        encodedPath: String? = "/app",
        encodedQuery: String? = null,
        method: String = "GET",
        isForMainFrame: Boolean = false,
        headers: Map<String, String> = mapOf("Sec-Fetch-Mode" to "navigate"),
        encodedUserInfo: String? = null,
    ): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns scheme
        every { uri.host } returns host
        every { uri.port } returns port
        every { uri.encodedPath } returns encodedPath
        every { uri.path } returns encodedPath
        every { uri.encodedQuery } returns encodedQuery
        every { uri.query } returns encodedQuery
        every { uri.encodedUserInfo } returns encodedUserInfo
        every { uri.userInfo } returns encodedUserInfo
        every { uri.encodedAuthority } returns buildString {
            if (!encodedUserInfo.isNullOrEmpty()) append("$encodedUserInfo@")
            append(host)
            if (port > 0) append(":$port")
        }
        every { uri.pathSegments } returns (encodedPath ?: "").split('/').filter { it.isNotEmpty() }
        val req = mockk<WebResourceRequest>(relaxed = true)
        every { req.url } returns uri
        every { req.method } returns method
        every { req.isForMainFrame } returns isForMainFrame
        every { req.requestHeaders } returns headers
        return req
    }

    private fun assertForbidden(response: WebResourceResponse?, label: String) {
        assertNotNull(response, "$label: expected 403 response, got null")
        assertEquals(403, response!!.statusCode, "$label: expected status 403")
        assertEquals("Forbidden", response.reasonPhrase, "$label: expected Forbidden reason")
    }

    private fun assertRedirectDocument(response: WebResourceResponse?, label: String) {
        assertNotNull(response, "$label: expected 200 redirect document, got null")
        assertEquals(200, response!!.statusCode, "$label: expected status 200")
        assertEquals("REDIRECT_DOC_OK", response.reasonPhrase, "$label: body or security headers did not match")
    }

    @Test
    fun `active editor port on 127_0_0_1 remains untouched`() {
        val req = request(host = "127.0.0.1", port = EDITOR_PORT, encodedPath = "/workbench")
        assertNull(client().shouldInterceptRequest(view, req))
        assertNull(serviceWorkerClient().shouldInterceptRequest(req))
    }

    @Test
    fun `localhost on any port remains untouched`() {
        val httpReq = request(scheme = "http", host = "localhost", port = 3000, encodedPath = "/app")
        val httpsReq = request(scheme = "https", host = "localhost", port = 8443, encodedPath = "/app")
        val c = client()
        val sw = serviceWorkerClient()
        assertNull(c.shouldInterceptRequest(view, httpReq))
        assertNull(c.shouldInterceptRequest(view, httpsReq))
        assertNull(sw.shouldInterceptRequest(httpReq))
        assertNull(sw.shouldInterceptRequest(httpsReq))
    }

    @Test
    fun `subframe GET navigation on cross-port 127_0_0_1 redirects to localhost without double encoding`() {
        expectRedirectDocument("http://localhost:3000/a%20b?x=1%26y=2")
        val req = request(
            host = "127.0.0.1",
            port = 3000,
            encodedPath = "/a%20b",
            encodedQuery = "x=1%26y=2",
            method = "GET",
            isForMainFrame = false,
            headers = mapOf("Sec-Fetch-Mode" to "navigate"),
        )
        assertRedirectDocument(client().shouldInterceptRequest(view, req), "encoded path and query")
    }

    @Test
    fun `subframe GET navigation escapes ampersands in html meta refresh attribute`() {
        expectRedirectDocument("http://localhost:3000/app?a=1&amp;b=2")
        val req = request(
            host = "127.0.0.1",
            port = 3000,
            encodedPath = "/app",
            encodedQuery = "a=1&b=2",
            method = "GET",
            isForMainFrame = false,
            headers = mapOf("Sec-Fetch-Mode" to "navigate"),
        )
        assertRedirectDocument(client().shouldInterceptRequest(view, req), "ampersand html escape")
    }

    @Test
    fun `subframe GET navigation with absent Sec-Fetch-Mode and text-html Accept redirects to localhost`() {
        expectRedirectDocument("http://localhost:3000/index.html")
        val req = request(
            host = "127.0.0.1",
            port = 3000,
            encodedPath = "/index.html",
            method = "GET",
            isForMainFrame = false,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"),
        )
        assertRedirectDocument(client().shouldInterceptRequest(view, req), "Accept text/html fallback")
    }

    @Test
    fun `subframe GET navigation on implicit port -1 redirects to localhost without port`() {
        expectRedirectDocument("http://localhost/status")
        val req = request(
            host = "127.0.0.1",
            port = -1,
            encodedPath = "/status",
            method = "GET",
            isForMainFrame = false,
            headers = mapOf("Sec-Fetch-Mode" to "navigate"),
        )
        assertRedirectDocument(client().shouldInterceptRequest(view, req), "implicit port -1")
    }

    @Test
    fun `HEAD and non-GET methods on cross-port 127_0_0_1 return 403`() {
        val c = client()
        for (method in listOf("HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")) {
            val req = request(
                host = "127.0.0.1",
                port = 3000,
                encodedPath = "/api",
                method = method,
                isForMainFrame = false,
                headers = mapOf("Sec-Fetch-Mode" to "navigate"),
            )
            assertForbidden(c.shouldInterceptRequest(view, req), "method $method")
        }
    }

    @Test
    fun `subresource and XHR GET requests on cross-port 127_0_0_1 return 403`() {
        val c = client()
        val cases = listOf(
            "Sec-Fetch-Mode cors with html Accept" to mapOf("Sec-Fetch-Mode" to "cors", "Accept" to "text/html"),
            "Sec-Fetch-Mode no-cors" to mapOf("Sec-Fetch-Mode" to "no-cors"),
            "missing Sec-Fetch-Mode with wildcard Accept" to mapOf("Accept" to "*/*"),
            "missing Sec-Fetch-Mode and Accept" to emptyMap(),
        )
        for ((label, headers) in cases) {
            val req = request(host = "127.0.0.1", port = 3000, encodedPath = "/api", headers = headers)
            assertForbidden(c.shouldInterceptRequest(view, req), label)
        }
    }

    @Test
    fun `main-frame navigation on cross-port 127_0_0_1 returns 403`() {
        val req = request(
            host = "127.0.0.1",
            port = 3000,
            encodedPath = "/app",
            method = "GET",
            isForMainFrame = true,
            headers = mapOf("Sec-Fetch-Mode" to "navigate"),
        )
        assertForbidden(client().shouldInterceptRequest(view, req), "main frame")
    }

    @Test
    fun `https on cross-port 127_0_0_1 returns 403`() {
        val c = client()
        val explicit = request(scheme = "https", host = "127.0.0.1", port = 8443, encodedPath = "/app")
        val implicit = request(scheme = "https", host = "127.0.0.1", port = -1, encodedPath = "/app")
        assertForbidden(c.shouldInterceptRequest(view, explicit), "https explicit port")
        assertForbidden(c.shouldInterceptRequest(view, implicit), "https implicit port")
    }

    @Test
    fun `inactive editorPort fails closed with 403`() {
        for (inactivePort in listOf(0, -1)) {
            val c = client(port = inactivePort)
            val getReq = request(host = "127.0.0.1", port = 3000, encodedPath = "/app", method = "GET")
            val postReq = request(host = "127.0.0.1", port = 3000, encodedPath = "/api", method = "POST")
            val httpsReq = request(scheme = "https", host = "127.0.0.1", port = 8443, encodedPath = "/app")
            assertForbidden(c.shouldInterceptRequest(view, getReq), "port=$inactivePort GET")
            assertForbidden(c.shouldInterceptRequest(view, postReq), "port=$inactivePort POST")
            assertForbidden(c.shouldInterceptRequest(view, httpsReq), "port=$inactivePort HTTPS")
        }
    }

    @Test
    fun `userinfo and unsafe url characters return 403`() {
        val c = client()
        assertForbidden(
            c.shouldInterceptRequest(view, request(encodedUserInfo = "user", encodedPath = "/")),
            "userinfo",
        )
        val unsafeCases = listOf(
            "semicolon in query" to ("/app" to "a=1;url=https://evil.example"),
            "double quote in query" to ("/app" to "a=\""),
            "single quote in query" to ("/app" to "a='"),
            "angle bracket in path" to ("/<script>" to null),
            "backslash in path" to ("/a\\b" to null),
            "CR in query" to ("/app" to "a=1\r"),
            "LF in query" to ("/app" to "a=1\n"),
            "space in path" to ("/a b" to null),
            "non-ASCII in path" to ("/caf\u00e9" to null),
            "path missing leading slash" to ("@evil.example" to null),
        )
        for ((label, pair) in unsafeCases) {
            val (path, query) = pair
            val req = request(encodedPath = path, encodedQuery = query)
            assertForbidden(c.shouldInterceptRequest(view, req), label)
        }
    }

    @Test
    fun `non-canonical ipv4 loopback aliases fail closed with 403 on cross-port and editorPort`() {
        val c = client()
        val sw = serviceWorkerClient()
        val aliases = listOf(
            "127.0.0.1.",
            "0177.0.0.1",
            "127.1",
            "127.0.1",
            "2130706433",
            "0x7f000001",
            "0X7F000001",
            "127.000.000.001",
            " 127.0.0.1 ",
        )
        for (host in aliases) {
            for (port in listOf(3000, EDITOR_PORT)) {
                val req = request(host = host, port = port, encodedPath = "/app")
                assertForbidden(c.shouldInterceptRequest(view, req), "WebView ipv4 alias '$host' port=$port")
                assertForbidden(sw.shouldInterceptRequest(req), "SW ipv4 alias '$host' port=$port")
                assertForbidden(
                    VSCodroidWebViewClient.interceptCrossPortLoopback(req, EDITOR_PORT, allowDocumentRedirect = true),
                    "helper ipv4 alias '$host' port=$port",
                )
            }
        }
    }

    @Test
    fun `ipv6-mapped and ipv6-translated loopback aliases fail closed with 403 on cross-port and editorPort`() {
        val c = client()
        val sw = serviceWorkerClient()
        val aliases = listOf(
            "[::ffff:127.0.0.1]",
            "[::FFFF:127.0.0.1]",
            "[::ffff:7f00:1]",
            "[::ffff:0:127.0.0.1]",
            "[::ffff:0:7f00:1]",
            "[0000:0000:0000:0000:0000:ffff:127.0.0.1]",
            "[0::ffff:127.0.0.1]",
            "[::0:ffff:127.0.0.1]",
            "[::ffff:7f00:01]",
            "[::ffff:7f00:001]",
            "::ffff:127.0.0.1",
            "::ffff:0:127.0.0.1",
        )
        for (host in aliases) {
            for (port in listOf(3000, EDITOR_PORT)) {
                val req = request(host = host, port = port, encodedPath = "/app")
                assertForbidden(c.shouldInterceptRequest(view, req), "WebView ipv6 alias '$host' port=$port")
                assertForbidden(sw.shouldInterceptRequest(req), "SW ipv6 alias '$host' port=$port")
                assertForbidden(
                    VSCodroidWebViewClient.interceptCrossPortLoopback(req, EDITOR_PORT, allowDocumentRedirect = true),
                    "helper ipv6 alias '$host' port=$port",
                )
            }
        }
    }

    @Test
    fun `non-loopback numeric ipv4, ipv6, and malformed hosts are not falsely blocked`() {
        val c = client()
        val sw = serviceWorkerClient()
        val nonAliases = listOf(
            "127.0.0.2",
            "192.168.1.1",
            "[::1]",
            "[::ffff:127.0.0.2]",
            "[::ffff:192.168.1.1]",
            "[::ffff:0:127.0.0.2]",
            "0178.0.0.1",
            "+127.0.0.1",
            "-127.0.0.1",
            "127.0.0.1..",
            ".127.0.0.1",
            "127..0.1",
            "127.0.0.256",
            "99999999999999999999",
            "[::ffff::127.0.0.1]",
            "[:::ffff:127.0.0.1]",
            "[::ffff:7f000:1]",
            "[::ffff:127.0.0.256]",
            "[::ffff:127.0.0.1",
            "::ffff:127.0.0.1]",
        )
        for (host in nonAliases) {
            val req = request(host = host, port = 3000, encodedPath = "/app")
            assertNull(c.shouldInterceptRequest(view, req), "WebView falsely blocked '$host'")
            assertNull(sw.shouldInterceptRequest(req), "SW falsely blocked '$host'")
            assertNull(
                VSCodroidWebViewClient.interceptCrossPortLoopback(req, EDITOR_PORT),
                "helper falsely blocked '$host'",
            )
        }
    }

    @Test
    fun `null requestHeaders and null method fail closed with 403 without throwing`() {
        val c = client()
        val nullHeadersReq = request(host = "127.0.0.1", port = 3000, encodedPath = "/app")
        every { nullHeadersReq.requestHeaders } returns null
        assertForbidden(c.shouldInterceptRequest(view, nullHeadersReq), "null requestHeaders")

        val nullMethodReq = request(host = "127.0.0.1", port = 3000, encodedPath = "/app")
        every { nullMethodReq.method } returns null
        assertForbidden(c.shouldInterceptRequest(view, nullMethodReq), "null method")
    }

    @Test
    fun `ServiceWorkerClient always enforces 403 on cross-port 127_0_0_1`() {
        val sw = serviceWorkerClient(port = EDITOR_PORT)
        val navGet = request(
            host = "127.0.0.1",
            port = 3000,
            encodedPath = "/app",
            method = "GET",
            isForMainFrame = false,
            headers = mapOf("Sec-Fetch-Mode" to "navigate"),
        )
        val post = request(host = "127.0.0.1", port = 3000, encodedPath = "/api", method = "POST")
        val https = request(scheme = "https", host = "127.0.0.1", port = 8443, encodedPath = "/app")
        assertForbidden(sw.shouldInterceptRequest(navGet), "SW GET navigate")
        assertForbidden(sw.shouldInterceptRequest(post), "SW POST")
        assertForbidden(sw.shouldInterceptRequest(https), "SW HTTPS")

        val swInactive = serviceWorkerClient(port = null)
        assertForbidden(swInactive.shouldInterceptRequest(navGet), "SW inactive port")
    }

    @Test
    fun `interceptCrossPortLoopback helper enforces case-insensitive http GET navigation and never constructs 3xx`() {
        expectRedirectDocument("http://localhost:3000/page?k=v")
        val caseInsensitiveReq = request(
            scheme = "HTTP",
            host = "127.0.0.1",
            port = 3000,
            encodedPath = "/page",
            encodedQuery = "k=v",
            method = "get",
            isForMainFrame = false,
            headers = mapOf("sec-fetch-mode" to "Navigate"),
        )
        assertRedirectDocument(
            VSCodroidWebViewClient.interceptCrossPortLoopback(caseInsensitiveReq, EDITOR_PORT),
            "direct helper safe subframe GET",
        )
        assertForbidden(
            VSCodroidWebViewClient.interceptCrossPortLoopback(
                caseInsensitiveReq, EDITOR_PORT, allowDocumentRedirect = false,
            ),
            "direct helper with document redirect disabled",
        )
        assertNull(
            VSCodroidWebViewClient.interceptCrossPortLoopback(
                request(host = "127.0.0.1", port = EDITOR_PORT), EDITOR_PORT,
            ),
            "direct helper active editor port",
        )
        assertNull(
            VSCodroidWebViewClient.interceptCrossPortLoopback(
                request(host = "localhost", port = 3000), EDITOR_PORT,
            ),
            "direct helper localhost",
        )
    }

    private companion object {
        const val EDITOR_PORT = 41234
    }
}
