package com.vscodroid.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
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
        set(value) {
            field = value
            // A node advertises ACTION_LONG_CLICK only when the view says it is
            // long clickable, and View gates the incoming action on the same
            // flag, so without this line an assistive service is refused before
            // performLongClick below can run. Keys with no alternates stay
            // false: offering an action that opens nothing is the defect this
            // pair exists to remove, not a second copy of it.
            isLongClickable = value.isNotEmpty()
        }
    var onLongPressAction: ((ExtraKeyButton, List<AlternateKey>) -> Unit)? = null

    /**
     * The latch a hold on this modifier switched away from, while that touch
     * lasts; null otherwise. A touch that ends in a cancel, as one the pager
     * takes does, puts it back, unless [anotherFingerOnRow].
     */
    private var latchBeforeHold: Boolean? = null

    /**
     * Whether another finger is on the row, asked when a hold on this modifier
     * ends in a cancel. [KeyPageAdapter] hands every key the row's answer.
     */
    var anotherFingerOnRow: () -> Boolean = { false }

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
                emitPress()
                return true
            }

            // Only a modifier or a key with alternates gets here: the touch
            // listener turns the long press off for every other key, so a hold
            // on one of those presses it once, on release. Nothing repeats, on
            // purpose. The keys that would want repeating, Backspace and the
            // arrows, are not on the row: the soft keyboard owns Backspace and
            // the trackpad's drag already sends arrows continuously. Repeating
            // Tab, Esc or a `;` would only spray a source file from one hold
            // that ran long.
            override fun onLongPress(e: MotionEvent) {
                this@ExtraKeyButton.alpha = 1.0f
                if (alternates.isEmpty()) {
                    // A modifier switches while the finger is still down, so a
                    // letter typed on the soft keyboard meanwhile is chorded and
                    // spends the latch. Lifting the finger then does nothing
                    // more: the detector sends no tap after a long press.
                    latchBeforeHold = isToggleActive
                    emitPress()
                    return
                }
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPressAction?.invoke(this@ExtraKeyButton, alternates)
            }
        }).apply {
        // No double taps. The listener above is also an OnDoubleTapListener,
        // which makes the detector look for them: a second tap within 300 ms
        // of the first went to onDoubleTap, which this listener does not
        // override, and never became a press. Measured on an API 36
        // emulator: two taps on F7 about 200 ms apart moved the caret once.
        setOnDoubleTapListener(null)
    }

    /**
     * Delivers one press of this key, for a tap, a hold and an assistive click.
     *
     * One path for every press, and being one path is the fix rather than
     * tidiness. A hold used to press from its own branch in `onLongPress`, which
     * reported a bare `true` and flipped nothing where `onSingleTapUp` flipped
     * `isToggleActive` and reported the new value. Ctrl, Alt and Shift are
     * toggles with no alternates, so holding one a moment too long switched the
     * modifier on inside [ExtraKeyRow] while the button carried on looking off,
     * and the next tap arrived inverted. A hold on a modifier still switches it
     * from `onLongPress`, and a cancel of that touch, as the pager taking this
     * finger's drag is, switches it back, both through here; a hold on any other
     * key without alternates ends in `onSingleTapUp` like a tap.
     *
     * This is a `View` callback, so nothing in the JVM unit suite can invoke
     * it. [pressedState] carries the part that can be pinned.
     */
    private fun emitPress() {
        val state = pressedState(isToggle, isToggleActive)
        if (isToggle) isToggleActive = state
        onKeyAction?.invoke(keyValue, state)
    }

    /**
     * Opens the alternates layer when a service long presses this key.
     *
     * The same gap as [performClick], one gesture over: the popup's only entry
     * was [GestureDetector]'s `onLongPress`, which needs a finger held on the
     * view, and touch exploration never delivers one. Five of the nine
     * alternates are top-level keys on other pages and one is Shift plus
     * backtick, but `)`, `'` and `\` are on no page at all, so without this
     * they exist on the row for sighted users only.
     *
     * A key with no alternates falls through to `super`, which is what keeps
     * the row honest: those keys advertise no long click, so nothing offers an
     * action that would open an empty popup.
     */
    override fun performLongClick(): Boolean {
        if (alternates.isEmpty()) return super.performLongClick()
        onLongPressAction?.invoke(this, alternates)
        return true
    }

    /**
     * Delivers one press when an accessibility service activates this key.
     *
     * `isClickable` has been true on these buttons all along, so a screen
     * reader has always offered "double tap to activate" on every one of them,
     * and until this override the offer did nothing at all: the touch listener
     * returns true, so `View.onTouchEvent` never runs and never calls
     * `performClick`, the presses come out of a [GestureDetector] instead, and
     * no `OnClickListener` was ever set for `performClick` to run. An offered
     * action that silently does nothing is worse than an absent one, because
     * the user cannot tell the key from a broken one.
     *
     * Nothing routes a finger through here, so this cannot double-fire: the
     * listener consumes the event before the view's own click handling. That
     * also means an ordinary tap and an assistive activation reach [emitPress]
     * by different paths, which is why this calls it rather than duplicating
     * what a press does.
     *
     * `super` still runs for the click sound and the TYPE_VIEW_CLICKED event a
     * service listens for; the return is `true` because this handled the click
     * regardless of what `super` reports with no listener attached.
     */
    override fun performClick(): Boolean {
        emitPress()
        super.performClick()
        return true
    }

    /**
     * What a screen reader calls this view.
     *
     * A `TextView` that behaves as a button has to say so: the spoken role comes
     * from the node's class name, and `TextView`'s own override put "TextView"
     * there, so every key was announced as its description alone. The key is
     * clickable, offers ACTION_CLICK and ACTION_LONG_CLICK and honours both, so
     * the node described a button in every respect except the one a reader reads
     * the role from.
     */
    override fun getAccessibilityClassName(): CharSequence =
        android.widget.Button::class.java.name

    init {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.MONOSPACE
        maxLines = 1
        minWidth = dpToPx(48)
        minimumHeight = dpToPx(56)
        isClickable = true
        isFocusable = false

        // Auto-shrink text to fit within button width.
        //
        // Called through TextViewCompat rather than the method inherited from
        // AppCompatTextView, which carries @RestrictTo and is meant only for
        // calls from inside AppCompat itself. Same work underneath: the wrapper
        // passes its four arguments straight through to the platform method on
        // API 27 and above, and minSdk here is 33, so the compatibility branch
        // below that is never taken.
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            this, 8, 13, 1, TypedValue.COMPLEX_UNIT_SP
        )

        // Rounded corner background. This is also what applies the key's
        // padding, and the comment in applyRoundedBackground says why the two
        // cannot be separated.
        applyRoundedBackground(context.getColor(R.color.colorExtraKeyBg))

        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Only a key that does something while held has a long press:
                // a modifier switches and a key with alternates opens them. The
                // detector's long press fires once a finger has stayed inside
                // the touch slop for the long-press timeout, and every other
                // key used to press itself there. A swipe that starts slowly
                // does exactly that, so the key went out and the pager then
                // took the drag and turned the page; the ACTION_CANCEL it sends
                // cannot take back a key already sent. Measured on an API 36
                // emulator: F7 held 0.5 s, then swiped, moved the caret and
                // turned the page. Read here, at each touch, because the
                // adapter sets isToggle and alternates after building the key.
                gestureDetector.setIsLongpressEnabled(isToggle || alternates.isNotEmpty())
            }
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isToggle) {
                        alpha = 1.0f
                    }
                    val before = latchBeforeHold
                    latchBeforeHold = null
                    // The touch ended without lifting after a hold had switched
                    // this modifier, so it was never a press: the latch goes
                    // back to where it was before the touch. Mostly that is the
                    // pager taking a drag, which makes the touch a swipe. The
                    // system cancelling the gesture, and the key being removed
                    // under the finger, by a repack (ExtraKeyRow.setupAdapter)
                    // or with its window, end it the same way. A latch spent on
                    // a letter typed during the hold is back there already and
                    // is left alone.
                    //
                    // So is one while another finger is on the row. The pager
                    // drags with the finger that went down last and cancels
                    // every key under a finger when it takes the drag, so the
                    // cancel can come from another finger's swipe, which must
                    // leave this latch as the hold set it: Ctrl held with one
                    // thumb while the other swipes to F5 runs Ctrl+F5. The
                    // cancel cannot say whose drag it was, since what it
                    // carries differs by release: ViewGroup hands it on
                    // untransformed with every pointer up to API 35, and split
                    // to this key's pointer in the 36.1 sources.
                    if (event.action == MotionEvent.ACTION_CANCEL && before != null && before != isToggleActive &&
                        !anotherFingerOnRow()
                    ) {
                        emitPress()
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
        // Whether a modifier is latched was carried by those two colours and
        // nothing else, which is a channel a screen reader cannot read. That
        // did not matter while the keys could not be activated at all; once
        // they could, it created a state the user can change and cannot
        // observe, which is worse than the key doing nothing.
        //
        // stateDescription rather than isSelected: the platform announces a
        // change to it on its own, and it says "on" instead of "selected",
        // which is what a latched modifier is.
        stateDescription = toggleStateDescription(isToggle, isToggleActive)?.let(context::getString)
    }

    fun applyRoundedBackground(color: Int) {
        // Inset rather than a margin, and the difference is the whole point of
        // this drawable being built here. A 2dp gap between keys used to come
        // from marginStart/marginEnd on the layout params, which takes the dp
        // away from the view itself and therefore from what a finger can hit.
        // As an inset it is taken from the drawing of the background instead:
        // the gap between keys is the same 4dp and the touch target is 4dp
        // wider. Measured on a 411dp screen, one visible change comes with it:
        // the trackpad grows by about 5dp, because it never carried a margin
        // and so now takes its weighted share of the dp the margins used to
        // consume. A wider drag area is the right direction for the one
        // control on the row that wants area, so it stays.
        background = InsetDrawable(
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dpToPx(KEY_GAP_DP * 3).toFloat()
            },
            dpToPx(KEY_GAP_DP), 0, dpToPx(KEY_GAP_DP), 0,
        )
        // After the assignment, never before it, and that order is the whole
        // reason the padding lives in a method rather than in one line of init.
        // setBackground asks the drawable for its padding and writes whatever
        // it reports onto the view, and an InsetDrawable reports its insets, so
        // the assignment above replaces the key's padding with the two insets
        // horizontally and zero vertically. That silently dropped the 6dp that
        // keeps the label off the top and bottom edges for as long as the
        // background was assigned last. It has to run on every repaint, not
        // only the first: updateToggleAppearance repaints on every latch and
        // KeyPageAdapter calls in here on every bind.
        applyKeyPadding()
    }

    /**
     * The distance from the label to the edge of the key.
     *
     * Horizontal padding carries the inset as well as the old padding, so the
     * distance from the text to the edge of the drawn key is what it was before
     * the gap moved off the layout params.
     */
    private fun applyKeyPadding() {
        setPadding(dpToPx(4 + KEY_GAP_DP), dpToPx(6), dpToPx(4 + KEY_GAP_DP), dpToPx(6))
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
}

