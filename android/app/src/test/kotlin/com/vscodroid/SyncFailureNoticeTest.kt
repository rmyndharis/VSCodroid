package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The notice a folder that did not open raises is assembled from string resources.
 *
 * Android resolves a string resource per locale and resolves a Kotlin literal at
 * compile time, so a sentence built from literals is English for ever and adding a
 * `values-xx/strings.xml` does not reach it. This one is worth pinning because it is
 * the sentence that tells a user their edits are no longer being written back to the
 * device folder, which is the notice they can least afford to be unable to read.
 *
 * `scripts/check-translatable-strings.py` cannot see this: it is a predicate over call
 * shapes and finds a literal only where the literal is written at the sink, while this
 * sentence reached the `Toast` through a local. Its own docstring names that as the
 * hole it cannot close by pattern matching, so the check has to be written where the
 * assembly is.
 *
 * Read from the source, which is the weaker kind of test in this suite: no JVM test can
 * build this Activity, and `getString` is a `Context` call with no seam in front of it.
 * What it buys is that no user-visible half of the sentence is written in Kotlin. It
 * does not check the resources say anything sensible.
 */
class SyncFailureNoticeTest {

    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /** Any string literal carrying a letter, which is the shape a translator needs. */
    private val proseLiteral = Regex("\"([^\"\\\\\\n]|\\\\.)*\"")

    private fun prose(fragment: String): List<String> =
        proseLiteral.findAll(fragment).map { it.value }
            .filter { text -> text.any { it.isLetter() } }
            .toList()

    /**
     * The body of a named method, by brace matching from its declaration.
     *
     * Reading a fixed number of lines is the shape that rots: a body that grows past
     * the window stops being checked and nothing says so.
     */
    private fun methodBody(name: String): String {
        val source = mainActivity.readText()
        val start = source.indexOf("private fun $name(")
        assertTrue(start >= 0) {
            "Could not find `private fun $name(` in ${mainActivity.path}. If it moved or " +
                "was renamed, this test is measuring nothing; point it at the new name " +
                "rather than deleting it."
        }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, i)
            }
        }
        error("Unbalanced braces after `private fun $name(`.")
    }

    /** Each call's argument list, from its parenthesis to the one that closes it. */
    private fun callArguments(name: String): List<String> {
        val source = mainActivity.readText()
        val calls = mutableListOf<String>()
        val declaration = "private fun "
        var from = source.indexOf("$name(")
        while (from >= 0) {
            // The declaration wears the same spelling as a call and is not one.
            val declaredHere = from >= declaration.length &&
                source.startsWith(declaration + name + "(", from - declaration.length)
            if (!declaredHere) {
                val open = source.indexOf('(', from)
                var depth = 0
                for (i in open until source.length) {
                    when (source[i]) {
                        '(' -> depth++
                        ')' -> if (--depth == 0) {
                            calls.add(source.substring(open + 1, i))
                            break
                        }
                    }
                }
            }
            from = source.indexOf("$name(", from + 1)
        }
        return calls
    }

    /**
     * The consequence half, which is the part that says whether the folder still on
     * screen is saving.
     */
    @Test
    fun `the consequence of a folder that did not open comes from a resource`() {
        val body = methodBody("reportSyncFailure")

        assertTrue(body.isNotEmpty() && body.length < 2_000) {
            "the extracted body is ${body.length} characters, which is not one method"
        }
        assertTrue("private fun " !in body) {
            "the brace match ran past the end of the method and into the next one"
        }
        assertTrue(body.contains("R.string.")) {
            "the notice names no resource at all, so nothing below can be a translation"
        }
        assertEquals(
            emptyList<String>(), prose(body),
            "the notice is written in Kotlin, so it stays English in every locale and " +
                "no values-xx/strings.xml can reach it",
        )
    }

    /** The cause half, which each caller supplies. */
    @Test
    fun `the cause of a folder that did not open comes from a resource`() {
        val calls = callArguments("reportSyncFailure")

        assertTrue(calls.size >= 2) {
            "found ${calls.size} calls to reportSyncFailure, and the sync has two ways " +
                "to fail, so this is not reading the call sites"
        }
        assertEquals(
            emptyList<String>(), calls.flatMap { prose(it) },
            "a reason is passed as a Kotlin literal, so that half of the sentence stays " +
                "English in every locale",
        )
    }
}
