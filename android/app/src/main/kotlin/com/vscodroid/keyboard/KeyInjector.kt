package com.vscodroid.keyboard

import android.view.KeyEvent
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

        webView.evaluateJavascript(js) { target ->
            // What this can honestly report is where the event went, not what
            // the page did with it: a synthetic KeyboardEvent runs the
            // workbench's bindings and performs no default action, so "it was
            // delivered" and "something happened" are different claims and only
            // the first is observable from here. The line this replaced said
            // "Injected key={" for a press that inserted nothing, on every tap,
            // for as long as that key had been broken.
            Logger.d(tag, "sent key=$key to $target ctrl=$ctrlKey alt=$altKey shift=$effectiveShift")
        }
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

                document.addEventListener('beforeinput', function(e) {
                    var mod = window.__vscodroid;
                    // Shift alone is not intercepted: the soft keyboard's own
                    // insertText is the only thing that types the character, and
                    // cancelling it in favour of a synthetic keydown leaves the
                    // tap producing nothing. Shift exists here for the row's own
                    // keys, which arrive by injectKey with the modifier set.
                    if (!mod.ctrl && !mod.alt) return;

                    var target = document.activeElement || document.body;
                    var init;

                    // Handle delete operations (Ctrl+Backspace = delete word, etc.)
                    if (e.inputType === 'deleteContentBackward' || e.inputType === 'deleteContentForward') {
                        e.preventDefault();
                        e.stopImmediatePropagation();
                        var isForward = e.inputType === 'deleteContentForward';
                        init = {
                            key: isForward ? 'Delete' : 'Backspace',
                            code: isForward ? 'Delete' : 'Backspace',
                            keyCode: isForward ? 46 : 8,
                            which: isForward ? 46 : 8,
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

                    if (e.inputType !== 'insertText' || !e.data) return;

                    e.preventDefault();
                    e.stopImmediatePropagation();

                    var chars = e.data;
                    for (var i = 0; i < chars.length; i++) {
                        var ch = chars[i];
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
                    }

                    mod.ctrl = false;
                    mod.alt = false;
                    mod.shift = false;
                }, true);
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
