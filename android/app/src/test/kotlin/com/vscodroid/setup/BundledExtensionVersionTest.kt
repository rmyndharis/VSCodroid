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
 * release. The process-monitor extension's code was rewritten while its
 * manifest stayed at 1.0.0, extraction skipped the directory because a
 * directory of that name was already on disk, and the fix reached clean
 * installs and nobody who upgraded.
 *
 * That half is fixed, and reading this as "our edits never reach upgraders"
 * would be reading it backwards. [bundledDirsToExtract] re-unpacks every
 * `vscodroid.` directory unconditionally, so an upgrader does receive the
 * edited bytes, and restoring a blanket re-copy of the fetched ones to be safe
 * would undo the split that keeps 57 MB off every update and preserves state a
 * fetched extension regenerates inside its own directory.
 *
 * What a same-named edit still does not deliver is the editor's re-read of
 * `package.json`. Its scan of the extensions directory is keyed on that
 * directory's own timestamp, and writing a file two levels down does not move
 * it while adding and removing a directory does. So the bump is the delivery
 * mechanism for a change to a manifest, which is the only kind that needs one,
 * and half a bump, the name moved or the manifest moved but not both, is what
 * this file refuses.
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

    /**
     * `MILESTONES.md` holds the one prose inventory of these directories, and a
     * rename moves the directory without touching it. It was left naming a
     * welcome directory that no longer existed, which sends a reader, or an
     * agent told to check a claim against the tree, to a path nothing on disk
     * matches. Both directions, because an inventory that omits a directory is
     * as wrong as one that invents it.
     *
     * Scoped to that one file on purpose. `docs/12-IMPLEMENTATION_PLAN.md`
     * draws the same names at 1.0.0 inside a planned layout, which records what
     * was intended rather than claiming what ships; a rule sweeping every
     * document would fail on it, and a gate that fails on correct text is one
     * people edit rather than obey.
     */
    @Test
    fun `the milestones inventory names the directories that ship`() {
        val milestones = File("../../MILESTONES.md")
        assertTrue(
            milestones.isFile,
            "${milestones.absolutePath} not found. Paths here resolve from the Gradle test " +
                "working directory, which is the module directory (android/app).",
        )

        val named = Regex("""${Regex.escape(OWN_EXTENSION_PREFIX)}[a-z0-9-]+-\d+(?:\.\d+)*""")
            .findAll(milestones.readText()).map { it.value }.toSet()
        // The positive control, for the same reason as above: the inventory
        // moving to another heading, or the naming scheme changing, would leave
        // two empty sets agreeing with each other.
        assertTrue(
            named.isNotEmpty(),
            "MILESTONES.md names no $OWN_EXTENSION_PREFIX directory at all, so this test " +
                "compared nothing. The inventory moved or was dropped; point this at where it " +
                "lives now.",
        )

        val onDisk = extensionsDir.listFiles { f -> f.isDirectory }
            ?.map { it.name }.orEmpty()
            .filter { it.startsWith(OWN_EXTENSION_PREFIX) }
            .toSet()

        assertEquals(
            emptyList<String>(), (named - onDisk).sorted(),
            "MILESTONES.md names a bundled extension directory that is not in " +
                "${extensionsDir.absolutePath}. A rename left the inventory behind; update it " +
                "in the same change. Found ${named.size} named, ${onDisk.size} on disk.",
        )
        assertEquals(
            emptyList<String>(), (onDisk - named).sorted(),
            "a bundled extension directory is missing from the MILESTONES.md inventory, which " +
                "is the only prose list of them. Add it there, and check the count on the line " +
                "above the list. Found ${named.size} named, ${onDisk.size} on disk.",
        )
    }
}
