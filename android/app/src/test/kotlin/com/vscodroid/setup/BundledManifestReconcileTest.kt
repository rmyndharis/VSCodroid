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

    @Test
    fun `an extension the user uninstalled stays uninstalled`() {
        val add = bundledIdsToRelist(
            bundledIds = listOf(welcome, safBridge),
            keptIds = setOf(welcome),
            droppedIds = emptySet(),
            previouslyBundledIds = setOf(welcome, safBridge),
        )
        assertTrue(add.isEmpty(),
            "saf-bridge was bundled before and has no entry, so the user removed it")
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
