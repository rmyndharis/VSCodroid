package com.vscodroid.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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
 * How often a server that has not answered yet is asked again.
 *
 * The loop this feeds never ends while the process is alive — that is the whole
 * point of it, and a server can answer at any moment before it dies. Which means
 * the interval is the only brake there is.
 */
class LateReadinessPollTest {

    @Test
    fun `an answer that is still plausible is asked for often`() {
        assertEquals(LATE_READY_POLL_MS, lateReadinessPollMs(0))
        assertEquals(LATE_READY_POLL_MS, lateReadinessPollMs(LATE_READY_NOTICE_MS - 1))
    }

    @Test
    fun `once even a late answer is unlikely, asking slows down`() {
        // Kills: a single constant interval. Two seconds forever is a probe
        // every two seconds for as long as a wedged process lives, and the loop
        // has no other way to stop costing anything.
        assertEquals(LATE_READY_SLOW_POLL_MS, lateReadinessPollMs(LATE_READY_NOTICE_MS))
        assertTrue(
            LATE_READY_SLOW_POLL_MS > LATE_READY_POLL_MS,
            "the late interval has to be the slower of the two, or the step is backwards",
        )
    }

    @Test
    fun `no elapsed time produces an interval of zero`() {
        // The one property the step has to get right. A zero interval turns a
        // loop with no end condition into a spin on the IO dispatcher; every
        // other choice here is a tuning question, and this one is not.
        for (elapsed in listOf(Long.MIN_VALUE, -1L, 0L, 1L, LATE_READY_NOTICE_MS, Long.MAX_VALUE)) {
            assertTrue(
                lateReadinessPollMs(elapsed) > 0,
                "elapsed $elapsed gave ${lateReadinessPollMs(elapsed)}",
            )
        }
    }
}

/**
 * Where a start attempt ends up, for each of the four ways it can end.
 *
 * The state machine these describe had no test at all. It could not have one
 * where it lived: the branches sat inside a coroutine on a `Dispatchers.Main`
 * scope owned by a `Service`, and this suite has neither Robolectric nor
 * `kotlinx-coroutines-test`, so neither the context nor the dispatcher exists
 * here. That is the same reason [hasRestartBudget] and [restartBackoffMs] were
 * lifted out, and [launchOutcome] is now lifted out beside them.
 *
 * What decides whether the user sees the workbench or a placeholder is which of
 * these four a start reaches, so each one is named.
 */
class LaunchOutcomeTest {

    /** Records which steps were run, so the short-circuits can be asserted. */
    private class Steps(
        val started: Boolean,
        val ready: Boolean = false,
        val alive: Boolean = false,
    ) {
        var awaitReadyCalls = 0
        var isAliveCalls = 0

        suspend fun run(): LaunchOutcome = launchOutcome(
            start = { started },
            awaitReady = { awaitReadyCalls++; ready },
            isAlive = { isAliveCalls++; alive },
        )
    }

    @Test
    fun `a server that answers inside the poll is ready`() {
        val steps = Steps(started = true, ready = true)
        assertEquals(LaunchOutcome.READY, runBlocking { steps.run() })
    }

    @Test
    fun `a spawn that fails is not started`() {
        val steps = Steps(started = false)
        assertEquals(LaunchOutcome.NOT_STARTED, runBlocking { steps.run() })
    }

    @Test
    fun `a failed spawn is not made to sit through the readiness poll`() {
        // The property that made this take suppliers instead of three booleans.
        // waitForReady() polls for thirty seconds by default, so running it after
        // a spawn that already failed delays a message the user is waiting for by
        // half a minute, for an answer that cannot change anything.
        val steps = Steps(started = false)

        runBlocking { steps.run() }

        assertEquals(0, steps.awaitReadyCalls, "a server that never spawned was polled anyway")
        assertEquals(0, steps.isAliveCalls, "and its liveness was asked about")
    }

    @Test
    fun `a ready server is not asked whether it is alive`() {
        // The other short-circuit, and the cheaper one: liveness is beside the
        // point once the server has answered.
        val steps = Steps(started = true, ready = true)

        runBlocking { steps.run() }

        assertEquals(0, steps.isAliveCalls)
    }

    @Test
    fun `a slow start that is still alive keeps being watched`() {
        // The case that removed a cliff. A start slower than the bounded poll is
        // not a failed start, and reporting it as one used to make it permanent --
        // nothing else in the app probes again, so a server that bound its port a
        // second after the poll gave up stayed unreachable for as long as it ran.
        val steps = Steps(started = true, ready = false, alive = true)
        assertEquals(LaunchOutcome.STILL_COMING_UP, runBlocking { steps.run() })
    }

