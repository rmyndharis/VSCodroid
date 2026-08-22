package com.vscodroid

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vscodroid.util.ServerReadyHelper
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for [SplashActivity].
 *
 * SplashActivity is transitional; it finishes itself after setup completes
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
        // Both version keys, through the shared helper. Removing setup_version
        // alone does force extraction, because the staleness test is an OR, but
        // it leaves a version code behind that names a build no record here
        // agrees with, for whatever runs next in the same session to read.
        ServerReadyHelper.clearSetupState(context)
        // After the clear, which also removes this: keep toolchain_picker_shown
        // true to prevent the picker UI from blocking (it waits for user
        // interaction, and it is now offered on any launch that has not answered
        // it rather than only on the first).
        getAppPrefs().edit().putBoolean("toolchain_picker_shown", true).commit()
    }

    @After
    fun tearDown() {
        ServerReadyHelper.markSetupComplete(context)
    }

    // `firstRun_launchesWithoutCrash` stood here. It launched the activity, slept a
    // flat 60 s and closed the scenario without asserting anything, so the only
    // failure it could report was a throw out of onCreate -- which the test below
    // also reports, while additionally catching a setup that ends in showSetupError()
    // instead of markSetupComplete(). Its mutation coverage was a strict subset, and
    // it cost 60.7 s of a 22-test run to add nothing.

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
        // Asserted, not assumed, for the reason ServerHealthTest records: a skip
        // reports zero failures and is indistinguishable from a pass. This one runs
        // first on a clean device -- JUnit 4 orders methods by name hash, and this
        // name sorts ahead of firstRun_extractionSetsVersion, which is what would
        // have extracted the assets -- so it was the last silent skip left in this
        // directory after that class was fixed.
        val serverJs = File(context.filesDir, "server/server.js")
        assertTrue(
            "Assets are not extracted on this install, so there is no previous launch " +
                "to skip. Launch the app once via SplashActivity and re-run.",
            serverJs.exists(),
        )
        val before = serverJs.lastModified()
        val versionBefore = getSetupPrefs().getString("setup_version", null)
        val codeBefore = getSetupPrefs().getInt("setup_version_code", 0)

        val scenario = ActivityScenario.launch(SplashActivity::class.java)
        Thread.sleep(3000)  // give it time to transition

        val version = getSetupPrefs().getString("setup_version", null)
        assertNotNull("setup_version should still be set after skip", version)
        assertEquals("setup_version changed across a launch that skipped setup", versionBefore, version)
        // The other half of the pair, which is what the skip is decided on: either
        // one differing re-runs the whole of setup, so a launch that rewrote only
        // the code would look identical to this test while having extracted 810 MB.
        assertEquals(
            "setup_version_code changed across a launch that skipped setup",
            codeBefore,
            getSetupPrefs().getInt("setup_version_code", 0),
        )
        assertEquals(
            "server.js was rewritten, so extraction ran instead of being skipped",
            before,
            serverJs.lastModified(),
        )
        scenario.close()
    }

}
