package com.vscodroid.webview

import com.vscodroid.callbackNonce
import com.vscodroid.callbackSecretMatches
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What proves a sign-in callback came from the editor's own page.
 *
 * The `vscodroid://callback` filter is exported and BROWSABLE, so any app on the
 * device and any page in any browser can fire it. Everything the gate checked
 * before this is guessable from outside: the request id is a counter the
 * workbench starts at one for each page, and the window around it is ten
 * minutes. So a page that knew a sign-in was in flight could forge the callback,
 * hand the signing-in extension an OAuth code of its choosing, and take the
 * pending id with it so the user's own callback was dropped.
 *
 * `server.js` binds a fresh secret into `callback.html` on every start and
 * records it in the app sandbox. The page is served from the server's origin, so
 * no other origin can read it.
 */
class AuthCallbackSecretTest {

    private val ours = "9f2c" + "a".repeat(60)

    @Test
    fun `a payload carrying the secret is accepted`() {
        assertTrue(
            callbackSecretMatches(ours, ours),
            "the editor's own callback was refused, so a real sign-in never completes",
        )
    }

    @Test
    fun `a payload carrying no secret is refused`() {
        assertFalse(
            callbackSecretMatches(null, ours),
            "a forged callback that simply omitted the secret was accepted",
        )
    }

    @Test
    fun `a payload carrying the wrong secret is refused`() {
        assertFalse(
            callbackSecretMatches("9f2c" + "b".repeat(60), ours),
            "a guessed secret was accepted",
        )
    }

    /**
     * A prefix must not pass. A comparison that stopped at the shorter of the two
     * would let a caller walk the secret out one character at a time.
     */
    @Test
    fun `a payload carrying a prefix of the secret is refused`() {
        assertFalse(
            callbackSecretMatches(ours.take(8), ours),
            "a prefix of the secret was accepted, so the secret can be guessed a " +
                "character at a time",
        )
    }

    /**
     * The file lives inside the app sandbox, so its absence means this app's own
     * binding did not happen, never that a caller withheld anything. Refusing here
     * would take sign-in away entirely from a build whose page could not be
     * rewritten, which is a worse outcome than the matching that shipped before.
     */
    @Test
    fun `no recorded secret falls back to the older matching`() {
        assertTrue(
            callbackSecretMatches(null, null),
            "a run where the server bound no secret refused every sign-in",
        )
        assertTrue(
            callbackSecretMatches(null, ""),
            "an empty secret file refused every sign-in",
        )
    }

    @Test
    fun `the secret is read out of the payload the page builds`() {
        assertEquals(
            ours,
            callbackNonce("""{"id":"1","uri":{"scheme":"vscodroid"},"nonce":"$ours"}"""),
        )
    }

    @Test
    fun `a payload that is not readable carries no secret`() {
        assertNull(callbackNonce(null), "a missing payload produced a secret")
        assertNull(callbackNonce(""), "an empty payload produced a secret")
        assertNull(callbackNonce("not json at all"), "unparseable text produced a secret")
        assertNull(
            callbackNonce("""{"id":"1"}"""),
            "a payload with no nonce produced one, which is the shape every forged " +
                "callback written before this check has",
        )
        assertNull(
            callbackNonce("""{"id":"1","nonce":{"not":"a string"}}"""),
            "a non-string nonce was read as a secret",
        )
    }
}
