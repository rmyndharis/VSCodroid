package com.vscodroid

import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.vscodroid.bridge.AuthTabWindow
import com.vscodroid.service.EditorGateway
import com.vscodroid.service.NodeService
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import com.vscodroid.webview.DownloadCoordinator
import com.vscodroid.webview.SecretStorageKey
import com.vscodroid.webview.VSCodroidWebViewClient
import io.mockk.ConstantMatcher
import io.mockk.EqMatcher
import io.mockk.FunctionMatcher
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

class EditorOriginOwnershipTest {
    private lateinit var gateway: EditorGateway
    private lateinit var activity: MainActivity
    private lateinit var view: WebView
    private lateinit var client: WebViewClient
    private lateinit var server: ServerSocket
    private lateinit var worker: Thread
    private lateinit var address: String
    private val scripts = mutableListOf<String>()

    private fun set(name: String, value: Any?) = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)

    @BeforeEach
    fun setUp() {
        AuthTabWindow.beginPage()
        gateway = EditorGateway()
        mockkObject(EditorGateway.Companion)
        every { EditorGateway.instance } returns gateway
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        mockkObject(VSCodroidWebViewClient.Companion)
        every {
            VSCodroidWebViewClient.setupServiceWorkerInterception(any(), any(), any(), any(), any(), any())
        } just Runs
        server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        address = "http://127.0.0.1:${server.localPort}/"
        worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = "<html><body>unverified listener</body></html>"
                    socket.getOutputStream().write(("HTTP/1.0 200 OK\r\nContent-Type: text/html\r\n" +
                        "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                }
            } catch (e: Exception) {
                if (!server.isClosed) throw e
            }
        }
        val body = URL(address).openConnection().apply { connectTimeout = 1_000; readTimeout = 1_000 }
            .getInputStream().bufferedReader().use { it.readText() }
        assertTrue(body.contains("unverified listener"))
        activity = mockk(relaxed = true)
        view = mockk(relaxed = true)
        val service = mockk<NodeService>(relaxed = true)
        every { service.isServerReady() } returns false
        every { view.url } returns address
        every { view.webViewClient = any() } answers { client = firstArg() }
        every { view.webViewClient } answers { client }
        every { view.evaluateJavascript(any(), any()) } answers { scripts += firstArg<String>() }
        every { activity["initBridge"](any<Int>()) } answers { callOriginal() }
        every { activity["injectBridgeToken"]() } answers { callOriginal() }
        every { activity["folderFromUrl"](any<String>()) } returns null
        set("tag", "MainActivity")
        set("webView", view)
        set("nodeService", service)
        set("safManager", mockk<SafStorageManager>(relaxed = true))
        set("downloads", mockk<DownloadCoordinator>(relaxed = true))
        set("appNavigation", AppNavigationMark(10_000))
        MainActivity::class.java.getDeclaredMethod("initBridge", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, server.localPort)
        client.onPageStarted(view, address, null)
    }

    @AfterEach
    fun tearDown() {
        server.close()
        worker.join(5_000)
        assertFalse(worker.isAlive, "the fixture listener outlived its test")
        gateway.close()
        AuthTabWindow.beginPage()
        unmockkAll()
    }

    @Test
    fun `an unverified loopback document never receives the native bridge token`() {
        client.onPageFinished(view, address)

        assertFalse(scripts.any { it.contains("metadata.authToken =") },
            "an unverified listener document received the native bridge token")
    }

    @Test
    fun `an unverified loopback document cannot fetch the persistent secret key`() {
        val key = ByteArray(32) { 42 }
        val storage = mockk<SecretStorageKey>()
        every { storage.bytes() } returns key
        mockkObject(SecretStorageKey.Companion)
        every { SecretStorageKey.forApp(any()) } returns storage
        var returned: ByteArray? = null
        mockkConstructor(WebResourceResponse::class)
        every { anyConstructed<WebResourceResponse>().reasonPhrase } returns "REFUSED"
        every {
            constructedWith<WebResourceResponse>(
                EqMatcher("application/octet-stream"), ConstantMatcher<Any>(true), EqMatcher(200),
                EqMatcher("OK"), ConstantMatcher<Any>(true),
                FunctionMatcher<InputStream>({ returned = it.readBytes(); true }, InputStream::class),
            ).reasonPhrase
        } returns "KEY"
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns "127.0.0.1"
        every { uri.port } returns server.localPort
        every { uri.path } returns "/_vscodroid/secret-key"
        val request = mockk<WebResourceRequest>(relaxed = true)
        every { request.url } returns uri
        every { request.method } returns "POST"
        every { request.requestHeaders } returns mapOf(
            "Origin" to address.removeSuffix("/"), "Sec-Fetch-Site" to "same-origin",
        )

        client.shouldInterceptRequest(view, request)?.reasonPhrase

        assertNull(returned, "an unverified listener document received the persistent key")
    }
}
