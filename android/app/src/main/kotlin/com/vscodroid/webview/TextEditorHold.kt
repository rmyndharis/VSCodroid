package com.vscodroid.webview

/**
 * Keeps the answer "this view is a text editor" for the length of a turn of the phone, for a page that was one.
 *
 * When a window gains the focus again the input method service is told whether the focused view is a text editor, and a window that is already the focused one
 * and says it is not has its keyboard hidden (the reason HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR; read from platform tags for Android 11 to 16, not Android 10,
 * though the hide itself was only seen on Android 14). A turn is followed by such a focus gain (1.03 to 2.13 s after refocus for recorded hides, and up to 2.89 s
 * for recorded focus gains), and on an Android 14 emulator the keyboard was hidden that way after a turn in 6 of 40 turns across 5 of 20 runs on one build. The
 * question is the WebView's `onCheckIsTextEditor`, which is "the renderer's last text input type is not none", and on Android 14 the WebView has since said no at
 * 5 window focus gains and 1 size change (0.6 to 3.9 s after arming): the hold turns those answers into yes, and the lines it writes ([onAnswer]) record what the
 * system was told.
 *
 * The hold is armed at a turn that found the keyboard up and the page an editor, so a page that is not aimed at text never gets a yes. It answers yes only in
 * place of a real no. It does not end on a real yes: it is armed at a turn whose real answer is yes, so the first question after the turn would end it before the
 * window's focus gain it is for. It ends on [end] (a Back the activity saw, a keyboard the person put away, the activity pausing) and [HOLD_MS] after it was
 * armed at the latest; the turn's refocus ([refocused]) extends it to [AFTER_REFOCUS_MS] after the refocus, never past [CAP_MS] after it was armed.
 *
 * The clock is passed in so that the rule can be run without a device.
 */
class TextEditorHold(
    private val now: () -> Long,
    /**
     * Called for the answers worth a line: every real no, and every answer while a hold is armed and not ended, or ran out less than [LOG_TAIL_MS] ago, with the
     * real answer, the held part of it, and the time since the hold was armed (-1 when it never was).
     */
    private val onAnswer: (real: Boolean, held: Boolean, sinceArmedMs: Long) -> Unit = { _, _, _ -> },
) {
    @Volatile
    private var armedAt = NEVER

    @Volatile
    private var endsAt = NEVER

    @Volatile
    private var realNoAnswers = 0

    @Volatile
    private var heldAnswers = 0

    /** Arms the hold for [HOLD_MS], from now. A hold that is already armed starts again from now: a turn back inside the window of a turn is a new turn. */
    fun arm() {
        armedAt = now()
        endsAt = armedAt + HOLD_MS
        realNoAnswers = 0
        heldAnswers = 0
    }

    /** Ends the hold, if there is one. */
    fun end() {
        endsAt = NEVER
    }

    /**
     * The WebView took the focus: the refocus a turn makes. Recorded system hides followed it by 1.03 to 2.13 s and held focus gains reached 2.89 s after it, and
     * the refocus itself waits for the keyboard's own commit (seconds at a high host load), so a hold that is active runs at least [AFTER_REFOCUS_MS] more, and never
     * past [CAP_MS] after it was armed. A hold that is not active (never armed, ended, run out) is left as it is.
     */
    fun refocused() {
        if (!active) return
        endsAt = minOf(maxOf(endsAt, now() + AFTER_REFOCUS_MS), armedAt + CAP_MS)
    }

    /** Whether a no would be answered yes now. */
    val active: Boolean
        get() = endsAt != NEVER && now() < endsAt

    /** The answer for the platform: [real], or yes in place of a real no while the hold is active. */
    fun answer(real: Boolean): Boolean {
        val held = !real && active
        if (!real && armedAt != NEVER) {
            realNoAnswers += 1
            if (held) heldAnswers += 1
        }
        if (!real || (endsAt != NEVER && now() < endsAt + LOG_TAIL_MS)) {
            onAnswer(real, held, if (armedAt == NEVER) -1L else now() - armedAt)
        }
        return real || held
    }

    /**
     * The state for the debug line: the age of the hold, whether it is active, and how many real no answers were asked since it was armed and how many of them
     * it turned into a yes. A hide that comes with `heldAnswers=0` came when the hold had answered nothing in place of the platform. Ages, flags and counts only.
     */
    fun describe(): String {
        val age = if (armedAt == NEVER) "none" else "${now() - armedAt}ms"
        return "holdAge=$age holdActive=$active realNoAnswers=$realNoAnswers heldAnswers=$heldAnswers"
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE

        /**
         * The longest the hold lasts. The system's hide after a turn came 1.03 to 2.13 s after the app's refocus and 1.58 to 3.25 s after the app's turn on five
         * hides measured on Android 14 (plus 2.69 s after turn and 1.64 s after refocus on a sixth), and the WebView said no at 5 focus gains and 1 size change up
         * to 3.9 s after arming. About 1.5 times the longest measured gain (3.9 s): a hold that outlives its turn by more than that would answer yes for a page that
         * has left its editor.
         */
        const val HOLD_MS = 6_000L

        /** How long an active hold runs after the turn's refocus at least: about 1.4 times the longest measured distance from the refocus to a held focus gain (2.89 s; hide requests came 1.03 to 2.13 s after refocus). */
        const val AFTER_REFOCUS_MS = 4_000L

        /** The longest a hold lasts, whatever the refocus does: past it a page that has left its editor would still get a yes. */
        const val CAP_MS = 10_000L

        /** How long after a hold's time has run out its yes answers are still written down: the lines that show the page's editor back. */
        const val LOG_TAIL_MS = 2_000L
    }
}
