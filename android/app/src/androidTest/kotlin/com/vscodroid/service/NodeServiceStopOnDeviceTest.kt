package com.vscodroid.service

import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.vscodroid.MainActivity
import com.vscodroid.R
import com.vscodroid.VSCodroidApp
import com.vscodroid.util.PortFinder
import com.vscodroid.util.ServerReadyHelper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Stop Server pressed on the running card, against the real service, the real server and the real
 * screen.
 *
 * `EditorShutdownOnDeviceTest` drives `ProcessManager.stopServer` itself, and `NodeServiceStopTest`
 * runs the service's half with the framework mocked. What only a device shows is the whole path: the
 * card's own action reaches the started service, the server ends off the main thread, and the card,
 * the screen and the editor all go.
 *
 * Needs the server assets extracted, as `ServerHealthTest` does, and fails rather than skipping when
 * they are not.
 */
@RunWith(AndroidJUnit4::class)
class NodeServiceStopOnDeviceTest {

    /** Without the permission from API 33 the card is not posted, so there would be no Stop to press. */
    @get:Rule
    val notifications: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RuleChain.emptyRuleChain()
        }

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val note get() = File(app.filesDir, "server/editor-server.pid")

    @Before
    fun setUp() {
        assertTrue(
            "Server assets are not extracted on this install, so nothing here can run. " +
                "Launch the app once via SplashActivity and re-run.",
            File(app.filesDir, "server/vscode-reh/out/server-main.js").exists(),
        )
        ServerReadyHelper.markSetupComplete(app)
    }

    @Test
    fun stopOnTheCardEndsTheServerTheCardAndTheEditor() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val port = ServerReadyHelper.waitForServer(app, timeoutMs = 60_000L)
            assertTrue("the server never became reachable", port != 0)
            val editor = JSONObject(note.readText()).getInt("pid")
            val started = startTicks(editor)
            assertNotNull("the editor the note names is not running", started)

            val shade = app.getSystemService(NotificationManager::class.java)
            await("the server's card") { shade.activeNotifications.any { it.id == VSCodroidApp.NOTIFICATION_ID } }
            val card = shade.activeNotifications.first { it.id == VSCodroidApp.NOTIFICATION_ID }
            val stop = card.notification.actions?.singleOrNull { it.title?.toString() == app.getString(R.string.action_stop) }
            assertNotNull("the running card carries no Stop", stop)

            stop!!.actionIntent.send()

            await("the editor server to exit") { startTicks(editor) != started }
            await("the card to go") { shade.activeNotifications.none { it.id == VSCodroidApp.NOTIFICATION_ID } }
            await("the screen to close") { scenario.state == Lifecycle.State.DESTROYED }
            assertFalse("the note still names the stopped editor", note.exists())
            // Home stays this process's, reserved against other programs, with no editor behind it.
            if (port == PortFinder.rememberedPort(app)) {
                assertTrue("the home origin was given up", EditorGateway.instance.isBound(port))
                assertEquals("the origin answered as though an editor were behind it", 503, version(port))
            }
        } finally {
            scenario.close()
        }
    }

    /** The start time of a live, not zombie, process with that pid, or null; a reused pid differs. */
    private fun startTicks(pid: Int): String? = runCatching {
        val text = File("/proc/$pid/stat").readText()
        val fields = text.substring(text.lastIndexOf(')') + 2).split(' ')
        fields[19].takeIf { fields[0] != "Z" }
    }.getOrNull()

    private fun version(port: Int): Int {
        val connection = URL("http://127.0.0.1:$port/version").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 1_000
            connection.readTimeout = 1_000
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!ready() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue("timed out waiting for $description", ready())
    }
}
