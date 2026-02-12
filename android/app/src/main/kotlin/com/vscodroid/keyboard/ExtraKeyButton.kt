package com.vscodroid.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import com.vscodroid.R

@SuppressLint("ClickableViewAccessibility")
class ExtraKeyButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var isToggle: Boolean = false
    var isToggleActive: Boolean = false
        set(value) {
            field = value
            updateToggleAppearance()
        }

    var keyValue: String = ""
    var onKeyAction: ((key: String, isActive: Boolean) -> Unit)? = null
    var alternates: List<AlternateKey> = emptyList()
    var onLongPressAction: ((ExtraKeyButton, List<AlternateKey>) -> Unit)? = null

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (!isToggle) {
                    this@ExtraKeyButton.alpha = 0.6f
                }
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isToggle) {
                    isToggleActive = !isToggleActive
                    onKeyAction?.invoke(keyValue, isToggleActive)
                } else {
                    onKeyAction?.invoke(keyValue, true)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                this@ExtraKeyButton.alpha = 1.0f
                if (alternates.isNotEmpty()) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongPressAction?.invoke(this@ExtraKeyButton, alternates)
                } else {
                    // No alternates — treat as repeated key press
                    onKeyAction?.invoke(keyValue, true)
                }
            }
        })

    init {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.MONOSPACE
        minWidth = dpToPx(48)
        minimumHeight = dpToPx(48)
        setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
        isClickable = true
        isFocusable = false

        // Rounded corner background
        applyRoundedBackground(context.getColor(R.color.colorExtraKeyBg))

        setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isToggle) {
                        alpha = 1.0f
                    }
                }
            }
            true
        }
    }

    private fun updateToggleAppearance() {
        if (isToggleActive) {
            applyRoundedBackground(context.getColor(R.color.colorExtraKeyActive))
            setTextColor(context.getColor(android.R.color.white))
        } else {
            applyRoundedBackground(context.getColor(R.color.colorExtraKeyBg))
            setTextColor(context.getColor(R.color.colorExtraKeyText))
        }
    }

    fun applyRoundedBackground(color: Int) {
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpToPx(6).toFloat()
        }
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
}
