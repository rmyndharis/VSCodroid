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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * Which entries in `usr/lib/git-core` get the execute bit, and which must not.
 *
 * The directory holds two kinds. Real extracted files, the shell helpers git
 * sources or execs by name (`git-submodule`, `git-mergetool`, `git-sh-setup`)
 * and a handful of standalone binaries, all of which arrive from the recursive
 * copy without modes and genuinely need the bit. And roughly 150 symlinks
 * `setupGitCore` has just pointed into `nativeLibraryDir`.
 *
 * `File.isFile` follows a link, so the pass chmod'ed the LINK TARGET inside
 * `nativeLibraryDir`, which SELinux refuses this app: `avc: denied { setattr }
 * ... tcontext=...apk_data_file` on every cold start, for a bit those files
 * already carry. `setExecutable` reports refusal by returning false and the
 * result was discarded, so nothing said the call had done nothing.
 *
 * Both halves are asserted here, because removing the pass altogether would
 * satisfy the link half on its own and take the helpers' execute bit with it.
 */
class GitCoreExecutableBitTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var elsewhere: File

    private lateinit var context: Context

    private val gitCore get() = File(filesDir, "usr/lib/git-core")

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs

        assertTrue(gitCore.mkdirs(), "could not stage git-core")
        // The manifest is what makes setupGitCore do anything at all.
        File(gitCore, "gitcore-symlinks").writeText("git-add\n")

        // Deliberately empty: with no libgit.so to point at, the linking loop
        // skips every entry and what runs is only the pass under test.
        val nativeLibDir = File(elsewhere, "nativeLibs").apply { mkdirs() }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationInfo } returns
            ApplicationInfo().apply { nativeLibraryDir = nativeLibDir.absolutePath }
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    @Test
    fun `a real helper still gets the execute bit`() {
        val helper = File(gitCore, "git-submodule").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(false, false)
        }
        assertFalse(helper.canExecute(), "fixture must start without the bit")

        FirstRunSetup(context).setupGitCore()

        assertTrue(
            helper.canExecute(),
            "the shell helper git execs by name is still not executable",
        )
    }

    @Test
    fun `a link is not followed, so its target keeps the mode it had`() {
        val target = File(elsewhere, "libgit.so").apply {
            writeText("not really an ELF")
            setExecutable(false, false)
        }
        assumeTrue(
            runCatching {
                Files.createSymbolicLink(File(gitCore, "git-add").toPath(), target.toPath())
            }.isSuccess,
            "this filesystem does not allow creating symlinks",
        )

        FirstRunSetup(context).setupGitCore()

        assertFalse(
            target.canExecute(),
            "the pass followed the link and chmod'ed a file in nativeLibraryDir, which " +
                "SELinux denies on device and which needed nothing done to it",
        )
    }
}
