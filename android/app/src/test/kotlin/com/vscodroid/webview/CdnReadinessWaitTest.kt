package com.vscodroid.webview

import android.net.Uri
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.vscodroid.service.restartBackoffMs
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * What a webview request does while the local server is not ready.
 *
 * Readiness is withdrawn for every restart, and that includes the one where
 * nothing the page talks to went away: the bootstrap is killed, the editor
 * server outlives it, and after the restart backoff it is adopted back, with
 * the page connected to it the whole time. A webview opened or shown again in
 * those seconds asks for its host document then, and an immediate 503 left it
 * blank until it was shown again, while `onServerReady` deliberately does not
 * reload a page whose server was adopted back.
 *
 * So the request waits for the token on its own thread, a bounded time, and at
 * most [VSCodroidWebViewClient.TOKEN_WAITERS] of them at once. What these pin:
 * a token that arrives inside the wait is the one the request is forwarded
 * with; a wait that runs out forwards nothing; the requests beyond those
 * already waiting are refused at once rather than holding another worker of the
 * WebView's thread pool; and a request that finds the token takes no place
 * among the waiting.
 *
 * Driven against [Recorder], one per request, so what is asserted is the
 * request line our server would have received, token included.
 *
 * NEGATIVE CONTROL, measured: with the token read once and a missing one
 * refused at once, which is what the interception did before, the first three
 * cases fail. With the wait kept and the limit on waiters removed, the third
 * fails on its refusal; with the token first asked only once a place is taken,
 * it fails on the ready request. With either caller handing over a token read
 * once on arrival, the case for that caller fails; with a place given back only
 * when a wait runs out, the case for places fails whatever order the cases run
 * in.
 */
class CdnReadinessWaitTest {

    private val token = "0123456789abcdef0123456789abcdef"

    private val hostDocument = "/stable/deadbeef/out/vs/workbench/contrib/webview/browser/pre/index.html"

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs

        // A stub under the unit-test android.jar, and the token is a hex string in
        // production, so identity encoding is faithful.
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers { firstArg<String>() }

        // Cannot be constructed under the stub android.jar. Mocked purely so the
        // function runs to its end; nothing is asserted about it.
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

    private fun intercept(
        server: Recorder,
        connectionToken: () -> String?,
        waitMs: Long,
        request: WebResourceRequest = request(),
    ) =
        VSCodroidWebViewClient.interceptCdnRequest(
            request, server.port, connectionToken, emptyList(), emptyList(), { null },
            tokenWaitMs = waitMs,
        )

    private fun msSince(startNanos: Long) =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    private val forwarded =
        "/stable-deadbeef/static/out/vs/workbench/contrib/webview/browser/pre/index.html?tkn=$token"

