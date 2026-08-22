package com.vscodroid

import android.net.Uri
import com.vscodroid.bridge.AUTH_TAB_WINDOW_MILLIS
import com.vscodroid.bridge.AuthTabWindow
import com.vscodroid.bridge.authRequestIdsIn
import com.vscodroid.webview.redactToken
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The gate on the one exported entry point this app has.
 *
 * `AndroidManifest.xml` gives `MainActivity` a VIEW filter for
 * `vscodroid://callback` carrying BROWSABLE, so the sender is any installed app
 * or any page the user taps a link on, and what arrives is written into the
 * workbench's `localStorage` and announced with a synthetic `StorageEvent`. The
 * filter exists precisely so an outside app (the browser finishing a sign-in)
 * can reach in, so the check cannot be about who sent it. What is left is that
 * it is shaped like the one message this relay is for.
 */
class ExtensionCallbackTest {

    @Test
    fun `the callback relay is recognised`() {
        assertTrue(isExtensionCallback("vscodroid", "callback"))
    }

    @Test
    fun `neither half alone is enough`() {
        // The mutation is `||` for `&&`, or dropping one comparison as
        // redundant. Either widens an exported, browsable entry point to
        // anything under the app's own scheme, or to any scheme at all pointed
        // at the word "callback".
        assertFalse(isExtensionCallback("vscodroid", "anything-else"))
        assertFalse(isExtensionCallback("https", "callback"))
    }

    @Test
    fun `a launcher intent carrying no URI is not a callback`() {
        // The routine case. MainActivity is singleTask, so every relaunch
        // arrives through the same two methods with null data.
        assertFalse(isExtensionCallback(null, null))
        assertFalse(isExtensionCallback("vscodroid", null))
        assertFalse(isExtensionCallback(null, "callback"))
    }

    @Test
    fun `the scheme and host are matched exactly`() {
        // Android lower-cases the scheme and host of an intent URI before it is
        // matched against a filter, so a comparison that stayed
        // case-insensitive would be granting nothing and hiding the fact that
        // the platform already normalised. Stated so the exact match is not
        // "loosened" later by someone assuming it has to be.
        assertFalse(isExtensionCallback("VSCodroid", "callback"))
        assertFalse(isExtensionCallback("vscodroid", "Callback"))
        assertFalse(isExtensionCallback("vscodroid ", "callback"))
    }
}

/**
 * That the window actually carries the value between the two halves, and that it
 * carries it per request rather than in bulk.
 *
 * `authCallbackIsExpected` is tested on numbers and the call sites are tested on
 * source text, so nothing in either touches the object that carries the reading
 * from the browser launch to the relay. An `AuthTabWindow` that returned a
 * constant, or that answered for an id it had never been given, would leave both
 * sets green and either break every sign-in on the device or accept a callback
 * nobody asked for.
 *
 * Plain JVM: the object holds Longs its callers supply and reads no Android API.
 *
 * It is also a process-wide singleton shared with every other test in this JVM,
 * so each case arms ids of its own and hands them back afterwards.
 */
class AuthTabWindowTest {

    /** Distinctive enough that no other test in this JVM could be arming them. */
    private val ids = listOf("900001", "900002", "900003")

    @AfterEach
    fun handBack() = AuthTabWindow.disarm(ids)

    @Test
    fun `the reading handed in is the reading handed back`() {
        AuthTabWindow.arm(listOf("900001"), 123_456L)

        assertEquals(123_456L, AuthTabWindow.armedAt("900001"))
    }

    @Test
    fun `an id nobody launched is not armed by a launch of another id`() {
        // The narrowing itself. One timestamp for the whole process answered yes
        // here, which is how opening a documentation link came to widen the
        // window an unsolicited callback is accepted in.
        AuthTabWindow.arm(listOf("900001"), 123_456L)

        assertNull(AuthTabWindow.armedAt("900002"))
    }

    @Test
    fun `a second launch arms its own id and leaves the first alone`() {
        AuthTabWindow.arm(listOf("900001"), 100L)
        AuthTabWindow.arm(listOf("900002"), 200L)

        assertEquals(100L, AuthTabWindow.armedAt("900001"), "a later launch overwrote an earlier one")
        assertEquals(200L, AuthTabWindow.armedAt("900002"))
    }

    @Test
    fun `a repeated id is moved forward to the launch that is actually in flight`() {
        // Ids repeat, which is what makes keeping the first reading the wrong
        // answer rather than the cautious one. The workbench counts them from one
        // out of a class static that is re-initialised with the page, so a
        // reload, a folder switch or a renderer recreation hands the same id to
        // the next sign-in. Holding the older reading meant a launch from a page
        // that no longer exists decided the window for the sign-in in flight: the
        // callback came back in seconds and was refused, with a message telling
        // the user their sign-in had taken too long.
        AuthTabWindow.arm(listOf("900001"), 1_000L)

        val armed = AuthTabWindow.arm(listOf("900001"), 9_000L)

        assertEquals(
            9_000L, AuthTabWindow.armedAt("900001"),
            "the window is still being judged from a launch the user has already left behind",
        )
        assertEquals(
            listOf("900001"), armed,
            "the ids a launch records are the ids its rollback has to take back",
        )
    }

    @Test
    fun `a sign-in started after a page reload is judged from its own launch`() {
        // The same fact as the case above, carried through to the answer the
        // relay actually gives, because that is where it was costing something.
        // A sign-in in the morning arms 900001. Hours later the page reloads --
        // after a folder switch, a renderer recreation or the resume path -- the
        // workbench counter starts again, and the next sign-in is handed 900001
        // too. Its callback comes back thirty seconds later.
        AuthTabWindow.arm(listOf("900001"), 1_000L)

        AuthTabWindow.arm(listOf("900001"), 1_201_000L)

        val armedAt = AuthTabWindow.armedAt("900001")
        assertNotNull(armedAt, "the launch just made was not recorded at all")
        assertTrue(
            authCallbackIsExpected(armedAt!!, 1_231_000L, AUTH_TAB_WINDOW_MILLIS),
            "a callback thirty seconds after its own launch was refused, because the id was " +
                "still carrying the reading of a sign-in from two hours before. The user is " +
                "told the sign-in took too long, and every retry in that page repeats it.",
        )
    }

    @Test
    fun `a launch reports the ids it armed, and disarming takes back only those`() {
        AuthTabWindow.arm(listOf("900001"), 100L)

        val added = AuthTabWindow.arm(listOf("900002", "900003"), 200L)
        assertEquals(listOf("900002", "900003"), added)

        AuthTabWindow.disarm(added)
        assertNull(AuthTabWindow.armedAt("900002"))
        assertNull(AuthTabWindow.armedAt("900003"))
        assertEquals(
            100L, AuthTabWindow.armedAt("900001"),
            "a failed launch took back a request it had nothing to do with",
        )
    }

    @Test
    fun `every launch still held is reported, which is what the reload asks`() {
        // The resume path asks whether ANY sign-in is still able to come back, and
        // it can only ask that of the readings. An empty answer here would let the
        // forced reload discard the page a callback was about to land in.
        AuthTabWindow.arm(listOf("900001"), 100L)
        AuthTabWindow.arm(listOf("900002"), 200L)

        val readings = AuthTabWindow.armedReadings()

        assertTrue(readings.contains(100L), "a launch is held but not reported: $readings")
        assertTrue(readings.contains(200L), "a launch is held but not reported: $readings")
    }

