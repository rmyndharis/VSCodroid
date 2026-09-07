package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That no touch-target floor this project writes lands on an element the
 * workbench sizes from JavaScript.
 *
 * A `min-height` in a stylesheet clamps over an inline `height` by the box
 * model, so where the workbench writes a height as an inline style the element
 * paints at our floor while everything around it keeps advancing at the number
 * JavaScript chose. The floor does not enlarge the target; it desynchronises
 * the two and the surplus lands on whatever comes next.
 *
 * Three of these shipped, and each was measured on an API 36 emulator:
 *
 *   `.monaco-list-row`     floored 36, rows pitched 22 -> every row covered the
 *                          top 14px of the next. Rows are absolutely positioned
 *                          siblings in index order with no z-index and the list
 *                          hit-tests by walking `event.target` up to a
 *                          `data-index`, so the later row took the tap too:
 *                          pressing the bottom of a filename opened the file
 *                          below it.
 *   `.tabs-container .tab` floored 40 inside a 35px title area -> 5px overflow.
 *   `.statusbar-item`      floored 32 inside a status bar pinned at 22
 *                          (`minimumHeight === maximumHeight`) -> 10px clipped,
 *                          so the floor bought nothing at all.
 *
 * Reading source rather than rendering, for the same reason
 * [MenuSeparatorFloorTest] does: the rules live in a string literal and no JVM
 * test can lay them out. So this cannot judge a rule that is present and
 * subtly wrong, only one that is present at all on a selector known to be
 * JS-sized. That is the failure mode worth defending, because all three of the
 * above were added in good faith by someone reading a WCAG target and not the
 * bundle.
 *
 * Widening is still fine and is not tested against: the status bar keeps its
 * horizontal padding, because a fixed-height part has width to give.
 */
class JsSizedElementFloorTest {

    private val injected: String =
        SourceScan.body(
            SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"),
            "private fun injectTouchTargetCSS(",
        )

    /**
     * Selectors whose height the workbench writes itself, and where it does it.
     *
     * Keyed by the fragment that identifies the element in a selector, so a
     * compound selector such as `.quick-input-list .monaco-list-row` is caught
     * as well: that exact rule shipped as a second copy of the row floor.
     */
    private val jsSized = mapOf(
        ".monaco-list-row" to
            "ListView.updateItemInDOM writes top/height/lineHeight per row from the " +
                "delegate's getHeight(), which is 22 for almost every workbench list",
        ".tabs-container .tab" to
            "the editor group writes --editor-group-tab-height from its own tabHeight " +
                "and reserves the title area from the same number",
        ".statusbar-item" to
            "the status bar part pins minimumHeight === maximumHeight, so anything " +
                "taller is clipped rather than shown",
    )

    /** `min-height`, `max-height` or `height`, but never `line-height`. */
    private val heightDecl = Regex("""(?<![-\w])(?:min-|max-)?height\s*:""")

    /**
     * The CSS rules in the injected stylesheet, one per Kotlin string literal.
     *
     * Comment lines are dropped BEFORE parsing, and that is load-bearing rather
     * than tidy. The prose around these rules is full of apostrophes (`the
     * delegate's getHeight()`, `the row's own height`), and a pattern that
     * anchors on the quote character will start a match inside one, run past the
     * literal that follows and swallow the very rule it was meant to read. The
     * first draft of this did exactly that: it parsed 16 rules out of a body
     * holding more, found none of them offending, and passed against a stylesheet
     * that still carried the floor this case exists to forbid.
     */
    private fun rulesIn(css: String): List<Pair<String, String>> =
        css.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") }
            .mapNotNull { Regex("""^'(.*)',?$""").find(it)?.groupValues?.get(1) }
            .mapNotNull { Regex("""^\s*([^{]+?)\s*\{(.*)\}\s*$""").find(it) }
            .map { it.groupValues[1].trim() to it.groupValues[2] }
            .toList()

    @Test
    fun `no injected rule sets a height on an element the workbench sizes itself`() {
        val rules = rulesIn(injected)
        check(rules.isNotEmpty()) {
            "no CSS rules parsed out of injectTouchTargetCSS; this test would pass by " +
                "looking at nothing. The rule shape or the extraction changed."
        }

        rules.forEach { (selector, declarations) ->
            jsSized.forEach { (fragment, why) ->
                val hits = selector.contains(fragment) && heightDecl.containsMatchIn(declarations)
                assertTrue(
                    !hits,
                    "`$selector` sets a height on $fragment, whose height the workbench " +
                        "writes as an inline style: $why. A stylesheet height clamps over " +
                        "that inline value, so the element paints at this size while the " +
                        "layout keeps its own, and the difference lands on the next " +
                        "element. Widen it instead, or raise the delegate constant in a " +
                        "patch and rebuild Code - OSS. Declarations were: $declarations",
                )
            }
        }
    }

    @Test
    fun `the selectors this guards are still the ones the workbench sizes`() {
        // A guard naming an element that no longer appears anywhere is a guard
        // that has quietly stopped defending anything. This does not require the
        // element to be styled, only that the file still speaks about the layer
        // these rules target, so the pairing above stays reviewable.
        assertTrue(
            injected.contains("pointer: coarse"),
            "injectTouchTargetCSS no longer gates on `pointer: coarse`; the whole " +
                "premise of these rules, and of this guard, has moved",
        )
    }
}
