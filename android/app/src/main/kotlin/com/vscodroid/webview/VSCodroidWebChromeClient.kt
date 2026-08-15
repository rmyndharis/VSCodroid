package com.vscodroid.webview

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import com.vscodroid.util.Logger

class VSCodroidWebChromeClient : WebChromeClient() {

    private val tag = "WebChromeClient"

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        // Redacted as one string, which covers both halves that can carry the
        // connection token: `sourceId` is a script URL and `message` is arbitrary
        // text the page chose to print. Neither is ours, and the ERROR and WARNING
        // branches below are not gated on a debuggable build, so anything either
        // one carries ships in release. See [redactToken].
        //
        // Whether the token ever actually reaches here is not established -- the
        // server consumes it on `/` and redirects, so the document URL afterwards
        // should not hold it. "Should" is the reason this is redacted anyway.
        val message = redactToken(
            "[JS:${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}] " +
                consoleMessage.message()
        )
        when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Logger.e(tag, message)
            ConsoleMessage.MessageLevel.WARNING -> Logger.w(tag, message)
            ConsoleMessage.MessageLevel.LOG -> Logger.d(tag, message)
            ConsoleMessage.MessageLevel.DEBUG -> Logger.d(tag, message)
            ConsoleMessage.MessageLevel.TIP -> Logger.d(tag, message)
            else -> Logger.d(tag, message)
        }
        return true
    }
}
