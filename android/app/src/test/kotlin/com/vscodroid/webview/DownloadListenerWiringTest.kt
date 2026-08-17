package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That every WebView this app puts on screen can receive a download.
 *
 * `DownloadCoordinator` decides what happens to a download and is tested
 * directly. None of that runs unless a `DownloadListener` is installed, and
 * nothing else in the suite would notice its absence: the coordinator's tests
 * call it themselves, so they stay green over a WebView the platform never
 * speaks to. That is precisely the state this replaced, where Download was on
 * the menu, did nothing, and reported nothing.
 *
 * Reading the source is the weaker kind of test in this suite and is used for
 * the same reason `BridgeCallbackThreadHopTest` uses it: there is no seam.
 * `setDownloadListener` is a final method on a framework class, the listener is
 * built inline, and a unit test has no WebView to install one on.
 *
 * The placement is asserted, not just the presence, and that is the part worth
 * explaining. A renderer crash destroys the WebView and builds another, and the
 * replacement is configured by `setupWebView`. A listener installed anywhere
 * that runs once per Activity would leave every post-crash WebView silently
 * back at the original defect. So the link is checked rather than assumed:
 * `recreateWebView` must call the function the listener lives in.
 */
class DownloadListenerWiringTest {

    private val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt").readText()

    /** The body of a `private fun name(` declaration, to its closing brace. */
    private fun body(name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue(start >= 0) {
            "$name is gone from MainActivity.kt, so this test is measuring nothing. " +
                "If it moved or was renamed, point this at the new site rather than deleting it."
        }
        val open = source.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < source.length) {
            if (source[i] == '{') depth += 1
            if (source[i] == '}') {
                depth -= 1
                if (depth == 0) return source.substring(open, i + 1)
            }
            i += 1
        }
        throw AssertionError("Could not find the end of $name in MainActivity.kt")
    }

    /**
     * Comments removed, so prose about the rule cannot satisfy a search for the
     * rule. This file's subject is discussed at length in the comments around
     * the very lines it checks.
     */
    private fun withoutComments(text: String): String =
        text.lines().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }

    @Test
    fun `every WebView gets a download listener that reaches the coordinator`() {
        val setup = withoutComments(body("setupWebView"))

        assertTrue(setup.contains("setDownloadListener")) {
            "No download listener is installed in setupWebView. Without one, Android drops " +
                "every download the editor starts and says nothing, which is the defect " +
                "issue #234 recorded."
        }
        assertTrue(setup.contains("downloads.onDownloadStart")) {
            "The download listener does not reach DownloadCoordinator. A listener that is " +
                "installed and does nothing fails exactly as invisibly as no listener at all."
        }
    }

    @Test
    fun `a WebView rebuilt after a renderer crash is configured the same way`() {
        val recreate = withoutComments(body("recreateWebView"))

        assertTrue(recreate.contains("setupWebView(")) {
            "recreateWebView no longer routes through setupWebView, so the replacement " +
                "WebView does not get whatever setupWebView installs, the download listener " +
                "included. Install it wherever the replacement is configured now."
        }
        assertTrue(recreate.contains("downloads.onPageGone")) {
            "A download in flight when the renderer died is left holding the document the " +
                "picker created. The page owing its bytes is gone, so that file stays in the " +
                "user's folder, empty, under the name of the file they asked for."
        }
    }

    @Test
    fun `the page that answers the listener is given the script that answers it`() {
        val inject = withoutComments(body("injectBridgeToken"))

        assertTrue(inject.contains("injectDownloadCapture(")) {
            "injectBridgeToken no longer injects the capture script, so no page has the " +
                "shadowed createObjectURL or the sender the listener hands off to. The " +
                "listener still installs, the picker still opens, and every download then " +
                "fails with nothing to read the bytes. Nothing else notices: the script's " +
                "own check reads the function's text out of this file rather than asking " +
                "whether anyone calls it, and the two cases above only prove the listener " +
                "is installed. Inject it wherever the page is prepared now."
        }
    }
}
