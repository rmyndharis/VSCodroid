package com.vscodroid.webview

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.content.edit
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vscodroid.R
import com.vscodroid.util.Logger

object VSCodroidWebView {
    private const val TAG = "WebView"

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            // useWideViewPort stays at its default, false. The UI scale depends on
            // it: without a wide viewport, a page is laid out at the view's width
            // divided by its initial scale (addUiScaleScript in MainActivity.kt).
            // Kept, and not because nothing was asked of it. Every webview
            // document this app renders sits on an https origin, and not because
            // anything here configures one: `branding/product.json` lists
            // `webviewContentExternalBaseUrlTemplate` under `remove`, so
            // `webviewExternalEndpoint` in the shipped `workbench.js` falls back to
            // its own hardcoded `https://{{uuid}}.vscode-cdn.net/...` template. So
            // a preview of a dev server the user is running is plain http inside
            // an https document: mixed content, and blocked under every other
            // mode. Loopback is exempt from that blocking in Chromium, a LAN
            // address is not, and a dev server on the LAN is the ordinary thing a
            // user of this app opens. COMPATIBILITY mode does not help either: an
            // iframe counts as active mixed content and is blocked there too.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            // Off, so page content cannot spend this app's persisted SAF grants.
            // The platform default is on, and turning it on explicitly read as a
            // requirement; nothing here has one. Every SAF folder the user grants
            // is reconciled into a hash-named mirror under `filesDir` and reaches
            // the page as a POSIX path, so no `content://` URI is ever loaded by
            // the workbench, by an injected script or by a bundled extension.
            allowContentAccess = false
            allowFileAccess = false
            // The platform default, restored. Set to false, any frame the editor
            // renders -- notebook output, an extension webview, a preview -- could
            // start audio or video with sound at any moment with the user having
            // touched nothing, which is the same shape as a navigation launching
            // an app without a gesture. Nothing here autoplays on load: the
            // bundled media preview ships `mediaPreview.video.autoPlay` false and
            // the app writes only fontSize, wordWrap and minimap into the default
            // settings, so a tap is what starts playback, and a tap is a gesture.
            //
            // What it costs, stated rather than left to be discovered: a user who
            // turns that setting ON gets nothing, because this flag is all or
            // nothing in the WebView and blocks a muted autoplay that Chrome's own
            // policy would allow. One opt-in setting is the price of closing the
            // channel for every frame the editor renders.
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        // The editor cannot be typed into unless this view holds Android focus.
        // Measured on WebView 109 and 150 and reported from a device: without
        // it the input method serves the DecorView through a fallback
        // connection, everything the keyboard produces is discarded, and the
        // keyboard never rises because the show request comes from an
        // unfocused view. A real tap moves the caret (Blink-side focus works)
        // but never grants the Android view focus, so both halves are done
        // here: once up front, and again on every touch-down. The listener
        // returns false so scrolling and taps are untouched, which is also why
        // suppressing ClickableViewAccessibility is honest: performClick still
        // runs, nothing is consumed.
        //
        // setOnTouchListener is a single setter. A second caller anywhere would
        // replace this listener and kill the fix with every test still green,
        // which is why WebViewFocusTest pins the set of files allowed to call
        // it. If you need to observe touches too, extend THIS listener.
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        webView.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !v.isFocused) v.requestFocus()
            false
        }

        webView.isScrollbarFadingEnabled = true
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        // What shows wherever no page has painted yet, which is white unless set:
        // the first frames of a new view, before the loading page, and longest on
        // the first launch after an update, when dropCacheLeftByEarlierBuild in
        // MainActivity runs first. Measured on an API 36 emulator as two white
        // frames fading in over the dark splash. The colour the workbench page last
        // painted, as the window behind the view and the loading page are, so a
        // light theme does not start dark; see lastPageColor. Between loads the
        // workbench page colours itself before its first paint from the theme it
        // expects to show (INITIAL_THEME_MARKER in assets/server.js), and a
        // plain-text page or the WebView's own error page takes the background of
        // its own colour scheme: see addPlainTextPageScript.
        webView.setBackgroundColor(lastPageColor(webView.context))

        if (Logger.debugEnabled) {
            WebView.setWebContentsDebuggingEnabled(true)
            Logger.d(TAG, "WebView remote debugging enabled")
        }

        Logger.i(TAG, "WebView configured")
    }
}

/**
 * Gives a plain-text page, and the WebView's own error page, the background of its
 * own colour scheme.
 *
 * The server answers a request it refuses with a bare `text/plain` body, among them
 * "Forbidden." for a missing or stale connection token, and Chromium paints such a
 * page no background, so behind its text is the view's own: the colour the
 * workbench page last painted ([VSCodroidWebView.configure],
 * [addPageColorListener]), which is dark under a dark theme. The text follows the
 * device's mode: white in dark mode, where Chromium also paints its dark canvas
 * under it, and black in light mode, which on a dark theme's colour could not be
 * read.
 *
 * The page the WebView shows for a load that failed, "Webpage not available" with
 * net::ERR_CONNECTION_REFUSED, paints no background and names no colour scheme, so
 * its text is black on that colour in both modes. It is what the workbench gets
 * when it navigates while the editor server restarts, for a folder switch or a
 * reload, and it stays until the server is back and MainActivity loads the editor
 * again.
 * Chromium commits it as an HTML document at `chrome-error://chromewebdata/`, whose
 * opaque origin the "*" rule below matches, and runs document-start scripts in it as
 * in any other document: read in the sources of WebView 153, and seen on an API 36
 * emulator with WebView 153, where the pages for a refused and for an unsafe port
 * each had this script's style sheet and, in light mode, a white background.
 *
 * `Canvas` is the background of the page's own scheme: white or that dark canvas for
 * a plain-text page, white for the error page, which is what each showed before the
 * view had a background. Measured in Chromium 151 over a #1E1E1E background.
 *
 * At document start and as an adopted style sheet, so the page's first paint has it
 * and nothing waits for an element. For every origin, for the reason
 * [com.vscodroid.addUiScaleScript] gives; the script does nothing but in those two
 * kinds of top-level document. A frame is left alone: what shows behind one is the
 * page around it.
 */
