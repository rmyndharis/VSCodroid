package com.vscodroid.keyboard

import android.webkit.WebView
import com.vscodroid.util.Logger

class KeyInjector(private val webView: WebView) {
    private val tag = "KeyInjector"

    fun injectKey(
        key: String,
        ctrlKey: Boolean = false,
        altKey: Boolean = false,
        shiftKey: Boolean = false,
        metaKey: Boolean = false
    ) {
        val keyDef = KeyMapping.getKeyDefOrLetter(key)
        val jsKey = keyDef.key.replace("'", "\\'").replace("\"", "\\\"")
        val jsCode = keyDef.code.replace("'", "\\'")
        // Force shiftKey=true for characters that require Shift on a physical keyboard
        val effectiveShift = shiftKey || keyDef.requiresShift

        val js = """
            (function() {
                var target = document.activeElement || document.body;
                var eventInit = {
                    key: '${jsKey}',
                    code: '${jsCode}',
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
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
        Logger.d(tag, "Injected key=$key ctrl=$ctrlKey alt=$altKey shift=$shiftKey")
    }

    fun injectText(text: String) {
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        val js = """
            (function() {
                var target = document.activeElement || document.body;
                var event = new InputEvent('beforeinput', {
                    data: '${escaped}',
                    inputType: 'insertText',
                    bubbles: true,
                    cancelable: true,
                    composed: true
                });
                target.dispatchEvent(event);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Installs a JS `beforeinput` listener that intercepts soft keyboard text input
     * when ExtraKeyRow modifiers (Ctrl/Alt) are active. Instead of inserting text,
     * it dispatches modified KeyboardEvents so VS Code shortcuts work.
     *
     * Call once after the page finishes loading.
     */
    fun setupModifierInterceptor() {
        val js = """
            (function() {
                if (window.__vscodroid_modifier_interceptor) return;
                window.__vscodroid_modifier_interceptor = true;
                window.__vscodroid = window.__vscodroid || {};
                window.__vscodroid.ctrl = false;
                window.__vscodroid.alt = false;
                window.__vscodroid.shift = false;

                document.addEventListener('beforeinput', function(e) {
                    var mod = window.__vscodroid;
                    if (!mod.ctrl && !mod.alt && !mod.shift) return;

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
                        var upper = ch.toUpperCase();
                        var code = /[a-zA-Z]/.test(ch) ? 'Key' + upper :
                                   /[0-9]/.test(ch) ? 'Digit' + ch : '';
                        var keyCode = upper.charCodeAt(0);

                        init = {
                            key: ch,
                            code: code,
                            keyCode: keyCode,
                            which: keyCode,
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
