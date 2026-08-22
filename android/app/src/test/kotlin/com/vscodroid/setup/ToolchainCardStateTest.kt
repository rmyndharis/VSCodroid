package com.vscodroid.setup

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.StorageManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for [ToolchainCardState], the data half of the toolchain cards.
 *
 * A card is the only way a user installs or removes a toolchain, and every way
 * of getting it wrong is quiet: the button says Install for something already on
 * disk, or says nothing at all where the only download button should be. None of
 * that is reachable through the adapter, which needs a RecyclerView and an
 * inflated layout; all of it is reachable from here.
 *
 * The two screens differ in what they may show, which is why the mode is a
 * constructor argument and not a flag consulted at draw time: a picker card that
 * grew a manager card's button would take the tap that ticks it, and first-run
 * setup would then download nothing.
 */
class ToolchainCardStateTest {

    private val go = "toolchain_go"
    private val ruby = "toolchain_ruby"

    private fun manager() = ToolchainCardState(ToolchainCardMode.MANAGER)
    private fun picker() = ToolchainCardState(ToolchainCardMode.PICKER)

    @Nested
    inner class InstalledSet {

        @Test
        fun `the short names the install record holds are recognised as installed`() {
            // ToolchainManager.getInstalledToolchains() reads the "name" field of
            // each pack manifest, which the download scripts write as "go",
            // "ruby", "java". The cards are keyed by pack name. Matching the two
            // spellings literally leaves every installed toolchain offering
            // Install, and taking that offer re-downloads a payload the user
            // already has.
            val state = manager()
            state.setInstalled(listOf("go"))
            assertEquals(ToolchainAction.REMOVE, state.card(go).action)
            assertEquals(ToolchainBadge.INSTALLED, state.card(go).badge)
        }

        @Test
        fun `pack names are recognised too, in the same list`() {
            val state = manager()
            state.setInstalled(listOf("toolchain_go", "ruby"))
            assertEquals(ToolchainAction.REMOVE, state.card(go).action)
            assertEquals(ToolchainAction.REMOVE, state.card(ruby).action)
        }

        @Test
        fun `a later list replaces the earlier one rather than adding to it`() {
            // ToolchainActivity calls this again in onStart, so a removal
            // performed anywhere else has to be able to take a toolchain out.
            // Merging would leave Remove on a card with nothing behind it.
            val state = manager()
            state.setInstalled(listOf("go", "ruby"))
            state.setInstalled(listOf("ruby"))

            assertEquals(ToolchainAction.INSTALL, state.card(go).action)
            assertEquals(ToolchainAction.REMOVE, state.card(ruby).action)
        }

        @Test
        fun `a toolchain nothing has said anything about offers Install`() {
            val card = manager().card(go)
            assertEquals(ToolchainAction.INSTALL, card.action)
            assertEquals(ToolchainBadge.NONE, card.badge)
            assertNull(card.progressPercent, "an idle card carries no progress bar")
        }
    }

