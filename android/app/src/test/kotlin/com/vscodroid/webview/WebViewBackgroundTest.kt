package com.vscodroid.webview

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
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
 * (measured on an API 36 emulator). It is the colour the workbench page last
 * painted, kept for the next start (`lastPageColor`), and the window colour before
 * it ever has. Asserted through `configure`, which every WebView of the editor goes
 * through, the one rebuilt after a renderer crash included.
 *
 * A plain-text page, and the WebView's own error page for a load that failed, paint
 * no background of their own, so the same colour sits behind their text, and
 * [addPlainTextPageScript] gives such a page the background of its own scheme
 * instead. `scripts/test-plain-text-page.js` runs that script; the plain-text case
 * here pins that every WebView is given it, which the script's own test cannot see.
 * Source reading, as in `UiScaleScriptWiringTest` and for its reason.
 *
 * The workbench page posts the colour it paints itself with, and
 * [addPageColorListener] makes it the view's and hands it to MainActivity, which
 * gives it to the window behind the view: that, not the view's background, is
 * what shows below the last page's frame when the soft keyboard goes down while a
 * folder opens, and behind the bars. `scripts/test-server-bootstrap.js` runs the
 * page's side; the cases here pin which messages are taken, that every WebView
 * listens before its first load, that the view is given the colour the page
 * posts, that the window is given the colour of each page the view shows, and
 * what `paintWindow` does with it.
 *
 * NEGATIVE CONTROL: without the `setBackgroundColor` call in `configure` the
 * first case fails, and with the window colour there rather than `lastPageColor`
 * the second. Without the `addPlainTextPageScript` call in `setupWebView`, or with
 * it after the first load, the plain-text case. Without the `addPageColorListener`
 * call, or with it after the first load, the listener case; taking a frame's
 * message, another host's, a short or translucent colour or one in another
 * notation fails the message case, and reading an ArrayBuffer's data, which
 * throws as it would in the listener, fails it too. Without the
 * `setBackgroundColor` or `onColor` call in the listener, or the `paintWindow`
 * call in `showPageColor`, or with none before the load in `setupWebView`,
 * `retryServerStart` or `showErrorPage`, or one there with the other page's
 * colour, the window case; and so does `paintWindow` without its background call
 * or with either bar appearance pinned.
 */
class WebViewBackgroundTest {