internal fun addPlainTextPageScript(webView: WebView) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, plainTextPageScript(), setOf("*"))
        } catch (e: RuntimeException) {
            // What is at stake is an error page that is hard to read, never the editor.
            Logger.w("WebView", "Could not add the plain-text page script: ${e.message}")
        }
    }
}

/** The document-start script [addPlainTextPageScript] adds. */
internal fun plainTextPageScript(): String =
    """
    (function() {
        if (window.top !== window) return;
        if (document.contentType !== 'text/plain' && location.protocol !== 'chrome-error:') return;
        var sheet = new CSSStyleSheet();
        sheet.replaceSync(':root { background: Canvas; }');
        document.adoptedStyleSheets = [sheet];
    })();
    """.trimIndent()

/** The object the workbench page posts its background colour to; see [addPageColorListener]. */
internal const val PAGE_COLOR_OBJECT = "vscodroidPageColor"

/**
 * Hands each colour the workbench page paints itself with to [onColor], after
 * giving it to the view as its background.
 *
 * The page script in assets/server.js (INITIAL_THEME_MARKER) posts the colour it
 * paints the page with at the start of each load and again whenever the theme
 * changes. What it is for is the window behind the view, which
 * [com.vscodroid.util.paintWindow] says where it shows: as the space the soft
 * keyboard gives back while a navigation holds the last page's frame, and behind
 * the status and navigation bars. The view's own background shows only where a
 * page paints nothing at all, and is kept on the same colour for that. For every
 * origin, for the reason
 * [com.vscodroid.addUiScaleScript] gives; [pageColorFromMessage] says which
 * messages are taken.
 */
internal fun addPageColorListener(webView: WebView, onColor: (Int) -> Unit) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        try {
            WebViewCompat.addWebMessageListener(webView, PAGE_COLOR_OBJECT, setOf("*")) { view, message, origin, isMainFrame, _ ->
                pageColorFromMessage(message, origin.host, isMainFrame)?.let { color ->
                    view.setBackgroundColor(color)
                    onColor(color)
                }
            }
        } catch (e: RuntimeException) {
            // What is at stake is the colour of a band for a moment, never the editor.
            Logger.w("WebView", "Could not add the page colour listener: ${e.message}")
        }
    }
}

/**
 * The colour a message to [PAGE_COLOR_OBJECT] asks for, or null to leave the view
 * as it is. Only the workbench page sets it: the top frame on the loopback address,
 * which is where the page script runs, since any frame can post. Only an opaque
 * `#rrggbb`, which is how the workbench writes an opaque theme colour; a
 * translucent one would let the window behind show through.
 *
 * Only a string, tested before `data` is read. A page can post an ArrayBuffer as
 * well, which androidx.webkit hands over as one wherever the WebView supports
 * WEB_MESSAGE_ARRAY_BUFFER, and `data` throws IllegalStateException for that, in
 * the callback the WebView runs on the UI thread.
 */
internal fun pageColorFromMessage(message: WebMessageCompat, host: String?, isMainFrame: Boolean): Int? {
    if (!isMainFrame || (host != "127.0.0.1" && host != "localhost")) return null
    if (message.type != WebMessageCompat.TYPE_STRING) return null
    val data = message.data
    if (data == null || !Regex("#[0-9a-fA-F]{6}").matches(data)) return null
    return (0xFF shl 24) or data.substring(1).toInt(16)
}

/**
 * The preferences file MainActivity, SplashActivity, PortFinder, NodeService and
 * ProcessManager share.
 */
private const val PAGE_COLOR_PREFS = "vscodroid"

private const val KEY_PAGE_COLOR = "page_color"

/**
 * The colour the workbench page last posted, or the theme's window colour before
 * it ever has.
 *
 * What the screens before the workbench start on: the windows of SplashActivity
 * and MainActivity with their bars, the setup screen's text, this view and the
 * loading page. Each was the dark window colour whatever the editor's theme, so
 * under a light one every cold start and every first launch after an update was
 * dark until the workbench painted: 2.3 s and 40 s on an API 36 emulator.
 */
internal fun lastPageColor(context: Context): Int =
    context.getSharedPreferences(PAGE_COLOR_PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_PAGE_COLOR, context.getColor(R.color.colorBackground))

/** Keeps [color] for [lastPageColor]; false when it is the colour kept already. */
internal fun keepPageColor(context: Context, color: Int): Boolean {
    val prefs = context.getSharedPreferences(PAGE_COLOR_PREFS, Context.MODE_PRIVATE)
    // 0 for none: an opaque colour, the only kind taken, is never 0.
    if (prefs.getInt(KEY_PAGE_COLOR, 0) == color) return false
    prefs.edit { putInt(KEY_PAGE_COLOR, color) }
    return true
}
