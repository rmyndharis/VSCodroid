package com.vscodroid.keyboard

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.vscodroid.R
import com.vscodroid.ToolchainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a finger does on one key of the row.
 *
 * Every press is decided by the key's `GestureDetector`, a platform class that
 * posts its long press on the main thread's Looper, so the JVM suite can only
 * read how [ExtraKeyButton] configures it (`ExtraKeyPressStateTest`). These
 * feed it real [MotionEvent]s and wait out its real timers.
 *
 * Two defects lived there. A key without alternates pressed itself once a
 * finger had rested on it for the long-press timeout, so a swipe across the row
 * that started slowly sent the key it started on and then turned the page; and
 * the second of two quick taps went to the detector's double tap, which the key
 * does not handle, and was lost. A modifier keeps its long press, which latches
 * it while the finger is down, and the swipe cases hold that a drag the pager
 * takes from one leaves it as it was.
 *
 * The last three put the row in a window and a finger on it. One swipes the
 * pager with a second finger while the first holds Ctrl, which has to leave
 * Ctrl as the hold set it. The other two resize the row so that it repacks:
 * the swap removes the key or the trackpad under the finger, whose touch is
 * cancelled from inside it, and the row and the page it pushes to have to
 * agree afterwards.
 *
 * Not run by CI, which compiles the instrumented tests but has no emulator to
 * run them on.
 */
