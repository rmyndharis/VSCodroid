package com.vscodroid

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vscodroid.util.ServerReadyHelper
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for [SplashActivity].
 *
 * SplashActivity is transitional — it finishes itself after setup completes
 * and starts MainActivity. ActivityScenario.state is unreliable when the
 * tracked activity starts a new activity (lifecycle events get ignored due
 * to intent mismatch). Tests verify side effects (SharedPreferences) and
 * use sleep-based waits instead of lifecycle polling.
 */
@RunWith(AndroidJUnit4::class)
class SplashActivityTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // FirstRunSetup writes setup_version to "vscodroid_setup"
    private fun getSetupPrefs() = context.getSharedPreferences("vscodroid_setup", Context.MODE_PRIVATE)
    // SplashActivity reads toolchain_picker_shown from "vscodroid"
    private fun getAppPrefs() = context.getSharedPreferences("vscodroid", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        // Clear setup_version so SplashActivity runs first-run extraction.
        getSetupPrefs().edit().remove("setup_version").commit()
        // Keep toolchain_picker_shown=true to prevent the picker UI from
        // blocking (it waits for user interaction).
        getAppPrefs().edit().putBoolean("toolchain_picker_shown", true).commit()
    }

    @After
    fun tearDown() {
        ServerReadyHelper.markSetupComplete(context)
    }

    @Test
    fun firstRun_launchesWithoutCrash() {
        // Verify SplashActivity can be launched without throwing.
        // The activity will run extraction and transition to MainActivity.
        val scenario = ActivityScenario.launch(SplashActivity::class.java)
        // If we get here, no crash on launch.
        // Wait for extraction (30-60s on emulator, ~3-5s on device).
        Thread.sleep(60_000)
        scenario.close()
        // Success: no exception thrown
    }

    @Test
    fun firstRun_extractionSetsVersion() {
        val scenario = ActivityScenario.launch(SplashActivity::class.java)

        // Poll for setup_version to be written (extraction completes async).
        // Emulators can take 30-60s for extraction; real devices ~3-5s.
        val deadline = System.currentTimeMillis() + 90_000L
        var version: String? = null
        while (System.currentTimeMillis() < deadline) {
            version = getSetupPrefs().getString("setup_version", null)
            if (version != null) break
            Thread.sleep(500)
        }

        assertTrue(
            "setup_version should be set after extraction within 30s, got: $version",
            version != null && version.isNotEmpty()
        )
        scenario.close()
    }

    @Test
    fun subsequentLaunch_skipsExtraction() {
        ServerReadyHelper.markSetupComplete(context)

        // Observed, not timed. This asserted `elapsed < 12_000` against a clock
        // that included its own Thread.sleep(3000), so what it measured was how
        // fast the machine was rather than whether extraction ran. Three
        // readings of first-run on one emulator came out 61s, 78s and 200s --
        // the last while a Gradle build shared the machine. No threshold
        // survives a 3x spread; one that passes today is only waiting for a
        // busier afternoon.
        //
        // Extraction rewrites server.js. If it is skipped the file is not
        // touched, and that is directly visible.
        val serverJs = File(context.filesDir, "server/server.js")
        assumeTrue("Skipping: assets not extracted yet", serverJs.exists())
        val before = serverJs.lastModified()

        val scenario = ActivityScenario.launch(SplashActivity::class.java)
        Thread.sleep(3000)  // give it time to transition

        val version = getSetupPrefs().getString("setup_version", null)
        assertNotNull("setup_version should still be set after skip", version)
        assertEquals(
            "server.js was rewritten, so extraction ran instead of being skipped",
            before,
            serverJs.lastModified(),
        )
        scenario.close()
    }

    @Test
    fun subsequentLaunch_preservesVersion() {
        ServerReadyHelper.markSetupComplete(context)
        val versionBefore = getSetupPrefs().getString("setup_version", null)

        val scenario = ActivityScenario.launch(SplashActivity::class.java)
        Thread.sleep(3000)

        val versionAfter = getSetupPrefs().getString("setup_version", null)
        assertTrue(
            "setup_version should be preserved: before=$versionBefore, after=$versionAfter",
            versionAfter != null && versionAfter == versionBefore
        )
        scenario.close()
    }
}
