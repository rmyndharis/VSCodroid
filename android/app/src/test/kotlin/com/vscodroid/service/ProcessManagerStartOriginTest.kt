package com.vscodroid.service

import android.content.Context
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.util.PortFinder
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies that [ProcessManager.startServer] never reads a concurrently cleared `_port`
 * when building the process environment or command line, refuses to spawn when stopped
 * right before `ProcessBuilder.start()`, and releases a newly bound away origin on
 * spawn failure.
 */
class ProcessManagerStartOriginTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private var storedHome = 0
    private val gateways = mutableListOf<EditorGateway>()
    private val managers = mutableListOf<ProcessManager>()
    private val holders = mutableListOf<ServerSocket>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkObject(Environment)
        every { Environment.getNodePath(any()) } returns "/bin/echo"
        every { Environment.getServerScript(any()) } returns "server.js"
        every { Environment.buildProcessEnvironment(any(), any()) } returns emptyMap()
        every { Environment.getExtensionsDir(any()) } returns "extensions"
        every { Environment.getUserDataDir(any()) } returns "data"
        every { Environment.getLogsDir(any()) } returns File(tempDir, "logs").path
        every { Environment.getServerDir(any()) } returns File(tempDir, "server").path

        storedHome = 0
        val editor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)
        every { editor.putInt("server_port", any()) } answers {
            storedHome = secondArg()
            editor
        }
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getInt("server_port", any()) } answers { storedHome }
        every { prefs.edit() } returns editor

        context = mockk(relaxed = true)
        every { context.filesDir } returns tempDir
        every { context.cacheDir } returns tempDir
        every { context.getSharedPreferences(any(), any()) } returns prefs
    }

    @AfterEach
    fun tearDown() {
        managers.forEach { it.stopServer() }
        gateways.forEach { it.close() }
        holders.forEach { runCatching { it.close() } }
        unmockkAll()
    }

    private fun hold(port: Int): ServerSocket =
        ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).also { holders += it }

    private fun freePort(): Int = PortFinder.findAvailablePort().also {
        assertTrue(it < 32768)
    }

    @Test
    fun `stop and releaseAwayOrigin after bind never feeds port 0 or spawns a process`() {
        val home = freePort()
        storedHome = home
        hold(home)

        val gateway = spyk(EditorGateway()).also { gateways += it }
        val manager = ProcessManager(context, gateway).also { managers += it }

        val envPorts = mutableListOf<Int>()
        every { Environment.buildProcessEnvironment(any(), any()) } answers {
            envPorts += secondArg<Int>()
            emptyMap()
        }
        every { gateway.noteAwayFromHome(any()) } answers {
            callOriginal()
            manager.stopServer()
            manager.releaseAwayOrigin()
        }

        mockkConstructor(ProcessBuilder::class)
        every { anyConstructed<ProcessBuilder>().start() } answers {
            error("ProcessBuilder.start() must not be invoked after stop landed before spawn")
        }

        assertFalse(manager.startServer())
        assertTrue(envPorts.none { it == 0 }, "buildProcessEnvironment must never receive port 0: $envPorts")
        verify(exactly = 0) { anyConstructed<ProcessBuilder>().start() }
        assertNull(gateway.boundPort, "temporary away origin must not remain bound")
    }

    @Test
    fun `stop during buildProcessEnvironment aborts before ProcessBuilder start and releases away origin`() {
        val home = freePort()
        storedHome = home
        hold(home)

        val gateway = spyk(EditorGateway()).also { gateways += it }
        val manager = ProcessManager(context, gateway).also { managers += it }

        every { Environment.buildProcessEnvironment(any(), any()) } answers {
            manager.stopServer()
            emptyMap()
        }

        mockkConstructor(ProcessBuilder::class)
        every { anyConstructed<ProcessBuilder>().start() } answers {
            error("ProcessBuilder.start() must not run when stopped immediately prior")
        }

        assertFalse(manager.startServer())
        verify(exactly = 0) { anyConstructed<ProcessBuilder>().start() }
        assertNull(gateway.boundPort, "newly opened temporary away origin must be released when aborted before spawn")
    }

    @Test
    fun `spawn IOException releases newly bound away origin so the next start returns home`() {
        val home = freePort()
        storedHome = home
        val holder = hold(home)

        val gateway = EditorGateway().also { gateways += it }
        val manager = ProcessManager(context, gateway).also { managers += it }

        every { Environment.getNodePath(any()) } returns "/nonexistent/vscodroid-node-binary"
        assertFalse(manager.startServer(), "missing node binary must fail to start")
        assertNull(gateway.boundPort, "a failed spawn must release the temporary away origin it opened")
        assertEquals(0, manager.port)

        holder.close()
        every { Environment.getNodePath(any()) } returns "/bin/echo"
        val exited = CountDownLatch(1)
        manager.onServerCrashed = { exited.countDown() }
        assertTrue(manager.startServer(), "next start after home is free must succeed")
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertEquals(home, manager.port, "next start must return to home instead of staying on an away port")
    }

    @Test
    fun `spawn IOException keeps the home origin reserved`() {
        val home = freePort()
        storedHome = home

        val gateway = EditorGateway().also { gateways += it }
        val manager = ProcessManager(context, gateway).also { managers += it }

        every { Environment.getNodePath(any()) } returns "/nonexistent/vscodroid-node-binary"
        assertFalse(manager.startServer())
        assertEquals(home, gateway.boundPort, "a failed spawn on the home port must keep home reserved")
        assertNotEquals(0, manager.port)
    }

    @Test
    fun `concurrent releaseAwayOrigin before gateway bind never passes port 0 to gateway bind`() {
        val home = freePort()
        storedHome = home
        hold(home)
        val away = freePort()

        val gateway = spyk(EditorGateway()).also { gateways += it }
        gateway.bind(away)
        val manager = ProcessManager(context, gateway).also { managers += it }

        // Stage a recorded editor note so startServer reads context.filesDir inside
        // recordedBackendSocket right after capturing candidatePort and before gateway.bind.
        val note = File(tempDir, "server/editor-server.pid").apply { parentFile!!.mkdirs() }
        note.writeText("""{"pid":999999,"port":$away,"runId":"stale-run","socketPath":"/none"}""")

        var released = false
        val seenHome = java.util.concurrent.atomic.AtomicBoolean(false)
        val prefs = context.getSharedPreferences("vscodroid_prefs", Context.MODE_PRIVATE)
        every { prefs.getInt("server_port", any()) } answers {
            seenHome.set(true)
            storedHome
        }
        every { context.filesDir } answers {
            if (seenHome.get() && !released) {
                released = true
                manager.releaseAwayOrigin()
            }
            tempDir
        }

        val bindArgs = mutableListOf<Int>()
        every { gateway.bind(any()) } answers {
            bindArgs += firstArg<Int>()
            callOriginal()
        }

        val exited = CountDownLatch(1)
        manager.onServerCrashed = { exited.countDown() }
        assertTrue(manager.startServer())
        assertTrue(exited.await(5, TimeUnit.SECONDS))

        assertTrue(released, "the test must have released the away origin before gateway.bind")
        assertTrue(bindArgs.isNotEmpty(), "gateway.bind must have been invoked")
        assertTrue(bindArgs.none { it == 0 }, "gateway.bind must never receive port 0: $bindArgs")
        assertEquals(away, bindArgs.first(), "gateway.bind must receive the captured candidatePort")
    }
}
