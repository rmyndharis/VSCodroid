package com.vscodroid.setup

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two installs of one pack, overlapping, and what the second one is allowed to do.
 *
 * `installFromDirectory` copies into `filesDir/usr`, which every toolchain and the
 * base install share. Nothing serialises two of those against each other: each
 * call site builds its own [ToolchainManager] and `ioExecutor` is an instance
 * field, and `ToolchainActivity` declares no `configChanges`, so rotating the
 * screen mid-install recreates it with a second manager whose card offers Install
 * again for a pack that is already being installed.
 *
 * The bytes are identical on both sides, so the overlap does not corrupt the tree.
 * What it costs is the space pre-flight: neither install reserves for the other, so
 * a device between one install's reservation and two installs' real peak passes
 * both checks and then runs out of room partway through the second copy, leaving a
 * truncated tree under a record the first install has already written as installed.
 * The repair pass at launch does not re-examine a pack the record calls installed.
 *
 * The sequencing here is real rather than simulated: the log line immediately
 * before the copy is stubbed to hold the first install there, so the second one
 * runs while the first genuinely holds the pack and has not yet written a byte.
 */
class ToolchainInstallClaimTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packManager: AssetPackManager

    /** Set by whichever thread reaches the copy, so it is counted atomically. */
    private val entered = AtomicInteger(0)

    /** Counts down when an install has taken the pack and is held before its copy. */
    private val firstIsHolding = CountDownLatch(1)

    /** Releases the held install once the second one has had its turn. */
    private val letFirstProceed = CountDownLatch(1)

    private val pack = "toolchain_java"

    /**
     * The install parked inside the guarded region, held so [tearDown] can wait
     * for it to leave rather than only waking it.
     */
    private var heldInstall: Thread? = null

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // The last statement before the tree is copied, and the only point at
        // which an install both holds the pack and has changed nothing yet.
        // Stubbed after the general Logger.i above so this narrower one wins.
        every { Logger.i(any(), match { it.startsWith("Installing toolchain:") }) } answers {
            if (entered.incrementAndGet() == 1) {
                firstIsHolding.countDown()
                // Only the first entrant waits. An install that reaches here while
                // the claim is meant to have refused it must run to completion
                // rather than deadlock, or removing the claim would hang this test
                // instead of failing it.
                letFirstProceed.await(10, TimeUnit.SECONDS)
            }
        }

        packManager = mockk(relaxed = true)
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() {
        // Two steps, both before unmockking, and neither does the other's job.
        //
        // Released first, so a failed assertion cannot leave a thread parked on
        // the latch holding a claim that outlives this test: the set is process
        // wide, and a claim never released refuses every later install of that
        // pack for the life of the JVM.
        letFirstProceed.countDown()
        // Then waited out, because the claim comes back when that thread returns
        // from `installFromDirectory`, not when it wakes. Until then the pack is
        // still held, and the next test to touch it, in this class or any other,
        // is declined by a claim whose owner has already finished: the install
        // reports UNKNOWN, `downloadViaHttp` returns before it downloads
        // anything, and both fail on assertions that name no cause. Measured with
        // a copy of a few seconds, which is a short one for a toolchain tree: the
        // next class began within milliseconds of the failure here and its first
        // test failed with "the HTTP task never reported an outcome".
        //
        // Joining before `unmockkAll` also leaves Logger and the pack manager
        // stubbed for the rest of that install rather than pulling them out from
        // under it midway.
        heldInstall?.join(TimeUnit.SECONDS.toMillis(10))
        val stillHolding = heldInstall?.isAlive == true
        heldInstall = null
        unmockkAll()
        assertFalse(
            stillHolding,
            "an install is still inside the guarded region and still holds $pack, " +
                "so later installs of that pack in this JVM will be declined",
        )
    }

    /** A pack whose `usr/` tree has one file, so a copy leaves a trace. */
    private fun packDirectory(): File {
        val dir = File(filesDir, "delivered-$pack").apply { mkdirs() }
        File(dir, "$pack.json").writeText("""{"name":"java","installRoot":"usr/opt/java"}""")
        File(dir, "usr/opt/java/bin").mkdirs()
        File(dir, "usr/opt/java/bin/java").writeText("payload")
        return dir
    }

    /** Where [packDirectory]'s one file lands, and the evidence a copy ran. */
    private fun copiedFile() = File(filesDir, "usr/opt/java/bin/java")

    private fun manager(events: MutableList<Int>) = ToolchainManager(context).apply {
        onStateChange = { _, status, _, _ -> events.add(status) }
    }

    /**
     * Starts the install that will be held inside the guarded region, recording
     * it so [tearDown] can wait for the claim to be given back. Every install
     * that can still be inside that region when a test ends has to start here.
     */
    private fun startHeldInstall(dir: File, events: MutableList<Int>): Thread =
        Thread({ installFromDirectory(manager(events), dir) }, "first-install")
            .also {
                heldInstall = it
                it.start()
            }

    /** Private, and the point at which both delivery routes converge. */
    private fun installFromDirectory(m: ToolchainManager, dir: File) =
        ToolchainManager::class.java
            .getDeclaredMethod("installFromDirectory", String::class.java, File::class.java)
            .apply { isAccessible = true }
            .invoke(m, pack, dir)

    /** Private; `uninstall` hands this to an executor and swallows nothing else. */
    private fun uninstallSync(m: ToolchainManager, name: String) =
        ToolchainManager::class.java
            .getDeclaredMethod("uninstallSync", String::class.java)
            .apply { isAccessible = true }
            .invoke(m, name)

    /** Private; the launch repair is its only caller in production. */
    private fun reclaimInterruptedInstalls(m: ToolchainManager) =
        ToolchainManager::class.java
            .getDeclaredMethod("reclaimInterruptedInstallsSync")
            .apply { isAccessible = true }
            .invoke(m)

    /** Where an install notes the tree it is about to write, before it writes it. */
    private fun marker() = File(filesDir, "home/.vscodroid/toolchain-installing/$pack.json")

    /**
     * An install writes down where it is copying BEFORE it copies.
     *
     * Any other ordering makes the marker useless: written after the copy it says
     * nothing about the window the copy occupies, and written after the record it
     * says nothing the record does not already say. The park point here is the log
     * line immediately before the copy, so this is taken at the one moment an
     * install holds the pack and has written nothing.
     */
    @Test
    fun `an install writes down where it is copying before it copies`() {
        val dir = packDirectory()
        val events = Collections.synchronizedList(mutableListOf<Int>())

        val first = startHeldInstall(dir, events)
        assertTrue(
            firstIsHolding.await(10, TimeUnit.SECONDS),
            "the install never reached the copy, so there was nothing to observe",
        )

        assertTrue(
            marker().exists(),
            "the install is about to copy and nothing on disk names the tree it will " +
                "fill, so a kill here strands it for good",
        )
        val manifest = JSONObject(marker().readText())
        assertEquals("java", manifest.optString("name"))
        assertEquals("usr/opt/java", manifest.optString("installRoot"))
        assertFalse(
            copiedFile().exists(),
            "the copy had already started, so this proves nothing about the ordering",
        )

        letFirstProceed.countDown()
        first.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse(first.isAlive, "the install never finished")
    }

    /**
     * And the reclaim leaves that install alone.
     *
     * `SplashActivity` fires the launch repair on one manager's executor while the
     * delivered-pack reconcile runs on another's, so the pass genuinely can meet a
     * copy in progress. What stops it deleting those files is the same
     * `installsInFlight` claim an install takes: the marker is written after the
     * claim, so a marker without a claim is provably a dead install and a marker
     * with one is not.
     *
     * The tree here is planted rather than copied, and no record names it, so the
     * reclaim would take it on the spot if the claim were dropped from the pass.
     */
    @Test
    fun `a pack an install is copying right now is left alone by the reclaim`() {
        val dir = packDirectory()
        val earlier = copiedFile().apply {
            parentFile?.mkdirs()
            writeText("bytes this install has already written")
        }
        val events = Collections.synchronizedList(mutableListOf<Int>())

        val first = startHeldInstall(dir, events)
        assertTrue(
            firstIsHolding.await(10, TimeUnit.SECONDS),
            "the install never reached the copy, so nothing was being raced",
        )

        reclaimInterruptedInstalls(manager(Collections.synchronizedList(mutableListOf())))

        assertTrue(
            earlier.exists(),
            "the reclaim deleted the tree an install is copying into right now",
        )
        assertTrue(
            marker().exists(),
            "the reclaim took the marker of a live install, so a kill after this point " +
                "strands the tree with nothing left to name it",
        )

        letFirstProceed.countDown()
        first.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse(first.isAlive, "the install never finished")
        assertEquals(
            listOf(AssetPackStatus.COMPLETED), events,
            "the install that held the pack did not finish it",
        )
    }

    /**
     * A removal is the same file work in the opposite direction, and nothing put
     * the two in order.
     *
     * The install claims the pack for the length of its copy; the uninstall
     * claimed nothing and took `stateLock` for the record alone, which the copy
     * does not hold. Each construction site has its own single-thread executor,
     * so the pair is reachable across two managers: a removal from the bridge or
     * from the Toolchains screen against the copy the first-run queue is
     * performing. What it costs is the files being deleted while they are
     * written, after which the install writes a record calling the result
     * complete.
     *
     * The record names the toolchain here because that is what makes Remove
     * reachable at all: a card offers it only for an install the record knows.
     */
    @Test
    fun `a removal is declined while an install of the same pack is copying`() {
        val dir = packDirectory()
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(
            """[{"name":"java","installRoot":"usr/opt/java"}]"""
        )
        val earlier = copiedFile().apply {
            parentFile?.mkdirs()
            writeText("payload")
        }
        val installEvents = Collections.synchronizedList(mutableListOf<Int>())
        val removeEvents = Collections.synchronizedList(mutableListOf<Int>())

        val first = startHeldInstall(dir, installEvents)
        assertTrue(
            firstIsHolding.await(10, TimeUnit.SECONDS),
            "the install never reached the copy, so nothing was being raced",
        )

        uninstallSync(manager(removeEvents), "java")

        assertTrue(
            earlier.exists(),
            "the removal deleted the tree an install is copying into right now",
        )
        assertTrue(
            File(filesDir, "home/.vscodroid/toolchains.json").readText().contains("\"java\""),
            "the removal took the record out from under the install that is still running",
        )
        assertEquals(
            listOf(AssetPackStatus.UNKNOWN), removeEvents,
            "a declined removal has to say something: the card has taken its Remove " +
                "button away and needs an answer to put it back",
        )

        letFirstProceed.countDown()
        first.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse(first.isAlive, "the install never finished")
    }

    @Test
    fun `a second install of a pack already being installed copies nothing`() {
        val dir = packDirectory()
        val firstEvents = Collections.synchronizedList(mutableListOf<Int>())
        val secondEvents = Collections.synchronizedList(mutableListOf<Int>())

        val first = startHeldInstall(dir, firstEvents)
        assertTrue(
            firstIsHolding.await(10, TimeUnit.SECONDS),
            "the first install never reached the copy, so nothing was being raced",
        )

        installFromDirectory(manager(secondEvents), dir)

        assertEquals(
            1, entered.get(),
            "the second install copied the tree on top of the first one's copy",
        )
        assertFalse(
            copiedFile().exists(),
            "the first install is still held before its copy, so this file can only " +
                "have been written by the install that was supposed to decline",
        )
        assertEquals(
            listOf(AssetPackStatus.UNKNOWN), secondEvents,
            "an install that copied nothing has to say so, and must not say COMPLETED",
        )
        // Named rather than left to the assertion above, because the two statuses are
        // interchangeable to everything that only asks whether the pack is finished,
        // and differ to the one reader that matters: NOT_INSTALLED is what a completed
        // uninstall reports, and ToolchainCardState drops the pack from its installed
        // set on it. A decline is not an uninstall.
        assertFalse(
            secondEvents.contains(AssetPackStatus.NOT_INSTALLED),
            "declining an install must not report the status that means it was removed",
        )

        letFirstProceed.countDown()
        first.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse(first.isAlive, "the first install never finished")
        assertEquals(
            listOf(AssetPackStatus.COMPLETED), firstEvents,
            "the install that held the pack did not finish it",
        )
        assertTrue(copiedFile().exists(), "the tree was never copied at all")
    }

    @Test
    fun `a pack installs normally once the install holding it has finished`() {
        // The claim is only correct if it is given back. Released on every exit
        // rather than on the one the happy path takes, or the first install of a
        // pack would be the last one this process ever performs.
        val dir = packDirectory()
        val events = Collections.synchronizedList(mutableListOf<Int>())

        val first = startHeldInstall(dir, events)
        assertTrue(firstIsHolding.await(10, TimeUnit.SECONDS), "the first install never started")
        letFirstProceed.countDown()
        first.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse(first.isAlive, "the first install never finished")

        copiedFile().delete()
        events.clear()

        installFromDirectory(manager(events), dir)

        assertEquals(
            2, entered.get(),
            "the later install was refused, so the claim was never given back",
        )
        assertEquals(listOf(AssetPackStatus.COMPLETED), events)
        assertTrue(copiedFile().exists(), "the later install reported success and copied nothing")
    }
}
