package com.vscodroid.setup

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vscodroid.util.Environment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * That the exec interceptor lets a shell start what the app itself is forbidden
 * to run, and that the patches carried on it hold on this device.
 *
 * Same question as [ExecTrampolineOnDeviceTest], asked of the other mechanism.
 * SELinux denies `execute_no_trans` on `app_data_file`, so nothing under
 * `filesDir` can be execve'd whatever its mode; a shell that has
 * `libtermux-exec.so` preloaded catches the exec and starts the file through
 * `/system/bin/linker64`, which the same policy permits. Only a process running
 * as the app can ask that: `adb shell run-as` runs in a domain that may execute
 * files this one may not, so it reports success for a binary that fails here.
 *
 * The CONTROL is asserted first and is not optional, for the reason the
 * trampoline suite gives: if a direct execve of the copied payload SUCCEEDS,
 * nothing is being denied on this device and every case below passes for a
 * reason that has nothing to do with the preload.
 *
 * The library is taken from the APK's assets into the probe directory rather
 * than from `usr/lib`, so the suite does not depend on first-run state, and it
 * is left at the mode the write gives it, 0600 under the app's umask, because
 * that is the mode the extraction leaves the real one in and what the linker
 * has to be able to map. The extracted library has loaded at that mode in real
 * terminals on API 33 and 36 emulators (2026-09-23), and this suite pins the
 * case; the linker's behaviour on a missing preload and P1 to P5 of
 * `scripts/termux-exec.patch` were measured on the same emulators,
 * 2026-09-22/23, with 755-mode copies of the library. P6 and the three cases
 * added on 2026-10-01 (the relative path, the linker started by hand and the
 * exec with no argv[0]) have not run on a device yet.
 */
@RunWith(AndroidJUnit4::class)
class ExecPreloadOnDeviceTest {

    private lateinit var context: Context
    private lateinit var probeRoot: File
    private lateinit var payload: File
    private lateinit var preload: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        probeRoot = File(context.filesDir, "exec-preload-probe")
        probeRoot.deleteRecursively()
        probeRoot.mkdirs()

        val source = File(context.applicationInfo.nativeLibraryDir, "libmake.so")
        assertTrue("libmake.so is not in nativeLibraryDir, so there is nothing to run: $source", source.isFile)
        payload = File(probeRoot, "make")
        source.copyTo(payload, overwrite = true)
        assertTrue("could not mark the copied payload executable", payload.setExecutable(true, true))

