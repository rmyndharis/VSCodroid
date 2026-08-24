package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [bundledDirsToExtract], which decides what gets copied over an
 * extension directory that is already on disk.
 *
 * The risk runs in both directions here, unlike the delete-side decisions.
 * Naming one directory too few means an edit this project made to its own
 * extension never reaches anyone who upgrades -- silently, because the code
 * loads and only its behaviour is stale. Naming one too many means re-copying
 * 57 MB on every version change and overwriting whatever an extension has
 * generated inside its own directory since it was installed.
 *
 * So most of these pin which side of the line a name falls on, and the two
 * kinds are deliberately given identical shapes -- same layout, same version
 * suffix -- so that nothing but the publisher can be doing the work.
 *
 * Every call names its arguments. The function and its two neighbours all take
 * two `List<String>` in the same order, and a swap between them compiles in
 * silence; the asymmetric cases below would catch one, but only by failing in a
 * way that looks like a logic bug rather than a transposition.
 */
class BundledExtractionSplitTest {

    /** Authored in this repository, edited in place, version rarely moves. */
    private val ourMonitor = "vscodroid.vscodroid-process-monitor-1.0.0"
    private val ourWelcome = "vscodroid.vscodroid-welcome-1.2.0"

    /** Fetched at a pinned version by download-extensions.sh. */
    private val fetchedIcons = "PKief.material-icon-theme-5.37.0"
    private val fetchedPython = "ms-python.python-2026.4.0"

    @Test
    fun `our own extension is unpacked again even though its directory is there`() {
        // The defect the split exists for: the version in the name is unchanged
        // because this project edited the code without bumping it.
        assertEquals(
            listOf(ourMonitor),
            bundledDirsToExtract(present = listOf(ourMonitor), bundled = listOf(ourMonitor)),
        )
    }

    @Test
    fun `a fetched extension is left alone once its directory is there`() {
        // Its bytes cannot have changed under a fixed version, and re-copying
        // would overwrite anything it has generated for itself since install.
        assertTrue(
            bundledDirsToExtract(present = listOf(fetchedIcons), bundled = listOf(fetchedIcons)).isEmpty()
        )
    }

    @Test
    fun `a fetched extension is unpacked when its directory is absent`() {
        // Clean install, and the version-bump case: a new version is a new
        // directory name, so it is absent and gets unpacked.
        assertEquals(
            listOf(fetchedIcons),
            bundledDirsToExtract(
                present = listOf("PKief.material-icon-theme-5.36.0"),
                bundled = listOf(fetchedIcons),
            ),
        )
    }

    /**
     * A user's own newer install of something this build has begun to bundle
     * stops the bundled copy being unpacked at all.
     *
     * The four sweeps that read this directory all decline it:
     * [supersededExtensionDirs] refuses to touch a directory that is currently
     * bundled, [retiredOwnExtensionDirs] wants our publisher and
     * [retiredFetchedExtensionDirs] wants an id this build no longer ships. Nor
     * does it gain a manifest entry, because `bundledIdsToRelist` deliberately
     * leaves an id whose own entry survives alone, so the user's newer install
     * keeps winning. Unpacked, listed by nobody, removable by nothing: 29 MiB for
     * the Python extension, re-created on every upgrade. The copy the user chose
     * runs either way, so not writing it is the whole remedy.
     */
    @Test
    fun `a newer install of the same id stops the bundled copy being unpacked`() {
        val theirs = "ms-python.python-2026.9.0"

        assertTrue(
            bundledDirsToExtract(present = listOf(theirs), bundled = listOf(fetchedPython)).isEmpty(),
            "29 MiB was written for a version nothing will ever load or remove",
        )
    }

    /**
     * The control, and it is what keeps the case above from being "fetched
     * extensions stopped being unpacked". An OLDER install is exactly the version
     * bump the split exists to serve: the bundled copy is unpacked and the older
     * directory is removed by supersession.
     */
    @Test
    fun `an older install of the same id is still replaced`() {
        val theirs = "ms-python.python-2026.1.0"

        assertEquals(
            listOf(fetchedPython),
            bundledDirsToExtract(present = listOf(theirs), bundled = listOf(fetchedPython)),
        )
        assertEquals(listOf(theirs), supersededExtensionDirs(listOf(theirs), listOf(fetchedPython)))
    }

