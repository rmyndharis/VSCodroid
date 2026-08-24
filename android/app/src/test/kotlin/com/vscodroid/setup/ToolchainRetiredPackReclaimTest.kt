package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
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
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A Play delivery of a toolchain this build no longer offers is handed back.
 *
 * Two sweeps exist for a retired toolchain and neither reached this. The launch
 * sweep reads the install record and deletes the copy under `usr/` without
 * asking Play anything, and the reconcile pass asked Play only about the packs
 * the registry still lists. A delivery that a process death left in
 * `filesDir/assetpacks`, between the copy and the removal that should have
 * followed it, therefore outlived the withdrawal on every later launch: 179 MB
 * for Go, counted by no space pre-flight and offered for removal by no screen.
 *
 * `AssetPackManager` is mocked because it has no implementation off-device.
 * What is under test is which pack names the real reconcile pass asks about
 * and what it does with the answer.
 */
class ToolchainRetiredPackReclaimTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packManager: AssetPackManager

    /** Every pack name the registry has withdrawn, in the form Play knows. */
    private val retiredPacks = RETIRED_TOOLCHAINS.keys.map { "toolchain_$it" }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        val removal = mockk<Task<Void>>()
        every { removal.addOnSuccessListener(any()) } returns removal
        every { removal.addOnFailureListener(any()) } returns removal

        packManager = mockk(relaxed = true)
        every { packManager.removePack(any()) } returns removal
        // Stated rather than left to the relaxed mock, which answers a location
        // for every pack ever asked about.
        every { packManager.getPackLocation(any()) } returns null
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        val packageManager = mockk<PackageManager>(relaxed = true)
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

    /** Makes Play answer that it is still holding a delivered [pack]. */
    private fun playHolds(pack: String) {
        val location = mockk<AssetPackLocation>(relaxed = true)
        every { location.assetsPath() } returns File(filesDir, "delivered/$pack").absolutePath
        every { packManager.getPackLocation(pack) } returns location
    }

    @Test
    fun `a delivered pack for a retired toolchain is handed back`() {
        assertTrue(retiredPacks.isNotEmpty(), "nothing is retired, so this test proves nothing")
        retiredPacks.forEach(::playHolds)

        ToolchainManager(context).reconcileDeliveredPacks()

        // Timed rather than immediate: reconcile runs on the manager's io executor.
        for (pack in retiredPacks) {
            verify(timeout = 10_000, exactly = 1) { packManager.removePack(pack) }
        }
    }

    /**
     * The control, and the half that says the reclaim is driven by Play's answer:
     * a retired pack Play is not holding is asked about and left alone.
     */
    @Test
    fun `a retired pack Play holds nothing for is left alone`() {
        ToolchainManager(context).reconcileDeliveredPacks()

        for (pack in retiredPacks) {
            verify(timeout = 10_000) { packManager.getPackLocation(pack) }
        }
        verify(exactly = 0) { packManager.removePack(any()) }
    }

    /**
     * Released, never installed. The offered packs go through
     * `installDeliveredPack` when Play holds one nobody was listening for; a
     * retired one has no registry entry to install from, and copying it into
     * `usr/` would put back exactly what the launch sweep removes.
     */
    @Test
    fun `a delivered retired pack is released rather than installed`() {
        val pack = retiredPacks.first()
        val name = pack.removePrefix("toolchain_")
        playHolds(pack)
        val dir = File(filesDir, "delivered/$pack").apply { mkdirs() }
        File(dir, "$pack.json").writeText("""{"name":"$name","installRoot":"usr/opt/$name"}""")
        File(dir, "usr/opt/$name/bin").mkdirs()
        File(dir, "usr/opt/$name/bin/$name").writeText("payload")

        ToolchainManager(context).reconcileDeliveredPacks()

        verify(timeout = 10_000, exactly = 1) { packManager.removePack(pack) }
        assertFalse(
            File(filesDir, "usr/opt/$name").exists(),
            "a withdrawn toolchain was installed from its leftover delivery",
        )
        val record = File(filesDir, "home/.vscodroid/toolchains.json")
        assertFalse(
            record.isFile && record.readText().contains("\"$name\""),
            "a withdrawn toolchain was recorded as installed",
        )
    }
}
