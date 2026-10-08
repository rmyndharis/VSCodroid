package com.vscodroid

import android.content.Context
import android.content.SharedPreferences
import com.vscodroid.util.isLightColor
import com.vscodroid.webview.SAVED_SPLASH_COLOR_SCRIPT
import com.vscodroid.webview.hasKeptPageColor
import com.vscodroid.webview.keepPageColor
import com.vscodroid.webview.savedPageColor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import kotlin.math.pow

/**
 * The screens before the workbench start on the colour it last painted.
 *
 * Under a light theme a cold start was dark until the workbench painted, 2.3 s on
 * an API 36 emulator, and the first launch after an update 40 s: the system's
 * starting window, the setup screen, the loading page and the bars around them
 * were all the theme's #1E1E1E. MainActivity keeps each colour the page posts
 * (`keepPageColor`), each of those screens starts on it (`lastPageColor`), and the
 * system's starting window, which is drawn before this app runs, is pointed at a
 * light theme for the launches after.
 *
 * Activities cannot be built on the JVM, so their wiring is read off the source as
 * the other [SourceScan] cases do; the colour store and the loading page are driven.
 *
 * NEGATIVE CONTROL: writing the colour on every post fails the first case; the
 * loading page with its old literal colours, its dark text kept on a light
 * colour, or the #888 line it had on #1E1E1E on every dark colour, the second;
 * the setup screen's light-ground text at #767676 the third; dropping any one of
 * the calls the fourth names or either of the setup screen's two text colours,
 * or turning round the test that chooses them, the fourth; dropping the
 * starting window's light theme, swapping it with the app's own, or choosing it
 * only when the kept colour stays the same, the fifth; and reading the saved
 * colour where one is kept, without a port, after setup has started, from another
 * key or without storage, taking a short colour, painting it over the picker or not
 * keeping it, leaving the view that reads it alive, or letting a view that cannot be
 * made end the launch, the sixth.
 */
class StartColorTest {

    @Test
    fun `a colour is kept once, and only when it changed`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences> { every { edit() } returns editor }
        val context = mockk<Context> {
            every { getSharedPreferences("vscodroid", Context.MODE_PRIVATE) } returns prefs
        }

        every { prefs.getInt("page_color", 0) } returns 0
        assertTrue(keepPageColor(context, WHITE)) { "a first colour is not kept" }
        verify(exactly = 1) { editor.putInt("page_color", WHITE) }

