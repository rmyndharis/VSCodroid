package com.vscodroid.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
            setBackgroundColor(context.getColor(R.color.colorPopupBg))
            val pad = dpToPx(4)
            setPadding(pad, pad, pad, pad)
        }

        for (alt in alternates) {
            val button = TextView(context).apply {
                text = alt.label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.MONOSPACE
                setTextColor(context.getColor(R.color.colorExtraKeyText))
                setBackgroundColor(context.getColor(R.color.colorExtraKeyBg))
                minWidth = dpToPx(44)
                minimumHeight = dpToPx(38)
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                isClickable = true

                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onKeySelected(alt.value)
                    dismiss()
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpToPx(2) }
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
        val x = location[0] + (anchor.width - popupWidth) / 2
        val y = location[1] - popupHeight - dpToPx(4)

        popup = PopupWindow(container, popupWidth, popupHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(context.getColor(R.color.colorPopupBg)))
            elevation = dpToPx(4).toFloat()
            isOutsideTouchable = true
            showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
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
