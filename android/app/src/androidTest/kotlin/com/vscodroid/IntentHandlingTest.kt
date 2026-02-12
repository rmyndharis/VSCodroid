package com.vscodroid

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vscodroid.util.ServerReadyHelper
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for intent handling in [MainActivity].
 *
 * Verifies that ACTION_VIEW intents with various MIME types launch
 * without crashing, and that invalid schemes are handled gracefully.
 */
@RunWith(AndroidJUnit4::class)
class IntentHandlingTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        ServerReadyHelper.markSetupComplete(context)
    }

    @Test
    fun actionView_textPlain_launchesWithoutCrash() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("content://com.example/test.txt")
            type = "text/plain"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val scenario = ActivityScenario.launch<MainActivity>(intent)

        // If we get here without an exception, the activity launched OK
        scenario.onActivity { activity ->
            assertNotNull("Activity should be created", activity)
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertNotNull("WebView should exist", webView)
        }
        scenario.close()
    }

    @Test
    fun actionView_applicationJson_launchesWithoutCrash() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("content://com.example/config.json")
            type = "application/json"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val scenario = ActivityScenario.launch<MainActivity>(intent)

        scenario.onActivity { activity ->
            assertNotNull("Activity should be created", activity)
        }
        scenario.close()
    }

    @Test
    fun invalidScheme_doesNotNavigateWebView() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("javascript:alert(1)")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val scenario = ActivityScenario.launch<MainActivity>(intent)
        Thread.sleep(2000)

        scenario.onActivity { activity ->
            val webView = activity.findViewById<WebView>(R.id.webView)
            assertNotNull(webView)
            val url = webView.url ?: ""
            // Should NOT have navigated to a javascript: URL
            assertTrue(
                "WebView should not load javascript: scheme, url=$url",
                !url.startsWith("javascript:")
            )
        }
        scenario.close()
    }
}
