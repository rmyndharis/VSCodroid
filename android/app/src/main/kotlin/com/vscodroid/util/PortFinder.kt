package com.vscodroid.util

import android.content.Context
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import androidx.core.content.edit

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
     * This install's home port, the one its workbench storage lives under, or 0
     * when there is none yet.
     *
     * Split out of [getOrAllocatePort] because the two answer different questions
     * and only one of them is allowed to move. [getOrAllocatePort] answers "what can
     * be bound now", so a held port makes it walk to a free one for the process.
     * A caller deciding whether the HOLDER is worth adopting needs the port itself,
     * held or not, and needs it before that walk.
     *
     * The single reader of [KEY_PORT], so the two cannot drift into disagreeing
     * about which key the remembered port lives under.
     */
    fun rememberedPort(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_PORT, 0)

    /**
     * The port to bind now: the remembered one while it is free, another one otherwise.
     *
     * The port is part of the WebView's origin, and the browser keys IndexedDB and
     * localStorage by origin. Every extension's `globalState` lives in the first
     * and the sealed extension secrets in the second, so a
     * freshly allocated port on each launch silently emptied all of it: sessions
     * signed out, extension state reset, with nothing in any log to connect it to
     * the port. The remembered port is therefore reused whenever it is free.
     *
     * Nothing is written here. A port taken because the remembered one is busy
     * serves this process only: written over the remembered one, it moved the
     * workbench to a new origin for good, so one start with another program on the
     * port left the user's settings, sign-ins and open editors behind the old
     * origin, never read again and with nothing said, while the next cold start
     * would have found the old port free. [rememberHome] writes the first port an
     * install binds, and nothing ever replaces it.
     */
    fun getOrAllocatePort(context: Context): Int {
        val remembered = rememberedPort(context)
        if (remembered != 0 && isPortAvailable(remembered)) {
            return remembered
        }

        val port = findAvailablePort()
        if (remembered != 0) {
            Logger.w(
                TAG,
                "Port $remembered is taken; this process uses $port, and the next " +
                    "cold start tries $remembered again.",
            )
        }
        return port
    }

    /**
     * Makes [port] this install's home port, when it has none yet.
     *
     * Called with the first port a start actually bound, so a port that was only
     * found free and then lost to another program before the bind never becomes
     * home. A home already remembered is never replaced, which keeps an install
     * whose port moved before this rule existed on the port its present storage
     * lives under.
     *
     * Only a port from the scan range is worth remembering. The fallback in
     * findAvailablePort() returns one from the kernel's ephemeral range, and the
     * comment on DEFAULT_PORT explains why that is the wrong thing to write here:
     * an unrelated outbound socket can be holding such a port at the moment of the
     * next launch, which sends the workbench to a new origin. Leaving home unset
     * instead makes the next cold start scan the range again.
     */
    fun rememberHome(context: Context, port: Int) {
        if (rememberedPort(context) != 0 || port !in DEFAULT_PORT until DEFAULT_PORT + SCAN_RANGE) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putInt(KEY_PORT, port) }
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
     * binds `0.0.0.0` and asks a different one, and the two answers disagree:
     * Java sets `SO_REUSEADDR` on a `ServerSocket` by default, and on
     * BSD-derived systems that lets a wildcard bind succeed while a specific
     * address is held. Measured here: with a holder on `127.0.0.1:53194`, a
     * wildcard bind of 53194 succeeded and a loopback bind failed with
     * `EADDRINUSE`. So the wildcard form reported a held port as free.
     *
     * Linux is believed to refuse the wildcard bind in that situation, which
     * would make the old form accidentally right on device. That belief is not
     * what this rests on, and deliberately so: nobody has measured it on
     * Android, and a check that happens to agree with the server on one platform
     * is a check that disagrees with it on another.
     *
     * Binds the way the server that will use the port binds: with `SO_REUSEADDR`
     * set before the bind.
     *
     * A plain bind refuses a port whose only occupants are `TIME_WAIT` remnants,
     * connections the previous holder closed from its own side and the kernel
     * keeps for a while after the process is gone. The editor server sets
     * `SO_REUSEADDR` (libuv does it by default), so it can bind exactly those
     * ports, and a probe that cannot is answering a question nothing asked:
     * after reaping a stuck server of ours, the remnant of every connection it
     * dropped reads as "still held", the verdict calls the replacement doomed,
     * and a healthy start is stopped once for nothing. Setting the option asks
     * the one question that matters, whether a real listener can go there,
     * without changing the answer for a port with an active listener: reuse
     * permits sharing a port with the dead, not with the living.
     */
    fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(LOOPBACK, port), 0)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
