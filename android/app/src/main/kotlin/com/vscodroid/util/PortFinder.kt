package com.vscodroid.util

import android.content.Context
import java.net.InetAddress
import java.net.ServerSocket

object PortFinder {
    private const val TAG = "PortFinder"

    /**
     * Base of the scan range, and the fallback when nothing can be bound.
     *
     * Deliberately below the kernel's ephemeral range (`net.ipv4.ip_local_port_range`,
     * typically 32768-60999): a port handed out by `ServerSocket(0)` comes from that
     * range, where an unrelated outbound connection can be holding it at the moment
     * the next launch checks. Scanning from a fixed base keeps the remembered port
     * free far more often, which is the whole point of remembering it.
     */
    private const val DEFAULT_PORT = 13337
    private const val SCAN_RANGE = 64

    private const val PREFS_NAME = "vscodroid"
    private const val KEY_PORT = "server_port"

    /**
     * The address the server binds, resolved once.
     *
     * A literal rather than `InetAddress.getLoopbackAddress()`, which follows the
     * `java.net.preferIPv6Addresses` system property and can answer `::1`. The
     * server is told `127.0.0.1`, so this has to be the same thing and not
     * whichever loopback the JVM prefers.
     *
     * Declared here, above every function that reads it, rather than at the foot
     * of the object. Property initialisers run in declaration order, so a
     * property or `init` block added above a later declaration and calling
     * [isPortAvailable] would have read null. Nothing does that today; the point
     * is that nothing can.
     */
    private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

    /**
     * The port this install serves the workbench on, stable across cold starts.
     *
     * The port is part of the WebView's origin, and the browser keys IndexedDB by
     * origin. Secret storage and every extension's `globalState` live there, so a
     * freshly allocated port on each launch silently emptied all of it — sessions
     * signed out, extension state reset, with nothing in any log to connect it to
     * the port. The chosen port is therefore remembered and reused whenever it is
     * still free.
     *
     * Moving to a different port is still correct when the old one is taken; it
     * costs the workbench storage held under the old origin, so it is logged.
     */
    fun getOrAllocatePort(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remembered = prefs.getInt(KEY_PORT, 0)
        if (remembered != 0 && isPortAvailable(remembered)) {
            return remembered
        }

        val port = findAvailablePort()
        if (remembered != 0) {
            Logger.w(
                TAG,
                "Port $remembered is taken, moving to $port. " +
                    "Workbench storage keyed to the old origin is lost.",
            )
        }
        // Only a port from the scan range is worth remembering. The fallback in
        // findAvailablePort() returns one from the kernel's ephemeral range, and
        // the comment on DEFAULT_PORT explains why that is the wrong thing to
        // write here: an unrelated outbound socket can be holding such a port at
        // the moment of the next launch, which sends the workbench to a new
        // origin and drops the IndexedDB behind the old one. Persisting it would
        // also be one-way -- once remembered, the port never migrates back to the
        // stable range even after the congestion that forced it has cleared.
        //
        // Leaving the previous value in place is deliberate: the next cold start
        // retries the port this install has been using, which is exactly right if
        // the range was only briefly full. This is persistence only; the port is
        // still returned and still reused for the whole process lifetime through
        // ProcessManager's cached _port.
        if (port in DEFAULT_PORT until DEFAULT_PORT + SCAN_RANGE) {
            prefs.edit().putInt(KEY_PORT, port).apply()
        }
        return port
    }

    /**
     * The first free port at or above [DEFAULT_PORT], falling back to an
     * OS-assigned one when the whole scan range is occupied.
     */
    fun findAvailablePort(): Int {
        for (port in DEFAULT_PORT until DEFAULT_PORT + SCAN_RANGE) {
            if (isPortAvailable(port)) return port
        }
        return try {
            // Loopback here too, for the reason [isPortAvailable] gives. Asking
            // the OS for a port free on the wildcard address would leave this one
            // function using two different notions of "free": the scan above
            // rejects a port held on 127.0.0.1, and this would then hand back
            // exactly such a port on any system where a wildcard bind can succeed
            // beside a specific one.
            ServerSocket(0, 0, LOOPBACK).use { socket ->
                socket.localPort
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to find available port, using default $DEFAULT_PORT", e)
            DEFAULT_PORT
        }
    }

    /**
     * Whether [port] can be bound on the address the server will actually use.
     *
     * Loopback, not the wildcard address, and the difference is not academic.
     * `ProcessManager` launches the server with `--host=127.0.0.1`, so the only
     * useful question is whether *that* address is free. `ServerSocket(port)`
     * binds `0.0.0.0` and asks a different one — and the two answers disagree:
     * Java sets `SO_REUSEADDR` on a `ServerSocket` by default, and on
     * BSD-derived systems that lets a wildcard bind succeed while a specific
     * address is held. Measured here: with a holder on `127.0.0.1:53194`, a
     * wildcard bind of 53194 succeeded and a loopback bind failed with
     * `EADDRINUSE`. So the wildcard form reported a held port as free.
     *
     * Linux is believed to refuse the wildcard bind in that situation, which
     * would make the old form accidentally right on device. That belief is not
     * what this rests on, and deliberately so — nobody has measured it on
     * Android, and a check that happens to agree with the server on one platform
     * is a check that disagrees with it on another.
     */
    fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port, 0, LOOPBACK).use { true }
        } catch (e: Exception) {
            false
        }
    }
}
