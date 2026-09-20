package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the bug report is not drawn on screen on its way to the clipboard.
 *
 * `CrashReporter.generateBugReport` gathers the last 200 lines of server output
 * and the text of the three most recent crash logs, and two screens offer to copy
 * all of it. Android 13 and later render a preview of whatever is put on the
 * clipboard, so without `ClipDescription.EXTRA_IS_SENSITIVE` that material is
 * shown over the editor at the one moment the user is most likely to be handing
 * the device to somebody or taking a screenshot of it. `minSdk` is 33, so every
 * device this ships to draws that preview and no version guard is involved.
 *
 * Read out of the source, which is the weaker kind of test in this suite and is
 * used here for the reason `DownloadListenerWiringTest` gives: there is no seam.
 * The clip is built inside a private method of an Activity,
 * `ClipData.newPlainText` is static and `ClipboardManager` is a system service,
 * so a unit test has no way to reach the call and read back what was put on the
 * clipboard.
 *
 * It reads `copyBugReport` and not the screens that call it. The copy used to be
 * written inline in the crash dialog's button handler, which is where this test
 * pointed; a second screen now needs the same clip, the server-gave-up page,
 * whose whole purpose is to be reachable when no workbench and no Kotlin crash
 * exist. One function is what stops the two drifting on the part that is easy to
 * leave out, and reading it here covers both callers at once.
 *
 * The order is asserted as well as the presence. Setting the flag on a
 * `ClipDescription` after the clip has already been handed to the clipboard
 * changes nothing that has been shown, and it would read exactly like this test
 * passing.
 */
class BugReportClipTest {

    private fun source() = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /**
     * Comments removed, so prose about the rule cannot satisfy a search for the
     * rule. The lines this file checks are surrounded by a comment that names
     * both `EXTRA_IS_SENSITIVE` and `setPrimaryClip`.
     */
    private fun copyBody() =
        SourceScan.withoutComments(SourceScan.body(source(), "private suspend fun copyBugReport("))

    @Test
    fun `the copied bug report is marked sensitive before it reaches the clipboard`() {
        val copy = copyBody()

        // Control. Without it a scan that stopped finding the copy action at all
        // would report green over a screen that still puts the log on view.
        assertTrue(copy.contains("generateBugReport")) {
            "copyBugReport no longer builds a bug report, so this test is measuring " +
                "nothing. If the copy action moved, point this at the new site."
        }

        val flagged = copy.indexOf("EXTRA_IS_SENSITIVE")
        assertTrue(flagged >= 0) {
            "the bug report goes on the clipboard with no ClipDescription.EXTRA_IS_SENSITIVE, " +
                "so Android 13 and later draw a preview of it: the last 200 lines of server " +
                "output and three crash logs, rendered over the editor."
        }

        val handedOver = copy.indexOf("setPrimaryClip")
        assertTrue(handedOver >= 0) {
            "copyBugReport no longer puts the report on the clipboard; this test is " +
                "measuring nothing"
        }
        assertTrue(flagged < handedOver) {
            "the clip is marked sensitive after it has already been handed to the " +
                "clipboard, which is after the preview has been drawn. Set the flag on the " +
                "description before setPrimaryClip."
        }
    }

    /**
     * And it is not built on the main thread. `generateBugReport` reads three
     * crash files whole and all of `server.log` under the lock a rotation holds,
     * a full read and a full write of that file on the drain thread, so a call
     * that lands during one waits it out. Both callers fire it from a button, on
     * the main thread, on a screen the user is looking at and cannot leave.
     */
    @Test
    fun `the bug report is built off the main thread before it reaches the clipboard`() {
        val copy = copyBody()
        val built = copy.indexOf("generateBugReport")
        assertTrue(built >= 0) { "copyBugReport no longer builds a bug report" }

        val hop = copy.lastIndexOf("withContext(Dispatchers.IO)", built)
        assertTrue(hop >= 0) {
            "generateBugReport runs on the main thread: it reads three crash files and all " +
                "of server.log under the lock a rotation holds, a stall on a screen the " +
                "user cannot leave"
        }
        assertTrue(copy.indexOf("setPrimaryClip") > built) { "the clip is written before the report exists" }
    }

    /**
     * And nothing writes a second clip beside it.
     *
     * The case above moved from the crash dialog to the function it now shares
     * with the server-gave-up page, which is right for the rule it owns and took
     * the only coverage the dialog had with it. A re-inlined copy in either
     * screen, without the flag, would leave both cases above green. Counting the
     * hand-over is what refuses that: there is one writer, so there is one place
     * the rule has to hold.
     */
    @Test
    fun `the clipboard is written from exactly one place`() {
        val writes = Regex("setPrimaryClip\\(").findAll(SourceScan.withoutComments(source())).count()

        assertEquals(1, writes) {
            "MainActivity writes the clipboard from $writes places. The bug report has one " +
                "writer on purpose, copyBugReport, because EXTRA_IS_SENSITIVE is the part " +
                "that gets left out of a second copy."
        }
    }

    /**
     * And both screens still route through it.
     *
     * The control for the case above: one writer proves nothing if neither screen
     * reaches it any more.
     */
    @Test
    fun `both screens copy through the shared function`() {
        val src = SourceScan.withoutComments(source())

        assertTrue(SourceScan.body(src, "private fun checkPreviousCrash(").contains("copyBugReport()")) {
            "the crash dialog no longer copies the bug report"
        }
        assertTrue(SourceScan.body(src, "private fun copyDiagnostics(").contains("copyBugReport()")) {
            "the server-gave-up page's copy control no longer copies the bug report"
        }
    }
}
