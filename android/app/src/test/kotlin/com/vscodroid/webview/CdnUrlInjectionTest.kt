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
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * What a rendered page can make the CDN rewrite ask our own server for.
 *
 * The entry test is the hostname and nothing else: `interceptCdnRequest` accepts
 * any `*.vscode-cdn.net` address, and `shouldOverrideUrlLoading` lets those
 * through, so the whole of the path and the query belong to whatever document is
 * on screen. That includes an extension webview, notebook output and any site
 * open in the bundled Simple Browser. `withToken` then signs the result with the
 * server's connection token, which authenticates every route but `/version`,
 * `/delay-shutdown` and `/callback`.
 *
 * `Uri.getPath()` hands this code the DECODED path, so `%3F` arrives as a real
 * `?` and `%2E%2E%2F` as a real `../`. Concatenated into a URL string, both stop
 * being text and become syntax: the first opens a query the page wrote and the
 * token is appended to it with `&`, the second walks out of
 * `/{quality}-{commit}/static/` onto any route the server has, and
 * `/vscode-remote-resource?path=<absolute>` reads any file the server can read.
 * The two sibling arms of `interceptResourceRequest` were deleted for exactly
 * this, and their comments in the source are the record of it.
 *
 * Driven against a real loopback server so the assertion is on the request line
 * the server actually receives, not on a string this file rebuilds. That matters
 * for the traversal case in particular: whether the dot segments are collapsed by
 * the client (Android's HttpURLConnection is OkHttp-backed and canonicalises
 * them) or left for the server to trip over is not this app's to rely on, so what
 * is asserted is that the request is never made at all.
 *
 * NEGATIVE CONTROL, measured for each case:
 *  - drop the dot-segment refusal from `rewriteCdnUrl` and
 *    `a traversal out of the static prefix is never requested` goes red;
 *  - build the URL by concatenation again (`"http://127.0.0.1:$port$localPath" +
 *    queryPart`) and `a query injected into the path does not become a query`
 *    goes red, while the ordinary-asset case stays green in both.
 */
class CdnUrlInjectionTest {

    /** Hex, so that `Uri.encode` being the identity below is faithful to production. */
    private val token = "0123456789abcdef0123456789abcdef"

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

    private fun request(path: String, query: String?): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns "0f7c2b1a.vscode-cdn.net"
        every { uri.path } returns path
        every { uri.query } returns query
        every { uri.toString() } returns
            "https://0f7c2b1a.vscode-cdn.net$path" + if (query != null) "?$query" else ""
        val request = mockk<WebResourceRequest>(relaxed = true)
        every { request.url } returns uri
        every { request.method } returns "GET"
        return request
    }

    private fun intercept(server: Recorder, path: String, query: String? = null) =
        VSCodroidWebViewClient.interceptCdnRequest(
            request(path, query), server.port, token, emptyList(), emptyList(), { null }
        )

    /**
     * The control the two cases below rest on: an ordinary workbench asset still
     * reaches the server unchanged, token and all.
     *
     * Without it, a `rewriteCdnUrl` that refused everything would satisfy both of
     * them while emptying the editor of every asset it loads.
     */
    @Test
    fun `an ordinary asset is still rewritten and still carries the token`() {
        Recorder().use { server ->
            assertNotNull(
                intercept(server, "/stable/deadbeef/out/vs/workbench/workbench.js"),
                "the rewrite refused an asset the workbench asks for on every load",
            )

            assertEquals(
                "/stable-deadbeef/static/out/vs/workbench/workbench.js?tkn=$token",
                server.target,
                "the local URL is no longer the one the server's static route answers",
            )
        }
    }

    /**
     * A `?` the page percent-encoded into its path must stay a character in a path
     * segment, never the start of a query the token then authenticates.
     */
    @Test
    fun `a query injected into the path does not become a query`() {
        Recorder().use { server ->
            intercept(
                server,
                // What `%3Fpath%3D...` decodes to by the time this code sees it.
                "/stable/deadbeef/vscode-remote-resource?path=/data/data/com.vscodroid/" +
                    "files/home/.ssh/id_ed25519",
            )

            val target = server.target
            assertNotNull(target, "no request reached the server at all")
            assertEquals(
                "tkn=$token",
                target!!.substringAfter('?', ""),
                "the page wrote part of the query on a request signed with the server's " +
                    "connection token. Request line target: $target",
            )
            assertTrue(
                target.startsWith("/stable-deadbeef/static/"),
                "the request left the static prefix: $target",
            )
        }
    }

    /**
     * And a `../` it encoded the same way must not walk out of the static prefix.
     *
     * Asserted as "no request", because the collapse happens in the client on
     * Android and in neither on the desktop JVM this test runs on. A request that
     * depends on which end resolves the dot segments is one this app should not be
     * making.
     */
    @Test
    fun `a traversal out of the static prefix is never requested`() {
        Recorder().use { server ->
            val response = intercept(
                server,
                "/stable/deadbeef/../../vscode-remote-resource",
                query = "path=/data/data/com.vscodroid/files/home/.ssh/id_ed25519",
            )

            assertNull(
                server.target,
                "a token-authenticated request left the app for a route the page chose: " +
                    "${server.target}",
            )
            assertNotNull(
                response,
                "the refusal handed the address back to the WebView, which then fetches it " +
                    "from the real CDN over the device's network",
            )
        }
    }
}

/**
 * A loopback server that answers one empty 200 and remembers the request target.
 *
 * `ConnectionTokenLoggingTest` has a sibling that deliberately records nothing,
 * because it reads the log. This one exists for the opposite reason: the request
 * line is the only place the URL this app built can be observed after
 * `HttpURLConnection` has had it.
 */
private class Recorder : AutoCloseable {

    private val socket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    @Volatile
    private var running = true

    /** The path and query of the last request, or null if none was made. */
    @Volatile
    var target: String? = null
        private set

    val port: Int get() = socket.localPort

    init {
        thread(name = "cdn-injection-stub", isDaemon = true) {
            while (running) {
                try {
                    socket.accept().use(::answer)
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }
    }

    private fun answer(client: Socket) {
        val reader = client.getInputStream().bufferedReader()
        // "GET <target> HTTP/1.1", recorded before the response so that it is
        // written by the time the caller's readResponseCode() returns.
        target = reader.readLine()?.split(" ")?.getOrNull(1)
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
        }
        client.getOutputStream().apply {
            write(
                ("HTTP/1.1 200 Stub\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .toByteArray()
            )
            flush()
        }
    }

    override fun close() {
        running = false
        socket.close()
    }
}
