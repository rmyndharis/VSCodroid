package com.vscodroid.util

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

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

    /** mockkObject replaces the singleton process-wide; see BridgeTokenUniformityTest.tearDown. */
    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * Holds every port the scan hands out, until it has to fall through to `ServerSocket(0)`.
     *
     * Driven by what [PortFinder.findAvailablePort] actually returns rather than by a
     * literal `13337 until 13337 + 64`. DEFAULT_PORT and SCAN_RANGE are private, so a test
     * that restates them keeps compiling and quietly stops exhausting the range the moment
     * either one moves. The cap is only a runaway guard: callers assert that the fallback
     * was really reached, and that assertion is what fails if it is ever hit.
     */
    private fun holdEntireScanRange(): List<ServerSocket> {
        val held = mutableListOf<ServerSocket>()
        try {
            while (held.size < 1024) {
                val port = PortFinder.findAvailablePort()
                if (port >= EPHEMERAL_BASE) break
                held += hold(port)
            }
        } catch (e: Exception) {
            // findAvailablePort() reports a port as free and this binds it, which is
            // two steps: another process can take it in between, and ServerSocket
            // then throws. Without this the throw escapes before the caller's
            // `val held = ...` completes, so its finally never runs and every socket
            // bound so far stays open for the life of the test JVM -- holding most of
            // the production scan range and failing whichever test runs next.
            held.forEach { it.close() }
            throw e
        }
        return held
    }

    private companion object {
        /** Bottom of the kernel's ephemeral range (`net.ipv4.ip_local_port_range`). */
        const val EPHEMERAL_BASE = 32768

        /**
         * Holds [port] the way a real holder holds it: on `127.0.0.1`.
         *
         * These fixtures used `ServerSocket(port)`, which binds the wildcard
         * address, and that models a holder this app never meets. The server is
         * launched with `--host=127.0.0.1`, and the case [PortFinder] exists to
         * survive is an orphaned server of our own still sitting on that address.
         *
         * The distinction is not pedantic; it decides the answer. Measured on
         * darwin with `SO_REUSEADDR`, which Java sets on a `ServerSocket` by
         * default:
         *
         *     holder=wildcard  tester=loopback  -> SUCCEEDS
         *     holder=loopback  tester=wildcard  -> SUCCEEDS
         *     holder=loopback  tester=loopback  -> BindException
         *     holder=wildcard  tester=wildcard  -> BindException
         *
         * A conflict is seen only when both sockets name the same address, so
         * neither form is the stronger one — the check simply has to bind what the
         * server binds. With a wildcard fixture these tests passed while modelling
         * something that cannot happen, and would have gone on passing against a
         * check unable to detect the holder that can.
         */
        fun hold(port: Int): ServerSocket =
            ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"))
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
            // Bind whatever the scan is currently handing out rather than the literal
            // base. Naming the base meant this test threw BindException -- an
            // environmental failure, not a verdict on PortFinder -- on any machine
            // already using that port.
            val taken = PortFinder.findAvailablePort()
            assertTrue(taken < EPHEMERAL_BASE, "precondition failed: the scan range was already full")

            hold(taken).use {
                val port = PortFinder.findAvailablePort()
                assertNotEquals(taken, port, "the scan must step over a bound port")
                // Stepping over the bound port and giving up on the range both
                // return something that is neither `taken` nor bound, so the two
                // assertions around this one cannot tell them apart. Measured:
                // with the scan range shrunk to a single port, every bound port
                // falls straight through to the ServerSocket(0) fallback and both
                // stay green.
                //
                // The fallback is not a harmless second answer. It hands back a
                // port from the kernel's ephemeral range, which getOrAllocatePort
                // deliberately refuses to remember, so the workbench origin stops
                // being stable across cold starts and the IndexedDB behind it is
                // dropped on every launch.
                assertTrue(
                    port < EPHEMERAL_BASE,
                    "the scan must step over $taken, not abandon the range for the " +
                        "ephemeral fallback; got $port",
                )
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
            val held = holdEntireScanRange()
            try {
                val port = PortFinder.getOrAllocatePort(context)
                assertTrue(port >= EPHEMERAL_BASE, "precondition failed: the scan range was not exhausted")
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
            // Taken before the range is held, so it is a port inside the scan range --
            // the only kind getOrAllocatePort is willing to remember. Asserted rather
            // than assumed: if something else already owns the whole range, the scan
            // returns an ephemeral port here, and remembering one of those would make
            // the assertion below pass without exercising the branch at all.
            val remembered = PortFinder.findAvailablePort()
            assertTrue(remembered < EPHEMERAL_BASE, "precondition failed: the scan range was already full")

            val held = holdEntireScanRange()
            stored = remembered
            try {
                PortFinder.getOrAllocatePort(context)
                assertEquals(remembered, stored, "the remembered port must survive an ephemeral fallback")
            } finally {
                held.forEach { it.close() }
            }
        }

        @Test
        fun `returns the same port on the next cold start`() {
            // The workbench keys IndexedDB by origin, and the port is part of the
            // origin: a different port here empties secret storage and every
            // extension's globalState, with nothing in any log to explain it.
            //
            // A cold start begins from what the previous process left in prefs, so
            // the remembered port is seeded rather than allocated here, and it has
            // to be one a fresh scan would not hand back on its own. Allocating
            // twice in a row cannot tell the two apart: findAvailablePort() scans
            // upward from a fixed base, so while that base is free the scan returns
            // the same number the remembered branch would, and the assertion holds
            // with the reuse branch deleted.
            val base = PortFinder.findAvailablePort()
            assertTrue(base < EPHEMERAL_BASE, "precondition failed: the scan range was already full")
            val remembered = hold(base).use { PortFinder.findAvailablePort() }
            assertNotEquals(base, remembered, "precondition failed: the scan did not step over $base")
            assertTrue(
                remembered < EPHEMERAL_BASE,
                "precondition failed: the scan abandoned the range for an ephemeral port",
            )

            stored = remembered

            assertEquals(
                remembered, PortFinder.getOrAllocatePort(context),
                "the port the previous process left behind must come back while it is " +
                    "free; rediscovering $base puts the workbench on a new origin and " +
                    "drops the storage keyed to $remembered",
            )
        }

        @Test
        fun `does not drift back to a lower port once it has moved`() {
            // Allocating twice over a free scan base cannot see whether the
            // port was remembered. findAvailablePort() scans upward from a
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

            val moved = hold(first).use {
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

            hold(first).use {
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
            val port = hold(0).use { it.localPort }
            assertTrue(PortFinder.isPortAvailable(port), "Released port $port should be available")
        }

        @Test
        fun `a port with only dead connections reads as available`() {
            // The remnant a reaped server leaves behind: every connection it
            // closed from its own side lingers in TIME_WAIT after the process is
            // gone, and the kernel keeps those past the kill. The real listener
            // this answer is for binds with SO_REUSEADDR, which shares a port
            // with the dead and not with the living, so the probe has to ask the
            // same question. A plain bind here refuses the port and reports a
            // bindable one as held, which is how a healthy restart came to be
            // called doomed.
            val listener = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
            val port = listener.localPort
            val client = Socket()
            client.connect(InetSocketAddress(listener.inetAddress, port))
            val accepted = listener.accept()
            accepted.close()
            client.close()
            listener.close()

            assertTrue(
                PortFinder.isPortAvailable(port),
                "remnants of connections the old holder closed must not read as a holder"
            )
        }

        @Test
        fun `returns false for a port in use`() {
            // Held on 127.0.0.1, which is where the server listens and therefore
            // the only place a holder can actually stop it binding. See [hold].
            hold(0).use { socket ->
                val port = socket.localPort
                assertFalse(PortFinder.isPortAvailable(port), "Bound port $port should NOT be available")
            }
        }

        @Test
        fun `a holder on the wildcard address does not make the port unavailable`() {
            // Not a curiosity -- it is the property that decides which address the
            // check binds, and it is the one a reader is most likely to get
            // backwards. On darwin, with SO_REUSEADDR set by default, a loopback
            // bind succeeds beside a wildcard holder, so the server would bind
            // successfully too and "available" is the honest answer. On a kernel
            // that refuses the overlap, the check and the server's own bind fail
            // together. Either way the check agrees with what the server will
            // meet, which is the whole requirement.
            //
            // Asserted as a documented observation rather than as a demand on the
            // platform: what must hold is that the check and the server agree, and
            // both bind 127.0.0.1, so they cannot disagree by construction.
            ServerSocket(0).use { wildcard ->
                val port = wildcard.localPort
                val available = PortFinder.isPortAvailable(port)
                val serverCouldBind = try {
                    hold(port).use { true }
                } catch (e: Exception) {
                    false
                }
                assertEquals(
                    serverCouldBind, available,
                    "the availability check must give the same answer the server's own " +
                        "bind would give; it said $available, the bind said $serverCouldBind",
                )
            }
        }
    }
}
