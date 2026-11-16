package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [EdgeBackGesture].
 *
 * What is decided here is whether a touch the system cancelled had gone down inside a side's `systemGestures()`
 * back-gesture region, which is the trace a back gesture leaves where the activity is not handed the Back key.
 * At default sensitivity on a screen of 2.75 pixels to the dp, the system reports a 66 px gesture inset at each side
 * under gesture navigation and zero under three-button navigation.
 */
class EdgeBackGestureTest {

    private val width = 1_080f
    private val inset = 66

    @Test
    fun `a touch down at the left edge and cancelled is a back gesture`() {
        val gesture = EdgeBackGesture()

        assertTrue(gesture.down(0.76f, width, inset, inset))

        assertTrue(gesture.cancelled(), "a touch that went down in the first pixel of the window and was cancelled by the system was not taken for the back gesture")
    }

    @Test
    fun `a touch down at the right edge and cancelled is a back gesture`() {
        val gesture = EdgeBackGesture()

        assertTrue(gesture.down(width - 1f, width, inset, inset))

        assertTrue(gesture.cancelled(), "the right edge was not an edge")
    }

    @Test
    fun `a touch down away from the edges and cancelled is not`() {
        val gesture = EdgeBackGesture()

        assertFalse(gesture.down(width / 2, width, inset, inset))

        assertFalse(gesture.cancelled(), "a cancel of a touch that went down in the middle of the window was taken for the back gesture")
    }

    @Test
    fun `the edge zone matches each side's system gesture inset and is inclusive`() {
        val inside = EdgeBackGesture()
        assertTrue(inside.down(inset.toFloat(), width, inset, inset))
        assertTrue(inside.cancelled(), "a touch at the end of the left inset was not an edge touch")

        val outside = EdgeBackGesture()
        assertFalse(outside.down(inset + 1f, width, inset, inset))
        assertFalse(outside.cancelled(), "a touch just past the left inset was an edge touch")

        val insideRight = EdgeBackGesture()
        assertTrue(insideRight.down(width - inset, width, inset, inset))
        assertTrue(insideRight.cancelled(), "a touch at the start of the right inset was not an edge touch")

        val outsideRight = EdgeBackGesture()
        assertFalse(outsideRight.down(width - inset - 1f, width, inset, inset))
        assertFalse(outsideRight.cancelled(), "a touch just outside the right inset was an edge touch")
    }

    @Test
    fun `the zone follows the system gesture inset width across density and sensitivity`() {
        val narrow = EdgeBackGesture()
        assertFalse(narrow.down(60f, width, 24, 24))
        assertFalse(narrow.cancelled(), "60 px is past a 24 px gesture inset")

        val wide = EdgeBackGesture()
        assertTrue(wide.down(60f, width, 96, 96))
        assertTrue(wide.cancelled(), "60 px is inside a 96 px gesture inset")
    }

    @Test
    fun `three-button navigation with zero system gesture insets arms neither edge`() {
        val left = EdgeBackGesture()
        assertFalse(left.down(0f, width, 0, 0), "three-button navigation must not arm a touch at x=0")
        assertFalse(left.cancelled(), "three-button navigation must not report a back gesture on cancel")

        val nearLeft = EdgeBackGesture()
        assertFalse(nearLeft.down(2f, width, 0, 0))
        assertFalse(nearLeft.cancelled())

        val right = EdgeBackGesture()
        assertFalse(right.down(width, width, 0, 0), "three-button navigation must not arm a touch at the right edge")
        assertFalse(right.cancelled())
    }

    @Test
    fun `each side is armed only when its own system gesture inset is above zero`() {
        val leftOnlyOnLeft = EdgeBackGesture()
        assertTrue(leftOnlyOnLeft.down(10f, width, inset, 0))
        assertTrue(leftOnlyOnLeft.cancelled())

        val leftOnlyOnRight = EdgeBackGesture()
        assertFalse(leftOnlyOnRight.down(width - 2f, width, inset, 0), "a zero right inset must not arm the right edge")
        assertFalse(leftOnlyOnRight.cancelled())

        val rightOnlyOnLeft = EdgeBackGesture()
        assertFalse(rightOnlyOnLeft.down(2f, width, 0, inset), "a zero left inset must not arm the left edge")
        assertFalse(rightOnlyOnLeft.cancelled())

        val rightOnlyOnRight = EdgeBackGesture()
        assertTrue(rightOnlyOnRight.down(width - 10f, width, 0, inset))
        assertTrue(rightOnlyOnRight.cancelled())
    }

    @Test
    fun `unreadable insets or an unmeasured window width arm nothing`() {
        val missingLeft = EdgeBackGesture()
        assertFalse(missingLeft.down(1f, width, null, null), "missing insets before attach must arm nothing")
        assertFalse(missingLeft.cancelled())

        val missingRight = EdgeBackGesture()
        assertFalse(missingRight.down(width - 1f, width, null, null))
        assertFalse(missingRight.cancelled())

        val unmeasured = EdgeBackGesture()
        assertFalse(unmeasured.down(0f, 0f, inset, inset), "a window of zero width must arm nothing")
        assertFalse(unmeasured.cancelled())
    }

    @Test
    fun `a touch that ends with the finger going up is not a back gesture, and does not leave one for the next cancel`() {
        val gesture = EdgeBackGesture()

        gesture.down(2f, width, inset, inset)
        gesture.lifted()

        assertFalse(gesture.cancelled(), "a touch lifted at the edge was taken for the back gesture by the next cancel")
    }

