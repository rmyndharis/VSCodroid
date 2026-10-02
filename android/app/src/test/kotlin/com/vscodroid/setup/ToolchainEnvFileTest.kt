package com.vscodroid.setup

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What `toolchain-env.sh` has to contain for a downloaded toolchain to run at
 * all.
 *
 * SELinux denies `execute_no_trans` on `app_data_file`, so nothing whose inode
 * lives under `filesDir` can be `execve`d whatever its mode, and every byte of
 * a downloaded toolchain lives there. The only thing that makes `go` or `ruby` a
 * working command is the wrapper this file emits: a shell function that hands the
 * binary to `/system/bin/linker64`, which the app is allowed to execute, and
 * which loads the payload itself.
 *
 * That indirection had no test. The predicate it consults did, dozens of names
 * against a real bash, while the line that actually routes through the loader
 * was pinned nowhere, and neither was the ELF guard that decides which names get
 * a wrapper at all. Both are one-line edits that read like leftovers, both leave
 * every other test in this package green, and on a device the result is a picker
 * that still says Installed and a command that answers `Permission denied` with
 * exit 126.
 *
 * The wrapper is asserted as a whole line rather than by searching for the loader
 * path: the loader has to come first, before the binary, and a line that merely
 * mentions it somewhere is not the same thing.
 */
class ToolchainEnvFileTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var stateFile: File
    private lateinit var envFile: File

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // The constructor reaches Play Core through field initialisation, so it
        // runs before any method can be called.
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir

        File(filesDir, "home/.vscodroid").mkdirs()
        stateFile = File(filesDir, "home/.vscodroid/toolchains.json")
        envFile = File(filesDir, "home/.vscodroid/toolchain-env.sh")
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** The four bytes every ELF object starts with, plus enough body to be a file. */
    private fun elf(relPath: String) = File(filesDir, relPath).apply {
        parentFile?.mkdirs()
        writeBytes(
            byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) +
                ByteArray(64)
        )
    }

    private fun script(relPath: String) = File(filesDir, relPath).apply {
        parentFile?.mkdirs()
        writeText("#!/usr/bin/env ruby\nputs 1\n")
    }

    private fun install(vararg binaries: String) {
        val list = binaries.joinToString(",") { "\"$it\"" }
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/opt/ruby","binaries":[$list],""" +
                """"env":{"GEM_HOME":"${'$'}FILESDIR/usr/opt/ruby/gems"},""" +
                """"pathDirs":["usr/opt/ruby/bin"]}]"""
        )
    }

    /**
     * A record whose `scriptWrappers` names [scripts], with `ruby` as the
     * interpreter, exactly as `download-ruby.sh` writes one.
     */
    private fun installWithScripts(vararg scripts: String) {
        val wrappers = scripts.joinToString(",") { """"$it":"usr/opt/ruby/bin/$it"""" }
        val binaries = (listOf("ruby") + scripts)
            .joinToString(",") { """"usr/opt/ruby/bin/$it"""" }
        // `env` is not decoration here: the generator's per-toolchain loop reads
        // it with `?: continue`, so a record without one is skipped whole and
        // neither loop below it runs.
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/opt/ruby","binaries":[$binaries],""" +
                """"env":{"GEM_HOME":"${'$'}FILESDIR/usr/opt/ruby/gems"},""" +
                """"scriptWrappers":{"interpreter":"ruby","scripts":{$wrappers}}}]"""
        )
    }

    private fun regenerate() = ToolchainManager(context).regenerateDerivedFiles()

    private fun envLines() = envFile.readText().lines().map { it.trim() }

    @Test
    fun `a binary is invoked through the system loader, not directly`() {
        elf("usr/opt/ruby/bin/ruby")
        install("usr/opt/ruby/bin/ruby")

        regenerate()

        assertTrue(
            envLines().contains(
                """ruby() { /system/bin/linker64 "${'$'}PREFIX/../usr/opt/ruby/bin/ruby" "${'$'}@"; }"""
            ),
            "the wrapper no longer hands the binary to the system loader, so every " +
                "downloaded toolchain command answers Permission denied:\n" +
                envFile.readText(),
        )
    }

    /**
     * The env file is not only wrappers, and this is the control for the test
     * above: a generator that emitted nothing at all, or that skipped this
     * toolchain, would satisfy an assertion written as "no bad line is present".
     */
    @Test
    fun `the toolchain's own environment and PATH still reach the file`() {
        elf("usr/opt/ruby/bin/ruby")
        install("usr/opt/ruby/bin/ruby")

        regenerate()

        val lines = envLines()
        assertTrue(
            lines.contains("""export GEM_HOME="${'$'}PREFIX/../usr/opt/ruby/gems""""),
            "the toolchain's environment is missing:\n" + envFile.readText(),
        )
        assertTrue(
            lines.contains("""export PATH="${'$'}PATH:${'$'}PREFIX/../usr/opt/ruby/bin""""),
            "the toolchain's PATH entry is missing:\n" + envFile.readText(),
        )
    }

    /**
     * Appended, never prepended, and the direction is the whole point.
     *
     * `pathDirs` names the directories the payload actually sits in, and for
     * Ruby that is `usr/bin`, where `ruby` is the interpreter ELF itself, an
     * inode under filesDir that SELinux refuses to execve. The trampoline
     * directory that CAN start it is already ahead of them on the PATH the
     * server process was given. A shell that prepended these would put the
     * unexecutable copies back in front of it, for itself and for every child it
     * spawns, and the fix would be invisible in exactly the place a user tests
     * it first. Bash resolves its own functions before consulting PATH at all,
     * so nothing about the interactive terminal would change to give it away.
     */
    @Test
    fun `the toolchain PATH line appends rather than prepends`() {
        elf("usr/opt/ruby/bin/ruby")
        install("usr/opt/ruby/bin/ruby")

        regenerate()

        val pathLine = envLines().single { it.startsWith("export PATH=") }
        assertTrue(
            pathLine.startsWith("""export PATH="${'$'}PATH:"""),
            "the raw, unexecutable payload directories were put back in front of the " +
                "trampoline directory: $pathLine",
        )
    }

    /**
     * Only ELF objects get a loader wrapper. A script handed to `linker64` is not
     * a program it can load, so wrapping one would replace "this command does not
     * exist" with a command that exists and always fails, and it would shadow
     * the interpreter wrapper that does work, since that is written further down
     * the same file.
     */
    @Test
    fun `a script in the binaries list gets no loader wrapper`() {
        script("usr/opt/ruby/bin/gem")
        install("usr/opt/ruby/bin/gem")

        regenerate()

        assertEquals(
            emptyList<String>(),
            envLines().filter { it.startsWith("gem()") },
            "a Ruby script was wrapped as though the loader could execute it",
        )
    }

    /**
     * A manifest naming a binary that is not on disk, a partial extraction, or
     * a file an uninstall took, produces no wrapper for it either. Same guard,
     * and worth holding separately: this is the case where the wrapper would be
     * syntactically fine and fail only when the user runs it.
     */
    @Test
    fun `a missing binary gets no wrapper`() {
        install("usr/opt/ruby/bin/ruby")

        regenerate()

        assertEquals(
            emptyList<String>(),
            envLines().filter { it.startsWith("ruby()") },
            "a wrapper was written for a binary that is not on disk",
        )
    }

    /**
     * A script wrapper is held to the same naming bar as a binary wrapper, and
     * for the same reason.
     *
     * These names are not this repository's choice: `download-ruby.sh` takes each
     * one from the `basename` of whatever upstream ships in the package's bin
     * directory, splitting binaries from scripts by reading the file rather than
     * by naming it. `.bashrc` sources this file unconditionally, and a name bash
     * cannot use as a function is a parse error that costs every definition after
     * it -- measured: sourcing `good() {...}`, `a=b() {...}`, `after() {...}`
     * leaves `good` defined and `after` gone. The binaries loop refused such a
     * name; the scripts loop beside it wrote it out.
     *
     * Dropping the [isShellFunctionName] check from the scripts loop turns this
     * red on its first assertion.
     */
    @Test
    fun `a script whose name bash cannot use as a function gets no wrapper`() {
        every { Logger.w(any(), any()) } just Runs
        elf("usr/opt/ruby/bin/ruby")
        script("usr/opt/ruby/bin/gem")
        script("usr/opt/ruby/bin/a=b")
        installWithScripts("a=b", "gem")

        regenerate()

        assertEquals(
            emptyList<String>(),
            envLines().filter { it.startsWith("a=b()") },
            "a name that is a parse error was written into a file .bashrc sources, " +
                "so every wrapper after it is lost in every new terminal:\n" +
                envFile.readText(),
        )
        // The control, and the half that says the refusal is narrow: the usable
        // name beside it still gets its wrapper, so this is not a generator that
        // simply stopped emitting script wrappers.
        assertTrue(
            envLines().contains(
                """gem() { ruby "${'$'}PREFIX/../usr/opt/ruby/bin/gem" "${'$'}@"; }"""
            ),
            "the usable script name lost its wrapper too:\n" + envFile.readText(),
        )
    }

    /**
     * And a toolchain whose every script name is unusable writes no header for a
     * section with nothing in it, which is what the binaries loop already does.
     */
    @Test
    fun `a section with no usable script names is not written at all`() {
        every { Logger.w(any(), any()) } just Runs
        elf("usr/opt/ruby/bin/ruby")
        script("usr/opt/ruby/bin/a;b")
        installWithScripts("a;b")

        regenerate()

        assertEquals(
            emptyList<String>(),
            envLines().filter { it.contains("script wrappers") },
            "an empty script-wrapper section was announced:\n" + envFile.readText(),
        )
    }

    private val jdk = "usr/lib/jvm/java-17-openjdk"

    /** What the jshell wrapper hands the loader ahead of the caller's arguments. */
    private val jshell = "</p/../$jdk/bin/jshell><-J-Djdk.lang.Process.launchMechanism=VFORK>" +
        "<-J-Duser.home=/h o/me>"

    /**
     * Writes the env file for a Java toolchain holding `java` and `jshell`,
     * sources it in a real bash with the loader replaced by a function that
     * prints its arguments, runs [commands] there and returns what they printed.
     * Anything the file prints while it is sourced, a syntax error included,
     * lands in the same output.
     */
    private fun runJavaWrappers(commands: String): List<String> {
        assumeTrue(File("/bin/bash").canExecute(), "no /bin/bash on this host")
        elf("$jdk/bin/java")
        elf("$jdk/bin/jshell")
        stateFile.writeText(
            """[{"name":"java","binaries":["$jdk/bin/java","$jdk/bin/jshell"],""" +
                """"env":{"JAVA_HOME":"${'$'}FILESDIR/$jdk"}}]"""
        )

        regenerate()

        val sourced = File(filesDir, "env-under-test.sh")
        sourced.writeText(envFile.readText().replace("/system/bin/linker64", "argv"))
        val builder = ProcessBuilder(
            "/bin/bash", "-c",
            """argv() { printf '<%s>' "${'$'}@"; echo; }; PREFIX=/p; HOME='/h o/me'; """ +
                """. "${'$'}1"; $commands""",
            "bash", sourced.path,
        ).redirectErrorStream(true)
        builder.environment().apply {
            remove("BASH_ENV")
            remove("SSH_CLIENT")
            remove("SSH2_CLIENT")
        }
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bash did not finish")
        return out.lines().filter { it.isNotEmpty() }
    }

    /**
     * `jshell` is the one wrapper that adds arguments, and they are what make it
     * start at all: its default engine launches a second JVM by absolute path,
     * which cannot be exec'd from filesDir without the exec interceptor, and
     * the wrapper is also sourced where none is preloaded, so it selects the
     * local engine unless the caller chose one. jshell refuses `--execution`
     * given twice and accepts abbreviations of it, so the guard is asserted in
     * all three spellings. The `$HOME` with a space in it holds the quoting.
     * `java` is the control: the special case must not leak into the ordinary
     * wrapper beside it.
     */
    @Test
    fun `jshell runs its snippets in its own JVM`() {
        val out = runJavaWrappers(
            """jshell; jshell a 'b c'; jshell --execution jdi; """ +
                """jshell --exec=jdi; jshell -execution jdi; java -version"""
        )

        assertEquals(
            listOf(
                "$jshell<--execution><local>",
                "$jshell<--execution><local><a><b c>",
                "$jshell<--execution><jdi>",
                "$jshell<--exec=jdi>",
                "$jshell<-execution><jdi>",
                "</p/../$jdk/bin/java><-version>",
            ),
            out,
            "the jshell wrapper no longer selects the local engine exactly once, so " +
                "jshell fails to launch or refuses its arguments:\n" + envFile.readText(),
        )
    }

    /**
     * The engine guard looks at each argument on its own, never at the joined
     * command line. A search of `" $* "` for `" -ex"` failed both ways. A value
     * holding a space before `-ex`, as these two paths do, read as an engine
     * choice, so jshell got its default engine back and failed to launch it.
     * And `"$*"` joins with the first character of IFS, so under the IFS a
     * script in strict mode sets, or an empty one, a later `--execution` had
     * no space before it, went unseen, and jshell refused the option given
     * twice. The third line is the control in the other direction: `-ex`
     * inside an argument, rather than at its start, is not an engine choice.
     */
    @Test
    fun `jshell judges each argument on its own, whatever IFS holds`() {
        val out = runJavaWrappers(
            """jshell --startup 'My -experiments/a.jsh'; """ +
                """jshell --class-path 'build/lib -extra/x.jar'; """ +
                """jshell -R-Dexample=1 -C-Xlint:-exports my-example.jsh; """ +
                """IFS=${'$'}'\n\t'; jshell -v --execution jdi; """ +
                """IFS=; jshell -q --exec=jdi x.jsh"""
        )

        assertEquals(
            listOf(
                "$jshell<--execution><local><--startup><My -experiments/a.jsh>",
                "$jshell<--execution><local><--class-path><build/lib -extra/x.jar>",
                "$jshell<--execution><local><-R-Dexample=1><-C-Xlint:-exports><my-example.jsh>",
                "$jshell<-v><--execution><jdi>",
                "$jshell<-q><--exec=jdi><x.jsh>",
            ),
            out,
            "the jshell wrapper read its arguments as one string, so a path holding " +
                "' -ex' lost the local engine or a non-default IFS passed --execution " +
                "twice:\n" + envFile.readText(),
        )
    }

    /**
     * The guard keeps nothing from one call to the next. The function is sourced
     * into every bash, scripts included, so a flag that outlived its call would
     * let one `jshell --execution jdi` take the local engine away from every
     * later `jshell` in that shell, and a global `arg` would overwrite the
     * caller's own. `set -u` is what a strict-mode script runs under, and there
     * a flag declared without a value is unbound and ends the script. That last
     * one shows only where bash leaves such a flag unset, as bash 5 on CI and on
     * the device does; the bash 3.2 macOS ships gives it an empty value.
     */
    @Test
    fun `jshell keeps nothing from one call to the next`() {
        val out = runJavaWrappers(
            """set -u; jshell --execution jdi; jshell x.jsh; """ +
                """argv "${'$'}{arg-unset}" "${'$'}{chosen-unset}""""
        )

        assertEquals(
            listOf(
                "$jshell<--execution><jdi>",
                "$jshell<--execution><local><x.jsh>",
                "<unset><unset>",
            ),
            out,
            "the jshell wrapper's engine check outlived its call or failed under " +
                "set -u, so a later jshell lost the local engine, the caller's " +
                "variables changed, or the script stopped:\n" + envFile.readText(),
        )
    }

    /**
     * A state file nothing can parse is not "no toolchains installed".
     *
     * `readState` answers a damaged file with an empty array because it cannot do
     * better, and the per-launch regeneration treats empty as "nothing is
     * installed" and deletes the env file. The devices that have such a file are
     * the ones upgrading from a version that wrote it with `writeText`: the
     * truncation window this build closes with `writeAtomically` is exactly the
     * damage being read back. Deleting over it takes the loader wrappers out of
     * every new terminal while the several hundred MB they make runnable stay on
     * disk, and the env file written by the version that installed them is then
     * the only working record of how to run any of it. The bashrc repair keeps
     * damaged files it cannot parse for the same reason; this is its toolchain
     * counterpart.
     */
    @Test
    fun `a state file that cannot be parsed leaves the env file alone`() {
        every { Logger.w(any(), any()) } just Runs

        elf("usr/opt/ruby/bin/ruby")
        install("usr/opt/ruby/bin/ruby")
        regenerate()
        val writtenByTheInstall = envFile.readText()

        stateFile.writeText("""[{"name":"ruby","installRoot":"usr/op""")

        regenerate()

        assertTrue(envFile.isFile, "the env file was deleted over an unreadable state")
        assertEquals(
            writtenByTheInstall, envFile.readText(),
            "the env file was rewritten over an unreadable state"
        )
    }
}