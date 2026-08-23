package com.vscodroid.setup

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.StorageManager

/** Which screen the cards are on. */
enum class ToolchainCardMode {
    /** First-run selection: tapping a card ticks it. */
    PICKER,

    /** Settings screen: each card offers one action. */
    MANAGER,
}

/** What a card's action button offers. */
enum class ToolchainAction { INSTALL, REMOVE, CANCEL, RETRY }

/** What a card's status line says. */
enum class ToolchainBadge { NONE, INSTALLED, FAILED }

/**
 * The two sizes a card quotes, already formatted.
 *
 * Two fields rather than one, because they answer different questions and the
 * card was showing only the second: what the download costs on mobile data, and
 * what it occupies once unpacked.
 */
data class SizeFigures(val download: String, val installed: String)

/**
 * Everything one toolchain card shows, decided without a View.
 *
 * The nullable fields are absences rather than defaults: no progress bar at all
 * is not the same card as a progress bar reading zero, and no button at all is
 * not the same card as a disabled one.
 */
data class ToolchainCard(
    /** PICKER only: the card is ticked and outlined. */
    val selected: Boolean = false,
    val badge: ToolchainBadge = ToolchainBadge.NONE,
    /** Progress bar percentage, or null when the card carries no progress bar. */
    val progressPercent: Int? = null,
    /** The action button's offer, or null when the card carries no button. */
    val action: ToolchainAction? = null,
)

/**
 * The toolchain card's data logic, with no Android types in it.
 *
 * It holds what the two screens know about each toolchain (ticked, installed,
 * last download report) and turns that into the card to draw. Kept apart from
 * [ToolchainPickerAdapter] so the decisions can be asserted without a
 * RecyclerView: which report counts as "still downloading", which of installed
 * and failed wins, and whether a name from the install record matches a pack
 * name at all. Getting any of those wrong offers "Install" for something already
 * on disk, or hides the only button that starts a download, and both are silent.
 */
class ToolchainCardState(private val mode: ToolchainCardMode) {

    /** The cards, in the order they are drawn. */
    val items: List<ToolchainRegistry.ToolchainInfo> = ToolchainRegistry.available

    private val selected = mutableSetOf<String>()
    private val installed = mutableSetOf<String>()
    private val downloadStatus = mutableMapOf<String, Int>()
    private val downloadPercent = mutableMapOf<String, Int>()

    /**
     * Downloads the process knows about that this screen was never told of, as
     * pack name to percentage.
     *
     * Kept apart from [downloadStatus] rather than folded into it, because the
     * two have opposite lifetimes. A report is this screen's own and stays until
     * the next one replaces it; a seed is a snapshot of what some other manager
     * is doing and is replaced wholesale by the next [setDownloading]. Folding a
     * seed into [downloadStatus] would leave a card offering Cancel for a
     * download that finished, since nothing ever reports its end here.
     *
     * This is the HTTP half only. What Play is fetching goes through
     * [updateState] instead, because one of the states a pack can be recovered
     * in is Play waiting on the cellular-data question, and that has to be put
     * rather than drawn. The cost of that route is exactly the trap above, a
     * report whose end no listener on this screen hears, and [clearSettledDownload]
     * is what ends it the next time Play is asked.
     */
    private val seededDownloads = mutableMapOf<String, Int>()

    /**
     * Packs the user has confirmed a removal for, until something reports back.
     *
     * An uninstall is queued on the same single-thread executor a download
     * occupies for the whole of a transfer, an extraction and a ~155 MB copy, so
     * confirming "Remove Ruby?" while Java 17 downloads does nothing for
     * minutes: the dialog closed, the card went on offering Remove, and tapping
     * it again queued a second removal that would also do nothing. Taking the
     * button away is the honest reading of what the user asked for and what has
     * happened so far, and it is what makes the second tap impossible.
     *
     * Cleared by any report about the pack, which every route out of the
     * uninstall now makes: the removal itself, a record that could not be
     * written, a record that never named the toolchain, and a decline.
     */
    private val removing = mutableSetOf<String>()

    /** Position of [packName] among [items], or -1 when no card shows it. */
    fun positionOf(packName: String): Int = items.indexOfFirst { it.packName == packName }

