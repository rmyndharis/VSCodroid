package com.vscodroid.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the two ends of the adoption note agree on what they are writing and reading.
 *
 * `assets/server.js` writes `{pid, port}` into a file next to itself; Kotlin reads
 * it back to decide whether the process holding the port is an editor server this
 * app started. Nothing but agreement makes that work, and nothing but this file
 * checks the agreement: `AdoptionTest` builds its own fixture from the same
 * literals the reader uses, so reader and fixture can be right together while the
 * writer is wrong.
 *
 * That gap was measured rather than assumed. Renaming the file to `editor.pid` and
 * the keys to `processId`/`listenPort` in `server.js` leaves all 818 tests green,
 * while on a device `portHeldByOurEditorServer` returns false for ever, so every
 * launch after a bootstrap dies spawns onto a port the surviving server still
 * holds, which is the hang `LaunchOutcome.CANNOT_BIND` exists to catch. A silent
 * loss of the feature, with the whole suite agreeing it was fine.
 *
 * The same shape was already fixed once here for the memory-pressure severity
 * word, and the reasoning is identical: a value that crosses a language boundary
 * has no compiler, so it needs a test that reads both sides.
 *
 * This reads source text, which is the weaker kind of test. What it buys is the
 * only property at issue, that the same spelling appears on both sides. What it
 * does not buy: it cannot tell that the writer runs, that the JSON parses, or that
 * the file lands in the directory the reader looks in. `Environment.getServerDir`
 * and `server.js`'s own `SERVER_DIR` are pinned by the third case below, but only
 * as far as both naming the same subdirectory.
 */
class AdoptionNoteWireTest {

    private val serverJs = File("src/main/assets/server.js")

    private fun source(): String {
        assertTrue(serverJs.isFile) {
            "Could not read ${serverJs.absolutePath}. If the bootstrap moved, point this " +
                "test at it rather than deleting it, the wire it checks has no other guard."
        }
        return serverJs.readText()
    }

    /**
     * The `try` block that writes the note, from the file name to its `catch`.
     *
     * Scoped rather than whole-file, because the questions below are about what
     * gets serialised and the warning inside that `catch` repeats the word `pid`
     * in prose: `'Could not record the editor server pid: '`. A search over the
     * whole file is answered by that sentence, so renaming the JSON key while
     * leaving the sentence alone left the check green over a note the reader can
     * no longer use. The sentence is outside this window on purpose.
     */
    private fun pidNoteWrite(): String {
        val js = source()
        val start = js.indexOf(EDITOR_PID_FILE)
        assertTrue(start >= 0) {
            "assets/server.js no longer names \"$EDITOR_PID_FILE\", so there is no write " +
                "to scope this to. The case above reports the same thing on its own terms."
        }
        val end = js.indexOf("} catch", start)
        return js.substring(start, if (end < 0) js.length else end)
    }

    @Test
    fun `the bootstrap writes the file name Kotlin reads`() {
        assertTrue(source().contains(EDITOR_PID_FILE)) {
            "assets/server.js does not mention \"$EDITOR_PID_FILE\", which is the name " +
                "ProcessManager.portHeldByOurEditorServer opens. If one side was renamed, " +
                "adoption is dead: the reader finds no note, falls through to a spawn onto " +
                "a port the surviving server still holds, and nothing else goes red."
        }
    }

    @Test
    fun `the bootstrap writes the JSON keys Kotlin reads`() {
        // The reader asks for these two by name (optInt("pid"), optInt("port")), and
        // a missing key answers 0 rather than throwing, so a renamed key reads as
        // "no pid" and declines adoption silently, which is the failure this pins.
        //
        // Asked of the object that is serialised, not of the file. Searching the
        // whole of server.js for `pid:` is answered by the warning three lines
        // below the write, `'Could not record the editor server pid: '`, so
        // renaming the key while leaving that sentence alone kept this green over
        // a note ProcessManager can no longer read. The literal is located from
        // the JSON.stringify that feeds the write, which is the only thing whose
        // shape the reader depends on.
        val note = Regex("""JSON\.stringify\(\s*\{([^}]*)}""")
            .find(pidNoteWrite())
            ?.groupValues?.get(1)
            ?: fail(
                "assets/server.js no longer serialises an object literal into the pid " +
                    "note. If the note is now built somewhere else, point this at it; " +
                    "ProcessManager.portHeldByOurEditorServer still reads it with " +
                    "optInt(\"pid\") and optInt(\"port\").",
            )

        // Split into keys rather than searched, because a search inside the literal
        // has the same weakness one line down: in `{ pid: server.pid, port: PORT }`
        // the VALUE ends in `.pid`, so a pattern looking for the word would be
        // answered by it and a key renamed to `processId` would still read as
        // present. Only the name before the colon is a key, and a shorthand
        // `{ pid, port }` has no colon and is the whole segment.
        val keys = note.split(",").map { it.substringBefore(":").trim() }

        for (key in listOf("pid", "port")) {
            assertTrue(key in keys) {
                "the pid note carries no \"$key\" key; it serialises {$note}, " +
                    "whose keys are $keys. " +
                    "ProcessManager reads it with optInt(\"$key\"), which answers 0 for " +
                    "a key that is absent, so this fails as a refusal to adopt rather " +
                    "than as an error."
            }
        }
    }

    @Test
    fun `both sides put the note in the server directory`() {
        // Kotlin: File(Environment.getServerDir(context), EDITOR_PID_FILE), and
        // getServerDir is "${filesDir}/server". JS: path.join(SERVER_DIR, ...),
        // where SERVER_DIR is path.dirname(__filename) and server.js is extracted
        // into files/server. Neither half can be checked from the other, so this
        // asserts the two spellings that make them meet.
        assertTrue(source().contains("path.join(SERVER_DIR")) {
            "assets/server.js no longer joins the note onto SERVER_DIR. If it writes " +
                "elsewhere, Kotlin looks in files/server and finds nothing."
        }
        val environment = File("src/main/kotlin/com/vscodroid/util/Environment.kt")
        assertTrue(environment.readText().contains("\${context.filesDir}/server")) {
            "Environment.getServerDir no longer resolves to filesDir/server, which is " +
                "where server.js is extracted and therefore where it writes the note."
        }
    }

    @Test
    fun `the bootstrap clears the note when the editor server exits`() {
        // Without this the note outlives the process it names, and a pid recycled
        // into another editor server, a debug build beside a release one, say,
        // could be adopted on the strength of a stale record.
        //
        // The CALL is what is asked for, anchored to the start of a line, and
        // both halves of that are load-bearing. Searching for the bare name was
        // answered by the `const clearPidFile = () => {` that defines it, so the
        // exit handler could stop calling it and nothing here would notice; and
        // an unanchored search for the call reads `// clearPidFile();` exactly as
        // it reads the live statement, which is how a line gets switched off.
        assertTrue(Regex("""(?m)^\s*clearPidFile\(\)""").containsMatchIn(source())) {
            "assets/server.js no longer clears the note on the child's exit. A note that " +
                "outlives its process is a note that vouches for the wrong one."
        }
    }
}
