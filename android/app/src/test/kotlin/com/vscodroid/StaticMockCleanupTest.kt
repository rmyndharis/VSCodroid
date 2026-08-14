package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `mockkObject` and `mockkStatic` replace their target for the whole process, and Gradle
 * runs this suite in one JVM (`useJUnitPlatform()`, no `forkEvery`, no `maxParallelForks`),
 * so a mock left standing outlives the class that installed it.
 *
 * That is not hypothetical here. `BridgeTokenUniformityTest.tearDown` records the last
 * occurrence: three tests in `CrashReporterTest` failed when the two classes ran together,
 * and the full suite passed only because some class scheduled in between happened to call
 * `unmockkAll()` for its own reasons. Five classes were still missing the teardown when
 * this was written, so the same accident was one scheduling change away from returning.
 *
 * Per-file discipline fixed those five. This is what keeps the sixth from being written:
 * the failure it prevents shows up as an unrelated class failing, in a run that does not
 * name this one.
 */
class StaticMockCleanupTest {

    private val processWideMock = Regex("""\bmockk(Object|Static|Constructor)\s*\(""")

    /**
     * A call, not the word. Matching the bare string accepted a class whose only
     * "unmockk" was in a comment saying it should have one -- which is the exact
     * shape of the mistake this guard exists to catch.
     */
    private val teardown = Regex("""\bunmockk\w*\s*\(""")

    @Test
    fun `every class that mocks process-wide also tears it down`() {
        // Resolved by probing rather than assumed: the working directory of the test JVM is
        // Gradle's business, and a wrong guess here would find zero files and pass.
        val root = listOf("src/test/kotlin", "app/src/test/kotlin", "android/app/src/test/kotlin")
            .map(::File)
            .firstOrNull { it.isDirectory }
        assertTrue(root != null, "test sources not found from ${File("").absolutePath}")

        val sources = root!!.walkTopDown().filter { it.extension == "kt" }.toList()

        // Positive control. Without it a scan that finds nothing reports success, which is
        // the failure mode this whole class exists to argue against.
        assertTrue(
            sources.any { it.name == "StaticMockCleanupTest.kt" },
            "the scan did not find its own source under ${root.absolutePath}, so it is " +
                "looking in the wrong place and would pass no matter what the suite does",
        )

        val offenders = sources
            .filter { file ->
                val text = file.readText()
                processWideMock.containsMatchIn(text) && !teardown.containsMatchIn(text)
            }
            .map { it.name }
            .sorted()

        assertEquals(
            emptyList<String>(), offenders,
            "these classes install a process-wide mock and never remove it, so it is still " +
                "standing when a later class runs: $offenders",
        )
    }
}
