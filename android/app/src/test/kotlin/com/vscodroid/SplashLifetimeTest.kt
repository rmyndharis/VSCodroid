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

    /** Parsed once per test, so two lookups return nodes of the same document. */
    private val parsed: Element by lazy { layoutRoot() }

    /** The element carrying `android:id="@+id/<id>"`, wherever it sits in the layout. */
    private fun viewById(id: String): Element {
        val all = parsed.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val e = all.item(i) as Element
            if (e.getAttribute("android:id") == "@+id/$id") return e
        }
        throw AssertionError("no view with id $id in ${layout.name}; this test is measuring nothing")
    }

    private fun ancestorsOf(view: Element): List<Element> {
        val out = mutableListOf<Element>()
        var node = view.parentNode
        while (node is Element) {
            out.add(node)
            node = node.parentNode
        }
        return out
    }

    /**
     * The Retry button is held against the bottom of the root, and the content
     * above it ends at its top.
     *
     * It is the single control on that screen, and the message above it is the
     * failed step plus up to `DETAIL_LIMIT` characters of the cause. Where the
     * button used to be built in code and anchored to the bottom, a long cause in
     * landscape or at a raised font scale could still run under it, because the
     * message had no container to scroll in. Now the layout says both halves: the
     * button is a direct child of the root with its bottom on the root's, and the
     * scroll container's bottom is the button's top, so neither can cover the
     * other.
     *
     * NEGATIVE CONTROL: remove `layout_constraintBottom_toBottomOf` from the
     * button, point the scroll's bottom at `parent`, or move the button inside the
     * scroll, and each goes red. Measured.
     */
    @Test
    fun `the retry button cannot be pushed off the bottom of the screen`() {
        val button = viewById("retryButton")
        assertEquals(parsed, button.parentNode) {
            "the Retry button is not a direct child of the root, so it scrolls with " +
                "the message it is meant to stay clear of"
        }
        assertEquals("parent", button.getAttribute("app:layout_constraintBottom_toBottomOf")) {
            "the Retry button is not held against the bottom of the root, so a long " +
                "failure detail can push the only control on the screen out of the window"
        }
        assertEquals("@+id/retryButton", viewById("splashScroll").getAttribute("app:layout_constraintBottom_toTopOf")) {
            "the scroll container does not end at the Retry button's top, so the " +
                "message can be drawn under the one control that answers it"
        }
    }

    /**
     * The button is gone until a setup fails, and big enough to hit.
     *
     * Gone, not invisible: the progress screen would otherwise carry a button
     * that takes touches and does nothing, and a screen reader would announce it.
     * 48dp is the touch target floor, and the same figure the picker's Continue
     * button uses.
     *
     * NEGATIVE CONTROL: drop `android:visibility="gone"` or lower `minHeight` and
     * the matching assertion goes red. Measured.
     */
    @Test
    fun `the retry button is hidden until a setup fails and is a full touch target`() {
        val button = viewById("retryButton")
        assertEquals("gone", button.getAttribute("android:visibility")) {
            "the Retry button is on screen while setup is still running"
        }
        assertEquals("48dp", button.getAttribute("android:minHeight")) {
            "the Retry button is under the 48dp touch target"
        }
    }

    /**
     * The failure message can be read to its end, and never touches the screen edge.
     *
     * The message is inside a scroll container whose child fills the viewport, so
     * a short progress line stays centred where it always was and a long failure
     * scrolls. The status line is `0dp` wide between two margins rather than
     * `wrap_content` with none: a long message wrapped edge to edge, with its
     * first letters against the bezel.
     *
     * NEGATIVE CONTROL: replace the scroll container with a plain layout, drop
     * `fillViewport`, or restore `wrap_content` on the status line, and each goes
     * red. Measured.
     */
    @Test
    fun `the failure message scrolls and keeps clear of the screen edges`() {
        val status = viewById("statusText")
        val scroll = viewById("splashScroll")
        assertTrue(scroll.tagName.endsWith("ScrollView")) {
            "splashScroll is a ${scroll.tagName}, which does not scroll"
        }
        assertTrue(ancestorsOf(status).contains(scroll)) {
            "the status line is not inside the scroll container, so a message taller " +
                "than the window is cut off with no way to read it"
        }
        assertEquals("true", scroll.getAttribute("android:fillViewport")) {
            "without fillViewport the progress screen's chain collapses to the top of " +
                "the window instead of staying centred"
        }
        assertEquals("0dp", status.getAttribute("android:layout_width")) {
            "the status line is not constrained between its margins"
        }
        assertTrue(status.getAttribute("android:layout_marginStart").isNotEmpty()) {
            "the status line has no start margin, so wrapped text touches the screen edge"
        }
        assertTrue(status.getAttribute("android:layout_marginEnd").isNotEmpty()) {
            "the status line has no end margin, so wrapped text touches the screen edge"
        }
    }

    /**
     * A message that does not fit is read from its first line, and the scroll says
     * there is more.
     *
     * Two halves of one need. Landscape, or a raised font scale, leaves less room
     * than the message takes: measured on an API 37 emulator, the hint paragraph
     * sat below the fold with nothing to show it was there, and at the largest
     * font the line naming the cause did too. The fading edge is the sign, and
     * `showSetupError` starting the scroll at the status line puts the cause on
     * screen before anything is dragged.
     *
     * NEGATIVE CONTROL: drop `requiresFadingEdge` from the scroll and the first
     * assertion goes red; delete the `scrollTo` line from `showSetupError` and the
     * second does. Measured.
     */
    @Test
    fun `a failure starts at the message and the scroll hints at hidden lines`() {
        assertEquals("vertical", viewById("splashScroll").getAttribute("android:requiresFadingEdge")) {
            "nothing shows that a line of the message is hidden below the fold"
        }
        val error = body("showSetupError")
        assertTrue(error.any { it.contains("scrollTo(0, statusText.top") }) {
            "showSetupError leaves the scroll at the icon, so in landscape the cause " +
                "of the failure is off screen until something is dragged:\n${error.joinToString("\n")}"
        }
    }

    /**
     * `showSetupError` reveals the layout's button, and the click hides it again.
     *
     * Source reading, for the reason this class states. The first half is the
     * button appearing at all; the second is the progress screen not keeping a
     * dead Retry button after a tap.
     *
     * NEGATIVE CONTROL: delete the `View.VISIBLE` line and the first assertion
     * goes red; delete the `View.GONE` line and the second does. Measured.
     */
    @Test
    fun `a failed setup shows the retry button and a tap hides it`() {
        val error = body("showSetupError")
        assertTrue(error.any { it.contains("R.id.retryButton") }) {
            "showSetupError does not look up the layout's Retry button"
        }
        assertTrue(error.any { Regex("""retryButton\.visibility\s*=\s*View\.VISIBLE""").containsMatchIn(it) }) {
            "showSetupError never shows the Retry button, so a failed setup leaves a " +
                "screen with nothing to tap"
        }
        assertTrue(error.any { Regex("""retryButton\.visibility\s*=\s*View\.GONE""").containsMatchIn(it) }) {
            "tapping Retry leaves the button on the progress screen"
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

    /**
     * The foreground hold is taken by the instance that unpacks, and by nothing
     * waiting for it.
     *
     * The hold is released in a `finally`, on the argument that the unpack has
     * no suspension point so cancellation is only observed once it has returned.
     * That is true of the instance running the unpack and false of a second
     * Splash parked on the setup lock, which a locale change mid-extraction or
     * MainActivity's hand-off creates: parking suspends, so destroying the
     * waiter throws it out of the lock at once, and a hold it had taken before
     * `runSetup` was given back while the first instance was still writing. The
     * service answers that by leaving the foreground and stopping.
     *
     * So the hold has to be taken from inside `runSetup`, which calls back only
     * from the instance holding the lock with work to do. The behavioural half
     * is pinned in FirstRunSetupHoldTest; this is the wiring.
     *
     * NEGATIVE CONTROL: move `holdProcessForSetup()` back above `runSetup` on a
     * line of its own and the second assertion goes red.
     */
    @Test
    fun `the setup hold is taken inside the lock by the instance that unpacks`() {
        val run = body("runSetupWithRetry")
        assertTrue(run.isNotEmpty()) { "runSetupWithRetry is empty; this test is measuring nothing" }

        val holds = run.filter { it.contains("holdProcessForSetup()") }
        assertEquals(1, holds.size) {
            "the hold is taken from ${holds.size} places in runSetupWithRetry:\n" +
                "${holds.joinToString("\n")}\nOne of them will be a waiter's."
        }
        assertTrue(holds.single().contains("runSetup")) {
            "the hold is taken outside runSetup, so a second Splash parked on the " +
                "setup lock takes one too, and its finally gives it back while the " +
                "first instance is still writing:\n${holds.single()}"
        }
        assertTrue(run.any { it.contains("releaseSetupHold()") }) {
            "nothing gives the hold back, so the service stays in the foreground " +
                "with nothing to serve"
        }
    }
}
