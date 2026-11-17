package com.vscodroid.keyboard

import kotlin.math.abs

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
 * shows within the delay, and not when the turn explains it. The turn is its own
 * test because the keyboard does not always return within the delay: the hidden
 * report came with the turn, and the visible one 150 to 650 ms after it across
 * six spaced turns on that AVD, and seconds after it in a run of turns every 4 s.
 *
 * What the turn explains follows the turn's own events and not a clock alone: see
 * [turnOwns]. A Back the activity sees before the keyboard does is the person's
 * whenever it comes: see [backSeen]. Where the key is not seen, the pair of touch
 * events a back gesture makes counts for a short time: see [edgeGesture].
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
    /** Called at each report of hidden, before anything is decided, with [describe]: the debug line. */
    private val onReport: (String) -> Unit = {},
    private val delayMs: Long = DELAY_MS,
    /** Called when a hidden report is explained by [backSeen] or [edgeGesture], with the age in milliseconds of that Back or gesture pair when this callback runs. */
    private val onExplicitDismissed: ((Long) -> Unit)? = null,
) {
    private var turnedAt: Long? = null
    private var refocusedAt: Long? = null
    // False once the page has answered that this turn makes no refocus: see noRefocus.
    private var refocusComing = true
    private var backAt: Long? = null
    private var edgeAt: Long? = null
    // The times of the Back key's and the pair's own events, set with backAt and edgeAt: the age the page is given runs from them.
    private var backEventAt: Long? = null
    private var edgeEventAt: Long? = null
    private var edgeDownAt: Long? = null
    private var edgeCompletedDownAt: Long? = null
    private var edgePairSpanMs: Long? = null
    private var hiddenAt = 0L
    private var inHide = false
    private var hidePending = false
    private var upAtTurn = false
    private var claimEnded = false

    private val fire = Runnable {
        hidePending = false
        if (imeVisible()) {
            inHide = false
            return@Runnable
        }
        // Judged at the report, which is what the turn explains, and not at this check, which runs half a second later.
        val backExplained = backFollowedBy(hiddenAt)
        val edgeExplained = edgeGestureFollowedBy(hiddenAt)
        // From the event's own time where the activity had it, so a busy main thread between the gesture and its handling does not make it look younger.
        val triggerAt = maxOf(
            (backEventAt ?: backAt).takeIf { backExplained } ?: Long.MIN_VALUE,
            (edgeEventAt ?: edgeAt).takeIf { edgeExplained } ?: Long.MIN_VALUE,
        )
        if (backExplained) backAt = null
        if (edgeExplained) {
            edgeAt = null
            edgeCompletedDownAt = null
            edgePairSpanMs = null
            edgeDownAt = null
        }
        if (backExplained || edgeExplained || !turnOwns(hiddenAt)) {
            // A keyboard put away is the person's. The turn has no claim on a later report either:
            // the person has shown whose the keyboard is.
            claimEnded = true
            if ((backExplained || edgeExplained) && onExplicitDismissed != null) {
                onExplicitDismissed.invoke((now() - triggerAt).coerceAtLeast(0L))
            } else {
                onDismissed()
            }
        }
    }

    /**
     * Whether a hidden report at [at] is the turn's doing and not the person's.
     *
     * Always within [TURN_FLOOR_MS] of the turn. Beyond it only for a turn that found the keyboard up, which is the one the system takes the keyboard
     * from after the transition, and then until [AFTER_REFOCUS_MS] after the refocus the turn makes, or while that refocus is still to come, and never
     * past [TURN_CAP_MS]. A keyboard that was down at the turn gets the floor and no more: the report that follows is a keyboard shown and put away after it.
     * So does a turn the page answered with a plain text box in focus ([noRefocus]): there is no refocus to wait for.
     */
    private fun turnOwns(at: Long): Boolean {
        if (claimEnded) return false
        val turn = turnedAt ?: return false
        val age = at - turn
        if (age < TURN_FLOOR_MS) return true
        if (!upAtTurn || age >= TURN_CAP_MS) return false
        val refocus = refocusedAt ?: return refocusComing
        return at - refocus < AFTER_REFOCUS_MS
    }

    /** Whether the report at [at] came within [BACK_MS] after a Back the activity saw. */
    private fun backFollowedBy(at: Long): Boolean = backAt?.let { (at - it) in 0L until BACK_MS } == true

    /**
     * Whether the report at [at] is explained by an edge back gesture (see [EdgeBackGesture]): a completed pair whose end followed its down within
     * [EDGE_PAIR_MS] (measured from event timestamps when provided), whose down fell on or before the report, whose end completed within
     * [EDGE_GESTURE_MS] before or [delayMs] after the report (observed in one of three runs on Android 17 where the hide report arrived 77 ms before the
     * cancel completed), and that was not the turn's (both down and end fell after the turn and at least [EDGE_TURN_GUARD_MS] away from it).
     */
    private fun edgeGestureFollowedBy(at: Long): Boolean {
        val edge = edgeAt ?: return false
        val down = edgeCompletedDownAt ?: edge
        val span = edgePairSpanMs ?: (edge - down)
        if (span !in 0L until EDGE_PAIR_MS) return false
        val inWindow = if (edge <= at) (at - edge) < EDGE_GESTURE_MS else down <= at && (edge - at) <= delayMs
        if (!inWindow) return false
        val turn = turnedAt ?: return true
        return down > turn && abs(edge - turn) >= EDGE_TURN_GUARD_MS && abs(down - turn) >= EDGE_TURN_GUARD_MS
    }

    /** The system reported the keyboard visible again: clear any prior dismissal markers and cancel pending checks. */
    fun shown() {
        backAt = null
        edgeAt = null
        edgeCompletedDownAt = null
        edgePairSpanMs = null
        edgeDownAt = null
        inHide = false
        hidePending = false
        cancel(fire)
    }

    /** The system reported the keyboard hidden. An uninterrupted hide keeps the time of its first report while restarting the delayed check. */
    fun hidden() {
        if (!inHide) {
            hiddenAt = now()
            inHide = true
        }
        onReport(describe())
        hidePending = true
        cancel(fire)
        schedule(fire, delayMs)
    }

    /**
     * The configuration changed: the turn of the phone, a resize, a fold. Whether the keyboard was up is read now, as a hide that arrived a moment before
     * the turn counts as up, and is kept from the turn before when that one still explains a report: a turn and the turn back are one event for it. Also
     * keeps an unjudged Back: its hidden report may be waiting or still in flight, and a rotation that keeps the main thread busy can deliver the turn
     * first. What bounds such a Back is [BACK_MS], measured to the report it explains, as it is without a turn.
     *
     * A hide that is still waiting for its check when the turn comes is judged here, by the state before the turn, which this call then clears: where
     * the platform hands the activity no Back key, the pair of a back gesture is all there is of a Back, and a turn within the half second the report waits
     * would otherwise leave it no judge. A pair that explains the report by the rules of [fire], and ended at least [EDGE_BEFORE_TURN_MS] before the turn, is
     * the person's: the dismissal is made now and the turn starts with the keyboard down. So is a hide that a Back key the activity saw explains, at
     * any age of that Back: the check may run only after a turn that a busy main thread delivered late.
     * Answers whether the keyboard counts as up, which is what the caller arms its hold of the editor answer on.
     */
    fun turned(): Boolean {
        val at = now()
        val visibleNow = imeVisible()
        if (visibleNow) inHide = false
        val waiting = hidePending && !visibleNow
        // A Back key that explains the waiting hide is the person's at any age: a busy main thread can hold the turn back for seconds.
        val backAge = if (waiting && backFollowedBy(hiddenAt)) backAt?.let { (at - (backEventAt ?: it)).coerceAtLeast(0L) } else null
        val pairAt = edgeAt
        val pairAge = if (backAge == null && waiting && pairAt != null && at - pairAt >= EDGE_BEFORE_TURN_MS && edgeGestureFollowedBy(hiddenAt)) {
            (at - (edgeEventAt ?: pairAt)).coerceAtLeast(0L)
        } else null
        val putAwayAge = backAge ?: pairAge
        upAtTurn = putAwayAge == null && (visibleNow || hidePending || (turnOwns(at) && upAtTurn))
        refocusedAt = null
        refocusComing = true
        if (backAge != null) backAt = null
        edgeAt = null
        edgeCompletedDownAt = null
        edgePairSpanMs = null
        edgeDownAt = null
        claimEnded = false
        turnedAt = at
        if (putAwayAge != null) {
            hidePending = false
            cancel(fire)
            if (onExplicitDismissed != null) onExplicitDismissed.invoke(putAwayAge) else onDismissed()
        }
        return upAtTurn
    }

    /**
     * The WebView took the focus: the refocus a turn makes to release the old input connection (a view that never lost it gives none). Inside the turn's
     * cap it fixes when [AFTER_REFOCUS_MS] starts. The first one of a turn counts: a later focus change does not move the end of the window.
     */
    fun refocused() {
        val turn = turnedAt ?: return
        if (!upAtTurn || now() - turn >= TURN_CAP_MS) return
        if (refocusedAt == null) refocusedAt = now()
    }

    /**
     * The page answered that the focus is in a plain text box (Quick Open, the find box, the Search view's input, a rename box), where this turn
     * makes no refocus and the system brings the keyboard back by itself. With none to wait for, the turn's claim ends at [TURN_FLOOR_MS], and a
     * keyboard reported hidden after it is the person's. Left to [TURN_CAP_MS], a keyboard put away with a Back the activity does not see was
     * never told to the page, and the next touch raised it again. Not called for the editor, the terminal or a focus in none of them, where a
     * refocus is made or waited for, or the keyboard is the editor's to bring back: those keep the claim to the cap. A focus the WebView takes all
     * the same still starts [AFTER_REFOCUS_MS] ([refocused]), and the next turn waits for its own answer.
     */
    fun noRefocus() {
        refocusComing = false
    }

    /**
     * The activity saw a Back before the soft keyboard did (see [BackObserverLayout]). It is the person's: a keyboard reported hidden within [BACK_MS]
     * after it is [onDismissed] even inside the turn's window. Where the platform does not hand the activity a Back ahead of the keyboard this is never
     * called, and the turn's window is all there is. [eventTimeMs] is the key event's own time on the clock of [now], when the caller has it.
     */
    fun backSeen(eventTimeMs: Long? = null) {
        backAt = now()
        backEventAt = eventTimeMs
    }

    /** A finger went down at the edge of the window: remember when this potential back gesture began. */
    fun edgeDown() {
        edgeDownAt = now()
    }

    /** The edge touch lifted or was reset without completing a back gesture. */
    fun edgeLifted() {
        edgeDownAt = null
    }

    /**
     * The window's touch events held the pair of a back gesture: a finger down at an edge that was cancelled by the system or flicked inward and lifted
     * within [EDGE_PAIR_MS] (see [EdgeBackGesture]). [pairSpanMs] is the gesture's span from the touch events themselves when available so delivery stalls do
     * not distort the span check, while receipt time is still used for comparisons with hide and turn reports. Where the platform does not hand the activity the
     * Back key ahead of the keyboard (Android 14 and Android 16 targeting 36, and documented for Android 13 though an Android 13 emulator with Gboard 12.4 still
     * delivered the Back key 8 of 8 times with backAge 8 to 49 ms), it is the only trace of a Back the activity gets, and it counts as one for [EDGE_GESTURE_MS]:
     * a keyboard reported hidden within that time after it is [onDismissed] even inside the turn's window, unless the pair fell within [EDGE_TURN_GUARD_MS]
     * of a turn. Unlike [backSeen] it ends nothing by itself: a gesture that is started and aborted makes the same pair and hides nothing, and only the hide
     * that follows says that a keyboard was put away. A report that does not follow it is judged as before. [eventTimeMs] is the time of the pair's last
     * event on the clock of [now], when the caller has it.
     */
    fun edgeGesture(pairSpanMs: Long? = null, eventTimeMs: Long? = null) {
        val at = now()
        edgeCompletedDownAt = edgeDownAt ?: at
        edgeDownAt = null
        edgePairSpanMs = pairSpanMs
        edgeAt = at
        edgeEventAt = eventTimeMs
    }

    /** The state a decision reads, for the debug log: ages in milliseconds and flags, no text, no token and no path. */
    fun describe(): String {
        val at = now()
        fun age(t: Long?) = t?.let { "${at - it}ms" } ?: "none"
        return "turnAge=${age(turnedAt)} refocusAge=${age(refocusedAt)} backAge=${age(backAt)} edgeAge=${age(edgeAt)} upAtTurn=$upAtTurn claimEnded=$claimEnded refocusComing=$refocusComing"
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
         * A hidden report within this long of a change of configuration is the
         * turn's and not the person's, whatever the keyboard was doing: no hold is
         * written for it. Counted from the report to the turn, so that the delay
         * does not eat into it. It is the whole window of a turn that found the
         * keyboard down, and the least of one that found it up.
         */
        const val TURN_FLOOR_MS = 2_000L

        /**
         * The latest a report is the turn's, however long the refocus takes. The first hidden reports after a turn came 0.44, 1.75, 3.29 and 5.89 s after it on
         * runs where the system took the keyboard away, 6.77 s after the turn's finish in another turn-transition hide, and 12.08 s on Android 16 while the
         * keyboard rebuilt under host load 200 to 284. A refocus that has not been made by then is not coming. The cap is for a refocus that is late, not
         * for one that is never made: the app makes it only for an editing host aimed at text, and where the page says the focus is in a plain text box
         * ([noRefocus]) the claim ends at [TURN_FLOOR_MS].
         */
        const val TURN_CAP_MS = 13_000L

        /**
         * How long after the turn's refocus a hidden report is still the turn's. In the 13 earlier recorded turns where a hidden report after a turn reached the
         * page's hook, the keyboard was reported hidden 1.48 to 5.53 s after the refocus (10 of them within 3 s, 12 within 5 s), and one loaded report reached
         * 6.09 s after refocus (including 4.45 s between the hide and its report). A longer allowance keeps more of those keyboards, and where the platform does
         * not hand the activity a Back ahead of the keyboard it also takes more of a person's Back for the turn's: this is the number to move.
         */
        const val AFTER_REFOCUS_MS = 6_500L

        /**
         * A hidden report within this long after a Back the activity saw is the person's. The keyboard closes itself on the Back it is handed, and the report
         * follows it by 0.03 to 1.29 s where the Back was a tap on the navigation bar or the back gesture (Android 12 and 15, injected and as a person makes it)
         * and by 2.2 to 4.5 s on Android 10 at a host load of 99 to 191. A longer value can only take for the person's a turn within that long after a Back.
         */
        const val BACK_MS = 5_000L

        /**
         * The longest span from a finger's down at the edge to the system's cancel that still counts as a back gesture pair. Android's gesture handler abandons
         * its back gesture if the finger rests past the platform long-press timeout (400 ms) before crossing touch slop, and cancels the app's touch as soon as
         * slop is crossed; measured real pairs took 9 to 22 ms on Android 14 and 16 and 172 ms in the slowest swipe on Android 17.
         */
        const val EDGE_PAIR_MS = 500L

        /**
         * A hidden report within this long after the pair of a back gesture (a finger down at an edge of the window, cancelled by the system) is the person's.
         * The report came 0.17 to 0.33 s after the cancel on an Android 14 emulator and 0.17 to 0.54 s after it on an Android 16 emulator (5 of 5 on each, at a
         * host load near 50); the largest measured distance is 1.02 s at host load 76. Two seconds leaves margin above 1.02 s without lingering too far into later
         * turns.
         */
        const val EDGE_GESTURE_MS = 2_000L

        /**
         * A pair this close to a turn of the phone, before it or after it, is the turn's and not a Back: the platform cancels a resting edge touch when the
         * display turns (measured at turn ages of 6 ms, 19 ms, and 171 ms on Android 17). Without it the report a turn makes would be taken for the person's
         * and a hold written for a keyboard the turn took away. It costs the gesture made within this long of a turn, which is judged by the turn's window as
         * before.
         */
        const val EDGE_TURN_GUARD_MS = 1_000L

        /**
         * How long before a turn a pair must have ended for a hide that waits at the turn to be judged by it ([turned]). The platform may cancel a touch just
         * before it tells the app of the turn: a resting finger's cancel came 6 ms before the turn on Android 13, and 6, 10, 19, 171 and 216 ms after it on
         * Android 13 and 17 (1,321 ms on Android 14). The pairs of a Back followed by a turn ended 272 to 619 ms before it in six turns on Android 14 and 15,
         * and 624 and 711 ms before it in two on Android 16. The value lies between the two, with more than 140 ms to either.
         */
        const val EDGE_BEFORE_TURN_MS = 150L
    }
}
