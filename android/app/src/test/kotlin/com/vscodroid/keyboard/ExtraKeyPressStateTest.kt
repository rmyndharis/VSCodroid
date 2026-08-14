package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What one press of an extra key reports, and the premises the long-press
 * behaviour rests on.
 *
 * [ExtraKeyButton] is a `View` whose initialiser reaches resources, colours and
 * display metrics, so it cannot be constructed in a JVM unit test and its
 * gesture callbacks cannot be invoked here at all. Say that plainly rather than
 * write something that looks like coverage: what is pinned below is the
 * arithmetic of a press and the shape of the key row, not the wiring from
 * `onLongPress` to it. That wiring is instead held by there being exactly one
 * call site -- both callbacks go through `emitPress` -- so the two cannot
 * diverge the way they had.
 */
class ExtraKeyPressStateTest {

    /**
     * The defect, as arithmetic.
     *
     * A long press on a modifier reported a hard-coded `true` while a tap
     * reported the flipped state. So a long press could only ever switch a
     * modifier on, never off, and it never told the button to change appearance
     * -- Ctrl went live inside `ExtraKeyRow` while the key still looked idle,
     * and the next tap then read as "switch on" from the button and arrived as
     * "switch off" at the row.
     *
     * Replacing this body with a constant `true` -- which is precisely the bug
     * -- turns the second assertion red.
     */
    @Test
    fun `a toggle reports the state it is moving to`() {
        assertTrue(pressedState(isToggle = true, isActive = false), "an idle modifier must switch on")
        assertFalse(
            pressedState(isToggle = true, isActive = true),
            "an active modifier must switch off; reporting a constant true is what made a long " +
                "press unable to release a modifier"
        )
    }

    /**
     * The other half, and what stops the test above being satisfiable by
     * inverting everything: an ordinary key is not a toggle and every press of
     * it is a press.
     */
    @Test
    fun `an ordinary key always reports a press`() {
        assertTrue(pressedState(isToggle = false, isActive = false))
        assertTrue(
            pressedState(isToggle = false, isActive = true),
            "a non-toggle has no active state to invert; `isActive` must not be consulted"
        )
    }

    /**
     * The premise behind not implementing auto-repeat, checked rather than
     * asserted in a comment.
     *
     * The keys that would justify repeating are Backspace and the arrows, and
     * neither is a button on this row: the soft keyboard owns backspace, and the
     * arrows come from the gesture pad, whose drag already emits them
     * continuously through three acceleration gears. What is left with no
     * alternates is Tab, Esc and punctuation, where a repeat turns one
     * over-long hold into `;;;;;;;;` in a source file.
     *
     * If this fails, someone has put a key on the row that wants repeating, and
     * the decision recorded in `ExtraKeyButton.onLongPress` needs revisiting
     * rather than the test.
     */
    @Test
    fun `no key on the row is one that would want auto-repeat`() {
        val repeatWanting = setOf(
            "Backspace", "Delete", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight",
        )

        val offenders = KeyPages.defaults
            .flatMap { it.items }
            .filterIsInstance<KeyItem.Button>()
            .filter { it.value in repeatWanting }
            .map { it.value }

        assertEquals(
            emptyList<String>(), offenders,
            "these keys are on the extra key row and are the kind that wants held-key repeat; " +
                "ExtraKeyButton.onLongPress deliberately does not repeat, on the premise that no " +
                "such key is here. Revisit that decision"
        )
    }

    /**
     * The coupling that makes the toggle half of the fix necessary at all.
     *
     * A long press only reaches the single-press branch when the key has no
     * alternates. The three modifiers have none, which is why they land there
     * and why that branch has to handle toggles correctly. Give one an alternate
     * and it stops arriving there -- worth knowing, because the reasoning in
     * `emitPress` would then describe a case that no longer happens.
     */
    @Test
    fun `the modifier toggles have no alternates so a long press reaches the single-press branch`() {
        val toggles = KeyPages.defaults
            .flatMap { it.items }
            .filterIsInstance<KeyItem.Button>()
            .filter { it.isToggle }

        check(toggles.isNotEmpty()) { "no toggles found; this test would prove nothing" }
        for (toggle in toggles) {
            assertTrue(
                toggle.alternates.isEmpty(),
                "'${toggle.value}' is a toggle with alternates, so a long press now opens a popup " +
                    "instead of reaching the branch emitPress documents"
            )
        }
    }
}
