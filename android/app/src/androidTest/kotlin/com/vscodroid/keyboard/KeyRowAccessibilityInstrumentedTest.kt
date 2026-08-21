package com.vscodroid.keyboard

import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.vscodroid.ToolchainActivity
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

    /**
     * Runs [body] with the view alive inside a real window.
     *
     * The window is not a convenience, and neither is the shape of this helper.
     *
     * A detached view yields an EMPTY node: measured on API 33 and 37, a button
     * with `isClickable`, a content description, `isSelected` and a state
     * description all set produced a node reading `clickable=false,
     * contentDescription=null, selected=false, stateDescription=null`.
     * `View.onInitializeAccessibilityNodeInfoInternal` fills almost nothing
     * without an attach info.
     *
     * That is also why [body] runs inside the scenario rather than the helper
     * returning a node. An earlier version closed the scenario first and handed
     * the view back; the first node, read while the activity lived, was
     * complete, and every node read afterwards was empty again. A test written
     * that way fails on the second assertion for a reason that has nothing to
     * do with the code it is about.
     *
     * The one thing that survives detachment is an action added through
     * `ViewCompat.addAccessibilityAction`, because that installs a delegate
     * which contributes to the node after the host has finished. That is why
     * the trackpad cases passed before any of this existed, and it is exactly
     * the accident this removes.
     *
     * [ToolchainActivity] hosts it: the only activity here that neither runs
     * first-run setup nor waits on the editor server.
     */
    private fun <T : android.view.View> inWindow(
        create: (android.content.Context) -> T,
        body: (view: T, node: () -> AccessibilityNodeInfo) -> Unit,
    ) {
        ActivityScenario.launch(ToolchainActivity::class.java).use { scenario ->
            lateinit var view: T
            scenario.onActivity { activity ->
                view = create(activity)
                activity.addContentView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onMain {
                assertTrue("the view never reached a window", view.isAttachedToWindow)
            }
            body(view) {
                lateinit var node: AccessibilityNodeInfo
                onMain { node = view.createAccessibilityNodeInfo()!! }
                node
            }
        }
    }

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
    fun aLatchedModifierSaysSoOnItsNode() {
        inWindow({ ctx ->
            ExtraKeyButton(ctx).apply {
                keyValue = "Ctrl"
                isToggle = true
                contentDescription = "Control modifier"
                isToggleActive = false
            }
        }) { button, node ->
            assertEquals(
                "the control for this test: with no description on the node the view is " +
                    "not really in a window, and the state assertions would mean nothing",
                "Control modifier",
                node().contentDescription?.toString(),
            )
            assertEquals(
                "an idle modifier should say it is off, not stay silent",
                "off",
                node().stateDescription?.toString(),
            )

            onMain { button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) }
            assertEquals(
                "a latched modifier does not say so, so a screen reader user can switch " +
                    "it on and cannot tell that they did",
                "on",
                node().stateDescription?.toString(),
            )

            onMain { button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) }
            assertEquals(
                "switching the modifier off is not published either",
                "off",
                node().stateDescription?.toString(),
            )
        }
    }

    @Test
    fun aPlainKeyPublishesNoState() {
        inWindow({ ctx ->
            ExtraKeyButton(ctx).apply {
                keyValue = "Tab"
                isToggle = false
                contentDescription = "Tab key"
                isToggleActive = false
            }
        }) { _, node ->
            assertEquals(
                "control: no description means no window, and the assertion below would " +
                    "pass for the wrong reason",
                "Tab key",
                node().contentDescription?.toString(),
            )
            assertNull(
                "a plain key carries a state description, which would be read out on " +
                    "every key",
                node().stateDescription,
            )
        }
    }

    @Test
    fun longPressingAKeyThroughAServiceOpensItsAlternates() {
        var opened: List<AlternateKey>? = null
        inWindow({ ctx ->
            ExtraKeyButton(ctx).apply {
                keyValue = "'"
                contentDescription = "Apostrophe"
                alternates = listOf(AlternateKey("\\", "\\"))
                onLongPressAction = { _, alts -> opened = alts }
            }
        }) { button, node ->
            assertTrue(
                "the node does not offer a long click, so a service is refused before the " +
                    "override can run; node actions: ${node().actionList.map { it.id }}",
                node().actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK },
            )

            var handled = false
            onMain {
                handled = button.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_LONG_CLICK,
                    null,
                )
            }
            assertTrue("the long click was refused", handled)
            assertEquals(
                "long pressing through a service did not open the alternates",
                listOf(AlternateKey("\\", "\\")),
                opened,
            )
        }
    }

    @Test
    fun aKeyWithNoAlternatesOffersNoLongClick() {
        inWindow({ ctx ->
            ExtraKeyButton(ctx).apply {
                keyValue = "Tab"
                contentDescription = "Tab key"
            }
        }) { _, node ->
            assertEquals(
                "control: without a description the view is not in a window",
                "Tab key",
                node().contentDescription?.toString(),
            )
            assertTrue(
                "a key with nothing to show offers a long click, which would open an " +
                    "empty popup",
                node().actionList.none { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK },
            )
        }
    }

    /**
     * Lays out one page at a given row width and returns each child's width in dp.
     *
     * Built from [keyLayoutParams] and [padLayoutParams] rather than from params
     * written here, so the numbers below come from the same call the adapter
     * makes. A page is a plain horizontal `LinearLayout` (KeyPageAdapter's
     * `PageViewHolder`), and the row width is fixed with an EXACTLY spec because
     * that is what ViewPager2 hands it, and it is the reason `minWidth` on a key
     * is inert.
     */
    private fun keyWidthsDp(rowWidthDp: Int, keys: Int, withPad: Boolean): List<Float> {
        val density = context.resources.displayMetrics.density
        val widths = mutableListOf<Float>()
        onMain {
            val row = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            repeat(keys) { row.addView(ExtraKeyButton(context), keyLayoutParams()) }
            if (withPad) row.addView(GestureTrackpad(context), padLayoutParams())

            val widthPx = (rowWidthDp * density).toInt()
            row.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec((56 * density).toInt(), android.view.View.MeasureSpec.EXACTLY),
            )
            row.layout(0, 0, widthPx, (56 * density).toInt())
            for (i in 0 until row.childCount) {
                if (row.getChildAt(i) is ExtraKeyButton) widths.add(row.getChildAt(i).width / density)
            }
        }
        return widths
    }

    @Test
    fun aKeyGetsTheWholeShareOfTheRow() {
        // The property the fix guarantees, and the only one that holds at every
        // screen width: nothing is subtracted from a key before the finger gets
        // it. A margin would show up here as a shortfall on every key.
        val density = context.resources.displayMetrics.density
        for (rowDp in listOf(320, 360, 411, 448, 800)) {
            val widths = keyWidthsDp(rowDp, keys = 8, withPad = false)
            assertEquals("expected eight keys at ${rowDp}dp", 8, widths.size)
            val total = widths.sum()
            assertTrue(
                "keys at ${rowDp}dp add up to ${total}dp, so ${rowDp - total}dp of the row " +
                    "is not on any key. A gap has moved back onto the layout params, and " +
                    "every dp of it comes off a touch target",
                kotlin.math.abs(total - rowDp) <= 8f / density,
            )
        }
    }

    @Test
    fun keysClearFortyEightDpOnAMainstreamPhone() {
        // 411dp is this emulator and the common Pixel width. Narrower phones do
        // not clear the guideline on width even with the whole row shared out,
        // and that needs a different decision: fewer keys per page, which means
        // more pages. This case pins what the fix does reach, so a regression
        // that puts the gap back is caught by a number rather than by a shape.
        val page1 = keyWidthsDp(411, keys = 7, withPad = true)
        val others = keyWidthsDp(411, keys = 8, withPad = false)

        assertTrue(
            "page 1 keys are ${page1.min()}dp beside the trackpad, under the 48dp target",
            page1.min() >= 48f,
        )
        assertTrue(
            "pages 2 to 5 keys are ${others.min()}dp, under the 48dp target",
            others.min() >= 48f,
        )
    }

    @Test
    fun theGapBetweenKeysIsStillDrawn() {
        // The gap did not disappear, it moved. If the inset goes too, the keys
        // butt together and the row reads as one slab.
        lateinit var bg: android.graphics.drawable.Drawable
        onMain {
            bg = ExtraKeyButton(context).background
        }
        assertTrue(
            "the key background is no longer inset, so the 2dp gap is gone from the " +
                "drawing as well as from the layout: it is ${bg.javaClass.simpleName}",
            bg is android.graphics.drawable.InsetDrawable,
        )
    }

    @Test
    fun theTrackpadOffersOneActionPerArrow() {
        inWindow({ ctx -> GestureTrackpad(ctx) }) { _, node ->
            val offered = node().actionList.mapNotNull { it.label?.toString() }
            // Resolved here rather than compared as ids: what the node carries is
            // the text the platform will read out, so this is the one place the
            // resource and the spoken label are checked against each other.
            for ((label, _) in ARROW_ACTIONS.map { context.getString(it.first) to it.second }) {
                assertTrue(
                    "no accessibility action is labelled \"$label\"; the node offers $offered",
                    offered.contains(label),
                )
            }
        }
    }

    @Test
    fun performingAnArrowActionSendsThatArrowAndEndsTheDrag() {
        val sent = mutableListOf<String>()
        var dragsEnded = 0
        inWindow({ ctx ->
            GestureTrackpad(ctx).apply {
                onArrowKey = { sent.add(it) }
                onDragEnd = { dragsEnded++ }
            }
        }) { trackpad, node ->
            for ((label, direction) in ARROW_ACTIONS.map { context.getString(it.first) to it.second }) {
                val action = node().actionList.firstOrNull { it.label?.toString() == label }
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

}
