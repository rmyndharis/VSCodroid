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
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = true
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = false
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
