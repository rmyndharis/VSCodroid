package com.vscodroid.webview

import android.webkit.WebSettings
import android.webkit.WebView
import com.vscodroid.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The three WebView settings that decide what rendered content is allowed to
 * reach outside the page, pinned as decisions rather than left as defaults
 * somebody flips while reading the block.
 *
 * They are asserted through `configure` rather than read off the source, because
 * a setting written and then overwritten two lines later reads correctly and
 * behaves wrongly, and this is the one file where every one of them is set.
 */
class WebViewSettingsTest {

    private val settings = mockk<WebSettings>(relaxed = true)
    private val webView = mockk<WebView>(relaxed = true)

    @BeforeEach
    fun setUp() {
        // configure() logs through android.util.Log, an android.jar stub that
        // throws on the JVM.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any<String>()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        // And it calls the static WebView.setWebContentsDebuggingEnabled behind
        // this flag, which is another stub that throws. It reads false in this
        // suite only because nothing calls Logger.init, which is a global left
        // alone by every other file rather than anything this one arranges.
        // Pinned here so that the class states the condition it needs instead of
        // inheriting it from whatever else the JVM has already run.
        mockkObject(Logger)
        every { Logger.debugEnabled } returns false
        every { webView.settings } returns settings
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * A `content://` URI loaded by page content is spent against this app's own
     * persisted SAF grants, which are exactly the folders the user handed over.
     * Nothing in the app or its injected scripts ever loads one: a granted folder
     * is reconciled into a hash-named mirror under `filesDir` and reaches the page
     * as a POSIX path, so the capability had no user and one consumer, whatever
     * the editor happened to be rendering.
     */
    @Test
    fun `page content cannot load a content URI`() {
        VSCodroidWebView.configure(webView)

        verify { settings.allowContentAccess = false }
    }

    /**
     * Media that starts itself is the same abuse shape as a navigation that
     * launches an app with no gesture behind it, and this app has no autoplay of
     * its own to protect: the bundled media preview ships `autoPlay` false.
     */
    @Test
    fun `media does not start without a gesture`() {
        VSCodroidWebView.configure(webView)

        verify { settings.mediaPlaybackRequiresUserGesture = true }
    }

    /**
     * And the one that is deliberately left open, so that tightening it is a
     * decision somebody has to take rather than a tidy-up.
     *
     * Every webview document sits on an https origin, so a preview of a dev
     * server on the LAN is plain http inside an https document: mixed content,
     * and blocked under every other mode, including COMPATIBILITY, where an
     * iframe counts as active. Loopback is exempt from mixed-content blocking in
     * Chromium; a LAN address is not, and a LAN dev server is the ordinary thing
     * a user of this app opens.
     */
    @Test
    fun `mixed content stays allowed so a LAN dev server can be previewed`() {
        VSCodroidWebView.configure(webView)

        verify { settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW }
    }
}
