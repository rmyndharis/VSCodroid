package com.vscodroid

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.vscodroid.service.mockEditorGateway
import com.vscodroid.bridge.AuthTabWindow
import com.vscodroid.service.NodeService
import com.vscodroid.service.StartupNotice
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import com.vscodroid.webview.DownloadCoordinator
import com.vscodroid.webview.VSCodroidWebChromeClient
import com.vscodroid.webview.VSCodroidWebViewClient
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ServerReadinessCallSiteTest {
    private lateinit var activity: MainActivity
    private lateinit var service: NodeService
    private lateinit var webView: WebView
    private val events = mutableListOf<String>()
    private var now = 400_000L

    private fun field(name: String) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun setField(name: String, value: Any?) = field(name).set(activity, value)
    private fun port() = field("serverPort").getInt(activity)
    private fun backgroundedAt() = field("backgroundedAt").getLong(activity)

    private fun runReal(name: String) {
        every { activity[name]() } answers { callOriginal() }
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }

    @BeforeEach
    fun setUp() {
        events.clear()
        now = 400_000L
        AuthTabWindow.beginPage()
        mockEditorGateway().bind(13337)
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { now }
        mockkStatic(Toast::class)
        val toast = mockk<Toast>(relaxed = true)
        every { Toast.makeText(any<Context>(), any<CharSequence>(), any()) } answers {
            events += "toast:${secondArg<CharSequence>()}"
            toast
        }
        every { toast.show() } answers { events += "show" }
        activity = mockk(relaxed = true)
        setField("tag", "MainActivity")
        service = mockk(relaxed = true)
        webView = mockk(relaxed = true)
        setField("nodeService", service)
        setField("webView", webView)
        every { service.getPort() } returns 13337
        every { service.isServerReady() } returns true
        every { service.lastStartupNotice() } returns null
        every { activity["loadVSCode"](any<Int>(), isNull<String>(), isNull<String>()) } answers {
            events += "load:${firstArg<Int>()}:port=${port()}"
        }
        every { activity["showServerGaveUpWithLog"]() } answers { events += "gave-up" }
        every { activity["checkConnectionHealth"](any<Long>()) } answers { events += "probe" }
        every { webView.reload() } answers { events += "reload" }
        every { activity["signInIsPending"]() } answers { callOriginal() }
        every { activity["fileChooserIsPending"]() } answers { callOriginal() }
        every { activity["savePickerIsPending"]() } answers { callOriginal() }
    }

    @AfterEach
    fun tearDown() {
        AuthTabWindow.beginPage()
        unmockkAll()
    }

    @Test
    fun `binding shows a nonterminal notice without loading`() {
        every { service.lastStartupNotice() } returns StartupNotice("Starting slowly", terminal = false)
        runReal("setupServiceCallbacks")
        assertEquals(listOf("toast:Starting slowly", "show"), events)
    }

    /**
     * The page says it, and a notice raised before it is taken down by it at once: a dialog that
     * flashes wherever the text is too long for a toast.
     */
    @Test
    fun `binding a server that gave up shows the gave up page and no notice under it`() {
        every { service.lastStartupNotice() } returns StartupNotice("Server stopped", terminal = true)
        runReal("setupServiceCallbacks")
        assertEquals(listOf("gave-up"), events)
    }

    @Test
    fun `binding a ready server records its port before loading`() {
        runReal("setupServiceCallbacks")
        assertEquals(listOf("load:13337:port=13337"), events)
        assertEquals(13337, port())
    }

    @Test
    fun `binding an unready server waits despite its allocated port`() {
        every { service.isServerReady() } returns false
        runReal("setupServiceCallbacks")
        assertEquals(emptyList<String>(), events)
        assertEquals(0, port())
    }

    private fun resume(timestamp: Long = 1_000L, serverPort: Int = 13337) {
        setField("backgroundedAt", timestamp)
        setField("serverPort", serverPort)
        runReal("handleResumeFromBackground")
    }

    @Test
    fun `a long background with a ready server reloads`() {
        resume()
        assertEquals(listOf("reload"), events)
        assertEquals(0L, backgroundedAt())
    }

    @Test
    fun `an unready or unbound service consumes the timestamp without acting`() {
        every { service.isServerReady() } returns false
        resume()
        assertEquals(emptyList<String>(), events)
        assertEquals(0L, backgroundedAt())
        setField("nodeService", null)
        resume()
        assertEquals(emptyList<String>(), events)
        assertEquals(0L, backgroundedAt())
    }

    @Test
    fun `no background timestamp or no port leaves the page alone`() {
        resume(timestamp = 0L)
        assertEquals(emptyList<String>(), events)
        resume(serverPort = 0)
        assertEquals(emptyList<String>(), events)
        assertEquals(1_000L, backgroundedAt())
    }

    @Test
    fun `a medium background probes and a short background does nothing`() {
        resume(timestamp = 300_000L)
        assertEquals(listOf("probe"), events)
        events.clear()
        resume(timestamp = 390_000L)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `pending sign in changes a long background reload to a probe`() {
        val nonce = "a".repeat(32)
        assertTrue(AuthTabWindow.register("1", nonce, "b".repeat(32), AuthTabWindow.pageGeneration()))
        AuthTabWindow.launched(AuthTabWindow.arm(listOf("1"), nonce, now - 1_000L))
        resume()
        assertEquals(listOf("probe"), events)
    }

    @Test
    fun `pending file chooser prevents recovery`() {
        val chrome = mockk<VSCodroidWebChromeClient>()
        every { chrome.hasPendingFileChooser } returns true
        every { webView.webChromeClient } returns chrome
        resume()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `pending save picker prevents recovery`() {
        setField("pickerRequestId", "request")
        resume()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `lifecycle records the background time and invokes recovery on start`() {
        val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")
        val stop = SourceScan.withoutComments(SourceScan.body(source, "override fun onStop()"))
        val start = SourceScan.withoutComments(SourceScan.body(source, "override fun onStart()"))
        assertTrue(Regex("""backgroundedAt\s*=\s*SystemClock\.elapsedRealtime\(\)""").containsMatchIn(stop))
        assertTrue(Regex("""\bhandleResumeFromBackground\s*\(\s*\)""").containsMatchIn(start))
    }

    private fun installWebViewClient(port: Int = 13337): WebViewClient {
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers { firstArg() }
        mockkObject(VSCodroidWebViewClient.Companion)
        every {
            VSCodroidWebViewClient.setupServiceWorkerInterception(any(), any(), any(), any(), any(), any(), any())
        } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { service.getConnectionToken() } returns "token"
        lateinit var client: WebViewClient
        every { webView.webViewClient = any() } answers { client = firstArg() }
        every { webView.webViewClient } answers { client }
        every { webView.loadUrl(any()) } answers { events += "load-url" }
        mockkStatic("com.vscodroid.MainActivityPageScriptsKt")
        every { activity["markAppNavigation"]() } answers { callOriginal() }
        every { activity["rememberWorkspaceFolder"](any<String>(), any<Boolean>()) } returns true
        every { activity["folderFromUrl"](any<String>()) } returns null
        every { activity.injectBridgeToken() } returns Unit
        every { activity["initBridge"](any<Int>()) } answers { callOriginal() }
        every { activity["navigateToFolder"](any<Int>(), any<String>(), any<String>()) } answers { callOriginal() }
        setField("safManager", mockk<SafStorageManager>(relaxed = true))
        setField("downloads", mockk<DownloadCoordinator>(relaxed = true))
        setField("appNavigation", AppNavigationMark(10_000))
        setField("serverPort", port)
        MainActivity::class.java.getDeclaredMethod("initBridge", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, port)
        return client
    }

    @Test
    fun `a workbench load started while stopped counts absence from that load`() {
        installWebViewClient()
        setField("backgroundedAt", 1_000L)

        now = 390_000L
        MainActivity::class.java.getDeclaredMethod(
            "navigateToFolder", Int::class.javaPrimitiveType, String::class.java, String::class.java,
        ).apply { isAccessible = true }.invoke(activity, 13337, null, "token")
        assertEquals(listOf("load-url"), events)
        events.clear()

        now = 400_000L
        runReal("handleResumeFromBackground")
        assertEquals(emptyList<String>(), events, "a 10s-old background load was reloaded from the 399s-old onStop timestamp")
    }

    @Test
    fun `a workbench page started before onStop and finished while stopped is not reloaded on return`() {
        val client = installWebViewClient()
        val url = "http://127.0.0.1:13337/"
        now = 500L
        client.onPageStarted(webView, url, null)
        assertEquals(0L, backgroundedAt())

        setField("backgroundedAt", 1_000L)

        now = 380_000L
        client.onPageFinished(webView, url)

        now = 405_000L
        runReal("handleResumeFromBackground")
        assertEquals(emptyList<String>(), events, "a page that finished loading 25s ago while stopped was reloaded")
        assertEquals(0L, backgroundedAt())
    }

    @Test
    fun `a background-loaded page still reloads once its own idle time passes the threshold, and non-workbench pages keep the clock`() {
        val client = installWebViewClient()
        val url = "http://127.0.0.1:13337/"
        setField("backgroundedAt", 1_000L)

        now = 390_000L
        client.onPageStarted(webView, "about:blank", null)
        client.onPageFinished(webView, "about:blank")
        assertEquals(1_000L, backgroundedAt(), "a non-workbench page must not refresh the background clock")

        client.onPageStarted(webView, url, null)
        assertEquals(390_000L, backgroundedAt())
        now = 400_000L
        client.onPageFinished(webView, url)
        assertEquals(400_000L, backgroundedAt())

        now = 750_000L
        runReal("handleResumeFromBackground")
        assertEquals(listOf("reload"), events)
    }

    @Test
    fun `a server restart on a new port updates client routing and page callbacks`() {
        assertTrue(
            java.lang.reflect.Modifier.isVolatile(MainActivity::class.java.getDeclaredField("serverPort").modifiers),
            "serverPort must be @Volatile so off-main interception threads observe port updates",
        )
        val client = installWebViewClient(13337)
        var tokenInjections = 0
        every { activity.injectBridgeToken() } answers { tokenInjections++ }

        setField("serverPort", 14444)
        setField("backgroundedAt", 1_000L)
        now = 390_000L

        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns "http"
        every { uri.host } returns "127.0.0.1"
        every { uri.port } returns 14444
        every { uri.path } returns "/"
        every { uri.toString() } returns "http://127.0.0.1:14444/"
        val req = mockk<android.webkit.WebResourceRequest>(relaxed = true)
        every { req.url } returns uri
        every { req.isForMainFrame } returns true

        assertEquals(
            false,
            client.shouldOverrideUrlLoading(webView, req),
            "client treated the restarted server's new port as an external URL",
        )
        client.onPageStarted(webView, "http://127.0.0.1:14444/", null)
        assertEquals(390_000L, backgroundedAt(), "onNavigationStarted ignored the restarted server's new port")
        client.onPageFinished(webView, "http://127.0.0.1:14444/")
        assertEquals(1, tokenInjections, "onPageLoaded did not inject the bridge token for the restarted server's new port")
    }
}
