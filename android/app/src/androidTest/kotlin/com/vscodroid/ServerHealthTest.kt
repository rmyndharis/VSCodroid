package com.vscodroid

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vscodroid.util.ServerReadyHelper
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for VS Code server health.
 *
 * These tests require the VS Code Server assets (vscode-reh/) to be bundled
 * in the APK. If assets are missing (dev build without download scripts),
 * the tests are skipped via [assumeTrue].
 *
 * Run separately from the fast Activity UI tests — these have 60s+ timeouts.
 */
@RunWith(AndroidJUnit4::class)
class ServerHealthTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        ServerReadyHelper.markSetupComplete(context)

        // This class silently does not run on a clean install, and that is a
        // known defect rather than a design. Measured, not inferred:
        //
        //   - Classes run alphabetically, so ServerHealthTest goes before
        //     SplashActivityTest -- and SplashActivityTest is what triggers the
        //     extraction that puts server-main.js in filesDir.
        //   - connectedAndroidTest installs over any existing app, which keeps
        //     filesDir, and uninstalls afterwards. So the first run on a device
        //     someone else set up inherits their assets and passes; the next run
        //     starts bare and all three of these skip.
        //   - A skipped test reports zero failures. Reading the failure count
        //     alone cannot tell "passed" from "never ran": on the clean run these
        //     three reported 0.008s, 0.003s and 0.002s.
        //
        // An attempt to arrange the precondition here -- launch SplashActivity
        // and wait for extraction -- did not work and is not shipped rather than
        // shipped unverified. Whoever fixes it properly should make the skip
        // loud, or order the suite so extraction happens first.
        //
        // Until then: READ THE SKIP COUNT, not just the failure count.
        val serverMainJs = File(context.filesDir, "server/vscode-reh/out/server-main.js")
        assumeTrue(
            "SKIPPING, NOT PASSING: server assets are not extracted on this " +
                "install, so nothing here ran. Launch the app once and re-run.",
            serverMainJs.exists()
        )
    }

    @Test
    fun server_becomesReady() {
        val scenario = ActivityScenario.launch(SplashActivity::class.java)

        // The server typically needs 10-30s. Use 60s timeout.
        val ready = ServerReadyHelper.waitForPort(13337, timeoutMs = 60_000L)

        assertTrue(
            "Server should become reachable on a port within 60s",
            ready
        )
        scenario.close()
    }

    @Test
    fun server_answersTheReadinessProbe() {
        val scenario = ActivityScenario.launch(SplashActivity::class.java)

        val portReady = ServerReadyHelper.waitForPort(13337, timeoutMs = 60_000L)
        assumeTrue("Server port not reachable — skipping health check", portReady)

        val healthy = ServerReadyHelper.healthCheck(13337, timeoutMs = 10_000L)
        assertTrue("HTTP health check should succeed", healthy)
        scenario.close()
    }

    @Test
    fun server_survivesActivityRecreation() {
        // MainActivity, not SplashActivity, and that is the whole reason this
        // test could never pass. SplashActivity carries android:noHistory and
        // finishes itself once setup is done, so by the time recreate() is
        // called the scenario has nothing to recreate and ActivityScenario
        // throws NullPointerException from its own null check. Nothing noticed,
        // because nothing runs this suite.
        //
        // markSetupComplete() in setUp is what makes launching MainActivity
        // directly legitimate here: it is the flag SplashActivity would have
        // written before handing over.
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        val portReady = ServerReadyHelper.waitForPort(13337, timeoutMs = 60_000L)
        assumeTrue("Server port not reachable — skipping recreation test", portReady)

        // Simulate configuration change (rotation)
        scenario.recreate()
        Thread.sleep(3000)

        // Server should still be running (it's in a foreground service)
        val stillHealthy = ServerReadyHelper.healthCheck(13337, timeoutMs = 10_000L)
        assertTrue("Server should survive activity recreation", stillHealthy)
        scenario.close()
    }
}
