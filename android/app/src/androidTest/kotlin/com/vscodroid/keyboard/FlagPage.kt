package com.vscodroid.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView

/**
 * A page holding the three flags the row pushes, `window.__vscodroid`'s,
 * and answering the row's poll from them a turn later, as a page does.
 * Nothing else of a page: no script it is sent is run.
 *
 * Behind any row whose latches a test reads more than one main-thread turn after
 * setting them. A row with no injector has no page to hold a modifier for, so its
 * poll clears every latch at its first tick, 200 ms after the latch
 * (`ExtraKeyRow.modifierSyncRunnable`), and a test that reads the latch later than
 * that reads the row doing what it should.
 */
internal class FlagPage(context: Context) : WebView(context) {
    var ctrl = false
    private var alt = false
    private var shift = false

    /** How many times the row's poll has asked; read on the main thread. */
    var queries = 0
        private set

    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
        val pushed = PUSH.find(script)
        if (pushed != null) {
            ctrl = pushed.groupValues[1] == "true"
            alt = pushed.groupValues[2] == "true"
            shift = pushed.groupValues[3] == "true"
        } else if (resultCallback != null) {
            queries += 1
            val answer = "{\"c\":$ctrl,\"a\":$alt,\"s\":$shift}"
            Handler(Looper.getMainLooper()).post { resultCallback.onReceiveValue(answer) }
        }
    }

    private companion object {
        /** `KeyInjector.setModifierState`'s script, which is the only push there is. */
        val PUSH = Regex("""\.ctrl=(\w+),window\.__vscodroid\.alt=(\w+),window\.__vscodroid\.shift=(\w+)""")
    }
}
