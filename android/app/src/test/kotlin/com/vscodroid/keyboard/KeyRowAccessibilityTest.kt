package com.vscodroid.keyboard

import com.vscodroid.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What a user who cannot see the screen can reach on the extra key row.
 *
 * Two routes, and neither existed before. A screen reader activates a view
 * through ACTION_CLICK, which lands on `View.performClick`; the row's keys are
 * `isClickable` and so have always advertised that action, but the presses came
 * out of a [GestureDetector] and nothing ever called `performClick`, so the
 * offer did nothing. The trackpad was worse: its arrows are earned only by a
 * drag, touch exploration consumes the gesture stream before onTouchEvent sees
 * it, and the arrow buttons that would have been the fallback were deleted when
 * the pad replaced them. So there was no route to a left or right cursor move
 * anywhere in the app.
 *
 * Both fixes live in `View` subclasses whose initialisers reach resources on
 * their first line, so no test here can construct one. The split follows what
 * this package already does with `pressedState`: the data half is asserted
 * directly, and the wiring half is asserted by reading the source, with a
 * control first so that a scan which has stopped matching cannot pass by
 * finding nothing.
 */
class KeyRowAccessibilityTest {

    private val mainSources = File("src/main/kotlin/com/vscodroid/keyboard")

    private fun source(name: String): String {
        val file = File(mainSources, name)
        assertTrue(
            file.isFile,
            "$name is not at ${file.absolutePath}; this test would otherwise pass " +
                "by reading nothing",
        )
        return file.readText()
    }

    /** Source with comment lines dropped, so prose naming a call is not a call. */
    private fun code(name: String): List<String> =
        source(name).lines().filterNot {
            val start = it.trimStart()
            start.startsWith("//") || start.startsWith("*") || start.startsWith("/*")
        }

    @Nested
    inner class TrackpadArrowActions {

        @Test
        fun `offers exactly the four arrows, each named once`() {
            assertEquals(4, ARROW_ACTIONS.size, "one action per arrow, no more")
            assertEquals(
                4,
                ARROW_ACTIONS.map { it.second }.toSet().size,
                "two actions send the same arrow: $ARROW_ACTIONS",
            )
            assertEquals(
                4,
                ARROW_ACTIONS.map { it.first }.toSet().size,
                "two actions share a label resource, so a screen reader would read the " +
                    "same entry twice: $ARROW_ACTIONS",
            )
        }

        @Test
        fun `sends only arrows the key table knows`() {
            // The failure this catches is silent. KeyInjector routes anything
            // that is not a single character through announceKeystroke, which
            // asks KeyMapping for the key code; a direction the table does not
            // hold would be dispatched with a code derived from its first
            // letter, so "ArrowLef" would arrive in the page as the letter A.
            for ((label, direction) in ARROW_ACTIONS) {
                val def = KeyMapping.getKeyDef(direction)
                assertNotNull(
                    def,
                    "the action \"$label\" sends \"$direction\", which KeyMapping does " +
                        "not hold; it would reach the page as a letter instead of an arrow",
                )
                assertEquals(
                    direction,
                    def!!.key,
                    "KeyMapping resolves \"$direction\" to a different key",
                )
            }
        }

        @Test
        fun `covers every arrow a drag can produce`() {
            // The two routes have to stay in step. A drag earns its arrows from
            // string literals in TrackpadGesture; if a fifth direction is ever
            // added there, this fails rather than leaving the assistive route
            // quietly one short. Read from the producer rather than restated
            // here, because a restated list is only as good as its last edit.
            val fromDrag = Regex("\"(Arrow[A-Za-z]+)\"")
                .findAll(code("TrackpadGesture.kt").joinToString("\n"))
                .map { it.groupValues[1] }
                .toSet()

            assertEquals(
                4,
                fromDrag.size,
                "the scan found ${fromDrag.size} arrow literals in TrackpadGesture.kt, " +
                    "not the four a drag emits, so it is not reading that file and its " +
                    "verdict below is worth nothing. It found: $fromDrag",
            )
            assertEquals(
                fromDrag,
                ARROW_ACTIONS.map { it.second }.toSet(),
                "a drag and the accessibility actions no longer send the same set of " +
                    "arrows, so one route can do something the other cannot",
            )
        }

        @Test
        fun `every action is registered on the view, and ends the drag`() {
            val lines = code("GestureTrackpad.kt")
            val body = lines.joinToString("\n")

            assertTrue(
                body.contains("ViewCompat.addAccessibilityAction"),
                "GestureTrackpad no longer registers accessibility actions, so the " +
                    "list in ARROW_ACTIONS is data nothing reads and the arrows are " +
                    "again reachable only by a drag",
            )
            val registration = lines.indexOfFirst {
                it.contains("for ((label, direction) in ARROW_ACTIONS)")
            }
            assertTrue(
                registration >= 0,
                "the registration no longer walks ARROW_ACTIONS, so the list and what " +
                    "the view offers can disagree while every assertion above stays green",
            )

            // Scoped to the loop body, not the file. `onDragEnd?.invoke()` also
            // appears in onTouchEvent's ACTION_UP branch, and a whole-file
            // search for it passes on that copy alone: measured, deleting the
            // call from the action left this test green. What is guarded is
            // what the action does, so the window is what the action is.
            val actionBody = lines.drop(registration).take(6).joinToString("\n")
            assertTrue(
                actionBody.contains("onArrowKey?.invoke(direction)"),
                "the action no longer sends its arrow, so activating it does nothing. " +
                    "It reads: $actionBody",
            )
            assertTrue(
                actionBody.contains("onDragEnd?.invoke()"),
                "the action no longer ends the drag, so a modifier latched before it " +
                    "stays latched afterwards, unlike every ordinary key on the row. " +
                    "It reads: $actionBody",
            )
        }
    }

