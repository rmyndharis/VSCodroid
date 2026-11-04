package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import android.os.StatFs
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class ToolchainReinstallRecoveryTest {
    @TempDir
    lateinit var filesDir: File

    private val managers = mutableListOf<ToolchainManager>()
    private lateinit var context: Context
    private lateinit var packManager: AssetPackManager

    @BeforeEach
    fun setUp() {
        mockkStatic(Files::class)
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns Long.MAX_VALUE
        packManager = mockk(relaxed = true)
        every { packManager.getPackLocation(any()) } returns null
        every { packManager.removePack(any()) } answers { completedPackRemoval() }
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager
        val packages = mockk<PackageManager>(relaxed = true)
        every { packages.getInstallSourceInfo(any()) } returns mockk<InstallSourceInfo> {
            every { installingPackageName } returns "com.android.vending"
        }
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"
        every { context.packageManager } returns packages
        File(filesDir, "home/.vscodroid").mkdirs()
    }

    private fun manager() = ToolchainManager(context).also { managers.add(it) }

    private fun complete(listener: AssetPackStateUpdateListener) {
        listener.onStateUpdate(mockk<AssetPackState> {
            every { name() } returns "toolchain_java"
            every { status() } returns AssetPackStatus.COMPLETED
            every { totalBytesToDownload() } returns 1L
            every { bytesDownloaded() } returns 1L
            every { errorCode() } returns 0
        })
    }

    @AfterEach
    fun tearDown() {
        for (manager in managers) {
            val executor = ToolchainManager::class.java.getDeclaredField("ioExecutor")
                .apply { isAccessible = true }.get(manager) as ExecutorService
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "the install outlived its fixture")
        }
        unmockkAll()
    }

    @Test
    fun `a failed replacement leaves the previous file bytes intact`() {
        val source = File(filesDir, "source").apply { writeText("replacement") }
        val dest = File(filesDir, "dest").apply { writeText("previous") }
        assertTrue(source.setReadable(false, false))
        assertFalse(source.canRead(), "the fixture needs an unreadable source file")
        try {
            assertThrows(IOException::class.java) { copyDirectoryTree(source, dest) }

            assertEquals("previous", dest.takeIf { it.exists() }?.readText())
        } finally {
            source.setReadable(true, true)
        }
    }

    @Test
    fun `a failed Play reinstall retains the source and finishes on the next launch`() {
        val root = "usr/lib/jvm/test"
        val state = File(filesDir, "home/.vscodroid/toolchains.json").apply {
            parentFile?.mkdirs()
            writeText("""[{"name":"java","displayName":"Java 21","installRoot":"$root","execBitsChecked":true}]""")
        }
        val delivery = File(filesDir, "delivery").apply { mkdirs() }
        File(delivery, "toolchain_java.json").writeText(
            """{"name":"java","displayName":"Java 21","installRoot":"$root"}""",
        )
        val payload = File(delivery, root).apply { mkdirs() }
        File(payload, "one").writeText("new one")
        File(payload, "two").writeText("new two")
        val children = requireNotNull(payload.listFiles())
        assertEquals(2, children.size)
        val first = File(filesDir, "$root/${children.first().name}").apply {
            parentFile?.mkdirs()
            writeText("previous first")
        }
        val blocked = File(filesDir, "$root/${children.last().name}").apply { mkdirs() }
        File(blocked, "hold").writeText("blocks file replacement")
        var playHasDelivery = true
        every { packManager.getPackLocation("toolchain_java") } answers {
            if (!playHasDelivery) null else mockk {
                every { assetsPath() } returns delivery.path
            }
        }
        every { packManager.removePack("toolchain_java") } answers {
            playHasDelivery = false
            delivery.deleteRecursively()
            completedPackRemoval()
        }
        val listener = slot<AssetPackStateUpdateListener>()
        every { packManager.registerListener(capture(listener)) } just Runs
        val failed = CountDownLatch(1)
        val install = manager()
        install.onStateChange = { _, status, _, why, _ ->
            if (status == AssetPackStatus.FAILED) {
                assertEquals(ToolchainFailure.STORAGE, why)
                failed.countDown()
            }
        }
        install.install("toolchain_java")
        complete(listener.captured)
        assertTrue(failed.await(5, TimeUnit.SECONDS), "the real copy never reported its failure")
        assertTrue(state.isFile, "the prior ownership record must remain recoverable")
        assertEquals(children.first().readText(), first.readText(), "the fixture never reached a partial reinstall")
        val marker = File(filesDir, "home/.vscodroid/toolchain-installing/toolchain_java.json")
        assertTrue(marker.isFile, "the incomplete reinstall lost the witness that it needs completion")
        assertTrue(playHasDelivery && delivery.isDirectory, "the failed install discarded its repair source")

        blocked.deleteRecursively()
        val nextLaunch = manager()
        nextLaunch.repairInstalledToolchains()
        awaitLaunchPass(nextLaunch)
        nextLaunch.reconcileDeliveredPacks()
        awaitLaunchPass(nextLaunch)

        assertEquals("new one", File(filesDir, "$root/one").readText())
        assertEquals("new two", File(filesDir, "$root/two").readText())
        assertEquals(listOf("java"), nextLaunch.getInstalledToolchains())
        assertFalse(marker.exists(), "the completed reinstall kept its pending marker")
        assertFalse(playHasDelivery, "the completed reinstall kept duplicate Play storage")
    }

    @Test
    fun `a Play reinstall reserves room for its largest temporary replacement`() {
        val root = "usr/lib/jvm/test"
        val manifest = """{"name":"java","displayName":"Java 21","installRoot":"$root"}"""
        File(filesDir, "home/.vscodroid/toolchains.json").writeText("[$manifest]")
        val old = File(filesDir, "$root/modules").apply { parentFile?.mkdirs() }
        val installedBytes = ToolchainRegistry.find("toolchain_java")!!.estimatedSize
        RandomAccessFile(old, "rw").use { it.setLength(installedBytes) }
        val delivery = File(filesDir, "delivery").apply { mkdirs() }
        File(delivery, "toolchain_java.json").writeText(manifest)
        val incoming = File(delivery, "$root/modules").apply { parentFile?.mkdirs() }
        RandomAccessFile(incoming, "rw").use { it.setLength(SPACE_BUFFER + 1) }
        every { anyConstructed<StatFs>().availableBytes } returns SPACE_BUFFER
        every { packManager.getPackLocation("toolchain_java") } returns mockk {
            every { assetsPath() } returns delivery.path
        }
        val listener = slot<AssetPackStateUpdateListener>()
        every { packManager.registerListener(capture(listener)) } just Runs
        val settled = CountDownLatch(1)
        var result: Pair<Int, ToolchainFailure?>? = null
        val install = manager()
        install.onStateChange = { _, status, _, why, _ ->
            if (status == AssetPackStatus.FAILED || status == AssetPackStatus.COMPLETED) {
                result = status to why
                settled.countDown()
            }
        }
        install.install("toolchain_java")
        complete(listener.captured)
        assertTrue(settled.await(5, TimeUnit.SECONDS), "the preflight never reported an outcome")

        assertEquals(AssetPackStatus.FAILED to ToolchainFailure.STORAGE, result)
        assertEquals(installedBytes, old.length(), "the space refusal touched the installed payload")
        assertTrue(delivery.isDirectory, "a space refusal must retain the delivered source")
    }

    /**
     * Runs a Play reinstall of Java over an installed copy with [available] bytes
     * free, and says whether the space gate refused it. The delivered file is 30 MB
     * and replaces a file as large as the registry says the toolchain unpacks to.
     */
    private fun playGateRefused(available: Long): Boolean {
        val root = "usr/lib/jvm/test"
        val manifest = """{"name":"java","displayName":"Java 21","installRoot":"$root"}"""
        File(filesDir, "home/.vscodroid/toolchains.json").writeText("[$manifest]")
        val unpacked = ToolchainRegistry.find("toolchain_java")!!.estimatedSize
        RandomAccessFile(File(filesDir, "$root/modules").apply { parentFile?.mkdirs() }, "rw")
            .use { it.setLength(unpacked) }
        val delivery = File(filesDir, "delivery").apply { mkdirs() }
        File(delivery, "toolchain_java.json").writeText(manifest)
        val incoming = File(delivery, "$root/modules").apply { parentFile?.mkdirs() }
        RandomAccessFile(incoming, "rw").use { it.setLength(30_000_000L) }
        every { anyConstructed<StatFs>().availableBytes } returns available
        every { packManager.getPackLocation("toolchain_java") } returns mockk {
            every { assetsPath() } returns delivery.path
        }
        val listener = slot<AssetPackStateUpdateListener>()
        every { packManager.registerListener(capture(listener)) } just Runs
        val settled = CountDownLatch(1)
        val install = manager()
        install.onStateChange = { _, status, _, _, _ ->
            if (status == AssetPackStatus.FAILED || status == AssetPackStatus.COMPLETED) settled.countDown()
        }
        install.install("toolchain_java")
        complete(listener.captured)
        assertTrue(settled.await(10, TimeUnit.SECONDS), "the install never reported an outcome")
        return runCatching {
            verify { Logger.e(any(), match { it.startsWith("Not enough disk space for toolchain_java") }, any()) }
        }.isSuccess
    }

    /** The Play route asks for what [toolchainCopyBytes] computes, no more and no less. */
    private val playCopyFigure: Long
        get() = toolchainCopyBytes(
            ToolchainRegistry.find("toolchain_java")!!.estimatedSize,
            ToolchainCopySpace(30_000_000L, 30_000_000L),
        )

    @Test
    fun `a Play reinstall is admitted at exactly the copy figure`() {
        assertFalse(playGateRefused(playCopyFigure), "the gate asked for more than the copy needs")
    }

    @Test
    fun `a Play reinstall is refused one byte under the copy figure`() {
        assertTrue(playGateRefused(playCopyFigure - 1), "the gate admitted a copy that does not fit")
    }

    @Test
    fun `a marker for the committed install is cleared without removing its files`() {
        val manifest = """{"name":"java","installRoot":"usr/lib/jvm/test","installId":"complete"}"""
        File(filesDir, "home/.vscodroid/toolchains.json").writeText("[$manifest]")
        val payload = File(filesDir, "usr/lib/jvm/test/payload").apply {
            parentFile?.mkdirs()
            writeText("complete payload")
        }
        val marker = File(filesDir, "home/.vscodroid/toolchain-installing/toolchain_java.json").apply {
            parentFile?.mkdirs()
            writeText(manifest)
        }

        val launch = manager()
        launch.repairInstalledToolchains()
        awaitLaunchPass(launch)

        assertFalse(marker.exists(), "a committed install was left marked pending")
        assertEquals("complete payload", payload.readText())
        assertEquals(listOf("java"), launch.getInstalledToolchains())
    }

    @Test
    fun `a legacy marker without completion identity keeps its retry state`() {
        val manifest = """{"name":"java","installRoot":"usr/lib/jvm/test"}"""
        File(filesDir, "home/.vscodroid/toolchains.json").writeText("[$manifest]")
        val payload = File(filesDir, "usr/lib/jvm/test/payload").apply {
            parentFile?.mkdirs()
            writeText("retained payload")
        }
        val marker = File(filesDir, "home/.vscodroid/toolchain-installing/toolchain_java.json").apply {
            parentFile?.mkdirs()
            writeText(manifest)
        }
        val launch = manager()

        repeat(2) {
            launch.repairInstalledToolchains()
            awaitLaunchPass(launch)
        }

        assertTrue(marker.isFile, "an unproven legacy completion was accepted as current")
        assertEquals(listOf("toolchain_java"), launch.getIncompleteToolchains())
        assertEquals(listOf("java"), launch.getInstalledToolchains(), "ownership API must retain its record")
        assertEquals("retained payload", payload.readText())

        launch.uninstall("java")
        awaitLaunchPass(launch)

        assertFalse(marker.exists(), "an explicit removal retained a retry that could reinstall the pack")
        assertTrue(launch.getIncompleteToolchains().isEmpty())
        assertTrue(launch.getInstalledToolchains().isEmpty())
    }

    @Test
    fun `a failed pending marker write leaves the installed bytes unchanged`() {
        val root = "usr/lib/jvm/test"
        val manifest = """{"name":"java","installRoot":"$root"}"""
        File(filesDir, "home/.vscodroid/toolchains.json").writeText("[$manifest]")
        val old = File(filesDir, "$root/payload").apply {
            parentFile?.mkdirs()
            writeText("previous payload")
        }
        val delivery = File(filesDir, "delivery").apply { mkdirs() }
        File(delivery, "toolchain_java.json").writeText(manifest)
        File(delivery, root).mkdirs()
        File(delivery, "$root/payload").writeText("new payload")
        blockAtomicWritesTo(File(filesDir, "home/.vscodroid/toolchain-installing/toolchain_java.json"))
        every { packManager.getPackLocation("toolchain_java") } returns mockk {
            every { assetsPath() } returns delivery.path
        }
        val listener = slot<AssetPackStateUpdateListener>()
        every { packManager.registerListener(capture(listener)) } just Runs
        val settled = CountDownLatch(1)
        var result: Pair<Int, ToolchainFailure?>? = null
        val install = manager()
        install.onStateChange = { _, status, _, why, _ ->
            result = status to why
            settled.countDown()
        }

        install.install("toolchain_java")
        complete(listener.captured)
        assertTrue(settled.await(5, TimeUnit.SECONDS))

        assertEquals(AssetPackStatus.FAILED to ToolchainFailure.STORAGE, result)
        assertEquals("previous payload", old.readText())
        assertTrue(delivery.isDirectory)
    }
}
