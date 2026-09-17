package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Environment
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What a shell that is not interactive can run.
 *
 * npm, npx, claude and every toolchain binary exist here as bash FUNCTIONS
 * rather than as files, because SELinux refuses to execute anything under the
 * app's data directory. Those functions were written into `.bashrc`, and bash
 * reads `.bashrc` only when it is interactive, so a VS Code task, an npm
 * lifecycle script or a build an extension spawns through `bash -c` was told
 * "command not found" for a command the terminal beside it ran fine.
 *
 * `BASH_ENV` is the startup file for the other case. Two halves have to agree
 * for it to work and they live in different files, which is what the first test
 * pins: [Environment.buildProcessEnvironment] names a path, and
 * [FirstRunSetup.createBashEnvFile] writes one.
 *
 * The bash test at the end is the only one that asks bash rather than asking the
 * string we wrote. It runs against the host's bash, not the device's, so what it
 * establishes is the RULE -- non-interactive sources BASH_ENV, interactive does
 * not -- rather than anything about the bundled build. That rule has one escape
 * hatch, and [runBash] closes it: bash reads `~/.bashrc` INSTEAD of `$BASH_ENV`
 * when it believes a remote shell daemon started it.
 */
class NonInteractiveShellEnvTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"
        }
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        // buildProcessEnvironment folds in the installed toolchains, and
        // ToolchainManager takes an asset-pack manager in a field initialiser, so
        // constructing it runs Play Core's static setup, which cannot complete
        // off-device. Its caller guards that with catch(Exception) and what
        // arrives is an Error, so the guard does not apply. Same stub as
        // [TerminalShellPathTest], for the same reason.
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun bashEnvFile() = File(Environment.getBashEnvPath(context))

    /**
     * The drift guard, and the reason it is first: the two halves are edited
     * separately and neither fails on its own. A file written where nothing
     * reads it, or a variable naming a path nothing writes, both leave every
     * command missing again with every test about the file's CONTENT still
     * green.
     */
    @Test
    fun `the server env names the file setup writes`() {
        FirstRunSetup(context).createBashEnvFile()

        val named = Environment.buildProcessEnvironment(context, 1234)["BASH_ENV"]
        assertEquals(bashEnvFile().path, named, "BASH_ENV does not name the generated file")
        assertTrue(File(named!!).isFile, "BASH_ENV names a path nothing writes")
    }

    @Test
    fun `the file defines the commands a task would otherwise not find`() {
        FirstRunSetup(context).createBashEnvFile()

        val written = bashEnvFile().readText()
        // pip and pip3 are here because pip has no name on PATH at all: it is
        // installed as a library and its console script is a #! file SELinux
        // will not execute, so the walkthrough's "Python + pip: ready" was
        // answered by `bash: pip: command not found`.
        for (name in listOf("npm()", "npx()", "claude()", "pip()", "pip3()")) {
            assertTrue(written.contains(name), "$name is missing, so a task cannot run it")
        }
        assertTrue(
            written.contains("toolchain-env.sh"),
            "an installed toolchain stays invisible to everything that is not a terminal",
        )
    }

    /**
     * The terminal's half of what makes Claude Code run on Android 13 and 14.
     *
     * Android's app seccomp filter refuses `epoll_pwait2` (syscall 441) on those
     * releases, and a refused syscall is a kill rather than the ENOSYS a runtime
     * could fall back from, so the CLI dies the moment its event loop starts.
     * Measured with a ptrace tracer on this project's own emulators: the same
     * binary and the same loader die on Android 13 and run on Android 17, and a
     * CLI four months older behaves identically, so it follows the platform and
     * not the extension version.
     *
     * `libclaude-launch.so` is what answers it, by exec'ing musl's loader with
     * `--preload=` naming `libseccomp-shim.so`. Calling that loader directly
     * here, which is what this line used to do and is the obvious shape, starts
     * the CLI without the shim and puts the kill straight back. Pinned because
     * nothing else in a terminal run would say so: the failure surfaces as bash's
     * bare "Bad system call", four layers from this line.
     */
    @Test
    fun `the claude wrapper starts the CLI through the launcher that carries the shim`() {
        FirstRunSetup(context).createBashEnvFile()

        val written = bashEnvFile().readText()
        val wrapper = written.substringAfter("claude()", "").substringBefore("\n}")
        assertTrue(wrapper.isNotEmpty(), "the claude wrapper is gone")
        assertTrue(
            wrapper.contains("libclaude-launch.so"),
            "the wrapper does not go through the launcher, so the CLI starts without the " +
                "seccomp shim and is killed on Android 13 and 14",
        )
        assertTrue(
            wrapper.contains("159"),
            "the wrapper does not read the status a SIGSYS kill leaves (128+31), so a call " +
                "the shim does not cover reaches the user as bash's bare \"Bad system call\"",
        )
    }

    /**
     * It is rewritten, not appended to, so a change to what the functions say
     * reaches an install that already has the file. Appending was how `.bashrc`
     * had to be handled -- that file is the user's -- and copying that habit here
     * would have frozen the first version onto every device.
     */
    @Test
    fun `a stale file from an older release is replaced`() {
        val envFile = bashEnvFile()
        envFile.parentFile?.mkdirs()
        envFile.writeText("npm() { echo from-an-older-release; }\n")

        FirstRunSetup(context).createBashEnvFile()

        assertFalse(
            envFile.readText().contains("from-an-older-release"),
            "the previous generation survived, so a fix to a wrapper never lands",
        )
        assertTrue(envFile.readText().contains("npx()"), "the rewrite did not happen at all")
    }

    /**
     * Every non-interactive shell in the app sources this file, so a half-written
     * one is not a missing command but a syntax error printed by every task and
     * every npm script. The failure is arranged as in [BashrcAtomicityTest], by
     * occupying the temporary path [writeAtomically] derives from the
     * destination.
     */
    @Test
    fun `a write that cannot finish leaves the previous file intact`() {
        val envFile = bashEnvFile()
        envFile.parentFile?.mkdirs()
        val previous = "npm() { echo previous; }\n"
        envFile.writeText(previous)

        File(envFile.parentFile, "${envFile.name}.tmp~").also {
            assertTrue(it.mkdirs(), "could not stage the blocked temp path")
            File(it, "occupied").writeText("x")
        }

        FirstRunSetup(context).createBashEnvFile()

        assertEquals(previous, envFile.readText(), "a failed rewrite damaged the working file")
    }

    /**
     * The mechanism itself, asked of bash rather than of the string.
     *
     * Three verdicts in one run, and the second and third are what make the
     * first mean anything: with BASH_ENV set, a non-interactive shell reports
     * `npm` as a FUNCTION -- not as a file, which is what a system npm on the
     * runner's PATH would report; without it, nothing; and the working directory
     * is unchanged, which is the property that would be lost by pointing
     * BASH_ENV at `.bashrc`, since that file ends by cd-ing into the workspace.
     */
    @Test
    fun `a non-interactive bash picks the commands up, and stays where it was`() {
        val bash = File("/bin/bash")
        assumeTrue(bash.canExecute(), "no /bin/bash on this host to ask")

        FirstRunSetup(context).createBashEnvFile()
        val cwd = File(filesDir, "somewhere").apply { mkdirs() }

        val withEnv = runBash(cwd, bashEnvFile().path, "pwd; type -t npm; type -t npx; type -t claude")
        assertEquals(
            listOf(cwd.canonicalPath, "function", "function", "function"),
            withEnv,
            "a non-interactive bash did not pick up the wrappers, or moved out of the " +
                "directory it was started in",
        )

        // The control. Without it, a test whose assertions all hold because the
        // runner happens to have these on PATH reads exactly the same.
        val withoutEnv = runBash(cwd, null, "type -t npm; type -t npx; type -t claude")
        assertFalse(
            withoutEnv.contains("function"),
            "these are functions with BASH_ENV unset too, so the run above proves nothing: " +
                withoutEnv,
        )
    }

    /**
     * What a user is told when npm fails in a folder that cannot hold a symlink.
     *
     * npm writes `node_modules/.bin/<name>` as a link for every package that
     * ships an executable, and shared storage is served through FUSE, which has
     * no `symlink(2)`: measured on an API 37 emulator, real npm in the default
     * workspace exits 243 with `syscall: 'symlink'` and writes no `.bin` at all,
     * while the same npm in the app's internal storage exits 0 and writes the
     * link. New installs get internal storage; one that already put the user's
     * work on shared storage keeps it, so this is the whole of what those
     * installs have to go on, and without it the failure is a page of npm output
     * with the cause nowhere in it.
     *
     * `ln` is overridden rather than a hostile filesystem arranged, because there
     * is none to arrange on a build host. What that leaves untested is FUSE
     * itself, which is what the device measurement covers; what it does test is
     * every decision this makes around the probe.
     */
    @Test
    fun `a folder that refuses a symlink gets one line of cause, once`() {
        val bash = File("/bin/bash")
        assumeTrue(bash.canExecute(), "no /bin/bash on this host to ask")

        FirstRunSetup(context).createBashEnvFile()
        val cwd = File(filesDir, "workspace").apply { mkdirs() }

        val out = runBash(
            cwd,
            bashEnvFile().path,
            """
            node() { return 7; }
            ln() { return 1; }
            npm install; echo "first=${'$'}?"
            npm install; echo "second=${'$'}?"
            ls -a | grep probe || echo no-leftover-probe
            """.trimIndent(),
        )

        assertEquals(
            1, out.count { it.startsWith("vscodroid: this folder is on shared storage") },
            "the cause was not named exactly once per shell: $out",
        )
        assertTrue(
            out.any { it.contains("node_modules/.bin") },
            "the note does not say what npm could not do: $out",
        )
        assertEquals(
            listOf("first=7", "second=7"), out.filter { it.startsWith("first=") || it.startsWith("second=") },
            "wrapping npm changed the exit status a script or a task sees: $out",
        )
        assertTrue(out.contains("no-leftover-probe"), "the probe was left in the user's folder: $out")
    }

    /**
     * The control, and the reason the note tries a symlink rather than matching
     * the path: a folder where links work must hear nothing, whatever npm did.
     * Matching on a path would have to name the shared-storage layout, and would
     * then be wrong for every workspace opened through the SAF picker, whose
     * mirror is internal.
     */
    @Test
    fun `a folder where symlinks work hears nothing about them`() {
        val bash = File("/bin/bash")
        assumeTrue(bash.canExecute(), "no /bin/bash on this host to ask")

        FirstRunSetup(context).createBashEnvFile()
        val cwd = File(filesDir, "workspace").apply { mkdirs() }

        val failed = runBash(cwd, bashEnvFile().path, "node() { return 7; }\nnpm install; echo \"status=\$?\"")
        assertFalse(
            failed.any { it.startsWith("vscodroid:") },
            "a folder that can hold links was told it cannot: $failed",
        )
        assertTrue(failed.contains("status=7"), "the exit status did not survive: $failed")

        val ok = runBash(cwd, bashEnvFile().path, "node() { return 0; }\nnpm install; echo \"status=\$?\"")
        assertEquals(listOf("status=0"), ok, "a successful npm said something: $ok")
    }

    /**
     * `python3` as pip() calls it, standing in for pip: it writes `ERRORS` to
     * the file the wrapper names after the code, where the real shim sends pip's
     * error records, and fails. `-P` has to come first, or a `logging.py` in the
     * user's folder replaces the module the shim imports and pip never starts.
     */
    private val failingPip = """
        export TMPDIR="${'$'}PWD/t"; mkdir -p "${'$'}TMPDIR"
        python3() { [ "${'$'}1" = -P ] || echo NO_SAFE_PATH; printf '%s\n' "${'$'}ERRORS" > "${'$'}4"; return 3; }
    """.trimIndent() + "\n"

    private val buildNote = "vscodroid: a package failed to build"

    /**
     * A failed `pip install` names the cause once, and only when pip reported a
     * build. The status cannot tell: pip exits 1 for a missing package and a
     * failed build alike, so a typo used to take the note and the build that
     * failed next in the same terminal was told nothing. Wrapping pip changes
     * nothing a script or a task sees: the exit status survives, and other
     * subcommands, or a success, say nothing. Options before the subcommand
     * still count as an install.
     */
    @Test
    fun `a pip install that fails to build gets one note, once, and keeps its status`() {
        val bash = File("/bin/bash")
        assumeTrue(bash.canExecute(), "no /bin/bash on this host to ask")

        FirstRunSetup(context).createBashEnvFile()
        val cwd = File(filesDir, "workspace").apply { mkdirs() }

        val out = runBash(
            cwd,
            bashEnvFile().path,
            failingPip + """
            ERRORS='ERROR: No matching distribution found for nosuch'
            pip install nosuch; echo "missing=${'$'}?"
            echo ---
            ERRORS='error: metadata-generation-failed'
            pip install matplotlib; echo "first=${'$'}?"
            ERRORS='ERROR: Failed building wheel for cmake'
            pip3 install cmake; echo "second=${'$'}?"
            echo "leftover=[${'$'}(ls -A "${'$'}TMPDIR")]"
            """.trimIndent(),
        )
        assertFalse(out.contains("NO_SAFE_PATH"), "pip runs without -P, so the folder's own files come first: $out")
        assertFalse(
            out.takeWhile { it != "---" }.any { it.startsWith("vscodroid:") },
            "a package that does not exist was told it failed to build: $out",
        )
        assertEquals(
            1, out.dropWhile { it != "---" }.count { it.startsWith(buildNote) },
            "the cause was not named exactly once per shell: $out",
        )
        assertEquals(
            listOf("missing=3", "first=3", "second=3"),
            out.filter { it.matches(Regex("""\w+=\d+""")) },
            "wrapping pip changed the exit status a script or a task sees: $out",
        )
        assertTrue(out.contains("leftover=[]"), "the errors file was left in TMPDIR: $out")

        // What pip prints for a build, in every form the note answers, and the
        // failures that read like one and are not: a build dependency that does
        // not exist, and a clone that failed.
        mapOf(
            "ERROR: Failed building wheel for cmake" to true,
            "metadata generation failed" to true,
            "error: metadata-generation-failed" to true,
            "ERROR: Failed to build 'x' when getting requirements to build wheel" to true,
            "ERROR: Failed to build 'x' when installing build dependencies: No matching distribution found for y" to false,
            "ERROR: Failed to build 'x' when git clone --filter=blob:none --quiet file:///nope" to false,
        ).forEach { (errors, isBuild) ->
            val one = runBash(cwd, bashEnvFile().path, failingPip + "ERRORS=\"$errors\"\npip install x")
            assertEquals(if (isBuild) 1 else 0, one.count { it.startsWith(buildNote) }, "for `$errors`: $one")
        }

        val listing = runBash(cwd, bashEnvFile().path, failingPip + "ERRORS='Failed building wheel for a'\npip list; echo \"list=\$?\"")
        assertEquals(listOf("list=3"), listing, "a failed pip list was given the install note: $listing")

        // On stderr: stdout inside ${'$'}(...) belongs to whatever captures it.
        val captured = runBash(cwd, bashEnvFile().path, failingPip + "ERRORS='Failed building wheel for a'\nx=${'$'}(pip install a)\nprintf 'captured=[%s]\\n' \"${'$'}x\"")
        assertTrue(captured.contains("captured=[]"), "the note went into a command substitution: $captured")
        assertEquals(1, captured.count { it.startsWith(buildNote) }, "the note was lost with stdout: $captured")

        val optionFirst = runBash(cwd, bashEnvFile().path, failingPip + "ERRORS='Failed building wheel for a'\npip -q install a")
        assertEquals(
            1, optionFirst.count { it.startsWith(buildNote) },
            "an install with an option before the subcommand said nothing: $optionFirst",
        )

        val ok = runBash(cwd, bashEnvFile().path, "python3() { return 0; }\npip install a; echo \"status=\$?\"")
        assertEquals(listOf("status=0"), ok, "a successful pip said something: $ok")

        // A script under set -e stops at a failed pip with pip's own status, and
        // the errors file does not stay behind.
        val errexit = runBash(
            cwd, bashEnvFile().path,
            failingPip + "ERRORS='Failed building wheel for a'\n(set -e; pip install a; echo unreachable); " +
                "echo \"sub=\$?\"; echo \"leftover=[\$(ls -A \"\$TMPDIR\")]\"",
        )
        assertTrue(errexit.contains("sub=3"), "set -e did not stop with pip's status: $errexit")
        assertFalse(errexit.contains("unreachable"), "set -e carried on past a failed pip: $errexit")
        assertTrue(errexit.contains("leftover=[]"), "a failed pip under set -e left its file: $errexit")
    }

    /**
     * pip() starts pip the way `python3 -m pip` does, so a venv made with
     * `--without-pip` answers with the one line `-m` prints. `run_module` raised
     * the same ImportError as a five-line traceback, through the shim's own
     * frames. Measured against a real interpreter rather than here: no python3
     * runs in this suite, and the stub above stands in for all of it.
     */
    @Test
    fun `pip starts the module the way python3 -m pip does`() {
        FirstRunSetup(context).createBashEnvFile()

        val wrapper = bashEnvFile().readText().substringAfter("pip()", "").substringBefore("\n}")
        assertTrue(wrapper.isNotEmpty(), "the pip wrapper is gone")
        assertTrue(
            wrapper.contains("runpy._run_module_as_main(\"pip\")"),
            "pip is not started as -m starts it, so a missing pip prints a traceback: $wrapper",
        )
    }

    /**
     * tkinter and turtle are not in this Python, and pip gives no hint of it:
     * `tkinter` is not on PyPI, and `tk` is, as an unrelated package that
     * installs cleanly. So this note is keyed on the name, success included,
     * and never borrows the build note.
     */
    @Test
    fun `asking pip for tkinter says it is not included, whatever pip returns`() {
        val bash = File("/bin/bash")
        assumeTrue(bash.canExecute(), "no /bin/bash on this host to ask")

        FirstRunSetup(context).createBashEnvFile()
        val cwd = File(filesDir, "workspace").apply { mkdirs() }

        val out = runBash(
            cwd,
            bashEnvFile().path,
            failingPip + """
            ERRORS='ERROR: No matching distribution found for tkinter'
            pip install tkinter; echo "tkinter=${'$'}?"
            python3() { return 0; }
            pip install tk; echo "tk=${'$'}?"
            pip show tk; echo "show=${'$'}?"
            """.trimIndent(),
        )
        assertEquals(
            2, out.count { it.startsWith("vscodroid: tkinter") },
            "an install of tkinter or tk was not told they are not included, or `pip show` was: $out",
        )
        assertFalse(out.any { it.startsWith(buildNote) }, "a missing tkinter was told it failed to build: $out")
        assertEquals(
            listOf("tkinter=3", "tk=0", "show=0"),
            out.filter { it.matches(Regex("""\w+=\d+""")) },
            "wrapping pip changed the exit status a script or a task sees: $out",
        )
    }

    /**
     * A shell the extension host starts has no NODE_OPTIONS, because VS Code
     * deletes it from the extension host's environment, so npm() there ran node
     * with no preload to act on VSCODROID_PLATFORM_FIX. BASH_ENV puts it back,
     * once, and leaves a terminal's value alone.
     */
    @Test
    fun `a shell without the node preload gets it back, and a terminal keeps its own`() {
        val bash = File("/bin/bash")
        assumeTrue(bash.canExecute(), "no /bin/bash on this host to ask")

        FirstRunSetup(context).createBashEnvFile()
        val cwd = File(filesDir, "workspace").apply { mkdirs() }
        val fix = "${filesDir.absolutePath}/server/platform-fix.js"

        val stripped = runBash(cwd, bashEnvFile().path, "unset NODE_OPTIONS; bash -c 'echo \"opts=[${'$'}NODE_OPTIONS]\"'")
        assertEquals(listOf("opts=[--require=$fix]"), stripped.filter { it.startsWith("opts=") },
            "a shell started without NODE_OPTIONS still has no preload: $stripped")

        val kept = runBash(cwd, bashEnvFile().path,
            "export NODE_OPTIONS='--require=$fix --max-old-space-size=64'; bash -c 'echo \"opts=[${'$'}NODE_OPTIONS]\"'")
        assertEquals(listOf("opts=[--require=$fix --max-old-space-size=64]"), kept.filter { it.startsWith("opts=") },
            "a shell that already had the preload got it twice or lost its options: $kept")

        val other = runBash(cwd, bashEnvFile().path,
            "export NODE_OPTIONS='--max-old-space-size=64'; bash -c 'echo \"opts=[${'$'}NODE_OPTIONS]\"'")
        assertEquals(listOf("opts=[--require=$fix --max-old-space-size=64]"), other.filter { it.startsWith("opts=") },
            "the preload replaced options the shell already had: $other")
    }

    /** Non-empty lines of stdout+stderr from `bash -c $script`, run in [cwd]. */
    private fun runBash(cwd: File, bashEnv: String?, script: String): List<String> {
        val builder = ProcessBuilder("/bin/bash", "-c", script)
            .directory(cwd)
            .redirectErrorStream(true)
        builder.environment().apply {
            // BASH_ENV is not a switch the child may inherit: each case below
            // sets it or leaves it unset, and the control is worth nothing if
            // the host already exported one.
            remove("BASH_ENV")
            // SSH_CLIENT and SSH2_CLIENT are removed because either one turns
            // BASH_ENV off entirely. A non-interactive bash that finds one
            // concludes a remote shell daemon started it and sources ~/.bashrc
            // INSTEAD of $BASH_ENV, and under this test's HOME there is no
            // .bashrc, so every wrapper goes missing and the failure talks about
            // wrappers and working directories with the cause nowhere in it.
            //
            // Measured on bash 3.2.57, including the condition that keeps it off
            // most machines: that branch is taken only by a TOP-LEVEL shell, so
            // an inherited SHLVL of 1 or more skips it. A `./gradlew` run from a
            // terminal always has one; a JVM some CI agent forked need not, and
            // which of those this is should not decide whether the test passes.
            // The same branch is taken when stdin is a socket, and ProcessBuilder
            // hands the child a pipe, so that half needs nothing done to it.
            remove("SSH_CLIENT")
            remove("SSH2_CLIENT")
            if (bashEnv != null) put("BASH_ENV", bashEnv)
            put("HOME", File(filesDir, "home").apply { mkdirs() }.path)
        }
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bash did not finish")
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}
