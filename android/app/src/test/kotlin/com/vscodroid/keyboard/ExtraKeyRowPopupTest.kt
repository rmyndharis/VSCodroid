package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the alternates window is owned by the row that opens it.
 *
 * A [LongPressPopup] is a window, not a child view: nothing takes it down with
 * the row it was anchored to. It used to be constructed and dropped on the
 * floor, so the only things that could close it were the user selecting an
 * alternate and the user touching outside it. A row torn down with one open,
 * which is what finishing the activity does and what a configuration change
 * outside `MainActivity`'s `configChanges` list does, left a window attached to
 * a token that no longer exists.
 *
 * Source-reading, for the reason the rest of this package gives: [ExtraKeyRow]
 * is a `View` whose initialiser reaches resources, colours and display metrics
 * before its constructor finishes, so no JVM test can build one. Every case
 * below opens with a control, so a scan that has stopped matching cannot report
 * a clean row by finding nothing at all.
 */
class ExtraKeyRowPopupTest {

    /**
     * The row's source with comment lines dropped, so prose naming a call is
     * not a call. Both comment forms, because both switch code off, and this
     * file's subject is a `dismiss()` that the class comments also discuss.
     */
    private fun code(): List<String> {
        val file = File("src/main/kotlin/com/vscodroid/keyboard/ExtraKeyRow.kt")
        assertTrue(
            file.isFile,
            "ExtraKeyRow.kt is not at ${file.absolutePath}; this test would otherwise " +
                "pass by reading nothing",
        )
        var inBlock = false
        return file.readText().lines().filterNot { line ->
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

    @Test
    fun `the row keeps the popup it opened`() {
        val lines = code()
        val start = lines.indexOfFirst { it.contains("private fun showLongPressPopup(") }
        assertTrue(start >= 0, "showLongPressPopup is no longer where this test looks")

        val body = lines.drop(start).take(10).joinToString("\n")
        assertTrue(
            body.contains("LongPressPopup(context, alternates)"),
            "the window found does not open the popup, so its verdict below is worth " +
                "nothing. It reads:\n$body",
        )
        assertTrue(
            body.contains("longPressPopup = LongPressPopup("),
            "the popup is constructed and dropped, so nothing owned by the app can close " +
                "it and a second long press stacks another window over it. It reads:\n$body",
        )
        assertTrue(
            body.contains("longPressPopup?.dismiss()"),
            "opening a popup no longer closes the one already up. It reads:\n$body",
        )
    }

    @Test
    fun `a configuration change closes the popup before deciding it changed nothing`() {
        val lines = code()
        val start = lines.indexOfFirst { it.contains("override fun onConfigurationChanged(") }
        assertTrue(start >= 0, "onConfigurationChanged is no longer where this test looks")

        val body = lines.drop(start).take(12)
        val dismiss = body.indexOfFirst { it.contains("longPressPopup?.dismiss()") }
        val earlyReturn = body.indexOfFirst { it.contains("if (repacked == pages) return") }

        // The control. Both landmarks have to be in the window this reads, or a
        // renamed guard would leave the case passing on an empty comparison.
        assertTrue(dismiss >= 0, "the config-change path no longer dismisses the popup at all")
        assertTrue(
            earlyReturn >= 0,
            "the repack guard is no longer where this test looks, so the ordering below is " +
                "being asserted about nothing",
        )

        assertTrue(
            dismiss < earlyReturn,
            "the popup is dismissed only after the repack guard has decided the pages did " +
                "not change, and on a rotation they never do: smallestScreenWidthDp is the " +
                "smaller dimension by definition, so turning the phone over always takes " +
                "that return and leaves the alternates floating over the new layout",
        )
    }

    @Test
    fun `the popup goes down with the keyboard`() {
        val lines = code()
        val start = lines.indexOfFirst { it.contains("visibility = if (imeVisible)") }
        assertTrue(
            start >= 0,
            "the row no longer drives its visibility from the IME insets, so this case is " +
                "reading nothing",
        )

        val body = lines.drop(start).take(8).joinToString("\n")
        assertTrue(
            body.contains("resetModifiersIfNeeded()"),
            "the hidden-IME branch is no longer where this test looks. It reads:\n$body",
        )
        assertTrue(
            body.contains("longPressPopup?.dismiss()"),
            "the row goes GONE with the keyboard and leaves the alternates window standing " +
                "over the editor, with no key under it and nothing to say what it belongs " +
                "to. It reads:\n$body",
        )
    }

    @Test
    fun `a row leaving its window takes the popup with it`() {
        val lines = code()
        val start = lines.indexOfFirst { it.contains("override fun onDetachedFromWindow()") }
        assertTrue(
            start >= 0,
            "the row no longer closes its popup when it leaves the window, so an activity " +
                "finished or recreated with the alternates open leaks that window",
        )

        val body = lines.drop(start).take(5).joinToString("\n")
        assertTrue(
            body.contains("super.onDetachedFromWindow()"),
            "the override never reaches super, which is a different bug in the same " +
                "place. It reads:\n$body",
        )
        assertTrue(
            body.contains("longPressPopup?.dismiss()"),
            "detaching the row no longer dismisses the popup, so the window outlives the " +
                "view it is anchored to. It reads:\n$body",
        )
    }
}
