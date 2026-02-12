package com.vscodroid

import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vscodroid.util.ServerReadyHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [SplashActivity].
 *
 * These tests verify the first-run extraction UI and the skip-to-main
 * fast-path when setup is already complete.
 */
@RunWith(AndroidJUnit4::class)
class SplashActivityTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // Default: clear setup state so SplashActivity shows first-run UI
        ServerReadyHelper.clearSetupState(context)
    }

    @After
    fun tearDown() {
        // Restore setup-complete state so other tests can skip first-run
        ServerReadyHelper.markSetupComplete(context)
    }

    @Test
    fun firstRun_showsProgressBar() {
        val scenario = ActivityScenario.launch(SplashActivity::class.java)
        scenario.onActivity { activity ->
            val progressBar = activity.findViewById<ProgressBar>(R.id.progressBar)
            assertNotNull("ProgressBar should exist on first run", progressBar)
            assertEquals(
                "ProgressBar should be visible",
                View.VISIBLE,
                progressBar.visibility
            )
        }
        scenario.close()
    }

    @Test
    fun firstRun_showsExtractingStatusText() {
        val scenario = ActivityScenario.launch(SplashActivity::class.java)
        scenario.onActivity { activity ->
            val statusText = activity.findViewById<TextView>(R.id.statusText)
            assertNotNull("Status text should exist on first run", statusText)
            val text = statusText.text.toString().lowercase()
            assertTrue(
                "Status should mention extracting, got: $text",
                text.contains("extract") || text.contains("setting up") || text.contains("preparing")
            )
        }
        scenario.close()
    }

    @Test
    fun subsequentLaunch_skipsSetup() {
        // Pre-populate setup-complete prefs
        ServerReadyHelper.markSetupComplete(context)

        val scenario = ActivityScenario.launch(SplashActivity::class.java)

        // SplashActivity should call finish() and start MainActivity.
        // After finish(), the activity state moves to DESTROYED.
        // Give it a moment to transition.
        Thread.sleep(2000)

        // The scenario should have moved past SplashActivity.
        // We can't easily assert MainActivity launched, but we can verify
        // SplashActivity didn't stay on the extraction layout.
        scenario.onActivity { activity ->
            // If we reach here, the activity is still alive — check it's finishing
            assertTrue(
                "SplashActivity should be finishing when setup is complete",
                activity.isFinishing
            )
        }
        scenario.close()
    }

    @Test
    fun firstRun_progressBarStartsAtZero() {
        val scenario = ActivityScenario.launch(SplashActivity::class.java)
        scenario.onActivity { activity ->
            val progressBar = activity.findViewById<ProgressBar>(R.id.progressBar)
            assertNotNull(progressBar)
            assertEquals("Progress should start at 0", 0, progressBar.progress)
        }
        scenario.close()
    }
}
