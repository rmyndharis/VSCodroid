package com.vscodroid.setup

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.vscodroid.R

/**
 * Shared RecyclerView adapter for toolchain cards.
 *
 * Two modes:
 * - PICKER: first-run selection (tap toggles checkmark, reports via [onSelectionChanged])
 * - MANAGER: settings screen (shows installed/downloading/action buttons, reports via [onAction])
 *
 * What each card should say is decided by [ToolchainCardState], which has no
 * Android types in it and is pinned by ToolchainCardStateTest. This class only
 * puts that decision on screen.
 */
class ToolchainPickerAdapter(
    private val mode: ToolchainCardMode,
) : RecyclerView.Adapter<ToolchainPickerAdapter.ViewHolder>() {

    /** PICKER mode: called when selection set changes. */
    var onSelectionChanged: ((selected: Set<String>) -> Unit)? = null

    /** MANAGER mode: called when an action button is tapped. */
    var onAction: ((packName: String, action: ToolchainAction) -> Unit)? = null

    private val cards = ToolchainCardState(mode)

    fun getSelectedPackNames(): Set<String> = cards.selectedPackNames()

    @SuppressLint("NotifyDataSetChanged")
    fun setInstalled(packNames: Collection<String>) {
        cards.setInstalled(packNames)
        notifyDataSetChanged()
    }

    /**
     * MANAGER mode: replaces the downloads this screen was never told about.
     *
     * See [ToolchainCardState.setDownloading]. Called when the screen starts,
     * because that is the moment a rebuilt one holds no reports at all.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun setDownloading(percentByPack: Map<String, Int>) {
        cards.setDownloading(percentByPack)
        notifyDataSetChanged()
    }

    fun updateState(packName: String, status: Int, percent: Int) {
        val pos = cards.updateState(packName, status, percent)
        if (pos >= 0) notifyItemChanged(pos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_toolchain_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = cards.items[position]
        val ctx = holder.itemView.context

        holder.name.text = info.shortLabel
        holder.description.text = ctx.getString(info.descriptionRes)
        // Both, because they answer different questions and only one of them
        // used to be here. The single figure was the unpacked size, so a card
        // offered "179 MB" for a 59 MB download, and the person most likely to
        // read it is the one deciding whether to spend mobile data.
        val sizes = cards.sizeFigures(info)
        holder.size.text = ctx.getString(
            R.string.toolchain_size_download_and_installed,
            sizes.download,
            sizes.installed,
        )

        when (mode) {
            ToolchainCardMode.PICKER -> bindPickerMode(holder, info)
            ToolchainCardMode.MANAGER -> bindManagerMode(holder, info, ctx)
        }
    }

    override fun getItemCount() = cards.items.size

    private fun bindPickerMode(holder: ViewHolder, info: ToolchainRegistry.ToolchainInfo) {
        val state = cards.card(info.packName)
        val card = holder.card

        // Already on disk, so this card is a statement rather than an offer, and it
        // returns before any of the selection wiring below: no tick, no checked
        // state for a screen reader to act on, and no listener.
        // ToolchainCardState.toggleSelection refuses it too; that is the half a
        // test can drive, this is the half the user sees.
        //
        // Drawn rather than dropped from the grid: two cards are the whole list,
        // and one going missing reads as a build that withdrew the toolchain.
        // Every field is set on both paths because a recycled holder arrives
        // carrying the previous card's badge, listener and clickability.
        if (state.badge == ToolchainBadge.INSTALLED) {
            card.strokeWidth = 0
            card.setCardBackgroundColor(card.context.getColor(R.color.colorSurface))
            holder.checkmark.visibility = View.GONE
            card.isCheckable = false
            card.isChecked = false
            card.setOnClickListener(null)
            card.isClickable = false
            holder.statusBadge.visibility = View.VISIBLE
            holder.statusBadge.text = card.context.getString(R.string.toolchain_installed)
            holder.statusBadge.setTextColor(card.context.getColor(R.color.colorSuccess))
            holder.actionButton.visibility = View.GONE
            holder.downloadProgress.visibility = View.GONE
            return
        }

        val isSelected = state.selected

        // Visual selection state
        card.strokeWidth = if (isSelected) 2.dpToPx(card) else 0
        card.strokeColor = card.context.getColor(R.color.colorPrimary)
        card.setCardBackgroundColor(
            card.context.getColor(if (isSelected) R.color.colorCardSelected else R.color.colorSurface)
        )
        holder.checkmark.visibility = if (isSelected) View.VISIBLE else View.GONE

        // The state a screen reader can read. MaterialCardView implements Checkable
        // and already forwards these into the node and into the change event, but
        // nothing ever turned it on, so every card reported checkable=false and
        // checked=false in both states. Selection existed only as a stroke, a
        // background colour and a checkmark that vanishes when deselected, which is
        // to say only in the visual channel.
        card.isCheckable = true
        card.isChecked = isSelected
        // Checkable is wanted for what it tells a screen reader, and not for what it
        // draws. MaterialCardView answers isCheckable by switching on its own checked
        // icon, in the top right, which is where this layout already constrains
        // R.id.checkmark, so turning the state on put a second tick on top of the
        // first one. Null removes the drawable without touching the state.
        card.checkedIcon = null

        // Hide MANAGER-only views
        holder.statusBadge.visibility = View.GONE
        holder.actionButton.visibility = View.GONE
        holder.downloadProgress.visibility = View.GONE

        card.setOnClickListener {
            cards.toggleSelection(info.packName)
            // With a payload rather than without: a plain notifyItemChanged runs the
            // default change animation, which swaps the item view and takes
            // accessibility focus with it, so the user who just double-tapped a card
            // is returned to the top of the list. RecyclerView skips that animation
            // when a payload is supplied, and the rebind below is the same work.
            notifyItemChanged(holder.bindingAdapterPosition, PAYLOAD_SELECTION)
            onSelectionChanged?.invoke(cards.selectedPackNames())
        }
    }

    private fun bindManagerMode(
        holder: ViewHolder,
        info: ToolchainRegistry.ToolchainInfo,
        ctx: android.content.Context,
    ) {
        val card = holder.card
        val state = cards.card(info.packName)

        // No selection visuals in manager mode
        card.strokeWidth = 0
        card.setCardBackgroundColor(ctx.getColor(R.color.colorSurface))
        holder.checkmark.visibility = View.GONE
        card.setOnClickListener(null)
        card.isClickable = false

        when (state.badge) {
            ToolchainBadge.NONE -> holder.statusBadge.visibility = View.GONE
            ToolchainBadge.FAILED -> {
                holder.statusBadge.visibility = View.VISIBLE
                holder.statusBadge.text = ctx.getString(R.string.progress_failed)
                holder.statusBadge.setTextColor(ctx.getColor(R.color.colorError))
            }
            ToolchainBadge.INSTALLED -> {
                holder.statusBadge.visibility = View.VISIBLE
                holder.statusBadge.text = ctx.getString(R.string.toolchain_installed)
                holder.statusBadge.setTextColor(ctx.getColor(R.color.colorSuccess))
            }
        }

        val percent = state.progressPercent
        if (percent == null) {
            holder.downloadProgress.visibility = View.GONE
        } else {
            holder.downloadProgress.visibility = View.VISIBLE
            holder.downloadProgress.progress = percent
        }

        val action = state.action
        if (action == null) {
            holder.actionButton.visibility = View.GONE
        } else {
            holder.actionButton.visibility = View.VISIBLE
            holder.actionButton.text = ctx.getString(labelFor(action))
            holder.actionButton.setOnClickListener {
                onAction?.invoke(info.packName, action)
            }
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.toolchainCard)
        val name: TextView = view.findViewById(R.id.toolchainName)
        val description: TextView = view.findViewById(R.id.toolchainDescription)
        val size: TextView = view.findViewById(R.id.toolchainSize)
        val checkmark: ImageView = view.findViewById(R.id.checkmark)
        val statusBadge: TextView = view.findViewById(R.id.statusBadge)
        val actionButton: Button = view.findViewById(R.id.actionButton)
        val downloadProgress: ProgressBar = view.findViewById(R.id.downloadProgress)
    }

    companion object {
        /**
         * Marks a rebind that only changes selection.
         *
         * Its value is never read; supplying any payload is what makes RecyclerView
         * skip the change animation, and skipping it is what keeps accessibility
         * focus on the card the user just tapped.
         */
        private const val PAYLOAD_SELECTION = "selection"
        private fun labelFor(action: ToolchainAction): Int = when (action) {
            ToolchainAction.INSTALL -> R.string.toolchain_install
            ToolchainAction.REMOVE -> R.string.toolchain_remove
            ToolchainAction.CANCEL -> R.string.toolchain_cancel
            ToolchainAction.RETRY -> R.string.progress_retry
        }

        private fun Int.dpToPx(view: View): Int =
            (this * view.resources.displayMetrics.density).toInt()
    }
}
