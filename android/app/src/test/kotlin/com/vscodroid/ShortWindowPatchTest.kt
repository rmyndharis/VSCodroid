package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That patch 0033 puts the title bar and the status bar away in a short window, on Android
 * only, without taking a choice from the user.
 *
 * A phone in landscape with the soft keyboard up leaves the page about 122px, and the two
 * bars took 63 of it. The patch lives in TypeScript and its thresholds have to agree with
 * the native key row, which stands down on the same screens, so nothing but this reads both.
 *
 * Reading source rather than rendering, as [NarrowWindowPatchTest] does: no JVM test can
 * lay the workbench out.
 */
class ShortWindowPatchTest {

    /** The repository root is two levels above `android/app`, this suite's working directory. */
    private val patch: String = File("../../patches/0033-short-window-hides-title-and-status-bar.patch").also {
        check(it.isFile) {
            "${it.absolutePath} not found; if the patch was renumbered, point this case at the " +
                "new name rather than letting it pass by looking at nothing"
        }
    }.readText()

    private val addedLines: String = patch.lines().filter { it.startsWith("+") && !it.startsWith("+++") }.joinToString("\n")
    private val removedLines: String = patch.lines().filter { it.startsWith("-") && !it.startsWith("---") }.joinToString("\n")

    private fun constant(name: String): Int? =
        Regex("""const $name = (\d+);""").find(addedLines)?.groupValues?.get(1)?.toInt()

    @Test
    fun `the bars leave before the key row does and return after a settling keyboard`() {
        val enter = constant("SHORT_WINDOW_HEIGHT")
        val release = constant("SHORT_WINDOW_RELEASE_HEIGHT")
        val keyRowMinimum = Regex("""private const val MIN_PAGE_HEIGHT_DP = (\d+)""")
            .find(SourceScan.read("src/main/kotlin/com/vscodroid/keyboard/ExtraKeyRow.kt"))
            ?.groupValues?.get(1)?.toInt()

        assertNotNull(enter, "patch 0033 no longer defines SHORT_WINDOW_HEIGHT")
        assertNotNull(release, "patch 0033 no longer defines SHORT_WINDOW_RELEASE_HEIGHT")
        assertNotNull(keyRowMinimum, "ExtraKeyRow no longer defines MIN_PAGE_HEIGHT_DP")
        assertTrue(
            enter!! > keyRowMinimum!!,
            "the key row stands down when the page it leaves is under ${keyRowMinimum}px, and a " +
                "window that short must already have lost its bars: with ${enter}px it would not",
        )
        assertTrue(
            release!! > enter,
            "the bars return at ${release}px but leave at ${enter}px: with no gap between the " +
                "two a keyboard that is still settling toggles them on every layout",
        )
    }

    @Test
    fun `only Android puts the bars away`() {
        assertTrue(addedLines.contains("isWeb, isIOS, isAndroid }"), "layout.ts no longer imports isAndroid")
        assertTrue(
            Regex("""if \(!isAndroid\) \{\n\+\s*return;""").containsMatchIn(addedLines),
            "updateShortWindow must return early off Android: a desktop browser window under " +
                "200px would lose its title bar and status bar",
        )
    }

    @Test
    fun `the status bar goes back to what the user set`() {
        assertTrue(
            addedLines.contains("this.shortWindow || this.stateModel.getRuntimeValue(LayoutStateKeys.STATUSBAR_HIDDEN)"),
            "the status bar must stay hidden for a user who hid it, and come back only if the " +
                "setting has it shown",
        )
    }

    @Test
    fun `every title bar decision goes through one method`() {
        val direct = "shouldShowCustomTitleBar(this.configurationService, mainWindow, this.state.runtime.menuBar.toggled)"

        assertEquals(7, removedLines.lines().count { it.contains(direct) }, "the patch no longer replaces all seven direct asks")
        assertEquals(
            1,
            addedLines.lines().count { it.contains(direct) },
            "only Layout#shouldShowTitleBar may ask shouldShowCustomTitleBar: any other site " +
                "can bring the title bar back while the keyboard is still up",
        )
        // The seven sites it replaces, and the one in updateShortWindow that applies the answer.
        assertEquals(8, addedLines.lines().count { it.contains("this.shouldShowTitleBar()") })
    }
}
