package com.vscodroid.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That nothing a page hands the bridge reaches logcat in the clear.
 *
 * `Logger.i`, `Logger.w` and `Logger.e` are not gated on a debuggable build, so
 * they ship, and logcat is readable by anything holding `READ_LOGS`. The page on
 * the other side of these methods is the workbench, which holds the connection
 * token, so a string it passes can carry one. `logToNative` states that reasoning
 * and redacts; this makes it true of every method rather than of the one whose
 * author thought of it.
 *
 * Written as a predicate over the parameters, not as a list of known sites. Four
 * sites were already redacted by hand and three more were not, and the three were
 * missed because a reader sweeping for `url` and `uri` does not match `name` or
 * `uriString`. A list would have been written from the same sweep and inherited
 * the same blind spot.
 */
class PageSuppliedLoggingTest {

    private val bridge = File("src/main/kotlin/com/vscodroid/bridge/AndroidBridge.kt")

    /**
     * One chunk per `@JavascriptInterface`, from the annotation to the next one.
     *
     * A superset of the method body rather than the body exactly: a private helper
     * declared between two bridge methods falls into the earlier chunk. That is the
     * safe direction. Such a helper is reached from the bridge and holds the same
     * values, so examining it too is not a false alarm, while missing it would be a
     * real one.
     */
    private fun bridgeMethods(): List<String> {
        assertTrue(
            bridge.isFile,
            "${bridge.absolutePath} is missing; this test would otherwise pass by reading nothing",
        )
        return stripComments(bridge.readText()).split("@JavascriptInterface").drop(1)
    }

