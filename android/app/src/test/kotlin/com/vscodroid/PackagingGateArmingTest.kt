package com.vscodroid

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A packaging gate is armed by a predicate, and the predicate has to cover the
 * gate's whole subject.
 *
 * `checkPackOverlap` runs `scripts/check-pack-overlap.py`, which sweeps every
 * pack's assets directory under `android/toolchain_<name>` against the base
 * module's. It was armed by
 * `rubyPackHoldsPayload()`, a predicate borrowed from the Ruby-only shell sweep
 * beside it, which asks whether ONE pack has been downloaded. Both payload trees
 * are gitignored, so a checkout that has run `download-java.sh` and not
 * `download-ruby.sh` is an ordinary state to work in, and in it the predicate is
 * false: a local `./gradlew bundleRelease` skipped the overlap gate with no
 * output and packaged the Java pack without ever comparing it against the base
 * module. The duplicate entry that gate exists to catch early then surfaces when
 * bundletool assembles the whole thing, which is the late failure it was added
 * to move forward.
 *
 * Kotlin cannot express "this predicate covers that script's subject", and a
 * unit test cannot execute a build-script function, so the arming is read out of
 * the build script as text. What is asserted is the relation that broke: a gate
 * whose subject is the family of packs may not be armed by a predicate that
 * names one member of it. Dropping the `onlyIf` altogether is the other correct
 * answer and passes here, because an unconditional gate covers every pack by
 * construction.
 *
 * These tests see the build script only because `tasks.withType<Test>` declares
 * it an input; without that line an edit to it leaves the task UP-TO-DATE and
 * every mutation below passes.
 *
 * NEGATIVE CONTROL: restore `onlyIf { rubyPackHoldsPayload() }` in the
 * `checkPackOverlap` block and the first test goes red, naming toolchain_ruby.
 * Point `verifyRubyPackShellPaths` at the family predicate instead and the
 * second goes red, which is what stops this file being read as "no gate may name
 * a pack".
 */
class PackagingGateArmingTest {

    // Unit tests run with app/ as the working directory.
    private val buildScript = File("build.gradle.kts")

    /**
     * The body of one `tasks.register<...>("name") { ... }` block.
     *
     * Ended on a closing brace in the first column, which is where a top-level
     * registration's is and where nothing inside one can be.
     */
    private fun taskBody(name: String): String {
        val script = buildScript.readText()
        val body = script.substringAfter("(\"$name\") {", "").substringBefore("\n}")
        assertTrue(body.isNotEmpty(), "the $name task is gone from build.gradle.kts")
        return body
    }

    /**
     * The predicate a task is armed by, or null when it is armed by nothing.
     *
     * Anchored at the start of a line: `build.gradle.kts` is Kotlin, so a
     * commented-out `onlyIf` is not an arming and must not be read as one.
     */
    private fun armingPredicate(task: String): String? =
        Regex("""(?m)^\s*onlyIf\s*\{\s*(\w+)\(\)\s*}""").find(taskBody(task))?.groupValues?.get(1)

    /**
     * The declaration of a top-level predicate function, comments stripped.
     *
     * The comments go because this reads the body for the names of packs, and a
     * comment explaining which pack a predicate no longer singles out would
     * otherwise be read as it singling that pack out.
     */
    private fun predicateBody(name: String): String {
        val body = buildScript.readText()
            .substringAfter("fun $name()", "").substringBefore("\n\n")
        assertTrue(body.contains("Boolean"), "$name() is not declared in build.gradle.kts")
        return body.lines().joinToString("\n") { it.substringBefore("//") }
    }

    /** Every pack this build packages, by module name. */
    private fun packedModules(): List<String> {
        val list = Regex("""(?m)^\s*assetPacks\s*\+=\s*listOf\(([^)]*)\)""")
            .find(buildScript.readText())?.groupValues?.get(1)
        assertTrue(list != null, "build.gradle.kts no longer names the asset packs it packages")
        val modules = Regex(""""\s*:(toolchain_\w+)\s*"""").findAll(list!!)
            .map { it.groupValues[1] }.toList()
        assertTrue(
            modules.size > 1,
            "only ${modules.size} asset pack(s) are packaged, so a predicate naming one pack " +
                "and a predicate naming the family cannot be told apart by anything below"
        )
        return modules
    }

    @Test
    fun `the overlap gate is not armed by a predicate that one pack can answer for`() {
        val predicate = armingPredicate("checkPackOverlap") ?: return
        val body = predicateBody(predicate)
        for (module in packedModules()) {
            assertFalse(
                body.contains(module),
                "checkPackOverlap sweeps every asset pack, but it is armed by $predicate(), " +
                    "which asks about $module alone. A checkout that has downloaded another " +
                    "pack and not this one skips the gate entirely and bundles that pack " +
                    "without comparing it against the base module."
            )
        }
        assertTrue(
            body.contains("toolchain_"),
            "$predicate() no longer looks at the toolchain packs at all, so what arms " +
                "checkPackOverlap is unrelated to whether there is a pack to compare"
        )
    }

    @Test
    fun `the Ruby shell sweep keeps the predicate its own subject needs`() {
        val predicate = armingPredicate("verifyRubyPackShellPaths")
        assertTrue(
            predicate != null,
            "verifyRubyPackShellPaths is armed by nothing, so a checkout that has never run " +
                "download-ruby.sh sweeps a pack holding only its manifest and reports it clean"
        )
        assertTrue(
            predicateBody(predicate!!).contains("toolchain_ruby"),
            "verifyRubyPackShellPaths checks the Ruby pack and nothing else, deliberately: the " +
                "Java pack carries the same path in files that are unreachable on Android. " +
                "Arming it by $predicate(), which answers for the family, fails builds that " +
                "are correct."
        )
    }
}
