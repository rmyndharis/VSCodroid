package com.vscodroid

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vscodroid.util.ServerReadyHelper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for VS Code server health.
 *
 * These tests require the server assets to be extracted into `filesDir`, which
 * happens on the app's first launch. When they are not, the run FAILS rather than
 * skipping: a skipped test reports zero failures, and zero failures is what a
 * passing run also reports.
 *
 * Run separately from the fast Activity UI tests: these have 60s+ timeouts.
 */
@RunWith(AndroidJUnit4::class)
class ServerHealthTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // Asserted BEFORE markSetupComplete, which is not cosmetic ordering.
        // markSetupComplete writes setup_version and setup_version_code, and
        // FirstRunSetup.isFirstRun() returns whether EITHER differs from what the
        // package reports now -- so running it first would make the very launch
        // this message asks for skip extraction, leaving the operator to repeat a
        // remedy that cannot work.
        //
        // This used to be an assumption, which meant the class silently did not run
        // on a clean install. Measured, not inferred:
        //
        //   - Classes run alphabetically, so ServerHealthTest goes before
        //     SplashActivityTest -- and SplashActivityTest is what triggers the
        //     extraction that puts server-main.js in filesDir.
        //   - connectedAndroidTest installs over any existing app, which keeps
        //     filesDir, and uninstalls afterwards. So the first run on a device
        //     someone else set up inherits their assets and passes; the next run
        //     starts bare and all three of these skipped.
        //   - A skipped test reports zero failures, and reading the failure count
        //     alone cannot tell "passed" from "never ran": on the clean run these
        //     three reported 0.008s, 0.003s and 0.002s. The instruction left here
        //     was to READ THE SKIP COUNT -- an instruction to a human, which is
        //     the part that does not survive contact with a busy afternoon.
        //
        // It now fails, which is what a missing prerequisite deserves when the
        // alternative is being indistinguishable from success. An attempt to
        // arrange the precondition here -- launch SplashActivity and wait for
        // extraction -- did not work and is not shipped rather than shipped
        // unverified, so the fix is still the operator's: launch the app once.
        val serverMainJs = File(context.filesDir, "server/vscode-reh/out/server-main.js")
        assertTrue(
            "Server assets are not extracted on this install, so nothing here can run. " +
                "Launch the app once via SplashActivity and re-run. If a previous run " +
                "already wrote setup_version, first-run setup will skip itself -- " +
                "adb shell pm clear the package, then launch it.",
            serverMainJs.exists()
        )

        // Only now: this is the flag SplashActivity would have written before handing
        // over, and server_survivesActivityRecreation launches MainActivity directly.
        ServerReadyHelper.markSetupComplete(context)
    }

    @Test
    fun server_becomesReady() {
        val scenario = ActivityScenario.launch(SplashActivity::class.java)

        // The server typically needs 10-30s. Use 60s timeout.
        val port = ServerReadyHelper.waitForServer(context, timeoutMs = 60_000L)

        assertTrue(PORT_UNREACHABLE, port != 0)
        scenario.close()
    }

    /**
     * The probe answered by this build's own server, not merely by something.
     *
     * [server_becomesReady] already has `/version` answering 200, because that is
     * what [ServerReadyHelper.waitForServer] waits for, so asking for a 200 again
     * here proved nothing the case above had not. What the answer says is the other
     * half: the commit of the tree this install extracted, which is also how
     * `ProcessManager` tells its own server from another before adopting one.
     */
    @Test
    fun server_answersTheReadinessProbe() {
        val scenario = ActivityScenario.launch(SplashActivity::class.java)

        val port = ServerReadyHelper.waitForServer(context, timeoutMs = 60_000L)
        assertTrue(PORT_UNREACHABLE, port != 0)

        val product = File(context.filesDir, "server/vscode-reh/product.json")
        assertTrue("$product is missing, so nothing names this build's commit", product.isFile)
        val commit = JSONObject(product.readText()).optString("commit")
        assertTrue("$product records no commit", commit.isNotBlank())
        assertEquals(
            "/version on port $port did not answer with the commit of the extracted tree",
            commit.trim(),
            ServerReadyHelper.version(port),
        )
        scenario.close()
    }

    @Test
    fun server_survivesActivityRecreation() {
        // MainActivity, not SplashActivity, and that is the whole reason this
        // test could never pass. SplashActivity finishes itself once setup is
        // done (launchMain calls finish()), so by the time recreate() is
        // called the scenario has nothing to recreate and ActivityScenario
        // throws NullPointerException from its own null check. Nothing noticed,
        // because nothing runs this suite.
        //
        // markSetupComplete() in setUp is what makes launching MainActivity
        // directly legitimate here: it is the flag SplashActivity would have
        // written before handing over.
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        val port = ServerReadyHelper.waitForServer(context, timeoutMs = 60_000L)
        assertTrue(PORT_UNREACHABLE, port != 0)

        // Simulate configuration change (rotation)
        scenario.recreate()
        Thread.sleep(3000)

        // Server should still be running (it's in a foreground service)
        val stillHealthy = ServerReadyHelper.healthCheck(port, timeoutMs = 10_000L)
        assertTrue("Server should survive activity recreation on port $port", stillHealthy)
        scenario.close()
    }

    private companion object {
        /**
         * The port is the one the app's editor gateway bound, read back by
         * `ServerReadyHelper.waitForServer`, with `PortFinder`'s record of the
         * home port standing in only until the gateway is bound. A literal 13337
         * stood here, and then the record alone, and each sent all three cases red
         * on a device where that port was held, for a reason that had nothing to do
         * with the server: since a start that finds home busy binds another port
         * for that process without recording it, only the gateway knows where the
         * server is. What is left for this message is a gateway that never bound,
         * or a server that never answered on the port it did bind.
         */
        const val PORT_UNREACHABLE =
            "Server never answered /version with 200 within 60s. Either the editor " +
                "gateway never bound a port in this process (the service did not start " +
                "the server, see server.log), or it bound one and the server behind it " +
                "never became ready."
    }
}
