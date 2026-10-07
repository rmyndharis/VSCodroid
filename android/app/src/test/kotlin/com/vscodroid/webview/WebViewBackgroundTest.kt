package com.vscodroid.webview

import android.content.Context
import android.webkit.WebView
import com.vscodroid.R
import com.vscodroid.SourceScan
import com.vscodroid.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What the WebView shows where no page has painted yet.
 *
 * Unset, that is white, and on the first launch after an update it showed as two
 * white frames fading in over the dark splash before the loading page painted
 * (measured on an API 36 emulator). Asserted through `configure`, which every
 * WebView of the editor goes through, the one rebuilt after a renderer crash
 * included.
 *
 * A plain-text page paints no background of its own, so the same colour sits
 * behind its text, and [addPlainTextPageScript] gives such a page the background of
 * its own scheme instead. `scripts/test-plain-text-page.js` runs that script; the
 * second case here pins that every WebView is given it, which the script's own test
 * cannot see. Source reading, as in `UiScaleScriptWiringTest` and for its reason.
 *
 * NEGATIVE CONTROL: without the `setBackgroundColor` call in `configure` the
 * first case fails, and without the `addPlainTextPageScript` call in
 * `setupWebView`, or with it after the first load, the second.
 */
class WebViewBackgroundTest {

    private val webView = mockk<WebView>(relaxed = true)
    private val context = mockk<Context>()

    @BeforeEach
    fun setUp() {
        // configure() logs through android.util.Log and, behind Logger.debugEnabled,
        // calls a static WebView method; both are android.jar stubs that throw on
        // the JVM. See WebViewSettingsTest, which needs the same.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any<String>()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        mockkObject(Logger)
        every { Logger.debugEnabled } returns false
        every { webView.context } returns context
        every { context.getColor(R.color.colorBackground) } returns WINDOW
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a page that has not painted yet shows the window colour, not white`() {
        VSCodroidWebView.configure(webView)

        verify { webView.setBackgroundColor(WINDOW) }
    }

    @Test
    fun `every WebView gets the plain-text page script before its first load`() {
        val setup = SourceScan.withoutComments(
            SourceScan.body(
                SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"),
                "private fun setupWebView()",
            )
        )
        val added = setup.indexOf("addPlainTextPageScript(wv)")
        val loaded = setup.indexOf("wv.loadData(")
        assertTrue(loaded >= 0) { "setupWebView no longer loads its placeholder, so this case measures nothing" }
        assertTrue(added in 0 until loaded) {
            "setupWebView does not add the plain-text page script before the WebView loads its " +
                "first page, so the server's plain-text answers keep black text on the dark " +
                "background in light mode"
        }

        // Every origin, for the reason addUiScaleScript gives: the port is not known yet.
        val add = SourceScan.withoutComments(
            SourceScan.body(
                SourceScan.read("src/main/kotlin/com/vscodroid/webview/VSCodroidWebView.kt"),
                "internal fun addPlainTextPageScript(",
            )
        )
        assertTrue(
            "WebViewCompat.addDocumentStartJavaScript(webView, plainTextPageScript(), setOf(\"*\"))" in add
        ) { "addPlainTextPageScript no longer adds plainTextPageScript() for every origin" }
    }

    private companion object {
        /** What `colorBackground` resolves to, as the window background does. */
        const val WINDOW = 0xFF1E1E1E.toInt()
    }
}
