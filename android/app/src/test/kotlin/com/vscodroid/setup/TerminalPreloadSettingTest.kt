package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [ensureTerminalPreload]: the one line this app adds to
 * `terminal.integrated.env.linux` so an install that predates the exec
 * interceptor gets it on its next launch.
 *
 * The value is a Bionic library the linker loads ahead of every program a
 * terminal starts, and a missing LD_PRELOAD entry is fatal to every exec, so the
 * rules about when NOT to write are the ones that matter most here. A key the
 * user already holds, whatever its value, is theirs: a path of their own is a
 * choice, and `"LD_PRELOAD": null` is the off switch, the same leave-alone rule
 * `claudeCode.claudeProcessWrapper` follows. And settings.json is JSONC the
 * user owns, so the insert is one line and every other byte survives.
 *
 * Fixtures are built by concatenation rather than `trimIndent()`, because a
 * template holding a newline shifts the minimal indent and with it every line,
 * and these cases assert on indentation.
 */
class TerminalPreloadSettingTest {

    private val preload = "/data/user/0/com.vscodroid/files/usr/lib/libtermux-exec.so"

    private fun settings(envLinux: String? = null, preamble: String = "") =
        "{\n" +
            preamble +
            "    \"editor.fontSize\": 14,\n" +
            (envLinux?.let { "    \"terminal.integrated.env.linux\": $it,\n" } ?: "") +
            "    \"terminal.integrated.profiles.linux\": {\n" +
            "        \"bash\": {\n" +
            "            \"path\": \"/data/user/0/com.vscodroid/files/usr/bin/bash\",\n" +
            "            \"args\": [],\n" +
            "            \"icon\": \"terminal-bash\"\n" +
            "        }\n" +
            "    },\n" +
            "    \"workbench.colorTheme\": \"Monokai\"\n" +
            "}\n"

    /** The document with every line holding [needle] taken out, for byte-identity checks. */
    private fun without(document: String, needle: String) =
        document.lines().filterNot { it.contains(needle) }.joinToString("\n")

    @Nested
    inner class Absent {

        @Test
        fun `inserts the key with the preload when the document has none`() {
            val result = ensureTerminalPreload(settings(), preload)

            requireNotNull(result) { "a document without the key must gain it" }
            assertTrue(
                result.startsWith("{\n    \"terminal.integrated.env.linux\": { \"LD_PRELOAD\": \"$preload\" },\n"),
                "the key was not inserted at the document's indent in the shape insertSetting writes:\n$result",
            )
            assertFalse(result.contains("/data/app/"), "the value must never name nativeLibraryDir")
            assertEquals(settings(), without(result, "LD_PRELOAD"), "bytes outside the new line changed")
        }

        @Test
        fun `a root object opening with a comment falls back to four spaces`() {
            val document = "{\n  // mine\n  \"editor.fontSize\": 14\n}\n"

            val result = ensureTerminalPreload(document, preload)

            requireNotNull(result)
            assertTrue(
                result.startsWith("{\n    \"terminal.integrated.env.linux\""),
                "the insert did not take the four-space fallback:\n$result",
            )
            assertEquals(document, without(result, "LD_PRELOAD"), "bytes outside the new line changed")
        }
    }

