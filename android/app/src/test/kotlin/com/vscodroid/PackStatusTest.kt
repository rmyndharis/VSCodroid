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
 * `if (isTerminalPackStatus(status)) downloadNext()` call from
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
 * wrongly treated as finished loses that toolchain, and there is no in-app way
 * to install it afterwards. A pack wrongly waited on loses that one and every
 * toolchain queued behind it. Losing one is better than losing the remainder.
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

/**
 * Tests for [isCurrentDownload] — the check that decides whether a terminal
 * state is allowed to move the queue.
 *
 * This is the invariant the old code never expressed: it deduplicated by pack
 * name, which stops the same pack advancing twice and does nothing about a
 * different pack advancing once. The listener is registered for the app rather
 * than for one fetch, and every queued pack has a progress row, so a state
 * naming a pack that is not the current download reaches the handler.
 *
 * These have the same boundary as the tests above, and it is worth stating twice
 * because the first draft of this comment claimed otherwise: deleting the
 * isCurrentDownload guard from handleDownloadState leaves every test here green.
 * Measured, with the results directory cleared first. Nothing in this project
 * can pin a call inside an Activity method -- there is no Robolectric -- so what
 * is pinned is the rule, not that the code still consults it.
 */
class CurrentDownloadTest {

    private val queue = listOf("toolchain_go", "toolchain_ruby", "toolchain_java")

    @Test
    fun `the pack being downloaded advances the queue`() {
        assertTrue(isCurrentDownload("toolchain_ruby", queue, 1))
    }

    @Test
    fun `a pack still queued behind the current one does not`() {
        // The skip that mattered: java reporting anything while ruby is
        // downloading would have stepped over ruby and left it uninstalled,
        // with its row still reading "installing".
        assertFalse(isCurrentDownload("toolchain_java", queue, 1))
    }

    @Test
    fun `a pack the queue has already passed does not`() {
        // This is also how a repeat is handled. The first report moves the
        // index, so a second report for the same pack is no longer current --
        // no separate bookkeeping needed.
        assertFalse(isCurrentDownload("toolchain_go", queue, 1))
    }

    @Test
    fun `nothing advances the queue once it has passed the end`() {
        // Otherwise a late arrival reaches launchMain() a second time.
        assertFalse(isCurrentDownload("toolchain_java", queue, queue.size))
    }

    @Test
    fun `nothing advances the queue before it has started`() {
        // downloadNext() starts at -1 and pre-increments.
        assertFalse(isCurrentDownload("toolchain_go", queue, -1))
    }
}