    @Test
    fun `the record does not grow for the life of the process`() {
        // Entries outlive their own window on purpose, so that a late callback can
        // be told apart from an invented one. Without a bound that is a leak in a
        // process that stays alive for days.
        val many = (1..200).map { "9100$it" }
        try {
            AuthTabWindow.arm(many, 500L)

            assertNull(
                AuthTabWindow.armedAt(many.first()),
                "the oldest entry survived 200 launches; nothing is bounding this",
            )
            assertNotNull(
                AuthTabWindow.armedAt(many.last()),
                "the newest entry was evicted, which would refuse the sign-in just started",
            )
        } finally {
            AuthTabWindow.disarm(many)
        }
    }
}

/**
 * Which browser launches arm anything at all.
 *
 * This is the half that decides whether opening a link is treated as starting a
 * sign-in. It used to be all of them: one timestamp, written by every
 * `openExternalUrl` call, so following a documentation link opened a ten-minute
 * window in which an unsolicited `vscodroid://callback` from anything on the
 * device would be answered.
 *
 * What replaces it is not a guess about the URL. `callback.html` refuses to run
 * without a `vscode-reqid` and relays that same parameter on as the id, so a
 * callback can only exist for a request whose id left this app inside the address
 * it opened. The addresses below are the real shapes that carries in.
 */
class AuthRequestIdTest {

    @Test
    fun `the callback address names its own request`() {
        val ids = authRequestIdsIn(
            "http://127.0.0.1:13337/callback?vscode-reqid=3&vscode-scheme=vscodroid" +
                "&vscode-authority=vscode.github-authentication&vscode-path=/did-authenticate"
        )

        assertEquals(listOf("3"), ids)
    }

    @Test
    fun `an authorisation address carrying the callback in state names it too`() {
        // The shape the bundled GitHub provider actually sends: the callback
        // address is encoded into `state`, and then the whole parameter list is
        // encoded again on its way into the query. `=` becomes `%3D` and then
        // `%253D`, which is why matching the plain spelling alone finds nothing
        // and refuses every sign-in.
        val ids = authRequestIdsIn(
            "https://github.com/login/oauth/authorize?client_id=abc" +
                "&redirect_uri=https%3A%2F%2Fvscode.dev%2Fredirect&scope=repo" +
                "&state=http%253A%252F%252F127.0.0.1%253A13337%252Fcallback" +
                "%253Fvscode-reqid%253D7%2526vscode-scheme%253Dvscodroid" +
                "&code_challenge_method=S256"
        )

        assertEquals(listOf("7"), ids)
    }

    @Test
    fun `a singly encoded callback address is read as well`() {
        assertEquals(
            listOf("11"),
            authRequestIdsIn("https://login.example.com/authorize?redirect_uri=" +
                "http%3A%2F%2F127.0.0.1%3A13337%2Fcallback%3Fvscode-reqid%3D11"),
        )
    }

    @Test
    fun `a link that is not a sign-in arms nothing`() {
        // The whole point. Every one of these used to open the callback window as
        // wide as a sign-in did.
        for (link in listOf(
            "https://code.visualstudio.com/docs/editor/extension-marketplace",
            "http://192.168.1.50:5173/",
            "https://example.com/?callback=1&reqid=4",
            "https://example.com/vscode-reqid",
            "https://example.com/?vscode-reqid=",
        )) {
            assertEquals(emptyList<String>(), authRequestIdsIn(link), "armed by: $link")
        }
    }

    @Test
    fun `an address naming the same request twice arms it once`() {
        val ids = authRequestIdsIn(
            "https://login.example.com/authorize?redirect_uri=" +
                "http%3A%2F%2F127.0.0.1%3A13337%2Fcallback%3Fvscode-reqid%3D5" +
                "&state=http%3A%2F%2F127.0.0.1%3A13337%2Fcallback%3Fvscode-reqid%3D5"
        )

        assertEquals(listOf("5"), ids)
    }
}

/**
 * Which request a callback payload claims to be for.
 *
 * The id is the whole of the match: it is compared against the launches this app
 * made, and a value read out of the wrong place would either refuse every real
 * sign-in or answer for one nobody started. The payload is built by
 * `callback.html` as `JSON.stringify({ id, uri })`, and anything on the device
 * can fire the intent that carries it, so the reader has to be total.
 */
class CallbackRequestIdTest {

    @Test
    fun `the id in a relay payload is read`() {
        val payload = """{"id":"7","uri":{"scheme":"vscodroid","authority":"vscode.github-authentication"}}"""

        assertEquals("7", callbackRequestId(payload))
    }

    @Test
    fun `a number in the rest of the payload is not mistaken for the id`() {
        // The reason this parses instead of searching the text. The `uri` half
        // carries whatever the provider sent back, and a reader looking for the
        // first number in the string would take a code or a state value and
        // compare that against the launches instead.
        val payload = """{"id":"7","uri":{"query":"code=12&state=99","path":"/did-authenticate"}}"""

        assertEquals("7", callbackRequestId(payload))
    }

    @Test
    fun `a payload with no id is refused rather than guessed at`() {
        assertNull(callbackRequestId("""{"uri":{"scheme":"vscodroid"}}"""))
        assertNull(callbackRequestId("""{"id":""}"""))
    }

    @Test
    fun `text that is not a payload yields null rather than throwing`() {
        // Reached from an exported, BROWSABLE filter, so this is ordinary input
        // rather than a corner case. A throw here would take the whole intent
        // handler with it.
        for (text in listOf("", "not json", "[1,2,3]", "{", """{"id":}""")) {
            assertNull(callbackRequestId(text), "accepted: $text")
        }
        assertNull(callbackRequestId(null))
    }
}

/**
 * That the timing gate is reached, and that anything opening a browser arms it.
 *
 * `authCallbackIsExpected` can be exactly right and never called, and the two
 * halves fail in opposite directions: a relay that never asks accepts
 * everything, and a browser launch that never records leaves the gate shut on a
 * real sign-in. Neither shows up in a test of the function itself.
 *
 * Source reading, which is weaker than the rest of this suite, and here for the
 * reason `ServerReadinessCallSiteTest` gives: the decision lives inside an
 * Activity method with no seam, and the mutation is not a value the code
 * computes but whether a call is present at all.
 *
 * What it does not catch: a browser opened from a third file, or the gate
 * reached through some other spelling. It catches removing either half of what
 * is here, which is the regression that would actually be made.
 */
class AuthCallbackCallSiteTest {

    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")
    private val bridge = File("src/main/kotlin/com/vscodroid/bridge/AndroidBridge.kt")

    /** Comments dropped: all of these names are discussed in prose above them. */
    private fun code(file: File): List<String> =
        file.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    private companion object {
        /** What [skeleton] leaves behind where a string literal stood. */
        const val LITERAL = "STR"

        /**
         * A string resource read with no arguments, which is as fixed as a
         * literal: nothing from the caller can reach what it resolves to.
         * Deliberately refuses the form that takes format arguments, since that
         * is exactly how a payload would get in.
         */
        val RESOURCE = Regex("""getString\(R\.string\.\w+\)""")

        const val TOAST = "Toast.makeText("
    }

