package com.vscodroid.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That a refusal leaving through a return value is still something a translator
 * can reach.
 *
 * These sentences are as user-facing as anything in a Toast: `openExternalUrl`
 * and `reclaimSafMirror` answer with the reason they refused, the relay posts it
 * as the error of a rejected promise, and the bundled bridge extension puts it
 * straight into `showErrorMessage`. `SafStorageManager.reclaimRefusal` produces
 * three more that travel the same way. Written as Kotlin they are the same in
 * every locale for ever, so the app would half-translate and the English half
 * would read as an oversight by the translator rather than as a defect here.
 *
 * Nothing else can see this. `check-translatable-strings.py` is a predicate over
 * call shapes and finds a literal only where the literal is written at the sink;
 * its own docstring names a string that reaches the screen through a helper or a
 * return value as the biggest hole it has, and every one of these does. Lint's
 * `HardcodedText`, the other half of that gate, reads layouts only.
 *
 * Read out of the source, because what is being checked is where the text lives
 * rather than what any call returns. The identities stay named constants for the
 * reason their own documentation gives, so this asks only that what they hold is
 * a resource id.
 */
class BridgeRefusalLanguageTest {

    /**
     * The two files that declare a refusal a bridge caller is shown, with the
     * name prefixes each one uses.
     *
     * Prefixes rather than a list of constant names: a refusal added later to
     * either family is covered without anyone remembering this file, which is the
     * failure mode a written list has.
     */
    private val sources = mapOf(
        "src/main/kotlin/com/vscodroid/bridge/AndroidBridge.kt" to
            listOf("OPEN_URL_", "RECLAIM_"),
        "src/main/kotlin/com/vscodroid/storage/SafStorageManager.kt" to
            listOf("RECLAIM_FOLDER_"),
    )

    /**
     * `NAME = value` for every `internal val` or `internal const val` in [text]
     * whose name starts with one of [prefixes], with the value read to the end of
     * the statement.
     *
     * The value may sit on the following line, which is how the longer ones are
     * written, so this takes the rest of the declaration rather than the rest of
     * the line.
     */
    private fun declarations(text: String, prefixes: List<String>): Map<String, String> {
        val pattern = Regex(
            """(?m)^\s*internal (?:const )?val (\w+)\s*=\s*((?:.|\n)*?)(?=\n\s*\n|\n\s*/\*|\n\s*@|\n\s*internal |\n})"""
        )
        return pattern.findAll(text)
            .filter { match -> prefixes.any { match.groupValues[1].startsWith(it) } }
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    @Test
    fun `every refusal the editor renders is a string resource`() {
        val written = mutableListOf<String>()
        var seen = 0

        for ((path, prefixes) in sources) {
            val file = File(path)
            assertTrue(file.isFile) {
                "$path not found at ${file.absolutePath}; this test would otherwise pass " +
                    "by looking at nothing"
            }
            for ((name, value) in declarations(file.readText(), prefixes)) {
                seen += 1
                if (!value.contains("R.string.")) written += "$name in $path = $value"
            }
        }

        // Control, and not a formality: every assertion below is satisfied by an
        // empty scan, so a regex that stopped matching would leave this file
        // reporting green over sentences nobody can translate. Eight is what the
        // two families hold; a refusal added later only raises it.
        assertTrue(seen >= 8) {
            "only found $seen refusal declaration(s); the scan is no longer reading them, " +
                "so this test is measuring nothing"
        }

        assertEquals(
            emptyList<String>(), written,
            "a refusal a bridge caller is shown is written in Kotlin, so it stays English " +
                "whatever language the editor is in. It leaves through a return value and " +
                "is drawn by the bundled extension's showErrorMessage, which is a shape " +
                "check-translatable-strings.py cannot see. Move the sentence into " +
                "res/values/strings.xml and let the constant name the resource.",
        )
    }
}
