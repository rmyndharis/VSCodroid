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
 * What a latched Ctrl or Alt does when the page reports input this listener has
 * no chord for.
 *
 * The interceptor acts on two kinds of `beforeinput`: an `insertText`, which it
 * turns into a chord, and the two deletes, which become Ctrl+Backspace and its
 * neighbours. Both spend the latch on the way out. Everything else a soft
 * keyboard produces, a paste, an IME composition update, an autocorrect
 * replacement, a word delete, used to leave it standing, and a modifier left
 * standing is not a modifier that did nothing: the next ordinary character is
 * cancelled and dispatched as a chord in its place, so typing `a` after a paste
 * selects the document instead of inserting a letter and the keystroke after
 * that replaces the selection.
 *
 * [KeyInjectorShiftTest] holds the same rule for a lone Shift, which was already
 * spent on every event. These hold the branch the other two modifiers reach.
 * Reachable in the shipped build: `MainActivity.injectBridgeToken` installs this
 * listener with no debug-only guard.
 *
 * Read off the injected script, the way `KeyInjectorShiftTest` reads the branch
 * it is about, because there is no JS engine on the test classpath. The branch is
 * delimited by its own braces rather than searched for across the whole listener:
 * the delete branch above it clears the same three flags, so a whole-script
 * search would be satisfied by that copy while this branch cleared nothing.
 *
 * NEGATIVE CONTROL, per case below. Dropping the three `mod.* = false;` lines
 * from the branch, which is a return to the bare `if (...) return;` this was,
 * turns the slice's own control assertion red, because the block it looks for is
 * gone; keeping the block and dropping only `mod.ctrl = false;` turns `a
 * modifier the listener cannot act on is spent` red. Adding an
 * `e.preventDefault();` to the branch turns `the page keeps the input this
 * listener has no chord for` red.
 */
class KeyInjectorLatchTest {

    private val script = slot<String>()
    private val webView = mockk<WebView>(relaxed = true).also {
        every { it.evaluateJavascript(capture(script), any()) } returns Unit
    }

    /** Uniform across this package; [KeyInjectorShiftTest] gives the reason. */
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * The branch a `beforeinput` takes when Ctrl or Alt is held and the input is
     * neither an insertText nor one of the two deletes.
     */
    private fun unhandledInputBranch(): String {
        KeyInjector(webView).setupModifierInterceptor()
        val installed = script.captured
        val guard = "if (e.inputType !== 'insertText' || !e.data) {"
        val start = installed.indexOf(guard)
        assertTrue(
            start >= 0,
            "the listener has no block for an inputType it cannot act on, so either this " +
                "test is reading nothing or the branch is back to a bare `return` that " +
                "leaves the latch standing. It reads:\n$installed"
        )
        val end = installed.indexOf("}", start + guard.length)
        assertTrue(end > start, "the branch's block is never closed")
        return installed.substring(start, end + 1)
    }

    @Test
    fun `a modifier the listener cannot act on is spent, not carried`() {
        val branch = unhandledInputBranch()

        assertTrue(
            branch.contains("mod.ctrl = false;"),
            "a Ctrl held across a paste or a composition survives it, and attaches to " +
                "whichever character is typed next: that character is cancelled and sent " +
                "as Ctrl+<char> instead. It reads: $branch"
        )
        assertTrue(
            branch.contains("mod.alt = false;"),
            "an Alt held across the same input survives it, on the same terms. " +
                "It reads: $branch"
        )
        assertTrue(
            branch.contains("mod.shift = false;"),
            "a Shift held together with Ctrl or Alt skips the lone-Shift branch above, " +
                "so this is the only place that spends it, and a surviving one decides " +
                "WHICH character the next row key types. It reads: $branch"
        )
    }

    @Test
    fun `the page keeps the input this listener has no chord for`() {
        // There is nothing to send in its place: no chord exists for a paste or a
        // composition update, so cancelling one would leave the user's tap
        // producing nothing at all. That is the same trade the lone-Shift branch
        // makes, and it is what makes spending the latch here safe.
        val branch = unhandledInputBranch()

        assertFalse(
            branch.contains("preventDefault"),
            "the page's own insertion is cancelled for input nothing replaces, so the " +
                "paste or the composed word is lost. It reads: $branch"
        )
        assertFalse(
            branch.contains("dispatchEvent"),
            "a synthetic keystroke is sent for an inputType this listener has no key " +
                "for. It reads: $branch"
        )
    }
}
