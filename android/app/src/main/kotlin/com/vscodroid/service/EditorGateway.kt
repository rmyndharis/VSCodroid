package com.vscodroid.service

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import com.vscodroid.util.Logger
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
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
// How long the listener waits for a connection it closed to leave before the client that needed
// its slot is admitted. Both pumps are woken by the close and leave at once; one still connecting
// to the backend can take IO_POLL_MS, and the client is refused then, as it was before.
private const val MAKE_ROOM_WAIT_MS = 500L
private const val BUFFER_BYTES = 32 * 1024
// errno values, compared with Linux's own numbers rather than `OsConstants`: a JVM test reads every
// field there as 0, so each comparison matched whatever errno a case gave. The generic table arm64 uses.
private const val EINTR = 4
private const val EBADF = 9
private const val EAGAIN = 11

/**
 * Keeps the WebView origin owned even when its editor backend stops.
 *
 * [ownClients] answers, for the address the listener is bound to, with the client ends of the
 * connections to it that this process made itself, and throws when it cannot say; see
 * [ownLoopbackClients] and [admit]. [slots] is how many connections are relayed at a time.
 */
internal class EditorGateway(
    private val ownClients: (InetSocketAddress) -> Set<InetSocketAddress> = ::ownLoopbackClients,
    private val slots: Int = MAX_CONNECTIONS,
) : Closeable {
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
    // Two threads a connection, and two more: the pumps of a connection that has just left may
    // not be back in the pool when the client that takes its slot asks for its own.
    private val workers = ThreadPoolExecutor(
        0, slots * 2 + 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
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
                val connection = admit(channel, client)
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

    /**
     * Gives [client] a connection to the backend selected for [channel], or null when it is to be
     * refused: no backend, a listener released or closed, or no slot.
     *
     * Any app on the device can connect to the origin, and thirty-two connections that each send a
     * byte now and then held every slot for as long as their owner liked, with the page refused
     * meanwhile. So when every slot is taken the oldest connection that another program opened is
     * closed and the client gets its slot. A connection this process opened is never closed for
     * one: the WebView's network stack runs in this process, so those are the page's, with the
     * requests the app makes itself. With every slot held by this process the client is refused.
     */
    private fun admit(channel: ServerSocketChannel, client: SocketChannel): Connection? {
        var full = false
        // The backend is the one selected for the listener that accepted the client. A listener
        // released between the accept and this lock is no longer [listener], and the backend
        // selected meanwhile belongs to the one bound after it, which this client never reached.
        fun place(): Connection? = synchronized(lock) {
            val target = backend
            full = !closed && listener === channel && target != null && connections.size >= slots
            if (closed || listener !== channel || target == null || full) null
            else Connection(client, target).also { connections.add(it) }
        }
        place()?.let { return it }
        if (!full) return null
        // Without an answer about whose the connections are, none is closed: the client is refused.
        val ours = try {
            ownClients(channel.socket().localSocketAddress as InetSocketAddress)
        } catch (_: Exception) {
            return null
        }
        // In the order they were admitted, so the first is the oldest.
        val oldest = synchronized(lock) { connections.firstOrNull { it.clientEnd !in ours } } ?: return null
        oldest.close()
        try {
            oldest.left.await(MAKE_ROOM_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        return place()
    }

    private inner class Connection(val client: SocketChannel, val target: Backend) : Closeable {
        private val ended = AtomicBoolean()
        private val pumps = AtomicInteger(1)
        @Volatile private var local: EditorBackend? = null
        // The address and port the client connected from, which tell its owner; see [admit].
        val clientEnd: InetSocketAddress? = client.socket().remoteSocketAddress as? InetSocketAddress
        // The selectors the pumps wait on, which [close] wakes so that neither waits out a poll
        // after it. Guarded by its own monitor, which a selector is also closed under: one woken
        // after its close writes to a descriptor number that may be another file's by then.
        private val selectors = ArrayList<Selector>(2)
        /** Counted down when both pumps have left and the slot is free. */
        val left = CountDownLatch(1)
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
            if (failure.errno != EAGAIN && failure.errno != EINTR) {
                throw IOException(failure)
            }
            checkIdle()
        }

        /** Runs [pump] with a selector on the client for [interest], one that [close] wakes. */
        private inline fun selecting(interest: Int, pump: (Selector) -> Unit) {
            val selector = Selector.open()
            synchronized(selectors) { selectors.add(selector) }
            try {
                client.register(selector, interest)
                pump(selector)
            } finally {
                synchronized(selectors) {
                    selectors.remove(selector)
                    runCatching { selector.close() }
                }
            }
        }

        private fun toBackend(destination: EditorBackend) {
            val bytes = ByteArray(BUFFER_BYTES)
            val buffer = ByteBuffer.wrap(bytes)
            var heard = false
            selecting(SelectionKey.OP_READ) { selector ->
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
            selecting(SelectionKey.OP_WRITE) { selector ->
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
            // The pumps read and write the backend's descriptor with no lock, so closing it here, on
            // whatever thread ends the connection, could hand its number to another file while a pump
            // is about to use it. It is only shut down, which wakes them; [finished] closes it.
            runCatching { local?.shutdown() }
            synchronized(selectors) { selectors.forEach { it.wakeup() } }
        }

        fun finished() {
            if (pumps.decrementAndGet() == 0) {
                close()
                // Both pumps have left, so nothing reads or writes the descriptor any more, and a
                // thread still on its way to shut it down finds it closed.
                runCatching { local?.close() }
                synchronized(lock) { connections.remove(this) }
                left.countDown()
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

/**
 * The client ends of the connections to [listener] that this process opened itself.
 *
 * Nothing tells a loopback listener who its client is: every app connects from a loopback
 * address, and /proc/net/tcp is not readable by an app. A connection of this process has its
 * other end among the process's own descriptors, though, and that end names both addresses. Each
 * socket descriptor is looked at through a duplicate, closed at once. Only what reads as a socket
 * is duplicated, because closing a duplicate of a file gives up the locks the process holds on
 * that file; a descriptor closed and given to a file between the look and the duplicate is the
 * one case left, and it needs every slot to be taken at that moment.
 *
 * Throws when the descriptors cannot be listed or one of the sockets cannot be looked at: an
 * answer that left out a connection of this process would have it closed as another's.
 *
 * [descriptors] is where the process's descriptors are listed, and a parameter only so that a
 * JVM test can lay out a directory of its own; nothing in the app passes another.
 */
internal fun ownLoopbackClients(
    listener: InetSocketAddress,
    descriptors: File = File("/proc/self/fd"),
): Set<InetSocketAddress> {
    val ends = ArrayList<Pair<SocketAddress?, SocketAddress?>>()
    val names = descriptors.list() ?: throw IOException("The descriptors cannot be listed")
    for (name in names) {
        val number = name.toIntOrNull() ?: continue
        val kind = try { Os.readlink("${descriptors.path}/$number") } catch (_: ErrnoException) { continue }
        if (!kind.startsWith("socket:")) continue
        val copy = try {
            ParcelFileDescriptor.fromFd(number)
        } catch (failure: IOException) {
            // Closed since the listing. Any other failure leaves a socket nobody has looked at.
            if ((failure.cause as? ErrnoException)?.errno == EBADF) continue
            throw failure
        }
        copy.use {
            // Not connected, or of a family with no such address: not a client of the listener.
            val peer = try { Os.getpeername(it.fileDescriptor) } catch (_: Exception) { return@use }
            ends += Os.getsockname(it.fileDescriptor) to peer
        }
    }
    return clientsOf(listener, ends)
}

/**
 * Of [ends], each the local and the remote address of one socket of this process, the local
 * addresses of those connected to [listener].
 *
 * The whole address is compared on both sides, not the port alone. Another app can connect from
 * 127.0.0.2 with the listener's own port number as its source port, and the socket this process
 * accepted for it then has that port on both ends; it can also connect from the port number one
 * of this process's own connections uses, at another loopback address. Neither is a socket
 * connected to [listener], and neither has the client end of one.
 */
internal fun clientsOf(
    listener: InetSocketAddress,
    ends: List<Pair<SocketAddress?, SocketAddress?>>,
): Set<InetSocketAddress> = ends.mapNotNullTo(HashSet()) { (local, peer) ->
    (local as? InetSocketAddress)?.takeIf { peer == listener && local != listener }
}

internal class EditorBackend(private val socket: LocalSocket) : Closeable {
    private val descriptor = socket.fileDescriptor
    fun read(bytes: ByteArray): Int = Os.read(descriptor, bytes, 0, bytes.size)
    fun write(bytes: ByteArray, offset: Int, count: Int): Int = Os.write(descriptor, bytes, offset, count)
    fun shutdownOutput() = socket.shutdownOutput()

    private var closed = false

    /**
     * Ends both directions, which wakes a read or a write waiting on the socket, and keeps the
     * descriptor. Nothing once the socket is closed: a thread that ends a connection can still be on
     * its way here when the last pump leaves and closes it, and the descriptor's number may by then
     * be another file's.
     */
    @Synchronized
    fun shutdown() {
        if (closed) return
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        shutdown()
        closed = true
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
