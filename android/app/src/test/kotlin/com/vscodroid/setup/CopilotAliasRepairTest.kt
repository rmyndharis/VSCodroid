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
 * Node reports `process.platform === "android"` here and Copilot builds its
 * platform-named paths from that with no fallback, so what the tree ships under
 * a linux name is invisible on device unless these links exist. They are rebuilt
 * on every launch because AAPT flattens asset symlinks into copies. The alias
 * exercised here is the Copilot extension's `copilot-android-arm64/sdk`.
 *
 * Presence was the whole test, which is what the three sibling writers
 * (`setupToolSymlinks`, `setupGitCore`, `setupRipgrepVscodeSymlink`) all read the
 * link and compare precisely to avoid. Nothing else rewrites this directory: it
 * is built at runtime under the server tree rather than carried in the assets, so
 * extraction never touches it. An alias written by an earlier release, or one
 * whose target was renamed, therefore went on pointing where it always had for
 * the life of the install, and the failure is silent: the name resolves, and
 * leads to nothing.
 *
 * `Os` cannot run in a JVM unit test, so the three calls are routed to
 * `java.nio.file` here, as [ToolchainTrampolineLinkTest] does. The links are real
 * and the assertions read them back.
 */
class CopilotAliasRepairTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    private val reh by lazy { File(filesDir, "server/vscode-reh") }
    private val extensionGh by lazy { File(reh, "extensions/copilot/node_modules/@github") }
    private val alias by lazy { File(extensionGh, "copilot-android-arm64") }

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

        val copilot = File(extensionGh, "copilot")
        assertTrue(File(copilot, "sdk").mkdirs(), "could not stage the extension's SDK")
        File(copilot, "sdk/index.js").writeText("// the sdk")
        File(copilot, "package.json").writeText("""{"name":"@github/copilot","version":"1.0.73"}""")
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

    private fun aliasTarget(): String? =
        runCatching { Files.readSymbolicLink(File(alias, "sdk").toPath()).toString() }.getOrNull()

    @Test
    fun `a tree with no alias gains one`() {
        FirstRunSetup(context).setupCopilotAndroidAliases()

        assertEquals("../copilot/sdk", aliasTarget())
        val manifest = File(alias, "package.json").readText()
        assertTrue(
            manifest.contains("\"@github/copilot-android-arm64\"") && manifest.contains("\"1.0.73\""),
            "the alias manifest does not name the android package at the extension's SDK version: $manifest",
        )
    }

    /**
     * The agent host's link farm over the server's `copilot-linux-arm64` is not
     * built any more: the host does not start here (patch 0020) and from
     * Code - OSS 1.139 the tree does not ship the package. A tree that still has
     * it, an upgrade from 1.4.0 before its pre-extraction prune, gains nothing.
     *
     * NEGATIVE CONTROL: restore the REH block in `setupCopilotAndroidAliases`.
     * It builds `copilot-android-arm64` beside the old runtime and this reddens.
     */
    @Test
    fun `the server's copilot-linux-arm64 is not aliased`() {
        val gh = File(reh, "node_modules/@github")
        assertTrue(File(gh, "copilot-linux-arm64").mkdirs())
        File(gh, "copilot-linux-arm64/index.js").writeText("// the old runtime")
        File(gh, "copilot-linux-arm64/package.json")
            .writeText("""{"name":"@github/copilot-linux-arm64","version":"1.0.79-6"}""")

        FirstRunSetup(context).setupCopilotAndroidAliases()

        assertFalse(
            Files.exists(File(gh, "copilot-android-arm64").toPath(), LinkOption.NOFOLLOW_LINKS),
            "an alias was built over the agent host's runtime, which nothing loads",
        )
    }

    /**
     * NEGATIVE CONTROL: restore the `if (link.exists()) return` shape this
     * replaced. The stale link is then left where it is and this reddens.
     */
    @Test
    fun `an alias left pointing at a package that has moved is repointed`() {
        assertTrue(alias.mkdirs(), "could not stage the alias directory")
        Files.createSymbolicLink(File(alias, "sdk").toPath(), Path.of("../copilot-1.0.0/sdk"))

        FirstRunSetup(context).setupCopilotAndroidAliases()

        assertEquals(
            "../copilot/sdk", aliasTarget(),
            "the alias still points at a directory that is not there",
        )
    }

    /**
     * A path that is present and is not a link is a real file, which is not ours
     * to remove on a guess.
     */
    @Test
    fun `a real directory under the alias name is left alone`() {
        assertTrue(File(alias, "sdk").mkdirs(), "could not stage the alias directory")
        File(alias, "sdk/index.js").writeText("someone put this here")

        FirstRunSetup(context).setupCopilotAndroidAliases()

        assertFalse(Files.isSymbolicLink(File(alias, "sdk").toPath()))
        assertEquals("someone put this here", File(alias, "sdk/index.js").readText())
    }

    /**
     * A stale alias the process cannot unlink is reported, not passed over in
     * silence.
     *
     * `File.delete()` reports refusal by returning false, and the answer was
     * discarded: the repair fell straight through to `Os.symlink`, which came
     * back EEXIST into a `Logger.d`, and `Logger.d` is gated on a debuggable
     * build. So a release install kept an alias pointing at a package that had
     * moved, and there was no record anywhere that the launch-time repair had
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
        Files.createSymbolicLink(File(alias, "sdk").toPath(), Path.of("../copilot-1.0.0/sdk"))
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
            "../copilot-1.0.0/sdk", aliasTarget(),
            "the fixture did not hold: the stale alias was replaced, so the refusal this " +
                "case is about never happened",
        )
        verify {
            Logger.w(any(), match { it.contains("Copilot platform alias") }, any())
        }
    }

    /**
     * The control. A link that is already right must not be rewritten, or every
     * launch would unlink and relink it.
     */
    @Test
    fun `an alias that is already right is not rewritten`() {
        assertTrue(alias.mkdirs(), "could not stage the alias directory")
        Files.createSymbolicLink(File(alias, "sdk").toPath(), Path.of("../copilot/sdk"))

        FirstRunSetup(context).setupCopilotAndroidAliases()

        assertEquals("../copilot/sdk", aliasTarget())
        verify(exactly = 0) { Os.symlink(any(), any()) }
    }
}
