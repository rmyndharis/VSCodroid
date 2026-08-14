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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Both places the bundled shell is named have to name the symlink, not the
 * binary it points at.
 *
 * The ptyHost decides whether to inject shell integration by switching on the
 * executable's *basename*: `bash` selects `["--init-file", …]` and `libbash.so`
 * matches no case at all, so the injection is skipped without a word. Command
 * decorations, cwd detection and command duration all quietly stop working, and
 * the terminal still opens and still runs commands — there is nothing to notice.
 *
 * `getTerminalShellPath` exists for that single reason and had no test, while
 * `getBashPath`, which returns the `.so` and has no caller in production at all,
 * had two. The coverage was not thin, it was inverted: the value nothing used
 * was pinned precisely, and the value three call sites depend on was pinned
 * nowhere. Swapping one for the other is the obvious tidy-up, and nothing in the
 * suite moved when it was tried.
 *
 * These assert the basename rather than the whole path, because the basename is
 * the part that carries the behaviour. A test spelling out the full string would
 * repeat the mistake it exists to catch: it would pass just as happily against a
 * `.so`, since the value would still be exactly what the function returns.
 */
class TerminalShellPathTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var nativeLibDir: File

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs

        // A real directory, because the production code decides which shell to
        // name by asking whether the bundled one is on disk.
        nativeLibDir = File(tempDir, "nativeLibs").apply { mkdirs() }
        context = mockk(relaxed = true)
        every { context.applicationInfo } returns
            ApplicationInfo().apply { nativeLibraryDir = nativeLibDir.absolutePath }
        every { context.filesDir } returns tempDir
        every { context.cacheDir } returns tempDir
        // A relaxed mock answers this with a mocked File whose path is null, and
        // getProjectsDir builds a File from it -- so it fails inside the code
        // under test for a reason that has nothing to do with the shell.
        every { context.getExternalFilesDir(any()) } returns File(tempDir, "external")

        // buildProcessEnvironment folds in the installed toolchains, and
        // ToolchainManager takes an asset-pack manager in a field initialiser, so
        // merely constructing it runs Play Core's static setup -- which cannot
        // complete off-device. The caller guards that lookup with
        // catch(Exception) and what arrives is an Error, so the guard does not
        // apply here; on a device the classes are present and it never comes up.
        // Stubbing the factory is enough: nothing further in Play Core loads.
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun bundleBash() = File(nativeLibDir, "libbash.so").apply { writeText("") }

    @Nested
    inner class ShellVariableTest {

        @Test
        fun `SHELL names something called bash, not the library behind it`() {
            bundleBash()

            val shell = Environment.buildProcessEnvironment(context, 1234)["SHELL"]

            assertTrue(shell != null && shell.isNotEmpty(), "SHELL must be set")
            assertEquals(
                "bash", File(shell!!).name,
                "SHELL is what a terminal falls back to with no profile, and the ptyHost " +
                    "keys shell integration off this basename; got $shell"
            )
        }

        @Test
        fun `falls back to the system shell when the bundled one is absent`() {
            // The other half of the same branch. Without it, an inverted
            // condition would still satisfy the test above on a device that
            // happens to have the file.
            assertEquals(
                "/system/bin/sh", Environment.buildProcessEnvironment(context, 1234)["SHELL"],
                "with no bundled bash on disk there is nothing else to fall back to"
            )
        }
    }

    @Nested
    inner class TerminalProfileTest {

        @Test
        fun `the profile path names something called bash`() {
            bundleBash()
            createDefaultSettings()

            val path = profilePath()
            assertEquals(
                "bash", File(path).name,
                "the workbench looks up injection arguments by this basename; got $path"
            )
            assertNotEquals(
                "libbash.so", File(path).name,
                "naming the library directly is the regression this exists for"
            )
        }

        /**
         * Runs the real writer rather than asserting on the string it embeds.
         *
         * The point is the value reaching the file the workbench reads, and the
         * method is private; reflection reaches it the same way
         * [com.vscodroid.service.ProcessManager]'s tests reach private state,
         * rather than widening production API for a test.
         */
        private fun createDefaultSettings() {
            FirstRunSetup::class.java
                .getDeclaredMethod("createDefaultSettings")
                .apply { isAccessible = true }
                .invoke(FirstRunSetup(context))
        }

        private fun profilePath(): String {
            val settings = File(Environment.getMachineSettingsPath(context))
            assertTrue(settings.isFile, "no settings file was written at $settings")
            val text = settings.readText()

            // Scoped to the profiles block: `path` appears under other keys, and
            // matching the first one anywhere in the file would pass or fail for
            // reasons unrelated to the terminal.
            val profiles = text.substringAfter("\"terminal.integrated.profiles.linux\"", "")
            assertTrue(profiles.isNotEmpty(), "the terminal profile block is missing")
            return Regex("\"path\"\\s*:\\s*\"([^\"]+)\"").find(profiles)
                ?.groupValues?.get(1)
                ?: error("the bash profile has no path; block was: ${profiles.take(200)}")
        }
    }
}
