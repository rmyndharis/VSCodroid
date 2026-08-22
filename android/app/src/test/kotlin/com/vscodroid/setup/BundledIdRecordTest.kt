package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

/**
 * What the persisted bundled-identifier record is derived from.
 *
 * The record is the only thing that tells an extension the user uninstalled
 * from one this app has never shipped: [bundledIdsToRelist] says so, and every
 * upgrade acts on it. It therefore has to name what the APK carries. It was
 * derived instead from the ids a manifest entry could be built for, which is a
 * question about the disk, and the two were the same set only while every
 * bundled directory was unpacked.
 *
 * [bundledDirsToExtract] stopped unpacking one case on purpose: a fetched
 * extension whose id the user already holds at a newer version. That directory
 * is never created, so no entry can be built for it, so its id fell out of the
 * record. Nothing is visible on that upgrade, because the user's own copy
 * carries the entry. It becomes visible one upgrade later: they uninstall their
 * copy, VS Code removes the entry and the directory together, and the next
 * upgrade unpacks the bundled version, finds no entry, no dropped entry, and no
 * record of ever having bundled the id, so it lists it again and the uninstall
 * is undone.
 *
 * ExtensionsManifestWriteTest already pins WHEN the record is written; every
 * assertion there takes the value as `any()`. These are about WHAT is in it.
 */
class BundledIdRecordTest {

    /** The record's key, named rather than matched, for the reason its twin gives. */
    private val BUNDLED_IDS_KEY = "bundled_extension_ids"

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var extensionsDir: File
    private lateinit var manifestFile: File

    /** What this build bundles, and the newer copy the user already holds of it. */
    private val bundledDir = "ms-python.python-2026.4.0"
    private val theirsDir = "ms-python.python-2026.9.0"
    private val id = "ms-python.python"

    private fun pkg(version: String) =
        """{"publisher":"ms-python","name":"python","version":"$version"}"""

    /**
     * The last value written under [BUNDLED_IDS_KEY].
     *
     * Round-tripped rather than merely captured: the defect only shows on the
     * upgrade AFTER the one that writes the record, so a second run has to read
     * back what the first one wrote.
     */
    private var recorded: MutableSet<String>? = null

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        assets = mockk()
        every { assets.list(any()) } returns emptyArray()
        every { assets.list("extensions") } returns arrayOf(bundledDir)
        every { assets.list("extensions/$bundledDir") } returns arrayOf("package.json")
        // answers rather than returns: the second run opens the same asset again.
        every { assets.open("extensions/$bundledDir/package.json") } answers
            { ByteArrayInputStream(pkg("2026.4.0").toByteArray()) }

        editor = mockk(relaxed = true)
        every { editor.putStringSet(BUNDLED_IDS_KEY, any()) } answers {
            recorded = HashSet(secondArg<Set<String>>())
            editor
        }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getStringSet(any(), any()) } returns mutableSetOf()
        every { prefs.getStringSet(BUNDLED_IDS_KEY, any()) } answers { recorded }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs

        extensionsDir = File(filesDir, "home/.vscodroid/extensions")
        extensionsDir.mkdirs()
        manifestFile = File(extensionsDir, "extensions.json")
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun extractBundledExtensions() {
        FirstRunSetup::class.java
            .getDeclaredMethod("extractBundledExtensions")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    /**
     * The user's own gallery install of the same extension, one version newer,
     * listed in the manifest the way the server leaves it.
     */
    private fun stageTheirNewerInstall() {
        val dir = File(extensionsDir, theirsDir)
        assertTrue(dir.mkdirs(), "could not stage the user's own install")
        File(dir, "package.json").writeText(pkg("2026.9.0"))
        manifestFile.writeText(
            """[{"identifier":{"id":"$id"},"relativeLocation":"$theirsDir"}]"""
        )
    }

    @Test
    fun `the ids come from the directory names, not from the disk`() {
        assertEquals(
            listOf("pkief.material-icon-theme", "ms-python.python", "vscodroid.vscodroid-welcome"),
            bundledExtensionIds(
                listOf(
                    "PKief.material-icon-theme-5.37.0",
                    "ms-python.python-2026.4.0",
                    "vscodroid.vscodroid-welcome-1.2.2",
                )
            ),
            "the record has to name the same identifiers manifestEntryFor builds from each " +
                "package.json, which lowercases both halves; a real bundled publisher is PKief",
        )
    }

    @Test
    fun `a name that is not publisher-name-version contributes nothing`() {
        assertTrue(
            bundledExtensionIds(listOf("notanextension", "trailing-")).isEmpty(),
            "a directory name that is not the shape the whole file splits on was turned into " +
                "an identifier anyway, which puts a name nothing bundles into the record",
        )
    }

    @Test
    fun `an extension skipped for a newer install is still recorded as bundled`() {
        stageTheirNewerInstall()

        extractBundledExtensions()

        assertFalse(
            File(extensionsDir, bundledDir).exists(),
            "the harness did not reach the skip this test is about",
        )
        assertEquals(
            setOf(id),
            recorded,
            "the record left out an id this build bundles, so a later uninstall of the user's " +
                "own copy reads as an id never shipped",
        )
    }

    /**
     * The user-visible half, end to end, and the reason the test above is worth
     * having: the run that writes the short record shows nothing wrong.
     */
    @Test
    fun `an uninstall after that upgrade is not undone by the next one`() {
        stageTheirNewerInstall()
        extractBundledExtensions()

        // VS Code removes the entry and the directory together.
        assertTrue(File(extensionsDir, theirsDir).deleteRecursively(), "could not remove their copy")
        manifestFile.writeText("[]")

        extractBundledExtensions()

        assertTrue(
            File(extensionsDir, bundledDir).isDirectory,
            "the second run did not unpack the bundled copy, so this case proves nothing",
        )
        assertFalse(
            manifestFile.readText().contains(id),
            "the extension the user uninstalled was listed again by the next upgrade",
        )
    }
}
