package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [TextEditorHold].
 *
 * What is decided here is the answer the platform gets to "is this view a text editor". A window that is already the focused one and says no when it gains the
 * focus again has its keyboard hidden (the reason HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR), and a turn of the phone is followed by such a focus gain. The hold
 * answers yes in place of a real no for the length of the turn, and only then: a page that is not an editor, a keyboard the person put away and a window that is
 * leaving get the real answer. Time is a counter, so every case is exact.
 */
class TextEditorHoldTest {

    private class Harness {
        var now = 10_000L
        val lines = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val hold = TextEditorHold(now = { now }, onAnswer = { real, held, since -> lines += Triple(real, held, since) })
    }

    @Test
    fun `with no hold the answer is the real one`() {
        val h = Harness()

        assertTrue(h.hold.answer(true), "a real yes was changed")
        assertFalse(h.hold.answer(false), "a real no was answered yes with no hold armed: a page that is not an editor would get a keyboard")
        assertFalse(h.hold.active)
    }

    @Test
    fun `an armed hold answers yes in place of a real no, and the real yes stays yes`() {
        val h = Harness()

        h.hold.arm()
        h.now += 1_500
        assertTrue(h.hold.answer(false), "the window's focus gain 1.5 s after the turn got a no from a page that was an editor")
        assertTrue(h.hold.answer(true))
        assertTrue(h.hold.active)
    }

    @Test
    fun `a real yes does not end the hold`() {
        // The hold is armed at a turn whose real answer is yes, so the first question after the turn is a yes: ending on it would end the hold before the
        // window's focus gain it is for.
        val h = Harness()

        h.hold.arm()
        h.now += 100
        assertTrue(h.hold.answer(true))
        h.now += 1_900
        assertTrue(h.hold.answer(false), "a yes that came first ended the hold, and the focus gain two seconds later got the real no")
    }

    @Test
    fun `the hold ends after its time and not before`() {
        val h = Harness()

        h.hold.arm()
        h.now += TextEditorHold.HOLD_MS - 1
        assertTrue(h.hold.answer(false), "the hold ended before its time")
        h.now += 1
        assertFalse(h.hold.answer(false), "the hold outlived its time: a page that has left its editor would still get a yes")
        assertFalse(h.hold.active)
    }

    @Test
    fun `end stops the hold at once`() {
        val h = Harness()

        h.hold.arm()
        h.now += 500
        h.hold.end()
        assertFalse(h.hold.answer(false), "a Back, a keyboard put away or a window leaving did not end the hold")
        assertFalse(h.hold.active)
    }

    @Test
    fun `end with no hold armed is harmless, and a hold armed after it works`() {
        val h = Harness()

        h.hold.end()
        assertFalse(h.hold.answer(false))
        h.hold.arm()
        assertTrue(h.hold.answer(false))
    }

    @Test
    fun `arming again starts the time again, as a turn back inside the window of a turn does`() {
        val h = Harness()

        h.hold.arm()
        h.now += TextEditorHold.HOLD_MS - 500
        h.hold.arm()
        h.now += TextEditorHold.HOLD_MS - 500
        assertTrue(h.hold.answer(false), "the second turn did not extend the hold")
        h.now += 500
        assertFalse(h.hold.answer(false))
    }

    @Test
    fun `a refocus inside the hold extends it to four seconds after the refocus, and a later one extends it again`() {
        val h = Harness()

        h.hold.arm()
        h.now += 5_000
        h.hold.refocused()
        h.now += TextEditorHold.AFTER_REFOCUS_MS - 1
        assertTrue(h.hold.answer(false), "a refocus 5 s after the turn did not carry the hold four seconds on")
        h.now += 1
        assertFalse(h.hold.answer(false), "the extension outlived its four seconds")

        val twice = Harness()
        twice.hold.arm()
        twice.now += 3_000
        twice.hold.refocused()
        twice.now += 3_000
        twice.hold.refocused()
        twice.now += TextEditorHold.AFTER_REFOCUS_MS - 1
        assertTrue(twice.hold.answer(false), "a second refocus did not extend the hold again")
    }

    @Test
    fun `the extension never passes the cap, and a refocus changes nothing for a hold that is not active`() {
        val capped = Harness()
        capped.hold.arm()
        capped.now += 5_000
        capped.hold.refocused()
        capped.now += 3_500
        capped.hold.refocused()
        capped.now += TextEditorHold.CAP_MS - 8_500 - 1
        assertTrue(capped.hold.answer(false), "the hold ended before the cap")
        capped.now += 1
        assertFalse(capped.hold.answer(false), "the hold outlived the cap: a refocus at 8.5 s must not carry it to 12.5 s")

        val none = Harness()
        none.hold.refocused()
        assertFalse(none.hold.answer(false), "a refocus with no hold armed armed one")

        val ended = Harness()
        ended.hold.arm()
        ended.hold.end()
        ended.hold.refocused()
        assertFalse(ended.hold.answer(false), "a refocus brought back a hold that was ended")

        val ran = Harness()
        ran.hold.arm()
        ran.now += TextEditorHold.HOLD_MS
        ran.hold.refocused()
        assertFalse(ran.hold.answer(false), "a refocus after the hold had run out brought it back")
    }

