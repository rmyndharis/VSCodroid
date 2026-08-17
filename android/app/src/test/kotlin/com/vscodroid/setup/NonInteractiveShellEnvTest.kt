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
 * not -- rather than anything about the bundled build.
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
        for (name in listOf("npm()", "npx()", "claude()")) {
            assertTrue(written.contains(name), "$name is missing, so a task cannot run it")
        }
        assertTrue(
            written.contains("toolchain-env.sh"),
            "an installed toolchain stays invisible to everything that is not a terminal",
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

    /** Non-empty lines of stdout+stderr from `bash -c $script`, run in [cwd]. */
    private fun runBash(cwd: File, bashEnv: String?, script: String): List<String> {
        val builder = ProcessBuilder("/bin/bash", "-c", script)
            .directory(cwd)
            .redirectErrorStream(true)
        builder.environment().apply {
            remove("BASH_ENV")
            if (bashEnv != null) put("BASH_ENV", bashEnv)
            put("HOME", File(filesDir, "home").apply { mkdirs() }.path)
        }
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bash did not finish")
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}
