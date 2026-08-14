package com.vscodroid.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The budget half of the automatic restart.
 *
 * Lifted out of the service because the decision is arithmetic and the place it
 * used to live is not: `onStartCommand` and the crash handler need a Service,
 * and a Service cannot be built in a plain JVM. What sat inside them was reached
 * by nothing.
 */
class RestartBudgetTest {

    @Test
    fun `the last attempt in the budget is granted and the next is not`() {
        // The mutation this exists for is `<=` in place of `<`. MAX_RESTARTS
        // counts attempts, so off by one here is a crash loop that runs a whole
        // extra cycle -- long enough to look like the fix did nothing, short
        // enough that nobody counts.
        assertTrue(
            hasRestartBudget(MAX_RESTARTS - 1),
            "attempt $MAX_RESTARTS is inside a budget of $MAX_RESTARTS",
        )
        assertFalse(
            hasRestartBudget(MAX_RESTARTS),
            "the budget is $MAX_RESTARTS attempts, not ${MAX_RESTARTS + 1}",
        )
    }

    @Test
    fun `a server that has not crashed yet has budget`() {
        assertTrue(hasRestartBudget(0))
    }

    @Test
    fun `the ceiling that is passed in is the one that is used`() {
        // Kills a predicate written against the constant instead of the
        // parameter, which reads identically at the call site and ignores its
        // argument entirely.
        assertFalse(hasRestartBudget(0, maxRestarts = 0))
        assertTrue(hasRestartBudget(MAX_RESTARTS, maxRestarts = MAX_RESTARTS + 1))
    }
}

/**
 * The pause between restart attempts.
 *
 * Untested while it was one expression inside a coroutine, and it is the kind of
 * expression that fails quietly: a shift whose distance is not bounded produces
 * a number, always, and nothing about the number says it is wrong.
 */
class RestartBackoffTest {

    @Test
    fun `the first attempt waits the base delay`() {
        assertEquals(RESTART_DELAY_MS, restartBackoffMs(1))
    }

    @Test
    fun `each attempt waits twice as long as the one before it`() {
        for (attempt in 1..MAX_BACKOFF_SHIFT) {
            assertEquals(
                restartBackoffMs(attempt) * 2, restartBackoffMs(attempt + 1),
                "attempt ${attempt + 1} has to double attempt $attempt",
            )
        }
    }

    @Test
    fun `the wait stops growing once the shift is capped`() {
        val capped = restartBackoffMs(MAX_BACKOFF_SHIFT + 1)
        assertEquals(capped, restartBackoffMs(MAX_BACKOFF_SHIFT + 2))
        assertEquals(capped, restartBackoffMs(1_000))
    }

    @Test
    fun `a far-out attempt does not wrap round to no wait at all`() {
        // Why the cap is on the shift rather than on the result. Kotlin masks a
        // shift distance to its low six bits, so an unbounded `1L shl (n - 1)`
        // comes back round to `1 shl 0` at attempt 65: an instant retry at the
        // exact point the server has been failing longest, which is the one
        // moment a backoff is worth having.
        val capped = restartBackoffMs(MAX_BACKOFF_SHIFT + 1)
        assertEquals(capped, restartBackoffMs(65))
        assertEquals(capped, restartBackoffMs(Int.MAX_VALUE))
    }

    @Test
    fun `no attempt asks for a wait of zero or less`() {
        // The same masking from the other end: attempt 0 shifts by -1, which
        // masks to 63 and turns the product negative. `delay()` treats that as
        // no wait, so the guard that reads as paranoia is the one keeping the
        // backoff a backoff.
        for (attempt in listOf(Int.MIN_VALUE, -1, 0, 1, 7, Int.MAX_VALUE)) {
            assertTrue(
                restartBackoffMs(attempt) > 0,
                "attempt $attempt gave ${restartBackoffMs(attempt)}",
            )
        }
    }
}