    @Nested
    inner class ExistingObject {

        @Test
        fun `adds one line to an object that holds other keys, at the object's indent`() {
            val document = settings(envLinux = "{\n        \"FOO\": \"bar\",\n        \"BAZ\": \"qux\"\n    }")

            val result = ensureTerminalPreload(document, preload)

            requireNotNull(result) { "an object without LD_PRELOAD must gain it" }
            val line = result.lines().single { it.contains("LD_PRELOAD") }
            assertEquals("        \"LD_PRELOAD\": \"$preload\",", line, "the line is not at the object's indent")
            assertTrue(result.contains("\"FOO\": \"bar\""), "another key was lost:\n$result")
            assertTrue(result.contains("\"BAZ\": \"qux\""), "another key was lost:\n$result")
            assertEquals(document, without(result, "LD_PRELOAD"), "bytes outside the new line changed")
        }

        @Test
        fun `an empty object gains the line without a trailing comma`() {
            val result = ensureTerminalPreload(settings(envLinux = "{}"), preload)

            requireNotNull(result)
            assertTrue(
                result.contains("\"terminal.integrated.env.linux\": {\n    \"LD_PRELOAD\": \"$preload\"},\n"),
                "an empty object took a trailing comma or the wrong shape:\n$result",
            )
        }

        @Test
        fun `an object whose first entry is a comment falls back to four spaces`() {
            val document = settings(envLinux = "{\n        // keep\n        \"FOO\": \"bar\"\n    }")

            val result = ensureTerminalPreload(document, preload)

            requireNotNull(result)
            val line = result.lines().single { it.contains("LD_PRELOAD") }
            assertEquals("    \"LD_PRELOAD\": \"$preload\",", line)
            assertTrue(result.contains("        // keep\n"), "the comment did not survive:\n$result")
        }

        @Test
        fun `a value holding a closing brace does not end the object early`() {
            // The scan for the object's end has to skip string values, or the
            // first `}` inside one reads as the close and an LD_PRELOAD after it
            // is missed, which writes a second copy of the key beside the user's.
            val document = settings(
                envLinux = "{\n        \"PS1\": \"\\\\u@} \",\n        \"LD_PRELOAD\": \"/mine.so\"\n    }",
            )

            assertNull(ensureTerminalPreload(document, preload))
        }

        @Test
        fun `an env object for another platform ahead of it is not the one edited`() {
            val document = "{\n" +
                "    \"terminal.integrated.env.osx\": {\n        \"FOO\": \"bar\"\n    },\n" +
                "    \"terminal.integrated.env.linux\": {\n        \"FOO\": \"bar\"\n    },\n" +
                "    \"workbench.colorTheme\": \"Monokai\"\n}\n"

            val result = ensureTerminalPreload(document, preload)

            requireNotNull(result)
            val osx = result.substringAfter("\"terminal.integrated.env.osx\"")
                .substringBefore("\"terminal.integrated.env.linux\"")
            assertFalse(osx.contains("LD_PRELOAD"), "the osx object was edited:\n$result")
            val linux = result.substringAfter("\"terminal.integrated.env.linux\"")
            assertTrue(linux.contains("\n        \"LD_PRELOAD\": \"$preload\",\n"), "the linux object was not:\n$result")
        }
    }

    @Nested
    inner class LeftAlone {

        @Test
        fun `a preload the user chose is theirs`() {
            assertNull(ensureTerminalPreload(settings(envLinux = "{ \"LD_PRELOAD\": \"/their/own.so\" }"), preload))
        }

        @Test
        fun `null is the off switch and survives`() {
            assertNull(ensureTerminalPreload(settings(envLinux = "{ \"LD_PRELOAD\": null }"), preload))
        }

        @Test
        fun `a second pass over our own value writes nothing`() {
            val first = requireNotNull(ensureTerminalPreload(settings(), preload))

            assertNull(ensureTerminalPreload(first, preload), "the insert is not idempotent")
        }

        @Test
        fun `a key holding something other than an object is not touched`() {
            assertNull(ensureTerminalPreload(settings(envLinux = "null"), preload))
        }

        @Test
        fun `comments elsewhere survive byte for byte`() {
            val document = settings(
                envLinux = "{\n        \"FOO\": \"bar\"\n    }",
                preamble = "    // the user's note, with a brace { in it\n" +
                    "    /* and \"LD_PRELOAD\" in a block */\n",
            )

            val result = ensureTerminalPreload(document, preload)

            requireNotNull(result) { "a mention inside a comment must not count as the key" }
            assertEquals(document, without(result, "\"LD_PRELOAD\": \"$preload\""))
        }
    }

    @Nested
    inner class Malformed {

        @Test
        fun `a document with no root object is left alone`() {
            assertNull(ensureTerminalPreload("// only a comment\n", preload))
            assertNull(ensureTerminalPreload("", preload))
        }

        @Test
        fun `an object that never closes is left alone`() {
            val document = "{\n    \"terminal.integrated.env.linux\": {\n        \"FOO\": \"bar\"\n"

            assertNull(ensureTerminalPreload(document, preload))
        }
    }
}
