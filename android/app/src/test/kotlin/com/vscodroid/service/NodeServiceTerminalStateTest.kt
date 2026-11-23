package com.vscodroid.service

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.core.app.ServiceCompat
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.util.PortFinder
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies that entering the terminal state withdraws gateway routing immediately
 * (returning HTTP 503 instead of a 0-byte EOF), reaps surviving editor processes off
 * the calling thread, and keeps the public origin bound so Retry recovers in place.
 */
class NodeServiceTerminalStateTest {

    @TempDir
    lateinit var temp: File

    private lateinit var context: Context
    private lateinit var gateway: EditorGateway
    private lateinit var manager: ProcessManager
    private lateinit var service: NodeService
    private val holders = mutableListOf<ServerSocket>()
    private var storedHome = 0

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(ServiceCompat::class)
        every { ServiceCompat.startForeground(any(), any(), any(), any()) } just Runs
        every { ServiceCompat.stopForeground(any(), any<Int>()) } just Runs

        val script = File(temp, "bootstrap.sh").apply {
            writeText("#!/bin/sh\nprintf '%s' \"\$VSCODROID_SERVER_RUN_ID\" > run-id\n")
            check(setExecutable(true))
        }
        mockkObject(Environment)
        every { Environment.getNodePath(any()) } returns script.path
        every { Environment.getServerScript(any()) } returns "server.js"
        every { Environment.buildProcessEnvironment(any(), any()) } returns emptyMap()
        every { Environment.getExtensionsDir(any()) } returns "extensions"
        every { Environment.getUserDataDir(any()) } returns "data"
        every { Environment.getLogsDir(any()) } returns File(temp, "logs").path
        every { Environment.getServerDir(any()) } returns File(temp, "server").path

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
        every { context.filesDir } returns temp
        every { context.cacheDir } returns temp
        every { context.applicationInfo } returns ApplicationInfo().apply { uid = 12345 }
        every { context.getSharedPreferences(any(), any()) } returns prefs

        gateway = EditorGateway()
        manager = ProcessManager(context, gateway)
        ProcessManager::class.java.getDeclaredField("procDir").apply {
            isAccessible = true
            set(manager, File(temp, "proc"))
        }

