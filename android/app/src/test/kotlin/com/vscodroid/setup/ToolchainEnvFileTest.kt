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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What `toolchain-env.sh` has to contain for a downloaded toolchain to run at
 * all.
 *
 * SELinux denies `execute_no_trans` on `app_data_file`, so nothing whose inode
 * lives under `filesDir` can be `execve`d whatever its mode -- and every byte of
 * a downloaded toolchain lives there. The only thing that makes `go` or `ruby` a
 * working command is the wrapper this file emits: a shell function that hands the
 * binary to `/system/bin/linker64`, which the app is allowed to execute, and
 * which loads the payload itself.
 *
 * That indirection had no test. The predicate it consults did -- dozens of names
 * against a real bash -- while the line that actually routes through the loader
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

    private fun regenerate() = ToolchainManager(context).regenerateEnvFile()

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
            lines.contains("""export PATH="${'$'}PREFIX/../usr/opt/ruby/bin:${'$'}PATH""""),
            "the toolchain's PATH entry is missing:\n" + envFile.readText(),
        )
    }

    /**
     * Only ELF objects get a loader wrapper. A script handed to `linker64` is not
     * a program it can load, so wrapping one would replace "this command does not
     * exist" with a command that exists and always fails -- and it would shadow
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
     * A manifest naming a binary that is not on disk -- a partial extraction, or
     * a file an uninstall took -- produces no wrapper for it either. Same guard,
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
}
