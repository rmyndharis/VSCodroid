package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [supersededExtensionDirs], which decides what gets deleted from the
 * user's extensions directory on upgrade.
 *
 * The risk being covered is one-directional: naming too few directories only
 * wastes disk, while naming one too many deletes an extension the user installed.
 * So most of these assert on what is *not* returned.
 */
class SupersededExtensionsTest {

    private val bundled = listOf(
        "PKief.material-icon-theme-5.37.0",
        "ms-python.python-2026.4.0",
        "eamodio.gitlens-2026.3.1505",
        "vscodroid.vscodroid-welcome-1.0.0",
    )

    @Test
    fun `names the previous version of a bundled extension`() {
        val present = bundled + "PKief.material-icon-theme-5.35.0"
        assertEquals(listOf("PKief.material-icon-theme-5.35.0"),
            supersededExtensionDirs(present, bundled))
    }

    @Test
    fun `keeps a newer copy the user installed from the marketplace`() {
        val present = bundled + "ms-python.python-2026.9.0"
        assertTrue(supersededExtensionDirs(present, bundled).isEmpty())
    }

    @Test
    fun `keeps extensions that are not bundled at all`() {
        val present = bundled + listOf("rust-lang.rust-analyzer-0.3.2000", "golang.go-0.42.0")
        assertTrue(supersededExtensionDirs(present, bundled).isEmpty())
    }

    @Test
    fun `keeps the bundled versions themselves`() {
        assertTrue(supersededExtensionDirs(bundled, bundled).isEmpty())
    }

    @Test
    fun `compares numerically, not as text`() {
        // The trap: "5.9.0" sorts after "5.37.0" as text, and gitlens versions are
        // dates where the same trap reads as a year-long jump backwards.
        val present = bundled + listOf(
            "PKief.material-icon-theme-5.9.0",
            "eamodio.gitlens-2026.11.1505",
        )
        assertEquals(listOf("PKief.material-icon-theme-5.9.0"),
            supersededExtensionDirs(present, bundled))
    }

    @Test
    fun `handles an id that itself contains a dash`() {
        val present = bundled + "vscodroid.vscodroid-welcome-0.9.0"
        assertEquals(listOf("vscodroid.vscodroid-welcome-0.9.0"),
            supersededExtensionDirs(present, bundled))
    }

    @Test
    fun `leaves alone a name that does not split into a bundled id`() {
        // None of these three splits into an id the bundled set knows, so what
        // they guard is that lookup and not the version comparison below it,
        // which they never reach. The case after this one covers that.
        val present = bundled + listOf(
            "ms-python.python-2026.4.0-rc1",
            "extensions.json",
            "PKief.material-icon-theme",
        )
        assertTrue(supersededExtensionDirs(present, bundled).isEmpty())
    }

    @Test
    fun `a non-numeric component is not treated as zero`() {
        // The case above never reaches the numeric guard. Its three names are
        // dropped earlier, by the `current[id] ?: return@filter false` lookup:
        // "extensions.json" and the un-versioned directory do not split into an
        // id the bundled set knows, and "ms-python.python-2026.4.0-rc1" splits
        // on its LAST dash, so its id is "ms-python.python-2026.4.0" -- also
        // unknown. Measured: making a non-numeric component parse as 0 instead
        // of refusing left that test green.
        //
        // This one uses an id the bundled set does know AND a version with no
        // dash in it, so split() keeps the id intact and the version really is
        // compared. Both matter: split cuts at the LAST dash, so "5.35.0-rc1"
        // would leave the id as "PKief.material-icon-theme-5.35.0", which the
        // bundled set does not contain -- dropped before the guard again.
        //
        // Scored with "x" as 0, 5.35.x looks older than the bundled 5.37.0 and
        // gets deleteRecursively()d, taking a directory the app did not ship.
        val present = bundled + "PKief.material-icon-theme-5.35.x"
        assertTrue(
            supersededExtensionDirs(present, bundled).isEmpty(),
            "a version with a non-numeric component must be left alone, not scored as 0",
        )
    }

    @Test
    fun `treats a missing trailing component as zero`() {
        val present = bundled + "ms-python.python-2026.4"
        assertTrue(supersededExtensionDirs(present, bundled).isEmpty())
    }

    // retiredOwnExtensionDirs: the same one-directional risk applies, sharpened
    // by the publisher filter — nothing outside vscodroid.* may ever be named.

    @Test
    fun `retires an own extension that is no longer bundled`() {
        val present = bundled + "vscodroid.vscodroid-github-auth-1.0.0"
        assertEquals(listOf("vscodroid.vscodroid-github-auth-1.0.0"),
            retiredOwnExtensionDirs(present, bundled))
    }

    @Test
    fun `never retires marketplace extensions or bundled own ones`() {
        val present = bundled + listOf(
            "rust-lang.rust-analyzer-0.3.2000",
            "vscodroid.vscodroid-welcome-0.9.0", // old version: superseded's job
            "extensions.json",
        )
        assertTrue(retiredOwnExtensionDirs(present, bundled).isEmpty())
    }
}
