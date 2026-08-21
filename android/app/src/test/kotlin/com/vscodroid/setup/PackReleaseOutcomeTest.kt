package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.android.play.core.assetpacks.AssetPackLocation
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
