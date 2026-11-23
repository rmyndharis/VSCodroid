package com.vscodroid

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the script keeping the keyboard down is aimed at something that exists,
 * and is installed on every page it has to cover.
 *
 * This is the one piece of this app whose failure is silent in both directions.
 * Aimed at nothing, it does nothing and the keyboard covers half the screen
 * while the user is reading a file: exactly what shipped in a first version of
 * it, which looked for `textarea.inputarea` in a workbench that uses the
 * EditContext API and matched no element at all. Aimed too widely, or installed
 * where a page does not get it, and the editor is one you cannot type into.
 *
 * Mostly source-level, and that is the limit worth stating. Two cases read the
 * packaged `workbench.js` wherever the server tree is present, the selector
 * and the read-only marker, and those catch the workbench renaming either in a
 * VS Code bump; where the tree is absent they are skipped, and what catches a
 * rename then is the device row in `docs/DEVICE_TEST_CHECKLIST.md` (KB-21),
 * which is why that row exists.
 */
class KeyboardGuardWiringTest {

    private companion object {
        const val MAIN_ACTIVITY = "src/main/kotlin/com/vscodroid/MainActivity.kt"
        const val WORKBENCH = "src/main/assets/vscode-reh/out/vs/code/browser/workbench/workbench.js"
    }

    /**
     * The activity's source with its comments blanked.
     *
     * Comments blanked because this file explains the guard at length, and every
     * name the cases below look for appears in that prose. Read raw, a selector
     * deleted from the script still matches its own KDoc and the test passes on
     * a guard that matches nothing, which is the exact failure it exists for.
     */
    private fun mainActivity(): String =
        SourceScan.withoutComments(
            SourceScan.readAll(
                listOf(
                    MAIN_ACTIVITY,
                    "src/main/kotlin/com/vscodroid/MainActivityPageScripts.kt",
                )
            )
        )

    @Test
    fun `the guard is installed from the path every page load takes`() {
        val source = mainActivity()

        assertTrue(
            source.contains("internal fun MainActivity.injectKeyboardGuard()"),
            "injectKeyboardGuard is gone, so nothing keeps the keyboard down when a file is " +
                "opened or the Explorer is tapped.",
        )
        // The call sits with the other per-page injections in `injectBridgeToken`,
        // whose one call site is inside the `onPageFinished` lambda, under the
        // `isWorkbenchUrl` test. That is the path which runs again after a folder
        // switch, which the workbench performs by navigating its own WebView
        // without telling this side.
        //
        // Read out of that body rather than off the whole file, because being
        // next to `injectTouchTargetCSS()` is not the property and never was:
        // move both calls into `initBridge`, which runs once per WebView, and
        // they are still adjacent while the guard is gone from every page after
        // the first. That is the regression itself, and an adjacency check
        // passes it.
        val injected = SourceScan.body(source, "internal fun MainActivity.injectBridgeToken()")
        for (call in listOf("injectTouchTargetCSS()", "injectKeyboardGuard()")) {
            assertTrue(
                injected.contains(call),
                "`$call` is no longer called from injectBridgeToken, the one path every " +
                    "page load takes. Wherever it went, if that place runs once per WebView " +
                    "rather than once per page, the workbench's first self-navigation drops " +
                    "it: the keyboard then covers half the screen while a file is being read, " +
                    "or the editor is one nothing can be typed into, and neither says why.",
            )
        }
    }

    /**
     * That a scroll is still told apart from a tap.
     *
     * Dragging inside the editor is how a phone scrolls a file, and it goes down
     * on the same text a tap does. A guard that answers at pointerdown raises
     * the keyboard over every scroll, which is the complaint it exists to fix
     * arriving by another route; it was measured doing exactly that before the
     * pointerup branch was written. The three names below are that branch.
     */
    @Test
    fun `a long press is not a tap`() {
        val guard = SourceScan.scriptBody(mainActivity(), "internal fun MainActivity.injectKeyboardGuard(")
        val up = guard.substring(guard.indexOf("addEventListener('pointerup'").also {
            assertTrue(it >= 0, "the guard no longer decides on pointerup")
        })
        val check = Regex("""held\s*>=\s*LONG_PRESS_MS\s*\)\s*return""").find(up)
        assertTrue(
            check != null && check.range.first < up.indexOf("letTheKeyboardUp()"),
            "the guard no longer measures how long the finger was down, so a long press is " +
                "read as a tap again: the keyboard comes up, the window resizes, and the " +
                "workbench closes the menu the press had just opened. Measured on an API 36 " +
                "emulator as pointerup at t+985ms, the editor's gesture at t+995ms, the " +
                "resize at t+1621ms, and no menu.",
        )
        assertTrue(
            up.contains("e.timeStamp - pendingTap.at"),
            "the duration is no longer read from the events themselves. Both timestamps have " +
                "to come from the same clock; a wall-clock read here would compare two " +
                "different origins.",
        )
    }

