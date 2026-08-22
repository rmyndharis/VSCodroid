package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertEquals
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
     *
     * Both forms, because both disable code, and a call switched off while
     * debugging and left that way is the state these cases exist to catch. A
     * block counts as a comment only where it opens a line: a wildcard mime
     * type and the CSS comments inside the scripts this file injects carry the
     * same two characters inside a string literal and disable nothing.
     */
    private fun withoutComments(text: String): String {
        var inBlock = false
        return text.lines().joinToString("\n") { raw ->
            var line = raw
            if (inBlock) {
                val close = line.indexOf("*/")
                if (close < 0) return@joinToString ""
                inBlock = false
                line = line.substring(close + 2)
            }
            while (line.trimStart().startsWith("/*")) {
                val open = line.indexOf("/*")
                val close = line.indexOf("*/", open + 2)
                if (close < 0) {
                    inBlock = true
                    return@joinToString line.substring(0, open)
                }
                line = line.substring(0, open) + line.substring(close + 2)
            }
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
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

    /**
     * The body of the anonymous [com.vscodroid.webview.DownloadHost], which is a
     * property initializer rather than a `private fun` and so is out of reach of
     * [body].
     */
    private fun host(): String {
        val start = source.indexOf("private val downloads: DownloadCoordinator")
        assertTrue(start >= 0) {
            "the download coordinator is gone from MainActivity.kt, so this test is " +
                "measuring nothing"
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
        throw AssertionError("Could not find the end of the download host in MainActivity.kt")
    }

    @Test
    fun `only an answer that says the read began is read as one`() {
        // The page answers with a JSON literal, so a read that started is "true"
        // and nothing else is. Testing for "false" alone treated every other
        // answer as success, and evaluateJavascript delivers "null" whenever the
        // script throws or yields undefined. The download then stayed in
        // `pending` for ever: no toast either way, an empty file in the user's
        // folder under the name they chose, and every download after it queued
        // behind one that could never end.
        val bytes = withoutComments(host())

        assertTrue(bytes.contains("""answer != "true"""")) {
            "the answer to the capture script is not tested for being the one answer " +
                "that means the read began. Found: " +
                bytes.lines().filter { it.contains("answer") }.joinToString("\n")
        }
    }

    @Test
    fun `how a download ended is said in the user's language`() {
        // These three are the entire user-visible outcome of the feature, and a
        // Kotlin literal is the same in every locale for ever. The gate over
        // translatable strings cannot see them: it is a predicate over call
        // shapes and finds a literal only where it is written at the sink, while
        // this message is built into a local first, which its own docstring names
        // as the biggest hole it has.
        val report = withoutComments(host())
            .lines()
            .dropWhile { !it.contains("val message = when (outcome)") }
            .takeWhile { !it.contains("Toast.makeText(") }

        assertTrue(report.isNotEmpty()) {
            "the download outcome message is gone from MainActivity.kt, so this test is " +
                "measuring nothing"
        }
        val literals = report.filter { line ->
            Regex(""""[^"]*[A-Za-z][^"]*"""").containsMatchIn(line)
        }
        assertEquals(emptyList<String>(), literals.map { it.trim() }) {
            "a download outcome is written in Kotlin, so it stays English whatever " +
                "language the device is in. Move it to a string resource and build the " +
                "message with getString."
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
