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
        color = context.getColor(R.color.colorGestureTrackpadCrosshair)
        strokeWidth = dpToPx(1.5f)
        style = Paint.Style.STROKE
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.colorExtraKeyActive)
        style = Paint.Style.FILL
    }

    private val gesture = TrackpadGesture()

    private var tracking = false

    /**
     * The pointer id the drag belongs to, or [MotionEvent.INVALID_POINTER_ID].
     *
     * An id, not an index. Indices renumber as fingers come and go: this view
     * used to read `event.x`, which is index 0, so resting a second finger and
     * then lifting the first made index 0 become the second finger. The next
     * MOVE then reported the distance between two fingers as one delta, and the
     * accumulator paid that out as a burst of arrows in a single frame.
     */
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f

    init {
        // Rounded background with subtle border for visual distinction
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.colorGestureTrackpadBg))
            cornerRadius = dpToPx(6f)
            setStroke(dpToPx(1f).toInt(), context.getColor(R.color.colorGestureTrackpadBorder))
        }
        contentDescription = context.getString(R.string.trackpad_description)

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
            ViewCompat.addAccessibilityAction(this, context.getString(label)) { _, _ ->
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

    /**
     * Ends the drag, whichever way it ended, and reports that it did.
     *
     * Reached from ACTION_UP, ACTION_CANCEL and from the tracked finger lifting
     * while another stays down. The [onDragEnd] call is what clears a latched
     * modifier, so a path that ends tracking without coming through here leaves
     * a Ctrl on the row that the next key would carry.
     *
     * Called more than once per gesture, which is why the body is guarded rather
     * than the callers: the tracked finger lifting ends the drag from
     * ACTION_POINTER_UP, and the finger that outlived it delivers ACTION_UP a
     * moment later, which reaches here again for a drag that is already over.
     * Both calls reported an end, so one gesture paid out two [onDragEnd]s and
     * two `requestDisallowInterceptTouchEvent(false)`.
     */
    private fun endDrag() {
        if (!tracking) return
        tracking = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        touchOffsetX = 0f
        touchOffsetY = 0f
        parent.requestDisallowInterceptTouchEvent(false)
        onDragEnd?.invoke()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // actionMasked, not action: ACTION_POINTER_DOWN and ACTION_POINTER_UP
        // carry the pointer index in the high byte, so the raw value matched no
        // branch and a second finger's down and up fell through to super.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                pointerId = event.getPointerId(0)
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
                // Found by id. A second finger renumbers the indices, and index
                // 0 is whichever pointer happens to be first in this event.
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y

                touchOffsetX = x - width / 2f
                touchOffsetY = y - height / 2f

                val directions =
                    gesture.accumulate(dx, dy, resources.displayMetrics.density)
                for (direction in directions) {
                    onArrowKey?.invoke(direction)
                }

                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Only when the finger that lifted is the one being tracked. Any
                // other finger leaving is not this drag's business, and handing
                // the drag over to a surviving finger would resume it from a
                // position tens of dp away, which is the burst this fixes.
                if (event.getPointerId(event.actionIndex) == pointerId) endDrag()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endDrag()
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
 * The two halves of each pair are different kinds of string and only one is
 * language. The label is read out as an entry in the actions menu a screen
 * reader offers, so it is a resource id; the direction is a DOM key name the
 * page receives, so it is a literal and must stay one. Translating a direction
 * would send "ArrowLeft" to [KeyMapping] under a name it does not hold, and
 * [KeyInjector] drops what it cannot resolve without saying so.
 */
internal val ARROW_ACTIONS: List<Pair<Int, String>> = listOf(
    R.string.trackpad_action_left to "ArrowLeft",
    R.string.trackpad_action_right to "ArrowRight",
    R.string.trackpad_action_up to "ArrowUp",
    R.string.trackpad_action_down to "ArrowDown",
)
