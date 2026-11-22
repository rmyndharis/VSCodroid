package com.vscodroid.service

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
import java.net.InetSocketAddress
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
    // Which clients the gateway takes for this process's own, by the address they connected
    // from. Every one unless a test says otherwise: on a device the page's connections are, and
    // the suite's clients stand for those.
    private val everyClient: Set<InetSocketAddress> = object : AbstractSet<InetSocketAddress>() {
        override val size = 0
        override fun iterator() = emptyList<InetSocketAddress>().iterator()
        override fun contains(element: InetSocketAddress) = true
    }
    private var ownClients: () -> Set<InetSocketAddress> = { everyClient }
    private val gateway = EditorGateway({ ownClients() })
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
        // What a read or a write with nothing to move answers on the device: EAGAIN, Linux's 11. One
        // instance, thrown on every empty poll.
        val wouldBlock = errnoException(11)
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
                    if (count == 0) throw wouldBlock
                    if (count < 0) 0 else count
                }
                every { backend.write(any(), any(), any()) } answers {
                    val count = channel.write(ByteBuffer.wrap(firstArg(), secondArg(), thirdArg()))
                    if (count == 0) {
                        Thread.sleep(20)
                        throw wouldBlock
                    }
                    count
                }
                every { backend.shutdownOutput() } answers { channel.shutdownOutput(); Unit }
                // Both directions end and a read that waits comes back, as on the real socket, and the
                // descriptor stays open.
                every { backend.shutdown() } answers {
                    runCatching { channel.shutdownInput() }
                    runCatching { channel.shutdownOutput() }
                    input.wakeup()
                    Unit
                }
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
    fun releaseFreesItsPortAndTheGatewayCanBindAgain() {
        // For an origin taken only because the usual one was busy: given up at a stop,
        // so that the next start in the same process can bind the usual port. Not
        // close(), which would leave a gateway that can never bind again.
        val first = gateway.bind(0)

        assertTrue(gateway.release(first))
        assertNull(gateway.boundPort)
        java.net.ServerSocket(first, 0, InetAddress.getByName("127.0.0.1")).close()

        val second = gateway.bind(0)
        assertTrue(second > 0)
        assertEquals(second, gateway.boundPort)
    }

    @Test
    fun aPortReleasedWhileItsListenerWaitsForAClientCanBeBoundAtOnce() {
        // Closing a channel that a thread is waiting on in accept returns before the kernel lets
        // the port go: on Linux a bind in that moment is refused, from one time in ten to one
        // in three when measured with this class. release has to wait for that thread, or a
        // start that releases a temporary port and binds it again lands on a third one.
        // Repeated, because one round can pass by luck; macOS frees the port at once and
        // passes either way.
        val loopback = InetAddress.getByName("127.0.0.1")
        repeat(100) { round ->
            val port = gateway.bind(0)
            Thread.sleep(2) // long enough for the accept thread to be waiting

            assertTrue(gateway.release(port))
            try {
                java.net.ServerSocket(port, 0, loopback).close()
            } catch (failure: java.net.BindException) {
                fail<Unit>("round $round: port $port could not be bound right after release returned", failure)
            }
        }
    }

    @Test
    fun releaseOfAnotherPortLeavesTheListenerBound() {
        val bound = gateway.bind(0)

        assertFalse(gateway.release(bound + 1))
        assertEquals(bound, gateway.boundPort)
    }

    @Test
    fun theStandInOtherSuitesUseHoldsItsOriginAsTheGatewayDoes() {
        // The suites built on mockEditorGateway() see the origin only through it. One that
        // called any port bound, or let a second port replace the first, would pass a check
        // made against the wrong port and a start that moves a bound origin. Both are taken
        // through the same steps here, so the stand-in cannot drift from this class unseen.
        for ((name, subject) in listOf("the gateway" to gateway, "the stand-in" to mockEditorGateway())) {
            assertFalse(subject.isBound(13337), "$name: no port is bound before a bind")

            val port = subject.bind(0)
            val other = port - 1
            assertTrue(port > 0, "$name: bind(0) answers the port it took")
            assertEquals(port, subject.boundPort, name)
            assertTrue(subject.isBound(port), name)
            assertFalse(subject.isBound(other), "$name: only the bound port is the origin")
            assertFalse(subject.isBound(0), name)

            assertEquals(port, subject.bind(0), "$name: bind(0) keeps the listener that is there")
            assertEquals(port, subject.bind(port), name)
            assertThrows(IOException::class.java, { subject.bind(other) }, "$name: a bound origin does not move")
            assertEquals(port, subject.boundPort, "$name: a refused bind leaves the origin")
            assertEquals(1L, subject.listenersOpened, name)

            assertFalse(subject.release(other), name)
            assertTrue(subject.release(port), name)
            assertNull(subject.boundPort, name)
            assertFalse(subject.isBound(port), "$name: a released port is not the origin")

            val next = subject.bind(0)
            assertTrue(subject.isBound(next), "$name: a release leaves it able to bind again")
            assertEquals(2L, subject.listenersOpened, name)
        }
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
                assertEquals('G'.code, connection.socketInput().read())
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
            it.getOutputStream().write('G'.code)
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
                assertEquals('G'.code, it.socketInput().read())
                it.socketOutput().write(42)
                assertEquals(-1, it.socketInput().read())
                backendClosed.countDown()
            }
        }
        client().use {
            it.getOutputStream().write('G'.code)
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

    /**
     * Answers [reply] on every connection that sends [request] whole, and keeps each
     * connection until its client side is closed, so silent clients hold their slots.
     */
    private fun answering(server: ServerSocketChannel, request: ByteArray, reply: ByteArray) {
        worker {
            while (!closing.get()) {
                val connection = try { server.accept() } catch (_: IOException) { return@worker }
                resources.add(connection)
                Thread {
                    runCatching {
                        connection.use {
                            if (it.socketInput().readNBytes(request.size).size == request.size) {
                                it.socketOutput().write(reply)
                            }
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }
    }

    @Test
    fun silentClientsGiveUpTheirSlotsLongBeforeTheInactivityLimit() {
        // Any app can connect to the origin. Thirty-two connections that never send a byte
        // held every relay slot for the two-minute inactivity limit, and the page was refused.
        val request = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
        val reply = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
        val (path, server) = backend("silent")
        answering(server, request, reply)
        gateway.bind(0)
        gateway.selectBackend(path, "silent-run")
        val started = System.nanoTime()
        val silent = List(32) { client().apply { soTimeout = 30000 } }
        client().use {
            assertEquals("HTTP/1.1 503 Service Unavailable", it.getInputStream().bufferedReader().readLine())
        }

        assertEquals(-1, silent[0].getInputStream().read(), "a silent client was answered")
        val elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
        assertTrue(elapsed in 9..14, "a silent client kept its slot for $elapsed seconds")
        silent.forEach { assertEquals(-1, it.getInputStream().read()) }
        awaitServed(request, reply)
    }

    @Test
    fun aClientThatHasSpokenIsNotCutByTheFirstByteBound() {
        val request = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
        val reply = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
        val (path, server) = backend("slow-request")
        answering(server, request, reply)
        gateway.bind(0)
        gateway.selectBackend(path, "slow-request-run")
        client().use {
            it.soTimeout = 30000
            Thread.sleep(2000)
            it.getOutputStream().write(request, 0, 1)
            Thread.sleep(10000) // past the bound counted from the connect
            it.getOutputStream().write(request, 1, request.size - 1)
            assertArrayEquals(reply, it.getInputStream().readNBytes(reply.size))
        }
    }

    /**
     * Sends [request] until [reply] comes back, for up to ten seconds. A relay gives its slot back once
     * both of its pumps have left, and a fixed pause only guessed at how long that takes on a busy
     * machine; until then a client is answered 503, or reset.
     */
    private fun awaitServed(request: ByteArray, reply: ByteArray) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val answer = try {
                client().use {
                    it.getOutputStream().write(request)
                    it.getInputStream().readNBytes(reply.size)
                }
            } catch (_: IOException) {
                ByteArray(0)
            }
            if (answer.contentEquals(reply) || System.nanoTime() >= deadline) {
                assertArrayEquals(reply, answer, "the listener never served again")
                return
            }
            Thread.sleep(50)
        }
    }

    /** A client that has sent the first byte of [request] and keeps its connection open. */
    private fun holder(request: ByteArray): Socket = client().apply {
        soTimeout = 5000
        getOutputStream().write(request, 0, 1)
    }

    // Closed with the byte it sent still unread, a connection is reset and not ended.
    private fun Socket.wasClosed(): Boolean = try { getInputStream().read() == -1 } catch (_: java.net.SocketException) { true }

    private fun Socket.finish(request: ByteArray, reply: ByteArray) {
        getOutputStream().write(request, 1, request.size - 1)
        assertArrayEquals(reply, getInputStream().readNBytes(reply.size))
    }

    @Test
    fun aNewClientTakesTheSlotOfTheOldestConnectionAnotherProgramOpened() {
        // Any app can connect to the origin. Thirty-two connections that had each sent one byte
        // held every slot for as long as their owner liked, and the page was refused meanwhile.
        ownClients = { emptySet() }
        val request = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
        val reply = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
        val (path, server) = backend("held")
        answering(server, request, reply)
        gateway.bind(0)
        gateway.selectBackend(path, "held-run")
        val held = List(32) { holder(request) }

        val first = holder(request)
        assertTrue(held[0].wasClosed(), "the oldest connection kept its slot")
        val second = holder(request)
        assertTrue(held[1].wasClosed(), "the next oldest connection kept its slot")

        first.finish(request, reply)
        second.finish(request, reply)
        held.drop(2).forEach { it.finish(request, reply) }
    }

    @Test
    fun aConnectionOfThisProcessIsNeverClosedForANewClient() {
        val ours = java.util.concurrent.ConcurrentHashMap.newKeySet<InetSocketAddress>()
        ownClients = { ours }
        val request = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
        val reply = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
        val (path, server) = backend("ours")
        answering(server, request, reply)
        gateway.bind(0)
        gateway.selectBackend(path, "ours-run")
        // The fifth is another program's and the other thirty-one are the page's.
        val held = List(32) { index ->
            holder(request).also { if (index != 4) ours.add(it.localSocketAddress as InetSocketAddress) }
        }

        val page = holder(request).also { ours.add(it.localSocketAddress as InetSocketAddress) }
        assertTrue(held[4].wasClosed(), "the other program's connection kept its slot")

        // Every slot is this process's now, so the next client is the one that goes without.
        client().use {
            assertEquals("HTTP/1.1 503 Service Unavailable", it.getInputStream().bufferedReader().readLine())
        }
        page.finish(request, reply)
        held.filterIndexed { index, _ -> index != 4 }.forEach { it.finish(request, reply) }
    }

    /**
     * The rule that tells this process's connections from another's, on the addresses of both
     * ends of a socket. The port alone does not: another app chooses the address and the port
     * it connects from.
     */
    @Test
    fun onlyASocketConnectedToTheListenerIsAClientOfThisProcess() {
        fun at(host: String, port: Int) = InetSocketAddress(InetAddress.getByName(host), port)
        val listener = at("127.0.0.1", 13337)
        val page = at("127.0.0.1", 40001)
        val ends = listOf<Pair<java.net.SocketAddress?, java.net.SocketAddress?>>(
            // A connection of the page: its client end, and the end this process accepted for it.
            page to listener,
            listener to page,
            // Accepted for another app that connects from the listener's own port number.
            listener to at("127.0.0.2", 13337),
            // Accepted for another app that connects from the page's port number, elsewhere.
            listener to at("127.0.0.2", 40001),
            // Sockets of this process that have nothing to do with the listener.
            at("127.0.0.1", 40002) to at("127.0.0.1", 13338),
            at("10.0.2.16", 40003) to at("93.184.216.34", 13337),
            at("127.0.0.1", 40004) to null,
            null to listener,
        )

        assertEquals(setOf(page), clientsOf(listener, ends))
    }

    @Test
    fun withNoAnswerAboutWhoseTheConnectionsAreNoneIsClosed() {
        ownClients = { throw IOException("The descriptors cannot be listed") }
        val request = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
        val reply = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
        val (path, server) = backend("unknown")
        answering(server, request, reply)
        gateway.bind(0)
        gateway.selectBackend(path, "unknown-run")
        val held = List(32) { holder(request) }

        client().use {
            assertEquals("HTTP/1.1 503 Service Unavailable", it.getInputStream().bufferedReader().readLine())
        }
        held.forEach {
            it.finish(request, reply)
            it.close()
        }
        // The listener goes on serving after the failure.
        awaitServed(request, reply)
    }

    private fun SocketChannel.socketInput() = java.nio.channels.Channels.newInputStream(this)
    private fun SocketChannel.socketOutput() = java.nio.channels.Channels.newOutputStream(this)
    @Test
    fun releaseDuringBackendConnectDoesNotRelayOrCloseTheReplacementClient() {
        val (oldPath, oldServer) = backend("released-connect")
        val (newPath, newServer) = backend("replacement-connect")
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1).also { releases.add(it) }
        val oldDisconnected = CountDownLatch(1)
        val first = AtomicBoolean(true)
        connected = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                assertTrue(proceed.await(3, TimeUnit.SECONDS))
            }
        }
        worker {
            oldServer.accept().use {
                assertEquals(-1, it.socketInput().read(), "released client bytes reached the retired backend")
                oldDisconnected.countDown()
            }
        }
        val port = gateway.bind(0)
        gateway.selectBackend(oldPath, "released-owner")
        val old = client()
        old.getOutputStream().write("old request".toByteArray())
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertTrue(gateway.release(port))
        assertEquals(-1, old.getInputStream().read())
        gateway.bind(0)
        gateway.selectBackend(newPath, "replacement-owner")
        worker { newServer.accept().use { it.socketOutput().write(27) } }
        client().use { assertEquals(27, it.getInputStream().read()) }
        proceed.countDown()
        assertTrue(oldDisconnected.await(3, TimeUnit.SECONDS))
        assertNotNull(gateway.boundPort)
    }

    @Test
    fun clientAcceptedBeforeReleaseMustNotReachTheNextBackend() {
        val originalPort = gateway.bind(0)
        gateway.selectBackend(File(directory, "old.sock"), "old-owner")
        val listenerThread = EditorGateway::class.java.getDeclaredField("acceptThread")
            .apply { isAccessible = true }.get(gateway) as Thread
        val lock = EditorGateway::class.java.getDeclaredField("lock")
            .apply { isAccessible = true }.get(gateway)
        val reachedNewBackend = CountDownLatch(1)
        every { connectEditorBackend(any()) } answers {
            reachedNewBackend.countDown()
            mockk<EditorBackend>(relaxed = true)
        }
        synchronized(lock) {
            resources.add(Socket(InetAddress.getByName("127.0.0.1"), originalPort))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (listenerThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(Thread.State.BLOCKED, listenerThread.state, "the old listener did not reach its target-selection lock")
            assertTrue(listenerThread.stackTrace.any { it.className == EditorGateway::class.java.name && it.methodName == "accept" })
            assertTrue(gateway.release(originalPort))
            gateway.bind(0)
            gateway.selectBackend(File(directory, "new.sock"), "new-owner")
        }
        assertFalse(reachedNewBackend.await(2, TimeUnit.SECONDS),
            "a client from the released listener was routed to the next listener's backend")
    }

    /**
     * The pumps use the backend's descriptor with no lock, so the thread that ends a connection must not
     * close it while one of them may be inside a read or a write: the kernel hands the number to the next
     * file that is opened, and the pump then reads or writes that file. The connection is shut down, which
     * wakes the pump, and the descriptor is closed once both pumps have left.
     */
    @Test
    fun theBackendIsNotClosedWhileAPumpIsReadingIt() {
        val port = gateway.bind(0)
        val readEntered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val reading = AtomicInteger()
        val readingAtClose = AtomicInteger(-1)
        every { connectEditorBackend(any()) } answers {
            mockk<EditorBackend>(relaxed = true).also { backend ->
                every { backend.read(any()) } answers {
                    reading.incrementAndGet()
                    readEntered.countDown()
                    try {
                        released.await(10, TimeUnit.SECONDS)
                        0
                    } finally {
                        reading.decrementAndGet()
                    }
                }
                every { backend.shutdown() } answers { released.countDown(); Unit }
                every { backend.close() } answers {
                    readingAtClose.set(reading.get())
                    released.countDown()
                    closed.countDown()
                    Unit
                }
            }
        }
        gateway.selectBackend(File(directory, "stub.sock"), "owner")
        resources.add(Socket(InetAddress.getByName("127.0.0.1"), port))
        assertTrue(readEntered.await(3, TimeUnit.SECONDS), "the relay never read the backend")

        gateway.withdrawBackend("owner")

        assertTrue(closed.await(3, TimeUnit.SECONDS), "the backend was never closed")
        assertEquals(0, readingAtClose.get(), "the backend was closed while a pump was reading it")
    }

}
