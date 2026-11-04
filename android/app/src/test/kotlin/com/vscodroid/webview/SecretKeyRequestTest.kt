package com.vscodroid.webview

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.vscodroid.service.mockEditorGateway
import com.vscodroid.SourceScan
import com.vscodroid.util.Logger
import com.vscodroid.webview.VSCodroidWebViewClient.Companion.SECRET_KEY_PATH
import com.vscodroid.webview.VSCodroidWebViewClient.Companion.secretKeyRequest
import io.mockk.ConstantMatcher
import io.mockk.EqMatcher
import io.mockk.FunctionMatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream

/**
 * Which requests for the secret storage key are answered.
 *
 * The two directions cost different things. Refusing the workbench is data
 * loss, not an error message: the page retries, gives up, starts with no
 * secrets, and the next sign-in overwrites everything stored before it with that
 * one session. Answering another frame hands it a key, which is useless without
 * the workbench's localStorage but is still not its to have. So a request is
 * refused only on evidence that it came from somewhere else, and an absent
 * header is not that evidence.
 */
class SecretKeyRequestTest {

    private val port = 41234
    private val ours = "http://127.0.0.1:$port"

    @Test
    fun `the workbench's key request is answered`() {
        assertEquals(SecretKeyRequest.ANSWER, secretKeyRequest(SECRET_KEY_PATH, "POST", null, null, port))
        assertEquals(SecretKeyRequest.ANSWER, secretKeyRequest(SECRET_KEY_PATH, "POST", ours, "same-origin", port))
    }

    @Test
    fun `a loopback hostname is not the reserved key origin`() {
        assertEquals(
            SecretKeyRequest.REFUSE,
            secretKeyRequest(SECRET_KEY_PATH, "POST", "http://localhost:$port", null, port),
        )
    }

    @Test
    fun `another path is not a key request`() {
        assertEquals(SecretKeyRequest.NOT_ONE, secretKeyRequest("/", "POST", ours, null, port))
        assertEquals(
            SecretKeyRequest.NOT_ONE,
            secretKeyRequest("$SECRET_KEY_PATH/x", "POST", ours, null, port),
        )
        assertEquals(SecretKeyRequest.NOT_ONE, secretKeyRequest(null, "POST", ours, null, port))
    }

    @Test
    fun `a read is refused`() {
        // The workbench only ever posts. A GET is a navigation or a subresource,
        // which a frame can make without script.
        assertEquals(SecretKeyRequest.REFUSE, secretKeyRequest(SECRET_KEY_PATH, "GET", ours, null, port))
    }

    @Test
    fun `another origin is refused`() {
        assertEquals(
            SecretKeyRequest.REFUSE,
            secretKeyRequest(SECRET_KEY_PATH, "POST", "https://0f7c-uuid.vscode-cdn.net", null, port),
        )
        assertEquals(
            SecretKeyRequest.REFUSE,
            secretKeyRequest(SECRET_KEY_PATH, "POST", "http://127.0.0.1:5173", null, port),
        )
        assertEquals(SecretKeyRequest.REFUSE, secretKeyRequest(SECRET_KEY_PATH, "POST", "null", null, port))
        assertEquals(SecretKeyRequest.REFUSE, secretKeyRequest(SECRET_KEY_PATH, "POST", null, "cross-site", port))
    }

    /**
     * The two call sites the decision depends on, which nothing above observes.
     *
     * Each failure compiles and keeps every other case green. Without the key
     * argument the client's default throws and every key request is a 503;
     * without the cookie the workbench never asks; with the arm below the
     * bundle arm's early return, the POST reaches Node and gets 405. All three
     * put secrets back in memory with nothing on screen to say so.
     */
    @Test
    fun `the activity switches the storage on and the client answers first`() {
        val activity = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))
        assertTrue(
            activity.contains("secretStorageKey ="),
            "MainActivity no longer hands VSCodroidWebViewClient its key; the default throws",
        )
        val load = SourceScan.body(activity, "private fun loadVSCode(")
        val cookie = load.indexOf("applySecretStorage()")
        assertTrue(
            cookie >= 0 && cookie < load.indexOf("navigateToFolder("),
            "loadVSCode must set the secret storage cookie before it navigates",
        )
        val client = SourceScan.withoutComments(
            SourceScan.read("src/main/kotlin/com/vscodroid/webview/VSCodroidWebViewClient.kt")
        )
        val intercept = SourceScan.body(client, "override fun shouldInterceptRequest(view: WebView")
        val decided = intercept.indexOf("secretKeyRequest(")
        assertTrue(
            decided >= 0 && decided < intercept.indexOf("nlsBundleRequested("),
            "the key arm must run before the bundle arm, whose early return hands the POST to Node",
        )
    }

    @Test
    fun `the cookie names the path the arm answers`() {
        assertEquals(
            "vscode-secret-key-path=$SECRET_KEY_PATH; path=/",
            VSCodroidWebViewClient.secretStorageCookie(),
        )
    }
}

/**
 * Runtime responses of [VSCodroidWebViewClient.shouldInterceptRequest] for the
 * secret storage key path (`200` with `no-store` and key bytes, `403` without
 * reading the key on refused method or origin, `503` when the key supplier
 * fails, and `null` for unrelated paths).
 */
class SecretKeyInterceptorResponseTest {

