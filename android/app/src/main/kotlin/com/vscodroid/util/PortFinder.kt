package com.vscodroid.util

import java.net.ServerSocket

object PortFinder {
    private const val TAG = "PortFinder"
    private const val DEFAULT_PORT = 13337

    fun findAvailablePort(): Int {
        return try {
            ServerSocket(0).use { socket ->
                socket.localPort
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to find available port, using default $DEFAULT_PORT", e)
            DEFAULT_PORT
        }
    }

    fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }
}
