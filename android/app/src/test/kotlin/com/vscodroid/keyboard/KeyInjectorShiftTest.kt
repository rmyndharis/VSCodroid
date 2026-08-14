package com.vscodroid.keyboard

import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wire between [KeyMapping]'s `requiresShift` flag and the event the page
 * actually receives.
 *
 * `KeyMappingTest` checks the flag on all fifteen characters that carry it and
 * never checked that anything reads it. Dropping `|| keyDef.requiresShift` from
 * the injector leaves every one of those green, and leaves the extra key row
 * sending `shiftKey: false` for `{`, `(`, `:` and the rest.
 *
 * What that costs is the whole point of those keys. Monaco decides how to handle
 * a keystroke from the modifiers on the event, not from the character, so a `{`
 * arriving without Shift is not the same keystroke — auto-closing brackets and
 * the bindings that watch for shifted punctuation stop matching. The character
 * still appears, which is why nothing would look broken.
 *
 * The injector already takes its WebView through the constructor, so the script
 * it hands over can be captured without touching production code.
 */
class KeyInjectorShiftTest {

    private val script = slot<String>()
    private val webView = mockk<WebView>(relaxed = true).also {
        every { it.evaluateJavascript(capture(script), any()) } returns Unit
    }

    private fun inject(key: String, shiftKey: Boolean = false): String {
        KeyInjector(webView).injectKey(key, shiftKey = shiftKey)
        return script.captured
    }

    @Test
    fun `a character that needs Shift is sent with Shift held`() {
        // `{` rather than a letter: a letter is unshifted, so it would pass
        // whether or not the flag is read, and the fixture would agree with the
        // bug it exists to catch.
        assertTrue(
            inject("{").contains("shiftKey: true"),
            "{ requires Shift on a physical keyboard; the event has to say so"
        )
    }

    @Test
    fun `a character that does not need Shift is sent without it`() {
        // The other half. Without this, forcing shiftKey to a constant true
        // would satisfy the test above.
        assertFalse(
            inject("a").contains("shiftKey: true"),
            "an ordinary character must not arrive as a shifted keystroke"
        )
    }

    @Test
    fun `an explicit Shift is still honoured for an unshifted character`() {
        // The flag widens the caller's request; it does not replace it. Tapping
        // Shift on the key row and then a letter has to reach the page as a
        // shifted letter.
        assertTrue(
            inject("a", shiftKey = true).contains("shiftKey: true"),
            "a Shift the caller asked for cannot be dropped"
        )
    }
}
