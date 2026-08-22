package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every way out of the crash dialog has to clear the crash log.
 *
 * `checkPreviousCrash` runs unconditionally from `onCreate`, and its only guard is
 * `CrashReporter.hasPendingCrash()`, which is false only once the files under
 * `cacheDir/crash-logs` are deleted. The dialog has three exits and two of them
 * deleted: the "Dismiss" button and "Copy Report". The third is the gesture most
 * dialogs are closed with, Back or a tap outside, and it ran no handler at all, so
 * the same modal came back over the loading editor on every later cold start.
 *
 * Read off the source, because the dialog needs a device: an `AlertDialog.Builder`
 * on an Activity is not something a plain JVM test can build, and `CrashReporterTest`
 * already drives the object underneath it.
 *
 * NEGATIVE CONTROL, measured rather than assumed: deleting the
 * `.setOnCancelListener { CrashReporter.clearCrashLogs() }` line reddens
 * `cancelling the dialog clears the crash log` and nothing else; adding a
 * `setNegativeButton` whose body clears nothing reddens `an exit added without a
 * clear is refused` and nothing else.
 */
class CrashDialogExitTest {

    private val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt").readText()

    /** The body of a `private fun name(` declaration, to its closing brace. */
    private fun body(name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue(start >= 0) {
            "$name is gone from MainActivity.kt, so this test is measuring nothing. " +
                "If it moved or was renamed, point this at the new site."
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

    /** Comment text removed, so the prose beside a rule cannot satisfy a search for it. */
    private fun code(text: String): List<String> =
        text.lines().map { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }

    private val dialog by lazy { code(body("checkPreviousCrash")) }

    @Test
    fun `the body being checked was actually found`() {
        assertTrue(dialog.any { it.contains("setPositiveButton") }) {
            "checkPreviousCrash no longer builds a dialog, so these cases are searching " +
                "the wrong text and would pass over anything"
        }
    }

    @Test
    fun `cancelling the dialog clears the crash log`() {
        val cancel = dialog.filter { it.contains("setOnCancelListener") }

        assertEquals(
            1, cancel.size,
            "Back and a tap outside cancel this dialog without running either button, " +
                "and nothing else on a normal path deletes a crash log, so the modal " +
                "returns over the editor on every launch from then on. Found: " +
                cancel.map { it.trim() },
        )
        assertTrue(cancel.single().contains("clearCrashLogs()")) {
            "the cancel handler does not clear the log, which is the one thing that " +
                "stops the dialog coming back. Found: ${cancel.single().trim()}"
        }
    }

    /**
     * ⚠️ Its ceiling, measured rather than assumed: deleting the cancel listener
     * leaves this green, because it counts the exits that are present and a missing
     * exit is not one of them. That is the case above's job, and it does redden.
     * What this one catches is the other direction, measured too: adding a
     * `setNegativeButton` with an empty body reddens it.
     */
    @Test
    fun `an exit added without a clear is refused`() {
        // Whichever exit forgets, the user is back to a modal that returns on every
        // launch, so the count is the assertion rather than a list of the three
        // that exist today.
        val exits = dialog.count {
            it.contains("setPositiveButton") ||
                it.contains("setNeutralButton") ||
                it.contains("setNegativeButton") ||
                it.contains("setOnCancelListener")
        }
        val clears = dialog.count { it.contains("clearCrashLogs()") }

        assertEquals(
            exits, clears,
            "an exit from the crash dialog leaves the crash log in place. Exits found: " +
                dialog.filter { it.contains("set") && it.contains("Button") || it.contains("setOnCancel") }
                    .map { it.trim() },
        )
    }
}
