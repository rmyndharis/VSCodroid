package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The window attributes that replaced `androidx.activity.enableEdgeToEdge()`.
 *
 * [drawBehindSystemBars] sets two things from code and leaves the other four to
 * `values/themes.xml`, because Play's scan reads bytecode and an attribute is not a
 * call. That trade is what cleared the three deprecated APIs Play reported, and it
 * moves the cost here: a theme item has no compiler. Deleting one is a silent,
 * device-only regression, and one of the four was in fact missing when the migration
 * first landed.
 *
 * ⚠️ What this cannot see. It reads the XML, so it proves the attribute is written,
 * not that the platform honours it, and not that the value looks right on a screen.
 * The API 33 emulator is still the instrument for that, and
 * `docs/PLAY_EDGE_TO_EDGE.md` says so: API 35+ ignores every attribute checked here
 * and paints the bars from the window background, so a current image cannot see this
 * class of defect at all.
 *
 * Values are asserted, not just presence. `enforceNavigationBarContrast` defaulting
 * to `true` is exactly the state this exists to refuse, and an item naming `true`
 * would satisfy a presence check while restoring the scrim.
 */
class ThemeEdgeToEdgeTest {

    private val themes = File("src/main/res/values/themes.xml")

    /** `name` to literal value, for every `<item>` in the app's theme. */
    private fun themeItems(): Map<String, String> {
        assertTrue(
            themes.isFile,
            "themes.xml is not at ${themes.absolutePath}; this test would otherwise " +
                "pass by reading nothing",
        )
        val text = themes.readText()
        // The one style this app declares. Bounded to it rather than scanning the file,
        // so a second style added later cannot answer for this one.
        val style = Regex("""<style\s+name="Theme\.VSCodroid".*?</style>""", RegexOption.DOT_MATCHES_ALL)
            .find(text)
        assertTrue(style != null, "Theme.VSCodroid is not declared in themes.xml")
        return Regex("""<item\s+name="([^"]+)"\s*>([^<]*)</item>""")
            .findAll(style!!.value)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    @Test
    fun `the theme carries every window attribute drawBehindSystemBars does not set`() {
        val items = themeItems()

        // The control: if the parse silently matched nothing, every assertion below
        // would fail with "expected X but was null", which reads like a deleted
        // attribute rather than a broken parser. This says which it is.
        assertTrue(
            items.size >= 5,
            "only ${items.size} items were parsed out of Theme.VSCodroid, so the " +
                "pattern is not matching the file. Parsed: $items",
        )

        mapOf(
            // Transparent rather than the Material3 default, which is blue against
            // this app's dark editor on API 33 and 34.
            "android:statusBarColor" to "@android:color/transparent",
            "android:navigationBarColor" to "@android:color/transparent",
            // `always`, which is what Api30 wrote and what API 35+ forces. Not
            // `shortEdges`: Api28 wrote that and minSdk 33 never reached it.
            "android:windowLayoutInDisplayCutoutMode" to "always",
            // Both default to true. Leaving either out puts a platform scrim behind
            // a bar the two colours above just made transparent.
            "android:enforceStatusBarContrast" to "false",
            "android:enforceNavigationBarContrast" to "false",
        ).forEach { (name, expected) ->
            assertEquals(
                expected, items[name],
                "Theme.VSCodroid no longer sets $name to $expected. " +
                    "drawBehindSystemBars() deliberately does not set it from code, " +
                    "because Play flags the setter, so the theme is the only thing " +
                    "holding it and there is nothing else to catch this.",
            )
        }
    }

    /**
     * [text] with every comment blanked to spaces, newlines and offsets preserved.
     *
     * Blanking rather than deleting so a line number still points where it did.
     *
     * Not optional, and the first version of this test proved it: the KDoc on
     * [drawBehindSystemBars] names all three deprecated APIs, because explaining why
     * they are gone requires saying what they were. Scanning raw text found those four
     * lines and reported the documentation as the defect. A guard that fires on the
     * prose describing it is one someone silences by deleting the prose.
     */
    private fun codeOnly(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) ->
                    while (i < text.length && text[i] != '\n') { out.append(' '); i++ }
                text.startsWith("/*", i) -> {
                    while (i < text.length && !text.startsWith("*/", i)) {
                        out.append(if (text[i] == '\n') '\n' else ' '); i++
                    }
                    repeat(minOf(2, text.length - i)) { out.append(' '); i++ }
                }
                else -> { out.append(text[i]); i++ }
            }
        }
        return out.toString()
    }

    @Test
    fun `the window setters Play reports are not called from app code`() {
        // The other half of the trade, and the reason the attributes exist at all.
        // Source-level, so it speaks only for this repository's own code; the release
        // dex is what settles the bundle, and r8.yml is where that runs.
        val sources = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue(sources.size > 10, "only ${sources.size} sources walked; the walk is wrong")

        val banned = listOf(
            "setStatusBarColor", "statusBarColor =",
            "setNavigationBarColor", "navigationBarColor =",
            "enableEdgeToEdge",
        )

        // The negative control, run against a literal rather than the tree: the
        // blanking has to remove a commented occurrence and keep a real one, or an
        // empty offender list means nothing.
        val probe = codeOnly("/** enableEdgeToEdge */\nwindow.setStatusBarColor(0)\n")
        assertTrue("enableEdgeToEdge" !in probe, "codeOnly did not blank a KDoc mention")
        assertTrue("setStatusBarColor" in probe, "codeOnly blanked a real call")

        val offenders = sources.flatMap { f ->
            codeOnly(f.readText()).lines().withIndex()
                .filter { (_, line) -> banned.any { it in line } }
                .map { (i, _) -> "${f.name}:${i + 1}" }
        }

        assertEquals(
            emptyList<String>(), offenders,
            "these call an edge-to-edge API Play reports as deprecated. The theme " +
                "attributes replace them; adding one back puts the class into the " +
                "bundle again and the Play Console warning with it.",
        )
    }
}
