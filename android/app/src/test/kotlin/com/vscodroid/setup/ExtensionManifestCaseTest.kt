package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.json.JSONArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale

/**
 * Verifies that `reconcileExtensionsManifest` normalizes `identifier.id` values from
 * `extensions.json` using [Locale.ROOT] before passing `keptIds` and `droppedIds` to
 * [bundledIdsToRelist].
 */
class ExtensionManifestCaseTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var extensionsDir: File
    private lateinit var manifestFile: File
    private var previousBundled: Set<String> = emptySet()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        previousBundled = emptySet()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getStringSet("bundled_extension_ids", any()) } answers { previousBundled }
        every { prefs.edit() } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.getSharedPreferences(any(), any()) } returns prefs

        extensionsDir = File(filesDir, "home/.vscodroid/extensions").apply { mkdirs() }
        manifestFile = File(extensionsDir, "extensions.json")
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun writeExtensionPackage(dirName: String, publisher: String, name: String, version: String) {
        val dir = File(extensionsDir, dirName).apply { mkdirs() }
        File(dir, "package.json").writeText(
            """{"publisher":"$publisher","name":"$name","version":"$version"}""",
        )
    }

    private fun reconcile(
        bundledDirs: Array<String>,
        bundledIds: List<String>,
        supersededDirs: List<String>,
    ) {
        val method = FirstRunSetup::class.java.getDeclaredMethod(
            "reconcileExtensionsManifest",
            File::class.java,
            File::class.java,
            Array<String>::class.java,
            List::class.java,
            List::class.java,
        ).apply { isAccessible = true }
        method.invoke(FirstRunSetup(context), manifestFile, extensionsDir, bundledDirs, bundledIds, supersededDirs)
    }

    @Test
    fun `mixed-case dropped entry is relisted when its bundled version is upgraded`() {
        val oldDir = "ms-python.python-1.0.0"
        val newDir = "ms-python.python-2.0.0"
        writeExtensionPackage(newDir, publisher = "MS-Python", name = "Python", version = "2.0.0")
        previousBundled = setOf("ms-python.python")
        manifestFile.writeText(
            """[{"identifier":{"id":"MS-Python.Python"},"relativeLocation":"$oldDir"}]""",
        )

        reconcile(
            bundledDirs = arrayOf(newDir),
            bundledIds = listOf("ms-python.python"),
            supersededDirs = listOf(oldDir),
        )

        val entries = JSONArray(manifestFile.readText())
        assertEquals(
            1,
            entries.length(),
            "a mixed-case dropped entry must be relisted with its upgraded bundled copy: ${manifestFile.readText()}",
        )
        assertEquals(newDir, entries.getJSONObject(0).getString("relativeLocation"))
    }

    @Test
    fun `mixed-case kept entry is not duplicated by bundled relist`() {
        val dir = "vscodroid.vscodroid-welcome-1.2.0"
        writeExtensionPackage(dir, publisher = "vscodroid", name = "vscodroid-welcome", version = "1.2.0")
        manifestFile.writeText(
            """[{"identifier":{"id":"VSCodroid.vscodroid-welcome"},"relativeLocation":"$dir","metadata":{"isBuiltin":true}}]""",
        )

        reconcile(
            bundledDirs = arrayOf(dir),
            bundledIds = listOf("vscodroid.vscodroid-welcome"),
            supersededDirs = emptyList(),
        )

        val entries = JSONArray(manifestFile.readText())
        assertEquals(
            1,
            entries.length(),
            "a live mixed-case entry must not gain a duplicate bundled entry: ${manifestFile.readText()}",
        )
    }

    @Test
    fun `uppercase ASCII I normalizes under Locale ROOT even when default locale is Turkish`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val oldDir = "ms-toolsai.jupyter-1.0.0"
            val newDir = "ms-toolsai.jupyter-2.0.0"
            writeExtensionPackage(newDir, publisher = "MS-ToolsAI", name = "Jupyter", version = "2.0.0")
            previousBundled = setOf("ms-toolsai.jupyter")
            manifestFile.writeText(
                """[{"identifier":{"id":"MS-ToolsAI.Jupyter"},"relativeLocation":"$oldDir"}]""",
            )

            reconcile(
                bundledDirs = arrayOf(newDir),
                bundledIds = listOf("ms-toolsai.jupyter"),
                supersededDirs = listOf(oldDir),
            )

            val entries = JSONArray(manifestFile.readText())
            assertEquals(1, entries.length(), "Turkish locale must not break ASCII id matching")
            val entry = entries.getJSONObject(0)
            assertEquals(newDir, entry.getString("relativeLocation"))
            assertEquals(
                "ms-toolsai.jupyter",
                entry.getJSONObject("identifier").getString("id"),
                "written manifest id must use ASCII lowercase, not Turkish dotless i",
            )
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `kept uppercase ASCII I entry is not duplicated under Turkish locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val dir = "ms-toolsai.jupyter-2.0.0"
            writeExtensionPackage(dir, publisher = "MS-ToolsAI", name = "Jupyter", version = "2.0.0")
            manifestFile.writeText(
                """[{"identifier":{"id":"MS-ToolsAI.Jupyter"},"relativeLocation":"$dir"}]""",
            )

            reconcile(
                bundledDirs = arrayOf(dir),
                bundledIds = listOf("ms-toolsai.jupyter"),
                supersededDirs = emptyList(),
            )

            val entries = JSONArray(manifestFile.readText())
            assertEquals(
                1,
                entries.length(),
                "a kept MS-ToolsAI.Jupyter entry must not be duplicated under Turkish locale: ${manifestFile.readText()}",
            )
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `uninstalled uppercase ASCII I extension is not resurrected under Turkish locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val dir = "ms-toolsai.jupyter-2.0.0"
            writeExtensionPackage(dir, publisher = "MS-ToolsAI", name = "Jupyter", version = "2.0.0")
            previousBundled = setOf("MS-ToolsAI.Jupyter")
            manifestFile.writeText("[]")

            reconcile(
                bundledDirs = arrayOf(dir),
                bundledIds = listOf("ms-toolsai.jupyter"),
                supersededDirs = emptyList(),
            )

            val entries = JSONArray(manifestFile.readText())
            assertEquals(
                0,
                entries.length(),
                "an uninstalled MS-ToolsAI.Jupyter extension must not be resurrected under Turkish locale: ${manifestFile.readText()}",
            )
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `extension helper functions normalize ASCII I under Turkish locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val bundled = listOf("MS-ToolsAI.Jupyter-2.0.0")
            assertEquals(listOf("ms-toolsai.jupyter"), bundledExtensionIds(bundled))

            val toExtractAfterUninstall = bundledDirsToExtract(
                present = emptyList(),
                bundled = bundled,
                uninstalled = setOf("ms-toolsai.jupyter"),
            )
            assertTrue(toExtractAfterUninstall.isEmpty(), "uninstalled id must be skipped under Turkish locale")

            val toExtractWithGallery = bundledDirsToExtract(
                present = listOf("ms-toolsai.jupyter-2.0.0-linux-arm64"),
                bundled = bundled,
            )
            assertTrue(toExtractWithGallery.isEmpty(), "gallery copy must suppress bundled extract under Turkish locale")

            val toExtractWithNewer = bundledDirsToExtract(
                present = listOf("ms-toolsai.jupyter-3.0.0"),
                bundled = bundled,
            )
            assertTrue(toExtractWithNewer.isEmpty(), "newer installed version must suppress bundled extract under Turkish locale")

            val orphans = orphanedBundledCopies(
                onDisk = listOf("MS-ToolsAI.Jupyter-2.0.0", "ms-toolsai.jupyter-3.0.0"),
                bundled = bundled,
                listed = listOf("MS-ToolsAI.Jupyter" to "ms-toolsai.jupyter-3.0.0"),
                referencedElsewhere = emptySet(),
            )
            assertEquals(bundled, orphans, "orphaned bundled copy detection must match ASCII I under Turkish locale")

            assertTrue(
                bundledIdsToRelist(
                    bundledIds = listOf("MS-ToolsAI.Jupyter"),
                    keptIds = setOf("ms-toolsai.jupyter"),
                    droppedIds = emptySet(),
                    previouslyBundledIds = emptySet(),
                ).isEmpty(),
            )
            assertFalse(
                bundledIdsToRelist(
                    bundledIds = listOf("MS-ToolsAI.Jupyter"),
                    keptIds = emptySet(),
                    droppedIds = emptySet(),
                    previouslyBundledIds = setOf("ms-toolsai.jupyter"),
                ).isNotEmpty(),
            )
        } finally {
            Locale.setDefault(saved)
        }
    }
}
