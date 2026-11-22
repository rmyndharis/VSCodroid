package com.vscodroid.service

import android.system.ErrnoException
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import java.io.File
import java.io.IOException
import java.net.ProtocolFamily
import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

// Android's compile stubs omit the host JDK's Unix-domain channel overloads.
internal fun openUnixServerChannel(): ServerSocketChannel =
    ServerSocketChannel::class.java.getMethod("open", ProtocolFamily::class.java)
        .invoke(null, StandardProtocolFamily.UNIX) as ServerSocketChannel

internal fun openUnixClientChannel(): SocketChannel =
    SocketChannel::class.java.getMethod("open", ProtocolFamily::class.java)
        .invoke(null, StandardProtocolFamily.UNIX) as SocketChannel

/**
 * An [ErrnoException] carrying [number]. The JVM stand-in keeps no errno of its own, so a constructed
 * one reads 0 whatever it was given, which is also what every `OsConstants` field reads there. A real
 * instance, not a mock: one that escapes a failing case is serialized whole, and a mock drags MockK's
 * state along until the test worker runs out of heap.
 */
internal fun errnoException(number: Int): ErrnoException =
    ErrnoException("fixture", number).also { ErrnoException::class.java.getField("errno").setInt(it, number) }

/** Stubs only the native transport boundary; process probes still use real fixture sockets. */
internal fun mockEditorGateway(): EditorGateway {
    mockkObject(EditorGateway.Companion)
    val gateway = mockk<EditorGateway>(relaxed = true)
    var bound: Int? = null
    every { EditorGateway.instance } returns gateway
    every { gateway.boundPort } answers { bound }
    // The origin as the real gateway holds it: a bind with nothing bound opens a listener, counted in
    // listenersOpened, on the port asked for or, for 0, on one the system picks; a bind while one is
    // bound answers that port and refuses any other, and isBound is true for that port alone. So a
    // suite binds the port it then asks about. EditorGatewayTest takes this and the real class through
    // the same steps.
    var opened = 0L
    every { gateway.bind(any()) } answers {
        val port = firstArg<Int>()
        val held = bound
        if (held != null && port != 0 && port != held) throw IOException("Editor origin is already bound")
        held ?: (if (port == 0) ServerSocket(0).use { it.localPort } else port).also { opened++; bound = it }
    }
    every { gateway.listenersOpened } answers { opened }
    every { gateway.isBound(any()) } answers { firstArg<Int>().let { it > 0 && it == bound } }
    // Release and the away notice as the real gateway has them: release answers true and unbinds only
    // for the port that is bound, and takes an unsaid notice with it; the notice is said once.
    var away = 0
    every { gateway.release(any()) } answers {
        val port = firstArg<Int>()
        if (port > 0 && bound == port) { bound = null; away = 0; true } else false
    }
    every { gateway.noteAwayFromHome(any()) } answers { away = firstArg() }
    every { gateway.takeAwayFromHome() } answers { away.also { away = 0 } }
    return gateway
}

/**
 * Makes a fixture editor under [proc] die of the SIGTERM a reap sends first, as an editor does
 * once any handler it has for the signal has run, after passing its pid to the SIGKILL hook. A
 * case that collects or refuses signals there then sees every editor a reap ends, by either signal.
 */
internal fun ProcessManager.editorsDieOfTheirSigterm(proc: File) {
    terminateRecordedProcess = { pid ->
        killRecordedProcess(pid)
        File(proc, pid.toString()).deleteRecursively()
    }
}