    /**
     * [source] with every string literal collapsed to [LITERAL].
     *
     * What is left is the shape of the expression, which is the property worth
     * measuring: a message built from literals alone reads as `STR + STR`, and
     * anything else reaching it -- a variable, a call, a template hole -- leaves
     * a name or a marker behind. Searching the text for one composition operator
     * measures the operator rather than the property, and misses the other.
     *
     * A literal that interpolates is collapsed to `STR$`, because a hole inside a
     * literal takes its value from outside it exactly as `+` does. An escaped
     * character is skipped whole: `\$` is a dollar sign, not a hole, and `\"`
     * does not end the literal.
     */
    private fun skeleton(source: String): String {
        val out = StringBuilder()
        val literal = StringBuilder()
        var inLiteral = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                inLiteral && c == '\\' -> i++
                c == '"' -> {
                    if (inLiteral) {
                        out.append(if (literal.contains('$')) "$LITERAL\$" else LITERAL)
                        literal.clear()
                    }
                    inLiteral = !inLiteral
                }
                inLiteral -> literal.append(c)
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /**
     * The message argument of every [TOAST] call in a [skeleton]ised source.
     *
     * Balanced rather than line-based: the calls here are written over four lines
     * and the message is the one argument that is allowed to vary.
     */
    private fun toastMessages(skeletonised: String): List<String> {
        val messages = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = skeletonised.indexOf(TOAST, from)
            if (start < 0) return messages
            val args = mutableListOf<String>()
            var i = start + TOAST.length
            var argStart = i
            var depth = 1
            while (i < skeletonised.length && depth > 0) {
                when (skeletonised[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) args += skeletonised.substring(argStart, i)
                    }
                    ',' -> if (depth == 1) {
                        args += skeletonised.substring(argStart, i)
                        argStart = i + 1
                    }
                }
                i++
            }
            messages += args.getOrElse(1) { "" }
            from = i
        }
    }

    @Test
    fun `the relay asks whether a sign-in was in flight`() {
        check(mainActivity.isFile) {
            "MainActivity.kt not found at ${mainActivity.absolutePath}; this test " +
                "would otherwise pass by looking at nothing"
        }

        // The declaration is in this same file and its first line reads
        // `fun authCallbackIsExpected(`, so a search for the name alone is
        // satisfied by the function existing. Measured: deleting the entire
        // gate from receiveCallbackIntent left all 630 tests green.
        val calls = code(mainActivity).filterNot { it.contains("fun authCallbackIsExpected") }
            .count { it.contains("authCallbackIsExpected(") }

        assertTrue(
            calls >= 1,
            "the vscodroid://callback relay must refuse a callback no sign-in was " +
                "waiting for; found $calls call site(s), and without one the " +
                "exported entry point takes a value from anything on the device " +
                "at any moment",
        )
    }

    @Test
    fun `the restart message is decided before the timing gate, not after`() {
        // A regression that no test caught, which is why this one exists.
        // Putting the timing gate first is the obvious ordering and it is
        // wrong: the case the restart message exists for is arriving through
        // onCreate after the process was killed with the browser in front, and a
        // fresh process has openedAt == 0, so the gate returns before the
        // message is ever reached. The user who came back from signing in gets
        // silence, and every test stays green because neither branch is a value
        // any of them can see.
        check(mainActivity.isFile) { "MainActivity.kt not found" }

        val lines = code(mainActivity)
        val restart = lines.indexOfFirst { it.contains("if (!workbenchLoaded)") }
        val gate = lines.indexOfFirst {
            it.contains("authCallbackIsExpected(") && !it.contains("fun authCallbackIsExpected")
        }

        assertTrue(restart >= 0, "the no-workbench branch is gone; the restart message with it")
        assertTrue(gate >= 0, "the timing gate is gone")
        assertTrue(
            restart < gate,
            "the no-workbench branch must come first: it explains rather than " +
                "injects, and gating it silences the one case it was written for",
        )
    }

    @Test
    fun `every browser launch arms the window it will be judged against`() {
        // The control for the test above, and the half more likely to rot: a
        // second launch site added later without recording would leave that
        // sign-in's return refused, and the symptom is a sign-in that quietly
        // never completes rather than anything that fails loudly.
        //
        // Counted inside openExternalUrl itself rather than over the whole file,
        // and counting both ways out of it: launchUrl for the Custom Tabs half
        // and startActivity for the ACTION_VIEW half. A whole-file count of the
        // first form alone was green while the second launch site armed nothing,
        // because startActivity also appears outside this method for launches
        // that are not browsers at all.
        check(bridge.isFile) { "AndroidBridge.kt not found at ${bridge.absolutePath}" }

        val lines = code(bridge)
        val method = lines.dropWhile { !it.contains("fun openExternalUrl") }
            .takeWhile { !it.trimStart().startsWith("}") }
        check(method.any { it.contains("launchUrl(") }) { "no browser launch found in openExternalUrl" }

        val launches = method.count { it.contains("launchUrl(") || it.contains("startActivity(") }
        val arms = method.count { it.contains("AuthTabWindow.arm(") }

        assertEquals(
            launches, arms,
            "each browser launch must record that it happened, or the callback it " +
                "returns with is refused",
        )
    }

    @Test
    fun `the launch arms the ids the address carries, not the fact that it launched`() {
        // The narrowing, at the bridge's arming site. Passing anything other
        // than the ids read out of the address puts the old behaviour back:
        // every browser launch opening the callback window, whether or not a
        // sign-in was involved.
        //
        // Not the only arming site any more. `VSCodroidWebViewClient` arms the
        // link-navigation hand-off as well, and this reads AndroidBridge.kt
        // only; `ExternalUrlHandoffTest` holds that site to the same rule from
        // the other end, by driving it with an address whose only number is not
        // a request id.
        check(bridge.isFile) { "AndroidBridge.kt not found" }

        val arming = code(bridge).filter { it.contains("AuthTabWindow.arm(") }

        assertEquals(
            1, arming.size,
            "expected exactly one arming site in AndroidBridge.kt, found: $arming",
        )
        assertTrue(
            arming.single().contains("authRequestIdsIn("),
            "the arming must be keyed on the request ids in the address; found: ${arming.single()}",
        )
    }

    @Test
    fun `the relay matches a callback against the launch that could have produced it`() {
        // Without this the timing gate is asked about a launch chosen by nothing:
        // the most recent one, whatever it was for. Deleting the lookup and
        // handing the gate any reading at all leaves every other test here green.
        check(mainActivity.isFile) { "MainActivity.kt not found" }

        val lookups = code(mainActivity).count { it.contains("AuthTabWindow.armedAt(") }

        assertTrue(
            lookups >= 1,
            "the relay must look the callback's own request id up against the launches " +
                "this app made; found $lookups",
        )
    }

    @Test
    fun `the message for a callback that arrived too late carries nothing from it`() {
        // The message exists because a sign-in that took longer than the window
        // used to die in silence. Reaching it needs an id this app really did
        // launch a browser for, which is a bound rather than a gate: the id is an
        // integer the workbench counts from one and the filter is exported, so an
        // outside caller can reach this once per launch the user made. What it
        // must never do is put any part of the payload on the screen, since that
        // payload is attacker-shaped by construction.
        //
        // Measured on the expression, not on the line. The predecessor of this
        // assertion looked for a `$` beside a quote, which is one of the two ways
        // to build a message out of a value: `"too long: " + payload` carries no
        // `$` at all, and the mutation that inlines the intent's own `data`
        // parameter that way left every case here green.
        check(mainActivity.isFile) { "MainActivity.kt not found" }

        val body = code(mainActivity)
            .dropWhile { !it.contains("private fun receiveCallbackIntent(") }
            .drop(1)
            .takeWhile { it != "    }" }
        check(body.isNotEmpty()) { "receiveCallbackIntent not found; this test is measuring nothing" }

        val toasts = body.filter { it.contains("Toast.makeText(") }
        assertEquals(
            2, toasts.size,
            "expected the restart message and the expiry message, found ${toasts.size}: $toasts",
        )

        val messages = toastMessages(skeleton(body.joinToString("\n")))
        assertEquals(
            2, messages.size,
            "expected to read two message arguments, found ${messages.size}: $messages",
        )
        messages.forEach { message ->
            // A resource lookup counts as fixed text, and only the argumentless
            // form does. Both messages here are `getString(R.string.name)` now
            // that they are translatable, and what that resolves to is decided by
            // strings.xml and the device's locale, never by the intent. The
            // moment one takes a second argument it stops being fixed: RESOURCE
            // does not match `getString(R.string.name, x)`, so the `x` is left
            // behind and this fails, which is the whole point of reading the
            // shape of the expression rather than searching for one operator.
            val beyondLiterals = message
                .replace(RESOURCE, "")
                .replace(LITERAL, "")
                .filterNot { it.isWhitespace() || it == '+' }
            assertEquals(
                "", beyondLiterals,
                "a message shown here must be fixed text, and this one is assembled from " +
                    "something else: `$message` ($LITERAL stands for a string literal, and a " +
                    "trailing \$ for a template hole inside one; an argumentless " +
                    "getString(R.string.…) is fixed text too). Whatever reaches it can be " +
                    "chosen by whoever fired the intent, and this app is the one the user " +
                    "trusts to be telling them about their sign-in.",
            )
        }
    }

    @Test
    fun `the late message is given once per launch, not on every arrival`() {
        // What keeps that message from being something an outside caller can
        // operate. Records are kept past their own window on purpose, so once the
        // user has signed in, an id this app launched stays known for the life of
        // the process, and the id is a small integer through an exported
        // BROWSABLE filter. Without the record being taken back as the message
        // goes up, anything on the device can raise "Sign-in took too long" in a
        // loop, branded as this app and nagging the user to re-authenticate on
        // someone else's cue.
        //
        // Asserted on the source because the branch lives in an Activity method,
        // which cannot be built in a plain JVM test. Position is the property:
        // taking the record back before the timing gate would refuse the sign-in
        // the user is actually completing, and taking it back after the gate has
        // passed would consume a callback that is about to be relayed.
        check(mainActivity.isFile) { "MainActivity.kt not found" }

        val body = code(mainActivity)
            .dropWhile { !it.contains("private fun receiveCallbackIntent(") }
            .drop(1)
            .takeWhile { it != "    }" }
        check(body.isNotEmpty()) { "receiveCallbackIntent not found; this test is measuring nothing" }

        val gate = body.indexOfFirst { it.contains("authCallbackIsExpected(") }
        val relay = body.indexOfFirst { it.contains("handleExtensionCallback(") }
        val taken = body.indexOfFirst { it.contains("AuthTabWindow.disarm(") }

        assertTrue(gate >= 0, "the timing gate is gone")
        assertTrue(relay >= 0, "the relay itself is gone")
        assertTrue(
            taken > gate,
            "the launch record must be taken back inside the branch that shows the expiry " +
                "message; found the timing gate at line $gate and the take-back at $taken",
        )
        assertTrue(
            taken < relay,
            "the take-back must sit in the refusing branch, not on the accepting one: a " +
                "callback that is being relayed is still being collected by the workbench, " +
                "and the forced reload asks this same record whether to stay out of the way",
        )
    }

    @Test
    fun `the forced reload asks whether a sign-in is still able to come back`() {
        // The race the sign-in branch exists for: returning after five minutes is
        // the ordinary shape of a sign-in that needed a second factor, and the
        // reload discards the page the callback has to land in. Dropping the
        // argument leaves resumeAction correct and its first branch unreachable.
        check(mainActivity.isFile) { "MainActivity.kt not found" }

        val decisions = code(mainActivity).filter { it.contains("resumeAction(") }
            .filterNot { it.contains("fun resumeAction") }

        assertEquals(1, decisions.size, "expected one resume decision, found: $decisions")
        assertTrue(
            decisions.single().contains("signInIsPending()"),
            "the resume decision must be told whether a sign-in is in flight; found: " +
                decisions.single(),
        )
    }
}