    private val port = 41234
    private val ours = "http://127.0.0.1:$port"
    private val keyBytes = ByteArray(32) { (it + 1).toByte() }
    private var keyReads = 0
    private val logged = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        keyReads = 0
        logged.clear()
        mockEditorGateway()
        mockkObject(Logger)
        every { Logger.d(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.i(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.w(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.w(any(), any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.e(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.e(any(), any(), any()) } answers { logged += secondArg<String>() }
        mockkConstructor(WebResourceResponse::class)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun anything() = ConstantMatcher<Any>(true)

    private fun identifyResponses() {
        every { anyConstructed<WebResourceResponse>().reasonPhrase } returns "OTHER"
        every {
            constructedWith<WebResourceResponse>(
                EqMatcher("application/octet-stream"),
                anything(),
                EqMatcher(200),
                EqMatcher("OK"),
                EqMatcher(mapOf("Cache-Control" to "no-store")),
                FunctionMatcher<InputStream>(
                    {
                        it.mark(64)
                        val bytes = it.readBytes()
                        it.reset()
                        bytes.contentEquals(keyBytes)
                    },
                    InputStream::class,
                ),
            ).reasonPhrase
        } returns "KEY"
        every {
            constructedWith<WebResourceResponse>(
                EqMatcher("text/plain"),
                EqMatcher("utf-8"),
                EqMatcher(403),
                EqMatcher("Forbidden"),
                EqMatcher(mapOf("Cache-Control" to "no-store")),
                anything(),
            ).reasonPhrase
        } returns "FORBIDDEN"
        every {
            constructedWith<WebResourceResponse>(
                EqMatcher("text/plain"),
                EqMatcher("utf-8"),
                EqMatcher(503),
                EqMatcher("Service Unavailable"),
                EqMatcher(mapOf("Cache-Control" to "no-store")),
                anything(),
            ).reasonPhrase
        } returns "UNAVAILABLE"
    }

    private fun request(
        path: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        uriPort: Int = port,
        uriHost: String = "127.0.0.1",
    ): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns uriHost
        every { uri.port } returns uriPort
        every { uri.path } returns path
        every { uri.pathSegments } returns path.split('/').filter { it.isNotEmpty() }
        val request = mockk<WebResourceRequest>(relaxed = true)
        every { request.url } returns uri
        every { request.method } returns method
        every { request.requestHeaders } returns headers
        return request
    }

    private fun client(supplier: () -> ByteArray = { keyReads += 1; keyBytes.copyOf() }) =
        VSCodroidWebViewClient(
            allowedPort = port,
            resourceRoots = emptyList(),
            sensitiveLocations = emptyList(),
            openFolder = { null },
            connectionToken = { null },
            onCrash = {},
            onPageLoaded = {},
            onRetryServer = {},
            secretStorageKey = supplier,
        )

    @Test
    fun `the interceptor answers a workbench key POST with the key and no-store`() {
        identifyResponses()

        val r = client().shouldInterceptRequest(mockk(relaxed = true), request(SECRET_KEY_PATH, "POST"))

        assertNotNull(r, "the interceptor returned null for the key POST, so it reaches Node")
        assertEquals(1, keyReads, "the key was not read")
        assertEquals("KEY", r!!.reasonPhrase, "the answer was not built from the key, 200, no-store")
    }

    @Test
    fun `the interceptor answers a key POST carrying the workbench origin`() {
        identifyResponses()

        val r = client().shouldInterceptRequest(
            mockk(relaxed = true),
            request(
                SECRET_KEY_PATH,
                "POST",
                mapOf("Origin" to ours, "Sec-Fetch-Site" to "same-origin"),
            ),
        )

        assertNotNull(r)
        assertEquals(1, keyReads)
        assertEquals("KEY", r!!.reasonPhrase)
    }

    @Test
    fun `a hostname request cannot read the native key`() {
        identifyResponses()

        val r = client().shouldInterceptRequest(
            mockk(relaxed = true), request(SECRET_KEY_PATH, "POST", uriHost = "localhost"),
        )

        assertNull(r)
        assertEquals(0, keyReads)
    }

    @Test
    fun `the interceptor refuses a key GET with 403 and does not read the key`() {
        identifyResponses()

        val r = client().shouldInterceptRequest(mockk(relaxed = true), request(SECRET_KEY_PATH, "GET"))

        assertNotNull(r, "a refused key request must be answered, not handed to Node")
        assertEquals(0, keyReads, "a refused request must not read the key")
        assertEquals("FORBIDDEN", r!!.reasonPhrase, "the refusal was not a 403")
        assertTrue(logged.any { it.contains("Refused a secret key request") }, "the refusal was not logged: $logged")
    }

    @Test
    fun `the interceptor refuses a key POST from another origin with 403 and does not read the key`() {
        identifyResponses()

        val r = client().shouldInterceptRequest(
            mockk(relaxed = true),
            request(SECRET_KEY_PATH, "POST", mapOf("Origin" to "https://0f7c-uuid.vscode-cdn.net")),
        )

        assertNotNull(r)
        assertEquals(0, keyReads)
        assertEquals("FORBIDDEN", r!!.reasonPhrase)
        assertTrue(logged.any { it.contains("Refused a secret key request") }, "the refusal was not logged: $logged")
    }

    @Test
    fun `the interceptor leaves another local path to the server without reading the key`() {
        val r = client().shouldInterceptRequest(mockk(relaxed = true), request("/some/other/path", "GET"))

        assertNull(r)
        assertEquals(0, keyReads)
    }

    @Test
    fun `the interceptor answers 503 when the secret storage key cannot be read`() {
        identifyResponses()

        val r = client { throw IOException("disk") }
            .shouldInterceptRequest(mockk(relaxed = true), request(SECRET_KEY_PATH, "POST"))

        assertNotNull(r, "an unreadable key must be answered with 503, not thrown and not handed to Node")
        assertEquals("UNAVAILABLE", r!!.reasonPhrase)
    }
}
