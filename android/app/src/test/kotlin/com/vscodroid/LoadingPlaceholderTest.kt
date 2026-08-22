package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The first screen of every cold start, and the one that was blank.
 *
 * `WebView.loadData` does not load a document, it builds a `data:` URL out of one,
 * and an app targeting Q or later gets no escaping from the platform: the first
 * `#` ends the URL and the rest becomes a fragment. The placeholder opens with
 * `background:#1e1e1e`, so the WebView parsed an unterminated `<body` start tag,
 * dropped it at end of input and painted its own default white for the whole of
 * the server start, and again behind the Retry link.
 *
 * Two halves, because either one alone passes over the defect. [dataUrlSafe] can
 * be driven here; the call sites cannot, so they are read off the source the way
 * `SafFolderLogCallSiteTest` reads the same file. A helper nothing calls escapes
 * nothing.
 *
 * NEGATIVE CONTROL, measured rather than assumed:
 *  - dropping `.replace("#", "%23")` from [dataUrlSafe] reddens
 *    `the character that ends a data URL is encoded`.
 *  - swapping the two replacements so `#` is escaped before `%` reddens
 *    `a percent is encoded first, so an escape is not escaped twice`, and the case
 *    above it as well, since a bare `#` then comes out as `%2523`.
 *  - deleting `dataUrlSafe(` from either `loadData` call in MainActivity.kt
 *    reddens `both loading-page loads go through the escape`.
 */
class LoadingPlaceholderTest {

    private val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt").readText()

    @Test
    fun `the character that ends a data URL is encoded`() {
        assertEquals("%23", dataUrlSafe("#"))
        assertEquals("background:%231e1e1e;color:%23888", dataUrlSafe("background:#1e1e1e;color:#888"))
    }

    @Test
    fun `a percent is encoded first, so an escape is not escaped twice`() {
        // The ordering bug this is most likely to acquire, and it takes a subject
        // holding both characters to see: with "#" escaped first, the "%" of the
        // "%23" it just wrote is escaped again into "%2523", and the page shows
        // the three characters "%23" where a hash belonged. A subject with only
        // one of the two comes out the same either way, which is why the case
        // below cannot stand in for this one.
        assertEquals("%23%25", dataUrlSafe("#%"))
        assertEquals("width:50%25", dataUrlSafe("width:50%"))
    }

    @Test
    fun `markup with nothing to encode is returned unchanged`() {
        val plain = """<html><body><p>Starting server...</p></body></html>"""
        assertEquals(plain, dataUrlSafe(plain))
    }

    @Test
    fun `both loading-page loads go through the escape`() {
        // The setup one is what a launch shows; the retry one is what the Retry
        // link on the server-gave-up page shows, and that is the copy a user
        // reaches only after something has already gone wrong.
        val calls = source.lines().filter { line ->
            val code = line.substringBefore("//")
            code.contains(".loadData(")
        }

        assertEquals(
            2, calls.size,
            "expected the load in setupWebView and the one in retryServerStart. If a " +
                "call site was added or removed, update this count and check the new " +
                "one escapes its content. Found: " + calls.map { it.trim() },
        )
        calls.forEach { line ->
            assertTrue(line.contains("dataUrlSafe(")) {
                "a loading page is handed to loadData raw, so everything from its first " +
                    "'#' is read as the URL fragment and the WebView shows a blank white " +
                    "screen for the whole server start. Found: ${line.trim()}"
            }
        }
    }

    @Test
    fun `the page still carries the colours that made it fail`() {
        // Without this the pair above can be satisfied by a placeholder with no
        // '#' left in it, which would be a different page rather than a fixed one:
        // the dark background is the whole point of showing it at all.
        assertTrue(source.contains("background:#1e1e1e")) {
            "the loading page no longer sets the dark background it exists to show"
        }
    }
}