    /** The case adoption produces: the server comes back while the request waits. */
    @Test
    fun `a request made while the server is not ready is forwarded with the token once it is`() {
        Recorder().use { server ->
            val readyAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400)

            val response = intercept(
                server, { if (System.nanoTime() >= readyAt) token else null }, waitMs = 10_000,
            )

            assertEquals(
                forwarded,
                server.target,
                "a webview host document asked for during a restart was not forwarded once the " +
                    "server was ready, so the webview stays blank",
            )
            assertNotNull(response)
        }
    }

    /**
     * And the server that does not come back in time: nothing goes to the port,
     * and the request is answered when the wait is up rather than never.
     */
    @Test
    fun `a request still waiting when the wait is up is refused and nothing is forwarded`() {
        Recorder().use { server ->
            val started = System.nanoTime()

            val response = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                intercept(server, { null }, waitMs = 300)
            }

            val waited = msSince(started)
            assertTrue(
                waited >= 300,
                "answered after $waited ms, before the 300 ms wait was up: a server coming " +
                    "back inside it would have been refused",
            )
            assertNull(server.target, "a request went to the port without a token: ${server.target}")
            assertNotNull(
                response,
                "the refusal handed the address back to the WebView, which then fetches it " +
                    "from the real CDN over the device's network",
            )
        }
    }

    /**
     * Each waiting request holds a worker of the WebView's thread pool, which
     * also serves every other request the page makes, so a burst during a long
     * restart must not take all of them.
     */
    @Test
    fun `a request beyond those already waiting is refused at once`() {
        val waiting = VSCodroidWebViewClient.TOKEN_WAITERS
        val servers = List(waiting) { Recorder() }
        val asked = List(waiting) { AtomicInteger() }
        val failures = mutableListOf<Throwable>()
        val ready = AtomicBoolean(false)
        // Stubbed here rather than on the threads below, which only use them.
        val requests = List(waiting) { request() }
        val threads = List(waiting) { i ->
            thread(name = "cdn-wait-$i") {
                try {
                    intercept(
                        servers[i],
                        { asked[i].incrementAndGet(); if (ready.get()) token else null },
                        waitMs = 10_000,
                        request = requests[i],
                    )
                } catch (e: Throwable) {
                    synchronized(failures) { failures += e }
                }
            }
        }
        try {
            // Asked once on arrival and again only from inside the wait, so a
            // second question means that request holds its place.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (asked.any { it.get() < 2 } && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(
                asked.all { it.get() >= 2 },
                "the first $waiting requests never waited for the token: asked ${asked.map { it.get() }}",
            )

            Recorder().use { extra ->
                val started = System.nanoTime()
                intercept(extra, { null }, waitMs = 3_000)
                val waited = msSince(started)

                assertTrue(
                    waited < 1_500,
                    "a request beyond the $waiting already waiting waited $waited ms, holding " +
                        "another worker of the WebView's thread pool",
                )
                assertNull(extra.target, "a request went to the port without a token: ${extra.target}")
            }

            // The server coming back while those are asleep between questions: a
            // request arriving then finds the token, and the places they hold are
            // no reason to refuse it.
            Recorder().use { arriving ->
                intercept(arriving, { token }, waitMs = 3_000)

                assertEquals(
                    forwarded,
                    arriving.target,
                    "a request to a ready server was refused because others were still waiting",
                )
            }
        } finally {
            ready.set(true)
            threads.forEach { it.join(5_000) }
            servers.forEach { it.close() }
        }

        assertTrue(failures.isEmpty(), "a waiting request threw: $failures")
        servers.forEachIndexed { i, server ->
            assertEquals(
                forwarded,
                server.target,
                "waiting request $i was not forwarded once the server was ready",
            )
        }
    }

    /**
     * A place is held only while its request waits. One that is never given back
     * refuses at once every webview of every later restart, from the third wait in
     * the life of the process, and the case above only notices when it happens to
     * run after the waits that took them.
     */
    @Test
    fun `a place is given back when its wait ends`() {
        repeat(VSCodroidWebViewClient.TOKEN_WAITERS + 1) {
            Recorder().use { server -> intercept(server, { null }, waitMs = 150) }
        }
        repeat(VSCodroidWebViewClient.TOKEN_WAITERS + 1) { i ->
            Recorder().use { server ->
                // Missing when the request arrives and there at the first question
                // inside the wait, so each of these takes a place and ends its wait
                // with the token.
                val asked = AtomicInteger()
                intercept(server, { if (asked.incrementAndGet() > 1) token else null }, waitMs = 3_000)

                assertEquals(
                    forwarded,
                    server.target,
                    "wait ${i + 1} after ${VSCodroidWebViewClient.TOKEN_WAITERS + 1} that ran out " +
                        "was refused at once: an earlier wait never gave its place back",
                )
            }
        }
    }

    /**
     * The page's own requests reach the interception through the client, which
     * has to hand over the supplier itself. A token read once on arrival is null
     * for the whole of a restart, so the wait would spend its time asking a value
     * that cannot change and refuse the webview after it.
     */
    @Test
    fun `the client's requests wait on the live token`() {
        Recorder().use { server ->
            val readyAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
            val client = VSCodroidWebViewClient(
                allowedPort = server.port,
                resourceRoots = emptyList(),
                sensitiveLocations = emptyList(),
                openFolder = { null },
                connectionToken = { if (System.nanoTime() >= readyAt) token else null },
                onCrash = {},
                onPageLoaded = {},
                onRetryServer = {},
            )

            client.shouldInterceptRequest(mockk(relaxed = true), request())

            assertEquals(
                forwarded,
                server.target,
                "the client handed the interception a token read once, so a webview shown " +
                    "during a restart is refused although the server came back inside the wait",
            )
        }
    }

    /** And the second way a resource request gets there, for the same reason. */
    @Test
    fun `a service worker's requests wait on the live token`() {
        val controller = mockk<ServiceWorkerController>(relaxed = true)
        val registered = slot<ServiceWorkerClient>()
        every { controller.setServiceWorkerClient(capture(registered)) } just Runs
        mockkStatic(ServiceWorkerController::class)
        every { ServiceWorkerController.getInstance() } returns controller
        // The stub android.jar's constructor throws, and the registration's own
        // catch would then leave nothing registered.
        mockkConstructor(ServiceWorkerClient::class)

        Recorder().use { server ->
            val readyAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
            VSCodroidWebViewClient.setupServiceWorkerInterception(
                server.port, emptyList(), emptyList(), { null },
            ) { if (System.nanoTime() >= readyAt) token else null }
            assertTrue(registered.isCaptured, "no ServiceWorkerClient was registered")

            registered.captured.shouldInterceptRequest(request())

            assertEquals(
                forwarded,
                server.target,
                "the service worker client handed the interception a token read once, so " +
                    "its requests during a restart are refused although the server came back",
            )
        }
    }

    /**
     * The wait has to outlast the pause before the first restart, or a server
     * adopted back on the first attempt is never waited for at all and every
     * webview opened in that window is refused again.
     */
    @Test
    fun `the wait outlasts the pause before the first restart`() {
        assertTrue(
            VSCodroidWebViewClient.TOKEN_WAIT_MS > restartBackoffMs(1),
            "TOKEN_WAIT_MS ${VSCodroidWebViewClient.TOKEN_WAIT_MS} does not cover the " +
                "${restartBackoffMs(1)} ms backoff before the first restart",
        )
    }
}
