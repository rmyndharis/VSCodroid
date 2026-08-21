package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.system.Os
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
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The symlinks that put toolchain commands on PATH, and the two ways they go
 * wrong without being noticed.
 *
 * They point from `usr/libexec/tcbin` into `nativeLibraryDir`, which is the only
 * directory this app may execve from. Android hands out a NEW `nativeLibraryDir`
 * path on every reinstall, so every one of them dangles the moment the app is
 * updated, and the failure is silent: `execvp` treats a dangling link as "keep
 * looking further along PATH", so the command simply goes back to failing the
 * way it did before the trampoline existed. That is why the links are rebuilt on
 * every launch rather than only at install time, and it is why staleness is
 * decided by reading the link rather than by `File.exists()`, which follows a
 * link and answers false for a dangling one.
 *
 * `Os` cannot run in a JVM unit test, so the three calls the code makes are
 * routed to `java.nio.file` here. The links, their targets and the sweep are
 * therefore real files on disk and the assertions read them back; what is
 * stubbed is only the syscall wrapper, not the logic under test.
 */
class ToolchainTrampolineLinkTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var stateFile: File
    private lateinit var tcBinDir: File

    /** Stands in for the path Android hands out for the currently installed APK. */
    private var nativeLibDir = "/data/app/~~first==/com.vscodroid-first==/lib/arm64"

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

        // The three syscalls the refresher makes, answered by java.nio so the
        // filesystem effects are real. ErrnoException cannot be constructed
        // usefully here, so a failure surfaces as the IOException java.nio
        // raises, which the production code catches under the same
        // `catch (Exception)` as an ErrnoException.
        mockkStatic(Os::class)
        every { Os.readlink(any()) } answers {
            Files.readSymbolicLink(Path.of(firstArg<String>())).toString()
        }
        every { Os.symlink(any(), any()) } answers {
            Files.createSymbolicLink(Path.of(secondArg<String>()), Path.of(firstArg<String>()))
            Unit
        }
        every { Os.rename(any(), any()) } answers {
            Files.move(
                Path.of(firstArg<String>()), Path.of(secondArg<String>()),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
            Unit
        }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationInfo } answers {
            ApplicationInfo().apply { nativeLibraryDir = this@ToolchainTrampolineLinkTest.nativeLibDir }
        }

        File(filesDir, "home/.vscodroid").mkdirs()
        stateFile = File(filesDir, "home/.vscodroid/toolchains.json")
        tcBinDir = File(filesDir, "usr/libexec/tcbin")
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun elf(relPath: String) = File(filesDir, relPath).apply {
        parentFile?.mkdirs()
        writeBytes(
            byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) +
                ByteArray(64)
        )
    }

    private fun install(vararg commands: String) {
        val paths = commands.joinToString(",") { "\"usr/opt/ruby/bin/$it\"" }
        commands.forEach { elf("usr/opt/ruby/bin/$it") }
        stateFile.writeText(
            """[{"name":"ruby","installRoot":"usr/opt/ruby","binaries":[$paths]}]"""
        )
    }

    private fun regenerate() = ToolchainManager(context).regenerateDerivedFiles()

    private fun linkTarget(name: String): String? =
        try { Files.readSymbolicLink(File(tcBinDir, name).toPath()).toString() } catch (e: Exception) { null }

    private fun expectedTarget() = "$nativeLibDir/libexec-trampoline.so"

    @Test
    fun `every command in the table gets a link onto the trampoline`() {
        install("ruby", "irb")

        regenerate()

        assertEquals(expectedTarget(), linkTarget("ruby"), "ruby is not on PATH as a real file")
        assertEquals(expectedTarget(), linkTarget("irb"), "irb is not on PATH as a real file")
    }

    /**
     * The reinstall case, and the reason this pass runs on every launch.
     *
     * The link written by the install names the `nativeLibraryDir` of the APK
     * that was installed then. An update moves that directory, and the old path
     * is gone, so a check that asked only whether the link EXISTS would leave
     * every toolchain command pointing at nothing.
     */
    @Test
    fun `a link whose target moved is repointed`() {
        install("ruby")
        regenerate()
        assertEquals(expectedTarget(), linkTarget("ruby"), "the first link was never written")

        val beforeUpdate = expectedTarget()
        nativeLibDir = "/data/app/~~second==/com.vscodroid-second==/lib/arm64"

        regenerate()

        assertEquals(
            expectedTarget(), linkTarget("ruby"),
            "the link still names the directory the previous install used, so every " +
                "toolchain command is unreachable after an app update",
        )
        assertFalse(
            linkTarget("ruby") == beforeUpdate,
            "nothing moved, so this test cannot tell a repoint from a no-op",
        )
    }

    /**
     * A name whose toolchain has gone must go with it. Left behind, it still
     * resolves, so the command answers exit 127 from the trampoline saying there
     * is no entry for it rather than the shell's own "command not found" for a
     * command the user has just removed.
     */
    @Test
    fun `a name no longer in the table is swept out of the directory`() {
        install("ruby", "irb")
        regenerate()
        assertTrue(File(tcBinDir, "irb").let { Files.isSymbolicLink(it.toPath()) },
            "irb was never linked, so the sweep below proves nothing")

        install("ruby")
        regenerate()

        assertEquals(expectedTarget(), linkTarget("ruby"), "the surviving command lost its link")
        // Exactly the table's commands and nothing else. Written as an equality
        // rather than as "irb is gone" because this directory is on PATH, so the
        // refresher's own staging files have to leave with the uninstalled names.
        assertEquals(
            listOf("ruby"),
            tcBinDir.list()?.sorted(),
            "the trampoline directory holds something other than the installed commands",
        )
    }

    /**
     * The staging file is never left behind under a name PATH would find. It is
     * dot-prefixed and renamed away, but a failure between the two would leave
     * it, and a directory on PATH is the wrong place for debris.
     */
    /**
     * Uninstall has to take the links and the rows with it, and it does so only
     * because it deletes the payload BEFORE regenerating. Reverse those two and
     * the row survives, the link survives, and the command the user just removed
     * answers exit 127 from the trampoline instead of the shell's own
     * "command not found".
     */
    @Test
    fun `uninstall takes the rows and the links with it`() {
        install("ruby", "irb")
        regenerate()
        assertEquals(expectedTarget(), linkTarget("ruby"), "the install never linked anything")

        val manager = ToolchainManager(context)
        val uninstallSync = ToolchainManager::class.java
            .getDeclaredMethod("uninstallSync", String::class.java)
        uninstallSync.isAccessible = true
        uninstallSync.invoke(manager, "ruby")

        assertFalse(
            File(filesDir, "home/.vscodroid/toolchain-exec.tsv").exists(),
            "the table outlived the toolchain it describes",
        )
        assertEquals(
            emptyList<String>(),
            tcBinDir.list()?.sorted().orEmpty(),
            "a removed toolchain's commands are still on PATH",
        )
    }
}
