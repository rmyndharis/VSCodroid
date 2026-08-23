package com.vscodroid.webview

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.WebSettings
import android.webkit.WebView
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

        if (Logger.debugEnabled) {
            WebView.setWebContentsDebuggingEnabled(true)
            Logger.d(TAG, "WebView remote debugging enabled")
        }

        Logger.i(TAG, "WebView configured")
    }
}
