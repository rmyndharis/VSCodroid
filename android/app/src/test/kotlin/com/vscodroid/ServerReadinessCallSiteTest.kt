package com.vscodroid

import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import android.widget.Toast
import com.vscodroid.service.mockEditorGateway
import com.vscodroid.bridge.AuthTabWindow
import com.vscodroid.service.NodeService
import com.vscodroid.service.StartupNotice
import com.vscodroid.util.Logger
import com.vscodroid.webview.VSCodroidWebChromeClient
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
        mockEditorGateway()
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

    @Test
    fun `binding shows the terminal notice and the gave up page`() {
        every { service.lastStartupNotice() } returns StartupNotice("Server stopped", terminal = true)
        runReal("setupServiceCallbacks")
        assertEquals(listOf("toast:Server stopped", "show", "gave-up"), events)
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
}
