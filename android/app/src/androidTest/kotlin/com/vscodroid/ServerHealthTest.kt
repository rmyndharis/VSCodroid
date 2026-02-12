package com.vscodroid

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vscodroid.util.ServerReadyHelper
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Instrumented tests for VS Code server health.
 *
 * These tests launch the full app (SplashActivity -> first-run -> MainActivity -> server)
 * and verify the server comes up and stays healthy.
 *
 * Note: These are slow (60s+ timeout) and require a clean-ish app state.
 * Run separately from the fast Activity UI tests.
 */
@RunWith(AndroidJUnit4::class)
class ServerHealthTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // Let first-run setup complete naturally for server tests.
        // If it was already done, the server may already be running.
        ServerReadyHelper.markSetupComplete(context)
    }

    @Test
    fun server_becomesReady() {
        // Launch the full flow so NodeService starts
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

        // Wait for the server to be ready
        val portReady = ServerReadyHelper.waitForPort(13337, timeoutMs = 60_000L)
        if (!portReady) {
            // If default port doesn't work, test is inconclusive
            assertTrue("Server port should be reachable", false)
            return
        }

        // Verify HTTP health check
        val healthy = ServerReadyHelper.healthCheck(13337, timeoutMs = 10_000L)
        assertTrue("HTTP health check should succeed", healthy)
        scenario.close()
    }

    @Test
    fun server_survivesActivityRecreation() {
        val scenario = ActivityScenario.launch(SplashActivity::class.java)

        // Wait for server
        val portReady = ServerReadyHelper.waitForPort(13337, timeoutMs = 60_000L)
        if (!portReady) {
            assertTrue("Server should be reachable before recreation", false)
            return
        }

        // Simulate configuration change (rotation)
        scenario.recreate()

        // Give a moment for the activity to rebind
        Thread.sleep(3000)

        // Server should still be running (it's in a foreground service)
        val stillHealthy = ServerReadyHelper.healthCheck(13337, timeoutMs = 10_000L)
        assertTrue("Server should survive activity recreation", stillHealthy)
        scenario.close()
    }
}
