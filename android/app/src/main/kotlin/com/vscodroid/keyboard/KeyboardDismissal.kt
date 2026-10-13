package com.vscodroid.keyboard

/**
 * Tells a keyboard the user put away from one the system took away for a moment.
 *
 * The page's keyboard guard answers a keyboard going away by holding the focused
 * editor down with `inputmode="none"`, so that the next scroll does not raise it
 * again. On a device with no hardware keyboard that write is also what takes a
 * visible keyboard down: Chromium hides it when the focused element's input mode
 * turns to none. A turn of the phone reports the keyboard hidden and, a few
 * hundred milliseconds later, visible again, and a hold written at the first
 * report kept it away: on an AVD with `hw.keyboard=no`, API 36, the keyboard went
 * down at the turn to landscape and stayed down for as long as it was watched,
 * where without the hold it stayed up. The AVDs that report a hardware keyboard
 * cannot show it, because there the write changes nothing.
 *
 * So a report of hidden does not act at once. It acts after [delayMs] if the
 * keyboard is still hidden by then, which covers a keyboard switch that hides and
 * shows within the delay, and not within [TURN_WINDOW_MS] of a change of
 * configuration. The turn is its own test because the keyboard does not always
 * return within the delay: the hidden report came with the turn, and the visible
 * one 150 to 650 ms after it across six spaced turns on that AVD, and seconds
 * after it in a run of turns every 4 s.
 *
 * Scheduling, the clock and the state of the keyboard are passed in so that the
 * decision can be run without a looper.
 */
class KeyboardDismissal(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val now: () -> Long,
    private val imeVisible: () -> Boolean,
    private val onDismissed: () -> Unit,
    private val delayMs: Long = DELAY_MS,
) {
    private var turnedAt: Long? = null

    private val fire = Runnable {
        val turned = turnedAt?.let { now() - it < TURN_WINDOW_MS } == true
        if (!imeVisible() && !turned) onDismissed()
    }

    /** The system reported the keyboard hidden. A second report replaces the first. */
    fun hidden() {
        cancel(fire)
        schedule(fire, delayMs)
    }

    /** The configuration changed: the turn of the phone, a resize, a fold. */
    fun turned() {
        turnedAt = now()
    }

    /** The activity is going away. */
    fun release() {
        cancel(fire)
    }

    companion object {
        /**
         * Longer than the visible report took after a turn on a clean run, 650 ms
         * at most, and short enough that a scroll right after Back finds the hold
         * in place. A scroll inside it can raise the keyboard again, which is what
         * Back did before the hold existed.
         */
        const val DELAY_MS = 500L

        /**
         * The hold is skipped when the configuration changed this recently. It has
         * to outlast [DELAY_MS], since the change and the hidden report arrive
         * together and the check runs when the delay is over.
         */
        const val TURN_WINDOW_MS = 2_000L
    }
}