    /**
     * A version neither side can order is unpacked rather than guessed at, the
     * same direction [supersededExtensionDirs] takes for one it cannot parse.
     * Unpacking a copy that is already there costs disk; skipping one that should
     * have landed costs the extension.
     */
    @Test
    fun `an unorderable installed version does not suppress the bundled copy`() {
        val theirs = "ms-python.python-2026.9.0-rc1"

        assertEquals(
            listOf(fetchedPython),
            bundledDirsToExtract(present = listOf(theirs), bundled = listOf(fetchedPython)),
        )
    }

    @Test
    fun `a newer install of a DIFFERENT id changes nothing`() {
        // The id has to match. Without that test every fetched extension would
        // be suppressed by any newer directory in the same folder.
        assertEquals(
            listOf(fetchedPython),
            bundledDirsToExtract(
                present = listOf("PKief.material-icon-theme-9.99.0"),
                bundled = listOf(fetchedPython),
            ),
        )
    }

    @Test
    fun `a clean install unpacks everything`() {
        val bundled = listOf(ourMonitor, fetchedIcons, ourWelcome, fetchedPython)
        assertEquals(bundled, bundledDirsToExtract(present = emptyList(), bundled = bundled))
    }

    @Test
    fun `an upgrade unpacks ours and skips theirs`() {
        // The everyday case, and the one the split is measured on: four
        // directories present, only the two this project authors are re-copied.
        val bundled = listOf(ourMonitor, fetchedIcons, ourWelcome, fetchedPython)
        assertEquals(
            listOf(ourMonitor, ourWelcome),
            bundledDirsToExtract(present = bundled, bundled = bundled),
        )
    }

    @Test
    fun `the publisher decides, not the shape of the name`() {
        // Both of these are `publisher.name-version` with the same number of
        // segments and the same version. If anything but the publisher were
        // doing the work, these two would not come apart.
        val ours = "vscodroid.material-icon-theme-5.37.0"
        val theirs = "PKief.material-icon-theme-5.37.0"
        assertEquals(
            listOf(ours),
            bundledDirsToExtract(present = listOf(ours, theirs), bundled = listOf(ours, theirs)),
        )
    }

    @Test
    fun `a publisher that merely starts the same way is not ours`() {
        // `vscodroid.` includes the dot for a reason: a marketplace publisher
        // called `vscodroidtools` would otherwise be re-copied every upgrade,
        // taking its generated state with it.
        val lookalike = "vscodroidtools.helper-1.0.0"
        assertTrue(
            bundledDirsToExtract(present = listOf(lookalike), bundled = listOf(lookalike)).isEmpty()
        )
    }

    @Test
    fun `order follows the bundled list`() {
        // Extraction order is the manifest's order downstream; keeping it
        // stable keeps the generated extensions.json stable.
        val bundled = listOf(fetchedPython, ourWelcome, fetchedIcons, ourMonitor)
        assertEquals(bundled, bundledDirsToExtract(present = emptyList(), bundled = bundled))
    }

    /**
     * What all three decisions do on a version bump, together.
     *
     * Each is tested alone elsewhere; nothing pinned how they compose, and that
     * is the gap a reader falls into. A bump leaves both directories on disk,
     * and the obvious reading is that the old one is *retired* -- it carries
     * this project's publisher and is no longer bundled, which is two of
     * [retiredOwnExtensionDirs]'s three conditions. The third excludes it: its
     * base id is still bundled, under a different version. So the old directory
     * is a supersession, handled by [supersededExtensionDirs], and retirement
     * is reserved for an id this build stopped shipping altogether.
     *
     * The distinction is not cosmetic. Retirement deletes on the strength of
     * the publisher alone; supersession deletes only after comparing versions
     * and refuses when the version on disk is newer or unparseable. Routing a
     * bump through the wrong one would delete a user's newer install of the
     * same extension.
     */
    @Test
    fun `a version bump is a supersession, not a retirement`() {
        val old = "vscodroid.vscodroid-welcome-1.2.0"
        val new = "vscodroid.vscodroid-welcome-1.2.1"
        val present = listOf(old, new)
        val bundled = listOf(new)

        assertEquals(
            listOf(new),
            bundledDirsToExtract(present = present, bundled = bundled),
            "only the bundled version is unpacked",
        )
        assertEquals(
            listOf(old),
            supersededExtensionDirs(present, bundled),
            "the older directory is what gets removed, and by version comparison",
        )
        assertTrue(
            retiredOwnExtensionDirs(present, bundled).isEmpty(),
            "the older directory must NOT be read as retired: its base id is still bundled, " +
                "and retirement deletes on the publisher alone without comparing versions",
        )
    }