    private val webView = mockk<WebView>(relaxed = true)
    private val context = mockk<Context>()
    private val prefs = mockk<SharedPreferences>()

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
        every { context.getSharedPreferences("vscodroid", Context.MODE_PRIVATE) } returns prefs
        // Nothing kept: the default asked for is the answer.
        every { prefs.getInt("page_color", any()) } answers { secondArg() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a page that has not painted yet shows the window colour, not white`() {
        VSCodroidWebView.configure(webView)

        verify { webView.setBackgroundColor(WINDOW) }
    }

    @Test
    fun `a new view starts on the colour the workbench last painted`() {
        every { prefs.getInt("page_color", any()) } returns 0xFFFFFFFF.toInt()

        VSCodroidWebView.configure(webView)

        verify { webView.setBackgroundColor(0xFFFFFFFF.toInt()) }
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
                "first page, so under a dark theme the server's plain-text answers keep black text " +
                "on the WebView's dark background in light mode, and the WebView's error page in both modes"
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
        fun color(data: String?, host: String? = "127.0.0.1", isMainFrame: Boolean = true) =
            pageColorFromMessage(WebMessageCompat(data), host, isMainFrame)

        assertEquals(0xFFFFFFFF.toInt(), color("#ffffff"))
        assertEquals(0xFF1F1F1F.toInt(), color("#1F1F1F", "localhost"))
        // Any frame can post, webviews of extensions included, and only the
        // workbench page, the top frame on the loopback address, says what it paints.
        assertNull(color("#ffffff", isMainFrame = false)) { "a frame's colour was taken" }
        assertNull(color("#ffffff", "abc.vscode-cdn.net")) { "another host's colour was taken" }
        assertNull(color("#ffffff", null)) { "a page with no host had its colour taken" }
        // Only what the workbench writes for an opaque colour.
        for (data in listOf("#fff", "#ffffff80", "rgb(255, 255, 255)", " #ffffff", "#ffffff ", "#gggggg", "", null)) {
            assertNull(color(data)) { "'$data' was taken as a background colour" }
        }
        // An ArrayBuffer, which any page can post too and whose data throws when read.
        assertNull(pageColorFromMessage(WebMessageCompat(byteArrayOf(0)), "127.0.0.1", true)) {
            "an ArrayBuffer was taken as a background colour"
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
        val added = setup.indexOf("addPageColorListener(wv, ::showPageColor)")
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

    @Test
    fun `the window takes the colour of each page the view shows`() {
        // The workbench page's, as it posts it, which the view is given as well.
        // Read rather than driven: the listener cannot be captured on the JVM,
        // where WebViewCompat's static initialiser calls the android.jar stub of
        // Uri.parse.
        val listener = SourceScan.withoutComments(
            SourceScan.body(
                SourceScan.read("src/main/kotlin/com/vscodroid/webview/VSCodroidWebView.kt"),
                "internal fun addPageColorListener(",
            )
        )
        assertTrue("view.setBackgroundColor(color)" in listener) {
            "addPageColorListener no longer gives the view the colour the page posted, so after a theme " +
                "change the view keeps the one it started on, which shows wherever no page has painted yet"
        }
        assertTrue("onColor(color)" in listener) {
            "addPageColorListener no longer hands the colour the page posted on, so the window behind the " +
                "view keeps the one it had: #1E1E1E under a light theme, where the keyboard was and behind the bars"
        }
        val main = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")
        fun body(declaration: String) = SourceScan.withoutComments(SourceScan.body(main, declaration))
        assertTrue("paintWindow(color)" in body("private fun showPageColor(")) {
            "showPageColor no longer gives the window the colour the workbench page posts"
        }
        // What paintWindow does with it, read as well: a Window and its drawable are
        // android.jar stubs here. Whole lines, so a call left with another argument,
        // or an appearance pinned to one value, does not pass.
        val paint = SourceScan.withoutComments(
            SourceScan.body(SourceScan.read("src/main/kotlin/com/vscodroid/util/ViewInsets.kt"), "fun Activity.paintWindow(")
        ).lines().map { it.trim() }
        assertTrue("window.setBackgroundDrawable(color.toDrawable())" in paint) {
            "paintWindow no longer gives the window the colour, so it keeps the one it had: #1E1E1E under a " +
                "light theme, where the keyboard was and behind the bars"
        }
        for (line in listOf(
            "val light = isLightColor(color)",
            "isAppearanceLightStatusBars = light",
            "isAppearanceLightNavigationBars = light",
        )) {
            assertTrue(line in paint) {
                "paintWindow no longer has `$line`, so the bar icons do not follow the colour: light icons on a " +
                    "light theme's bars, or dark ones on a dark theme's"
            }
        }
        // The app's own pages, which post nothing: each paints the window its colour
        // before it loads, the kept one for the loading page and the theme's window
        // colour, #1E1E1E, for the error page drawn on it.
        listOf(
            Triple("private fun setupWebView()", "paintWindow(lastPageColor(this))", "wv.loadData("),
            Triple("private fun retryServerStart()", "paintWindow(lastPageColor(this))", "webView?.loadData("),
            Triple("private fun showErrorPage(", "paintWindow(getColor(R.color.colorBackground))", "webView?.loadDataWithBaseURL("),
        ).forEach { (declaration, paint, load) ->
            val code = body(declaration)
            val loaded = code.indexOf(load)
            assertTrue(loaded >= 0) { "$declaration no longer loads its page with $load, so this case measures nothing" }
            assertTrue(code.indexOf(paint) in 0 until loaded) {
                "$declaration does not call `$paint` before it loads its page, so the window around that page " +
                    "keeps the colour it had, which under a light theme is not the page's"
            }
        }
    }

    private companion object {
        /** What `colorBackground` resolves to, as the window background does. */
        const val WINDOW = 0xFF1E1E1E.toInt()
    }
}
