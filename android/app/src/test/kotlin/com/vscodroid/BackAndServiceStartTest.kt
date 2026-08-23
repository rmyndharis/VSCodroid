package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the editor does at the two moments the thing it would rely on is not there.
 *
 * Both are lifecycle code inside an Activity, so there is nothing on this
 * classpath to drive: `onBackPressedDispatcher`, `moveTaskToBack` and
 * `startForegroundService` all need a real one. The regression in each case is a
 * call being in the wrong place rather than a value computed wrongly, which is
 * what `ServerReadinessCallSiteTest` gives as the reason for reading the source.
 *
 * **Back.** It used to ask the page whether the editor had handled the press,
 * through `AndroidBridge.onBackPressed`, and that method returns whatever the
 * `onBackPressed` constructor lambda answers, which `initBridge` passes as
 * `{ false }`. No patch, bundled extension or injected script defines a page-side
 * handler, so the answer was a constant and every press paid an
 * `evaluateJavascript` round trip to learn it. Worse, the call was made through
 * `webView?` with the minimise inside its result callback, so with no WebView (the
 * window between `recreateWebView` tearing one down and building the next) back
 * did nothing at all.
 *
 * **Starting the server.** On Android 12+ `startForegroundService` throws
 * `ForegroundServiceStartNotAllowedException` at the call site when the app may
 * not start one from the background. `NodeService.promoteToForeground` catches the
 * service side of that same refusal and stands down with a log, reasoning in its
 * own documentation that an uncaught throw there would be a crash loop with no
 * screen in front of it. Both Activity call sites were bare, and one of them is in
 * `onCreate`. Catching it is only half the answer: the bind that follows creates
 * the service but never delivers an `onStartCommand`, and that is the only thing
 * that calls `NodeService.launchServer`, so the refusal has to be said out loud
 * as well as survived.
 */
class BackAndServiceStartTest {

    private val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /** A declaration's body with its comments blanked; see [SourceScan]. */
    private fun code(declaration: String): String =
        SourceScan.withoutComments(SourceScan.body(source, declaration))

    @Test
    fun `back leaves the app without asking a page that cannot answer`() {
        val back = code("override fun handleOnBackPressed(")

        assertTrue(back.contains("moveTaskToBack(true)")) {
            "back no longer sends the app to the background, which is the only thing it " +
                "has ever actually done"
        }
        assertTrue(!back.contains("evaluateJavascript")) {
            "back asks the page again. The bridge method it reaches answers from the " +
                "`onBackPressed` constructor lambda, which is `{ false }`, and nothing " +
                "anywhere installs a page-side handler, so the round trip can only ever " +
                "return the answer it already had."
        }
        assertTrue(!back.contains("webView?")) {
            "the minimise is conditional on there being a WebView again, so back does " +
                "nothing at all in the window where there is none"
        }
    }

    @Test
    fun `both foreground starts stand down instead of throwing out of a lifecycle callback`() {
        val starts = listOf(
            "private fun startAndBindService(" to
                "this runs from onCreate, so an uncaught refusal is a crash loop",
            "private fun retryServerStart(" to
                "this runs from the control on either error page",
        )

        val unguarded = starts.filter { (declaration, _) ->
            val text = code(declaration)
            val call = text.indexOf("startForegroundService(")
            assertTrue(call >= 0) {
                "`$declaration` no longer starts the service, so this case is measuring nothing"
            }
            val tryAt = text.indexOf("try {")
            val catchAt = text.indexOf("catch")
            tryAt < 0 || tryAt > call || catchAt < call
        }.map { it.first }

        assertEquals(
            emptyList<String>(), unguarded,
            "startForegroundService throws ForegroundServiceStartNotAllowedException at " +
                "the call site on Android 12+ when the app may not start one, and the " +
                "service's own promotion already catches that refusal and stands down. " +
                "Unguarded here it leaves a lifecycle callback: " +
                starts.filter { it.first in unguarded }.joinToString("; ") { it.second },
        )
    }

    @Test
    fun `a refused promotion keeps the binding and is put on screen`() {
        val start = code("private fun startAndBindService(")
        // From the try's opening brace to the catch's closing one, which is what
        // "inside the guard" means. Ordering against the word `catch` is not: a
        // bindService moved into the catch block satisfies that and is the exact
        // regression this case exists to refuse.
        val handler = SourceScan.body(start, "catch (")
        val guardAt = start.indexOf("try {")
        val guardEnd = start.indexOf(handler) + handler.length
        val bind = start.indexOf("bindService(")

        assertTrue(bind >= 0 && guardAt >= 0) {
            "startAndBindService no longer both binds and guards; this case is measuring nothing"
        }

        assertTrue(bind !in guardAt until guardEnd) {
            "the bind is inside the guarded block, so a refused foreground start takes " +
                "the binding with it, and with it every callback that would report a " +
                "server coming up later"
        }

        // What the binding does NOT do is start anything. `bindService` reaches
        // `NodeService.onCreate`, which constructs a ProcessManager and wires
        // callbacks; `launchServer` runs only from `onStartCommand`, which a bind
        // never delivers. So a swallowed refusal leaves a created service that
        // will never spawn Node, and `setupServiceCallbacks` reads port 0 and an
        // unready server and settles on BindDecision.Wait: the loading page says
        // the server is starting, for ever, with no control on it.
        assertTrue(handler.contains("showServerGaveUp()")) {
            "a refused foreground start is swallowed. Nothing else starts the server " +
                "after one, so the user is left on the loading page with no message and " +
                "no way to try again, which is the state that page exists to replace"
        }
    }
}
