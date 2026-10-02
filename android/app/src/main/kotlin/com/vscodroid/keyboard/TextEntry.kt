package com.vscodroid.keyboard

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Whether [key] is a character to be typed rather than a keystroke to be
 * announced. They are two different operations and only one of them can be done
 * from JavaScript.
 *
 * A `KeyboardEvent` built in the page is untrusted, so the browser runs the
 * listeners and performs no default action. The workbench's key bindings fire,
 * which is why Tab indents and Ctrl+P opens Quick Open, and no text is
 * inserted, which is why `{` produced nothing at all. Text has to enter through
 * the browser's own text input path, and the only way into that path from
 * Kotlin is a real Android [KeyEvent] delivered to the WebView.
 *
 * That distinction, rather than the edit widget, is what makes this survive
 * both DOM shapes. Old WebViews put a hidden `textarea.inputarea` behind the
 * editor; WebView 121 and later use `EditContext` over `div.native-edit-context`
 * and have no textarea at all. A key press is above both.
 *
 * [KeyMapping]'s own names separate the two cases: a key that types is one
 * character (`{`, `;`, `"`), a key that commands is spelled out (`Tab`,
 * `Escape`, `F7`, `PageDown`). Ctrl, Alt or Meta makes any key a chord, and a
 * chord is a command however it is spelled. Shift does not: the layout below
 * presses Shift itself for the characters that need it.
 *
 * A spelled-out key listed in [NAVIGATION_KEYS] is the third case: it moves the
 * caret, and it is pressed rather than announced, modifiers and all.
 *
 * ASCII only, because [virtualKeyboardEvents] resolves through a US layout and
 * anything outside it has no key to press there.
 */
internal fun isTextEntry(key: String, ctrlKey: Boolean, altKey: Boolean, metaKey: Boolean): Boolean {
    if (ctrlKey || altKey || metaKey) return false
    if (key.length != 1) return false
    return key[0].code in 0x20..0x7E
}

/**
 * The presses that type [text] on the virtual keyboard layout, or null when the
 * layout cannot produce one of its characters.
 *
 * Shift is not applied by hand. `getEvents` returns whatever the layout needs,
 * which for `{` is Shift down, `[` down, `[` up, Shift up, with the meta state
 * already set on each event. A table written here would be a second opinion
 * about a layout that is available to ask.
 *
 * The events come back stamped at time zero and are re-stamped: a press that
 * claims to have happened at boot is one whose repeat and long-press timing
 * cannot be read downstream. The device id stays [KeyCharacterMap.VIRTUAL_KEYBOARD]
 * because that is the map whose characters these key codes were resolved from,
 * and it is the map `KeyEvent.getUnicodeChar` will consult again on the way in.
 */
internal fun virtualKeyboardEvents(text: String): List<KeyEvent>? {
    val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        .getEvents(text.toCharArray()) ?: return null
    val now = SystemClock.uptimeMillis()
    return events.map { event ->
        KeyEvent(
            now, now, event.action, event.keyCode, event.repeatCount, event.metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, event.scanCode, event.flags,
            InputDevice.SOURCE_KEYBOARD,
        )
    }
}

/**
 * The keys pressed as real key events, by name: the Android key code and the
 * evdev scan code a hardware keyboard sends for each.
 *
 * An announced key runs the page's listeners and performs no default action.
 * The editor and the terminal do not notice, because their own handlers do the
 * moving. A text box has no such handler, so the caret stays put in the
 * Explorer's rename box, the Command Palette and the find widget, and an
 * announced key never reaches a frame at all. A real press is what a hardware
 * keyboard sends, and it works in all of them.
 *
 * The scan code is what Chromium derives `KeyboardEvent.code` from; without
 * one every press arrives with a `code` of "", which a listener reading `code`
 * rather than `key` takes for no key at all. `MOVE_HOME` and `MOVE_END`, never
 * `KEYCODE_HOME`, which is the system Home button.
 *
 * The trackpad's Up and Down, Tab, Escape and the function keys stay
 * announced. Up and Down are the quick pick's list keys, in the Command Palette
 * and Go to File, and a real key pressed while the soft keyboard is composing a
 * word carries `isComposing`, for which the workbench dispatches no keybinding
 * at all; the announced event carries none and moves the highlight. The quick
 * pick also binds PageUp, PageDown, Ctrl+Home, Ctrl+End and Right, which are
 * pressed for real all the same, because their default is what a text box and
 * an extension panel need; in the quick pick, where a binding is all they have,
 * the page clears `isComposing` on them (`MainActivity.injectComposingEnter`).
 * Keeping Up and Down announced also means no real vertical arrow is ever sent:
 * Blink's editing on Android has no command for Alt+Up or Alt+Down, so a real
 * one would go straight to spatial navigation, below, wherever the caret is. A
 * real Tab moves focus, and the Explorer's rename and New File boxes commit the
 * typed name when they lose it. A real arrow turns on WebView spatial
 * navigation until the next touch on the page, and under it an unhandled real
 * Escape blurs whatever has focus. The function keys are workbench bindings,
 * which the announced event already reaches.
 */
internal val NAVIGATION_KEYS: Map<String, Pair<Int, Int>> = mapOf(
    "ArrowLeft" to (KeyEvent.KEYCODE_DPAD_LEFT to 105),
    "ArrowRight" to (KeyEvent.KEYCODE_DPAD_RIGHT to 106),
    "Home" to (KeyEvent.KEYCODE_MOVE_HOME to 102),
    "End" to (KeyEvent.KEYCODE_MOVE_END to 107),
    "PageUp" to (KeyEvent.KEYCODE_PAGE_UP to 104),
    "PageDown" to (KeyEvent.KEYCODE_PAGE_DOWN to 109),
)

/** The row's latched modifiers as the meta state a hardware keyboard would report. */
internal fun navigationMetaState(ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean): Int =
    (if (ctrl) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or
        (if (alt) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0) or
        (if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0) or
        (if (meta) KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON else 0)

/**
 * One press of a [NAVIGATION_KEYS] entry, down then up, stamped and addressed
 * the way [virtualKeyboardEvents] stamps a typed character.
 */
internal fun navigationKeyEvents(keyCode: Int, scanCode: Int, metaState: Int): List<KeyEvent> {
    val now = SystemClock.uptimeMillis()
    return listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).map { action ->
        KeyEvent(
            now, now, action, keyCode, 0, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, scanCode, 0,
            InputDevice.SOURCE_KEYBOARD,
        )
    }
}