    @Test
    fun `an id this build stopped shipping is retired, and only then`() {
        // The other side of the same line, so the test above cannot pass by
        // retirement having stopped working altogether.
        val dropped = "vscodroid.vscodroid-github-auth-1.0.0"
        val present = listOf(dropped, ourWelcome)
        val bundled = listOf(ourWelcome)

        assertEquals(listOf(dropped), retiredOwnExtensionDirs(present, bundled))
        assertTrue(
            supersededExtensionDirs(present, bundled).isEmpty(),
            "nothing supersedes it -- no version of that id is bundled at all",
        )
    }

    /** What earlier releases unpacked and no build ships any more. */
    private val gitlens = "eamodio.gitlens-2026.2.1114"

    @Test
    fun `an extension this project stopped shipping is retired at any version`() {
        // Keyed on the identifier, not the version. The pin moved four times
        // before removal, so a version literal would clear some devices and
        // leave others -- and the one the last release shipped is not the one
        // the repository carried last.
        for (version in listOf("2026.2.1114", "2026.3.1505", "17.11.1", "18.3.0")) {
            val dir = "eamodio.gitlens-$version"
            assertEquals(
                listOf(dir),
                retiredFetchedExtensionDirs(listOf(dir, ourWelcome)),
                "$dir was not retired",
            )
        }
    }

    @Test
    fun `an extension still bundled is not retired by it`() {
        // The other direction. This sweep names only what the list names, so
        // everything the build still ships has to come through untouched.
        assertTrue(
            retiredFetchedExtensionDirs(listOf(ourWelcome, ourMonitor, fetchedIcons, fetchedPython)).isEmpty()
        )
    }

    @Test
    fun `a publisher whose name merely begins the same way is not retired`() {
        // The match is the id, or the id followed by a hyphen -- never a bare
        // prefix, which would also claim a different extension.
        assertTrue(retiredFetchedExtensionDirs(listOf("eamodio.gitlens2-1.0.0")).isEmpty())
        assertTrue(retiredFetchedExtensionDirs(listOf("eamodio.gitlensx")).isEmpty())
    }

    @Test
    fun `a fresh install has nothing to retire`() {
        // Pinned deliberately: "no-op when absent" is the case a future widening
        // to a prefix or a glob would break with every other assertion here
        // still green.
        assertTrue(retiredFetchedExtensionDirs(emptyList()).isEmpty())
        assertTrue(retiredFetchedExtensionDirs(listOf(ourWelcome, fetchedIcons)).isEmpty())
    }

    /**
     * Which of the four sweeps claims it, since they all read one listing.
     *
     * Three of them derive their answer from what is currently bundled, so the
     * natural assumption is that one already covers this. None does: GitLens
     * carries someone else's publisher, so retirement cannot see it, and no
     * version of its id is bundled, so supersession cannot either.
     */
    @Test
    fun `a stopped-shipping extension is claimed by exactly one sweep`() {
        val present = listOf(gitlens, ourWelcome, fetchedIcons)
        val bundled = listOf(ourWelcome, fetchedIcons)

        assertTrue(
            supersededExtensionDirs(present, bundled).isEmpty(),
            "supersession claimed it; no version of its id is bundled",
        )
        assertTrue(
            retiredOwnExtensionDirs(present, bundled).isEmpty(),
            "retirement claimed it; it is not our publisher",
        )
        assertTrue(
            bundledDirsToExtract(present = present, bundled = bundled).none { it == gitlens },
            "extraction wanted to unpack it; it is not in the build",
        )
        assertEquals(listOf(gitlens), retiredFetchedExtensionDirs(present))
    }

