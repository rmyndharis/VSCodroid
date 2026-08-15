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
}
