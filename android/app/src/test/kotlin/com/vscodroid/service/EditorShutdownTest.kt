package com.vscodroid.service

import android.content.Context
import com.vscodroid.setup.finishTestProcess
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EditorShutdownTest {
    @TempDir lateinit var temp: File
    private lateinit var manager: ProcessManager
    private val killed = mutableListOf<Int>()
    private val note get() = File(temp, "server/editor-server.pid")
    private val runId get() = File(temp, "run-id").readText()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkObject(Environment)
        val script = File(temp, "bootstrap.sh").apply {
            writeText("#!/bin/sh\nprintf '%s' \"\$VSCODROID_SERVER_RUN_ID\" > run-id\n")
            check(setExecutable(true))
        }
        every { Environment.getNodePath(any()) } returns script.path
        every { Environment.getServerScript(any()) } returns "server.js"
        every { Environment.buildProcessEnvironment(any(), any()) } returns emptyMap()
        every { Environment.getExtensionsDir(any()) } returns "extensions"
        every { Environment.getUserDataDir(any()) } returns "data"
        every { Environment.getLogsDir(any()) } returns File(temp, "logs").path
        every { Environment.getServerDir(any()) } returns File(temp, "server").path
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns temp
        every { context.cacheDir } returns temp
        manager = ProcessManager(context)
        set("procDir", File(temp, "proc"))
        manager.killRecordedProcess = { killed += it }
        startFixture()
    }

    @AfterEach
    fun tearDown() {
        manager.stopServer()
        unmockkAll()
    }

    @Test
    fun `stop ends a surviving editor after its bootstrap has already exited`() {
        val child = ProcessBuilder("/bin/sleep", "30").apply {
            environment()["VSCODROID_SERVER_RUN_ID"] = runId
        }.start()
        try {
            val pid = (Process::class.java.getMethod("pid").invoke(child) as Long).toInt()
            record(pid)
            manager.killRecordedProcess = { requested ->
                check(requested == pid) { "only the fixture child may be signalled" }
                child.destroyForcibly()
            }
            assertFalse(manager.isRunning(), "the bootstrap must have exited before Stop")
            assertTrue(child.isAlive, "the child must survive until Stop")

            manager.stopServer()

            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Stop left the recorded editor alive")
            assertFalse(note.exists(), "the consumed note must not survive Stop")
        } finally {
            child.destroyForcibly()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `each spawned bootstrap receives a distinct nonempty run identity`() {
        val first = runId
        assertTrue(first.isNotEmpty())
        startFixture()
        assertTrue(runId.isNotEmpty())
        assertNotEquals(first, runId)
    }

    @Test
    fun `stop reaps only once after an already exited bootstrap`() {
        record(8123)
        manager.stopServer()
        manager.stopServer()
        assertEquals(listOf(8123), killed)
    }

    @Test
    fun `a cooperative bootstrap leaves the recorded editor alone`() {
        record(8123)
        bootstrap(alive = true, exits = true)
        manager.stopServer()
        assertTrue(killed.isEmpty())
    }

    @Test
    fun `a bootstrap timeout ends its recorded editor`() {
        record(8123)
        bootstrap(alive = true, exits = false)
        manager.stopServer()
        assertEquals(listOf(8123), killed)
    }

    @Test
    fun `a bootstrap that outlives the first signal is killed by pid`() {
        record(8123)
        set("serverProcess", PidProcess(4242L))
        manager.stopServer()
        assertEquals(listOf(4242, 8123), killed)
    }

    @Test
    fun `a bootstrap whose pid is unreadable is never signalled by pid 0`() {
        record(8123)
        set("serverProcess", PidProcess(0L))
        manager.stopServer()
        assertEquals(listOf(8123), killed)
    }

    @Test
    fun `stop preserves a note replaced while waiting for the bootstrap`() {
        record(8123)
        bootstrap(alive = true, exits = false) { record(8124, owner = "replacement") }
        manager.stopServer()
        assertTrue(killed.isEmpty())
        assertEquals(8124, JSONObject(note.readText()).getInt("pid"))
    }

    /**
     * The check right before the signal, which nothing else exercises: the first look at
     * the note is a few lines above it, and the reads of the process's environment and
     * command line sit between the two. A replacement bootstrap that writes its own note
     * in that gap is a different run on the same port, and the pid in the note this Stop
     * captured must not be signalled on its account.
     *
     * The environment is read through a pipe so the gap can be held open: the replacement
     * note is written from inside the read, after the first look and before the second.
     */
    @Test
    @Timeout(30)
    fun `stop preserves a note replaced after the process was checked`() {
        val original = runId
        record(8123)
        val environ = File(temp, "proc/8123/environ")
        assertTrue(environ.delete())
        val fifo = try {
            ProcessBuilder("mkfifo", environ.path).redirectErrorStream(true).start()
        } catch (_: java.io.IOException) {
            null
        }
        val made = fifo != null && finishTestProcess(fifo).first == 0
        assumeTrue(made, "this host cannot make a named pipe")

        val replacing = Thread {
            FileOutputStream(environ).use { pipe ->
                record(8124, owner = "replacement")
                pipe.write("VSCODROID_SERVER_RUN_ID=$original\u0000".toByteArray())
            }
        }.apply { isDaemon = true; start() }

        manager.stopServer()
        replacing.join(5_000)

        assertTrue(killed.isEmpty(), "the pid of the replaced note was signalled: $killed")
        assertEquals(8124, JSONObject(note.readText()).getInt("pid"), "the replacement's note was consumed")
    }

    @Test
    fun `stop refuses a note from another run on the same port`() {
        record(8123, owner = "another-run")
        manager.stopServer()
        assertTrue(killed.isEmpty())
        assertTrue(note.exists())
    }

    @Test
    fun `stop refuses a note for another port`() {
        record(8123, port = manager.port + 1)
        manager.stopServer()
        assertTrue(killed.isEmpty())
        assertTrue(note.exists())
    }

    @Test
    fun `stop does not kill a pid reused by another editor run`() {
        record(8123)
        File(temp, "proc/8123/environ").writeText("VSCODROID_SERVER_RUN_ID=replacement\u0000")
        manager.stopServer()
        assertTrue(killed.isEmpty())
    }

    @Test
    fun `stop does not kill a pid reused by a terminal`() {
        record(8123)
        File(temp, "proc/8123/cmdline").writeText("/bin/sh\u0000")
        manager.stopServer()
        assertTrue(killed.isEmpty())
    }

    @Test
    fun `stop leaves a process alone when its environment cannot be read`() {
        record(8123)
        assertTrue(File(temp, "proc/8123/environ").delete())
        manager.stopServer()
        assertTrue(killed.isEmpty())
        assertTrue(note.exists())
    }

    @Test
    fun `an overlapping start retains its own process handle and note`() {
        record(8123)
        var replacement: Process? = null
        bootstrap(alive = false, exits = true) {
            startFixture()
            replacement = ProcessManager::class.java.getDeclaredField("serverProcess").let {
                it.isAccessible = true
                it.get(manager) as Process
            }
            record(8124)
        }
        manager.stopServer()
        assertTrue(killed.isEmpty())
        assertEquals(8124, JSONObject(note.readText()).getInt("pid"))
        val current = ProcessManager::class.java.getDeclaredField("serverProcess").let {
            it.isAccessible = true
            it.get(manager)
        }
        assertTrue(current === replacement, "the previous stop discarded the new process handle")
    }

    private fun startFixture() {
        val exited = CountDownLatch(1)
        manager.onServerCrashed = { exited.countDown() }
        assertTrue(manager.startServer())
        assertTrue(exited.await(5, TimeUnit.SECONDS), "the fixture bootstrap did not exit")
    }

    private fun record(pid: Int, port: Int = manager.port, owner: String = runId) {
        note.parentFile!!.mkdirs()
        note.writeText(JSONObject().put("pid", pid).put("port", port).put("runId", owner).toString())
        File(temp, "proc/$pid/cmdline").apply {
            parentFile!!.mkdirs()
            writeText("/lib/libnode.so\u0000/data/server/vscode-reh/out/server-main.js\u0000")
        }
        File(temp, "proc/$pid/environ").writeText("VSCODROID_SERVER_RUN_ID=$owner\u0000")
    }

    private fun bootstrap(alive: Boolean, exits: Boolean, waiting: () -> Unit = {}) {
        set("serverProcess", mockk<Process>(relaxed = true) {
            every { isAlive } returns alive
            every { waitFor(any(), any()) } answers { waiting(); exits }
        })
    }

    /**
     * A bootstrap that never leaves, whose `pid()` the reflective read in `ProcessManager`
     * can reach: a public class, which a mock or an anonymous object is not.
     */
    class PidProcess(private val bootstrapPid: Long) : Process() {
        fun pid(): Long = bootstrapPid
        override fun getOutputStream(): OutputStream = throw UnsupportedOperationException()
        override fun getInputStream(): InputStream = throw UnsupportedOperationException()
        override fun getErrorStream(): InputStream = throw UnsupportedOperationException()
        override fun waitFor(): Int = throw UnsupportedOperationException()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = throw IllegalThreadStateException()
        override fun destroy() {}
        override fun destroyForcibly(): Process = this
        override fun isAlive(): Boolean = true
    }

    private fun set(name: String, value: Any) {
        ProcessManager::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(manager, value)
        }
    }
}
