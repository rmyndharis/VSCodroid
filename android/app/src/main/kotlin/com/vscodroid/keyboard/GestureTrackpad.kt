package com.vscodroid.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.vscodroid.R

/**
 * Drag-to-navigate trackpad that replaces 4 arrow key buttons with a single gesture area.
 *
 * Three speed gears activate based on cumulative drag distance; the distances
 * and the arrows a drag earns live in [TrackpadGesture], which has no Android
 * types in it and is tested on the JVM. What is left here is touch plumbing and
 * drawing.
 */
@SuppressLint("ClickableViewAccessibility")
class GestureTrackpad @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onArrowKey: ((direction: String) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        strokeWidth = dpToPx(1.5f)
        style = Paint.Style.STROKE
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.colorExtraKeyActive)
        style = Paint.Style.FILL
    }

    private val gesture = TrackpadGesture()

    private var tracking = false
    private var lastX = 0f
    private var lastY = 0f
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f

    init {
        // Rounded background with subtle border for visual distinction
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.colorGestureTrackpadBg))
            cornerRadius = dpToPx(6f)
            setStroke(dpToPx(1f).toInt(), 0xFF555555.toInt())
        }
        contentDescription = "Arrow key trackpad. Drag to move cursor."
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = dpToPx(96f).toInt()
        val desiredHeight = dpToPx(56f).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val arm = dpToPx(8f)

        // Crosshair
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crosshairPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crosshairPaint)

        // Touch feedback dot
        if (tracking) {
            val margin = dpToPx(4f)
            val dotX = (cx + touchOffsetX).coerceIn(margin, width - margin)
            val dotY = (cy + touchOffsetY).coerceIn(margin, height - margin)
            canvas.drawCircle(dotX, dotY, dpToPx(3f), dotPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                lastX = event.x
                lastY = event.y
                gesture.reset()
                touchOffsetX = 0f
                touchOffsetY = 0f
                parent.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y

                touchOffsetX = event.x - width / 2f
                touchOffsetY = event.y - height / 2f

                val directions =
                    gesture.accumulate(dx, dy, resources.displayMetrics.density)
                for (direction in directions) {
                    onArrowKey?.invoke(direction)
                }

                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                touchOffsetX = 0f
                touchOffsetY = 0f
                parent.requestDisallowInterceptTouchEvent(false)
                onDragEnd?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
}
