package com.vscodroid.keyboard

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a second finger does to a drag on the trackpad.
 *
 * The pad reads deltas off pointer index 0 and used to branch on the raw
 * `event.action`, which carries the pointer index in its high byte:
 * ACTION_POINTER_DOWN and ACTION_POINTER_UP matched nothing and fell through, and
 * once the first finger lifted, index 0 became the finger that was left. The next
 * MOVE then reported the gap between two fingers as one delta, and the
 * accumulator pays a delta out as one arrow per threshold crossed, so a single
 * frame emitted a burst of them and the caret jumped.
 *
 * Off the JVM, because none of this is reachable from one: [GestureTrackpad] is a
 * `View` whose initialiser reaches colours, strings and display metrics, and the
 * events are real [MotionEvent]s with two pointers in them.
 *
 * Not run by CI, which compiles the instrumented tests but has no emulator to run
 * them on.
 */
@RunWith(AndroidJUnit4::class)
class GestureTrackpadTouchInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private val downTime = 1000L

    /** One event carrying [coords], keyed by the pointer ids in [ids]. */
    private fun event(action: Int, ids: List<Int>, coords: List<Pair<Float, Float>>): MotionEvent {
        val properties = Array(ids.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = ids[i]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val points = Array(coords.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = coords[i].first
                y = coords[i].second
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime, downTime + 16, action, ids.size, properties, points,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    /** `ACTION_POINTER_UP` for the pointer at [index], with the index packed in. */
    private fun pointerUp(index: Int): Int =
        MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun pointerDown(index: Int): Int =
        MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /**
     * A pad inside a parent, measured and laid out.
     *
     * The parent is not scenery: the drag calls
     * `parent.requestDisallowInterceptTouchEvent` on the way in and again on the
     * way out, so a pad with no parent throws on ACTION_DOWN. In the app that
     * parent is the page's `LinearLayout`, which is what the pad has to talk out
     * of to keep the pager from stealing the drag.
     */
    private fun trackpad(sent: MutableList<String>, ended: () -> Unit): GestureTrackpad {
        lateinit var pad: GestureTrackpad
        onMain {
            pad = GestureTrackpad(context).apply {
                onArrowKey = { sent.add(it) }
                onDragEnd = { ended() }
            }
            val parent = android.widget.FrameLayout(context)
            parent.addView(pad, android.widget.FrameLayout.LayoutParams(300, 200))
            val width = android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY)
            val height = android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY)
            parent.measure(width, height)
            parent.layout(0, 0, 300, 200)
        }
        return pad
    }

    @Test
    fun aSecondFingerTakingOverDoesNotJumpTheCaret() {
        val sent = mutableListOf<String>()
        var drags = 0
        val pad = trackpad(sent) { drags++ }

        onMain {
            // One finger, moving slowly enough to earn nothing: the precise gear
            // wants 24dp a step.
            pad.onTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(0), listOf(10f to 10f)))
            pad.onTouchEvent(event(MotionEvent.ACTION_MOVE, listOf(0), listOf(12f to 10f)))

            // A second finger lands far away, then the first one lifts.
            pad.onTouchEvent(
                event(pointerDown(1), listOf(0, 1), listOf(12f to 10f, 260f to 10f)),
            )
            pad.onTouchEvent(
                event(pointerUp(0), listOf(0, 1), listOf(12f to 10f, 260f to 10f)),
            )

            // The surviving finger moves a little. Read off index 0, that little
            // move used to arrive as the whole 248px gap between the two fingers.
            pad.onTouchEvent(event(MotionEvent.ACTION_MOVE, listOf(1), listOf(264f to 10f)))

            // And then it lifts, which is how every such gesture actually ends.
            // The drag was already over, so this must report nothing: `endDrag`
            // is reached twice for one gesture and each call invokes onDragEnd
            // and hands the pager back its touch interception.
            pad.onTouchEvent(event(MotionEvent.ACTION_UP, listOf(1), listOf(264f to 10f)))
        }

        assertTrue(
            "the drag was handed to the second finger and paid out ${sent.size} arrows " +
                "for a 4px move: $sent",
            sent.isEmpty(),
        )
        assertEquals(
            "the tracked finger lifting has to end the drag, which is what clears a " +
                "latched modifier, and the last finger leaving must not end it a second " +
                "time: one gesture, one end",
            1,
            drags,
        )
    }

    @Test
    fun anUntrackedFingerLeavingDoesNotEndTheDrag() {
        // The other half. A second finger resting on the pad and lifting again is
        // not this drag's business, and ending the drag there would clear a
        // latched modifier the user is still holding.
        val sent = mutableListOf<String>()
        var drags = 0
        val pad = trackpad(sent) { drags++ }

        onMain {
            pad.onTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(0), listOf(10f to 10f)))
            pad.onTouchEvent(
                event(pointerDown(1), listOf(0, 1), listOf(10f to 10f, 260f to 10f)),
            )
            pad.onTouchEvent(
                event(pointerUp(1), listOf(0, 1), listOf(10f to 10f, 260f to 10f)),
            )
            // The original finger is still down and still tracked: a real move
            // still earns its arrow.
            pad.onTouchEvent(event(MotionEvent.ACTION_MOVE, listOf(0), listOf(300f to 10f)))
        }

        assertEquals("the drag ended when a finger that was not driving it lifted", 0, drags)
        assertTrue(
            "the tracked finger stopped being followed when another one lifted: $sent",
            sent.isNotEmpty() && sent.all { it == "ArrowRight" },
        )
    }
}
