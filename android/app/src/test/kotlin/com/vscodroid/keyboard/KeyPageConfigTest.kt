package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for [KeyPages]: extra key row page configuration.
 */
class KeyPageConfigTest {

    @Nested
    inner class Page1Test {

        private val page = KeyPages.defaults[0]

        @Test
        fun `contains Tab key`() {
            val tab = page.items.filterIsInstance<KeyItem.Button>().find { it.value == "Tab" }
            assertNotNull(tab, "Page 1 should contain Tab")
            assertEquals("Tab", tab!!.label)
        }

        @Test
        fun `contains Escape key`() {
            val esc = page.items.filterIsInstance<KeyItem.Button>().find { it.value == "Escape" }
            assertNotNull(esc, "Page 1 should contain Escape")
            assertEquals("Esc", esc!!.label)
        }

        @Test
        fun `contains modifier keys Ctrl Alt Shift`() {
            val buttons = page.items.filterIsInstance<KeyItem.Button>()
            val modifiers = buttons.filter { it.isToggle }
            assertEquals(3, modifiers.size, "Page 1 should have 3 toggle modifiers (Ctrl, Alt, Shift)")
            val values = modifiers.map { it.value }.toSet()
            assertTrue(values.containsAll(setOf("Ctrl", "Alt", "Shift")))
        }

        @Test
        fun `contains exactly one GesturePad`() {
            val pads = page.items.filterIsInstance<KeyItem.GesturePad>()
            assertEquals(1, pads.size, "Page 1 should have exactly 1 GesturePad")
        }

        @Test
        fun `the parenthesis key offers the closing parenthesis`() {
            // The only route on the row to `)`. The key sends `(`, and a latched
            // Shift cannot reach the other half of the pair the way it reaches
            // `}` over `]`: shiftedForm returns `(` unchanged because `(` already
            // requires Shift, and `)` sits on Digit0, which no page carries.
            val parens = page.items.filterIsInstance<KeyItem.Button>().find { it.value == "(" }
            assertNotNull(parens, "Page 1 should have the parenthesis button")
            assertTrue(
                parens!!.alternates.any { it.value == ")" },
                "`)` is reachable from no key, no alternate and no shifted form, so a " +
                    "closing parenthesis the editor did not auto-close cannot be typed " +
                    "from this row at all. The alternates are ${parens.alternates.map { it.value }}"
            )
            assertNotEquals(
                ")",
                KeyMapping.shiftedForm("("),
                "shiftedForm now reaches `)`, which would make this alternate a second " +
                    "route rather than the only one; the reasoning above needs rechecking"
            )
        }

        @Test
        fun `curly brace button has bracket alternates`() {
            val braces = page.items.filterIsInstance<KeyItem.Button>().find { it.value == "{" }
            assertNotNull(braces, "Page 1 should have curly brace button")
            // Pinned rather than searched. Asserting only that '[' is somewhere in
            // the list left the second alternate unguarded: replacing '<' with a
            // duplicate '[' removes the '<' long-press option and passes both the
            // isNotEmpty and the contains check.
            assertEquals(
                listOf("[" to "[", "<" to "<"),
                braces!!.alternates.map { it.label to it.value },
                "Curly brace long-press offers the bracket then the angle bracket"
            )
        }
    }

    @Nested
    inner class Page2Test {

        private val page = KeyPages.defaults[1]

        @Test
        fun `contains common symbol keys`() {
            val buttons = page.items.filterIsInstance<KeyItem.Button>()
            val values = buttons.map { it.value }.toSet()
            assertTrue(values.containsAll(setOf(";", ":", "\"", "/", "|", "`", "&", "_")),
                "Page 2 should contain common symbol keys")
        }

        @Test
        fun `double quote has alternates for single quote and backtick`() {
            val quote = page.items.filterIsInstance<KeyItem.Button>().find { it.value == "\"" }
            assertNotNull(quote)
            val altValues = quote!!.alternates.map { it.value }
            assertTrue(altValues.contains("'"), "Double quote alternates should include single quote")
            assertTrue(altValues.contains("`"), "Double quote alternates should include backtick")
        }
    }

    @Nested
    inner class Page3Test {

        private val page = KeyPages.defaults[2]

        @Test
        fun `contains bracket and operator keys`() {
            val buttons = page.items.filterIsInstance<KeyItem.Button>()
            val values = buttons.map { it.value }.toSet()
            assertTrue(values.containsAll(setOf("[", "]", "<", ">", "=", "!", "#", "@")),
                "Page 3 should contain bracket and operator keys")
        }
    }

