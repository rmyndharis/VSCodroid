package com.vscodroid

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the extra key row is told about hardware typing from the two places that
 * can know, in an order that leaves the key alone, and folds it into the one
 * decision about whether the row shows.
 *
 * Source-level, like [LeaveCurtainWiringTest], with the same ceiling: it cannot
 * see a real keyboard, only that the pieces are connected. What each piece
 * answers is `HardwareTypingTest`, and a real HID keyboard is checklist KB-45.
 */
class HardwareTypingWiringTest {

    private companion object {
        const val MAIN_ACTIVITY = "src/main/kotlin/com/vscodroid/MainActivity.kt"
        const val EXTRA_KEY_ROW = "src/main/kotlin/com/vscodroid/keyboard/ExtraKeyRow.kt"
    }

    private fun read(path: String): String = SourceScan.withoutComments(SourceScan.read(path))

    @Test
    fun `a key is observed after it was delivered, never before`() {
        val dispatch = SourceScan.body(read(MAIN_ACTIVITY), "override fun dispatchKeyEvent(")
        val delegates = dispatch.indexOf("super.dispatchKeyEvent(event)")
        val notes = dispatch.indexOf("noteHardwareTyping(event)")
        assertTrue(
            delegates >= 0 && notes > delegates,
            "dispatchKeyEvent must hand the key to the view tree before it looks at it. " +
                "Looking first puts a statement of ours ahead of the workbench's own handling " +
                "of every key a hardware keyboard sends. super at $delegates, the note at $notes.",
        )
    }

    @Test
    fun `only a key going down from a hardware keyboard takes the row away`() {
        val note = SourceScan.body(read(MAIN_ACTIVITY), "private fun noteHardwareTyping(")
        listOf(
            "KeyEvent.ACTION_DOWN" to "a key going up would hide the row a second time, and a " +
                "repeat would keep asking for a pass over the insets",
            "HardwareTyping.isTypingOnHardwareKeyboard(" to "the decision about which keys count " +
                "lives in one place, and the row's and the soft keyboard's are the virtual " +
                "keyboard's",
            "hiddenForHardwareKeyboard = true" to "the row is never told, so it stays up beside " +
                "the keyboard it repeats",
        ).forEach { (name, why) ->
            assertTrue(note.contains(name), "noteHardwareTyping no longer names `$name`: $why")
        }
        // By name, and each from the object it belongs to: the two integers compile in
        // either order, a swap kills the feature, and the unit cases cannot see the call.
        listOf(
            "isVirtual = device.isVirtual",
            "source = event.source",
            "keyboardType = device.keyboardType",
            "isPrintingKey = event.isPrintingKey",
        ).forEach {
            assertTrue(
                note.contains(it),
                "noteHardwareTyping no longer passes `$it`, so an argument is positional, " +
                    "swapped or read from the wrong object",
            )
        }
    }

    @Test
    fun `a finger leaving the screen gives the row back, after the touch was delivered`() {
        val touch = SourceScan.body(read(MAIN_ACTIVITY), "override fun dispatchTouchEvent(")
        listOf(
            "MotionEvent.ACTION_UP" to "the lift decides: at the first contact the page gives way " +
                "under the finger and a tap is placed on the page as it is when the finger lifts",
            "HardwareTyping.isScreenTouch(" to "a mouse click or a touchpad on a keyboard cover " +
                "would bring the row back in the middle of a run of typing",
            "hiddenForHardwareKeyboard = false" to "the row never returns after the first key",
        ).forEach { (name, why) ->
            assertTrue(touch.contains(name), "dispatchTouchEvent no longer names `$name`: $why")
        }
        assertFalse(
            touch.contains("ACTION_DOWN"),
            "dispatchTouchEvent reacts to the first contact again, which moves the bottom of the " +
                "page under a finger that is still down",
        )
        val delegates = touch.indexOf("super.dispatchTouchEvent(ev)")
        val clears = touch.indexOf("hiddenForHardwareKeyboard = false")
        assertTrue(
            delegates >= 0 && clears > delegates,
            "the touch is not handed to the view tree before the row is brought back, so the " +
                "tap is placed on a page that has already moved. super at $delegates, the " +
                "flag at $clears.",
        )
        assertTrue(
            touch.contains("return handled"),
            "dispatchTouchEvent no longer returns what the view tree answers, so a touch is " +
                "swallowed or invented here",
        )
    }

    @Test
    fun `the row is also given back when asked for by name or when the keyboard is gone`() {
        val activity = read(MAIN_ACTIVITY)
        val toggle = SourceScan.body(activity, "onToggleExtraKeyRow = {")
        assertTrue(
            toggle.contains("if (!hidden) extraKeyRow?.hiddenForHardwareKeyboard = false"),
            "\"Toggle Extra Key Row\" to shown no longer clears the stand-aside, so the " +
                "command says the row is shown while it stays put away until a finger lifts",
        )
        val config = SourceScan.body(activity, "override fun onConfigurationChanged(")
        assertTrue(
            Regex("""newConfig\.keyboard == Configuration\.KEYBOARD_NOKEYS[\s\S]*hiddenForHardwareKeyboard = false""")
                .containsMatchIn(config),
            "a hardware keyboard that went away no longer gives the row back, so a person left " +
                "on the soft keyboard has none until they touch the editor",
        )
    }

    @Test
    fun `the row shows only when neither the user nor a hardware keyboard has put it away`() {
        val source = read(EXTRA_KEY_ROW)
        assertTrue(
            Regex("""val showRow = imeVisible && !suppressedForHeight && !hiddenByUser && !hiddenForHardwareKeyboard""")
                .containsMatchIn(source),
            "the decision about showing the row no longer reads hiddenForHardwareKeyboard, so " +
                "the flag is set and nothing happens",
        )
        val flag = SourceScan.body(source, "var hiddenForHardwareKeyboard = false")
        assertTrue(
            flag.contains("ViewCompat.requestApplyInsets(this)"),
            "setting the flag no longer asks for a pass over the insets, which is the one place " +
                "the row leaves by the door that clears a latched modifier",
        )
        assertFalse(
            flag.contains("visibility"),
            "the flag's setter changes visibility itself, bypassing the branch that dismisses " +
                "the popup and clears a latched modifier. It reads:\n$flag",
        )
    }
}
