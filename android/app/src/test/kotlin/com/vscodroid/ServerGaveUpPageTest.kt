package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The page shown when the server will not come back, and the escaping it rests on.
 *
 * The page itself needs a device: it replaces a WebView's content and its button
 * is a navigation the bootstrap client answers, neither of which a JVM can build.
 * What can be checked here is the part that would fail silently rather than
 * visibly, which is the interpolation. The page is assembled with string
 * templates, so a message carrying a quote or an angle bracket would not throw,
 * it would change the markup around it, and the retry button is inside that
 * markup.
 *
 * Nothing hostile reaches it today: both strings come from this app's resources.
 * It is covered because the next person to interpolate something here may be
 * carrying a filename, a path, or an exception message.
 */
class ServerGaveUpPageTest {

    @Test
    fun `the five characters that change surrounding markup are escaped`() {
        assertEquals("&amp;", escapeHtml("&"))
        assertEquals("&lt;", escapeHtml("<"))
        assertEquals("&gt;", escapeHtml(">"))
        assertEquals("&quot;", escapeHtml("\""))
        assertEquals("&#39;", escapeHtml("'"))
    }

    @Test
    fun `the ampersand is escaped first, so an escape is not escaped twice`() {
        // Kills the ordering bug this is most likely to acquire: replacing "<"
        // before "&" turns a plain "<" into "&amp;lt;", which renders as the
        // text "&lt;" instead of a bracket. Ordering is invisible in the output
        // of every single-character case above.
        assertEquals("&amp;lt;", escapeHtml("&lt;"))
        assertEquals("&lt;a&gt;", escapeHtml("<a>"))
    }

    @Test
    fun `text with nothing to escape is returned unchanged`() {
        val plain = "The development server stopped and could not be restarted."
        assertEquals(plain, escapeHtml(plain))
    }

    @Test
    fun `a message cannot close the anchor the retry button lives in`() {
        // The concrete harm, stated as the assertion. The button is an <a> tag
        // in the same document as the message, so a message able to emit a tag
        // could take the only way out off the page.
        val hostile = """</p><a href="#">not the retry button</a><p>"""

        val escaped = escapeHtml(hostile)

        assertFalse(escaped.contains("<a"), "an anchor survived escaping: $escaped")
        assertFalse(escaped.contains("</p>"), "a closing tag survived escaping: $escaped")
        assertTrue(escaped.contains("&lt;a"), "the text should still be readable as text")
    }
}
