package com.vscodroid.webview

import android.content.Context
import android.net.Uri
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * The published roots and the locations that must stay unreadable follow links a person can make or remove while
 * the editor is open, so they are resolved again at every main-frame navigation. Resolved once per screen, a
 * `~/.npmrc` linked into the projects folder after the screen was built left that folder published, the credential
 * with it, until the screen was destroyed.
 *
 * These drive the client's own navigation callback and its two entry points against real files, so what decides is
 * the code that runs on a device; only the response object and the descriptor open are stood in for, as in
 * [ResourceInterceptionWiringTest].
 */
class ResourceRulesNavigationTest {

    @TempDir
    lateinit var tmp: File

    private val filesDir get() = File(tmp, "files")
    private val home get() = File(filesDir, "home")
    private val kept get() = File(filesDir, "projects/dotfiles/npmrc")
    private val link get() = File(home, ".npmrc")

    private val logged = mutableListOf<String>()
    private val served get() = logged.filter { it.startsWith("Resource served") && kept.name in it }
    private val refused get() = logged.filter { it.contains("outside the published roots") && kept.name in it }

    private lateinit var context: Context
    private lateinit var rules: ResourceRules
    private val view = mockk<WebView>(relaxed = true)

    @BeforeEach
    fun setUp() {
        home.mkdirs()
        kept.parentFile.mkdirs()
        kept.writeText("//registry.npmjs.org/:_authToken=secret")
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.dataDir } returns tmp
        every { context.noBackupFilesDir } returns File(tmp, "no_backup")
        every { context.cacheDir } returns File(tmp, "cache")
        every { context.getExternalFilesDir(null) } returns null

        mockkObject(Logger)
        every { Logger.d(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        mockkConstructor(WebResourceResponse::class)
        mockResourceFileOpening()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun client() = VSCodroidWebViewClient(
        allowedPort = { PORT },
        resourceRules = rules,
        openFolder = { null },
        connectionToken = { null },
        onCrash = {},
        onPageLoaded = {},
        onRetryServer = {},
    )

    private fun request(): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns "file+.vscode-resource.vscode-cdn.net"
        every { uri.path } returns kept.path
        every { uri.encodedQuery } returns null
        every { uri.query } returns null
        val request = mockk<WebResourceRequest>(relaxed = true)
        every { request.url } returns uri
        every { request.requestHeaders } returns mapOf("Referer" to WEBVIEW_REFERER)
        return request
    }

    private fun navigate(client: VSCodroidWebViewClient) =
        client.onPageStarted(view, "http://127.0.0.1:$PORT/?folder=%2Fprojects", null)

    @Test
    fun `a credential linked into the projects folder after the client was built is refused from the next page load`() {
        rules = ResourceRules.of(context)
        val client = client()
        client.shouldInterceptRequest(view, request())
        assertTrue(served.size == 1 && refused.isEmpty(), "the control was not served, so nothing below is measured: $logged")

        Files.createSymbolicLink(link.toPath(), kept.toPath())
        navigate(client)
        client.shouldInterceptRequest(view, request())

        assertTrue(refused.size == 1 && served.size == 1, "the credential a link made it was served after a navigation: $logged")
    }

    @Test
    fun `a credential link removed after the client was built lets the folder serve again from the next page load`() {
        Files.createSymbolicLink(link.toPath(), kept.toPath())
        rules = ResourceRules.of(context)
        val client = client()
        client.shouldInterceptRequest(view, request())
        assertTrue(refused.size == 1 && served.isEmpty(), "the control was served, so nothing below is measured: $logged")

        assertTrue(link.delete(), "could not remove the link")
        navigate(client)
        client.shouldInterceptRequest(view, request())

        assertTrue(served.size == 1, "the projects folder stayed unserved after its link was removed: $logged")
    }

    /** The service worker reads the holder the client refreshes, so the two entry points answer alike. */
    @Test
    fun `the service worker refuses what the client's last navigation refused`() {
        val controller = mockk<ServiceWorkerController>(relaxed = true)
        val registered = slot<ServiceWorkerClient>()
        every { controller.setServiceWorkerClient(capture(registered)) } just Runs
        mockkStatic(ServiceWorkerController::class)
        every { ServiceWorkerController.getInstance() } returns controller
        mockkConstructor(ServiceWorkerClient::class)
        rules = ResourceRules.of(context)
        val client = client()
        val holder = rules
        VSCodroidWebViewClient.setupServiceWorkerInterception(
            { PORT }, { holder.roots }, { holder.sensitive }, { null }, { null }, { holder.staticRoots },
        ) { null }
        registered.captured.shouldInterceptRequest(request())
        assertTrue(served.size == 1 && refused.isEmpty(), "the control was not served, so nothing below is measured: $logged")

        Files.createSymbolicLink(link.toPath(), kept.toPath())
        navigate(client)
        registered.captured.shouldInterceptRequest(request())

        assertTrue(refused.size == 1, "the service worker served what the client's navigation refused: $logged")
    }

    /**
     * The worker reads its rules through the screen, so it can be asked after the screen is gone. A folder it is
     * still told about must then go unpublished: with no set to judge it by, the home directory would be served whole.
     */
    @Test
    fun `a service worker with no rules to judge by publishes no open folder`() {
        val controller = mockk<ServiceWorkerController>(relaxed = true)
        val registered = slot<ServiceWorkerClient>()
        every { controller.setServiceWorkerClient(capture(registered)) } just Runs
        mockkStatic(ServiceWorkerController::class)
        every { ServiceWorkerController.getInstance() } returns controller
        mockkConstructor(ServiceWorkerClient::class)
        var sensitive: List<String>? = listOf(File(home, ".ssh").canonicalPath)
        val folder = kept.parentFile.path
        VSCodroidWebViewClient.setupServiceWorkerInterception(
            { PORT }, { emptyList() }, { sensitive }, { folder }, { null }, { emptyList() },
        ) { null }
        registered.captured.shouldInterceptRequest(request())
        assertTrue(served.size == 1 && refused.isEmpty(), "the control was not served, so nothing below is measured: $logged")

        sensitive = null
        registered.captured.shouldInterceptRequest(request())

        assertTrue(refused.size == 1, "a folder was published with no locations to judge it by: $logged")
    }

    @Test
    fun `constructing rules and starting a navigation do not run disk resolution on the caller thread`() {
        val callerThread = Thread.currentThread()
        val resolveThreads = mutableListOf<Thread>()
        rules = ResourceRules {
            resolveThreads += Thread.currentThread()
            val sensitive = sensitiveLocations(context)
            publishedResourceRoots(context, sensitive) to sensitive
        }
        assertTrue(
            callerThread !in resolveThreads,
            "ResourceRules constructor ran disk resolution synchronously on the caller thread",
        )

        val client = client()
        navigate(client)
        assertTrue(
            callerThread !in resolveThreads,
            "onPageStarted ran disk resolution synchronously on the caller thread",
        )
    }

    private companion object {
        const val PORT = 41234
        const val WEBVIEW_REFERER = "https://0f7c2b1a-uuid.vscode-cdn.net/"
    }
}
