package com.vscodroid.bridge

import android.net.Uri
import com.vscodroid.util.Logger
import java.security.SecureRandom

class SecurityManager(private val allowedPort: Int) {
    private val tag = "SecurityManager"
    private val sessionToken: String = generateToken()

    fun getSessionToken(): String = sessionToken

    fun validateToken(token: String): Boolean {
        val valid = token == sessionToken
        if (!valid) {
            Logger.w(tag, "Invalid auth token rejected")
        }
        return valid
    }

    fun isAllowedUrl(url: String): Boolean {
        if (url.startsWith("https://") || url.startsWith("mailto:")) {
            return true
        }
        // Allow http:// only for exact localhost/127.0.0.1 hosts (dev servers)
        try {
            val uri = Uri.parse(url)
            val host = uri.host
            if (uri.scheme == "http" && (host == "127.0.0.1" || host == "localhost")) {
                return true
            }
        } catch (_: Exception) { }
        Logger.w(tag, "Blocked URL scheme: $url")
        return false
    }

    fun isAllowedOrigin(origin: String?): Boolean {
        if (origin == null) return false
        val allowed = origin == "http://127.0.0.1:$allowedPort" ||
                origin == "http://localhost:$allowedPort"
        if (!allowed) {
            Logger.w(tag, "Rejected origin: $origin")
        }
        return allowed
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
