package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
                        is KeyItem.Button -> 10
                        is KeyItem.GesturePad -> 15
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
