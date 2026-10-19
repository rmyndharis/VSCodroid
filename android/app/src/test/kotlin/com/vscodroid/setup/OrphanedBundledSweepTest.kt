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
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a version bump preserves another profile's copy while updating the default`(pathOnly: Boolean) {
        val oldDir = "ms-python.python-2026.3.0"
        val old = installedDir(oldDir, "2026.3.0")
        File(old, "extension.js").writeText("previous extension")
        manifest.writeText("[${entry(oldDir, "2026.3.0")}]")
        val profileEntry = if (pathOnly) {
            org.json.JSONObject(entry(oldDir, "2026.3.0")).apply { remove("relativeLocation") }.toString()
        } else {
            entry(oldDir, "2026.3.0")
        }
        val profileManifest = File(profiles, "5f3a").apply { mkdirs() }.resolve("extensions.json")
        val profileContent = "[$profileEntry]"
        profileManifest.writeText(profileContent)

        extractBundledExtensions()

        assertTrue(old.isDirectory, "the named profile's extension was deleted")
        assertEquals("previous extension", File(old, "extension.js").readText())
        assertEquals(profileContent, profileManifest.readText())
        assertTrue(File(extensionsDir, "$bundledDir/package.json").isFile)
        assertEquals(listOf(bundledDir), listedDirs())

        profileManifest.writeText("[]")
        extractBundledExtensions()

        assertFalse(old.exists(), "the unreferenced older copy was never reclaimed")
        assertEquals(listOf(bundledDir), listedDirs())
    }

    @ParameterizedTest
    @ValueSource(strings = ["[{truncated", "[{}]", "[{\"identifier\":{\"id\":\"ms-python.python\"}}]"])
    fun `unknown profile references preserve superseded copies`(profileContent: String) {
        val oldDir = "ms-python.python-2026.3.0"
        val old = installedDir(oldDir, "2026.3.0")
        manifest.writeText("[${entry(oldDir, "2026.3.0")}]")
        val profileManifest = File(profiles, "5f3a").apply { mkdirs() }.resolve("extensions.json")
        profileManifest.writeText(profileContent)

        extractBundledExtensions()

        assertTrue(old.isDirectory, "deleted while the profile's references were unknown")
        assertEquals(profileContent, profileManifest.readText())
        assertEquals(listOf(bundledDir), listedDirs())
        assertTrue(File(extensionsDir, "$bundledDir/package.json").isFile)
    }

    @Test
    fun `an unreadable profile manifest preserves superseded copies`() {
        val oldDir = "ms-python.python-2026.3.0"
        val old = installedDir(oldDir, "2026.3.0")
        manifest.writeText("[${entry(oldDir, "2026.3.0")}]")
        File(profiles, "5f3a/extensions.json").mkdirs()

        extractBundledExtensions()

        assertTrue(old.isDirectory)
        assertEquals(listOf(bundledDir), listedDirs())
    }

    @Test
    fun `an unreadable profiles directory preserves superseded copies`() {
        val oldDir = "ms-python.python-2026.3.0"
        val old = installedDir(oldDir, "2026.3.0")
        manifest.writeText("[${entry(oldDir, "2026.3.0")}]")
        profiles.parentFile.mkdirs()
        profiles.writeText("not a directory")

        extractBundledExtensions()

        assertTrue(old.isDirectory)
        assertEquals(listOf(bundledDir), listedDirs())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a profile that cannot be searched preserves its hidden references`(hideProfilesRoot: Boolean) {
        val oldDir = "ms-python.python-2026.3.0"
        val old = installedDir(oldDir, "2026.3.0")
        manifest.writeText("[${entry(oldDir, "2026.3.0")}]")
        val profile = File(profiles, "5f3a").apply { mkdirs() }
        val profileManifest = File(profile, "extensions.json")
        profileManifest.writeText("[${entry(oldDir, "2026.3.0")}]")
        val protectedDir = if (hideProfilesRoot) profiles.parentFile else profile
        try {
            protectedDir.setExecutable(false, false)
            assumeFalse(profileManifest.exists(), "this host can still search the protected profile")

            extractBundledExtensions()

            assertTrue(old.isDirectory, "an inaccessible manifest was treated as absent")
            assertEquals(listOf(bundledDir), listedDirs())
        } finally {
            protectedDir.setExecutable(true, true)
        }
    }

    @Test
    fun `a pinned older gallery default stays selected during a bundled upgrade`() {
        val pinnedDir = "ms-python.python-2026.1.0-universal"
        installedDir(pinnedDir, "2026.1.0")
        val pinned = org.json.JSONObject(entry(pinnedDir, "2026.1.0"))
            .put("metadata", org.json.JSONObject().put("pinned", true)).toString()
        manifest.writeText("[$pinned]")
        val oldDir = "ms-python.python-2026.3.0"
        val old = installedDir(oldDir, "2026.3.0")
        File(profiles, "5f3a").apply { mkdirs() }
            .resolve("extensions.json").writeText("[${entry(oldDir, "2026.3.0")}]")

        extractBundledExtensions()

        assertTrue(old.isDirectory)
        assertTrue(File(extensionsDir, "$pinnedDir/package.json").isFile)
        assertEquals(listOf(pinnedDir), listedDirs())
        assertTrue(JSONArray(manifest.readText()).getJSONObject(0).getJSONObject("metadata").getBoolean("pinned"))
    }

    @Test
    fun `a version bump installs the new bundled copy beside a copy only another profile uses`() {
        val oldDir = "ms-python.python-2026.3.0"
        val profileGallery = "ms-python.python-2026.2.0-universal"
        installedDir(oldDir, "2026.3.0")
        installedDir(profileGallery, "2026.2.0")
        manifest.writeText("[${entry(oldDir, "2026.3.0")}]")
        File(profiles, "5f3a").apply { mkdirs() }
            .resolve("extensions.json").writeText("[${entry(profileGallery, "2026.2.0")}]")

        extractBundledExtensions()

        assertTrue(
            File(extensionsDir, "$bundledDir/package.json").isFile,
            "the default profile was left without the updated copy",
        )
        assertFalse(File(extensionsDir, oldDir).exists(), "the superseded copy is still on disk")
        assertTrue(File(extensionsDir, "$profileGallery/package.json").isFile, "the named profile's copy was touched")
        assertEquals(listOf(bundledDir), listedDirs())
    }

    @Test
    fun `a version bump installs the new bundled copy beside a gallery copy nothing lists`() {
        val oldDir = "ms-python.python-2026.3.0"
        installedDir(oldDir, "2026.3.0")
        installedDir(galleryDir, "2026.5.0")
        manifest.writeText("[${entry(oldDir, "2026.3.0")}]")

        extractBundledExtensions()

        assertTrue(File(extensionsDir, "$bundledDir/package.json").isFile)
        assertFalse(File(extensionsDir, oldDir).exists())
        assertTrue(File(extensionsDir, "$galleryDir/package.json").isFile, "an unlisted copy that is not ours was touched")
        assertEquals(listOf(bundledDir), listedDirs())
    }

    @Test
    fun `a version bump leaves the default profile on its own newer gallery copy`() {
        installedDir("ms-python.python-2026.3.0", "2026.3.0")
        installedDir(galleryDir, "2026.5.0")
        manifest.writeText("[${entry(galleryDir, "2026.5.0")}]")

        extractBundledExtensions()

        assertFalse(File(extensionsDir, bundledDir).exists(), "unpacked beside the user's own copy")
        assertEquals(listOf(galleryDir), listedDirs())
    }
}
