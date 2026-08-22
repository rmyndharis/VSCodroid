package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.json.JSONObject
import java.io.File

/**
 * What the sign-in relay reads out of a callback, and what it hands the page.
 *
 * `callback.html` encodes the payload exactly once
 * (`encodeURIComponent(JSON.stringify({ id, uri }))`) and `getQueryParameter`
 * undoes it, so what reaches Kotlin is the JSON object itself. The relay used to
 * run `decodeURIComponent` over that text again inside the page, which undid a
 * second layer of escaping that was never applied. Two things came off that.
 *
 * The address is percent-encoded by construction: `callback.html` builds
 * `uri.query` with `params.toString()`. A second decode therefore rewrote the
 * provider's own values, so a `+` inside a base64 authorisation code arrived as
 * a space, an escaped `&` split the query and invented a parameter, and a quote
 * made the page's own `JSON.parse` throw. Nothing on screen said so.
 *
 * And the page was parsing the same attacker-supplied text the timing gate had
 * parsed, by a different grammar, so the id the gate approved was not
 * necessarily the id the value was written under. The filter that delivers this
 * is exported and BROWSABLE, so the payload is anyone's to write.
 *
 * Both are closed the same way: one reader, in Kotlin, whose results are handed
 * to the page as finished literals.
 */
class CallbackRelayTest {

    /**
     * A payload shaped exactly as `callback.html` builds one, after
     * `getQueryParameter` has undone the single layer of encoding.
     *
     * Written as text rather than assembled with `JSONObject`, because the
     * property under test is how a browser's bytes are read, and building the
     * input with the same library that reads it would test the round trip
     * instead.
     */
    private val payload =
        """{"id":"7","uri":{"scheme":"vscode","authority":"ms-x.y",""" +
            """"query":"code=4%2F0AX4XfWjA%2BbQ%2FcD&state=a%26b%3Dc"}}"""

    @Test
    fun `the address is read out of the payload`() {
        val uri = JSONObject(callbackUriJson(payload)!!)
        assertEquals("vscode", uri.getString("scheme"))
        assertEquals("ms-x.y", uri.getString("authority"))
    }

    @Test
    fun `the provider's own escaping reaches the workbench untouched`() {
        // The whole defect, as one assertion. Decoded a second time this reads
        // `code=4/0AX4XfWjA+bQ/cD&state=a&b=c`, where the `+` of a base64 code
        // has become a space, `state` is truncated at an `&` the provider
        // escaped, and a parameter called `b` appears that nothing sent. The
        // token exchange then fails with invalid_grant and the sign-in dies.
        val uri = JSONObject(callbackUriJson(payload)!!)
        assertEquals("code=4%2F0AX4XfWjA%2BbQ%2FcD&state=a%26b%3Dc", uri.getString("query"))
    }

    @Test
    fun `a payload carrying no address relays nothing`() {
        assertNull(callbackUriJson("""{"id":"7"}"""))
    }

    @Test
    fun `an address that is not an object relays nothing`() {
        // callback.html can only ever build `uri` as an object, so anything else
        // is not the message this relay exists for. Refusing leaves it in the
        // branch that injects nothing, which is the direction an exported entry
        // point has to fail in.
        assertNull(callbackUriJson("""{"id":"7","uri":"vscode://x"}"""))
        assertNull(callbackUriJson("""{"id":"7","uri":9}"""))
        assertNull(callbackUriJson("""{"id":"7","uri":[{"scheme":"vscode"}]}"""))
    }

    @Test
    fun `text that is not a payload yields null rather than throwing`() {
        // Reached from an exported, BROWSABLE filter, so this is ordinary input.
        // A throw here would take the whole intent handler with it.
        for (text in listOf("", "not json", "[1,2,3]", "{", """{"uri":}""")) {
            assertNull(callbackUriJson(text), "accepted: $text")
        }
        assertNull(callbackUriJson(null))
    }

    @Test
    fun `the id the gate reads and the address the page is given come from one payload`() {
        // The pair, driven together. Before this they were two readings of the
        // same bytes by two grammars, and only one of them decided whether the
        // callback was allowed through.
        assertEquals("7", callbackRequestId(payload))
        assertTrue(callbackUriJson(payload)!!.contains("ms-x.y"))
    }
}

