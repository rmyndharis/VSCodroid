package com.vscodroid.util

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/**
 * Tests for [PortFinder] — port discovery, availability checking, and the
 * remembered port that keeps the workbench origin stable across cold starts.
 */
class PortFinderTest {

    private lateinit var context: Context

    /** Stands in for the SharedPreferences file, so a round trip can be asserted. */
    private var stored: Int = 0

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs

        stored = 0
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putInt(any(), any()) } answers {
            stored = secondArg()
            editor
        }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getInt(any(), any()) } answers { stored }
        every { prefs.edit() } returns editor

        context = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
    }

    @Nested
    inner class FindAvailablePortTest {

        @Test
        fun `returns a valid port number`() {
            val port = PortFinder.findAvailablePort()
            assertTrue(port in 1..65535, "Port $port should be in valid range 1-65535")
        }

        @Test
        fun `returns a port that is currently available`() {
            val port = PortFinder.findAvailablePort()
            assertTrue(PortFinder.isPortAvailable(port), "Port $port should be available after discovery")
        }

        @Test
        fun `prefers a port below the ephemeral range`() {
            // A port from ServerSocket(0) comes out of the kernel's ephemeral range,
            // where an unrelated outbound socket can be holding it by the next launch.
            // Scanning from a fixed base is what makes the remembered port worth
            // remembering, so the scan must actually be reached.
            val port = PortFinder.findAvailablePort()
            assertTrue(port < 32768, "Port $port should sit below the ephemeral range")
        }

        @Test
        fun `skips a port that is already bound`() {
            ServerSocket(13337).use {
                val port = PortFinder.findAvailablePort()
                assertNotEquals(13337, port, "the scan must step over a bound port")
                assertTrue(PortFinder.isPortAvailable(port))
            }
        }
    }

    @Nested
    inner class RememberedPortTest {

        @Test
        fun `remembers the port it allocated`() {
            val first = PortFinder.getOrAllocatePort(context)
            assertEquals(first, stored, "the allocated port must be persisted")
        }

        @Test
        fun `does not remember a port from the ephemeral range`() {
            // Exhaust the scan range so allocation has to fall through to
            // ServerSocket(0). That port comes out of the kernel's volatile range,
            // where an unrelated outbound socket can be holding it by the next
            // launch -- remembering it would re-arm the very storage loss the fixed
            // scan base exists to avoid, and it would never migrate back once the
            // congestion clears.
            val held = (13337 until 13337 + 64).mapNotNull {
                runCatching { ServerSocket(it) }.getOrNull()
            }
            try {
                val port = PortFinder.getOrAllocatePort(context)
                assertTrue(port >= 32768, "precondition failed: the scan range was not exhausted")
                assertEquals(0, stored, "an ephemeral port must not be persisted")
            } finally {
                held.forEach { it.close() }
            }
        }

        @Test
        fun `keeps the previously remembered port when it falls back to an ephemeral one`() {
            // The old value is worth more than the emergency port: if the range was
            // only briefly full, the next cold start returns to the origin this
            // install has been using, with its IndexedDB intact.
            stored = 13350
            val held = (13337 until 13337 + 64).mapNotNull {
                runCatching { ServerSocket(it) }.getOrNull()
            }
            try {
                PortFinder.getOrAllocatePort(context)
                assertEquals(13350, stored, "the remembered port must survive an ephemeral fallback")
            } finally {
                held.forEach { it.close() }
            }
        }

        @Test
        fun `returns the same port on the next cold start`() {
            // The workbench keys IndexedDB by origin, and the port is part of the
            // origin: a different port here empties secret storage and every
            // extension's globalState, with nothing in any log to explain it.
            val first = PortFinder.getOrAllocatePort(context)
            val second = PortFinder.getOrAllocatePort(context)
            assertEquals(first, second)
        }

        @Test
        fun `does not drift back to a lower port once it has moved`() {
            // `returns the same port on the next cold start` cannot see whether
            // the port was remembered. findAvailablePort() scans upward from a
            // fixed default, so while that default is free -- the ordinary case --
            // a fresh scan returns the same number the remembered branch would
            // have. Both calls move together, and the assertion holds with the
            // branch deleted.
            //
            // Holding the first port forces the two paths apart: the remembered
            // port is now the higher one, and only a scan would go back down to
            // the lower one. Drifting back is not harmless -- it is a second
            // origin change, and it discards whatever the workbench stored under
            // the port we had just moved to.
            val first = PortFinder.getOrAllocatePort(context)

            val moved = ServerSocket(first).use {
                PortFinder.getOrAllocatePort(context).also { moved ->
                    assertNotEquals(first, moved, "a held port cannot be reused")
                }
            }

            assertEquals(
                moved, PortFinder.getOrAllocatePort(context),
                "once $first was free again the remembered $moved must still win; " +
                    "returning $first would empty the storage keyed to $moved",
            )
        }

        @Test
        fun `moves off a remembered port that something else has taken`() {
            val first = PortFinder.getOrAllocatePort(context)

            ServerSocket(first).use {
                val second = PortFinder.getOrAllocatePort(context)
                assertNotEquals(first, second, "a taken port cannot be reused")
                assertEquals(second, stored, "the new port must replace the old one")
            }
        }
    }

    @Nested
    inner class IsPortAvailableTest {

        @Test
        fun `returns true for an unused port`() {
            val port = ServerSocket(0).use { it.localPort }
            assertTrue(PortFinder.isPortAvailable(port), "Released port $port should be available")
        }

        @Test
        fun `returns false for a port in use`() {
            ServerSocket(0).use { socket ->
                val port = socket.localPort
                assertFalse(PortFinder.isPortAvailable(port), "Bound port $port should NOT be available")
            }
        }
    }
}
