package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A write-back that gives up has to reach a screen, and nothing in the sync engine has
 * one.
 *
 * Two halves, and the failure this pins is the first one going missing quietly. The
 * engine's seam defaults to a no-op, deliberately: a default that reached for
 * `Looper.getMainLooper()` threw "not mocked" in every existing test that drives a
 * failing write-back. That makes the wiring load-bearing rather than decorative, and an
 * unwired seam is indistinguishable from the silence this was meant to end.
 */
class WriteBackNoticeWiringTest {

    private val activity = File("../../android/app/src/main/kotlin/com/vscodroid/MainActivity.kt")

    /**
     * Read from the source rather than driven through the Activity, which needs a device.
     *
     * ⚠️ Its ceiling, measured rather than assumed: wrapping the call in `if (false)`
     * leaves this green. It catches the wiring being deleted, which is the likely
     * regression, and cannot catch it being made unreachable. The behaviour either side
     * of it is covered where it can be: the engine announcing is pinned by
     * InitialSyncWiringTest, and the throttle by the case below.
     */
    @Test
    fun `MainActivity asks to be told when a write-back gives up`() {
        assertTrue(activity.isFile, "MainActivity.kt is not where this test expects it")
        val source = activity.readText()

        assertTrue(
            Regex("""safManager\.onWriteBackFailed\s*\{""").containsMatchIn(source),
            "nothing wires the write-back notice, so a save that never reached the " +
                "device folder is silent again",
        )
        assertTrue(
            Regex("""saf_write_back_failed""").containsMatchIn(source),
            "the notice does not name the string that tells the user what happened",
        )
    }

    /**
     * The throttle belongs to the manager, so one refusing provider is one notice.
     *
     * Asserted as a rule rather than as a constant: the number is a product decision and
     * may move, but "the first failure speaks and the next one inside the interval does
     * not" is the behaviour.
     */
    @Test
    fun `the first failure is announced and a prompt second one is not`() {
        val interval = SafStorageManager.FAILURE_NOTICE_INTERVAL_MS
        // A wall-clock value, because that is what the caller passes. Toy numbers put
        // the first failure inside the interval of a zero last-announced time and made
        // this assert the opposite of the shipped behaviour.
        val now = 1_700_000_000_000L

        assertTrue(
            SafStorageManager.shouldAnnounce(now = now, lastAnnouncedAt = 0),
            "the first failure of a session has to be said",
        )
        assertFalse(
            SafStorageManager.shouldAnnounce(now = now + interval - 1, lastAnnouncedAt = now),
            "a provider that refuses everything would otherwise bury the editor",
        )
        assertTrue(
            SafStorageManager.shouldAnnounce(now = now + interval, lastAnnouncedAt = now),
            "the notice has to come back, or a later failure is silent again",
        )
    }
}