    /**
     * That the guard never touches `inputmode` on an editor that is composing.
     *
     * An on-screen keyboard keeps the word being typed as a composition, and
     * Chromium answers a changed `inputmode` on the focused element by restarting
     * input, which writes the composed word back in again, reversed and several
     * times over. Measured on an API 33 emulator with Gboard: type `xyz`, tap the
     * Search icon, and the file reads `xyzzyxzyxzyx`. The editor composes through
     * EditContext, whose composition events fire on the element's `editContext`
     * and never on the document, so that is where the guard has to listen.
     */
    @Test
    fun `a composing editor keeps its word`() {
        val guard = SourceScan.scriptBody(mainActivity(), "internal fun MainActivity.injectKeyboardGuard(")
        for (name in listOf("editContext", "'compositionstart'", "'compositionend'")) {
            assertTrue(
                guard.contains(name),
                "the guard no longer names `$name`, so it cannot tell a composing editor " +
                    "apart and flips inputmode under a word being typed, which duplicates it.",
            )
        }
        val apply = SourceScan.jsBody(guard, "function apply(element)")
        val skip = apply.indexOf("composing")
        assertTrue(
            skip >= 0 && skip < apply.indexOf("inputmode"),
            "apply() changes inputmode before asking whether the element is composing: a word " +
                "typed on the on-screen keyboard is written in again when the user taps a " +
                "toolbar.",
        )
    }

    /**
     * That the set of composing editors follows the page, in both directions.
     *
     * A host left in the set after its composition is over is one the guard
     * never lets the keyboard up for again, because apply() skips it. Chromium
     * ends an EditContext composition on an empty commit without dispatching
     * compositionend (`EditContext::CommitText` sends it only for non-empty
     * text), so compositionend cannot be the only way out. Losing focus is the
     * reliable one: Blink finishes the composition of the element it takes focus
     * from, and of the page when the page loses focus.
     *
     * The other direction is the user typing. A tap outside text that leaves
     * focus in a composing editor clears `aimedAtText` while the keyboard stays
     * up, and the next time the workbench refocuses that editor the guard takes
     * the keyboard away in the middle of typing. A new composition in the focused
     * editor, with the keyboard not held down, is the user aiming at text.
     */
    @Test
    fun `the composing state ends with focus and a new word is aiming at text`() {
        val guard = SourceScan.scriptBody(mainActivity(), "internal fun MainActivity.injectKeyboardGuard(")

        val focusout = guard.indexOf("addEventListener('focusout'")
        assertTrue(
            focusout >= 0 &&
                guard.substring(focusout, guard.indexOf(", true)", focusout)).contains("composing.delete("),
            "the guard no longer drops an editor from the composing set when it loses focus, " +
                "so an editor whose composition ended without compositionend is skipped by " +
                "apply() for good, and tapping its text never raises the keyboard again.",
        )

        val started = SourceScan.jsBody(guard, "function composeStarted(element)")
        for (name in listOf("composing.add(element)", "document.activeElement", "'none'", "aimedAtText = true")) {
            assertTrue(
                started.contains(name),
                "composeStarted no longer contains `$name`: a new word typed into the focused " +
                    "editor has to both mark it composing and count as aiming at text, and only " +
                    "while the keyboard is not being held down.",
            )
        }
        val listeners = Regex("""addEventListener\('compositionstart'""").findAll(guard).count()
        val routed = Regex("""addEventListener\('compositionstart'[^;]*composeStarted\(""").findAll(guard).count()
        assertTrue(
            listeners > 0 && listeners == routed,
            "$routed of $listeners compositionstart listeners go through composeStarted, so a " +
                "composition seen by the others does not count as the user typing.",
        )
    }

