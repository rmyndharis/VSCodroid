package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That patch 0032 lets the Settings editor shrink to the narrowest page the app produces,
 * and only on Android.
 *
 * The list of settings keeps a minimum width, and the workbench's own is 500px: wider than
 * a phone, where the list ran a quarter of its width past the right edge. The patch lowers
 * it under `platform.isAndroid`. The new number has to fit the narrowest page the app
 * itself makes, which is the 320px floor of UI Scale (`MIN_WIDTH` in the script
 * [uiScaleScript] adds), or the overflow returns at 125%. The two numbers live in a
 * TypeScript patch and in a Kotlin string, so nothing but this reads both.
 *
 * Reading source rather than rendering, as [ActivityBarItemSizeTest] does: no JVM test can
 * lay the workbench out.
 */
class NarrowWindowPatchTest {

    /** The repository root is two levels above `android/app`, this suite's working directory. */
    private val patch: String = File("../../patches/0032-settings-list-and-modal-shrink-to-a-phone.patch").also {
        check(it.isFile) {
            "${it.absolutePath} not found; if the patch was renumbered, point this case at the " +
                "new name rather than letting it pass by looking at nothing"
        }
    }.readText()

    private val addedLines: String = patch.lines().filter { it.startsWith("+") && !it.startsWith("+++") }.joinToString("\n")

    @Test
    fun `the list fits the narrowest page UI Scale produces`() {
        val list = Regex("""LIST_MIN_WIDTH: number = platform\.isAndroid \? (\d+) :""")
            .find(patch)?.groupValues?.get(1)?.toInt()
        val page = Regex("""var MIN_WIDTH = (\d+);""")
            .findAll(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))
            .map { it.groupValues[1].toInt() }
            .toList()

        assertNotNull(list, "patch 0032 no longer lowers LIST_MIN_WIDTH under platform.isAndroid")
        assertEquals(1, page.size, "expected one MIN_WIDTH in the UI Scale script, found $page")
        assertTrue(
            list!! <= page.single(),
            "the settings list may shrink to ${list}px but UI Scale can leave the page " +
                "${page.single()}px wide: the list would overflow again at 125%. Change both together",
        )
    }

    @Test
    fun `the table of contents keeps the threshold it had`() {
        assertNull(
            Regex("""NARROW_TOTAL_WIDTH\s*[:=]""").find(addedLines),
            "NARROW_TOTAL_WIDTH decides when the table of contents is dropped. Lowering it " +
                "with the list's minimum would drop the table on tablets that show it today",
        )
    }

    @Test
    fun `the modal is centred from the width it really gets`() {
        assertTrue(
            addedLines.contains("Math.min(effectiveMinWidth, containerDimension.width)"),
            "the modal's minimum width must not exceed the window's",
        )
        assertTrue(
            addedLines.contains("resizableElement.minSize = new Dimension(minWidth, MODAL_MIN_HEIGHT);"),
            "the wrapper clamps up to the minSize it was created with, 400px, so without this " +
                "line the modal is still 400px wide in a narrower window however `width` is clamped",
        )
        assertTrue(
            addedLines.contains("width = Math.min(Math.max(width, minWidth), containerDimension.width);"),
            "the modal is centred from `width`, so it has to be the clamped one, or the modal " +
                "starts where the narrower width put it and ends past the right edge",
        )
    }

    @Test
    fun `a settings dropdown cannot be wider than its row`() {
        val css = File("../../scripts/build-vscode-oss.sh").readText()
        assertTrue(
            css.contains(".settings-editor .setting-item-control.select-container,") &&
                css.contains(".settings-editor .setting-item-control select { max-width: 100%; }"),
            "the appended mobile CSS no longer caps the settings dropdown and its box: both are " +
                "320px wide and ran off a 360px phone",
        )
    }
}