/**
 * That the workbench URL and the redactor agree about where the token lives.
 *
 * The connection token is the whole of the server's authentication: it is
 * required on every route but `/version`, `/delay-shutdown` and `/callback`, so
 * anything holding it can read what the server can read and open a terminal.
 * `Logger.i` is not gated on a debuggable build, so a token printed here reaches
 * a release build's logcat.
 *
 * The coupling is the thing worth pinning. `redactToken` keys on the literal
 * `tkn=`, and nothing but this test connects that to the parameter the URL is
 * built with, rename the parameter and the redactor matches nothing while every
 * log statement still reads as redacted.
 */
class WorkbenchUrlTest {

    /** Distinctive enough that a match cannot be a coincidence. */
    private val token = "tkn-8f31c07a-do-not-log-me"

    @BeforeEach
    fun setUp() {
        // The token is a UUID in production, so identity encoding is faithful.
        // Uri is a stub under the unit-test android.jar and throws otherwise.
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers { firstArg<String>() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `the token the workbench needs is the token the redactor hides`() {
        val url = workbenchUrl(13337, "/data/data/com.vscodroid/files/home/projects", token)

        assertTrue(
            url.contains(token),
            "the navigation URL must carry the token or the server answers Forbidden: $url",
        )
        assertFalse(
            redactToken(url).contains(token),
            "the redactor does not recognise where the token is in this URL, so every " +
                "log statement that trusts it prints the credential: ${redactToken(url)}",
        )
    }

    @Test
    fun `a server that has not written its token yet yields a bare URL`() {
        // Control, and a real case: the token file does not exist until the
        // server has started. An empty `tkn=` would be indistinguishable from a
        // wrong one, and there would be nothing for the redactor to find either.
        for (missing in listOf(null, "")) {
            val url = workbenchUrl(13337, "/projects", missing)
            assertFalse(url.contains("tkn="), "an absent token produced a token parameter: $url")
            assertTrue(url.endsWith("/?folder=/projects"), url)
        }
    }

    @Test
    fun `redaction holds wherever in a message the URL sits`() {
        // The property [NavigationTokenLoggingTest] rests on. That test requires
        // every mention of the navigation URL inside a log statement to be
        // wrapped in redactToken, and the requirement is only worth having if a
        // wrapped mention is safe in whatever shape the surrounding message
        // takes. A redactor anchored at the start of the string, or one
        // replacing the first match and stopping, would leave that test correct
        // and still ship the token out of a message built by concatenation or
        // one that names the URL twice.
        val url = workbenchUrl(13337, "/projects", token)

        val messages = mapOf(
            "interpolated" to "Loading VS Code at $url",
            "concatenated" to "Loading VS Code at " + url + " now",
            "named twice" to "$url then $url",
            "assembled by a builder" to StringBuilder("at ").append(url).append(" ok").toString(),
            "carrying a further parameter" to "$url&reload=1",
            "quoted" to "\"$url\"",
        )

        messages.forEach { (shape, message) ->
            val safe = redactToken(message)
            assertFalse(
                safe.contains(token),
                "the token survived redaction of a message that is $shape: $safe",
            )
            assertTrue(
                safe.contains("tkn=<redacted>"),
                "nothing at all was redacted in a message that is $shape: $safe",
            )
        }
    }
}

/**
 * That nothing in `MainActivity` puts a token-bearing value into logcat.
 *
 * [WorkbenchUrlTest] pins that the redactor understands the URL, and that a
 * redacted message stays safe however it was assembled; this pins that the
 * redactor is reached at all, and that there is only one URL for it to be reached
 * on. The defect is not a leak but the shape of one: the method used to build two
 * strings, the URL it loaded and a token-free twin it logged, and what kept the
 * token out of logcat was a person keeping them apart. Collapsing them is what a
 * merge does, and it is also the deliberate edit of anyone who wants the real
 * navigation URL in the log.
 *
 * What is dangerous is a *value*, not a *spelling*: something holding the
 * connection token reaching a `Logger` argument. A check written against one
 * spelling is a check on that spelling wearing the costume of a check on the
 * leak, `"at $url"` fails it, and `"at " + url` walks straight through while the
 * token goes to a release build's logcat, readable by anything holding READ_LOGS.
 *
 * So [TokenTaint] reads the file the way taint reads it. A `val`/`var`
 * declaration seeded from `workbenchUrl(...)`, `getConnectionToken()` or a
 * WebView's `url` is tainted; anything declared from a tainted name is tainted
 * too; and a `Logger` statement is an offender when a tainted name survives
 * removing every `redactToken(...)` from it. Statements are read whole, across
 * the lines a formatter wraps them onto, and string prose is dropped so the word
 * "token" in a message is not mistaken for the variable. Interpolation,
 * concatenation and an intermediate local all read the same to it. The reader is
 * driven against fixed snippets in the cases below, so it is measured in both
 * directions rather than trusted in either.
 *
 * What still passes. The first of these is pinned as a case below, so the claim
 * is measured rather than promised, and the rest are the same shape as it:
 *
 *  - a value laundered through something that is not a declaration, appended to
 *    a `StringBuilder`, put in a collection, or assigned to a `var` some lines
 *    after it was declared;
 *  - a declaration split across lines, with `val x =` on one and the value on the
 *    next;
 *  - a token arriving by a route none of the three seeds name;
 *  - a value handed to a helper that logs it somewhere else. The reverse of that
 *    one is a false accusation rather than a miss: only `redactToken(...)` counts
 *    as sanitising, so any other wrapper is reported and has to be argued with;
 *  - names are tracked across the whole file rather than per scope, so an
 *    unrelated local called `url` or `token` is held to the same rule. Every
 *    binding of either name in this file today holds the same tokened URL or the
 *    bridge's session token, so here that is the answer wanted; elsewhere it
 *    would be a nuisance.
 *
 * Source reading, and the weaker layer for the usual reason: the statement is
 * inside an Activity method, and a plain JVM test can build no Activity. The
 * webview layer needs none of this, `ConnectionTokenLoggingTest` drives its log
 * statements for real and reads what came out, which catches every spelling at
 * once.
 */
class NavigationTokenLoggingTest {

    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    private fun source(): List<String> {
        check(mainActivity.isFile) {
            "MainActivity.kt not found at ${mainActivity.absolutePath}, this test " +
                "would otherwise pass by looking at nothing"
        }
        return mainActivity.readLines()
    }

    @Test
    fun `no log statement prints a token-bearing value unredacted`() {
        val offenders = TokenTaint.leaks(source()).map { "MainActivity.kt:$it" }

        assertEquals(
            emptyList<String>(), offenders,
            "the URL the WebView loads carries the connection token, and only " +
                "Logger.d is gated on a debuggable build, so this reaches a release " +
                "build's logcat. Print it through redactToken().",
        )
    }

    @Test
    fun `the navigation URL still reaches the log, redacted`() {
        // Control, and it has to be a control on the *statement*. Asserting that
        // some line somewhere calls `redactToken(url)` is satisfied by a spare
        // `val safe = redactToken(url)` that nothing logs, which is exactly the
        // company a raw log statement keeps. Deleting the log statement, renaming
        // the local, or logging the URL by some other route all leave this empty.
        val redacted = TokenTaint.redactedLogs(source())

        assertTrue(
            redacted.isNotEmpty(),
            "no log statement in MainActivity prints a token-bearing value through " +
                "redactToken. Either the navigation log went, or it stopped going " +
                "through the redactor, or the seeds in TokenTaint no longer recognise " +
                "where the token enters the file, and in every one of those cases the " +
                "test above is passing by looking at nothing",
        )
    }

    @Test
    fun `the workbench URL is assembled in exactly one place`() {
        // The affordance, not the symptom. Two expressions for the same URL is
        // what made the redaction a matter of discipline; one cannot drift from
        // itself. Read off the raw lines: the literal host is string prose, which
        // is the one thing TokenTaint's reader throws away.
        val builders = source().withIndex()
            .filterNot { (_, l) ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .filter { (_, l) -> l.contains("http://127.0.0.1:\$port") }
            .map { (i, l) -> "MainActivity.kt:${i + 1}: ${l.trim()}" }

        assertEquals(
            1, builders.size,
            "the navigation URL must be built once, so that the string logged and the " +
                "string loaded cannot differ. Found:\n" + builders.joinToString("\n"),
        )
    }

    // --- The reader itself, driven against fixed snippets. ------------------
    //
    // Everything above points TokenTaint at one file, where the only measurable
    // outcome is "found nothing". A reader that always finds nothing passes all
    // of it. These give it sources whose answer is known.

    /** A stand-in for `navigateToFolder`, with the log statement swapped in. */
    private fun navigateSource(vararg body: String): List<String> =
        listOf(
            "    private fun navigateToFolder(port: Int, folderPath: String) {",
            "        val token = nodeService?.getConnectionToken()",
            "        val url = workbenchUrl(port, folderPath, token)",
        ) + body + listOf(
            "        wv.loadUrl(url)",
            "    }",
        )

    private val redactedLog =
        """        Logger.i(tag, "Loading VS Code at ${'$'}{redactToken(url)}")"""

    @Test
    fun `the code as it stands reads clean`() {
        val clean = navigateSource(redactedLog)

        assertEquals(emptyList<String>(), TokenTaint.leaks(clean))
        assertTrue(
            TokenTaint.redactedLogs(clean).isNotEmpty(),
            "the reader did not recognise the redacted log statement it is built around",
        )
    }

    @Test
    fun `an interpolated URL is a leak`() {
        assertLeaks(
            navigateSource("""        Logger.i(tag, "Loading VS Code at ${'$'}url")"""),
        )
    }

    @Test
    fun `a concatenated URL is a leak, even beside a redacted copy of it`() {
        // The spelling that defeats a check written against interpolation, and
        // the one worth stating in full because it is what the honest version of
        // the mistake looks like: someone keeps the redacted string, and prints
        // the real one next to it.
        val defeat = navigateSource(
            """        val safeUrl = redactToken(url)""",
            """        Logger.i(tag, "Loading VS Code at " + url + " (" + safeUrl + ")")""",
        )

        assertLeaks(defeat)
        assertEquals(
            emptyList<String>(), TokenTaint.redactedLogs(defeat),
            "a `val safeUrl = redactToken(url)` that nothing logs must not answer for " +
                "the log statement; that is how a control gets satisfied by a line it " +
                "has nothing to do with",
        )
    }

    @Test
    fun `an intermediate local carries the taint with it`() {
        assertLeaks(
            navigateSource(
                """        val shown = url""",
                """        Logger.i(tag, shown)""",
            ),
        )
    }

    @Test
    fun `a statement the formatter wrapped is read whole`() {
        // Both directions, because line-at-a-time reading gets them both wrong:
        // it misses the leak, since the line holding the URL has no `Logger.` on
        // it, and, for a reader patched to look at neighbouring lines instead of
        // whole statements, it would accuse the correct one for the same reason.
        val wrappedRaw = navigateSource(
            """        Logger.i(""",
            """            tag,""",
            """            "Loading VS Code at " +""",
            """                url,""",
            """        )""",
        )
        val wrappedRedacted = navigateSource(
            """        Logger.i(""",
            """            tag,""",
            """            "Loading VS Code at ${'$'}{redactToken(url)}",""",
            """        )""",
        )

        assertLeaks(wrappedRaw)
        assertEquals(emptyList<String>(), TokenTaint.leaks(wrappedRedacted))
        assertTrue(
            TokenTaint.redactedLogs(wrappedRedacted).isNotEmpty(),
            "a redacted log statement stopped counting as one once it was wrapped",
        )
    }

    @Test
    fun `the word token in a message is not the variable token`() {
        // The false accusation this would make if it matched on text rather than
        // on code positions. MainActivity really does log "No connection token;
        // the workbench will be refused." a few lines above the statement under
        // guard, so getting this wrong makes the whole guard unrunnable and the
        // next person deletes it.
        val prose = navigateSource(
            """        Logger.e(tag, "No connection token; the workbench will be refused.")""",
            redactedLog,
        )

        assertEquals(emptyList<String>(), TokenTaint.leaks(prose))
    }

    @Test
    fun `a value laundered through a builder still passes, a stated limit`() {
        // Pinned rather than described, so the docstring's list of what gets
        // through is measured. Taint is followed through declarations only, and a
        // `StringBuilder` is fed by a call. The redacted statement is kept beside
        // it deliberately: with it gone the control catches this anyway, which
        // would flatter the reader into looking like it saw the leak.
        //
        // If this case ever fails because the reader grew to follow a builder,
        // that is an improvement: delete the case and the bullet in the docstring
        // that promises it.
        val laundered = navigateSource(
            redactedLog,
            """        val message = StringBuilder("also at ")""",
            """        message.append(url)""",
            """        Logger.w(tag, message.toString())""",
        )

        assertEquals(emptyList<String>(), TokenTaint.leaks(laundered))
        assertTrue(
            TokenTaint.redactedLogs(laundered).isNotEmpty(),
            "the control has to be satisfied here, or this case is not the gap it claims",
        )
    }

    private fun assertLeaks(source: List<String>) {
        assertTrue(
            TokenTaint.leaks(source).isNotEmpty(),
            "a token-bearing value reaches Logger here and the reader did not see it:\n" +
                source.joinToString("\n"),
        )
    }
}

/**
 * Which `Logger` statements in a Kotlin source hand on a value carrying the
 * connection token.
 *
 * Kept apart from the test that uses it because it is the part with behaviour of
 * its own: the cases in [NavigationTokenLoggingTest] drive it against sources
 * whose answer is known, which is only possible while it takes lines rather than
 * a filename.
 *
 * Three passes over the text, none of them a Kotlin parser and none pretending to
 * be. [codeView] drops string prose while keeping what a `$` interpolation names,
 * so a message can talk about a token without being one. [statements] gathers a
 * `Logger` call across however many lines it was wrapped onto, by counting
 * parentheses on that same prose-free view, which is what keeps a `" ("` in a
 * message from unbalancing the count. [taintedNames] walks declarations to a
 * fixpoint from the three places the token enters.
 *
 * Its blind spots are the docstring on [NavigationTokenLoggingTest], and one more
 * that belongs to the text scanning rather than to the design: a double quote
 * written inside an interpolation inside a string, `trim('"')` is one, and
 * MainActivity has it, ends the literal early, because handling it properly
 * means recursing into interpolations. The damage is bounded to that one
 * statement, and it cannot hide an interpolated name: [leaks] asks twice, once of
 * the prose-free view and once of the raw text, and only the first is affected.
 */
private object TokenTaint {

    /** Where a value carrying the connection token enters a file. */
    private val SOURCES = listOf(
        Regex("""\bworkbenchUrl\("""),
        Regex("""\bgetConnectionToken\("""),
        Regex("""\b(?:wv|webView)\??\.url\b"""),
    )

    private val DECLARATION = Regex("""\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=]*?)?=(.*)""")
    private val INTERPOLATION = Regex("""\$\{[^}]*}|\$[A-Za-z_]\w*""")
    private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

    /** Statements printing a tainted value with nothing hiding it. */
    fun leaks(source: List<String>): List<String> {
        val tainted = taintedNames(source)
        return statements(source).filter { st ->
            val code = stripRedacted(st.code)
            val raw = stripRedacted(st.raw)
            tainted.any { mentions(code, it) } ||
                INTERPOLATION.findAll(raw).any { hole ->
                    IDENTIFIER.findAll(hole.value).any { it.value in tainted }
                }
        }.map { "${it.line}: ${it.raw}" }
    }

    /** Statements printing a tainted value through the redactor. */
    fun redactedLogs(source: List<String>): List<String> {
        val tainted = taintedNames(source)
        return statements(source).filter { st ->
            redactedArguments(st.raw).any { arg -> tainted.any { mentions(arg, it) } }
        }.map { "${it.line}: ${it.raw}" }
    }

    private class Statement(val line: Int, val raw: String, val code: String)

    private fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    private fun mentions(text: String, name: String): Boolean =
        Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text)

    /**
     * The line with its string prose removed and its interpolations kept.
     *
     * An identifier inside a literal can only be referred to through `$`, so what
     * is left after this is every position where a name means a value.
     */
    private fun codeView(line: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("\"\"\"", i) -> {
                    val end = line.indexOf("\"\"\"", i + 3)
                    val inner = if (end < 0) line.substring(i + 3) else line.substring(i + 3, end)
                    out.append(' ').append(interpolations(inner)).append(' ')
                    i = if (end < 0) line.length else end + 3
                }
                line[i] == '"' -> {
                    var j = i + 1
                    while (j < line.length && !(line[j] == '"' && line[j - 1] != '\\')) j++
                    val inner = line.substring(i + 1, j.coerceAtMost(line.length))
                    out.append(' ').append(interpolations(inner)).append(' ')
                    i = j + 1
                }
                else -> out.append(line[i++])
            }
        }
        return out.toString()
    }

