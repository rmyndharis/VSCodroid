package com.vscodroid.keyboard

/** Where a popup's top-left corner goes, in the app window's coordinates. */
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
     * above the anchor's top edge, kept inside a window [windowWidthPx] wide.
     *
     * Every number here is in the app window's coordinates, which is the space
     * `PopupWindow.showAtLocation` puts a popup in: the popup is a child of the
     * activity's window and is laid out against that window's frame. The anchor
     * position is therefore the one [android.view.View.getLocationInWindow]
     * reports, and [windowWidthPx] is the window's width, which is what an
     * activity's `DisplayMetrics` reports from API 30 on. Screen coordinates
     * coincide with these only while the window fills the display.
     *
     * The clamp is not a nicety. `()` is the last key on page 1 and carries
     * three alternates, so its popup is roughly two and a half times the width
     * of the key it is centred on and the centred x puts its right edge well
     * past the edge. Whether the window manager shifted it back or clipped it
     * was never the point: the popup was not where this said it was, and this is
     * the file that is supposed to know.
     *
     * A popup wider than the window is pinned to the left edge rather than
     * pushed off the other one. Nothing builds one today; the alternative is a
     * `coerceIn` whose bounds cross, which throws.
     */
    fun above(
        anchorLeft: Int,
        anchorTop: Int,
        anchorWidth: Int,
        popupWidth: Int,
        popupHeight: Int,
        gapPx: Int,
        windowWidthPx: Int,
    ): PopupPosition = PopupPosition(
        x = (anchorLeft + (anchorWidth - popupWidth) / 2)
            .coerceIn(0, (windowWidthPx - popupWidth).coerceAtLeast(0)),
        y = anchorTop - popupHeight - gapPx,
    )
}
