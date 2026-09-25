package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [orphanedBundledCopies], which names bundled directories an earlier
 * update unpacked beside the user's own gallery install and nothing lists.
 *
 * It deletes, so almost every case pins something it must NOT name. The one
 * that matters most is the version bump: a freshly extracted bundled directory
 * is also unlisted for a moment, until the manifest reconcile lists it in place
 * of the superseded entry, and naming it there removes an extension the user
 * relied on.
 */
class OrphanedBundledCopyTest {

    private val bundled = listOf("ms-python.python-2026.4.0", "vscodroid.vscodroid-welcome-1.8.0")
    private val theirs = "ms-python.python-2026.5.0-universal"

    @Test
    fun `a bundled copy beside the user's listed gallery install is named`() {
        assertEquals(
            listOf("ms-python.python-2026.4.0"),
            orphanedBundledCopies(
                onDisk = listOf("ms-python.python-2026.4.0", theirs),
                bundled = bundled,
                listed = listOf("ms-python.python" to theirs),
                referencedElsewhere = emptySet(),
            ),
        )
    }

    @Test
    fun `a bundled copy the manifest lists is kept`() {
        assertTrue(
            orphanedBundledCopies(
                onDisk = listOf("ms-python.python-2026.4.0", theirs),
                bundled = bundled,
                listed = listOf(
                    "ms-python.python" to "ms-python.python-2026.4.0",
                    "ms-python.python" to theirs,
                ),
                referencedElsewhere = emptySet(),
            ).isEmpty(),
        )
    }

    @Test
    fun `a bundled copy another profile lists is kept`() {
        assertTrue(
            orphanedBundledCopies(
                onDisk = listOf("ms-python.python-2026.4.0", theirs),
                bundled = bundled,
                listed = listOf("ms-python.python" to theirs),
                referencedElsewhere = setOf("ms-python.python-2026.4.0"),
            ).isEmpty(),
        )
    }

    /**
     * The version bump. Extraction has just written 2026.4.0 and the
     * superseded sweep has just removed 2026.3.0, whose entry the manifest still
     * holds; the reconcile that runs next drops that entry and lists 2026.4.0 in
     * its place. The listed copy of the id is therefore gone from disk, and
     * "listed elsewhere" must be read against the disk as it is now.
     */
    @Test
    fun `the new bundled version during a bump is kept`() {
        assertTrue(
            orphanedBundledCopies(
                onDisk = listOf("ms-python.python-2026.4.0"),
                bundled = bundled,
                listed = listOf("ms-python.python" to "ms-python.python-2026.3.0"),
                referencedElsewhere = emptySet(),
            ).isEmpty(),
            "the extension the reconcile is about to list would be deleted",
        )
    }

    @Test
    fun `without a live copy of the same id nothing is named`() {
        assertTrue(
            orphanedBundledCopies(
                onDisk = listOf("ms-python.python-2026.4.0"),
                bundled = bundled,
                listed = emptyList(),
                referencedElsewhere = emptySet(),
            ).isEmpty(),
        )
    }

    @Test
    fun `our own extensions are never named`() {
        assertTrue(
            orphanedBundledCopies(
                onDisk = listOf("vscodroid.vscodroid-welcome-1.8.0", "vscodroid.vscodroid-welcome-1.9.0"),
                bundled = bundled,
                listed = listOf("vscodroid.vscodroid-welcome" to "vscodroid.vscodroid-welcome-1.9.0"),
                referencedElsewhere = emptySet(),
            ).isEmpty(),
        )
    }

    @Test
    fun `a directory this build does not bundle is never named`() {
        assertTrue(
            orphanedBundledCopies(
                onDisk = listOf("ms-python.python-2026.1.0-universal", theirs),
                bundled = bundled,
                listed = listOf("ms-python.python" to theirs),
                referencedElsewhere = emptySet(),
            ).isEmpty(),
            "a gallery install is the user's, whatever else is listed",
        )
    }

    @Test
    fun `identifiers match without regard to case`() {
        assertEquals(
            listOf("PKief.material-icon-theme-5.37.0"),
            orphanedBundledCopies(
                onDisk = listOf("PKief.material-icon-theme-5.37.0", "pkief.material-icon-theme-5.40.0-universal"),
                bundled = listOf("PKief.material-icon-theme-5.37.0"),
                listed = listOf("pkief.material-icon-theme" to "pkief.material-icon-theme-5.40.0-universal"),
                referencedElsewhere = emptySet(),
            ),
        )
    }

    @Test
    fun `an entry that names no directory proves nothing`() {
        assertTrue(
            orphanedBundledCopies(
                onDisk = listOf("ms-python.python-2026.4.0", theirs),
                bundled = bundled,
                listed = listOf("ms-python.python" to ""),
                referencedElsewhere = emptySet(),
            ).isEmpty(),
        )
    }
}
