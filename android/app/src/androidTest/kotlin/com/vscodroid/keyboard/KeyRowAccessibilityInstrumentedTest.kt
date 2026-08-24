package com.vscodroid.keyboard

import android.content.res.Configuration
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
import com.vscodroid.R
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
                alternates = listOf(AlternateKey("\\", "\\", R.string.key_desc_backslash))
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
                listOf(AlternateKey("\\", "\\", R.string.key_desc_backslash)),
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
    fun keysClearFortyEightDpOnEveryWidthTheRowIsPagedFor() {
        // The measured half of KeyPageConfigTest's arithmetic: real
        // LinearLayout, real weights, real remeasure. Narrower phones used to be
        // out of scope here, and the note that said so was the whole record of
        // the gap: at 360dp page 1 gave 42.4dp a key and the other pages 45.0dp,
        // on every key, on one of the most common portrait widths there is.
        // KeyPages.forSmallestWidthDp splits the pages instead, and this walks
        // whatever it hands back rather than a count written here.
        val density = context.resources.displayMetrics.density
        // One pixel of slack. LinearLayout shares a weighted row out in integer
        // pixels and gives the remainder to the last child, so a page that
        // divides exactly in dp can still come back a pixel short of it.
        val floor = 48f - 1f / density
        for (widthDp in listOf(320, 360, 411, 448)) {
            val pages = KeyPages.forSmallestWidthDp(widthDp)
            // The control. An empty or shortened list walks fewer pages and
            // reports every key wide enough, which reads exactly like a row that
            // fits at every width.
            assertTrue(
                "at ${widthDp}dp the row was paged into ${pages.size} pages, fewer than the " +
                    "${KeyPages.defaults.size} KeyPageConfig writes out, so keys have gone " +
                    "missing and the widths below are measured on what is left",
                pages.size >= KeyPages.defaults.size,
            )
            for ((index, page) in pages.withIndex()) {
                val keys = page.items.count { it is KeyItem.Button }
                val withPad = page.items.any { it is KeyItem.GesturePad }
                val widths = keyWidthsDp(widthDp, keys = keys, withPad = withPad)
                assertEquals("expected $keys keys on page ${index + 1}", keys, widths.size)
                assertTrue(
                    "at ${widthDp}dp, page ${index + 1} measures ${widths.min()}dp a key, " +
                        "under the 48dp target",
                    widths.min() >= floor,
                )
            }
        }
    }

    @Test
    fun aKeyCallsItselfAButton() {
        // TalkBack derives the spoken role from the node's class name, and
        // ExtraKeyButton extends AppCompatTextView, so every key was announced as
        // its description alone: "Curly braces", with nothing saying it can be
        // activated. The node was a button in every other respect, offering
        // ACTION_CLICK and ACTION_LONG_CLICK and honouring both.
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
            assertEquals(
                "the key reports itself as a plain text view, so a screen reader names " +
                    "it without saying it is a button",
                android.widget.Button::class.java.name,
                node().className?.toString(),
            )
        }
    }

    @Test
    fun anAlternateCallsItselfAButton() {
        // The same gap, one layer down, and the layer that matters more: the
        // long-press popup is the only route to `'`, `\`, `~` and `)`, so an
        // entry announced as a text view is the only way those characters are
        // offered to a screen reader at all.
        inWindow({ ctx ->
            AlternateKeyView(ctx).apply {
                text = "\\"
                contentDescription = "Backslash"
            }
        }) { _, node ->
            assertEquals(
                "control: without a description the view is not in a window",
                "Backslash",
                node().contentDescription?.toString(),
            )
            assertEquals(
                "an alternate reports itself as a plain text view, so a screen reader " +
                    "names the character without saying it can be activated",
                android.widget.Button::class.java.name,
                node().className?.toString(),
            )
        }
    }

    /** The first key under [root] that types [keyValue], or null. */
    private fun findKey(root: android.view.View, keyValue: String): ExtraKeyButton? = when {
        root is ExtraKeyButton -> root.takeIf { it.keyValue == keyValue }
        root is ViewGroup -> (0 until root.childCount)
            .firstNotNullOfOrNull { findKey(root.getChildAt(it), keyValue) }
        else -> null
    }

    /** The page-indicator band: the row's only child that says anything. */
    private fun indicator(row: ExtraKeyRow): android.view.View? =
        (0 until row.childCount)
            .map { row.getChildAt(it) }
            .firstOrNull { it is android.widget.LinearLayout && it.contentDescription != null }

    @Test
    fun latchingAModifierDoesNotChangeTheHeightOfTheRow() {
        // Measured on an API 37 device before the band was given a floor: the row
        // went from 184px to 202px the moment Ctrl was latched, and the WebView
        // beside it from 1215px to 1197px. activity_main.xml gives the row
        // wrap_content above a WebView that is 0dp with a weight, so those
        // pixels come off the editor and go back on every latch and again on
        // every key that spends one: a Chromium viewport resize and a full
        // workbench relayout on the app's hottest interaction.
        //
        // Off the JVM because it is a measure pass on real views, which is the
        // one thing the unit suite next door cannot see.
        val density = context.resources.displayMetrics.density
        val widthPx = (411 * density).toInt()
        fun heightOf(row: ExtraKeyRow): Int {
            row.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            return row.measuredHeight
        }

        var idle = 0
        var latched = 0
        var ctrl: ExtraKeyButton? = null
        onMain {
            val row = ExtraKeyRow(context)
            idle = heightOf(row)
            row.layout(0, 0, widthPx, idle)
            ctrl = findKey(row, "Ctrl")
            ctrl?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
            latched = heightOf(row)
        }

        assertTrue("control: the row measured to nothing at all", idle > 0)
        assertNotNull(
            "control: the row never bound its first page, so nothing here latched anything",
            ctrl,
        )
        assertTrue("control: activating Ctrl did not latch it", ctrl!!.isToggleActive)
        assertEquals(
            "latching a modifier took the row from ${idle}px to ${latched}px, so the " +
                "WebView above it is resized every time one is pressed or spent",
            idle,
            latched,
        )
    }

    @Test
    fun aLatchedModifierIsSaidByTheNodeThatSpeaks() {
        // The badge beside the dots draws the latch and says nothing: it sits
        // inside the band, the band carries a description of its own, and a
        // description on a non-actionable child of a speaking ViewGroup is
        // collapsed into the parent's and never read. So the one node reports
        // both, and this reads it the way a screen reader would.
        inWindow({ ctx -> ExtraKeyRow(ctx) }) { row, _ ->
            val band = indicator(row)
            assertNotNull("the row publishes no page indicator at all", band)
            // Read off the row's own configuration, which is what it paged
            // itself for: a wide device gets five pages and a narrow one more.
            val pageCount = KeyPages
                .forSmallestWidthDp(row.resources.configuration.smallestScreenWidthDp).size

            var idle: String? = null
            onMain {
                idle = band!!.createAccessibilityNodeInfo()?.contentDescription?.toString()
            }
            assertEquals(
                "control: the band does not name the page it is showing, so the assertion " +
                    "below is not reading the indicator",
                context.getString(R.string.key_page_indicator, 1, pageCount),
                idle,
            )

            val ctrl = findKey(row, "Ctrl")
            assertNotNull("the row never bound its first page, so nothing can be latched", ctrl)
            var held: String? = null
            onMain {
                ctrl!!.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                held = band!!.createAccessibilityNodeInfo()?.contentDescription?.toString()
            }
            assertEquals(
                "a latched Ctrl reaches no node a screen reader stops on, so a user who " +
                    "has swiped away from page 1 is told the page and not the modifier",
                context.getString(R.string.key_page_indicator_held, 1, pageCount, "ctrl"),
                held,
            )
        }
    }

    @Test
    fun aResizeRepacksTheRowWithoutSpendingALatchedModifier() {
        // MainActivity handles smallestScreenSize itself, so dropping a session
        // into a split-screen pane resizes the window without recreating the
        // activity, and nothing else rebuilds this row. Until it repacked, keys
        // sized for a full-screen window went on dividing a pane half that wide,
        // which is the touch target KeyPages.forSmallestWidthDp exists to hold.
        //
        // Off the JVM for the reason the whole file is: the row's initialiser
        // reaches resources on its first line, and this drives a real adapter
        // swap through a real ViewPager2.
        inWindow({ ctx -> ExtraKeyRow(ctx) }) { row, _ ->
            val band = indicator(row)
            assertNotNull("the row publishes no page indicator at all", band)

            val before = KeyPages
                .forSmallestWidthDp(row.resources.configuration.smallestScreenWidthDp)
            // Whichever of the two packings this device is not already showing,
            // so the resize below has something to rebuild whatever it runs on.
            val targetDp = if (before.size == KeyPages.defaults.size) 320 else 600
            val after = KeyPages.forSmallestWidthDp(targetDp)
            assertTrue(
                "control: ${targetDp}dp packs into ${after.size} pages exactly like this " +
                    "device does, so nothing below exercises a rebuild",
                after.size != before.size,
            )

            val ctrl = findKey(row, "Ctrl")
            assertNotNull("the row never bound its first page, so nothing can be latched", ctrl)
            onMain { ctrl!!.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) }

            val resized = Configuration(row.resources.configuration).apply {
                smallestScreenWidthDp = targetDp
            }
            onMain { row.dispatchConfigurationChanged(resized) }
            // The adapter swap requests a layout; the pages are bound on the
            // traversal that follows, not inside the call above.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            var held: String? = null
            onMain { held = band!!.createAccessibilityNodeInfo()?.contentDescription?.toString() }
            assertEquals(
                "the resized row still reports the page count it was built with, so every " +
                    "key on it is dividing a window it was not sized for",
                context.getString(R.string.key_page_indicator_held, 1, after.size, "ctrl"),
                held,
            )

            val latched = findKey(row, "Ctrl")
            assertNotNull("the rebuilt row never bound its first page", latched)
            assertTrue(
                "the rebuild dropped the toggle map, so the row paints Ctrl idle while " +
                    "KeyInjector still holds it and the next key goes out as a chord",
                latched!!.isToggleActive,
            )
        }
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
            // The pad draws no text, so this description is the only thing a
            // screen reader can say when focus lands on it. Without it the four
            // actions below hang off a node announced as an unlabelled view, and
            // a user has no reason to open the actions menu that holds the only
            // route to the arrows.
            assertEquals(
                "the trackpad node carries no usable label, so the arrow actions below " +
                    "belong to something the user cannot identify",
                context.getString(R.string.trackpad_description),
                node().contentDescription?.toString(),
            )

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