    /**
     * That putting the keyboard away puts the hold back, and only that, and not at once.
     *
     * Once a tap on text has let the keyboard up, the editing host is left
     * without `inputmode`, and Chromium raises the keyboard for any touch on a
     * focused editable element: a user who hid it with Back had it back with the
     * next scroll of the file. `scripts/test-keyboard-guard.js` runs the guard's
     * half; this holds the call from the inset listener's report, which that
     * script cannot see. Made on the keyboard coming up as well, the same call
     * would put `inputmode="none"` on the host the user had just tapped into.
     *
     * And made at once, it is what takes the keyboard down at a turn of the phone
     * on a device with no hardware keyboard: the report of hidden that a turn
     * brings reached the hook directly, and the hold it wrote kept the keyboard
     * away. The call goes through [com.vscodroid.keyboard.KeyboardDismissal], which
     * `KeyboardDismissalTest` runs; this holds that the report of hidden and the
     * change of configuration are wired to it.
     */
    @Test
    fun `the keyboard going away restores the hold`() {
        val source = mainActivity()
        val guard = SourceScan.scriptBody(source, "internal fun MainActivity.injectKeyboardGuard(")
        assertTrue(
            guard.contains("window.__vscodroidKeyboardDismissed = function"),
            "the guard no longer defines __vscodroidKeyboardDismissed, so a keyboard put away " +
                "with Back comes back on the next scroll of the file.",
        )
        val wiring = SourceScan.body(source, "private fun setupExtraKeyRow(")
        assertTrue(
            Regex("""if \(!visible\) keyboardDismissal\.hidden\(\)""").containsMatchIn(wiring),
            "setupExtraKeyRow no longer hands the report of hidden, and only that, to " +
                "keyboardDismissal. Without it a keyboard put away with Back comes back on the " +
                "next scroll; made when the keyboard comes up, the call holds down the keyboard " +
                "the user just raised.",
        )
        assertFalse(
            wiring.contains("__vscodroidKeyboardDismissed"),
            "setupExtraKeyRow calls __vscodroidKeyboardDismissed itself again. A turn of the " +
                "phone reports the keyboard hidden before it comes back, and a hold written at " +
                "that report takes it down for good on a device with no hardware keyboard.",
        )
        // The call is the dismissal's callback; this holds the string being sent, so
        // dropping it from evaluateJavascript cannot leave the case green.
        assertTrue(
            Regex(
                """private val keyboardDismissal = KeyboardDismissal\([\s\S]*?onDismissed = \{[\s\S]*?""" +
                    """evaluateJavascript\(\s*"[^"]*__vscodroidKeyboardDismissed\(\)""",
            ).containsMatchIn(source),
            "the callback of keyboardDismissal no longer sends __vscodroidKeyboardDismissed with " +
                "evaluateJavascript, so the hold never comes back.",
        )
        assertTrue(
            Regex("""private fun configurationChanged\(newConfig: Configuration\)[^}]*keyboardDismissal\.turned\(\)""")
                .containsMatchIn(source),
            "onConfigurationChanged no longer tells keyboardDismissal about the turn, so the " +
                "hold is written for the hide a turn of the phone brings and keeps the keyboard " +
                "away on a device with no hardware keyboard.",
        )
        assertTrue(
            guard.contains("window.__vscodroidFinishComposition = finishComposition"),
            "the guard no longer exposes __vscodroidFinishComposition on window",
        )
        val configBody = SourceScan.withoutComments(
            SourceScan.body(source, "private fun configurationChanged("),
        )
        assertTrue(
            configBody.contains("finishKeyboardCompositionOnTurn()"),
            "onConfigurationChanged no longer invokes composition recovery",
        )
        val turnedIdx = configBody.indexOf("keyboardDismissal.turned()")
        val finishIdx = configBody.indexOf("finishKeyboardCompositionOnTurn()")
        val noKeysIdx = configBody.indexOf("Configuration.KEYBOARD_NOKEYS")
        assertTrue(
            turnedIdx in 0 until finishIdx && finishIdx < noKeysIdx,
            "keyboardDismissal.turned() must precede composition recovery, before the KEYBOARD_NOKEYS branch",
        )
    }

    @Test
    fun `native turn recovery waits with the page and abandons stale callbacks`() {
        val body = SourceScan.body(mainActivity(), "private fun finishKeyboardCompositionOnTurn()")
        val callback = body.substringAfter(") { editable ->")
        val pending = SourceScan.body(callback, "if (editable == \"\\\"wait\\\"\")")
        val ready = SourceScan.body(callback, "else if (editable == \"true\")")
        assertTrue(body.contains("__vscodroidFinishCompositionOnTurn(\$start)"))
        assertTrue(body.contains("attempt(true)"), "a new turn must start a fresh page wait")
        assertTrue(pending.contains("postDelayed({ attempt(false) }, 50L)"))
        assertFalse(pending.contains("clearFocus()"), "a pending commit still needs the old native connection")
        assertFalse(pending.contains("requestFocus()"))
        assertTrue(ready.contains("targetView.clearFocus()"))
        assertTrue(ready.contains("targetView.requestFocus()"))
        for (call in listOf("targetView.clearFocus()", "targetView.requestFocus()")) {
            assertTrue(body.split(call).size == 2, "native refocus must occur only in the ready branch: $call")
        }
        val beforeDispatch = body.substringBefore("targetView.evaluateJavascript(")
        val beforeAnswer = callback.substringBefore("if (editable ==")
        for (part in listOf(beforeDispatch, beforeAnswer)) {
            for (guard in listOf(
                "webView !== targetView", "keyboardCompositionTurn != turn",
                "AuthTabWindow.pageGeneration() != generation", "isFinishing", "isDestroyed",
            )) assertTrue(part.contains(guard), "a stale turn must stop before dispatch and before acting: $guard")
        }
        assertFalse(
            beforeAnswer.contains("isFocused") || beforeAnswer.contains("hasWindowFocus"),
            "a wait is asked again whether or not the view has the focus: a poll dropped for lack of it leaves the page's turn standing",
        )
        assertFalse(pending.contains("return"), "a wait is never dropped on its way to the next poll")
        assertTrue(ready.contains("!targetView.isFocused"))
        assertTrue(ready.contains("!targetView.hasWindowFocus()"))
        assertTrue(
            ready.indexOf("!targetView.hasWindowFocus()") < ready.indexOf("targetView.clearFocus()"),
            "native input is released only to a view that has the focus",
        )
        val guard = SourceScan.scriptBody(mainActivity(), "internal fun MainActivity.injectKeyboardGuard()")
        assertTrue(
            guard.contains("scheduleCompositionTurnFinish()"),
            "same-text composition events during a turn must schedule immediate completion",
        )
        assertFalse(
            SourceScan.jsBody(guard, "function composeStarted(").contains("compositionTurn = null"),
            "a keyboard reopening a composition during a turn must not cancel turn recovery",
        )
    }

