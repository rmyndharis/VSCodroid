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

    /**
     * The branch a `beforeinput` takes when Shift is the only modifier held.
     *
     * Delimited by its own braces rather than searched for across the script: the
     * two things asserted about it, that nothing is cancelled and that the latch
     * is dropped, both appear elsewhere in the listener for the Ctrl and Alt
     * paths, and a whole-script search is satisfied by either of those copies
     * while this branch does neither.
     */
    private fun shiftOnlyBranch(): String {
        KeyInjector(webView).setupModifierInterceptor()
        val installed = script.captured
        val guard = "if (!mod.ctrl && !mod.alt) {"
        val start = installed.indexOf(guard)
        assertTrue(
            start >= 0,
            "the listener no longer guards on Ctrl or Alt being held, so this test is " +
                "reading nothing and its verdict is worth nothing. It reads:\n$installed"
        )
        val end = installed.indexOf("}", start + guard.length)
        assertTrue(end > start, "the guard's block is never closed")
        return installed.substring(start, end + 1)
    }

    @Test
    fun `typing with only Shift active is left to the page`() {
        // The interceptor's own doc scopes what it ACTS on to Ctrl and Alt, and
        // the guard is what has to say so. Admitting Shift cancels the soft
        // keyboard's insertText and dispatches a synthetic keydown in its place,
        // and Monaco types through the input event that was just cancelled: tap
        // Shift, type s, and nothing appears. The row's Shift exists for the
        // row's own keys, which arrive by injectKey with the modifier already on
        // the event.
        val branch = shiftOnlyBranch()

        assertFalse(
            branch.contains("preventDefault"),
            "the soft keyboard's own insertion is cancelled while only Shift is held, " +
                "so the tap types nothing at all. It reads: $branch"
        )
        assertFalse(
            branch.contains("dispatchEvent"),
            "a synthetic keystroke is sent in place of the character the page was " +
                "about to insert. It reads: $branch"
        )
        assertTrue(
            branch.contains("return;"),
            "the branch falls through into the Ctrl and Alt handling. It reads: $branch"
        )
    }

    @Test
    fun `a Shift the page has answered is not left latched`() {
        // Nothing else clears it while the keyboard is up: the row un-latches on
        // its own key presses, on the end of a trackpad drag and when the IME
        // hides, and none of those is typing on the soft keyboard. What a
        // surviving latch costs changed with the character mapping: it used to
        // add a shiftKey nobody read, and it now decides WHICH character the
        // next row key types, so a Shift the user has forgotten turns `/` into
        // `?`. The row's own poll reads this flag back, so clearing it here is
        // also what un-lights the button and stops that poll.
        val branch = shiftOnlyBranch()

        assertTrue(
            branch.contains("mod.shift = false;"),
            "a Shift held while the page takes text of its own stays latched forever, " +
                "and the next key tapped on the row types a different character than " +
                "the one on it. It reads: $branch"
        )
    }

    /**
     * The branch the listener takes for a character the table does not carry,
     * which is every letter.
     *
     * Sliced by its own `} else {` rather than searched for across the script:
     * the branch above it assigns the same three variables from the table, so a
     * whole-script search for an assignment to `shiftKey` is satisfied by that
     * copy while this one leaves it alone.
     */
    private fun letterBranch(): String {
        KeyInjector(webView).setupModifierInterceptor()
        val installed = script.captured
        val marker = "var upper = ch.toUpperCase();"
        val start = installed.indexOf(marker)
        assertTrue(
            start >= 0,
            "the listener no longer derives a key from the character itself, so this test " +
                "is reading nothing and its verdict is worth nothing. It reads:\n$installed",
        )
        // The closing brace on a line of its own, found by shape rather than by
        // a counted indent: the script is trimIndent'ed on its way out, so the
        // column it sits in is a property of the Kotlin file around it and not of
        // the JS. Nothing inside this branch closes a block, so the first one is
        // the branch's own.
        val end = Regex("\\n\\s*\\}").find(installed, start)
        assertTrue(end != null, "the branch is never closed")
        return installed.substring(start, end!!.range.first)
    }

    @Test
    fun `a capital typed on the soft keyboard carries Shift`() {
        // The one route a phone has to Ctrl+Shift+letter: latch Ctrl on the row,
        // then use the soft keyboard's own Shift for the letter. The table this
        // branch falls back from carries no letters, so nothing else can say the
        // character was shifted, and a chord that loses it is not a chord that
        // does nothing. Measured on an API 37 emulator: Ctrl with a capital P
        // arrived as {key:"P", code:"KeyP", ctrl:true, shift:false} and opened
        // Quick Open; the same event with shift true opens the Command Palette.
        val branch = letterBranch()

        assertTrue(
            branch.contains("shiftKey = true"),
            "a capital reaches the page as an unshifted keystroke, so Ctrl with a capital " +
                "resolves to the unshifted chord: Ctrl+Shift+P opens Quick Open instead of " +
                "the Command Palette. It reads: $branch",
        )
    }

    @Test
    fun `a lowercase letter is not given a Shift it never had`() {
        // The other half, and the reason the test above is not satisfied by
        // forcing the flag on: an ordinary letter typed with Ctrl latched has to
        // stay the unshifted chord, or Ctrl+c becomes Ctrl+Shift+C.
        val branch = letterBranch()

        assertTrue(
            branch.contains("/[A-Z]/"),
            "the branch decides Shift from something other than the character being an " +
                "upper-case letter, so it cannot tell `p` from `P`. It reads: $branch",
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
