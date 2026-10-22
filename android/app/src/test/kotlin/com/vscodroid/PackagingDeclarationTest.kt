package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Declarations in `build.gradle.kts` that decide whether the app works at all,
 * and that nothing else in this project could see.
 *
 * Both are settings rather than code, so there is no compiler relationship and
 * no runtime seam to reach them through: the first decides how the Package
 * Manager treats every bundled binary, and the answer only shows up on a device;
 * the second decides which suites re-run at all after an edit, and a wrong
 * answer there is silent by construction. So the
 * build script is read as text, which is
 * the same tool `NoticesTest` and `PackagingGateArmingTest` use for the same
 * reason, and it works only because `tasks.withType<Test>` declares the script
 * an input. Without that line an edit to it leaves this task UP-TO-DATE and
 * every mutation below passes.
 *
 * The `assetPacks` declaration is a third such setting and is checked in
 * `ToolchainRegistryTest`, beside the catalog it has to agree with. That copy
 * also holds `settings.gradle.kts` to the same set, which a copy here did not,
 * so the question is asked once and in the stronger place.
 */
class PackagingDeclarationTest {

    // Unit tests run with app/ as the working directory.
    private val buildScript = File("build.gradle.kts")

    /**
     * The whole `useLegacyPackaging` question, which is the one setting the
     * entire binary-bundling strategy rests on and which had no check of any
     * kind.
     *
     * AGP defaults it to false, and false means the `.so` files are stored
     * uncompressed inside the APK and loaded from there rather than unpacked to
     * a real directory. This app does not load its binaries, it EXECUTES them:
     * `Environment` builds `applicationInfo.nativeLibraryDir + "/libnode.so"`
     * and the same for bash, the trampoline and every other bundled tool, and
     * an entry inside a zip is not a path `execve` can take. Turning it off
     * therefore does not degrade anything; it produces an APK that installs,
     * opens, and cannot start the server, the terminal, git or a toolchain.
     *
     * Line-anchored because `build.gradle.kts` is Kotlin: a commented-out
     * setting is not a setting, and a substring search reads one as though it
     * were, which is exactly the state this exists to refuse.
     *
     * NEGATIVE CONTROL: comment out the `useLegacyPackaging = true` line, or set
     * it to false, and this goes red.
     */
    @Test
    fun `jniLibs are packaged the legacy way, so the binaries land in a real directory`() {
        val script = buildScript.readText()
        val packaging = script.substringAfter("\n    packaging {", "")
            .substringBefore("\n    }")
        assertTrue(
            packaging.isNotEmpty(),
            "build.gradle.kts has no android { packaging { ... } } block, so " +
                "useLegacyPackaging is whatever AGP defaults to"
        )
        assertTrue(
            Regex("""(?m)^\s*useLegacyPackaging\s*=\s*true\s*$""").containsMatchIn(packaging),
            "jniLibs.useLegacyPackaging is not set to true. AGP then stores the " +
                "bundled binaries inside the APK instead of unpacking them, and " +
                "nativeLibraryDir stops naming a directory anything can execve. " +
                "The APK still installs and opens; nothing in it runs."
        )
    }

