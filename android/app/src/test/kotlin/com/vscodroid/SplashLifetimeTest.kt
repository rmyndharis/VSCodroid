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
 * what an interruption costs depends on which run is interrupted.
 * `extractAssetFile` skips a file it finds at the asset's own length, but only
 * under `resumeSameBuild`: a retry of this exact build on an install no earlier
 * build ever completed under. Every other run, an upgrade above all, re-copies
 * from zero, because equal length is not equal content. Two things decide
 * whether the extraction is interrupted at all, and neither is visible from the
 * code that does the extracting.
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
                "system may reclaim mid-write. An upgrade then re-copies the whole " +
                "tree, since the skip on the next attempt is licensed only for a " +
                "retry of the same build on an install nothing ever completed under."
        }
    }

    /**
     * The other half of the flag, and the half nothing asked for.
     *
     * `showSetupError` leaves the extraction layout in place: it rewrites the
     * status line, hides the progress bar and adds a Retry button into the same
     * root. Nothing is in flight after that and the screen changes only when a
     * person taps, so the argument the layout makes for holding the display awake
     * has expired, and the layout's own comment makes exactly that argument
     * against the picker one screen later. Held anyway, a phone put down or
     * pocketed on the failure screen lights until the battery is flat.
     *
     * NEGATIVE CONTROL: delete `root.keepScreenOn = false` from `showSetupError`
     * and the first assertion goes red; delete `root.keepScreenOn = true` from
     * the Retry listener and the second does. Both measured.
     */
    @Test
    fun `the failure screen stops holding the display awake`() {
        val onLayout = layoutRoot().getAttribute("android:keepScreenOn") == "true"
        val onWindow = code().any { it.contains("FLAG_KEEP_SCREEN_ON") }
        assertTrue(onLayout || onWindow) {
            "nothing holds the display awake at all, so this test has no subject; " +
                "see the test above"
        }

        val error = body("showSetupError")
        assertTrue(error.isNotEmpty()) { "showSetupError is empty; this test is measuring nothing" }

        assertTrue(error.any { Regex("""keepScreenOn\s*=\s*false""").containsMatchIn(it) }) {
            "showSetupError leaves the display held awake on a screen that waits " +
                "for a person and changes for nothing else. The reachable way in is " +
                "a phone with under ~875 MB free, which fails the storage pre-flight " +
                "on first launch."
        }
        assertTrue(error.any { Regex("""keepScreenOn\s*=\s*true""").containsMatchIn(it) }) {
            "nothing gives the flag back, so tapping Retry restarts an 800 MB " +
                "extraction with the display free to time out part-way through it " +
                "again. The skip makes this retry cheaper than the first attempt " +
                "was; it does not make being stopped free"
        }
    }

    /**
     * The splash root is padded for the system bars, like every other full-screen
     * root in this app.
     *
     * `SplashActivity.onCreate` calls `drawBehindSystemBars()`, so this window
     * draws under the status and navigation bars. `pickerRoot`, `progressRoot`
     * and `toolchainRoot` are each padded at their own `setContentView`; this one
     * was not, because its root carried no id to look it up by. The child that
     * pays for it is the Retry button `showSetupError` adds, which is anchored to
     * the bottom of the root.
     *
     * NEGATIVE CONTROL: drop the `padForSystemBars()` line from
     * `showSplashLayout` and the second assertion goes red; inline the
     * `setContentView` back into either caller and the first does. Both measured.
     */
    @Test
    fun `the splash layout is shown from the one place that pads it`() {
        val shows = code().filter { it.contains("setContentView(R.layout.activity_splash)") }
        assertEquals(1, shows.size) {
            "activity_splash is shown from ${shows.size} places:\n${shows.joinToString("\n")}\n" +
                "One of them will be the one that forgets the insets. It belongs in " +
                "showSplashLayout() alone."
        }

        val show = body("showSplashLayout")
        assertTrue(show.any { it.contains("padForSystemBars") }) {
            "the splash root is not padded, so this edge-to-edge window draws its " +
                "one control under the navigation bar:\n${show.joinToString("\n")}"
        }
        assertTrue(show.any { it.contains("R.id.splashRoot") }) {
            "showSplashLayout pads something other than the splash root"
        }
    }

    /**
     * The Retry button is held against the bottom of the root, not only under the
     * message.
     *
     * It is the single control on that screen, there is no scroll container, and
     * the message above it is the failed step plus up to `DETAIL_LIMIT`
     * characters of the cause in a packed chain that grows downward as it wraps.
     * Anchored to the message alone, a long cause in landscape or at a raised
     * font scale puts the button past the bottom of the window with no way to
     * reach it, and reinstalling is the only way out.
     *
     * NEGATIVE CONTROL: remove `bottomToBottom` from the button's LayoutParams
     * and this goes red, naming the button. Measured.
     */
    @Test
    fun `the retry button cannot be pushed off the bottom of the screen`() {
        val error = body("showSetupError")
        assertTrue(error.any { it.contains("bottomToBottom") }) {
            "the Retry button is constrained downward from the message only, so a " +
                "long failure detail can push the only control on the screen out of " +
                "the window:\n${error.joinToString("\n")}"
        }
        assertTrue(error.any { it.contains("verticalBias") }) {
            "a bottom constraint without the bias against it centres the button in " +
                "the gap instead of pinning it, which is the same overflow one half " +
                "as far down"
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
