package com.vscodroid.service

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
