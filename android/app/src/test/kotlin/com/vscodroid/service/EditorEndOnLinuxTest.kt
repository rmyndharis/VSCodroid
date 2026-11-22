package com.vscodroid.service

import android.content.Context
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * How a stop ends an editor server, against real processes and the real `/proc`.
 *
 * `EditorShutdownTest` runs the same decisions over a `/proc` it lays out itself. What only a real one
 * shows is that the identity is read the way the kernel writes it: the command line and the environment
 * as NUL-separated text, readable for a process of the same user, and a zombie with no command line. Linux
 * only: the machines without `/proc` skip it, and CI's runner has one.
 */
class EditorEndOnLinuxTest {
    @TempDir
    lateinit var temp: File

    private val started = mutableListOf<Process>()
    private val signals = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())

    @BeforeEach
    fun setUp() {
        assumeTrue(File("/proc/self/environ").canRead(), "needs a Linux /proc")
        mockEditorGateway()
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        started.forEach { it.destroyForcibly() }
        unmockkAll()
    }

    private fun manager(): ProcessManager {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns temp
        every { context.cacheDir } returns temp
        return ProcessManager(context).apply {
            terminateRecordedProcess = { signal("TERM", it) }
            killRecordedProcess = { signal("KILL", it) }
        }
    }

    // The shell's own kill, as the fixture runs under that shell: a /bin/kill on PATH is not on every
    // Linux, and a minimal container image has none.
    private fun signal(name: String, pid: Int) {
        signals += name to System.nanoTime()
        ProcessBuilder("/bin/sh", "-c", "kill -s $name $pid").start().waitFor(5, TimeUnit.SECONDS)
    }

    private fun pid(process: Process) = (Process::class.java.getMethod("pid").invoke(process) as Long).toInt()

    /** A process that `/proc` shows as the editor server of [runId]: its command line names server-main.js. */
    private fun editor(runId: String, ignoresTerm: Boolean): Int {
        val script = (if (ignoresTerm) "trap '' TERM; " else "") + "while :; do sleep 0.05; done"
        val process = ProcessBuilder("/bin/sh", "-c", script, EDITOR_ENTRY_POINT)
            .apply { environment()["VSCODROID_SERVER_RUN_ID"] = runId }
            .start()
        started += process
        val pid = pid(process)
        // The pid names the launcher until the shell has been exec'd.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!File("/proc/$pid/cmdline").readText().contains(EDITOR_ENTRY_POINT)) {
            check(System.nanoTime() < deadline) { "the fixture never became the editor" }
            Thread.sleep(10)
        }
        return pid
    }

    private fun end(manager: ProcessManager, pid: Int, runId: String) {
        ProcessManager::class.java.getDeclaredMethod("endEditorProcess", Int::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }.invoke(manager, pid, runId)
    }

    @Test
    fun `an editor that ignores its SIGTERM is killed, and only once the grace has run`() {
        val runId = UUID.randomUUID().toString()
        val pid = editor(runId, ignoresTerm = true)

        end(manager(), pid, runId)

        assertEquals(listOf("TERM", "KILL"), signals.map { it.first })
        val waited = TimeUnit.NANOSECONDS.toMillis(signals[1].second - signals[0].second)
        assertTrue(waited >= REAP_TERM_GRACE_MS, "killed $waited ms after the SIGTERM, before the editor's handler had its time")
        assertTrue(started.single().waitFor(5, TimeUnit.SECONDS), "the editor outlived its SIGKILL")
    }

    @Test
    fun `an editor that leaves on its SIGTERM is not killed`() {
        val runId = UUID.randomUUID().toString()
        val pid = editor(runId, ignoresTerm = false)

        end(manager(), pid, runId)

        assertEquals(listOf("TERM"), signals.map { it.first })
        assertTrue(started.single().waitFor(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a process of another run is not killed`() {
        val pid = editor(UUID.randomUUID().toString(), ignoresTerm = true)

        end(manager(), pid, UUID.randomUUID().toString())

        assertEquals(listOf("TERM"), signals.map { it.first })
        assertFalse(started.single().waitFor(REAP_TERM_GRACE_MS, TimeUnit.MILLISECONDS), "a process of another run was ended")
    }
}
