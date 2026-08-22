package com.vscodroid.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.vscodroid.R

class LongPressPopup(
    private val context: Context,
    private val alternates: List<AlternateKey>,
    private val onKeySelected: (String) -> Unit
) {
    private var popup: PopupWindow? = null

    fun show(anchor: View) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dpToPx(6)
            setPadding(pad, pad, pad, pad)
            // Rounded popup container
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.colorPopupBg))
                cornerRadius = dpToPx(10).toFloat()
            }
        }

        for (alt in alternates) {
            val button = TextView(context).apply {
                text = alt.label
                // Named, not spelled. Without this the only accessible text on
                // an alternate is its glyph, so the two entries under the quote
                // key are announced as `'` and `` ` `` and a user who cannot see
                // them has nothing to tell an apostrophe from a backtick. The
                // keys on the row itself have been named since they were built;
                // this is the layer that was not.
                contentDescription = context.getString(alt.contentDescriptionRes)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.MONOSPACE
                setTextColor(context.getColor(R.color.colorExtraKeyText))
                minWidth = dpToPx(48)
                minimumHeight = dpToPx(48)
                setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                isClickable = true

                // Rounded button background
                background = GradientDrawable().apply {
                    setColor(context.getColor(R.color.colorExtraKeyBg))
                    cornerRadius = dpToPx(8).toFloat()
                }

                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    // Dismiss BEFORE emitting. This popup is focusable (the
                    // fourth argument to PopupWindow below), so while it is up
                    // the activity window does not hold input focus, and an
                    // alternate is now delivered as a real key press rather than
                    // as JavaScript. A key press aimed at a window that is not
                    // focused is at best fragile, and `\`, `~` and `'` reach the
                    // editor through no other route.
                    dismiss()
                    onKeySelected(alt.value)
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpToPx(4) }
            container.addView(button, lp)
        }

        // Measure to calculate position
        container.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = container.measuredWidth
        val popupHeight = container.measuredHeight

        // Position centered above anchor
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val at = LongPressPlacement.above(
            anchorLeft = location[0],
            anchorTop = location[1],
            anchorWidth = anchor.width,
            popupWidth = popupWidth,
            popupHeight = popupHeight,
            gapPx = dpToPx(4),
        )

        popup = PopupWindow(container, popupWidth, popupHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(context.getColor(R.color.colorPopupBg)))
            elevation = dpToPx(4).toFloat()
            isOutsideTouchable = true
            showAtLocation(anchor, Gravity.NO_GRAVITY, at.x, at.y)
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics
        ).toInt()
}
