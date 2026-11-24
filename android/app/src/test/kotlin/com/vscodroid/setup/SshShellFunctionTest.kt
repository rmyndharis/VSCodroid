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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files

/**
 * `ssh` in a shell reads the config the app writes.
 *
 * The bundled OpenSSH finds `~` at the prefix Termux compiled in, not at this app's home,
 * so run bare it never read `~/.ssh/config` (a host alias in it was "Could not resolve
 * hostname"), looked for the key where none is and could not save `known_hosts`. Measured
 * on an API 37 emulator against an OpenSSH server: bare `ssh` failed host key
 * verification in batch mode, `ssh srv` for an alias in the config could not resolve
 * `srv`, and the same commands with `-F ~/.ssh/config` connected, authenticated by key
 * and saved the host. The fix is a bash function, for the reason npm is one, in the two
 * files that define the app's shell commands: `.bashrc` for the terminal and the file
 * `BASH_ENV` names for everything else.
 *
 * Pinned from both ends. The first three cases are about the text reaching the two
 * files, and the last three ask bash: a stand-in `ssh` prints what it was given, so what
 * is checked is the command line the function builds and not a string we wrote.
 */
class SshShellFunctionTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var bashrc: File

    private val header = "# ssh: shell function."
    private val scpHeader = "# scp: shell function."
    private val sftpHeader = "# sftp: shell function."

    @BeforeEach
    fun setUp() {
        mockkStatic(Files::class)
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
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        bashrc = File(filesDir, "home/.bashrc")
        bashrc.parentFile?.mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** What [FirstRunSetup.createNpmWrappers] needs on disk before it reconciles `.bashrc`. */
    private fun bundleNpm() {
        val cli = File(filesDir, "usr/lib/node_modules/npm/bin/npm-cli.js")
        cli.parentFile?.mkdirs()
        cli.writeText("// npm")
    }

    @Test
    fun `an existing bashrc gets the block once and keeps what the user wrote`() {
        bundleNpm()
        bashrc.writeText("alias ll='ls -la'\n# a note of mine\n")

        FirstRunSetup(context).createNpmWrappers()
        FirstRunSetup(context).createNpmWrappers()

        val written = bashrc.readText()
        assertEquals(1, written.windowed(header.length).count { it == header }, "the block was appended more than once")
        assertEquals(1, written.windowed(scpHeader.length).count { it == scpHeader }, "the scp block was appended more than once")
        assertEquals(1, written.windowed(sftpHeader.length).count { it == sftpHeader }, "the sftp block was appended more than once")
        assertTrue(written.contains("function ssh {"), "the function is not there")
        assertTrue(written.contains("function scp {"), "the scp function is not there")
        assertTrue(written.contains("function sftp {"), "the sftp function is not there")
        assertTrue(written.startsWith("alias ll='ls -la'\n# a note of mine\n"), "the user's lines were disturbed")
    }

    @Test
    fun `an existing bashrc with ssh alone receives scp and sftp on upgrade`() {
        bundleNpm()
        bashrc.writeText(
            "$header existing\nssh() {\n    command ssh \"\$@\"\n}\n",
        )

        FirstRunSetup(context).createNpmWrappers()

        val written = bashrc.readText()
        assertEquals(1, written.windowed(header.length).count { it == header }, "ssh block was duplicated on upgrade")
        assertEquals(1, written.windowed(scpHeader.length).count { it == scpHeader }, "scp block was not added on upgrade")
        assertEquals(1, written.windowed(sftpHeader.length).count { it == sftpHeader }, "sftp block was not added on upgrade")
    }

    /** Whoever wrote an `ssh` keeps it: bash takes the last definition, so appending ours would replace it. */
    @ParameterizedTest
    @ValueSource(strings = ["ssh() { echo mine; }", "ssh () { echo mine; }", "function ssh { echo mine; }", "function ssh() { echo mine; }"])
    fun `a function of the user's own is not replaced`(definition: String) {
        bundleNpm()
        bashrc.writeText("$definition\n")

        FirstRunSetup(context).createNpmWrappers()

        val written = bashrc.readText()
        assertFalse(written.contains(header), "a block was appended after the user's own ssh: $definition")
        assertTrue(written.contains(scpHeader), "user's ssh blocked scp wrapper unnecessarily")
        assertTrue(written.contains(sftpHeader), "user's ssh blocked sftp wrapper unnecessarily")
    }

    @ParameterizedTest
    @ValueSource(strings = ["scp() { echo mine; }", "scp () { echo mine; }", "function scp { echo mine; }", "function scp() { echo mine; }"])
    fun `a user scp function is not replaced`(definition: String) {
        bundleNpm()
        bashrc.writeText("$definition\n")

        FirstRunSetup(context).createNpmWrappers()

        val written = bashrc.readText()
        assertFalse(written.contains(scpHeader), "scp block appended after user's own scp: $definition")
        assertTrue(written.contains(header), "user's scp blocked ssh wrapper")
        assertTrue(written.contains(sftpHeader), "user's scp blocked sftp wrapper")
    }

    @ParameterizedTest
    @ValueSource(strings = ["sftp() { echo mine; }", "sftp () { echo mine; }", "function sftp { echo mine; }", "function sftp() { echo mine; }"])
    fun `a user sftp function is not replaced`(definition: String) {
        bundleNpm()
        bashrc.writeText("$definition\n")

        FirstRunSetup(context).createNpmWrappers()

        val written = bashrc.readText()
        assertFalse(written.contains(sftpHeader), "sftp block appended after user's own sftp: $definition")
        assertTrue(written.contains(header), "user's sftp blocked ssh wrapper")
        assertTrue(written.contains(scpHeader), "user's sftp blocked scp wrapper")
    }

    @Test
    fun `the file for non-interactive shells defines it too`() {
        FirstRunSetup(context).createBashEnvFile()

        val written = File(Environment.getBashEnvPath(context)).readText()
        assertTrue(written.contains("function ssh {"), "a task, a script or an extension's shell has no ssh function")
        assertTrue(written.contains("function scp {"), "a task, a script or an extension's shell has no scp function")
        assertTrue(written.contains("function sftp {"), "a task, a script or an extension's shell has no sftp function")
        assertTrue(written.contains("-F \"\$HOME/.ssh/config\""), "the functions do not name the app's config")
    }

    private val home get() = File(filesDir, "home")

    /** Stand-in `ssh`, `scp`, and `sftp` that only print their arguments, found first on PATH. */
    private fun standInSsh(): File {
        val bin = File(filesDir, "fakebin").apply { mkdirs() }
        for (cmd in listOf("ssh", "scp", "sftp")) {
            val binary = File(bin, cmd)
            binary.writeText("#!/bin/sh\nprintf 'ARGS:%s\\n' \"\$*\"\n")
            assertTrue(binary.setExecutable(true), "the stand-in $cmd cannot be executed")
        }
        return bin
    }

    /** Non-empty lines of `bash -c $script` with the generated BASH_ENV file, HOME at the app's home and the stand-in first on PATH. */
    private fun runBash(script: String): List<String> {
        val bash = File("/bin/bash")
        assumeTrue(bash.canExecute(), "no /bin/bash on this host to ask")
        FirstRunSetup(context).createBashEnvFile()
        val builder = ProcessBuilder("/bin/bash", "-c", script).directory(filesDir).redirectErrorStream(true)
        builder.environment().apply {
            // Either of these makes a non-interactive bash read ~/.bashrc instead of $BASH_ENV.
            remove("SSH_CLIENT")
            remove("SSH2_CLIENT")
            put("BASH_ENV", Environment.getBashEnvPath(context))
            put("HOME", home.apply { mkdirs() }.path)
            put("PATH", standInSsh().path + File.pathSeparator + (get("PATH") ?: "/usr/bin:/bin"))
        }
        val out = finishTestProcess(builder.start()).second
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    @Test
    fun `bash runs ssh against the config the app wrote`() {
        File(home, ".ssh").mkdirs()
        File(home, ".ssh/config").writeText("Host srv\n    HostName 10.0.2.2\n")

        val out = runBash("ssh -p 2222 srv true")

        assertEquals(
            listOf("ARGS:-F ${home.path}/.ssh/config -p 2222 srv true"),
            out,
            "ssh was not given the app's config, so an alias or the default key is never read",
        )
    }

    /** `-F` on a missing file is a fatal error, so a user who deleted the config still gets an ssh. */
    @Test
    fun `without a config bash runs the bare command`() {
        val out = runBash("ssh -p 2222 host true")

        assertEquals(listOf("ARGS:-p 2222 host true"), out)
    }

    /** ssh keeps the last `-F`, so the one a user types after ours is the one that applies. */
    @Test
    fun `a config the user names is still the one that applies`() {
        File(home, ".ssh").mkdirs()
        File(home, ".ssh/config").writeText("Host *\n")

        val out = runBash("ssh -F /tmp/other host true")

        assertEquals(
            listOf("ARGS:-F ${home.path}/.ssh/config -F /tmp/other host true"),
            out,
            "the user's -F does not come after the function's, so it would be the one ignored",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["ssh", "scp", "sftp"])
    fun `bash runs ssh, scp, and sftp consistently with and without config`(cmd: String) {
        assertEquals(listOf("ARGS:host"), runBash("$cmd host"))

        File(home, ".ssh").mkdirs()
        File(home, ".ssh/config").writeText("Host srv\n    HostName 10.0.2.2\n")
        assertEquals(listOf("ARGS:-F ${home.path}/.ssh/config srv"), runBash("$cmd srv"))
        assertEquals(
            listOf("ARGS:-F ${home.path}/.ssh/config -F /tmp/other srv"),
            runBash("$cmd -F /tmp/other srv"),
        )
    }

    /** Interactive bash expands aliases on `cmd() {` headers, breaking .bashrc; `function cmd {` does not. */
    @Test
    fun `user aliases for ssh, scp, and sftp do not break interactive bashrc sourcing`() {
        val bash = File("/bin/bash")
        assumeTrue(bash.canExecute(), "no /bin/bash on this host to ask")
        bundleNpm()
        File(home, ".ssh").mkdirs()
        File(home, ".ssh/config").writeText("Host srv\n    HostName 10.0.2.2\n")
        bashrc.writeText(
            """
            alias ssh='ssh -v'
            alias scp='scp -r'
            alias sftp='sftp -C'
            """.trimIndent() + "\n",
        )
        FirstRunSetup(context).createNpmWrappers()

        val builder = ProcessBuilder(
            "/bin/bash", "--rcfile", bashrc.path, "-i", "-c",
            "ssh srv; scp a srv:b; sftp srv",
        ).directory(filesDir).redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", home.path)
            put("PATH", standInSsh().path + File.pathSeparator + (get("PATH") ?: "/usr/bin:/bin"))
        }
        val (status, out) = finishTestProcess(builder.start())
        assertEquals(0, status, "interactive bash failed with: $out")
        assertFalse(out.contains("syntax error"), "wrapper header expanded user alias into syntax error: $out")
        val lines = out.lines().map { it.trim() }.filter { it.startsWith("ARGS:") }
        assertEquals(
            listOf(
                "ARGS:-F ${home.path}/.ssh/config -v srv",
                "ARGS:-F ${home.path}/.ssh/config -r a srv:b",
                "ARGS:-F ${home.path}/.ssh/config -C srv",
            ),
            lines,
        )
    }
}
