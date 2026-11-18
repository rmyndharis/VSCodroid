package com.vscodroid.service

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class EditorGatewayOnDeviceTest {
    private val gateway = EditorGateway()
    private val resources = ConcurrentLinkedQueue<Closeable>()
    private val workers = mutableListOf<Thread>()
    private val failures = ConcurrentLinkedQueue<Throwable>()
    private val closing = AtomicBoolean()
    private lateinit var directory: File

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "gateway-" + UUID.randomUUID().toString().take(12))
        check(directory.mkdir())
    }

    @After
    fun cleanup() {
        closing.set(true)
        gateway.close()
        resources.forEach { runCatching { it.close() } }
        workers.forEach { it.join(3000) }
        val alive = workers.filter { it.isAlive }
        directory.deleteRecursively()
        assertTrue("backend worker did not stop", alive.isEmpty())
        assertTrue("backend worker failed: " + failures.joinToString(), failures.isEmpty())
    }

    private fun server(name: String): Pair<File, LocalServerSocket> {
        val path = File(directory, name)
        val bound = LocalSocket()
        resources.add(bound)
        bound.bind(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        val server = LocalServerSocket(bound.fileDescriptor)
        resources.add(server)
        return path to server
    }

    private fun worker(action: () -> Unit) {
        val thread = Thread {
            try { action() } catch (failure: Throwable) {
                if (!closing.get() || failure !is IOException) failures.add(failure)
            }
        }
        workers.add(thread)
        thread.start()
    }

    private fun client(): Socket = Socket(InetAddress.getByName("127.0.0.1"), gateway.boundPort!!).also {
        it.soTimeout = 3000
        resources.add(it)
    }

    @Test(timeout = 10000)
    fun nativeRelayPreservesBinaryBytesAndHostHeader() {
        val (path, server) = server("editor.sock")
        val port = gateway.bind(0)
        gateway.selectBackend(path, "run")
        val request = "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n"
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        worker {
            server.accept().use {
                assertEquals(request, it.inputStream.readExactly(request.toByteArray().size).toString(Charsets.US_ASCII))
                it.outputStream.write(bytes)
            }
        }
        client().use {
            it.getOutputStream().write(request.toByteArray())
            assertArrayEquals(bytes, it.getInputStream().readBytes())
        }
    }

    @Test(timeout = 10000)
    fun nativeRelayKeepsTheResponseAfterClientHalfClose() {
        val (path, server) = server("half-close.sock")
        gateway.bind(0)
        gateway.selectBackend(path, "run")
        val response = ByteArray(512 * 1024) { 42 }
        worker {
            server.accept().use {
                assertEquals("request", it.inputStream.readBytes().toString(Charsets.UTF_8))
                it.outputStream.write(response)
            }
        }
        client().use {
            it.getOutputStream().write("request".toByteArray())
            it.shutdownOutput()
            assertArrayEquals(response, it.getInputStream().readBytes())
        }
    }

    @Test(timeout = 10000)
    fun nativeRelaySupportsUnicodeSocketPaths() {
        val (path, server) = server("éditeur 空.sock")
        gateway.bind(0)
        gateway.selectBackend(path, "run")
        worker { server.accept().use { it.outputStream.write(7) } }
        client().use { assertEquals(7, it.getInputStream().read()) }
    }

    @Test(timeout = 10000)
    fun aSilentBackendReadCanResumeAfterItsPollingTimeout() {
        val (path, server) = server("poll.sock")
        gateway.bind(0)
        gateway.selectBackend(path, "run")
        worker {
            server.accept().use {
                Thread.sleep(1500)
                it.outputStream.write(7)
            }
        }
        client().use { assertEquals(7, it.getInputStream().read()) }
    }

    @Test(timeout = 10000)
    fun aFullListenBacklogBoundsNativeConnectionSetup() {
        val (path, _) = server("full.sock")
        var full = false
        repeat(64) {
            if (!full) {
                val socket = LocalSocket()
                resources.add(socket)
                socket.outputStream
                socket.soTimeout = 100
                try { socket.connect(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)) }
                catch (_: IOException) { full = true }
            }
        }
        assertTrue("the fixture did not fill the listen backlog", full)
        val started = System.nanoTime()
        try {
            connectEditorBackend(path).close()
            fail("a full backlog admitted another connection")
        } catch (_: IOException) {
            val elapsed = (System.nanoTime() - started) / 1000000
            assertTrue("native connection setup took " + elapsed + " ms", elapsed in 500..2500)
        }
    }

    @Test(timeout = 10000)
    fun missingBackendAndWithdrawalKeepTheFrontendReserved() {
        val port = gateway.bind(0)
        gateway.selectBackend(File(directory, "absent.sock"), "run")
        client().use {
            try { assertEquals(-1, it.getInputStream().read()) } catch (_: IOException) { }
        }
        gateway.withdrawBackend("run")
        assertTrue(gateway.isBound(port))
        try {
            java.net.ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
            fail("the frontend origin was released")
        } catch (_: IOException) { }
    }

    /**
     * A backend that sends every byte back, on as many connections as come. Its threads are
     * daemons outside [workers]: the close of a listening socket does not wake a thread that
     * waits in accept on it, so the one that takes the connections has no end to wait for.
     */
    private fun echoing(server: LocalServerSocket) {
        Thread {
            runCatching {
                while (!closing.get()) {
                    val connection = server.accept()
                    resources.add(connection)
                    Thread {
                        runCatching {
                            while (true) {
                                val byte = connection.inputStream.read()
                                if (byte < 0) break
                                connection.outputStream.write(byte)
                            }
                        }
                    }.apply { isDaemon = true }.start()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * A connection another process holds: toybox's nc, which sends what it is given and prints
     * what comes back. Returned once the byte it sent has come back, so it has its slot.
     */
    private fun outsider(port: Int): Process =
        holding(ProcessBuilder("/system/bin/toybox", "nc", "127.0.0.1", port.toString()))

    /**
     * A connection another process holds from the address and the port it chose, which nc does
     * not offer: [ChosenSourceClient], loaded from the test APK by a runtime of its own.
     */
    private fun outsider(port: Int, from: String, sourcePort: Int): Process {
        val builder = ProcessBuilder(
            "/system/bin/app_process", "/system/bin", ChosenSourceClient::class.java.name,
            from, sourcePort.toString(), port.toString(),
        ).redirectError(ProcessBuilder.Redirect.INHERIT)
        builder.environment()["CLASSPATH"] =
            InstrumentationRegistry.getInstrumentation().context.packageCodePath
        return holding(builder)
    }

    private fun holding(builder: ProcessBuilder): Process {
        val process = builder.start()
        resources.add(Closeable { process.destroy() })
        process.outputStream.apply { write('x'.code); flush() }
        assertEquals("the other process did not get a connection", 'x'.code, process.inputStream.read())
        return process
    }

    /** A client of this process that has its slot: the byte it sent has come back. */
    private fun insider(gateway: EditorGateway): Socket =
        Socket(InetAddress.getByName("127.0.0.1"), gateway.boundPort!!).also {
            it.soTimeout = 20000
            resources.add(it)
            it.getOutputStream().write('x'.code)
            assertEquals('x'.code, it.getInputStream().read())
        }

    private fun Socket.stillServed(): Boolean {
        getOutputStream().write('y'.code)
        return getInputStream().read() == 'y'.code
    }

    /**
     * A gateway of four slots, so that filling it takes four child processes: the system allows
     * every app's children thirty-two between them, and ends some when there are more.
     */
    private fun fourSlots(name: String): EditorGateway {
        val (path, server) = server(name)
        echoing(server)
        return EditorGateway(slots = 4).also {
            resources.add(it)
            it.bind(0)
            it.selectBackend(path, "run")
        }
    }

    /**
     * What the gateway rests on when it makes room: that it can tell a connection this process
     * opened from one another process opened. Nothing in the JVM suite can show that, because
     * every client there is the suite's own.
     */
    @Test(timeout = 60000)
    fun aClientOfThisProcessTakesTheSlotOfTheOldestConnectionAnotherProcessHolds() {
        val small = fourSlots("held.sock")
        val held = List(4) { outsider(small.boundPort!!) }

        val ours = insider(small)

        assertEquals("the oldest connection of the other process kept its slot", -1, held[0].inputStream.read())
        held[1].outputStream.apply { write('z'.code); flush() }
        assertEquals("a later connection of the other process was closed as well", 'z'.code, held[1].inputStream.read())
        assertTrue(ours.stillServed())
    }

    @Test(timeout = 60000)
    fun noConnectionOfThisProcessIsClosedForAClientOfAnotherProcess() {
        val small = fourSlots("ours.sock")
        val ours = List(4) { insider(small) }

        val refused = ProcessBuilder("/system/bin/toybox", "nc", "127.0.0.1", small.boundPort!!.toString()).start()
        resources.add(Closeable { refused.destroy() })
        assertEquals("HTTP/1.1 503 Service Unavailable", refused.inputStream.bufferedReader().readLine())
        ours.forEach { assertTrue("a connection of the app was closed for another process", it.stillServed()) }
    }

    /**
     * Another process chooses the address and the port it connects from. From 127.0.0.2 with the
     * listener's own port number, the socket accepted for it has that number on both ends; from
     * the port number one of this process's connections uses, it shares that number. Told by
     * the port alone, both read as connections of this process and kept their slots for good.
     */
    @Test(timeout = 60000)
    fun aConnectionAnotherProcessMakesFromAChosenAddressAndPortIsNotTakenForTheApps() {
        val small = fourSlots("chosen.sock")
        val port = small.boundPort!!
        val page = insider(small)
        val held = listOf(
            outsider(port, from = "127.0.0.2", sourcePort = page.localPort),
            outsider(port, from = "127.0.0.2", sourcePort = port),
            outsider(port, from = "127.0.0.3", sourcePort = port),
        )

        val second = insider(small)
        assertEquals("a connection from the page's port number elsewhere kept its slot", -1, held[0].inputStream.read())
        val third = insider(small)
        assertEquals("a connection from the listener's port number kept its slot", -1, held[1].inputStream.read())

        listOf(page, second, third).forEach { assertTrue(it.stillServed()) }
    }

    private fun java.io.InputStream.readExactly(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            check(read > 0) { "backend request ended early" }
            offset += read
        }
        return bytes
    }
}
