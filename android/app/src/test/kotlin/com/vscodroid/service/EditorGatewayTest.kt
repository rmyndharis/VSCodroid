package com.vscodroid.service

import android.system.ErrnoException
import android.system.OsConstants
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EditorGatewayTest {
    @TempDir lateinit var directory: File
    private val gateway = EditorGateway()
    private val resources = ConcurrentLinkedQueue<Closeable>()
    private val workers = mutableListOf<Thread>()
    private val failures = ConcurrentLinkedQueue<Throwable>()
    private val releases = mutableListOf<CountDownLatch>()
    private val closing = AtomicBoolean()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var connected: ((EditorBackend) -> Unit)? = null

    @BeforeEach
    fun nativeBoundary() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> failures.add(failure) }
        mockkStatic("com.vscodroid.service.EditorGatewayKt")
        every { connectEditorBackend(any()) } answers {
            val channel = openUnixClientChannel()
            resources.add(channel)
            channel.connect(UnixDomainSocketAddress.of(firstArg<File>().toPath()))
            channel.configureBlocking(false)
            val input = Selector.open().also { resources.add(it) }
            channel.register(input, SelectionKey.OP_READ)
            mockk<EditorBackend>().also { backend ->
                every { backend.read(any()) } answers {
                    val buffer = ByteBuffer.wrap(firstArg<ByteArray>())
                    var count = channel.read(buffer)
                    if (count == 0) {
                        input.select(20)
                        input.selectedKeys().clear()
                        count = channel.read(buffer)
                    }
                    if (count == 0) throw ErrnoException("read", OsConstants.EAGAIN)
                    if (count < 0) 0 else count
                }
                every { backend.write(any(), any(), any()) } answers {
                    val count = channel.write(ByteBuffer.wrap(firstArg(), secondArg(), thirdArg()))
                    if (count == 0) {
                        Thread.sleep(20)
                        throw ErrnoException("write", OsConstants.EAGAIN)
                    }
                    count
                }
                every { backend.shutdownOutput() } answers { channel.shutdownOutput(); Unit }
                every { backend.close() } answers { channel.close(); input.wakeup(); Unit }
                connected?.invoke(backend)
            }
        }
    }

    @AfterEach
    fun cleanup() {
        closing.set(true)
        releases.forEach { it.countDown() }
        gateway.close()
        resources.forEach { runCatching { it.close() } }
        workers.forEach { it.join(3000) }
        workers.filter { it.isAlive }.forEach { it.interrupt(); it.join(1000) }
        val alive = workers.filter { it.isAlive }
        val relays = EditorGateway::class.java.getDeclaredField("workers").apply {
            isAccessible = true
        }.get(gateway) as java.util.concurrent.ExecutorService
        val relaysStopped = relays.awaitTermination(3, TimeUnit.SECONDS)
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        unmockkAll()
        assertTrue(alive.isEmpty(), "backend fixture did not stop")
        assertTrue(relaysStopped, "relay workers did not stop")
        assertTrue(failures.isEmpty(), "backend fixture failed: " + failures.joinToString())
    }

    private fun backend(name: String): Pair<File, ServerSocketChannel> {
        val path = File(directory, name + ".sock")
        val server = openUnixServerChannel()
        resources.add(server)
        server.bind(UnixDomainSocketAddress.of(path.toPath()))
        return path to server
    }

    private fun worker(block: () -> Unit) {
        val thread = Thread {
            try { block() } catch (failure: Throwable) {
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

    @Test
    fun preservesHttpHostAndBinaryResponse() {
        val (path, server) = backend("http")
        val port = gateway.bind(0)
        gateway.selectBackend(path, "http-run")
        val request = "GET /folder?q=x HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n"
        val body = ByteArray(1024 * 1024) { (it % 251).toByte() }
        worker {
            server.accept().use { connection ->
                val received = connection.socketInput().readNBytes(request.toByteArray().size)
                assertEquals(request, received.toString(Charsets.US_ASCII))
                connection.socketOutput().write(body)
            }
        }
        client().use {
            it.getOutputStream().write(request.toByteArray())
            assertArrayEquals(body, it.getInputStream().readBytes())
        }
    }

    @Test
    fun carriesBidirectionalBytesAfterUpgrade() {
        val (path, server) = backend("upgrade")
        gateway.bind(0)
        gateway.selectBackend(path, "upgrade-run")
        val upgrade = "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n".toByteArray()
        val frame = byteArrayOf(0x82.toByte(), 3, 0, 0xff.toByte(), 7)
        worker {
            server.accept().use { connection ->
                connection.socketOutput().write(upgrade)
                assertArrayEquals(frame, connection.socketInput().readNBytes(frame.size))
                connection.socketOutput().write(frame.reversedArray())
            }
        }
        client().use {
            assertArrayEquals(upgrade, it.getInputStream().readNBytes(upgrade.size))
            it.getOutputStream().write(frame)
            assertArrayEquals(frame.reversedArray(), it.getInputStream().readNBytes(frame.size))
        }
    }

    @Test
    fun clientHalfCloseDoesNotTruncateBackendResponse() {
        val (path, server) = backend("half-close")
        gateway.bind(0)
        gateway.selectBackend(path, "half-close-run")
        val response = ByteArray(512 * 1024) { 42 }
        worker {
            server.accept().use { connection ->
                assertEquals("request", connection.socketInput().readBytes().toString(Charsets.UTF_8))
                connection.socketOutput().write(response)
            }
        }
        client().use {
            it.getOutputStream().write("request".toByteArray())
            it.shutdownOutput()
            assertArrayEquals(response, it.getInputStream().readBytes())
        }
    }

    @Test
    fun backendHalfCloseStillAcceptsClientUpload() {
        val (path, server) = backend("reverse-half-close")
        val uploaded = CountDownLatch(1)
        gateway.bind(0)
        gateway.selectBackend(path, "reverse-half-close-run")
        worker {
            server.accept().use {
                it.socketOutput().write(7)
                it.shutdownOutput()
                assertEquals("after eof", it.socketInput().readBytes().toString(Charsets.UTF_8))
                uploaded.countDown()
            }
        }
        client().use {
            assertEquals(7, it.getInputStream().read())
            assertEquals(-1, it.getInputStream().read())
            it.getOutputStream().write("after eof".toByteArray())
            it.shutdownOutput()
            assertTrue(uploaded.await(3, TimeUnit.SECONDS), "the backend did not receive the upload")
        }
    }

    @Test
    fun replacementClosesOldConnectionsAndStaleWithdrawalKeepsNewBackend() {
        val (firstPath, first) = backend("first")
        val (secondPath, second) = backend("second")
        val oldConnected = CountDownLatch(1)
        val oldClosed = CountDownLatch(1)
        gateway.bind(0)
        gateway.selectBackend(firstPath, "first-run")
        worker {
            first.accept().use {
                it.socketOutput().write(1)
                oldConnected.countDown()
                it.socketInput().readBytes()
                oldClosed.countDown()
            }
        }
        val old = client()
        assertEquals(1, old.getInputStream().read())
        assertTrue(oldConnected.await(3, TimeUnit.SECONDS))
        gateway.selectBackend(secondPath, "second-run")
        gateway.withdrawBackend("first-run")
        assertEquals(-1, old.getInputStream().read())
        assertTrue(oldClosed.await(3, TimeUnit.SECONDS), "the old backend remained connected")
        worker { second.accept().use { it.socketOutput().write(2) } }
        client().use { assertEquals(2, it.getInputStream().read()) }
        assertTrue(gateway.isBound(gateway.boundPort!!))
    }

    @Test
    fun reselectingSameOwnerAndPathPreservesTheConnection() {
        val (path, server) = backend("same")
        gateway.bind(0)
        gateway.selectBackend(path, "same-run")
        val release = CountDownLatch(1).also { releases.add(it) }
        worker {
            server.accept().use {
                it.socketOutput().write(1)
                assertTrue(release.await(3, TimeUnit.SECONDS))
                it.socketOutput().write(2)
            }
        }
        client().use {
            assertEquals(1, it.getInputStream().read())
            gateway.selectBackend(path, "same-run")
            release.countDown()
            assertEquals(2, it.getInputStream().read())
        }
    }

    @Test
    fun withdrawingBackendKeepsPublicPortReserved() {
        val port = gateway.bind(0)
        gateway.selectBackend(File(directory, "missing.sock"), "missing-run")
        gateway.withdrawBackend("missing-run")
        assertEquals(port, gateway.bind(port))
        assertTrue(gateway.isBound(port))
        assertThrows(IOException::class.java) {
            java.net.ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
        }
        client().use {
            assertEquals("HTTP/1.1 503 Service Unavailable", it.getInputStream().bufferedReader().readLine())
        }
    }

    @Test
    fun aConnectionRetiredDuringConnectClosesItsLateBackendDescriptor() {
        val (path, server) = backend("late")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases.add(it) }
        val disconnected = CountDownLatch(1)
        connected = {
            entered.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
        }
        worker {
            server.accept().use {
                assertEquals(-1, it.socketInput().read())
                disconnected.countDown()
            }
        }
        gateway.bind(0)
        gateway.selectBackend(path, "late-run")
        val old = client()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        gateway.withdrawBackend("late-run")
        assertEquals(-1, old.getInputStream().read())
        release.countDown()
        assertTrue(disconnected.await(3, TimeUnit.SECONDS), "the late backend descriptor was leaked")
    }

    @Test
    fun admittedConnectionsAreBoundedWithoutReleasingTheListener() {
        val (path, server) = backend("bounded")
        val release = CountDownLatch(1).also { releases.add(it) }
        worker {
            val accepted = mutableListOf<SocketChannel>()
            try {
                repeat(32) {
                    val connection = server.accept()
                    accepted.add(connection)
                    connection.socketOutput().write(1)
                }
                assertTrue(release.await(10, TimeUnit.SECONDS))
            } finally { accepted.forEach { it.close() } }
        }
        val port = gateway.bind(0)
        gateway.selectBackend(path, "bounded-run")
        repeat(32) { assertEquals(1, client().getInputStream().read()) }
        client().use {
            assertEquals("HTTP/1.1 503 Service Unavailable", it.getInputStream().bufferedReader().readLine())
        }
        assertTrue(gateway.isBound(port))
        release.countDown()
    }

    /**
     * Under memory pressure a thread cannot be created, and that is an Error rather than an
     * IOException, so it ended the listener or a relay thread, which on Android ends the app.
     * The client that found no thread is refused and the next one is served. Submission 0 is
     * the listener's own, 1 is the relay's second pump.
     */
    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun aClientThatFindsNoThreadIsRefusedAndTheListenerKeepsServing(failingSubmission: Int) {
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        val request = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
        val reply = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()
        val (path, server) = backend("no-thread")
        worker {
            while (!closing.get()) {
                val connection = try { server.accept() } catch (_: IOException) { return@worker }
                runCatching {
                    connection.use {
                        val received = it.socketInput().readNBytes(request.size)
                        if (received.size == request.size) it.socketOutput().write(reply)
                    }
                }
            }
        }
        val field = EditorGateway::class.java.getDeclaredField("workers").apply { isAccessible = true }
        val real = field.get(gateway) as ThreadPoolExecutor
        val submissions = AtomicInteger()
        val refusing = object : ThreadPoolExecutor(0, 4, 1, TimeUnit.SECONDS, SynchronousQueue()) {
            override fun execute(command: Runnable) {
                if (submissions.getAndIncrement() == failingSubmission) throw OutOfMemoryError("pthread_create failed")
                real.execute(command)
            }
        }
        field.set(gateway, refusing)
        try {
            gateway.bind(0)
            gateway.selectBackend(path, "no-thread-run")

            client().use {
                it.getOutputStream().write(request)
                val answer = try { it.getInputStream().readBytes() } catch (_: IOException) { ByteArray(0) }
                assertEquals(0, answer.size, "the client that found no thread was answered")
            }
            client().use {
                it.getOutputStream().write(request)
                assertArrayEquals(reply, it.getInputStream().readNBytes(reply.size))
            }
        } finally {
            field.set(gateway, real)
            refusing.shutdownNow()
        }
    }

    @Test
    fun bindingAnotherPortCannotMoveThePublishedOrigin() {
        val port = gateway.bind(0)
        java.net.ServerSocket(0).use {
            assertThrows(IOException::class.java) { gateway.bind(it.localPort) }
        }
        assertEquals(port, gateway.bind(0))
        assertTrue(gateway.isBound(port))
    }

    @Test
    fun aOneWayDownloadCanContinuePastTheInactivityLimit() {
        val (path, server) = backend("download")
        gateway.bind(0)
        gateway.selectBackend(path, "download-run")
        worker {
            server.accept().use { connection ->
                val output = connection.socketOutput()
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(123)
                while (System.nanoTime() < end) {
                    output.write(42)
                    Thread.sleep(500)
                }
                output.write(7)
            }
        }
        client().use {
            it.soTimeout = 130000
            val bytes = it.getInputStream().readBytes()
            assertTrue(bytes.size > 240)
            assertEquals(7, bytes.last().toInt())
        }
    }

    @Test
    fun inactivityEventuallyClosesBothDirections() {
        val (path, server) = backend("idle")
        val backendClosed = CountDownLatch(1)
        gateway.bind(0)
        gateway.selectBackend(path, "idle-run")
        worker {
            server.accept().use {
                it.socketOutput().write(42)
                assertEquals(-1, it.socketInput().read())
                backendClosed.countDown()
            }
        }
        client().use {
            assertEquals(42, it.getInputStream().read())
            it.soTimeout = 130000
            val started = System.nanoTime()
            assertEquals(-1, it.getInputStream().read())
            val elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
            assertTrue(elapsed in 118..125, "inactivity timeout took " + elapsed + " seconds")
            assertTrue(backendClosed.await(3, TimeUnit.SECONDS), "the idle backend remained connected")
        }
    }

    @Test
    fun aNonreadingClientCannotKeepAStalledWriteAliveWithUploadTraffic() {
        val (path, server) = backend("stalled-write")
        val accepted = CompletableFuture<SocketChannel>()
        val disconnected = CountDownLatch(1)
        val received = AtomicInteger()
        worker {
            val connection = server.accept()
            resources.add(connection)
            accepted.complete(connection)
            val buffer = ByteBuffer.allocate(32)
            try {
                while (true) {
                    val count = connection.read(buffer)
                    if (count < 0) break
                    received.addAndGet(count)
                    buffer.clear()
                }
            } catch (_: IOException) {
                // Closing a Unix stream with unread output can reset its peer.
            } finally { disconnected.countDown() }
        }
        worker {
            val connection = accepted.get(3, TimeUnit.SECONDS)
            val buffer = ByteBuffer.allocate(1024 * 1024)
            try {
                while (true) {
                    buffer.clear()
                    while (buffer.hasRemaining()) connection.write(buffer)
                }
            } catch (_: IOException) {
                if (!closing.get()) assertTrue(disconnected.await(3, TimeUnit.SECONDS))
            }
        }
        val port = gateway.bind(0)
        gateway.selectBackend(path, "stalled-write-run")
        Socket().use { client ->
            resources.add(client)
            client.receiveBufferSize = 1024
            client.connect(java.net.InetSocketAddress("127.0.0.1", port))
            val started = System.nanoTime()
            while (!disconnected.await(500, TimeUnit.MILLISECONDS) &&
                System.nanoTime() - started < TimeUnit.SECONDS.toNanos(130)) {
                try { client.getOutputStream().write(42) }
                catch (_: IOException) { break }
            }
            assertTrue(disconnected.await(3, TimeUnit.SECONDS), "the blocked write retained a relay slot")
            val elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
            assertTrue(elapsed in 118..130, "the blocked write ended after " + elapsed + " seconds")
            assertTrue(received.get() > 200, "the reverse direction did not remain active")
        }
    }

    private fun SocketChannel.socketInput() = java.nio.channels.Channels.newInputStream(this)
    private fun SocketChannel.socketOutput() = java.nio.channels.Channels.newOutputStream(this)
}
