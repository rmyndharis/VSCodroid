package com.vscodroid

import android.content.Context
import android.content.SharedPreferences
import com.vscodroid.webview.keepPageColor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
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
 * loading page with its old literal colours, or its dark text kept on a light
 * colour, the second; the setup screen's light-ground text at #767676 the third;
 * and dropping any one of the calls the fourth names, or the starting window's
 * light theme, the fourth or the fifth.
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
        for (background in listOf(DARK, WHITE)) {
            val page = loadingPageHtml(background, "Starting server...")
            val hex = "#%06x".format(background and 0xFFFFFF)
            assertTrue("<body style=\"background:$hex;" in page) { "the loading page is not painted $hex" }
            // The heading and the line under it, each against that colour.
            val colors = Regex("color:(#[0-9a-f]{3,6})").findAll(page).map { it.groupValues[1] }.toList()
            assertTrue(colors.size == 2) { "expected the line's and the heading's colour, found $colors" }
            for (text in colors) {
                assertTrue(contrast(parse(text), background) >= 4.5) {
                    "$text on $hex measures %.2f:1, below 4.5:1".format(Locale.ROOT, contrast(parse(text), background))
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
        // The status line is the dimmer of the two, at android:alpha 0.7 in activity_splash.xml.
        val dimmed = blend(text!!, WHITE, 0.7)
        assertTrue(contrast(dimmed, WHITE) >= 4.5) {
            "the status line on white measures %.2f:1, below 4.5:1".format(Locale.ROOT, contrast(dimmed, WHITE))
        }
    }

    @Test
    fun `every screen of a start begins on the kept colour`() {
        val main = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")
        val splash = SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt")
        fun body(source: String, declaration: String) =
            SourceScan.withoutComments(SourceScan.body(source, declaration))

        mapOf(
            "MainActivity.showPageColor" to (body(main, "private fun showPageColor(") to "keepPageColor(this, color)"),
            "MainActivity.setupWebView" to (body(main, "private fun setupWebView()") to "paintWindow(lastPageColor(this))"),
            "MainActivity.retryServerStart" to (body(main, "private fun retryServerStart()") to "paintWindow(lastPageColor(this))"),
            // An expression body, which SourceScan.body cannot bound: the line after it.
            "MainActivity.loadingPage" to (
                SourceScan.withoutComments(main).substringAfter("private fun loadingPage(): String =").lines()[1]
                    to "loadingPageHtml(lastPageColor(this)"
                ),
            "SplashActivity.onCreate" to (body(splash, "override fun onCreate(") to "paintWindow(lastPageColor(this))"),
            "SplashActivity.showSplashLayout" to (body(splash, "private fun showSplashLayout()") to "isLightColor(lastPageColor(this))"),
        ).forEach { (where, pair) ->
            val (code, call) = pair
            assertTrue(call in code) {
                "$where no longer has $call, so under a light theme that screen starts dark again"
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
        val main = SourceScan.withoutComments(
            SourceScan.body(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"), "private fun showPageColor(")
        )
        assertTrue("splashScreen.setSplashScreenTheme(" in main && "R.style.Theme_VSCodroid_LightStart" in main) {
            "showPageColor no longer chooses the theme the system draws its starting window from"
        }
        val style = Regex("""<style\s+name="Theme\.VSCodroid\.LightStart".*?</style>""", RegexOption.DOT_MATCHES_ALL)
            .find(File("src/main/res/values/themes.xml").readText())?.value
        assertTrue(style != null && "<item name=\"android:windowSplashScreenBackground\">@android:color/white</item>" in style) {
            "Theme.VSCodroid.LightStart no longer gives the starting window a white background"
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
        const val DARK = 0xFF1E1E1E.toInt()
    }
}
