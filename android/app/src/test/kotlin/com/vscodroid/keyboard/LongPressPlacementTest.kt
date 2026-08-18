package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Tests for [LongPressPlacement].
 *
 * The alternates popup is drawn under a finger that is still holding the key
 * down, so the two things it must do are sit above that key and line up with it.
 * Both are one sign or one operand away from a popup that is still on screen,
 * still tappable, and covering either the finger or the wrong key. Nothing
 * reports that; the user just misses.
 *
 * Measurements come from a real view at draw time, which is why they are
 * arguments here rather than read inside: this is the whole calculation, running
 * without a display.
 */
class LongPressPlacementTest {

    /** A key roughly 48dp wide at density 2, sitting partway across the row. */
    private val anchorLeft = 200
    private val anchorTop = 900
    private val anchorWidth = 96

    private fun centreOf(left: Int, width: Int) = left + width / 2.0

    @Test
    fun `a popup wider than the key is centred on it`() {
        // The normal case: three or four alternates over one key.
        val at = LongPressPlacement.above(
            anchorLeft = anchorLeft,
            anchorTop = anchorTop,
            anchorWidth = anchorWidth,
            popupWidth = 300,
            popupHeight = 120,
            gapPx = 8,
        )

        assertEquals(centreOf(anchorLeft, anchorWidth), centreOf(at.x, 300))
        assertTrue(at.x < anchorLeft, "a wider popup has to start left of the key it belongs to")
    }

    @Test
    fun `a popup narrower than the key is centred on it too`() {
        // One alternate on a wide key. Anchoring to the left edge instead of the
        // centre is the mistake that still looks plausible on a wide key.
        val at = LongPressPlacement.above(
            anchorLeft = anchorLeft,
            anchorTop = anchorTop,
            anchorWidth = 300,
            popupWidth = 96,
            popupHeight = 120,
            gapPx = 8,
        )

        assertEquals(centreOf(anchorLeft, 300), centreOf(at.x, 96))
        assertTrue(at.x > anchorLeft, "a narrower popup has to start right of the key's left edge")
    }

    @Test
    fun `an odd difference in width still lands within a pixel of the centre`() {
        val at = LongPressPlacement.above(
            anchorLeft = anchorLeft,
            anchorTop = anchorTop,
            anchorWidth = 101,
            popupWidth = 300,
            popupHeight = 120,
            gapPx = 8,
        )

        val drift = abs(centreOf(at.x, 300) - centreOf(anchorLeft, 101))
        assertTrue(drift <= 1.0, "the popup centre is $drift px off the key centre")
    }

    @Test
    fun `the popup clears the top of the key by exactly the gap it was given`() {
        // Above, not below. Below puts the popup under the finger that opened
        // it, and the alternate the user wants is the one they cannot see.
        //
        // Both numbers move at run time, the height with the number of
        // alternates and the font scale, the gap with the density, so several
        // pairs go through: a formula that folds either into a constant is
        // right on the one device that constant was read from.
        for ((popupHeight, gapPx) in listOf(120 to 8, 64 to 12, 253 to 3)) {
            val at = LongPressPlacement.above(
                anchorLeft = anchorLeft,
                anchorTop = anchorTop,
                anchorWidth = anchorWidth,
                popupWidth = 300,
                popupHeight = popupHeight,
                gapPx = gapPx,
            )

            val popupBottom = at.y + popupHeight
            val given = "popup height $popupHeight, gap $gapPx"
            assertTrue(
                popupBottom < anchorTop,
                "the popup runs down to $popupBottom, over a key at $anchorTop ($given)",
            )
            assertEquals(
                gapPx, anchorTop - popupBottom,
                "the gap above the key is not the one asked for ($given)",
            )
        }
    }

    @Test
    fun `the key's position on screen moves the popup with it`() {
        // getLocationOnScreen, so both numbers are absolute. Dropping either one
        // pins every popup to the top left corner of the display, which is where
        // a popup goes when the offset is computed but never added.
        val atOrigin = LongPressPlacement.above(0, 0, anchorWidth, 300, 120, 8)
        val moved = LongPressPlacement.above(300, 900, anchorWidth, 300, 120, 8)

        assertEquals(300, moved.x - atOrigin.x)
        assertEquals(900, moved.y - atOrigin.y)
    }
}
