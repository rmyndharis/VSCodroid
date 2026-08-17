package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every `AndroidBridge` callback that touches the UI hops to the UI thread.
 *
 * `addJavascriptInterface` binds the object on the WebView's own "JavaBridge"
 * thread, and its documentation says so directly: the bound object "runs in
 * another thread and not in the thread that it was constructed in". So a handler
 * that builds a Dialog, shows a Toast or touches a View is on the wrong thread
 * unless it hops, and the failure is not a quiet one, `ViewRootImpl.checkThread`
 * throws `CalledFromWrongThreadException` and takes the process with it.
 *
 * One of the five was wrapped and the rest were not. That held for as long as the
 * unwrapped ones were unreachable: the bundled extension declared `main`, so it
 * ran in the Node extension host, and its `BroadcastChannel` never reached the
 * relay that calls into the bridge. Moving it to `browser` made the commands
 * reachable and made the missing hops reachable with them, without any line that
 * looks like it changed thread behaviour changing at all.
 *
 * This reads the source, which is the weaker kind of test in this suite, and it
 * is worth naming what that buys and what it does not.
 *
 * It buys the property that is actually at issue: whether the hop is present at
 * the boundary. There is no seam to extract instead, the lambdas are built
 * inline in `initBridge`, `runOnUiThread` is a final Activity method, and a
 * unit test on the JVM has no Looper to distinguish threads with even if there
 * were one.
 *
 * What it does not buy: it sees the token `runOnUiThread` and not what runs
 * inside it. A handler that hops and then hands the work to a background
 * executor satisfies this test and is just as broken. It also only reads this
 * one construction site; a second bridge built elsewhere is invisible to it.
 *
 * The exempt entries are deliberate rather than oversights, and they share one
 * shape: each is a callback whose answer or whose ordering is consumed before a
 * posted body could run. `onBackPressed` and `onDownloadChunk` return a
 * `Boolean` the caller consumes immediately, and `runOnUiThread` returns `Unit`,
 * so posting them would mean answering before the answer exists. The two
 * download callbacks beside them are ordered against calls that cannot be
 * posted for that reason, and posting only some of a sequence reorders it.
 *
 * Every exempt body must therefore stay free of UI calls, which is not something
 * this test can check; the reasons are recorded here so each exemption is a
 * decision on the record rather than a gap someone finds later.
 */
class BridgeCallbackThreadHopTest {

    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /**
     * Callbacks whose bodies are exempt, with the reason each is exempt. Adding a
     * name here is the way to opt out, and it costs a line in a file whose whole
     * subject is why the hop matters.
     */
    private val exempt = mapOf(
        "onBackPressed" to "returns a Boolean the caller reads synchronously",
        "onDownloadChunk" to "returns a Boolean the caller reads synchronously",
        "onDownloadNamed" to
            "the platform's download hook runs immediately after the click that calls this, " +
            "so a posted name would arrive after the download that needs it",
        "onDownloadComplete" to
            "ordered against onDownloadChunk, which cannot be posted; posting only this one " +
            "would end the download before its last pieces were written",
    )

    /**
     * The `onX = ...` assignments inside the `AndroidBridge(` construction, as
     * name to the text of the assignment.
     *
     * Parsed rather than hard-coded so that a callback added later is covered
     * without anyone remembering this file exists.
     */
    private fun bridgeCallbacks(): Map<String, String> {
        val source = mainActivity.readText()
        val start = source.indexOf("val bridge = AndroidBridge(")
        assertTrue(start >= 0) {
            "Could not find the AndroidBridge construction in ${mainActivity.path}. " +
                "If it moved or was renamed, this test is measuring nothing, point it " +
                "at the new site rather than deleting it."
        }
        val open = source.indexOf('(', start)
        var depth = 0
        var end = -1
        for (i in open until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) { end = i; break }
            }
        }
        assertTrue(end > open) { "Unbalanced parentheses in the AndroidBridge construction." }

        // Each assignment runs to the start of the next one, not to the end of its
        // line, and comments come out of the value before it is searched. Both
        // halves matter, and each was wrong in one direction: reading to
        // end-of-line accused a correct callback whose body had been wrapped
        // across three lines, while leaving comments in let a trailing
        // "no hop needed" satisfy the search on a callback that had none.
        //
        // Comments are stripped from the extracted VALUES, not from the whole
        // file: the brace matching above counts parentheses, and a "//" inside a
        // string literal elsewhere in this activity would take its closing
        // parenthesis with it.
        val body = source.substring(open + 1, end)
        val heads = Regex("""\bon([A-Za-z]+)\s*=""").findAll(body).toList()
        return heads.mapIndexed { i, m ->
            val to = heads.getOrNull(i + 1)?.range?.first ?: body.length
            "on" + m.groupValues[1] to withoutComments(body.substring(m.range.last + 1, to))
        }.toMap()
    }

    /**
     * Line comments removed.
     *
     * The file this reads discusses `runOnUiThread` at length in prose, so a
     * sentence about the rule must not be able to satisfy a search for the rule.
     * Line comments only: no callback value in this construction contains a block
     * comment, and a stripper that handled them would have to handle strings too.
     */
    private fun withoutComments(value: String): String =
        value.lines().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }

    @Test
    fun `every UI-touching bridge callback hops to the UI thread`() {
        val callbacks = bridgeCallbacks()

        // Count the subject before judging it. A regex that stops matching would
        // otherwise report a clean run over nothing at all, which is the failure
        // this suite has shipped before.
        assertTrue(callbacks.size >= 5) {
            "Expected at least five on* callbacks in the AndroidBridge construction, " +
                "found ${callbacks.size}: ${callbacks.keys}. The parse is broken, not the code."
        }

        val missing = callbacks
            .filterKeys { it !in exempt }
            .filterValues { !it.contains("runOnUiThread") }
            .keys

        assertEquals(
            emptySet<String>(), missing,
            "These bridge callbacks reach the Activity from the WebView's JavaBridge " +
                "thread without hopping: $missing. Wrap the body in runOnUiThread, or " +
                "add the name to `exempt` with the reason it cannot hop."
        )
    }

    @Test
    fun `the exemption list names only callbacks that exist`() {
        val callbacks = bridgeCallbacks().keys
        val stale = exempt.keys - callbacks
        assertEquals(
            emptySet<String>(), stale,
            "The exemption list names callbacks that are no longer constructed here: " +
                "$stale. An exemption for something that does not exist hides nothing " +
                "and misleads the next reader."
        )
    }
}