        service = spyk(mockk<NodeService>(relaxed = true), recordPrivateCalls = true)
        for ((name, value) in listOf(
            "tag" to "NodeService",
            "processManager" to manager,
            "isServiceRunning" to true,
        )) {
            NodeService::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(service, value)
            }
        }
        every { service.getString(any()) } returns "Server stopped"
        every {
            service["createNotification"](
                any<String>(),
                any<String>(),
                any<Boolean>(),
                any<Boolean>(),
            )
        } returns mockk<Notification>(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        manager.stopServer()
        gateway.close()
        holders.forEach { runCatching { it.close() } }
        unmockkAll()
    }

    private fun invokeEnterTerminalState() {
        NodeService::class.java.getDeclaredMethod("enterTerminalState").apply {
            isAccessible = true
            invoke(service)
        }
    }

    private fun recordEditor(pid: Int, port: Int, runId: String) {
        val note = File(temp, "server/editor-server.pid")
        note.parentFile!!.mkdirs()
        note.writeText(
            JSONObject()
                .put("pid", pid)
                .put("port", port)
                .put("runId", runId)
                .toString(),
        )
        val proc = File(temp, "proc/$pid").apply { mkdirs() }
        File(proc, "cmdline").writeText("/lib/libnode.so\u0000/data/server/vscode-reh/out/server-main.js\u0000")
        File(proc, "environ").writeText("VSCODROID_SERVER_RUN_ID=$runId\u0000")
    }

    private fun readHttpStatusLine(port: Int): String {
        Socket().use { socket ->
            socket.soTimeout = 3_000
            socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            return socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
        }
    }

    @Test
    fun `enterTerminalState withdraws backend for 503, reaps survivor off-thread, and keeps origin for Retry`() {
        val exited = CountDownLatch(1)
        manager.onServerCrashed = { exited.countDown() }
        assertTrue(manager.startServer())
        assertTrue(exited.await(5, TimeUnit.SECONDS), "bootstrap fixture must exit first")

        val boundPort = gateway.boundPort!!
        val runId = File(temp, "run-id").readText()
        recordEditor(pid = 9101, port = boundPort, runId = runId)

        val callerThread = Thread.currentThread()
        val reaped = CountDownLatch(1)
        val reapThread = AtomicReference<Thread?>()
        manager.terminateRecordedProcess = { pid ->
            if (pid == 9101) {
                reapThread.set(Thread.currentThread())
                File(temp, "proc/$pid").deleteRecursively()
                reaped.countDown()
            }
        }
        manager.killRecordedProcess = { }

        val gaveUp = mutableListOf<Boolean>()
        service.onServerGaveUp = { gaveUp += it }

        invokeEnterTerminalState()

        val status = readHttpStatusLine(boundPort)
        assertEquals(
            "HTTP/1.1 503 Service Unavailable",
            status,
            "entering terminal state must withdraw the gateway backend so clients receive 503 instead of EOF",
        )
        assertEquals(boundPort, gateway.boundPort, "the public origin must stay bound for in-place Retry")
        assertEquals(boundPort, manager.port, "the manager must keep its bound port for in-place Retry")
        assertEquals(listOf(true), gaveUp)

        assertTrue(reaped.await(5, TimeUnit.SECONDS), "surviving editor child must be reaped asynchronously")
        assertNotEquals(
            callerThread,
            reapThread.get(),
            "survivor reaping must run off the calling main thread",
        )
        assertFalse(File(temp, "server/editor-server.pid").exists(), "the survivor note should be consumed")

        val retryExited = CountDownLatch(1)
        manager.onServerCrashed = { retryExited.countDown() }
        assertTrue(manager.startServer(), "Retry must be able to start a replacement server")
        assertTrue(retryExited.await(5, TimeUnit.SECONDS))
        assertEquals(boundPort, gateway.boundPort, "Retry must recover on the exact same origin")
    }

    @Test
    fun `enterTerminalState withdraws an adopted backend and keeps temporary away origin bound for Retry`() {
        val home = PortFinder.findAvailablePort()
        storedHome = home
        val holder = ServerSocket(home, 0, InetAddress.getByName("127.0.0.1")).also { holders += it }
        val away = PortFinder.findAvailablePort()
        assertNotEquals(home, away)

        gateway.bind(away)
        val owner = "adopted-terminal-run"
        val backendSocket = editorBackendSocket(context, owner)!!
        gateway.selectBackend(backendSocket, owner)
        recordEditor(pid = 9202, port = away, runId = owner)
        val noteText = File(temp, "server/editor-server.pid").readText()

        fun setManagerField(name: String, value: Any?) {
            ProcessManager::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(manager, value)
            }
        }
        setManagerField("_port", away)
        setManagerField("adopted", true)
        setManagerField("serverRunId", owner)
        setManagerField("serverEditorNote", noteText)

        val reaped = CountDownLatch(1)
        manager.terminateRecordedProcess = { pid ->
            if (pid == 9202) {
                File(temp, "proc/$pid").deleteRecursively()
                reaped.countDown()
            }
        }
        manager.killRecordedProcess = { }

        invokeEnterTerminalState()

        assertEquals("HTTP/1.1 503 Service Unavailable", readHttpStatusLine(away))
        assertFalse(manager.hasLiveServer(), "adopted server must no longer report live")
        assertEquals(away, gateway.boundPort, "terminal state must not release the origin while Retry is offered")
        assertTrue(reaped.await(5, TimeUnit.SECONDS), "adopted survivor must be reaped asynchronously")
        holder.close()
    }

    @Test
    fun `delayed terminal reap leaves a Retry-adopted replacement of the same run untouched`() {
        val commit = "abc1234567890"
        val owner = "retry-raced-run"
        File(temp, "server/vscode-reh/product.json").apply {
            parentFile!!.mkdirs()
            writeText(JSONObject().put("commit", commit).toString())
        }

        // Local stub answering /version and /vscodroid-server-proof/<nonce> for real adoption.
        val stub = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).also { holders += it }
        val stubPort = stub.localPort
        val stubThread = Thread {
            while (!stub.isClosed) {
                val client = try { stub.accept() } catch (_: Exception) { break }
                try {
                    client.use { socket ->
                        val requestLine = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
                        val path = requestLine.split(' ').getOrNull(1).orEmpty()
                        val body = when {
                            path == "/version" -> commit
                            path.startsWith("/vscodroid-server-proof/") -> {
                                val challenge = path.removePrefix("/vscodroid-server-proof/")
                                val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
                                    init(javax.crypto.spec.SecretKeySpec(owner.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
                                }
                                mac.doFinal(challenge.toByteArray(Charsets.US_ASCII))
                                    .joinToString("") { "%02x".format(it) }
                            }
                            else -> ""
                        }
                        val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    }
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true; start() }

        val mockedGateway = mockEditorGateway()
        val racedManager = ProcessManager(context, mockedGateway)
        ProcessManager::class.java.getDeclaredField("procDir").apply {
            isAccessible = true
            set(racedManager, File(temp, "proc"))
        }

        val backendSocket = editorBackendSocket(context, owner)!!
        val note = File(temp, "server/editor-server.pid").apply { parentFile!!.mkdirs() }
        val noteText = JSONObject()
            .put("pid", 9303)
            .put("port", stubPort)
            .put("runId", owner)
            .put("socketPath", backendSocket.path)
            .toString()
        note.writeText(noteText)
        val proc = File(temp, "proc/9303").apply { mkdirs() }
        File(proc, "cmdline").writeText("/lib/libnode.so\u0000/data/server/vscode-reh/out/server-main.js\u0000")
        File(proc, "environ").writeText("VSCODROID_SERVER_RUN_ID=$owner\u0000")

        mockedGateway.bind(stubPort)
        fun setRacedField(name: String, value: Any?) {
            ProcessManager::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(racedManager, value)
            }
        }
        setRacedField("_port", stubPort)
        setRacedField("adopted", true)
        setRacedField("serverRunId", owner)
        setRacedField("serverEditorNote", noteText)

        val signalled = mutableListOf<Int>()
        racedManager.terminateRecordedProcess = { signalled += it }
        racedManager.killRecordedProcess = { signalled += it }

        try {
            val delayedReap = racedManager.withdrawForTerminalState()
            assertTrue(delayedReap != null, "withdrawForTerminalState must return a reap task for the adopted run")
            assertFalse(racedManager.hasLiveServer())

            // Retry wins the race to startLock and adopts the same surviving run before delayedReap executes.
            assertTrue(racedManager.startServer(), "Retry must adopt the live surviving server")
            assertTrue(racedManager.isAdopted())

            // Delayed terminal reap now acquires startLock and must leave the Retry-adopted run untouched.
            delayedReap!!.invoke()

            assertTrue(signalled.isEmpty(), "delayed terminal reap signalled a Retry-adopted server: $signalled")
            assertTrue(note.exists(), "delayed terminal reap deleted the Retry-adopted server's note")
            assertTrue(racedManager.isAdopted(), "Retry-adopted server must remain adopted")
        } finally {
            racedManager.stopServer()
            stub.close()
            stubThread.join(3_000)
        }
    }
}
