package com.vscodroid

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How often the folder-sync dialog is allowed to redraw.
 *
 * `SafSyncEngine.initialSync` reports once per file, and `MainActivity.openSafFolder`
 * answered each report with a `getQuantityString` and an `AlertDialog.setMessage`
 * posted to the main looper: a measure and layout pass per file, on the thread the
 * user is watching, behind a dialog they cannot cancel. A device folder with tens
 * of thousands of files is ordinary (a checkout, a photo directory), and the text
 * was changing far faster than anyone could read it, so the cost bought nothing.
 *
 * The throttle is on this side rather than in the engine on purpose: the engine
 * has no clock policy, and every other consumer of that callback would inherit
 * whichever one it acquired.
 */
class SyncProgressThrottleTest {

    @Test
    fun `a burst of files inside the interval redraws once`() {
        assertTrue(syncProgressIsDue(done = 1, total = 20_000, sinceLastMs = 5_000))
        assertFalse(syncProgressIsDue(done = 2, total = 20_000, sinceLastMs = 0))
        assertFalse(
            syncProgressIsDue(done = 3, total = 20_000, sinceLastMs = SYNC_PROGRESS_INTERVAL_MS - 1),
            "a file arriving inside the interval still redraws, so a large folder posts " +
                "one relayout per file onto the thread drawing the screen",
        )
        assertTrue(
            syncProgressIsDue(done = 4, total = 20_000, sinceLastMs = SYNC_PROGRESS_INTERVAL_MS)
        )
    }

    @Test
    fun `the last file is always drawn`() {
        // The reading the user is owed, and the one a throttle is most likely to
        // swallow: it arrives immediately after its predecessor. Without it the
        // dialog's last word is a count short of the total, and that is the number
        // left on screen while the watcher starts.
        assertTrue(
            syncProgressIsDue(done = 20_000, total = 20_000, sinceLastMs = 0),
            "the final count is throttled away, so the dialog ends saying fewer files " +
                "were copied than were",
        )
    }

    @Test
    fun `a folder of one file is not throttled into silence`() {
        assertTrue(syncProgressIsDue(done = 1, total = 1, sinceLastMs = 0))
    }
}
