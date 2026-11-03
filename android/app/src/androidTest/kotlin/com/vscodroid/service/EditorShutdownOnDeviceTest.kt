package com.vscodroid.service

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EditorShutdownOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val name = "es-${UUID.randomUUID().toString().take(8)}"
    private val sandbox = File(app.filesDir, name)
    private val evidence = File(app.cacheDir, "$name-evidence")
    private val preferences = mutableSetOf<String>()
    private val runs = mutableListOf<OwnedRun>()
    private val gateway = EditorGateway()
    private val context = object : ContextWrapper(app) {
        override fun getFilesDir() = File(sandbox, "f")
        override fun getCacheDir() = File(sandbox, "c")
        override fun getExternalFilesDir(type: String?) = File(sandbox, "e")
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply {
            dataDir = sandbox.path
        }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "${this@EditorShutdownOnDeviceTest.name}-$name"
            preferences += isolated
            return super.getSharedPreferences(isolated, mode)
        }
    }
    private val note get() = File(context.filesDir, "server/editor-server.pid")

    @Test
    fun stopAfterBootstrapDeathEndsTheOwnedEditorAndAllowsANewRun() = runBlocking {
        val domain = File("/proc/self/attr/current").readText().trim()
        assertTrue("must execute in the app domain, got $domain", domain.contains(":untrusted_app"))
        assertTrue(context.filesDir.mkdirs())
        assertTrue(context.cacheDir.mkdirs())
        assertTrue(evidence.mkdirs())
        val manager = ProcessManager(context, gateway)
        try {
            phase("before-extraction")
            extract("vscode-reh", File(context.filesDir, "server/vscode-reh"))
            extract("usr", File(context.filesDir, "usr"))
            for (script in listOf("server.js", "platform-fix.js", "dns-proxy.js", "process-monitor.js")) {
                extract(script, File(context.filesDir, "server/$script"))
            }
            File(context.filesDir, "home/.vscodroid/extensions").mkdirs()
            phase("before-first-start")
            assertTrue("the shipped bootstrap did not start", manager.startServer())
            phase("first-bootstrap-started")
            assertTrue("the isolated editor did not become ready", manager.waitForReady(60_000))
            val first = ownedRun(manager)
            phase("first-editor-ready", first)
            assertOwnedEnvironment(first)

            assertTrue("the captured bootstrap must still be alive", first.bootstrap.isAlive)
            val expectedBootstrapScript = File(context.filesDir, "server/server.js").canonicalFile
            assertTrue(
                "the bootstrap PID must still name this fixture",
                File("/proc/${first.bootstrapPid}/cmdline").readText().split('\u0000').any {
                    it.isNotEmpty() && File(it).canonicalFile == expectedBootstrapScript
                },
            )
            assertOwnedEnvironment(first, first.bootstrapPid.toInt())
            // Android's inherited destroyForcibly sends SIGTERM; request SIGKILL explicitly.
            Process.killProcess(first.bootstrapPid.toInt())
            phase("bootstrap-signalled", first)
            assertTrue("the owned bootstrap did not exit", first.bootstrap.waitFor(5, TimeUnit.SECONDS))
            assertFalse(first.bootstrap.isAlive)
            assertEquals("the bootstrap must have died from SIGKILL", 137, first.bootstrap.exitValue())
            assertTrue("the editor did not survive its bootstrap", sameEditorAlive(first))
            assertTrue("the surviving editor must still accept its private transport", backendOpen(first))
            assertEquals("the surviving editor must still serve its socket", 200, version(first.port))
            phase("bootstrap-dead-editor-serving", first)

            manager.stopServer()
            phase("dead-bootstrap-stop-returned", first)
            await("the recorded editor and its private transport to stop") {
                !sameEditorAlive(first) && !backendOpen(first)
            }
            assertReservedUnavailable(first.port)
            assertFalse(note.exists())
            manager.stopServer()
            assertFalse(manager.hasLiveServer())
            assertFalse(note.exists())
            assertReservedUnavailable(first.port)
            phase("repeated-stop-complete", first)

            assertTrue("a fresh editor must start after Stop", manager.startServer())
            phase("second-bootstrap-started")
            assertTrue("the next isolated editor did not become ready", manager.waitForReady(60_000))
            val second = ownedRun(manager)
            assertNotEquals(first.runId, second.runId)
            assertNotEquals(first.backendSocket, second.backendSocket)
            assertEquals("a restart must retain the WebView origin", first.port, second.port)
            assertOwnedEnvironment(second)
            assertEquals(200, version(second.port))
            phase("second-editor-ready", second)
            manager.stopServer()
            phase("cooperative-stop-returned", second)
            await("the cooperative bootstrap, editor and private transport to stop") {
                !second.bootstrap.isAlive && !sameEditorAlive(second) && !backendOpen(second)
            }
            assertReservedUnavailable(second.port)
            assertFalse(note.exists())
            manager.stopServer()
            assertFalse(manager.hasLiveServer())
            assertReservedUnavailable(second.port)
            phase("cooperative-repeated-stop-complete", second)
        } finally {
            try {
                manager.stopServer()
                for (run in runs) {
                    run.bootstrap.destroyForcibly()
                    if (sameEditorAlive(run)) Process.killProcess(run.pid)
                }
                await("all fixture-owned editors to exit") { runs.none { sameEditorAlive(it) } }
                assertTrue("the extracted fixture could not be removed", sandbox.deleteRecursively())
                preferences.forEach {
                    assertTrue("the fixture preferences could not be removed", app.deleteSharedPreferences(it))
                }
            } finally {
                gateway.close()
            }
        }
    }

    private fun ownedRun(manager: ProcessManager): OwnedRun {
        val recorded = JSONObject(note.readText())
        val pid = recorded.getInt("pid")
        val bootstrap = ProcessManager::class.java.getDeclaredField("serverProcess").let {
            it.isAccessible = true
            it.get(manager) as java.lang.Process
        }
        val bootstrapPid = checkNotNull(manager.getServerPid())
        val parentPid = File("/proc/$pid/stat").readText().let {
            it.substring(it.lastIndexOf(')') + 2).split(' ')[1].toLong()
        }
        assertEquals("the editor must be a child of the captured bootstrap", bootstrapPid, parentPid)
        val runId = recorded.getString("runId")
        val backendSocket = File(recorded.getString("socketPath"))
        assertEquals("the private transport must belong to this run", editorBackendSocket(context, runId), backendSocket)
        val run = OwnedRun(pid, recorded.getInt("port"), runId, stat(pid)!!.second, bootstrapPid, bootstrap, backendSocket)
        assertTrue(run.runId.isNotEmpty())
        assertEquals(manager.port, run.port)
        assertTrue("the note must name this fixture's editor", sameEditorAlive(run))
        assertTrue("the noted editor must accept its private transport", backendOpen(run))
        runs += run
        return run
    }

    private fun assertOwnedEnvironment(run: OwnedRun, pid: Int = run.pid) {
        val entries = File("/proc/$pid/environ").readText().split('\u0000')
        assertTrue("the real app must read its owned process identity", "VSCODROID_SERVER_RUN_ID=${run.runId}" in entries)
    }

    private fun stat(pid: Int): Pair<String, String>? = runCatching {
        val text = File("/proc/$pid/stat").readText()
        val fields = text.substring(text.lastIndexOf(')') + 2).split(' ')
        fields[0] to fields[19]
    }.getOrNull()

    private fun sameEditorAlive(run: OwnedRun): Boolean {
        val state = stat(run.pid) ?: return false
        return state.first != "Z" && state.second == run.startTicks && runCatching {
            File("/proc/${run.pid}/cmdline").readText()
                .contains(File(context.filesDir, "server/vscode-reh/out/server-main.js").canonicalPath)
        }.getOrDefault(false)
    }

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

    private fun backendOpen(run: OwnedRun): Boolean = runCatching {
        connectEditorBackend(run.backendSocket).use { }
        true
    }.getOrDefault(false)

    private fun assertReservedUnavailable(port: Int) {
        assertTrue("the editor origin was released", gateway.isBound(port))
        assertEquals("the reserved origin must report an unavailable editor", 503, version(port))
    }

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!ready() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue("timed out waiting for $description", ready())
    }

    private fun extract(asset: String, destination: File) {
        val children = app.assets.list(asset).orEmpty()
        if (children.isNotEmpty()) {
            check(destination.mkdirs())
            children.forEach { extract("$asset/$it", File(destination, it)) }
        } else {
            destination.parentFile!!.mkdirs()
            app.assets.open(asset).use { input -> destination.outputStream().use { input.copyTo(it) } }
        }
    }

    private fun phase(stage: String, run: OwnedRun? = null) {
        val metadata = JSONObject().put("stage", stage).put("uid", Process.myUid())
            .put("atElapsedMs", SystemClock.elapsedRealtime()).put("appPid", Process.myPid())
            .put("domain", File("/proc/self/attr/current").readText().trim())
        if (run != null) metadata.put("editorPid", run.pid).put("bootstrapPid", run.bootstrapPid)
            .put("port", run.port).put("runId", run.runId)
        File(evidence, "$stage.json").writeText(metadata.toString())
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(evidence, "$stage.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
        instrumentation.sendStatus(2, Bundle().apply { putString("editorShutdownEvidence", File(evidence, stage).path) })
    }

    private data class OwnedRun(
        val pid: Int,
        val port: Int,
        val runId: String,
        val startTicks: String,
        val bootstrapPid: Long,
        val bootstrap: java.lang.Process,
        val backendSocket: File,
    )
}
