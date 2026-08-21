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

            override fun onLongPress(e: MotionEvent) {
                this@ExtraKeyButton.alpha = 1.0f
                if (alternates.isNotEmpty()) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongPressAction?.invoke(this@ExtraKeyButton, alternates)
                    return
                }
                // A key with no alternates delivers one press, the same one a tap
                // delivers, through the same call below.
                //
                // The comment here used to read "treat as repeated key press"
                // while the body did exactly one invoke, and the two cannot both
                // be right: GestureDetector fires onLongPress once per hold and
                // sends nothing on release, so there is no repeat here and never
                // was. What is written now is what happens.
                //
                // It stays that way on purpose, because no key on this row wants
                // repeating. The two that would -- Backspace and the arrows --
                // are not ExtraKeyButtons at all: KeyPages carries neither, the
                // soft keyboard owns backspace (KeyInjector's beforeinput
                // listener already turns Ctrl+Backspace into delete-word), and
                // the arrows come from GesturePad, whose drag emits them
                // continuously through three acceleration gears -- a better
                // answer than key repeat, and the reason those buttons were
                // removed. What is left with no alternates is Tab, Esc and
                // punctuation, where repeating is not a feature but a way to
                // spray `;;;;;;;;` into a source file from one hold that ran
                // long. A repeat loop here would add a timer whose stop path has
                // to survive ACTION_CANCEL, pager swipes, detach and the row
                // being hidden with the IME -- real failure modes, bought for a
                // key that wants none of it.
                emitPress()
            }
        })

    /**
     * Delivers one press of this key.
     *
     * One path for a tap and for a long press, and being one path is the fix
     * rather than tidiness. They used to differ: `onSingleTapUp` flipped
     * `isToggleActive` and reported the new value, while the long-press branch
     * reported a bare `true` and flipped nothing. Ctrl, Alt and Shift are
     * toggles with no alternates, so they are the only toggles that reach that
     * branch -- and holding one a moment too long switched the modifier on
     * inside [ExtraKeyRow] while the button carried on looking off. The next tap
     * then read as "switch on" from the button's side and arrived as "switch
     * off" on the row's, so the modifier the user could see and the modifier
     * being applied were inverted from each other until something reset them.
     *
     * With one call site the two cannot diverge again, which is a stronger
     * guarantee than a test: this is a `View` callback, so nothing in the JVM
     * unit suite can invoke it. [pressedState] carries the part that can be
     * pinned.
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
     * view, and touch exploration never delivers one. Five of the eight
     * alternates are top-level keys on other pages and one is Shift plus
     * backtick, but `'` and `\` are on no page at all, so without this they
     * exist on the row for sighted users only.
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
 * that both gesture callbacks ask. That wiring is held by there being exactly
 * one caller rather than by a test.
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
