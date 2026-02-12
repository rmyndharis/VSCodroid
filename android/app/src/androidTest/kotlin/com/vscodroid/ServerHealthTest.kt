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

        // Skip all server tests if vscode-reh assets aren't bundled.
        // The server can't start without server-main.js.
        val serverMainJs = File(context.filesDir, "server/vscode-reh/out/server-main.js")
        assumeTrue(
            "Skipping: vscode-reh assets not bundled (run download-vscode-server.sh)",
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
    fun server_healthCheckReturns200to499() {
        val scenario = ActivityScenario.launch(SplashActivity::class.java)

        val portReady = ServerReadyHelper.waitForPort(13337, timeoutMs = 60_000L)
        assumeTrue("Server port not reachable — skipping health check", portReady)

        val healthy = ServerReadyHelper.healthCheck(13337, timeoutMs = 10_000L)
        assertTrue("HTTP health check should succeed", healthy)
        scenario.close()
    }

    @Test
    fun server_survivesActivityRecreation() {
        val scenario = ActivityScenario.launch(SplashActivity::class.java)

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
