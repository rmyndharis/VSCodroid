package com.vscodroid.keyboard

import android.view.InputDevice
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [HardwareTyping].
 *
 * What is decided here is when the extra key row stands down for a hardware
 * keyboard and when it comes back, and every wrong answer costs someone a row
 * they were using: a "yes" for a soft keyboard's own key would take the row away
 * from the person typing on the screen, and a "yes" for a mouse click would
 * bring it back in the middle of a run of typing on a hardware keyboard.
 */
class HardwareTypingTest {

    private val keyboard = InputDevice.SOURCE_KEYBOARD
    private val alphabetic = InputDevice.KEYBOARD_TYPE_ALPHABETIC

    @Test
    fun `a key from an alphabetic keyboard that is not the virtual one is hardware typing`() {
        assertTrue(
            HardwareTyping.isHardwareKeyboardKey(isVirtual = false, source = keyboard, keyboardType = alphabetic),
            "typing on a hardware keyboard did not count, so the row stays up beside it",
        )
    }

    @Test
    fun `a key of a source with more than the keyboard bit still counts`() {
        val combined = keyboard or InputDevice.SOURCE_DPAD
        assertTrue(
            HardwareTyping.isHardwareKeyboardKey(isVirtual = false, source = combined, keyboardType = alphabetic),
            "a keyboard that also reports a directional pad was taken for something else",
        )
    }

    @Test
    fun `the soft keyboard and the row, which come from the virtual keyboard, are not`() {
        assertFalse(
            HardwareTyping.isHardwareKeyboardKey(isVirtual = true, source = keyboard, keyboardType = alphabetic),
            "a key from the virtual keyboard counted, so tapping Enter on the soft keyboard or a " +
                "row key would take the row away from the person using it",
        )
    }

    @Test
    fun `a device that is not an alphabetic keyboard is not`() {
        for (type in listOf(InputDevice.KEYBOARD_TYPE_NONE, InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)) {
            assertFalse(
                HardwareTyping.isHardwareKeyboardKey(isVirtual = false, source = keyboard, keyboardType = type),
                "the volume and power keys, a media remote or a pad counted as a keyboard to " +
                    "type on (keyboard type $type)",
            )
        }
    }

    @Test
    fun `a source that is not a keyboard is not`() {
        for (source in listOf(InputDevice.SOURCE_GAMEPAD, InputDevice.SOURCE_TOUCHSCREEN, InputDevice.SOURCE_MOUSE)) {
            assertFalse(
                HardwareTyping.isHardwareKeyboardKey(isVirtual = false, source = source, keyboardType = alphabetic),
                "a key event whose source is not a keyboard counted (source $source)",
            )
        }
    }

    @Test
    fun `a finger or a pen on the screen is a touch`() {
        assertTrue(HardwareTyping.isScreenTouch(InputDevice.SOURCE_TOUCHSCREEN), "a finger did not count")
        assertTrue(
            HardwareTyping.isScreenTouch(InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS),
            "a pen on the screen did not count",
        )
    }

    @Test
    fun `a mouse, a touchpad and a keyboard are not a touch`() {
        for (source in listOf(
            InputDevice.SOURCE_MOUSE,
            InputDevice.SOURCE_TOUCHPAD,
            InputDevice.SOURCE_KEYBOARD,
            InputDevice.SOURCE_TRACKBALL,
        )) {
            assertFalse(
                HardwareTyping.isScreenTouch(source),
                "source $source brought the row back, so a click to place the caret would " +
                    "bring it back in the middle of a run of typing",
            )
        }
    }
}
