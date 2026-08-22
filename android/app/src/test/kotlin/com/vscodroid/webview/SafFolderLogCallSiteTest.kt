package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That opening a device folder does not spell the user's directory into logcat.
 *
 * A SAF tree URI is the path itself, percent-encoded:
 * `content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FClientProject`
 * names the employer, the project and the folder layout of whoever is holding
 * the phone. `Logger.i` is not gated on a debuggable build, so the line ships,
 * and logcat is readable by anything holding `READ_LOGS` and by whoever a device
 * bug report is sent to.
 *
 * The redaction one frame down already exists: `SafStorageManager` names the same
 * folder by the digest its mirror is called after, and `SafFolderPathLoggingTest`
 * holds it there. That guard drives `SafStorageManager` and `SafSyncEngine`
 * directly and never opens `MainActivity.kt`, so the caller one frame up was
 * outside the population it covers and went on printing the value in full.
 *
 * Source reading, for the reason `DownloadListenerWiringTest` gives: there is no
 * seam. `openSafFolder` shows a dialog, takes a permission and launches a
 * coroutine, none of which a plain JVM test has. Comments are stripped first,
 * because the rule is discussed at length in prose beside the very line it
 * governs and a search over the raw text would be satisfied by the explanation.
 *
 * What this deliberately does not certify: the `Logger.e` calls in the same body
 * pass a `SecurityException` whose own message can quote the URI the provider
 * refused. That is the same defect in a different channel and is not fixed here,
 * so nothing below reads as a clean bill for it.
 */
class SafFolderLogCallSiteTest {

    private val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt").readText()

    /** The body of a `private fun name(` declaration, to its closing brace. */
    private fun body(name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue(start >= 0) {
            "$name is gone from MainActivity.kt, so this test is measuring nothing. " +
                "If it moved or was renamed, point this at the new site rather than deleting it."
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
        throw AssertionError("Could not find the end of $name in MainActivity.kt")
    }

    /**
     * Comments removed, so prose about the rule cannot satisfy a search for the
     * rule. Both forms, because both disable code, and a block counts as a
     * comment only where it opens a line.
     */
    private fun withoutComments(text: String): String {
        var inBlock = false
        return text.lines().joinToString("\n") { raw ->
            var line = raw
            if (inBlock) {
                val close = line.indexOf("*/")
                if (close < 0) return@joinToString ""
                inBlock = false
                line = line.substring(close + 2)
            }
            while (line.trimStart().startsWith("/*")) {
                val open = line.indexOf("/*")
                val close = line.indexOf("*/", open + 2)
                if (close < 0) {
                    inBlock = true
                    return@joinToString line.substring(0, open)
                }
                line = line.substring(0, open) + line.substring(close + 2)
            }
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    private val opened by lazy { withoutComments(body("openSafFolder")) }

    @Test
    fun `the body being checked was actually found`() {
        assertTrue(opened.isNotBlank() && opened.contains("persistPermission")) {
            "openSafFolder no longer looks like the function these cases were written " +
                "against, so both of them are searching an empty string and would pass " +
                "over anything. Point them at wherever a device folder is opened now."
        }
    }

    @Test
    fun `opening a device folder does not put its tree URI in the log`() {
        val interpolated = Regex("""\$\{?uri\b""")
        val leaks = opened.lines()
            .filter { it.contains("Logger.") && interpolated.containsMatchIn(it) }

        assertTrue(leaks.isEmpty()) {
            "a SAF tree URI is the user's own directory written out, and these lines " +
                "ship: Logger.i is not gated on a debuggable build. Name the folder by " +
                "its mirror digest, which is what persistPermission one line below " +
                "already does. Found:\n" + leaks.joinToString("\n") { "  ${it.trim()}" }
        }
    }

    @Test
    fun `the line still names the folder by its mirror`() {
        val named = opened.lines()
            .any { it.contains("Logger.") && it.contains("getMirrorDir(uri).name") }

        assertTrue(named) {
            "nothing in openSafFolder says which folder is being opened, so a bug report " +
                "can no longer be lined up with one. Redaction here means naming the " +
                "folder by the digest the rest of the app already calls it by, not " +
                "deleting the statement. Found:\n" +
                opened.lines().filter { it.contains("Logger.") }
                    .joinToString("\n") { "  ${it.trim()}" }
        }
    }
}
