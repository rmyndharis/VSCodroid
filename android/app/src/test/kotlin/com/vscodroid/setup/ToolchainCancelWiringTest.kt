package com.vscodroid.setup

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That cancelling a download does not claim the toolchain was uninstalled.
 *
 * `NOT_INSTALLED` is this app's word for a completed uninstall: `uninstallLocked`
 * reports it at the end of a removal, and [ToolchainCardState] acts on it by
 * dropping the pack from its installed set. Cancelling removes nothing. It flips
 * the download token, asks Play to cancel and hands the delivery back; nothing
 * under `usr/` is touched and nothing leaves `toolchains.json`. Reporting the
 * uninstall word for it hid the Remove button of an installed toolchain whose
 * re-download the user had just stopped, until the screen was closed and
 * reopened. `ToolchainManager` already made the same choice, deliberately, the
 * one other place it had to name a status for something that is not an uninstall,
 * and picked `UNKNOWN` for exactly this reason.
 *
 * ToolchainCardStateTest characterises what each of the two constants means to a
 * card. Neither of those cases can fail for this defect: the wrong constant is
 * chosen in the Activity, which no JVM test here can build. This reads that
 * choice out of the source, which is the only place it is visible.
 */
class ToolchainCancelWiringTest {

    private val source = File("src/main/kotlin/com/vscodroid/ToolchainActivity.kt")

    /**
     * Comments dropped first. The comment beside the call explains at length why
     * `NOT_INSTALLED` is wrong there and names it repeatedly, so a search over raw
     * text would fail on the explanation and pass on nothing.
     */
    private fun code(): String = SourceScan.withoutComments(SourceScan.read(source.path))

    @Test
    fun `cancelling does not claim the toolchain was uninstalled`() {
        val body = code()

        assertTrue(
            body.length > 1_000,
            "read ${body.length} characters, which is not ToolchainActivity",
        )
        assertTrue(
            body.contains("ToolchainAction.CANCEL"),
            "the Cancel branch is gone from ToolchainActivity, so the assertions below " +
                "are vacuous rather than satisfied",
        )
        assertTrue(
            body.contains("AssetPackStatus.CANCELED"),
            "cancelling reports something other than CANCELED, and CANCELED is the only " +
                "status here that no card branch reads as a completed uninstall",
        )
        assertFalse(
            body.contains("NOT_INSTALLED"),
            "ToolchainActivity reports NOT_INSTALLED, which the card state reads as a " +
                "finished uninstall: cancelling a re-download then hides the Remove " +
                "button of a toolchain that is still on disk",
        )
    }

    /**
     * The end of a cancelled download is reported later: Play's CANCELED ends the manager's request for the pack by
     * name, and a cancelled HTTP transfer holds the pack until it stops. A Cancel branch that draws Install at once
     * lets an Install tapped before that end have its request ended, or be refused with "already being installed"
     * (ToolchainCardStateTest has the card's half). So the branch asks the manager whether an end is to be reported
     * and, when one is, leaves the card offering nothing until it is; the screen's start ends any wait it missed.
     */
    @Test
    fun `a cancel whose end is still to be reported leaves the card waiting for it`() {
        val source = code()
        val branch = com.vscodroid.SourceScan.body(source, "ToolchainAction.CANCEL ->")

        assertTrue(
            branch.contains("adapter.setCancelling(packName, transferId)"),
            "the Cancel branch draws the card before Play has reported the cancel it accepted, so an Install " +
                "tapped in between has its request ended by that report:\n$branch",
        )
        assertFalse(
            Regex("""(?m)^\s*toolchainManager\.cancel\(packName\)\s*$""").containsMatchIn(branch),
            "the Cancel branch drops the manager's answer, so it cannot tell a cancel Play will report:\n$branch",
        )
        assertTrue(
            Regex("""(else|if\s*\(\s*!\s*\w+\s*\))\s*\{?\s*adapter\.updateState\(packName,\s*AssetPackStatus\.CANCELED""")
                .containsMatchIn(branch),
            "the card is drawn as cancelled at the tap also when Play's report is still to come:\n$branch",
        )
        assertTrue(
            com.vscodroid.SourceScan.body(source, "override fun onStart()").contains("adapter.endCancelWaits()"),
            "a cancel's end the screen did not hear holds its card past the screen's next start",
        )
    }
}