/**
 * The active-state one press reports, given the kind of key and where it stands.
 *
 * Split out of [ExtraKeyButton] because that class is a `View` and cannot be
 * constructed in a JVM unit test -- its initialiser reaches resources, colours
 * and display metrics on the first line. This is the half of the press that is
 * arithmetic, so it can be checked, and it is the half the defect lived in: a
 * long press on a modifier reported a constant `true` instead of the flipped
 * state, so it could not turn a modifier off and could not agree with the
 * button's appearance.
 *
 * Be plain about the limit. This pins what a press should report; it cannot pin
 * that every press asks. That wiring is held by [ExtraKeyButton]'s `emitPress`
 * being the only caller rather than by a test.
 */
internal fun pressedState(isToggle: Boolean, isActive: Boolean): Boolean =
    if (isToggle) !isActive else true

/**
 * What a screen reader should say about a key's latch, or null for a plain key.
 *
 * Split out for the same reason as [pressedState]: [ExtraKeyButton] is a `View`
 * whose initialiser reaches resources on its first line, so a JVM test cannot
 * construct one, and this is the half that is arithmetic.
 *
 * A resource id rather than the words, so that this stays the half that is
 * arithmetic. Resolving it needs a `Context`, which is exactly what a JVM test
 * does not have; returning the id keeps the decision testable and leaves the
 * lookup to the caller, which is a `View` and has one.
 *
 * Null for a plain key states the contract rather than clearing anything today:
 * [KeyPageAdapter] builds a fresh button on every bind, after
 * `removeAllViews`, so no view carries a previous key's state into its next
 * life. The null branch is unreachable from the adapter, since it only assigns
 * `isToggleActive` to keys that are toggles. It is here so that a future caller
 * who does assign it to a plain key gets silence rather than a stray "off".
 */
@StringRes
internal fun toggleStateDescription(isToggle: Boolean, isActive: Boolean): Int? =
    when {
        !isToggle -> null
        isActive -> R.string.key_toggle_on
        else -> R.string.key_toggle_off
    }

/**
 * The gap drawn on each side of a key, in dp.
 *
 * It is a drawing inset, not a layout margin. A margin subtracts from the
 * view, so on a row that divides the width by weight it subtracts from the
 * touch target of every key; an inset subtracts only from the background,
 * leaving the view, and the finger's target, the full share of the row.
 */
internal const val KEY_GAP_DP = 2
