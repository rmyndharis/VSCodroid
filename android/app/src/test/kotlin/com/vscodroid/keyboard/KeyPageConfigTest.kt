package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [KeyPages] — extra key row page configuration.
 */
class KeyPageConfigTest {

    @Nested
    inner class PageStructureTest {

        @Test
        fun `has exactly 3 pages`() {
            assertEquals(3, KeyPages.defaults.size, "Should have 3 key pages")
        }

        @Test
        fun `page 1 has 8 items`() {
            assertEquals(8, KeyPages.defaults[0].items.size, "Page 1 should have 8 items")
        }

        @Test
        fun `page 2 has 8 items`() {
            assertEquals(8, KeyPages.defaults[1].items.size, "Page 2 should have 8 items")
        }

        @Test
        fun `page 3 has 8 items`() {
            assertEquals(8, KeyPages.defaults[2].items.size, "Page 3 should have 8 items")
        }
    }

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
            assertTrue(braces!!.alternates.isNotEmpty(), "Curly brace should have alternate keys")
            val altValues = braces.alternates.map { it.value }
            assertTrue(altValues.contains("["), "Alternates should include '['")
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
    inner class ButtonIntegrityTest {

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
        fun `every button has a contentDescription`() {
            for ((pageIndex, page) in KeyPages.defaults.withIndex()) {
                for (item in page.items) {
                    if (item is KeyItem.Button) {
                        assertTrue(item.contentDescription.isNotEmpty(),
                            "Button contentDescription should not be empty (page ${pageIndex + 1}, label: ${item.label})")
                    }
                }
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
