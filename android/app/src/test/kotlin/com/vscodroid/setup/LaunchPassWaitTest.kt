package com.vscodroid.setup

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That every case which starts the launch pass waits for the whole of it.
 *
 * [awaitLaunchPass] gives the reason: the pass runs on the manager's io executor
 * and ends by writing into `filesDir`, which every one of these cases puts in a
 * JUnit `@TempDir`. A case that waits for an earlier effect and returns leaves
 * those writes racing JUnit's deletion of that directory, and the resulting
 * `TempDirDeletionStrategy$DeletionException` fails the whole task on a case
 * whose own assertions passed.
 *
 * Derived rather than listed, because a list cannot notice the next case. This
 * has already been true of two classes at once and neither knew about the other:
 * one waited for the chmod at step four of five and the other for the sweep at
 * step two, and the second is the more exposed of the two while being the one
 * that never failed.
 *
 * Read out of each function's own body rather than off the file, so a single
 * `awaitLaunchPass` somewhere in a class does not vouch for a second case that
 * does not call it.
 *
 * A call at statement position, not the bare name: `LaunchRepairWiringTest`
 * asserts on the wiring by holding the same call inside string literals, which
 * `withoutComments` has no reason to strip and which this would otherwise report
 * as a case that never waits.
 */
class LaunchPassWaitTest {

    private companion object {
        /** The pass being started, at statement position rather than quoted. */
        val STARTS_PASS = Regex("""\n\s+\w+\.repairInstalledToolchains\(\)""")

        /**
         * A case's own declaration, at whatever depth it was written.
         *
         * Any indentation rather than the four spaces of a case sitting directly
         * in its class: a case inside a `@Nested` class is written at eight and
         * was never looked at, so it could start the pass without waiting and
         * this file would say nothing. Every case that starts the pass today is
         * written at four, which is why nothing here noticed.
         *
         * Widening it costs nothing downstream because the match is trimmed
         * before [SourceScan.body] is handed it, and that lookup is by text
         * rather than by offset, so the indentation dropped is not wanted again.
         */
        val DECLARES_CASE = Regex("""\n\s+(?:\w+ )*fun [`\w][^\n(]*\(""")
    }

    @Test
    fun `every case that starts the launch pass waits for the whole of it`() {
        val root = File("src/test/kotlin/com/vscodroid")
        assertTrue(root.isDirectory) {
            "the test sources are not at ${root.absolutePath}, so this case is reading nothing"
        }

        val sites = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val source = SourceScan.withoutComments(SourceScan.read(file.path))
                if (!STARTS_PASS.containsMatchIn(source)) return@flatMap emptySequence()
                DECLARES_CASE
                    .findAll(source)
                    .map { it.value.trim() }
                    .distinct()
                    .map { Triple(file.name, it, SourceScan.body(source, it)) }
                    .filter { (_, _, body) -> STARTS_PASS.containsMatchIn(body) }
            }
            .toList()

        // The control. A scan that matches nothing passes every assertion below
        // it, which is the one way a derived list is weaker than a written one.
        // Three is what the suite holds today, across two classes.
        assertTrue(sites.size >= 3) {
            "found ${sites.size} cases starting the launch pass, so this scan has stopped " +
                "matching them and would pass by looking at nothing"
        }

        sites.forEach { (file, declaration, body) ->
            assertTrue(body.contains("awaitLaunchPass(")) {
                "$file `$declaration` starts the launch pass and does not wait for it. " +
                    "Waiting for the effect it asserts on is not enough: the pass goes on " +
                    "writing into the @TempDir after the case returns, and JUnit's deletion " +
                    "of that directory then fails the whole task"
            }
        }
    }

    // --- The scan itself, driven against a source whose answer is known. -----
    //
    // The case above derives its list from the suite, and every case in the
    // suite that starts the pass is written directly in its class today. A scan
    // that reads only that depth passes it while looking at one shape less than
    // it claims, and goes on passing until someone puts a `@Nested` class around
    // the next one, which is when the guard is wanted rather than before.
    //
    // Assembled from lines rather than written as a raw string, so the call it
    // holds never begins a line of this file. [STARTS_PASS] reads a call at
    // statement position and has no reason to care that it is inside a literal,
    // which is the same trap the docstring names `LaunchRepairWiringTest` for:
    // written the other way, this file becomes a case that starts the pass and
    // never waits for it.
    private val nestedCase = listOf(
        "class Outer {",
        "    @Nested",
        "    inner class Inner {",
        "        @Test",
        "        fun `repairs on launch`() {",
        "            manager.repairInstalledToolchains()",
        "        }",
        "    }",
        "}",
    ).joinToString("\n")

    @Test
    fun `a case inside a nested class is read as a case that starts the pass`() {
        val found = DECLARES_CASE.findAll(nestedCase)
            .map { it.value.trim() }
            .filter { STARTS_PASS.containsMatchIn(SourceScan.body(nestedCase, it)) }
            .toList()

        assertTrue(found.isNotEmpty()) {
            "a case written inside a @Nested class is not seen by the declaration scan, " +
                "so it can start the launch pass without ever being asked whether it waits " +
                "for the whole of it"
        }
    }
}
