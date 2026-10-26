package com.vscodroid.bridge

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.Task
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.AssetPackStates
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.storage.SafStorageManager
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ToolchainBridgeSubscriptionTest {
    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packs: AssetPackManager
    private val registered = mutableSetOf<AssetPackStateUpdateListener>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        packs = mockk(relaxed = true)
        every { packs.registerListener(any()) } answers {
            registered.add(firstArg())
            Unit
        }
        every { packs.unregisterListener(any()) } answers {
            registered.remove(firstArg())
            Unit
        }
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packs

        val source = mockk<InstallSourceInfo>()
        every { source.installingPackageName } returns "com.android.vending"
        val packages = mockk<PackageManager>(relaxed = true)
        every { packages.getInstallSourceInfo(any()) } returns source
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"
        every { context.packageManager } returns packages
        every { context.applicationContext } returns context
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun bridge() = AndroidBridge(
        context = context,
        security = mockk<SecurityManager>(relaxed = true).also {
            every { it.validateToken(any()) } returns true
        },
        clipboard = mockk(relaxed = true),
        onBackPressed = mockk(relaxed = true),
        onMinimize = mockk(relaxed = true),
        onOpenFolderPicker = mockk(relaxed = true),
        onOpenRecentFolder = mockk(relaxed = true),
        onShowAbout = mockk(relaxed = true),
        safManager = mockk<SafStorageManager>(relaxed = true),
    )

    private fun emit(pack: String, status: Int) {
        val state = mockk<AssetPackState>(relaxed = true)
        every { state.name() } returns pack
        every { state.status() } returns status
        registered.toList().forEach { it.onStateUpdate(state) }
    }

    @Test
    fun `one failed pack keeps the other requested pack subscribed`() {
        val bridge = bridge()
        bridge.installToolchain("ruby", "")
        bridge.installToolchain("java", "")
        assertEquals(1, registered.size)

        emit("toolchain_ruby", AssetPackStatus.FAILED)

        assertEquals(1, registered.size, "Java still needs the manager listener")
        emit("toolchain_java", AssetPackStatus.CANCELED)
        assertTrue(registered.isEmpty(), "the last requested pack has settled")
    }

    @Test
    fun `an unrelated failed pack keeps requested packs subscribed`() {
        bridge().installToolchain("java", "")
        assertEquals(1, registered.size)

        emit("toolchain_cpp", AssetPackStatus.FAILED)

        assertEquals(1, registered.size, "the reported pack was never requested here")
    }

    @Test
    fun `a single settled request releases its listener`() {
        bridge().installToolchain("ruby", "")
        assertEquals(1, registered.size)

        emit("toolchain_ruby", AssetPackStatus.CANCELED)

        assertTrue(registered.isEmpty())
    }

    @Test
    fun `same pack requests share a fetch and settle once`() {
        val bridge = bridge()
        bridge.installToolchain("ruby", "")
        bridge.installToolchain("toolchain_ruby", "")

        verify(exactly = 1) { packs.fetch(listOf("toolchain_ruby")) }
        emit("toolchain_ruby", AssetPackStatus.CANCELED)
        assertTrue(registered.isEmpty())

        bridge.installToolchain("ruby", "")
        verify(exactly = 2) { packs.fetch(listOf("toolchain_ruby")) }
        assertEquals(1, registered.size)
    }

    @Test
    fun `a refused fetch leaves another requested pack subscribed`() {
        val bridge = bridge()
        bridge.installToolchain("java", "")
        every { packs.fetch(listOf("toolchain_ruby")) } returns mockk(relaxed = true) {
            every { addOnFailureListener(any<OnFailureListener>()) } answers {
                firstArg<OnFailureListener>().onFailure(IllegalStateException("refused"))
                self as Task<AssetPackStates>
            }
        }

        bridge.installToolchain("ruby", "")

        assertEquals(1, registered.size)
        emit("toolchain_java", AssetPackStatus.CANCELED)
        assertTrue(registered.isEmpty())
    }

    @Test
    fun `an older fetch refusal cannot settle a newer request for that pack`() {
        val failures = mutableListOf<OnFailureListener>()
        every { packs.fetch(listOf("toolchain_ruby")) } returns mockk(relaxed = true) {
            every { addOnFailureListener(any<OnFailureListener>()) } answers {
                failures.add(firstArg())
                self as Task<AssetPackStates>
            }
        }
        val bridge = bridge()
        bridge.installToolchain("ruby", "")
        emit("toolchain_ruby", AssetPackStatus.CANCELED)
        bridge.installToolchain("ruby", "")

        failures.first().onFailure(IllegalStateException("old refusal"))

        assertEquals(1, registered.size)
        failures.last().onFailure(IllegalStateException("current refusal"))
        assertTrue(registered.isEmpty())
    }

    @Test
    fun `a synchronous fetch refusal releases its request`() {
        every { packs.fetch(listOf("toolchain_ruby")) } throws IllegalStateException("refused")

        bridge().installToolchain("ruby", "")

        assertTrue(registered.isEmpty())
    }
}