    @Test
    fun `a scroll is not a tap`() {
        // Inside the guard: the same names appear in other scripts of the file, so a search of the
        // whole of it stays green with the guard back on pointerdown.
        val guard = SourceScan.scriptBody(mainActivity(), "internal fun MainActivity.injectKeyboardGuard()")

        for (decides in listOf(
            "document.addEventListener('pointerup'",
            "document.addEventListener('pointercancel'",
            "moved <= TAP_SLOP",
        )) {
            assertTrue(
                guard.contains(decides),
                "the guard no longer has `$decides`, so the decision has moved back to " +
                    "pointerdown and dragging to scroll a file raises the keyboard again.",
            )
        }
    }

    @Test
    fun `the guard aims at the element this workbench actually focuses`() {
        // The guard's own selectors: another script of the file names the element as well.
        val source = SourceScan.scriptBody(mainActivity(), "internal fun MainActivity.injectKeyboardGuard()")

        assertTrue(
            source.contains(".native-edit-context"),
            "the guard no longer names `native-edit-context`. That is the element the shipped " +
                "workbench focuses for the caret, so a guard without it matches nothing and " +
                "silently does nothing at all.",
        )

        // The other direction: that the name still describes the workbench in
        // the tree.
        assertTrue(
            packagedWorkbench().contains("native-edit-context"),
            "the packaged workbench no longer mentions `native-edit-context`, so the " +
                "selector in MainActivity now matches nothing. Open the editor over the " +
                "DevTools protocol, read `document.activeElement` with the caret in a " +
                "file, and point the guard at what it answers.",
        )
    }

    /**
     * That the packaged editor still marks a read-only editor the way the guard
     * reads it.
     *
     * The guard never lets the keyboard up for an EditContext host carrying
     * `aria-autocomplete="none"`, which Monaco's ScreenReaderSupport writes from
     * the readOnly option when the host is built and again on every option
     * change. A bump that writes it from anything else could make every editor
     * one the soft keyboard never comes up for; one that stops rewriting it
     * leaves a file made writable in a session without a keyboard.
     */
    @Test
    fun `the workbench still marks a read-only editor`() {
        val workbench = packagedWorkbench()
        val readOnly = Regex("""\((\d+),"readOnly",""").findAll(workbench).map { it.groupValues[1] }.toList()
        assertTrue(
            readOnly.size == 1,
            "the packaged workbench declares the readOnly editor option ${readOnly.size} times " +
                "rather than once, so the check below cannot tell which option the marker follows.",
        )
        assertTrue(
            Regex("""_updateDomAttributes\(\)\{[^}]*"aria-autocomplete",\w+\.get\(${readOnly[0]}\)\?"none":"both"\)""")
                .containsMatchIn(workbench),
            "the packaged workbench no longer writes aria-autocomplete from the readOnly option " +
                "in _updateDomAttributes, which writable() in injectKeyboardGuard reads. Find what " +
                "marks a read-only EditContext host now and gate on that.",
        )
        assertTrue(
            workbench.contains("_screenReaderSupport.onConfigurationChanged(") &&
                Regex("""onConfigurationChanged\(\w+\)\{this\._instantiateScreenReaderContent\(\)[^}]*this\._updateDomAttributes\(\)""")
                    .containsMatchIn(workbench),
            "the packaged workbench no longer rewrites the EditContext host's aria-autocomplete " +
                "on an option change, so an editor made writable in a session keeps \"none\" and " +
                "the guard never lets the keyboard up for it.",
        )
    }

