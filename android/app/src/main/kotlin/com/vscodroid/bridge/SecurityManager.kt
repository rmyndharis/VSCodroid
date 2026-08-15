package com.vscodroid.bridge

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

    // There is deliberately no URL allow-list here.
    //
    // `isAllowedUrl` stood at this point and permitted https, mailto, and http
    // only to localhost. It was removed rather than relaxed, because a dead
    // allow-list is the thing somebody re-wires later. VSCodroid is a development
    // environment and everything has to be reachable: a LAN dev server on plain
    // http, a private registry, a staging host, a scheme belonging to another
    // tool on the device. The list refused the work the app exists for — the same
    // `http://192.168.1.50:5173` opened when followed as a link and was silently
    // dropped when the workbench chose to send it through `window.open`.
    //
    // What remains is [validateToken], and it answers a different question: it
    // asks whether the caller is our own page, not whether the destination is one
    // somebody approved. Do not put a destination filter back here without a
    // product decision that says so; there is none today.

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
