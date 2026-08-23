package com.vscodroid.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.system.Os
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [Environment]: path generation and environment configuration.
 *
 * Complements [EnvironmentSafTest] which covers SAF-specific methods.
 * Tests pure path-building methods using mocked Context.
 */
class EnvironmentTest {

    private lateinit var context: Context
    private val mockFilesDir = File("/data/data/com.vscodroid/files")
    private val mockNativeLibDir = "/data/data/com.vscodroid/nativeLibs"

    @BeforeEach
    fun setUp() {
        context = mockk(relaxed = true)
        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = mockNativeLibDir
        }
        every { context.applicationInfo } returns appInfo
        every { context.filesDir } returns mockFilesDir
    }

    @Nested
    inner class SafPathsTest {

        @Test
        fun `getSafMirrorsDir returns filesDir + saf-mirrors`() {
            val result = Environment.getSafMirrorsDir(context)
            assertEquals("${mockFilesDir}/saf-mirrors", result)
        }
    }

    /**
     * Which filesystem a workspace lands on, which is not a matter of taste.
     *
     * Shared storage is served through FUSE and has no `symlink(2)`, so
     * `npm install` cannot write `node_modules/.bin` for any package shipping an
     * executable and dies with EPERM on a path that says nothing about storage.
     * Measured on an API 37 emulator: `ln -s` under `Android/data` answers
     * "Permission denied" and the same call under `filesDir` succeeds, and real
     * npm reproduces both sides. So a new install gets internal storage.
     *
     * An install that already has a projects directory on shared storage keeps
     * it. `.bashrc` exports `PROJECTS_DIR` when it is first written and nothing
     * rewrites it, so an answer that moved under such an install would leave
     * every terminal starting somewhere the editor is not, and the user's files
     * would be somewhere neither of them looks.
     *
     * These cases were two assertions against fabricated paths, which could not
     * see any of it: the decision is made of `isDirectory` questions, so the
     * directories here are real.
     */
    @Nested
    inner class ProjectsDirTest {

        @TempDir
        lateinit var root: File

        private val projectsFilesDir by lazy { File(root, "files").apply { mkdirs() } }
        private val externalDir by lazy { File(root, "external").apply { mkdirs() } }
        private val legacy by lazy { File(externalDir, "projects") }

        @BeforeEach
        fun stubStorage() {
            every { context.filesDir } returns projectsFilesDir
            every { context.getExternalFilesDir(null) } returns externalDir
            // Os throws off a device, and the production code reads that as "no
            // link", so the case below would pass for the wrong reason. Routed to
            // java.nio as [com.vscodroid.setup.ProjectsSymlinkTest] does, which
            // makes the link and its target real.
            mockkStatic(Os::class)
            every { Os.readlink(any()) } answers {
                Files.readSymbolicLink(Path.of(firstArg<String>())).toString()
            }
        }

        @AfterEach
        fun unstubOs() = unmockkStatic(Os::class)

        @Test
        fun `a fresh install gets internal storage, where a symlink can be made`() {
            assertEquals("$projectsFilesDir/projects", Environment.getProjectsDir(context))
        }

        @Test
        fun `an install that already has one on shared storage keeps it`() {
            assertTrue(legacy.mkdirs(), "could not stage the directory an older release made")

            assertEquals(legacy.absolutePath, Environment.getProjectsDir(context))
        }

        /**
         * The deletion `FirstRunSetup.ensureProjectsDir` exists to repair: some
         * routes outside the app still reach that directory. Answering "internal"
         * for the launch after it would move an existing install's workspace on
         * the strength of someone else's delete, and `.bashrc` would go on
         * exporting the old path. `~/projects` is written beside the directory and
         * outlives it, so it is the record of which one is in use.
         */
        @Test
        fun `it keeps naming shared storage while the directory is missing`() {
            val link = File(projectsFilesDir, "home/projects")
            assertTrue(link.parentFile!!.mkdirs(), "could not stage the home directory")
            Files.createSymbolicLink(link.toPath(), legacy.toPath())

            assertEquals(legacy.absolutePath, Environment.getProjectsDir(context))
        }

        /**
         * The control for the case above. A fresh install's `~/projects` names
         * internal storage, so the link must not drag it back to the old place.
         */
        @Test
        fun `a link into internal storage is not read as a shared-storage install`() {
            val internal = File(projectsFilesDir, "projects")
            val link = File(projectsFilesDir, "home/projects")
            assertTrue(link.parentFile!!.mkdirs(), "could not stage the home directory")
            Files.createSymbolicLink(link.toPath(), internal.toPath())

            assertEquals(internal.absolutePath, Environment.getProjectsDir(context))
        }

        @Test
        fun `it falls back to internal storage when shared storage is unavailable`() {
            every { context.getExternalFilesDir(null) } returns null

            assertEquals("$projectsFilesDir/projects", Environment.getProjectsDir(context))
        }
    }

    @Nested
    inner class PathConsistencyTest {

        @Test
        fun `all path methods return absolute paths`() {
            val paths = listOf(
                Environment.getNodePath(context),
                Environment.getServerScript(context),
                Environment.getHomeDir(context),
                Environment.getUserDataDir(context),
                Environment.getExtensionsDir(context),
                Environment.getLogsDir(context),
                Environment.getServerDir(context),
                Environment.getBashPath(context),
                Environment.getGitPath(context),
                Environment.getSafMirrorsDir(context),
            )
            for (path in paths) {
                assertTrue(path.startsWith("/"), "Path should be absolute: $path")
            }
        }

        @Test
        fun `userDataDir is under homeDir`() {
            val home = Environment.getHomeDir(context)
            val userData = Environment.getUserDataDir(context)
            assertTrue(userData.startsWith(home), "User data dir should be under home dir")
        }

        @Test
        fun `extensionsDir is under userDataDir`() {
            val userData = Environment.getUserDataDir(context)
            val extensions = Environment.getExtensionsDir(context)
            assertTrue(extensions.startsWith(userData), "Extensions dir should be under user data dir")
        }

        @Test
        fun `logsDir is under userDataDir`() {
            val userData = Environment.getUserDataDir(context)
            val logs = Environment.getLogsDir(context)
            assertTrue(logs.startsWith(userData), "Logs dir should be under user data dir")
        }

        @Test
        fun `each path getter names its own destination`() {
            // Renamed from `each bundled binary getter names its own file`, which
            // stopped describing the case once the filesDir-relative getters were
            // added below: only the first two assertions name a binary.
            // The nine per-getter cases that used to sit above restated the string
            // concatenation each getter performs: change the implementation and the
            // test is edited to match, which is a restatement rather than a verdict.
            // They are gone, but one mutation went with them that nothing else here
            // can see -- every getter returns an absolute path whichever binary it
            // names, so a copy-paste that has getNodePath hand back libgit.so passes
            // every assertion in this class.
            //
            // getBashPath is deliberately absent: it has no production caller.
            assertEquals("libnode.so", Environment.getNodePath(context).substringAfterLast('/'))
            assertEquals("libgit.so", Environment.getGitPath(context).substringAfterLast('/'))

            // The same argument applies to the filesDir-relative getters, and the first
            // version of this test forgot them: every one of these also returns an
            // absolute path whatever it names, so the four assertions above them see
            // nothing when a destination moves. server/server.js in particular is
            // written by FirstRunSetup.extractAssetFile and read back through here --
            // two places that have to agree and are edited separately.
            val under = { p: String -> p.removePrefix("$mockFilesDir/") }
            assertEquals("server/server.js", under(Environment.getServerScript(context)))
            assertEquals("server", under(Environment.getServerDir(context)))
            assertEquals("home", under(Environment.getHomeDir(context)))
            assertEquals("home/.vscodroid", under(Environment.getUserDataDir(context)))

            // These two were still missing, and they are the ones the server is
            // handed on its command line: --extensions-dir and --logsPath, both
            // in ProcessManager.startServer. Measured: shortening getLogsDir's
            // tail from data/logs to data/log left every case in this class
            // green, because "under userDataDir" and "starts with /" are true of
            // any tail at all.
            //
            // The destination is not a detail either getter is free to move. The
            // extensions directory already holds whatever the user installed, so
            // pointing the server at a different one empties the workbench's
            // extension list with nothing to explain it, and the logs directory
            // is the only place the server's own logs can be looked for.
            assertEquals("home/.vscodroid/extensions", under(Environment.getExtensionsDir(context)))
            assertEquals("home/.vscodroid/data/logs", under(Environment.getLogsDir(context)))
        }
    }
}
