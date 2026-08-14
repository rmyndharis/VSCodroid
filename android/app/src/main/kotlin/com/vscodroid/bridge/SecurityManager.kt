package com.vscodroid.bridge

import java.net.URI
import com.vscodroid.util.Logger
import java.security.MessageDigest
import java.security.SecureRandom

class SecurityManager {
    private val tag = "SecurityManager"
    private val sessionToken: String = generateToken()

    fun getSessionToken(): String = sessionToken

    /**
     * Whether [token] is this session's token.
     *
     * Compared with [MessageDigest.isEqual] rather than `==`. String equality
     * returns at the first differing character, so how long the comparison takes
     * is a function of how much of the token the caller got right -- the shape
     * that lets a secret be recovered one character at a time instead of guessed
     * whole.
     *
     * Against a 32-byte SecureRandom token that attack is theoretical and stays
     * theoretical; nobody is walking 2^256 through a WebView bridge. It is
     * changed anyway because the correct primitive is free here and the
     * reasoning that makes `==` acceptable is entirely about the current token
     * size -- a fact this function does not state, does not enforce, and would
     * not notice losing. `isEqual` examines every byte and depends on no such
     * premise.
     *
     * Contract is unchanged: same signature, same answers, same rejection log.
     * Twenty-eight `@JavascriptInterface` methods validate through here and
     * `BridgeTokenUniformityTest` pins that they all do.
     */
    fun validateToken(token: String): Boolean {
        val valid = MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            sessionToken.toByteArray(Charsets.UTF_8),
        )
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

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