        // Each load posts the colour it starts on: the same one is not written again.
        every { prefs.getInt("page_color", 0) } returns WHITE
        assertFalse(keepPageColor(context, WHITE)) { "the colour kept already is kept again" }
        verify(exactly = 1) { editor.putInt(any(), any()) }
    }

    @Test
    fun `the loading page takes the kept colour, with text that reads on it`() {
        for ((where, background) in EDITOR_COLORS + BOUNDS) {
            val page = loadingPageHtml(background, "Starting server...")
            val hex = "#%06x".format(background and 0xFFFFFF)
            assertTrue("<body style=\"background:$hex;" in page) { "the loading page is not painted $hex" }
            // The heading and the line under it, each against that colour.
            val colors = Regex("color:(#[0-9a-f]{3,6})").findAll(page).map { it.groupValues[1] }.toList()
            assertTrue(colors.size == 2) { "expected the line's and the heading's colour, found $colors" }
            for (text in colors) {
                assertTrue(contrast(parse(text), background) >= 4.5) {
                    "$text on $hex ($where) measures %.2f:1, below 4.5:1"
                        .format(Locale.ROOT, contrast(parse(text), background))
                }
            }
        }
    }

    @Test
    fun `the setup screen's text on a light editor colour clears the ratio dimmed`() {
        val colors = File("src/main/res/values/colors.xml").readText()
        val text = Regex("""<color name="colorOnLightBackground">#([0-9A-Fa-f]{6})</color>""")
            .find(colors)?.groupValues?.get(1)?.toInt(16)
        assertTrue(text != null) { "colors.xml does not define colorOnLightBackground as #rrggbb" }
        for ((where, ground) in EDITOR_COLORS.filterValues(::isLightColor)) {
            // The status line is the dimmer of the two, at android:alpha 0.7 in activity_splash.xml.
            val dimmed = blend(text!!, ground, 0.7)
            assertTrue(contrast(dimmed, ground) >= 4.5) {
                "the status line on #%06x (%s) measures %.2f:1, below 4.5:1"
                    .format(Locale.ROOT, ground and 0xFFFFFF, where, contrast(dimmed, ground))
            }
        }
    }

    @Test
    fun `every screen of a start begins on the kept colour`() {
        val main = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")
        val splash = SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt")
        fun body(source: String, declaration: String) =
            SourceScan.withoutComments(SourceScan.body(source, declaration))

        mapOf(
            "MainActivity.showPageColor" to (body(main, "private fun showPageColor(") to "keepStartColor(color)"),
            "MainActivity.setupWebView" to (body(main, "private fun setupWebView()") to "paintWindow(lastPageColor(this))"),
            "MainActivity.retryServerStart" to (body(main, "private fun retryServerStart()") to "paintWindow(lastPageColor(this))"),
            // An expression body, which SourceScan.body cannot bound: the line after it.
            "MainActivity.loadingPage" to (
                SourceScan.withoutComments(main).substringAfter("private fun loadingPage(): String =").lines()[1]
                    to "loadingPageHtml(lastPageColor(this)"
                ),
            "SplashActivity.onCreate" to (body(splash, "override fun onCreate(") to "paintWindow(lastPageColor(this))"),
        ).forEach { (where, pair) ->
            val (code, call) = pair
            assertTrue(call in code) {
                "$where no longer has $call, so under a light theme that screen starts dark again"
            }
        }
        // The setup screen's two texts, light for the theme's dark window, made dark on
        // a light colour. Whole lines, so the test turned round, or a text left with
        // another colour, does not pass.
        assertTrue("tintSplashText()" in body(splash, "private fun showSplashLayout()")) {
            "showSplashLayout no longer gives the setup screen's text the colour that reads on the editor's"
        }
        val layout = body(splash, "private fun tintSplashText()").lines().map { it.trim() }
        for (line in listOf(
            "if (isLightColor(lastPageColor(this))) {",
            "val text = getColor(R.color.colorOnLightBackground)",
            "findViewById<TextView>(R.id.appName).setTextColor(text)",
            "findViewById<TextView>(R.id.statusText).setTextColor(text)",
        )) {
            assertTrue(line in layout) {
                "tintSplashText no longer has `$line`, so the setup screen's text does not follow the editor " +
                    "colour: light text on a light one, or dark text on a dark one"
            }
        }
        // Drawn for the theme's dark window, whatever colour onCreate gave it.
        val picker = body(splash, "private fun showToolchainPicker()")
        val painted = picker.indexOf("paintWindow(getColor(R.color.colorBackground))")
        assertTrue(painted in 0 until picker.indexOf("setContentView(")) {
            "the toolchain picker is shown on the editor's colour, where its light text cannot be read"
        }
    }

    @Test
    fun `a light editor points the next starting window at a light theme`() {
        // Whole lines, so the two themes swapped, or the guard turned round, does not pass.
        val main = SourceScan.withoutComments(
            SourceScan.body(
                SourceScan.read("src/main/kotlin/com/vscodroid/webview/VSCodroidWebView.kt"),
                "internal fun Activity.keepStartColor(",
            )
        ).lines().map { it.trim() }
        assertTrue("splashScreen.setSplashScreenTheme(" in main) {
            "keepStartColor no longer chooses the theme the system draws its starting window from"
        }
        assertTrue("if (!keepPageColor(this, color)) return" in main) {
            "keepStartColor no longer chooses the starting window only when the kept colour changes, so it " +
                "follows a change one post late and is chosen again on every load"
        }
        assertTrue("if (isLightColor(color)) R.style.Theme_VSCodroid_LightStart else Resources.ID_NULL" in main) {
            "keepStartColor no longer gives a light colour alone the white starting window, so the next start " +
                "under a light theme begins dark, or under a dark one white"
        }
        val style = Regex("""<style\s+name="Theme\.VSCodroid\.LightStart".*?</style>""", RegexOption.DOT_MATCHES_ALL)
            .find(File("src/main/res/values/themes.xml").readText())?.value
        assertTrue(style != null && "<item name=\"android:windowSplashScreenBackground\">@android:color/white</item>" in style) {
            "Theme.VSCodroid.LightStart no longer gives the starting window a white background"
        }
    }

    @Test
    fun `a start with no colour kept reads the editor's from the workbench page's storage`() {
        // What evaluateJavascript hands back: the script's answer, as a JSON string.
        assertEquals(WHITE, savedPageColor("\"#ffffff\""))
        assertEquals(0xFF1F1F1F.toInt(), savedPageColor("\"#1F1F1F\""))
        for (result in listOf("\"\"", "null", "\"#fff\"", "\"#ffffff80\"", "\"rgb(255, 255, 255)\"", null)) {
            assertNull(savedPageColor(result)) { "'$result' was taken as the editor's colour" }
        }
        // The splash the workbench saves in its page's storage, and the part of it
        // that is the editor's colour.
        assertTrue(
            "localStorage.getItem('monaco-parts-splash')" in SAVED_SPLASH_COLOR_SCRIPT &&
                "s.colorInfo.background" in SAVED_SPLASH_COLOR_SCRIPT
        ) { "the script no longer reads the editor colour of the splash the workbench saves" }

        val prefs = mockk<SharedPreferences>()
        val context = mockk<Context> {
            every { getSharedPreferences("vscodroid", Context.MODE_PRIVATE) } returns prefs
        }
        every { prefs.contains("page_color") } returns false
        assertFalse(hasKeptPageColor(context)) { "a colour is taken as kept where none is" }
        every { prefs.contains("page_color") } returns true
        assertTrue(hasKeptPageColor(context)) { "a kept colour is not taken as kept" }

        // The wiring, read off the source: only where nothing is kept and a port is
        // remembered, before setup runs, so the colour reaches most of it.
        val splash = SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt")
        fun lines(source: String, declaration: String) =
            SourceScan.withoutComments(SourceScan.body(source, declaration)).lines().map { it.trim() }
        val create = lines(splash, "override fun onCreate(")
        val asked = listOf(
            "if (!hasKeptPageColor(this)) {",
            "val port = PortFinder.rememberedPort(this)",
            "if (port > 0) {",
            "colorProbe = readSavedPageColor(this, port) { color ->",
            "if (color != null) showSavedPageColor(color)",
        ).map { line -> create.indexOf(line).also { assertTrue(it >= 0) { "SplashActivity.onCreate no longer has `$line`" } } }
        assertTrue(asked == asked.sorted() && asked.last() < create.indexOfFirst { it.startsWith("runSetupWithRetry(") }) {
            "SplashActivity.onCreate does not read the saved colour, in that order, before setup runs, so the " +
                "first start after an update from a build that kept none runs its setup dark under a light theme"
        }
        val shown = lines(splash, "private fun showSavedPageColor(")
        val steps = listOf(
            "keepStartColor(color)",
            "if (findViewById<View>(R.id.splashRoot) == null) return",
            "paintWindow(color)",
            "tintSplashText()",
        ).map { line -> shown.indexOf(line).also { assertTrue(it >= 0) { "showSavedPageColor no longer has `$line`" } } }
        assertTrue(steps == steps.sorted()) {
            "showSavedPageColor does not keep the colour for the screens after, and give it to the setup screen " +
                "only while that is up, in that order, so the toolchain picker can be painted a colour its light " +
                "text does not read on"
        }
        assertTrue("colorProbe?.destroy()" in lines(splash, "override fun onDestroy()")) {
            "SplashActivity no longer lets the view that reads the colour go with it"
        }
        // On the workbench page's own origin, as MainActivity loads it, with storage.
        // Without the comment strip, which takes the `//` of the address for one.
        val read = SourceScan.body(
            SourceScan.read("src/main/kotlin/com/vscodroid/webview/VSCodroidWebView.kt"),
            "internal fun readSavedPageColor(",
        ).lines().map { it.trim() }
        val noColor = "it reads no colour, or another origin's"
        // A WebView provider missing or being replaced throws from the constructor,
        // and the setup beside this needs no view.
        val noView = "a WebView that cannot be made ends the launch, where the setup needs none"
        // The renderer is the editor's too, and a client that does not take its loss
        // takes the app down with it.
        val lost = "a renderer lost while it reads takes the app down with it"
        for ((line, why) in listOf(
            "probe.settings.domStorageEnabled = true" to noColor,
            "probe.loadDataWithBaseURL(\"http://127.0.0.1:\$port/\", \"<!DOCTYPE html><title></title>\", \"text/html\", \"utf-8\", null)" to noColor,
            "if (!answered) view.evaluateJavascript(SAVED_SPLASH_COLOR_SCRIPT) { answer(savedPageColor(it)) }" to noColor,
            "} catch (t: Throwable) {" to noView,
            "return null" to noView,
            "override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {" to lost,
            "answer(null)" to lost,
            "return true" to lost,
        )) {
            assertTrue(line in read) { "readSavedPageColor no longer has `$line`, so $why" }
        }
    }

    private fun parse(css: String): Int {
        val h = css.removePrefix("#")
        val full = if (h.length == 3) h.map { "$it$it" }.joinToString("") else h
        return full.toInt(16)
    }

    private fun luminance(rgb: Int): Double {
        fun linear(shift: Int): Double {
            val c = (rgb shr shift and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** [fg] at [alpha] over [bg], composited in sRGB as View.setAlpha does. */
    private fun blend(fg: Int, bg: Int, alpha: Double): Int =
        listOf(16, 8, 0).fold(0) { acc, shift ->
            val c = ((fg shr shift and 0xFF) * alpha + (bg shr shift and 0xFF) * (1 - alpha)).toInt()
            acc or (c shl shift)
        }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()

        /**
         * The editor colour of every theme the 1.139.1 server ships, which is the
         * colour the page posts and the next loading page starts on.
         */
        val EDITOR_COLORS = mapOf(
            "Light Modern, Light+, Light 2026, Visual Studio Light, High Contrast Light" to WHITE,
            "Quiet Light" to 0xFFF5F5F5.toInt(),
            "Solarized Light" to 0xFFFDF6E3.toInt(),
            "Dark Modern" to 0xFF1F1F1F.toInt(),
            "Dark+, Visual Studio Dark, Monokai Dimmed, the window" to 0xFF1E1E1E.toInt(),
            "Dark 2026" to 0xFF121314.toInt(),
            "Abyss" to 0xFF000C18.toInt(),
            "Kimbie Dark" to 0xFF221A0F.toInt(),
            "Monokai" to 0xFF272822.toInt(),
            "Red" to 0xFF390000.toInt(),
            "Solarized Dark" to 0xFF002B36.toInt(),
            "Tomorrow Night Blue" to 0xFF002451.toInt(),
            "High Contrast" to 0xFF000000.toInt(),
        )

        /** The greys nearest the middle that loadingPageHtml says its text still clears. */
        val BOUNDS = mapOf(
            "the lightest dark colour promised" to 0xFF3F3F3F.toInt(),
            "the darkest light colour promised" to 0xFFDCDCDC.toInt(),
        )
    }
}
