package com.vscodroid.webview

import android.webkit.JsResult
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the browser's "are you sure you want to leave" never reaches the user.
 *
 * The workbench registers a `beforeunload` handler, and the WebView answers one
 * with a modal worded for a browser: "Changes you made may not be saved. Are
 * you sure you want to navigate away from this page?" There is nowhere to leave
 * to. This app holds one document, the user cannot type an address, and every
 * navigation is one the app or the workbench decided to perform.
 *
 * Measured before this existed: asking the workbench for a second window, which
 * on a device is this one, put that modal in front of the editor over a
 * navigation that was working.
 *
 * Two halves, and a fix that does only one of them is worse than none. Left
 * unanswered, the [JsResult] holds the renderer's navigation for ever and the
 * page is stuck with no dialog on screen to say why; answered but reported as
 * unhandled, the platform draws its own dialog on top of the answer.
 */
class BeforeUnloadTest {

    @Test
    fun `the page is let go without asking`() {
        val client = VSCodroidWebChromeClient { true }
        val result = mockk<JsResult>(relaxed = true)

        val handled = client.onJsBeforeUnload(null, "http://127.0.0.1:13337/", "", result)

        assertTrue(
            handled,
            "the client reported the confirm as unhandled, so the platform draws the " +
                "browser's own modal over the navigation anyway",
        )
        verify(exactly = 1) { result.confirm() }
        verify(exactly = 0) { result.cancel() }
    }

    @Test
    fun `a missing result is not a crash`() {
        val client = VSCodroidWebChromeClient { true }

        assertTrue(
            client.onJsBeforeUnload(null, "http://127.0.0.1:13337/", "", null),
            "the platform passes a null result on some paths, and throwing here takes the " +
                "renderer's navigation with it",
        )
    }
}
