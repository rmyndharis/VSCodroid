package com.vscodroid.keyboard

import android.view.KeyEvent
import android.webkit.ValueCallback
import android.webkit.WebView
import com.vscodroid.util.Logger

/**
 * @param keyEventsFor how a character becomes key presses. Defaulted rather than
 * called directly so the routing can be exercised on the JVM: `KeyCharacterMap`
 * and `KeyEvent` are android.jar stubs that throw off a device.
 */
class KeyInjector(
    private val webView: WebView,
    private val keyEventsFor: (String) -> List<KeyEvent>? = ::virtualKeyboardEvents
) {
    private val tag = "KeyInjector"

    /**
     * Delivers one press of [key], by whichever of the two routes it needs.
     *
     * See [isTextEntry] for which is which and why there have to be two. In
     * short: a character is typed as a real key press, because a synthetic DOM
     * event performs no default action and inserts nothing; everything else,
     * and anything held with Ctrl, Alt or Meta, is announced as a DOM event,
     * because that is what the workbench resolves its bindings from.
     */
    fun injectKey(
        key: String,
        ctrlKey: Boolean = false,
        altKey: Boolean = false,
        shiftKey: Boolean = false,
        metaKey: Boolean = false
    ) {
        // A latched Shift changes WHICH character is typed, not whether it is
        // typed. The row offers no other route to `?`, `+`, `}` and the rest,
        // so resolving it here is what makes those reachable at all.
        val typed = if (shiftKey) KeyMapping.shiftedForm(key) ?: key else key
        if (isTextEntry(typed, ctrlKey, altKey, metaKey) && typeCharacter(typed)) return
        announceKeystroke(key, ctrlKey, altKey, shiftKey, metaKey)
    }

    /**
     * Types [key] through the WebView's own key handling. False when the layout
     * has no press for it, which leaves the caller to fall back.
     *
     * Read that fallback for what it is. The path it falls back to is the one
     * that inserts nothing, which is the whole defect this routing exists to
     * fix, so for such a character nothing is recovered: the press is preserved
     * as a keystroke the page can see and the text still does not arrive.
     * Swallowing the key would be worse, which is why it stays, but "fell back"
     * must not be read as "handled".
     *
     * The [Logger.w] below is the only signal that a key on the row cannot type
     * on the layout in force, and it is deliberately a warning rather than a
     * debug line: `Logger.d` is gated on a debuggable build, so on the builds
     * users run it would say nothing at all. On the US layout this is expected
     * to be no keys, and "expected to be none" on a layout nobody here chose is
     * exactly the claim worth instrumenting rather than assuming.
     */
    private fun typeCharacter(key: String): Boolean {
        val events = keyEventsFor(key)
        if (events.isNullOrEmpty()) {
            Logger.w(tag, "no press types '$key' on the virtual keyboard layout")
            return false
        }
        var handled = true
        for (event in events) handled = webView.dispatchKeyEvent(event) && handled
        if (!handled) {
            // The view refused the press: the renderer is being rebuilt after a
            // crash, or the WebView is detached mid folder switch. Reporting
            // success here would swallow the key entirely, so say so and let the
            // caller fall back to the path that at least reaches the page.
            Logger.w(tag, "the WebView refused the press for '$key'")
            return false
        }
        Logger.d(tag, "typed key=$key presses=${events.size}")
        return true
    }

    private fun announceKeystroke(
        key: String,
        ctrlKey: Boolean,
        altKey: Boolean,
        shiftKey: Boolean,
        metaKey: Boolean
    ) {
        val keyDef = KeyMapping.getKeyDefOrLetter(key)
        // Quoted by [KeyMapping.jsQuote], which is the table's own escaper and
        // the only one in this package that is correct.
        //
        // What was here escaped `'` and `"` and left `\` alone, which is exactly
        // the character the table holds as a key. For the `\` entry it rendered
        // `key: '\',` : the backslash escapes the closing quote, the string
        // runs on into the rest of the object, and the whole injected IIFE is a
        // SyntaxError. `evaluateJavascript` reports a parse failure to a null
        // callback, so the key did nothing at all and did it silently. It is
        // reachable: the `/` key offers `\` as its long-press alternate.
        //
        // jsQuote emits a double-quoted literal, so these are no longer wrapped
        // in quotes here; doing both would produce a quoted quote.
        val jsKey = KeyMapping.jsQuote(keyDef.key)
        val jsCode = KeyMapping.jsQuote(keyDef.code)
        // Force shiftKey=true for characters that require Shift on a physical keyboard
        val effectiveShift = shiftKey || keyDef.requiresShift

        val js = """
            (function() {
                var target = document.activeElement || document.body;
                var eventInit = {
                    key: ${jsKey},
                    code: ${jsCode},
                    keyCode: ${keyDef.keyCode},
                    which: ${keyDef.keyCode},
                    ctrlKey: ${ctrlKey},
                    altKey: ${altKey},
                    shiftKey: ${effectiveShift},
                    metaKey: ${metaKey},
                    bubbles: true,
                    cancelable: true,
                    composed: true
                };
                target.dispatchEvent(new KeyboardEvent('keydown', eventInit));
                target.dispatchEvent(new KeyboardEvent('keyup', eventInit));
                return target === document.body ? 'body' : (target.tagName || 'unknown');
            })();
        """.trimIndent()

        // The callback is attached only where something reads it. Passing one
        // makes the renderer serialize the script's return value back across
        // the process boundary, and the body below is `Logger.d`, which does
        // nothing on a build that is not debuggable. The trackpad is what makes
        // that matter: an arrow is not text entry, so every one comes through
        // here, and one MOVE delta in the fast gear pays out several arrows,
        // each of which was buying a round trip to discard the answer.
        //
        // The script itself is still one per arrow, and knowingly so. The
        // trackpad invokes its callback once per direction, so a MOVE that pays
        // out three builds and posts three of these. Collapsing them needs a
        // second entry point taking a list and an IIFE that loops, which is a
        // shape the text-entry routing above does not generalise to, and the
        // remaining cost is a one-way post with no reply to wait for. It is
        // worth doing when a fast flick is measured and this is what it costs,
        // not before.
        val report = if (Logger.debugEnabled) {
            ValueCallback<String> { target ->
                // What this can honestly report is where the event went, not
                // what the page did with it: a synthetic KeyboardEvent runs the
                // workbench's bindings and performs no default action, so "it
                // was delivered" and "something happened" are different claims
                // and only the first is observable from here. The line this
                // replaced said "Injected key={" for a press that inserted
                // nothing, on every tap, for as long as that key had been
                // broken.
                Logger.d(tag, "sent key=$key to $target ctrl=$ctrlKey alt=$altKey shift=$effectiveShift")
            }
        } else {
            null
        }
        webView.evaluateJavascript(js, report)
    }


    /**
     * Installs a JS `beforeinput` listener that intercepts soft keyboard text input
     * when ExtraKeyRow modifiers (Ctrl/Alt) are active. Instead of inserting text,
     * it dispatches modified KeyboardEvents so VS Code shortcuts work.
     *
     * The listener resolves each character through [KeyMapping]'s table, serialized in
     * here as a lookup object, so it answers from the same definitions [injectKey] uses
     * for the key row. Deriving the fields from the character instead only works for
     * letters and digits, where the character's own code point happens to equal the
     * keyCode; for punctuation the two differ and VS Code matches no binding.
     *
     * A Shift held on its own is not intercepted but is spent: the page keeps the
     * character it was about to insert, and the flag is cleared, so a latch cannot
     * carry over into a key the user taps minutes later. A Ctrl or Alt that meets
     * input this listener has no chord for, a paste or an IME composition, is spent
     * the same way and for the same reason.
     *
     * Backspace, Delete and Enter reach the page from here rather than from the key
     * row, because a soft keyboard reports all three as an edit and not as a key,
     * and no page of the row carries one. They are listed in `COMMANDS` below.
     *
     * This is live on both edit paths, not only the legacy one. The workbench uses
     * `NativeEditContext` wherever `globalThis.EditContext` exists, and an element
     * with an `EditContext` attached still receives every `beforeinput` except
     * `insertCompositionText`; only the `input` event is withheld. The workbench's
     * own `NativeEditContext` reads `beforeinput` for `insertParagraph` for exactly
     * that reason.
     *
     * Two inputs reach the page with no `beforeinput` at all, and each gets a hook
     * of its own below so the latch is still spent: a composition on the
     * EditContext path, which Chromium reports to the `EditContext` object and
     * never to the element, and typing inside a frame, which no event in this
     * document can see.
     *
     * Call once after the page finishes loading.
     */
    fun setupModifierInterceptor() {
        val keyLookup = KeyMapping.toJsLookup()
        val js = """
            (function() {
                if (window.__vscodroid_modifier_interceptor) return;
                window.__vscodroid_modifier_interceptor = true;
                window.__vscodroid = window.__vscodroid || {};
                window.__vscodroid.ctrl = false;
                window.__vscodroid.alt = false;
                window.__vscodroid.shift = false;

                var KEYS = $keyLookup;

                // The edits a soft keyboard reports instead of a key, and the
                // key each one stands for. Built once rather than per event.
                //
                // Enter is here because the editor never sees it as a key press
                // on either edit path: the textarea path reports it as
                // insertLineBreak and the EditContext path as insertParagraph,
                // which is the event the workbench's own NativeEditContext
                // listener reads to type the newline. Both used to fall into the
                // catch-all below, which spends the latch and leaves the page to
                // insert a plain newline, so Ctrl+Enter could not be produced
                // from this row by any route: no page carries an Enter key
                // either, and shiftedForm cannot manufacture one. It is a real
                // binding here, for open-to-the-side and for submitting in the
                // chat view of the extension that ships in the APK.
                var COMMANDS = {
                    deleteContentBackward: ['Backspace', 8],
                    deleteContentForward: ['Delete', 46],
                    insertParagraph: ['Enter', 13],
                    insertLineBreak: ['Enter', 13]
                };

                document.addEventListener('beforeinput', function(e) {
                    var mod = window.__vscodroid;
                    // Shift alone is not intercepted: the soft keyboard's own
                    // insertText is the only thing that types the character, and
                    // cancelling it in favour of a synthetic keydown leaves the
                    // tap producing nothing. Shift exists here for the row's own
                    // keys, which arrive by injectKey with the modifier set.
                    //
                    // It is still SPENT here, and that is not the same thing as
                    // acting on it: no preventDefault, so the page's own
                    // insertion is untouched, and the only change is that the
                    // latch does not outlive the character it was meant for.
                    // The only other things that clear it while the keyboard is
                    // up are the two hooks below, for a composition and for
                    // focus entering a frame, and a latch that survives no
                    // longer produces nothing: injectKey
                    // resolves it into a DIFFERENT character, so a Shift the
                    // user has forgotten turns a later tap on `/` into `?`,
                    // `;` into `:` and `[` into `{`. Ctrl and Alt are cleared
                    // on every branch below, on the same principle: the two
                    // this listener acts on spend them by acting, and the rest
                    // spend them by reaching the end of the character they were
                    // meant for.
                    if (!mod.ctrl && !mod.alt) {
                        mod.shift = false;
                        return;
                    }

                    var target = document.activeElement || document.body;
                    var init;

                    // The edits that stand for a key: a delete becomes
                    // Ctrl+Backspace and its neighbours, and Enter becomes the
                    // key press the editor otherwise never sees. See COMMANDS.
                    //
                    // hasOwnProperty rather than a bare lookup: e.inputType is
                    // an arbitrary string from the page, and a plain index would
                    // answer with an inherited member for one that happens to
                    // name it.
                    var command = COMMANDS.hasOwnProperty(e.inputType) ? COMMANDS[e.inputType] : null;
                    if (command) {
                        e.preventDefault();
                        e.stopImmediatePropagation();
                        init = {
                            key: command[0],
                            code: command[0],
                            keyCode: command[1],
                            which: command[1],
                            ctrlKey: !!mod.ctrl,
                            altKey: !!mod.alt,
                            shiftKey: !!mod.shift,
                            metaKey: false,
                            bubbles: true,
                            cancelable: true,
                            composed: true
                        };
                        target.dispatchEvent(new KeyboardEvent('keydown', init));
                        target.dispatchEvent(new KeyboardEvent('keyup', init));
                        mod.ctrl = false;
                        mod.alt = false;
                        mod.shift = false;
                        return;
                    }

                    // Everything else the page can insert: a paste, an IME
                    // composition update, an autocorrect replacement, a word
                    // delete. There is no chord to send for one, and cancelling
                    // it would leave the tap producing nothing, so it is left to
                    // the page exactly as a lone Shift is.
                    //
                    // A multi-character insertText belongs here for the same
                    // reason, and used to be the one case that did not get it. A
                    // next-word prediction chip commits a whole word in one
                    // event, and there is one chord per character and none for a
                    // word: the insertion was cancelled and a chord ran for
                    // every letter in its place, so a Ctrl held over `hello` fired
                    // Replace, Ctrl+E, Ctrl+L twice and Open File, and typed
                    // nothing.
                    //
                    // The latch is still spent. A Ctrl that survives here does
                    // not stay harmless: it attaches to whichever character is
                    // typed next, and that character is then CANCELLED in favour
                    // of a chord the user never asked for, so typing `a` after a
                    // paste selects the document instead of inserting a letter,
                    // and the keystroke after that replaces the selection.
                    if (e.inputType !== 'insertText' || !e.data || e.data.length !== 1) {
                        mod.ctrl = false;
                        mod.alt = false;
                        mod.shift = false;
                        return;
                    }

                    e.preventDefault();
                    e.stopImmediatePropagation();

                    var ch = e.data;
                    var def = KEYS[ch];
                    var code, keyCode, shiftKey = !!mod.shift;
                    if (def) {
                        code = def[0];
                        keyCode = def[1];
                        // The character carries Shift on a US layout, so the event
                        // has to as well or VS Code sees a different chord.
                        if (def[2]) shiftKey = true;
                    } else {
                        var upper = ch.toUpperCase();
                        code = /[a-zA-Z]/.test(ch) ? 'Key' + upper :
                               /[0-9]/.test(ch) ? 'Digit' + ch : '';
                        keyCode = upper.charCodeAt(0);
                    }

                    init = {
                        key: ch,
                        code: code,
                        keyCode: keyCode,
                        which: keyCode,
                        ctrlKey: !!mod.ctrl,
                        altKey: !!mod.alt,
                        shiftKey: shiftKey,
                        metaKey: false,
                        bubbles: true,
                        cancelable: true,
                        composed: true
                    };
                    target.dispatchEvent(new KeyboardEvent('keydown', init));
                    target.dispatchEvent(new KeyboardEvent('keyup', init));

                    mod.ctrl = false;
                    mod.alt = false;
                    mod.shift = false;
                }, true);

                // A composing IME on the EditContext edit path. Chromium reports
                // a composition to the EditContext object alone: compositionstart,
                // then a textupdate per keystroke, and the element receives no
                // beforeinput for any of it, where the textarea path reports the
                // same keystrokes as insertCompositionText, which the branch
                // above spends. A latch that survives the first composed
                // character attaches to the first one the IME commits outright,
                // which is the space that ends the word: the space was cancelled
                // and Ctrl+Space opened suggestions in its place.
                //
                // compositionstart rather than compositionend, because it is the
                // earliest signal and the one the textarea path already spends
                // at: the character is the page's from its first update, and
                // however the IME then closes the composition, by committing the
                // word or the space after it, the latch is already gone. A
                // latch held to compositionend would show Ctrl over characters
                // the page had already inserted. The EditContext is read off the
                // focused element's editContext attribute, which is where the
                // workbench attaches it, and hooked once per object. The element
                // focused when this installs is hooked as well: the editor is
                // usually focused before this runs and would otherwise wait for
                // the next focus change.
                function hookComposition(el) {
                    var ec = el && el.editContext;
                    if (!ec || ec.__vscodroid_hooked) return;
                    ec.__vscodroid_hooked = true;
                    ec.addEventListener('compositionstart', function() {
                        var mod = window.__vscodroid;
                        mod.ctrl = false;
                        mod.alt = false;
                        mod.shift = false;
                    });
                }
                document.addEventListener('focusin', function(e) {
                    hookComposition(e.target);
                }, true);
                hookComposition(document.activeElement);

                // Focus moving into a frame. An event never leaves the document
                // it fires in, so what is typed in a frame is out of the
                // listener's reach whatever its origin, and every frame the
                // workbench opens, extension webviews, the Simple Browser and
                // notebook output among them, is served from the vscode-cdn.net
                // origin and cannot be entered from here either. Chromium fires
                // blur on the window whose frame loses focus, with activeElement
                // already answering with the frame's element, and that is the
                // one signal this document gets. No chord can follow the focus,
                // so the latch is spent: standing, it attached to the first
                // character typed back in the editor. A blur for any other
                // reason, the app losing the window or a popup taking it, leaves
                // activeElement where it was and passes through.
                window.addEventListener('blur', function() {
                    var active = document.activeElement;
                    if (!active || active.tagName !== 'IFRAME') return;
                    var mod = window.__vscodroid;
                    mod.ctrl = false;
                    mod.alt = false;
                    mod.shift = false;
                });
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
        Logger.d(tag, "Modifier interceptor installed")
    }

    /**
     * Updates the JS-side modifier flags. Called by ExtraKeyRow when Ctrl/Alt/Shift toggles.
     */
    fun setModifierState(ctrl: Boolean, alt: Boolean, shift: Boolean) {
        webView.evaluateJavascript(
            "window.__vscodroid&&(window.__vscodroid.ctrl=$ctrl,window.__vscodroid.alt=$alt,window.__vscodroid.shift=$shift);",
            null
        )
    }

    /**
     * Queries the JS-side modifier flags and calls back with the current state.
     * Used by ExtraKeyRow to detect when the JS interceptor consumed a modifier
     * (e.g., user typed on soft keyboard after toggling Ctrl).
     */
    fun queryModifierState(callback: (ctrl: Boolean, alt: Boolean, shift: Boolean) -> Unit) {
        webView.evaluateJavascript(
            "(function(){var m=window.__vscodroid||{};return JSON.stringify({c:!!m.ctrl,a:!!m.alt,s:!!m.shift})})()"
        ) { result ->
            try {
                // Result is like '{"c":true,"a":false,"s":false}' (quoted string)
                val cleaned = result.trim('"').replace("\\", "")
                val ctrl = cleaned.contains("\"c\":true")
                val alt = cleaned.contains("\"a\":true")
                val shift = cleaned.contains("\"s\":true")
                callback(ctrl, alt, shift)
            } catch (_: Exception) {
                callback(false, false, false)
            }
        }
    }
}
