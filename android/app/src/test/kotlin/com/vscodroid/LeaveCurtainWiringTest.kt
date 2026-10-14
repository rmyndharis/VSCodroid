package com.vscodroid

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the script covering the workbench with the page's own colour while the
 * page is told to leave is installed where every page load passes, listens in
 * the capture phase for the one event the workbench dismantles itself in, and
 * still matches how the shipped workbench shuts down and vetoes.
 *
 * Source-level, like [ComposingEnterWiringTest], with the same ceiling: it
 * cannot see what the WebView paints, only that the script and the bundle still
 * agree on what it depends on. What the script does with a leave, a veto and a
 * clock is `scripts/test-leave-curtain.js` against a stub page.
 */
class LeaveCurtainWiringTest {

    private companion object {
        const val MAIN_ACTIVITY = "src/main/kotlin/com/vscodroid/MainActivity.kt"
        const val WORKBENCH = "src/main/assets/vscode-reh/out/vs/code/browser/workbench/workbench.js"
    }

    /** Comments blanked, because the KDoc names everything the cases look for. */
    private fun mainActivity(): String =
        SourceScan.withoutComments(SourceScan.read(MAIN_ACTIVITY))

    /** The packaged workbench, or the case is skipped where there is none. */
    private fun shippedWorkbench(): String {
        val workbench = File(WORKBENCH)
        assumeTrue(
            workbench.isFile,
            "no packaged workbench at ${workbench.path}; run scripts/fetch-vscode-oss.sh and " +
                "scripts/package-assets.sh to check the mapping against the shipped bundle",
        )
        return workbench.readText()
    }

    @Test
    fun `the script is installed from the path every page load takes`() {
        val source = mainActivity()
        assertTrue(
            source.contains("private fun injectLeaveCurtain()"),
            "injectLeaveCurtain is gone, so a reload shows the half-dismantled workbench " +
                "again until the next page paints.",
        )
        val bridgeToken = SourceScan.body(source, "private fun injectBridgeToken(")
        assertTrue(
            bridgeToken.contains("injectLeaveCurtain()"),
            "injectLeaveCurtain() is not called from injectBridgeToken, the one path that " +
                "runs again after the workbench switches folders by navigating its own WebView.",
        )
    }

    @Test
    fun `the script covers the page for a leave and gives it back`() {
        val script = SourceScan.body(mainActivity(), "private fun injectLeaveCurtain(")
        listOf(
            "'beforeunload'" to "the event the workbench dismantles itself in; any other " +
                "event is too late, because the dismantled page is painted by the next frame",
            "document.documentElement.appendChild(curtain)" to "the curtain goes on the root, " +
                "outside the workbench's container, which the workbench disposes",
            "z-index:2147483647" to "anything the workbench draws above a lower layer would " +
                "show through",
            "e.defaultPrevented" to "a leave the workbench cancels with preventDefault keeps " +
                "its page",
            "e.returnValue" to "the workbench cancels with returnValue as well, and an event " +
                "with only that set is a veto too",
            "'pageshow'" to "a page restored from the back-forward cache is not left covered",
            "setTimeout(lower, 5000)" to "a navigation that never commits leaves the page alive, " +
                "and only a timer gives it back",
        ).forEach { (name, why) ->
            assertTrue(script.contains(name), "injectLeaveCurtain no longer names `$name`: $why")
        }
        assertTrue(
            Regex("""addEventListener\('beforeunload'[\s\S]*\},\s*true\)""").containsMatchIn(script),
            "the beforeunload listener is not in the capture phase on the window, so the " +
                "listeners that stop propagation run ahead of it",
        )
        assertFalse(
            script.contains("visibility") || script.contains("innerHTML"),
            "the script hides the workbench by changing its own elements. Hiding the focused " +
                "editor blurs it, and the blur reaches the workbench's own handlers while it " +
                "shuts down; a curtain on the root touches nothing the workbench owns.",
        )
    }

    @Test
    fun `a navigation the client declines is told to the page under the name the script listens for`() {
        val source = mainActivity()
        val event = "vscodroid-navigation-declined"
        val script = SourceScan.body(source, "private fun injectLeaveCurtain(")
        assertTrue(
            script.contains("addEventListener('$event', lower)"),
            "the curtain script no longer lowers on `$event`, so a link handed to another app " +
                "or a refused load leaves the page blank for the whole five seconds.",
        )
        val lift = SourceScan.body(source, "private fun liftLeaveCurtain(")
        assertTrue(
            lift.contains("new Event('$event')") && lift.contains("evaluateJavascript"),
            "liftLeaveCurtain does not dispatch `$event` into the page.",
        )
        assertTrue(
            source.contains("onNavigationDeclined = { liftLeaveCurtain() }"),
            "the client's declined-navigation callback is not wired to liftLeaveCurtain().",
        )
    }

    @Test
    fun `the shipped workbench still turns its own shutdown off for the links it hands away`() {
        // The reason only the curtain is left over a page whose link went to another app:
        // the opener assigns `location.href` inside `withExpectedShutdown` with the
        // shutdown handling disabled, so the workbench's beforeunload listener stands down.
        val bundle = shippedWorkbench()
        assertTrue(
            Regex("""withExpectedShutdown\(\{disableShutdownHandling:!0\},\(\)=>[\w$]+\.location\.href=""")
                .containsMatchIn(bundle),
            "the opener no longer assigns location.href inside withExpectedShutdown with " +
                "disableShutdownHandling. A link to another app would then dismantle the " +
                "workbench as a reload does, and lowering the curtain would show that.",
        )
    }

    @Test
    fun `the shipped workbench still shuts down inside beforeunload and vetoes by cancelling it`() {
        val bundle = shippedWorkbench()
        assertTrue(
            Regex("""onBeforeUnload\(([\w$]+)\)\{this\.ignoreBeforeUnload\?[^{}]{0,300}this\.doShutdown\(\(\)=>this\.vetoBeforeUnload\(\1\)\)""")
                .containsMatchIn(bundle),
            "the lifecycle service no longer runs doShutdown from its beforeunload listener " +
                "with vetoBeforeUnload as the veto. If the dismantling moved to pagehide, the " +
                "curtain raised from beforeunload is no longer what covers it.",
        )
        assertTrue(
            Regex("""typeof [\w$]+=="function"\?[\w$]+\(\):this\.onUnload\(\)""").containsMatchIn(bundle),
            "doShutdown no longer calls onUnload, the full dismantling, in the same call when " +
                "nothing vetoes. The script assumes it is done by the end of the dispatch.",
        )
        assertTrue(
            Regex("""vetoBeforeUnload\(([\w$]+)\)\{\1\.preventDefault\(\),\1\.returnValue=""")
                .containsMatchIn(bundle),
            "a veto is no longer preventDefault plus returnValue on the event. The script " +
                "lowers the curtain for exactly those two, so a new way of cancelling would " +
                "leave it over the page the user is asked about.",
        )
    }
}