    private fun interpolations(inner: String): String =
        INTERPOLATION.findAll(inner).joinToString(" ") { it.value }

    /** Every `Logger` call, gathered across the lines it was wrapped onto. */
    private fun statements(source: List<String>): List<Statement> {
        val code = source.map { if (isComment(it)) "" else codeView(it) }
        val out = mutableListOf<Statement>()
        var i = 0
        while (i < source.size) {
            if (!code[i].contains("Logger.")) {
                i++
                continue
            }
            val raw = StringBuilder()
            val whole = StringBuilder()
            var depth = 0
            var j = i
            while (j < source.size && j - i < 12) {
                raw.append(if (isComment(source[j])) "" else source[j].trim()).append(' ')
                whole.append(code[j]).append(' ')
                depth += code[j].count { it == '(' } - code[j].count { it == ')' }
                if (depth <= 0) break
                j++
            }
            out += Statement(i + 1, raw.toString().trim(), whole.toString())
            i = j + 1
        }
        return out
    }

    /** Names holding a value that came, however indirectly, from a [SOURCES] hit. */
    private fun taintedNames(source: List<String>): Set<String> {
        val code = source.filterNot(::isComment).map(::codeView)
        val names = linkedSetOf<String>()
        // Declarations are walked to a fixpoint rather than once in file order, so
        // that a chain assigned in the other order is still followed.
        repeat(3) {
            for (line in code) {
                for (m in DECLARATION.findAll(line)) {
                    val name = m.groupValues[1]
                    val initialiser = m.groupValues[2]
                    val seeded = SOURCES.any { it.containsMatchIn(initialiser) }
                    val derived = names.toList()
                        .any { mentions(stripRedacted(initialiser), it) }
                    if (seeded || derived) names += name
                }
            }
        }
        return names
    }

