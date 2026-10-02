package com.vscodroid.setup

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

/**
 * When `ensureExecPreload` rewrites an interceptor that already has the
 * asset's length.
 *
 * A same-version reinstall runs no extraction, so this per-launch repair is
 * the only thing that carries a rebuilt library onto the device, and a rebuild
 * can keep the old length. The installed file and the asset here are the same
 * length with different bytes, so only a rewrite changes what is on disk, and
 * the package's last update time is what tells the two cases apart.
 */
class ExecPreloadRepairTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    private val packageInfo = PackageInfo().apply { lastUpdateTime = UPDATED }

    private val installed = "OLD-LIBRARY-v1"

    /** Same length as [installed]. */
    private val shipped = "NEW-LIBRARY-v2"

    private val library by lazy { File(filesDir, Environment.EXEC_PRELOAD_ASSET) }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        val assets = mockk<AssetManager>()
        every { assets.open(Environment.EXEC_PRELOAD_ASSET) } answers { ByteArrayInputStream(shipped.toByteArray()) }
        val packageManager = mockk<PackageManager>()
        every { packageManager.getPackageInfo("com.vscodroid", 0) } returns packageInfo

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets
        every { context.packageName } returns "com.vscodroid"
        every { context.packageManager } returns packageManager

        library.parentFile!!.mkdirs()
        library.writeText(installed)
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    @Test
    fun `a library older than the last install is replaced at the same length`() {
        library.setLastModified(UPDATED - 60_000)

        FirstRunSetup(context).ensureExecPreload()

        assertEquals(shipped, library.readText(), "the reinstalled build's library never reached the device")
    }

    /** The repair stays a stat on the launches where the file is current. */
    @Test
    fun `a library written since the last install is left alone at the same length`() {
        library.setLastModified(UPDATED + 60_000)

        FirstRunSetup(context).ensureExecPreload()

        assertEquals(installed, library.readText(), "a current library was rewritten")
    }

    private companion object {
        const val UPDATED = 1_790_000_000_000L
    }
}
