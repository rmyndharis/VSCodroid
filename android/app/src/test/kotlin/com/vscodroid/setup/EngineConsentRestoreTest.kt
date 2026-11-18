package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `vscodroid.antigravity.runEngine` is consent given for the apps on one phone,
 * and the machine settings file it lives in travels with a backup and a move to
 * a new phone. Setup on an install no build has completed setup on, which is
 * what a restore is, turns it off and leaves the rest of the file alone.
 *
 * What counts as the setting is what platform-fix.js reads as it, which is how
 * VS Code builds its settings from a file: three spellings, and every one of
 * them has to come out off, or the engine starts on the new phone with no notice.
 * `EngineConsentSetupRunTest` runs the setup that calls this.
 */
class EngineConsentRestoreTest {
    private val key = "vscodroid.antigravity.runEngine"

    @Test
    fun `the setting on in the file the app writes is turned off and nothing else moves`() {
        val before = "{\n    // what the app wrote\n    \"git.path\": \"/x/libgit.so\",\n" +
            "    \"$key\": true,\n    \"update.mode\": \"none\"\n}\n"
        assertEquals(before.replace("\"$key\": true", "\"$key\": false"), withEngineConsentWithdrawn(before))
    }

    @Test
    fun `every spelling VS Code reads as the top-level key is turned off`() {
        for ((label, before, after) in listOf(
            Triple("a single line", "{\"$key\": true}", "{\"$key\": false}"),
            Triple("a byte order mark", "﻿{\"$key\": true}", "﻿{\"$key\": false}"),
            Triple("CRLF", "{\r\n\t\"$key\": true\r\n}\r\n", "{\r\n\t\"$key\": false\r\n}\r\n"),
            Triple(
                "an escaped dot",
                "{\n    \"vscodroid.antigravity\\u002erunEngine\": true\n}\n",
                "{\n    \"vscodroid.antigravity\\u002erunEngine\": false\n}\n",
            ),
            Triple(
                "comments around the value",
                "{ /* x */\n    \"$key\" /* y */ : /* z */ true // on\n}\n",
                "{ /* x */\n    \"$key\" /* y */ : /* z */ false // on\n}\n",
            ),
            Triple(
                "the key twice, so the last is in force",
                "{\n    \"$key\": true,\n    \"a\": \"/*\",\n    \"$key\": true\n}\n",
                "{\n    \"$key\": false,\n    \"a\": \"/*\",\n    \"$key\": false\n}\n",
            ),
            Triple(
                "the setting written as an object under vscodroid.antigravity",
                "{\n    \"vscodroid.antigravity\": { \"runEngine\": true }\n}\n",
                "{\n    \"vscodroid.antigravity\": { \"runEngine\": false }\n}\n",
            ),
            Triple(
                "the fully nested form",
                "{\n    \"vscodroid\": { \"antigravity\": { \"runEngine\": true } }\n}\n",
                "{\n    \"vscodroid\": { \"antigravity\": { \"runEngine\": false } }\n}\n",
            ),
            Triple(
                "an object spelled with an escape, and a tab before the value",
                "{\n    \"vscodroid\\u002eantigravity\": {\n        \"runEngine\":\ttrue\n    }\n}\n",
                "{\n    \"vscodroid\\u002eantigravity\": {\n        \"runEngine\":\tfalse\n    }\n}\n",
            ),
            Triple(
                "a copy a later key replaces, which the reader reads as off and which is turned off all the same",
                "{\n    \"$key\": true,\n    \"vscodroid\": {}\n}\n",
                "{\n    \"$key\": false,\n    \"vscodroid\": {}\n}\n",
            ),
            Triple(
                "two spellings at once",
                "{\n    \"vscodroid.antigravity\": { \"runEngine\": true },\n    \"$key\": true\n}\n",
                "{\n    \"vscodroid.antigravity\": { \"runEngine\": false },\n    \"$key\": false\n}\n",
            ),
            Triple(
                "after a long string, a deep array and a comment that holds a quote",
                "{\n    \"a\": \"" + "x".repeat(300_000) + "\",\n    \"b\": " + "[".repeat(50_000) + "]".repeat(50_000) +
                    ",\n    // it's not a string\n    \"$key\": true\n}\n",
                "{\n    \"a\": \"" + "x".repeat(300_000) + "\",\n    \"b\": " + "[".repeat(50_000) + "]".repeat(50_000) +
                    ",\n    // it's not a string\n    \"$key\": false\n}\n",
            ),
            Triple(
                "after a string of a hundred thousand escaped line breaks",
                "{\n    \"a\": \"" + "\\n".repeat(100_000) + "\",\n    \"$key\": true\n}\n",
                "{\n    \"a\": \"" + "\\n".repeat(100_000) + "\",\n    \"$key\": false\n}\n",
            ),
        )) {
            assertEquals(after, withEngineConsentWithdrawn(before), label)
        }
    }

