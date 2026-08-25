package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Which question `MainActivity` asks before it navigates the WebView.
 *
 * `isServerRunning()` is `Process.isAlive`: true from the instant the process is
 * spawned, and true for the whole of a restart after a crash, neither of which
 * means anything is listening on the port. Navigating on it produces a
 * connection-refused page, and `onReceivedError` only logs, so nothing takes it
 * away again. `isServerReady()` reports what the health probe found.
 *
 * This reads the source, which is a weaker kind of test than the rest of this
 * suite. It is one of two layers, and it is worth being exact about which half
 * each covers, because an earlier version of this comment claimed there was no
 * seam to extract and that turned out to be wrong.
 *
 * A source-reading test sees the token `isServerReady` and not the branch, so it
 * cannot tell a call whose answer is obeyed from one whose answer is discarded.
 * That mutation was applied to both call sites (keep the calls, drop the
 * verdicts) and all 632 tests stayed green. What could not be extracted is the
 * *identity* of the method, since a function handed a boolean cannot tell which
 * question produced it; what could be, and now is, are the branches themselves:
 * `bindDecision` and `shouldActOnResume`, covered by
 * [ServerReadinessDecisionTest], which fails on either mutation by name.
 *
 * So: that file owns "the answer is obeyed", this one owns "the right question
 * is asked". Neither subsumes the other, and only this one would notice
 * `isServerRunning` coming back.
 *
 * What neither catches: a third call site added later that asks the wrong
 * question through some other spelling, or the same mistake made in another file.
 */
class ServerReadinessCallSiteTest {

    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    private val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /** Source lines with comments dropped, since both methods are named in prose. */
    private fun codeLines(): List<IndexedValue<String>> =
        mainActivity.readLines().withIndex().filterNot { (_, line) ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    @Test
    fun `the activity asks whether the server is serving, not whether a process exists`() {
        check(mainActivity.isFile) {
            "MainActivity.kt not found at ${mainActivity.absolutePath}: this test " +
                "would otherwise pass by looking at nothing"
        }

        val offenders = codeLines()
            .filter { (_, line) -> line.contains("isServerRunning") }
            .map { (i, line) -> "MainActivity.kt:${i + 1}: ${line.trim()}" }

        assertEquals(
            emptyList<String>(), offenders,
            "isServerRunning() is Process.isAlive and is true long before the server " +
                "answers. Navigating on it gives the user a connection-refused page. " +
                "Use isServerReady(), which reports the health probe's own result.",
        )
    }

    // The two cases below replaced a single count with a floor of two. The floor
    // was the control for the case above, and it stopped discriminating once a
    // third `isServerReady()` reached this file: deleting either decision, or
    // reverting one to the port-only check, still left two tokens for it to
    // count, so it passed. A count cannot say WHICH decision it counted, and
    // that is the whole question here. Each decision is now pinned where it
    // lives, so absence fails by name rather than by arithmetic.
    //
    // `SourceScan.body` throws with a sentence when the declaration is gone,
    // which is the self-blindness this class of test otherwise has: "found
    // nothing" and "found what I wanted" look identical from the same distance.

    @Test
    fun `the resume guard asks the readiness question and obeys it`() {
        val body = SourceScan.withoutComments(
            SourceScan.body(source, "private fun handleResumeFromBackground()"),
        )

        val decisions = Regex("""shouldActOnResume\(""").findAll(body).count()
        assertEquals(
            1, decisions,
            "expected exactly one resume decision inside handleResumeFromBackground(), " +
                "found $decisions. Two of them split the verdict this case pins, and " +
                "zero means the guard is gone.",
        )
        assertTrue(
            body.contains("shouldActOnResume(nodeService?.isServerReady()"),
            "the resume decision must be handed the readiness answer. A port that is " +
                "merely allocated, or a process that is merely alive, reloads the page " +
                "into a socket nothing is listening on yet.",
        )

        // The call and the branch are separate mutations: keeping the call and
        // dropping its `return` leaves this file's token counts untouched and was
        // one of the mutations the old floor passed.
        val decidedAt = body.indexOf("shouldActOnResume(")
        val actedAt = body.indexOf("resumeAction(")
        assertTrue(
            actedAt > decidedAt,
            "resumeAction( no longer follows the resume decision in this body, so the " +
                "span this case reads for the verdict is not the one that guards it",
        )
        assertTrue(
            body.substring(decidedAt, actedAt).contains("return"),
            "the resume decision's verdict must be obeyed before resumeAction( is " +
                "reached; nothing returns between them, so the guard is computed and " +
                "discarded",
        )
    }

    @Test
    fun `the binding decision is handed the readiness answer`() {
        val body = SourceScan.withoutComments(
            SourceScan.body(source, "private fun setupServiceCallbacks()"),
        )

        val decisions = Regex("""bindDecision\(""").findAll(body).count()
        assertEquals(
            1, decisions,
            "expected exactly one binding decision inside setupServiceCallbacks(), " +
                "found $decisions",
        )
        assertTrue(
            body.contains("ready = service.isServerReady()"),
            "the binding decision must be told what the health probe found. Handed " +
                "`getPort() > 0` instead it navigates the WebView at a port that is " +
                "allocated but not yet bound, and onReceivedError only logs, so the " +
                "connection-refused page stays.",
        )
    }
}
