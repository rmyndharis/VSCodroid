package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import android.os.StatFs
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.android.play.core.assetpacks.AssetPackLocation
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What happens to Play's copy of a pack after the install has taken what it needs.
 *
 * Play delivers into `filesDir/assetpacks` and keeps the tree until it is asked to
 * drop it, and the tree is a whole toolchain: 155 MB for Java 17 today. Two things
 * were wrong with the asking.
 *
 * `removePack` posts the delete and returns a `Task`; it does not perform it. The
 * caller attached nothing to that task and logged "freed duplicate storage" on the
 * return, so a delete that failed, or that the process outlived, was recorded as a
 * success. Neither space pre-flight notices the difference, because both measure
 * free bytes rather than what Play is holding.
 *
 * And nothing could reclaim the leftovers afterwards. `reconcileDeliveredPacks`
 * asked the record before it asked Play, so a toolchain already named in
 * `toolchains.json` was skipped without Play ever being asked whether it was still
 * holding a delivery for it. The orphan was therefore permanent for the life of
 * the install.
 *
 * `AssetPackManager` and the `Task` are mocked because neither has an
 * implementation off-device. What is not mocked is the decision under test: the
 * real `reconcileDeliveredPacks` reads the real `toolchains.json` and chooses.
 */
class PackReleaseOutcomeTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var packManager: AssetPackManager

    /** What `removePack` hands back, so the listeners can be driven by hand. */
    private lateinit var removal: Task<Void>

    private val onSuccess = slot<OnSuccessListener<in Void>>()
    private val onFailure = slot<OnFailureListener>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        removal = mockk()
        every { removal.addOnSuccessListener(capture(onSuccess)) } returns removal
        every { removal.addOnFailureListener(capture(onFailure)) } returns removal

        packManager = mockk(relaxed = true)
        every { packManager.removePack(any()) } returns removal
        // Stated rather than left to the relaxed mock, which answers a location for
        // every pack ever asked about. Each case names the one pack Play is holding;
        // without this default the others are deliveries too, and reconcile spends
        // the case installing them.
        every { packManager.getPackLocation(any()) } returns null
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        packageManager = mockk(relaxed = true)
        val source = mockk<InstallSourceInfo>()
        // A Play install, or reconcile returns before it asks Play anything.
        every { source.installingPackageName } returns "com.android.vending"
        every { packageManager.getInstallSourceInfo(any()) } returns source

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.vscodroid"

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** Records [names] as installed, which is what `getInstalledToolchains` reads. */
    private fun recordInstalled(vararg names: String) {
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(
            names.joinToString(",", "[", "]") { """{"name":"$it","version":"1"}""" }
        )
    }

    /** Makes Play answer that it is still holding a delivered [pack]. */
    private fun playHolds(pack: String) {
        val location = mockk<AssetPackLocation>(relaxed = true)
        every { location.assetsPath() } returns File(filesDir, "delivered/$pack").absolutePath
        every { packManager.getPackLocation(pack) } returns location
    }

    /**
     * Writes a real delivered tree for [pack] where [playHolds] says one is, with
     * a manifest good enough for the install to run through to its record write.
     */
    private fun deliver(pack: String, name: String) {
        val dir = File(filesDir, "delivered/$pack").apply { mkdirs() }
        File(dir, "$pack.json").writeText("""{"name":"$name","installRoot":"usr/opt/$name"}""")
        File(dir, "usr/opt/$name/bin").mkdirs()
        File(dir, "usr/opt/$name/bin/$name").writeText("payload")
    }

    /** Room to spare, so the space pre-flight passes and the copy is reached. */
    private fun plentyOfRoom() {
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns 8L * 1024 * 1024 * 1024
    }

    private val outcomes: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val settled = CountDownLatch(1)

    /** A manager whose reports this test can wait on. */
    private fun watchedManager() = ToolchainManager(context).apply {
        onStateChange = { _, status, _, _ ->
            outcomes.add(status)
            if (status == AssetPackStatus.COMPLETED || status == AssetPackStatus.FAILED) {
                settled.countDown()
            }
        }
    }

    /**
     * Occupies the temporary path `writeAtomically` derives from `toolchains.json`,
     * which is how the rest of this package arranges a record write that fails.
     * Non-empty, so the cleanup `delete()` cannot quietly reclaim it.
     */
    private fun blockTheRecordWrite() {
        val blocker = File(filesDir, "home/.vscodroid/toolchains.json.tmp~")
        assertTrue(blocker.mkdirs(), "could not stage the blocked temp path")
        File(blocker, "occupied").writeText("x")
    }

    /**
     * A pack Play still holds for a toolchain that is already installed is storage
     * nothing will ever read again.
     *
     * It gets there whenever the release did not complete: the delete is
     * asynchronous, and the process can go away between the copy and the delete.
     * Before this, the record was consulted first and such a pack was skipped on
     * every launch for as long as the toolchain stayed installed.
     */
    @Test
    fun `a delivered pack for an installed toolchain is reclaimed`() {
        recordInstalled("java")
        playHolds("toolchain_java")

        ToolchainManager(context).reconcileDeliveredPacks()

        // Timed rather than immediate: reconcile runs on the manager's io executor.
        verify(timeout = 10_000, exactly = 1) { packManager.removePack("toolchain_java") }
    }

    /**
     * The control for the case above, and it is the half that says the reclaim is
     * driven by Play's answer rather than by the record.
     *
     * A pack Play is not holding must not be removed again. Asking on every launch
     * for every installed toolchain would be a call per toolchain per launch with
     * nothing to free, and it would hide a wrong reading of `getPackLocation`
     * behind a delete that always succeeds because there is nothing to delete.
     */
    @Test
    fun `an installed toolchain Play holds nothing for is left alone`() {
        // Nothing calls playHolds, so Play is holding no delivery at all.
        recordInstalled("java", "ruby")

        ToolchainManager(context).reconcileDeliveredPacks()

        // Ordered after a call that reconcile makes unconditionally on the same
        // executor, so this asserts on a pass that has finished rather than on one
        // that has not started.
        verify(timeout = 10_000) { packManager.getPackLocation("toolchain_java") }
        verify(exactly = 0) { packManager.removePack(any()) }
    }

    /**
     * A delivery whose install could not write the record is KEPT, so the next
     * launch can finish it.
     *
     * `toolchains.json` is four lines written after the whole tree has already
     * been copied, which makes it exactly the write a device that has just filled
     * up fails. The user is told to free some space and try again -- the same
     * sentence the space refusal shows -- and the space refusal keeps Play's copy
     * for precisely that reason: the next launch reconciles and finishes the
     * install with no download at all. This exit released it instead, so the
     * repair the class advertises had nothing to work from and the user paid for
     * the whole delivery again.
     *
     * Returning true from the record-write failure turns this red.
     */
    @Test
    fun `a delivery whose record cannot be written is kept for the next launch`() {
        plentyOfRoom()
        playHolds("toolchain_java")
        deliver("toolchain_java", "java")
        blockTheRecordWrite()

        watchedManager().reconcileDeliveredPacks()

        assertTrue(settled.await(10, TimeUnit.SECONDS), "the install never reported an outcome")
        assertEquals(
            listOf(AssetPackStatus.FAILED), outcomes,
            "the install did not fail at the record write, so this proves nothing",
        )
        verify(exactly = 0) { packManager.removePack(any()) }
    }

    /**
     * The control: the same delivery, with the record write left alone, IS handed
     * back. Without it the case above would pass against a build that never
     * released anything.
     */
    @Test
    fun `a delivery whose install finishes is handed back`() {
        plentyOfRoom()
        playHolds("toolchain_java")
        deliver("toolchain_java", "java")

        watchedManager().reconcileDeliveredPacks()

        assertTrue(settled.await(10, TimeUnit.SECONDS), "the install never reported an outcome")
        assertEquals(listOf(AssetPackStatus.COMPLETED), outcomes, "the install did not finish")
        verify(timeout = 10_000, exactly = 1) { packManager.removePack("toolchain_java") }
    }

    /**
     * One pack that throws does not end the pass for the packs behind it.
     *
     * The catch used to wrap the whole loop rather than an iteration, and
     * `installDeliveredPack` throws readily: `JSONObject` on a malformed manifest,
     * `copyDirectoryTree` on a directory it cannot list or a disk that fills up.
     * Ruby is first in the registry, so one throw on Ruby meant Java's delivery
     * was never examined at all -- neither installed if it was outstanding, nor
     * reclaimed if it was a whole toolchain of duplicate storage that no space
     * pre-flight counts. Every later launch repeated exactly that, so the reclaim
     * that could have made room for Ruby was the thing Ruby's failure prevented.
     *
     * Moving the catch back around the `forEach` turns this red.
     */
    @Test
    fun `a pack that throws does not stop the packs behind it`() {
        plentyOfRoom()
        // Ruby comes first in ToolchainRegistry.available, and its manifest is
        // not JSON, so JSONObject throws out of the middle of its install.
        playHolds("toolchain_ruby")
        File(filesDir, "delivered/toolchain_ruby").mkdirs()
        File(filesDir, "delivered/toolchain_ruby/toolchain_ruby.json").writeText("not json at all")
        // Java is behind it and is pure duplicate storage: already recorded as
        // installed, with a delivery nobody released.
        recordInstalled("java")
        playHolds("toolchain_java")

        ToolchainManager(context).reconcileDeliveredPacks()

        verify(timeout = 10_000, exactly = 1) { packManager.removePack("toolchain_java") }
    }

    /** A manager reporting into [sink], settling [latch] on a terminal status. */
    private fun managerReporting(latch: CountDownLatch, sink: MutableList<Int>) =
        ToolchainManager(context).apply {
            onStateChange = { _, status, _, _ ->
                sink.add(status)
                if (status == AssetPackStatus.COMPLETED || status == AssetPackStatus.FAILED) {
                    latch.countDown()
                }
            }
        }

    /** Exactly [bytes] free, so a gate's arithmetic decides the outcome. */
    private fun room(bytes: Long) {
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns bytes
    }

    /** Eight readable megabytes inside the delivered tree, so the credit is legible. */
    private fun fattenDelivery(pack: String, name: String) {
        val lib = File(filesDir, "delivered/$pack/usr/opt/$name/lib").apply { mkdirs() }
        File(lib, "rt").writeBytes(ByteArray(8_000_000))
    }

    /** 8,000,007 bytes: the fattened file plus the "payload" [deliver] writes. */
    private val orphanBytes = 8_000_007L

    /**
     * The orphan is placed on disk rather than made by a failing install, and the
     * difference is the whole point of the case.
     *
     * A record write that fails now reclaims what it copied, so that exit no longer
     * leaves one. Two sources still do, and neither has a code path that can clear
     * them: a process killed partway through the copy, which nothing persists intent
     * before, and a device already carrying an orphan from a build before the reclaim
     * existed. For both, the tree is on disk and no record names it, which is exactly
     * what is built here. Driving a failing install instead would measure the reclaim
     * and call it the credit.
     */
    @Test
    fun `an orphaned tree the copy writes over is credited to the space gate`() {
        playHolds("toolchain_java")
        deliver("toolchain_java", "java")
        fattenDelivery("toolchain_java", "java")

        val orphan = File(filesDir, "usr/opt/java/lib").apply { mkdirs() }
        File(orphan, "rt").writeBytes(ByteArray(8_000_000))
        File(File(filesDir, "usr/opt/java"), "payload").writeBytes(ByteArray(7))
        assertEquals(
            orphanBytes,
            com.vscodroid.util.StorageManager.dirSize(File(filesDir, "usr/opt/java")),
            "the orphan is not the size this case's arithmetic assumes",
        )

        // Strictly between the credited demand (206,000,000 - 8,000,007) and the
        // uncredited one (206,000,000).
        room(200_000_000L)
        val secondOut = Collections.synchronizedList(mutableListOf<Int>())
        val second = CountDownLatch(1)
        managerReporting(second, secondOut).reconcileDeliveredPacks()
        assertTrue(second.await(10, TimeUnit.SECONDS), "the second pass never reported an outcome")
        assertEquals(
            listOf(AssetPackStatus.COMPLETED), secondOut,
            "the gate charged for bytes the copy writes over",
        )
        verify(timeout = 10_000, exactly = 1) { packManager.removePack("toolchain_java") }
    }

    /** The control: the same tight figure with nothing on disk to credit must refuse. */
    @Test
    fun `the same room with no tree on disk is still refused`() {
        playHolds("toolchain_java")
        deliver("toolchain_java", "java")
        fattenDelivery("toolchain_java", "java")

        room(200_000_000L)
        val out = Collections.synchronizedList(mutableListOf<Int>())
        val settledHere = CountDownLatch(1)
        managerReporting(settledHere, out).reconcileDeliveredPacks()
        assertTrue(settledHere.await(10, TimeUnit.SECONDS), "the pass never reported an outcome")
        assertEquals(
            listOf(AssetPackStatus.FAILED), out,
            "the gate passed with nothing on disk to credit, so it was weakened rather than corrected",
        )
    }

    /**
     * The outcome of the delete reaches a channel.
     *
     * `removePack` returns before the delete is attempted, so the success line the
     * caller used to log unconditionally was a claim about something that had not
     * happened yet. The failure branch is the one that matters: it is the only
     * record that a toolchain's worth of storage is still out there, and it now
     * says so along with the fact that the next launch will reclaim it.
     */
    @Test
    fun `a delete Play refuses is reported rather than logged as success`() {
        recordInstalled("java")
        playHolds("toolchain_java")

        ToolchainManager(context).reconcileDeliveredPacks()

        // Waited on here rather than on `removePack`, which is the call the other
        // cases wait on and the wrong one for this: the chain runs on the io thread,
        // so `removePack` is already recorded while the listeners after it are not,
        // and a wait that ends there reads a slot nothing has filled. That is a race
        // this suite loses only under load, which is the worst way to find it.
        verify(timeout = 10_000) { removal.addOnSuccessListener(any()) }
        verify(timeout = 10_000) { removal.addOnFailureListener(any()) }

        assertTrue(onSuccess.isCaptured, "nothing is listening for the delete succeeding")
        assertTrue(onFailure.isCaptured, "nothing is listening for the delete failing")

        val why = IllegalStateException("Play said no")
        onFailure.captured.onFailure(why)

        verify {
            Logger.w(
                any(),
                match<String> { "toolchain_java" in it && "stays on disk" in it },
                why,
            )
        }
    }
}
