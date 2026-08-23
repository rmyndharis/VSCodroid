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
 *
 * The centring cases below were the whole of this file, and they cover the
 * mistake that shows up on first run. The one that does not is the popup landing
 * off the edge of the display, which is what the `()` key on page 1 did: a
 * three-alternate popup centred on the last key of the row. The clamp is now part
 * of the calculation, and the three cases at the end are what make this file
 * worth having as a file.
 */
class LongPressPlacementTest {

    /** A key roughly 48dp wide at density 2, sitting partway across the row. */
    private val anchorLeft = 200
    private val anchorTop = 900
    private val anchorWidth = 96

    /** A 411dp portrait window at density 2, which is where the row is laid out. */
    private val windowWidth = 822

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
            windowWidthPx = windowWidth,
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
            windowWidthPx = windowWidth,
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
            windowWidthPx = windowWidth,
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
                windowWidthPx = windowWidth,
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
    fun `the key's position in the window moves the popup with it`() {
        // getLocationInWindow, so both numbers are the anchor's own position.
        // Dropping either one pins every popup to the top left corner of the
        // window, which is where a popup goes when the offset is computed but
        // never added.
        // Both far enough from either edge that the clamp does not fire, which
        // is the property being measured here.
        val atOrigin = LongPressPlacement.above(200, 0, anchorWidth, 300, 120, 8, windowWidth)
        val moved = LongPressPlacement.above(500, 900, anchorWidth, 300, 120, 8, windowWidth)

        assertEquals(300, moved.x - atOrigin.x)
        assertEquals(900, moved.y - atOrigin.y)
    }

    @Test
    fun `a popup that would run off the right edge is pulled back on screen`() {
        // The live case. `()` is the last key on page 1 and carries three
        // alternates, so its popup is roughly two and a half key widths against a
        // key at the right edge of the row: centred, its right edge lands well
        // past the display. The centring is right and the result was still off
        // screen, which is the failure this file exists to catch and did not.
        val at = LongPressPlacement.above(
            anchorLeft = windowWidth - anchorWidth,
            anchorTop = anchorTop,
            anchorWidth = anchorWidth,
            popupWidth = 240,
            popupHeight = 120,
            gapPx = 8,
            windowWidthPx = windowWidth,
        )

        assertTrue(at.x >= 0, "the popup starts at ${at.x}, left of the window")
        assertTrue(
            at.x + 240 <= windowWidth,
            "the popup runs to ${at.x + 240} on a ${windowWidth}px window, so its last " +
                "alternate is off screen or the window manager has moved it somewhere " +
                "this file cannot describe",
        )
    }

    @Test
    fun `a popup on the leftmost key is not pushed off the left edge`() {
        // The other half. Centring a wide popup on the first key of a page gives
        // a negative x, and clamping only the right edge would leave it there.
        val at = LongPressPlacement.above(
            anchorLeft = 0,
            anchorTop = anchorTop,
            anchorWidth = anchorWidth,
            popupWidth = 240,
            popupHeight = 120,
            gapPx = 8,
            windowWidthPx = windowWidth,
        )

        assertEquals(0, at.x, "the popup starts left of the window")
    }

    @Test
    fun `a popup wider than the window is pinned rather than thrown`() {
        // No page builds one, and `coerceIn` with bounds that cross throws, which
        // would take the key press with it. Pinned to the left edge instead.
        val at = LongPressPlacement.above(
            anchorLeft = 200,
            anchorTop = anchorTop,
            anchorWidth = anchorWidth,
            popupWidth = windowWidth + 100,
            popupHeight = 120,
            gapPx = 8,
            windowWidthPx = windowWidth,
        )

        assertEquals(0, at.x)
    }
}
