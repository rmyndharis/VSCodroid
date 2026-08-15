package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * Tests that no half-written `.bashrc` can survive, and -- the sharper half --
 * that none can certify itself as finished.
 *
 * Every writer on this file is guarded by something the file itself contains:
 * the prompt rewrite returns early once the current version marker is present,
 * the npm block once `npm()` is, the toolchain line once the filename is. Each
 * of those strings sits near the *start* of what its writer emits, and the
 * writes truncated in place. So a write cut short by process death left a file
 * that satisfied its own guard while missing everything after the cut -- an
 * unbalanced function body, no `PROJECTS_DIR`, no `cd` into the workspace --
 * and no later launch would touch it again. The terminal opens on a syntax
 * error, permanently, and the marker says the work was done.
 *
 * Ordering the marker last would narrow the window; writing through a temporary
 * file closes it, because nothing appears under the real name until all of it
 * has. These pin the second, so the ordering inside the block stays free.
 *
 * The failure is arranged as in [UpdateSettingsPathsTest]: by occupying the
 * temporary path [writeAtomically] derives from the destination.
 */
class BashrcAtomicityTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var bashrc: File

    /** The v1.0.0 prompt shape: no versioned markers, `PS1` left empty. */
    private val legacyBashrc = """
        # VSCodroid bash configuration
        # Prompt via PROMPT_COMMAND
        __vscodroid_prompt() {
            echo -n "${'$'}PWD ${'$'} "
        }
        PROMPT_COMMAND=__vscodroid_prompt
        PS1=''

        export PROJECTS_DIR='/data/projects'
        alias ll='ls -la'
    """.trimIndent() + "\n"

    /** The marker the prompt rewrite writes, and the guard it reads back. */
    private val currentMarker = ">>> vscodroid prompt"

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"
        }
        // createBashrc interpolates this into PROJECTS_DIR; pinned so the file
        // it writes does not depend on what a relaxed mock invents.
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        bashrc = File(filesDir, "home/.bashrc")
        bashrc.parentFile?.mkdirs()
        bashrc.writeText(legacyBashrc)
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    /** Non-empty, so the cleanup `delete()` cannot quietly reclaim it. */
    private fun blockTheWrite(): File =
        File(bashrc.parentFile, "${bashrc.name}.tmp~").also {
            assertTrue(it.mkdirs(), "could not stage the blocked temp path")
            File(it, "occupied").writeText("x")
        }

    /** What [FirstRunSetup.createNpmWrappers] needs on disk before it will do anything. */
    private fun bundleNpm() {
        val cli = File(filesDir, "usr/lib/node_modules/npm/bin/npm-cli.js")
        cli.parentFile?.mkdirs()
        cli.writeText("// npm")
    }

    @Test
    fun `rewrites the legacy prompt and keeps everything after it`() {
        // The control. Every failure assertion below would also hold for a
        // method that had stopped doing anything at all.
        FirstRunSetup(context).ensurePromptFix()

        val written = bashrc.readText()
        assertTrue(written.contains(currentMarker), "the prompt block was not brought up to date")
        assertTrue(written.contains("export PROJECTS_DIR"), "the rewrite ate what followed the prompt")
        assertTrue(written.contains("alias ll="), "the rewrite ate what followed the prompt")
        assertFalse(written.contains("PS1=''"), "the legacy empty prompt survived the rewrite")
    }

    @Test
    fun `a failed prompt rewrite leaves neither a partial file nor its marker`() {
        blockTheWrite()

        FirstRunSetup(context).ensurePromptFix()

        assertEquals(
            legacyBashrc,
            bashrc.readText(),
            "the shell configuration was modified by a write that could not be completed",
        )
        assertFalse(
            bashrc.readText().contains(currentMarker),
            "the marker was left behind by an incomplete write, so every later launch " +
                "reads it as done and the file is never repaired",
        )
    }

    @Test
    fun `the prompt rewrite completes on a later launch once the write can succeed`() {
        val blocker = blockTheWrite()
        FirstRunSetup(context).ensurePromptFix()

        blocker.deleteRecursively()
        FirstRunSetup(context).ensurePromptFix()

        assertTrue(bashrc.readText().contains(currentMarker), "the rewrite never recovered")
        assertTrue(bashrc.readText().contains("export PROJECTS_DIR"), "the retry ate what followed the prompt")
    }

    @Test
    fun `appends the toolchain sourcing line`() {
        FirstRunSetup(context).ensureToolchainEnvSourcing()

        assertTrue(bashrc.readText().contains("toolchain-env.sh"), "the sourcing line was not appended")
        assertTrue(bashrc.readText().contains("alias ll="), "the append lost what was already there")
    }

    @Test
    fun `a failed toolchain sourcing append leaves the file as it was`() {
        blockTheWrite()

        FirstRunSetup(context).ensureToolchainEnvSourcing()

        assertEquals(
            legacyBashrc,
            bashrc.readText(),
            "a partial append survived; `toolchain-env.sh` near its start is this writer's " +
                "own guard, so the half-written line would never be completed",
        )
    }

    @Test
    fun `appends the npm and claude functions`() {
        bundleNpm()

        FirstRunSetup(context).createNpmWrappers()

        val written = bashrc.readText()
        assertTrue(written.contains("npm()"), "the npm function was not appended")
        assertTrue(written.contains("npx()"), "the npx function was not appended")
        assertTrue(written.contains("claude()"), "the claude function was not appended")
        assertTrue(written.contains("alias ll="), "the append lost what was already there")
    }

    @Test
    fun `a failed npm wrapper append leaves the file as it was`() {
        bundleNpm()
        blockTheWrite()

        FirstRunSetup(context).createNpmWrappers()

        assertEquals(
            legacyBashrc,
            bashrc.readText(),
            "a partial append survived; `npm()` opens this writer's own block, so a body " +
                "cut short by process death would satisfy the guard and never be repaired",
        )
    }

    private fun createBashrc() {
        FirstRunSetup::class.java
            .getDeclaredMethod("createBashrc")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    @Test
    fun `writes the shell configuration on a first run`() {
        bashrc.delete()

        createBashrc()

        assertTrue(bashrc.isFile, "no .bashrc was written")
        assertTrue(bashrc.readText().contains("export PROJECTS_DIR"), "the file is not the one we write")
    }

    /**
     * The first-run write is not like the three above it, and the difference is
     * which loop gets to try again.
     *
     * Those three run from SplashActivity on every launch, so a failure heals by
     * itself next time. This one runs only inside `runSetupLocked`, whose
     * `markSetupComplete()` sits at the end of the same try block, and
     * `isFirstRun()` is keyed on versionName -- so a failure that merely logs
     * lets setup be certified with no .bashrc at all, and nothing writes one
     * until the app updates. The every-launch repairs cannot fill the gap
     * either: `createNpmWrappers`, `ensureToolchainEnvSourcing` and
     * `ensurePromptFix` all open with `if (bashrc.exists())`, so with the file
     * absent all three no-op forever too.
     *
     * Failing loudly reaches `runSetupLocked`'s catch, which SplashActivity
     * turns into an error screen with a Retry button. Atomicity is what makes
     * that retry worth pressing: the file is absent rather than truncated, so
     * the retry's own `if (!bashrc.exists())` is true and it writes a complete
     * one. On main the truncated file satisfied that guard and the retry skipped
     * it, so neither half alone produces a working shell.
     */
    @Test
    fun `a failed first-run write leaves nothing behind and fails loudly`() {
        bashrc.delete()
        blockTheWrite()

        val thrown = assertThrows(InvocationTargetException::class.java) { createBashrc() }

        assertTrue(
            thrown.cause is IOException,
            "the failure must reach runSetupLocked's catch so markSetupComplete is skipped; " +
                "it surfaced as ${thrown.cause}",
        )
        assertFalse(
            bashrc.exists(),
            "a truncated .bashrc was left behind; the retry's own exists() guard would skip it",
        )
    }
}
