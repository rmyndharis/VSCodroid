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
 * Tests for [Environment] — path generation and environment configuration.
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
    inner class PathMethodsTest {

        @Test
        fun `getNodePath returns nativeLibraryDir + libnode_so`() {
            val result = Environment.getNodePath(context)
            assertEquals("$mockNativeLibDir/libnode.so", result)
        }

        @Test
        fun `getServerScript returns filesDir + server_server_js`() {
            val result = Environment.getServerScript(context)
            assertEquals("${mockFilesDir}/server/server.js", result)
        }

        @Test
        fun `getHomeDir returns filesDir + home`() {
            val result = Environment.getHomeDir(context)
            assertEquals("${mockFilesDir}/home", result)
        }

        @Test
        fun `getUserDataDir returns filesDir + home_vscodroid`() {
            val result = Environment.getUserDataDir(context)
            assertEquals("${mockFilesDir}/home/.vscodroid", result)
        }

        @Test
        fun `getExtensionsDir returns filesDir + home_vscodroid_extensions`() {
            val result = Environment.getExtensionsDir(context)
            assertEquals("${mockFilesDir}/home/.vscodroid/extensions", result)
        }

        @Test
        fun `getLogsDir returns filesDir + home_vscodroid_data_logs`() {
            val result = Environment.getLogsDir(context)
            assertEquals("${mockFilesDir}/home/.vscodroid/data/logs", result)
        }

        @Test
        fun `getServerDir returns filesDir + server`() {
            val result = Environment.getServerDir(context)
            assertEquals("${mockFilesDir}/server", result)
        }

        @Test
        fun `getBashPath returns nativeLibraryDir + libbash_so`() {
            val result = Environment.getBashPath(context)
            assertEquals("$mockNativeLibDir/libbash.so", result)
        }

        @Test
        fun `getGitPath returns nativeLibraryDir + libgit_so`() {
            val result = Environment.getGitPath(context)
            assertEquals("$mockNativeLibDir/libgit.so", result)
        }
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
    }
}
