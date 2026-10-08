package com.vscodroid.keyboard

import android.view.KeyEvent
import android.webkit.ValueCallback
import android.webkit.WebView
import com.vscodroid.util.Logger

/**
 * @param keyEventsFor how a character becomes key presses. Defaulted rather than
 * called directly so the routing can be exercised on the JVM: `KeyCharacterMap`
 * and `KeyEvent` are android.jar stubs that throw off a device.
 * @param navigationEventsFor how a [NAVIGATION_KEYS] entry becomes its press,
 * defaulted for the same reason.
 */
class KeyInjector(
    private val webView: WebView,
    private val keyEventsFor: (String) -> List<KeyEvent>? = ::virtualKeyboardEvents,
    private val navigationEventsFor: (keyCode: Int, scanCode: Int, metaState: Int) -> List<KeyEvent> =
        ::navigationKeyEvents,
) {
    private val tag = "KeyInjector"

    /**
     * Delivers one press of [key], by whichever of the three routes it needs.
     *
     * A character is typed as a real key press, because a synthetic DOM event
     * performs no default action and inserts nothing; [isTextEntry] says which
     * presses those are. A key in [NAVIGATION_KEYS] is pressed for real too,
     * with any latched modifier as its meta state. Everything else, and any
     * character held with Ctrl, Alt or Meta, is announced as a DOM event,
     * because that is what the workbench resolves its bindings from;
     * [NAVIGATION_KEYS] says why the trackpad's Up and Down, Tab and Escape
     * stay there. Either way the modifiers a press carried come up after it,
     * on the same route ([modifierReleases] says why).
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
        NAVIGATION_KEYS[key]?.let { (keyCode, scanCode) ->
            val metaState = navigationMetaState(ctrlKey, altKey, shiftKey, metaKey)
            if (press(key, navigationEventsFor(keyCode, scanCode, metaState))) return
        }
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
        return press(key, events)
    }

    /**
     * Dispatches [events] at the WebView, false when the view refused one.
     *
     * True means the WebView took the events, not that the page acted on them:
     * while the soft keyboard is connected, the WebView queues a key event
     * behind the keyboard's own input and answers true whatever happens next.
     */
    private fun press(key: String, events: List<KeyEvent>): Boolean {
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
        Logger.d(tag, "pressed key=$key presses=${events.size}")
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
                $RELEASE_MODIFIERS_JS
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
                releaseModifiers(target, eventInit);
                return target === document.body ? 'body' : (target.tagName || 'unknown');
            })();
        """.trimIndent()

        // The callback is attached only where something reads it. Passing one
        // makes the renderer serialize the script's return value back across
        // the process boundary, and the body below is `Logger.d`, which does
        // nothing on a build that is not debuggable. The trackpad is what makes
        // that matter: its Up and Down are announced, so every one comes through
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
     * it dispatches modified KeyboardEvents so VS Code shortcuts work, then a keyup
     * for each modifier the chord carried ([modifierReleases] says why).
     *
     * It also stops, at the window, every keyup of a modifier whose key this page
     * did not see go down, paired by `code` so that each of two Shifts held at
     * once counts. That is what the row's releases look like, from a chord here
     * or from a real press. A keyboard's release follows its own keydown and goes
     * on, in an extension webview too: the webview's frame passes a modifier's
     * keydown and keyup to the workbench, which dispatches them again on this
     * window. A key that went down in another window, or in a frame that passes
     * nothing on, such as the page Simple Browser nests in its webview, has its
     * release stopped, and a key released there after going down here lets one
     * later release from the row through. The workbench reads which modifiers
     * are held from capture listeners on the window, so those still hear it. A
     * quick pick opened with quick navigate, as Ctrl+Tab's is, does not: it
     * accepts on a modifier's keyup in its own container. Opened in the Command
     * Palette it keeps the palette's input box, and with it the focus, the
     * keyboard and the key row, so a Ctrl+Tab from the row is typed inside that
     * container and its release would open the highlighted editor at once
     * instead of leaving the list for the next Ctrl+Tab to move down. Opened
     * from a file it focuses its list instead, and the keyboard goes down and
     * takes the row with it: Chromium hides the keyboard whenever focus leaves
     * an editable element.
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
     * input this listener has no chord for, a paste or a composition update that
     * adds more or other than one character, is spent the same way and for the
     * same reason.
     *
     * Backspace, Delete and Enter reach the page from here rather than from the key
     * row, because a soft keyboard reports all three as an edit and not as a key,
     * and no page of the row carries one. They are listed in `COMMANDS` below.
     *
     * A keyboard that composes, as Gboard 12.4 composes every word, reports each
     * letter in a text box as `insertCompositionText`, which cannot be cancelled:
     * the letter is the box's before anything here can refuse it. So when such an
     * edit adds one character with a key to the composition, in a focused `input`
     * or `textarea`, the terminal's included, the box's own listeners are kept
     * from hearing it, and in the next task the composition is ended by taking
     * the focus away and giving it back, and the box gets back the text and
     * selection it had before the character. A task later the character comes
     * back to this listener as the `insertText` a keyboard that commits would
     * have sent, which makes the chord. The blur is Blink's own end of a
     * composition, with the `compositionend` the box's listeners expect: the
     * terminal sends what it composed on that event, and a chord the terminal
     * hands to the workbench never reaches its composition handling, so without
     * the blur the word typed before such a chord would stay unsent. It sends the
     * word in a zero timeout, reading it back from its textarea, which empties
     * itself on blur (read from the shipped xterm). So the text goes back after
     * the blur, and only if nothing changed it in between, and the chord waits
     * for that read, so the word reaches the shell first. Quick Open, which the
     * terminal hands on, focuses its own box before the chord's keydown is over,
     * so a chord sent in the blur's task emptied the textarea before the read;
     * that is read from the shipped workbench, and the wait was not measured.
     * Measured on an API 33 emulator with WebView 153 and Gboard 12.4, before
     * the wait: in the Search view's box Ctrl, held past the long-press delay or
     * tapped, then `p` opened Quick Open and left the box empty, and over an
     * underlined `fo` left `fo`; Ctrl then `a` over `fo` selected it, and the
     * next letter replaced it; in the terminal Ctrl then `c` gave `^C`, over an
     * underlined `ab` gave `ab^C`, and stopped a running `cat`. Before, the
     * letter joined the word and the latch was spent. What the blur costs: the
     * quick input forgets which element to give focus back to when it closes,
     * and gives it to the active editor instead, and a terminal program that
     * asked to hear focus changes hears focus go and come back. The editor's own
     * textarea host, which reads compositions itself, is left as it was.
     *
     * This is live on both edit paths, not only the legacy one, but not for
     * everything on the EditContext path. The workbench uses `NativeEditContext`
     * wherever `globalThis.EditContext` exists, and an element with an
     * `EditContext` attached still receives the `beforeinput` of an edit a key
     * press makes, such as Enter's `insertParagraph`, which the workbench's own
     * `NativeEditContext` reads to type the newline. Text a keyboard composes or
     * commits does not reach the element as a `beforeinput`: Chromium reports it
     * to the `EditContext` object, as `textupdate` beside the composition and
     * format events, and the element gets only the keydown and keyup of key code
     * 229. So on that path a letter the soft keyboard types is never made a chord
     * here.
     *
     * Three inputs reach the page with no `beforeinput` at all. Two get a hook of
     * their own below so the latch is still spent: a composition on the
     * EditContext path, and typing inside a frame, which no event in this
     * document can see. The third, a letter committed outright on the EditContext
     * path, as Gboard 18 commits one, is not covered: it is typed and the latch
     * stays on for the next key. Measured on an API 36 emulator with WebView 153
     * and Gboard 18.4.1: Ctrl latched, then `p` gave a keydown 229, a
     * `textupdate` and a keyup, and no `beforeinput`, and `p` was typed with Ctrl
     * still lit. A fourth is the soft keyboard's Enter when a keybinding accepts
     * it. `MainActivity.injectComposingEnter` spends a lone Shift on the Enter
     * whose empty `code` it fills, without applying it, as here; an Enter that
     * arrives with a `code` is not covered.
     *
     * The same script guards Left and Right, the only arrows pressed for real,
     * at the edge of a text box. A real arrow turns WebView spatial navigation
     * on until the next touch on the page, and the trackpad is not on the page,
     * so it stays on while the user types. Under it, an arrow that leaves a
     * collapsed caret where it was, at the start or the end of the text, moves
     * focus to the nearest control instead. A control that takes no text drops
     * the soft keyboard and this row with it, and the Explorer's rename box
     * commits the half-typed name when it loses focus. Cancelling such a press
     * ends it where a desktop would.
     *
     * Spatial navigation ignores an arrow held with Ctrl, Shift or Meta, and a
     * selection that collapses has moved, so those are left alone. A number or
     * email box takes text but has no selection API, so its caret cannot be
     * read and every unmodified Left and Right there is cancelled, as the
     * announced arrow they replaced did nothing there either; Home, End and a
     * tap still move it. Alt is not ignored. Before Chromium 149, Blink on
     * Android has no command for Alt+Left or Alt+Right, so the press reaches
     * spatial navigation wherever the caret is, in a text box and on the
     * editor's EditContext host alike, and below 149, read from the user agent,
     * it is cancelled on both. From 149 they move to the start and end of the
     * line, which Blink counts as handled even where the caret already is, so
     * they are left to it. Home, End, PageUp and PageDown are not arrows, and
     * spatial navigation never moves focus for them.
     *
     * The press is decided on the box itself, after the box's own listeners,
     * and a key one of them already cancelled is left alone. A box whose own
     * handler or container stops the key's propagation, as the Problems,
     * Output, Debug Console and Comments filters and the chat model picker's
     * filter do, hides it from a listener on the window, and its default action
     * and spatial navigation run all the same. The listener that decides is
     * added to the box for that one key and removed by a timer once the key is
     * over, so a box that stops the key before it, as the terminal's textarea
     * does with every arrow, keeps none. Cancelling stops no binding: the
     * workbench's keybinding service listens on the window, after the box, and
     * does not read `defaultPrevented`. It covers this document only, so a text
     * box inside an extension webview, a frame of another origin, is not
     * guarded. It applies to a hardware keyboard's Left and Right too. The
     * edges are read in logical order, which is the screen's order for
     * left-to-right text.
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

                $RELEASE_MODIFIERS_JS

                // A modifier's keyup with no keydown of it is the row releasing
                // a latch, from releaseModifiers or, for a real press, from
                // Chromium at whatever has focus. It ends at this window's
                // capture listeners, where the workbench reads what is held, so
                // a quick pick below cannot accept on it. See the KDoc. A
                // keyboard presses a modifier before releasing it, so its own
                // release goes on. Paired by code, the physical key: paired by
                // key, a keyboard holding both Shifts would have its second
                // release stopped.
                var MODIFIERS = { Alt: 1, Control: 1, Shift: 1, Meta: 1 };
                var pressed = {};
                window.addEventListener('keydown', function(e) {
                    if (MODIFIERS.hasOwnProperty(e.key)) pressed[e.code] = true;
                }, true);
                window.addEventListener('keyup', function(e) {
                    if (!MODIFIERS.hasOwnProperty(e.key)) return;
                    if (pressed[e.code]) delete pressed[e.code];
                    else e.stopPropagation();
                }, true);

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

                // What a keyboard is composing in a text box, and what it was
                // before its latest update. Blink fires compositionupdate with
                // the whole composition just before the beforeinput that puts
                // it in. A composition on the EditContext path fires at the
                // EditContext, not here.
                var composition = { text: '', previous: '' };
                function compositionOver() {
                    composition.text = '';
                    composition.previous = '';
                }
                document.addEventListener('compositionstart', compositionOver, true);
                document.addEventListener('compositionend', compositionOver, true);
                document.addEventListener('compositionupdate', function(e) {
                    composition.previous = composition.text;
                    composition.text = e.data || '';
                }, true);

                // A character a composing keyboard adds in a text box, as Gboard
                // 12.4 adds every letter, made the chord it would have been from
                // a keyboard that commits. See the KDoc. True when this takes the
                // event, or leaves it to the page because a chord is pending.
                var chordPending = false;
                function chordComposed(e) {
                    if (chordPending) return true;
                    var box = e.target;
                    var data = e.data || '';
                    var previous = composition.previous;
                    var ch = data.charAt(previous.length);
                    if (box !== document.activeElement ||
                        !(box.tagName === 'TEXTAREA' || (box.tagName === 'INPUT' && box.selectionStart !== null)) ||
                        box.classList.contains('inputarea') ||
                        data.length !== previous.length + 1 || data.slice(0, previous.length) !== previous ||
                        !(KEYS[ch] || /[a-zA-Z0-9]/.test(ch))) return false;
                    e.stopImmediatePropagation();
                    chordPending = true;
                    var before = { value: box.value, start: box.selectionStart, end: box.selectionEnd,
                        direction: box.selectionDirection };
                    var after = null;
                    // The input event the letter's insertion fires, kept from
                    // the box as the beforeinput was.
                    function hide(ev) {
                        if (ev.target !== box) return;
                        document.removeEventListener('input', hide, true);
                        after = box.value;
                        ev.stopImmediatePropagation();
                    }
                    document.addEventListener('input', hide, true);
                    // A later task, once the insertion is over: a chord sent now
                    // would move focus before the letter is put in, and the
                    // letter would land in whatever took it.
                    setTimeout(function() {
                        document.removeEventListener('input', hide, true);
                        var untouched = after !== null && box.value === after;
                        if (document.activeElement === box) {
                            box.blur();
                            box.focus();
                        }
                        if (untouched) {
                            box.value = before.value;
                            box.setSelectionRange(before.start, before.end, before.direction);
                        }
                        // And one task more, after the terminal's own zero
                        // timeout, which the blur's compositionend queued and
                        // which sends the word from the text just put back. A
                        // chord that takes the focus, as Quick Open does at
                        // once, would empty the textarea before that read. The
                        // chord is pending until it is sent.
                        setTimeout(function() {
                            chordPending = false;
                            box.dispatchEvent(new InputEvent('beforeinput', {
                                inputType: 'insertText', data: ch, bubbles: true, cancelable: true, composed: true
                            }));
                        }, 0);
                    }, 0);
                    return true;
                }

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
                    // focus entering a frame, and the soft keyboard Enter script
                    // in MainActivity, on the code-less Enter it fills, which a
                    // keybinding may accept with no beforeinput to follow. A
                    // latch that survives no longer produces nothing: injectKey
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
                        releaseModifiers(target, init);
                        mod.ctrl = false;
                        mod.alt = false;
                        mod.shift = false;
                        return;
                    }

                    // A character a composing keyboard adds in a text box. Its
                    // chord comes from chordComposed, in a later task.
                    if (e.inputType === 'insertCompositionText' && chordComposed(e)) return;

                    // Everything else the page can insert: a paste, any other
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
                        // A capital is a shifted key, and the table above says so
                        // for punctuation and cannot for letters: it carries no
                        // letters at all, so `def` is null here and shiftKey kept
                        // whatever the ROW's Shift said, which for a capital typed
                        // on the soft keyboard is false.
                        //
                        // What that costs is the chord. Measured on an API 37
                        // emulator against the shipped build: latch Ctrl on the
                        // row, type a capital P, and the page receives
                        // {key:"P", code:"KeyP", ctrl:true, shift:false} and opens
                        // Quick Open, "Search files by name". The same event with
                        // shift true opens the Command Palette, "Type the name of
                        // a command to run". So Ctrl+Shift+anything was
                        // unreachable from this row by the one route a phone
                        // offers for it, and it failed by doing something else
                        // plausible rather than nothing.
                        //
                        // [A-Z] and not `ch !== ch.toLowerCase()`: a capital
                        // outside ASCII gets no `code` two lines up, so there is
                        // no chord for a modifier to belong to, and claiming
                        // Shift for it would describe a US layout that has no such
                        // key.
                        if (/[A-Z]/.test(ch)) shiftKey = true;
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
                    releaseModifiers(target, init);

                    mod.ctrl = false;
                    mod.alt = false;
                    mod.shift = false;
                }, true);

                // A composing IME on the EditContext edit path. Chromium reports
                // a composition to the EditContext object alone: compositionstart,
                // then a textupdate per keystroke, and the element receives no
                // beforeinput for any of it, where the editor's textarea path
                // reports the same keystrokes as insertCompositionText, which the
                // branch above spends there. A latch that survives the first
                // composed character attaches to the first one the IME commits
                // outright, which is the space that ends the word: the space was
                // cancelled and Ctrl+Space opened suggestions in its place.
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

                // A Left or Right at the edge of a text box ends there instead
                // of moving focus. See the KDoc for why the edge is dangerous.
                var EDGE = { ArrowLeft: -1, ArrowRight: 1 };
                // Alt+Left and Alt+Right have no command on Android before
                // Chromium 149. A user agent with no version counts as older:
                // a lost line move costs less than lost focus.
                var version = /Chrome\/(\d+)/.exec(navigator.userAgent);
                var altUnhandled = !version || +version[1] < 149;
                // Whether the press goes on to spatial navigation from `t`. A
                // number or email box takes text but reports no caret, so any
                // press there may be the one at its edge.
                function leaves(e, t) {
                    var at = t.selectionStart;
                    var box = t.tagName === 'TEXTAREA' || (t.tagName === 'INPUT' && at !== null);
                    var blind = t.tagName === 'INPUT' && (t.type === 'number' || t.type === 'email');
                    if (e.altKey) return altUnhandled && (box || blind || !!t.editContext);
                    return blind || (box && at === t.selectionEnd && at === (EDGE[e.key] < 0 ? 0 : t.value.length));
                }
                // Decided on the innermost target, by a listener added there
                // while the key is on its way down. It runs after the target's
                // own listeners and before the key bubbles to any container,
                // so neither can hide the key with stopPropagation in the
                // bubble phase.
                // Only for its own event, and removed by a timer once that
                // event is over, whether or not it ran. A key stopped before
                // the listener's turn never runs it, so removing it from
                // inside, as `once` did, left it there: the terminal's textarea
                // stops every arrow in its own capture listener, and each
                // trackpad step in a terminal left one more behind. The timer
                // runs in a later task, after the whole dispatch. A microtask
                // would not: the browser runs microtasks between the listeners
                // of a key it dispatches itself, so one queued here would
                // remove the listener before the key reached the box.
                window.addEventListener('keydown', function(e) {
                    if (!EDGE.hasOwnProperty(e.key) || e.ctrlKey || e.shiftKey || e.metaKey) return;
                    var t = e.composedPath()[0];
                    if (!t) return;
                    function decide(ev) {
                        if (ev === e && !e.defaultPrevented && leaves(e, t)) e.preventDefault();
                    }
                    t.addEventListener('keydown', decide);
                    setTimeout(function() { t.removeEventListener('keydown', decide); }, 0);
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

/**
 * `releaseModifiers(target, init)`, defined in each script that sends a chord
 * and called right after the chord's own keyup: a keyup at `target` for each
 * modifier `init` holds, as a hardware keyboard sends one when the key comes
 * up. Alt, Ctrl, Shift and Meta, in that order and each with the flags still
 * held, which is what [modifierReleases] gives a real press; it says why a
 * chord needs them.
 *
 * At the chord's own target, not at whatever has focus by then, so that the
 * window hears the release exactly when it heard the chord's keyup. A chord
 * that takes its target out of the document goes unheard from there on, and a
 * release heard after an Alt chord whose keyup was not reads as an Alt pressed
 * and released alone, which focuses the menu bar.
 *
 * The target alone does not keep the release out of a quick pick: a Ctrl+Tab
 * typed in the Command Palette has its target inside the picker it opens. The
 * modifier interceptor, which every workbench page gets, stops a release at
 * the window ([setupModifierInterceptor] says why).
 *
 * Called from the chord's script, so it reaches the page after the chord, and
 * defined in each of the two rather than shared on `window`: an announced
 * chord works without the modifier interceptor, and so should its release.
 *
 * Indented to the sixteen columns both scripts interpolate it at. Lines at
 * column 0 would leave `trimIndent()` no margin to take off either script, and
 * both would go out as indented as this source.
 */
private val RELEASE_MODIFIERS_JS = """
    function releaseModifiers(target, init) {
        var held = { altKey: init.altKey, ctrlKey: init.ctrlKey, shiftKey: init.shiftKey, metaKey: init.metaKey };
        [['altKey', 'Alt', 'AltLeft', 18], ['ctrlKey', 'Control', 'ControlLeft', 17],
            ['shiftKey', 'Shift', 'ShiftLeft', 16], ['metaKey', 'Meta', 'MetaLeft', 91]].forEach(function(m) {
            if (!held[m[0]]) return;
            held[m[0]] = false;
            target.dispatchEvent(new KeyboardEvent('keyup', { key: m[1], code: m[2], keyCode: m[3], which: m[3],
                location: 1, altKey: held.altKey, ctrlKey: held.ctrlKey, shiftKey: held.shiftKey,
                metaKey: held.metaKey, bubbles: true, cancelable: true, composed: true }));
        });
    }
""".replaceIndent(" ".repeat(16)).trimStart()
