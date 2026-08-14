package com.vscodroid.bridge

import java.net.URI
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
        // Allow http:// only for exact localhost/127.0.0.1 hosts (dev servers).
        //
        // Parsed with java.net.URI rather than android.net.Uri so this decision runs in
        // tests. It is not a stand-in for the Android parser: it is stricter, and the
        // cases it refuses to parse end as an exception or a null host rather than as a
        // host it read differently — which for an allow-list is the safe direction.
        //
        // The shape matters: this asks whether the host IS one of the two allowed
        // values. A null host — which `http://loc%61lhost:3000` produces without
        // throwing — therefore fails to match and is refused. Phrasing it as "reject if
        // there is a host and it is not allowed" would let that through.
        val allowed = try {
            val uri = URI(url)
            uri.scheme == "http" && (uri.host == "127.0.0.1" || uri.host == "localhost")
        } catch (e: Exception) {
            // Anything unparseable is refused, but not silently: the previous empty
            // catch is what hid the fact that this branch never ran at all.
            Logger.w(tag, "Unparseable URL refused: $url (${e.javaClass.simpleName})")
            false
        }
        if (!allowed) {
            Logger.w(tag, "Blocked URL: $url")
        }
        return allowed
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
