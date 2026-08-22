package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the page holds a download's bytes for at least as long as the queue can
 * make the user wait for them.
 *
 * Two clocks, set independently, with nothing connecting them. The page-side hold
 * starts at the anchor click, before the platform has decided anything, and
 * releases itself when its budget runs out. The Android side runs one download at
 * a time on purpose: there is one create-document picker and two open at once
 * produce two results that cannot be told apart. So a file clicked as part of a
 * multi-select waits behind up to `MAX_QUEUED` pickers, each of which is a trip
 * into another app that takes the user as long as it takes.
 *
 * When the budget expires first, the failure lands after the user has done the
 * work: they answer the picker, a document is created under the name they typed,
 * the bytes are asked for, the blob is gone, and the fall back to `fetch` on a
 * `blob:` URL is refused by the page's own CSP. A save that was never going to
 * work is reported as a failure over a file the picker had already made.
 *
 * There is no JavaScript harness in this repository, so this is a predicate over
 * two constants nothing else connects, in the same spirit as the source-shape
 * cases in `DownloadListenerWiringTest`. It is deliberately a floor rather than
 * an equality: what matters is the relation between the two, so raising the
 * queue without raising the budget is what has to go red.
 */
class DownloadHoldBudgetTest {

    private val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt").readText()

    /** Line comments removed, so prose beside the rule cannot satisfy a search for it. */
    private fun withoutComments(text: String): String =
        text.lines().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }

    /** The body of an injected `function name(...) {` declaration, to its closing brace. */
    private fun script(declaration: String): String {
        val start = source.indexOf(declaration)
        assertTrue(start >= 0) {
            "the injected $declaration is gone from MainActivity.kt, so this test is " +
                "measuring nothing. If the capture script was rewritten, point this at " +
                "whatever reads a download's bytes now rather than deleting it."
        }
        val open = source.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < source.length) {
            if (source[i] == '{') depth += 1
            if (source[i] == '}') {
                depth -= 1
                if (depth == 0) return withoutComments(source.substring(open, i + 1))
            }
            i += 1
        }
        throw AssertionError("Could not find the end of $declaration in MainActivity.kt")
    }

    private val readerFor by lazy { script("function readerFor(url) {") }

    @Test
    fun `the script being checked was actually found`() {
        assertTrue(readerFor.isNotBlank() && readerFor.contains("blob.stream")) {
            "readerFor no longer reads the blob off the held object, so the two cases " +
                "here are searching text that no longer decides anything:\n$readerFor"
        }
    }

    @Test
    fun `the blob hold outlasts the queue it may wait behind`() {
        val matches = Regex("""var HOLD_MS = (\d+);""").findAll(source).toList()

        assertEquals(1, matches.size) {
            "expected exactly one blob-hold budget in the capture script, found " +
                "${matches.size}. Two of them drift, and the one that is wrong is the " +
                "one nobody reads."
        }
        val holdMs = matches.single().groupValues[1].toLong()
        val floor = MAX_QUEUED * 60_000L
        assertTrue(holdMs >= floor) {
            "the page gives up a download's bytes after ${holdMs}ms while up to " +
                "$MAX_QUEUED downloads can be queued ahead of it, one create-document " +
                "picker at a time. At a minute per picker that is ${floor}ms of waiting " +
                "the hold has to outlast. Below it the user answers the picker, a " +
                "document is created under the name they typed, and the download then " +
                "fails because the blob was revoked while they were choosing."
        }
    }

    @Test
    fun `the hold is released as soon as the bytes are being read`() {
        val released = readerFor.indexOf("held.delete(url)")
        val revoked = readerFor.indexOf("revoke(url)")
        val fetched = readerFor.indexOf("return fetch(")

        assertTrue(released >= 0 && revoked >= 0) {
            "the hold is kept for its whole budget even after the bytes are being read, " +
                "so a saved file's blob stays pinned in the page for minutes after the " +
                "save. The reader keeps the bytes alive by itself, which is what makes " +
                "the release safe and what pays for the budget above:\n$readerFor"
        }
        assertTrue(fetched >= 0) {
            "the fall back to fetch is gone, so the ordering below cannot be judged:\n" +
                readerFor
        }
        assertTrue(released < fetched && revoked < fetched) {
            "the release sits on the fetch path rather than the blob path. The fetch " +
                "has not read anything yet, so revoking there refuses the very request " +
                "the hold exists for."
        }
    }
}
