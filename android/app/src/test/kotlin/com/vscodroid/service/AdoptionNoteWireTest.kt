package com.vscodroid.service

import org.junit.jupiter.api.Assertions.assertTrue
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
 * while on a device `portHeldByOurEditorServer` returns false for ever — so every
 * launch after a bootstrap dies spawns onto a port the surviving server still
 * holds, which is the hang `LaunchOutcome.CANNOT_BIND` exists to catch. A silent
 * loss of the feature, with the whole suite agreeing it was fine.
 *
 * The same shape was already fixed once here for the memory-pressure severity
 * word, and the reasoning is identical: a value that crosses a language boundary
 * has no compiler, so it needs a test that reads both sides.
 *
 * This reads source text, which is the weaker kind of test. What it buys is the
 * only property at issue — that the same spelling appears on both sides. What it
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
                "test at it rather than deleting it -- the wire it checks has no other guard."
        }
        return serverJs.readText()
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
        // a missing key answers 0 rather than throwing -- so a renamed key reads as
        // "no pid" and declines adoption silently, which is the failure this pins.
        val js = source()
        for (key in listOf("pid", "port")) {
            assertTrue(Regex("""\b$key\s*:""").containsMatchIn(js)) {
                "assets/server.js writes no \"$key\" key. ProcessManager reads it with " +
                    "optInt(\"$key\"), which answers 0 for a key that is absent, so this " +
                    "fails as a refusal to adopt rather than as an error."
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
        // into another editor server -- a debug build beside a release one, say --
        // could be adopted on the strength of a stale record.
        assertTrue(source().contains("clearPidFile")) {
            "assets/server.js no longer clears the note on the child's exit. A note that " +
                "outlives its process is a note that vouches for the wrong one."
        }
    }
}