    @Nested
    inner class ModifierState {

        @Test
        fun `a toggle says on or off, and a plain key says nothing`() {
            // Resource ids rather than the words. What this decides is which of
            // the two states a latch is in, and that is the half worth pinning
            // here; the words themselves are in strings.xml, where a translation
            // can reach them, and resolving one needs a Context no JVM test has.
            assertEquals(
                R.string.key_toggle_on,
                toggleStateDescription(isToggle = true, isActive = true),
            )
            assertEquals(
                R.string.key_toggle_off,
                toggleStateDescription(isToggle = true, isActive = false),
            )
            assertNull(
                toggleStateDescription(isToggle = false, isActive = false),
                "a plain key has no state, and a stray \"off\" would be read out on every key",
            )
            assertNull(
                toggleStateDescription(isToggle = false, isActive = true),
                "isActive is meaningless on a plain key and must not leak a state either way",
            )
        }

        @Test
        fun `the latch is published wherever the appearance changes`() {
            val lines = code("ExtraKeyButton.kt")

            // Scoped to updateToggleAppearance, not the file: that method is the
            // one place both colours are written, and it is reached from the
            // isToggleActive setter, which is the only way a latch changes. A
            // whole-file search would pass on the declaration of the helper
            // alone, which publishes nothing.
            val start = lines.indexOfFirst { it.contains("private fun updateToggleAppearance()") }
            assertTrue(start >= 0, "updateToggleAppearance is no longer where this test looks")

            val body = lines.drop(start).takeWhile { !it.contains("fun applyRoundedBackground") }
            assertTrue(
                body.any { it.contains("stateDescription = toggleStateDescription(") },
                "the latch no longer reaches an accessibility service, so a screen reader " +
                    "user can switch a modifier and cannot tell whether it is on. It reads: " +
                    body.joinToString("\n"),
            )
            assertTrue(
                body.any { it.contains("colorExtraKeyActive") },
                "the scan is no longer reading the method that paints the latched state, so " +
                    "its verdict above is worth nothing",
            )
        }
    }

    @Nested
    inner class ButtonActivation {

        @Test
        fun `a key press is what an assistive activation produces`() {
            val lines = code("ExtraKeyButton.kt")

            // The control: this scan drops comment lines, and the class carries
            // long prose about presses. If the drop is ever widened, or the file
            // moved, the assertion below would pass by reading nothing.
            assertTrue(
                lines.any { it.contains("private fun emitPress()") },
                "the scan cannot see emitPress in ExtraKeyButton.kt, so it is not " +
                    "reading the source and cannot judge what performClick does",
            )

            val performClick = lines.indexOfFirst {
                it.contains("override fun performClick()")
            }
            assertTrue(
                performClick >= 0,
                "ExtraKeyButton no longer overrides performClick. isClickable is true " +
                    "on these keys, so a screen reader still offers to activate them, " +
                    "and without this override the offer does nothing at all",
            )

            val bodyAfter = lines.drop(performClick).take(5).joinToString("\n")
            assertTrue(
                bodyAfter.contains("emitPress()"),
                "performClick no longer delivers a press, so activating a key through " +
                    "an accessibility service types nothing. It reads: $bodyAfter",
            )
        }

        @Test
        fun `the alternates layer has an assistive route, and only where it opens`() {
            val lines = code("ExtraKeyButton.kt")

            assertTrue(
                lines.any { it.contains("private fun emitPress()") },
                "control: the scan cannot see emitPress, so it is not reading this source",
            )

            val longClick = lines.indexOfFirst { it.contains("override fun performLongClick()") }
            assertTrue(
                longClick >= 0,
                "nothing opens the alternates popup for an assistive service. Its only other " +
                    "entry is a finger held on the view, which touch exploration never delivers",
            )
            val body = lines.drop(longClick).take(5).joinToString("\n")
            assertTrue(
                body.contains("onLongPressAction?.invoke("),
                "performLongClick no longer opens the popup. It reads: $body",
            )
            assertTrue(
                body.contains("alternates.isEmpty()"),
                "a key with no alternates no longer falls through, so it would open an " +
                    "empty popup. It reads: $body",
            )

            // The flag matters as much as the override: View refuses an incoming
            // ACTION_LONG_CLICK, and leaves it off the node, unless the view is
            // long clickable. Scoped to the alternates setter, because that is
            // the only place that knows whether a key has any.
            val setter = lines.indexOfFirst { it.contains("var alternates: List<AlternateKey>") }
            assertTrue(setter >= 0, "the alternates property is no longer where this test looks")
            val setterBody = lines.drop(setter).take(12).joinToString("\n")
            assertTrue(
                setterBody.contains("isLongClickable = value.isNotEmpty()"),
                "the key never advertises a long click, so a service is refused before " +
                    "performLongClick can run. It reads: $setterBody",
            )
        }

        @Test
        fun `the touch path still consumes, so a press cannot double-fire`() {
            // performClick is safe to emit from only because a finger never
            // reaches it: the touch listener returns true, so View.onTouchEvent
            // never runs and never calls performClick itself. If that return
            // ever becomes false, one tap would deliver two presses.
            val listener = code("ExtraKeyButton.kt")
                .dropWhile { !it.contains("setOnTouchListener") }
                .takeWhile { !it.contains("private fun updateToggleAppearance") }

            assertTrue(
                listener.isNotEmpty(),
                "the touch listener is no longer where this test looks for it",
            )
            assertTrue(
                listener.any { it.trim() == "true" },
                "the touch listener no longer returns true unconditionally. If it can " +
                    "return false, View.onTouchEvent runs and calls performClick, and " +
                    "one tap delivers two presses. It reads: ${listener.joinToString("\n")}",
            )
        }
    }
}
