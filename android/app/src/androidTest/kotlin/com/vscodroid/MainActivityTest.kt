package com.vscodroid

import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.vscodroid.keyboard.ExtraKeyRow
import com.vscodroid.util.ServerReadyHelper
import org.hamcrest.CoreMatchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Instrumented tests for [MainActivity].
 *
 * Pre-populates setup prefs so SplashActivity fast-forwards to MainActivity.
 * These tests verify UI structure, the WebView's initial page, and the About dialog.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    /**
     * MainActivity asks for POST_NOTIFICATIONS on every launch where it is not
     * granted, and the system prompt takes window focus. Espresso answers a
     * root without focus with RootViewWithoutFocusException after ten seconds,
     * so the two About cases below, which look inside the dialog's own window,
     * went red on a device nobody had tapped Allow on. Granted here rather than
     * by an operator.
     *
     * Only from API 33, where the permission exists: MainActivity asks for it on
     * no earlier release, so there is no prompt to answer, and granting one the
     * platform has never heard of fails the rule before the first case runs.
     */
    @get:Rule
    val notifications: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RuleChain.emptyRuleChain()
        }

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
    fun webView_startsOnPlaceholderOrWorkbench() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // The placeholder is loaded from onCreate, before launch() returns, so the
        // URL is expected on the first read; the deadline is for the day it is not,
        // and costs a run that has it nothing. A flat two-second sleep stood here
        // and bought exactly two seconds.
        val deadline = System.currentTimeMillis() + 5_000L
        var url: String? = null
        while (url == null && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                url = activity.findViewById<WebView>(R.id.webView).url
            }
            if (url == null) Thread.sleep(50)
        }

        // Two answers are right. The "Starting server..." placeholder is the
        // ordinary one. The workbench is the other: the foreground service outlives
        // the activity, so a case that ran before this one leaves the server up,
        // and a fresh activity that finds it ready navigates as soon as it binds.
        // What neither is: null, or the about:blank an error page loads under.
        val loaded = url
        assertTrue(
            "WebView should show the placeholder or the workbench, url=$loaded",
            loaded != null && (loaded.startsWith("data:") || loaded.startsWith("http://127.0.0.1"))
        )
        scenario.close()
    }

    @Test
    fun aboutDialog_showsTitle() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var aboutTitle: String
            scenario.onActivity {
                aboutTitle = it.getString(R.string.about_title)
                assertTrue("About title must not be blank", aboutTitle.isNotBlank())
                it.showAboutDialog()
            }

            onView(withText(aboutTitle))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun aboutDialog_containsDisclaimer() {
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        ).getString(R.string.legal_disclaimer)

        // English wording is checked independently of the active dialog language.
        assertTrue(
            "Disclaimer should mention MIT-licensed",
            english.contains("MIT-licensed")
        )
        assertTrue(
            "Disclaimer should name the trademark and the disaffiliation: $english",
            english.contains("trademarks of Microsoft") &&
                english.contains("Not affiliated with or endorsed by Microsoft")
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var disclaimer: String
            scenario.onActivity {
                disclaimer = it.getString(R.string.legal_disclaimer)
                assertTrue("Disclaimer must not be blank", disclaimer.isNotBlank())
                it.showAboutDialog()
            }

            // The message combines version text with the localized disclaimer.
            onView(withId(android.R.id.message))
                .inRoot(isDialog())
                .check(matches(withText(containsString(disclaimer))))
        }
    }
}
