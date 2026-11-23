package com.vscodroid.webview

import android.net.Uri
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.vscodroid.SourceScan
import com.vscodroid.service.EditorGateway
import com.vscodroid.service.mockEditorGateway
import com.vscodroid.util.Logger
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration

/**
 * The CDN arm sends the connection token to `127.0.0.1:<port>`, and that is the app's own server only while
 * [EditorGateway] holds the port.
 *
 * A Stop Server gives a temporary port back; a start after it makes a new token. A page of the earlier origin that is
 * still asking for resources, through a client built with the earlier port, was handed that token by a supplier that
 * answers for the server running now, at a port any other app may have bound in between. The arms that carry a
 * credential to the page's origin already ask the gateway; this one now does the same through
 * [VSCodroidWebViewClient.tokenWhileBound], for both of its entries: the client and the service worker's.
 */
class CdnTokenOwnershipTest {

    private val token = "0123456789abcdef0123456789abcdef"

    private val hostDocument = "/stable/deadbeef/out/vs/workbench/contrib/webview/browser/pre/index.html"

    private lateinit var gateway: EditorGateway

    @BeforeEach
    fun setUp() {
        gateway = mockEditorGateway()
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers { firstArg<String>() }
        mockkConstructor(WebResourceResponse::class)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun request(): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns "0f7c2b1a.vscode-cdn.net"
        every { uri.path } returns hostDocument
        every { uri.query } returns null
        every { uri.toString() } returns "https://0f7c2b1a.vscode-cdn.net$hostDocument"
        val request = mockk<WebResourceRequest>(relaxed = true)
        every { request.url } returns uri
        every { request.method } returns "GET"
        return request
    }

    @Test
    fun `the token is handed over only while the gateway holds the port`() {
        gateway.bind(41_234)
        var asked = 0
        val supplier = VSCodroidWebViewClient.tokenWhileBound(41_234) { asked++; token }

        assertEquals(token, supplier(), "the token was withheld from the port the app's own listener holds")
        assertTrue(gateway.release(41_234))
        assertNull(supplier(), "the token was handed over for a port the gateway gave back")
        assertEquals(1, asked, "the token was read for a port the app no longer holds")
    }

    @Test
    fun `a port the gateway never held gets no token`() {
        gateway.bind(41_234)
        var asked = 0

        assertNull(VSCodroidWebViewClient.tokenWhileBound(41_235) { asked++; token }())
        assertNull(VSCodroidWebViewClient.tokenWhileBound(0) { asked++; token }())
        assertEquals(0, asked, "the token was read for a port that is not the origin")
    }

    @Test
    fun `a client of an origin the gateway gave back forwards nothing to its port`() {
        Recorder().use { server ->
            gateway.bind(server.port)
            gateway.release(server.port)
            val client = VSCodroidWebViewClient(
                allowedPort = { server.port },
                resourceRules = noResourceRules(),
                openFolder = { null },
                connectionToken = { token },
                onCrash = {},
                onPageLoaded = {},
                onRetryServer = {},
            )

            val response = assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                client.shouldInterceptRequest(mockk(relaxed = true), request())
            }

            assertNull(server.target, "a request with the token went to a port the app gave back: ${server.target}")
            assertNotNull(response, "null sends the page to the real CDN: every exit of the arm answers")
        }
    }

    @Test
    fun `a service worker of an origin the gateway gave back forwards nothing to its port`() {
        val controller = mockk<ServiceWorkerController>(relaxed = true)
        val registered = slot<ServiceWorkerClient>()
        every { controller.setServiceWorkerClient(capture(registered)) } just Runs
        mockkStatic(ServiceWorkerController::class)
        every { ServiceWorkerController.getInstance() } returns controller
        mockkConstructor(ServiceWorkerClient::class)

        Recorder().use { server ->
            gateway.bind(server.port)
            gateway.release(server.port)
            VSCodroidWebViewClient.setupServiceWorkerInterception(
                { server.port }, { emptyList() }, { emptyList() }, { null },
            ) { token }
            assertTrue(registered.isCaptured, "no ServiceWorkerClient was registered")

            val response = assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                registered.captured.shouldInterceptRequest(request())
            }

            assertNull(server.target, "a request with the token went to a port the app gave back: ${server.target}")
            assertNotNull(response)
        }
    }

    /**
     * Both entries hand the token over through [VSCodroidWebViewClient.tokenWhileBound], so a third caller added
     * later has the two next to it to copy and this to fail on.
     */
    @Test
    fun `every call of the CDN arm in the client passes the token through tokenWhileBound`() {
        val source = SourceScan.read("src/main/kotlin/com/vscodroid/webview/VSCodroidWebViewClient.kt")

        val calls = Regex("""return interceptCdnRequest\(""").findAll(source).count()
        val bound = Regex("""return interceptCdnRequest\(\s*request,\s*(\w+),\s*tokenWhileBound\(\1, connectionToken\)""")
            .findAll(source).count()

        assertEquals(2, calls, "the client's entries into the CDN arm are no longer these two")
        assertEquals(calls, bound, "an entry into the CDN arm hands the token over outside tokenWhileBound")
    }

    @Test
    fun `port zero fails closed on CDN requests without returning null or forwarding`() {
        Recorder().use { server ->
            gateway.bind(server.port)
            var currentPort = 0
            val client = VSCodroidWebViewClient(
                allowedPort = { currentPort },
                resourceRules = noResourceRules(),
                openFolder = { null },
                connectionToken = { token },
                onCrash = {},
                onPageLoaded = {},
                onRetryServer = {},
            )

            val closed = client.shouldInterceptRequest(mockk(relaxed = true), request())
            assertNotNull(closed, "port <= 0 must return a closed response rather than null to the real CDN")
            assertNull(server.target, "port <= 0 must not forward to any loopback listener")

            currentPort = server.port
            client.shouldInterceptRequest(mockk(relaxed = true), request())
            assertTrue(server.target != null, "live port supplier must route to the active port once updated")
        }
    }
}
