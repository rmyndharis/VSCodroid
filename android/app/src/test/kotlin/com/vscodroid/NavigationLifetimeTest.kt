package com.vscodroid

import android.content.SharedPreferences
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import com.vscodroid.service.mockEditorGateway
import com.vscodroid.bridge.AuthTabWindow
import com.vscodroid.service.NodeService
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.webview.DownloadCoordinator
import com.vscodroid.webview.VSCodroidWebViewClient
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class NavigationLifetimeTest {
    @TempDir
    lateinit var temp: File
    private lateinit var activity: MainActivity
    private lateinit var view: WebView
    private lateinit var prefs: SharedPreferences
    private val loads = mutableListOf<String>()

    private fun set(name: String, value: Any?) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)

    @BeforeEach
    fun setUp() {
        loads.clear()
        AuthTabWindow.beginPage()
        mockEditorGateway()
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L
        activity = mockk(relaxed = true)
        view = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        every { view.url } returns null
        every { view.loadUrl(any()) } answers { loads += "editor" }
        every { view.loadDataWithBaseURL(any(), any(), any(), any(), any()) } answers { loads += "recovery" }
        every { activity.getString(any()) } returns "Retry"
        every { activity["initBridge"](any<Int>()) } returns Unit
        every { activity["applyEditorLanguage"]() } returns Unit
        every { activity["applySecretStorage"]() } returns Unit
        every { activity["paintWindowNow"](any<Int>()) } returns Unit
        every { activity getProperty "workspacePrefs" } returns prefs
        every { activity["markAppNavigation"]() } answers { callOriginal() }
        every { activity["navigationIsOurs"]() } answers { callOriginal() }
        every { activity["rememberWorkspaceFolder"](any<String>(), any<Boolean>()) } answers { callOriginal() }
        every { activity["navigateToFolder"](any<Int>(), any<String>(), any<String>()) } answers { callOriginal() }
        val service = mockk<NodeService>(relaxed = true)
        every { service.getConnectionToken() } returns "token"
        every { service.isServerReady() } returns true
        val storage = mockk<SafStorageManager>(relaxed = true)
        every { storage.claimWorkspacePaths(any()) } returns true
        set("tag", "MainActivity")
        set("webView", view)
        set("nodeService", service)
        set("safManager", storage)
        set("downloads", mockk<DownloadCoordinator>(relaxed = true))
        set("appNavigation", AppNavigationMark(10_000))
    }

    @AfterEach
    fun tearDown() {
        AuthTabWindow.beginPage()
        unmockkAll()
    }

    @Test
    fun `a clean app navigation cannot consent to the next document unload`() {
        mockkObject(VSCodroidWebViewClient.Companion)
        every {
            VSCodroidWebViewClient.setupServiceWorkerInterception(any(), any(), any(), any(), any(), any())
        } just Runs
        lateinit var client: WebViewClient
        lateinit var chrome: WebChromeClient
        every { view.webViewClient = any() } answers { client = firstArg() }
        every { view.webChromeClient = any() } answers { chrome = firstArg() }
        every { activity["initBridge"](any<Int>()) } answers { callOriginal() }
        MainActivity::class.java.getDeclaredMethod("initBridge", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 13337)
        val mark = MainActivity::class.java.getDeclaredMethod("markAppNavigation").apply { isAccessible = true }
        mark.invoke(activity)
        assertTrue(chrome.onJsBeforeUnload(view, "about:blank", "", null))
        mark.invoke(activity)

        client.onPageStarted(view, "about:blank", null)

        assertFalse(chrome.onJsBeforeUnload(view, "about:blank", "", null),
            "the new document inherited an unused navigation consent")
        mark.invoke(activity)
        client.onPageFinished(view, "about:blank")
        assertTrue(chrome.onJsBeforeUnload(view, "about:blank", "", null),
            "an older completion withdrew the next app navigation")
    }

    @Test
    fun `a delayed workspace lookup cannot replace a newer recovery page`() = runBlocking {
        pauseLookup {
            MainActivity::class.java.getDeclaredMethod("showErrorPage", String::class.java,
                String::class.java, String::class.java).apply { isAccessible = true }
                .invoke(activity, "Server stopped", "vscodroid://retry-server", "")
        }

        assertEquals(listOf("recovery"), loads, "the old lookup dispatched a load over the recovery page")
    }

    @Test
    fun `the newest workspace lookup opens the editor`() = runBlocking {
        prepareLookup(coroutineContext)
        startLookup()
        coroutineContext.job.children.toList().joinAll()

        assertEquals(listOf("editor"), loads)
    }

    @Test
    fun `a delayed lookup leaves a newer workbench document alone`() = runBlocking {
        pauseLookup {
            val next = File(temp, "next").apply { check(mkdirs()) }
            val url = "http://127.0.0.1:13337/?folder=${next.path}"
            every { view.url } returns url
            every { activity["folderFromUrl"](url) } returns next.path
            AuthTabWindow.beginPage()
        }

        assertEquals(emptyList<String>(), loads, "the old lookup reloaded the newer document")
    }

    @Test
    fun `a late placeholder start does not discard the workspace lookup`() = runBlocking {
        pauseLookup {
            every { view.url } returns "data:text/html,Starting"
            every { activity["folderFromUrl"](any<String>()) } returns null
            AuthTabWindow.beginPage()
        }

        assertEquals(listOf("editor"), loads)
    }

    @Test
    fun `a lookup does not load a server that stopped while it waited`() = runBlocking {
        pauseLookup {
            val service = MainActivity::class.java.getDeclaredField("nodeService").apply { isAccessible = true }
                .get(activity) as NodeService
            every { service.isServerReady() } returns false
        }

        assertEquals(emptyList<String>(), loads, "the lookup loaded a server that was no longer ready")
    }

    private fun prepareLookup(context: CoroutineContext, beforeRead: () -> Unit = {}) {
        mockkStatic("androidx.lifecycle.LifecycleOwnerKt")
        val scope = mockk<LifecycleCoroutineScope>()
        every { scope.coroutineContext } returns context + Dispatchers.Unconfined
        every { activity.lifecycleScope } returns scope
        every { activity["workspaceWasClosed"]() } answers { callOriginal() }
        every { activity["rememberedWorkspaceFolder"]() } answers { callOriginal() }
        every { activity["folderFromUrl"](isNull<String>()) } answers { callOriginal() }
        every { activity["workspaceQueryFromUrl"](isNull<String>()) } answers { callOriginal() }
        every { activity["loadVSCode"](any<Int>(), isNull<String>(), isNull<String>()) } answers { callOriginal() }
        every { activity["showErrorPage"](any<String>(), any<String>(), any<String>()) } answers { callOriginal() }
        mockkObject(Environment)
        every { Environment.getSafMirrorsDir(any()) } returns File(temp, "mirrors").path
        val folder = File(temp, "project").apply { check(mkdirs()) }
        every { prefs.getString("last_workspace_folder", null) } answers { beforeRead(); folder.path }
        mockkStatic(android.net.Uri::class)
        every { android.net.Uri.encode(any()) } answers { firstArg<String>() }
    }

    private fun startLookup() {
        MainActivity::class.java.getDeclaredMethod("loadVSCode", Int::class.javaPrimitiveType,
            String::class.java, String::class.java).apply { isAccessible = true }.invoke(activity, 13337, null, null)
    }

    private suspend fun CoroutineScope.pauseLookup(change: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        prepareLookup(coroutineContext) {
            if (firstRead.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            startLookup()
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the workspace lookup did not begin")
            change()
        } finally {
            release.countDown()
        }
        coroutineContext.job.children.toList().joinAll()
    }
}
