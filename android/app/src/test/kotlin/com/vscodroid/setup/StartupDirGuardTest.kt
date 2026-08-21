package com.vscodroid.setup

import android.content.Context
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
 * That `.bashrc` moves a shell only when nobody chose where it should start.
 *
 * bash runs `.bashrc` for every interactive shell, including the ones VS Code
 * started in a directory it picked, so the unguarded `cd` that used to end the
 * file overrode the folder right-clicked in the explorer, an extension's
 * `createTerminal({cwd})`, the `terminal.integrated.cwd` setting, and the
 * directory a revived terminal was restored to.
 *
 * The first four cases run real bash against the real generated file, because a
 * text assertion cannot tell a guard that works from one that never fires. What
 * they do is what `shellIntegration-bash.sh` does on the device: source
 * `.bashrc` from a shell started in a given directory, then ask where it ended
 * up.
 */
class StartupDirGuardTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var externalDir: File

    private lateinit var context: Context
    private val home: File get() = File(filesDir, "home")
    private val bashrc: File get() = File(home, ".bashrc")
    private val projects: File get() = File(externalDir, "projects")

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        // Named rather than relaxed, because PROJECTS_DIR is what three of the
        // cases below assert on: a relaxed mock answers with a File whose path is
        // empty, the export becomes PROJECTS_DIR='/projects', and every cd fails
        // for a reason that has nothing to do with the guard.
        every { context.getExternalFilesDir(null) } returns externalDir

        home.mkdirs()
        projects.mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun createBashrc() {
        FirstRunSetup::class.java
            .getDeclaredMethod("createBashrc")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    /**
     * Where a shell started in [from] ends up after sourcing the generated file.
     *
     * `bash -c` sourcing `.bashrc` is the honest reproduction rather than a
     * shortcut: it is literally what the shell integration script the ptyHost
     * injects does. `BASH_ENV`, `SSH_CLIENT` and `SSH2_CLIENT` are removed for
     * the reason `NonInteractiveShellEnvTest` documents: either SSH variable
     * makes bash source `~/.bashrc` in place of `$BASH_ENV`, which would make the
     * result depend on the host that ran the suite.
     */
    private fun endsUpIn(from: File): String {
        assumeTrue(File("/bin/bash").canExecute(), "no /bin/bash on this host")
        val builder = ProcessBuilder("/bin/bash", "-c", ". \"\$HOME/.bashrc\"; pwd")
            .directory(from)
            .redirectErrorStream(true)
        builder.environment().apply {
            remove("BASH_ENV")
            remove("SSH_CLIENT")
            remove("SSH2_CLIENT")
            put("HOME", home.path)
        }
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bash did not finish")
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(
            1, lines.size,
            "sourcing .bashrc printed something other than the one path: $lines",
        )
        return File(lines.single()).canonicalPath
    }

    @Test
    fun `a shell started in a folder stays in it`() {
        val workspace = File(filesDir, "workspace").apply { mkdirs() }
        val recorded = File(filesDir, "recorded").apply { mkdirs() }
        createBashrc()
        File(home, ".vscodroid_folder").writeText(recorded.path)

        assertEquals(
            workspace.canonicalPath, endsUpIn(workspace),
            "the shell was moved out of the directory VS Code started it in, which " +
                "is what the explorer's Open in Integrated Terminal relies on",
        )
    }

    /**
     * The control for the case above. Without it a guard that is simply always
     * false satisfies the first case while removing the behaviour entirely.
     */
    @Test
    fun `a shell started in HOME still goes to the projects directory`() {
        createBashrc()

        assertEquals(
            projects.canonicalPath, endsUpIn(home),
            "nobody chose a directory for this shell, so the block still has a job " +
                "to do and did not do it",
        )
    }

    @Test
    fun `a shell started in HOME prefers the recorded folder`() {
        val recorded = File(filesDir, "recorded").apply { mkdirs() }
        createBashrc()
        File(home, ".vscodroid_folder").writeText(recorded.path)

        assertEquals(recorded.canonicalPath, endsUpIn(home), "the recorded folder was ignored")
    }

    @Test
    fun `a recorded folder that no longer exists falls back to the projects directory`() {
        createBashrc()
        File(home, ".vscodroid_folder").writeText(File(filesDir, "deleted").path)

        // endsUpIn asserts the output is exactly one line, which is the second half
        // of this case: a failed cd that printed a diagnostic would corrupt the
        // terminal's first prompt as surely as landing in the wrong place.
        assertEquals(
            projects.canonicalPath, endsUpIn(home),
            "a stale recorded folder left the shell in HOME instead of falling back",
        )
    }

    /**
     * The case the `[ -d ]` test is actually for.
     *
     * Measured while checking that each clause earns its place: with the recorded
     * folder merely deleted, dropping `[ -d ]` changes nothing, because `cd` to a
     * missing path fails and the `||` already falls through to the projects
     * directory. An EMPTY recorded file is different. `__folder` is then the empty
     * string, `cd ""` succeeds in bash and moves nowhere, so without the test the
     * shell simply stays in HOME and the fallback never runs.
     */
    @Test
    fun `an empty recorded folder falls back rather than staying put`() {
        createBashrc()
        File(home, ".vscodroid_folder").writeText("")

        assertEquals(
            projects.canonicalPath, endsUpIn(home),
            "an empty recorded folder left the shell in HOME: cd to the empty string " +
                "succeeds, so only the -d test can send it to the fallback",
        )
    }

    // -- The migration, for devices that already have a .bashrc --

    /**
     * The historical file, with the unguarded block exactly as every release
     * before this one wrote it, and with content on both sides of it so that a
     * rewrite which loses one half is visible.
     */
    private fun writeLegacyBashrc(): ByteArray {
        val text = """
            # VSCodroid bash configuration

            export PROJECTS_DIR='${projects.path}'
            alias ll='ls -la'

            # Start in the active folder (SAF or default projects dir)
            if [ -f "${'$'}HOME/.vscodroid_folder" ]; then
                __folder="${'$'}(cat "${'$'}HOME/.vscodroid_folder" 2>/dev/null)"
                [ -d "${'$'}__folder" ] && cd "${'$'}__folder" 2>/dev/null || cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
                unset __folder
            else
                cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
            fi

            npm() { :; }
            # the user's own alias
        """.trimIndent() + "\n"
        bashrc.writeText(text)
        return text.toByteArray()
    }

    @Test
    fun `the unguarded block is rewritten and everything around it survives`() {
        val before = writeLegacyBashrc()

        FirstRunSetup(context).ensureStartupDirGuard()

        val after = bashrc.readText()
        assertTrue(after.contains("vscodroid startup dir"), "the block was not replaced")
        assertFalse(
            after.contains("# Start in the active folder (SAF or default projects dir)"),
            "the unguarded block is still there as well",
        )
        for (kept in listOf("export PROJECTS_DIR", "alias ll=", "npm() { :; }", "# the user's own alias")) {
            assertTrue(after.contains(kept), "the rewrite lost $kept")
        }
        val prefix = String(before).substringBefore("# Start in the active folder")
        assertTrue(after.startsWith(prefix), "the bytes before the block did not survive unchanged")
    }

    @Test
    fun `guarding is idempotent`() {
        writeLegacyBashrc()
        val setup = FirstRunSetup(context)

        setup.ensureStartupDirGuard()
        val once = bashrc.readBytes()
        setup.ensureStartupDirGuard()

        assertArrayEquals(once, bashrc.readBytes(), "a second launch rewrote a file that was already guarded")
    }

    /**
     * A file whose block the user edited matches the frozen legacy text nowhere,
     * so it is left exactly as written. That is the whole reason the legacy text
     * is frozen rather than regenerated from the current block.
     */
    @Test
    fun `a block the user edited is left alone`() {
        writeLegacyBashrc()
        val edited = bashrc.readText().replace(
            """cd "${'$'}PROJECTS_DIR" 2>/dev/null || true""",
            """cd "${'$'}HOME/somewhere-else" 2>/dev/null || true""",
        )
        bashrc.writeText(edited)

        FirstRunSetup(context).ensureStartupDirGuard()

        assertEquals(edited, bashrc.readText(), "an edited block was rewritten")
        assertFalse(bashrc.readText().contains("vscodroid startup dir"), "a marker was added anyway")
    }

    @Test
    fun `a file with no bashrc at all is left absent`() {
        bashrc.delete()

        FirstRunSetup(context).ensureStartupDirGuard()

        assertFalse(bashrc.exists(), "the guard created a .bashrc where setup had written none")
    }

    /**
     * The generated file and the migrated one have to agree, or a device that
     * upgrades ends up with different shell behaviour from one that installs
     * fresh. Compared as text rather than asserted twice, so the two cannot
     * drift apart without this failing.
     */
    @Test
    fun `a fresh install and a migrated one write the same block`() {
        createBashrc()
        val fresh = bashrc.readText().substringAfter("# >>> vscodroid startup dir")

        bashrc.delete()
        writeLegacyBashrc()
        FirstRunSetup(context).ensureStartupDirGuard()
        val migrated = bashrc.readText().substringAfter("# >>> vscodroid startup dir")

        assertEquals(
            fresh.substringBefore("# <<< vscodroid startup dir"),
            migrated.substringBefore("# <<< vscodroid startup dir"),
            "the block written on a fresh install differs from the one the migration writes",
        )
    }
}