    /**
     * Replaces what is on disk with [packNames].
     *
     * The install record names toolchains the short way ("go"), the cards name
     * them the pack way ("toolchain_ruby"), so each name is normalised on the way
     * in. Both spellings are accepted because both reach here.
     */
    fun setInstalled(packNames: Collection<String>) {
        installed.clear()
        installed.addAll(packNames.map { name ->
            if (name.startsWith("toolchain_")) name else "toolchain_$name"
        })
    }

    /**
     * Replaces what the process is downloading with [percentByPack].
     *
     * A download reports its progress to the manager that began it and to
     * nothing else, so a screen built after the transfer started hears nothing:
     * rotating the toolchain screen rebuilds it, and so does opening it while
     * the first-run queue is still working. With an empty state the card fell
     * through to Install for a pack that was already downloading, offering no
     * progress and, worse, no Cancel, which is the only way to stop a 56 MB
     * transfer once it is running.
     *
     * Replaces rather than adds, so a download that has since finished stops
     * being drawn as running. That matters because its end is not reported here
     * either.
     */
    fun setDownloading(percentByPack: Map<String, Int>) {
        seededDownloads.clear()
        seededDownloads.putAll(percentByPack)
    }

    /**
     * Folds one download report in and returns the position whose card it
     * changed, or -1 for a pack no card shows.
     *
     * COMPLETED and NOT_INSTALLED also move the pack in and out of the installed
     * set, so a card is right immediately rather than after the next
     * [setInstalled].
     */
    fun updateState(packName: String, status: Int, percent: Int): Int {
        downloadStatus[packName] = status
        downloadPercent[packName] = percent
        // Any answer at all ends the wait a confirmed removal put the card in.
        // Which answer it was decides the card below on its own merits.
        removing.remove(packName)

        if (status == AssetPackStatus.COMPLETED) {
            installed.add(packName)
        }
        if (status == AssetPackStatus.NOT_INSTALLED) {
            installed.remove(packName)
        }
        return positionOf(packName)
    }

    /**
     * MANAGER: drops a still-running report for [packName] because the download
     * it describes has settled, and returns the position whose card it changed,
     * or -1 when nothing was dropped.
     *
     * `ToolchainActivity.onStart` asks Play what it is already fetching and
     * feeds the unsettled answers through [updateState], which is a report like
     * any other and stays until the next one replaces it. For a download this
     * screen did not start, no next one arrives: `onStop` hands the Play Core
     * subscription back whenever nothing is outstanding, so a pack that
     * finished while the user was in the editor was heard by nobody here. The
     * card was left on a frozen percentage offering Cancel, over a toolchain
     * that is installed, for the life of the Activity. Play's own answer at the
     * next `onStart` is the report that ends it.
     *
     * Only a report that says the download is still going is dropped. FAILED is
     * settled too and Play answers it for the same pack, but it is this
     * screen's own word on a download that ended and is the whole of what the
     * Retry button and the badge beside it rest on.
     */
    fun clearSettledDownload(packName: String): Int {
        val status = downloadStatus[packName]
        if (status !in IN_FLIGHT) return -1
        downloadStatus.remove(packName)
        downloadPercent.remove(packName)
        return positionOf(packName)
    }

    /**
     * MANAGER: records that a removal of [packName] has been asked for and has
     * not answered yet. See [removing].
     */
    fun setRemoving(packName: String) {
        removing.add(packName)
    }

    /** PICKER: ticks [packName] if it is not ticked, unticks it if it is. */
    fun toggleSelection(packName: String) {
        // An installed toolchain is not a choice. Continue starts a fresh install
        // for everything ticked, and install() has no already-installed branch, so
        // a tick here spends the whole transfer and the whole copy again for bytes
        // that are on disk, against a pre-flight that asks for twice the unpacked
        // tree. Refused in the model as well as in the binding, because this half
        // is the one a test can reach.
        if (packName in installed) return
        if (!selected.remove(packName)) selected.add(packName)
    }

    /** A snapshot of what is ticked, safe to hold while the user carries on tapping. */
    fun selectedPackNames(): Set<String> = selected.toSet()

