package com.vscodroid.webview

import com.vscodroid.callbackNonce
import com.vscodroid.callbackQueryWithoutNonce
import com.vscodroid.callbackSecretMatches
import com.vscodroid.callbackUriJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.json.JSONObject

/**
 * What proves a sign-in callback answers the launch this app made for it.
 *
 * The `vscodroid://callback` filter is exported and BROWSABLE, so any app on the
 * device and any page in any browser can fire it. Everything else the gate checks
 * is guessable from outside: the request id is a counter the workbench starts at
 * one for each page, and the window around it is ten minutes. So a caller that
 * knew a sign-in was in flight could forge the callback, hand the signing-in
 * extension an OAuth code of its choosing, and take the pending id with it so the
 * user's own callback was dropped.
 *
 * The secret that stops it is minted per request by the workbench (patch 0019),
 * read out of the address this app is asked to open, and carried back in the
 * query the provider redirects to. It replaced a per-run secret bound into
 * `callback.html`, which any app on the device could read with a plain GET to
 * `/callback`: that route is answered before the connection-token check.
 */
class AuthCallbackSecretTest {

    private val ours = "9f2c" + "a".repeat(28)

    private fun payload(query: String?) = JSONObject(
        mapOf(
            "id" to "1",
            "uri" to JSONObject(
                mutableMapOf<String, Any>("scheme" to "vscodroid", "authority" to "ext")
                    .apply { query?.let { put("query", it) } },
            ),
        ),
    ).toString()

    @Test
    fun `a callback carrying this request's secret is accepted`() {
        assertTrue(
            callbackSecretMatches(ours, ours),
            "the browser's own callback was refused, so a real sign-in never completes",
        )
    }

    @Test
    fun `a callback carrying no secret is refused`() {
        assertFalse(
            callbackSecretMatches(null, ours),
            "a forged callback that simply omitted the secret was accepted",
        )
    }

    @Test
    fun `a callback carrying another secret is refused`() {
        assertFalse(
            callbackSecretMatches("9f2c" + "b".repeat(28), ours),
            "a guessed secret was accepted",
        )
    }

    /**
     * A prefix must not pass. A comparison that stopped at the shorter of the two
     * would let a caller walk the secret out one character at a time.
     */
    @Test
    fun `a callback carrying a prefix of the secret is refused`() {
        assertFalse(
            callbackSecretMatches(ours.take(8), ours),
            "a prefix of the secret was accepted, so the secret can be guessed a " +
                "character at a time",
        )
    }

    /**
     * An address this app opened without a secret in it is one it never had a
     * secret for. Refusing there would take sign-in away from a flow whose callback
     * URL this app could not read, and from a provider that drops parameters it was
     * not expecting, which is worse than the matching that shipped before.
     */
    @Test
    fun `an address that carried no secret falls back to the older matching`() {
        assertTrue(
            callbackSecretMatches(null, null),
            "a launch whose address carried no secret refused its own callback",
        )
        assertTrue(
            callbackSecretMatches(null, ""),
            "an empty secret refused every sign-in",
        )
    }

    @Test
    fun `the secret is read out of the query the provider redirected to`() {
        assertEquals(
            ours,
            callbackNonce(payload("code=abc&vscodroid-nonce=$ours&state=xyz")),
            "the secret the callback carried was not found, so a real sign-in is refused",
        )
        assertEquals(ours, callbackNonce(payload("vscodroid-nonce=$ours")))
    }

    @Test
    fun `a callback with nothing to read carries no secret`() {
        assertNull(callbackNonce(null), "a missing payload produced a secret")
        assertNull(callbackNonce(""), "an empty payload produced a secret")
        assertNull(callbackNonce("not json at all"), "unparseable text produced a secret")
        assertNull(callbackNonce(payload(null)), "a callback with no query produced a secret")
        assertNull(
            callbackNonce(payload("code=abc")),
            "a query without the parameter produced a secret, which is the shape every " +
                "callback forged before this check has",
        )
        assertNull(
            callbackNonce("""{"id":"1","uri":{"query":{"not":"a string"}}}"""),
            "a query that is not a string was read as one",
        )
        assertNull(
            callbackNonce(payload("x-vscodroid-nonce=$ours")),
            "a parameter merely ending in the name was read as the secret",
        )
    }

    /**
     * The secret is this app's business with the browser. What the workbench hands
     * the extension is the callback it asked for, so the parameter goes before the
     * address is relayed, and a query left empty by that goes with it.
     */
    @Test
    fun `the secret does not travel on to the extension`() {
        val relayed = callbackUriJson(payload("code=abc&vscodroid-nonce=$ours"))
        assertEquals("code=abc", JSONObject(relayed!!).optString("query"))
        assertTrue(
            JSONObject(callbackUriJson(payload("vscodroid-nonce=$ours"))!!).isNull("query"),
            "a query holding nothing but the secret was relayed as an empty one",
        )
        assertEquals("code=abc", callbackQueryWithoutNonce("code=abc"))
    }
}