    /** The spans of every `redactToken(...)` call, argument list included. */
    private fun redactSpans(text: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val at = text.indexOf("redactToken(", from)
            if (at < 0) return spans
            var depth = 0
            var i = at + "redactToken".length
            var end = -1
            while (i < text.length) {
                if (text[i] == '(') depth++
                else if (text[i] == ')' && --depth == 0) {
                    end = i
                    break
                }
                i++
            }
            if (end < 0) {
                spans += at..text.lastIndex
                return spans
            }
            spans += at..end
            from = end + 1
        }
    }

    private fun stripRedacted(text: String): String {
        var out = text
        for (span in redactSpans(text).reversed()) out = out.removeRange(span)
        return out
    }

    private fun redactedArguments(text: String): List<String> =
        redactSpans(text).map { text.substring(it).substringAfter('(').removeSuffix(")") }
}

/**
 * The second half of that gate: whether anyone here asked for the callback.
 *
 * Shape was the only thing the relay could judge, and shape is exactly what an
 * unsolicited sender can produce. The id the value is filed under is handed out
 * by the workbench as a counter from one, so it is not a secret that has to be
 * guessed either. Identity cannot be checked at all (the legitimate sender is
 * whichever browser the user has), so what is left is timing: this app either
 * opened a sign-in tab in the last few minutes or it did not.
 *
 * Every case here is arithmetic on three numbers, which is why the function
 * takes its clock readings instead of asking for them.
 */