/**
 * That the relay does its reading in Kotlin and hands the page a result.
 *
 * [CallbackRelayTest] drives the readers; this pins that the page is not given a
 * second one. Source reading, which is the weaker layer and is used here for the
 * reason `ServerReadinessCallSiteTest` gives: the injection lives inside an
 * Activity method with no seam, and the regression is a call being present at
 * all rather than a value the code computes.
 */
class CallbackRelayInjectionTest {

    private val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt").readText()

    private fun body(declaration: String): String {
        val start = source.indexOf(declaration)
        assertTrue(start >= 0) {
            "`$declaration` is gone from MainActivity.kt, so this test is measuring " +
                "nothing. If it moved or was renamed, point this at the new site rather " +
                "than deleting it."
        }
        val open = source.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < source.length) {
            if (source[i] == '{') depth += 1
            if (source[i] == '}') {
                depth -= 1
                if (depth == 0) return source.substring(open, i + 1)
            }
            i += 1
        }
        throw AssertionError("Could not find the end of `$declaration` in MainActivity.kt")
    }

    /** Comments dropped, so prose about the rule cannot satisfy a search for it. */
    private fun code(text: String): String =
        text.lines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.joinToString("\n")

    private val relay by lazy { code(body("private fun handleExtensionCallback(")) }

    @Test
    fun `the relay is still a relay`() {
        // The control for the three cases below, each of which is satisfied by
        // an empty method. What the page is here to do is store the value and
        // announce it, because the workbench listens for the storage event
        // rather than polling.
        assertTrue(relay.contains("localStorage.setItem")) {
            "the relay no longer writes the callback into the page's storage"
        }
        assertTrue(relay.contains("dispatchEvent")) {
            "the relay no longer announces the write, so the workbench never collects it"
        }
    }

    @Test
    fun `the payload is not decoded a second time inside the page`() {
        assertTrue(!relay.contains("decodeURIComponent")) {
            "getQueryParameter has already undone callback.html's single layer of " +
                "encoding, so a decode here undoes escaping the provider applied. " +
                "uri.query is built with params.toString() and is percent-encoded by " +
                "construction: a `+` inside a base64 code becomes a space, an escaped " +
                "`&` splits the query, and a quote makes the page's own parse throw."
        }
    }

    @Test
    fun `the page is handed a result rather than the payload to parse`() {
        assertTrue(!relay.contains("JSON.parse")) {
            "the page must not parse the callback payload. Parsing it here and in " +
                "callbackRequestId is two readers over one attacker-supplied string, " +
                "and the id the timing gate approves is then not the id the value is " +
                "written under."
        }
    }

    @Test
    fun `the id written under is the id the timing gate matched`() {
        // The other half: reading the parameter again inside the relay would put
        // the second reader back under a different name.
        assertTrue(!relay.contains("callbackRequestId(")) {
            "the relay derives the request id itself again; it must use the one " +
                "receiveCallbackIntent matched against AuthTabWindow"
        }
        val gate = code(body("private fun receiveCallbackIntent("))
        assertTrue(gate.contains("handleExtensionCallback(uri, requestId)")) {
            "the accepted request id is no longer handed to the relay, so the two " +
                "can drift apart again"
        }
    }

    @Test
    fun `the restart explanation is raised at most once per activity`() {
        // The one message in receiveCallbackIntent that was neither bounded nor
        // keyed on a launch this app made. workbenchLoaded is false on every cold
        // start, through the whole of a server start-up, after showServerGaveUp
        // and after recreateWebView, so anything on the device could fire this
        // exported filter in a loop, hold the screen with a long toast telling
        // the user to sign in again, and bring this app to the front each time.
        // The case it exists for, coming back from the browser after the process
        // was killed, is a single arrival into a fresh instance.
        val gate = code(body("private fun receiveCallbackIntent("))
        val guard = gate.indexOf("restartNoticeShown")
        val toast = gate.indexOf("Toast.makeText(")

        assertTrue(guard >= 0) {
            "nothing bounds the restart explanation any more"
        }
        assertTrue(toast >= 0) { "the restart explanation is gone" }
        assertTrue(guard < toast) {
            "the bound must be read before the first message is raised, or every " +
                "arrival raises one again"
        }
        assertEquals(
            2, Regex("restartNoticeShown").findAll(gate).count(),
            "expected the bound to be read and then taken, found: " +
                Regex(".*restartNoticeShown.*").findAll(gate).map { it.value.trim() }.toList(),
        )
    }
}
