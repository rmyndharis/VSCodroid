package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Which question `MainActivity` asks before it navigates the WebView.
 *
 * `isServerRunning()` is `Process.isAlive`: true from the instant the process is
 * spawned, and true for the whole of a restart after a crash — neither of which
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
 * That mutation was applied to both call sites — keep the calls, drop the
 * verdicts — and all 632 tests stayed green. What could not be extracted is the
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

    /** Source lines with comments dropped, since both methods are named in prose. */
    private fun codeLines(): List<IndexedValue<String>> =
        mainActivity.readLines().withIndex().filterNot { (_, line) ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    @Test
    fun `the activity asks whether the server is serving, not whether a process exists`() {
        check(mainActivity.isFile) {
            "MainActivity.kt not found at ${mainActivity.absolutePath} — this test " +
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

    @Test
    fun `both places that decide on the server ask it`() {
        // A file with neither call would satisfy the test above. This is the
        // control for it, not a second assertion about the same thing.
        //
        // A floor of two rather than of one, and the difference is a real hole
        // that a threshold of `> 0` left open: there are two decisions here, the
        // bind-time check and the resume-from-background guard, and reverting
        // just one of them restores half the defect while leaving both tests
        // green. Either one alone is enough to put a user in front of a dead
        // port.
        //
        // A floor rather than an exact count, because a third site would be an
        // ordinary thing to add and should not have to come here first.
        check(mainActivity.isFile) { "MainActivity.kt not found" }

        val readinessCalls = codeLines().count { (_, line) -> line.contains("isServerReady()") }

        assertTrue(
            readinessCalls >= 2,
            "both decisions must ask the readiness question; found $readinessCalls call site(s)",
        )
    }
}
