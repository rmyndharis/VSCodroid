package com.vscodroid.setup

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A bundled extension's directory name and its own manifest must agree on
 * publisher, name and version.
 *
 * The directory name is not decoration. [supersededExtensionDirs] decides what
 * to delete from it, [bundledDirsToExtract] decides what to unpack by it, and
 * `reconcileExtensionsManifest` writes it into `extensions.json` as
 * `relativeLocation` beside a version it reads from `package.json`. Let those
 * two disagree and the manifest claims one version lives in another version's
 * directory, while the sweep that removes the previous copy is keyed on the
 * name nobody updated.
 *
 * The reason this is worth a test rather than care: editing one of our own
 * extensions and forgetting the version has already cost this project a
 * release. A correction made inside a directory whose name did not change
 * reached clean installs and no one upgrading, because the editor caches its
 * scan of the extensions directory against that directory's own timestamp, and
 * rewriting a file two levels down does not move it. Adding and removing a
 * directory does. So the version bump is not bookkeeping; it is the delivery
 * mechanism, and half a bump delivers nothing while looking done.
 *
 * Deliberately not a check that the version was bumped *this* change: there is
 * no baseline in the tree to compare against, and a rule demanding a bump on
 * every edit would fire on whitespace. Agreement between the two halves is the
 * part that is decidable here, and it is the half that fails silently.
 */
class BundledExtensionVersionTest {

    private val extensionsDir = File("src/main/assets/extensions")

    @Test
    fun `every bundled extension directory is named for the manifest inside it`() {
        val dirs = extensionsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()

        // The positive control. The assertion below reports success when it sees
        // no disagreement, so a wrong directory or a renamed asset path would
        // turn this into a scan of nothing that passes.
        assertTrue(
            dirs.isNotEmpty(),
            "no bundled extension directories under ${extensionsDir.absolutePath}, so this " +
                "test checked nothing. Paths here resolve from the Gradle test working " +
                "directory, which is the module directory (android/app).",
        )

        val disagreements = dirs.mapNotNull { dir ->
            val manifest = File(dir, "package.json")
            if (!manifest.isFile) return@mapNotNull "${dir.name}: no package.json"
            val pkg = JSONObject(manifest.readText())
            val expected = "${pkg.optString("publisher")}.${pkg.optString("name")}-" +
                pkg.optString("version")
            if (dir.name == expected) null else "${dir.name}: manifest says $expected"
        }

        assertEquals(
            emptyList<String>(), disagreements,
            "a bundled extension's directory name and its own package.json disagree. The " +
                "directory name is what the superseded sweep and the extraction split read, " +
                "and the manifest is what the version in extensions.json comes from, so a " +
                "mismatch ships a manifest pointing at the wrong copy. Rename the directory " +
                "and edit the manifest together. Checked ${dirs.size} directories.",
        )
    }
}
