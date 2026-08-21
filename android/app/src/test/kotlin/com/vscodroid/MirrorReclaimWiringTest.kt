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

    @Test
    fun `the removal guard is given every piece of state that decides it`() {
        val body = methodBody("removeDeviceFolderCopy")

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

        assertTrue(begin.contains("mirrorsWatchedThisProcess.add")) {
            "beginWatching no longer records the mirror, so the record is empty and the " +
                "guard's widest check passes everything"
        }
    }
}
