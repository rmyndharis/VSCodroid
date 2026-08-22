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
        val portHeld: Boolean = false,
    ) {
        var awaitReadyCalls = 0
        var isAliveCalls = 0
        var portWasHeldCalls = 0

        suspend fun run(): LaunchOutcome = launchOutcome(
            start = { started },
            awaitReady = { awaitReadyCalls++; ready },
            isAlive = { isAliveCalls++; alive },
            portWasHeld = { portWasHeldCalls++; portHeld },
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

    @Test
    fun `an alive process that never had a port to bind is not a slow start`() {
        // The pair that hung the app. Both are alive and neither has answered;
        // only the port tells them apart. A process spawned onto a port something
        // else holds prints EADDRINUSE and then does not exit, so the loop that
        // waits on liveness waits for the life of the app, no failure, no
        // restart, nothing on the notification, and a service that believes it is
        // running so relaunching does nothing either.
        val doomed = Steps(started = true, ready = false, alive = true, portHeld = true)
        assertEquals(LaunchOutcome.CANNOT_BIND, runBlocking { doomed.run() })

        val slow = Steps(started = true, ready = false, alive = true, portHeld = false)
        assertEquals(
            LaunchOutcome.STILL_COMING_UP, runBlocking { slow.run() },
            "a server that had its port and is merely slow must keep being waited for",
        )
    }

    @Test
    fun `a process that answered is never called doomed, whatever the port was doing`() {
        // The port was taken at the spawn and the server answered anyway, the
        // holder let go, or it was ours all along. A rule that reads the port
        // before the answer would kill a server that is serving.
        val steps = Steps(started = true, ready = true, alive = true, portHeld = true)
        assertEquals(LaunchOutcome.READY, runBlocking { steps.run() })
        assertEquals(0, steps.portWasHeldCalls, "a serving server was asked about its port")
    }

    @Test
    fun `a process that is already gone stays the crash path's to report`() {
        // Order, not just outcome. Asking about the port first would take a death
        // the watchdog has already reported and give it a second recovery.
        val steps = Steps(started = true, ready = false, alive = false, portHeld = true)
        assertEquals(LaunchOutcome.DIED_BEFORE_ANSWERING, runBlocking { steps.run() })
        assertEquals(0, steps.portWasHeldCalls)
    }
}

/**
 * Which outcomes leave nothing running, and therefore have to leave the app able
 * to start again.
 *
 * The defect this pins is what a failed start used to leave behind. Both failure
 * branches recorded a notice and returned, and neither cleared the flag
 * `onStartCommand` guards on — so the service went on believing it was running,
 * every later launch was a no-op, and the notification still said "VSCodroid is
 * running" over a Stop button for a server that did not exist. The failure was
 * permanent for the life of the process; the only way out was that Stop button,
 * or force-stopping the app.
 *
 * `enterTerminalState` had already written down why that matters, eleven lines
 * from the code that failed to do it: clearing the flag "is what makes the app
 * recoverable ... relaunching would bind to a service that believes it is
 * already running, start nothing, and leave the editor waiting for a readiness
 * callback that can no longer fire". The crash path did it. The two start
 * failures did not.
 */
class EndsUnreportedTest {

    @Test
    fun `a spawn that never happened has nothing watching it`() {
        // No process means no watchdog and no crash callback, so without the
        // launch path handing this on, nothing in the app would ever try again.
        // That is the state a port held by an orphan reaches -- and an orphan is
        // something that dies, so the attempt is worth repeating.
        assertTrue(endsUnreported(LaunchOutcome.NOT_STARTED))
    }

    @Test
    fun `a pair that cannot bind is handed on too, for the opposite reason`() {
        // Not "no process" but "a process the service has just stopped", which
        // sets isShuttingDown and makes the watchdog suppress the exit. Answering
        // false here is the worse half of the two: the pair is alive and stays
        // alive, so nothing reports anything, and the app sits on a placeholder
        // with a service that believes it is running.
        assertTrue(
            endsUnreported(LaunchOutcome.CANNOT_BIND),
            "a start stopped by the service reports itself or is never reported at all",
        )
    }

    @Test
    fun `a process that died before answering is the crash path's to report`() {
        // The distinction the whole function exists for, and the one that reads
        // wrong at a glance: this ends with nothing running too, so it looks like
        // a sibling of NOT_STARTED. It is not. A process existed and exited, which
        // means the watchdog has already fired for it and a recovery is already
        // under way. Answering true here gives one death two recoveries, and the
        // slower one writes its conclusion over the other's -- which is exactly
        // the defect that made this function change shape.
        assertFalse(
            endsUnreported(LaunchOutcome.DIED_BEFORE_ANSWERING),
            "a process that existed has a watchdog behind it; recovering it twice " +
                "is how a restart gets swallowed",
        )
    }

    @Test
    fun `a serving server needs no recovery`() {
        assertFalse(endsUnreported(LaunchOutcome.READY))
    }

    @Test
    fun `a slow start that is still alive needs no recovery`() {
        assertFalse(
            endsUnreported(LaunchOutcome.STILL_COMING_UP),
            "a live process has not failed, and its eventual exit is the crash path's to report",
        )
    }

    @Test
    fun `every outcome is classified`() {
        // Control. The cases above prove five values; this proves the function is
        // total, so an outcome added later cannot fall through to a silent
        // default. The `when` is exhaustive over the enum, so adding one is a
        // compile error too -- belt and braces, because that half disappears the
        // moment someone adds an `else`.
        LaunchOutcome.entries.forEach { endsUnreported(it) }
        assertEquals(
            5, LaunchOutcome.entries.size,
            "an outcome was added; decide whether anything is watching it",
        )
    }
}

/**
 * That the two start failures actually go through the recoverable stop.
 *
 * [EndsUnreportedTest] pins the decision; this pins that it is consulted
 * and acted on. Neither subsumes the other: the predicate can be perfect and
 * called from nowhere, which is the exact shape of the defect being closed —
 * `enterTerminalState` did the right thing and the two start failures simply did
 * not call it.
 *
 * Source-reading, and therefore the weaker layer. The methods are private on a
 * `Service` and this suite can build neither a `Service` nor its main
 * dispatcher, so there is no stronger option available here.
 */
class RecoverableStopCallSiteTest {

    private val nodeService = File("src/main/kotlin/com/vscodroid/service/NodeService.kt")

    private fun codeLines(): List<IndexedValue<String>> =
        nodeService.readLines().withIndex().filterNot { (_, line) ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    @Test
    fun `an unreported run is handed on, and ending a run has one owner`() {
        check(nodeService.isFile) {
            "NodeService.kt not found at ${nodeService.absolutePath} -- this test would " +
                "otherwise pass by looking at nothing"
        }
        val lines = codeLines()
        val report = { hits: List<IndexedValue<String>> ->
            hits.joinToString("\n") { (i, l) -> "  NodeService.kt:${i + 1}: ${l.trim()}" }
        }

        val gated = lines.filter { (_, l) -> l.contains("if (endsUnreported(") }
        assertTrue(
            gated.isNotEmpty(),
            "the launch path must hand a run nothing is watching to the retry budget, " +
                "or a start that spawned no process is never tried again",
        )
        assertTrue(
            gated.any { (_, l) -> l.contains("retryOrGiveUp") },
            "the predicate must gate the retry, not something else:\n" + report(gated),
        )

        // Exactly two: the declaration and the single call in enterTerminalState.
        // An exact count rather than a floor, and that is the whole assertion --
        // a second caller is the defect, not an extension. Calling this from the
        // launch path is what cleared the service flag while a restart was still
        // waiting out its backoff, and the check on the far side then read it as
        // the user having stopped the server.
        val owners = lines.filter { (_, l) -> l.contains("stopServingRecoverably()") }
        assertEquals(
            2, owners.size,
            "ending a run has one owner -- enterTerminalState, when the budget is " +
                "gone. Found ${owners.size} mention(s):\n" + report(owners),
        )
    }
}

/**
 * That the branch which finds a server unable to bind also gets rid of it.
 *
 * [EndsUnreportedTest] pins that the run is handed to the retry budget; this pins
 * the half that makes the retry mean anything. The pair spawned onto a taken port
 * does not exit on its own, measured: it prints `EADDRINUSE` and stays, and
 * `ProcessManager.startServer` refuses while a process is alive. So a branch that
 * reported the failure without stopping the process would turn every remaining
 * attempt into an instant `NOT_STARTED`, spend the budget in seconds, and still
 * leave the pair running afterwards.
 *
 * Source-reading, and the weaker layer for the usual reason: the branch is inside
 * a coroutine on a `Service`'s main dispatcher, and this suite can build neither.
 */
class CannotBindCleanupTest {

    private val nodeService = File("src/main/kotlin/com/vscodroid/service/NodeService.kt")

    private fun codeLines(): List<IndexedValue<String>> =
        nodeService.readLines().withIndex().filterNot { (_, line) ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    /**
     * The lines of the branch that opens at [start], delimited by its own braces.
     *
     * Not a count of anything, which is the point. A fixed eight-line window stood
     * here, and its comment said it counted statements while it counted
     * non-comment lines, a distinction with no consequence until the branch
     * grows. It had exactly one line of slack: a statement added anywhere above
     * the last assertion still fitted, and a second one pushed `reportFailure(`
     * past the end of the window, at which point this test reports a missing call
     * that is sitting two lines below where it stopped looking. A guard that
     * accuses correct code is worse than no guard, because the first thing the
     * reader does is change the code.
     *
     * Braces cannot drift that way: the window is whatever the branch is, however
     * long it becomes. What they cannot survive is an unbalanced brace inside a
     * string literal on a code line, `"{"` and nothing to close it, which
     * nothing in this branch has today; the failure would be a window running to
     * the end of the file, which the error below names rather than hides.
     */
    private fun branchBody(
        lines: List<IndexedValue<String>>,
        start: Int,
    ): List<IndexedValue<String>> {
        val body = mutableListOf<IndexedValue<String>>()
        var depth = 0
        var opened = false
        for (entry in lines.drop(start)) {
            if (opened) body += entry
            depth += entry.value.count { it == '{' } - entry.value.count { it == '}' }
            if (depth > 0) opened = true
            if (opened && depth == 0) return body
        }
        error(
            "the branch opening at NodeService.kt:${lines[start].index + 1} is never closed; " +
                "its braces do not balance, so no window over it would mean anything"
        )
    }

    @Test
    fun `the doomed pair is stopped where it is diagnosed`() {
        check(nodeService.isFile) {
            "NodeService.kt not found at ${nodeService.absolutePath}, this test would " +
                "otherwise pass by looking at nothing"
        }
        val lines = codeLines()

        // The opening brace is what distinguishes the launch branch from the arm
        // of the same name in [endsUnreported], which is a one-liner.
        val branch = lines.indexOfFirst { (_, l) -> l.contains("LaunchOutcome.CANNOT_BIND -> {") }
        check(branch >= 0) {
            "expected a CANNOT_BIND branch in the launch path; without it a process " +
                "that can never bind is watched for the life of the app"
        }

        // The whole branch, found by its braces. The branch is mostly comment and
        // grows whenever anything is added to it, so any window with a size in it
        // is a window that will one day stop containing the code it is looking for.
        val body = branchBody(lines, branch)
        val shown = body.joinToString("\n") { (i, l) -> "  NodeService.kt:${i + 1}: ${l.trim()}" }

        assertTrue(
            body.any { (_, l) -> l.contains("processManager.stopServer()") },
            "the branch must stop the process it is giving up on, or the retries it " +
                "hands the run to are all refused as 'already running'. Lines " +
                "found in the branch:\n$shown",
        )
        assertTrue(
            body.any { (_, l) -> l.contains("reportFailure(") },
            "and it must say so, or the user is left with a placeholder and no reason:" +
                "\n$shown",
        )
    }
}

/**
 * That a restart takes ownership of the recovery before it waits, not after.
 *
 * The defect: `waitForReady` asks the port and never the process, so a launch
 * attempt polls out its full budget even though the process it is waiting for
 * has already died. That attempt then concludes DIED_BEFORE_ANSWERING and clears
 * the service flag — and `handleServerCrash`, resuming from its backoff, reads
 * the cleared flag as the user having stopped the server and returns without
 * restarting. The restart is swallowed with nothing said.
 *
 * `launchServer` cancels the previous job as its first act, which normally
 * prevents exactly this, but that only helps while the backoff is shorter than
 * what remains of the poll. `RestartBackoffTest` measures where it is not: the
 * last backoff exceeds the whole poll on its own, so there the stale attempt
 * always wins and the restart it eats is the last one in the budget.
 *
 * So the cancel has to happen when the crash is accepted, not when the next
 * launch begins. This pins that ordering.
 *
 * Source-reading, and the weaker of the two layers as always: it sees the order
 * of two statements and not whether they run. Nothing stronger is available —
 * the method is private on a `Service`, and this suite can build neither a
 * `Service` nor a main dispatcher.
 */
class RestartOwnershipTest {

    private val nodeService = File("src/main/kotlin/com/vscodroid/service/NodeService.kt")

    private fun codeLines(): List<IndexedValue<String>> =
        nodeService.readLines().withIndex().filterNot { (_, line) ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    @Test
    fun `the restart cancels the superseded attempt before it backs off`() {
        check(nodeService.isFile) {
            "NodeService.kt not found at ${nodeService.absolutePath} -- this test would " +
                "otherwise pass by looking at nothing"
        }
        val lines = codeLines()

        val bumped = lines.singleOrNull { (_, l) -> l.contains("restartCount++") }
        val backedOff = lines.singleOrNull { (_, l) -> l.contains("delay(restartBackoffMs(") }
        // Both are preconditions rather than assertions about the fix. If either
        // moved or gained a twin, the window below stops meaning what it says and
        // this test would quietly measure the wrong span.
        checkNotNull(bumped) { "expected exactly one restartCount++ in the file" }
        checkNotNull(backedOff) { "expected exactly one delay(restartBackoffMs( in the file" }

        val between = lines.filter { (i, _) -> i > bumped.index && i < backedOff.index }
        val shown = between.joinToString("\n") { (i, l) -> "  NodeService.kt:${i + 1}: ${l.trim()}" }

        assertTrue(
            between.any { (_, l) -> l.contains("cancelLaunch()") },
            "the superseded launch attempt must be cancelled between accepting the crash " +
                "and waiting out the backoff, or it outlives the wait and clears the service " +
                "state from under the restart. Statements found in that span:\n$shown",
        )
    }
}

/**
 * That only the callback is throttled, and that the throttle is keyed on the
 * failure episode.
 *
 * Two substitutions, each of which looked like an identity when it was made.
 *
 * The first was the KEY. `restartCount == 0` looked like "the first failure of
 * this run" and is "the first attempt of a fresh process"; then `runId` looked
 * like "this failure episode" and is "this run" — and a run does not end when a
 * server finally comes up, so after any success every later failure in that run
 * was silent, for as long as the run lasted. A count is not a run, and a run is
 * not an episode. Each fix moved the substitution up one level rather than
 * removing it.
 *
 * The second was the SUBJECT, and it is the one this file now pins hardest.
 * `reportStartupNotice` does two things for two audiences: it raises
 * `onServerError`, which reaches whoever is bound right now and which
 * `MainActivity` answers with a long toast, and it records `startupNotice`,
 * which a client binding LATER reads on demand. Only the first is noisy. The
 * throttle covered both — and since `launchServer` nulls the field at the top of
 * every attempt, from the second attempt onward the gate returned before
 * restoring it, so `lastStartupNotice()` answered null for the rest of the
 * episode. The harm the throttle was added to prevent was moved, not removed.
 *
 * Source-reading, and the weak layer, and there is no strong one here: these are
 * private methods on a `Service`, and this suite can build neither a `Service`
 * nor a main dispatcher. What it can do is refuse both substitutions.
 */
class NoticeGateKeyTest {

    private val nodeService = File("src/main/kotlin/com/vscodroid/service/NodeService.kt")

    private fun codeLines(): List<IndexedValue<String>> =
        nodeService.readLines().withIndex().filterNot { (_, line) ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    private fun report(hits: List<IndexedValue<String>>) =
        hits.joinToString("\n") { (i, l) -> "  NodeService.kt:${i + 1}: ${l.trim()}" }

    @Test
    fun `the record is written before the throttle can return`() {
        // The subject substitution. Both statements live in reportFailure, and
        // the whole correctness of it is that the assignment comes FIRST: a
        // client that binds late reads the field, and launchServer nulls it at
        // the top of every attempt, so a throttle that returns before restoring
        // it leaves that client with nothing for the rest of the episode.
        check(nodeService.isFile) {
            "NodeService.kt not found at ${nodeService.absolutePath} -- this test would " +
                "otherwise pass by looking at nothing"
        }
        val lines = codeLines()

        // Matches the two writers and not the two `startupNotice = null` resets,
        // which is the same pair the literal `startupNotice = message` used to
        // select before the field started carrying whether the attempt was the
        // last one.
        val record = lines.filter { (_, l) -> l.contains("startupNotice = StartupNotice(") }
        val throttle = lines.filter { (_, l) -> l.contains("if (failureRaised) return") }
        assertEquals(1, throttle.size, "expected one throttle:\n" + report(throttle))
        assertTrue(
            record.isNotEmpty(),
            "nothing records the notice at all, so no client could ever read one",
        )

        // Positioned rather than merely present. `reportStartupNotice` writes the
        // same expression, so a bare "one of them comes first" would be satisfied
        // by that unrelated one; the record this is about is the statement
        // immediately above the throttle, in the same function.
        val t = throttle.single().index
        assertTrue(
            record.any { it.index in (t - 6) until t },
            "the throttle returns before the notice is recorded, so from the second " +
                "attempt on there is nothing for a late-binding client to read. " +
                "Throttle at ${t + 1}, records at ${record.map { it.index + 1 }}",
        )
    }

    @Test
    fun `an episode ends everywhere the retry budget is refreshed`() {
        // The key substitution, pinned as the invariant that makes the key right
        // rather than as the name of a field. Every point that hands out a fresh
        // budget is a point a fresh failure deserves to be heard: the server came
        // up, the user stopped it, or the budget ran out. Keying on runId missed
        // the first of those, because a run does not end when a server comes up.
        val lines = codeLines()

        val budget = lines.filter { (_, l) ->
            l.contains("restartCount = 0") && !l.contains("var ")
        }
        assertEquals(
            1, budget.size,
            "the budget must be refreshed in exactly one place, so it cannot be " +
                "refreshed without also ending the episode:\n" + report(budget),
        )

        val ends = lines.filter { (_, l) -> l.contains("endFailureEpisode()") }
        assertEquals(
            4, ends.size,
            "expected the declaration plus its three callers -- readiness, stop, and " +
                "the terminal state:\n" + report(ends),
        )
    }

    @Test
    fun `every failure outcome reports through the throttle`() {
        val lines = codeLines()
        val reported = lines.filter { (_, l) -> l.contains("reportFailure(") }
        assertEquals(
            4, reported.size,
            "expected the declaration plus all three failure outcomes, a spawn that " +
                "failed, a process that died before answering, and one that could never " +
                "bind. A failure that speaks on every attempt is the defect, and so is " +
                "one that never speaks:\n" + report(reported),
        )
    }
}

/**
 * Whether a coroutine waking from a backoff still belongs to the run that started
 * it.
 *
 * The defect: the only thing checked on the far side of the backoff was
 * `isServiceRunning`, and that flag legitimately goes false and true again. Stop
 * clears it; `onStartCommand` sets it back for a fresh run. A backoff reaches
 * half a minute at the later attempts, so a Stop followed by a relaunch inside
 * one let the stale chain wake, read `true`, and restart on top of the server the
 * new run had already started — refused as a live process, so it arrived as a
 * start failure with nothing actually wrong and spent the budget down to the
 * terminal state.
 *
 * A boolean cannot express "still the same run". Two ints can.
 */
class StillOurRunTest {

    @Test
    fun `the run that started the wait may finish it`() {
        assertTrue(stillOurRun(serviceRunning = true, startedRun = 7, currentRun = 7))
    }

    @Test
    fun `a stopped service ends the wait`() {
        assertFalse(
            stillOurRun(serviceRunning = false, startedRun = 7, currentRun = 7),
            "restarting a server the user stopped is the one outcome nothing explains",
        )
    }

    @Test
    fun `a stop and relaunch inside the backoff fences the old chain out`() {
        // The case the flag alone cannot catch, and the whole reason the counter
        // exists: the service IS running, because a newer run started it. A check
        // that only read the flag passes here, which is the defect.
        assertFalse(
            stillOurRun(serviceRunning = true, startedRun = 7, currentRun = 8),
            "a newer run owns the server now; the old chain must not restart over it",
        )
    }

    @Test
    fun `both conditions are required, not either`() {
        // Kills `||` in place of `&&`, which reads identically at the call site
        // and lets a stopped service through whenever the run happens to match.
        assertFalse(stillOurRun(serviceRunning = false, startedRun = 1, currentRun = 2))
    }
}

/**
 * That the kill latch is wired where a single death is counted once.
 *
 * [HeapOverrideLatchTest] pins the arithmetic and says nothing about where it
 * runs, which is the half that is easy to get wrong here and impossible to notice
 * afterwards. Two paths reach `retryOrGiveUp` for one death, the watchdog through
 * `handleServerCrash` and `launchServer`'s `endsUnreported` branch, and the
 * existing code deliberately routes both there. So `retryOrGiveUp` is the obvious
 * home for a counter and the wrong one: a value would lose two lives per crash and
 * be disabled after a death and a half, with the user told it crashed three times
 * when it crashed twice.
 *
 * Source-reading, and the weaker layer for the reason its neighbours give: the
 * method is private on a `Service` and this suite can build neither a `Service`
 * nor its main dispatcher.
 *
 * The comment filter is load-bearing rather than tidiness. A guard over source
 * text is blind to the difference between code and a comment describing code, and
 * every paragraph above mentions `retryOrGiveUp` by name. Without the filter this
 * test reads documentation and passes on it.
 *
 * The second case here is about the same latch and not about that call site: what
 * makes a count worth keeping is that it survives the process, and the count has
 * two writers rather than one. `ProcessManager.requestedHeapCeiling` writes the
 * pair back on every start, so it is read alongside this file.
 */
class HeapLatchCallSiteTest {

    private val nodeService = File("src/main/kotlin/com/vscodroid/service/NodeService.kt")
    private val processManager = File("src/main/kotlin/com/vscodroid/service/ProcessManager.kt")

    private fun codeLines(): List<IndexedValue<String>> =
        nodeService.readLines().withIndex().filterNot { (_, line) ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    /**
     * Every `SharedPreferences` edit in [file], as the text running from `.edit()`
     * to the call that ends it.
     *
     * A statement rather than a line, and that is the whole of the difference. The
     * filter this replaced took the lines mentioning `PREF_HEAP_KILLS` and refused
     * an `.apply()` on one of them, so it could only ever fire while the key and
     * the call shared a line. Spreading the same statement over three lines, which
     * is what a formatter does to it as soon as a second `putInt` is added, puts
     * the key on one line and the terminating call on another and the guard sees
     * neither.
     *
     * Comment lines go first, because both files explain in prose why `apply()` is
     * wrong here and a scan over raw text reads those sentences as code.
     *
     * The KTX `edit { }` form is deliberately not matched. It carries no
     * terminating call for this to read, so a switch to it leaves no statement to
     * find and the count control below goes red rather than green -- which is the
     * direction that wants to be loud, since `edit { }` defaults to `apply()`.
     */
    private fun editStatements(file: File): List<String> {
        check(file.isFile) {
            "${file.name} is not at ${file.absolutePath} -- this test would otherwise " +
                "pass by reading nothing"
        }
        val code = file.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.joinToString("\n")
        return Regex("""\.edit\(\).*?\.(?:commit|apply)\(\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(code)
            .map { it.value }
            .toList()
    }

    @Test
    fun `one death spends at most one life`() {
        check(nodeService.isFile) {
            "NodeService.kt not found at ${nodeService.absolutePath} -- this test would " +
                "otherwise pass by looking at nothing"
        }
        val lines = codeLines()
        val report = { hits: List<IndexedValue<String>> ->
            hits.joinToString("\n") { (i, l) -> "  NodeService.kt:${i + 1}: ${l.trim()}" }
        }

        // Exactly two: the declaration and the one call. A third mention is either
        // a second charging path or the same one moved, and both need reading.
        val mentions = lines.filter { (_, l) -> l.contains("chargeHeapOverride") }
        assertEquals(
            2, mentions.size,
            "charging a kill has one caller, in handleServerCrash. Found ${mentions.size} " +
                "mention(s):\n" + report(mentions),
        )

        // And that the caller is the watchdog-fed entry rather than the shared
        // retry. Located by brace depth from the declaration, because the name of
        // the enclosing function is not on the line that calls it. Depth is counted
        // over the comment-free lines for the reason the class header gives: a
        // brace inside a comment widens the window without bounding anything.
        val declaration = lines.indexOfFirst { (_, l) ->
            l.contains("private suspend fun handleServerCrash(")
        }
        assertTrue(declaration >= 0, "handleServerCrash is gone; this guard names a method")
        var depth = 0
        var entered = false
        var charged = false
        for ((_, line) in lines.drop(declaration)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth > 0) entered = true
            if (line.contains("chargeHeapOverride(")) charged = true
            if (entered && depth <= 0) break
        }
        assertTrue(
            charged,
            "the kill must be charged from handleServerCrash, which the watchdog feeds " +
                "once per death, and not from retryOrGiveUp, which two paths reach for one",
        )
    }

    /**
     * The write has to outlive the cancellation of the scope that reaches it.
     *
     * The charge runs in the service's own scope, which onDestroy cancels, and it
     * suspends on its way to the preference file. A crash arriving while the
     * service is being torn down could otherwise reach the read and never reach
     * the write, and what is lost is not work a cancelled caller stopped wanting:
     * it is the record of a death that has already happened. A ceiling that kills
     * the server every time would go uncharged for the last of those kills and so
     * would never be suspended.
     *
     * Read off the source because the alternative is racing a teardown against a
     * crash, which would be a test that passes on a fast machine.
     */
    @Test
    fun `the charge survives the scope being cancelled`() {
        val body = nodeService.readText()
            .substringAfter("private suspend fun chargeHeapOverride")
            .substringBefore("\n    }")
        assertTrue(
            body.contains("PREF_HEAP_KILLS"),
            "chargeHeapOverride no longer writes the count, so this guard is reading " +
                "the wrong method and would pass whatever the write does",
        )
        assertTrue(
            Regex("""(?m)^\s*val \w+ = withContext\(NonCancellable""").containsMatchIn(body),
            "the charge suspends on its way to the preference file without " +
                "NonCancellable, so a teardown landing mid-charge drops the count " +
                "the suspension budget is made of",
        )
    }

    @Test
    fun `the count is committed rather than deferred`() {
        // apply() is the idiomatic choice everywhere else and is wrong here. The
        // event being recorded is a SIGKILL, and the kill of this app's own process
        // often follows the one it is reacting to; apply()'s deferred write is
        // exactly what loses that race, and a count that does not survive is a
        // latch that never latches.
        //
        // Both writers, not one. The count NodeService puts down is read straight
        // back by ProcessManager.requestedHeapCeiling on the next start, and that
        // method writes the pair itself -- so a deferred write there loses the same
        // race in the same way, and guarding one file leaves half a latch.
        val sites = listOf(nodeService, processManager).flatMap { file ->
            editStatements(file).map { file.name to it }
        }

        // The scanner control. Everything below is a filter, and a filter over an
        // empty list refuses nothing in exactly the voice of one that found nothing
        // wrong.
        assertTrue(
            sites.size >= 2,
            "only ${sites.size} preference edits were found across the two files, so " +
                "the scan is not reading them: $sites",
        )
        // A statement that never reaches a commit or an apply of its own would run
        // on into the next one, and then the terminator this reads belongs to some
        // other edit.
        assertTrue(
            sites.none { (_, statement) -> statement.indexOf(".edit()", 1) > 0 },
            "one edit runs into the next, so the call ending it is not its own: $sites",
        )

        val charging = sites.filter { (_, statement) -> statement.contains("PREF_HEAP_KILLS") }
        assertEquals(
            2, charging.size,
            "the kill count has two writers, NodeService.chargeHeapOverride and " +
                "ProcessManager.requestedHeapCeiling, and both have to survive the " +
                "kill they are recording. Found:\n" +
                charging.joinToString("\n") { (name, s) -> "  $name: $s" },
        )
        charging.forEach { (name, statement) ->
            assertTrue(
                statement.endsWith(".commit()"),
                "the kill count must be committed, not applied, in $name: $statement",
            )
        }
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
    fun `the later backoffs outlive a readiness poll, which is why a restart cancels it`() {
        // Measurement, not a requirement, and it is here because the numbers are
        // here. ProcessManager.waitForReady asks the port and never the process,
        // so it runs its full budget even when the server died a second in. A
        // launch attempt therefore concludes at READY_POLL_TIMEOUT_MS after it
        // started, whatever happened to the process.
        //
        // Meanwhile the crash for that same process starts a backoff. Whichever
        // of the two lands second decides the service state, and if it is the
        // stale launch it writes "nothing is running" over a restart that was
        // already in flight -- which handleServerCrash then reads as a reason to
        // return without restarting.
        //
        // The last attempt is the unconditional case: its backoff exceeds the
        // whole poll, so the stale launch always concludes first no matter when
        // the crash happened.
        assertTrue(
            restartBackoffMs(MAX_RESTARTS) > READY_POLL_TIMEOUT_MS,
            "attempt $MAX_RESTARTS waits ${restartBackoffMs(MAX_RESTARTS)}ms against a " +
                "${READY_POLL_TIMEOUT_MS}ms poll",
        )
        // And the earlier ones are reachable too, on a narrowing window: a crash
        // at t leaves the launch to conclude first whenever t + backoff exceeds
        // the poll. Printed as the latest crash time that is still safe, so the
        // shape is visible rather than asserted away.
        val windows = (1..MAX_RESTARTS).map { attempt ->
            attempt to (READY_POLL_TIMEOUT_MS - restartBackoffMs(attempt))
        }
        assertTrue(
            windows.any { (_, safeUntil) -> safeUntil <= 0 },
            "no attempt reaches the unconditional case: $windows",
        )
        assertTrue(
            windows.all { (_, safeUntil) -> safeUntil < READY_POLL_TIMEOUT_MS },
            "every attempt has some exposed window: $windows",
        )
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
