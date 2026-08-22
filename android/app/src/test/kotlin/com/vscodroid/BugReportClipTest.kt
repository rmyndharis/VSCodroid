package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the bug report is not drawn on screen on its way to the clipboard.
 *
 * `CrashReporter.generateBugReport` gathers the last 200 lines of server output
 * and the text of the three most recent crash logs, and the crash dialog offers
 * to copy all of it. Android 13 and later render a preview of whatever is put on
 * the clipboard, so without `ClipDescription.EXTRA_IS_SENSITIVE` that material is
 * shown over the editor at the one moment the user is most likely to be handing
 * the device to somebody or taking a screenshot of it. `minSdk` is 33, so every
 * device this ships to draws that preview and no version guard is involved.
 *
 * Read out of the source, which is the weaker kind of test in this suite and is
 * used here for the reason `DownloadListenerWiringTest` gives: there is no seam.
 * The clip is built inline inside a dialog button handler on a private method of
 * an Activity, `ClipData.newPlainText` is static and `ClipboardManager` is a
 * system service, so a unit test has no way to reach the call and read back what
 * was put on the clipboard.
 *
 * The order is asserted as well as the presence. Setting the flag on a
 * `ClipDescription` after the clip has already been handed to the clipboard
 * changes nothing that has been shown, and it would read exactly like this test
 * passing.
 */
class BugReportClipTest {

    private val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt").readText()

    /** The body of a `private fun name(` declaration, to its closing brace. */
    private fun body(name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue(start >= 0) {
            "$name is gone from MainActivity.kt, so this test is measuring nothing. If it " +
                "moved or was renamed, point this at the new site rather than deleting it."
        }
        val open = source.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < source.length) {
            if (source[i] == '{') depth += 1
            if (source[i] == '}') {
                depth -= 1
                if (depth == 0) return source.substring(open, i + 1)
            }
            i += 1
        }
        throw AssertionError("Could not find the end of $name in MainActivity.kt")
    }

    /**
     * Comments removed, so prose about the rule cannot satisfy a search for the
     * rule. The lines this file checks are surrounded by a comment that names
     * both `EXTRA_IS_SENSITIVE` and `setPrimaryClip`.
     */
    private fun withoutComments(text: String): String {
        var inBlock = false
        return text.lines().joinToString("\n") { raw ->
            var line = raw
            if (inBlock) {
                val close = line.indexOf("*/")
                if (close < 0) return@joinToString ""
                inBlock = false
                line = line.substring(close + 2)
            }
            while (line.trimStart().startsWith("/*")) {
                val open = line.indexOf("/*")
                val close = line.indexOf("*/", open + 2)
                if (close < 0) {
                    inBlock = true
                    return@joinToString line.substring(0, open)
                }
                line = line.substring(0, open) + line.substring(close + 2)
            }
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    @Test
    fun `the copied bug report is marked sensitive before it reaches the clipboard`() {
        val dialog = withoutComments(body("checkPreviousCrash"))

        // Control. Without it a scan that stopped finding the copy action at all
        // would report green over a dialog that still puts the log on screen.
        assertTrue(dialog.contains("generateBugReport")) {
            "checkPreviousCrash no longer builds a bug report, so this test is measuring " +
                "nothing. If the copy action moved, point this at the new site."
        }

        val flagged = dialog.indexOf("EXTRA_IS_SENSITIVE")
        assertTrue(flagged >= 0) {
            "the bug report goes on the clipboard with no ClipDescription.EXTRA_IS_SENSITIVE, " +
                "so Android 13 and later draw a preview of it: the last 200 lines of server " +
                "output and three crash logs, rendered over the editor."
        }

        val handedOver = dialog.indexOf("setPrimaryClip")
        assertTrue(handedOver >= 0) {
            "checkPreviousCrash no longer puts the report on the clipboard; this test is " +
                "measuring nothing"
        }
        assertTrue(flagged < handedOver) {
            "the clip is marked sensitive after it has already been handed to the " +
                "clipboard, which is after the preview has been drawn. Set the flag on the " +
                "description before setPrimaryClip."
        }
    }
}
