package com.vscodroid.webview

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import com.vscodroid.util.Logger

class VSCodroidWebChromeClient : WebChromeClient() {

    private val tag = "WebChromeClient"

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val message = "[JS:${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}] ${consoleMessage.message()}"
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
