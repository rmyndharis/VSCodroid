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
import org.junit.jupiter.api.Assumptions.assumeFalse
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
        assumeFalse(source.canRead(), "This account bypasses file read permissions")
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
        // The second file's write runs out of room the way a full disk refuses it, after the first was replaced.
        val blocker = blockAtomicWritesTo(File(filesDir, "$root/${children.last().name}"))
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

        blocker.close()
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

    /**
     * A folder where the pack writes a file is found before the copy starts. Nothing of the installed tree is
     * replaced, no marker asks a later launch to settle a copy that never began, and the person is not told to free
     * space, which would not help. Play's copy stays for a Retry in the same process; the next launch finds the
     * toolchain recorded with nothing pending and hands the copy back, so a Retry after it fetches the pack again.
     */
    @Test
    fun `a Play reinstall blocked by a folder in its way writes nothing and finishes once the folder is cleared`() {
        val root = "usr/lib/jvm/test"
        val state = File(filesDir, "home/.vscodroid/toolchains.json").apply {
            writeText("""[{"name":"java","displayName":"Java 21","installRoot":"$root","execBitsChecked":true}]""")
        }
        val recorded = state.readText()
        val delivery = File(filesDir, "delivery")
        fun deliver() {
            File(delivery, "toolchain_java.json").apply { parentFile?.mkdirs() }.writeText(
                """{"name":"java","displayName":"Java 21","installRoot":"$root"}""",
            )
            File(delivery, "$root/one").apply { parentFile?.mkdirs() }.writeText("new one")
            File(delivery, "$root/two").writeText("new two")
        }
        deliver()
        val first = File(filesDir, "$root/one").apply { parentFile?.mkdirs() }.apply { writeText("previous one") }
        val blocked = File(filesDir, "$root/two").apply { mkdirs() }
        val held = File(blocked, "hold").apply { writeText("the person's file") }
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
        val completed = CountDownLatch(1)
        var why: ToolchainFailure? = null
        val install = manager()
        install.onStateChange = { _, status, _, reason, _ ->
            if (status == AssetPackStatus.FAILED) {
                why = reason
                failed.countDown()
            }
            if (status == AssetPackStatus.COMPLETED) completed.countDown()
        }
        install.install("toolchain_java")
        complete(listener.captured)
        assertTrue(failed.await(5, TimeUnit.SECONDS), "the install never reported its failure")

        assertEquals(ToolchainFailure.INTERNAL, why, "a folder in the pack's way was reported as a lack of room")
        assertEquals("previous one", first.readText(), "the copy started over a path it could not finish")
        assertEquals("the person's file", held.readText())
        assertEquals(recorded, state.readText(), "the record changed for a copy that never started")
        val marker = File(filesDir, "home/.vscodroid/toolchain-installing/toolchain_java.json")
        assertFalse(marker.exists(), "a marker was left for a copy that never started")
        assertTrue(playHasDelivery && delivery.isDirectory, "the refused install discarded its source")

        val nextLaunch = manager()
        nextLaunch.reconcileDeliveredPacks()
        awaitLaunchPass(nextLaunch)

        assertFalse(playHasDelivery, "a delivery for a recorded toolchain with nothing pending was kept")
        assertEquals("previous one", first.readText(), "the next launch touched the installed tree")
        assertEquals(recorded, state.readText(), "the next launch changed the record")

        // Cleared, a Retry fetches the pack again and installs it.
        blocked.deleteRecursively()
        deliver()
        playHasDelivery = true
        install.install("toolchain_java")
        complete(listener.captured)
        assertTrue(completed.await(5, TimeUnit.SECONDS), "the retry never finished")

        assertEquals("new one", first.readText())
        assertEquals("new two", File(filesDir, "$root/two").readText())
        assertEquals(listOf("java"), install.getInstalledToolchains())
        awaitLaunchPass(install)
        assertFalse(playHasDelivery, "the finished install kept Play's copy")
    }

    /**
     * A copy killed partway leaves its temporary file beside the file it was replacing, up to the size of a library,
     * and only the next write into that directory removes it, which comes after the check of free room. So the check,
     * about to refuse, removes those first and measures again.
     */
    @Test
    fun `a Play install about to be refused for room first takes back what a killed copy left`() {
        val root = "usr/lib/jvm/test"
        val stray = File(filesDir, "usr/lib/.vscodroid-${Integer.toUnsignedString("libbig.so".hashCode(), 16)}-" +
            "${java.util.UUID.randomUUID()}.tmp~").apply { parentFile?.mkdirs() }
        val strayBytes = 40_000_000L
        RandomAccessFile(stray, "rw").use { it.setLength(strayBytes) }
        val delivery = File(filesDir, "delivery").apply { mkdirs() }
        File(delivery, "toolchain_java.json").writeText(
            """{"name":"java","displayName":"Java 21","installRoot":"$root"}""",
        )
        File(delivery, "$root/one").apply { parentFile?.mkdirs() }.writeText("new one")
        val required = toolchainCopyBytes(packUnpackedBytes("toolchain_java")!!, ToolchainCopySpace(0, 0))
        every { anyConstructed<StatFs>().availableBytes } answers {
            required - 1 + if (stray.exists()) 0 else strayBytes
        }
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
        assertTrue(settled.await(5, TimeUnit.SECONDS), "the install never reported an outcome")

        assertEquals(AssetPackStatus.COMPLETED to null, result, "refused for room a killed copy was still holding")
        assertFalse(stray.exists(), "the killed copy's temporary file is still there")
    }

    /**
     * The check is made where each entry is written too, not only before the copy starts: an entry that comes into
     * the pack's way after that check is refused as a path in the way, and nothing is written through it.
     */
    @Test
    fun `an entry in the pack's way is refused as such where the copy reaches it`() {
        val src = File(filesDir, "src").apply { mkdirs() }
        File(src, "dir").mkdirs()
        File(src, "dir/file").writeText("new")
        val elsewhere = File(filesDir, "elsewhere").apply { mkdirs() }
        val occupations = mapOf<String, (File) -> Unit>(
            "a folder at a file's name" to { dest ->
                File(dest, "dir/file").mkdirs()
                File(dest, "dir/file/kept").writeText("kept")
            },
            "a file at a folder's name" to { dest ->
                dest.mkdirs()
                File(dest, "dir").writeText("kept")
            },
            "a link at a folder's name" to { dest ->
                dest.mkdirs()
                Files.createSymbolicLink(File(dest, "dir").toPath(), elsewhere.toPath())
            },
        )
        for ((why, occupy) in occupations) {
            val dest = File(filesDir, "dest-${occupations.keys.indexOf(why)}")
            occupy(dest)

            assertThrows(PathOccupied::class.java, { copyDirectoryTree(src, dest) }, why)
        }
        assertFalse(File(elsewhere, "file").exists(), "the copy wrote through the link")
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