    @Nested
    inner class DownloadReports {

        @Test
        fun `every state the download can sit in offers Cancel, not a second Install`() {
            // Only DOWNLOADING arrives promptly. PENDING and WAITING_FOR_WIFI can
            // last minutes, and TRANSFERRING covers the unpack at the end. A card
            // showing Install through any of them invites a second fetch of the
            // same pack.
            for (status in listOf(
                AssetPackStatus.DOWNLOADING,
                AssetPackStatus.PENDING,
                AssetPackStatus.WAITING_FOR_WIFI,
                AssetPackStatus.TRANSFERRING,
            )) {
                val state = manager()
                state.updateState(go, status, 10)
                assertEquals(
                    ToolchainAction.CANCEL, state.card(go).action,
                    "status $status left the card without a way to stop the download",
                )
                assertEquals(ToolchainBadge.NONE, state.card(go).badge, "status $status")
            }
        }

        /**
         * The one status that had been left out of that list.
         *
         * Play emits REQUIRES_USER_CONFIRMATION for a pack it will not start
         * without the user answering a system dialog, and then waits: dismissing
         * the dialog leaves the pack queued for as long as the user leaves it.
         * The card fell through to a plain Install for all of that time, which
         * both says nothing is happening and withholds the only route this app
         * has to `assetPackManager.cancel`. `isTerminalPackStatus`, the project's
         * other predicate for the same question, has always counted it unsettled.
         *
         * NEGATIVE CONTROL: take REQUIRES_USER_CONFIRMATION back out of
         * `ToolchainCardState.IN_FLIGHT` and this goes red. Measured.
         */
        @Test
        fun `a pack waiting for the confirmation dialog still offers Cancel`() {
            val state = manager()
            state.updateState(go, AssetPackStatus.REQUIRES_USER_CONFIRMATION, 0)

            val card = state.card(go)
            assertEquals(
                ToolchainAction.CANCEL, card.action,
                "a pack Play is holding for confirmation offered no way to stop it",
            )
            assertEquals(ToolchainBadge.NONE, card.badge)
        }

        @Test
        fun `a download in progress shows the percent last reported for it`() {
            val state = manager()
            state.updateState(go, AssetPackStatus.DOWNLOADING, 42)
            assertEquals(42, state.card(go).progressPercent)

            state.updateState(go, AssetPackStatus.DOWNLOADING, 77)
            assertEquals(77, state.card(go).progressPercent, "the bar has to follow the report")
        }

        @Test
        fun `a failed download offers Retry and says so`() {
            val state = manager()
            state.updateState(go, AssetPackStatus.FAILED, 0)

            val card = state.card(go)
            assertEquals(ToolchainAction.RETRY, card.action)
            assertEquals(ToolchainBadge.FAILED, card.badge)
            assertNull(card.progressPercent, "a stopped download must not leave a bar behind")
        }

        @Test
        fun `a completed download turns the card into an installed one at once`() {
            // Nothing re-reads the install record between the report and the next
            // draw, so a card that waits for setInstalled reads Install for a
            // toolchain that has just finished installing.
            val state = manager()
            state.updateState(go, AssetPackStatus.COMPLETED, 100)

            val card = state.card(go)
            assertEquals(ToolchainAction.REMOVE, card.action)
            assertEquals(ToolchainBadge.INSTALLED, card.badge)
            assertNull(card.progressPercent)
        }

        @Test
        fun `a cancelled or uninstalled pack goes back to offering Install`() {
            // NOT_INSTALLED is what uninstallLocked reports when a toolchain has
            // actually been removed, and what Play reports once a pack is gone.
            // Cancelling reports CANCELED instead, because it removes nothing; see
            // ToolchainActivity. A card stuck on Remove leaves no way to install
            // the toolchain again.
            val state = manager()
            state.setInstalled(listOf("go"))
            state.updateState(go, AssetPackStatus.NOT_INSTALLED, 0)

            val card = state.card(go)
            assertEquals(ToolchainAction.INSTALL, card.action)
            assertEquals(ToolchainBadge.NONE, card.badge)
        }

        /**
         * Characterisation, not a guard, and it matters which: this passes both
         * before and after the change it was written for, because the defect was
         * `ToolchainActivity` choosing NOT_INSTALLED for a cancel rather than
         * anything here. What it records is the meaning CANCELED has to keep for
         * that choice to be right. The guard is in ToolchainCancelWiringTest.
         */
        @Test
        fun `a cancelled download leaves an installed toolchain installed`() {
            // Cancelling deletes nothing: it flips the download token, asks Play
            // to cancel and hands the delivery back. The toolchain is still on
            // disk, so the card owes the user its Remove button.
            val state = manager()
            state.setInstalled(listOf("ruby"))
            state.updateState(ruby, AssetPackStatus.DOWNLOADING, 30)
            state.updateState(ruby, AssetPackStatus.CANCELED, 0)

            val card = state.card(ruby)
            assertEquals(ToolchainAction.REMOVE, card.action)
            assertEquals(ToolchainBadge.INSTALLED, card.badge)
            assertNull(card.progressPercent, "a cancelled download must leave no bar behind")
        }

        /** The other half of the case above, and characterisation for the same reason. */
        @Test
        fun `a cancelled first download goes back to offering Install`() {
            val state = manager()
            state.updateState(go, AssetPackStatus.DOWNLOADING, 30)
            state.updateState(go, AssetPackStatus.CANCELED, 0)

            val card = state.card(go)
            assertEquals(ToolchainAction.INSTALL, card.action)
            assertEquals(ToolchainBadge.NONE, card.badge)
        }

        @Test
        fun `a report about one toolchain leaves the other cards alone`() {
            // The listener is registered for the app, not for one fetch, so
            // reports for several packs reach the same adapter. Sharing one
            // status between the cards would put Retry on a toolchain that never
            // failed, and hide Install on the one the user is looking at.
            val state = manager()
            state.setInstalled(listOf("ruby"))
            state.updateState(go, AssetPackStatus.FAILED, 0)

            assertEquals(ToolchainAction.RETRY, state.card(go).action)
            assertEquals(ToolchainAction.REMOVE, state.card(ruby).action)
        }

        @Test
        fun `a download still running hides the installed badge behind Cancel`() {
            // Reinstall over an existing copy: while it runs the only useful
            // button is the one that stops it.
            val state = manager()
            state.setInstalled(listOf("go"))
            state.updateState(go, AssetPackStatus.DOWNLOADING, 5)

            assertEquals(ToolchainAction.CANCEL, state.card(go).action)
            assertEquals(5, state.card(go).progressPercent)
        }

        @Test
        fun `a failed report outranks the install record behind it`() {
            // A toolchain already on disk can still be carrying a failed
            // report: a reinstall over it that dies partway, or a copy that
            // wrote the install record and then threw on its way out. The card
            // shows one of the two facts, and the failure is the one the user
            // can still act on.
            val state = manager()
            state.setInstalled(listOf("go"))
            state.updateState(go, AssetPackStatus.FAILED, 0)

            val card = state.card(go)
            assertEquals(ToolchainAction.RETRY, card.action)
            assertEquals(ToolchainBadge.FAILED, card.badge)
        }
    }

