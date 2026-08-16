package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the storage gate may credit for a directory it does not own alone.
 *
 * `server/` can be measured and believed; nothing but extraction writes there.
 * `usr/` and the extensions directory cannot, because toolchains, `npm install
 * -g` and gallery installs all land in them. Charging both in full on every
 * update is what asked an updater for about 334 MB where roughly 180 would do.
 *
 * Every case here is chosen for the direction it can be wrong in. Crediting too
 * little costs a user one round of freeing space they did not need to free.
 * Crediting too much is the failure that matters: the gate passes, extraction
 * runs out of disk partway, and the user is told "Setup failed" with no figure,
 * on every retry, because the toolchains stay exactly where they are.
 */
class SharedTreeCreditTest {

    private val mb = 1_048_576L

    /** Stands in for `BUNDLED_USR_BYTES`; the real one is zero on a CI runner. */
    private val bundled = 110 * mb

    @Test
    fun `a directory holding exactly the bundled tree is credited in full`() {
        assertEquals(
            bundled,
            FirstRunSetup.sharedTreeCredit(
                installedBytes = bundled,
                bundledBytes = bundled,
                foreignBytes = 0,
            ),
        )
    }

    @Test
    fun `a partially unpacked directory is credited for what it holds`() {
        assertEquals(
            40 * mb,
            FirstRunSetup.sharedTreeCredit(
                installedBytes = 40 * mb,
                bundledBytes = bundled,
                foreignBytes = 0,
            ),
        )
    }

    /**
     * The case the whole function exists for: `usr/` swollen by Java and Go is
     * still only worth the bundled part, because overwriting the bundled part is
     * all extraction does.
     */
    @Test
    fun `toolchains in the directory do not inflate the credit`() {
        val java = 146 * mb
        val go = 179 * mb
        assertEquals(
            bundled,
            FirstRunSetup.sharedTreeCredit(
                installedBytes = bundled + java + go,
                bundledBytes = bundled,
                foreignBytes = java + go,
            ),
        )
    }

    /**
     * Without the cap this would credit 325 MB of toolchain as though extraction
     * were about to write over it, which is the direction that lets a device
     * through that cannot finish.
     */
    @Test
    fun `the credit never exceeds what the APK actually carries`() {
        val credit = FirstRunSetup.sharedTreeCredit(
            installedBytes = 500 * mb,
            bundledBytes = bundled,
            foreignBytes = 0,
        )
        assertEquals(bundled, credit)
        assertTrue(credit <= bundled, "a credit above the bundled size is space that does not exist")
    }

    @Test
    fun `a foreign share larger than the directory yields no credit rather than a negative one`() {
        assertEquals(
            0L,
            FirstRunSetup.sharedTreeCredit(
                installedBytes = 50 * mb,
                bundledBytes = bundled,
                foreignBytes = 400 * mb,
            ),
        )
    }

    /**
     * Null and zero must not be interchangeable, and the same inputs are used
     * for both so the difference cannot come from anywhere else.
     *
     * Zero asserts the directory is ours alone. Null says we could not tell, and
     * the honest answer to that is to credit nothing: an unreadable
     * `toolchains.json` would otherwise credit a `usr/` full of toolchains in
     * full, which is the exact shape of the bug being avoided.
     */
    @Test
    fun `an unknown foreign share credits nothing, unlike a foreign share of zero`() {
        val installed = bundled + 300 * mb
        val unknown = FirstRunSetup.sharedTreeCredit(installed, bundled, foreignBytes = null)
        val none = FirstRunSetup.sharedTreeCredit(installed, bundled, foreignBytes = 0)

        assertEquals(0L, unknown, "an undetermined share must credit nothing")
        assertEquals(bundled, none, "a share known to be zero credits the bundled tree")
        assertTrue(
            unknown < none,
            "null is being treated as zero, so a directory we cannot account for is " +
                "credited as if we owned all of it",
        )
    }

    @Test
    fun `a build with no asset tree credits nothing`() {
        assertEquals(
            0L,
            FirstRunSetup.sharedTreeCredit(
                installedBytes = 300 * mb,
                bundledBytes = 0,
                foreignBytes = 0,
            ),
            "with nothing bundled there is nothing extraction will write over",
        )
    }

    /**
     * Ties the credit back to the demand, which is the number a user reads.
     *
     * Not a restatement of [FirstRunSetup.requiredExtractionBytes]: it checks
     * that crediting the shared trees actually moves the demand down, which is
     * the user-visible effect, and that it moves down by the credited amount
     * rather than by some other quantity.
     */
    @Test
    fun `crediting the shared trees lowers what the gate demands`() {
        // Proportioned like the shipping tree: server, usr and extensions sum to
        // the asset total, so the credit stays inside what is still missing and
        // the subtraction is not clamped. Rounding the three to whole MB against
        // a separately rounded total is what made an earlier version of this
        // test expect a fall 1 MiB larger than the arithmetic can produce.
        val server = 654 * mb
        val usr = 110 * mb
        val extensions = 47 * mb
        val assetBytes = server + usr + extensions
        val largest = 113 * mb

        val before = FirstRunSetup.requiredExtractionBytes(assetBytes, largest, server)
        val credit = FirstRunSetup.sharedTreeCredit(usr, usr, 0) +
            FirstRunSetup.sharedTreeCredit(extensions, extensions, 0)
        val after = FirstRunSetup.requiredExtractionBytes(assetBytes, largest, server + credit)

        assertTrue(after < before, "crediting the shared trees must lower the demand")
        assertEquals(
            credit,
            before - after,
            "the demand should fall by exactly what was credited, no more",
        )
    }
}
