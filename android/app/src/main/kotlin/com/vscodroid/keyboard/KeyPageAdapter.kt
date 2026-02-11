package com.vscodroid.keyboard

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.vscodroid.R

class KeyPageAdapter(
    private val pages: List<KeyPage>,
    private val onKeyAction: (key: String, isActive: Boolean, button: ExtraKeyButton) -> Unit,
    private val onArrowKey: (direction: String) -> Unit,
    private val onLongPress: (button: ExtraKeyButton, alternates: List<AlternateKey>) -> Unit
) : RecyclerView.Adapter<KeyPageAdapter.PageViewHolder>() {

    // Persist toggle state across RecyclerView recycling
    private val toggleState = mutableMapOf<String, Boolean>()
    private val toggleButtons = mutableMapOf<String, ExtraKeyButton>()

    inner class PageViewHolder(val container: LinearLayout) : RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        return PageViewHolder(container)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages[position]
        holder.container.removeAllViews()

        for (item in page.items) {
            when (item) {
                is KeyItem.Button -> {
                    val button = ExtraKeyButton(holder.container.context).apply {
                        text = item.label
                        keyValue = item.value
                        isToggle = item.isToggle
                        contentDescription = item.contentDescription
                        alternates = item.alternates
                        setBackgroundColor(context.getColor(R.color.colorExtraKeyBg))
                        setTextColor(context.getColor(R.color.colorExtraKeyText))

                        onKeyAction = { key, isActive ->
                            this@KeyPageAdapter.onKeyAction(key, isActive, this)
                        }
                        onLongPressAction = { btn, alts ->
                            this@KeyPageAdapter.onLongPress(btn, alts)
                        }

                        // Restore toggle state after recycling
                        if (item.isToggle) {
                            isToggleActive = toggleState[item.value] ?: false
                        }
                    }

                    if (item.isToggle) {
                        toggleButtons[item.value] = button
                    }

                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    holder.container.addView(button, lp)
                }

                is KeyItem.GesturePad -> {
                    val trackpad = GestureTrackpad(holder.container.context).apply {
                        onArrowKey = { direction ->
                            this@KeyPageAdapter.onArrowKey(direction)
                        }
                    }
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f)
                    holder.container.addView(trackpad, lp)
                }
            }
        }
    }

    override fun getItemCount(): Int = pages.size

    fun setToggleState(keyValue: String, active: Boolean) {
        toggleState[keyValue] = active
        toggleButtons[keyValue]?.isToggleActive = active
    }
}
