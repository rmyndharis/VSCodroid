package com.vscodroid.setup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The one claim no JVM test can settle: that the trampoline actually starts a
 * program the app is otherwise forbidden to run.
 *
 * Everything else about this mechanism is bookkeeping a unit test can check, and
 * bookkeeping is not the question. The question is a kernel policy decision:
 * SELinux denies `execute_no_trans` on `app_data_file`, so the app cannot execve
 * anything under `filesDir` whatever its mode, and it may execve
 * `/system/bin/linker64`, which then mmaps the payload out of `filesDir` under
 * the `execute` permission it does hold. Only a test running as the app itself
 * can ask that. `adb shell run-as` cannot substitute: it runs as
 * `u:r:runas_app:s0`, a domain that is allowed to execute files this one may
 * not, so it reports success for a binary that fails on the device.
 *
 * The CONTROL is asserted first and is not optional. If a direct execve of the
 * copied payload SUCCEEDS on this device, then nothing was ever being denied
 * here, the subject below would pass for a reason that has nothing to do with
 * the trampoline, and the run proves nothing. That case fails loudly and says so
 * rather than reporting green.
 *
 * The payload is `libmake.so`, copied out of `nativeLibraryDir`: it is small,
 * self-contained, already bundled, and `make --version` exits 0 with output that
 * cannot be mistaken for anything else.
 */
@RunWith(AndroidJUnit4::class)
class ExecTrampolineOnDeviceTest {

    private lateinit var context: Context
    private lateinit var probeRoot: File
    private lateinit var tcBin: File
    private lateinit var table: File
    private lateinit var payload: File

    private val trampoline
        get() = File(context.applicationInfo.nativeLibraryDir, "libexec-trampoline.so")

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Its own subtree, so nothing here touches the directories the app's own
        // toolchain machinery owns and a failed run leaves no name on PATH.
        probeRoot = File(context.filesDir, "exec-trampoline-probe")
        probeRoot.deleteRecursively()
        tcBin = File(probeRoot, "tcbin").apply { mkdirs() }
        table = File(probeRoot, "toolchain-exec.tsv")

        val source = File(context.applicationInfo.nativeLibraryDir, "libmake.so")
        assertTrue(
            "libmake.so is not in nativeLibraryDir, so there is nothing to run: $source",
            source.isFile,
        )
        payload = File(probeRoot, "make")
        source.copyTo(payload, overwrite = true)
        assertTrue("could not mark the copied payload executable", payload.setExecutable(true, true))

        assertTrue(
            "libexec-trampoline.so is not in nativeLibraryDir. The APK was built " +
                "without scripts/build-exec-trampoline.sh having run, which is the " +
                "one failure in this area that leaves every other gate green: " +
                trampoline,
            trampoline.isFile,
        )

