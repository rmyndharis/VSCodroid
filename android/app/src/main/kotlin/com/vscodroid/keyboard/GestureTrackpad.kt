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
import androidx.core.view.ViewCompat
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

        // A drag is the only way to earn an arrow from this view, and a screen
        // reader user cannot drag: touch exploration takes the gesture stream
        // first, so ACTION_MOVE never reaches onTouchEvent below. Every other
        // key on this row is a view a screen reader can activate, which left
        // the four arrows as the one thing on the row with no reachable route
        // at all. There is no fallback elsewhere either: the arrow buttons
        // that used to carry them were deleted when this pad replaced them,
        // and no KeyPage has carried an arrow since.
        //
        // Each action ends the drag after emitting, because that call is what
        // clears a latched modifier. Without it a Ctrl latched before the
        // action would stay latched afterwards, while every ordinary key on
        // the row clears it -- the same one-shot behaviour, reached a
        // different way.
        for ((label, direction) in ARROW_ACTIONS) {
            ViewCompat.addAccessibilityAction(this, label) { _, _ ->
                onArrowKey?.invoke(direction)
                onDragEnd?.invoke()
                true
            }
        }
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

/**
 * The four arrows reachable without a drag, and what each one is called.
 *
 * Top level rather than inside [GestureTrackpad] for the same reason
 * `pressedState` sits outside [ExtraKeyButton]: that class is a `View` whose
 * initialiser reaches resources and display metrics on its first line, so no
 * JVM unit test can construct one. This is the half that is data, so it can be
 * checked -- and the half worth checking, because a direction that [KeyMapping]
 * does not know is dropped by [KeyInjector] with nothing said.
 *
 * The strings are DOM key names, not Android key codes; the whole arrow path in
 * this app speaks the web client's language and never touches KEYCODE_DPAD.
 */
internal val ARROW_ACTIONS: List<Pair<String, String>> = listOf(
    "Move cursor left" to "ArrowLeft",
    "Move cursor right" to "ArrowRight",
    "Move cursor up" to "ArrowUp",
    "Move cursor down" to "ArrowDown",
)
