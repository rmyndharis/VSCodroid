package com.vscodroid.webview

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.vscodroid.service.mockEditorGateway
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The CDN arm fetches from the app's own server at `127.0.0.1:<port>`, and that address is on the phone, so the
 * request must never be handed to a proxy.
 *
 * A manual Wi-Fi proxy or a global HTTP proxy with an empty bypass list makes the platform set `http.nonProxyHosts`
 * to an empty string, which turns the JVM's default bypass for loopback off. A connection opened without naming a
 * proxy then goes to the proxy: the proxy cannot reach the phone's loopback, so every webview host document came
 * back as the proxy's error page, and the connection token in the URL reached the proxy's log.
 *
 * Driven against two [Recorder]s: one is the server the arm is meant to reach, the other stands for the proxy.
 */
class CdnLoopbackProxyTest {

    private val token = "0123456789abcdef0123456789abcdef"

    private val hostDocument = "/stable/deadbeef/out/vs/workbench/contrib/webview/browser/pre/index.html"

    private val proxyProperties = listOf("http.proxyHost", "http.proxyPort", "http.nonProxyHosts")

    private val saved = HashMap<String, String?>()

    @BeforeEach
    fun setUp() {
        mockEditorGateway()
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers { firstArg<String>() }
        mockkConstructor(WebResourceResponse::class)
        proxyProperties.forEach { saved[it] = System.getProperty(it) }
    }

    @AfterEach
    fun tearDown() {
        proxyProperties.forEach { name -> saved[name]?.let { System.setProperty(name, it) } ?: System.clearProperty(name) }
        unmockkAll()
    }

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
    fun `a phone proxy with an empty bypass list is never asked for the app's own server`() {
        Recorder().use { proxy ->
            Recorder().use { server ->
                System.setProperty("http.proxyHost", "127.0.0.1")
                System.setProperty("http.proxyPort", proxy.port.toString())
                System.setProperty("http.nonProxyHosts", "")

                VSCodroidWebViewClient.interceptCdnRequest(
                    request(), server.port, { token }, emptyList(), emptyList(), { null },
                    tokenWaitMs = 0,
                )

                assertNull(proxy.target, "the request for the app's own server went to the proxy: ${proxy.target}")
                assertNotNull(server.target, "the request never reached the app's own server")
            }
        }
    }
}
