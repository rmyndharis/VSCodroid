package com.vscodroid.setup

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.SourceScan
import com.vscodroid.handedToAnotherInstall
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the first-run progress screen says when a pack is handed to an install
 * that is already running.
 *
 * `ToolchainManager` reports `UNKNOWN` when it declines a duplicate: the pack is
 * held by another install in this process, which is the opposite of a failure.
 * That status reaches the screen through the same callback a Play state does, and
 * the `else` arm painted every unnamed status "Failed", so the user was told an
 * install had failed while it was succeeding one screen over.
 *
 * The screen reaches this without anyone trying: two managers are live on every
 * launch, since the delivered-pack reconcile runs on one while Continue queues
 * installs on another.
 */
class SplashHandoffTest {

    @Test
    fun `a declined duplicate is not a failure`() {
        assertTrue(handedToAnotherInstall(AssetPackStatus.UNKNOWN, true))
    }

    @Test
    fun `UNKNOWN with the pack held by nobody is still a failure`() {
        // Whether Play itself ever emits UNKNOWN through a listener is not
        // established here. The row keeps saying "Failed" for it either way,
        // because nothing in this process claims the pack.
        assertFalse(handedToAnotherInstall(AssetPackStatus.UNKNOWN, false))
    }

    /**
     * The half that keeps a real failure visible.
     *
     * The flag is process-wide, so it reads true whenever ANY install is running,
     * including one of a different pack. Without the status test a genuine FAILED
     * arriving in that window would be swallowed and the row would sit on
     * "Installing..." saying nothing, which is worse than the wrong word.
     */
    @Test
    fun `a real failure is a failure however the flag reads`() {
        listOf(
            AssetPackStatus.FAILED,
            AssetPackStatus.CANCELED,
            AssetPackStatus.NOT_INSTALLED,
        ).forEach {
            assertFalse(handedToAnotherInstall(it, true), "status $it was swallowed")
            assertFalse(handedToAnotherInstall(it, false), "status $it was swallowed")
        }
    }

    /**
     * The wiring the case above cannot reach.
     *
     * The predicate is called from an Activity method, and this project has no
     * Robolectric, so deleting the call leaves every assertion above green. Read
     * out of the source through [SourceScan], following the source-scanning cases
     * this suite already keeps for `SplashActivity`; what it proves is that the
     * call is written, not that a screen renders anything.
     */
    @Test
    fun `handleDownloadState asks whether the pack was handed to another install`() {
        val source = SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt")
        val raw = SourceScan.body(source, "private fun handleDownloadState(")
        // Bounded rather than trusted: the brace walk is comment- and
        // string-unaware, so an extraction that ran to the end of the file would
        // find both names for entirely the wrong reason.
        check(raw.length in 500..9000) {
            "extracted ${raw.length} characters of handleDownloadState, which means the " +
                "extraction is wrong rather than the code"
        }
        val body = SourceScan.withoutComments(raw)

        assertTrue(
            body.contains("handedToAnotherInstall("),
            "handleDownloadState no longer asks whether the pack was handed to another " +
                "install, so a decline is painted \"Failed\" while that install succeeds",
        )
        assertTrue(
            body.contains("packIsBeingInstalled("),
            "the predicate is called with something other than the process-wide claim, " +
                "which is the only thing that knows another install holds the pack",
        )
    }
}
