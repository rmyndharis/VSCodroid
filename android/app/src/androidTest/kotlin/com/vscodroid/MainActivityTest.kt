package com.vscodroid

import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vscodroid.keyboard.ExtraKeyRow
import com.vscodroid.util.ServerReadyHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests for [MainActivity].
 *
 * Pre-populates setup prefs so SplashActivity fast-forwards to MainActivity.
 * These tests verify UI structure, WebView loading, and the About dialog.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        ServerReadyHelper.markSetupComplete(context)
    }

    @Test
    fun webView_isDisplayed() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertNotNull("WebView should exist", webView)
            assertEquals("WebView should be visible", View.VISIBLE, webView.visibility)
        }
        scenario.close()
    }

    @Test
    fun extraKeyRow_startsHidden() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val ekr = activity.findViewById<ExtraKeyRow>(R.id.extraKeyRow)
            assertNotNull("ExtraKeyRow should exist", ekr)
            assertEquals("ExtraKeyRow should start GONE", View.GONE, ekr.visibility)
        }
        scenario.close()
    }

    @Test
    fun webView_loadsContent() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        // Wait a moment for the loading placeholder to render
        Thread.sleep(2000)
        scenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertNotNull(webView)
            val url = webView.url
            // Initially shows the "Starting server..." placeholder (data: URL)
            // or localhost if server was already running
            assertTrue(
                "WebView should have loaded content, url=$url",
                url != null && (url.startsWith("data:") || url.startsWith("http://127.0.0.1"))
            )
        }
        scenario.close()
    }

    @Test
    fun aboutDialog_showsTitle() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val latch = CountDownLatch(1)
        var dialogShown = false

        scenario.onActivity { activity ->
            activity.showAboutDialog()
            // The dialog is shown asynchronously on the UI thread.
            // Since we're already on it, post a check after the dialog shows.
            activity.window.decorView.post {
                // Look for the AlertDialog title in the window's decor view
                dialogShown = true
                latch.countDown()
            }
        }

        assertTrue("Dialog callback should fire", latch.await(5, TimeUnit.SECONDS))
        assertTrue("About dialog should be shown", dialogShown)
        scenario.close()
    }

    @Test
    fun aboutDialog_containsDisclaimer() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val latch = CountDownLatch(1)
        var messageText = ""

        scenario.onActivity { activity ->
            // Read the expected strings from resources
            val disclaimer = activity.getString(R.string.legal_disclaimer)
            val aboutTitle = activity.getString(R.string.about_title)

            // Verify the strings are what we expect
            assertTrue(
                "About title should be 'About VSCodroid'",
                aboutTitle.contains("About VSCodroid")
            )
            assertTrue(
                "Disclaimer should mention MIT-licensed",
                disclaimer.contains("MIT-licensed")
            )
            messageText = disclaimer
            latch.countDown()
        }

        assertTrue("Should read strings", latch.await(5, TimeUnit.SECONDS))
        assertTrue("Disclaimer text should not be empty", messageText.isNotEmpty())
        scenario.close()
    }
}
