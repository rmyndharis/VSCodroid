package com.vscodroid.setup

import android.content.Context
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
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
 * `.bash_profile` and `.tmux.conf`, the two setup files that were still written
 * straight to their destination.
 *
 * Both are guarded by their own existence and both are reached only from
 * `runSetupLocked`, which `isFirstRun()` gates on versionName or versionCode:
 * no per-launch repair reads either path, and `repairTruncatedSetupFiles`
 * covers `.bashrc` and settings.json alone. `writeText` creates and truncates
 * before writing a byte, so a write that failed left a file that satisfied the
 * guard for the life of the install, and one Retry tap cemented it.
 *
 * What that costs is confined to INTERACTIVE LOGIN shells, which is what a tmux
 * window is: they read `.bash_profile` and never `.bashrc` or `BASH_ENV`, so
 * they come up with no prompt block, no aliases and no `npm`/`npx`/`claude`,
 * while the editor's own terminals (profile args are empty, so not login
 * shells) and `bash -c` (covered by `createBashEnvFile` through `BASH_ENV`) are
 * unaffected, and `PROJECTS_DIR` still arrives through the process
 * environment.
 *
 * The failure is arranged as in [BashrcAtomicityTest]: by occupying the
 * temporary path [writeAtomically] derives from the destination. That path only
 * exists to be occupied once the writer goes through it, which is the point.
 */
class LoginShellFilesAtomicityTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    private val bashProfile by lazy { File(filesDir, "home/.bash_profile") }
    private val tmuxConf by lazy { File(filesDir, "home/.tmux.conf") }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir

        File(filesDir, "home").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun invoke(name: String) {
        FirstRunSetup::class.java
            .getDeclaredMethod(name)
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    /** Non-empty, so the cleanup `delete()` cannot quietly reclaim it. */
    private fun block(dest: File) {
        val blocker = File(dest.parentFile, "${dest.name}.tmp~")
        assertTrue(blocker.mkdirs(), "could not stage the blocked temp path")
        File(blocker, "occupied").writeText("x")
    }

    @Test
    fun `the login shell profile is written on a first run`() {
        // The control. Both failure assertions below would also hold for a
        // writer that had stopped doing anything at all.
        invoke("createBashProfile")

        assertTrue(bashProfile.readText().contains(". \"\$HOME/.bashrc\""))
    }

    /**
     * NEGATIVE CONTROL: put `bashProfile.writeText(content)` back in place of
     * the atomic write. Nothing then goes near the temp path, the write
     * succeeds, and both assertions fail: no exception is thrown and a file is
     * left behind.
     */
    @Test
    fun `a failed profile write leaves nothing behind and fails loudly`() {
        block(bashProfile)

        val thrown = assertThrows(InvocationTargetException::class.java) { invoke("createBashProfile") }

        assertTrue(
            thrown.cause is IOException,
            "the failure must reach runSetupLocked's catch, which is what offers the Retry; " +
                "it surfaced as ${thrown.cause}",
        )
        assertFalse(
            bashProfile.exists(),
            "a truncated .bash_profile was left behind, and its own exists() guard means no " +
                "retry and no later launch would ever replace it",
        )
    }

    @Test
    fun `the tmux configuration is written on a first run`() {
        invoke("createTmuxConf")

        assertTrue(tmuxConf.readText().contains("set -g mouse on"))
    }

    /**
     * The one place these two part company. A lost `.tmux.conf` costs the
     * mouse, the colours and the scrollback, and tmux starts perfectly well
     * without them, so failing an 875 MB unpack over five options would cost
     * the user more than the options are worth.
     *
     * NEGATIVE CONTROL: restore `tmuxConf.writeText(content)`. The write then
     * succeeds past the blocked temp path and the file exists, so the assertion
     * that nothing partial was left fails. Making it throw instead reddens the
     * first half.
     */
    @Test
    fun `a failed tmux write is quiet and leaves nothing behind`() {
        block(tmuxConf)

        // Must not throw: it runs between createBashrc and the extension unpack.
        invoke("createTmuxConf")

        assertFalse(
            tmuxConf.exists(),
            "a truncated .tmux.conf was left behind; tmux reads it on every start and " +
                "nothing here would ever rewrite it",
        )
    }
}
