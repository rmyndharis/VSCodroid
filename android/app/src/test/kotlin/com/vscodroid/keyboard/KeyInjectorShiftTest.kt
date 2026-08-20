package com.vscodroid.keyboard

import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
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
 * What that costs is which chord the workbench resolves. Monaco decides how to
 * handle a keystroke from the modifiers on the event, so Ctrl with an unshifted
 * `{` is a different binding from Ctrl with a shifted one, and neither is the
 * one the user asked for. It does not cost the character. This paragraph said
 * "The character still appears, which is why nothing would look broken" until it
 * was measured on a device: a synthetic KeyboardEvent is untrusted, the browser
 * performs no default action for it, and tapping `{` inserted nothing whatever
 * the modifiers said. Characters now go through a real key press instead, which
 * is why every case here holds Ctrl.
 *
 * The injector already takes its WebView through the constructor, so the script
 * it hands over can be captured without touching production code.
 */
class KeyInjectorShiftTest {

    private val script = slot<String>()
    private val webView = mockk<WebView>(relaxed = true).also {
        every { it.evaluateJavascript(capture(script), any()) } returns Unit
    }

    /**
     * The suite runs in one JVM with no `forkEvery`, so anything MockK replaces
     * process-wide outlives the class that replaced it. Nothing here does that
     * today -- a plain `mockk` is an object, not a global -- but the rule is
     * uniform for a reason: `BridgeTokenUniformityTest` documents how a class
     * exempting itself on those grounds ends up poisoning `CrashReporterTest`,
     * and an exemption that depends on nobody adding a `mockkStatic` later is
     * not an exemption.
     */
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun inject(key: String, shiftKey: Boolean = false): String {
        // Ctrl for the reason given in the class comment: it is what keeps a
        // character on the path that generates this script.
        KeyInjector(webView).injectKey(key, ctrlKey = true, shiftKey = shiftKey)
        return script.captured
    }

    @Test
    fun `typing with only Shift active is left to the page`() {
        // The interceptor's own doc scopes it to Ctrl and Alt, and the guard is
        // what has to say so. Admitting Shift cancels the soft keyboard's
        // insertText and dispatches a synthetic keydown in its place, and Monaco
        // types through the input event that was just cancelled: tap Shift, type
        // s, and nothing appears. The row's Shift exists for the row's own keys,
        // which arrive by injectKey with the modifier already on the event.
        KeyInjector(webView).setupModifierInterceptor()

        val script = script.captured
        assertTrue(
            script.contains("if (!mod.ctrl && !mod.alt) return;"),
            "the interceptor must not act on Shift alone; it exists for the " +
                "chords a soft keyboard cannot type, not for capital letters"
        )
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
