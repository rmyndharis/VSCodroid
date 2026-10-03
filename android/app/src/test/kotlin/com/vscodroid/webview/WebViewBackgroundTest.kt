package com.vscodroid.webview

import android.content.Context
import android.webkit.WebView
import com.vscodroid.R
import com.vscodroid.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What the WebView shows where no page has painted yet.
 *
 * Unset, that is white, and on the first launch after an update it showed as two
 * white frames fading in over the dark splash before the loading page painted
 * (measured on an API 36 emulator). Asserted through `configure`, which every
 * WebView of the editor goes through, the one rebuilt after a renderer crash
 * included.
 *
 * NEGATIVE CONTROL: without the `setBackgroundColor` call in `configure` the
 * case below fails.
 */
class WebViewBackgroundTest {

    private val webView = mockk<WebView>(relaxed = true)
    private val context = mockk<Context>()

    @BeforeEach
    fun setUp() {
        // configure() logs through android.util.Log and, behind Logger.debugEnabled,
        // calls a static WebView method; both are android.jar stubs that throw on
        // the JVM. See WebViewSettingsTest, which needs the same.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any<String>()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        mockkObject(Logger)
        every { Logger.debugEnabled } returns false
        every { webView.context } returns context
        every { context.getColor(R.color.colorBackground) } returns WINDOW
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a page that has not painted yet shows the window colour, not white`() {
        VSCodroidWebView.configure(webView)

        verify { webView.setBackgroundColor(WINDOW) }
    }

    private companion object {
        /** What `colorBackground` resolves to, as the window background does. */
        const val WINDOW = 0xFF1E1E1E.toInt()
    }
}
