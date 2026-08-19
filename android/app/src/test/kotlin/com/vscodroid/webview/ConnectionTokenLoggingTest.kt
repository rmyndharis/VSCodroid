package com.vscodroid.webview

import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.vscodroid.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * That the webview layer never puts the server's connection token into logcat.
 *
 * Scoped to this layer, and the scope is now a division of labour rather than an
 * admission. The other sink is the bridge's `logToNative`, which relays
 * page-chosen text; it is covered by `LogRelayRedactionTest` beside its own code,
 * which is where a reader standing in that file will look. These cases cover the
 * paths this layer owns: the CDN interception, the proxy, the page callbacks and
 * the console relay.
 *
 * The token is the whole of the server's authentication: it is required on every
 * route but `/version`, `/delay-shutdown` and `/callback`, so anything holding it
 * can drive the editor — read any file the server can read, and open a terminal.
 * `MainActivity.navigateToFolder` keeps its own URL out of the log, and the
 * comment there once claimed that line was the only place the token could
 * otherwise escape. This file exists because that claim was false: the same token
 * rides into this class on every intercepted request and is appended, by
 * `withToken`, to URLs that several log statements then print whole.
 *
 * Both sides now answer through the same `redactToken`. That is worth more than
 * the two hand-written variants it replaced: `navigateToFolder` used to build a
 * second, token-free copy of its URL purely to have something safe to log, and a
 * twin maintained by hand is a twin that drifts, the safe copy stayed correct
 * only for as long as nobody added a parameter to the real one.
 *
 * Written against the log rather than against a redaction helper on purpose. A
 * helper can be perfect and called from nowhere; what a reader of logcat gets is
 * decided at the call sites, so the call sites are what these drive. Every
 * `Logger` level is captured, not just the debug ones — `Logger.d` is gated on a
 * debuggable build but `i`, `w` and `e` are not, so a leak at those levels ships
 * in release.
 */
class ConnectionTokenLoggingTest {

    /** Distinctive enough that a match cannot be a coincidence. */
    private val token = "tkn-2f9c4a1e-do-not-log-me"

    private val logged = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        logged.clear()

