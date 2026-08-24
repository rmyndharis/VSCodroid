package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every preference write in FirstRunSetup commits.
 *
 * The class suppresses the ApplySharedPref lint on the statement that every
 * write there uses `commit()` on purpose: each records a step of a run a kill
 * can interrupt at any moment, and `apply()`'s flush window is the interval
 * those records exist to survive. Two writes had drifted to the KTX default,
 * which is `apply()`: the retired-extension sweep record, written right after
 * the delete it stands for, and the bundled-id record the next upgrade reads
 * to tell an uninstall from a first bundling. A reader trusting the header
 * reasoned about the manifest and record pairing on a guarantee two of the
 * keys did not have.
 *
 * Source reading, over the statements rather than the lines: the KTX form
 * carries its choice as an argument on the opening call, so a scan for
 * `.apply()` finds nothing either way.
 *
 * NEGATIVE CONTROL: drop `commit = true` from `rememberBundledIds` and the first
 * assertion names the statement.
 */
class FirstRunPrefsCommitTest {

    private val source = File("src/main/kotlin/com/vscodroid/setup/FirstRunSetup.kt")

    /** Every `prefs.edit` statement, comments dropped so prose cannot satisfy the scan. */
    private fun editStatements(): List<String> {
        check(source.isFile) {
            "FirstRunSetup.kt not found at ${source.absolutePath}; this test would " +
                "otherwise pass by looking at nothing"
        }
        val code = source.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.joinToString("\n")
        return Regex("""prefs\.edit\s*(?:\([^)]*\))?\s*\{""").findAll(code).map { it.value }.toList()
    }

    @Test
    fun `every preference write commits`() {
        val applying = editStatements().filterNot { it.contains("commit = true") }
        assertTrue(applying.isEmpty()) {
            "these writes take the KTX default, which is apply(), under a header that " +
                "promises commit():\n${applying.joinToString("\n")}"
        }
    }

    @Test
    fun `the scan sees the writes it is about`() {
        // The control: a regex that matched nothing would pass the case above
        // with every write applying.
        val statements = editStatements()
        assertTrue(statements.size >= 5) {
            "only ${statements.size} prefs.edit statement(s) found; the scan has lost the " +
                "shape of the call it reads"
        }
    }
}
