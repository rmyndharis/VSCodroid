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
import org.json.JSONArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The orphan sweep inside the real extraction pass, where the order of the
 * sweeps and the manifest reconcile decides whether it is safe.
 *
 * [OrphanedBundledCopyTest] pins the decision; this pins that it is asked of the
 * disk after the superseded sweep and of every profile's manifest, and that a
 * version bump still ends with the new bundled copy installed and listed.
 */
class OrphanedBundledSweepTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    private val bundledDir = "ms-python.python-2026.4.0"
    private val galleryDir = "ms-python.python-2026.5.0-universal"

    private val extensionsDir by lazy { File(filesDir, "home/.vscodroid/extensions") }
    private val manifest by lazy { File(extensionsDir, "extensions.json") }
    private val profiles by lazy { File(filesDir, "home/.vscodroid/data/User/profiles") }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        val packageJson = """{"publisher":"ms-python","name":"python","version":"2026.4.0"}"""
        val assets = mockk<AssetManager>()
        every { assets.list("extensions") } returns arrayOf(bundledDir)
        every { assets.list("extensions/$bundledDir") } returns arrayOf("package.json")
        every { assets.list("extensions/$bundledDir/package.json") } returns emptyArray()
        every { assets.open("extensions/$bundledDir/package.json") } answers
            { ByteArrayInputStream(packageJson.toByteArray()) }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets

        extensionsDir.mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun extractBundledExtensions() {
        FirstRunSetup::class.java
            .getDeclaredMethod("extractBundledExtensions")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    private fun installedDir(name: String, version: String) = File(extensionsDir, name).apply {
        mkdirs()
        File(this, "package.json").writeText(
            """{"publisher":"ms-python","name":"python","version":"$version"}""",
        )
    }

    private fun entry(dir: String, version: String) =
        """{"identifier":{"id":"ms-python.python"},"version":"$version",""" +
            """"location":{"${'$'}mid":1,"path":"${File(extensionsDir, dir).absolutePath}","scheme":"file"},""" +
            """"relativeLocation":"$dir"}"""

    private fun listedDirs() = JSONArray(manifest.readText()).let { a ->
        (0 until a.length()).map { a.getJSONObject(it).optString("relativeLocation") }
    }

    @Test
    fun `the unlisted bundled copy beside the user's install is removed`() {
        installedDir(bundledDir, "2026.4.0")
        installedDir(galleryDir, "2026.5.0")
        manifest.writeText("[${entry(galleryDir, "2026.5.0")}]")

        extractBundledExtensions()

        assertFalse(File(extensionsDir, bundledDir).exists(), "the orphan is still on disk")
        assertTrue(File(extensionsDir, "$galleryDir/package.json").isFile, "the user's copy was touched")
        assertEquals(listOf(galleryDir), listedDirs())
    }

    @Test
    fun `a version bump keeps the new bundled copy and lists it`() {
        installedDir("ms-python.python-2026.3.0", "2026.3.0")
        manifest.writeText("[${entry("ms-python.python-2026.3.0", "2026.3.0")}]")

        extractBundledExtensions()

        assertTrue(File(extensionsDir, "$bundledDir/package.json").isFile, "the new bundled copy was deleted")
        assertFalse(File(extensionsDir, "ms-python.python-2026.3.0").exists())
        assertEquals(listOf(bundledDir), listedDirs())
    }

    @Test
    fun `a bundled copy another profile lists is kept`() {
        installedDir(bundledDir, "2026.4.0")
        installedDir(galleryDir, "2026.5.0")
        manifest.writeText("[${entry(galleryDir, "2026.5.0")}]")
        File(profiles, "5f3a").apply { mkdirs() }
            .resolve("extensions.json").writeText("[${entry(bundledDir, "2026.4.0")}]")

        extractBundledExtensions()

        assertTrue(File(extensionsDir, bundledDir).isDirectory, "a profile's extension was deleted")
    }

    @Test
    fun `a profile manifest that cannot be read stops the sweep`() {
        installedDir(bundledDir, "2026.4.0")
        installedDir(galleryDir, "2026.5.0")
        manifest.writeText("[${entry(galleryDir, "2026.5.0")}]")
        File(profiles, "5f3a").apply { mkdirs() }.resolve("extensions.json").writeText("[{truncated")

        extractBundledExtensions()

        assertTrue(File(extensionsDir, bundledDir).isDirectory, "deleted without knowing what the profile lists")
    }
}
