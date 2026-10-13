package com.vscodroid.keyboard

import android.view.InputDevice

/**
 * Which input events say the user is typing on a hardware keyboard, and which say
 * they have gone back to the screen.
 *
 * The extra key row repeats keys a hardware keyboard already has, and some
 * keyboard apps keep their soft keyboard up beside one, so the row went on taking
 * its height out of the editor for nothing. `VSCodroid: Toggle Extra Key Row`
 * answers that by hand; this is what lets the row stand down on its own, for the
 * length of a run of typing on the hardware keyboard, and come back at the next
 * touch of the screen.
 *
 * Both answers are about where an event came from, not what it was, and each is
 * deliberately narrow, because a wrong "yes" removes a row someone is using:
 *
 * - A key counts only from an alphabetic keyboard that is not the virtual one.
 *   The soft keyboard's own keys, and the row's, are delivered as the virtual
 *   keyboard's, so they never count. The volume and power keys, a media remote and
 *   a game controller are keys but not a keyboard to type on.
 * - A touch counts only from the touchscreen. A mouse click or a touchpad on a
 *   keyboard cover is how someone at a hardware keyboard places the caret, and it
 *   does not mean the soft keyboard is about to be used.
 *
 * Plain values rather than the platform's event objects, so that the decision can
 * be run without a device.
 */
internal object HardwareTyping {

    /**
     * Whether a key from a device with these properties is typing on a hardware
     * keyboard. [source] is the event's own, [isVirtual] and [keyboardType] the
     * producing device's.
     */
    fun isHardwareKeyboardKey(isVirtual: Boolean, source: Int, keyboardType: Int): Boolean =
        !isVirtual &&
            (source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
            keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

    /** Whether a pointer event with this [source] is a finger, or a pen, on the screen. */
    fun isScreenTouch(source: Int): Boolean =
        (source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
}
