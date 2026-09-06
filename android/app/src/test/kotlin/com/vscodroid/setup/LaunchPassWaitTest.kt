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
                Regex("""\n    (?:\w+ )*fun [`\w][^\n(]*\(""")
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
}
