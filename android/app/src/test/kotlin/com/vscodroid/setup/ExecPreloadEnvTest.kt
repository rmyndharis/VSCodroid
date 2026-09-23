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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What the exec interceptor is handed, and where it is handed it.
 *
 * The interceptor is a Bionic library a terminal preloads so that a program
 * under `filesDir`, which SELinux refuses to execve, is started through the
 * system linker instead. It reads three variables to know which paths are the
 * app's: the data directory in both spellings and the prefix. Those rows live
 * in the server environment, which is the base of every terminal, task and
 * extension spawn, and they are inert until `LD_PRELOAD` names the library,
 * because no bundled ELF reads them.
 *
 * `LD_PRELOAD` itself is NOT in that environment, and the assertion saying so
 * is the one that carries the scope. The library is delivered through
 * `terminal.integrated.env.linux`, which reaches terminals and tasks and nothing
 * the extension host spawns without a pty; widening it to the server would put
 * it under the extension host, every language server and node itself, which
 * nothing has measured. A well-meant one-row addition beside the three below
 * is exactly how that would happen.
 *
 * Every path here is measured on API 33 and 36 emulators, 2026-09-22/23.
 */
class ExecPreloadEnvTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    private val nativeLibDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"
    private val dataDir = "/data/user/0/com.vscodroid"

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
        every { context.packageName } returns "com.vscodroid"
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = this@ExecPreloadEnvTest.nativeLibDir
            dataDir = this@ExecPreloadEnvTest.dataDir
        }
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun env(): Map<String, String> = Environment.buildProcessEnvironment(context, 1234)

    /**
     * Both data-dir spellings, and neither is redundant. The kernel reports the
     * working directory as `/data/data/<pkg>` on API 33 and 36 alike, so with
     * the legacy row unset every relative exec of a filesDir ELF is refused
     * with 126 whichever way the caller spelled it.
     */
    @Test
    fun `the three rows the interceptor reads derive from the data dir, the package and filesDir`() {
        val env = env()

        assertEquals(dataDir, env["TERMUX_APP__DATA_DIR"])
        assertEquals("/data/data/com.vscodroid", env["TERMUX_APP__LEGACY_DATA_DIR"])
        assertEquals("${filesDir.absolutePath}/usr", env["TERMUX__PREFIX"])
    }

    @Test
    fun `the preload is not in the server environment`() {
        val env = env()

        assertFalse(
            env.containsKey("LD_PRELOAD"),
            "LD_PRELOAD in the server environment puts the interceptor under the " +
                "extension host, every language server and node itself, none of which " +
                "has been measured; it belongs in terminal.integrated.env.linux",
        )
        assertFalse(
            env.containsKey("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE"),
            "the linker-exec mode is left at its default, which is what was measured",
        )
        assertFalse(env.containsKey("TERMUX__ROOTFS"), "nothing on the exec path reads the rootfs row")
    }

    /**
     * A missing LD_PRELOAD entry is fatal to every exec, so the path must be one
     * that never moves and never dangles: a real file under filesDir, refreshed
     * by the same extraction that refreshes the rest of `usr/`. Never
     * nativeLibraryDir, which Android moves on every reinstall, and never a bare
     * name, which resolves through LD_LIBRARY_PATH and dies when that is cleared.
     */
    @Test
    fun `the preload path is a real file under filesDir and never nativeLibraryDir`() {
        val path = Environment.getExecPreloadPath(context)

        assertEquals("${filesDir.absolutePath}/usr/lib/libtermux-exec.so", path)
        assertFalse(path.startsWith(nativeLibDir), "a nativeLibraryDir path goes stale on every reinstall: $path")
        assertTrue(path.startsWith("/"), "a bare name resolves through LD_LIBRARY_PATH and can be cleared away: $path")
    }

    /**
     * The real writer, as `ClaudeLauncherSettingsTest` runs it: what matters is
     * the value reaching the file the workbench reads. The env.linux setting and
     * not the profile's `env`, because a profile env is invisible for the whole
     * first session after an edit made while the app was stopped and never
     * reaches a `"type": "process"` task; env.linux reaches both and applies
     * live.
     */
    @Test
    fun `a clean install's settings carry the preload in terminal env linux`() {
        FirstRunSetup::class.java
            .getDeclaredMethod("createDefaultSettings")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))

        val settings = File(Environment.getMachineSettingsPath(context))
        assertTrue(settings.isFile, "no settings file was written at $settings")
        val text = settings.readText()

        val block = text.substringAfter("\"terminal.integrated.env.linux\"", "")
        assertTrue(block.isNotEmpty(), "the env.linux key is missing:\n$text")
        val value = Regex("\"LD_PRELOAD\"\\s*:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1)
            ?: error("env.linux has no LD_PRELOAD; block was: ${block.take(200)}")
        assertEquals(Environment.getExecPreloadPath(context), value)
        assertFalse(value.contains("/data/app/"), "the written value names nativeLibraryDir: $value")
    }
}
