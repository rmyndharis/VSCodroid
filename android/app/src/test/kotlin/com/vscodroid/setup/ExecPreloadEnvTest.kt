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
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 * `terminal.integrated.env.linux`, which reaches terminals and tasks. What the
 * extension host starts without a pty gets it only from `platform-fix.js`, per
 * call and in a copy of that call's environment, and only for a program it
 * starts through the linker; a command run in a shell gets none. Widening it to
 * the server would put it under the extension host itself, every language
 * server and node itself, which nothing has measured. A well-meant one-row addition beside the three below
 * is exactly how that would happen.
 *
 * The rows' values and the shape of the preload path are measured on API 33
 * and 36 emulators, 2026-09-22/23. That the rows reach a terminal from the
 * server environment, rather than exported through the settings as the probes
 * did, is the prototype's first device row and not something this file shows.
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
        File(Environment.getExecPreloadPath(context)).apply { parentFile!!.mkdirs(); writeText("elf") }

        createDefaultSettings()

        val value = preloadValue(settingsText()) ?: error("env.linux has no LD_PRELOAD:\n${settingsText()}")
        assertEquals(Environment.getExecPreloadPath(context), value)
        assertFalse(value.contains("/data/app/"), "the written value names nativeLibraryDir: $value")
    }

    /**
     * Bionic aborts every exec whose preload it cannot map, the shell included,
     * so a build whose assets lack the library (a stale CI cache, a skipped
     * build script) must write no line at all rather than a line naming a file
     * that never arrives. The launch-time refresh then adds the line as soon as
     * the file is there, so an install the library reaches later is one launch
     * from the feature and never a dead terminal.
     */
    @Test
    fun `without the library on disk no LD_PRELOAD is written, and the refresh adds it once it is`() {
        createDefaultSettings()

        assertFalse(
            settingsText().contains("LD_PRELOAD"),
            "a fresh install wrote a preload for a file that is not there:\n${settingsText()}",
        )

        File(Environment.getExecPreloadPath(context)).apply { parentFile!!.mkdirs(); writeText("elf") }
        FirstRunSetup(context).updateSettingsNativeLibPaths()

        assertEquals(Environment.getExecPreloadPath(context), preloadValue(settingsText()))
    }

    /**
     * The same guard on the launch-time refresh, which is the path every
     * upgrade takes: the default writer replaces only a missing or empty
     * settings.json, so a file that predates the line gets it from this call.
     * With no library on disk it must still write none.
     */
    @Test
    fun `the launch-time refresh writes no LD_PRELOAD while the library is missing`() {
        createDefaultSettings()

        FirstRunSetup(context).updateSettingsNativeLibPaths()

        assertFalse(
            settingsText().contains("LD_PRELOAD"),
            "the refresh wrote a preload for a file that is not there:\n${settingsText()}",
        )
    }

    /**
     * The extension host has no terminal, so `platform-fix.js` hands the interceptor to a program it starts
     * through the linker itself, finding the file under `TERMUX__PREFIX` from the environment this class
     * builds, and names the program in `TERMUX_EXEC__PROC_SELF_EXE`. The path is written down twice, once
     * in each language. A move on either side would leave every such start without the interceptor, or, if
     * the script named a file that is not there, give it a preload Bionic cannot map, which aborts every
     * exec below it. So the shipped script runs under Node with the rows built here, an Android program under
     * the data directory is started, and the preload the start is handed is compared with this path.
     */
    @Test
    fun `an extension's start through the linker is handed the preload this path names`() {
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = this@ExecPreloadEnvTest.nativeLibDir
            dataDir = filesDir.absolutePath
        }
        val env = env()
        File(Environment.getExecPreloadPath(context)).apply { parentFile!!.mkdirs(); writeText("elf") }
        val program = File(filesDir, "home/tool").apply { parentFile!!.mkdirs(); writeBytes(elfAskingFor("/system/bin/linker64")) }
        val probe = File(filesDir, "probe").apply { mkdirs() }
        val android = File(probe, "android.js").apply {
            writeText("Object.defineProperty(process, 'platform', { value: 'android', configurable: true });\n")
        }
        val record = File(probe, "record.js").apply {
            writeText(
                "require('child_process').spawn = function (file, args, options) {\n" +
                    "    const env = (options && options.env) || {};\n" +
                    "    console.log(JSON.stringify({ file: file, preload: env.LD_PRELOAD || null, self: env.TERMUX_EXEC__PROC_SELF_EXE || null }));\n" +
                    "};\n",
            )
        }
        val main = File(probe, "main.js").apply { writeText("require('child_process').spawn(process.argv[2], []);\n") }
        val builder = ProcessBuilder(
            "node", "--require", android.path, "--require", record.path,
            "--require", File("src/main/assets/platform-fix.js").absolutePath, main.path, program.absolutePath,
        )
        builder.environment().apply {
            keys.removeAll { it.startsWith("TERMUX") || it == "LD_PRELOAD" || it == "NODE_OPTIONS" }
            for (name in listOf("HOME", "TERMUX_APP__DATA_DIR", "TERMUX_APP__LEGACY_DATA_DIR", "TERMUX__PREFIX")) {
                put(name, env.getValue(name))
            }
        }

        val (code, output) = finishTestProcess(builder.redirectErrorStream(true).start(), timeoutMillis = 20_000)

        assertEquals(0, code, output)
        val start = JSONObject(output.trim().lines().last())
        assertEquals("/system/bin/linker64", start.getString("file"), output)
        assertEquals(Environment.getExecPreloadPath(context), start.optString("preload"), output)
        assertEquals(program.absolutePath, start.optString("self"), output)
    }

    /** An ELF64 little-endian header with one program header, a PT_INTERP naming [interpreter]. */
    private fun elfAskingFor(interpreter: String): ByteArray {
        val text = (interpreter + "\u0000").toByteArray(Charsets.ISO_8859_1)
        return ByteBuffer.allocate(120 + text.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
            putShort(16, 3) // ET_DYN
            putShort(18, 183) // EM_AARCH64
            putInt(20, 1)
            putLong(32, 64) // e_phoff
            putShort(52, 64) // e_ehsize
            putShort(54, 56) // e_phentsize
            putShort(56, 1) // e_phnum
            putInt(64, 3) // PT_INTERP
            putInt(68, 4)
            putLong(72, 120) // p_offset
            putLong(96, text.size.toLong()) // p_filesz
            position(120)
            put(text)
        }.array()
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

    /** The LD_PRELOAD value under the env.linux key, or null when either is absent. */
    private fun preloadValue(text: String): String? =
        Regex("\"LD_PRELOAD\"\\s*:\\s*\"([^\"]+)\"")
            .find(text.substringAfter("\"terminal.integrated.env.linux\"", ""))
            ?.groupValues?.get(1)
}
