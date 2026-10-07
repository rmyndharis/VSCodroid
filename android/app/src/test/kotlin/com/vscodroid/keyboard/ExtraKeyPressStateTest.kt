package com.vscodroid.keyboard

import com.vscodroid.SourceScan
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
 * arithmetic of a press, the shape of the key row, and, by reading the source,
 * how the button configures its `GestureDetector`. What the detector then does
 * with a finger is `ExtraKeyButtonTouchInstrumentedTest`'s, on a device. Every
 * press goes through `emitPress`, so a tap, a hold and an assistive click
 * cannot report different things.
 */
class ExtraKeyPressStateTest {

    /** The button's source with comments blanked, so prose naming a call is not a call. */
    private fun button(): String = SourceScan.withoutComments(
        SourceScan.read("src/main/kotlin/com/vscodroid/keyboard/ExtraKeyButton.kt"),
    )

    /** The block every touch on the key goes through before and after the detector. */
    private fun touchListener(source: String): String = SourceScan.body(source, "setOnTouchListener {")

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
     * the decision recorded beside `ExtraKeyButton.onLongPress` needs revisiting
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
                "a hold on an ExtraKeyButton presses once and deliberately does not repeat, on " +
                "the premise that no such key is here. Revisit that decision"
        )
    }

    /**
     * The coupling that decides what a hold on a modifier does.
     *
     * A key with alternates has a long press, which opens them, and a modifier
     * has one that switches it; `onLongPress` tells the two apart by whether the
     * key has alternates. The three modifiers have none, which is why holding
     * Ctrl past the long press latches it while the finger is still down. Give
     * one an alternate and a hold opens a popup instead, which `emitPress` and
     * the row's latch handling do not describe.
     */
    @Test
    fun `the modifier toggles have no alternates so a hold switches them`() {
        val toggles = KeyPages.defaults
            .flatMap { it.items }
            .filterIsInstance<KeyItem.Button>()
            .filter { it.isToggle }

        check(toggles.isNotEmpty()) { "no toggles found; this test would prove nothing" }
        for (toggle in toggles) {
            assertTrue(
                toggle.alternates.isEmpty(),
                "'${toggle.value}' is a toggle with alternates, so a hold on it now opens a popup " +
                    "instead of switching the modifier"
            )
        }
    }

    /**
     * Only a modifier or a key with alternates has a long press, so a swipe
     * from any other key presses nothing.
     *
     * The detector's long press fires once a finger has stayed inside the touch
     * slop for the long-press timeout, and every key used to press itself
     * there. A swipe across the row that starts slowly does exactly that, so the
     * key went out, then the pager took the drag and turned the page, and the
     * ACTION_CANCEL it sent could not take the key back. Measured on an API 36
     * emulator: F7 held 0.5 s and then swiped moved the caret and turned the
     * page.
     *
     * NEGATIVE CONTROL, measured: the button as it was at 54352514, which kept
     * the long press on for every key and pressed from `onLongPress`, and at
     * 602b0afe, which turned it on in the `alternates` setter for keys with
     * alternates alone, both fail at the slice, deciding nothing at a touch.
     * Turning the long press on for every key or for keys with alternates
     * alone, or pressing from `onLongPress` for a key with alternates too, each
     * fails an assertion.
     */
    @Test
    fun `only a modifier or a key with alternates has a long press`() {
        val source = button()
        assertTrue(
            source.contains("private val gestureDetector = GestureDetector(context,"),
            "the button no longer builds the detector this case reads, so its verdict is worth nothing",
        )

        val down = SourceScan.body(touchListener(source), "if (event.action == MotionEvent.ACTION_DOWN) {")
        assertTrue(
            down.contains("gestureDetector.setIsLongpressEnabled(isToggle || alternates.isNotEmpty())"),
            "the long press is not decided at each touch by whether the key is a modifier or has " +
                "alternates: a modifier cannot latch while held, a key with alternates cannot open " +
                "them, or any other key presses itself on the timer. It reads:\n$down",
        )
        val longPress = SourceScan.body(source, "override fun onLongPress(")
        val modifier = SourceScan.body(longPress, "if (alternates.isEmpty()) {")
        assertFalse(
            longPress.replace(modifier, "").contains("emitPress()"),
            "a long press on a key with alternates delivers a press as well. It reads:\n$longPress",
        )
    }

    /**
     * Holding a modifier latches it at the long press, as before the swipe fix,
     * and a drag the pager takes leaves it as it was before the touch.
     *
     * The latch has to be on while the finger is still down: a letter typed on
     * the soft keyboard while Ctrl is held goes through the modifier interceptor,
     * which chords it only if Ctrl is latched by then, and spends the latch. With
     * the latch on release, as at 602b0afe, the letter went out plain and the
     * keystroke after it was chorded instead. A slow swipe that starts on Ctrl
     * reaches the same long press before the pager takes the drag, so the
     * ACTION_CANCEL that follows has to switch the latch back, or the swipe
     * would have latched Ctrl.
     *
     * NEGATIVE CONTROL, measured: the button at 602b0afe fails at the slice,
     * its `onLongPress` having no branch for a modifier. Dropping the switch
     * from that branch, noting the latch after it, or dropping the switch back
     * on ACTION_CANCEL each fails an assertion.
     */
    @Test
    fun `a modifier held past the long press latches, and a drag the pager takes puts it back`() {
        val source = button()
        val modifier = SourceScan.body(SourceScan.body(source, "override fun onLongPress("), "if (alternates.isEmpty()) {")
        val recorded = modifier.indexOf("latchBeforeHold = isToggleActive")
        assertTrue(
            recorded >= 0 && modifier.indexOf("emitPress()") > recorded,
            "a hold on a modifier does not latch it while the finger is down, or switches it before " +
                "noting where it was. It reads:\n$modifier",
        )
        val end = SourceScan.body(touchListener(source), "MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->")
        assertTrue(
            end.contains("if (event.action == MotionEvent.ACTION_CANCEL && before != null && before != isToggleActive) {") &&
                SourceScan.body(end, "before != isToggleActive) {").contains("emitPress()"),
            "a drag the pager takes after a hold leaves the modifier as the hold switched it. " +
                "It reads:\n$end",
        )
    }

    /**
     * Two quick taps on a key are two presses.
     *
     * The listener is a `SimpleOnGestureListener`, which is also an
     * `OnDoubleTapListener`, and the detector looks for double taps whenever it
     * has one: a second tap within 300 ms of the first went to `onDoubleTap`,
     * which the button does not override, and never became a press. Measured on
     * an API 36 emulator: two taps on F7 about 200 ms apart moved the caret
     * once, 650 ms apart twice.
     *
     * NEGATIVE CONTROL, measured: the button as it was at 54352514 fails at the
     * slice, having no setup block for the detector; dropping only
     * `setOnDoubleTapListener(null)` fails the assertion.
     */
    @Test
    fun `a second quick tap on a key is a second press`() {
        val setup = SourceScan.body(button(), "}).apply {")
        assertTrue(
            setup.contains("setOnDoubleTapListener(null)"),
            "the detector still looks for double taps, so the second of two quick taps on a key " +
                "is dropped. It reads:\n$setup",
        )
    }
}
