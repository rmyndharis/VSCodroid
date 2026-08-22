package com.vscodroid.setup

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The launch pass reclaims staging directories no download owns any more.
 *
 * A download stages into a directory of its own and deletes it in a `finally`.
 * That `finally` does not run when the process goes away first: an OOM kill
 * while backgrounded, a force-stop, a crash. The directory used to be one
 * constant path, so the next attempt simply overwrote it; per-request paths
 * fixed two downloads deleting each other's work and turned an abandoned one
 * into permanent storage instead.
 *
 * Permanent and not idle. `StorageManager.clearCaches` deletes four other
 * directories, so the storage screen shows these bytes under "cache" and its
 * Clear action does not move the figure, while every toolchain space pre-flight
 * reads `StatFs(filesDir).availableBytes` on the same filesystem. Three attempts
 * abandoned mid-transfer leave roughly 170 MB apiece standing between the user
 * and the retry that then refuses for want of space.
 */
class AbandonedDownloadSweepTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var staging: File

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // Reached through field initialisation in the constructor, so it has to
        // be stubbed before a manager is built.
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        File(filesDir, "home/.vscodroid").mkdirs()
        staging = File(filesDir, "cache/toolchain-download")
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** A staging directory with a ZIP in it, last touched [ageMs] ago. */
    private fun abandoned(name: String, ageMs: Long): File {
        val dir = File(staging, name).apply { mkdirs() }
        File(dir, "toolchain_java.zip").writeText("payload")
        assertTrue(
            dir.setLastModified(System.currentTimeMillis() - ageMs),
            "could not age $name, so this test would prove nothing",
        )
        return dir
    }

    /** Waits for the launch pass, which runs on the manager's io executor. */
    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition(), what)
    }

    @Test
    fun `a staging directory left by a download that never finished is reclaimed`() {
        val old = abandoned("toolchain_java-1", 3 * ABANDONED_DOWNLOAD_AGE_MS)

        ToolchainManager(context).repairInstalledToolchains()

        waitUntil("the abandoned staging directory was never reclaimed: $old") { !old.exists() }
    }

    /**
     * The control, and the half that keeps the sweep from taking a running
     * download's work: a recent directory is left where it is. The timestamp is
     * a weak witness -- writing into a file that already exists does not touch
     * the directory -- which is why the bar is a whole day rather than minutes.
     */
    @Test
    fun `a staging directory that could still belong to a running download is kept`() {
        val old = abandoned("toolchain_java-1", 3 * ABANDONED_DOWNLOAD_AGE_MS)
        val fresh = abandoned("toolchain_ruby-2", 0)

        ToolchainManager(context).repairInstalledToolchains()

        waitUntil("the sweep never ran, so the case below proves nothing") { !old.exists() }
        assertTrue(
            fresh.isDirectory && File(fresh, "toolchain_java.zip").isFile,
            "a staging directory a download could still be writing into was deleted",
        )
    }
}
