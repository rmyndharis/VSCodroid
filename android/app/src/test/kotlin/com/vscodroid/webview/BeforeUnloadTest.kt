package com.vscodroid.webview

import android.webkit.JsResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Who gets to answer "are you sure you want to leave this page".
 *
 * Reaching this callback at all is the workbench refusing to shut down: the
 * WebView raises it only for a `beforeunload` the page cancelled, and the only
 * thing that cancels it here is a veto over a modified file whose backup has not
 * been written yet, or a save still in flight. So the answer decides whether
 * that work survives.
 *
 * Two directions, and a fix that gets one of them right is worse than none. For
 * a navigation this app started, the user has already chosen through the app's
 * own UI and a browser's modal on top of it is noise: measured, a same-origin
 * `window.open` drew "Changes you made may not be saved" over a navigation that
 * was working. For anything else the dialog is the only place the choice is
 * offered, and answering it for them loses the edits with nothing on screen.
 */
class BeforeUnloadTest {

    /**
     * The refusal branch logs, and `android.util.Log` throws on a plain JVM.
     * Unmocked afterwards because mockkStatic replaces the class for the whole
     * process and this suite runs in one JVM; see BridgeTokenUniformityTest.
     */
    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun client(ours: Boolean) = VSCodroidWebChromeClient({ ours }) { true }

    @Test
    fun `a navigation this app started is let through`() {
        val result = mockk<JsResult>(relaxed = true)

        val handled = client(ours = true)
            .onJsBeforeUnload(null, "http://127.0.0.1:13337/", "", result)

        assertTrue(
            handled,
            "the client reported the confirm as unhandled, so the platform draws the " +
                "browser's own modal over a navigation the user already chose",
        )
        verify(exactly = 1) { result.confirm() }
        verify(exactly = 0) { result.cancel() }
    }

    @Test
    fun `anything else keeps the platform's dialog`() {
        val result = mockk<JsResult>(relaxed = true)

        val handled = client(ours = false)
            .onJsBeforeUnload(null, "http://127.0.0.1:13337/", "", result)

        assertFalse(
            handled,
            "the client claimed a page-initiated navigation. Reaching here means the " +
                "editor vetoed leaving because it has work it cannot yet recover, and " +
                "confirming for the user throws that away with nothing on screen",
        )
        verify(exactly = 0) { result.confirm() }
        verify(exactly = 0) { result.cancel() }
    }

    @Test
    fun `a missing result is not a crash`() {
        assertTrue(
            client(ours = true).onJsBeforeUnload(null, "http://127.0.0.1:13337/", "", null),
            "the platform passes a null result on some paths, and throwing here takes " +
                "the renderer's navigation with it",
        )
    }
}