        val asset = try {
            context.assets.open(Environment.EXEC_PRELOAD_ASSET)
        } catch (e: IOException) {
            null
        }
        assertNotNull(
            "${Environment.EXEC_PRELOAD_ASSET} is not in the APK. The APK was built without " +
                "scripts/build-termux-exec.sh having run, which ensureExecPreload() turns " +
                "into today's behaviour rather than a dead shell, so nothing else notices.",
            asset,
        )
        preload = File(probeRoot, "libtermux-exec.so")
        asset!!.use { input -> preload.outputStream().use { input.copyTo(it) } }
    }

    @After
    fun tearDown() {
        probeRoot.deleteRecursively()
    }

    /**
     * The control. A copy of a working binary, under filesDir, mode 0755, run by
     * absolute path with no preload. It must not start.
     */
    @Test
    fun `a payload under filesDir cannot be executed directly`() {
        val result = run(listOf(payload.absolutePath, "--version"), emptyMap())

        assertNotEquals(
            "a binary under filesDir executed directly. SELinux is not denying " +
                "execve on this device, so every preload case beside this one " +
                "would pass whether or not the preload works. Output: ${result.second}",
            0, result.first,
        )
    }

    /**
     * The library is mapped into a system shell started with it, at mode 0600.
     * The shell's own maps, by its pid, rather than `/proc/self/maps`, which a
     * forked `cat` would read as its own.
     */
    @Test
    fun `the preload loads into a shell started with it`() {
        val result = sh("cat /proc/\$\$/maps", preloadEnv())

        assertEquals("the shell did not start: ${result.second}", 0, result.first)
        assertTrue(
            "the shell started without the library mapped, so nothing below it is intercepted",
            result.second.contains("libtermux-exec.so"),
        )
    }

    /** The subject. Same binary, same mode, same path, exec'd by a preloaded shell. */
    @Test
    fun `a preloaded shell runs the same payload by absolute path`() {
        val result = sh("${payload.absolutePath} --version", preloadEnv())

        assertEquals("make did not run through the preload: ${result.second}", 0, result.first)
        assertTrue("something ran, but not GNU Make: ${result.second}", result.second.contains("GNU Make"))
    }

    /**
     * `./make` from the directory that holds it, the `./a.out` a user types.
     * The interceptor makes a relative path absolute with getcwd(), and the
     * kernel reports a directory under the app's data as
     * `/data/data/<package>` for user 0 rather than the `/data/user/0` that
     * filesDir names, so only the TERMUX_APP__LEGACY_DATA_DIR row lets it see
     * the file as the app's. Every absolute-path case here passes without that
     * row. With it unset, every relative exec of a filesDir ELF was refused
     * with 126 on API 33 and 36 emulators, 2026-09-22/23 (the note on the row
     * in `Environment.buildProcessEnvironment`); this case itself has not been
     * run without it. `pwd -P` puts the kernel's spelling into a failure
     * message.
     */
    @Test
    fun `a preloaded shell runs the payload by a relative path`() {
        val result = sh("cd ${probeRoot.absolutePath} && pwd -P && ./make --version", preloadEnv())

        assertEquals("./make did not run through the preload: ${result.second}", 0, result.first)
        assertTrue("something ran, but not GNU Make: ${result.second}", result.second.contains("GNU Make"))
    }

    /**
     * A shebang script under filesDir. Without the preload the kernel answers
     * EACCES for the script's own inode before it reads the interpreter line;
     * with it the interceptor reads the line itself and starts the interpreter.
     */
    @Test
    fun `a script under filesDir runs from a preloaded shell`() {
        val script = script("sys_sh.sh", "#!/system/bin/sh\necho script-ok\n")

        val result = sh(script.absolutePath, preloadEnv())

        assertEquals("the script did not run: ${result.second}", 0, result.first)
        assertTrue("the script ran something else: ${result.second}", result.second.contains("script-ok"))
    }

    /**
     * `#!/bin/sh`, which is what git hooks, npm shims and every script written
     * off a Linux box carry. Android has a `/bin/sh`, through its `/bin` link
     * onto `/system/bin`, but the interceptor rewrites `/bin/sh` into the
     * prefix before the kernel sees it, and the prefix has no `sh`, so this
     * runs only through the patch's fallback to `/system/bin/sh`.
     */
    @Test
    fun `a script naming slash bin slash sh runs from a preloaded shell`() {
        val script = script("bin_sh.sh", "#!/bin/sh\necho bin-sh-ok\n")

        val result = sh(script.absolutePath, preloadEnv())

        assertEquals("the script did not run: ${result.second}", 0, result.first)
        assertTrue("the script ran something else: ${result.second}", result.second.contains("bin-sh-ok"))
    }

    /**
     * A system binary keeps the preload for the program it starts. Upstream
     * strips `LD_PRELOAD` on every exec of a `/system` path, so `env`, the first
     * hop of every `#!/usr/bin/env` script, would start its target with no
     * interceptor and a filesDir target would be refused one hop later.
     */
    @Test
    fun `a system binary keeps the preload for what it starts`() {
        val result = sh("/system/bin/env ${payload.absolutePath} --version", preloadEnv())

        assertEquals("env lost the preload before starting make: ${result.second}", 0, result.first)
        assertTrue("something ran, but not GNU Make: ${result.second}", result.second.contains("GNU Make"))
    }

    /**
     * A symlink under filesDir onto a binary outside it is started directly,
     * not through the linker. `usr/bin/node` and `usr/bin/bash` are that shape,
     * and a node started through the linker reports `linker64` as its own path,
     * which breaks everything that re-executes `process.execPath`. A link onto
     * the system shell stands in for them so the case needs no extracted tree.
     *
     * This case also passes with no preload at all, since the kernel follows
     * the link to a system file the app may exec, so it holds the direct start
     * against a regression to upstream's linker route and says nothing about
     * the library being present; the maps case is what shows it loaded.
     */
    @Test
    fun `a symlink onto a system binary is started as itself`() {
        val link = File(probeRoot, "sh")
        Runtime.getRuntime().exec(arrayOf("/system/bin/ln", "-sf", "/system/bin/sh", link.absolutePath)).waitFor()
        assertTrue("the link was not created", link.exists())

        // The linked shell's own exe, by its pid: `/proc/self/exe` would be the
        // forked readlink's.
        val result = sh("${link.absolutePath} -c 'readlink /proc/\$\$/exe'", preloadEnv())

        assertEquals("the linked shell did not start: ${result.second}", 0, result.first)
        val exe = result.second.trim()
        assertFalse("the shell was started through the linker and reports it as itself: $exe", exe.contains("linker"))
        assertTrue("the shell reports something other than a system path: $exe", exe.startsWith("/system/"))
    }

    /**
     * A program that starts the linker itself, `linker64 <payload>`, as the
     * exec trampoline and the toolchain launchers do, has the payload named in
     * TERMUX_EXEC__PROC_SELF_EXE where upstream stripped the variable, so a
     * payload that finds itself through it still can. The payload is a copy of
     * the system shell under filesDir, so it can print what it was given.
     */
    @Test
    fun `a linker started by hand names its payload in the environment`() {
        val shell = File(probeRoot, "shell")
        File("/system/bin/sh").copyTo(shell, overwrite = true)

        val result = sh(
            "/system/bin/linker64 ${shell.absolutePath} -c 'echo \"self=\$TERMUX_EXEC__PROC_SELF_EXE\"'",
            preloadEnv(),
        )

        assertEquals("the copied shell did not start through the linker: ${result.second}", 0, result.first)
        assertTrue(
            "the payload was not named to itself: ${result.second}",
            result.second.contains("self=${shell.absolutePath}"),
        )
    }

    /**
     * A process that kept the preload but lost the prefix still reaches
     * `/system/bin/sh` for `/bin/sh`. The rows are split, LD_PRELOAD in the
     * terminal setting and the prefix in the server environment, so a child
     * that scrubs its environment can arrive in exactly this state.
     */
    @Test
    fun `a shell without the prefix still finds slash bin slash sh`() {
        val result = sh("/bin/sh -c 'echo no-prefix-ok'", preloadEnv() - "TERMUX__PREFIX")

        assertEquals("/bin/sh was not mapped with the prefix unset: ${result.second}", 0, result.first)
        assertTrue(result.second.contains("no-prefix-ok"))
    }

    /**
     * An exec with no argv[0] goes to the kernel as it stands, as it did with
     * no preload, so a payload under filesDir is refused rather than handed to
     * the linker. Linux accepts argc 0, and the rewrite sized the linker's argv
     * from argc: it wrote one pointer past the end and gave the linker no
     * program, which printed its usage and exited 0.
     *
     * No shell can make that call, so [ExecveWithArgv] makes it from Java under
     * `app_process`, through `Os.execve` into the libc `execve` the preload
     * replaces. The first run is the control: the same route with an ordinary
     * argv has to start the payload through the linker, or a refusal in the
     * second run would say nothing about the library.
     */
    @Test
    fun `an exec with no argv0 is refused as it is without the preload`() {
        val control = execveFromJava(payload.absolutePath, "--version")
        assertTrue(
            "app_process did not start the payload through the preload, so this case " +
                "cannot speak for it: ${control.second}",
            control.second.contains("GNU Make"),
        )

        val result = execveFromJava()

        assertTrue(
            "an exec with no argv[0] was not refused the way the kernel refuses it: ${result.second}",
            result.second.contains("execve: EACCES"),
        )
    }

    /**
     * The failure shape every repair of the value has to keep in mind. Bionic
     * treats a preload name like a `DT_NEEDED`: one it cannot find aborts the
     * exec, the system shell included, with no warning mode. This is why the
     * value is a real file under filesDir and why the line is only ever written
     * once that file is there.
     */
    @Test
    fun `a preload naming a missing file is fatal to the shell itself`() {
        val missing = File(probeRoot, "nope.so")
        val result = sh("echo ok", preloadEnv() + ("LD_PRELOAD" to missing.absolutePath))

        assertNotEquals("the shell started with a preload it cannot have loaded", 0, result.first)
        assertTrue(
            "the failure is not the linker's: ${result.second}",
            result.second.contains("CANNOT LINK EXECUTABLE"),
        )
    }

    /**
     * What a terminal exports: the library, and the three rows the server
     * environment carries for it, taken from the real composition so a renamed
     * row fails here rather than on a device.
     */
    private fun preloadEnv(): Map<String, String> =
        Environment.buildProcessEnvironment(context, 0).filterKeys { it.startsWith("TERMUX") } +
            ("LD_PRELOAD" to preload.absolutePath)

    private fun script(name: String, body: String): File =
        File(probeRoot, name).apply {
            writeText(body)
            assertTrue("could not mark $name executable", setExecutable(true, true))
        }

    /** [command] as the system shell runs it, with exactly [env] added. */
    private fun sh(command: String, env: Map<String, String>): Pair<Int, String> =
        run(listOf("/system/bin/sh", "-c", command), env)

    /**
     * `execve(payload, argv, environ)` made by [ExecveWithArgv] in a process
     * started with the preload. The app's own APK joins the test APK on the
     * class path for the Kotlin runtime, which the test APK leaves to it.
     */
    private fun execveFromJava(vararg argv: String): Pair<Int, String> {
        val testApk = InstrumentationRegistry.getInstrumentation().context.packageCodePath
        return run(
            listOf(
                "/system/bin/app_process",
                "-Djava.class.path=$testApk:${context.packageCodePath}",
                "/system/bin",
                ExecveWithArgv::class.java.name,
                payload.absolutePath,
            ) + argv,
            preloadEnv(),
        )
    }

    /** Exit status and merged output of one command, run with exactly [env] added. */
    private fun run(command: List<String>, env: Map<String, String>): Pair<Int, String> {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().putAll(env)
        return try {
            val process = builder.start()
            val out = process.inputStream.bufferedReader().readText()
            assertTrue("the command did not finish", process.waitFor(30, TimeUnit.SECONDS))
            process.exitValue() to out
        } catch (e: Exception) {
            // A refused execve arrives as an IOException from ProcessBuilder
            // rather than as a non-zero status, and for the control that is the
            // expected shape. Reported as 126, the status a shell gives for
            // "found but could not be executed".
            126 to (e.message ?: e.toString())
        }
    }
}

/**
 * `execve(args[0], args[1..], environ)` from Java, which can pass an argv no
 * shell can write: an empty one. Run under `app_process` by
 * [ExecPreloadOnDeviceTest]; prints the errno's name when the exec is refused.
 */
object ExecveWithArgv {
    @JvmStatic
    fun main(args: Array<String>) {
        val env = System.getenv().map { (key, value) -> "$key=$value" }.toTypedArray()
        try {
            Os.execve(args[0], args.copyOfRange(1, args.size), env)
        } catch (e: ErrnoException) {
            println("execve: ${OsConstants.errnoName(e.errno)}")
            System.out.flush()
        }
    }
}
