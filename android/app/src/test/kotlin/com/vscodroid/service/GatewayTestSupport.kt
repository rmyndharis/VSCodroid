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
    every { gateway.bind(any()) } answers { firstArg<Int>().also { bound = it } }
    every { gateway.isBound(any()) } answers { firstArg<Int>() > 0 }
    return gateway
}
