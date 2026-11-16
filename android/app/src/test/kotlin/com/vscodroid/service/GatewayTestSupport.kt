package com.vscodroid.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import java.net.ProtocolFamily
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

/** Stubs only the native transport boundary; process probes still use real fixture sockets. */
internal fun mockEditorGateway(): EditorGateway {
    mockkObject(EditorGateway.Companion)
    val gateway = mockk<EditorGateway>(relaxed = true)
    var bound: Int? = null
    every { EditorGateway.instance } returns gateway
    every { gateway.boundPort } answers { bound }
    // A bind with nothing bound opens a listener, as the real gateway counts them in listenersOpened.
    var opened = 0L
    every { gateway.bind(any()) } answers { firstArg<Int>().also { if (bound == null) opened++; bound = it } }
    every { gateway.listenersOpened } answers { opened }
    every { gateway.isBound(any()) } answers { firstArg<Int>() > 0 }
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
