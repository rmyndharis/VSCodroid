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
 * What a latched Ctrl or Alt does with each kind of input the page reports.
 *
 * The interceptor acts on two kinds of `beforeinput`: a single-character
 * `insertText`, which it turns into a chord, and the edits that stand for a key,
 * which become Ctrl+Backspace, Ctrl+Delete and Ctrl+Enter. Both spend the latch
 * on the way out. Everything else a soft keyboard produces, a paste, an IME
 * composition update, an autocorrect replacement, a word delete, used to leave it
 * standing, and a modifier left standing is not a modifier that did nothing: the
 * next ordinary character is cancelled and dispatched as a chord in its place, so
 * typing `a` after a paste selects the document instead of inserting a letter and
 * the keystroke after that replaces the selection.
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
 *
 * Two inputs never produce a `beforeinput` for any branch to spend on, and the
 * last cases hold the hooks that spend the latch for them: a composition on the
 * EditContext edit path, which Chromium reports to the `EditContext` object and
 * not to the element, and typing inside a frame, which no listener in this
 * document can see. Dropping either hook turns its case red at the slice.
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

    private fun installedListener(): String {
        KeyInjector(webView).setupModifierInterceptor()
        return script.captured
    }

    /**
     * The branch a `beforeinput` takes when Ctrl or Alt is held and there is no
     * single keystroke to send in place of what the page was going to insert.
     *
     * The slice starts at the guard rather than after it, because the condition
     * itself is half of what this branch decides: a word committed in one event
     * belongs here and reached the chord loop instead.
     */
    private fun unhandledInputBranch(): String {
        val installed = installedListener()
        val guard = "if (e.inputType !== 'insertText'"
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

    /**
     * The branch that turns an edit the page reports into one keystroke.
     *
     * Delimited by the guard and the `return;` that closes it: the block holds a
     * nested object literal, so counting to the next `}` would stop inside the
     * event init rather than at the end of the arm.
     */
    private fun commandBranch(): String {
        val installed = installedListener()
        val guard = "if (command) {"
        val start = installed.indexOf(guard)
        assertTrue(
            start >= 0,
            "the listener no longer answers an edit with a keystroke, so Backspace, " +
                "Delete and Enter carry no modifier at all. It reads:\n$installed"
        )
        val end = installed.indexOf("return;", start)
        assertTrue(end > start, "the branch never returns, so it falls into the one below")
        return installed.substring(start, end + "return;".length)
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
    fun `a word committed in one event is left to the page, not spelled out as chords`() {
        // An Android IME commits a whole word in a single insertText when the
        // user taps a next-word prediction chip: one beforeinput, several
        // characters, no composition (a composition arrives as
        // insertCompositionText, which this branch already covers). The listener
        // cancelled the insertion and then dispatched one chord per character, so
        // the word never arrived and N unrelated commands ran: `hello` with Ctrl
        // latched fired Replace, Ctrl+E, Ctrl+L twice and Open File.
        //
        // NEGATIVE CONTROL: drop `|| e.data.length !== 1` from the guard and this
        // goes red.
        assertTrue(
            unhandledInputBranch().contains("e.data.length !== 1"),
            "a multi-character insertText is still cancelled and replaced by one chord " +
                "per character, so a predicted word types nothing and runs a command for " +
                "each of its letters. It reads: ${unhandledInputBranch()}"
        )
    }

    @Test
    fun `Enter is answered with a keystroke, so a latched Ctrl can ride on it`() {
        // Enter never reaches the page as a key press: a soft keyboard reports it
        // as an edit, insertLineBreak on the textarea edit path and
        // insertParagraph on the EditContext one, and no page of the key row
        // carries an Enter key for injectKey to send instead. Both inputTypes used
        // to fall into the branch above, which spends the latch and leaves the page
        // to insert a plain newline, so Ctrl+Enter could not be produced at all.
        val table = installedListener()
        for (inputType in listOf("insertParagraph", "insertLineBreak")) {
            assertTrue(
                table.contains("$inputType: ['Enter', 13]"),
                "$inputType is not answered with an Enter keystroke, so Ctrl+Enter is " +
                    "unreachable from this row on that edit path"
            )
        }
        for (pair in listOf("deleteContentBackward: ['Backspace', 8]", "deleteContentForward: ['Delete', 46]")) {
            assertTrue(
                table.contains(pair),
                "the delete keystrokes went with the rewrite: $pair is gone"
            )
        }
    }

    @Test
    fun `the keystroke sent for an edit carries the latch and cancels the edit`() {
        val branch = commandBranch()

        assertTrue(
            branch.contains("e.preventDefault();"),
            "the page performs the edit as well as receiving the chord, so Ctrl+Enter " +
                "inserts a newline and opens to the side. It reads: $branch"
        )
        assertTrue(
            branch.contains("ctrlKey: !!mod.ctrl") && branch.contains("altKey: !!mod.alt"),
            "the keystroke goes out without the modifier that was latched, which is the " +
                "whole reason this branch exists. It reads: $branch"
        )
        assertTrue(
            branch.contains("new KeyboardEvent('keydown', init)"),
            "nothing is dispatched, so the edit is cancelled and replaced by nothing. " +
                "It reads: $branch"
        )
        assertTrue(
            branch.contains("mod.ctrl = false;") &&
                branch.contains("mod.alt = false;") &&
                branch.contains("mod.shift = false;"),
            "the latch outlives the keystroke it was spent on, so the next character " +
                "typed is cancelled and sent as a chord too. It reads: $branch"
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

    /**
     * The listener the script attaches to an `EditContext`, from its registration
     * to the close of its body.
     *
     * Sliced at the event name because the body is one flag-clearing block among
     * several: the three `beforeinput` branches above clear the same flags, so a
     * whole-script search cannot tell a hook that spends the latch from one that
     * only registers.
     */
    private fun compositionHook(): String {
        val installed = installedListener()
        val guard = "addEventListener('compositionstart'"
        val start = installed.indexOf(guard)
        assertTrue(
            start >= 0,
            "nothing listens for a composition starting, so on the EditContext edit " +
                "path a composed character spends no latch: Chromium reports the " +
                "composition to the EditContext and fires no beforeinput at the " +
                "element, and the latch attaches to the space that commits the word. " +
                "It reads:\n$installed"
        )
        val end = installed.indexOf("});", start)
        assertTrue(end > start, "the composition hook's body is never closed")
        return installed.substring(start, end + "});".length)
    }

    /** The window `blur` hook, from its registration to the close of its body. */
    private fun frameBlurHook(): String {
        val installed = installedListener()
        val guard = "window.addEventListener('blur'"
        val start = installed.indexOf(guard)
        assertTrue(
            start >= 0,
            "nothing notices focus moving into a frame, so a Ctrl latched before " +
                "typing in a webview survives it and cancels the first character typed " +
                "back in the editor. It reads:\n$installed"
        )
        val end = installed.indexOf("});", start)
        assertTrue(end > start, "the blur hook's body is never closed")
        return installed.substring(start, end + "});".length)
    }

    @Test
    fun `a composition on the EditContext path spends the latch at its start`() {
        // The textarea path reports every composed keystroke as an
        // insertCompositionText beforeinput, which the unhandled-input branch
        // spends on the first one. With an EditContext attached, Chromium
        // dispatches compositionstart and textupdate to the EditContext object
        // and fires no beforeinput at the element, so the same keystrokes reached
        // no branch at all and the latch stood until the IME committed a space.
        //
        // NEGATIVE CONTROL: drop the compositionstart listener and the slice
        // assertion goes red; keep it and drop `mod.ctrl = false;` from its body
        // and the first assertion below goes red.
        val hook = compositionHook()
        assertTrue(
            hook.contains("mod.ctrl = false;") && hook.contains("mod.alt = false;"),
            "the composition hook registers but spends nothing, so the latch still " +
                "outlives the composed character. It reads: $hook"
        )
        assertTrue(
            hook.contains("mod.shift = false;"),
            "a Shift latched with Ctrl or Alt is the one that decides WHICH character " +
                "the next row key types, and this hook leaves it standing. It reads: $hook"
        )
        assertFalse(
            hook.contains("preventDefault") || hook.contains("dispatchEvent"),
            "the hook acts on the composition instead of only spending the latch: " +
                "there is no chord for a composed character and cancelling it types " +
                "nothing. It reads: $hook"
        )
    }

    @Test
    fun `the EditContext is reached through the focused element and hooked once`() {
        // `element.editContext` is the attribute the workbench sets on its edit
        // surface, and the only route to the object Chromium fires the events at.
        // The element focused when the script installs is hooked as well as any
        // focused later: the editor usually has focus before this runs, and a
        // hook that waited for the next focusin would miss the whole session.
        //
        // NEGATIVE CONTROL: hooking on `focusin` alone, without the call on
        // `document.activeElement`, turns the second assertion red.
        val installed = installedListener()
        assertTrue(
            installed.contains(".editContext"),
            "the script never reads the editContext attribute, so it has no object to " +
                "hear compositionstart from. It reads:\n$installed"
        )
        assertTrue(
            installed.contains("hookComposition(document.activeElement)"),
            "only elements focused after install are hooked, and the editor is " +
                "usually focused before it. It reads:\n$installed"
        )
        assertTrue(
            installed.contains("__vscodroid_hooked"),
            "every focusin adds another compositionstart listener to the same " +
                "EditContext. It reads:\n$installed"
        )
    }

    @Test
    fun `focus moving into a frame spends the latch, any other blur does not`() {
        // A beforeinput never leaves the document it fires in, and every frame
        // the workbench opens is served from the vscode-cdn.net origin, so what is
        // typed in one is out of reach either way. Chromium fires blur on the
        // window whose frame loses focus, with activeElement already the frame's
        // element; that is the signal, and the IFRAME check is what keeps a blur
        // for any other reason, the app losing the window or a popup taking it,
        // from spending a latch the user is still holding.
        //
        // NEGATIVE CONTROL: drop the `tagName !== 'IFRAME'` guard and the first
        // assertion goes red; keep it and drop `mod.ctrl = false;` and the second
        // goes red.
        val hook = frameBlurHook()
        assertTrue(
            hook.contains("tagName !== 'IFRAME'") && hook.contains("return;"),
            "the blur hook spends the latch on every blur, including the WebView " +
                "losing window focus to a dialog or the long-press popup. It reads: $hook"
        )
        assertTrue(
            hook.contains("mod.ctrl = false;") &&
                hook.contains("mod.alt = false;") &&
                hook.contains("mod.shift = false;"),
            "the blur hook registers but spends nothing. It reads: $hook"
        )
    }
}
