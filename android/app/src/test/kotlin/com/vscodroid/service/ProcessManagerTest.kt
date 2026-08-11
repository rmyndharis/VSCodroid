package com.vscodroid.service

import android.content.Context
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.Runs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for [ProcessManager]'s start guard and port allocation.
 *
 * Regression coverage for issue #3: the guard tested `serverProcess != null`,
 * but only `stopServer()` ever cleared that field — the crash path left the
 * dead Process referenced. Every automatic restart after an unexpected exit
 * was therefore refused, and the app stayed wedged until it was relaunched.
 *
 * [Environment] is stubbed so the spawned command is `/bin/echo`, which lets a
 * start actually succeed without the bundled Node binary. The private state is
 * reached by reflection rather than widening the production API for a test.
 */
class ProcessManagerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var manager: ProcessManager

    @BeforeEach
    fun setUp() {
        // Mock Logger to avoid android.util.Log crashes in JVM tests
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkObject(Environment)
        every { Environment.getNodePath(any()) } returns "/bin/echo"
        every { Environment.getServerScript(any()) } returns "server.js"
        every { Environment.buildProcessEnvironment(any(), any()) } returns emptyMap()
        every { Environment.getExtensionsDir(any()) } returns "extensions"
        every { Environment.getUserDataDir(any()) } returns "data"
        every { Environment.getLogsDir(any()) } returns "logs"

        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns tempDir
        every { context.filesDir } returns tempDir

        manager = ProcessManager(context)
    }

    @AfterEach
    fun tearDown() {
        manager.stopServer()
        unmockkAll()
    }

    @Test
    fun `refuses to start while the process is alive`() {
        manager.serverProcessField = mockk<Process>(relaxed = true) {
            every { isAlive } returns true
        }

        assertFalse(manager.startServer(), "a live server must not be started twice")
        assertEquals(0, manager.port, "the guard must reject before a port is taken")
    }

    @Test
    fun `starts again after the process has died`() {
        // The crash path leaves this reference in place; it must not block a restart.
        manager.serverProcessField = mockk<Process>(relaxed = true) {
            every { isAlive } returns false
        }

        assertTrue(manager.startServer(), "a dead server must be restartable")
    }

    @Test
    fun `keeps the port across a restart`() {
        // The WebView's loaded URL and the bridge's allowed-origin check are bound
        // to the port and are not rebuilt on restart, so it has to stay put.
        manager.portField = 45678
        manager.serverProcessField = mockk<Process>(relaxed = true) {
            every { isAlive } returns false
        }

        assertTrue(manager.startServer())
        assertEquals(45678, manager.port, "restart must reuse the original port")
    }

    @Test
    fun `allocates a port on the first start`() {
        assertTrue(manager.startServer())
        assertNotEquals(0, manager.port, "the first start must allocate a port")
    }
}

/** Reaches [ProcessManager.serverProcess], which is private production state. */
private var ProcessManager.serverProcessField: Process?
    get() = field("serverProcess").get(this) as Process?
    set(value) = field("serverProcess").set(this, value)

/** Reaches [ProcessManager._port], which is private production state. */
private var ProcessManager.portField: Int
    get() = field("_port").getInt(this)
    set(value) = field("_port").setInt(this, value)

private fun field(name: String) =
    ProcessManager::class.java.getDeclaredField(name).apply { isAccessible = true }
