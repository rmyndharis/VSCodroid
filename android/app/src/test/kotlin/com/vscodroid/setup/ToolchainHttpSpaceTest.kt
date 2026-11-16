package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.os.StatFs
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
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class ToolchainHttpSpaceTest {
    @TempDir
    lateinit var filesDir: File
    private lateinit var context: Context
    private lateinit var toolchains: ToolchainManager
    private val failed = CountDownLatch(1)

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns 0L
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"
        File(filesDir, "home/.vscodroid").mkdirs()
        toolchains = ToolchainManager(context).apply {
            onStateChange = { _, status, _, _, _ ->
                if (status == AssetPackStatus.FAILED) failed.countDown()
            }
        }
    }

    @AfterEach
    fun tearDown() {
        val executor = ToolchainManager::class.java.getDeclaredField("ioExecutor")
            .apply { isAccessible = true }.get(toolchains) as ExecutorService
        try {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "the HTTP task outlived its fixture")
        } finally {
            executor.shutdownNow()
            unmockkAll()
        }
    }

    private fun installedBy(installer: String) {
        val source = mockk<InstallSourceInfo>()
        every { source.installingPackageName } returns installer
        every { context.packageManager.getInstallSourceInfo(any()) } returns source
    }

    private fun manager() = toolchains

    private fun sparseFile(path: String, bytes: Long) {
        val file = File(filesDir, path)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
    }

    private fun refusalNaming(bytes: Long) =
        "Not enough disk space: 0 MB available, " + bytes / 1_000_000 + " MB required"

    @Test
    fun `an HTTP reinstall credits a pack's payload across shared directories once`() {
        installedBy("com.example.sideloader")
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(
            """[{"name":"cpp","displayName":"C/C++",
                "binaries":["usr/bin/clang-21"],
                "libs":["libLLVM.so","libclang-cpp.so"],
                "ownedPaths":["usr/lib/clang","usr/lib/clang/21","usr/lib/clang/21/header.h",
                    "usr/bin/clang-21","usr/lib/libLLVM.so"]}]"""
        )
        sparseFile("usr/lib/libLLVM.so", 30_000_000L)
        sparseFile("usr/lib/libclang-cpp.so", 10_000_000L)
        sparseFile("usr/bin/clang-21", 5_000_000L)
        sparseFile("usr/lib/clang/21/header.h", 2_000_000L)
        sparseFile("usr/lib/clang/21/other.h", 1_000_000L)
        sparseFile("usr/lib/libpython.so", 100_000_000L)
        val cpp = ToolchainRegistry.find("toolchain_cpp")!!

        manager().install(cpp.packName)

        assertTrue(failed.await(10, TimeUnit.SECONDS), "the HTTP task never reported an outcome")
        val credited = toolchainInstallBytes(cpp.estimatedSize, cpp.downloadSize, InstalledTree(48_000_000L, 30_000_000L))
        verify { Logger.e(any(), refusalNaming(credited), any()) }
    }

    @Test
    fun `shared directories and redirected payload paths earn no HTTP credit`() {
        installedBy("com.example.sideloader")
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(
            """[{"name":"cpp","displayName":"C/C++",
                "ownedPaths":["usr/lib","usr/include","usr/lib/redirect"],
                "binaries":["../outside","usr/bin/link","usr/lib/redirect/large"],
                "symlinks":{"alias":"usr/bin/clang-21"}}]"""
        )
        sparseFile("usr/lib/other/large", 30_000_000L)
        sparseFile("usr/bin/alias", 20_000_000L)
        Files.createSymbolicLink(File(filesDir, "usr/lib/redirect").toPath(), File(filesDir, "usr/lib/other").toPath())
        Files.createSymbolicLink(File(filesDir, "usr/bin/link").toPath(), File(filesDir, "usr/lib/other/large").toPath())
        val cpp = ToolchainRegistry.find("toolchain_cpp")!!

        manager().install(cpp.packName)

        assertTrue(failed.await(10, TimeUnit.SECONDS), "the HTTP task never reported an outcome")
        verify { Logger.e(any(), refusalNaming(toolchainInstallBytes(cpp.estimatedSize, cpp.downloadSize)), any()) }
    }

}
