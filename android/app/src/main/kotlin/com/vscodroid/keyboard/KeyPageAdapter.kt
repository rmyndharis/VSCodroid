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
    private val onDragEnd: () -> Unit,
    private val onLongPress: (button: ExtraKeyButton, alternates: List<AlternateKey>) -> Unit
) : RecyclerView.Adapter<KeyPageAdapter.PageViewHolder>() {

    /**
     * Whether each modifier is currently on, the only place that answer is kept.
     *
     * [onBindViewHolder] rebuilds a page's buttons from nothing and paints them
     * from here, and [ExtraKeyRow] reads the same entries to decide which
     * modifiers an injected key carries. One value, so what the user can see and
     * what gets sent cannot be different things.
     *
     * They used to be two values, and the copy here was only ever written in one
     * direction: releasing a modifier reached it, switching one on did not, so it
     * said "off" for the whole time a modifier was held. That stayed invisible
     * while the row had three pages: `offscreenPageLimit = 1` left at most one
     * detached, and RecyclerView handed a single detached page back from its
     * two-entry view cache still bound, so nothing was ever repainted from the
     * stale copy. Five pages no longer fit that argument. Sitting on page 1
     * leaves three detached, one more than the view cache holds, so a page
     * really does go to the pool and come back through [onBindViewHolder].
     * That is harmless only because this map is now the single answer and the
     * bind repaints from it; splitting it in two again would show up as a Ctrl
     * painted idle while the next key still arrives as Ctrl+key.
     */
    private val toggleState = mutableMapOf<String, Boolean>()

    /** The live button for each toggle, so [setToggleState] can repaint it. */
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
                        applyRoundedBackground(context.getColor(R.color.colorExtraKeyBg))
                        setTextColor(context.getColor(R.color.colorExtraKeyText))

                        onKeyAction = { key, isActive ->
                            this@KeyPageAdapter.onKeyAction(key, isActive, this)
                        }
                        onLongPressAction = { btn, alts ->
                            this@KeyPageAdapter.onLongPress(btn, alts)
                        }

                        // Restore toggle state after recycling
                        if (item.isToggle) {
                            isToggleActive = this@KeyPageAdapter.isToggleActive(item.value)
                        }
                    }

                    if (item.isToggle) {
                        toggleButtons[item.value] = button
                    }

                    // No margins. The row divides its width by weight, so every
                    // dp given to a margin is a dp taken off the touch target of
                    // the key beside it, and these keys are already under the
                    // 48dp guideline on a narrow phone. The 2dp gap now comes
                    // from an inset inside the key's own background, which looks
                    // the same and leaves the target whole.
                    holder.container.addView(button, keyLayoutParams())
                }

                is KeyItem.GesturePad -> {
                    val trackpad = GestureTrackpad(holder.container.context).apply {
                        onArrowKey = { direction ->
                            this@KeyPageAdapter.onArrowKey(direction)
                        }
                        onDragEnd = {
                            this@KeyPageAdapter.onDragEnd()
                        }
                    }
                    holder.container.addView(trackpad, padLayoutParams())
                }
            }
        }
    }

    override fun getItemCount(): Int = pages.size

    /** Whether the toggle key `keyValue` is on. A key never set is off. */
    fun isToggleActive(keyValue: String): Boolean = toggleState[keyValue] == true

    /**
     * The one way a modifier changes: records it and repaints the button in the
     * same call, so a caller cannot do one and forget the other.
     */
    fun setToggleState(keyValue: String, active: Boolean) {
        toggleState[keyValue] = active
        toggleButtons[keyValue]?.isToggleActive = active
    }

}

/**
 * How one key claims its share of the row.
 *
 * Weight with a zero width, so the row is divided rather than measured: the
 * page container is measured EXACTLY, which means `LinearLayout` remeasures
 * each of these children with an EXACTLY spec of its own and `TextView`'s
 * minWidth clamp never runs. That is why [ExtraKeyButton]'s `minWidth` cannot
 * hold a key at 48dp, and why nothing here may hand width back through a
 * margin.
 *
 * Named rather than inline so a test can measure the real thing.
 */
internal fun keyLayoutParams(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

/** The trackpad's share, one and a half keys wide, for the same reason. */
internal fun padLayoutParams(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f)
