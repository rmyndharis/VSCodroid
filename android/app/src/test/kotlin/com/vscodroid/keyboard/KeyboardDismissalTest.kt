package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [KeyboardDismissal].
 *
 * What is decided here is the difference between a keyboard the user put away
 * and one the system took away for the length of a turn of the phone. Told the
 * second as the first, the page writes `inputmode="none"` on the editor, which on
 * a device with no hardware keyboard takes the keyboard down for good: measured
 * on an AVD with `hw.keyboard=no`, where it went down at the turn to landscape
 * and stayed down. The AVDs that report a hardware keyboard cannot show that, so
 * this is the one place the rule is held.
 *
 * Time is a counter and the scheduler a list, so every case is exact.
 */
class KeyboardDismissalTest {

    private class Harness {
        var now = 1_000L
        var visible = false
        var dismissed = 0
        private val pending = mutableListOf<Pair<Runnable, Long>>()

        val dismissal = KeyboardDismissal(
            schedule = { task, delayMs -> pending += task to (now + delayMs) },
            cancel = { task -> pending.removeAll { it.first === task } },
            now = { now },
            imeVisible = { visible },
            onDismissed = { dismissed += 1 },
        )

        /** Runs whatever falls due in the next [ms], in order, and leaves the clock there. */
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = pending.filter { it.second <= target }.minByOrNull { it.second } ?: break
                now = next.second
                pending.remove(next)
                next.first.run()
            }
            now = target
        }
    }

    @Test
    fun `a keyboard that stays away is dismissed once, after the delay`() {
        val h = Harness()

        h.dismissal.hidden()
        h.advance(KeyboardDismissal.DELAY_MS - 1)
        assertEquals(0, h.dismissed, "the hold was written before the delay was over")

        h.advance(1)
        assertEquals(1, h.dismissed, "a keyboard put away with Back was never dismissed")

        h.advance(60_000)
        assertEquals(1, h.dismissed, "the dismissal ran again with no new report")
    }

    @Test
    fun `a keyboard that is back within the delay is not dismissed`() {
        val h = Harness()

        h.dismissal.hidden()
        h.advance(200)
        h.visible = true
        h.advance(60_000)

        assertEquals(
            0, h.dismissed,
            "a hide followed by a show, as a keyboard switch makes, was taken for the user " +
                "putting the keyboard away, and the hold would keep it away",
        )
    }

    @Test
    fun `a hide that comes with a turn of the phone is not dismissed`() {
        val h = Harness()

        // Measured: the hidden report and the change of configuration arrived within
        // 60 ms of each other, in either order, and the keyboard came back 150 to
        // 650 ms later. The check runs when the delay is over, so the turn is
        // still recent then.
        h.dismissal.hidden()
        h.advance(30)
        h.dismissal.turned()
        h.advance(60_000)
        assertEquals(0, h.dismissed, "the turn that followed the hide was not taken into account")

        val first = Harness()
        first.dismissal.turned()
        first.advance(30)
        first.dismissal.hidden()
        first.advance(60_000)
        assertEquals(0, first.dismissed, "the turn that came before the hide was not taken into account")
    }

    @Test
    fun `a turn does not outlast the keyboard that stayed away after it`() {
        val h = Harness()

        h.dismissal.turned()
        h.advance(KeyboardDismissal.TURN_WINDOW_MS + 1)
        h.dismissal.hidden()
        h.advance(KeyboardDismissal.DELAY_MS)

        assertEquals(
            1, h.dismissed,
            "a turn long past left every later Back unheld, and the next scroll raised the keyboard",
        )
    }

    @Test
    fun `a second hide replaces the first rather than stacking`() {
        val h = Harness()

        h.dismissal.hidden()
        h.advance(300)
        h.dismissal.hidden()
        h.advance(300)
        assertEquals(0, h.dismissed, "the first hide's delay ran after the second replaced it")

        h.advance(KeyboardDismissal.DELAY_MS)
        assertEquals(1, h.dismissed, "two hides were dismissed twice, or not at all")
    }

    @Test
    fun `release drops a dismissal that is waiting`() {
        val h = Harness()

        h.dismissal.hidden()
        h.dismissal.release()
        h.advance(60_000)

        assertEquals(0, h.dismissed, "a dismissal ran after the activity was released")
    }

    @Test
    fun `the turn window is longer than the delay`() {
        // The check runs when the delay is over, so a window not longer than the
        // delay has already closed for a turn that arrived with the hide.
        assertTrue(
            KeyboardDismissal.TURN_WINDOW_MS > KeyboardDismissal.DELAY_MS,
            "the turn window closes before the delay is over, so a turn is never seen by the check",
        )
    }
}
