package com.vscodroid.webview

import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.vscodroid.util.Logger

/**
 * @param openFileChooser starts the device file picker and reports whether it
 *   actually got dispatched. `allowMultiple` carries what the page asked for.
 */
class VSCodroidWebChromeClient(
    private val openFileChooser: (allowMultiple: Boolean) -> Boolean
) : WebChromeClient() {

    private val tag = "WebChromeClient"

    /**
     * The one `<input type=file>` still waiting for an answer, or null.
     *
     * Kept per client rather than per Activity because that is the lifetime the
     * waiting element has: a renderer crash replaces this client along with the
     * page, and the replacement must not inherit a callback belonging to a
     * document that no longer exists.
     */
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null

    /**
     * Whether an `<input type=file>` is still waiting for the picker.
     *
     * Read by the Activity on the way back from the background. The picker runs
     * in another app, so browsing storage backgrounds this one exactly as a
     * sign-in does, and the resume rule reloads the page after five minutes: a
     * file chosen at the end of a long browse would arrive at a document that is
     * being torn down, and be lost with nothing said.
     */
    val hasPendingFileChooser: Boolean
        get() = pendingFileChooser != null

    /**
     * Answers the browser's "are you sure you want to leave" for the page.
     *
     * The workbench registers a `beforeunload` handler, and the WebView's default
     * handling of it is a modal asking whether to leave the page, worded for a
     * browser: "Changes you made may not be saved." There is nowhere to leave to.
     * This app holds exactly one document, the user cannot type an address, and
     * every navigation that reaches here is one the app or the workbench decided
     * to perform: opening a folder, restoring the empty window, or the workbench
     * asking for a second window, which on a device is this one. Measured: a
     * same-origin `window.open` put that modal in front of the editor, over a
     * navigation that was working.
     *
     * The confirm is answered rather than the handler suppressed, so nothing
     * about the page changes. Unsaved editor content is not what this protects:
     * the workbench keeps it in browser storage and restores it on the next load,
     * which is why its own reload and its own folder switch pass through here
     * without asking either.
     */
    override fun onJsBeforeUnload(
        view: WebView?,
        url: String?,
        message: String?,
        result: android.webkit.JsResult?,
    ): Boolean {
        result?.confirm()
        return true
    }

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

    /**
     * Opens the device file picker for an `<input type=file>`.
     *
     * The Explorer's `Upload...` command is the caller that matters: it appends
     * a multiple-selection input to the document and waits on its `input` event.
     * Without this override the default client declines, no chooser opens, and
     * the command waits forever with nothing said.
     *
     * Returning true takes ownership of [filePathCallback], which must then be
     * invoked exactly once. Returning true and never invoking it wedges the
     * element for good: the page keeps one request outstanding, so every later
     * Upload on that document does nothing at all, and only a reload clears it.
     * Every path below therefore answers, the failing ones included.
     *
     * The pairing to avoid is answering *and* declining: a callback invoked here
     * on a path that also returns false hands a request back to the framework
     * that has already been answered. What the framework does with a request
     * declined without an answer was not measured here, so nothing below leans
     * on it either way.
     */
    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        // Only one request can be tracked, so anything still outstanding is
        // answered before it is displaced rather than dropped. A displaced
        // callback nobody answers is the permanent wedge described above, and
        // the likeliest way to reach one is a result that never came back.
        answerFileChooser(emptyArray())
        pendingFileChooser = filePathCallback
        if (!openFileChooser(fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE)) {
            Logger.w(tag, "No file picker started; answering the page with nothing")
            answerFileChooser(emptyArray())
        }
        return true
    }

    /**
     * Hands the picker's answer back to the page.
     *
     * An empty list is a cancellation, which the page still has to be told
     * about. Safe to call with nothing outstanding: a result can arrive for a
     * page that has since been replaced, and there is then nobody to answer.
     */
    fun onFileChooserResult(uris: List<Uri>) = answerFileChooser(uris.toTypedArray())

    private fun answerFileChooser(uris: Array<Uri>) {
        val callback = pendingFileChooser ?: return
        pendingFileChooser = null
        callback.onReceiveValue(uris)
    }
}
