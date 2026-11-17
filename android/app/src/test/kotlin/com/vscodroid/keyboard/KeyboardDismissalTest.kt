package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

        val dismissal: KeyboardDismissal = KeyboardDismissal(
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
        h.advance(KeyboardDismissal.TURN_FLOOR_MS + 1)
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

    // ---- a hide reported within the window of a turn, whenever the check runs
    //
    // First hidden report after a turn on the runs where the keyboard went away: 0.44, 1.75, 3.29 and 5.89 s on one emulator at a host load of 50 to 150, and
    // 2.5 s on another. The check runs half a second after the report, so a window counted to the check closed on three of them.

    @Test
    fun `a hide reported two and a half seconds after a turn that found the keyboard up is not dismissed`() {
        val h = Harness()

        h.visible = true
        h.dismissal.turned()
        h.advance(2_500)
        h.visible = false
        h.dismissal.hidden()
        h.advance(60_000)

        assertEquals(
            0, h.dismissed,
            "a hidden report that came 2.5 s after the turn was taken for a keyboard put away, and the hold took the keyboard down",
        )
    }

    @Test
    fun `a keyboard that was down at the turn is dismissed when it is reported hidden after the floor`() {
        val h = Harness()

        h.visible = false
        h.dismissal.turned()
        h.advance(2_500)
        h.visible = false
        h.dismissal.hidden()
        h.advance(60_000)

        assertEquals(
            1, h.dismissed,
            "a keyboard shown after a turn and put away 2.5 s after it was left to the turn: only a keyboard the turn found up has the longer window",
        )
    }

    @Test
    fun `the window is counted to the report and not to the check`() {
        val h = Harness()

        h.dismissal.turned()
        h.advance(KeyboardDismissal.TURN_FLOOR_MS - 1)
        h.dismissal.hidden()
        h.advance(60_000)
        assertEquals(0, h.dismissed, "a report inside the window was judged by the time the delay ran out")

        val late = Harness()
        late.dismissal.turned()
        late.advance(KeyboardDismissal.TURN_FLOOR_MS)
        late.dismissal.hidden()
        late.advance(KeyboardDismissal.DELAY_MS)
        assertEquals(1, late.dismissed, "a report as old as the window was still taken for a turn")

        val refocused = Harness()
        refocused.visible = true
        refocused.dismissal.turned()
        refocused.dismissal.refocused()
        refocused.advance(KeyboardDismissal.AFTER_REFOCUS_MS - 1)
        refocused.visible = false
        refocused.dismissal.hidden()
        refocused.advance(60_000)
        assertEquals(0, refocused.dismissed, "a report inside the allowance after the refocus was judged by the time the delay ran out")

        val past = Harness()
        past.visible = true
        past.dismissal.turned()
        past.dismissal.refocused()
        past.advance(KeyboardDismissal.AFTER_REFOCUS_MS)
        past.visible = false
        past.dismissal.hidden()
        past.advance(KeyboardDismissal.DELAY_MS)
        assertEquals(1, past.dismissed, "a report as old as the allowance after the refocus was still taken for the turn")
    }

    @Test
    fun `the cap covers the slowest hidden report measured`() {
        assertTrue(
            KeyboardDismissal.TURN_CAP_MS > 6_772,
            "the cap is shorter than the 6.8 s a hidden report took after a turn's finish on an emulator at a host load of 50 to 150",
        )
    }

    @Test
    fun `the allowance after the refocus covers the longest distance measured`() {
        assertTrue(
            KeyboardDismissal.AFTER_REFOCUS_MS > 5_526,
            "the allowance is shorter than the 5.5 s the keyboard was reported hidden after the refocus in the latest recorded turn",
        )
    }

    // ---- runs that lost the keyboard on a loaded emulator, replayed through the real decision
    //
    // Times are milliseconds after the turn, taken as the configuration change; the keyboard was up when it began. "Refocus" is the WebView taking the focus
    // again (the app's `clearFocus` and `requestFocus`), which the ask waits for; "report" is the key row reporting the keyboard hidden, which `hidden`
    // gets only when the state changes.

    /** Runs the events in the order of their times, then lets everything that is due run. */
    private fun replay(h: Harness, events: List<Pair<Long, () -> Unit>>) {
        val start = h.now
        for ((at, event) in events.sortedBy { it.first }) {
            h.advance(start + at - h.now)
            event()
        }
        h.advance(60_000)
    }

    // Five runs of a debug build on HeliBoard: the page's event log has the turn's first event, the refocus's focusin on the editor host, and
    // `setAttribute inputmode` written 2.5 s after that focusin, by the guard's late check, which waits out the 2.5 s after a text focus when the dismissal
    // hook is called inside them and then holds a keyboard still reported down. The hook was called
    // by [KeyboardDismissal] between the focusin and the hold, so the hidden report came between half a second before the focusin and half a second before
    // the hold. The turn's first event stands for the configuration change (it follows it, so a report counted to the configuration change is no younger
    // than shown). Each run is tried with the earliest report that can have been dismissed (the check runs 2 s or more after the turn, today) and the latest
    // one that fits.
    private val heliBoardRuns = listOf(
        Triple("run 1", 929L, 3_487L),
        Triple("run 2", 1_611L, 4_124L),
        Triple("run 3", 1_954L, 4_457L),
        Triple("run 4", 2_601L, 5_115L),
        Triple("run 5", 2_743L, 5_258L),
    )

    @Test
    fun `the hidden reports of five HeliBoard runs that lost the keyboard never reach the page's hook`() {
        for ((run, focusin, hold) in heliBoardRuns) {
            val earliest = maxOf(1_500L, focusin - KeyboardDismissal.DELAY_MS)
            val latest = hold - KeyboardDismissal.DELAY_MS - 1
            for (report in listOf(earliest, latest)) {
                val h = Harness()
                h.visible = true
                h.dismissal.turned()
                replay(h, listOf(focusin to { h.dismissal.refocused() }, report to { h.visible = false; h.dismissal.hidden() }))

                assertEquals(
                    0, h.dismissed,
                    "$run: a hidden report $report ms after the turn reached the page's hook, whose late check writes the hold 2.5 s after the turn's focus",
                )
            }
        }
    }

    // Gboard 15.9, Korean, on an emulator at a host load of 100 to 170, the turn to landscape. The key row reports the keyboard hidden 1.34 s after the turn
    // and logs another hidden dispatch at 3.91 s; the app's refocus waited for the keyboard's own commit: the page's blur and focus come 4.28 s after the turn
    // and the WebView's own about 5.08 s after it; the page's hold was written 0.19 s after the first of them and the app hid the keyboard 0.04 s later.
    @Test
    fun `a refocus that comes five seconds after the turn is waited for and no hold is written`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                1_340L to { h.visible = false; h.dismissal.hidden() },
                3_910L to { h.dismissal.hidden() },
                5_080L to { h.dismissal.refocused() },
            ),
        )

        assertEquals(0, h.dismissed, "the second hidden report, 3.9 s after the turn, was taken for the person's: the guard wrote the hold at 19.0 s")
    }

    // The turn back on the same device: hidden 1.71 s and 2.28 s after the turn; the WebView's refocus 2.1 s after it; the keyboard reported visible again
    // 5.65 s after it; the page's hold was written after that, 3.9 s after the refocus, and the app hid the keyboard.
    @Test
    fun `the turn back writes no hold, before or after the keyboard comes back`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                1_714L to { h.visible = false; h.dismissal.hidden() },
                2_078L to { h.dismissal.refocused() },
                2_275L to { h.dismissal.hidden() },
                5_650L to { h.visible = true },
            ),
        )

        assertEquals(0, h.dismissed, "a hold was written for the turn back, after the keyboard had come back")
    }

    // ---- a keyboard that was up when a turn began and is gone within its window writes no hold

    @Test
    fun `a keyboard that was up at a turn and is reported hidden within the window writes no hold`() {
        val h = Harness()

        h.visible = true
        h.dismissal.turned()
        h.advance(200)
        h.dismissal.refocused()
        h.advance(1_500)
        h.visible = false
        h.dismissal.hidden()
        h.advance(60_000)

        assertEquals(0, h.dismissed, "the keyboard the system took away after the turn was held down")
    }

    // Android 16 emulator, Korean Gboard 15.9, at a host load of 185 to 198: the display had not turned 2 s after the command, the hidden report came 59 ms
    // after the app's turn, and the page's refocus pair came about 12 s after the command, after the turn back.
    @Test
    fun `a keyboard lost at the turn, with the turn's refocus twelve seconds late, writes no hold`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                59L to { h.visible = false; h.dismissal.hidden() },
                10_000L to { h.dismissal.turned() },
                12_000L to { h.dismissal.refocused() },
            ),
        )

        assertEquals(0, h.dismissed)
    }

    @Test
    fun `the turn says whether the keyboard counts as up`() {
        val h = Harness()
        assertEquals(false, h.dismissal.turned(), "a turn with the keyboard down counted it as up")

        h.visible = true
        assertEquals(true, h.dismissal.turned(), "a turn with the keyboard up did not")

        h.visible = false
        h.dismissal.hidden()
        assertEquals(true, h.dismissal.turned(), "a turn after a hide that arrived a moment before did not count the keyboard as up")

        val later = Harness()
        later.visible = true
        later.dismissal.turned()
        later.advance(KeyboardDismissal.TURN_CAP_MS + 1)
        later.visible = false
        assertEquals(false, later.dismissal.turned(), "a turn long after the last one, with the keyboard down, counted it as up")
    }

    @Test
    fun `a dismissal after the window still fires after an earlier hide inside it once the keyboard has come back`() {
        val uninterrupted = Harness()
        uninterrupted.visible = true
        uninterrupted.dismissal.turned()
        uninterrupted.dismissal.refocused()
        uninterrupted.advance(KeyboardDismissal.AFTER_REFOCUS_MS - 400)
        uninterrupted.visible = false
        uninterrupted.dismissal.hidden()
        uninterrupted.advance(KeyboardDismissal.DELAY_MS + 100)
        uninterrupted.dismissal.hidden()
        uninterrupted.advance(60_000)
        assertEquals(0, uninterrupted.dismissed, "a duplicate hidden report of an uninterrupted turn hide was taken for a fresh dismissal")

        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        h.dismissal.refocused()
        h.advance(KeyboardDismissal.AFTER_REFOCUS_MS - 400)
        h.visible = false
        h.dismissal.hidden()
        h.advance(200)
        h.visible = true
        h.dismissal.shown()
        h.advance(KeyboardDismissal.DELAY_MS)
        h.visible = false
        h.dismissal.hidden()
        h.advance(60_000)

        assertEquals(1, h.dismissed, "a keyboard that came back and was put away after the allowance was not dismissed")
    }

    @Test
    fun `a keyboard that comes back within the delay writes no hold`() {
        val h = Harness()

        h.visible = true
        h.dismissal.turned()
        h.advance(200)
        h.dismissal.refocused()
        h.advance(800)
        h.visible = false
        h.dismissal.hidden()
        h.advance(KeyboardDismissal.DELAY_MS - 1)
        h.visible = true
        h.advance(60_000)

        assertEquals(0, h.dismissed)
    }

    @Test
    fun `a keyboard reported hidden after the window is dismissed`() {
        val h = Harness()

        h.visible = true
        h.dismissal.turned()
        h.advance(200)
        h.dismissal.refocused()
        h.advance(KeyboardDismissal.TURN_CAP_MS)
        h.visible = false
        h.dismissal.hidden()
        h.advance(60_000)

        assertEquals(1, h.dismissed, "a keyboard put away long after the turn was not dismissed")
    }

    @Test
    fun `a turn inside the window of another remembers that the keyboard was up and writes no hold`() {
        val h = Harness()

        h.visible = true
        h.dismissal.turned()
        h.advance(200)
        h.dismissal.refocused()
        h.advance(1_300)
        h.visible = false
        h.dismissal.hidden()
        h.advance(1_500)

        h.advance(500)
        h.dismissal.turned()
        h.advance(200)
        h.dismissal.refocused()
        h.advance(800)
        h.dismissal.hidden()
        h.advance(60_000)

        assertEquals(0, h.dismissed)
    }

    @Test
    fun `a hide that arrives a moment before the turn counts as a keyboard that was up`() {
        val h = Harness()

        h.visible = false
        h.dismissal.hidden()
        h.advance(30)
        h.dismissal.turned()
        h.advance(100)
        h.dismissal.refocused()
        h.advance(60_000)

        assertEquals(0, h.dismissed)
    }

    @Test
    fun `release drops a dismissal check that is waiting`() {
        val h = Harness()

        h.visible = false
        h.dismissal.hidden()
        h.advance(KeyboardDismissal.DELAY_MS - 1)
        h.dismissal.release()
        h.advance(60_000)

        assertEquals(0, h.dismissed, "a dismissal check ran after the activity was released")
    }

    @Test
    fun `the floor is longer than the delay`() {
        // The check runs when the delay is over, so a window not longer than the
        // delay has already closed for a turn that arrived with the hide.
        assertTrue(
            KeyboardDismissal.TURN_FLOOR_MS > KeyboardDismissal.DELAY_MS,
            "the floor closes before the delay is over, so a turn is never seen by the check",
        )
    }

    @Test
    fun `the floor, the allowance and the cap are in order`() {
        assertTrue(KeyboardDismissal.TURN_FLOOR_MS < KeyboardDismissal.AFTER_REFOCUS_MS, "the allowance after the refocus is inside the floor")
        assertTrue(KeyboardDismissal.AFTER_REFOCUS_MS < KeyboardDismissal.TURN_CAP_MS, "the allowance after the refocus is longer than the cap")
    }

    @Test
    fun `the Back allowance covers the longest distance measured`() {
        assertTrue(KeyboardDismissal.BACK_MS > 4_500L, "the Back allowance ends before the longest distance measured from a Back to its report (4.5 s on Android 10 at a load of 99 to 191)")
    }

    // ---- the window follows the turn's own events

    @Test
    fun `a report before the refocus is the turn's until the cap, and not after it`() {
        val before = Harness()
        before.visible = true
        before.dismissal.turned()
        before.advance(KeyboardDismissal.TURN_CAP_MS - 1)
        before.visible = false
        before.dismissal.hidden()
        before.advance(60_000)
        assertEquals(0, before.dismissed, "a report with the refocus still to come, inside the cap, was taken for the person's")

        val capped = Harness()
        capped.visible = true
        capped.dismissal.turned()
        capped.advance(KeyboardDismissal.TURN_CAP_MS)
        capped.visible = false
        capped.dismissal.hidden()
        capped.advance(60_000)
        assertEquals(1, capped.dismissed, "a report at the cap, with no refocus ever made, was taken for the turn's")
    }

    @Test
    fun `a report within the allowance after the refocus is the turn's and one after it is the person's`() {
        // Refocus at 1.25 s, the keyboard reported hidden 5.53 s after it: the latest of the 13 recorded turns.
        val inside = Harness()
        inside.visible = true
        inside.dismissal.turned()
        replay(
            inside,
            listOf(
                1_246L to { inside.dismissal.refocused() },
                1_246L + 5_526L to { inside.visible = false; inside.dismissal.hidden() },
            ),
        )
        assertEquals(0, inside.dismissed, "the latest recorded report, 5.5 s after the refocus, was taken for the person's")

        val after = Harness()
        after.visible = true
        after.dismissal.turned()
        replay(
            after,
            listOf(
                300L to { after.dismissal.refocused() },
                300L + KeyboardDismissal.AFTER_REFOCUS_MS to { after.visible = false; after.dismissal.hidden() },
            ),
        )
        assertEquals(1, after.dismissed, "a report as late after the refocus as the allowance was taken for the turn's")
    }

    @Test
    fun `the first refocus of a turn fixes the end of the window and a later focus change does not move it`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                200L to { h.dismissal.refocused() },
                4_000L to { h.dismissal.refocused() },
                200L + KeyboardDismissal.AFTER_REFOCUS_MS + 100L to { h.visible = false; h.dismissal.hidden() },
            ),
        )

        assertEquals(1, h.dismissed, "a later focus change moved the end of the window after the refocus")
    }

    // ---- a turn the page answers with no refocus has the floor and no more
    //
    // The app refocuses the WebView only for an editing host aimed at text. With the focus in Quick Open, the find box, the Search view's input or a rename
    // box the page answers no, nothing is refocused, and no report is waited for past the floor: the window of 2 s that every turn had before the refocus.

    @Test
    fun `a keyboard put away 3 s or 12 s after a turn that makes no refocus is the person's, and one inside the floor is the turn's`() {
        for (report in listOf(3_000L, 12_000L)) {
            val h = Harness()
            h.visible = true
            h.dismissal.turned()
            replay(h, listOf(40L to { h.dismissal.noRefocus() }, report to { h.visible = false; h.dismissal.hidden() }))
            assertEquals(
                1, h.dismissed,
                "a keyboard put away $report ms after a turn whose page makes no refocus was left to the turn, and the next touch raised it again",
            )
        }

        val inside = Harness()
        inside.visible = true
        inside.dismissal.turned()
        replay(
            inside,
            listOf(
                40L to { inside.dismissal.noRefocus() },
                KeyboardDismissal.TURN_FLOOR_MS - 1 to { inside.visible = false; inside.dismissal.hidden() },
            ),
        )
        assertEquals(0, inside.dismissed, "a report inside the floor of a turn that makes no refocus was taken for the person's")
    }

    @Test
    fun `the page's no counts when it comes before the check, and a new turn waits for its own answer`() {
        // A loaded page can answer after the report: the check, half a second after it, reads what is known by then.
        val late = Harness()
        late.visible = true
        late.dismissal.turned()
        replay(late, listOf(3_000L to { late.visible = false; late.dismissal.hidden() }, 3_200L to { late.dismissal.noRefocus() }))
        assertEquals(1, late.dismissed, "the page's no, known at the check, was not read")

        val again = Harness()
        again.visible = true
        again.dismissal.turned()
        replay(again, listOf(40L to { again.dismissal.noRefocus() }, 1_000L to { again.dismissal.turned() }, 4_000L to { again.visible = false; again.dismissal.hidden() }))
        assertEquals(0, again.dismissed, "the answer to the turn before ended the claim of a turn whose own answer had not come")
    }

    @Test
    fun `the editor's turn keeps its allowance, and a focus the WebView takes after the page's no still starts one`() {
        val editor = Harness()
        editor.visible = true
        editor.dismissal.turned()
        replay(editor, listOf(300L to { editor.dismissal.refocused() }, 3_000L to { editor.visible = false; editor.dismissal.hidden() }))
        assertEquals(0, editor.dismissed, "a report 2.7 s after the refocus of an editor's turn was taken for the person's")

        val past = Harness()
        past.visible = true
        past.dismissal.turned()
        replay(
            past,
            listOf(
                300L to { past.dismissal.refocused() },
                300L + KeyboardDismissal.AFTER_REFOCUS_MS to { past.visible = false; past.dismissal.hidden() },
            ),
        )
        assertEquals(1, past.dismissed, "a report as late after the refocus as the allowance was taken for the turn's")

        val anyway = Harness()
        anyway.visible = true
        anyway.dismissal.turned()
        replay(
            anyway,
            listOf(
                40L to { anyway.dismissal.noRefocus() },
                2_500L to { anyway.dismissal.refocused() },
                5_000L to { anyway.visible = false; anyway.dismissal.hidden() },
            ),
        )
        assertEquals(0, anyway.dismissed, "a focus the WebView took after the page's no did not start the allowance the system's hide is given")
    }

    // ---- a Back the activity sees is the person's, whenever it comes

    @Test
    fun `a Back seen two seconds after a turn keeps the keyboard down`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                500L to { h.dismissal.refocused() },
                2_000L to { h.dismissal.backSeen() },
                2_050L to { h.visible = false; h.dismissal.hidden() },
            ),
        )

        assertEquals(1, h.dismissed, "a keyboard closed with a Back the activity saw, 2 s after a turn, was left to the turn")
    }

    @Test
    fun `a Back seen BACK_MS or more before the report does not make it the person's`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                300L to { h.dismissal.refocused() },
                400L to { h.dismissal.backSeen() },
                400L + KeyboardDismissal.BACK_MS to { h.visible = false; h.dismissal.hidden() },
            ),
        )

        assertEquals(0, h.dismissed, "a Back BACK_MS before the report was taken for its cause")

        val within = Harness()
        within.visible = true
        within.dismissal.turned()
        replay(
            within,
            listOf(
                300L to { within.dismissal.refocused() },
                400L to { within.dismissal.backSeen() },
                400L + KeyboardDismissal.BACK_MS - 1 to { within.visible = false; within.dismissal.hidden() },
            ),
        )
        assertEquals(1, within.dismissed, "a report one millisecond inside BACK_MS after the Back was not taken for its result")
    }

    // The report follows a Back the activity saw by 0.03 to 1.29 s on Android 12 and 15 (a tap on the navigation bar's Back, the back gesture, an injected key) and
    // by 2.2 to 4.5 s on Android 10 at a host load of 99 to 191.
    @Test
    fun `the report that follows a Back by the longest distance measured is the person's`() {
        for (distance in listOf(31L, 1_285L, 2_200L, 4_500L)) {
            val h = Harness()
            h.visible = true
            h.dismissal.turned()
            replay(
                h,
                listOf(
                    300L to { h.dismissal.refocused() },
                    2_000L to { h.dismissal.backSeen() },
                    2_000L + distance to { h.visible = false; h.dismissal.hidden() },
                ),
            )
            assertEquals(1, h.dismissed, "a report $distance ms after a Back the activity saw was taken for the turn's")
        }
    }

    @Test
    fun `a Back seen before any turn leaves nothing to a later turn`() {
        val h = Harness()
        h.dismissal.backSeen()
        h.advance(5_000)
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                300L to { h.dismissal.refocused() },
                1_000L to { h.visible = false; h.dismissal.hidden() },
            ),
        )

        assertEquals(0, h.dismissed, "a Back seen long before the turn still dismissed the turn's loss")
    }

    // ---- a Back whose report is still waiting when the phone is turned

    @Test
    fun `a Back the activity saw just before a turn still makes the hide that follows it the person's`() {
        val h = Harness()
        h.visible = true
        h.dismissal.backSeen()
        h.advance(100)
        h.visible = false
        h.dismissal.hidden()
        // The half second the report waits is not over when the phone is turned.
        h.advance(200)
        h.dismissal.turned()
        h.advance(60_000)

        assertEquals(
            1,
            h.dismissed,
            "a keyboard closed with a Back the activity saw was not held, because the phone was turned while the report waited",
        )
    }

    // ---- a person's dismissal inside the window

    @Test
    fun `a person's dismissal inside the window ends the turn's claim on later reports`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                300L to { h.dismissal.refocused() },
                2_000L to { h.dismissal.backSeen() },
                2_050L to { h.visible = false; h.dismissal.hidden() },
                3_000L to { h.visible = true },
                4_000L to { h.visible = false; h.dismissal.hidden() },
            ),
        )

        assertEquals(2, h.dismissed, "a hide 4 s after the turn, after the person had put the keyboard away once, was taken for the turn's")
    }

    @Test
    fun `a new turn gives the turn's claim back after a person's dismissal`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                300L to { h.dismissal.refocused() },
                2_000L to { h.dismissal.backSeen() },
                2_050L to { h.visible = false; h.dismissal.hidden() },
            ),
        )
        assertEquals(1, h.dismissed, "a keyboard closed with a Back the activity saw was not dismissed")

        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                300L to { h.dismissal.refocused() },
                1_000L to { h.visible = false; h.dismissal.hidden() },
            ),
        )
        assertEquals(1, h.dismissed, "a report of a new turn was taken for the person's: the claim of the turn before outlived it")
    }

    // ---- the pair a back gesture makes in the window's touch events (a finger down at an edge, cancelled by the system): a Back the activity cannot see as a key
    //
    // Android 14 emulator, a real back gesture that started over the window: the page had its pointer down at the very edge and a cancel 19 ms later, and the keyboard
    // was reported hidden 169 to 333 ms after the cancel (5 of 5). Android 16 emulator, the same: the cancel 9 to 22 ms after the down, the report 173 to 537 ms after the
    // cancel (5 of 5). Nothing like it stood before the keyboard's own hide key or the navigation bar's hide button (3 of 3) or the system's hide or rebuild at a turn.

    @Test
    fun `a report after the pair of a back gesture is the person's inside the window, where the turn would own it, at every distance measured`() {
        for (distance in listOf(169L, 333L, 173L, 537L, KeyboardDismissal.EDGE_GESTURE_MS - 1)) {
            val h = Harness()
            h.visible = true
            h.dismissal.turned()
            replay(
                h,
                listOf(
                    300L to { h.dismissal.refocused() },
                    5_000L to { h.dismissal.edgeGesture() },
                    5_000L + distance to { h.visible = false; h.dismissal.hidden() },
                ),
            )
            assertEquals(1, h.dismissed, "a report $distance ms after the pair of a back gesture was taken for the turn's")
        }

        val without = Harness()
        without.visible = true
        without.dismissal.turned()
        replay(
            without,
            listOf(
                300L to { without.dismissal.refocused() },
                5_169L to { without.visible = false; without.dismissal.hidden() },
            ),
        )
        assertEquals(0, without.dismissed, "the same report with no pair before it is not the turn's: the pair is what changes the verdict, not the timing")
    }

    @Test
    fun `a pair EDGE_GESTURE_MS or more before the report does not make it the person's, and one just inside does`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                300L to { h.dismissal.refocused() },
                1_000L to { h.dismissal.edgeGesture() },
                1_000L + KeyboardDismissal.EDGE_GESTURE_MS to { h.visible = false; h.dismissal.hidden() },
            ),
        )
        assertEquals(0, h.dismissed, "a pair EDGE_GESTURE_MS before the report was taken for its cause")

        val within = Harness()
        within.visible = true
        within.dismissal.turned()
        replay(
            within,
            listOf(
                300L to { within.dismissal.refocused() },
                1_000L to { within.dismissal.edgeGesture() },
                1_000L + KeyboardDismissal.EDGE_GESTURE_MS - 1 to { within.visible = false; within.dismissal.hidden() },
            ),
        )
        assertEquals(1, within.dismissed, "a report one millisecond inside the allowance was not taken for the gesture's result")
    }

    @Test
    fun `a pair with no report after it ends nothing`() {
        // A gesture that is started and aborted makes the same pair and hides nothing: the turn keeps its claim.
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                300L to { h.dismissal.refocused() },
                1_000L to { h.visible = false; h.dismissal.hidden() },
                1_800L to { h.dismissal.edgeGesture() },
            ),
        )

        assertEquals(0, h.dismissed, "a pair with no report after it dismissed a keyboard")
        assertTrue(h.dismissal.describe().contains("claimEnded=false"), "a pair with no report after it ended the turn's claim on later reports")
    }

    @Test
    fun `a pair that comes after the report is not its cause`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                300L to { h.dismissal.refocused() },
                1_000L to { h.visible = false; h.dismissal.hidden() },
                1_200L to { h.dismissal.edgeGesture() },
            ),
        )

        assertEquals(0, h.dismissed, "a pair that came after the report and before its check made the report the person's: the report is judged where it arrived")
    }

    @Test
    fun `a keyboard put away with a back gesture ends the turn's claim on later reports`() {
        // No refocus: the turn's window then runs to its cap, 13 s, past the pair's allowance.
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                2_000L to { h.dismissal.edgeGesture() },
                2_200L to { h.visible = false; h.dismissal.hidden() },
                3_000L to { h.visible = true },
                2_000L + KeyboardDismissal.EDGE_GESTURE_MS + 300L to { h.visible = false; h.dismissal.hidden() },
            ),
        )

        assertEquals(2, h.dismissed, "a hide after the pair's allowance, after the person had put the keyboard away with the gesture, was taken for the turn's")
    }

    @Test
    fun `a pair within a second of the turn, before it or after it, is the turn's, and one a second away is not`() {
        // A turn of the phone may cancel a touch that is in progress, and a finger resting at the edge is such a touch: the pair it leaves, and the report the turn
        // makes just after it, are the turn's.
        for ((offset, expectPersons) in listOf(0L to false, 500L to false, KeyboardDismissal.EDGE_TURN_GUARD_MS - 1 to false, KeyboardDismissal.EDGE_TURN_GUARD_MS to true)) {
            val after = Harness()
            after.visible = true
            after.dismissal.turned()
            replay(
                after,
                listOf(
                    offset to { after.dismissal.edgeGesture() },
                    offset + 200L to { after.visible = false; after.dismissal.hidden() },
                ),
            )
            assertEquals(if (expectPersons) 1 else 0, after.dismissed, "a pair $offset ms after the turn, with the report 200 ms later: wrong verdict")
        }

        for (offset in listOf(300L, KeyboardDismissal.EDGE_TURN_GUARD_MS - 1, KeyboardDismissal.EDGE_TURN_GUARD_MS)) {
            val before = Harness()
            before.visible = true
            before.dismissal.edgeGesture()
            before.advance(offset)
            before.dismissal.turned()
            replay(before, listOf(400L to { before.visible = false; before.dismissal.hidden() }))
            assertEquals(0, before.dismissed, "a pair $offset ms before the turn must be cleared by the turn and not count after it")
        }
    }

    @Test
    fun `the allowance after a pair covers the ~1 s distance measured on a loaded phone without lingering for unrelated later hides`() {
        assertTrue(
            KeyboardDismissal.EDGE_GESTURE_MS in 1_800L..2_500L,
            "the edge-gesture allowance should cover the ~1 s measured distance on a loaded device without lingering across later unrelated hides",
        )
        assertTrue(
            KeyboardDismissal.EDGE_TURN_GUARD_MS in 100L until KeyboardDismissal.EDGE_GESTURE_MS,
            "the turn's guard is shorter than a cancel could follow a rotation, or as long as the allowance",
        )
    }

    @Test
    fun `shown clears stale back and edge markers so a later hide is not blamed on them`() {
        val h = Harness()
        h.visible = true
        h.dismissal.backSeen()
        h.dismissal.shown()
        h.advance(100L)
        h.dismissal.turned()
        h.dismissal.edgeGesture()
        h.dismissal.shown()
        h.advance(1_500L)
        h.visible = false
        h.dismissal.hidden()
        h.advance(KeyboardDismissal.DELAY_MS + 1_000L)
        assertEquals(0, h.dismissed, "markers recorded before shown() must not explain a later hide")
    }

    @Test
    fun `explicit person dismissal callback runs only for backSeen and edgeGesture, not default dismissals`() {
        fun scenario(kind: String): Pair<Int, Int> {
            var now = 1_000L
            var visible = true
            var defaultDismissed = 0
            var explicitDismissed = 0
            val pending = mutableListOf<Pair<Runnable, Long>>()
            val d = KeyboardDismissal(
                schedule = { r, ms -> pending += r to (now + ms) },
                cancel = { r -> pending.removeAll { it.first === r } },
                now = { now },
                imeVisible = { visible },
                onDismissed = { defaultDismissed += 1 },
                onExplicitDismissed = { explicitDismissed += 1 },
            )
            when (kind) {
                "back" -> {
                    d.backSeen()
                    now += 50L
                    visible = false
                    d.hidden()
                }
                "edge" -> {
                    d.edgeDown()
                    now += 50L
                    d.edgeGesture()
                    now += 50L
                    visible = false
                    d.hidden()
                }
                "default" -> {
                    visible = false
                    d.hidden()
                }
            }
            val until = now + 5_000L
            while (true) {
                val next = pending.filter { it.second <= until }.minByOrNull { it.second } ?: break
                now = next.second
                pending.remove(next)
                next.first.run()
            }
            return explicitDismissed to defaultDismissed
        }

        assertEquals(1 to 0, scenario("back"), "seen Back must invoke explicit person dismissal")
        assertEquals(1 to 0, scenario("edge"), "edge gesture pair must invoke explicit person dismissal")
        assertEquals(0 to 1, scenario("default"), "default dismissal with no seen Back or edge pair must stay non-explicit")
    }

    @Test
    fun `edge pair completing within DELAY_MS after hidden still counts as an explicit person dismissal`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                363L to { h.dismissal.refocused() },
                6_300L to { h.dismissal.edgeDown() },
                6_395L to { h.visible = false; h.dismissal.hidden() },
                6_472L to { h.dismissal.edgeGesture() },
            ),
        )
        assertEquals(1, h.dismissed, "edge cancel 77 ms after hidden must explain the dismissal")

        var explicit = 0
        var now = 1_000L
        var visible = true
        val pending = mutableListOf<Pair<Runnable, Long>>()
        val d = KeyboardDismissal(
            schedule = { r, ms -> pending += r to (now + ms) },
            cancel = { r -> pending.removeAll { it.first === r } },
            now = { now },
            imeVisible = { visible },
            onDismissed = {},
            onExplicitDismissed = { explicit += 1 },
        )
        d.turned()
        now += 363L
        d.refocused()
        now = 7_300L
        d.edgeDown()
        now = 7_395L
        visible = false
        d.hidden()
        now = 7_472L
        d.edgeGesture()
        while (pending.isNotEmpty()) {
            val next = pending.minByOrNull { it.second }!!
            now = next.second
            pending.remove(next)
            next.first.run()
        }
        assertEquals(1, explicit, "late edge cancel after hidden must invoke onExplicitDismissed")
    }

    @Test
    fun `an edge touch still down at judgment with no cancel does not explain a hide, and a later lift leaves an earlier pair's down intact`() {
        val stillDown = Harness()
        stillDown.visible = true
        stillDown.dismissal.turned()
        replay(
            stillDown,
            listOf(
                300L to { stillDown.dismissal.refocused() },
                1_500L to { stillDown.dismissal.edgeDown() },
                1_600L to { stillDown.visible = false; stillDown.dismissal.hidden() },
            ),
        )
        assertEquals(0, stillDown.dismissed, "a finger still down at the edge with no system cancel must not explain a hide")

        val liftedBeforeCheck = Harness()
        liftedBeforeCheck.visible = true
        liftedBeforeCheck.dismissal.turned()
        replay(
            liftedBeforeCheck,
            listOf(
                300L to { liftedBeforeCheck.dismissal.refocused() },
                1_500L to { liftedBeforeCheck.dismissal.edgeDown() },
                1_600L to { liftedBeforeCheck.visible = false; liftedBeforeCheck.dismissal.hidden() },
                1_750L to { liftedBeforeCheck.dismissal.edgeLifted() },
            ),
        )
        assertEquals(0, liftedBeforeCheck.dismissed, "an edge touch lifted before judgment must not dismiss")

        val cancelledThenOtherTap = Harness()
        cancelledThenOtherTap.visible = true
        cancelledThenOtherTap.dismissal.turned()
        replay(
            cancelledThenOtherTap,
            listOf(
                300L to { cancelledThenOtherTap.dismissal.refocused() },
                800L to { cancelledThenOtherTap.dismissal.edgeDown() },
                1_050L to { cancelledThenOtherTap.dismissal.edgeGesture() },
                1_100L to { cancelledThenOtherTap.dismissal.edgeLifted() },
                1_600L to { cancelledThenOtherTap.visible = false; cancelledThenOtherTap.dismissal.hidden() },
            ),
        )
        assertEquals(
            0,
            cancelledThenOtherTap.dismissed,
            "a later edgeLifted() must not erase an earlier completed gesture's down timestamp inside EDGE_TURN_GUARD_MS",
        )
    }

    // ---- the two kinds of keyboard that is not on the screen after a turn, kept apart
    //
    // Kind 1, the system's no-editor hide (Android 14 emulator, Gboard 12.4): the system's log names the reason, HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR, and the hide
    // comes 1.0 to 2.1 s after the app's refocus; the key row's report follows it by 1.6 to 4.4 s on a loaded host.
    // Kind 2, the keyboard's own window gone while it rebuilds for the new configuration (Android 16 emulator, Gboard 15.9, from a dumpsys input_method start input
    // history over 21 runs and 41 hidden reports): no hide request, no start of input by a window focus gain, the key row's insets read "not visible" for 2 to 10 s at
    // each turn, no refocus, and the keyboard returns by itself.

    // The five system hides measured on an Android 14 emulator, each with the ages its debug line gave at the report: turn, refocus.
    @Test
    fun `kind 1, the five system hides measured on an Android 14 emulator, four are the turn's and one past the allowance is the person's`() {
        val cases = listOf(
            Triple("P03", 7_140L, 6_090L),
            Triple("P06", 5_846L, 5_586L),
            Triple("KB02", 4_815L, 3_305L),
            Triple("KB03 landscape", 5_073L, 3_932L),
            Triple("KB03 portrait", 4_805L, 4_249L),
        )
        for ((name, turnAge, refocusAge) in cases) {
            val h = Harness()
            h.visible = true
            h.dismissal.turned()
            replay(
                h,
                listOf(
                    turnAge - refocusAge to { h.dismissal.refocused() },
                    turnAge to { h.visible = false; h.dismissal.hidden() },
                ),
            )
            if (refocusAge < KeyboardDismissal.AFTER_REFOCUS_MS) {
                assertEquals(0, h.dismissed, "$name: a report $refocusAge ms after the refocus, inside the allowance, was taken for the person's")
            } else {
                // The one case the allowance misses, by 90 ms, on a host at a load of 99 to 198 where the report came 4.45 s after the system's request.
                assertEquals(1, h.dismissed, "$name: the report past the allowance was not taken for the person's")
            }
        }
    }

    @Test
    fun `kind 2, the keyboard rebuilding for the new configuration and back by itself, nothing is held`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                59L to { h.visible = false; h.dismissal.hidden() },
                2_500L to { h.visible = true },
            ),
        )

        assertEquals(0, h.dismissed, "the keyboard's rebuild for the new configuration was taken for the person putting it away")
    }

    @Test
    fun `kind 2, the keyboard still rebuilding through a turn back, nothing is held`() {
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                59L to { h.visible = false; h.dismissal.hidden() },
                8_000L to { h.dismissal.turned() },
                9_000L to { h.visible = true },
            ),
        )

        assertEquals(0, h.dismissed, "the keyboard's rebuild was taken for the person putting it away")
    }

    // ---- what the debug line says

    @Test
    fun `the debug line carries ages and flags and nothing else`() {
        val h = Harness()
        assertEquals(
            "turnAge=none refocusAge=none backAge=none edgeAge=none upAtTurn=false claimEnded=false refocusComing=true",
            h.dismissal.describe(),
        )

        h.visible = true
        h.dismissal.turned()
        h.advance(300)
        h.dismissal.refocused()
        h.advance(700)
        h.dismissal.backSeen()
        h.advance(100)
        h.dismissal.edgeGesture()
        h.advance(150)
        assertEquals(
            "turnAge=1250ms refocusAge=950ms backAge=250ms edgeAge=150ms upAtTurn=true claimEnded=false refocusComing=true",
            h.dismissal.describe(),
        )

        h.dismissal.noRefocus()
        assertTrue(h.dismissal.describe().endsWith(" claimEnded=false refocusComing=false"), "the page's no is not in the line a run on a device is read from")
    }

    // ---- Multi-API replay and boundary checks

    private class SdkHarness(val sdk: Int) {
        var now = 1_000L
        var visible = false
        var dismissed = 0
        private val pending = mutableListOf<Pair<Runnable, Long>>()

        val dismissal: KeyboardDismissal = KeyboardDismissal(
            schedule = { task, delayMs -> pending += task to (now + delayMs) },
            cancel = { task -> pending.removeAll { it.first === task } },
            now = { now },
            imeVisible = { visible },
            onDismissed = { dismissed += 1 },
        )

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

        fun replay(events: List<Pair<Long, () -> Unit>>) {
            val start = now
            for ((at, event) in events.sortedBy { it.first }) {
                advance(start + at - now)
                event()
            }
            advance(60_000)
        }
    }

    @Test
    fun `a person's first Back key or edge gesture after a turn keeps the keyboard down across API levels`() {
        // API 29, 3-button Back (including hidden arriving 2 ms before turned).
        val r1 = SdkHarness(29).apply { visible = true; dismissal.turned() }
        r1.replay(
            listOf(
                164L to { r1.visible = false; r1.dismissal.hidden() },
                1_779L to { r1.dismissal.refocused() },
                2_747L to { r1.visible = true; r1.dismissal.shown() },
                6_491L to { r1.dismissal.backSeen() },
                6_516L to { r1.visible = false; r1.dismissal.hidden() },
            ),
        )
        assertEquals(1, r1.dismissed, "API 29 Back after turn hide: first Back did not hold")

        val r3 = SdkHarness(29).apply { visible = false; dismissal.hidden() }
        r3.advance(2)
        r3.dismissal.turned()
        r3.replay(
            listOf(
                58L to { r3.dismissal.refocused() },
                526L to { r3.visible = true; r3.dismissal.shown() },
                4_109L to { r3.dismissal.backSeen() },
                4_240L to { r3.visible = false; r3.dismissal.hidden() },
            ),
        )
        assertEquals(1, r3.dismissed, "API 29 Back after pre-turn hide: first Back did not hold")

        // API 33: edge cancel inside turn window.
        for ((name, turnAge, refocusAge) in listOf(
            Triple("API 33 edge cancel case 1", 6_342L, 5_686L),
            Triple("API 33 edge cancel case 2", 5_425L, 5_138L),
            Triple("API 33 edge cancel case 3", 3_722L, 3_581L),
        )) {
            val g = SdkHarness(33).apply { visible = true; dismissal.turned() }
            g.replay(
                listOf(
                    turnAge - refocusAge to { g.dismissal.refocused() },
                    turnAge - 250L to { g.dismissal.edgeGesture() },
                    turnAge to { g.visible = false; g.dismissal.hidden() },
                ),
            )
            assertEquals(1, g.dismissed, "$name: first gesture Back with edge pair did not write the page hold")
        }

        // API 34: edge cancel near refocus window end.
        val b66 = SdkHarness(34).apply { visible = true; dismissal.turned() }
        b66.replay(
            listOf(
                589L to { b66.dismissal.refocused() },
                6_290L to { b66.dismissal.edgeGesture() },
                6_556L to { b66.visible = false; b66.dismissal.hidden() },
            ),
        )
        assertEquals(1, b66.dismissed, "API 34 edge cancel: first edge Back did not hold")

        // API 36 (including edge Back after an earlier turn hide and return) and API 37.
        val b50a = SdkHarness(36).apply { visible = true; dismissal.turned() }
        b50a.replay(
            listOf(
                89L to { b50a.visible = false; b50a.dismissal.hidden() },
                2_500L to { b50a.visible = true; b50a.dismissal.shown() },
                5_372L to { b50a.dismissal.edgeGesture() },
                5_727L to { b50a.visible = false; b50a.dismissal.hidden() },
            ),
        )
        assertEquals(1, b50a.dismissed, "API 36 after earlier turn hide: edge Back did not write the page hold")

        for ((name, sdk, turnAge) in listOf(
            Triple("API 36 edge cancel after refocus", 36, 4_815L),
            Triple("API 37 edge cancel after refocus", 37, 3_682L),
        )) {
            val b = SdkHarness(sdk).apply { visible = true; dismissal.turned() }
            b.replay(
                listOf(
                    250L to { b.dismissal.refocused() },
                    turnAge - 260L to { b.dismissal.edgeGesture() },
                    turnAge to { b.visible = false; b.dismissal.hidden() },
                ),
            )
            assertEquals(1, b.dismissed, "$name: first edge Back did not hold")
        }
    }

    @Test
    fun `unseen gesture or hide key inside the turn window writes no page hold on any API level`() {
        // Without a seen Back key or edge cancel inside the turn window, no hold is written on any API level.
        val chev = SdkHarness(33).apply { visible = true; dismissal.turned() }
        chev.replay(
            listOf(
                235L to { chev.dismissal.refocused() },
                3_701L to { chev.visible = false; chev.dismissal.hidden() },
            ),
        )
        assertEquals(
            0,
            chev.dismissed,
            "unseen hide inside the turn window must not write a page hold on any API level",
        )
    }

    @Test
    fun `late system hide 6090 ms after refocus is still claimed by the turn`() {
        // AFTER_REFOCUS_MS is 6.5 s so a loaded report at 6090 ms after refocus stays claimed by the turn.
        val p03 = SdkHarness(34).apply { visible = true; dismissal.turned() }
        p03.replay(
            listOf(
                1_050L to { p03.dismissal.refocused() },
                7_140L to { p03.visible = false; p03.dismissal.hidden() },
            ),
        )
        assertEquals(
            0,
            p03.dismissed,
            "6090 ms after refocus is inside the 6.5 s allowance and still claimed by the turn",
        )
    }

    @Test
    fun `slow keyboard rebuild reported at 12084 ms without a refocus is still claimed under the turn cap`() {
        // TURN_CAP_MS is 13 s so a loaded keyboard rebuild reported at 12084 ms without a refocus stays claimed by the turn.
        val ak11 = Harness()
        ak11.visible = true
        ak11.dismissal.turned()
        replay(
            ak11,
            listOf(
                12_084L to { ak11.visible = false; ak11.dismissal.hidden() },
            ),
        )
        assertEquals(
            0,
            ak11.dismissed,
            "12084 ms without a refocus is inside the 13 s turn cap and still claimed by the turn",
        )
    }

    @Test
    fun `an edge cancel shortly before a turn does not explain a hide after the turn`() {
        // At t=0 an unrelated edge gesture records edgeGesture() with no hide; at t=1500ms a turn starts and clears edgeAt,
        // so at t=1700ms the turn's own hide stays claimed by the turn.
        val h = Harness()
        h.visible = true
        h.dismissal.edgeGesture()
        h.advance(1_500)
        h.dismissal.turned()
        replay(
            h,
            listOf(
                100L to { h.dismissal.refocused() },
                200L to { h.visible = false; h.dismissal.hidden() },
            ),
        )
        assertEquals(0, h.dismissed, "an edge pair before the turn must not explain a hide after the turn")
    }

    @Test
    fun `a Back key or edge cancel is used up by its own hide and cleared when reopened or turned`() {
        for (useEdge in listOf(false, true)) {
            val h = Harness()
            h.visible = true
            // 1. Person genuinely closes the keyboard with Back (key or edge gesture).
            if (useEdge) h.dismissal.edgeGesture() else h.dismissal.backSeen()
            h.advance(100)
            h.visible = false
            h.dismissal.hidden()
            h.advance(KeyboardDismissal.DELAY_MS)
            assertEquals(1, h.dismissed, "setup: first Back should dismiss once")

            // 2. Person taps the editor 600 ms later to raise the keyboard again, then turns the phone 1 s after that.
            h.advance(600)
            h.visible = true
            h.advance(1_000)
            h.dismissal.turned()
            replay(
                h,
                listOf(
                    150L to { h.dismissal.refocused() },
                    250L to { h.visible = false; h.dismissal.hidden() },
                ),
            )
            val kind = if (useEdge) "edgeGesture" else "backSeen"
            assertEquals(
                1,
                h.dismissed,
                "$kind: the earlier Back was used up by its own hide and cleared at reopen/turn, so it did not hold the reopened keyboard",
            )
        }
    }

    @Test
    fun `an edge cancel more than 2 s before a delayed system hide does not explain that hide`() {
        // EDGE_GESTURE_MS is 2000 ms, so an edge cancel at turn + 1100ms expires before a delayed system hide at turn + 3800ms.
        val h = Harness()
        h.visible = true
        h.dismissal.turned()
        replay(
            h,
            listOf(
                400L to { h.dismissal.refocused() },
                1_100L to { h.dismissal.edgeGesture() },
                3_800L to { h.visible = false; h.dismissal.hidden() },
            ),
        )
        assertEquals(0, h.dismissed, "an edge touch at 1.1 s must not explain a delayed hide at 3.8 s")
    }

    @Test
    fun `a completed edge pair counts only when the cancel follows the down within EDGE_PAIR_MS`() {
        fun runPair(downToCancelMs: Long, cancelAfterHide: Boolean = false): Pair<Int, Int> {
            var now = 1_000L
            var visible = true
            var dismissed = 0
            var explicit = 0
            val pending = mutableListOf<Pair<Runnable, Long>>()
            val d = KeyboardDismissal(
                schedule = { r, ms -> pending += r to (now + ms) },
                cancel = { r -> pending.removeAll { it.first === r } },
                now = { now },
                imeVisible = { visible },
                onDismissed = { dismissed += 1 },
                onExplicitDismissed = { explicit += 1 },
            )
            fun advanceTo(target: Long) {
                while (true) {
                    val next = pending.filter { it.second <= target }.minByOrNull { it.second } ?: break
                    now = next.second
                    pending.remove(next)
                    next.first.run()
                }
                now = target
            }

            d.turned()
            advanceTo(1_400L)
            d.refocused()
            advanceTo(2_200L)
            d.edgeDown()
            if (cancelAfterHide) {
                advanceTo(2_200L + downToCancelMs - 200L)
                visible = false
                d.hidden()
                advanceTo(2_200L + downToCancelMs)
                d.edgeGesture()
            } else {
                advanceTo(2_200L + downToCancelMs)
                d.edgeGesture()
                advanceTo(2_200L + downToCancelMs + 100L)
                visible = false
                d.hidden()
            }
            advanceTo(10_000L)
            return explicit to dismissed
        }

        assertEquals(1 to 0, runPair(172L), "measured 172 ms down-to-cancel pair must count as explicit")
        assertEquals(1 to 0, runPair(KeyboardDismissal.EDGE_PAIR_MS - 1), "down-to-cancel 1 ms inside EDGE_PAIR_MS must count as explicit")
        assertEquals(0 to 0, runPair(KeyboardDismissal.EDGE_PAIR_MS), "down-to-cancel at EDGE_PAIR_MS must not count as a back gesture")
        assertEquals(0 to 0, runPair(3_200L, cancelAfterHide = true), "a 3.2 s bezel rest cancelled after a turn hide must stay claimed by the turn")
    }

    @Test
    fun `an uninterrupted hide keeps the time of its first report across duplicate hidden dispatches`() {
        var now = 1_000L
        var visible = true
        var dismissed = 0
        var explicit = 0
        val pending = mutableListOf<Pair<Runnable, Long>>()
        val d = KeyboardDismissal(
            schedule = { r, ms -> pending += r to (now + ms) },
            cancel = { r -> pending.removeAll { it.first === r } },
            now = { now },
            imeVisible = { visible },
            onDismissed = { dismissed += 1 },
            onExplicitDismissed = { explicit += 1 },
        )
        fun advanceTo(target: Long) {
            while (true) {
                val next = pending.filter { it.second <= target }.minByOrNull { it.second } ?: break
                now = next.second
                pending.remove(next)
                next.first.run()
            }
            now = target
        }

        d.turned()
        advanceTo(1_400L)
        d.refocused()
        // First hidden report of the turn arrives at turn + 1.7 s; keyboard stays down.
        advanceTo(2_700L)
        visible = false
        d.hidden()
        // While the keyboard is already hidden, an unrelated edge touch and cancel occur at turn + 1.85..1.87 s.
        advanceTo(2_850L)
        d.edgeDown()
        advanceTo(2_870L)
        d.edgeGesture()
        // Duplicate hidden dispatches arrive both while the first check is pending and after it has run,
        // including past AFTER_REFOCUS_MS, with no shown() in between.
        advanceTo(3_100L)
        d.hidden()
        advanceTo(1_400L + KeyboardDismissal.AFTER_REFOCUS_MS + 200L)
        d.hidden()
        advanceTo(12_000L)

        assertEquals(0, explicit, "an edge cancel after the keyboard was already hidden must not claim a duplicate hidden report")
        assertEquals(0, dismissed, "duplicate hidden reports of an uninterrupted turn hide must remain claimed by the turn")
    }

    @Test
    fun `a Back whose hidden report arrives after a turn is the person's within BACK_MS of the Back, and not after`() {
        // Measured on Android 10 with three buttons: the Back with the keyboard up, the turn handled 1,589 ms later by a main thread the rotation kept busy,
        // and the hidden report 202 ms after the turn. The turn does not forget the Back; what bounds it is the time from the Back to its report.
        for ((gapToTurnMs, expected) in listOf(
            150L to 1,
            1_589L to 1,
            KeyboardDismissal.BACK_MS - 203L to 1,
            KeyboardDismissal.BACK_MS - 202L to 0,
            KeyboardDismissal.BACK_MS to 0,
        )) {
            val h = ExplicitHarness()
            h.advanceTo(40_000L)
            h.dismissal.backSeen()
            h.advanceTo(40_000L + gapToTurnMs)
            assertTrue(h.dismissal.turned(), "the report is still in flight, so the keyboard counts as up at the turn")
            h.advanceTo(h.now + 202L)
            h.visible = false
            h.dismissal.hidden()
            h.advanceTo(h.now + 60_000L)
            assertEquals(expected to 0, h.explicit to h.dismissed, "gapToTurnMs=$gapToTurnMs")
        }
    }

    @Test
    fun `explicit dismissal passes the age of the Back or edge gesture at judgment`() {
        fun recordExplicitAge(block: (KeyboardDismissal, (Long) -> Unit, (Boolean) -> Unit) -> Unit): Long? {
            var now = 1_000L
            var visible = true
            var reportedAge: Long? = null
            val pending = mutableListOf<Pair<Runnable, Long>>()
            val d = KeyboardDismissal(
                schedule = { r, ms -> pending += r to (now + ms) },
                cancel = { r -> pending.removeAll { it.first === r } },
                now = { now },
                imeVisible = { visible },
                onDismissed = {},
                onExplicitDismissed = { ageMs -> reportedAge = ageMs },
            )
            fun advanceTo(target: Long) {
                while (true) {
                    val next = pending.filter { it.second <= target }.minByOrNull { it.second } ?: break
                    now = next.second
                    pending.remove(next)
                    next.first.run()
                }
                now = target
            }
            block(d, ::advanceTo) { visible = it }
            return reportedAge
        }

        // Aborted peek at t=1020 before Quick Open focused at t=1400 and dipped at t=1700:
        // judgment runs at t=2200 with gesture age 1180 ms (> focus age 800 ms on the page).
        val preFocusPeekAge = recordExplicitAge { d, advanceTo, setVisible ->
            d.edgeDown()
            advanceTo(1_020L)
            d.edgeGesture()
            advanceTo(1_700L)
            setVisible(false)
            d.hidden()
            advanceTo(3_000L)
        }
        assertEquals(1_180L, preFocusPeekAge)

        // Real edge Back at t=1920 after Quick Open focused at t=1400, hidden at t=2100:
        // judgment runs at t=2600 with gesture age 680 ms (<= focus age 1200 ms on the page).
        val postFocusEdgeAge = recordExplicitAge { d, advanceTo, setVisible ->
            advanceTo(1_900L)
            d.edgeDown()
            advanceTo(1_920L)
            d.edgeGesture()
            advanceTo(2_100L)
            setVisible(false)
            d.hidden()
            advanceTo(3_000L)
        }
        assertEquals(680L, postFocusEdgeAge)

        // Real Back key at t=1900, hidden at t=2100: judgment runs at t=2600 with Back age 700 ms.
        val postFocusBackAge = recordExplicitAge { d, advanceTo, setVisible ->
            advanceTo(1_900L)
            d.backSeen()
            advanceTo(2_100L)
            setVisible(false)
            d.hidden()
            advanceTo(3_000L)
        }
        assertEquals(700L, postFocusBackAge)
    }

    private class ExplicitHarness(var visible: Boolean = true) {
        var now = 1_000L
        var explicit = 0
        var dismissed = 0
        private val pending = mutableListOf<Pair<Runnable, Long>>()

        val dismissal: KeyboardDismissal = KeyboardDismissal(
            schedule = { r, ms -> pending += r to (now + ms) },
            cancel = { r -> pending.removeAll { it.first === r } },
            now = { now },
            imeVisible = { visible },
            onDismissed = { dismissed += 1 },
            onExplicitDismissed = { explicit += 1 },
        )

        fun advanceTo(target: Long) {
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
    fun `edge gesture pair span is taken from event timestamps across delivery stalls while keeping receipt time for hides`() {
        // Stalled delivery: 600 ms on receipt clocks between edgeDown and edgeGesture,
        // while the event timestamps report a genuine 120 ms swipe inside EDGE_PAIR_MS.
        val stalledSwipe = ExplicitHarness()
        stalledSwipe.dismissal.turned()
        stalledSwipe.advanceTo(1_400L)
        stalledSwipe.dismissal.refocused()
        stalledSwipe.advanceTo(2_200L)
        stalledSwipe.dismissal.edgeDown()
        stalledSwipe.advanceTo(2_800L)
        stalledSwipe.dismissal.edgeGesture(pairSpanMs = 120L)
        stalledSwipe.advanceTo(2_900L)
        stalledSwipe.visible = false
        stalledSwipe.dismissal.hidden()
        stalledSwipe.advanceTo(10_000L)
        assertEquals(1 to 0, stalledSwipe.explicit to stalledSwipe.dismissed)

        // Delayed down delivery on a slow 600 ms bezel rest: receipt gap is 20 ms,
        // but event pair span is 600 ms (>= EDGE_PAIR_MS), so the turn keeps the hide.
        val delayedDown = ExplicitHarness()
        delayedDown.dismissal.turned()
        delayedDown.advanceTo(1_400L)
        delayedDown.dismissal.refocused()
        delayedDown.advanceTo(2_780L)
        delayedDown.dismissal.edgeDown()
        delayedDown.advanceTo(2_800L)
        delayedDown.dismissal.edgeGesture(pairSpanMs = 600L)
        delayedDown.advanceTo(2_900L)
        delayedDown.visible = false
        delayedDown.dismissal.hidden()
        delayedDown.advanceTo(10_000L)
        assertEquals(0 to 0, delayedDown.explicit to delayedDown.dismissed)

        // Unpilfered flick recorded within a turn window claims its following hide,
        // while a flick followed by no hide claims nothing.
        val flickFollowedByHide = ExplicitHarness()
        flickFollowedByHide.dismissal.turned()
        flickFollowedByHide.advanceTo(1_807L)
        flickFollowedByHide.dismissal.refocused()
        flickFollowedByHide.advanceTo(6_600L)
        flickFollowedByHide.dismissal.edgeDown()
        flickFollowedByHide.advanceTo(6_720L)
        flickFollowedByHide.dismissal.edgeGesture(pairSpanMs = 120L)
        flickFollowedByHide.advanceTo(6_758L)
        flickFollowedByHide.visible = false
        flickFollowedByHide.dismissal.hidden()
        flickFollowedByHide.advanceTo(10_000L)
        assertEquals(1 to 0, flickFollowedByHide.explicit to flickFollowedByHide.dismissed)

        val isolatedFlick = ExplicitHarness()
        isolatedFlick.dismissal.turned()
        isolatedFlick.advanceTo(1_807L)
        isolatedFlick.dismissal.refocused()
        isolatedFlick.advanceTo(3_200L)
        isolatedFlick.dismissal.edgeDown()
        isolatedFlick.advanceTo(3_320L)
        isolatedFlick.dismissal.edgeGesture(pairSpanMs = 120L)
        isolatedFlick.advanceTo(10_000L)
        assertEquals(0 to 0, isolatedFlick.explicit to isolatedFlick.dismissed)
    }

    @Test
    fun `a hide that ends with the keyboard shown again does not lock its first time against a later Back or edge pair`() {
        // The extra key row reports every change of the keyboard's visibility, so the keyboard comes back through shown(), which ends the hide.
        val afterBack = ExplicitHarness()
        afterBack.dismissal.turned()
        afterBack.advanceTo(1_400L)
        afterBack.dismissal.refocused()
        // Spurious early hidden report while keyboard still appears visible before later real Back.
        afterBack.advanceTo(2_200L)
        afterBack.dismissal.hidden()
        afterBack.advanceTo(3_500L)
        afterBack.visible = true
        afterBack.dismissal.shown()
        afterBack.dismissal.backSeen()
        afterBack.advanceTo(3_600L)
        afterBack.visible = false
        afterBack.dismissal.hidden()
        afterBack.advanceTo(10_000L)
        assertEquals(1 to 0, afterBack.explicit to afterBack.dismissed)

        val afterEdgeDown = ExplicitHarness()
        afterEdgeDown.dismissal.turned()
        afterEdgeDown.advanceTo(1_400L)
        afterEdgeDown.dismissal.refocused()
        afterEdgeDown.advanceTo(2_200L)
        afterEdgeDown.dismissal.hidden()
        afterEdgeDown.advanceTo(3_500L)
        afterEdgeDown.visible = true
        afterEdgeDown.dismissal.shown()
        afterEdgeDown.dismissal.edgeDown()
        afterEdgeDown.advanceTo(3_620L)
        afterEdgeDown.dismissal.edgeGesture(pairSpanMs = 120L)
        afterEdgeDown.advanceTo(3_700L)
        afterEdgeDown.visible = false
        afterEdgeDown.dismissal.hidden()
        afterEdgeDown.advanceTo(10_000L)
        assertEquals(1 to 0, afterEdgeDown.explicit to afterEdgeDown.dismissed)
    }

    @Test
    fun `characterization - a Back that did not hide the keyboard claims the hide of a turn reported within BACK_MS of it`() {
        // The cost of keeping a Back across a turn: a Back the keyboard used for something of its own (closing one of its panels), then a turn whose
        // own hide is reported within five seconds of that Back.
        for ((turnAfterBackMs, expected) in listOf(1_200L to 1, 3_000L to 1, KeyboardDismissal.BACK_MS - 200L to 0)) {
            val h = ExplicitHarness()
            h.dismissal.backSeen()
            h.advanceTo(h.now + turnAfterBackMs)
            h.dismissal.turned()
            h.advanceTo(h.now + 200L)
            h.visible = false
            h.dismissal.hidden()
            h.advanceTo(h.now + 60_000L)
            assertEquals(expected to 0, h.explicit to h.dismissed, "turnAfterBackMs=$turnAfterBackMs")
        }
    }

    // ---- a back gesture, its hide, then a turn inside the half second the report waits
    //
    // Where the platform hands the activity no Back key (Android 14 and 16 with gesture navigation) the pair of the gesture is all there is of a Back. A turn that
    // comes after the report and before its check used to clear the pair, and the check then found the report owned by the turn: nothing was held and the next
    // scroll raised the keyboard (three of three on an Android 16 emulator, and three of three on an Android 14 one). Times below are milliseconds after the pair.

    /**
     * A Back by gesture: [previousTurnAgeMs] before the pair a turn that found the keyboard up and refocused 150 ms later (null: no turn lately), the pair at
     * 0 after a swipe of [spanMs], and the hide reported [reportMs] after it. Leaves the clock [turnMs] after the pair, where the caller turns the phone.
     */
    private fun gestureThenHide(reportMs: Long, turnMs: Long, previousTurnAgeMs: Long? = 6_500L, spanMs: Long = 20L): ExplicitHarness {
        val zero = 40_000L
        val h = ExplicitHarness()
        if (previousTurnAgeMs != null) {
            h.advanceTo(zero - previousTurnAgeMs)
            h.dismissal.turned()
            h.advanceTo(zero - previousTurnAgeMs + 150L)
            h.dismissal.refocused()
        }
        h.advanceTo(zero - spanMs)
        h.dismissal.edgeDown()
        h.advanceTo(zero)
        h.dismissal.edgeGesture(pairSpanMs = spanMs)
        h.advanceTo(zero + reportMs)
        h.visible = false
        h.dismissal.hidden()
        h.advanceTo(zero + turnMs)
        return h
    }

    @Test
    fun `a back gesture and its hide, then a turn before the check, is the person's at the turn and the turn starts with the keyboard down`() {
        // Offsets of the pair, the report and the turn as measured, in milliseconds after the pair: Android 16 (HeliBoard) 242 and 624; Android 14 (Gboard 12.4)
        // 135 and 480, 162 and 619, 121 and 334.
        for ((name, report, turn, previousAge) in listOf(
            Measured("Android 16", 242L, 624L, 6_526L),
            Measured("Android 14, run 1", 135L, 480L, null),
            Measured("Android 14, run 2", 162L, 619L, 26_475L),
            Measured("Android 14, run 3", 121L, 334L, 27_044L),
        )) {
            val h = gestureThenHide(report, turn, previousAge)
            val up = h.dismissal.turned()
            assertEquals(1, h.explicit, "$name: the person's Back was not told to the page at the turn")
            assertEquals(0, h.dismissed, "$name: the dismissal is the explicit one")
            assertFalse(up, "$name: the keyboard the person put away must not count as up at the turn")
            h.advanceTo(h.now + 60_000L)
            assertEquals(1, h.explicit, "$name: the check that was waiting made a second dismissal")
            assertEquals(0, h.dismissed, "$name: the check that was waiting made a default dismissal")
        }
    }

    private data class Measured(val name: String, val report: Long, val turn: Long, val previousAge: Long?)

    @Test
    fun `the age handed to the page at the turn is the pair's age`() {
        var age = -1L
        var now = 1_000L
        val pending = mutableListOf<Pair<Runnable, Long>>()
        val d = KeyboardDismissal(
            schedule = { r, ms -> pending += r to (now + ms) },
            cancel = { r -> pending.removeAll { it.first === r } },
            now = { now },
            imeVisible = { false },
            onDismissed = { },
            onExplicitDismissed = { ageMs -> age = ageMs },
        )
        d.edgeDown()
        now += 20L
        d.edgeGesture(pairSpanMs = 20L)
        now += 242L
        d.hidden()
        now += 382L
        d.turned()
        assertEquals(624L, age, "the page is told how old the gesture is, as the check would have told it")
    }

    @Test
    fun `a pair that ended less than EDGE_BEFORE_TURN_MS before the turn is the turn's, and one that ended that long before is not`() {
        // The cancel the platform makes for a turn of the phone came 6 ms before the turn on Android 13.
        for ((gap, expectPersons) in listOf(
            6L to false,
            KeyboardDismissal.EDGE_BEFORE_TURN_MS - 1 to false,
            KeyboardDismissal.EDGE_BEFORE_TURN_MS to true,
            272L to true,
        )) {
            val h = gestureThenHide(reportMs = 0L, turnMs = gap)
            h.dismissal.turned()
            h.advanceTo(h.now + 60_000L)
            assertEquals(if (expectPersons) 1 else 0, h.explicit, "a pair that ended $gap ms before the turn, with the report waiting: wrong verdict")
        }
    }

    @Test
    fun `the cancel of a finger resting at the edge through a turn is the turn's, with the report waiting`() {
        // A finger resting at the edge through a turn. The finger rests 3,200 ms and is cancelled for the turn, 6 ms before the callback or, on a slow host, 400 ms before it; the keyboard is
        // reported hidden with the turn. Neither the timing of the first nor the span of the second makes a Back of it.
        for (cancelBeforeTurn in listOf(6L, 400L)) {
            val h = ExplicitHarness()
            h.dismissal.edgeDown()
            h.advanceTo(4_200L)
            h.dismissal.edgeGesture(pairSpanMs = 3_200L)
            h.advanceTo(4_200L + cancelBeforeTurn - 5L)
            h.visible = false
            h.dismissal.hidden()
            h.advanceTo(4_200L + cancelBeforeTurn)
            assertTrue(h.dismissal.turned(), "a hide that comes with a turn counts as a keyboard that was up")
            h.advanceTo(h.now + 60_000L)
            assertEquals(0, h.explicit, "a resting finger's cancel $cancelBeforeTurn ms before the turn was taken for a Back")
            assertEquals(0, h.dismissed, "the turn's hide was held")
        }
    }

    @Test
    fun `a pair that belongs to the turn before is still that turn's when a second turn comes while its report waits`() {
        // The cancel of a resting finger 300 ms after a turn, its hide 100 ms later, and the turn back 300 ms after that.
        val h = ExplicitHarness()
        h.visible = true
        h.dismissal.turned()
        h.advanceTo(1_300L)
        h.dismissal.edgeDown()
        h.advanceTo(1_320L)
        h.dismissal.edgeGesture(pairSpanMs = 20L)
        h.advanceTo(1_420L)
        h.visible = false
        h.dismissal.hidden()
        h.advanceTo(1_720L)
        h.dismissal.turned()
        h.advanceTo(h.now + 60_000L)
        assertEquals(0, h.explicit, "a pair within EDGE_TURN_GUARD_MS of the turn before was taken for a Back at the next turn")
    }

    @Test
    fun `a pair that comes after the report is not its cause at the turn either`() {
        val h = ExplicitHarness()
        h.visible = false
        h.dismissal.hidden()
        h.advanceTo(1_100L)
        h.dismissal.edgeDown()
        h.advanceTo(1_120L)
        h.dismissal.edgeGesture(pairSpanMs = 20L)
        h.advanceTo(1_400L)
        h.dismissal.turned()
        h.advanceTo(h.now + 60_000L)
        assertEquals(0, h.explicit, "a gesture made after the keyboard was reported hidden explained that hide")
    }

    @Test
    fun `a keyboard that is visible at the turn is not judged`() {
        val h = gestureThenHide(reportMs = 100L, turnMs = 400L)
        h.visible = true
        h.dismissal.turned()
        h.advanceTo(h.now + 60_000L)
        assertEquals(0, h.explicit, "a keyboard that was up at the turn was dismissed by a pair and a report that had been waiting")
    }

    @Test
    fun `a Back key that explains a hide still waiting at the turn is the person's at the turn and the check adds nothing`() {
        // Android 15 hands the activity the Back key and the pair. The hide is judged at the turn, by the Back key.
        val zero = 40_000L
        val h = ExplicitHarness()
        h.advanceTo(zero - 20L)
        h.dismissal.edgeDown()
        h.advanceTo(zero)
        h.dismissal.edgeGesture(pairSpanMs = 20L)
        h.dismissal.backSeen()
        h.advanceTo(zero + 365L)
        h.visible = false
        h.dismissal.hidden()
        h.advanceTo(zero + 379L)
        assertFalse(h.dismissal.turned(), "a keyboard the person put away with Back must not count as up at the turn")
        assertEquals(1, h.explicit, "the Back key's dismissal was not made at the turn")
        h.advanceTo(zero + 379L + KeyboardDismissal.DELAY_MS)
        assertEquals(1, h.explicit, "the check that was waiting made a second dismissal")
        assertEquals(0, h.dismissed)
    }

    @Test
    fun `a Back key at a turn that a busy main thread delivered two seconds late still explains the hide that waited`() {
        // Measured on Android 15: the Back key, its hide 33 ms later, and the turn handled 1,995 ms after the Back, before the check could run.
        val zero = 40_000L
        val h = ExplicitHarness()
        h.advanceTo(zero)
        h.dismissal.backSeen()
        h.advanceTo(zero + 33L)
        h.visible = false
        h.dismissal.hidden()
        h.now = zero + 1_995L
        assertFalse(h.dismissal.turned(), "a keyboard the person put away with Back must not count as up at a turn delivered late")
        assertEquals(1, h.explicit, "the Back that explains the waiting hide was forgotten at the turn")
        h.advanceTo(h.now + 60_000L)
        assertEquals(1, h.explicit, "the check made a second dismissal")
        assertEquals(0, h.dismissed)
    }

    @Test
    fun `a Back judged at the turn stays judged when the rotation reports the keyboard visible and hidden again`() {
        // Android 10 with three buttons: the Back key, its hide 109 ms later, the turn 98 ms after that, an inset pass 78 ms after the turn that
        // reports the keyboard visible at the navigation bar's height, and the landscape report 130 ms after it.
        val zero = 40_000L
        val h = ExplicitHarness()
        h.advanceTo(zero)
        h.dismissal.backSeen()
        h.advanceTo(zero + 109L)
        h.visible = false
        h.dismissal.hidden()
        h.advanceTo(zero + 207L)
        assertFalse(h.dismissal.turned(), "a keyboard the person put away with Back must not count as up at the turn")
        assertEquals(1, h.explicit, "the Back key's dismissal was not made at the turn")
        h.advanceTo(zero + 285L)
        h.visible = true
        h.dismissal.shown()
        h.advanceTo(zero + 415L)
        h.visible = false
        h.dismissal.hidden()
        h.advanceTo(h.now + 60_000L)
        assertEquals(1, h.explicit, "the rotation's own report made a second dismissal")
        assertEquals(0, h.dismissed, "the rotation's own report was dismissed by default")
    }

    @Test
    fun `a second turn before the check would have run counts the keyboard as down`() {
        val h = gestureThenHide(reportMs = 162L, turnMs = 619L)
        assertFalse(h.dismissal.turned())
        h.advanceTo(h.now + 21L)
        assertFalse(h.dismissal.turned(), "the report that was waiting was judged at the first turn and must not count the keyboard as up at the second")
    }

    @Test
    fun `a hide reported again inside the same hide after the turn adds no dismissal`() {
        // Kind 2: the keyboard rebuilds for the new configuration and reports hidden again, 300 ms and 3 s after the turn.
        val h = gestureThenHide(reportMs = 242L, turnMs = 624L)
        h.dismissal.turned()
        h.advanceTo(h.now + 300L)
        h.dismissal.hidden()
        h.advanceTo(h.now + 2_700L)
        h.dismissal.hidden()
        h.advanceTo(h.now + 60_000L)
        assertEquals(1, h.explicit, "the keyboard's rebuild after the turn was dismissed again")
        assertEquals(0, h.dismissed, "the keyboard's rebuild after the turn was dismissed by default")
    }

    @Test
    fun `a pair, a turn, then the hide is still the turn's, which is not covered`() {
        // The order pair, turn, report needs a turn within the report's own delay of the swipe (121 to 631 ms measured), shorter than a hand turns the phone.
        // The pair is cleared at the turn as before and the report is the turn's: recorded here so that the limit is a decision and not an accident.
        val h = ExplicitHarness()
        h.visible = true
        h.dismissal.edgeDown()
        h.advanceTo(1_020L)
        h.dismissal.edgeGesture(pairSpanMs = 20L)
        h.advanceTo(1_300L)
        h.dismissal.turned()
        h.advanceTo(1_400L)
        h.visible = false
        h.dismissal.hidden()
        h.advanceTo(h.now + 60_000L)
        assertEquals(0 to 0, h.explicit to h.dismissed)
    }

    /**
     * The extra key row's wiring: it calls [KeyboardDismissal.hidden] and [KeyboardDismissal.shown] only when its own `imeVisible`
     * changes, and the activity's `imeVisible` lambda reads that same field.
     */
    private class WiredHarness(seed: Long) {
        var now = 1_000L
        var visible = true
        val random = java.util.Random(seed)
        private val pending = mutableListOf<Pair<Runnable, Long>>()

        val dismissal: KeyboardDismissal = KeyboardDismissal(
            schedule = { r, ms -> pending += r to (now + ms) },
            cancel = { r -> pending.removeAll { it.first === r } },
            now = { now },
            imeVisible = { visible },
            onDismissed = {},
            onExplicitDismissed = {},
        )
        private val inHideField = KeyboardDismissal::class.java.getDeclaredField("inHide").apply { isAccessible = true }

        fun inHide(): Boolean = inHideField.getBoolean(dismissal)

        fun changeVisible(value: Boolean) {
            if (visible == value) return
            visible = value
            if (value) dismissal.shown() else dismissal.hidden()
        }

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
    fun `under the extra key row's wiring a Back or an edge down never meets the keyboard visible during an uninterrupted hide`() {
        // With hidden() and shown() called only on a change of the field imeVisible() reads, a visible keyboard has always been
        // through shown() since the last hidden(), so the uninterrupted hide is already over when a Back or an edge down sees it.
        var checks = 0
        for (seed in 1L..500L) {
            val h = WiredHarness(seed)
            repeat(60) {
                when (h.random.nextInt(7)) {
                    0 -> h.changeVisible(true)
                    1 -> h.changeVisible(false)
                    2 -> {
                        checks += 1
                        assertFalse(h.visible && h.inHide(), "seed $seed: a Back met the keyboard visible during an uninterrupted hide")
                        h.dismissal.backSeen()
                    }
                    3 -> {
                        checks += 1
                        assertFalse(h.visible && h.inHide(), "seed $seed: an edge down met the keyboard visible during an uninterrupted hide")
                        h.dismissal.edgeDown()
                    }
                    4 -> h.dismissal.turned()
                    5 -> h.dismissal.edgeGesture(pairSpanMs = 120L)
                    else -> h.advance(h.random.nextInt(3_000).toLong())
                }
            }
        }
        assertTrue(checks > 5_000, "too few Backs and edge downs were tried: $checks")
    }

    @Test
    fun `an event span just under EDGE_PAIR_MS counts and one at it does not, whatever the gap between the two deliveries`() {
        fun explicitWith(spanMs: Long, receiptGapMs: Long): Pair<Int, Int> {
            val h = ExplicitHarness()
            h.dismissal.turned()
            h.advanceTo(1_400L)
            h.dismissal.refocused()
            h.advanceTo(4_000L)
            h.dismissal.edgeDown()
            h.advanceTo(4_000L + receiptGapMs)
            h.dismissal.edgeGesture(pairSpanMs = spanMs)
            h.advanceTo(4_100L + receiptGapMs)
            h.visible = false
            h.dismissal.hidden()
            h.advanceTo(20_000L)
            return h.explicit to h.dismissed
        }
        assertEquals(1 to 0, explicitWith(KeyboardDismissal.EDGE_PAIR_MS - 1, receiptGapMs = 1_500L))
        assertEquals(0 to 0, explicitWith(KeyboardDismissal.EDGE_PAIR_MS, receiptGapMs = 20L))
        assertEquals(0 to 0, explicitWith(-1L, receiptGapMs = 20L))
    }

    @Test
    fun `the age passed for an edge pair runs from its events, so a stall before its delivery does not make a gesture look younger than it is`() {
        // The gesture's own events: down at 10_000 and the system's cancel at 10_120. The main thread is busy for 480 ms, so the
        // activity handles them at 10_480 and 10_600. The page compares the age it is given with the latest text focus: a focus that
        // fell inside the stall, after the gesture, must not make the gesture count as made after it.
        var now = 10_480L
        var visible = true
        var reportedAge: Long? = null
        val pending = mutableListOf<Pair<Runnable, Long>>()
        val d = KeyboardDismissal(
            schedule = { r, ms -> pending += r to (now + ms) },
            cancel = { r -> pending.removeAll { it.first === r } },
            now = { now },
            imeVisible = { visible },
            onDismissed = {},
            onExplicitDismissed = { ageMs -> reportedAge = ageMs },
        )
        d.edgeDown()
        now = 10_600L
        d.edgeGesture(pairSpanMs = 120L, eventTimeMs = 10_120L)
        now = 10_700L
        visible = false
        d.hidden()
        while (pending.isNotEmpty()) {
            val next = pending.minByOrNull { it.second }!!
            now = next.second
            pending.remove(next)
            next.first.run()
        }
        assertEquals(
            now - 10_120L, reportedAge,
            "the age ran from the handling of the cancel and not from its event: a pair made before a focus that came during the stall is taken for one made after it",
        )
    }

    @Test
    fun `a back gesture whose pair and hidden report both came before a turn made half a second later is the person's`() {
        // The person swipes Back, the keyboard is reported hidden, and the phone is turned while that report still waits for its check.
        val h = ExplicitHarness()
        h.advanceTo(9_400L)
        h.dismissal.edgeDown()
        h.advanceTo(9_520L)
        h.dismissal.edgeGesture(pairSpanMs = 120L)
        h.advanceTo(9_800L)
        h.visible = false
        h.dismissal.hidden()
        h.advanceTo(10_000L)
        h.dismissal.turned()
        h.advanceTo(30_000L)
        assertEquals(1 to 0, h.explicit to h.dismissed, "the turn took a Back made before it for its own")
    }

    @Test
    fun `the age passed for a Back runs from the key's own event time when the activity has it`() {
        // The key's event at 20,000; the activity handles it at 20,400 behind a busy main thread; the keyboard is reported hidden at 20,500.
        var now = 20_400L
        var visible = true
        var reportedAge: Long? = null
        val pending = mutableListOf<Pair<Runnable, Long>>()
        val d = KeyboardDismissal(
            schedule = { r, ms -> pending += r to (now + ms) },
            cancel = { r -> pending.removeAll { it.first === r } },
            now = { now },
            imeVisible = { visible },
            onDismissed = {},
            onExplicitDismissed = { ageMs -> reportedAge = ageMs },
        )
        d.backSeen(eventTimeMs = 20_000L)
        now = 20_500L
        visible = false
        d.hidden()
        while (pending.isNotEmpty()) {
            val next = pending.minByOrNull { it.second }!!
            now = next.second
            pending.remove(next)
            next.first.run()
        }
        assertEquals(now - 20_000L, reportedAge, "the age of a Back ran from its handling and not from its key event")
    }

    @Test
    fun `a back gesture judged at the turn passes its age from the pair's own event time`() {
        // The pair's last event at 9,120, handled at 9,520; its hide reported at 9,800; the turn at 10,000, while that report waits for its check.
        var now = 9_400L
        var visible = true
        var reportedAge: Long? = null
        val pending = mutableListOf<Pair<Runnable, Long>>()
        val d = KeyboardDismissal(
            schedule = { r, ms -> pending += r to (now + ms) },
            cancel = { r -> pending.removeAll { it.first === r } },
            now = { now },
            imeVisible = { visible },
            onDismissed = {},
            onExplicitDismissed = { ageMs -> reportedAge = ageMs },
        )
        d.edgeDown()
        now = 9_520L
        d.edgeGesture(pairSpanMs = 120L, eventTimeMs = 9_120L)
        now = 9_800L
        visible = false
        d.hidden()
        now = 10_000L
        assertFalse(d.turned(), "the turn did not start with the keyboard down")
        assertEquals(880L, reportedAge, "the age at the turn ran from the pair's handling and not from its last event")
    }

    // ---- the event times handed on to the age

    /** A dismissal that records the age of every explicit dismissal and counts the default ones. */
    private class AgeHarness {
        var now = 1_000L
        var visible = true
        val ages = mutableListOf<Long>()
        var dismissed = 0
        private val pending = mutableListOf<Pair<Runnable, Long>>()

        val d: KeyboardDismissal = KeyboardDismissal(
            schedule = { r, ms -> pending += r to (now + ms) },
            cancel = { r -> pending.removeAll { it.first === r } },
            now = { now },
            imeVisible = { visible },
            onDismissed = { dismissed += 1 },
            onExplicitDismissed = { ageMs -> ages += ageMs },
        )

        fun advanceTo(target: Long) {
            while (true) {
                val next = pending.filter { it.second <= target }.minByOrNull { it.second } ?: break
                now = next.second
                pending.remove(next)
                next.first.run()
            }
            now = target
        }

        fun hideAndCheck(reportAt: Long) {
            advanceTo(reportAt)
            visible = false
            d.hidden()
            advanceTo(reportAt + KeyboardDismissal.DELAY_MS)
            visible = true
            d.shown()
        }
    }

    @Test
    fun `a Back handled without an event time takes its age from its handling after one that had one`() {
        val h = AgeHarness()
        h.advanceTo(1_000L)
        h.d.backSeen(eventTimeMs = 200L)
        h.hideAndCheck(1_100L)
        assertEquals(listOf(1_600L - 200L), h.ages, "setup: the first Back's age runs from its event")
        h.advanceTo(20_000L)
        h.d.backSeen()
        h.hideAndCheck(20_100L)
        assertEquals(20_600L - 20_000L, h.ages[1], "the second Back has no event time and its age ran from the first one's")
    }

    @Test
    fun `a pair handled without an event time takes its age from its handling after one that had one`() {
        val h = AgeHarness()
        h.advanceTo(1_000L)
        h.d.edgeDown()
        h.advanceTo(1_020L)
        h.d.edgeGesture(pairSpanMs = 20L, eventTimeMs = 200L)
        h.hideAndCheck(1_100L)
        assertEquals(listOf(1_600L - 200L), h.ages, "setup: the first pair's age runs from its event")
        h.advanceTo(20_000L)
        h.d.edgeDown()
        h.advanceTo(20_020L)
        h.d.edgeGesture(pairSpanMs = 20L)
        h.hideAndCheck(20_100L)
        assertEquals(20_600L - 20_020L, h.ages[1], "the second pair has no event time and its age ran from the first one's")
    }

    @Test
    fun `the event time of a Back that was used up does not lend itself to a later pair`() {
        val h = AgeHarness()
        h.advanceTo(1_000L)
        h.d.backSeen(eventTimeMs = 100L)
        h.hideAndCheck(1_100L)
        h.advanceTo(30_000L)
        h.d.edgeDown()
        h.advanceTo(30_020L)
        h.d.edgeGesture(pairSpanMs = 20L, eventTimeMs = 30_015L)
        h.hideAndCheck(30_100L)
        assertEquals(30_600L - 30_015L, h.ages[1], "a pair's age ran from a Back's event time that had been used up")
    }

    @Test
    fun `an event time after the handling gives an age of zero at the check and not a negative one at the turn`() {
        val check = AgeHarness()
        check.advanceTo(1_000L)
        check.d.backSeen(eventTimeMs = 6_000L)
        check.hideAndCheck(1_100L)
        assertEquals(listOf(0L), check.ages, "the check's age must not be negative")

        val turn = AgeHarness()
        turn.advanceTo(40_000L)
        turn.d.edgeDown()
        turn.advanceTo(40_020L)
        turn.d.edgeGesture(pairSpanMs = 20L, eventTimeMs = 45_000L)
        turn.advanceTo(40_300L)
        turn.visible = false
        turn.d.hidden()
        turn.advanceTo(40_600L)
        turn.d.turned()
        assertEquals(1, turn.ages.size, "setup: the pair explains the waiting report at the turn")
        assertTrue(turn.ages[0] >= 0L, "the age handed on at the turn is negative (${turn.ages[0]}): the check coerces to zero and the turn does not")
    }

    @Test
    fun `an event time far before the handling hands on its whole age`() {
        val h = AgeHarness()
        h.advanceTo(700_000L)
        h.d.backSeen(eventTimeMs = 100_000L)
        h.hideAndCheck(700_100L)
        assertEquals(listOf(700_600L - 100_000L), h.ages)
    }

    @Test
    fun `a pair handled less than EDGE_BEFORE_TURN_MS before a turn is not judged at it whatever its event time, which leaves a stalled Back undismissed`() {
        // The stall the notes accept: the pair's event at 40,000, handled at 40,700 behind a busy main thread, its hide handled at 40,750, the turn at 40,800:
        // by handling times the pair ended 100 ms before the turn, inside the distance that keeps a turn's own cancel from counting.
        val h = AgeHarness()
        h.advanceTo(40_700L)
        h.d.edgeDown()
        h.d.edgeGesture(pairSpanMs = 20L, eventTimeMs = 40_000L)
        h.advanceTo(40_750L)
        h.visible = false
        h.d.hidden()
        h.advanceTo(40_800L)
        h.d.turned()
        h.advanceTo(100_000L)
        assertEquals(0, h.ages.size + h.dismissed, "recorded characterization: a stalled Back is the turn's, as before 3.5")
    }

    @Test
    fun `a Back judged at the turn hands on its age from the key's own event time`() {
        val h = AgeHarness()
        h.advanceTo(50_000L)
        h.d.backSeen(eventTimeMs = 49_500L)
        h.advanceTo(50_100L)
        h.visible = false
        h.d.hidden()
        h.advanceTo(50_300L)
        h.d.turned()
        assertEquals(listOf(50_300L - 49_500L), h.ages, "the Back judged at the turn lost its event time")
        h.advanceTo(50_100L + KeyboardDismissal.DELAY_MS)
        assertEquals(1, h.ages.size, "the check added a second dismissal")
    }

    @Test
    fun `a Back spared across a turn keeps its event time for the age`() {
        // The hidden report is still in flight at the turn: the Back is spared and the check judges it.
        val h = AgeHarness()
        h.advanceTo(50_000L)
        h.d.backSeen(eventTimeMs = 49_500L)
        h.advanceTo(50_300L)
        h.d.turned()
        h.advanceTo(50_400L)
        h.visible = false
        h.d.hidden()
        h.advanceTo(50_400L + KeyboardDismissal.DELAY_MS)
        assertEquals(listOf(50_900L - 49_500L), h.ages, "a Back the turn spared lost its event time")
    }

    @Test
    fun `a Back and a pair that both explain one report hand on the younger age`() {
        val h = AgeHarness()
        h.advanceTo(60_000L)
        h.d.backSeen(eventTimeMs = 59_990L)
        h.d.edgeDown()
        h.advanceTo(60_010L)
        h.d.edgeGesture(pairSpanMs = 20L, eventTimeMs = 59_800L)
        h.hideAndCheck(60_100L)
        assertEquals(listOf(60_600L - 59_990L), h.ages, "the age is the younger of the two events")
    }
}
