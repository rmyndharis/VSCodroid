package com.vscodroid.service

import android.net.LocalSocket
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * The editor backend's socket after it has been closed.
 *
 * The relay's pumps use the descriptor with no lock, so it is closed only when both have left. A
 * thread that ends the connection from outside (a withdrawn backend, a stop, a slot taken for a new
 * client) shuts the socket down to wake them, and it can still be on its way there when the last pump
 * leaves and closes the socket. By then the descriptor's number can be another file's, so a shutdown
 * that arrives after the close has to do nothing.
 */
class EditorBackendCloseTest {

    private val socket = mockk<LocalSocket>(relaxed = true)

    @Test
    fun `a shutdown that arrives after the close touches nothing`() {
        val backend = EditorBackend(socket)
        backend.close()

        backend.shutdown()

        verify(exactly = 1) { socket.shutdownInput() }
        verify(exactly = 1) { socket.shutdownOutput() }
        verify(exactly = 1) { socket.close() }
    }

    @Test
    fun `a second close closes nothing a second time`() {
        val backend = EditorBackend(socket)
        backend.close()

        backend.close()

        verify(exactly = 1) { socket.close() }
    }

    @Test
    fun `a shutdown before the close still wakes the pumps and leaves the socket open`() {
        val backend = EditorBackend(socket)

        backend.shutdown()

        verify(exactly = 1) { socket.shutdownInput() }
        verify(exactly = 1) { socket.shutdownOutput() }
        verify(exactly = 0) { socket.close() }
    }
}
