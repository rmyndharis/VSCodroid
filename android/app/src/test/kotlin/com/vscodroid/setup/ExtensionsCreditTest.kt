package com.vscodroid.setup

import android.content.Context
import android.content.res.AssetManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * How much of the extensions directory the storage gate is allowed to credit.
 *
 * That directory is the `--extensions-dir` the server installs gallery
 * extensions into, so its size on disk is not an answer to "how much of what we
 * are about to write is already here". The gate passed the whole of it with a
 * literal `foreignBytes = 0`, which asserts the directory is ours alone, and
 * `sharedTreeCredit`'s own doc names that as the assumption to avoid.
 *
 * It cost nothing while everything bundled here was ours: four extensions,
 * 60 KB, and the cap the credit is measured against was 60 KB with it. This
 * release bundles five extensions from the gallery and that cap is 46.6 MiB, so
 * a device with any gallery installs at all was credited the whole bundled tree
 * for bytes not one of which was on disk. The slack absorbs it rather than
 * ENOSPC, which is the point: it is 46.6 MiB out of a 64 MiB margin, spent
 * silently, across an 810 MiB unpack.
 *
 * Reached through reflection because the measurement is private and takes no
 * arguments: what it reads is the directory and the assets listing, and both are
 * staged here.
 */
class ExtensionsCreditTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var assets: AssetManager
    private lateinit var context: Context

    /** Where the server and this app both write. */
    private val extensionsDir get() = File(filesDir, "home/.vscodroid/extensions")

    private val bundledPython = "ms-python.python-2026.4.0"

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs

        assets = mockk()
        every { assets.list(any()) } returns emptyArray()

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun bundle(vararg names: String) {
        every { assets.list("extensions") } returns arrayOf(*names)
    }

    private fun stage(dirName: String, bytes: Int) {
        val dir = File(extensionsDir, dirName)
        assertTrue(dir.mkdirs(), "could not stage $dirName")
        File(dir, "extension.js").writeText("x".repeat(bytes))
    }

    private fun credited(): Long =
        FirstRunSetup::class.java
            .getDeclaredMethod("installedBundledExtensionBytes")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context)) as Long

    @Test
    fun `a gallery install is not credited as a bundled one`() {
        bundle(bundledPython)
        stage("someone.else-1.0.0", 4096)

        assertEquals(
            0L,
            credited(),
            "an extension the user took from the gallery was counted as bytes the " +
                "next unpack writes over, so the gate asked for less than it needs",
        )
    }

    /**
     * The control. Without it a measurement that had simply stopped counting
     * anything would satisfy the case above.
     */
    @Test
    fun `a bundled directory already on disk is credited for what it holds`() {
        bundle(bundledPython)
        stage(bundledPython, 4096)

        assertEquals(4096L, credited())
    }

    /**
     * The version-bump half of the same defect. A pinned extension moving version
     * means a new `id-version` directory written BESIDE the old one, so none of
     * the bytes about to be written are on disk, yet the whole directory was
     * offered as though they were.
     */
    @Test
    fun `the previous version of a bundled extension is not credited`() {
        bundle(bundledPython)
        stage("ms-python.python-2026.1.0", 4096)

        assertEquals(
            0L,
            credited(),
            "the superseded directory was credited for a version that is not there yet",
        )
    }

    @Test
    fun `a bundled directory that is absent is worth nothing`() {
        bundle(bundledPython)

        assertEquals(0L, credited())
    }

    /**
     * An assets listing that cannot be read credits nothing, which is the
     * direction that asks for more space rather than less.
     */
    @Test
    fun `an unreadable assets listing credits nothing`() {
        every { assets.list("extensions") } throws java.io.IOException("no assets")
        stage(bundledPython, 4096)

        assertEquals(0L, credited())
    }
}
