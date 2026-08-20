package com.vscodroid.keyboard

import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Whether the JavaScript [KeyInjector] hands to the page is a program.
 *
 * The backslash key produced a syntax error and nothing else. `injectKey`
 * escaped `'` and `"` and left `\` alone, so the one table entry whose character
 * is a backslash rendered as `key: '\',` -- the backslash escapes the closing
 * quote, the literal swallows the rest of the line, and the whole injected IIFE
 * fails to parse. `evaluateJavascript` is called with a null callback, so the
 * parse error goes nowhere: the key does nothing, silently, every time. It is
 * reachable without going looking for it, since the `/` key on page 2 offers
 * `\` as its long-press alternate.
 *
 * These parse the emitted literals with a real parser rather than comparing them
 * to expected text. A string comparison written from the implementation agrees
 * with whatever the implementation does, including being wrong; `org.json` reads
 * the same string-literal grammar JavaScript does and either accepts the literal
 * and yields the character back, or does not.
 */
class KeyInjectorEscapingTest {

    private val script = slot<String>()
    private val webView = mockk<WebView>(relaxed = true).also {
        every { it.evaluateJavascript(capture(script), any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun inject(key: String): String {
        // Ctrl, and it is load-bearing rather than incidental. A bare character
        // is now typed as a real key press and never reaches the page as
        // JavaScript, so there would be no script to capture. A modifier makes
        // any key a chord, and a chord is what this generated source is for; the
        // escaping it checks is identical either way.
        KeyInjector(webView).injectKey(key, ctrlKey = true)
        return script.captured
    }

    /**
     * Pulls one `field: <literal>,` value out of the generated source.
     *
     * Deliberately does not try to interpret the literal itself -- that is the
     * parser's job below, and a hand-written unescaper here would just be a
     * third implementation of the thing under test.
     */
    private fun fieldLiteral(script: String, field: String): String =
        script.lineSequence()
            .map { it.trim() }
            .first { it.startsWith("$field: ") }
            .removePrefix("$field: ")
            .removeSuffix(",")

    /** The string a JavaScript engine would build from this literal. */
    private fun parseLiteral(literal: String): String = JSONArray("[$literal]").getString(0)

    @Test
    fun `the backslash key is emitted as a valid literal holding a backslash`() {
        val js = inject("\\")

        assertEquals(
            "\\", parseLiteral(fieldLiteral(js, "key")),
            "the backslash key did not survive as a backslash; this is the entry that used to " +
                "make the whole injected script a SyntaxError"
        )
        assertEquals("Backslash", parseLiteral(fieldLiteral(js, "code")))
    }

    @Test
    fun `the backslash key no longer emits the single-quoted form that broke`() {
        // The exact byte sequence of the defect: an apostrophe, a backslash, an
        // apostrophe. Pinned as well as the positive case because the positive
        // case would also pass if the field were dropped from the object
        // entirely, and a missing `key` is its own bug.
        assertFalse(
            inject("\\").contains("'\\'"),
            "the injected script still contains the unescaped single-quoted backslash"
        )
    }

    @Test
    fun `the quote keys are emitted as valid literals`() {
        // Both quote characters are in the table, and each breaks a different
        // naive escaper: a double quote breaks the double-quoted literal
        // jsQuote emits, an apostrophe broke nothing but was the only case the
        // old code did handle.
        assertEquals("\"", parseLiteral(fieldLiteral(inject("\""), "key")))
        assertEquals("'", parseLiteral(fieldLiteral(inject("'"), "key")))
    }

    /**
     * Every character the table defines, not the three that were interesting.
     *
     * The key set comes from [KeyMapping.toJsLookup], which is the table's own
     * public rendering, so a character added to `KeyMapping` later is covered by
     * this test without anyone remembering to add it here. Parsing that lookup
     * also checks the other half of the same concern: the interceptor installed
     * by `setupModifierInterceptor` embeds it as an object literal, and a single
     * bad key there stops the whole soft-keyboard modifier path from installing.
     */
    @Test
    fun `every key in the table injects a parseable literal`() {
        val table = JSONObject(KeyMapping.toJsLookup())
        val keys = table.keys().asSequence().toList()
        check(keys.size > 30) { "the key table came back nearly empty; this test would prove nothing" }

        for (key in keys) {
            val js = inject(key)
            assertEquals(
                key, parseLiteral(fieldLiteral(js, "key")),
                "the key '$key' did not survive injection intact"
            )
            // The code field goes through the same quoting and is equally able
            // to carry a character that needs escaping.
            assertEquals(
                table.getJSONArray(key).getString(0), parseLiteral(fieldLiteral(js, "code")),
                "the code for '$key' did not survive injection intact"
            )
        }
    }

    @Test
    fun `a plain letter is unaffected`() {
        // The escaping must not start quoting things that need no quoting.
        assertEquals("a", parseLiteral(fieldLiteral(inject("a"), "key")))
        assertEquals("KeyA", parseLiteral(fieldLiteral(inject("a"), "code")))
    }

    /**
     * [KeyMapping.jsQuote] on its own, since it is now the one escaper both the
     * key row and the soft-keyboard lookup go through.
     */
    @Test
    fun `jsQuote emits a literal that reads back as its input`() {
        for (s in listOf("\\", "\"", "'", "a", "Backslash", "\\\\", "\"\\\"", "")) {
            assertEquals(s, parseLiteral(KeyMapping.jsQuote(s)), "jsQuote mangled ${s.length} chars")
        }
    }
}
