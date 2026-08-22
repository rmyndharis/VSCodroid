package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What has to hold for the screen that unpacks the app.
 *
 * First-run setup writes out some 810 MB with nothing for the user to touch, and
 * `extractAssetFile` has no skip-if-present branch, so an interruption is not a
 * pause: the next attempt starts from zero. Two things decide whether it is
 * interrupted and what it costs, and neither is visible from the code that does
 * the extracting.
 *
 * Source and layout reading, which is the weaker layer in this suite. It is what
 * is available: both subjects are an Activity's lifecycle and a resource
 * attribute, and no plain JVM test can build either.
 */
class SplashLifetimeTest {

    private val source = File("src/main/kotlin/com/vscodroid/SplashActivity.kt")
    private val layout = File("src/main/res/layout/activity_splash.xml")

    /**
     * The source with comments dropped.
     *
     * Prose about a rule must not be able to satisfy a search for the rule: this
     * file explains at length what it does and why, so a check over raw text
     * would read its own justification as its implementation.
     */
    private fun code(): List<String> {
        check(source.isFile) {
            "SplashActivity.kt not found at ${source.absolutePath}; this test would " +
                "otherwise pass by looking at nothing"
        }
        return source.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
    }

    /** One method's body, from its declaration to the next one at class level. */
    private fun body(name: String): List<String> {
        val lines = code()
        val start = lines.indexOfFirst { it.contains("fun $name(") }
        check(start >= 0) { "$name is gone from SplashActivity; this test is measuring nothing" }
        val boundary = Regex("^ {4}(private |internal |public |protected |override )*fun ")
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { boundary.containsMatchIn(it) || it == "}" }
        return if (end < 0) rest else rest.take(end)
    }

    private fun layoutRoot(): Element {
        check(layout.isFile) {
            "activity_splash.xml not found at ${layout.absolutePath}; this test would " +
                "otherwise pass by looking at nothing"
        }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(layout).documentElement
    }

    @Test
    fun `the extraction screen holds the display awake`() {
        // The display timing out is not a cosmetic event here. It stops the
        // window being visible, which stops the activity, and a process holding
        // nothing but a stopped activity is the first thing the system reclaims
        // while it is part-way through writing 810 MB.
        //
        // Either implementation satisfies this: the attribute on the layout the
        // extraction is drawn in, or the window flag raised for the duration. What
        // must not happen is neither.
        val root = layoutRoot()
        val onLayout = root.getAttribute("android:keepScreenOn") == "true"
        val onWindow = code().any { it.contains("FLAG_KEEP_SCREEN_ON") }

        assertTrue(onLayout || onWindow) {
            "nothing holds the display awake while first-run setup runs. It takes " +
                "minutes with no reason for the user to touch the screen, so the " +
                "timeout stops the activity and the extraction becomes a process the " +
                "system may reclaim mid-write, with no partial progress to resume from."
        }
    }

    @Test
    fun `every launch that is past setup asks whether the picker was answered`() {
        // The picker is offered once and answering it is what records the answer,
        // so a launch that reaches the editor without asking spends the only offer
        // there is. It used to be asked from the continuation of the setup
        // coroutine alone, which a relaunch cancels while the extraction runs on to
        // completion, and the pref that gates it stays false for good.
        val onCreate = body("onCreate")
        assertTrue(onCreate.isNotEmpty()) { "onCreate is empty; this test is measuring nothing" }

        val direct = onCreate.filter { it.contains("launchMain()") }
        assertTrue(direct.isEmpty()) {
            "onCreate reaches the editor without asking whether the toolchain picker " +
                "has been answered:\n${direct.joinToString("\n")}\nA launch that skips " +
                "the question can never ask it again: the only other way in is a " +
                "launcher long-press nothing tells the user about."
        }

        val asks = onCreate.count { it.contains("continueAfterSetup()") }
        assertEquals(2, asks) {
            "both routes out of the already-set-up branch have to go through the one " +
                "place that decides, the Python reconcile and the plain launch; found " +
                "$asks call(s)"
        }
    }

    @Test
    fun `the picker decision is made in one place`() {
        // The control for the case above, and the half that rots first. Two
        // callers is how it started: the question was asked in the setup
        // continuation and nowhere else, and the second site added later is what
        // makes a check on the first look satisfied.
        val callers = code().filter {
            it.contains("shouldShowPicker()") && !it.contains("fun shouldShowPicker")
        }
        assertEquals(1, callers.size) {
            "shouldShowPicker() has ${callers.size} call sites:\n" +
                "${callers.joinToString("\n")}\nOne of them will be forgotten when a " +
                "route is added. It belongs in continueAfterSetup() alone."
        }
        assertTrue(body("continueAfterSetup").any { it.contains("shouldShowPicker()") }) {
            "the one caller is no longer continueAfterSetup(), which is what every " +
                "route out of onCreate calls"
        }
    }
}
