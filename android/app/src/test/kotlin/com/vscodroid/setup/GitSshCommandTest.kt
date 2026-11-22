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
import java.nio.file.Files

/**
 * What git hands the bundled ssh, asked of git itself.
 *
 * `GIT_SSH_COMMAND` is built once, when the server starts, and every terminal and task inherits it, so it has to
 * decide at the moment ssh runs whether `~/.ssh/config` can be read. It used to name the file with `-F` whatever its
 * state, and ssh treats a `-F` it cannot open as fatal (`Can't open user config file`, exit 255): a config that is a
 * link into a dotfiles folder that has gone, or one the person deleted, failed every clone, fetch and push over ssh.
 * Leaving `-F` out is not enough on its own, because the Termux ssh finds `~` at a prefix that is not this app's
 * home, so it would look for the key and `known_hosts` where they are not. Without a readable config, ssh is given the
 * options that config holds when the app writes it.
 *
 * A stand-in `libssh.so` records each command line git builds, including the `-G` probe git runs first for an ssh
 * whose name is not `ssh`.
 */
class GitSshCommandTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var nativeLibDir: File

    private lateinit var context: Context

    private val home get() = File(filesDir, "home")
    private val sshConfig get() = File(home, ".ssh/config")
    private val calls get() = File(nativeLibDir, "calls.txt")

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.applicationInfo } returns ApplicationInfo().apply { nativeLibraryDir = this@GitSshCommandTest.nativeLibDir.path }
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        File(home, ".vscodroid").mkdirs()
        val ssh = File(nativeLibDir, "libssh.so")
        ssh.writeText(
            "#!/bin/sh\nprintf '%s\\n' \"\$*\" >> '${calls.path}'\ncase \" \$* \" in *' -G '*) exit 0 ;; esac\nexit 1\n",
        )
        assertTrue(ssh.setExecutable(true), "the stand-in ssh cannot be executed")
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** The command lines git gave ssh for one `git ls-remote` over ssh, with the environment the server starts with. */
    private fun sshCallsOfGit(environment: Map<String, String>): List<String> {
        assumeTrue(File("/bin/sh").canExecute(), "no /bin/sh on this host for git to run the command with")
        val git = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "git") }.firstOrNull { it.canExecute() }
        assumeTrue(git != null, "no git on this host to ask")
        calls.delete()
        val builder = ProcessBuilder(git!!.path, "ls-remote", "ssh://git@example.invalid:2222/repo.git")
            .directory(filesDir).redirectErrorStream(true)
        builder.environment().apply {
            keys.removeAll { it.startsWith("GIT_") }
            put("HOME", home.path)
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_CONFIG_GLOBAL", File(filesDir, "no-gitconfig").path)
            put("GIT_SSH_COMMAND", environment.getValue("GIT_SSH_COMMAND"))
        }
        val (_, output) = finishTestProcess(builder.start())
        assertTrue(calls.isFile, "git never ran the ssh the variable names: $output")
        return calls.readLines().filter { it.isNotBlank() }
    }

    /** The settings of the config the app writes, as ssh takes them on its command line. */
    private fun defaultsAsOptions(): String {
        val scratch = File(filesDir, "scratch").apply { mkdirs() }
        val writer = mockk<Context>(relaxed = true)
        every { writer.filesDir } returns scratch
        FirstRunSetup::class.java.getDeclaredMethod("setupSshDefaults").apply { isAccessible = true }
            .invoke(FirstRunSetup(writer))
        val lines = File(scratch, "home/.ssh/config").readLines().drop(1).filter { it.isNotBlank() }
        assertEquals(4, lines.size, "the config the app writes changed shape: $lines")
        return lines.joinToString(" ") { line ->
            val (key, value) = line.trim().split(Regex("\\s+"), limit = 2)
            "-o $key=${value.replace(scratch.path, filesDir.path)}"
        }
    }

    @Test
    fun `git gives ssh the config when it can be read`() {
        sshConfig.parentFile!!.mkdirs()
        sshConfig.writeText("Host *\n")

        val calls = sshCallsOfGit(Environment.buildProcessEnvironment(context, 1234))

        assertTrue(calls.size >= 2, "expected the -G probe and the connection: $calls")
        for (call in calls) {
            assertTrue(call.startsWith("-F ${sshConfig.path} "), "ssh was not given the config: $call")
            assertFalse(call.contains("IdentityFile="), "options on the command line would override the config: $call")
        }
    }

    /**
     * The variable is the one the server started with, and the link breaks afterwards, which is what a dotfiles
     * folder renamed or checked out without the file does to a running editor.
     */
    @Test
    fun `git gives ssh the app's settings when the config is a link to a folder that has gone`() {
        val dots = File(filesDir, "projects/dotfiles").apply { mkdirs() }
        File(dots, "ssh_config").writeText("Host *\n")
        sshConfig.parentFile!!.mkdirs()
        Files.createSymbolicLink(sshConfig.toPath(), File(dots, "ssh_config").toPath())
        val environment = Environment.buildProcessEnvironment(context, 1234)
        assertTrue(dots.deleteRecursively(), "could not take the dotfiles folder away")

        val calls = sshCallsOfGit(environment)

        assertTrue(calls.size >= 2, "expected the -G probe and the connection: $calls")
        val options = defaultsAsOptions()
        for (call in calls) {
            assertFalse(call.contains("-F "), "ssh was handed a config it cannot open, which it refuses: $call")
            assertTrue(call.startsWith("$options "), "ssh was not given the app's key and known hosts file: $call")
        }
    }

    @Test
    fun `git gives ssh the app's settings when the config was deleted`() {
        sshConfig.parentFile!!.mkdirs()

        val calls = sshCallsOfGit(Environment.buildProcessEnvironment(context, 1234))

        val options = defaultsAsOptions()
        assertTrue(calls.isNotEmpty())
        for (call in calls) {
            assertFalse(call.contains("-F "), "ssh was handed a config that is not there: $call")
            assertTrue(call.startsWith("$options "), "ssh was not given the app's key and known hosts file: $call")
        }
    }
}
