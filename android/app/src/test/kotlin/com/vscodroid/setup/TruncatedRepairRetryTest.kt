package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vscodroid.util.Environment
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What `repairTruncatedSetupFiles` must leave behind when its rewrite fails.
 *
 * The repair exists for an install an older release left with a zero-byte
 * `.bashrc` or settings.json, and the condition that produced those is a full
 * disk, which is still the condition when the repair runs. So the rewrite
 * failing is not an exotic case here, it is the expected one, and until now it
 * was the one path that could make the install permanently worse: the file was
 * deleted first to satisfy the writers' `!exists()` guards, and a rewrite that
 * then failed left nothing. Absence is outside the repair's own re-entry test,
 * outside every per-launch appender (`createNpmWrappers`,
 * `ensureToolchainEnvSourcing`, `ensurePromptFix`, `ensureStartupDirGuard`,
 * `updateSettingsNativeLibPaths`, all `exists()`-gated) and outside
 * `createBashrc`/`createDefaultSettings`, which `runSetupLocked` reaches only
 * when versionName or versionCode moves. One launch that could not write turned
 * a self-healing install into one that recovers at the next app update or not
 * at all.
 *
 * Writing through [writeAtomically]'s rename instead of deleting first is the
 * whole fix, and what these measure is the property that follows from it: after
 * a failed rewrite the broken file is still there, so the repair fires again on
 * the next launch and freeing space is enough. The last test here is where that
 * property stops being unconditional: the appenders behind the repair can see a
 * file it could not rewrite, and for one of the two shapes what they add takes
 * the retry away. `repairTruncatedSetupFiles` documents which and why.
 *
 * The failure is arranged as in [BashrcAtomicityTest]: by occupying the
 * temporary path [writeAtomically] derives from the destination.
 */
class TruncatedRepairRetryTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    private val bashrc by lazy { File(filesDir, "home/.bashrc") }
    private val settings by lazy { File(Environment.getMachineSettingsPath(context)) }

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
        // Pinned so the files these writers produce do not depend on what a
        // relaxed mock invents for the projects directory.
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        bashrc.parentFile?.mkdirs()
        settings.parentFile?.mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    /** Non-empty, so the cleanup `delete()` cannot quietly reclaim it. */
    private fun block(dest: File): File =
        File(dest.parentFile, "${dest.name}.tmp~").also {
            assertTrue(it.mkdirs(), "could not stage the blocked temp path")
            File(it, "occupied").writeText("x")
        }

    /**
     * NEGATIVE CONTROL: restore `&& bashrc.delete()` to the branch condition in
     * `repairTruncatedSetupFiles`. The blocked launch then removes the file and
     * writes nothing, so `bashrc.isFile` fails; keep the delete and skip the
     * blocker and the second launch never re-enters the branch, so the healing
     * assertion fails too.
     */
    @Test
    fun `a blocked bashrc rewrite keeps the broken file and heals on the next launch`() {
        bashrc.writeText("")
        val blocker = block(bashrc)

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(
            bashrc.isFile,
            "the repair deleted the .bashrc it could not rewrite; nothing writes one again " +
                "until the app updates",
        )
        assertEquals("", bashrc.readText(), "the destination is supposed to be untouched")

        // The user frees space and launches again. That is the whole value of
        // leaving the empty file: it is what the repair re-enters through.
        assertTrue(blocker.deleteRecursively(), "could not free the temp path")
        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(
            bashrc.readText().contains("export PROJECTS_DIR"),
            "the install did not heal once there was room to write",
        )
    }

    /**
     * The settings half, which cannot even buy space by clearing: deleting a
     * zero-byte file frees nothing, so the disk that emptied it is still full
     * when the rewrite runs.
     *
     * NEGATIVE CONTROL: restore `&& settings.delete()` to the condition in
     * `repairTruncatedSetupFiles`. The first assertion fails on the blocked
     * launch, and with the blocker removed the second launch finds no file to
     * classify and writes nothing.
     */
    @Test
    fun `a blocked settings rewrite keeps the empty file and heals on the next launch`() {
        settings.writeText("")
        val blocker = block(settings)

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(
            settings.isFile,
            "the repair deleted the settings.json it could not rewrite, which is outside " +
                "every guard that would have tried again",
        )

        assertTrue(blocker.deleteRecursively(), "could not free the temp path")
        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(
            settings.readText().contains("terminal.integrated.profiles.linux"),
            "the managed settings did not come back once there was room to write",
        )
    }

    /**
     * The half of the retry that survives the appenders, which is what turns the
     * KDoc's bound into a measured one.
     *
     * SplashActivity runs this repair immediately before `createNpmWrappers` and
     * `ensureToolchainEnvSourcing`, and not deleting is what lets those two see a
     * file the repair judged unusable and could not rewrite. For the header shape
     * their append changes nothing the classification reads: the header is still
     * first and `PROJECTS_DIR` is still absent, so the next launch repairs it
     * anyway. The empty shape is where that stops being true, and the KDoc says
     * where the line is and why widening the test to reach it would cost more
     * than it buys.
     *
     * The blocker is freed before the append on purpose. It is the only route to
     * a failed rewrite followed by a successful append: both writes derive the
     * same temporary path from the same destination, so whatever stops one stops
     * the other.
     *
     * NEGATIVE CONTROL: change `text.startsWith(BASHRC_HEADER)` in
     * `repairTruncatedSetupFiles` to `text.trim() == BASHRC_HEADER`, the same
     * classification asked of the whole file rather than of its opening.
     * Measured: the append then de-classifies the file and the last assertion
     * below fails on its own, with the other three tests in this class green.
     * Restoring `&& bashrc.delete()` to the branch condition reddens this test
     * as well, at the "untouched" assertion rather than the healing one, since
     * `readText` is then left with no file to read.
     */
    @Test
    fun `an append between launches leaves the header shape repairable`() {
        // createNpmWrappers returns early unless npm was extracted, and an
        // appender that does not run would satisfy every assertion below.
        File(filesDir, "usr/lib/node_modules/npm/bin").mkdirs()
        File(filesDir, "usr/lib/node_modules/npm/bin/npm-cli.js").writeText("//")

        val header = "# VSCodroid bash configuration\n"
        bashrc.writeText(header)
        val blocker = block(bashrc)
        val setup = FirstRunSetup(context)

        setup.repairTruncatedSetupFiles()
        assertEquals(header, bashrc.readText(), "the destination is supposed to be untouched")

        // Room appears between the two calls. Nothing else lets the append below
        // succeed where the rewrite above failed.
        assertTrue(blocker.deleteRecursively(), "could not free the temp path")
        setup.createNpmWrappers()
        assertTrue(bashrc.readText().contains("npm()"), "the append under test never ran")
        assertFalse(
            bashrc.readText().contains("export PROJECTS_DIR"),
            "the append was supposed to leave the file still needing repair",
        )

        setup.repairTruncatedSetupFiles()
        assertTrue(
            bashrc.readText().contains("export PROJECTS_DIR"),
            "an append behind the repair cost the header shape its retry",
        )
    }

    /**
     * The control for both, and it is not decoration: a repair that had stopped
     * doing anything would satisfy every "the file is still there" assertion
     * above.
     */
    @Test
    fun `files that are fine are not rewritten`() {
        val theirs = "# my own shell setup\nalias g='git'\n"
        bashrc.writeText(theirs)
        val theirSettings = """{"editor.fontSize": 20}"""
        settings.writeText(theirSettings)

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertEquals(theirs, bashrc.readText(), "a file the user wrote was replaced")
        assertEquals(theirSettings, settings.readText(), "a settings file with content was replaced")
    }
}
