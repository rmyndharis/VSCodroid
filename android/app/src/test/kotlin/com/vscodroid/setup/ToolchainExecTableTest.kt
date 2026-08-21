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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What `toolchain-exec.tsv` has to contain for a toolchain command to work when
 * the caller is a program rather than a person.
 *
 * The loader wrappers in `toolchain-env.sh` are bash functions, so they are
 * reachable from bash and from nothing else. A direct `spawn("ruby", args)` from
 * an extension, a `make` recipe (mksh, by way of `patch-default-shell.py`), and
 * a `"type": "process"` task all resolve a bare name against PATH and execve
 * what they find, which is an ELF under filesDir that SELinux refuses. This
 * table is what the execution trampoline reads to answer the same question for
 * those callers, and the trampoline is a C program, not a shell: every claim
 * below is about the difference that makes.
 *
 * Deliberately separate from [ToolchainEnvFileTest] rather than folded into it.
 * The two generators read the same state and must NOT agree on everything, and
 * the third test here is the one that keeps them from being tidied together.
 */
class ToolchainExecTableTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var stateFile: File
    private lateinit var execTable: File
    private lateinit var envFile: File

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // Play Core is reached through field initialisation, so it runs before
        // any method can be called on the manager.
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir

        File(filesDir, "home/.vscodroid").mkdirs()
        stateFile = File(filesDir, "home/.vscodroid/toolchains.json")
        execTable = File(filesDir, "home/.vscodroid/toolchain-exec.tsv")
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

    private fun regenerate() = ToolchainManager(context).regenerateDerivedFiles()

    private fun tableLines() = execTable.readText().lines().filter { it.isNotEmpty() }

    /**
     * The trampoline gets no working directory it can trust and no shell to
     * expand anything, so the row has to name the payload outright. The env file
     * writes `$PREFIX/../usr/...` for its own reader, and copying that spelling
     * here would produce a path with a literal `$PREFIX` component in it.
     */
    @Test
    fun `an ELF binary gets one absolute row`() {
        elf("usr/opt/ruby/bin/ruby")
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/opt/ruby",""" +
                """"binaries":["usr/opt/ruby/bin/ruby"]}]"""
        )

        regenerate()

        assertEquals(
            listOf("ruby\t${filesDir.absolutePath}/usr/opt/ruby/bin/ruby"),
            tableLines(),
            "the trampoline cannot resolve this row, so `ruby` from a task or a " +
                "make recipe still fails:\n" + execTable.readText(),
        )
    }

    /**
     * A shebang script under filesDir is refused on its own inode before its
     * interpreter is ever consulted, so a script row carries two paths: the
     * interpreter to load, then the script to hand it. Order decides which is
     * which, and the interpreter has to be the absolute path of an ELF the
     * manifest ships, because the trampoline must never search PATH itself: a
     * poisoned PATH would otherwise choose what runs a toolchain's own scripts.
     */
    @Test
    fun `a script row names the interpreter first, then the script`() {
        elf("usr/opt/ruby/bin/ruby")
        script("usr/opt/ruby/bin/gem")
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/opt/ruby",""" +
                """"binaries":["usr/opt/ruby/bin/ruby","usr/opt/ruby/bin/gem"],""" +
                """"scriptWrappers":{"interpreter":"ruby",""" +
                """"scripts":{"gem":"usr/opt/ruby/bin/gem"}}}]"""
        )

        regenerate()

        val root = filesDir.absolutePath
        assertTrue(
            tableLines().contains("gem\t$root/usr/opt/ruby/bin/ruby\t$root/usr/opt/ruby/bin/gem"),
            "the script row is not interpreter-then-script with both resolved:\n" +
                execTable.readText(),
        )
    }

    /**
     * The one rule the two generators must NOT share.
     *
     * `toolchain-env.sh` skips a command [isShellFunctionName] refuses, because
     * one unusable name is a parse error that takes out every new terminal. The
     * trampoline has no naming constraint at all: a symlink can be called
     * anything a filesystem allows. Applying that filter here out of symmetry
     * would drop exactly the commands this mechanism exists to reach, and the
     * only visible sign would be one missing command.
     *
     * The fixture is `[`, coreutils' own name for `test` and therefore a name a
     * regenerated manifest can genuinely carry. It is also chosen because the
     * predicate really does refuse it: `2to3` and `foo-bar` LOOK like the
     * awkward cases and are not, which `ShellFunctionNameTest` establishes
     * against a real bash. A fixture named after one of those leaves this test
     * passing whether or not the filter has been copied over, which is how it
     * was first written.
     */
    @Test
    fun `a name the shell wrapper skips still gets a row`() {
        // Stated rather than assumed, so the fixture cannot quietly stop being
        // the awkward case the rest of this test is about.
        assertFalse(isShellFunctionName("["), "the fixture is no longer a name the env file skips")

        elf("usr/opt/ruby/bin/[")
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/opt/ruby",""" +
                """"binaries":["usr/opt/ruby/bin/["]}]"""
        )

        regenerate()

        assertTrue(
            tableLines().any { it.startsWith("[\t") },
            "the shell's naming rule was applied to a file name:\n" + execTable.readText(),
        )
        // The control for the claim above: if the env file also carried it, the
        // two generators would not in fact differ and this test would be
        // asserting nothing about them.
        assertFalse(
            envFile.exists() && envFile.readText().contains("[()"),
            "the env file wrapped a name its own predicate refuses",
        )
    }

    /**
     * Damage is not absence, exactly as for the env file.
     *
     * A state file nothing can parse is what a device upgrading from the build
     * that truncated it in place carries. Reading it as "no toolchains" would
     * delete the table, taking every toolchain command off PATH on every launch
     * while the payload it names is still on disk and still runnable.
     */
    @Test
    fun `a state file that cannot be parsed leaves the table alone`() {
        elf("usr/opt/ruby/bin/ruby")
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/opt/ruby",""" +
                """"binaries":["usr/opt/ruby/bin/ruby"]}]"""
        )
        regenerate()
        val writtenByTheInstall = execTable.readText()

        stateFile.writeText("""[{"name":"ruby","installRoot":"usr/op""")

        regenerate()

        assertTrue(execTable.isFile, "the table was deleted over an unreadable state")
        assertEquals(
            writtenByTheInstall, execTable.readText(),
            "the table was rewritten over an unreadable state",
        )
    }

    /**
     * A manifest naming a binary that is not on disk, a partial extraction, or a
     * file an uninstall took, gets no row. A row for it would be a command that
     * exists on PATH and always fails, which is worse than one that is not there.
     */
    @Test
    fun `a binary that is not on disk gets no row`() {
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/opt/ruby",""" +
                """"binaries":["usr/opt/ruby/bin/ruby"]}]"""
        )

        regenerate()

        assertEquals(
            emptyList<String>(),
            tableLines(),
            "a row was written for a binary that is not on disk",
        )
    }

    /**
     * With nothing installed the table goes, rather than being left as the last
     * record of a toolchain the user has removed.
     */
    @Test
    fun `an empty record removes the table`() {
        elf("usr/opt/ruby/bin/ruby")
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/opt/ruby",""" +
                """"binaries":["usr/opt/ruby/bin/ruby"]}]"""
        )
        regenerate()
        assertTrue(execTable.isFile, "the table was never written, so this proves nothing")

        stateFile.writeText("[]")
        regenerate()

        assertFalse(execTable.exists(), "the table outlived the last toolchain")
    }
}
