package com.vscodroid.keyboard

/** Where a popup's top-left corner goes, in screen coordinates. */
data class PopupPosition(val x: Int, val y: Int)

/**
 * Where the long-press alternates popup goes, with no Android types in it.
 *
 * Split out of [LongPressPopup] because the arithmetic is the part that can be
 * wrong: the popup is wider than the key it belongs to, so centring it means
 * starting to the left of the anchor, and it has to clear the finger that is
 * still holding that key down. Both are sign mistakes that leave a popup on
 * screen, in the wrong place, with nothing to report.
 */
object LongPressPlacement {

    /**
     * Centres a [popupWidth] by [popupHeight] popup horizontally on an anchor at
     * [anchorLeft], [anchorTop] of width [anchorWidth], and puts it [gapPx]
     * above the anchor's top edge.
     *
     * The anchor position is the one [android.view.View.getLocationOnScreen]
     * reports, so the result is in screen coordinates too. It is not clamped to
     * the display: a key near either edge yields a popup that starts off screen.
     */
    fun above(
        anchorLeft: Int,
        anchorTop: Int,
        anchorWidth: Int,
        popupWidth: Int,
        popupHeight: Int,
        gapPx: Int,
    ): PopupPosition = PopupPosition(
        x = anchorLeft + (anchorWidth - popupWidth) / 2,
        y = anchorTop - popupHeight - gapPx,
    )
}
