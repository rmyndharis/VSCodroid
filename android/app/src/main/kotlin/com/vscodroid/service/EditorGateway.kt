package com.vscodroid.service

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.vscodroid.util.Logger
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val IO_POLL_MS = 1000
private val IDLE_NANOS = TimeUnit.MINUTES.toNanos(2)
// A client that has sent nothing this long after it was admitted is closed. Any app may open such
// connections, and 32 of them held to the idle limit refused every request of the page. A browser
// writes its request or its WebSocket upgrade as soon as it connects; only a preconnect waits, and
// Chromium checks that an unused socket is still open before it sends on it.
private val FIRST_BYTE_NANOS = TimeUnit.SECONDS.toNanos(10)
private const val MAX_CONNECTIONS = 32
private const val BUFFER_BYTES = 32 * 1024

/** Keeps the WebView origin owned even when its editor backend stops. */
internal class EditorGateway : Closeable {
    private data class Backend(val path: File, val owner: String, val generation: Long)
    private val lock = Any()
    private var listener: ServerSocketChannel? = null
    private var backend: Backend? = null
    private var generation = 0L
    private var closed = false
    private var acceptThread: Thread? = null
    // The home port this origin stands in for, until the page has been told; 0 at home. Kept with the
    // listener rather than with a ProcessManager, which a recreated service replaces while the listener
    // lives on: see [noteAwayFromHome].
    private var awayFromHome = 0
    // How many listeners bind has opened, never decremented: see [listenersOpened].
    private var opened = 0L
    private val connections = mutableSetOf<Connection>()
    private val workers = ThreadPoolExecutor(
        0, MAX_CONNECTIONS * 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(null, task, "VSCodroid editor relay", 256 * 1024L).apply { isDaemon = true } },
    )

    val boundPort: Int?
        get() = synchronized(lock) { listener?.takeIf { it.isOpen }?.socket()?.localPort }

    fun isBound(port: Int): Boolean = port > 0 && boundPort == port

    /**
     * How many listeners [bind] has opened in this gateway's life.
     *
     * A bind that returns the listener already there opens none. Read before and after
     * a span of work, it tells whether a listener bound at the end was opened in that
     * span, which the port number cannot: a listener released and opened again on the
     * same port has the same number and a higher count.
     */
    val listenersOpened: Long get() = synchronized(lock) { opened }

    /**
     * Records which home port the origin just bound stands in for, or 0 when it is home itself.
     *
     * The notice belongs to the origin: it is said on the first page served from it, by whichever
     * ProcessManager serves that page. A service the system recreates in the same process starts on
     * the origin this gateway still holds and has no reason to set it again.
     */
    fun noteAwayFromHome(home: Int) = synchronized(lock) { awayFromHome = home }

    /** The home port the current origin stands in for, once; 0 afterwards and at home. */
    fun takeAwayFromHome(): Int = synchronized(lock) { awayFromHome.also { awayFromHome = 0 } }

    fun bind(port: Int): Int = synchronized(lock) {
        if (closed) throw IOException("Editor gateway is closed")
        listener?.let {
            val bound = it.socket().localPort
            if (port != 0 && port != bound) throw IOException("Editor origin is already bound")
            return bound
        }
        val channel = ServerSocketChannel.open()
        try {
            channel.socket().reuseAddress = true
            channel.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), MAX_CONNECTIONS)
        } catch (failure: Exception) {
            channel.close()
            throw failure
        }
        listener = channel
        opened++
        acceptThread = Thread({ accept(channel) }, "VSCodroid editor listener").apply {
            isDaemon = true
            start()
        }
        channel.socket().localPort
    }

    /**
     * Closes the listener when it is the one on [port], leaving this gateway able to
     * [bind] again; answers whether it did.
     *
     * Not [close], which ends the gateway for the life of the process. This is for
     * an origin taken only because the usual one was busy, given up when the editor
     * is stopped so that the next start can bind the usual one; see
     * `ProcessManager.releaseAwayOrigin`. Open connections, the selected backend and an
     * unsaid notice that it was away go with it. It returns once the accept thread has
     * left its channel, or after [ACCEPT_END_WAIT_MS] if that thread has not ended by
     * then; the port cannot be bound again before the thread has left. A client it
     * accepted that has not been given a backend yet is closed rather than given the
     * backend of a listener bound later; see [accept].
     */
    fun release(port: Int): Boolean {
        val (channel, retired, acceptor) = synchronized(lock) {
            val current = listener ?: return false
            if (current.socket().localPort != port) return false
            listener = null
            backend = null
            awayFromHome = 0
            generation++
            Triple(current, connections.toList(), acceptThread.also { acceptThread = null })
        }
        runCatching { channel.close() }
        retired.forEach { it.close() }
        // The port stays bound until the thread waiting in accept has left the channel, and
        // close returns before that: a bind of the same port in that moment is refused. A
        // caller that releases in order to bind again, here or in another program, is told
        // only once that thread has ended, or the wait has run out.
        if (acceptor !== Thread.currentThread()) acceptor?.join(ACCEPT_END_WAIT_MS)
        return true
    }

    fun selectBackend(path: File, owner: String) {
        val retired = synchronized(lock) {
            if (closed) throw IOException("Editor gateway is closed")
            if (backend?.path == path && backend?.owner == owner) return
            backend = Backend(path, owner, ++generation)
            connections.toList()
        }
        retired.forEach { it.close() }
    }

    fun withdrawBackend(owner: String) {
        val retired = synchronized(lock) {
            if (backend?.owner != owner) return
            backend = null
            generation++
            connections.toList()
        }
        retired.forEach { it.close() }
    }

    private fun accept(channel: ServerSocketChannel) {
        var reported = false
        while (channel.isOpen) {
            var accepted: SocketChannel? = null
            try {
                val client = channel.accept()
                accepted = client
                reported = false
                client.configureBlocking(false)
                client.socket().tcpNoDelay = true
                client.socket().sendBufferSize = BUFFER_BYTES * 2
                client.socket().receiveBufferSize = BUFFER_BYTES * 2
                // The backend is the one selected for the listener that accepted the client. A listener
                // released between the accept and this lock is no longer [listener], and the backend
                // selected meanwhile belongs to the one bound after it, which this client never reached.
                val connection = synchronized(lock) {
                    val target = backend
                    if (closed || listener !== channel || target == null || connections.size >= MAX_CONNECTIONS) null
                    else Connection(client, target).also { connections.add(it) }
                }
                if (connection == null) {
                    client.use {
                        it.write(ByteBuffer.wrap(
                            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII),
                        ))
                    }
                } else {
                    try { workers.execute { connection.start() } }
                    catch (_: RejectedExecutionException) { connection.close(); connection.finished() }
                    catch (_: OutOfMemoryError) {
                        // Thread creation failing under memory pressure is no fault of the listener:
                        // refuse this client and go on accepting, rather than end the thread and,
                        // on Android, the process with it.
                        Logger.w("EditorGateway", "Editor relay could not start a thread")
                        connection.close(); connection.finished()
                    }
                    accepted = null
                }
            } catch (failure: IOException) {
                if (!channel.isOpen) break
                if (!reported) Logger.w("EditorGateway", "Editor accept failed: " + failure.message)
                reported = true
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            } finally {
                runCatching { accepted?.close() }
            }
        }
    }

    private inner class Connection(val client: SocketChannel, val target: Backend) : Closeable {
        private val ended = AtomicBoolean()
        private val pumps = AtomicInteger(1)
        @Volatile private var local: EditorBackend? = null
        private val admitted = System.nanoTime()
        private val lastProgress = AtomicLong(admitted)

        private fun progress(): Long {
            val now = System.nanoTime()
            lastProgress.accumulateAndGet(now, ::maxOf)
            return now
        }

        fun start() {
            try {
                val opened = connectEditorBackend(target.path)
                local = opened
                val current = synchronized(lock) {
                    !closed && !ended.get() && backend?.generation == target.generation
                }
                if (!current) { opened.close(); close(); return }
                pumps.incrementAndGet()
                try { workers.execute { pump { fromBackend(opened) } } }
                catch (_: RejectedExecutionException) { finished(); throw IOException("Editor relay is busy") }
                catch (_: OutOfMemoryError) { finished(); throw IOException("Editor relay could not start a thread") }
                toBackend(opened)
            } catch (_: IOException) {
                close()
            } finally {
                finished()
            }
        }

        private fun pump(action: () -> Unit) {
            try { action() } catch (_: IOException) { close() } finally { finished() }
        }

        private fun checkOpen() {
            if (ended.get() || Thread.currentThread().isInterrupted) throw IOException("Editor connection closed")
        }

        private fun checkIdle() {
            checkOpen()
            if (System.nanoTime() - lastProgress.get() >= IDLE_NANOS) throw SocketTimeoutException("Editor connection idle")
        }

        private fun retry(failure: ErrnoException) {
            if (failure.errno != OsConstants.EAGAIN && failure.errno != OsConstants.EINTR) {
                throw IOException(failure)
            }
            checkIdle()
        }

        private fun toBackend(destination: EditorBackend) {
            val bytes = ByteArray(BUFFER_BYTES)
            val buffer = ByteBuffer.wrap(bytes)
            var heard = false
            Selector.open().use { selector ->
                client.register(selector, SelectionKey.OP_READ)
                while (true) {
                    checkIdle()
                    buffer.clear()
                    val count = client.read(buffer)
                    if (count < 0) { destination.shutdownOutput(); return }
                    if (count == 0) {
                        if (!heard && System.nanoTime() - admitted >= FIRST_BYTE_NANOS) {
                            throw SocketTimeoutException("Editor client sent nothing")
                        }
                        selector.select(IO_POLL_MS.toLong())
                        selector.selectedKeys().clear()
                        continue
                    }
                    heard = true
                    var offset = 0
                    var writeProgress = progress()
                    while (offset < count) {
                        checkOpen()
                        try {
                            val written = destination.write(bytes, offset, count - offset)
                            if (written <= 0) throw IOException("Editor backend made no write progress")
                            offset += written
                            writeProgress = progress()
                        } catch (failure: ErrnoException) { retry(failure) }
                        if (System.nanoTime() - writeProgress >= IDLE_NANOS) {
                            throw SocketTimeoutException("Editor backend write stalled")
                        }
                    }
                }
            }
        }

        private fun fromBackend(source: EditorBackend) {
            val bytes = ByteArray(BUFFER_BYTES)
            Selector.open().use { selector ->
                client.register(selector, SelectionKey.OP_WRITE)
                while (true) {
                    checkIdle()
                    val count = try { source.read(bytes) } catch (failure: ErrnoException) { retry(failure); continue }
                    if (count == 0) { client.socket().shutdownOutput(); return }
                    val buffer = ByteBuffer.wrap(bytes, 0, count)
                    var writeProgress = progress()
                    while (buffer.hasRemaining()) {
                        checkOpen()
                        if (client.write(buffer) > 0) {
                            writeProgress = progress()
                        } else {
                            if (System.nanoTime() - writeProgress >= IDLE_NANOS) {
                                throw SocketTimeoutException("Editor client write stalled")
                            }
                            selector.select(IO_POLL_MS.toLong())
                            selector.selectedKeys().clear()
                        }
                    }
                }
            }
        }

        override fun close() {
            if (!ended.compareAndSet(false, true)) return
            runCatching { client.socket().shutdownInput() }
            runCatching { client.socket().shutdownOutput() }
            runCatching { client.close() }
            runCatching { local?.close() }
        }

        fun finished() {
            if (pumps.decrementAndGet() == 0) {
                close()
                synchronized(lock) { connections.remove(this) }
            }
        }
    }

    override fun close() {
        val retired = synchronized(lock) {
            closed = true
            backend = null
            connections.toList()
        }
        runCatching { listener?.close() }
        retired.forEach { it.close() }
        workers.shutdownNow()
        acceptThread?.join(ACCEPT_END_WAIT_MS)
        workers.awaitTermination(3, TimeUnit.SECONDS)
    }

    companion object {
        val instance: EditorGateway by lazy { EditorGateway() }

        // How long release and close wait for the accept thread to leave its channel.
        private const val ACCEPT_END_WAIT_MS = 2000L
    }
}

internal class EditorBackend(private val socket: LocalSocket) : Closeable {
    private val descriptor = socket.fileDescriptor
    fun read(bytes: ByteArray): Int = Os.read(descriptor, bytes, 0, bytes.size)
    fun write(bytes: ByteArray, offset: Int, count: Int): Int = Os.write(descriptor, bytes, offset, count)
    fun shutdownOutput() = socket.shutdownOutput()
    override fun close() {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        socket.close()
    }
}

internal fun connectEditorBackend(path: File): EditorBackend {
    val socket = LocalSocket()
    try {
        // Creating the descriptor first lets SO_SNDTIMEO bound a full Unix listen backlog.
        socket.outputStream
        socket.soTimeout = IO_POLL_MS
        socket.sendBufferSize = BUFFER_BYTES * 2
        socket.receiveBufferSize = BUFFER_BYTES * 2
        socket.connect(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        return EditorBackend(socket)
    } catch (failure: Exception) {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
        throw failure
    }
}