    @Test
    fun `what VS Code does not read as the setting is left as it is`() {
        for ((label, text) in listOf(
            "off" to "{\n    \"$key\": false\n}\n",
            "absent" to "{\n    \"update.mode\": \"none\"\n}\n",
            "in a line comment" to "{\n    // \"$key\": true,\n    \"update.mode\": \"none\"\n}\n",
            "in a block comment" to "{\n    /* \"$key\": true */\n}\n",
            "inside another object" to "{\n    \"some.object\": {\n        \"$key\": true\n    }\n}\n",
            "inside a language block" to "{\n    \"[python]\": {\n        \"$key\": true\n    }\n}\n",
            "quoted inside another value" to "{\n    \"note\": \"\\\"$key\\\": true\"\n}\n",
            "not an object" to "[{\"$key\": true}]\n",
            "a dot inside a nested key, which VS Code does not split" to
                "{\n    \"vscodroid\": { \"antigravity.runEngine\": true }\n}\n",
            "inside an array under the setting's parent" to "{\n    \"vscodroid.antigravity\": [ { \"runEngine\": true } ]\n}\n",
            "a word that only starts like true" to "{\n    \"$key\": trueish\n}\n",
            "a string, not the value true" to "{\n    \"$key\": \"true\"\n}\n",
            "a key that is not the setting's, with a value that is" to "{\n    \"$key.extra\": true\n}\n",
            "a key too long to be the setting's" to "{\n    \"" + "k".repeat(400) + "\": true\n}\n",
            "an unterminated string after the key" to "{\n    \"$key\": false,\n    \"a\": \"never closed",
        )) {
            assertNull(withEngineConsentWithdrawn(text), label)
        }
    }

    /**
     * What the one pass over the text must keep from the pattern it replaced: the same text in, the same
     * text out. Compared on a few thousand small documents made of the characters that decide it (a quote, a
     * backslash, the comment openers, a line break), except where a backslash comes right before a line
     * break, which the pattern did not take as an escape and which is not a document anyone writes.
     */
    @Test
    fun `blanking comments gives what the pattern it replaced gave`() {
        val oracle = Regex("""\"(?:\\.|[^\"\\])*\"|//[^\n]*|/\*[\s\S]*?\*/""")
        fun expected(text: String) = oracle.replace(text) { match ->
            if (match.value.startsWith("\"")) match.value else match.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")
        }
        val alphabet = listOf("\"", "\\", "/", "*", "\n", " ", "a", "{", "}", ",", ":", "\"", "/", "*")
        val random = java.util.Random(20261117)
        var compared = 0
        repeat(40_000) {
            val text = (0 until random.nextInt(16)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }
            if (text.contains("\\\n")) return@repeat
            assertEquals(expected(text), commentsBlanked(text), "input: " + text.replace("\n", "\\n"))
            compared++
        }
        assertTrue(compared > 20_000, "too few documents were compared: $compared")
    }

    /**
     * A line comment ends at a CR as well as at an LF, as it does in VS Code's scanner and in
     * platform-fix.js, which reads the same file to start the engine. Ended only at an LF, a file
     * whose lines end in a bare CR had everything after its first comment read as that comment: the
     * setting on the next line was on for platform-fix.js and was not found here, so a restore left
     * the engine's consent standing.
     */
    @Test
    fun `a line comment ends at a carriage return`() {
        val bare = "{\r// a comment\r\"$key\": true\r}\r"
        assertEquals("{\r// a comment\r\"$key\": false\r}\r", withEngineConsentWithdrawn(bare))
        assertEquals("{\r" + " ".repeat("// a comment".length) + "\r\"$key\": true\r}\r", commentsBlanked(bare))

        val crlf = "{\r\n// a comment\r\n\"$key\": true\r\n}\r\n"
        assertEquals("{\r\n// a comment\r\n\"$key\": false\r\n}\r\n", withEngineConsentWithdrawn(crlf))
        assertEquals(crlf.length, commentsBlanked(crlf).length)

        // A comment before the root brace, which the search for that brace has to step over as well.
        val headed = "// a header\r{\r\"$key\": true\r}\r"
        assertEquals("// a header\r{\r\"$key\": false\r}\r", withEngineConsentWithdrawn(headed))

        // A comment that is the last thing in the file, with no line break after it.
        assertEquals("{}\r" + " ".repeat("// no line break".length), commentsBlanked("{}\r// no line break"))
    }

    @Test
    fun `blanking comments keeps the length and the line breaks, and strings whole`() {
        val text = "{ // it's a comment\n \"a\": \"http://x/*y*/\", /* two\nlines */ \"b\": 1 }"
        val blanked = commentsBlanked(text)
        assertEquals(text.length, blanked.length)
        assertEquals(text.count { it == '\n' }, blanked.count { it == '\n' })
        assertTrue(blanked.contains("\"http://x/*y*/\""), blanked)
        assertTrue(!blanked.contains("comment") && !blanked.contains("lines"), blanked)
    }
}
