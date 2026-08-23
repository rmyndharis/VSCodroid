package com.vscodroid

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What stops a dead renderer being answered with the thing that killed it.
 *
 * `MainActivity.recreateWebView` rebuilds the WebView and loads the workbench
 * into it, and loading the workbench is the peak of this app's memory use. A
 * renderer killed for memory therefore dies doing exactly what the recovery asks
 * of it, and with nothing counting, the recovery ran again, and again, for as
 * long as the app was left open: a page flashing, a warm device, and nothing on
 * screen ever saying why.
 *
 * The decision is [crashLoopReached] and is pure, so the window and the count are
 * checked here rather than inferred. The wiring around it cannot be: it is an
 * Activity method that destroys a WebView, adds another to a container and loads a
 * page into it, none of which a JVM can build. That half is read from the source
 * and buys only that the branch is at the site, in the order it has to be.
 */
class RendererCrashLoopTest {

    private val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /** A declaration's body with its comments blanked; see [SourceScan]. */
    private fun code(declaration: String): String =
        SourceScan.withoutComments(SourceScan.body(source, declaration))

    @Test
    fun `crashes inside the window are recovered from until the budget is spent`() {
        val times = ArrayDeque<Long>()

        assertFalse(crashLoopReached(times, 0))
        assertFalse(crashLoopReached(times, 1_000))
        assertFalse(crashLoopReached(times, 2_000))
        assertTrue(
            crashLoopReached(times, 3_000),
            "a fourth crash inside a minute is not read as a loop, so the recovery that " +
                "is causing it goes on running",
        )
    }

    @Test
    fun `crashes spread out are not a loop however many there have been`() {
        val times = ArrayDeque<Long>()
        // Well outside the window each time: a page that dies when one particular
        // file is opened, hours apart, is a bug to recover from rather than a loop
        // to refuse.
        var now = 0L
        repeat(20) {
            assertFalse(
                crashLoopReached(times, now),
                "a crash ${now / 1000}s in was counted against ones a window ago",
            )
            now += CRASH_LOOP_WINDOW_MS * 2
        }
        assertTrue(
            times.size == 1,
            "the record grows for the life of the Activity instead of being bounded by " +
                "the window; found ${times.size} readings",
        )
    }

    @Test
    fun `a reading exactly the window old still counts`() {
        // The boundary is where this silently becomes a different rule. Written
        // with `>=` the oldest reading is dropped one millisecond early, and the
        // budget is quietly four.
        val times = ArrayDeque<Long>()

        assertFalse(crashLoopReached(times, 0))
        assertFalse(crashLoopReached(times, 1))
        assertFalse(crashLoopReached(times, 2))
        assertTrue(crashLoopReached(times, CRASH_LOOP_WINDOW_MS))
    }

    @Test
    fun `the first crash after the window falls out is recovered from again`() {
        val times = ArrayDeque<Long>()
        repeat(CRASH_LOOP_CRASHES + 1) { crashLoopReached(times, it.toLong()) }

        assertFalse(
            crashLoopReached(times, CRASH_LOOP_WINDOW_MS * 2),
            "a session that once hit the limit never recovers from a crash again",
        )
    }

    @Test
    fun `the WebView is still rebuilt when the load is refused`() {
        val recreate = code("private fun recreateWebView()")

        val decided = recreate.indexOf("crashLoopReached(")
        val rebuilt = recreate.indexOf("setupWebView()")
        val refused = recreate.indexOf("showRendererCrashLoop()")
        val load = recreate.indexOf("loadVSCode(")

        assertTrue(decided >= 0) {
            "recreateWebView no longer counts the crash, so nothing bounds the recovery"
        }
        assertTrue(rebuilt >= 0 && load >= 0) {
            "recreateWebView no longer rebuilds the view or loads the editor; this case " +
                "is measuring nothing"
        }
        assertTrue(refused in (rebuilt + 1) until load) {
            "the refusal is not between the rebuild and the load. The crashed WebView is " +
                "documented as unusable and handleResumeFromBackground calls reload() on " +
                "whatever the field holds, so refusing to rebuild turns a loop into an " +
                "undefined call; refusing after the load refuses nothing."
        }
        assertTrue(recreate.substring(refused, load).contains("return")) {
            "the refusal falls through into the load it exists to prevent"
        }
    }

    /**
     * And the way back is a control that does something.
     *
     * The two error pages this app draws need opposite work done. The server-side
     * one restarts the service; here the server is healthy and running, so
     * `startForegroundService` is answered with ALREADY_SERVING and starts
     * nothing, leaving the loading page up for ever. What has to be repeated is
     * the page load, which is why the crash page carries its own URL.
     */
    @Test
    fun `the crash page offers a control that reloads rather than restarts`() {
        val handler = code("override fun shouldOverrideUrlLoading(")

        assertTrue(handler.contains("RELOAD_URL")) {
            "the bootstrap client no longer recognises the renderer-crash page's control, " +
                "so the only thing on that page does nothing at all"
        }
        val reload = handler.indexOf("RELOAD_URL")
        val retry = handler.indexOf("RETRY_URL")
        assertTrue(retry >= 0) { "the server retry control is gone; this case reads both" }
        // Before the first substring, because a swap of the two branches makes
        // every one below it throw StringIndexOutOfBoundsException instead of
        // failing, and a stack trace with no sentence in it is what this whole
        // file is written to avoid.
        assertTrue(reload < retry) {
            "the two controls have swapped order, so the span read below is the retry " +
                "branch and not the reload one"
        }
        assertTrue(handler.substring(reload, retry).contains("loadVSCode(")) {
            "the crash page's control does not reload the editor. A healthy server " +
                "answered with the start alone is answered ALREADY_SERVING, which " +
                "starts nothing and leaves the loading page up."
        }
        assertTrue(handler.substring(reload, retry).contains("webViewCrashes.clear()")) {
            "asking for the editor back does not clear the record, so the next single " +
                "crash is refused immediately"
        }
        assertTrue(handler.substring(reload, retry).contains("isServerReady()")) {
            "the reload navigates without asking whether the server is answering. A " +
                "renderer can die before it is, and a navigation at a port nothing is " +
                "listening on leaves a connection-refused page that nothing clears."
        }
        assertTrue(handler.substring(reload, retry).contains("retryServerStart()")) {
            "a server that is not answering is met with the loading page and nothing " +
                "else. It covers two states: one still coming up, which onServerReady " +
                "finishes, and one that has given up, whose isServiceRunning was cleared " +
                "and which has no callback left to fire. In the second the user is left " +
                "on 'starting' for ever with nothing on the page to press, which is the " +
                "state the server-gave-up page exists to replace."
        }
    }
}