    @Test
    fun `a cancel with no touch before it is not a back gesture`() {
        assertFalse(EdgeBackGesture().cancelled(), "a cancel with no touch down was taken for the back gesture")
    }

    @Test
    fun `a pair is answered once`() {
        val gesture = EdgeBackGesture()

        gesture.down(2f, width, inset, inset)
        assertTrue(gesture.cancelled())

        assertFalse(gesture.cancelled(), "one touch was counted as two back gestures")
    }

    @Test
    fun `a touch down in the middle replaces an edge touch that never ended`() {
        val gesture = EdgeBackGesture()

        gesture.down(2f, width, inset, inset)
        assertFalse(gesture.down(width / 2, width, inset, inset))

        assertFalse(gesture.cancelled(), "an edge touch whose end was never seen made the next touch, in the middle, a back gesture")
    }

    @Test
    fun `a quick inward flick from either edge ending in up within the pair span counts as the pair`() {
        val slop = 24f

        val leftFlick = EdgeBackGesture()
        assertTrue(leftFlick.down(2f, width, inset, inset, y = 720f, touchSlopPx = slop))
        leftFlick.move(140f, 725f)
        leftFlick.move(420f, 730f)
        assertTrue(leftFlick.up(420f, 730f, spanMs = 120L, maxSpanMs = 500L))
        assertFalse(leftFlick.up(420f, 730f, spanMs = 120L, maxSpanMs = 500L), "one flick was counted twice")

        val rightFlick = EdgeBackGesture()
        assertTrue(rightFlick.down(width - 2f, width, inset, inset, y = 720f, touchSlopPx = slop))
        rightFlick.move(width - 420f, 720f)
        assertTrue(rightFlick.up(width - 420f, 720f, spanMs = 120L, maxSpanMs = 500L))
    }

    @Test
    fun `a tap at the edge does not count at lift`() {
        val gesture = EdgeBackGesture()
        assertTrue(gesture.down(2f, width, inset, inset, y = 720f, touchSlopPx = 24f))
        gesture.move(4f, 722f)

        val trace = gesture.finishUp(4f, 722f, spanMs = 40L, maxSpanMs = 500L)
        assertFalse(trace?.matched ?: true, "a stationary tap at the edge must not count as a back flick")
        assertEquals(2f, trace?.horizontalTravelPx)
    }

    @Test
    fun `a vertical drag along the edge does not count at lift`() {
        val gesture = EdgeBackGesture()
        assertTrue(gesture.down(2f, width, inset, inset, y = 720f, touchSlopPx = 24f))
        gesture.move(30f, 570f)
        gesture.move(30f, 420f)

        assertFalse(
            gesture.up(30f, 420f, spanMs = 150L, maxSpanMs = 500L),
            "a vertical scroll along the edge with minor horizontal wobble must not count as a back flick",
        )
    }

    @Test
    fun `a slow drag in and out does not count at lift`() {
        val slowInOut = EdgeBackGesture()
        assertTrue(slowInOut.down(2f, width, inset, inset, y = 720f, touchSlopPx = 24f))
        slowInOut.move(200f, 720f)
        slowInOut.move(10f, 720f)
        assertFalse(
            slowInOut.up(10f, 720f, spanMs = 600L, maxSpanMs = 500L),
            "a slow drag moving inward and back out must not count as a back flick",
        )

        val slowInward = EdgeBackGesture()
        assertTrue(slowInward.down(2f, width, inset, inset, y = 720f, touchSlopPx = 24f))
        slowInward.move(420f, 720f)
        assertFalse(
            slowInward.up(420f, 720f, spanMs = 500L, maxSpanMs = 500L),
            "a swipe reaching the span bound must not count",
        )

        val fastAbortedPeek = EdgeBackGesture()
        assertTrue(fastAbortedPeek.down(2f, width, inset, inset, y = 720f, touchSlopPx = 24f))
        fastAbortedPeek.move(200f, 720f)
        fastAbortedPeek.move(10f, 720f)
        assertFalse(
            fastAbortedPeek.up(10f, 720f, spanMs = 150L, maxSpanMs = 500L),
            "a touch that returned back to the edge before lifting must not count as a flick",
        )
    }

    @Test
    fun `unarmed touches produce no end trace and cancel keeps peak inward travel`() {
        val middle = EdgeBackGesture()
        assertFalse(middle.down(width / 2, width, inset, inset, y = 500f))
        assertNull(middle.finishUp(width / 2 + 100f, 500f, spanMs = 80L, maxSpanMs = 500L))

        val cancelled = EdgeBackGesture()
        assertTrue(cancelled.down(2f, width, inset, inset, y = 500f))
        cancelled.move(48f, 500f)
        val cancelTrace = cancelled.finishCancel(12f, 500f)
        assertTrue(cancelTrace?.matched == true)
        assertEquals(10f, cancelTrace?.horizontalTravelPx)
        assertEquals(46f, cancelTrace?.maxInwardPx)
        assertNull(cancelled.finishCancel(12f, 500f), "cancel trace must be emitted once per touch")
    }

    @Test
    fun `an inward flick only just past touch slop counts as the pair`() {
        // Touch slop at density 3.5 is 28 px (8 dp): a flick of 29 px inward in 60 ms counts, whatever distance the system's own
        // back gesture needs before it takes a swipe for Back.
        val flick = EdgeBackGesture()
        assertTrue(flick.down(2f, width, inset, inset, y = 720f, touchSlopPx = 28f))
        flick.move(31f, 722f)
        assertTrue(flick.up(31f, 722f, spanMs = 60L, maxSpanMs = 500L))
    }
}
