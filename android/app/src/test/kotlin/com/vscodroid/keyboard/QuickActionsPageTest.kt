package com.vscodroid.keyboard

import android.webkit.WebView
import com.vscodroid.SourceScan
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The quick actions page: the last page of the key row, six keys that each send
 * one workbench shortcut with modifiers of their own.
 *
 * What a key does on a device is not reachable from here, and this says so
 * rather than imply it: `ExtraKeyRow` and `ExtraKeyButton` are views whose
 * initialisers reach resources. What is pinned is the data (which keys, which
 * shortcut each stands for), what [KeyInjector] is handed for each and the
 * script it builds, the decision about which modifiers a press carries, how the
 * page packs on a narrow phone, and, by reading the source as the other cases
 * in this package do, that the row and the adapter use those.
 *
 * The bindings were read from the 1.139.1 workbench's registrations: F1 is the
 * secondary binding of Show All Commands (Ctrl+Shift+P is its primary), Ctrl+P
 * is Go to File, Ctrl+` Toggle Terminal, Ctrl+B Toggle Primary Side Bar, Ctrl+J
 * Toggle Panel and Ctrl+S Save, and none sits behind a `when` clause. The codes
 * below are the DOM values its scan code table maps to its own key codes: F1 is
 * 112 for KeyCode 59, KeyP 80 for 46, Backquote 192 for 91, KeyB 66 for 32, KeyJ
 * 74 for 40 and KeyS 83 for 49.
 */
class QuickActionsPageTest {

    private data class Shortcut(
        val label: String,
        val value: String,
        val modifiers: KeyModifiers,
        val code: String,
        val keyCode: Int,
    )

    /** Written out rather than read from the page, so a changed binding is a decision. */
    private val expected = listOf(
        Shortcut("F1", "F1", KeyModifiers(), "F1", 112),
        Shortcut("^P", "p", KeyModifiers(ctrl = true), "KeyP", 80),
        Shortcut("^`", "`", KeyModifiers(ctrl = true), "Backquote", 192),
        Shortcut("^B", "b", KeyModifiers(ctrl = true), "KeyB", 66),
        Shortcut("^J", "j", KeyModifiers(ctrl = true), "KeyJ", 74),
        Shortcut("^S", "s", KeyModifiers(ctrl = true), "KeyS", 83),
    )

    private fun quickPage(): KeyPage = KeyPages.defaults.last()

    private fun shortcuts(page: KeyPage): List<KeyItem.Button> =
        page.items.filterIsInstance<KeyItem.Button>().filter { it.fixedModifiers != null }

    @Nested
    inner class TheData {

        @Test
        fun `the page is the sixth and last, and nothing else carries a shortcut`() {
            assertEquals(6, KeyPages.defaults.size, "the row is six pages, the quick actions last")
            assertEquals(
                expected.size, shortcuts(quickPage()).size,
                "the last page is not made of shortcut keys, or has keys that are not",
            )
            assertEquals(
                quickPage().items.size, shortcuts(quickPage()).size,
                "the quick actions page carries something that is not a shortcut",
            )
            for ((index, page) in KeyPages.defaults.dropLast(1).withIndex()) {
                assertEquals(
                    emptyList<KeyItem.Button>(), shortcuts(page),
                    "page ${index + 1} carries a shortcut key, which belongs on the last page",
                )
            }
        }

        @Test
        fun `the six keys, in order, with the shortcut each one stands for`() {
            val found = shortcuts(quickPage()).map { button ->
                button.label to Triple(button.value, button.fixedModifiers, button.alternates)
            }
            assertEquals(
                expected.map { it.label to Triple(it.value, it.modifiers, emptyList<AlternateKey>()) },
                found,
            )
        }

        @Test
        fun `no shortcut is a modifier, a toggle, or a key with a popup`() {
            // A toggle switches a latch and a key with alternates opens a popup on
            // a hold; a shortcut is neither, so a hold presses it once, on
            // release, as any other plain key.
            for (button in shortcuts(quickPage())) {
                assertFalse(button.isToggle, "'${button.label}' latches, so it is not a shortcut")
                assertTrue(
                    button.value !in setOf("Ctrl", "Alt", "Shift"),
                    "'${button.label}' sends a modifier as its key",
                )
                assertTrue(
                    button.alternates.isEmpty(),
                    "'${button.label}' has alternates, so a hold opens a popup instead of pressing it",
                )
            }
        }

        @Test
        fun `each shortcut is named for its command and not for its label`() {
            val declared = Regex(
                """KeyItem\.Button\("((?:[^"\\]|\\.)*)",\s*"(?:[^"\\]|\\.)*",\s*R\.string\.(\w+),\s*fixedModifiers"""
            ).findAll(File("src/main/kotlin/com/vscodroid/keyboard/KeyPageConfig.kt").readText())
                .map { it.groupValues[1] to it.groupValues[2] }.toList()
            // The control: a scan that stopped matching would report every key named.
            assertEquals(
                expected.map { it.label }, declared.map { it.first },
                "the scan did not find the six shortcut declarations, so its verdict is worth nothing",
            )
            assertEquals(declared.size, declared.map { it.second }.toSet().size, "two keys share a description")

            val english = stringsOf(File("src/main/res/values/strings.xml"))
            for ((label, resource) in declared) {
                val said = english[resource]
                assertTrue(!said.isNullOrBlank(), "'$label' names R.string.$resource, which strings.xml lacks")
                assertTrue(said != label, "'$label' is described as itself, which a reader would read out")
            }
        }

        @Test
        fun `every locale names every shortcut`() {
            val res = File("src/main/res")
            val locales = res.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith("values") }
                .map { File(it, "strings.xml") }
                .filter { it.isFile }
            // The control: an empty list would find nothing missing.
            assertTrue(locales.size >= 14, "found ${locales.size} strings.xml files, so the scan is not reading res/")

            val names = Regex("""R\.string\.(key_desc_quick_\w+)""")
                .findAll(File("src/main/kotlin/com/vscodroid/keyboard/KeyPageConfig.kt").readText())
                .map { it.groupValues[1] }.toSet()
            assertEquals(expected.size, names.size, "the scan did not find one description per shortcut")

            for (file in locales) {
                val strings = stringsOf(file)
                for (name in names) {
                    assertTrue(
                        !strings[name].isNullOrBlank(),
                        "${file.parentFile?.name} has no $name, so that locale falls back to English " +
                            "on a key a reader can only know by its description",
                    )
                }
            }
        }

        private fun stringsOf(file: File): Map<String, String> {
            assertTrue(file.isFile, "${file.absolutePath} is not there; the case would pass by reading nothing")
            return Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText())
                .associate { it.groupValues[1] to it.groupValues[2] }
        }
    }

    /**
     * What [KeyInjector] is given for each key, and the script that comes out.
     *
     * The injector is built with a layout and a navigation press that fail the
     * case if they are asked, because a shortcut that reached either would be
     * typed or pressed as a real key and not announced to the workbench, which
     * is the only way a chord is resolved.
     */
    @Nested
    inner class Delivery {

        private val script = slot<String>()
        private val webView = mockk<WebView>(relaxed = true).also {
            every { it.evaluateJavascript(capture(script), any()) } returns Unit
        }

        @AfterEach
        fun tearDown() {
            unmockkAll()
        }

        /** The press the row makes for [button] with [latched] on the row. */
        private fun press(button: KeyItem.Button, latched: KeyModifiers = KeyModifiers()): String {
            val sent = pressModifiers(button.fixedModifiers, latched)
            KeyInjector(
                webView,
                keyEventsFor = { error("a shortcut was asked of the layout, so it would be typed") },
                navigationEventsFor = { _, _, _ -> error("a shortcut was pressed as a real key") },
            ).injectKey(button.value, ctrlKey = sent.ctrl, altKey = sent.alt, shiftKey = sent.shift)
            return script.captured
        }

        private fun field(script: String, name: String): String =
            script.lineSequence().map { it.trim() }
                .first { it.startsWith("$name: ") }
                .removePrefix("$name: ").removeSuffix(",")

        @Test
        fun `each shortcut is announced to the workbench with its key and its own modifiers`() {
            for ((button, want) in shortcuts(quickPage()).zip(expected)) {
                val js = press(button)
                assertEquals(KeyMapping.jsQuote(want.value), field(js, "key"), "${want.label}: key")
                assertEquals(KeyMapping.jsQuote(want.code), field(js, "code"), "${want.label}: code")
                assertEquals(want.keyCode.toString(), field(js, "keyCode"), "${want.label}: keyCode")
                assertEquals(want.modifiers.ctrl.toString(), field(js, "ctrlKey"), "${want.label}: ctrlKey")
                assertEquals(want.modifiers.alt.toString(), field(js, "altKey"), "${want.label}: altKey")
                assertEquals(want.modifiers.shift.toString(), field(js, "shiftKey"), "${want.label}: shiftKey")
                // The chord comes up after it, so the page is not left holding Ctrl.
                assertTrue(
                    js.contains("releaseModifiers(target, eventInit);"),
                    "${want.label}: the modifiers the chord held are never released on the page",
                )
            }
            // Announced, not pressed: no real key event went to the WebView.
            verify(exactly = 0) { webView.dispatchKeyEvent(any()) }
            verify(exactly = expected.size) { webView.evaluateJavascript(any(), any()) }
        }

        @Test
        fun `the table resolves each key to the code and key code the workbench reads`() {
            for (want in expected) {
                val def = KeyMapping.getKeyDefOrLetter(want.value)
                assertEquals(want.value, def.key, "${want.label}: key")
                assertEquals(want.code, def.code, "${want.label}: code")
                assertEquals(want.keyCode, def.keyCode, "${want.label}: keyCode")
            }
        }

        @Test
        fun `a latched modifier neither joins a shortcut nor changes it`() {
            val everything = KeyModifiers(ctrl = true, alt = true, shift = true)
            for ((button, want) in shortcuts(quickPage()).zip(expected)) {
                val js = press(button, latched = everything)
                assertEquals(want.modifiers.ctrl.toString(), field(js, "ctrlKey"), "${want.label}: ctrlKey")
                assertEquals("false", field(js, "altKey"), "${want.label}: a latched Alt reached the chord")
                assertEquals("false", field(js, "shiftKey"), "${want.label}: a latched Shift reached the chord")
            }
        }

        @Test
        fun `a key without fixed modifiers still carries the latched ones`() {
            val latched = KeyModifiers(ctrl = true, shift = true)
            assertEquals(latched, pressModifiers(null, latched))
            assertEquals(KeyModifiers(), pressModifiers(null, KeyModifiers()))
            // A shortcut with no modifiers of its own, F1, is not a key without
            // them: it is held to sending none, and a Ctrl latched on page 1 is
            // not allowed to turn it into Ctrl+F1.
            assertEquals(KeyModifiers(), pressModifiers(KeyModifiers(), KeyModifiers(ctrl = true)))
        }
    }

    /** How the page packs when a narrow phone cuts the pages into more. */
    @Nested
    inner class OnANarrowPhone {

        private val widths = listOf(288, 320, 360, 393, 411, 448, 600)

        @Test
        fun `the page is one page of its own at every width a phone has`() {
            for (widthDp in widths) {
                val pages = KeyPages.forSmallestWidthDp(widthDp)
                assertEquals(
                    quickPage(), pages.last(),
                    "at ${widthDp}dp the quick actions are not one last page of their own",
                )
                for ((index, page) in pages.dropLast(1).withIndex()) {
                    assertEquals(
                        emptyList<KeyItem.Button>(), shortcuts(page),
                        "at ${widthDp}dp page ${index + 1} holds a shortcut beside other keys",
                    )
                }
            }
        }

        @Test
        fun `the keys before it page as they did, and the dots count one more`() {
            // The pages the user has always had: five at 411dp and wider, six at
            // 360dp and seven at 320dp, as the user guide says. The quick actions
            // are the one page added to each, so six, seven and eight in all.
            val before = mapOf(411 to 5, 448 to 5, 600 to 5, 360 to 6, 320 to 7)
            for ((widthDp, count) in before) {
                val pages = KeyPages.forSmallestWidthDp(widthDp)
                assertEquals(count, pages.size - 1, "at ${widthDp}dp the keys before the quick actions")
                assertEquals(
                    KeyPages.defaults.dropLast(1).flatMap { it.items },
                    pages.dropLast(1).flatMap { it.items },
                    "at ${widthDp}dp the keys before the quick actions were moved or lost",
                )
            }
        }

        @Test
        fun `the page counts are the ones the documents state`() {
            assertEquals(6, KeyPages.forSmallestWidthDp(411).size)
            assertEquals(7, KeyPages.forSmallestWidthDp(360).size)
            assertEquals(8, KeyPages.forSmallestWidthDp(320).size)
        }
    }

    /**
     * That the row and the adapter use the decision above.
     *
     * By reading the source, because no JVM case can build either view. Each
     * assertion names the window it reads so that a moved method fails loudly.
     */
    @Nested
    inner class Wiring {

        private fun source(name: String): String = SourceScan.withoutComments(
            SourceScan.read("src/main/kotlin/com/vscodroid/keyboard/$name"),
        )

        @Test
        fun `a key press sends the modifiers the decision gives and then clears the latches`() {
            val handle = SourceScan.body(source("ExtraKeyRow.kt"), "private fun handleKeyAction(")
            val fixed = handle.indexOf("pressModifiers(button.fixedModifiers")
            val inject = handle.indexOf("injectKey(key, ctrlKey = sent.ctrl, altKey = sent.alt, shiftKey = sent.shift)")
            val reset = handle.indexOf("resetModifiersIfNeeded()", inject)
            assertTrue(
                fixed in 0 until inject && reset > inject,
                "the press no longer takes its modifiers from pressModifiers, or no longer clears " +
                    "the latches after it, so a shortcut can pick up a latch or leave one lit. " +
                    "It reads:\n$handle",
            )
        }

        @Test
        fun `the adapter puts the item's modifiers on the key it builds`() {
            val bind = SourceScan.body(source("KeyPageAdapter.kt"), "override fun onBindViewHolder(")
            assertTrue(
                bind.contains("fixedModifiers = item.fixedModifiers"),
                "the bind does not hand the key its modifiers, so every shortcut is sent with " +
                    "the row's latches. It reads:\n$bind",
            )
        }
    }
}