    @Test
    fun `a start that timed out with the process gone is a failure`() {
        // The discriminating pair: same `ready = false`, opposite liveness, and
        // the two must not collapse into one answer. A mutation that drops the
        // liveness question reports every slow start as dead, or every dead start
        // as slow -- and the second leaves a user watching a placeholder forever.
        val steps = Steps(started = true, ready = false, alive = false)
        assertEquals(LaunchOutcome.DIED_BEFORE_ANSWERING, runBlocking { steps.run() })
    }
}

/**
 * What a crash report gets: nothing, another attempt, or the end.
 */
class CrashActionTest {

    @Test
    fun `a crash with budget left earns a restart`() {
        assertEquals(CrashAction.RESTART, crashAction(serviceRunning = true, restartCount = 0))
        assertEquals(
            CrashAction.RESTART,
            crashAction(serviceRunning = true, restartCount = MAX_RESTARTS - 1),
        )
    }

    @Test
    fun `a spent budget gives up`() {
        assertEquals(
            CrashAction.GIVE_UP,
            crashAction(serviceRunning = true, restartCount = MAX_RESTARTS),
        )
    }

    @Test
    fun `a stopped service ignores the exit whatever the budget says`() {
        // Order matters here, not just the outcome. A crash already on its way to
        // the service scope when Stop was pressed still lands in the handler, and
        // it describes a process the user asked to be rid of. Checking the budget
        // first would restart it -- and with a spent budget would instead rewrite
        // a notification that has just been taken down.
        assertEquals(
            CrashAction.IGNORE,
            crashAction(serviceRunning = false, restartCount = 0),
            "a stopped service must not be restarted",
        )
        assertEquals(
            CrashAction.IGNORE,
            crashAction(serviceRunning = false, restartCount = MAX_RESTARTS),
            "nor must a stopped service be driven into the terminal state",
        )
    }

    @Test
    fun `the ceiling that is passed in is the one that is used`() {
        assertEquals(
            CrashAction.GIVE_UP,
            crashAction(serviceRunning = true, restartCount = 0, maxRestarts = 0),
        )
    }
}

/**
 * That nothing forgets the launch coroutine without stopping it first.
 *
 * `enterTerminalState` cleared `launchJob` and did not cancel it, which is worse
 * than doing neither: nulling the handle does not wake the coroutine, it drops
 * the only reference to it -- so `launchServer`'s own `launchJob?.cancel()`, the
 * line that exists to stop two attempts overlapping, then cancelled null. The
 * terminal state deliberately leaves the service alive to recover in place, so
 * the abandoned loop would find the *next* attempt's process alive and carry on
 * against it, able to reset the restart budget and to post a slow-start notice
 * over one the new attempt had just cleared. `MainActivity` refuses to load when
 * it finds a notice, so that showed a healthy server as a failed one.
 *
 * The fix was to remove the affordance rather than to add a check: there is one
 * function that ends a launch, and this pins that there is still only one. A
 * test asserting "cancel appears in enterTerminalState" would measure the shape
 * of a call and pass against a second site added later, which is the shape of the
 * defect it is guarding.
 *
 * Source-reading, and therefore the weaker of two layers -- it cannot tell a
 * cancel that runs from one guarded by an impossible condition. Nothing stronger
 * is available: the methods are private on a `Service` and this suite cannot
 * build one.
 */
class LaunchJobLifecycleTest {

    private val nodeService = File("src/main/kotlin/com/vscodroid/service/NodeService.kt")

    private fun codeLines(): List<IndexedValue<String>> =
        nodeService.readLines().withIndex().filterNot { (_, line) ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    @Test
    fun `only one place clears the launch job, and it cancels in the same breath`() {
        check(nodeService.isFile) {
            "NodeService.kt not found at ${nodeService.absolutePath} -- this test would " +
                "otherwise pass by looking at nothing"
        }

        val lines = codeLines()
        val clears = lines.filter { (_, line) -> line.contains("launchJob = null") }
        val cancels = lines.filter { (_, line) -> line.contains("launchJob?.cancel()") }

        // Printed rather than merely counted: when this fails, which lines were
        // found is the whole of what a reader needs, and a bare count sends them
        // back to the file to work it out again.
        val found = clears.joinToString("\n") { (i, l) -> "  NodeService.kt:${i + 1}: ${l.trim()}" }

        assertEquals(
            1, clears.size,
            "exactly one place may clear launchJob, so that cancelling it cannot be " +
                "forgotten at a second one. Found ${clears.size}:\n$found",
        )
        assertTrue(
            cancels.isNotEmpty(),
            "nothing cancels launchJob at all, so clearing it abandons a running coroutine",
        )

        val clearedAt = clears.single().index
        assertTrue(
            cancels.any { (i, _) -> i in (clearedAt - 2)..clearedAt },
            "launchJob is cleared at NodeService.kt:${clearedAt + 1} with no cancel beside " +
                "it; cancels are at ${cancels.map { it.index + 1 }}",
        )
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