    /**
     * The packaged workbench, or a skip when the tree is absent.
     *
     * Skipped rather than failed in a local checkout without the gitignored
     * server tree. An assumption reports that the check did not run; a silent
     * return would read as a green check of something nothing ran. Both CI unit
     * jobs supply the server and refuse an unexpected skip.
     */
    private fun packagedWorkbench(): String {
        val workbench = File(WORKBENCH)
        assumeTrue(
            workbench.isFile,
            "no packaged workbench at ${workbench.path}; run scripts/fetch-vscode-oss.sh and " +
                "scripts/package-assets.sh to check the guard against the shipped bundle",
        )
        return workbench.readText()
    }

    /**
     * Turning the phone wires [com.vscodroid.keyboard.KeyboardDismissal.refocused] and [com.vscodroid.webview.TextEditorHold.refocused] to the WebView focus
     * listener, and removes both the turn ask (`askKeyboardAgainAfterTurn`) and the insets animation observer (`observeImeAnimation`) so debuggable and
     * release builds behave identically.
     */
    @Test
    fun `webView focus wires refocus and neither the turn ask nor the insets animation callback remains`() {
        val source = mainActivity()
        val setup = SourceScan.withoutComments(SourceScan.body(source, "private fun setupWebView()"))
        assertTrue(
            Regex("""onFocusChangeListener = View\.OnFocusChangeListener \{ _, hasFocus ->\s+if \(hasFocus\) \{\s+keyboardDismissal\.refocused\(\)\s+\(wv as\? EditorWebView\)\?\.textEditorHold\?\.refocused\(\)""").containsMatchIn(setup),
            "setupWebView no longer tells keyboardDismissal and the hold of the editor answer when the WebView takes the focus.",
        )
        assertFalse(
            source.contains("askAfterTurnWhereAsked") || source.contains("askKeyboardAgainAfterTurn") || source.contains("onLostAfterTurn"),
            "the ask after a turn must stay removed.",
        )
        assertFalse(
            source.contains("observeImeAnimation") || source.contains("setWindowInsetsAnimationCallback") || source.contains("userDrivenAnimation"),
            "the insets animation callback must stay removed so debuggable and release builds behave identically.",
        )
    }

