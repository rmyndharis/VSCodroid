package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That a dialog is taken down while its screen is finishing, and the dismissal skipped only once the
 * screen is destroyed.
 *
 * Dismissing a dialog whose window has gone throws, which is why each of these is guarded. A screen
 * that is finishing still has its window until it is destroyed, so a guard that also skipped a
 * finishing screen left the dialog over the closing screen, and the platform logged its window as
 * leaked when the screen went.
 *
 * Source reading: the dialogs belong to an Activity and its window, which a plain JVM test cannot
 * build.
 */
class DismissOnFinishingScreenTest {

    @Test
    fun `no dialog is left up for a screen that is only finishing`() {
        val code = SourceScan.withoutComments(SourceScan.readAll(SourceScan.MAIN_ACTIVITY_FILES)).lines()
        val guarded = code.filter { it.contains(".dismiss()") && it.contains("isDestroyed") }
        // The control: the sync dialog's four handlers, the unanswered removal question and the
        // away-from-home notice under a crash page are guarded dismissals today.
        assertTrue(guarded.size >= 6) {
            "found ${guarded.size} guarded dismissals in MainActivity.kt, so this case reads too little: $guarded"
        }

        assertEquals(
            emptyList<String>(),
            code.filter { it.contains(".dismiss()") && it.contains("isFinishing") }.map { it.trim() },
            "a dialog is left up over a screen that is finishing but still has its window; guard the " +
                "dismissal on isDestroyed alone",
        )
    }
}