@RunWith(AndroidJUnit4::class)
class ExtraKeyButtonTouchInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** Every press the key delivered, by key value. */
    private val pressed = mutableListOf<String>()

    /** The state each press reported, which for a modifier is the latch the row is told. */
    private val reported = mutableListOf<Boolean>()

    /** Every time the key opened its alternates. */
    private var opened = 0

    /** A key, built on the main thread: its detector needs a Looper. */
    private fun key(alternates: List<AlternateKey> = emptyList(), value: String = "F7"): ExtraKeyButton {
        lateinit var button: ExtraKeyButton
        onMain {
            button = ExtraKeyButton(instrumentation.targetContext).apply {
                keyValue = value
                isToggle = value == "Ctrl"
                this.alternates = alternates
                onKeyAction = { key, isActive ->
                    pressed.add(key)
                    reported.add(isActive)
                }
                onLongPressAction = { _, _ -> opened++ }
            }
        }
        return button
    }

    private fun event(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float = 10f): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0)

    /** The finger resting where it landed until the long press is due, and the timer then run. */
    private fun restPastTheLongPress() {
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + 300L)
        instrumentation.waitForIdleSync()
    }

    @Test
    fun aSwipeThatStartsSlowlyOnAKeyPressesNothing() {
        val button = key()
        val slop = ViewConfiguration.get(instrumentation.targetContext).scaledTouchSlop
        val down = SystemClock.uptimeMillis()
        onMain { button.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, 10f)) }

        restPastTheLongPress()
        assertEquals("the key pressed itself while the finger was still resting on it", emptyList<String>(), pressed)

        // The finger moves off past the slop and the pager takes the drag,
        // which reaches the key as ACTION_CANCEL.
        onMain {
            val now = SystemClock.uptimeMillis()
            button.dispatchTouchEvent(event(down, now, MotionEvent.ACTION_MOVE, 10f + slop * 4))
            button.dispatchTouchEvent(event(down, now, MotionEvent.ACTION_CANCEL, 10f + slop * 4))
        }
        instrumentation.waitForIdleSync()
        assertEquals("a swipe the pager took sent the key it started on", emptyList<String>(), pressed)
    }

    @Test
    fun aLongHoldPressesOnceWhenTheFingerLifts() {
        val button = key()
        val down = SystemClock.uptimeMillis()
        onMain { button.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, 10f)) }
        restPastTheLongPress()
        onMain { button.dispatchTouchEvent(event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 10f)) }
        instrumentation.waitForIdleSync()

        assertEquals("a hold no longer presses the key at all", listOf("F7"), pressed)
    }

    @Test
    fun twoQuickTapsAreTwoPresses() {
        val button = key()
        // Inside the detector's double-tap window: more than its 40 ms minimum
        // after the first tap lifts, less than the timeout. All four events go
        // in one main-thread turn, so the detector's own tap timer, posted on
        // the first DOWN, is still pending at the second, as it is for a finger.
        val gap = ViewConfiguration.getDoubleTapTimeout() / 2L
        onMain {
            val first = SystemClock.uptimeMillis()
            val second = first + 50 + gap
            button.dispatchTouchEvent(event(first, first, MotionEvent.ACTION_DOWN, 10f))
            button.dispatchTouchEvent(event(first, first + 50, MotionEvent.ACTION_UP, 10f))
            button.dispatchTouchEvent(event(second, second, MotionEvent.ACTION_DOWN, 10f))
            button.dispatchTouchEvent(event(second, second + 50, MotionEvent.ACTION_UP, 10f))
        }
        instrumentation.waitForIdleSync()

        assertEquals("the second of two quick taps was lost", listOf("F7", "F7"), pressed)
    }

    @Test
    fun aKeyWithAlternatesOpensThemOnAHoldAndPressesNothing() {
        // The control for the first case: the long press is still there for the
        // keys that have something to open.
        val button = key(listOf(AlternateKey("[", "[", R.string.key_desc_left_bracket)))
        val down = SystemClock.uptimeMillis()
        onMain { button.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, 10f)) }
        restPastTheLongPress()
        onMain { button.dispatchTouchEvent(event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 10f)) }
        instrumentation.waitForIdleSync()

        assertEquals("a hold on a key with alternates did not open them", 1, opened)
        assertEquals("a hold that opened the alternates also pressed the key", emptyList<String>(), pressed)
    }

    @Test
    fun aModifierHeldPastTheLongPressLatchesBeforeTheFingerLifts() {
        // So a letter typed on the soft keyboard while Ctrl is held is chorded:
        // the interceptor chords only what arrives while the latch is on.
        val button = key(value = "Ctrl")
        val down = SystemClock.uptimeMillis()
        onMain { button.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, 10f)) }
        restPastTheLongPress()
        assertEquals("Ctrl was not latched while the finger was still down", listOf(true), reported)

        onMain { button.dispatchTouchEvent(event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 10f)) }
        instrumentation.waitForIdleSync()
        assertEquals("lifting the finger switched Ctrl again", listOf(true), reported)
        assertTrue("Ctrl does not look latched", button.isToggleActive)
    }

    @Test
    fun aSwipeThatStartsSlowlyOnAModifierLeavesItAsItWas() {
        val slop = ViewConfiguration.get(instrumentation.targetContext).scaledTouchSlop
        for (latched in listOf(false, true)) {
            val button = key(value = "Ctrl")
            onMain { button.isToggleActive = latched }
            reported.clear()
            val down = SystemClock.uptimeMillis()
            onMain { button.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, 10f)) }
            restPastTheLongPress()
            onMain {
                val now = SystemClock.uptimeMillis()
                button.dispatchTouchEvent(event(down, now, MotionEvent.ACTION_MOVE, 10f + slop * 4))
                button.dispatchTouchEvent(event(down, now, MotionEvent.ACTION_CANCEL, 10f + slop * 4))
            }
            instrumentation.waitForIdleSync()

            assertEquals("a swipe from Ctrl latched=$latched left it switched", latched, button.isToggleActive)
            assertEquals("the row was not told the latch went back", latched, reported.last())
        }
    }

    /**
     * A swipe by a second finger while the first holds Ctrl lit leaves it lit,
     * on the row and on the page, and the same swipe by the finger on Ctrl puts
     * it back.
     *
     * The pager drags with the finger that went down last, and taking the drag
     * cancels every key under a finger, the held Ctrl among them. That cancel
     * used to switch Ctrl back whichever finger swiped, so holding Ctrl with one
     * thumb and swiping to F5 with the other ran F5 where it had run Ctrl+F5.
     */
    @Test
    fun aSwipeByAnotherFingerLeavesAHeldModifierLatched() {
        for (byAnotherFinger in listOf(true, false)) {
            inRow { row, page ->
                val ctrl = ctrlOf(row)
                val pager = find(row, "pager") { it is ViewPager2 } as ViewPager2
                val held = middleOf(row, ctrl)
                val other = middleOf(row, find(row, "Tab") { it is ExtraKeyButton && it.keyValue == "Tab" })
                // Past the pager's own slop: ViewPager2 gives its RecyclerView
                // the paging slop, twice the touch slop.
                val swipe = ViewConfiguration.get(row.context).scaledPagingTouchSlop * 3f
                val down = SystemClock.uptimeMillis()
                onMain { row.dispatchTouchEvent(fingers(down, MotionEvent.ACTION_DOWN, held)) }
                restPastTheLongPress()
                var lit = false
                var pushed = false
                onMain {
                    lit = ctrl.isToggleActive
                    pushed = page.ctrl
                }
                assertTrue("control: the hold did not latch Ctrl on the row and the page", lit && pushed)

                onMain {
                    if (byAnotherFinger) {
                        row.dispatchTouchEvent(fingers(down, pointerDown(1), held, other))
                        row.dispatchTouchEvent(
                            fingers(down, MotionEvent.ACTION_MOVE, held, other.first + swipe to other.second),
                        )
                    } else {
                        row.dispatchTouchEvent(fingers(down, MotionEvent.ACTION_MOVE, held.first + swipe to held.second))
                    }
                }
                var dragging = false
                onMain {
                    dragging = pager.scrollState == ViewPager2.SCROLL_STATE_DRAGGING
                    lit = ctrl.isToggleActive
                    pushed = page.ctrl
                }
                assertTrue("control: the pager did not take the drag, so nothing cancelled the hold", dragging)
                if (byAnotherFinger) {
                    assertTrue(
                        "a second finger's swipe switched off the Ctrl held under the first: " +
                            "the row shows Ctrl=$lit and the page holds Ctrl=$pushed",
                        lit && pushed,
                    )
                } else {
                    assertFalse(
                        "a swipe by the finger holding Ctrl left it latched: " +
                            "the row shows Ctrl=$lit and the page holds Ctrl=$pushed",
                        lit || pushed,
                    )
                }
            }
        }
    }

    /**
     * Holding a modifier past the long press and then having the row repacked
     * under the finger, as resizing a split-screen pane does, leaves it on both
     * sides as it was before the touch, like any other touch that ends in a
     * cancel.
     *
     * The repack swaps the pager's adapter, the swap removes the page under the
     * finger and cancels the hold from inside it, and the key switches the latch
     * back through the row, which pushes it. The row used to write the latches
     * back after the swap, unpushed: from off, it showed Ctrl lit over a page
     * holding none, with nothing polling to notice, and from on the reverse.
     */
    @Test
    fun aRepackUnderAHeldModifierLeavesTheRowAndThePageAgreeing() {
        for (latched in listOf(false, true)) {
            inRow { row, page ->
                val ctrl = ctrlOf(row)
                if (latched) onMain { ctrl.performClick() }
                press(row, ctrl)
                restPastTheLongPress()
                var held = latched
                var pushed = latched
                onMain {
                    held = ctrl.isToggleActive
                    pushed = page.ctrl
                }
                assertEquals("control: the hold did not switch Ctrl", !latched, held)
                assertEquals("control: the hold was not pushed to the page", !latched, pushed)

                repack(row)
                settle()

                val rebuilt = ctrlOf(row)
                var shown = false
                onMain {
                    shown = rebuilt.isToggleActive
                    pushed = page.ctrl
                }
                assertEquals(
                    "after a repack under a hold from latched=$latched the row shows Ctrl=$shown " +
                        "and the page holds Ctrl=$pushed",
                    shown,
                    pushed,
                )
                assertEquals("the cancelled hold did not put Ctrl back", latched, shown)
            }
        }
    }

    /**
     * A trackpad drag the repack cuts short ends as every drag does, spending
     * the latch on both sides. The trackpad's own cancel ends the drag from
     * inside the swap, and the row's reset used to find an adapter holding no
     * latch there: Ctrl stayed lit and held after the drag, with the poll
     * stopped.
     */
    @Test
    fun aRepackUnderATrackpadDragSpendsTheLatchAsAnyDragEnd() {
        inRow { row, page ->
            val ctrl = ctrlOf(row)
            onMain { ctrl.performClick() }
            var pushed = false
            onMain { pushed = page.ctrl }
            assertTrue("control: latching Ctrl was not pushed to the page", pushed)
            press(row, find(row, "trackpad") { it is GestureTrackpad })

            repack(row)
            settle()

            val rebuilt = ctrlOf(row)
            var shown = true
            onMain {
                shown = rebuilt.isToggleActive
                pushed = page.ctrl
            }
            assertEquals("a drag a repack ended left Ctrl latched on the row", false, shown)
            assertEquals("a drag a repack ended left Ctrl held on the page", false, pushed)
        }
    }

    /**
     * Runs [body] with a row inside a real window, its pages laid out and its
     * poll running, and a [FlagPage] behind its injector.
     *
     * [ToolchainActivity] hosts it for the reason `KeyRowAccessibilityInstrumentedTest`
     * gives: it neither runs first-run setup nor waits on the editor server.
     */
    private fun inRow(body: (ExtraKeyRow, FlagPage) -> Unit) {
        ActivityScenario.launch(ToolchainActivity::class.java).use { scenario ->
            lateinit var row: ExtraKeyRow
            lateinit var page: FlagPage
            scenario.onActivity { activity ->
                page = FlagPage(activity)
                row = ExtraKeyRow(activity).apply { keyInjector = KeyInjector(page) }
                activity.addContentView(
                    row,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            instrumentation.waitForIdleSync()
            body(row, page)
        }
    }

    /** The first view under [root] that [match] accepts. */
    private fun find(root: View, what: String, match: (View) -> Boolean): View {
        fun search(view: View): View? = when {
            match(view) -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { search(view.getChildAt(it)) }
            else -> null
        }
        var found: View? = null
        onMain { found = search(root) }
        assertNotNull("the row has no $what bound", found)
        return found!!
    }

    private fun ctrlOf(row: View) =
        find(row, "Ctrl") { it is ExtraKeyButton && it.keyValue == "Ctrl" } as ExtraKeyButton

    /** The middle of [view] in [row]'s coordinates, checked to be on the page showing. */
    private fun middleOf(row: View, view: View): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var bounds = 0f to 0f
        onMain {
            val origin = IntArray(2).also(row::getLocationInWindow)
            val at = IntArray(2).also(view::getLocationInWindow)
            x = at[0] - origin[0] + view.width / 2f
            y = at[1] - origin[1] + view.height / 2f
            bounds = row.width.toFloat() to row.height.toFloat()
        }
        assertTrue(
            "control: the ${view.javaClass.simpleName} found is not on the page showing, so no finger reaches it",
            x in 0f..bounds.first && y in 0f..bounds.second,
        )
        return x to y
    }

    /**
     * A finger put down on the middle of [view], dispatched through the row so
     * every parent on the way records where it went, as for a real finger. It
     * is never lifted: the repack cancels its touch.
     */
    private fun press(row: View, view: View) {
        val (x, y) = middleOf(row, view)
        val down = SystemClock.uptimeMillis()
        onMain { row.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, x, y)) }
    }

    /** One event of a gesture with a finger at each of [at], their ids counting up from 0. */
    private fun fingers(downTime: Long, action: Int, vararg at: Pair<Float, Float>): MotionEvent {
        val properties = Array(at.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(at.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = at[i].first
                y = at[i].second
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, at.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    /** `ACTION_POINTER_DOWN` for the finger at [index], with the index packed in. */
    private fun pointerDown(index: Int): Int =
        MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /**
     * Time for two poll ticks and their answers, so a row the page disagrees
     * with would have been corrected by now had anything been polling.
     */
    private fun settle() {
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
    }

    /** A resize that pages the row differently, so the pager's adapter is swapped. */
    private fun repack(row: View) {
        var current = 0
        onMain { current = row.resources.configuration.smallestScreenWidthDp }
        val before = KeyPages.forSmallestWidthDp(current)
        val targetDp = if (before.size == KeyPages.defaults.size) 320 else 600
        assertTrue(
            "control: ${targetDp}dp packs the row as this device does, so nothing is repacked",
            KeyPages.forSmallestWidthDp(targetDp).size != before.size,
        )
        onMain {
            row.dispatchConfigurationChanged(
                Configuration(row.resources.configuration).apply { smallestScreenWidthDp = targetDp },
            )
        }
        instrumentation.waitForIdleSync()
    }

    /**
     * A page holding the three flags the row pushes, `window.__vscodroid`'s,
     * and answering the row's poll from them a turn later, as a page does.
     * Nothing else of a page: no script it is sent is run.
     */
    private class FlagPage(context: Context) : WebView(context) {
        var ctrl = false
        private var alt = false
        private var shift = false

        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            val pushed = PUSH.find(script)
            if (pushed != null) {
                ctrl = pushed.groupValues[1] == "true"
                alt = pushed.groupValues[2] == "true"
                shift = pushed.groupValues[3] == "true"
            } else if (resultCallback != null) {
                val answer = "{\"c\":$ctrl,\"a\":$alt,\"s\":$shift}"
                Handler(Looper.getMainLooper()).post { resultCallback.onReceiveValue(answer) }
            }
        }

        private companion object {
            /** `KeyInjector.setModifierState`'s script, which is the only push there is. */
            val PUSH = Regex("""\.ctrl=(\w+),window\.__vscodroid\.alt=(\w+),window\.__vscodroid\.shift=(\w+)""")
        }
    }
}