    @Nested
    inner class DownloadsThisScreenWasNotTold {

        /**
         * A download reports only to the manager that started it, and a manager
         * belongs to whoever built it. Two ordinary things build a second one:
         * rotating this screen, which has no `configChanges` and so is destroyed
         * and rebuilt, and opening it from the launcher shortcut while the
         * first-run queue is still working. In both, the cards start with no
         * reports at all and fell through to Install for a pack that was already
         * downloading, offering no progress and no way to stop a 56 MB transfer.
         */
        @Test
        fun `a download begun elsewhere is drawn as running, with a way to stop it`() {
            val state = manager()
            state.setDownloading(mapOf(ruby to 42))

            val card = state.card(ruby)
            assertEquals(
                ToolchainAction.CANCEL, card.action,
                "a download already running was offered as a fresh Install",
            )
            assertEquals(42, card.progressPercent)
        }

        @Test
        fun `this screen's own report wins over what it was told at the start`() {
            // The report keeps arriving; the seed is a single snapshot taken when
            // the screen opened. Letting the stale one win freezes the bar.
            val state = manager()
            state.setDownloading(mapOf(ruby to 42))
            state.updateState(ruby, AssetPackStatus.DOWNLOADING, 77)

            assertEquals(77, state.card(ruby).progressPercent)
        }

        @Test
        fun `a download that has since finished stops being drawn as running`() {
            // Its end is not reported here either, so a seed that only ever
            // accumulated would leave the card offering Cancel for a transfer
            // that is over, and Cancel is destructive.
            val state = manager()
            state.setDownloading(mapOf(ruby to 42))
            state.setDownloading(emptyMap())
            state.setInstalled(listOf("ruby"))

            assertEquals(ToolchainAction.REMOVE, state.card(ruby).action)
            assertNull(state.card(ruby).progressPercent)
        }

        @Test
        fun `a completed report ends the seed as well`() {
            val state = manager()
            state.setDownloading(mapOf(ruby to 42))
            state.updateState(ruby, AssetPackStatus.COMPLETED, 100)

            val card = state.card(ruby)
            assertEquals(ToolchainAction.REMOVE, card.action)
            assertEquals(ToolchainBadge.INSTALLED, card.badge)
            assertNull(card.progressPercent)
        }

        @Test
        fun `the picker draws no download at all`() {
            // First-run cards carry a tick and nothing else. A Cancel button
            // there would take the tap that selects the toolchain.
            val state = picker()
            state.setDownloading(mapOf(ruby to 42))

            val card = state.card(ruby)
            assertNull(card.action)
            assertNull(card.progressPercent)
        }
    }

