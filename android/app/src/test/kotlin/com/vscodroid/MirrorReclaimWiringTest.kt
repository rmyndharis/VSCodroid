package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The removal guard in `MainActivity` asks every question it has to ask.
 *
 * `SafStorageManager.reclaimRefusal` is the rule and `MirrorInUseTest` pins it against
 * each input separately. What no JVM test can reach is the wiring: whether the Activity
 * actually hands it the state it holds. A guard supplied three of its four inputs and
 * `null` for the fourth answers "nothing is using this mirror" for a mirror that is, and
 * the consequence is not a wrong dialog. The mirror's observers are live, a
 * `FileObserver.DELETE` becomes a DELETE write-back job, and `deleteFromSaf` replays
 * every delete onto the user's real documents through the provider.
 *
 * So this reads the source, which is the weaker kind of test in this suite and is worth
 * naming for what it does and does not buy. It buys the presence of each input at the
 * call site; there is no seam to extract instead, because the fields are the Activity's
 * own and building one on the JVM is not possible. It does not check that the value
 * passed is the right one, only that the field is named.
 *
 * `beginWatching` is checked in the same file because it is the other half of the same
 * property: the guard's widest input is a set, and a site that starts a watcher without
 * adding to it leaves the guard believing a mirror was never touched.
 */
class MirrorReclaimWiringTest {

    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /**
     * The body of a named method, by brace matching from its declaration.
     *
     * Reading to the end of some fixed number of lines was the alternative and it is
     * the shape that rots: a body that grows past the window stops being checked and
     * nothing says so.
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

    /**
     * [body] with its comments blanked, so a name that survives only in prose or
     * in a call somebody switched off cannot answer for a live one.
     *
     * Not optional here. Commenting a line out is how a call gets disabled while
     * something is being debugged, and it leaves every character of that call in
     * the file; the guard below asks whether a name is present, and raw text
     * answers yes for the disabled call exactly as it does for the live one. The
     * body it reads also explains the guard in prose, so the names are in reach
     * of a search twice over.
     *
     * Applied to the extracted body rather than to the whole file: [methodBody]
     * counts braces raw, and blanking a `//` inside a string literal elsewhere in
     * this activity would take its closing brace with it and truncate the span.
     */
    private fun code(body: String) = body
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    @Test
    fun `the removal guard is given every piece of state that decides it`() {
        val body = code(methodBody("removeDeviceFolderCopy"))

        val missing = listOf(
            "watchedSafFolder",
            "syncingFolder",
            "openWorkspaceFolder",
            "mirrorsWatchedThisProcess",
            "reclaimRefusal",
        ).filterNot { body.contains(it) }

        assertEquals(
            emptyList<String>(), missing,
            "the removal guard never reads $missing, so a mirror in use by that route is " +
                "reported as free and deleting it replays every delete onto the user's " +
                "device documents",
        )
    }

    /**
     * And it obeys the answer it is given.
     *
     * The case above is satisfied by the call existing. A `reclaimRefusal` whose result
     * is dropped names every one of those fields and reads as correct at a glance, and
     * deleting the line that returns the refusal leaves the whole suite green. What that
     * costs is the whole point of the rule: the mirror the rule refused goes straight to
     * `reclaimMirror`, `force` deletes it, and its live observers replay every one of
     * those deletes onto the user's device documents.
     *
     * Read from the source for the reason the header of this file gives. The two
     * spellings below are the ones it recognises; a third fails loudly, and the answer
     * to that is to add it here rather than to loosen the pattern until anything passes.
     */
    @Test
    fun `a refused removal is returned before anything is deleted`() {
        val body = methodBody("removeDeviceFolderCopy")

        val refusal = Regex("""(?m)^\s*val (\w+) = SafStorageManager\.reclaimRefusal\(""")
            .find(body)
        assertTrue(refusal != null) {
            "the guard does not keep what reclaimRefusal answers, so there is no answer " +
                "for it to obey"
        }
        val answer = refusal!!.groupValues[1]

        // Anchored at the start of a line, so a return that has been commented out
        // cannot satisfy it. Commenting a line out is how a developer disables
        // something, and an unanchored search cannot tell that from live code.
        val enforced = Regex(
            """(?m)^\s*(?:if \($answer != null\)\s*\{?\s*return $answer\b""" +
                """|$answer\?\.let \{ return it \})"""
        ).find(body)
        assertTrue(enforced != null) {
            "the guard asks reclaimRefusal and then ignores what it says, so a mirror " +
                "that is in use is removed anyway and every delete inside it is replayed " +
                "onto the user's device documents"
        }

        val removal = body.indexOf("safManager.reclaimMirror(")
        assertTrue(removal >= 0) {
            "the removal no longer goes through safManager.reclaimMirror, so the order " +
                "checked below is measuring nothing"
        }
        assertTrue(enforced!!.range.first < removal) {
            "the refusal is returned only after the removal has already happened, which " +
                "is not a refusal"
        }
    }