    /**
     * Every repository file a unit test opens by hand is declared an input of
     * the test task.
     *
     * The suites here reach outside the module on purpose: a patch, a bundled
     * extension manifest, a licence text, a document restating a rule the code
     * owns. Kotlin compiles against none of them, so Gradle has no relationship
     * to them and answers UP-TO-DATE for a run that had to notice one change,
     * serving the previous run's results back green. That is not a slow test, it
     * is a test that did not run on the one edit it exists to catch, and an
     * incremental run is where it happens. `MILESTONES.md` and
     * `branding/product.json` were each read by a suite for exactly that reason
     * and declared nowhere.
     *
     * Read as text, matching any string literal that climbs out of the module and
     * names something that exists, however the suite then opens it: a `File(...)`,
     * a constant, a list of paths handed to a reader. The pattern is spelled here
     * only as an escaped string so this file is not its own match. BOTH depths,
     * because both are used: two levels reaches the repository root, one reaches
     * `android/`, and `ToolchainRegistryTest` opens `../settings.gradle.kts` that
     * way. What is captured is the remainder after the climb, so the two shapes
     * are compared the same way. A literal that names nothing on disk, or
     * something outside the repository, is test data, such as the traversal
     * strings the path-confinement suites feed to the code under test (one of
     * them is `/etc/passwd` when the checkout sits directly under `/`), and is
     * not a read. There is no other seam: the declaration is build
     * configuration, and the JVM cannot ask Gradle what it thinks its inputs are.
     * Sources under `android/app/src/main/kotlin` are exempt, and only those: they
     * compile into the classpath this task already depends on, so an edit re-runs
     * the suite with nothing declared.
     *
     * A path is declared when the block spells it out as a string, or spells out a
     * directory above it. Matching the first segment alone, as this once did,
     * counted any read under `scripts/` as declared because two named scripts
     * happen to sit beside the word, and `verify-server-tree.py` and
     * `CHANGELOG.md` were each read by a suite and declared nowhere. A directory
     * counts as a whole even where its declaration filters it (`docs` is
     * declared for `*.md`), so a reader that reaches a NEW corner of a declared
     * directory passes: widen the check the day a suite reads one.
     *
     * An interpolated literal is not resolved. It is judged by the text before its
     * first `$`, which has to open a string in the block, as `../../scripts/download-$name.sh`
     * is covered by the three scripts it can name. One that spells nothing before the `$`
     * is dropped, and the only one is the pack payload `../$packName/src/main/assets/usr`,
     * which a download script writes and git ignores: a source checkout does not carry it,
     * and declaring it would hash a 150 MB tree on every run of every suite.
     *
     * A reader that keeps a base directory and resolves names off it is invisible
     * here, and `NoticesTest` is one; its paths are covered because another suite
     * opens them by literal. A new reader of that shape needs its declaration
     * checked by hand.
     *
     * NEGATIVE CONTROL: delete the `MILESTONES.md` line from the
     * `statedRequirements` declaration, or the `verify-server-tree.py` or
     * `CHANGELOG.md` declaration, and this goes red naming it. Against the
     * first-segment comparison the last two passed.
     */
    @Test
    fun `every repository file a test opens is an input of the test task`() {
        val script = buildScript.readText()
        // Comment lines dropped before anything is searched for. That block
        // explains each declaration by naming the path it covers, so a substring
        // search over the raw text is answered by the prose and passes with the
        // declaration itself deleted, which is the state this exists to refuse.
        val inputs = script.substringAfter("\ntasks.withType<Test> {", "")
            .substringBefore("\n}")
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(
            inputs.contains("inputs."),
            "build.gradle.kts has no tasks.withType<Test> block declaring inputs, so " +
                "every suite that reads a repository file is served stale results on an " +
                "incremental run"
        )

        val repoRoot = File("../..").canonicalFile.toPath()
        val read = File("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { Regex("\"((?:\\.\\./)+)([^\"$]+)\"").findAll(it.readText()) }
            .map { File(it.groupValues[1] + it.groupValues[2]) to it.groupValues[2] }
            .filter { (file, _) -> file.exists() && file.canonicalFile.toPath().startsWith(repoRoot) }
            .map { (_, remainder) -> remainder }
            // The bare `../..` NoticesTest keeps as a base directory names no file this
            // can ask about.
            .filterNot { it.startsWith("..") }
            .toSortedSet()
        assertTrue(
            read.isNotEmpty(),
            "no test opens a repository file by a literal that climbs out of the module " +
                "any more, so this compared nothing. Find the shape the readers use now " +
                "and point this at it."
        )

        val undeclared = read
            .filterNot { it.startsWith("android/app/src/main/kotlin/") }
            // The path or a directory above it, as a whole string in the block: a name
            // that only sits inside a longer path declares nothing beside it.
            .filterNot { path ->
                generateSequence(path) { it.substringBeforeLast('/', "").ifEmpty { null } }
                    .any { inputs.contains("\"$it\"") }
            }
            .toSortedSet()
        assertEquals(
            emptySet<String>(),
            undeclared,
            "a unit test opens these repository paths and the test task declares none of " +
                "them, so an edit to one leaves the task UP-TO-DATE and the suite that " +
                "exists to catch it never runs. Add each to the inputs block in " +
                "build.gradle.kts."
        )

        val unspelled = File("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { Regex("\"((?:\\.\\./)+)([^\"$]+)\\$[^\"]*\"").findAll(it.readText()) }
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (climb, prefix) ->
                val dir = File(climb + prefix.substringBeforeLast('/', ""))
                dir.isDirectory && dir.canonicalFile.toPath().startsWith(repoRoot)
            }
            .map { (_, prefix) -> prefix }
            .filterNot { inputs.contains("\"$it") }
            .toSortedSet()
        assertEquals(
            emptySet<String>(),
            unspelled,
            "a unit test opens a repository path that starts with these and ends in an " +
                "interpolated name, and no string in the inputs block of build.gradle.kts " +
                "starts with it. Declare each file the name can take."
        )
    }
}
