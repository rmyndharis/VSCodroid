package com.vscodroid.keyboard

import kotlin.math.abs

/**
 * Spots the back gesture in the touch events of the window, for the platforms that do not hand the activity the Back key ahead of the keyboard.
 *
 * Where the keyboard or platform takes the Back (Android 14 and Android 16 targeting 36, and Android 13 by platform documentation, though an Android 13 emulator with
 * Gboard 12.4 still handed the Back key to [BackObserverLayout] 8 of 8 times with backAge 8 to 49 ms), [BackObserverLayout] never sees a key. The gesture that makes it leaves a trace in the window's touch events, which the system produces and no
 * page can fake: a finger goes down within the system's back-gesture region at the left or right edge of the window and the system then cancels the touch, because its back gesture has
 * taken it over. Measured on an Android 14 emulator and an Android 16 emulator, with the page's own pointer events and the keyboard's reports (5 of 5 gestures on
 * each): the pointer went down at the very edge (0.76 CSS px from it, on the 14) and the system's cancel followed 9 to 22 ms later, and the keyboard was reported
 * hidden 169 to 333 ms after the cancel on the 14 and 173 to 537 ms after it on the 16. A tap gives a down and an up. A quick inward flick whose lift finishes
 * before the system pilfers the stream also leaves an edge touch that moved inward beyond touch slop and went up within the pair's span ([up]). The keyboard's
 * own hide key and the navigation bar's hide button (3 of 3 on the 16) and the system's hide or the rebuild of the keyboard at a turn of the phone gave no touch
 * event in the 3 s before the report, and an edge swipe that starts over the keyboard is not a Back on either: the keyboard's window gets that touch, not this one.
 *
 * A gesture that is started and aborted gives the same pair and hides nothing, so the pair alone says that a finger was at an edge and the system took the touch,
 * not that a keyboard was put away: what follows it within a short time is what counts ([KeyboardDismissal.edgeGesture]). Nor is every pair a gesture: a turn of the
 * phone may cancel a touch in progress, which [KeyboardDismissal] leaves to the turn.
 */
class EdgeBackGesture {
    private enum class Side { NONE, LEFT, RIGHT }

    data class EndTrace(
        val matched: Boolean,
        val horizontalTravelPx: Float,
        val maxInwardPx: Float,
    )

    private var side = Side.NONE
    private var downX = 0f
    private var downY = 0f
    private var maxInwardPx = 0f
    private var maxVerticalPx = 0f
    private var minInwardPx = DEFAULT_TOUCH_SLOP_PX

    /**
     * A finger went down at ([x], [y]) in a window [width] wide, with system gesture insets [leftInsetPx] and [rightInsetPx]
     * from `WindowInsetsCompat.Type.systemGestures()`, or null when the window's insets cannot be read yet, and [touchSlopPx]
     * from `ViewConfiguration.scaledTouchSlop`.
     *
     * Each side is armed only when its inset is above zero, and its zone width is that inset in pixels, which matches
     * the system's own back-gesture region (24 dp at default sensitivity, scaled by the person's sensitivity setting,
     * and zero under three-button navigation). When the insets cannot be read or the window has no width, neither side
     * is armed. Answers whether this touch is armed, and keeps that state until the touch ends.
     */
    fun down(
        x: Float,
        width: Float,
        leftInsetPx: Int?,
        rightInsetPx: Int?,
        y: Float = 0f,
        touchSlopPx: Float = DEFAULT_TOUCH_SLOP_PX,
    ): Boolean {
        val left = leftInsetPx ?: 0
        val right = rightInsetPx ?: 0
        side = when {
            width <= 0f -> Side.NONE
            left > 0 && x <= left.toFloat() -> Side.LEFT
            right > 0 && x >= width - right.toFloat() -> Side.RIGHT
            else -> Side.NONE
        }
        if (side == Side.NONE) return false
        downX = x
        downY = y
        maxInwardPx = 0f
        maxVerticalPx = 0f
        minInwardPx = touchSlopPx.coerceAtLeast(0f)
        return true
    }

    /** Tracks pointer travel on an armed edge touch so taps, vertical drags, and flicks can be told apart at lift. */
    fun move(x: Float, y: Float) {
        val edgeSide = side
        if (edgeSide == Side.NONE) return
        val inward = inwardDistance(edgeSide, x)
        if (inward > maxInwardPx) maxInwardPx = inward
        val vertical = abs(y - downY)
        if (vertical > maxVerticalPx) maxVerticalPx = vertical
    }

    /** The finger went up or another pointer interfered: clear any armed touch without recording a gesture. */
    fun lifted() {
        side = Side.NONE
    }

    /** The system cancelled the touch. Whether it had gone down at an armed edge, which makes it the back gesture or one that was aborted. Answers once per touch. */
    fun cancelled(): Boolean = finishCancel(downX, downY) != null

    /**
     * An armed edge touch ended with the finger going up at ([x], [y]) after [spanMs]: counts when it moved inward beyond
     * touch slop, stayed predominantly horizontal and still inward at lift, and completed within [maxSpanMs].
     */
    fun up(
        x: Float,
        y: Float,
        spanMs: Long,
        maxSpanMs: Long = KeyboardDismissal.EDGE_PAIR_MS,
    ): Boolean = finishUp(x, y, spanMs, maxSpanMs)?.matched == true

    /** Finishes an armed edge touch on `ACTION_UP`, returning null if the touch was not armed. */
    fun finishUp(
        x: Float,
        y: Float,
        spanMs: Long,
        maxSpanMs: Long = KeyboardDismissal.EDGE_PAIR_MS,
    ): EndTrace? {
        val edgeSide = side
        if (edgeSide == Side.NONE) return null
        move(x, y)
        side = Side.NONE
        val inward = inwardDistance(edgeSide, x)
        val matched = spanMs in 0L until maxSpanMs &&
            maxInwardPx > minInwardPx &&
            inward > minInwardPx &&
            maxInwardPx > maxVerticalPx
        return EndTrace(
            matched = matched,
            horizontalTravelPx = x - downX,
            maxInwardPx = maxInwardPx,
        )
    }

    /** Finishes an armed edge touch on `ACTION_CANCEL`, returning null if the touch was not armed. */
    fun finishCancel(x: Float, y: Float): EndTrace? {
        val edgeSide = side
        if (edgeSide == Side.NONE) return null
        move(x, y)
        side = Side.NONE
        return EndTrace(
            matched = true,
            horizontalTravelPx = x - downX,
            maxInwardPx = maxInwardPx,
        )
    }

    private fun inwardDistance(edgeSide: Side, x: Float): Float = when (edgeSide) {
        Side.LEFT -> x - downX
        Side.RIGHT -> downX - x
        Side.NONE -> 0f
    }

    companion object {
        const val DEFAULT_TOUCH_SLOP_PX = 24f
    }
}