class AuthCallbackExpectedTest {

    private val window = 10 * 60 * 1000L
    private val opened = 5_000_000L

    @Test
    fun `a return from a sign-in this app started is taken`() {
        // The positive control. Every other case here is a refusal, and a
        // function that had been mutated to refuse everything would satisfy all
        // of them while breaking every sign-in in the app.
        assertTrue(authCallbackIsExpected(opened, opened + 30_000L, window))
    }

    @Test
    fun `a callback arriving with no sign-in in flight is refused`() {
        // The mutation is dropping the `openedAt != 0` guard as implied by the
        // range check. It is not: zero is the reading before any tab has been
        // opened, and early in a boot the elapsed clock is small enough that
        // `now - 0` falls inside the window on its own. That is the whole
        // attack -- a callback nobody asked for, accepted because the numbers
        // happened to line up.
        assertFalse(authCallbackIsExpected(0L, 1_000L, window))
        assertFalse(authCallbackIsExpected(0L, window, window))
    }

    @Test
    fun `a callback long after the tab was opened is refused`() {
        // The mutation is dropping the upper bound, which turns one sign-in into
        // a door that stays open for the rest of the process's life.
        assertFalse(authCallbackIsExpected(opened, opened + window + 1, window))
        assertFalse(authCallbackIsExpected(opened, opened + 24 * 60 * 60 * 1000L, window))
    }

    @Test
    fun `a reading from before the tab was opened is refused`() {
        // The mutation is dropping the lower bound as unreachable. The caller
        // passes a monotonic clock, so a negative elapsed time means the two
        // readings came from different boots and the difference is not an
        // elapsed time at all -- and it compares as comfortably under the
        // window, so a one-sided check accepts it.
        assertFalse(authCallbackIsExpected(opened, opened - 1, window))
        assertFalse(authCallbackIsExpected(opened, 0L, window))
    }

    @Test
    fun `the window is inclusive at its edge and closed one past it`() {
        // The mutation is `until` for `..`. It costs a millisecond of a
        // ten-minute window, so no sign-in would ever fail because of it and
        // nothing would ever say the boundary had moved. Pinned so the range is
        // stated rather than inherited.
        assertTrue(authCallbackIsExpected(opened, opened + window, window))
        assertFalse(authCallbackIsExpected(opened, opened + window + 1, window))
    }
}

/**
 * What a failed folder switch leaves watched.
 *
 * The watcher is stopped before every sync so the engine cannot observe its own
 * writes, which means a failure has to decide whether to put it back, and the
 * first version of that decision was "never", justified by a mirror being
 * part-written. That justification only holds for the folder the sync was
 * writing into. Switch away from a healthy folder, fail, and "never" leaves the
 * folder still on screen unwatched: the user goes on editing it believing it is
 * saving, and nothing anywhere says otherwise.
 */
class RestoreWatcherTest {

    private val opened = "content://com.android.externalstorage.documents/tree/primary%3Awork"
    private val other = "content://com.android.externalstorage.documents/tree/primary%3Anotes"

    @Test
    fun `a failed switch to a different folder keeps the open one watched`() {
        assertTrue(shouldRestorePreviousWatcher(previousUri = opened, failedUri = other))
    }

    @Test
    fun `a failed reopen of the watched folder leaves it stopped`() {
        // Its mirror is the one the sync was part-way through writing, and a
        // watcher over a part-written mirror pushes that half onto the user's
        // own documents. This is the case the whole reorder exists for, so
        // restoring unconditionally would undo it.
        assertFalse(shouldRestorePreviousWatcher(previousUri = opened, failedUri = opened))
    }

    @Test
    fun `nothing is restored when nothing was watched`() {
        // The first folder of a session. Restoring here would ask
        // startFileWatcher for a mirror that does not exist.
        assertFalse(shouldRestorePreviousWatcher(previousUri = null, failedUri = opened))
    }
}

/**
 * That a cancelled scope is not mistaken for a folder that failed.
 *
 * [RestoreWatcherTest] pins which folder a failure leaves watched; this pins what
 * counts as a failure in the first place. The sync runs in `lifecycleScope`, so
 * destroying the Activity cancels it: a font-size or display-size change, a
 * language change, the accessibility Bold Text switch, or "Don't keep
 * activities" while the user is in another app, none of which the manifest's
 * `configChanges` absorbs. A scheduled dark-mode switch used to belong on that
 * list and no longer does, because `uiMode` is declared now. Kotlin's
 * `CancellationException` is a plain `Exception`, so the catch-all sees it and
 * every statement in that handler runs.
 *
 * `restoreWatcherAfterFailure` is the one that matters: it restarts a FileObserver
 * and the `saf-writeback` thread on the `SafStorageManager` this Activity owns,
 * *after* `onDestroy` has stopped it. Only that instance can stop them again and
 * nothing holds it any more, so they run until the process does. When the user
 * next opens that folder two engines watch one mirror, and the orphaned one reads
 * the `.partial` renames of the fresh sync as edits and pushes them back onto the
 * user's own documents.
 *
 * Source reading, and the weaker layer: the handler is inside an Activity method
 * with a `lifecycleScope`, and a plain JVM test can build neither.
 */
class SyncCancellationTest {

    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    private fun codeLines(): List<String> =
        mainActivity.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    @Test
    fun `the sync handler answers cancellation before it answers failure`() {
        check(mainActivity.isFile) {
            "MainActivity.kt not found at ${mainActivity.absolutePath}, this test " +
                "would otherwise pass by looking at nothing"
        }
        val lines = codeLines()

        val sync = lines.indexOfFirst { it.contains("safManager.syncToLocal(") }
        check(sync >= 0) { "the SAF sync is gone; this test is measuring the wrong method" }

        val after = lines.drop(sync)
        val cancelled = after.indexOfFirst { it.contains("catch (e: CancellationException)") }
        val failed = after.indexOfFirst { it.contains("catch (e: Exception)") }

        assertTrue(failed >= 0, "the catch-all is gone; this test is measuring nothing")
        assertTrue(
            cancelled >= 0,
            "a cancelled lifecycleScope reaches `catch (e: Exception)` and is handled as " +
                "a failed folder, which restarts a file watcher on a destroyed Activity's " +
                "engine that nothing can stop again",
        )
        assertTrue(
            cancelled < failed,
            "the cancellation handler must come first, or the catch-all takes it",
        )
        assertTrue(
            after.drop(cancelled).take(6).any { it.contains("throw e") },
            "the cancellation must be rethrown; swallowing it lets the rest of the " +
                "coroutine run on an Activity that is gone",
        )
    }

