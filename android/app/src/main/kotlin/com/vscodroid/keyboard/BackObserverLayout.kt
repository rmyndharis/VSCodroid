package com.vscodroid.keyboard

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.LinearLayout

/**
 * The window's content, which is where a Back is seen before the soft keyboard sees it, on the platforms that hand it over.
 *
 * `View.dispatchKeyEventPreIme` runs ahead of the input method, which otherwise takes the Back and closes itself, so a Back reaches [onBack] here and
 * the activity's own back handling never hears of the one that only closed the keyboard. The event is passed on unchanged: nothing is consumed.
 *
 * Measured on emulators, with the debug line of a hidden report: the Back is seen on Android 10, on Android 12 (a tap on the navigation bar's Back) and on Android 15
 * (the back gesture from the left edge), and on one Android 13 emulator with Gboard 12.4 (8 of 8, where the documentation says the view is not asked: that holds for
 * that keyboard, from 2022, and a current one on a phone may differ). Nothing is expected here where the platform does not offer the Back at that point:
 * Android 13 and 14 with the keyboard up (the documentation of `View.onKeyPreIme` names the bug), and a window whose back callback is on, which is the default for an
 * app targeting API 36 on Android 16. The keyboard's own hide button (the chevron the system draws at the bottom left on Android 15) is not a Back on any version,
 * and a keyboard put away with it is never seen here. Where the key is not handed over, the back gesture leaves a trace in the window's touch events that the
 * activity reads instead: see [EdgeBackGesture].
 */
class BackObserverLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Called for every key event of the Back key, down and up, before the keyboard has seen it, with the event's own time (uptime). */
    var onBack: (eventTimeMs: Long) -> Unit = {}

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) onBack(event.eventTime)
        return super.dispatchKeyEventPreIme(event)
    }
}