    @Test
    fun `the two listings are not interchangeable`() {
        // Pins the parameter order itself. With the arguments transposed the
        // answer changes, so a call site that swaps them is a behaviour change
        // rather than a no-op -- which is why the neighbours' order is matched
        // and why every call above names its arguments.
        val present = listOf(fetchedIcons)
        val bundled = listOf(fetchedPython)
        assertEquals(listOf(fetchedPython), bundledDirsToExtract(present = present, bundled = bundled))
        assertEquals(listOf(fetchedIcons), bundledDirsToExtract(present = bundled, bundled = present))
    }

    /**
     * A fetched extension the user removed is not unpacked again.
     *
     * VS Code's uninstall deletes the directory with the manifest entry, so on
     * the next upgrade the directory is absent and presence alone answered
     * "unpack". `bundledIdsToRelist` then declined to list it, deliberately, and
     * no sweep removes a name this build bundles: 29 MiB of Python extension,
     * unlisted, until a version bump swept it and wrote it once more.
     *
     * NEGATIVE CONTROL: drop the `uninstalled` test from the filter and this
     * names the Python directory.
     */
    @Test
    fun `a fetched extension the user removed is not unpacked again`() {
        assertTrue(
            bundledDirsToExtract(
                present = emptyList(),
                bundled = listOf(fetchedPython),
                uninstalled = setOf("ms-python.python"),
            ).isEmpty(),
            "an extension the user uninstalled was unpacked into a directory nothing lists",
        )
    }

    @Test
    fun `the removal is read against the lowercased id`() {
        // The record is built from package.json's lowercased halves, and a real
        // bundled publisher is PKief.
        assertTrue(
            bundledDirsToExtract(
                present = emptyList(),
                bundled = listOf(fetchedIcons),
                uninstalled = setOf("pkief.material-icon-theme"),
            ).isEmpty(),
        )
    }

    @Test
    fun `a removal names one id and leaves every other bundled extension alone`() {
        // Ours are re-unpacked unconditionally whatever the set says, and a
        // fetched id not in it is unpacked exactly as before.
        assertEquals(
            listOf(ourMonitor, fetchedIcons),
            bundledDirsToExtract(
                present = emptyList(),
                bundled = listOf(ourMonitor, fetchedIcons, fetchedPython),
                uninstalled = setOf("ms-python.python", "vscodroid.vscodroid-process-monitor"),
            ),
        )
    }

    @Test
    fun `a retired id is owed a sweep until one has run`() {
        assertEquals(listOf("eamodio.gitlens"), retiredIdsToSweep(emptySet()))
        assertTrue(retiredIdsToSweep(setOf("eamodio.gitlens")).isEmpty())
    }

    @Test
    fun `retiring a second extension later sweeps only that one`() {
        // Kills a boolean in place of the set. With one flag, adding an id to
        // RETIRED_FETCHED_IDS would either sweep nothing on a device that had
        // already run, or sweep both again on one that had not.
        assertEquals(
            listOf("some.newlyretired"),
            retiredIdsToSweep(
                alreadySwept = setOf("eamodio.gitlens"),
                retiredIds = listOf("eamodio.gitlens", "some.newlyretired"),
            ),
        )
    }

    @Test
    fun `a reinstalled extension survives once the sweep has run`() {
        // The defect this pair exists for, stated end to end. The sweep cannot
        // tell a leftover from a deliberate reinstall -- both are the same
        // directory name -- so the only thing that can protect the second is
        // not looking a second time.
        val reinstalled = "eamodio.gitlens-2026.8.1"
        val present = listOf(reinstalled, "vscodroid.vscodroid-welcome-1.2.1")

        val firstRun = retiredIdsToSweep(emptySet())
        assertEquals(
            listOf(reinstalled),
            retiredFetchedExtensionDirs(present, firstRun),
            "the leftover must be removed the first time",
        )

        val laterRun = retiredIdsToSweep(setOf("eamodio.gitlens"))
        assertTrue(
            retiredFetchedExtensionDirs(present, laterRun).isEmpty(),
            "a later update must not take the user's reinstall away",
        )
    }
}