    @Nested
    inner class SizeLine {

        private val rubyInfo = ToolchainRegistry.find("toolchain_ruby")

        /**
         * The card used to format these itself, dividing by 1,000,000, while the
         * low-storage warning, the first-run pre-flight and the storage
         * breakdown all divide by 1,048,576 and write the same "MB". So the same
         * quantity read 4.9% differently depending on which screen the user was
         * on, with nothing to tell them which reading they were looking at.
         */
        @Test
        fun `the card quotes sizes the way the rest of the app quotes them`() {
            val info = requireNotNull(rubyInfo) { "the registry no longer lists Ruby" }
            val figures = manager().sizeFigures(info)

            assertEquals(StorageManager.formatSize(info.downloadSize), figures.download)
            assertEquals(StorageManager.formatSize(info.estimatedSize), figures.installed)
        }

        /**
         * The convention itself, pinned separately from the agreement above: if
         * both sides were moved to 1,000,000 together the case above would still
         * pass, and every free-space figure in the app would then be quoting a
         * different "MB" from the one this card quotes.
         */
        @Test
        fun `a hundred mebibytes is what the card calls a hundred MB`() {
            // A hundred and not one: 1,048,576 divided by 1,000,000 rounds to
            // "1.0 MB" as well, so a one-mebibyte case reads as a test of the
            // convention while agreeing with both of them.
            val info = ToolchainRegistry.ToolchainInfo(
                packName = "toolchain_test",
                displayName = "Test",
                shortLabel = "Test",
                descriptionRes = 0,
                estimatedSize = 100L * 1_048_576,
                downloadSize = 100L * 1_048_576,
            )

            assertEquals("100.0 MB", manager().sizeFigures(info).installed)
        }
    }

    @Nested
    inner class Positions {

        @Test
        fun `a report names the position of the card it changed`() {
            val state = manager()
            val expected = ToolchainRegistry.available.indexOfFirst { it.packName == ruby }
            assertTrue(expected >= 0, "the registry no longer lists $ruby")
            assertEquals(expected, state.updateState(ruby, AssetPackStatus.DOWNLOADING, 1))
        }

        @Test
        fun `a report for a pack no card shows names no position`() {
            // The adapter turns this into notifyItemChanged, so anything other
            // than a refusal repaints a card belonging to a different toolchain.
            assertEquals(-1, manager().updateState("toolchain_haskell", AssetPackStatus.FAILED, 0))
            assertEquals(-1, manager().positionOf("toolchain_haskell"))
        }
    }

