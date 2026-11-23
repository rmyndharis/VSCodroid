package com.vscodroid.keyboard

import android.os.Build

/** Where a popup's top-left corner goes, in the app window's coordinates. */
data class PopupPosition(val x: Int, val y: Int)

/** The stretch of the app window, left edge to right edge, a popup is kept inside. */
data class PopupSpan(val leftPx: Int, val rightPx: Int)

/**
 * Where the long-press alternates popup goes, in plain numbers: nothing here
 * takes or returns an Android type, so all of it runs on the JVM.
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
     * above the anchor's top edge, kept inside [span] and at or below
     * [topInsetPx].
     *
     * Every number here is in the app window's coordinates, which is the space
     * `PopupWindow.showAtLocation` puts a popup in: the popup is a child of the
     * activity's window and is laid out against that window's frame. The anchor
     * position is therefore the one [android.view.View.getLocationInWindow]
     * reports, and [span] is the stretch that [LongPressPlacement.span] picks.
     * Screen coordinates coincide with these only while the window fills the
     * display.
     *
     * The clamp is not a nicety. `()` is the last key on page 1 and carries
     * three alternates, so its popup is roughly two and a half times the width
     * of the key it is centred on and the centred x puts its right edge well
     * past the edge. Whether the window manager shifted it back or clipped it
     * was never the point: the popup was not where this said it was, and this is
     * the file that is supposed to know. Vertically, clamping to [topInsetPx]
     * keeps the popup below the status bar and top display cutout instead of
     * returning a negative y in short windows.
     *
     * A popup wider than the span is pinned to its left edge rather than
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
        span: PopupSpan,
        topInsetPx: Int = 0,
    ): PopupPosition = PopupPosition(
        x = (anchorLeft + (anchorWidth - popupWidth) / 2)
            .coerceIn(span.leftPx, (span.rightPx - popupWidth).coerceAtLeast(span.leftPx)),
        y = (anchorTop - popupHeight - gapPx).coerceAtLeast(topInsetPx.coerceAtLeast(0)),
    )

    /**
     * The stretch of the window a popup over a key is kept inside, from the
     * activity's display metrics, its safe window insets, and where the key row
     * lies in the window.
     *
     * From Android 15, for an app that targets it as this one does, the display
     * metrics are the whole window including edge-to-edge bars and cutouts, so
     * the safe horizontal span is `[leftInsetPx, displayMetricsWidthPx - rightInsetPx]`.
     * Below it they are the window less the navigation bar and the cutout,
     * and only by width: the app's window still runs under both. Measured in
     * landscape on 1080x2400 emulators set to density 320, because at their own
     * 420 the row stands down in landscape for want of height, with the key row
     * laid out between the insets its container is padded by:
     *
     * - Android 10, three buttons on the right: the metrics said 2304 of the
     *   2400px window, which happens to be where the row ends, so the popup over
     *   the last key stopped at the bar.
     * - Android 10, the bar on the left: the same 2304, while the row ran from 96
     *   to 2400, so that popup ended 96px short of its key's right edge.
     * - Android 13, the cutout on the left: 2264, with the row from 136 to 2400,
     *   so 136px short.
     *
     * So below Android 15 the span is the row itself, [rowLeftPx] for
     * [rowWidthPx]. It is the stretch where the keys are, which the padding keeps
     * clear of the bars and the cutout on both sides, so a popup over a key is
     * never pushed off its key by a band on the far side, nor put under a band on
     * the near one. [displayMetricsWidthPx], [leftInsetPx], and [rightInsetPx]
     * are not read there.
     */
    fun span(
        sdkInt: Int,
        displayMetricsWidthPx: Int,
        rowLeftPx: Int,
        rowWidthPx: Int,
        leftInsetPx: Int = 0,
        rightInsetPx: Int = 0,
    ): PopupSpan =
        if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val width = displayMetricsWidthPx.coerceAtLeast(0)
            val left = leftInsetPx.coerceIn(0, width)
            val right = (width - rightInsetPx.coerceAtLeast(0)).coerceIn(left, width)
            PopupSpan(left, right)
        } else {
            PopupSpan(rowLeftPx, rowLeftPx + rowWidthPx)
        }
}
