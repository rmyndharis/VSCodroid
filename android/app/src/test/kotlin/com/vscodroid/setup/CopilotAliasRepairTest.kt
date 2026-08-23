package com.vscodroid.setup

import android.content.Context
import android.system.Os
import android.system.StructStat
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
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
 * The Copilot platform aliases, and the repair they did not used to get.
 *
 * Node reports `process.platform === "android"` here and the Copilot CLI SDK
 * resolves its platform package as `@github/copilot-<platform>-<arch>` with no
 * fallback, so everything the server tree ships for linux-arm64 is invisible on
 * device unless these links exist. They are rebuilt on every launch because AAPT
 * flattens asset symlinks into copies.
 *
 * Presence was the whole test, which is what the three sibling writers
 * (`setupToolSymlinks`, `setupGitCore`, `setupRipgrepVscodeSymlink`) all read the
 * link and compare precisely to avoid. Nothing else rewrites this directory: it
 * is built at runtime under the server tree rather than carried in the assets, so
 * extraction never touches it. An alias written by an earlier release, or one
 * whose upstream entry was renamed, therefore went on pointing where it always
 * had for the life of the install, and the failure is silent: the SDK resolves
 * the alias, follows it to nothing, and chat submit dies before any request is
 * made.
 *
 * `Os` cannot run in a JVM unit test, so the three calls are routed to
 * `java.nio.file` here, as [ToolchainTrampolineLinkTest] does. The links are real
 * and the assertions read them back.
 */
class CopilotAliasRepairTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    private val gh by lazy { File(filesDir, "server/vscode-reh/node_modules/@github") }
    private val linuxPkg by lazy { File(gh, "copilot-linux-arm64") }
    private val alias by lazy { File(gh, "copilot-android-arm64") }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

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

        assertTrue(linuxPkg.mkdirs(), "could not stage the linux-arm64 package")
        File(linuxPkg, "index.js").writeText("// the cli")
        File(linuxPkg, "package.json").writeText("""{"name":"@github/copilot-linux-arm64","version":"1.0.0"}""")
    }

    @AfterEach
    fun tearDown() {
        // Before unmocking, and before JUnit takes the temporary tree away: one
        // case makes this directory read-only, and a read-only parent is exactly
        // what stops its children being deleted -- by the repair under test, and
        // equally by the cleanup.
        alias.setWritable(true)
        unmockkAll()
    }

    private fun aliasTargetOf(name: String): String? =
        runCatching { Files.readSymbolicLink(File(alias, name).toPath()).toString() }.getOrNull()

    @Test
    fun `a tree with no alias gains one`() {
        FirstRunSetup(context).setupCopilotAndroidAliases()

        assertEquals("../copilot-linux-arm64/index.js", aliasTargetOf("index.js"))
        assertTrue(
            File(alias, "package.json").readText().contains("copilot-android-arm64"),
            "the alias manifest still names the linux package, so the SDK resolves nothing",
        )
    }

    /**
     * NEGATIVE CONTROL: restore the `if (link.exists()) return` shape this
     * replaced. The stale link is then left where it is and this reddens.
     */
    @Test
    fun `an alias left pointing at a package that has moved is repointed`() {
        assertTrue(alias.mkdirs(), "could not stage the alias directory")
        Files.createSymbolicLink(
            File(alias, "index.js").toPath(),
            Path.of("../copilot-linux-arm64-1.0.0/index.js"),
        )

        FirstRunSetup(context).setupCopilotAndroidAliases()

        assertEquals(
            "../copilot-linux-arm64/index.js", aliasTargetOf("index.js"),
            "the alias still points at a directory that is not there, and the SDK has no " +
                "fallback to find the package by any other name",
        )
    }

    /**
     * A path that is present and is not a link is a real file, which is not ours
     * to remove on a guess.
     */
    @Test
    fun `a real file under the alias name is left alone`() {
        assertTrue(alias.mkdirs(), "could not stage the alias directory")
        File(alias, "index.js").writeText("someone put this here")

        FirstRunSetup(context).setupCopilotAndroidAliases()

        assertFalse(Files.isSymbolicLink(File(alias, "index.js").toPath()))
        assertEquals("someone put this here", File(alias, "index.js").readText())
    }

    /**
     * A stale alias the process cannot unlink is reported, not passed over in
     * silence.
     *
     * `File.delete()` reports refusal by returning false, and the answer was
     * discarded: the repair fell straight through to `Os.symlink`, which came
     * back EEXIST into a `Logger.d`, and `Logger.d` is gated on a debuggable
     * build. So a release install kept an alias pointing at a package that had
     * moved, the SDK resolved it to nothing, chat submit died before any request
     * was made, and there was no record anywhere that the launch-time repair had
     * been refused.
     *
     * Staged by taking write permission off the parent, which is what POSIX
     * needs to unlink a name from it. If this environment can write into a
     * read-only directory (a test running as root) there is nothing to measure,
     * so the case says so rather than passing.
     *
     * NEGATIVE CONTROL: discard `unlinkStale`'s answer in `linkTo`. The warning
     * is then never emitted and this reddens.
     */
    @Test
    fun `a stale alias that cannot be unlinked is reported`() {
        assertTrue(alias.mkdirs(), "could not stage the alias directory")
        Files.createSymbolicLink(
            File(alias, "index.js").toPath(),
            Path.of("../copilot-linux-arm64-1.0.0/index.js"),
        )
        assertTrue(alias.setWritable(false), "could not make the alias directory read-only")
        val probe = File(alias, "probe~")
        val stillWritable = runCatching { probe.createNewFile() }.getOrDefault(false)
        if (stillWritable) probe.delete()
        assumeFalse(
            stillWritable,
            "this environment writes into a read-only directory, so a refused unlink " +
                "cannot be staged here",
        )

        FirstRunSetup(context).setupCopilotAndroidAliases()

        assertEquals(
            "../copilot-linux-arm64-1.0.0/index.js", aliasTargetOf("index.js"),
            "the fixture did not hold: the stale alias was replaced, so the refusal this " +
                "case is about never happened",
        )
        verify {
            Logger.w(any(), match { it.contains("Copilot platform alias") }, any())
        }
    }

    /**
     * The control. A link that is already right must not be rewritten, or every
     * launch would unlink and relink every entry of the package.
     */
    @Test
    fun `an alias that is already right is not rewritten`() {
        assertTrue(alias.mkdirs(), "could not stage the alias directory")
        Files.createSymbolicLink(
            File(alias, "index.js").toPath(),
            Path.of("../copilot-linux-arm64/index.js"),
        )

        FirstRunSetup(context).setupCopilotAndroidAliases()

        assertEquals("../copilot-linux-arm64/index.js", aliasTargetOf("index.js"))
        verify(exactly = 0) { Os.symlink(any(), any()) }
    }
}
