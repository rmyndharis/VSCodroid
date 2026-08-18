package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [setupIsStale], which decides whether first-run setup has to be
 * redone for the build now running.
 *
 * The direction that matters is the quiet one. Answering "stale" when it is not
 * costs an extraction the user waits through and that leaves the same tree
 * behind. Answering "current" when it is not skips extraction entirely, so the
 * app runs new Kotlin against the previous release's server tree, reports
 * nothing, and looks exactly like a successful upgrade.
 *
 * The case that motivates the versionCode half is first: one versionName worn
 * by two builds is not hypothetical here, it is what 1.1.0 did across
 * versionCode 11 and 12.
 */
class SetupStalenessTest {

    @Test
    fun `one versionName worn by two builds is still an upgrade`() {
        assertTrue(setupIsStale("1.1.0", 11, "1.1.0", 12))
    }

    @Test
    fun `the same build is not an upgrade`() {
        assertFalse(setupIsStale("1.1.0", 12, "1.1.0", 12))
    }

    @Test
    fun `a changed versionName is an upgrade even at the same code`() {
        assertTrue(setupIsStale("1.0.0", 12, "1.1.0", 12))
    }

    @Test
    fun `a fresh install has recorded nothing`() {
        assertTrue(setupIsStale(null, 0, "1.1.0", 12))
    }

    @Test
    fun `an install predating the versionCode record is redone once`() {
        // getInt's default for a key never written. Trusting it as a match
        // would skip setup on the strength of a record that does not exist.
        assertTrue(setupIsStale("1.1.0", 0, "1.1.0", 12))
    }

    @Test
    fun `a downgrade is an upgrade, because the tree still has to change`() {
        assertTrue(setupIsStale("1.1.0", 12, "1.0.0", 11))
    }
}
