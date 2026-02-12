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
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.R

/**
 * Shared RecyclerView adapter for toolchain cards.
 *
 * Two modes:
 * - PICKER: first-run selection (tap toggles checkmark, reports via [onSelectionChanged])
 * - MANAGER: settings screen (shows installed/downloading/action buttons, reports via [onAction])
 */
class ToolchainPickerAdapter(
    private val mode: Mode,
) : RecyclerView.Adapter<ToolchainPickerAdapter.ViewHolder>() {

    enum class Mode { PICKER, MANAGER }

    enum class Action { INSTALL, REMOVE, CANCEL, RETRY }

    /** PICKER mode: called when selection set changes. */
    var onSelectionChanged: ((selected: Set<String>) -> Unit)? = null

    /** MANAGER mode: called when an action button is tapped. */
    var onAction: ((packName: String, action: Action) -> Unit)? = null

    private val items = ToolchainRegistry.available.toList()
    private val selected = mutableSetOf<String>()
    private val installed = mutableSetOf<String>()
    private val downloadStatus = mutableMapOf<String, Int>()
    private val downloadPercent = mutableMapOf<String, Int>()

    fun getSelectedPackNames(): Set<String> = selected.toSet()

    @SuppressLint("NotifyDataSetChanged")
    fun setInstalled(packNames: Collection<String>) {
        installed.clear()
        installed.addAll(packNames.map { name ->
            if (name.startsWith("toolchain_")) name else "toolchain_$name"
        })
        notifyDataSetChanged()
    }

    fun updateState(packName: String, status: Int, percent: Int) {
        downloadStatus[packName] = status
        downloadPercent[packName] = percent
        val pos = items.indexOfFirst { it.packName == packName }
        if (pos >= 0) notifyItemChanged(pos)

        // On COMPLETED, add to installed set
        if (status == AssetPackStatus.COMPLETED) {
            installed.add(packName)
        }
        // On NOT_INSTALLED (after uninstall), remove from installed set
        if (status == AssetPackStatus.NOT_INSTALLED) {
            installed.remove(packName)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_toolchain_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = items[position]
        val ctx = holder.itemView.context

        holder.name.text = info.shortLabel
        holder.description.text = info.description
        holder.size.text = "~${ToolchainRegistry.formatSize(info.estimatedSize)}"

        when (mode) {
            Mode.PICKER -> bindPickerMode(holder, info)
            Mode.MANAGER -> bindManagerMode(holder, info, ctx)
        }
    }

    override fun getItemCount() = items.size

    private fun bindPickerMode(holder: ViewHolder, info: ToolchainRegistry.ToolchainInfo) {
        val isSelected = info.packName in selected
        val card = holder.card

        // Visual selection state
        card.strokeWidth = if (isSelected) 2.dpToPx(card) else 0
        card.strokeColor = card.context.getColor(R.color.colorPrimary)
        card.setCardBackgroundColor(
            card.context.getColor(if (isSelected) R.color.colorCardSelected else R.color.colorSurface)
        )
        holder.checkmark.visibility = if (isSelected) View.VISIBLE else View.GONE

        // Hide MANAGER-only views
        holder.statusBadge.visibility = View.GONE
        holder.actionButton.visibility = View.GONE
        holder.downloadProgress.visibility = View.GONE

        card.setOnClickListener {
            if (info.packName in selected) {
                selected.remove(info.packName)
            } else {
                selected.add(info.packName)
            }
            notifyItemChanged(holder.bindingAdapterPosition)
            onSelectionChanged?.invoke(selected.toSet())
        }
    }

    private fun bindManagerMode(
        holder: ViewHolder,
        info: ToolchainRegistry.ToolchainInfo,
        ctx: android.content.Context,
    ) {
        val card = holder.card
        val isInstalled = info.packName in installed
        val status = downloadStatus[info.packName]
        val percent = downloadPercent[info.packName] ?: 0

        // No selection visuals in manager mode
        card.strokeWidth = 0
        card.setCardBackgroundColor(ctx.getColor(R.color.colorSurface))
        holder.checkmark.visibility = View.GONE
        card.setOnClickListener(null)
        card.isClickable = false

        val isDownloading = status in listOf(
            AssetPackStatus.DOWNLOADING,
            AssetPackStatus.PENDING,
            AssetPackStatus.WAITING_FOR_WIFI,
            AssetPackStatus.TRANSFERRING,
        )
        val isFailed = status == AssetPackStatus.FAILED

        when {
            isDownloading -> {
                holder.statusBadge.visibility = View.GONE
                holder.downloadProgress.visibility = View.VISIBLE
                holder.downloadProgress.progress = percent
                holder.actionButton.visibility = View.VISIBLE
                holder.actionButton.text = ctx.getString(R.string.toolchain_cancel)
                holder.actionButton.setOnClickListener {
                    onAction?.invoke(info.packName, Action.CANCEL)
                }
            }
            isFailed -> {
                holder.statusBadge.visibility = View.VISIBLE
                holder.statusBadge.text = ctx.getString(R.string.progress_failed)
                holder.statusBadge.setTextColor(ctx.getColor(R.color.colorError))
                holder.downloadProgress.visibility = View.GONE
                holder.actionButton.visibility = View.VISIBLE
                holder.actionButton.text = ctx.getString(R.string.progress_retry)
                holder.actionButton.setOnClickListener {
                    onAction?.invoke(info.packName, Action.RETRY)
                }
            }
            isInstalled -> {
                holder.statusBadge.visibility = View.VISIBLE
                holder.statusBadge.text = ctx.getString(R.string.toolchain_installed)
                holder.statusBadge.setTextColor(ctx.getColor(R.color.colorSuccess))
                holder.downloadProgress.visibility = View.GONE
                holder.actionButton.visibility = View.VISIBLE
                holder.actionButton.text = ctx.getString(R.string.toolchain_remove)
                holder.actionButton.setOnClickListener {
                    onAction?.invoke(info.packName, Action.REMOVE)
                }
            }
            else -> {
                holder.statusBadge.visibility = View.GONE
                holder.downloadProgress.visibility = View.GONE
                holder.actionButton.visibility = View.VISIBLE
                holder.actionButton.text = ctx.getString(R.string.toolchain_install)
                holder.actionButton.setOnClickListener {
                    onAction?.invoke(info.packName, Action.INSTALL)
                }
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
        private fun Int.dpToPx(view: View): Int =
            (this * view.resources.displayMetrics.density).toInt()
    }
}
