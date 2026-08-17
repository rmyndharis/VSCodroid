package com.vscodroid.keyboard

import kotlin.math.abs
import kotlin.math.sign

/**
 * How far the finger travels per emitted arrow, and how far it has to have
 * travelled in total before the next gear takes over.
 *
 * [activationDp] is cumulative path length since the drag began, so a gear is
 * reached by moving a lot rather than by moving fast.
 */
enum class TrackpadGear(val activationDp: Float, val thresholdDp: Float) {
    /** Character by character. */
    PRECISE(0f, 24f),

    /** Word-sized steps. */
    MODERATE(100f, 14f),

    /** Line and file traversal. */
    FAST(250f, 6f);

    companion object {
        /** The gear a drag of [totalDp] cumulative dp is in. */
        fun forTotalDp(totalDp: Float): TrackpadGear = when {
            totalDp >= FAST.activationDp -> FAST
            totalDp >= MODERATE.activationDp -> MODERATE
            else -> PRECISE
        }
    }
}

/**
 * The arrow-key half of [GestureTrackpad], with no Android types in it.
 *
 * It holds the two leftover deltas and the running path length, and turns each
 * touch delta into the arrow keys that delta earned. Kept apart from the View so
 * gear thresholds, direction choice, accumulator carry-over and the diagonal
 * case can be asserted without a device or a MotionEvent: this widget is the
 * only way a touch user moves the cursor, so a silent regression here costs them
 * arrow keys entirely.
 */
class TrackpadGesture {

    /** Cumulative path length of the current drag, in pixels. */
    var totalDistancePx: Float = 0f
        private set

    /** Horizontal travel not yet paid out as an arrow, in pixels. */
    var pendingDxPx: Float = 0f
        private set

    /** Vertical travel not yet paid out as an arrow, in pixels. */
    var pendingDyPx: Float = 0f
        private set

    /** Starts a new drag. Leftover travel from the previous one is dropped. */
    fun reset() {
        totalDistancePx = 0f
        pendingDxPx = 0f
        pendingDyPx = 0f
    }

    /** The gear the drag so far has reached, at screen [density]. */
    fun gear(density: Float): TrackpadGear =
        TrackpadGear.forTotalDp(totalDistancePx / density)

    /**
     * Folds one touch delta in and returns the arrow keys it earned, in the
     * order they should be sent: horizontal first, then vertical, so a diagonal
     * drag yields both rather than only its dominant axis.
     *
     * [dx] and [dy] are pixels, [density] is `DisplayMetrics.density` and must be
     * positive. Travel below the current gear's step is kept, not discarded, so
     * many small deltas still add up to an arrow.
     */
    fun accumulate(dx: Float, dy: Float, density: Float): List<String> {
        totalDistancePx += abs(dx) + abs(dy)
        val thresholdPx = gear(density).thresholdDp * density

        pendingDxPx += dx
        pendingDyPx += dy

        if (abs(pendingDxPx) < thresholdPx && abs(pendingDyPx) < thresholdPx) {
            return emptyList()
        }

        val directions = ArrayList<String>(2)
        while (abs(pendingDxPx) >= thresholdPx) {
            directions.add(if (pendingDxPx > 0) "ArrowRight" else "ArrowLeft")
            pendingDxPx -= sign(pendingDxPx) * thresholdPx
        }
        while (abs(pendingDyPx) >= thresholdPx) {
            directions.add(if (pendingDyPx > 0) "ArrowDown" else "ArrowUp")
            pendingDyPx -= sign(pendingDyPx) * thresholdPx
        }
        return directions
    }
}