    /**
     * The two figures the size line shows, both spelled the way the rest of the
     * app spells them.
     *
     * Here rather than in [ToolchainPickerAdapter] for the reason the rest of
     * this class is: it is part of what the card says, and it is the part that
     * was wrong without anything failing. The registry used to format these
     * itself, dividing by 1,000,000, while the low-storage warning, the
     * first-run pre-flight, the storage breakdown and the device-folder screen
     * all divide by 1,048,576 and write the same "MB". The card therefore
     * quoted a toolchain 4.9% larger than the number the user had just read on
     * the screen they came from.
     *
     * The convention kept is 1,048,576, for two reasons. It is what `du -h` and
     * `df -h` report in this app's own terminal, which is where its users check
     * a size, and it is what every other figure here already uses, so it is the
     * one that can be made consistent without changing what a free-space gate
     * appears to promise. Nothing computes with these strings: every pre-flight
     * works in raw bytes.
     */
    fun sizeFigures(info: ToolchainRegistry.ToolchainInfo): SizeFigures = SizeFigures(
        download = StorageManager.formatSize(info.downloadSize),
        installed = StorageManager.formatSize(info.estimatedSize),
    )

    /** The card to draw for [packName] on this screen. */
    fun card(packName: String): ToolchainCard = when (mode) {
        // The badge, and only the badge: the picker still carries no button and no
        // progress bar. What it has to be able to say is that a toolchain is
        // already on disk, because the screen is offered on any launch that has
        // not answered it rather than only on the launch that just unpacked the
        // app. An install whose first run was interrupted reaches the editor with
        // the preference unset and the launcher shortcut published, so it can
        // install from ToolchainActivity long before this screen is offered.
        ToolchainCardMode.PICKER -> ToolchainCard(
            selected = packName in selected,
            badge = if (packName in installed) ToolchainBadge.INSTALLED else ToolchainBadge.NONE,
        )
        ToolchainCardMode.MANAGER -> managerCard(packName)
    }

    private fun managerCard(packName: String): ToolchainCard {
        val status = downloadStatus[packName]
        // A report always wins over a seed, and only a pack with no report at
        // all is read from the seeds. A report is this screen's own and keeps
        // arriving; a seed is a one-off snapshot and would otherwise outlive the
        // download it describes, since nothing reports that download's end here.
        val seeded = if (status == null) seededDownloads[packName] else null
        return when {
            status in IN_FLIGHT -> ToolchainCard(
                progressPercent = downloadPercent[packName] ?: 0,
                action = ToolchainAction.CANCEL,
            )
            seeded != null -> ToolchainCard(
                progressPercent = seeded,
                action = ToolchainAction.CANCEL,
            )
            // Still installed, and no longer offering anything: the files are
            // there until the removal runs, and the one thing the user could do
            // about this card they have already done. Below the two download
            // branches deliberately, so a card that is somehow both would still
            // show the Cancel that stops a transfer.
            packName in removing -> ToolchainCard(badge = ToolchainBadge.INSTALLED)
            status == AssetPackStatus.FAILED -> ToolchainCard(
                badge = ToolchainBadge.FAILED,
                action = ToolchainAction.RETRY,
            )
            packName in installed -> ToolchainCard(
                badge = ToolchainBadge.INSTALLED,
                action = ToolchainAction.REMOVE,
            )
            else -> ToolchainCard(action = ToolchainAction.INSTALL)
        }
    }

    private companion object {
        /**
         * Reports that mean the download is still going somewhere, so the card
         * keeps offering Cancel rather than a second Install.
         *
         * REQUIRES_USER_CONFIRMATION belongs here for the same reason the other
         * four do, and it is the one that was missing. Play emits it and then
         * waits, indefinitely, for a dialog the user may have dismissed: the pack
         * is queued and not moving, and this card's Cancel is the only route in
         * the app to `assetPackManager.cancel`. Without it the card fell through
         * to a plain Install, which says a download is neither running nor
         * pending. `isTerminalPackStatus`, the other predicate in this project
         * asking the same question, has always counted it as unsettled.
         */
        val IN_FLIGHT = listOf(
            AssetPackStatus.DOWNLOADING,
            AssetPackStatus.PENDING,
            AssetPackStatus.WAITING_FOR_WIFI,
            AssetPackStatus.TRANSFERRING,
            AssetPackStatus.REQUIRES_USER_CONFIRMATION,
        )
    }
}