    @Nested
    inner class FunctionAndNavigationPageTest {

        // Pinned as whole ordered lists rather than searched for a few members.
        // These two pages are the only route to any of these keys, so one
        // dropped entry is a shortcut that cannot be typed at all on a
        // touchscreen.

        @Test
        fun `page 4 carries F1 to F8`() {
            val buttons = KeyPages.defaults[3].items.filterIsInstance<KeyItem.Button>()
            assertEquals(
                (1..8).map { "F$it" },
                buttons.map { it.value },
                "Page 4 carries F1 to F8 in order"
            )
        }

        @Test
        fun `page 5 carries F9 to F12 and the four navigation keys`() {
            val buttons = KeyPages.defaults[4].items.filterIsInstance<KeyItem.Button>()
            assertEquals(
                (9..12).map { "F$it" } + listOf("Home", "End", "PageUp", "PageDown"),
                buttons.map { it.value },
                "Page 5 carries F9 to F12 then Home, End and the two page keys"
            )
        }
    }

    @Nested
    inner class ButtonIntegrityTest {

        @Test
        fun `every key the row sends has a definition of its own`() {
            // A key the table does not name is not a dead key. KeyInjector
            // resolves through KeyMapping.getKeyDefOrLetter, which derives the
            // event fields from the first character, so an unmapped "F2" is sent
            // as KeyF with keyCode 70 and VS Code resolves it as the letter F.
            // The three modifiers never reach the injector;
            // ExtraKeyRow.handleKeyAction consumes them itself.
            val modifiers = setOf("Ctrl", "Alt", "Shift")
            val sent = KeyPages.defaults.flatMap { it.items }
                .filterIsInstance<KeyItem.Button>()
                .flatMap { button -> listOf(button.value) + button.alternates.map { it.value } }
                .filterNot { it in modifiers }
            for (value in sent) {
                assertNotNull(
                    KeyMapping.getKeyDef(value),
                    "'$value' is on the key row with no KeyMapping entry, so it is sent " +
                        "as whatever letter its first character names"
                )
            }
        }

        @Test
        fun `no page divides the row more finely than page 1 already does`() {
            // KeyPageAdapter gives every button LayoutParams(0, MATCH_PARENT,
            // 1f) and the trackpad 1.5f, so LinearLayout measures each child
            // with an EXACTLY spec at its share of the row and
            // ExtraKeyButton's own 48dp minWidth cannot widen it back. Page 1
            // is the densest today at 8.5 weight units, roughly 41dp a unit on
            // a 360dp portrait row. Sixteen keys on one page works out at
            // 18.5dp, under Android's 48dp minimum touch target, and
            // four-character labels such as PgDn clip rather than shrink.
            // Weights are counted in tenths to keep the arithmetic exact.
            for ((pageIndex, page) in KeyPages.defaults.withIndex()) {
                val tenths = page.items.sumOf { item ->
                    val weight: Int = when (item) {
                        is KeyItem.Button -> KEY_WEIGHT_TENTHS
                        is KeyItem.GesturePad -> PAD_WEIGHT_TENTHS
                    }
                    weight
                }
                assertTrue(
                    tenths <= 85,
                    "Page ${pageIndex + 1} claims ${tenths / 10.0} weight units. The row is " +
                        "divided exactly, so past 8.5 every key on the page gets narrower " +
                        "than page 1 already makes them; split the page instead"
                )
            }
        }

        /**
         * The width the pages are actually laid out at, rather than the width
         * they were written for.
         *
         * `defaults` divides a 360dp row into 8.5 shares on page 1 and 8 on the
         * rest, which is 42.4dp and 45.0dp: under the 48dp minimum touch target,
         * on every key of every page, on one of the most common portrait widths
         * there is. The instrumented case that measures real views only ever
         * asserted 411dp and said so. This is the arithmetic half, and it runs.
         *
         * 220dp is in the list because it is not a phone. The activity is
         * resizeable and declares `smallestScreenSize`, so a freeform or
         * multi-pane window reports a width under 320 and keeps the row alive
         * across the resize. A packer that floors that width at 320 rather than
         * testing it for the undefined sentinel leaves page 1 at 6.5 shares,
         * which a 220dp row divides into 33.8dp a key: the shortfall this whole
         * function exists to prevent, on the window nobody develops on.
         */
        @Test
        fun `no key falls under the touch target on a narrow phone`() {
            for (widthDp in listOf(220, 320, 360, 411, 448, 600)) {
                val pages = KeyPages.forSmallestWidthDp(widthDp)
                // The control. An empty list walks no page and reports every key
                // wide enough, which reads exactly like a row that fits.
                assertTrue(
                    pages.size >= KeyPages.defaults.size,
                    "at ${widthDp}dp the row was paged into ${pages.size} pages, fewer than " +
                        "the ${KeyPages.defaults.size} written above, so keys have gone " +
                        "missing and the widths below are measured on what is left",
                )
                for ((index, page) in pages.withIndex()) {
                    val shares = page.items.sumOf { item ->
                        when (item) {
                            is KeyItem.Button -> KEY_WEIGHT_TENTHS
                            is KeyItem.GesturePad -> PAD_WEIGHT_TENTHS
                        }
                    } / 10.0
                    val keyDp = widthDp / shares
                    assertTrue(
                        keyDp >= MIN_TOUCH_TARGET_DP,
                        "at ${widthDp}dp, page ${index + 1} claims $shares shares, which " +
                            "leaves ${keyDp}dp a key, under the ${MIN_TOUCH_TARGET_DP}dp " +
                            "minimum touch target"
                    )
                }
            }
        }

        @Test
        fun `every key survives being repacked for a narrow phone`() {
            // Repacking splits pages; it must not drop or reorder a key. Pages 4
            // and 5 are the only route to the function and navigation keys, so a
            // key lost here is a shortcut that cannot be typed on a touchscreen
            // at all, and the loss would be invisible on the device this was
            // written on.
            val wide = KeyPages.forSmallestWidthDp(411).flatMap { it.items }
            val narrow = KeyPages.forSmallestWidthDp(320).flatMap { it.items }
            assertEquals(
                KeyPages.defaults.flatMap { it.items },
                wide,
                "the pages a mainstream phone gets are no longer the ones written above"
            )
            assertEquals(wide, narrow, "repacking changed which keys the row carries, or their order")
            assertTrue(
                KeyPages.forSmallestWidthDp(320).size > KeyPages.defaults.size,
                "a 320dp phone gets the same page count as a 411dp one, so nothing was " +
                    "repacked and the case above is passing on the wrong arithmetic"
            )
        }

        @Test
        fun `a width no device can have is read as the narrowest one that can`() {
            // `Configuration` reports SMALLEST_SCREEN_WIDTH_DP_UNDEFINED, which
            // is 0, for a value it has not resolved, and the packer divides by
            // the width it is given: 0 leaves room for nothing, so every key
            // gets a page of its own and the row becomes 40 pages of one key.
            //
            // Its own line is what this needs. The width loop above computes
            // `widthDp / shares` and would divide by the reported 0 rather than
            // by the substitute, so it can neither see the defect nor be
            // extended to. It covers the other half instead: a real width under
            // 320 must be packed for, not read as this sentinel.
            assertEquals(
                KeyPages.forSmallestWidthDp(320),
                KeyPages.forSmallestWidthDp(0),
                "an unresolved smallestScreenWidthDp is being divided by rather than read " +
                    "as 320, which repacks the row into one key a page"
            )
        }

        @Test
        fun `the same width packs to a value-equal page set`() {
            // What `ExtraKeyRow.onConfigurationChanged` decides a rebuild on. It
            // compares the freshly packed pages against the ones the row is
            // already showing and returns when they match, so a rotation or a
            // dark-mode switch, which arrive on the same callback and move no
            // page boundary, costs the user neither their page nor a latch.
            //
            // Structural equality is the whole of that guard. 360dp repacks, so
            // each call builds new `KeyPage` objects; drop `data` from either
            // `KeyPage` or `KeyItem.Button` and the comparison silently becomes
            // identity, every configuration change rebuilds the row, and the
            // pager jumps back to page 1 on each one.
            val first = KeyPages.forSmallestWidthDp(360)
            val second = KeyPages.forSmallestWidthDp(360)
            assertNotSame(
                first,
                second,
                "360dp no longer repacks, so this compares one list with itself and the " +
                    "assertion below proves nothing"
            )
            assertEquals(
                first,
                second,
                "two packings of the same width are no longer equal, so the row rebuilds " +
                    "itself on every configuration change it is told about"
            )
        }

        @Test
        fun `every button has non-empty label and value`() {
            for ((pageIndex, page) in KeyPages.defaults.withIndex()) {
                for (item in page.items) {
                    if (item is KeyItem.Button) {
                        assertTrue(item.label.isNotEmpty(),
                            "Button label should not be empty (page ${pageIndex + 1})")
                        assertTrue(item.value.isNotEmpty(),
                            "Button value should not be empty (page ${pageIndex + 1}, label: ${item.label})")
                    }
                }
            }
        }

        @Test
        fun `every button is described by something other than its own label`() {
            // What this guards has not changed: a screen reader that reads the
            // symbol -- "{}" instead of "Curly braces" -- tells its user nothing,
            // and that is the case every one of these strings exists for. How it
            // is guarded had to, twice over.
            //
            // It began as an assertion that the description was non-empty, which
            // could not fail: the field defaulted to the label and a sibling test
            // already forbids an empty label. Every accessibility string in
            // KeyPageConfig could be deleted and this stayed green. Comparing
            // against the label caught that, because taking the default made the
            // two equal.
            //
            // The default is now gone -- contentDescriptionRes has none, so a key
            // added without one does not compile -- and with it went the thing
            // that comparison detected. What is left to check is the case the
            // compiler cannot see: a description that resolves to the label's own
            // text. So the pairing is read out of the source and resolved against
            // strings.xml, which is the only place either half now lives.
            val source = File("src/main/kotlin/com/vscodroid/keyboard/KeyPageConfig.kt")
            assertTrue(
                source.isFile,
                "KeyPageConfig.kt is not at ${source.absolutePath}; this test would " +
                    "otherwise pass by reading nothing",
            )
            val strings = File("src/main/res/values/strings.xml")
            assertTrue(
                strings.isFile,
                "strings.xml is not at ${strings.absolutePath}; this test would " +
                    "otherwise pass by resolving nothing",
            )

            val text = Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
                .findAll(strings.readText())
                .associate { it.groupValues[1] to it.groupValues[2] }

            // The label may carry Kotlin escapes: the double-quote key's label is
            // written "\"" and has to be compared as the one character it is.
            val declared = Regex(
                """KeyItem\.Button\("((?:[^"\\]|\\.)*)",\s*"(?:[^"\\]|\\.)*",\s*R\.string\.(\w+)"""
            ).findAll(source.readText()).map { m ->
                m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") to m.groupValues[2]
            }.toList()

            // The control. A scan that stopped matching would find no pairs and
            // report every key described, which reads exactly like a clean tree.
            val buttons = KeyPages.defaults.flatMap { it.items }.filterIsInstance<KeyItem.Button>()
            assertEquals(
                buttons.size, declared.size,
                "the scan found ${declared.size} button declarations in KeyPageConfig.kt, " +
                    "not the ${buttons.size} keys the pages hold, so it is not reading that " +
                    "file and its verdict below is worth nothing",
            )

            for ((label, resource) in declared) {
                val described = text[resource]
                assertNotNull(
                    described,
                    "the key '$label' names R.string.$resource, which strings.xml does not " +
                        "hold; a translator has nothing to translate and the build resolves " +
                        "it to whatever else carries that name",
                )
                assertNotEquals(
                    label, described,
                    "the key '$label' is described as \"$described\", which is the label " +
                        "itself; a screen reader would read the symbol out instead of " +
                        "naming the key",
                )
            }
        }

        @Test
        fun `toggle keys are only modifiers`() {
            val allButtons = KeyPages.defaults.flatMap { it.items }.filterIsInstance<KeyItem.Button>()
            val toggles = allButtons.filter { it.isToggle }
            for (toggle in toggles) {
                assertTrue(toggle.value in setOf("Ctrl", "Alt", "Shift"),
                    "Toggle key '${toggle.value}' should be a modifier")
            }
        }

        @Test
        fun `no other pages have GesturePad`() {
            // Only page 1 should have GesturePad
            for (i in 1 until KeyPages.defaults.size) {
                val pads = KeyPages.defaults[i].items.filterIsInstance<KeyItem.GesturePad>()
                assertEquals(0, pads.size, "Page ${i + 1} should NOT have GesturePad")
            }
        }
    }
}
