package com.vscodroid.service

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * Which of the process's own descriptors [ownLoopbackClients] looks at, and how.
 *
 * The gateway closes the oldest connection of another app when its slots are full, and never one of
 * this process's, so this list is what keeps the WebView's own connections open. On a device it reads
 * `/proc/self/fd`; here the directory and the system calls are stood in for, so what is run is the
 * choice of descriptors: a duplicate of a file would give up the locks the process holds on it, a
 * descriptor that went away meanwhile is passed over, and any other failure is not an answer.
 */
class OwnLoopbackClientsTest {

    @TempDir
    lateinit var fds: File

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val listener = InetSocketAddress(loopback, 13337)
    private val duplicates = mutableMapOf<Int, ParcelFileDescriptor>()

    @BeforeEach
    fun setUp() {
        mockkStatic(Os::class)
        mockkStatic(ParcelFileDescriptor::class)
        every { ParcelFileDescriptor.fromFd(any()) } answers { throw AssertionError("descriptor ${firstArg<Int>()} was duplicated") }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun address(port: Int) = InetSocketAddress(loopback, port)

    /**
     * What `Os` and `ParcelFileDescriptor` throw on a device, with an errno the JVM stand-in does not keep.
     * The numbers are Linux's own, as the code compares them: `OsConstants` reads 0 for every one here,
     * so a case written with it matched whatever the code compared against.
     */
    private fun errno(number: Int): ErrnoException = errnoException(number)

    private fun descriptor(number: Int, kind: String) {
        File(fds, number.toString()).createNewFile()
        every { Os.readlink("${fds.path}/$number") } returns kind
    }

    private fun socket(number: Int, local: SocketAddress, peer: SocketAddress?) {
        descriptor(number, "socket:[${4000 + number}]")
        val fd = FileDescriptor()
        val copy = mockk<ParcelFileDescriptor>(relaxed = true) { every { fileDescriptor } returns fd }
        every { ParcelFileDescriptor.fromFd(number) } returns copy
        every { Os.getsockname(fd) } returns local
        if (peer == null) every { Os.getpeername(fd) } throws errno(ENOTCONN) else every { Os.getpeername(fd) } returns peer
        duplicates[number] = copy
    }

    @Test
    fun `only sockets are looked at, each through a duplicate that is closed, and the clients of the listener come back`() {
        descriptor(0, "/dev/null")
        descriptor(3, "pipe:[77]")
        descriptor(9, "/data/data/com.vscodroid/files/home/.vscodroid/toolchains.json")
        File(fds, "not-a-number").createNewFile()
        socket(4, address(40000), listener)
        // The end this process accepted for that client: its local address is the listener's own.
        socket(5, listener, address(40000))
        socket(6, address(40001), address(9999))
        socket(7, address(40002), null)

        val ends = listOf<Pair<SocketAddress?, SocketAddress?>>(
            address(40000) to listener, listener to address(40000), address(40001) to address(9999),
        )
        assertEquals(clientsOf(listener, ends), ownLoopbackClients(listener, fds))
        assertEquals(setOf(address(40000)), ownLoopbackClients(listener, fds))

        verify(exactly = 0) { ParcelFileDescriptor.fromFd(0) }
        verify(exactly = 0) { ParcelFileDescriptor.fromFd(3) }
        verify(exactly = 0) { ParcelFileDescriptor.fromFd(9) }
        // Asked twice above, so each duplicate is made and closed twice.
        for (copy in duplicates.values) verify(exactly = 2) { copy.close() }
    }

    @Test
    fun `a descriptor whose link cannot be read is passed over`() {
        File(fds, "5").createNewFile()
        every { Os.readlink("${fds.path}/5") } throws errno(EBADF)
        socket(4, address(40000), listener)

        assertEquals(setOf(address(40000)), ownLoopbackClients(listener, fds))
    }

    @Test
    fun `a socket closed since the listing is passed over`() {
        descriptor(5, "socket:[1]")
        every { ParcelFileDescriptor.fromFd(5) } throws IOException("dup", errno(EBADF))
        socket(4, address(40000), listener)

        assertEquals(setOf(address(40000)), ownLoopbackClients(listener, fds))
    }

    @Test
    fun `a socket that cannot be looked at for any other reason is not an answer`() {
        descriptor(5, "socket:[1]")
        every { ParcelFileDescriptor.fromFd(5) } throws IOException("dup", errno(ECHILD))
        socket(4, address(40000), listener)

        assertThrows<IOException> { ownLoopbackClients(listener, fds) }
    }

    @Test
    fun `descriptors that cannot be listed are not an answer`() {
        assertThrows<IOException> { ownLoopbackClients(listener, File(fds, "absent")) }
    }

    private companion object {
        // The generic Linux table that arm64 uses.
        const val EBADF = 9
        const val ECHILD = 10
        const val ENOTCONN = 107
    }
}
