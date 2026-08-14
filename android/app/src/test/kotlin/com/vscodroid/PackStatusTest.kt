package com.vscodroid

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [isTerminalPackStatus] — the predicate that decides whether the
 * first-run download queue moves on.
 *
 * What these cover, and what they do not. They pin the predicate: which statuses
 * mean a pack is finished. They do NOT pin the wiring -- deleting the
 * `if (isTerminalPackStatus(status)) advancePast(packName)` call from
 * handleDownloadState leaves all four of them green, measured rather than
 * assumed. That call cannot be reached from a JVM test because it lives in an
 * Activity method and this project has no Robolectric, so the gap is stated here
 * rather than papered over: if you are touching handleDownloadState, these tests
 * will not tell you when you have broken it.
 *
 * The predicate is still worth pinning, because getting it wrong is what stalled
 * the queue: the decision used to live inside two branches of a `when` with no
 * `else`, so CANCELED, NOT_INSTALLED and UNKNOWN advanced nothing and every
 * toolchain behind the stalled one went uninstalled.
 *
 * The cost of being wrong is not symmetric, and these tests encode that: a pack
 * wrongly treated as finished loses one toolchain, which can be installed later
 * from inside the app. A pack wrongly waited on loses the rest of the queue.
 */
class PackStatusTest {

    @Test
    fun `states that are still going somewhere do not advance the queue`() {
        listOf(
            AssetPackStatus.DOWNLOADING,
            AssetPackStatus.TRANSFERRING,
            AssetPackStatus.PENDING,
            AssetPackStatus.WAITING_FOR_WIFI,
            AssetPackStatus.REQUIRES_USER_CONFIRMATION,
        ).forEach {
            assertFalse(isTerminalPackStatus(it), "status $it is still in progress")
        }
    }

    @Test
    fun `the three states that stalled setup now advance it`() {
        // The reported bug, exactly. CANCELED is what arrives when the download
        // is cancelled from the Play notification rather than from this screen.
        listOf(
            AssetPackStatus.CANCELED,
            AssetPackStatus.NOT_INSTALLED,
            AssetPackStatus.UNKNOWN,
        ).forEach {
            assertTrue(isTerminalPackStatus(it), "status $it must not stall the queue")
        }
    }

    @Test
    fun `the two states that always worked still advance it`() {
        assertTrue(isTerminalPackStatus(AssetPackStatus.COMPLETED))
        assertTrue(isTerminalPackStatus(AssetPackStatus.FAILED))
    }

    @Test
    fun `a status this build has never heard of advances it`() {
        // The default has to be "move on". A newer Play library adding a state
        // must not be able to reproduce the stall, and no code here can know
        // that state's name in advance.
        assertTrue(isTerminalPackStatus(9999), "an unknown status must not stall the queue")
    }
}
