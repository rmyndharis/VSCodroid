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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What the Claude Code extension is given to start its CLI with.
 *
 * An app may make only the system calls bionic exposes, and `epoll_pwait2` (441)
 * is on that list from android15: absent on android13 and android14, where the
 * kernel stops the process rather than returning the ENOSYS a runtime could fall
 * back from. The CLI's runtime reaches for it as soon as its event loop starts,
 * so on those releases it died before doing anything, and the extension showed
 * "Claude Code process terminated by signal SIGSYS" with nothing else to go on.
 *
 * `libclaude-launch.so` is what answers that: it puts `libseccomp-shim.so` into
 * LD_PRELOAD and then execs musl's loader, and the shim emulates the one refused
 * call with `epoll_pwait`. Naming musl's loader here instead is the obvious
 * shape, is what this key held before, and starts the same CLI with no shim at
 * all -- which is the failure above, back in full, on every device below Android
 * 15 and on none of the ones a developer is likely to be holding.
 *
 * `SettingsPathsTest` covers [refreshManagedPaths], which keeps this key current
 * across reinstalls, but it takes the wrapper as a parameter and so cannot say
 * which program is the right one. This runs the real writer for the same reason
 * `PythonLocatorDefaultTest` does: what matters is the value that reaches the
 * file the workbench reads.
 */
class ClaudeLauncherSettingsTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs

        val nativeLibDir = File(tempDir, "nativeLibs").apply { mkdirs() }
        context = mockk(relaxed = true)
        every { context.applicationInfo } returns
            ApplicationInfo().apply { nativeLibraryDir = nativeLibDir.absolutePath }
        every { context.filesDir } returns tempDir
        every { context.cacheDir } returns tempDir
        every { context.getExternalFilesDir(any()) } returns File(tempDir, "external")

        // Constructing ToolchainManager runs Play Core's static setup, which
        // cannot complete off-device; stubbing the factory is enough.
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a clean install starts the CLI through the launcher that carries the shim`() {
        createDefaultSettings()

        val written = settingsText()
        assertTrue(
            written.contains(
                """"claudeCode.claudeProcessWrapper": "${Environment.getClaudeLauncherPath(context)}"""",
            ),
            "the extension is pointed at something other than the launcher, so the CLI runs " +
                "without the seccomp shim and is killed on Android 13 and 14:\n$written",
        )
    }

    @Test
    fun `the launcher sits in the one directory this app may execute from`() {
        // SELinux denies execute_no_trans on app_data_file for targetSdk >= 29,
        // so a launcher copied into filesDir would be unrunnable however correct
        // its contents. nativeLibraryDir is the exception, which is why this is
        // a .so name rather than an executable one.
        val launcher = Environment.getClaudeLauncherPath(context)
        assertTrue(
            launcher.startsWith(context.applicationInfo.nativeLibraryDir + "/"),
            "the launcher is outside nativeLibraryDir and cannot be exec'd: $launcher",
        )
        assertTrue(
            launcher.endsWith(".so"),
            "only lib*.so names are extracted from the APK with the execute bit: $launcher",
        )
    }

    private fun createDefaultSettings() {
        FirstRunSetup::class.java
            .getDeclaredMethod("createDefaultSettings")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    private fun settingsText(): String {
        val settings = File(Environment.getMachineSettingsPath(context))
        assertTrue(settings.isFile, "no settings file was written at $settings")
        return settings.readText()
    }
}
