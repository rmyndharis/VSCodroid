package com.vscodroid.keyboard

import com.vscodroid.SourceScan
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
            span = PopupSpan(0, windowWidth),
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
            span = PopupSpan(0, windowWidth),
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
            span = PopupSpan(0, windowWidth),
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
                span = PopupSpan(0, windowWidth),
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
        // Both far enough from either edge that the clamps do not fire, which
        // is the property being measured here.
        val atOrigin = LongPressPlacement.above(200, 200, anchorWidth, 300, 120, 8, PopupSpan(0, windowWidth))
        val moved = LongPressPlacement.above(500, 1100, anchorWidth, 300, 120, 8, PopupSpan(0, windowWidth))

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
            span = PopupSpan(0, windowWidth),
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
            span = PopupSpan(0, windowWidth),
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
            span = PopupSpan(0, windowWidth),
        )

        assertEquals(0, at.x)
    }

    /**
     * The popup over `()`, three alternates, at density 320 on a 1080x2400
     * emulator turned to landscape: 336px wide, as `dumpsys window` measured it.
     * Density 320 because at the emulator's own 420 the row stands down in
     * landscape, so no popup can be opened there.
     */
    private val parenPopup = 336

    private fun overParen(span: PopupSpan, keyLeft: Int, keyWidth: Int) =
        LongPressPlacement.above(keyLeft, 505, keyWidth, parenPopup, 120, 8, span)

    @Test
    fun `below Android 15 a bar on the far side no longer pushes the popup off its key`() {
        // Android 10 with three buttons, turned so the bar is on the left: the
        // metrics said 2304, the row ran from 96 to 2400 and `()` from 2128 to
        // 2400. The popup measured at 1968 to 2304, short of its key by the bar.
        val span = LongPressPlacement.span(29, displayMetricsWidthPx = 2304, rowLeftPx = 96, rowWidthPx = 2304)
        assertEquals(PopupSpan(96, 2400), span)
        val at = overParen(span, keyLeft = 2128, keyWidth = 272)
        assertEquals(2064, at.x)
        assertEquals(2400, at.x + parenPopup, "the popup ends where its key ends")
    }

    @Test
    fun `below Android 15 a cutout on the far side no longer pushes the popup off its key`() {
        // Android 13 turned with the 136px cutout on the left: the metrics said
        // 2264, the row ran from 136 to 2400 and `()` from 2133 to 2400. The
        // popup measured at 1928 to 2264.
        val span = LongPressPlacement.span(33, displayMetricsWidthPx = 2264, rowLeftPx = 136, rowWidthPx = 2264)
        assertEquals(PopupSpan(136, 2400), span)
        assertEquals(2400, overParen(span, keyLeft = 2133, keyWidth = 267).x + parenPopup)
    }

    @Test
    fun `below Android 15 a band on the near side still stops the popup before it`() {
        // Three buttons on the right on Android 10: the metrics' 2304 was where
        // the row ended, and the popup stopped at the bar, measured at 1968 to
        // 2304 before and after. Taking the window's 2400 instead would ask for a
        // popup 32px past the row's end, over the bar.
        val bar = LongPressPlacement.span(29, displayMetricsWidthPx = 2304, rowLeftPx = 0, rowWidthPx = 2304)
        assertEquals(1968, overParen(bar, keyLeft = 2032, keyWidth = 272).x)

        // The cutout on the right on Android 13, the row from 0 to 2264: measured
        // at 1928 to 2264, clear of the cutout, as before.
        val cutout = LongPressPlacement.span(33, displayMetricsWidthPx = 2264, rowLeftPx = 0, rowWidthPx = 2264)
        assertEquals(1928, overParen(cutout, keyLeft = 1997, keyWidth = 267).x)
    }

    @Test
    fun `below Android 15 a popup at the row's left end stays clear of a band before it`() {
        // The left half of the span, which no device showed: no key at the left
        // end of a page carries alternates today. A row starting after a 96px bar
        // keeps a wide popup over its first key from starting under the bar.
        val span = LongPressPlacement.span(29, displayMetricsWidthPx = 2304, rowLeftPx = 96, rowWidthPx = 2304)
        assertEquals(96, overParen(span, keyLeft = 96, keyWidth = 272).x)
    }

    @Test
    fun `from Android 15 the span respects safe horizontal window insets`() {
        for (sdk in listOf(35, 36)) {
            assertEquals(
                PopupSpan(0, 3120),
                LongPressPlacement.span(sdk, displayMetricsWidthPx = 3120, rowLeftPx = 0, rowWidthPx = 3120),
            )
            assertEquals(
                PopupSpan(144, 3120),
                LongPressPlacement.span(
                    sdk,
                    displayMetricsWidthPx = 3120,
                    rowLeftPx = 144,
                    rowWidthPx = 2976,
                    leftInsetPx = 144,
                    rightInsetPx = 0,
                ),
            )
            assertEquals(
                PopupSpan(144, 3024),
                LongPressPlacement.span(
                    sdk,
                    displayMetricsWidthPx = 3120,
                    rowLeftPx = 144,
                    rowWidthPx = 2880,
                    leftInsetPx = 144,
                    rightInsetPx = 96,
                ),
            )
            assertEquals(
                PopupSpan(3120, 3120),
                LongPressPlacement.span(
                    sdk,
                    displayMetricsWidthPx = 3120,
                    rowLeftPx = 0,
                    rowWidthPx = 0,
                    leftInsetPx = 4000,
                    rightInsetPx = 4000,
                ),
            )
        }
    }

    @Test
    fun `a popup near the top edge is clamped instead of returning a negative y`() {
        val at = LongPressPlacement.above(
            anchorLeft = anchorLeft,
            anchorTop = 40,
            anchorWidth = anchorWidth,
            popupWidth = 300,
            popupHeight = 120,
            gapPx = 8,
            span = PopupSpan(0, windowWidth),
        )

        assertEquals(0, at.y, "the popup top went negative (${at.y}) above the window top edge")
    }

    @Test
    fun `a popup near the status bar or top cutout is clamped to the safe top inset`() {
        val clamped = LongPressPlacement.above(
            anchorLeft = anchorLeft,
            anchorTop = 180,
            anchorWidth = anchorWidth,
            popupWidth = 300,
            popupHeight = 120,
            gapPx = 8,
            span = PopupSpan(0, windowWidth),
            topInsetPx = 96,
        )
        assertEquals(96, clamped.y, "the popup top intruded into the 96px top safe inset")

        val clear = LongPressPlacement.above(
            anchorLeft = anchorLeft,
            anchorTop = 300,
            anchorWidth = anchorWidth,
            popupWidth = 300,
            popupHeight = 120,
            gapPx = 8,
            span = PopupSpan(0, windowWidth),
            topInsetPx = 96,
        )
        assertEquals(172, clear.y, "a popup with room below the top inset should not be shifted")
    }

    @Test
    fun `the popup uses a transparent window background so rounded corners render cleanly`() {
        val raw = SourceScan.read("src/main/kotlin/com/vscodroid/keyboard/LongPressPopup.kt")
        assertTrue(
            !raw.contains("import android.graphics.drawable.ColorDrawable"),
            "unused ColorDrawable import is still present in LongPressPopup.kt",
        )
        val popup = SourceScan.withoutComments(raw).replace(Regex("\\s+"), " ")
        assertTrue(
            popup.contains("setBackgroundDrawable(Color.TRANSPARENT.toDrawable())") &&
                !popup.contains("setBackgroundDrawable(context.getColor(R.color.colorPopupBg).toDrawable())"),
            "PopupWindow background is still opaque colorPopupBg instead of Color.TRANSPARENT.toDrawable()",
        )
    }

    @Test
    fun `the popup is placed over the row the key is on, on the release the device runs`() {
        val popup = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/keyboard/LongPressPopup.kt"))
            .replace(Regex("\\s+"), " ")
        assertTrue(
            popup.contains("row.getLocationInWindow(rowLocation)") &&
                popup.contains("ViewCompat.getRootWindowInsets(row)?.getInsets( WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() )") &&
                popup.contains(
                    "span = LongPressPlacement.span( sdkInt = Build.VERSION.SDK_INT, " +
                        "displayMetricsWidthPx = context.resources.displayMetrics.widthPixels, " +
                        "rowLeftPx = rowLocation[0], rowWidthPx = row.width, " +
                        "leftInsetPx = safeInsets?.left ?: 0, rightInsetPx = safeInsets?.right ?: 0, ), " +
                        "topInsetPx = safeInsets?.top ?: 0,",
                ),
            "LongPressPopup no longer keeps the popup inside the safe span and top inset of " +
                "the row it was given, for the release the device runs",
        )
        val row = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/keyboard/ExtraKeyRow.kt"))
        assertTrue(
            row.contains("}.also { it.show(button, this) }"),
            "the key row no longer hands the popup itself as the row the key is on",
        )
    }
}