    @Test
    fun `the failure handler still restores the watcher it is responsible for`() {
        // Control. Deleting `restoreWatcherAfterFailure` outright would satisfy
        // the test above by removing the subject: a real failed switch away from
        // a healthy folder must still put that folder's write-back back.
        val lines = codeLines()
        val restores = lines.count { it.contains("restoreWatcherAfterFailure(previouslyWatched") }

        assertEquals(
            2, restores,
            "expected both failure handlers, permission denied and everything else, " +
                "to decide what stays watched; found $restores",
        )
    }
}

/**
 * What the resume health check is allowed to depend on.
 *
 * The script used to search every `.monaco-dialog-box` for the words
 * "reconnect" and "lost". That is a check on the display language wearing the
 * costume of a check on the connection: under a language pack the substrings are
 * translated, the match never fires, and a broken IPC channel is reported as a
 * healthy one. Nothing throws and nothing logs, so the users it fails are
 * exactly the ones least able to report why.
 */
class ConnectionHealthProbeTest {

    @Test
    fun `the probe reads no user-visible text`() {
        val script = connectionHealthProbe()

        assertFalse(
            script.contains("textContent"),
            "reading rendered text makes the probe depend on the display language",
        )
        assertFalse(
            script.contains("monaco-dialog"),
            "a workbench dialog can only be identified here by the words in it",
        )
    }

    @Test
    fun `the probe asks something that answers the same in every locale`() {
        // A probe that reads nothing at all would also pass the test above.
        assertTrue(
            connectionHealthProbe().contains("indexedDB.open"),
            "narrowing the probe must not empty it",
        )
    }
}

/**
 * What returning from the background is allowed to do to the page.
 *
 * The reload above five minutes was written for a WebSocket that did not survive
 * being frozen, and it collides with the one thing that routinely takes a user
 * out of the app for more than five minutes: signing in. A second factor or an
 * organisation's consent screen is minutes of reading and typing in another app,
 * and the callback is delivered on the way back, into the same moment this
 * decision is made. The workbench keeps the requests it is waiting on in memory,
 * so a reload here throws away a sign-in that had already succeeded, silently.
 *
 * Picking a file from device storage is the second such trip, and the one a user
 * makes far more often. The picker is another app, browsing it takes as long as
 * finding the file takes, and the selection comes back through this same moment.
 */
class ResumeActionTest {

    private val long = FORCE_RELOAD_THRESHOLD_MS + 1
    private val middling = HEALTH_CHECK_THRESHOLD_MS + 1

    @Test
    fun `a long absence with a sign-in in flight probes instead of reloading`() {
        // Not NOTHING, and that is the half worth stating. The absence really was
        // long enough for the connection to have died, and no later resume comes
        // back to it: each one is judged on its own absence, so an action skipped
        // here is an action never taken. The probe is what can be answered safely
        // -- it reloads only when IndexedDB is already unusable, and such a page
        // has nothing left to collect a callback with.
        assertEquals(
            ResumeAction.PROBE_CONNECTION,
            resumeAction(long, signInPending = true, fileChooserPending = false),
        )
    }

    @Test
    fun `a long absence with nothing in flight still reloads`() {
        // The control, and the half that matters more often: downgrading every
        // long absence to the probe would leave a page stale for the rest of the
        // session, which is the failure the reload was added for in the first
        // place.
        assertEquals(
            ResumeAction.RELOAD,
            resumeAction(long, signInPending = false, fileChooserPending = false),
        )
    }

    @Test
    fun `a sign-in in flight does not stop the connection probe`() {
        // Narrower than "do nothing while signing in", deliberately. The probe
        // only reloads when IndexedDB is already unusable, and a page in that
        // state has nothing left to collect a callback with, so suppressing it
        // would trade a real recovery for a callback that cannot be delivered.
        assertEquals(
            ResumeAction.PROBE_CONNECTION,
            resumeAction(middling, signInPending = true, fileChooserPending = false),
        )
        assertEquals(
            ResumeAction.PROBE_CONNECTION,
            resumeAction(middling, signInPending = false, fileChooserPending = false),
        )
    }

    @Test
    fun `a file picker still waiting for an answer stops the reload`() {
        // Browsing device storage is a trip through another app, so the browse is
        // the absence: a file chosen after ten minutes in Downloads comes back
        // through this decision, and the reload discards the document the URI is
        // about to be handed to. The selection is lost with no message and no
        // log line, which is the failure this outranks.
        assertEquals(
            ResumeAction.NOTHING,
            resumeAction(long, signInPending = false, fileChooserPending = true),
        )
    }

    @Test
    fun `a file picker stops the probe as well as the reload`() {
        // Not the downgrade a sign-in gets. The probe reloads the page from JS
        // when IndexedDB is unusable, which loses the selection the same way, and
        // it starts at one minute, where a browse of that length is ordinary.
        assertEquals(
            ResumeAction.NOTHING,
            resumeAction(middling, signInPending = false, fileChooserPending = true),
        )
        assertEquals(
            ResumeAction.NOTHING,
            resumeAction(long, signInPending = true, fileChooserPending = true),
        )
    }

    @Test
    fun `a short absence does nothing, with or without a sign-in`() {
        assertEquals(
            ResumeAction.NOTHING,
            resumeAction(1_000L, signInPending = true, fileChooserPending = false),
        )
        assertEquals(
            ResumeAction.NOTHING,
            resumeAction(1_000L, signInPending = false, fileChooserPending = false),
        )
    }

    @Test
    fun `the sign-in case is tested before the reload, not after`() {
        // The mutation is reordering the two branches, which compiles, reads
        // fine, and makes the sign-in branch unreachable -- so a return from a
        // second factor reloads the page the callback has to land in. At exactly
        // the threshold neither fires, which is the boundary worth stating too.
        assertEquals(
            ResumeAction.PROBE_CONNECTION,
            resumeAction(long, signInPending = true, fileChooserPending = false),
        )
        assertEquals(
            ResumeAction.PROBE_CONNECTION,
            resumeAction(FORCE_RELOAD_THRESHOLD_MS, signInPending = true, fileChooserPending = false),
            "the reload threshold is exclusive; at the boundary there is no reload to spare",
        )
    }

    @Test
    fun `the resume decision is told about the picker, not only the sign-in`() {
        // Source reading, for the reason AuthCallbackCallSiteTest gives for the
        // sign-in half: resumeAction can be exactly right and never told. Passing
        // a literal false here, or dropping the argument on a later edit, leaves
        // every case above green while the reload goes back to discarding the
        // file the user just picked.
        val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")
        check(mainActivity.isFile) { "MainActivity.kt not found at ${mainActivity.absolutePath}" }

        val decisions = mainActivity.readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") }
            .filter { it.contains("resumeAction(") }
            .filterNot { it.contains("fun resumeAction") }

        assertEquals(1, decisions.size, "expected one resume decision, found: $decisions")
        assertTrue(
            decisions.single().contains("fileChooserIsPending()"),
            "the resume decision must be told whether a file picker is still out; found: " +
                decisions.single(),
        )
    }
}