    @Nested
    inner class PickerMode {

        @Test
        fun `the picker offers no buttons, whatever the download reports say`() {
            // The picker card's whole job is to take a tap and tick itself. A
            // button drawn over it takes that tap instead, and Continue then
            // starts no downloads at all.
            val state = picker()
            state.setInstalled(listOf("go"))
            state.updateState(go, AssetPackStatus.DOWNLOADING, 50)

            val card = state.card(go)
            assertNull(card.action, "a picker card must carry no action button")
            // The badge is the one thing the picker does say: it states that the
            // toolchain is already installed, and still offers no control at all.
            assertEquals(ToolchainBadge.INSTALLED, card.badge)
            assertNull(card.progressPercent)
        }

        @Test
        fun `an installed toolchain is stated rather than offered`() {
            // The picker is offered on any launch that has not answered it, not
            // only on the launch that unpacked the app, so a toolchain installed
            // from the Toolchains screen can be on disk before this screen is ever
            // shown. Drawn as a plain offer, ticking it spends the whole transfer
            // and the whole copy again.
            val state = picker()
            state.setInstalled(listOf("ruby"))

            val card = state.card(ruby)
            assertEquals(ToolchainBadge.INSTALLED, card.badge)
            assertNull(card.action, "a picker card must carry no action button")
            assertNull(card.progressPercent)
            assertFalse(card.selected, "an installed toolchain starts unticked")
        }

        @Test
        fun `an installed toolchain cannot be ticked`() {
            // Continue starts a fresh install for everything ticked and install()
            // has no already-installed branch, so a tick that survived here is a
            // second download of bytes the user already has, refused for space on
            // a tight device because the pre-flight asks for twice the tree.
            val state = picker()
            state.setInstalled(listOf("ruby"))

            state.toggleSelection(ruby)

            assertEquals(emptySet<String>(), state.selectedPackNames())
            assertFalse(state.card(ruby).selected)
        }

        @Test
        fun `a toolchain that is not installed still ticks`() {
            // The positive control for the case above: a refusal that refused
            // everything would satisfy it while making the screen useless.
            val state = picker()
            state.setInstalled(listOf("ruby"))

            state.toggleSelection("toolchain_java")

            assertEquals(setOf("toolchain_java"), state.selectedPackNames())
        }

        @Test
        fun `tapping a card twice leaves it unticked`() {
            // Untick is the only way out of a 179 MB download the user has
            // changed their mind about; there is no other control on that screen.
            val state = picker()
            state.toggleSelection(go)
            assertTrue(state.card(go).selected)
            assertEquals(setOf(go), state.selectedPackNames())

            state.toggleSelection(go)
            assertFalse(state.card(go).selected, "a second tap has to untick the card")
            assertEquals(emptySet<String>(), state.selectedPackNames())
        }

        @Test
        fun `ticking one card does not tick the others`() {
            val state = picker()
            state.toggleSelection(go)
            assertFalse(state.card(ruby).selected)
        }

        @Test
        fun `the selection handed out does not change under its holder`() {
            // SplashActivity reads the set, marks the picker shown, and only then
            // starts the downloads. A live view of the selection would let a tap
            // arriving in between change what gets downloaded.
            val state = picker()
            state.toggleSelection(go)
            val handedOut = state.selectedPackNames()

            state.toggleSelection(ruby)

            assertEquals(setOf(go), handedOut)
        }
    }
}

/**
 * The boundary the tests above rely on.
 *
 * They pin what each download status does to a card, and they pin it in
 * [ToolchainCardState]. Deciding it again inside the adapter would put it back
 * where no test can run it: binding a card needs a RecyclerView and an inflated
 * layout, and this project has no Robolectric. That failure is silent, since
 * every test in this file would stay green while the screen followed different
 * rules.
 *
 * Status names are the tell, so that is what this reads. The adapter is welcome
 * to know about actions and badges; it is not welcome to know what
 * AssetPackStatus.FAILED means.
 */
class ToolchainAdapterBoundaryTest {

    private val source = File("src/main/kotlin/com/vscodroid/setup/ToolchainPickerAdapter.kt")

    /**
     * Comments are stripped first: this file is named in the adapter's KDoc, and
     * a prose mention of a status must not be read as a decision.
     */
    private fun code(): String {
        check(source.isFile) {
            "ToolchainPickerAdapter.kt not found at ${source.absolutePath}; this test would " +
                "otherwise pass by looking at nothing"
        }
        return source.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
    }

    @Test
    fun `the adapter does not decide anything from a download status`() {
        val body = code()
        assertTrue(body.length > 1000, "read ${body.length} characters, which is not the adapter")
        assertFalse(
            body.contains("AssetPackStatus"),
            "the adapter is reading download statuses again, so what a status means to a card " +
                "is decided somewhere no unit test can reach it",
        )
    }
}
