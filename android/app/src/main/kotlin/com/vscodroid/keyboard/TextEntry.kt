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