        table.writeText("make\t${payload.absolutePath}\n")
        Runtime.getRuntime().exec(
            arrayOf("/system/bin/ln", "-sf", trampoline.absolutePath, File(tcBin, "make").absolutePath)
        ).waitFor()
        assertTrue("the trampoline link was not created", File(tcBin, "make").exists())
    }

    @After
    fun tearDown() {
        probeRoot.deleteRecursively()
    }

    /**
     * The control. A copy of a working binary, under filesDir, mode 0755, run by
     * absolute path. It must not start.
     */
    @Test
    fun `a payload under filesDir cannot be executed directly`() {
        val result = run(listOf(payload.absolutePath, "--version"), emptyMap())

        assertNotEquals(
            "a binary under filesDir executed directly. SELinux is not denying " +
                "execve on this device, so the trampoline test beside this one " +
                "would pass whether or not the trampoline works. Output: ${result.second}",
            0, result.first,
        )
    }

    /**
     * The subject. Same binary, same mode, same directory, reached by bare name
     * through a PATH holding only the trampoline directory.
     */
    @Test
    fun `the trampoline runs the same payload by bare name`() {
        val result = runByName(
            "make --version",
            mapOf(
                "PATH" to tcBin.absolutePath,
                "VSCODROID_EXEC_TABLE" to table.absolutePath,
            ),
        )

        assertEquals("make did not run through the trampoline: ${result.second}", 0, result.first)
        assertTrue(
            "the trampoline ran something, but not GNU Make: ${result.second}",
            result.second.contains("GNU Make"),
        )
    }

    /**
     * A variable the table carries reaches the program the trampoline starts.
     *
     * The server's environment is composed once, at start, and a toolchain
     * installed while it runs is not in it, so a task or a make recipe found
     * `ruby` through the trampoline and ran it with RUBYLIB unset. Measured on an
     * API 37 emulator: `LoadError` on `require "json"` from a `"type": "process"`
     * task while the same probe through bash worked. The table is what an install
     * regenerates, so the variable travels in it.
     *
     * GNU Make reads its environment into variables and `$(info)` prints one at
     * parse time, with no recipe shell involved, so the payload itself reports
     * what it was handed.
     */
    @Test
    fun `a variable in the table reaches the program the trampoline starts`() {
        val probe = File(probeRoot, "probe.mk").apply {
            writeText("\$(info probe=\$(VSCODROID_PROBE))\nall: ;\n")
        }
        table.writeText("\tVSCODROID_PROBE\tset-by-table\nmake\t${payload.absolutePath}\n")

        val result = runByName(
            "make -f ${probe.absolutePath}",
            mapOf(
                "PATH" to tcBin.absolutePath,
                "VSCODROID_EXEC_TABLE" to table.absolutePath,
            ),
        )

        assertEquals("make did not run through the trampoline: ${result.second}", 0, result.first)
        assertTrue(
            "the variable in the table never reached the program: ${result.second}",
            result.second.contains("probe=set-by-table"),
        )
    }

    /**
     * The control, and a rule of its own: a variable the caller already has is
     * left alone. Bundler sets GEM_HOME for a vendored bundle before it starts
     * `ruby`, and a person exports one in a terminal; the table fills in what the
     * caller was never given rather than overriding what it chose.
     */
    @Test
    fun `a variable the caller already has is not overwritten`() {
        val probe = File(probeRoot, "probe.mk").apply {
            writeText("\$(info probe=\$(VSCODROID_PROBE))\nall: ;\n")
        }
        table.writeText("\tVSCODROID_PROBE\tset-by-table\nmake\t${payload.absolutePath}\n")

        val result = runByName(
            "make -f ${probe.absolutePath}",
            mapOf(
                "PATH" to tcBin.absolutePath,
                "VSCODROID_EXEC_TABLE" to table.absolutePath,
                "VSCODROID_PROBE" to "from-the-caller",
            ),
        )

        assertEquals("make did not run through the trampoline: ${result.second}", 0, result.first)
        assertTrue(
            "the table overrode a variable the caller had set: ${result.second}",
            result.second.contains("probe=from-the-caller"),
        )
    }

    /**
     * A name with no row answers 127 and says why, rather than starting whatever
     * else happens to be reachable. The trampoline must never search PATH.
     */
    @Test
    fun `a name with no row fails with a reason`() {
        Runtime.getRuntime().exec(
            arrayOf("/system/bin/ln", "-sf", trampoline.absolutePath, File(tcBin, "nosuch").absolutePath)
        ).waitFor()

        val result = runByName(
            "nosuch",
            mapOf(
                "PATH" to tcBin.absolutePath,
                "VSCODROID_EXEC_TABLE" to table.absolutePath,
            ),
        )

        // A shell answers 127 for a name it cannot find at all, which is the same
        // status the trampoline answers with. The message below is what tells the
        // two apart, so it is not decoration.
        assertEquals("an unlisted name did not report command-not-found: ${result.second}",
            127, result.first)
        assertTrue(
            "the failure said nothing a user could act on: ${result.second}",
            result.second.contains("no toolchain entry"),
        )
    }

    /**
     * Runs [command] the way a `make` recipe or an editor task does: as a bare
     * name, left to a shell to resolve against the PATH it was handed.
     *
     * The shell has to be the one doing that lookup, because ProcessBuilder does
     * not do it. Java resolves a bare program name against the PARENT process's
     * PATH, not against the one placed in `builder.environment()`, so passing
     * `listOf("make", ...)` never reached the trampoline directory at all: it
     * failed with ENOENT, which [run]'s catch reports as 126, and the two tests
     * above then read that as a refused execve rather than as a lookup that never
     * happened. Both were failing for this reason and neither could ever pass,
     * which nothing noticed because no workflow runs this suite (see the README
     * beside this file). The trampoline itself was measured working at the same
     * time, by hand, with the same table and the same PATH.
     *
     * The shell is named by absolute path so Java's own resolution is not involved
     * in reaching it either.
     */
    private fun runByName(command: String, env: Map<String, String>): Pair<Int, String> =
        run(listOf("/system/bin/sh", "-c", command), env)

    /** Exit status and merged output of one command, run with exactly [env] added. */
    private fun run(command: List<String>, env: Map<String, String>): Pair<Int, String> {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        // PATH is replaced rather than extended, so a hit can only have come from
        // the trampoline directory and not from /system/bin.
        builder.environment().remove("PATH")
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
