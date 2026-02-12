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
import kotlin.math.abs
import kotlin.math.sign

/**
 * Drag-to-navigate trackpad that replaces 4 arrow key buttons with a single gesture area.
 *
 * Three speed gears activate based on cumulative drag distance:
 * - Precise (0dp): 24dp per arrow — character-by-character
 * - Moderate (100dp): 14dp per arrow — word navigation
 * - Fast (250dp): 6dp per arrow — line/file traversal
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

    private enum class Gear(val activationDp: Float, val thresholdDp: Float) {
        PRECISE(0f, 24f),
        MODERATE(100f, 14f),
        FAST(250f, 6f)
    }

    private var tracking = false
    private var lastX = 0f
    private var lastY = 0f
    private var accumulatedDx = 0f
    private var accumulatedDy = 0f
    private var totalDistance = 0f
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
        val desiredHeight = dpToPx(48f).toInt()
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
                accumulatedDx = 0f
                accumulatedDy = 0f
                totalDistance = 0f
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

                totalDistance += abs(dx) + abs(dy)
                val threshold = dpToPx(getCurrentGear().thresholdDp)

                accumulatedDx += dx
                accumulatedDy += dy

                touchOffsetX = event.x - width / 2f
                touchOffsetY = event.y - height / 2f

                // Emit horizontal arrows
                while (abs(accumulatedDx) >= threshold) {
                    val direction = if (accumulatedDx > 0) "ArrowRight" else "ArrowLeft"
                    onArrowKey?.invoke(direction)
                    accumulatedDx -= sign(accumulatedDx) * threshold
                }

                // Emit vertical arrows
                while (abs(accumulatedDy) >= threshold) {
                    val direction = if (accumulatedDy > 0) "ArrowDown" else "ArrowUp"
                    onArrowKey?.invoke(direction)
                    accumulatedDy -= sign(accumulatedDy) * threshold
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

    private fun getCurrentGear(): Gear {
        val totalDp = totalDistance / resources.displayMetrics.density
        return when {
            totalDp >= Gear.FAST.activationDp -> Gear.FAST
            totalDp >= Gear.MODERATE.activationDp -> Gear.MODERATE
            else -> Gear.PRECISE
        }
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
}
