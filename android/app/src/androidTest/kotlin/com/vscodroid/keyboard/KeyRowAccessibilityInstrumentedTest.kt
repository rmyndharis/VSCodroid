package com.vscodroid.keyboard

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What an accessibility service can actually do with the extra key row.
 *
 * The JVM suite cannot answer this: both classes are `View` subclasses whose
 * initialisers reach resources on the first line, so the unit tests next door
 * assert the wiring by reading the source instead. These run on a device and
 * take the same route a screen reader takes -- read the node, find the action,
 * perform it by id -- so what they pin is the behaviour rather than the shape
 * of the code that produces it.
 *
 * Not run by CI, which compiles the instrumented tests but has no emulator to
 * run them on. They are here to be run against a device when this area changes,
 * and to stop compiling if the API they use goes away.
 */
@RunWith(AndroidJUnit4::class)
class KeyRowAccessibilityInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Runs on the main thread and waits.
     *
     * [ExtraKeyButton] builds a `GestureDetector` in its initialiser, which
     * needs a Looper, and the instrumentation thread has none. A screen reader
     * also reaches these views on the main thread, so this is the honest place
     * to drive them from, not a workaround for the test's convenience.
     */
    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun activatingAKeyDeliversItsPress() {
        var delivered: Pair<String, Boolean>? = null
        var handled = false
        lateinit var button: ExtraKeyButton
        onMain {
            button = ExtraKeyButton(context).apply {
                keyValue = "Tab"
                onKeyAction = { key, active -> delivered = key to active }
            }
            handled = button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
        }

        assertTrue(
            "the key advertises no click, so a service would never offer to activate it",
            button.isClickable,
        )

        assertTrue("the click action was refused", handled)
        assertEquals(
            "activating the key through an accessibility service typed nothing",
            "Tab" to true,
            delivered,
        )
    }

    @Test
    fun activatingAModifierReportsTheFlippedState() {
        var delivered: Pair<String, Boolean>? = null
        lateinit var button: ExtraKeyButton
        onMain {
            button = ExtraKeyButton(context).apply {
                keyValue = "Ctrl"
                isToggle = true
                onKeyAction = { key, active -> delivered = key to active }
            }
            button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
        }
        assertEquals("first activation should switch the modifier on", "Ctrl" to true, delivered)

        onMain { button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) }
        assertEquals("second activation should switch it off again", "Ctrl" to false, delivered)
    }

    @Test
    fun theTrackpadOffersOneActionPerArrow() {
        lateinit var trackpad: GestureTrackpad
        val node = AccessibilityNodeInfo.obtain()
        onMain {
            trackpad = GestureTrackpad(context)
            trackpad.onInitializeAccessibilityNodeInfo(node)
        }

        val offered = node.actionList.mapNotNull { it.label?.toString() }
        for ((label, _) in ARROW_ACTIONS) {
            assertTrue(
                "no accessibility action is labelled \"$label\"; the node offers $offered",
                offered.contains(label),
            )
        }
    }

    @Test
    fun performingAnArrowActionSendsThatArrowAndEndsTheDrag() {
        val sent = mutableListOf<String>()
        var dragsEnded = 0
        lateinit var trackpad: GestureTrackpad
        val node = AccessibilityNodeInfo.obtain()
        onMain {
            trackpad = GestureTrackpad(context).apply {
                onArrowKey = { sent.add(it) }
                onDragEnd = { dragsEnded++ }
            }
            trackpad.onInitializeAccessibilityNodeInfo(node)
        }

        for ((label, direction) in ARROW_ACTIONS) {
            val action = node.actionList.firstOrNull { it.label?.toString() == label }
            assertNotNull("no action labelled \"$label\"", action)

            var handled = false
            onMain { handled = trackpad.performAccessibilityAction(action!!.id, null) }
            assertTrue("the action \"$label\" was refused", handled)
            assertEquals(
                "the action \"$label\" sent the wrong arrow",
                direction,
                sent.last(),
            )
        }

        assertEquals("one arrow per action, no repeats", ARROW_ACTIONS.size, sent.size)
        assertEquals(
            "each action must end the drag, which is what clears a latched modifier",
            ARROW_ACTIONS.size,
            dragsEnded,
        )
    }
}
