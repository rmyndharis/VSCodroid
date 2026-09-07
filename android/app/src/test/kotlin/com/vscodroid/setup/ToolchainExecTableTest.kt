package com.vscodroid.setup

import android.content.Context
import android.system.Os
import android.system.StructStat
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

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
    private lateinit var nativeLibDir: File

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

        // Backed by the real filesystem, so a symlink in usr/bin is told apart
        // from a script the way the generator tells them apart on a device.
        mockkStatic(Os::class)
        every { Os.lstat(any()) } answers {
            val path = Path.of(firstArg<String>())
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw java.io.FileNotFoundException(path.toString())
            }
            mockk<StructStat>(relaxed = true)
        }
        every { Os.readlink(any()) } answers {
            Files.readSymbolicLink(Path.of(firstArg<String>())).toString()
        }
        every { Os.symlink(any(), any()) } answers {
            Files.createSymbolicLink(Path.of(secondArg<String>()), Path.of(firstArg<String>()))
            Unit
        }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        // The only directory this app may execve from, and the one every
        // interpreter row has to name. Real files, because the generator declines
        // to write a row naming an interpreter that is not there.
        nativeLibDir = File(filesDir, "nativeLib").apply { mkdirs() }
        File(nativeLibDir, "libnode.so").writeText("elf")
        File(nativeLibDir, "libpython.so").writeText("elf")
        every { context.applicationInfo } answers {
            android.content.pm.ApplicationInfo().apply {
                nativeLibraryDir = this@ToolchainExecTableTest.nativeLibDir.absolutePath
            }
        }

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
     * The table without the row the app owns rather than a toolchain.
     *
     * `xdg-open` is written into every table, on a device with no toolchain
     * installed included, because a browser opener is not a toolchain's to
     * provide. The cases below are each about one toolchain's own rows and say so
     * by reading this; the base row has its own case, which is where a change to
     * it should fail.
     */
    private fun toolchainLines() = tableLines().filterNot { it.startsWith("xdg-open\t") }

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
            toolchainLines(),
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
     * The variables a manifest exports travel in this table too, because of
     * when each reader looks. Bash re-reads `toolchain-env.sh` in every shell;
     * the server process reads the same variables once, at start, and every
     * non-bash child inherits that snapshot. Measured on an API 37 emulator
     * with Ruby installed while the server ran: a `"type": "process"` task
     * found `ruby` through the trampoline and got RUBYLIB unset, a load path
     * inside Termux's prefix and `LoadError` on `require "json"`, while the
     * same probe through bash worked.
     *
     * Expanded to absolute paths for the reason the command rows are, and with
     * `$HOME` resolved as well: the trampoline is not a shell.
     */
    @Test
    fun `each variable the manifest exports gets an environment row`() {
        elf("usr/bin/ruby")
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/lib/ruby","binaries":["usr/bin/ruby"],""" +
                """"env":{"RUBYLIB":"${'$'}FILESDIR/usr/lib/ruby/3.4.0",""" +
                """"GEM_HOME":"${'$'}HOME/.gem/ruby"}}]"""
        )

        regenerate()

        val root = filesDir.absolutePath
        val lines = tableLines()
        assertTrue(
            lines.contains("\tRUBYLIB\t$root/usr/lib/ruby/3.4.0"),
            "a task or a make recipe starting ruby minutes after the install runs it " +
                "without its load path:\n" + execTable.readText(),
        )
        assertTrue(
            lines.contains("\tGEM_HOME\t$root/home/.gem/ruby"),
            "\$HOME was not resolved, and the trampoline is not a shell:\n" + execTable.readText(),
        )
    }

    /**
     * An environment row leads with an empty field, and that is what keeps it
     * harmless on a device whose trampoline predates it: that reader takes the
     * first field as a command name, and a basename is never empty, so it walks
     * past. A marker word would be a name a toolchain could ship (`env` is
     * one), and the old reader would then try to run a variable's name as a
     * path. The command rows are the control: they still lead with the name.
     */
    @Test
    fun `an environment row never starts with a command name`() {
        elf("usr/bin/ruby")
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/lib/ruby","binaries":["usr/bin/ruby"],""" +
                """"env":{"RUBYLIB":"${'$'}FILESDIR/usr/lib/ruby/3.4.0"}}]"""
        )

        regenerate()

        val (envRows, commandRows) = toolchainLines().partition { it.startsWith("\t") }
        assertEquals(1, envRows.size, "expected one environment row:\n" + execTable.readText())
        assertEquals(
            listOf("ruby\t${filesDir.absolutePath}/usr/bin/ruby"), commandRows,
            "the command rows changed shape alongside the environment rows",
        )
    }

    /**
     * A name the record cannot carry costs that variable and nothing else. The
     * trampoline splits a row on its tabs, so a tab inside the name would be
     * read as the value starting early, and the variables after a bad one must
     * still be written: losing the whole toolchain's environment over one key
     * is the larger failure, and the log says which key it was.
     */
    @Test
    fun `a variable name the table cannot carry is skipped and the rest are written`() {
        elf("usr/bin/ruby")
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/lib/ruby","binaries":["usr/bin/ruby"],""" +
                """"env":{"BAD\tNAME":"x","GEM_HOME":"${'$'}HOME/.gem/ruby"}}]"""
        )

        regenerate()

        val lines = tableLines()
        assertTrue(lines.none { it.contains("BAD") }, "a torn record was written:\n" + execTable.readText())
        assertTrue(
            lines.contains("\tGEM_HOME\t${filesDir.absolutePath}/home/.gem/ruby"),
            "one unusable name cost the variables after it:\n" + execTable.readText(),
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
            toolchainLines(),
            "a row was written for a binary that is not on disk",
        )
    }

    /**
     * With nothing installed a removed toolchain's rows go, and the row the app
     * owns stays.
     *
     * The table used to be deleted outright here, which was right while every row
     * in it belonged to a toolchain. It no longer is: `xdg-open` is what a Node
     * browser helper spawns, it has to be reachable on a device that has never
     * installed a toolchain, and that is most devices.
     */
    @Test
    fun `an empty record removes a toolchain's rows and keeps the app's own`() {
        elf("usr/opt/ruby/bin/ruby")
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/opt/ruby",""" +
                """"binaries":["usr/opt/ruby/bin/ruby"]}]"""
        )
        regenerate()
        assertTrue(execTable.isFile, "the table was never written, so this proves nothing")
        assertTrue(toolchainLines().isNotEmpty(), "control: the toolchain never got a row")

        stateFile.writeText("[]")
        regenerate()

        assertEquals(
            emptyList<String>(),
            toolchainLines(),
            "a removed toolchain's rows outlived it",
        )
        assertTrue(
            execTable.isFile,
            "the table went with the last toolchain and took the browser opener with it",
        )
    }

    /**
     * The row the app owns, which no toolchain provides and every device needs.
     *
     * Node's browser helpers spawn the literal command `xdg-open`, so the name is
     * not ours to choose. The interpreter form is: the payload is JavaScript under
     * `filesDir`, which SELinux will not execve, so the row names `libnode.so` in
     * `nativeLibraryDir` and hands it the script.
     */
    @Test
    fun `the browser opener gets a row on a device with no toolchain`() {
        stateFile.writeText("[]")

        regenerate()

        val fields = tableLines().single().split("\t")
        assertEquals(
            3, fields.size,
            "the row is not the interpreter form, so the trampoline would try to execve a " +
                "JavaScript file:\n" + execTable.readText(),
        )
        assertEquals("xdg-open", fields[0], "the command is not the name Node helpers spawn")
        assertTrue(
            fields[1].endsWith("/libnode.so"),
            "the interpreter is not the bundled Node, so nothing can run the payload: ${fields[1]}",
        )
        assertEquals(
            "${filesDir.absolutePath}/server/xdg-open.js", fields[2],
            "the row does not name the opener FirstRunSetup extracts",
        )
    }

    /** A console script pip has written, and the two names beside it that are not. */
    private fun pipBin() = File(filesDir, "usr/bin").apply { mkdirs() }

    /**
     * What `pip install black` leaves behind, and why it does not run without this.
     *
     * pip writes an executable `usr/bin/black` whose first line points at the
     * interpreter, and that directory is already on PATH, so the command is found.
     * Running it then fails: reaching the interpreter means execve on the script's
     * own inode, and SELinux refuses that for anything under filesDir. Measured in
     * the app's terminal as `bad interpreter: Permission denied`.
     *
     * The interpreter form has no such step. The trampoline starts because it lives
     * in nativeLibraryDir, and it runs the bundled Python with the script as an
     * argument, so nothing under filesDir is ever execve'd.
     */
    @Test
    fun `a command pip installed gets a row that runs it through Python`() {
        stateFile.writeText("[]")
        File(pipBin(), "black").writeText("#!${filesDir.absolutePath}/usr/bin/python3\nprint(1)\n")

        regenerate()

        assertEquals(
            listOf(
                "black\t${nativeLibDir.absolutePath}/libpython.so" +
                    "\t${filesDir.absolutePath}/usr/bin/black"
            ),
            tableLines().filter { it.startsWith("black\t") },
            "nothing runs `black`, so a formatter that spawns it still reports it missing:\n" +
                execTable.readText(),
        )
    }

    /**
     * The bundled tools in the same directory are symlinks onto ELFs that already
     * run, and a row for one would send `bash` or `node` through Python.
     */
    @Test
    fun `a bundled tool in the same directory gets no row`() {
        stateFile.writeText("[]")
        Files.createSymbolicLink(
            File(pipBin(), "node").toPath(),
            File(nativeLibDir, "libnode.so").toPath(),
        )

        regenerate()

        assertEquals(
            emptyList<String>(),
            tableLines().filter { it.startsWith("node\t") },
            "a bundled tool was given a row, which would run an ELF through Python",
        )
    }

    /**
     * Only a Python shebang. Handing anything else to Python turns a command that
     * does not start into one that starts and does the wrong thing.
     */
    @Test
    fun `a script naming another interpreter gets no row`() {
        stateFile.writeText("[]")
        File(pipBin(), "somesh").writeText("#!/bin/sh\necho hi\n")

        regenerate()

        assertEquals(
            emptyList<String>(),
            tableLines().filter { it.startsWith("somesh\t") },
            "a shell script was routed through the Python interpreter",
        )
    }

    /**
     * A name that would tear the record gets no row.
     *
     * The trampoline splits a row on its tabs, so a tab in a command name would be
     * read as the row's next field: a path the user never chose. The toolchain rows
     * above come from a manifest this app ships, but anyone can write a file into
     * `usr/bin`, so the name has to be checked here.
     */
    @Test
    fun `a command name the table cannot carry gets no row`() {
        stateFile.writeText("[]")
        val torn = File(pipBin(), "bad\tname")
        // Some filesystems refuse the character outright, which is the same outcome
        // by another route and leaves nothing to assert about.
        val staged = try {
            torn.writeText("#!${filesDir.absolutePath}/usr/bin/python3\nprint(1)\n"); torn.isFile
        } catch (_: Exception) {
            false
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(staged, "the filesystem would not stage the name")

        regenerate()

        assertFalse(
            execTable.readText().contains("bad\tname"),
            "a torn record reached the table:\n" + execTable.readText(),
        )
    }

    /**
     * A toolchain owns its own names. pip writing a script of the same name must
     * not take a toolchain's command away from it.
     */
    @Test
    fun `a toolchain keeps a name pip also installed`() {
        elf("usr/opt/ruby/bin/black")
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/opt/ruby",""" +
                """"binaries":["usr/opt/ruby/bin/black"]}]"""
        )
        File(pipBin(), "black").writeText("#!${filesDir.absolutePath}/usr/bin/python3\nprint(1)\n")

        regenerate()

        assertEquals(
            listOf("black\t${filesDir.absolutePath}/usr/opt/ruby/bin/black"),
            tableLines().filter { it.startsWith("black\t") },
            "the pip script displaced the toolchain's own command",
        )
    }
}