        mockkObject(Logger)
        every { Logger.d(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.i(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.w(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.w(any(), any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.e(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.e(any(), any(), any()) } answers { logged += secondArg<String>() }

        // The token is a UUID in production, so identity encoding is faithful.
        // Uri is a stub under the unit-test android.jar and throws otherwise.
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers { firstArg<String>() }

        // Cannot be constructed under the stub android.jar. Mocked purely so the
        // functions under test run to their end; nothing is asserted about it.
        mockkConstructor(WebResourceResponse::class)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun assertNothingLeaked() {
        assertTrue(
            logged.isNotEmpty(),
            "nothing was logged at all, so this proves nothing -- the fixture never " +
                "reached the branch it is aiming at",
        )
        val leaks = logged.filter { it.contains(token) }
        assertTrue(
            leaks.isEmpty(),
            "the connection token reached the log:\n" + leaks.joinToString("\n") { "  $it" },
        )
    }

    /** A CDN request for a static workbench asset, as the workbench sends one. */
    private fun cdnRequest(host: String, path: String, query: String? = null): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns host
        every { uri.path } returns path
        every { uri.query } returns query
        every { uri.toString() } returns "https://$host$path" + if (query != null) "?$query" else ""
        val request = mockk<WebResourceRequest>(relaxed = true)
        every { request.url } returns uri
        every { request.method } returns "GET"
        return request
    }

    /**
     * The line the CDN proxy prints on every successful rewrite.
     *
     * This needs a server that actually answers, because the statement sits after
     * `responseCode` is read: point it at a dead port and the function throws past
     * the line this test is about and the case passes while proving nothing.
     */
    @Test
    fun `the proxy does not log the token when the rewrite succeeds`() {
        Loopback(200).use { server ->
            VSCodroidWebViewClient.interceptCdnRequest(
                cdnRequest("abc123.vscode-cdn.net", "/stable/deadbeef/out/vs/workbench/workbench.js"),
                server.port, token, emptyList(), emptyList(), { null },
            )
            assertNothingLeaked()
        }
    }

    /**
     * And when it fails.
     *
     * The failure line prints `e.message`, and an exception raised by
     * `HttpURLConnection` is entitled to quote the URL it was given -- which by
     * then carries the token. Whether it does is a property of the JDK rather than
     * of this repository, which is exactly why it is measured here instead of
     * reasoned about.
     */
    @Test
    fun `the proxy does not log the token when the rewrite fails`() {
        val deadPort = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        VSCodroidWebViewClient.interceptCdnRequest(
            cdnRequest("abc123.vscode-cdn.net", "/stable/deadbeef/out/vs/workbench/workbench.js"),
            deadPort, token, emptyList(), emptyList(), { null },
        )
        assertNothingLeaked()
    }

    /**
     * An `https` resource host is refused rather than forwarded.
     *
     * An arm here used to proxy `https+host.vscode-resource.vscode-cdn.net/path` out to
     * `https://host/path`, whatever host the page named. It went without the connection
     * token, so no credential left, but it lent this app's network identity to any page
     * the editor renders, including reaching a LAN or loopback address the page could
     * not reach itself.
     *
     * Nothing produced the form: the shipped workbench returns http and https URIs
     * unchanged rather than encoding them into the resource host, and no bundled
     * extension contains one. This case is what notices the arm coming back.
     */
    @Test
    fun `an https resource host is refused rather than forwarded`() {
        val deadPort = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        VSCodroidWebViewClient.interceptCdnRequest(
            cdnRequest("https+example.test.vscode-resource.vscode-cdn.net", "/probe.txt"),
            deadPort, token, emptyList(), emptyList(), { null },
        )

        assertTrue(
            logged.none { it.contains("resource:example.test") },
            "the forwarding arm is back: a page named a host and the app went to it. " +
                "Logged:\n" + logged.joinToString("\n") { "  $it" },
        )
        assertTrue(
            logged.any { it.contains("Unknown resource scheme") },
            "the refusal should say which scheme it refused. Logged:\n" +
                logged.joinToString("\n") { "  $it" },
        )
        assertNothingLeaked()
    }

    /**
     * An unknown scheme is refused, and no tokened URL is built for it.
     *
     * This case used to assert the opposite: that the request DID reach a branch
     * appending the connection token, proven by the `remote-resource:` tag only
     * that branch logs. That branch is gone. It proxied to
     * `http://127.0.0.1:$port$path` with the token attached, where both the path
     * and the query were the page's to choose, so a webview could name the
     * server's `/vscode-remote-resource` route and read any absolute path with
     * this app's credential.
     *
     * The tag is therefore the control in reverse now: finding it would mean the
     * branch is back. Keeping the case rather than deleting it is the point, since
     * a deleted test cannot notice a restoration.
     */
    @Test
    fun `an unknown resource scheme is refused without building a tokened URL`() {
        val deadPort = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        // The return value is deliberately not inspected. Refusing and serving both
        // produce a WebResourceResponse, and its getters throw "not mocked" under the
        // stub android.jar -- the same limitation that made `resourceOutcome` a value
        // rather than a branch. The log is what distinguishes the two here.
        VSCodroidWebViewClient.interceptCdnRequest(
            cdnRequest("custom+authority.vscode-resource.vscode-cdn.net", "/some/asset.css"),
            deadPort, token, emptyList(), emptyList(), { null },
        )

        assertTrue(
            logged.none { it.contains("remote-resource:") },
            "the tokened proxy branch is back. Logged:\n" +
                logged.joinToString("\n") { "  $it" },
        )
        assertTrue(
            logged.any { it.contains("Unknown resource scheme") },
            "the refusal should say which scheme it refused. Logged:\n" +
                logged.joinToString("\n") { "  $it" },
        )
        assertNothingLeaked()
    }

    /**
     * A CDN path too short to rewrite, which is reported with the URI printed
     * whole. The token can be in that URI's query: the workbench is handed one on
     * the navigation and appends it to requests of its own.
     */
    @Test
    fun `an unrewritable CDN URL is not reported with its query intact`() {
        VSCodroidWebViewClient.interceptCdnRequest(
            cdnRequest("abc123.vscode-cdn.net", "/onlyonesegment", query = "tkn=$token"),
            41234, token, emptyList(), emptyList(), { null },
        )
        assertNothingLeaked()
    }

    /**
     * The navigation itself.
     *
     * `MainActivity` loads `http://127.0.0.1:PORT/?folder=...&tkn=...` into the
     * WebView, so the URL these two callbacks are handed is the tokened one --
     * the very string the activity took care not to print. `onPageFinished` logs
     * at `Logger.i`, which is not gated on a debuggable build.
     */
    @Test
    fun `the page lifecycle callbacks do not log the token`() {
        val client = VSCodroidWebViewClient(
            allowedPort = 41234,
            resourceRoots = emptyList(),
            sensitiveLocations = emptyList(),
            openFolder = { null },
            connectionToken = { token },
            onCrash = {},
            onPageLoaded = {},
                    onRetryServer = {},
        )
        val view = mockk<WebView>(relaxed = true)
        val url = "http://127.0.0.1:41234/?folder=%2Fhome%2Fprojects&tkn=$token"

        client.onPageStarted(view, url, null)
        client.onPageFinished(view, url)

        assertNothingLeaked()
    }

    /**
     * Everything the page prints to its console.
     *
     * Not one of ours: `message` is arbitrary text the workbench chose to print
     * and `sourceId` is the URL of the script that printed it. Both are
     * interpolated into one line and handed to `Logger`, and the ERROR and
     * WARNING branches are not gated on a debuggable build — so whatever they
     * carry ships in release.
     *
     * Whether the connection token ever actually reaches here is NOT established,
     * and this test does not claim it does: it hands the client a message that
     * contains one and requires that it does not come out the other side. A gap
     * whose safety rests on the server consuming the token at the redirect is a
     * gap resting on "should".
     */
    @Test
    fun `a console message carrying the token is not logged with it`() {
        val client = VSCodroidWebChromeClient { _ -> false }

        listOf(
            ConsoleMessage.MessageLevel.ERROR,
            ConsoleMessage.MessageLevel.WARNING,
            ConsoleMessage.MessageLevel.LOG,
        ).forEach { level ->
            val console = mockk<ConsoleMessage>(relaxed = true)
            every { console.messageLevel() } returns level
            every { console.lineNumber() } returns 42
            every { console.sourceId() } returns
                "http://127.0.0.1:41234/?folder=%2Fhome&tkn=$token"
            every { console.message() } returns "Failed to fetch /stub?tkn=$token"

            client.onConsoleMessage(console)
        }

        assertNothingLeaked()
    }

    /**
     * The callback still receives the real URL.
     *
     * Redacting is a change to what is *logged*, and it would be easy to make it a
     * change to what is *passed on* as well. `MainActivity` derives the open
     * workspace folder from this argument and nothing else does, so a redacted one
     * would silently lose the folder on every load.
     */
    @Test
    fun `redacting the log does not redact the page-loaded callback`() {
        var seen: String? = null
        val client = VSCodroidWebViewClient(
            allowedPort = 41234,
            resourceRoots = emptyList(),
            sensitiveLocations = emptyList(),
            openFolder = { null },
            connectionToken = { token },
            onCrash = {},
            onPageLoaded = { seen = it },
            onRetryServer = {},
        )
        val url = "http://127.0.0.1:41234/?folder=%2Fhome%2Fprojects&tkn=$token"

        client.onPageFinished(mockk<WebView>(relaxed = true), url)

        assertTrue(
            seen == url,
            "the callback must carry the URL as it was, or the open folder is lost: $seen",
        )
    }
}

/**
 * A loopback server that answers one status and nothing else.
 *
 * `ProcessManagerTest` has an equivalent, but it is file-private and in another
 * package. This one is smaller because it does not need to record what was asked
 * for -- these tests read the log, not the request.
 */
private class Loopback(private val status: Int) : AutoCloseable {

    private val socket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    @Volatile
    private var running = true

    val port: Int get() = socket.localPort

    init {
        thread(name = "token-log-stub", isDaemon = true) {
            while (running) {
                try {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        reader.readLine()
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                        }
                        client.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 $status Stub\r\n" +
                                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                            )
                            flush()
                        }
                    }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }
    }

    override fun close() {
        running = false
        socket.close()
    }
}
