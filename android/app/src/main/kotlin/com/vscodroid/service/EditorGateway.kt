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
    private val connections = mutableSetOf<Connection>()
    private val workers = ThreadPoolExecutor(
        0, MAX_CONNECTIONS * 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(null, task, "VSCodroid editor relay", 256 * 1024L).apply { isDaemon = true } },
    )

    val boundPort: Int?
        get() = synchronized(lock) { listener?.takeIf { it.isOpen }?.socket()?.localPort }

    fun isBound(port: Int): Boolean = port > 0 && boundPort == port

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
        acceptThread = Thread({ accept(channel) }, "VSCodroid editor listener").apply {
            isDaemon = true
            start()
        }
        channel.socket().localPort
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
                val connection = synchronized(lock) {
                    val target = backend
                    if (closed || target == null || connections.size >= MAX_CONNECTIONS) null
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
        private val lastProgress = AtomicLong(System.nanoTime())

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
            Selector.open().use { selector ->
                client.register(selector, SelectionKey.OP_READ)
                while (true) {
                    checkIdle()
                    buffer.clear()
                    val count = client.read(buffer)
                    if (count < 0) { destination.shutdownOutput(); return }
                    if (count == 0) {
                        selector.select(IO_POLL_MS.toLong())
                        selector.selectedKeys().clear()
                        continue
                    }
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
        acceptThread?.join(2000)
        workers.awaitTermination(3, TimeUnit.SECONDS)
    }

    companion object {
        val instance: EditorGateway by lazy { EditorGateway() }
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
