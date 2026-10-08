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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
 * A plain-text page, and the WebView's own error page for a load that failed, paint
 * no background of their own, so the same colour sits behind their text, and
 * [addPlainTextPageScript] gives such a page the background of its own scheme
 * instead. `scripts/test-plain-text-page.js` runs that script; the second case here
 * pins that every WebView is given it, which the script's own test cannot see.
 * Source reading, as in `UiScaleScriptWiringTest` and for its reason.
 *
 * The view's background also shows where the workbench page has not painted at a
 * new size, as below the last page's frame when the soft keyboard goes down while
 * a folder opens, so the page posts the colour it paints itself with and
 * [addPageColorListener] makes it the view's. `scripts/test-server-bootstrap.js`
 * runs the page's side; the cases here pin which messages are taken and that every
 * WebView listens before its first load.
 *
 * NEGATIVE CONTROL: without the `setBackgroundColor` call in `configure` the
 * first case fails, and without the `addPlainTextPageScript` call in
 * `setupWebView`, or with it after the first load, the second. Without the
 * `addPageColorListener` call, or with it after the first load, the fourth; taking
 * a frame's message, another host's, a short or translucent colour or one in
 * another notation fails the third.
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
                "background in light mode, and the WebView's error page in both modes"
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

    @Test
    fun `only the workbench page's opaque colour becomes the view's background`() {
        assertEquals(0xFFFFFFFF.toInt(), pageColorFromMessage("#ffffff", "127.0.0.1", true))
        assertEquals(0xFF1F1F1F.toInt(), pageColorFromMessage("#1F1F1F", "localhost", true))
        // Any frame can post, webviews of extensions included, and only the
        // workbench page, the top frame on the loopback address, says what it paints.
        assertNull(pageColorFromMessage("#ffffff", "127.0.0.1", false)) { "a frame's colour was taken" }
        assertNull(pageColorFromMessage("#ffffff", "abc.vscode-cdn.net", true)) { "another host's colour was taken" }
        assertNull(pageColorFromMessage("#ffffff", null, true)) { "a page with no host had its colour taken" }
        // Only what the workbench writes for an opaque colour.
        for (data in listOf("#fff", "#ffffff80", "rgb(255, 255, 255)", " #ffffff", "#ffffff ", "#gggggg", "", null)) {
            assertNull(pageColorFromMessage(data, "127.0.0.1", true)) { "'$data' was taken as a background colour" }
        }
    }

    @Test
    fun `every WebView listens for the page's colour before its first load`() {
        val setup = SourceScan.withoutComments(
            SourceScan.body(
                SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"),
                "private fun setupWebView()",
            )
        )
        val added = setup.indexOf("addPageColorListener(wv)")
        val loaded = setup.indexOf("wv.loadData(")
        assertTrue(loaded >= 0) { "setupWebView no longer loads its placeholder, so this case measures nothing" }
        assertTrue(added in 0 until loaded) {
            "setupWebView does not add the page colour listener before the WebView loads its first page, " +
                "so the workbench page has nothing to post its colour to and the space the soft keyboard " +
                "gives back stays the window colour under a light theme"
        }
        val add = SourceScan.withoutComments(
            SourceScan.body(
                SourceScan.read("src/main/kotlin/com/vscodroid/webview/VSCodroidWebView.kt"),
                "internal fun addPageColorListener(",
            )
        )
        assertTrue("WebViewCompat.addWebMessageListener(webView, PAGE_COLOR_OBJECT, setOf(\"*\"))" in add) {
            "addPageColorListener no longer listens under PAGE_COLOR_OBJECT for every origin"
        }
    }

    private companion object {
        /** What `colorBackground` resolves to, as the window background does. */
        const val WINDOW = 0xFF1E1E1E.toInt()
    }
}
