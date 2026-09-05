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

    /**
     * And nothing but that control takes the page down.
     *
     * The service restarts a server that dies, and a restart announces itself
     * through `onServerReady` exactly as a first start does. With the crash page
     * up, the handler loaded the workbench over it unasked: one more turn of the
     * loop the page had just promised to stop, under the memory pressure that
     * caused it. The port still has to be recorded, or the reload the page
     * offers has nothing to load.
     */
    @Test
    fun `a server that comes back on its own does not reload over the crash page`() {
        val callbacks = code("private fun setupServiceCallbacks()")
        val ready = callbacks.indexOf("onServerReady")
        val next = callbacks.indexOf("onServerError")
        assertTrue(ready >= 0 && next > ready) {
            "setupServiceCallbacks no longer installs the ready callback ahead of the " +
                "error one; this case reads the span between them"
        }
        val handler = callbacks.substring(ready, next)

        assertTrue(handler.contains("serverPort = port")) {
            "the ready callback no longer records the port, so a later tap on the crash " +
                "page's control finds nothing to load"
        }
        val guard = handler.indexOf("rendererCrashLoopShown")
        val load = handler.indexOf("loadVSCode(")
        assertTrue(load >= 0) { "the ready callback no longer loads the editor at all" }
        assertTrue(guard in 0 until load) {
            "the ready callback loads the editor without asking whether the crash page " +
                "is up, so a server the service restarted replaces the page that " +
                "promised not to reopen the editor unasked"
        }
    }

    @Test
    fun `the crash page sets the record and every asked-for page clears it`() {
        // Control for the case above. A guard reads a flag; the flag is only
        // worth reading if the page that refuses sets it and the pages that
        // expect the editor to follow clear it. Left set by the loading page,
        // the readiness after Try again would be refused too, and the user
        // would sit on 'starting' with nothing left to press.
        assertTrue(code("private fun showErrorPage(").contains("rendererCrashLoopShown = control == RELOAD_URL")) {
            "showErrorPage no longer answers the record from the control it draws, so " +
                "either the crash page never refuses a reload or the gave-up page does"
        }
        listOf(
            "private fun loadVSCode(",
            "private fun retryServerStart()",
            // The placeholder a rebuilt WebView starts on. A renderer death that
            // rebuilds the view over the crash page while the server is still
            // coming up (serverPort == 0) reaches neither of the two above, and
            // with the record left set the readiness that follows is refused:
            // "Starting server..." for ever, with no control on it.
            "private fun setupWebView()",
            // Every workbench load. A picker result that lands while the crash
            // page is up navigates here directly, and a later self-restart's
            // readiness must not be refused over the workbench it would replace.
            "private fun navigateToFolder(",
        ).forEach { declaration ->
            assertTrue(code(declaration).contains("rendererCrashLoopShown = false")) {
                "$declaration puts up a page the editor is expected to follow without " +
                    "clearing the record, so the next readiness is refused"
            }
        }
    }

    @Test
    fun `the rebuilt view's placeholder clears the record before the crash page can set it again`() {
        // Order inside recreateWebView: setupWebView clears, then a looping
        // rebuild sets it again through showRendererCrashLoop. Reversed, the
        // placeholder would clear what the crash page had just recorded.
        val recreate = code("private fun recreateWebView()")
        val rebuilt = recreate.indexOf("setupWebView()")
        val refused = recreate.indexOf("showRendererCrashLoop()")
        assertTrue(rebuilt in 0 until refused) {
            "recreateWebView shows the crash page before it rebuilds the view, so the " +
                "placeholder load clears the record the page just set"
        }
    }

    /**
     * What the refusal leaves behind, and the one way out of it that does not go
     * through [MainActivity.loadVSCode].
     *
     * `recreateWebView` clears `bridgeInitialized` and installs the bootstrap
     * client, and on the looping path it shows the crash page and returns before
     * the load, so the bridge is never put back. `onServerReady` is refused for as
     * long as that page is up, which leaves `navigateToFolder` as the only route
     * that can still load the workbench: a folder picked from the crash page
     * arrives there directly. Loading it under the bootstrap client gives a page
     * that renders and does nothing, with no JS interface and no request
     * interception, and nothing on screen saying so.
     *
     * Asking on that path is free rather than a second registration: `initBridge`
     * returns at its own flag before any work, so a folder switch re-registers
     * nothing and the once-per-WebView rule is untouched.
     */
    @Test
    fun `a folder opened after the refusal is given the bridge back`() {
        val navigate = code("private fun navigateToFolder(")

        val asks = navigate.indexOf("initBridge(")
        val loads = navigate.indexOf("loadUrl(")

        assertTrue(asks >= 0) {
            "navigateToFolder loads the workbench without asking for the bridge, so a " +
                "folder opened after the crash page comes up with no JS interface: every " +
                "VSCodroid command does nothing and CDN requests leave the device"
        }
        assertTrue(loads >= 0) {
            "navigateToFolder no longer loads anything, so this case is measuring nothing"
        }
        assertTrue(asks < loads) {
            "navigateToFolder asks for the bridge after starting the load, which races the " +
                "page it is meant to be ready for"
        }
    }
}
