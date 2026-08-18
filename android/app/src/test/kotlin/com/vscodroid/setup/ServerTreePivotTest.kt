package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins `PIVOT_VERSION_CODE`, which names a boundary in this project's history
 * and must never move again.
 *
 * `runPreExtractionMigrations` asks `fromVersionCode < PIVOT`, of the code a
 * device is upgrading FROM. So the value is not a property of the release being
 * built, it is the point where the server tree changed origin: everything
 * before it carries a pre-built VS Code Server that has to be deleted rather
 * than merged into, and everything from it onward carries the Code - OSS tree
 * that must be left alone.
 *
 * The mistake this guards is raising it to match the shipping versionCode, which
 * the constant's own documentation used to ask for. Every upgrade would then
 * delete a 700 MB server tree it already had correct and unpack it again, on
 * every release, and the only symptom would be that upgrading takes minutes.
 * Nothing else in the app would look wrong.
 *
 * Both bounds are settled history rather than judgement, which is why they can
 * be asserted at all. Read through reflection because the constant is private
 * and there is no reason to widen it for a test.
 */
class ServerTreePivotTest {

    private val pivot: Int by lazy {
        val field = FirstRunSetup::class.java.getDeclaredField("PIVOT_VERSION_CODE")
        field.isAccessible = true
        field.getInt(null)
    }

    /** versionCode 10 shipped v1.0.0 and its pre-built server. It must trigger. */
    @Test
    fun `the last release carrying the old tree still triggers the migration`() {
        assertTrue(
            10 < pivot,
            "PIVOT_VERSION_CODE is $pivot, so an upgrade from v1.0.0 (versionCode 10) " +
                "would keep its pre-built server tree and merge the new one into it",
        )
    }

    /**
     * versionCode 12 is the first release carrying the Code - OSS tree; 11 was
     * burned by a failed upload and reached no device. Anything from 12 onward
     * already has the right tree, so the migration must not fire for it.
     */
    @Test
    fun `the first release carrying the new tree does not trigger it`() {
        assertTrue(
            pivot <= 12,
            "PIVOT_VERSION_CODE is $pivot, past the release that first shipped the " +
                "Code - OSS tree. Every upgrade would delete a correct server tree " +
                "and unpack it again. The constant names a point in history and does " +
                "not track the shipping versionCode.",
        )
    }
}