    /**
     * [source] with its comments removed and its line structure kept.
     *
     * Everything below reads statements as text, and text a compiler never sees
     * answers those questions just as readily as code does. Two of them turn on
     * it. A trailing `// redactToken(uri)` beside a live `Logger.i(tag, "…$uri")`
     * sits inside the span [statementFrom] returns, so it answers the redaction
     * question for the call it is written next to, and a redaction taken off
     * while debugging, the one shape this class exists to catch, stops being
     * reported. The other is the vacuity counter: a commented-out `Logger` call
     * is counted as one examined, so a file whose live log lines had all been
     * disabled would still report that the scan looked at something.
     *
     * Newlines are kept, because [statementFrom] ends a statement on one and a
     * comment removed together with its line break would join the statement above
     * it to the one below.
     *
     * String literals are stepped over, raw strings included, so that a `//`
     * inside a message or a URL does not read as the start of a comment and take
     * the rest of the call with it. Character literals are not handled and this
     * file has none.
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3)
                    val stop = if (end < 0) source.length else end + 3
                    out.append(source, i, stop)
                    i = stop
                }
                source[i] == '"' -> {
                    var j = i + 1
                    while (j < source.length && source[j] != '"' && source[j] != '\n') {
                        if (source[j] == '\\') j++
                        j++
                    }
                    val stop = (if (j < source.length && source[j] == '"') j + 1 else j)
                        .coerceAtMost(source.length)
                    out.append(source, i, stop)
                    i = stop
                }
                source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    val stop = if (end < 0) source.length else end + 2
                    for (k in i until stop) if (source[k] == '\n') out.append('\n')
                    i = stop
                }
                else -> out.append(source[i++])
            }
        }
        return out.toString()
    }

    /**
     * From [from] to the end of the statement, following an unclosed bracket onto
     * the lines that continue it.
     *
     * A line-scoped read was the first shape of this scan and it had a hole with
     * no bottom: a `Logger.…(` whose arguments all sit on continuation lines
     * yields an empty capture, every question below is asked of nothing, and the
     * vacuity counter still increments, so the guard reports clean over a call it
     * never saw. Nothing in this repository forces such a wrap, since there is no
     * ktlint and no detekt, which is exactly why it would arrive unannounced.
     *
     * String literals are stepped over so that an unbalanced parenthesis inside a
     * message cannot end the statement early. Raw strings are not handled, and no
     * log call here uses one.
     */
    private fun statementFrom(text: String, from: Int): String {
        var i = from
        var depth = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                inString -> {}
                c == '(' || c == '{' -> depth++
                c == ')' || c == '}' -> depth--
                c == '\n' && depth <= 0 -> return text.substring(from, i)
            }
            i++
        }
        return text.substring(from)
    }

    /** What one scan found, and how much it looked at. */
    private data class Scan(val offenders: List<String>, val examined: Int)

    /**
     * Every page-supplied value that reaches a log call in [source] unredacted,
     * and every redacted call that hands over the exception beside it.
     *
     * Taken as a function of text rather than of the file, so its own correctness
     * can be put to fixtures below. A scanner that is only ever run against a tree
     * it reports clean has no evidence that it would report a dirty one.
     */
    private fun scan(source: String): Scan {
        val offenders = mutableListOf<String>()
        var examined = 0
        for (chunk in stripComments(source).split("@JavascriptInterface").drop(1)) {
            val signature = Regex("""fun\s+(\w+)\s*\(([^)]*)\)""").find(chunk) ?: continue
            val name = signature.groupValues[1]
            // authToken is the session token the page already holds, and it is never
            // logged; it is excluded so its absence cannot be mistaken for coverage.
            val params = signature.groupValues[2]
                .split(",")
                .mapNotNull { it.substringBefore(":").trim().ifEmpty { null } }
                .filter { it != "authToken" && it.matches(Regex("""\w+""")) }
            if (params.isEmpty()) continue

            // A parameter renamed on the way to the log line is the case that made
            // this necessary: `openRecentFolder` logged `uri`, a local built by
            // `Uri.parse(uriString)`, so a search for the parameter name found
            // nothing. Locals assigned from a parameter carry its taint, and the
            // right-hand side is read to the end of the statement rather than the
            // end of the line, for the reason [statementFrom] gives.
            val tainted = params.toMutableSet()
            for (m in Regex("""val\s+(\w+)\s*=""").findAll(chunk)) {
                val rhs = statementFrom(chunk, m.range.last + 1)
                if (tainted.any { Regex("""\b$it\b""").containsMatchIn(rhs) }) {
                    tainted += m.groupValues[1]
                }
            }
            // What a catch binds. A throwable is the second channel a log line has,
            // and redacting the first while handing over the second only looks like
            // redaction: Log.e prints the throwable with its message, and the
            // exceptions raised on these paths quote the whole URI or file they
            // failed on. The message and the trace then disagree about the same
            // value, one line apart.
            val caught = Regex("""catch\s*\(\s*(\w+)\s*:""")
                .findAll(chunk).map { it.groupValues[1] }.toSet()

            for (call in Regex("""Logger\.[diwe]\(""").findAll(chunk)) {
                val text = statementFrom(chunk, call.range.last)
                examined++
                for (value in tainted) {
                    val interpolated = Regex("""\$\{?$value\b""").containsMatchIn(text)
                    val redacted = Regex("""redactToken\(\s*$value\b""").containsMatchIn(text)
                    if (interpolated && !redacted) {
                        offenders += "$name logs $value unredacted: ${text.trim()}"
                    }
                }
                if (text.contains("redactToken(")) {
                    for (binding in caught) {
                        if (Regex(""",\s*$binding\s*\)""").containsMatchIn(text)) {
                            offenders += "$name redacts its message and then hands over " +
                                "$binding, whose own message repeats what was redacted: " +
                                text.trim()
                        }
                    }
                }
            }
        }
        return Scan(offenders, examined)
    }

    @Test
    fun `every page supplied value reaching a log call is redacted`() {
        val methods = bridgeMethods()
        // The control for the split. check-bridge-api-spec.py counts 31 bridge
        // methods, so a split that returns a handful means the annotation moved or
        // the file did, and every assertion below would then pass by examining
        // almost nothing.
        assertTrue(
            methods.size >= 25,
            "only ${methods.size} @JavascriptInterface methods were found; the scan is " +
                "looking at the wrong thing, and passing here would mean nothing",
        )

        val result = scan(bridge.readText())

        // If no bridge method logs any of its own values, the scan proves nothing,
        // and a refactor that moved every log line out would leave this green while
        // removing everything it watches.
        assertTrue(
            result.examined > 0,
            "no log call inside any bridge method was examined, so this test is vacuous",
        )
        assertTrue(
            result.offenders.isEmpty(),
            "a value the page supplied reaches a shipping log line in the clear:\n" +
                result.offenders.joinToString("\n") { "  $it" },
        )
    }

    /**
     * The scanner against a call whose arguments are all on continuation lines.
     *
     * Without this the widening is unverified and can regress to a line-scoped read
     * silently, because the tree it runs over is clean either way: this is the only
     * place a wrapped call exists to be found.
     */
    @Test
    fun `a wrapped log call is not invisible to the scan`() {
        val fixture = """
            @JavascriptInterface
            fun openThing(uriString: String, authToken: String) {
                Logger.w(
                    tag,
                    "Opening ${'$'}uriString",
                )
            }
        """.trimIndent()

        val result = scan(fixture)

        assertEquals(1, result.examined, "the wrapped call was not counted at all")
        assertTrue(
            result.offenders.any { it.contains("uriString") },
            "a page-supplied value on a continuation line was not seen: ${result.offenders}",
        )
    }

    /**
     * The same widening, on the other side of the scan.
     *
     * The taint follows a parameter into a local, and that read was line-scoped
     * too. A wrapped assignment would leave the local untainted, so the log line
     * below it reports clean while printing the parameter under another name,
     * which is precisely the renaming this taint step exists for.
     */
    @Test
    fun `a wrapped assignment still carries the taint`() {
        val fixture = """
            @JavascriptInterface
            fun openThing(uriString: String, authToken: String) {
                val uri = Uri.parse(
                    uriString,
                )
                Logger.i(tag, "Opening ${'$'}uri")
            }
        """.trimIndent()

        val result = scan(fixture)

        assertTrue(
            result.offenders.any { it.contains("logs uri ") },
            "the local built from a page-supplied parameter over two lines was not " +
                "tainted, so the log below it looked clean: ${result.offenders}",
        )
    }

    /**
     * The scanner against the shape that defeats a redaction from the side.
     *
     * The message is redacted and the exception is handed over anyway, and the
     * exception's own message repeats the value. This is the live defect that was
     * found one file over, in `VSCodroidWebViewClient`, which this class does not
     * read; the rule is expressed here so a bridge method cannot acquire it.
     */
    @Test
    fun `a redacted message that also hands over its exception is reported`() {
        val fixture = """
            @JavascriptInterface
            fun openThing(url: String, authToken: String) {
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Logger.e(tag, "Failed: ${'$'}{redactToken(url)}", e)
                }
            }
        """.trimIndent()

        val result = scan(fixture)

        assertTrue(
            result.offenders.any { it.contains("hands over e") },
            "the throwable channel was not examined: ${result.offenders}",
        )
    }

    /**
     * The control for the case above, and the reason it is not simply "never pass a
     * throwable". A call that redacts nothing has nothing for the trace to
     * contradict, and one that redacts without handing anything over is the shape
     * this whole class is asking for.
     */
    @Test
    fun `a redacted message with no throwable is left alone`() {
        val fixture = """
            @JavascriptInterface
            fun openThing(url: String, authToken: String) {
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Logger.e(tag, "Failed: ${'$'}{redactToken(url)} (${'$'}{e.javaClass.simpleName})")
                }
            }
        """.trimIndent()

        val result = scan(fixture)

        assertTrue(
            result.offenders.isEmpty(),
            "the shape this class exists to ask for was reported as an offence: ${result.offenders}",
        )
    }

    /**
     * The scanner against a redaction that is only written in a comment.
     *
     * The span [statementFrom] returns runs to the end of the line the call
     * closes on, so a trailing comment is part of the text every question below
     * is asked of. `redactToken(uriString)` written there answers the redaction
     * question for a call that does not make it, and the answer is the wrong one:
     * this is what taking a redaction off and leaving a note about it looks like,
     * which is the exact shape the class exists to report.
     */
    @Test
    fun `a redaction named only in a comment does not answer for the call`() {
        val fixture = """
            @JavascriptInterface
            fun openThing(uriString: String, authToken: String) {
                Logger.i(tag, "Opening ${'$'}uriString") // redactToken(uriString)
            }
        """.trimIndent()

        val result = scan(fixture)

        assertEquals(1, result.examined, "the live call was not examined at all")
        assertTrue(
            result.offenders.any { it.contains("uriString") },
            "a comment beside the call answered the redaction question for it: ${result.offenders}",
        )
    }

    /**
     * What the vacuity counter is allowed to count.
     *
     * `examined > 0` is the only thing standing between this class and reporting
     * clean over a file it read nothing out of, and a counter that includes
     * disabled calls reports coverage of exactly the lines that are no longer
     * there. Both spellings, because commenting a line out and commenting a block
     * out are the same act.
     */
    @Test
    fun `a log call that is commented out is not counted as one`() {
        val fixture = """
            @JavascriptInterface
            fun openThing(uriString: String, authToken: String) {
                // Logger.i(tag, "Opening ${'$'}uriString")
                /* Logger.w(tag, "Opening ${'$'}uriString") */
                onOpen(uriString)
            }
        """.trimIndent()

        val result = scan(fixture)

        assertEquals(
            0, result.examined,
            "a disabled log call was counted as one the scan had looked at",
        )
        assertTrue(
            result.offenders.isEmpty(),
            "a disabled log call was reported as a leak: ${result.offenders}",
        )
    }

    /**
     * The control for dropping comments, and the reason it cannot be done on text
     * alone.
     *
     * Two slashes are ordinary inside a message: any URL has them. Cutting at the
     * first pair without knowing a literal was open would end this call at
     * `http:`, and both the value and the closing quote would be gone before
     * anything was asked about them.
     */
    @Test
    fun `a slash pair inside a message does not hide the rest of the call`() {
        val fixture = """
            @JavascriptInterface
            fun openThing(uriString: String, authToken: String) {
                Logger.w(tag, "Opening http://example.com from ${'$'}uriString")
            }
        """.trimIndent()

        val result = scan(fixture)

        assertEquals(1, result.examined, "the call was lost with the message it carries")
        assertTrue(
            result.offenders.any { it.contains("uriString") },
            "a URL in the message hid the value beside it: ${result.offenders}",
        )
    }

    /**
     * The one site a predicate over `AndroidBridge` cannot reach.
     *
     * `DownloadCoordinator` takes the file name from `onDownloadNamed`, which the
     * page calls, and the failure detail from `onComplete(requestId, error)`, whose
     * error string the page also writes. Both are renamed twice on the way to
     * `MainActivity`'s `DownloadHost.report`, so no scan anchored on a bridge
     * parameter name can follow them, which is exactly why this line stayed in the
     * clear while its siblings were fixed.
     */
    @Test
    fun `the download report redacts both values it prints`() {
        val activity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")
        assertTrue(
            activity.isFile,
            "${activity.absolutePath} is missing; this test would otherwise pass by reading nothing",
        )
        val report = activity.readText()
            .substringAfter("override fun report(outcome: DownloadOutcome", "")
        assertTrue(
            report.isNotEmpty(),
            "DownloadHost.report is gone from MainActivity; if it moved, point this at its " +
                "new home rather than deleting the check",
        )
        val line = Regex("""(?m)^\s*Logger\.w\(tag, "Download of.*""").find(report)
        assertTrue(line != null, "the download failure is no longer logged at all")
        val text = line!!.value
        assertTrue(
            Regex("""redactToken\(\s*fileName\b""").containsMatchIn(text),
            "the page-supplied file name reaches logcat in the clear: ${text.trim()}",
        )
        assertTrue(
            Regex("""redactToken\(\s*detail\b""").containsMatchIn(text),
            "the page-supplied failure detail reaches logcat in the clear: ${text.trim()}",
        )
    }
}