    @Test
    fun `the time is longer than the longest hide measured after a turn and short enough to be a turn's`() {
        // The system's hide came 3.25 s after the app's turn at the most on the five measured (1.0 to 2.1 s after the refocus), at a host load of 99 to 198.
        assertTrue(TextEditorHold.HOLD_MS > 3_250L, "the hold ends before the latest measured hide")
        assertTrue(TextEditorHold.HOLD_MS <= 8_000L, "the hold is longer than the window the dismissal gives a turn")
        // The system's hide came 2.13 s after the refocus at the most, on the five measured.
        assertTrue(TextEditorHold.AFTER_REFOCUS_MS > 2_130L, "the hold after a refocus ends before the longest measured distance from the refocus to the hide")
        assertTrue(TextEditorHold.CAP_MS >= TextEditorHold.HOLD_MS && TextEditorHold.CAP_MS <= 12_000L, "the cap is shorter than the hold, or longer than any turn needs")
    }

    // ---- the lines a run on a device reads

    @Test
    fun `a real no is always written down, with the hold's part of the answer`() {
        val h = Harness()

        h.hold.answer(false)
        h.hold.arm()
        h.now += 1_200
        h.hold.answer(false)
        h.now += TextEditorHold.HOLD_MS
        h.hold.answer(false)

        assertEquals(
            listOf(Triple(false, false, -1L), Triple(false, true, 1_200L), Triple(false, false, 1_200L + TextEditorHold.HOLD_MS)),
            h.lines,
            "a real no was not written down, or its held part or the age of the hold was wrong",
        )
    }

    @Test
    fun `a yes is written down only while a hold is armed and for a moment after its time`() {
        val h = Harness()

        h.hold.answer(true)
        assertTrue(h.lines.isEmpty(), "a yes was written down with no hold: every restart of the input would be a line")

        h.hold.arm()
        h.hold.answer(true)
        assertEquals(1, h.lines.size, "a yes inside the hold was not written down: the page's editor being back would never show")

        h.now += TextEditorHold.HOLD_MS + TextEditorHold.LOG_TAIL_MS - 1
        h.hold.answer(true)
        assertEquals(2, h.lines.size, "a yes just after the hold's time was not written down")

        h.now += 1
        h.hold.answer(true)
        assertEquals(2, h.lines.size, "a yes long after the hold was written down")

        val ended = Harness()
        ended.hold.arm()
        ended.hold.end()
        ended.hold.answer(true)
        assertTrue(ended.lines.isEmpty(), "a yes after the hold was ended was written down")
    }

    @Test
    fun `the debug state carries an age and a flag and nothing else`() {
        val h = Harness()
        assertEquals("holdAge=none holdActive=false realNoAnswers=0 heldAnswers=0", h.hold.describe())

        h.hold.arm()
        h.now += 750
        assertEquals("holdAge=750ms holdActive=true realNoAnswers=0 heldAnswers=0", h.hold.describe())

        h.hold.end()
        assertEquals("holdAge=750ms holdActive=false realNoAnswers=0 heldAnswers=0", h.hold.describe())
    }

    @Test
    fun `the state counts the real no answers since the hold was armed and how many of them it turned into a yes`() {
        val h = Harness()

        h.hold.answer(false)
        assertEquals("holdAge=none holdActive=false realNoAnswers=0 heldAnswers=0", h.hold.describe(), "a no asked before any hold was counted")

        h.hold.arm()
        h.hold.answer(true)
        h.now += 1_000
        h.hold.answer(false)
        h.now += 1_000
        h.hold.answer(false)
        h.hold.end()
        h.hold.answer(false)
        assertEquals(
            "holdAge=2000ms holdActive=false realNoAnswers=3 heldAnswers=2",
            h.hold.describe(),
            "a yes was counted, or a no that the hold answered yes was not, or one after its end was counted as held",
        )

        h.hold.arm()
        assertEquals("holdAge=0ms holdActive=true realNoAnswers=0 heldAnswers=0", h.hold.describe(), "a new hold did not start the counts again")
    }

    // ---- Non-editor transitions and WebView recreation

    @Test
    fun `leaving Quick Pick or terminal for a non-editor inside the hold keeps answering yes until end or expiry`() {
        val h = Harness()
        h.hold.arm()
        h.now += 400
        h.hold.refocused()
        // Quick Pick input or terminal textarea is focused (real = true), then closed to Explorer/Output (real = false) at 1.5 s:
        // unless end() is called, the hold keeps answering true for a non-editor until 6 s (or 4 s past a late refocus).
        h.now += 600
        assertTrue(h.hold.answer(true))
        h.now += 500
        assertTrue(h.hold.answer(false), "while armed, closing Quick Pick or terminal to a non-editor still answers yes until end() or HOLD_MS")
        h.now += TextEditorHold.HOLD_MS - 1_500
        assertFalse(h.hold.answer(false), "after HOLD_MS with an early refocus, the hold must stop answering yes")
    }

    @Test
    fun `recreating EditorWebView after a renderer crash starts with an unarmed hold that refocus does not revive`() {
        val crashed = Harness()
        crashed.hold.arm()
        assertTrue(crashed.hold.active)

        val recreated = Harness()
        assertFalse(recreated.hold.active, "a newly constructed EditorWebView after onRenderProcessGone started with an active hold")
        recreated.hold.refocused()
        assertFalse(recreated.hold.answer(false), "focusing the recreated EditorWebView revived a hold that was never armed on it")
    }
}
