package com.vscodroid.setup

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.isTerminalPackStatus
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests for [toolchainFailureFor], the translation of Play's asset pack error
 * codes into the reasons the two toolchain screens display.
 *
 * These pin the rule only. What carries it, that the FAILED branch actually
 * calls this and that the reason reaches the callback exactly once, is
 * [PlayFailureWiringTest] below: a mapping nothing consults is as silent as no
 * mapping at all, and that is the shape the original defect had.
 */
class PlayFailureReasonTest {

    @Test
    fun `a network code sends the user to their connection`() {
        assertEquals(
            ToolchainFailure.NETWORK,
            toolchainFailureFor(AssetPackErrorCode.NETWORK_ERROR),
        )
    }

    @Test
    fun `a storage code sends the user to free space`() {
        assertEquals(
            ToolchainFailure.STORAGE,
            toolchainFailureFor(AssetPackErrorCode.INSUFFICIENT_STORAGE),
        )
    }

    @Test
    fun `a pack missing from the published bundle sends the user to the next update`() {
        // PACK_UNAVAILABLE is documented as the pack not being in the App Bundle
        // that was published, which is the same condition the HTTP path calls
        // NOT_PUBLISHED when the release carries no ZIP for it.
        assertEquals(
            ToolchainFailure.NOT_PUBLISHED,
            toolchainFailureFor(AssetPackErrorCode.PACK_UNAVAILABLE),
        )
    }

    @Test
    fun `an install Play did not deliver is told that is the requirement`() {
        listOf(
            AssetPackErrorCode.APP_NOT_OWNED,
            AssetPackErrorCode.UNRECOGNIZED_INSTALLATION,
        ).forEach {
            assertEquals(
                ToolchainFailure.PLAY_REQUIRED,
                toolchainFailureFor(it),
                "code $it means Play does not recognise this copy of the app",
            )
        }
    }

    @Test
    fun `an unavailable app is not reported as an unpublished toolchain`() {
        // The tempting one, and the reason this test exists. APP_UNAVAILABLE is
        // documented as the app, its version code, or the user's access to it
        // being what Play cannot serve. NOT_PUBLISHED would name the toolchain
        // instead and send the user to wait for an update that would change
        // nothing, so the honest answer is the one that says to read the log
        // rather than a wrong one that sounds specific.
        assertEquals(
            ToolchainFailure.INTERNAL,
            toolchainFailureFor(AssetPackErrorCode.APP_UNAVAILABLE),
        )
    }

    @Test
    fun `codes naming a condition no message can act on stay internal`() {
        listOf(
            AssetPackErrorCode.ACCESS_DENIED,
            AssetPackErrorCode.API_NOT_AVAILABLE,
            AssetPackErrorCode.INVALID_REQUEST,
            AssetPackErrorCode.DOWNLOAD_NOT_FOUND,
            AssetPackErrorCode.CONFIRMATION_NOT_REQUIRED,
            AssetPackErrorCode.INTERNAL_ERROR,
            AssetPackErrorCode.NO_ERROR,
        ).forEach {
            assertEquals(
                ToolchainFailure.INTERNAL,
                toolchainFailureFor(it),
                "code $it has no reason in the enum that the user could act on",
            )
        }
    }

    @Test
    fun `a code this build has no constant for stays internal`() {
        // PLAY_STORE_NOT_FOUND and NETWORK_UNRESTRICTED are in the library's own
        // IntDef listing but are not declared by asset-delivery 2.2.2, so they can
        // only arrive as numbers. A later library version adding codes must not be
        // able to reach anything other than the catch-all either.
        listOf(-11, -12, -9999).forEach {
            assertEquals(
                ToolchainFailure.INTERNAL,
                toolchainFailureFor(it),
                "code $it is not one this build can name",
            )
        }
    }
}

/**
 * The wiring [PlayFailureReasonTest] cannot reach: that a Play pack ending in
 * FAILED emits its reason, once, without stalling the first-run queue.
 *
 * Three things have to hold together and each fails silently on its own. The
 * FAILED branch has to call [toolchainFailureFor] at all, which it did not: it
 * logged the code and returned, leaving the row red with no explanation. The
 * status still has to be FAILED, because that is what [isTerminalPackStatus]
 * reads to move the queue on, and a pack that stops reporting a terminal status
 * strands every pack behind it. And it has to be one callback rather than two,
 * because the first-run screen advances on each one, so a second would skip
 * whichever pack came next.
 *
 * COMPLETED is held back by the same `if`, for the older of the two reasons, and
 * is covered here too: the two clauses live on one line, so an edit aimed at one
 * of them can drop the other, and either loss reports a status twice.
 *
 * Driven through the real listener rather than a source-text check: the manager
 * registers it with [AssetPackManager], so a mocked manager hands the test the
 * exact object Play would call.
 */
class PlayFailureWiringTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packManager: AssetPackManager
    private val listener = slot<AssetPackStateUpdateListener>()

    /**
     * Every callback the manager made: pack, status, reason.
     *
     * Thread-safe because it has to be: the install a COMPLETED pack triggers
     * runs on the manager's own executor and reports from there.
     */
    private val events = CopyOnWriteArrayList<Triple<String, Int, ToolchainFailure?>>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        packManager = mockk(relaxed = true)
        every { packManager.registerListener(capture(listener)) } just Runs
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /** The listener the manager gave Play, with the callback recorder behind it. */
    private fun playListener(): AssetPackStateUpdateListener {
        val manager = ToolchainManager(context)
        manager.onStateChange = { pack, status, _, why ->
            events.add(Triple(pack, status, why))
        }
        manager.registerListener()
        check(listener.isCaptured) { "the manager registered no listener with Play" }
        return listener.captured
    }

    private fun state(status: Int, errorCode: Int): AssetPackState {
        val state = mockk<AssetPackState>(relaxed = true)
        every { state.name() } returns "toolchain_go"
        every { state.status() } returns status
        every { state.errorCode() } returns errorCode
        return state
    }

    @Test
    fun `a failed pack reports why, once, and still ends the queue's wait`() {
        playListener().onStateUpdate(
            state(AssetPackStatus.FAILED, AssetPackErrorCode.INSUFFICIENT_STORAGE),
        )

        assertEquals(
            1,
            events.size,
            "a failed pack must be reported exactly once; the first-run screen " +
                "advances on every report, so a second skips the next toolchain",
        )
        assertEquals("toolchain_go", events[0].first)
        assertEquals(
            ToolchainFailure.STORAGE,
            events[0].third,
            "the reason never left the log line, which is the whole defect",
        )
        assertEquals(AssetPackStatus.FAILED, events[0].second)
        assertTrue(
            isTerminalPackStatus(events[0].second),
            "the status reported must still end the first-run queue's wait, or " +
                "every toolchain queued behind this one is stranded",
        )
    }

    @Test
    fun `the raw error code is still logged`() {
        // The reason on screen is a sentence for the user; the code is what a bug
        // report needs, and it is the only record left of the codes that map to
        // INTERNAL.
        playListener().onStateUpdate(
            state(AssetPackStatus.FAILED, AssetPackErrorCode.APP_UNAVAILABLE),
        )

        verify {
            Logger.e(any(), match { it.contains("errorCode=-1") }, any())
        }
    }

    @Test
    fun `a completed pack is not reported before its files are installed`() {
        // COMPLETED from Play means the pack has arrived, not that the toolchain
        // works: the copy, the chmod and the symlinks still have to run, and the
        // report the first-run screen advances on belongs to the end of that. Sent
        // from here as well, it arrives twice, the screen advances twice, and
        // whichever toolchain was picked after this one is silently skipped.
        //
        // There is nothing to install in a test, so the install path ends in a
        // failure. Waiting for that report is what makes this deterministic: the
        // report under test would be the synchronous one, so by the time the
        // asynchronous one lands it would already be in the list.
        every { packManager.getPackLocation(any()) } returns null

        playListener().onStateUpdate(
            state(AssetPackStatus.COMPLETED, AssetPackErrorCode.NO_ERROR),
        )
        awaitInstallReport()

        assertTrue(
            events.none { it.second == AssetPackStatus.COMPLETED },
            "COMPLETED was reported from the state update as well as from the " +
                "install, so the first-run queue steps twice: $events",
        )
    }

    /** Blocks until the install path, on the manager's own thread, has reported. */
    private fun awaitInstallReport() {
        val deadline = System.currentTimeMillis() + 5_000
        while (events.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        check(events.isNotEmpty()) {
            "the install reported nothing within 5s, so this test would pass by " +
                "looking before the report it exists to look after"
        }
    }

    @Test
    fun `a pack still downloading reports progress with no reason`() {
        // The other half of the routing change. Progress and success have no
        // reason to give and must keep flowing through the unchanged path.
        playListener().onStateUpdate(
            state(AssetPackStatus.DOWNLOADING, AssetPackErrorCode.NO_ERROR),
        )

        assertEquals(1, events.size)
        assertEquals(AssetPackStatus.DOWNLOADING, events[0].second)
        assertNull(events[0].third, "a download in progress has not failed")
    }
}