    /**
     * Where the platform hands the activity a Back ahead of the soft keyboard, [com.vscodroid.keyboard.KeyboardDismissal.backSeen] is how a keyboard a
     * person closes with it stays down inside a turn's window. The wiring is the window's content being the observer, the observer reporting the Back
     * key and passing every event on, and the activity connecting the two; the debug line is written at every hidden report, in debuggable builds only.
     */
    @Test
    fun `a Back seen ahead of the keyboard reaches keyboardDismissal and every hidden report is logged`() {
        val layout = SourceScan.read("src/main/res/layout/activity_main.xml")
        assertTrue(
            Regex("""<com\.vscodroid\.keyboard\.BackObserverLayout\b[^>]*android:id="@\+id/webViewContainer"""").containsMatchIn(layout),
            "the root of activity_main.xml is no longer the BackObserverLayout, so no Back is seen ahead of the keyboard and a Back inside a turn's window is taken for the turn's.",
        )
        val observer = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/keyboard/BackObserverLayout.kt"))
        val preIme = SourceScan.body(observer, "override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean")
        // The hand-on is the only way out: a `return true` ahead of it, under any condition, keeps
        // the key from the keyboard and the page, and a search for the hand-on alone still finds it.
        assertTrue(
            preIme.contains("event.keyCode == KeyEvent.KEYCODE_BACK") &&
                preIme.contains("onBack(event.eventTime)") &&
                Regex("""\breturn\b.*""").findAll(preIme).map { it.value.trim() }.toList() ==
                listOf("return super.dispatchKeyEventPreIme(event)"),
            "BackObserverLayout no longer reports the Back key from dispatchKeyEventPreIme and passes every event on: consuming it would stop the keyboard closing.",
        )
        val activity = mainActivity()
        val setup = SourceScan.body(activity, "private fun setupExtraKeyRow()")
        assertTrue(
            Regex("""findViewById<BackObserverLayout>\(R\.id\.webViewContainer\)\.onBack = \{ eventTimeMs ->\s+keyboardDismissal\.backSeen\(eventTimeMs\)\s+endTextEditorHold\(\)\s+\}""").containsMatchIn(setup),
            "setupExtraKeyRow no longer connects the Back the window sees, with its key event's time, to keyboardDismissal.backSeen and to the end of the hold of the editor answer.",
        )
        assertTrue(
            Regex("""private val keyboardDismissal = KeyboardDismissal\([\s\S]*?onReport = \{ state -> logKeyboardHidden\(state\) \}""").containsMatchIn(activity),
            "keyboardDismissal no longer hands each report of hidden to logKeyboardHidden, so a run on a device has no line to read.",
        )
        val log = SourceScan.body(activity, "private fun logKeyboardHidden(")
        assertTrue(
            log.contains("Logger.d(") && !log.contains("Logger.i(") && !log.contains("Logger.w(") &&
                log.contains("\$state") && log.contains("realTextEditor()") && log.contains("textEditorHold") &&
                log.contains("view?.isFocused") && log.contains("view?.hasWindowFocus()"),
            "the line for a hidden report no longer carries the decision's state and the WebView's own (its real answer, the hold, the focus), or is written outside debuggable builds.",
        )
        assertFalse(
            log.contains("evaluateJavascript") || log.contains("url") || log.contains("Token"),
            "the line for a hidden report reaches into the page, a URL or a token.",
        )
    }

    /**
     * The back gesture leaves a pair in the window's touch events (a finger down at an edge, cancelled by the system), which is how a Back is seen where the key is
     * not handed to the activity. The rule is run by `EdgeBackGestureTest` and `KeyboardDismissalTest`; this holds the wiring: the activity noting the pair from
     * `dispatchTouchEvent` after the delegate has had the event, answering what the delegate answered, and handing the cancel of an edge touch to the dismissal.
     */
    @Test
    fun `the pair of a back gesture reaches keyboardDismissal from the window's touch events, which are passed on unchanged`() {
        val activity = mainActivity()
        val touch = SourceScan.withoutComments(SourceScan.body(activity, "override fun dispatchTouchEvent("))
        assertTrue(
            Regex("""val handled = super\.dispatchTouchEvent\(ev\)\s+noteEdgeBackGesture\(ev\)""").containsMatchIn(touch) && touch.contains("return handled"),
            "dispatchTouchEvent no longer lets the delegate have the event first, notes the pair after it and answers what the delegate answered: " +
                "the touch the page gets would change, or the back gesture would never be noted.",
        )
        val note = SourceScan.withoutComments(SourceScan.body(activity, "private fun noteEdgeBackGesture("))
        val finish = SourceScan.withoutComments(SourceScan.body(activity, "private fun finishEdgeTouch("))
        assertTrue(
            note.contains("MotionEvent.ACTION_DOWN ->") &&
                note.contains("WindowInsetsCompat.Type.systemGestures()") &&
                note.contains("gestureInsets?.bottom") &&
                note.contains("scaledTouchSlop") &&
                note.contains("edgeBackGesture.down(") &&
                note.contains("MotionEvent.ACTION_MOVE -> edgeBackGesture.move(ev.x, ev.y)") &&
                note.contains("MotionEvent.ACTION_UP ->") &&
                note.contains("edgeBackGesture.finishUp(") &&
                note.contains("MotionEvent.ACTION_CANCEL ->") &&
                note.contains("edgeBackGesture.finishCancel(") &&
                note.contains("ev.eventTime - ev.downTime") &&
                finish.contains("keyboardDismissal.edgeGesture(spanMs, eventTimeMs)") &&
                note.contains("finishEdgeTouch(\"up\", spanMs, trace, trace?.matched == true, ev.eventTime)") &&
                note.contains("finishEdgeTouch(\"cancel\", spanMs, trace, trace != null, ev.eventTime)"),
            "noteEdgeBackGesture no longer tracks edge touches from down/move through up/cancel with systemGestures insets, " +
                "event timestamps, scaledTouchSlop, and bottom strip exclusion: a Back by gesture or quick flick would be misjudged.",
        )
        assertTrue(
            finish.contains("if (trace == null)") &&
                finish.contains("Logger.d(") &&
                finish.contains("ended=") &&
                finish.contains("span=") &&
                finish.contains("dx=") &&
                finish.contains("keyboardDismissal.describe()"),
            "finishEdgeTouch must log one debug line with ended state, span, horizontal travel, and describe() on an armed touch end.",
        )
        assertFalse(
            note.contains("evaluateJavascript") || note.contains("url") || note.contains("Token") || note.contains("return true") || note.contains("consume") ||
                finish.contains("evaluateJavascript") || finish.contains("url") || finish.contains("Token"),
            "noteEdgeBackGesture reaches into the page, a URL or a token, or answers for the event: it is an observation of the touch and nothing else.",
        )
        val layout = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/keyboard/BackObserverLayout.kt"))
        assertFalse(
            layout.contains("dispatchTouchEvent") || layout.contains("onTouchEvent"),
            "BackObserverLayout reads touch events: the pair is read by the activity, which is handed every event, and a layout only the ones a child holds.",
        )
    }

    /**
     * The WebView keeps its answer "text editor" through a turn that found the keyboard up and the page an editor ([com.vscodroid.webview.TextEditorHold]; the
     * rule is run by `TextEditorHoldTest`). The wiring is the class being the view, in the layout and in the replacement a renderer crash builds; the turn arming
     * the hold from what the dismissal says about the keyboard and from the WebView's own real answer; and every end the hold has: a Back the activity saw, a
     * keyboard the person put away, the activity pausing. The window's focus change is written down before the platform asks, in debuggable builds only.
     */
    @Test
    fun `the editor answer is held through a turn that found the keyboard up, and ends with a Back, a dismissal and a pause`() {
        val layout = SourceScan.read("src/main/res/layout/activity_main.xml")
        assertTrue(
            Regex("""<com\.vscodroid\.webview\.EditorWebView\b[^>]*android:id="@\+id/webView"""").containsMatchIn(layout),
            "the WebView of activity_main.xml is no longer an EditorWebView, so no turn holds the editor answer.",
        )
        val activity = mainActivity()
        val recreate = SourceScan.withoutComments(SourceScan.body(activity, "private fun recreateWebView()"))
        assertTrue(
            recreate.contains("val newWebView = EditorWebView(this)"),
            "recreateWebView builds a plain WebView: after a renderer crash no turn holds the editor answer.",
        )
        val turn = SourceScan.withoutComments(SourceScan.body(activity, "private fun configurationChanged("))
        assertTrue(
            turn.contains("armTextEditorHold(keyboardDismissal.turned())"),
            "onConfigurationChanged no longer arms the hold from what keyboardDismissal says about the keyboard at the turn.",
        )
        val arm = SourceScan.withoutComments(SourceScan.body(activity, "private fun armTextEditorHold("))
        assertTrue(
            arm.contains("if (keyboardUp && view.realTextEditor()) view.textEditorHold.arm() else view.textEditorHold.end()"),
            "the hold is no longer armed only for a keyboard that was up and a page that is an editor to the platform, or no longer ended by a turn that arms nothing.",
        )
        val dismissal = Regex("""private val keyboardDismissal = KeyboardDismissal\([\s\S]*?onDismissed = \{([\s\S]*?)\},\s+onReport""").find(activity)
        assertTrue(
            dismissal != null && dismissal.groupValues[1].contains("endTextEditorHold()"),
            "a keyboard the person put away no longer ends the hold of the editor answer.",
        )
        val webViewClient = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/webview/VSCodroidWebViewClient.kt"))
        assertTrue(
            SourceScan.body(webViewClient, "override fun onPageStarted(").contains("textEditorHold?.end()") &&
                SourceScan.body(webViewClient, "override fun onReceivedError(").contains("textEditorHold?.end()"),
            "VSCodroidWebViewClient must end an armed TextEditorHold on same-WebView page navigation or main-frame load error.",
        )
        assertTrue(
            Regex("""private val keyboardDismissal = KeyboardDismissal\([\s\S]*?onExplicitDismissed = \{\s*ageMs\s*->[\s\S]*?System\.currentTimeMillis\(\)\s*-\s*ageMs[\s\S]*?__vscodroidKeyboardDismissed\(true,\s*\${'$'}gestureAtMs\)""")
                .containsMatchIn(activity),
            "keyboardDismissal must pass byPerson=true and the gesture's wall-clock timestamp (System.currentTimeMillis() - ageMs) via onExplicitDismissed while keeping unflagged onDismissed for default dismissals",
        )
        val guard = SourceScan.scriptBody(activity, "internal fun MainActivity.injectKeyboardGuard(")
        assertTrue(
            guard.contains("Date.now()") &&
                guard.contains("gestureAtMs < textFocusedWallAt") &&
                guard.contains("element === document.activeElement"),
            "injectKeyboardGuard must track wall-clock focus time alongside performance.now() for explicit gesture comparison, and apply deferred composition holds only while focused.",
        )
        val focusout = guard.substring(guard.indexOf("addEventListener('focusout'"), guard.indexOf(", true);", guard.indexOf("addEventListener('focusout'")))
        assertTrue(
            !focusout.contains("composeEnded(") && focusout.contains("holdAfterComposition"),
            "focusout must drop pending holdAfterComposition without calling composeEnded/apply on a blurred host.",
        )
        val windowFocus = SourceScan.withoutComments(SourceScan.body(activity, "override fun onWindowFocusChanged("))
        assertFalse(
            windowFocus.contains("endTextEditorHold") || activity.contains("hasTurnYoungerThan"),
            "onWindowFocusChanged must not end TextEditorHold on focus loss, and hasTurnYoungerThan must be removed.",
        )
        val pause = SourceScan.withoutComments(SourceScan.body(activity, "override fun onPause()"))
        assertTrue(
            pause.contains("endTextEditorHold()"),
            "a window that is pausing no longer ends the hold of the editor answer.",
        )
        val focus = SourceScan.withoutComments(SourceScan.body(activity, "private fun logWindowFocus("))
        assertTrue(
            focus.contains("Logger.d(") && !focus.contains("Logger.i(") && !focus.contains("Logger.w(") &&
                focus.contains("findFocus()") && focus.contains("realTextEditor()") && focus.contains("keyboardDismissal.describe()"),
            "the line for a change of the window's focus no longer carries the focus owner, the real answer and the decision's ages, or is written outside debuggable builds.",
        )
        assertFalse(
            focus.contains("evaluateJavascript") || focus.contains("url") || focus.contains("Token"),
            "the line for a change of the window's focus reaches into the page, a URL or a token.",
        )
        assertTrue(
            SourceScan.withoutComments(SourceScan.body(activity, "override fun onWindowFocusChanged(")).contains("logWindowFocus(hasFocus)"),
            "onWindowFocusChanged no longer writes the line for a change of the window's focus.",
        )
        val view = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/webview/EditorWebView.kt"))
        assertTrue(
            view.contains("override fun onCheckIsTextEditor(): Boolean {") && view.contains("textEditorHold.answer(real)") &&
                view.contains("fun realTextEditor(): Boolean = super.onCheckIsTextEditor()"),
            "EditorWebView no longer answers through the hold, or no longer offers the real answer.",
        )
    }

    // ---- Additional wiring checks on TextEditorHold and edge back gesture observation

    @Test
    fun `page reload and error page end an armed TextEditorHold`() {
        val activity = mainActivity()
        val errorPage = SourceScan.withoutComments(SourceScan.body(activity, "private fun showErrorPage("))
        val beginPageEndsHold = activity.contains("AuthTabWindow.beginPage()") &&
            Regex("""AuthTabWindow\.beginPage\(\)[\s\S]{0,160}endTextEditorHold\(\)""").containsMatchIn(activity)
        val errorEndsHold = errorPage.contains("endTextEditorHold()")
        assertTrue(
            beginPageEndsHold && errorEndsHold,
            "TextEditorHold stays armed across same-WebView page reloads/navigations (beginPageEndsHold=$beginPageEndsHold), " +
                "and error pages (errorEndsHold=$errorEndsHold)",
        )
    }

    @Test
    fun `noteEdgeBackGesture handles multi-touch pointer up, screen-touch filtering and top-corner exclusion`() {
        val activity = mainActivity()
        val note = SourceScan.withoutComments(SourceScan.body(activity, "private fun noteEdgeBackGesture("))
        val handlesPointerUp = Regex(
            """MotionEvent\.ACTION_POINTER_DOWN,\s*MotionEvent\.ACTION_POINTER_UP\s*->\s*\{\s*edgeBackGesture\.lifted\(\)""",
        ).containsMatchIn(note)
        val checksTouchSource = note.contains("HardwareTyping.isScreenTouch(ev.source)") &&
            note.contains("MotionEvent.TOOL_TYPE_STYLUS")
        // The band of the status bar and the cutout: a touch that goes down there is the
        // notification shade's. Not `ev.y` alone, which the move branch reads whatever this does.
        val checksVerticalOrExclusion = Regex("""\bev\.y\s*<\s*topInset\b""").containsMatchIn(note) &&
            note.contains("WindowInsetsCompat.Type.statusBars()")
        assertTrue(
            handlesPointerUp && checksTouchSource && checksVerticalOrExclusion,
            "noteEdgeBackGesture produces false edge pairs on multi-touch pointer lift + later cancel (handlesPointerUp=$handlesPointerUp), " +
                "stylus/non-finger input (checksTouchSource=$checksTouchSource), and status-bar corner notification-shade pulls (checksVerticalOrExclusion=$checksVerticalOrExclusion)",
        )
    }

    @Test
    fun `noteEdgeBackGesture passes systemGestures insets to EdgeBackGesture`() {
        val activity = mainActivity()
        val note = SourceScan.withoutComments(SourceScan.body(activity, "private fun noteEdgeBackGesture("))
        assertTrue(
            note.contains("WindowInsetsCompat.Type.systemGestures()") &&
                note.contains("gestureInsets?.bottom") &&
                Regex("""edgeBackGesture\.down\([\s\S]*?gestureInsets\?\.left,\s*gestureInsets\?\.right""").containsMatchIn(note),
            "noteEdgeBackGesture must read WindowInsetsCompat.Type.systemGestures(), exclude bottom gesture insets, and pass the left and right insets to edgeBackGesture.down",
        )
    }
}
