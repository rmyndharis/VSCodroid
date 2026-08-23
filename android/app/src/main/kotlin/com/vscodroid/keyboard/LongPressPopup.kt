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
import com.vscodroid.R
import androidx.appcompat.widget.AppCompatTextView

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
            val button = AlternateKeyView(context).apply {
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

        // Position centered above anchor.
        //
        // In the app window's coordinates, not the screen's, and the three
        // numbers below have to agree on that. `showAtLocation` puts x and y in
        // the LayoutParams of a window whose parent is the activity's, and a
        // child window is laid out against its parent's frame, so what it
        // consumes is window space. `getLocationInWindow` reports the anchor in
        // that space, and `displayMetrics` taken off an activity's resources is
        // the window's width on API 30 and up, not the display's. This used to
        // read `getLocationOnScreen`, which agrees with the other two only while
        // the window fills the display: split-screen and freeform put the origin
        // somewhere else, and the popup then landed off by however far the
        // window sits from the top left of the screen.
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val at = LongPressPlacement.above(
            anchorLeft = location[0],
            anchorTop = location[1],
            anchorWidth = anchor.width,
            popupWidth = popupWidth,
            popupHeight = popupHeight,
            gapPx = dpToPx(4),
            windowWidthPx = context.resources.displayMetrics.widthPixels,
        )

        popup = PopupWindow(container, popupWidth, popupHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(context.getColor(R.color.colorPopupBg)))
            elevation = dpToPx(4).toFloat()
            isOutsideTouchable = true
            // The window takes input focus, which is what makes an alternate
            // tappable and what dismisses the popup on a touch outside it. With
            // the default input-method mode that also makes it the IME's target,
            // and the IME closes when its target cannot take text: the soft
            // keyboard went down, and the key row goes down with it, because the
            // row's visibility is driven by the ime() insets. Long pressing a key
            // therefore cost the keyboard and a tap to get it back.
            //
            // NOT_NEEDED sets FLAG_ALT_FOCUSABLE_IM, which leaves the IME
            // targeting the activity window while this one holds focus. It does
            // not make the popup unfocusable, so the dismiss-before-emit above
            // is still required.
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
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

/**
 * One entry in the alternates popup, which is a button and says so.
 *
 * The same gap [ExtraKeyButton.getAccessibilityClassName] closed on the row, one
 * layer down: a `TextView` that is clickable is still announced as a text view,
 * because the spoken role comes from the node's class name. It matters more here
 * than it does on the row, because this popup is the only route to `'`, `\`, `~`
 * and `)`: the keys that open it are on a page, these are on none.
 *
 * A class rather than an accessibility delegate on each entry, so the override
 * sits where the row's does and one JVM-free case can read it off a single view.
 */
internal class AlternateKeyView(context: Context) : AppCompatTextView(context) {
    override fun getAccessibilityClassName(): CharSequence =
        android.widget.Button::class.java.name
}
