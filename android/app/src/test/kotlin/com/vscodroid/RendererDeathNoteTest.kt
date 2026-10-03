package com.vscodroid

import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.Logger
import com.vscodroid.webview.VSCodroidWebViewClient
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That every renderer death is noted for the bug report, whichever client is
 * installed when it happens.
 *
 * Two clients answer `onRenderProcessGone` over one WebView's life: the
 * workbench's [VSCodroidWebViewClient], and the bootstrap client `MainActivity`
 * installs until the server is ready and again after every rebuild. A renderer
 * killed under the memory a starting server takes dies under the second, so a
 * note made by one of them alone misses the deaths of the other.
 *
 * NEGATIVE CONTROL, run: with the `CrashReporter.recordRendererDeath` call
 * removed from either override, the case for it reddens.
 */
class RendererDeathNoteTest {

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `the workbench client notes a renderer death before the view is rebuilt`() {
        mockkObject(Logger)
        every { Logger.e(any(), any(), any()) } just Runs
        val order = mutableListOf<String>()
        mockkObject(CrashReporter)
        val context = mockk<Context>()
        val view = mockk<WebView> { every { this@mockk.context } returns context }
        val detail = mockk<RenderProcessGoneDetail>(relaxed = true)
        every { CrashReporter.recordRendererDeath(context, detail) } answers {
            order += "noted"
            Thread()
        }
        val client = VSCodroidWebViewClient(
            allowedPort = 13337,
            resourceRoots = emptyList(),
            sensitiveLocations = emptyList(),
            openFolder = { null },
            connectionToken = { null },
            onCrash = { order += "rebuilt" },
            onPageLoaded = {},
            onRetryServer = {},
        )

        assertTrue(
            client.onRenderProcessGone(view, detail),
            "answering false ends the app process",
        )
        assertEquals(
            listOf("noted", "rebuilt"), order,
            "a renderer death has to be noted for the bug report, with the view it died " +
                "in, before the WebView is rebuilt",
        )
    }

    @Test
    fun `the bootstrap client notes a renderer death before the view is rebuilt`() {
        val bootstrap = SourceScan.body(
            SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"),
            "private fun bootstrapClient()",
        )
        val handler = SourceScan.withoutComments(
            SourceScan.body(bootstrap, "override fun onRenderProcessGone("),
        )

        val noted = handler.indexOf("CrashReporter.recordRendererDeath(view.context, detail)")
        val rebuilt = handler.indexOf("recreateWebView()")
        assertTrue(rebuilt >= 0) {
            "the bootstrap client no longer rebuilds the WebView, so this case is " +
                "measuring nothing"
        }
        assertTrue(noted in 0 until rebuilt) {
            "a renderer that dies before the workbench has loaded is rebuilt without a " +
                "note, so the bug report says nothing about the deaths a starting server " +
                "is most likely to cause"
        }
    }
}
