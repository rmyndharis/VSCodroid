package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [bundledIdsToRelist], which decides which bundled extensions get an
 * entry written back into `extensions.json` on upgrade.
 *
 * Two situations look identical from the directory tree alone, and the whole
 * decision is telling them apart:
 *
 *  - the user uninstalled a bundled extension, which removes both its manifest
 *    entry and its directory. Extraction used to recreate the directory on the
 *    next launch, and a copy an earlier release left that way is still on
 *    disk, so re-listing it would undo the uninstall on every app update.
 *  - the app started bundling an extension it never shipped before. It also has
 *    no manifest entry, and its directory has also just appeared.
 *
 * The only thing that separates them is whether this app has ever bundled that
 * identifier before, which is why the caller has to remember. Before it did, the
 * second case was treated as the first: `vscodroid.vscodroid-serve-network`
 * shipped in v1.1.0 and was invisible to every user upgrading from v1.0.0, while
 * a clean install got it -- so no test on a fresh device could see it.
 */
class BundledManifestReconcileTest {

    private val welcome = "vscodroid.vscodroid-welcome"
    private val safBridge = "vscodroid.vscodroid-saf-bridge"
    private val serveNetwork = "vscodroid.vscodroid-serve-network"

    /** A marketplace extension this app bundles, which the user may remove for good. */
    private val python = "ms-python.python"

    @Test
    fun `an extension bundled for the first time is listed`() {
        val add = bundledIdsToRelist(
            bundledIds = listOf(welcome, serveNetwork),
            keptIds = setOf(welcome),
            droppedIds = emptySet(),
            previouslyBundledIds = setOf(welcome, safBridge),
        )
        assertEquals(listOf(serveNetwork), add,
            "an identifier this app has never bundled cannot have been uninstalled")
    }

    /**
     * The rule's real subject: a marketplace extension this app happens to bundle.
     * Removing one is an ordinary preference, and bringing it back on every update
     * would override that choice for ever.
     */
    @Test
    fun `a fetched extension the user uninstalled stays uninstalled`() {
        val add = bundledIdsToRelist(
            bundledIds = listOf(welcome, python),
            keptIds = setOf(welcome),
            droppedIds = emptySet(),
            previouslyBundledIds = setOf(welcome, python),
        )
        assertTrue(add.isEmpty(),
            "the Python extension was bundled before and has no entry, so the user " +
                "removed it and it must not come back on every update")
    }

    /**
     * VSCodroid's own extensions are not a removable preference. They carry the
     * device folder picker, the toolchain screen and the editor defaults the app
     * contributes, so a removed one takes those with it and nothing inside the
     * editor can put it back.
     *
     * Releases before `isBuiltin` was written let the editor offer Uninstall, and
     * `isBuiltin` only refuses the next one; it cannot relist a copy already gone.
     * Meanwhile `bundledDirsToExtract` exempts this same prefix, so the directory
     * returned on every update and sat there unlisted and unloaded. The visible
     * cost is the editor defaults: the pass that removes the app's old overrides
     * commits once and for all, while the contributed defaults meant to replace
     * them never load, so word wrap, the minimap and the sash size all revert with
     * no route back short of clearing app data.
     */
    @Test
    fun `one of this app's own extensions is relisted even after it was removed`() {
        val add = bundledIdsToRelist(
            bundledIds = listOf(welcome, safBridge),
            keptIds = emptySet(),
            droppedIds = emptySet(),
            previouslyBundledIds = setOf(welcome, safBridge),
        )
        assertEquals(listOf(welcome, safBridge), add,
            "an extension of this app's own, removed under an older release, was left " +
                "unlisted, so its contributed defaults never load and the screens it " +
                "carries stay unreachable")
    }

    /** Even ours does not gain a second entry while it still has a live one. */
    @Test
    fun `one of this app's own extensions is not duplicated over a live entry`() {
        val add = bundledIdsToRelist(
            bundledIds = listOf(welcome),
            keptIds = setOf(welcome),
            droppedIds = emptySet(),
            previouslyBundledIds = setOf(welcome),
        )
        assertTrue(add.isEmpty(),
            "keptIds must still win for this app's own extensions, or a user's own " +
                "newer install of the same id is shadowed by the bundled copy")
    }

    @Test
    fun `an entry dropped because its directory was swapped is restored`() {
        val add = bundledIdsToRelist(
            bundledIds = listOf(safBridge),
            keptIds = emptySet(),
            droppedIds = setOf(safBridge),
            previouslyBundledIds = setOf(safBridge),
        )
        assertEquals(listOf(safBridge), add,
            "a version bump drops the old entry and must re-add the new directory")
    }

    @Test
    fun `a surviving entry is never duplicated`() {
        val add = bundledIdsToRelist(
            bundledIds = listOf(welcome),
            keptIds = setOf(welcome),
            droppedIds = setOf(welcome),
            previouslyBundledIds = emptySet(),
        )
        assertTrue(add.isEmpty(),
            "keptIds wins over every other reason to add, so a user's own newer " +
                "install of the same id is not shadowed by the bundled copy")
    }

    @Test
    fun `with nothing remembered every bundled extension is listed`() {
        // The transition case: upgrading from a version that never recorded the
        // set. Everything reads as new. That re-lists an extension a user had
        // uninstalled under the old version -- once, recoverably -- and is the
        // deliberate trade for making genuinely new extensions appear at all.
        // From the release that writes the set onward, the distinction is exact.
        val add = bundledIdsToRelist(
            bundledIds = listOf(welcome, safBridge, serveNetwork),
            keptIds = setOf(welcome),
            droppedIds = emptySet(),
            previouslyBundledIds = emptySet(),
        )
        assertEquals(listOf(safBridge, serveNetwork), add)
        assertFalse(add.contains(welcome), "a listed entry is still not duplicated")
    }

    @Test
    fun `order follows the bundled list so the manifest is stable`() {
        val add = bundledIdsToRelist(
            bundledIds = listOf(serveNetwork, safBridge, welcome),
            keptIds = emptySet(),
            droppedIds = emptySet(),
            previouslyBundledIds = emptySet(),
        )
        assertEquals(listOf(serveNetwork, safBridge, welcome), add)
    }
}
