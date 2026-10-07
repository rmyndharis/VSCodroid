package com.vscodroid.keyboard

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vscodroid.R
import org.junit.Assert.assertEquals
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

    private fun event(downTime: Long, eventTime: Long, action: Int, x: Float): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, x, 10f, 0)

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
}
