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

    private val safCoordinator = File("src/main/kotlin/com/vscodroid/MainActivitySafCoordinator.kt")

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
    private fun methodBody(name: String): String =
        SourceScan.body(SourceScan.read(safCoordinator.path), "internal fun MainActivity.$name(")

    /** Each call's argument list across all MainActivity source files, from `(` to `)`. */
    private fun callArguments(name: String): List<String> {
        val source = SourceScan.withoutComments(SourceScan.readAll(SourceScan.MAIN_ACTIVITY_FILES))
        val calls = mutableListOf<String>()
        val declarationPrefixes = listOf(
            "internal fun MainActivity.",
            "private fun ",
            "internal fun ",
            "fun MainActivity.",
            "fun ",
        )
        var from = source.indexOf("$name(")
        while (from >= 0) {
            val prevChar = if (from > 0) source[from - 1] else ' '
            val wordBoundary = !(prevChar.isLetterOrDigit() || prevChar == '_')
            val declaredHere = declarationPrefixes.any { prefix ->
                from >= prefix.length && source.startsWith(prefix + name + "(", from - prefix.length)
            }
            if (wordBoundary && !declaredHere) {
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
        assertTrue("internal fun " !in body) {
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

    @Test
    fun `a folder refused as busy is reported from a resource and not from the exception message`() {
        val source = safCoordinator.readText()
        val start = source.indexOf("catch (e: FolderBusyException)")
        assertTrue(start >= 0) { "the busy branch is gone or renamed, so this measures nothing" }
        val end = source.indexOf("} catch (e: Exception)", start)
        assertTrue(end > start) { "could not bound the busy branch" }
        val branch = source.substring(start, end).lines()
            .filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

        assertTrue("R.string.saf_sync_busy" in branch) {
            "the busy branch no longer names its own resource"
        }
        // FolderBusyException's message is an English sentence, so passing it on prints a
        // translated lead-in with an English tail.
        assertTrue(".message" !in branch) { "the busy branch reports the exception's own message" }
    }

    /**
     * The cause half, which each caller supplies.
     *
     * The count is the guard against this passing vacuously: with no call sites found,
     * there are no arguments to check and the claim below is green over nothing. It
     * counts only sites the compiler sees, so a site that has been commented out fails
     * it rather than standing in for the live one it replaced.
     */
    @Test
    fun `the cause of a folder that did not open comes from a resource`() {
        val calls = callArguments("reportSyncFailure")

        assertTrue(calls.size >= 2) {
            "found ${calls.size} live calls to reportSyncFailure, and the sync has two " +
                "ways to fail, so either this is not reading the call sites or one of " +
                "them no longer runs and that failure now reaches nobody"
        }
        assertEquals(
            emptyList<String>(), calls.flatMap { prose(it) },
            "a reason is passed as a Kotlin literal, so that half of the sentence stays " +
                "English in every locale",
        )
    }

    /**
     * The catch-all's sentence takes the exception's message, and a message is null for
     * many exceptions, so the user read "Failed to open folder: null."
     */
    @Test
    fun `the sentence for a folder that failed to open is never handed a null`() {
        val calls = callArguments("getString").filter { it.trimStart().startsWith("R.string.saf_sync_failed,") }

        assertEquals(1, calls.size) {
            "expected the one live use of saf_sync_failed, found ${calls.size}; this measures nothing"
        }
        val detail = calls.single().substringAfter(',').trim()
        assertTrue("?:" in detail) {
            "`$detail` can be null, and the sentence then ends in \"null\""
        }
    }
}
