package com.vscodroid.webview

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import com.vscodroid.util.Logger

object VSCodroidWebView {
    private const val TAG = "WebView"

    @SuppressLint("SetJavaScriptEnabled")
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