    /**
     * The extraction's own control. Every name above also appears elsewhere in this
     * activity, so a brace match that overran into the rest of the file would satisfy
     * the assertion above while checking nothing. `openSafFolder` is the neighbouring
     * method that writes all four of those fields; its name must not be inside this
     * body.
     */
    @Test
    fun `the guard body is bounded to the guard`() {
        val body = methodBody("removeDeviceFolderCopy")

        assertTrue(body.isNotEmpty() && body.length < 4_000) {
            "the extracted body is ${body.length} characters, which is not one method"
        }
        assertTrue("private fun " !in body) {
            "the brace match ran past the end of the method and into the next one"
        }
    }

    /**
     * Starting a watcher records the mirror for the rest of the process, and it has to
     * be one method rather than a habit at each site.
     *
     * `stopWatching` waits two seconds for the write-back worker and then leaves it
     * running rather than discarding writes the user expects on the device, so a drain
     * can still be inside a mirror the app considers closed, and each of its writes
     * opens the device document with `"wt"`, which truncates at open. The set is what
     * puts such a mirror out of reach of a removal; a site that assigned
     * `watchedSafFolder` without adding to it would put it back in reach, and nothing
     * about that site would look wrong.
     */
    @Test
    fun `every watcher this activity starts is recorded for the whole process`() {
        val source = mainActivity.readText()

        // Judged by WHERE the assignment is, not by what it says. Matching the text
        // instead was tried and does not bind: the permitted spelling is the same
        // spelling a site that skips `beginWatching` would use, so the allowlist
        // admitted exactly the mutation it exists to catch. Position cannot be
        // spoofed that way.
        val begin = methodBody("beginWatching")
        val beginAt = source.indexOf(begin)
        val insideBeginWatching = beginAt until (beginAt + begin.length)

        val nonNull = Regex("""watchedSafFolder\s*=\s*([^\n]+)""").findAll(source)
            .filterNot { it.groupValues[1].trim().startsWith("null") }
            .toList()

        assertTrue(nonNull.isNotEmpty()) {
            "no assignment setting watchedSafFolder to a folder was found, so this test " +
                "is checking nothing"
        }

        val outside = nonNull
            .filterNot { it.range.first in insideBeginWatching }
            .map { it.value.trim() }
        assertEquals(
            emptyList<String>(), outside,
            "these set the watched folder without going through beginWatching: $outside. " +
                "A mirror watched but not recorded can be removed while a write-back " +
                "drain is still streaming out of it, and each of that drain's writes " +
                "truncates the device document at open.",
        )

        // Anchored at the start of a line, for the reason `code()` exists: a
        // recording that has been commented out leaves its own text behind, and a
        // substring search reads the disabled line as the live one. The call is a
        // statement of its own in `beginWatching`, so the anchor matches what is
        // written today and refuses the same line behind a `//`.
        assertTrue(Regex("""(?m)^\s*mirrorsWatchedThisProcess\.add\(""").containsMatchIn(begin)) {
            "beginWatching no longer records the mirror, so the record is empty and the " +
                "guard's widest check passes everything"
        }
    }
}
