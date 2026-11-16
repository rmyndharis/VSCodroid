package com.vscodroid.webview

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.webkit.WebView
import com.vscodroid.util.Logger

/**
 * The editor's WebView. It differs from the platform's in one answer: whether it is a text editor to the input method, which [textEditorHold] keeps at yes
 * through a turn of the phone for a page that was an editor. The platform asks when a window gains the focus, and a window that is the focused one already
 * and has no editor loses its keyboard then: see [TextEditorHold].
 *
 * Everything else is the platform's WebView, and so is the answer whenever no hold is armed.
 */
class EditorWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {

    lateinit var textEditorHold: TextEditorHold
        private set

    init {
        textEditorHold = TextEditorHold(
            now = { SystemClock.uptimeMillis() },
            onAnswer = { real, held, sinceArmedMs -> logAnswer(real, held, sinceArmedMs) },
        )
    }

    /** What the page says it is, without the hold: the answer the platform would get from a plain WebView. */
    fun realTextEditor(): Boolean = super.onCheckIsTextEditor()

    // A question the platform's constructor asks before the hold exists (none does today) gets the real answer.
    override fun onCheckIsTextEditor(): Boolean {
        val real = super.onCheckIsTextEditor()
        return if (::textEditorHold.isInitialized) textEditorHold.answer(real) else real
    }

    /**
     * One debug line for an answer worth one (see [TextEditorHold.onAnswer]): the real answer, the held part, the age of the hold and the frames that asked,
     * which is how the question of a window's focus gain is told from the others. Class and method names only: no text, no token and no path.
     */
    private fun logAnswer(real: Boolean, held: Boolean, sinceArmedMs: Long) {
        // The stack is not worth capturing for a line nobody will read: a release build writes none.
        if (!Logger.debugEnabled) return
        val callers = Throwable().stackTrace
            .drop(2)
            .filterNot { it.className.startsWith("com.vscodroid.webview.EditorWebView") || it.className.startsWith("com.vscodroid.webview.TextEditorHold") }
            .take(3)
            .joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName }
        Logger.d(TAG, "text editor answer: real=$real held=$held sinceArmed=${if (sinceArmedMs < 0) "never" else "${sinceArmedMs}ms"} asker=$callers")
    }

    private companion object {
        const val TAG = "EditorWebView"
    }
}
