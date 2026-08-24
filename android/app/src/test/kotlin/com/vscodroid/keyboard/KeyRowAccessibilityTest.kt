package com.vscodroid.keyboard

import com.vscodroid.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    /**
     * Source with comment lines dropped, so prose naming a call is not a call.
     *
     * Both forms, because both switch code off. A line comment carries its own
     * marker and is recognised one line at a time; a block opened at the start
     * of a line leaves its inner lines unmarked, so the block is tracked to its
     * close instead. Without that, wrapping a registration in one left every
     * case below green over a view that registers nothing.
     *
     * A line that both opens and closes a block goes with it, whatever else it
     * carries. Nothing in this package writes one, and the cost of being wrong
     * about that is a red case quoting the window it read.
     */
    private fun code(name: String): List<String> {
        var inBlock = false
        return source(name).lines().filterNot { line ->
            val start = line.trimStart()
            when {
                inBlock -> {
                    if (line.indexOf("*/") >= 0) inBlock = false
                    true
                }
                start.startsWith("/*") -> {
                    if (line.indexOf("*/", line.indexOf("/*") + 2) < 0) inBlock = true
                    true
                }
                else -> start.startsWith("//") || start.startsWith("*")
            }
        }
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

    /**
     * That the alternates layer names its entries, as every key on the row does.
     *
     * `KeyPageConfigTest` pins that rule for the top-level keys and stops there.
     * The popup was the one surface in this package whose spoken text was the
     * glyph itself, so the two entries under the quote key were announced as `'`
     * and `` ` ``, and `'`, `\` and `~` are on no page at all: the popup is their
     * only route, and no translation could ever reach them.
     */
    @Nested
    inner class AlternateLabels {

        private val buttons = KeyPages.defaults.flatMap { it.items }
            .filterIsInstance<KeyItem.Button>()

        private val alternates = buttons.flatMap { it.alternates }

        @Test
        fun `an alternate that is also a key on some page is named the same way`() {
            assertTrue(
                alternates.size >= 8,
                "the row came back with ${alternates.size} alternates; this case would " +
                    "prove nothing about a layer that is nearly empty",
            )
            val byValue = buttons.associate { it.value to it.contentDescriptionRes }

            var shared = 0
            for (alt in alternates) {
                val onAPage = byValue[alt.value] ?: continue
                shared++
                assertEquals(
                    onAPage,
                    alt.contentDescriptionRes,
                    "the alternate '${alt.label}' is described differently from the key " +
                        "of the same character on another page, so the same character is " +
                        "two different things to a screen reader",
                )
            }
            assertTrue(
                shared >= 5,
                "only $shared alternates matched a key on a page, so the comparison above " +
                    "ran on almost nothing",
            )
        }

        @Test
        fun `every alternate names a string that exists and is not its own glyph`() {
            val config = File(mainSources, "KeyPageConfig.kt")
            assertTrue(config.isFile, "KeyPageConfig.kt is not at ${config.absolutePath}")
            val stringsXml = File("src/main/res/values/strings.xml")
            assertTrue(stringsXml.isFile, "strings.xml is not at ${stringsXml.absolutePath}")

            val text = Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
                .findAll(stringsXml.readText())
                .associate { it.groupValues[1] to it.groupValues[2] }

            // The label may carry Kotlin escapes: the backslash alternate is
            // written "\\" and has to be compared as the one character it is.
            val declared = Regex(
                """AlternateKey\("((?:[^"\\]|\\.)*)",\s*"(?:[^"\\]|\\.)*",\s*R\.string\.(\w+)"""
            ).findAll(config.readText()).map { m ->
                m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") to m.groupValues[2]
            }.toList()

            // The control. A scan that stopped matching would find no pairs and
            // report every alternate described, which reads like a clean tree.
            assertEquals(
                alternates.size, declared.size,
                "the scan found ${declared.size} alternate declarations in KeyPageConfig.kt, " +
                    "not the ${alternates.size} the pages hold, so its verdict below is worth nothing",
            )

            for ((label, resource) in declared) {
                val described = text[resource]
                assertNotNull(
                    described,
                    "the alternate '$label' names R.string.$resource, which strings.xml does " +
                        "not hold, so a translator has nothing to translate",
                )
                assertNotEquals(
                    label, described,
                    "the alternate '$label' is described as \"$described\", which is the glyph " +
                        "itself; that is what the popup already said before it was named",
                )
            }
        }

        @Test
        fun `the popup puts that name on the entry it builds`() {
            val lines = code("LongPressPopup.kt")
            val entry = lines.indexOfFirst { it.contains("AlternateKeyView(context).apply {") }
            assertTrue(
                entry >= 0,
                "the popup no longer builds its entries where this test looks, so nothing " +
                    "it reports about their labels is about the popup",
            )
            val body = lines.drop(entry).take(12).joinToString("\n")

            assertTrue(
                body.contains("text = alt.label"),
                "the window found is not the one that draws an alternate, so its verdict " +
                    "below means nothing. It reads:\n$body",
            )
            assertTrue(
                body.contains("contentDescription = context.getString(alt.contentDescriptionRes)"),
                "the popup no longer labels its entries, so every AlternateKey description " +
                    "is declared, translated and unspoken while a reader announces the raw " +
                    "character. It reads:\n$body",
            )
        }
    }

    /**
     * That the row says which of its pages is showing.
     *
     * Read off a running device: every key carried a description and the five
     * page-indicator dots carried neither text nor description, so a swipe
     * replaced eight keys and nothing announced it. The dots encode the page in
     * colour, which is a channel a screen reader has no access to at all.
     */
    @Nested
    inner class PageIndicator {

        @Test
        fun `the indicator publishes the page it draws`() {
            val lines = code("ExtraKeyRow.kt")
            val start = lines.indexOfFirst { it.contains("private fun updateDots(") }
            assertTrue(start >= 0, "updateDots is no longer where this test looks")

            val body = lines.drop(start).takeWhile { !it.contains("private fun pageIndicatorText(") }
            assertTrue(
                body.any { it.contains("colorExtraKeyActive") },
                "the window found is not the method that paints the dots, so its verdict " +
                    "below is worth nothing. It reads:\n${body.joinToString("\n")}",
            )
            assertTrue(
                body.any { it.contains("dotContainer.contentDescription = pageIndicatorText(") },
                "the page is drawn and not published, so it exists in colour only. It " +
                    "reads:\n${body.joinToString("\n")}",
            )
        }

        @Test
        fun `the dots are not five silent stops of their own`() {
            val lines = code("ExtraKeyRow.kt")
            val start = lines.indexOfFirst { it.contains("private fun setupDots()") }
            assertTrue(start >= 0, "setupDots is no longer where this test looks")

            val body = lines.drop(start).takeWhile { !it.contains("private fun updateDots(") }
            assertTrue(
                body.any { it.contains("ImageView(context)") },
                "the window found does not build the dots. It reads:\n${body.joinToString("\n")}",
            )
            assertTrue(
                body.any { it.contains("importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO") },
                "each dot is a node of its own again, so a reader stops on five images that " +
                    "have nothing to say. It reads:\n${body.joinToString("\n")}",
            )
        }

        @Test
        fun `a swipe says the page changed`() {
            val lines = code("ExtraKeyRow.kt")
            val start = lines.indexOfFirst { it.contains("private fun setupPageChangeCallback()") }
            assertTrue(start >= 0, "setupPageChangeCallback is no longer where this test looks")

            val body = lines.drop(start).takeWhile { !it.contains("private fun handleKeyAction(") }
            assertTrue(
                body.any { it.contains("override fun onPageSelected(") },
                "the window found is not the page callback. It reads:\n${body.joinToString("\n")}",
            )
            assertTrue(
                body.any { it.contains("announceForAccessibility(pageIndicatorText(position))") },
                "a swipe replaces every key on the row and moves focus nowhere, so without " +
                    "this nothing tells a reader the keys changed. It reads:\n" +
                    body.joinToString("\n"),
            )
        }

        @Test
        fun `the indicator string carries both numbers`() {
            val stringsXml = File("src/main/res/values/strings.xml")
            assertTrue(stringsXml.isFile, "strings.xml is not at ${stringsXml.absolutePath}")
            val declared = Regex("<string name=\"key_page_indicator\">(.*?)</string>")
                .find(stringsXml.readText())
                ?.groupValues?.get(1)

            assertNotNull(
                declared,
                "strings.xml no longer holds key_page_indicator, so the row formats a " +
                    "resource that does not exist",
            )
            assertTrue(
                declared!!.contains("%1\$d") && declared.contains("%2\$d"),
                "the indicator reads \"$declared\", which does not take both the page and " +
                    "the count; a user is told one number and cannot place it",
            )
        }
    }

    /**
     * That a latch is reported from the pages that cannot show it.
     *
     * Ctrl, Alt and Shift are on page 1 only, and a latch survives a page swipe
     * on purpose: the function keys are on a later page, so Ctrl+F5 is reachable
     * no other way. That left the latch carried by the modifier button's own
     * background, which is off screen from every page but the first. Swipe to the
     * function keys with a Ctrl the user has forgotten and F5 runs as Ctrl+F5
     * with nothing on screen saying why: the dots beside the keys report the page
     * and nothing else.
     */
    @Nested
    inner class LatchCue {

        @Test
        fun `nothing latched says nothing`() {
            assertNull(
                latchedModifierLabel(ctrl = false, alt = false, shift = false),
                "an idle row would carry a cue for a modifier nobody pressed",
            )
        }

        @Test
        fun `every latched modifier is named, in the order the keys sit in`() {
            assertEquals("ctrl", latchedModifierLabel(ctrl = true, alt = false, shift = false))
            assertEquals("alt", latchedModifierLabel(ctrl = false, alt = true, shift = false))
            assertEquals("shift", latchedModifierLabel(ctrl = false, alt = false, shift = true))
            assertEquals(
                "ctrl+alt+shift",
                latchedModifierLabel(ctrl = true, alt = true, shift = true),
                "the cue has to name all of them: Ctrl+Shift+P and Ctrl+P are different " +
                    "commands, and the row can hold both modifiers at once",
            )
            assertEquals(
                "ctrl+shift",
                latchedModifierLabel(ctrl = true, alt = false, shift = true),
                "the order follows the keys on page 1, so the cue reads the way the row " +
                    "does rather than the way the arguments happen to be listed",
            )
        }

        @Test
        fun `the cue is published wherever a modifier is written`() {
            val lines = code("ExtraKeyRow.kt")

            // Scoped to the three property setters, which are the only writes
            // there are: the row keeps no modifier state of its own and reads the
            // adapter's toggle map instead. A whole-file search would be
            // satisfied by the declaration of the helper.
            for (modifier in listOf("Ctrl", "Alt", "Shift")) {
                val setter = lines.firstOrNull {
                    it.contains("adapter.setToggleState(\"$modifier\"")
                }
                assertNotNull(
                    setter,
                    "the $modifier setter is no longer where this test looks, so its " +
                        "verdict is worth nothing",
                )
                assertTrue(
                    setter!!.contains("updateModifierBadge()"),
                    "writing $modifier no longer repaints the cue, so a latch made or " +
                        "spent from any page but the first goes unreported. It reads: $setter",
                )
            }
        }

        /**
         * The cue must not cost the row its height.
         *
         * The band the badge appears in is WRAP_CONTENT, so before it was given
         * a floor its tallest child was an 8dp dot one moment and an 11sp text
         * line the next, which grew the band and the row with it. The figures
         * behind that are in [ExtraKeyRow]'s own comment, where the floor is; a
         * JVM case cannot measure a view and should not restate one, so the
         * workbench relaid out and an open terminal took a PTY resize on the
         * app's hottest interaction. The measure pass that proves it is next
         * door in the instrumented suite, which CI compiles and has no emulator
         * to run; deleting either half of the floor left the whole JVM suite
         * green. This is the half that can go red here.
         *
         * Both halves, because the floor is only a bound while the badge stays
         * one line: it is a WRAP_CONTENT child measured against whatever the
         * dots leave, and a narrow phone gets the most dots and the longest
         * label at once.
         */
        @Test
        fun `the band the cue draws in reserves the badge's own height, for one line`() {
            val lines = code("ExtraKeyRow.kt")

            val bandStart = lines.indexOfFirst {
                it.contains("dotContainer = LinearLayout(context).apply {")
            }
            assertTrue(
                bandStart >= 0,
                "the page-indicator band is no longer built where this test looks, so " +
                    "its verdict is worth nothing",
            )
            val band = lines.drop(bandStart)
                .takeWhile { !it.contains("addView(dotContainer)") }
                .joinToString("\n")
            assertTrue(
                band.contains("minimumHeight") &&
                    band.contains("modifierBadge.paint.fontMetricsInt"),
                "the band no longer reserves the badge's height, so latching a modifier " +
                    "grows the row and resizes the WebView under it. It reads:\n$band",
            )
            // Which pair of metrics, not merely that some pair is read. The
            // badge measures itself descent-to-ascent -- includeFontPadding is
            // off and it is held to one line -- so bottom-to-top reserves the
            // font's padded extents on top of that and leaves a band taller
            // than any badge can fill, by a margin that varies by typeface. The
            // check above is satisfied by either pair, which left the one
            // mismatch that matters invisible while the comment beside the code
            // went on insisting on it.
            assertTrue(
                band.contains("descent - badgeMetrics.ascent"),
                "the band reserves a height the badge cannot fill: it is no longer the " +
                    "descent-to-ascent pair a single-line TextView with no font padding " +
                    "measures itself with. It reads:\n$band",
            )

            val badgeStart = lines.indexOfFirst {
                it.contains("modifierBadge = TextView(context).apply {")
            }
            assertTrue(
                badgeStart >= 0,
                "the badge is no longer built where this test looks, so its verdict is " +
                    "worth nothing",
            )
            val badge = lines.drop(badgeStart)
                .takeWhile { !it.contains("private lateinit var adapter") }
                .joinToString("\n")
            assertTrue(
                badge.contains("maxLines = 1"),
                "the badge can wrap again, so at a large font scale on a narrow row it " +
                    "measures taller than the band reserved for it and the floor above " +
                    "stops being a bound. It reads:\n$badge",
            )
            assertTrue(
                badge.contains("includeFontPadding = false"),
                "the badge measures with font padding again, which is the band the floor " +
                    "above deliberately does not reserve. It reads:\n$badge",
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

    /**
     * That the label a screen reader speaks is put on the key.
     *
     * `KeyPageConfigTest` pins both halves of the data: every key names a string
     * resource, and no resource resolves to the glyph painted on the key. Neither
     * half says the label is ever applied. The only place it is applied is
     * `KeyPageAdapter.onBindViewHolder`, and deleting that one line leaves every
     * assertion in this repository green while a screen reader falls back to the
     * button's text and reads "{}" and ";" aloud.
     *
     * Source-reading, and there is no stronger layer available here rather than none
     * chosen. Measured: `ExtraKeyButton(context)` throws `RuntimeException: Method
     * getContext in android.view.View not mocked` before its constructor finishes,
     * because `AppCompatTextView` reaches through `View` on the way up, and the bind
     * constructs one per key. `mockkConstructor` does not help -- it intercepts calls
     * on the instance and still runs the real constructor, and it throws in the same
     * place. This module has no Robolectric and does not set
     * `unitTests.returnDefaultValues`, so nothing in this source set can drive that
     * method. Running it needs a device, which is `androidTest`.
     */
    @Nested
    inner class KeyLabels {

        @Test
        fun `the bind puts each key's description on the key`() {
            val lines = code("KeyPageAdapter.kt")

            val bind = lines.indexOfFirst { it.contains("override fun onBindViewHolder(") }
            assertTrue(
                bind >= 0,
                "onBindViewHolder is no longer in KeyPageAdapter.kt, so this scan is not " +
                    "reading the method that builds the row and its verdict is worth nothing",
            )

            // The method, delimited by its own braces rather than by a line count.
            // The bind grows whenever a key gains a property, and a fixed window is a
            // window that one day stops containing the line it is looking for -- at
            // which point this reports a missing label that is sitting just below
            // where it stopped reading.
            var depth = 0
            var entered = false
            val body = mutableListOf<String>()
            for (line in lines.drop(bind)) {
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (depth > 0) entered = true
                if (entered) body += line
                if (entered && depth <= 0) break
            }
            val shown = body.joinToString("\n")

            // The window control. If the braces ever run away, this scan would be
            // reading the rest of the file and a hit below would mean nothing.
            assertTrue(
                body.any { it.contains("ExtraKeyButton(holder.container.context)") },
                "the window found is not the branch that builds a key, so nothing it " +
                    "reports about the label is about the bind. It reads:\n$shown",
            )

            assertTrue(
                body.any {
                    it.contains("contentDescription = context.getString(item.contentDescriptionRes)")
                },
                "the bind no longer labels the key it builds, so a screen reader falls " +
                    "back to the button's own text and reads the glyph -- \"{}\" instead " +
                    "of \"Curly braces\". Every accessibility string in KeyPageConfig " +
                    "would still be declared, translated and unspoken. It reads:\n$shown",
            )
        }
    }
}
