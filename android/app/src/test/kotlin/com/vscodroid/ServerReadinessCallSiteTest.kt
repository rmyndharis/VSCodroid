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
 * each covers:
 *
 *  - [ServerReadinessDecisionTest] covers the pure decision functions
 *    (`bindDecision` and `shouldActOnResume`) as values.
 *  - This file covers the call sites in `MainActivity`: that the right readiness
 *    question (`isServerReady()`) is handed to both decisions, that `onStop` and
 *    `onStart` wire the resume check, that `handleResumeFromBackground` returns
 *    when `shouldActOnResume` answers false, and that the `BindDecision.Load` arm
 *    records `serverPort` and calls `loadVSCode`.
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
    fun `onStop records the background timestamp and onStart runs the resume handler`() {
        val onStop = SourceScan.withoutComments(
            SourceScan.body(source, "override fun onStop()"),
        )
        assertTrue(
            Regex("""backgroundedAt\s*=\s*SystemClock\.elapsedRealtime\(\)""").containsMatchIn(onStop),
            "onStop no longer records backgroundedAt, so handleResumeFromBackground always " +
                "sees 0 and returns at its first line",
        )

        val onStart = SourceScan.withoutComments(
            SourceScan.body(source, "override fun onStart()"),
        )
        assertTrue(
            Regex("""\bhandleResumeFromBackground\s*\(\s*\)""").containsMatchIn(onStart),
            "onStart no longer calls handleResumeFromBackground(); moving or dropping the " +
                "call skips background recovery or runs it after an activity result has " +
                "already cleared the pending picker flag",
        )
    }

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

        val decidedAt = body.indexOf("shouldActOnResume(")
        val actedAt = body.indexOf("resumeAction(")
        assertTrue(
            actedAt > decidedAt,
            "resumeAction( no longer follows the resume decision in this body, so the " +
                "span this case reads for the verdict is not the one that guards it",
        )

        val readyAt = body.indexOf("nodeService?.isServerReady()")
        val decideCallEnd = body.indexOf(")", decidedAt)
        val feedsDecision = when {
            readyAt < 0 -> false
            decideCallEnd > decidedAt && readyAt in decidedAt until decideCallEnd -> true
            else -> {
                val localMatch = Regex("""val\s+(\w+)\s*=\s*nodeService\?\.isServerReady\(\)""")
                    .find(body.substring(0, decidedAt))
                val localName = localMatch?.groupValues?.get(1)
                localName != null &&
                    Regex("""shouldActOnResume\(\s*$localName\b""")
                        .containsMatchIn(body.substring(decidedAt, actedAt))
            }
        }
        assertTrue(
            feedsDecision,
            "the resume decision must be handed the readiness answer. A port that is " +
                "merely allocated, or a process that is merely alive, reloads the page " +
                "into a socket nothing is listening on yet.",
        )

        val guardStart = body.lastIndexOf("if", decidedAt).coerceAtLeast(0)
        val guardSpan = body.substring(guardStart, actedAt)
        assertTrue(
            guardSpan.contains("return"),
            "the resume decision's verdict must be obeyed before resumeAction( is " +
                "reached; nothing returns between them, so the guard is computed and " +
                "discarded",
        )
        val negatedGuard = Regex("""!\s*shouldActOnResume\(""").containsMatchIn(guardSpan) ||
            Regex("""shouldActOnResume\([\s\S]*?\)\s*==\s*false""").containsMatchIn(guardSpan)
        assertTrue(
            negatedGuard,
            "handleResumeFromBackground must return when shouldActOnResume answers false, " +
                "not when it answers true: $guardSpan",
        )
    }

    @Test
    fun `the binding decision is handed the readiness answer and its Load arm loads the port`() {
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
            body.contains("ready = service.isServerReady()") ||
                Regex("""bindDecision\([\s\S]*?service\.isServerReady\(\)""").containsMatchIn(body),
            "the binding decision must be told what the health probe found. Handed " +
                "`getPort() > 0` instead it navigates the WebView at a port that is " +
                "allocated but not yet bound, and onReceivedError only logs, so the " +
                "connection-refused page stays.",
        )

        val loadArmStart = body.indexOf("is BindDecision.Load ->")
        val waitArmStart = body.indexOf("BindDecision.Wait ->", loadArmStart.coerceAtLeast(0))
        assertTrue(
            loadArmStart >= 0 && waitArmStart > loadArmStart,
            "expected an `is BindDecision.Load ->` arm before `BindDecision.Wait ->` inside " +
                "setupServiceCallbacks()",
        )
        val loadArm = body.substring(loadArmStart, waitArmStart)
        val portRecordedAt = loadArm.indexOf("serverPort = decision.port")
        val loadedAt = loadArm.indexOf("loadVSCode(decision.port)")
        assertTrue(
            portRecordedAt >= 0,
            "the BindDecision.Load arm no longer records `serverPort = decision.port`, so a " +
                "late-bound activity leaves serverPort at 0",
        )
        assertTrue(
            loadedAt > portRecordedAt,
            "the BindDecision.Load arm no longer calls `loadVSCode(decision.port)` after " +
                "recording serverPort",
        )
    }
}
