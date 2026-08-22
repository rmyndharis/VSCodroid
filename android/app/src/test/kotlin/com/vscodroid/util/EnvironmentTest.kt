package com.vscodroid.util

import android.content.Context
import android.content.pm.ApplicationInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

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

    @Nested
    inner class ProjectsDirTest {

        @Test
        fun `getProjectsDir uses external storage when available`() {
            val externalDir = File("/storage/emulated/0/Android/data/com.vscodroid/files")
            every { context.getExternalFilesDir(null) } returns externalDir

            val result = Environment.getProjectsDir(context)
            assertEquals("${externalDir.absolutePath}/projects", result)
        }

        @Test
        fun `getProjectsDir falls back to internal storage when external unavailable`() {
            every { context.getExternalFilesDir(null) } returns null

            val result = Environment.getProjectsDir(context)
            assertEquals("${mockFilesDir}/home/projects", result)
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
