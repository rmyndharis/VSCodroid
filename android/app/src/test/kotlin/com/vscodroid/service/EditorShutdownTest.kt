package com.vscodroid.service

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vscodroid.setup.finishTestProcess
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkConstructor
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EditorShutdownTest {
    @TempDir lateinit var temp: File
    private lateinit var manager: ProcessManager
    private lateinit var context: Context
    private val killed = mutableListOf<Int>()
    private val note get() = File(temp, "server/editor-server.pid")
    private val runId get() = File(temp, "run-id").readText()

    @BeforeEach
    fun setUp() {
        mockEditorGateway()
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
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns temp
        every { context.cacheDir } returns temp
        every { context.applicationInfo } returns ApplicationInfo().apply { uid = 12345 }
        manager = ProcessManager(context)
        set("procDir", File(temp, "proc"))
        manager.killRecordedProcess = { killed += it }
        manager.editorsDieOfTheirSigterm(File(temp, "proc"))
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
        // What server.js leaves when it answers the signal: its child has exited and the
        // child's exit handler deleted the note before the bootstrap left.
        record(8123)
        bootstrap(alive = true, exits = true) {
            assertTrue(note.delete())
            assertTrue(File(temp, "proc/8123").deleteRecursively())
        }
        manager.stopServer()
        assertTrue(killed.isEmpty())
    }

    @Test
    fun `a bootstrap that leaves its note behind has its editor reaped`() {
        // A bootstrap killed by the stop's signal before it had a handler forwarded
        // nothing: it exits within the wait, and its note and child are both still there.
        val owner = runId
        val events = mutableListOf<String>()
        every { EditorGateway.instance.withdrawBackend(any()) } answers { events += "withdraw ${firstArg<String>()}" }
        manager.killRecordedProcess = { events += "kill $it" }
        record(8123)
        bootstrap(alive = true, exits = true)
        manager.stopServer()
        assertEquals(listOf("withdraw $owner", "kill 8123"), events, "the run's routing must go before its editor")
        assertFalse(note.exists())
    }

    @Test
    fun `a reap asks the editor to leave with SIGTERM and does not kill one that left`() {
        val signals = mutableListOf<String>()
        record(8123)
        manager.terminateRecordedProcess = {
            signals += "TERM $it"
            File(temp, "proc/$it").deleteRecursively()
        }
        manager.killRecordedProcess = { signals += "KILL $it" }

        manager.stopServer()

        assertEquals(listOf("TERM 8123"), signals)
    }

    @Test
    fun `an editor that outlives its SIGTERM is killed once the bounded wait has run`() {
        val signals = mutableListOf<String>()
        record(8123)
        manager.terminateRecordedProcess = { signals += "TERM $it" }
        manager.killRecordedProcess = { signals += "KILL $it" }

        val started = System.nanoTime()
        manager.stopServer()
        val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(listOf("TERM 8123", "KILL 8123"), signals)
        assertTrue(waited >= REAP_TERM_GRACE_MS, "killed after $waited ms, before the editor's handler had its time")
        assertTrue(waited < REAP_TERM_GRACE_MS + 2_000, "the wait for a terminated editor took $waited ms")
    }

    @Test
    fun `a pid that stops being the editor during the wait is not killed`() {
        val signals = mutableListOf<String>()
        record(8123)
        manager.terminateRecordedProcess = {
            signals += "TERM $it"
            File(temp, "proc/$it/cmdline").writeText("/bin/sh\u0000")
        }
        manager.killRecordedProcess = { signals += "KILL $it" }

        manager.stopServer()

        assertEquals(listOf("TERM 8123"), signals)
    }

    @Test
    fun `a pid given to an editor of another run during the wait is not killed`() {
        val signals = mutableListOf<String>()
        record(8123)
        manager.terminateRecordedProcess = {
            signals += "TERM $it"
            File(temp, "proc/$it/environ").writeText("VSCODROID_SERVER_RUN_ID=another-run\u0000")
        }
        manager.killRecordedProcess = { signals += "KILL $it" }

        manager.stopServer()

        assertEquals(listOf("TERM 8123"), signals)
    }

    @Test
    fun `an unrecorded editor given to another run after the scan gets no signal`() {
        // The signals go out once the whole scan is done, so a pid it found can have been
        // freed and given to an editor of another run by the time its turn comes.
        val signals = mutableListOf<String>()
        val owner = runId
        manager.terminateRecordedProcess = {
            signals += "TERM $it"
            File(temp, "proc/${if (it == 8123) 8124 else 8123}").takeIf { dir -> dir.isDirectory }
                ?.let { dir -> File(dir, "environ").writeText("VSCODROID_SERVER_RUN_ID=another-run\u0000") }
            File(temp, "proc/$it").deleteRecursively()
        }
        manager.killRecordedProcess = { signals += "KILL $it" }
        bootstrap(alive = true, exits = true) {
            unrecordedChild(8123, owner)
            unrecordedChild(8124, owner)
        }

        manager.stopServer()

        assertEquals(1, signals.size, "signalled: $signals")
        assertTrue(signals.single().startsWith("TERM "), "signalled: $signals")
    }

    @Test
    fun `an unrecorded editor is asked with SIGTERM first and every one is ended`() {
        val signals = mutableListOf<String>()
        val owner = runId
        manager.terminateRecordedProcess = { signals += "TERM $it" }
        manager.killRecordedProcess = { signals += "KILL $it" }
        // Three that ignore the SIGTERM: their waits together pass the scan's own time limit.
        bootstrap(alive = true, exits = true) {
            unrecordedChild(8123, owner)
            unrecordedChild(8124, owner)
            unrecordedChild(8125, owner)
        }

        manager.stopServer()

        val pids = listOf(8123, 8124, 8125)
        assertEquals(pids.flatMap { listOf("TERM $it", "KILL $it") }.toSet(), signals.toSet())
        assertEquals(6, signals.size)
        for (pid in pids) {
            assertTrue(signals.indexOf("TERM $pid") < signals.indexOf("KILL $pid"), "$pid was killed first: $signals")
        }
    }

    @Test
    fun `an editor that fails the identity checks gets no signal at all`() {
        val signals = mutableListOf<String>()
        manager.terminateRecordedProcess = { signals += "TERM $it" }
        manager.killRecordedProcess = { signals += "KILL $it" }
        record(8123)
        File(temp, "proc/8123/environ").writeText("VSCODROID_SERVER_RUN_ID=replacement\u0000")
        bootstrap(alive = true, exits = true) {
            unrecordedChild(8124, "another-run")
            unrecordedChild(8125, runId, entry = "user-script.js")
        }

        manager.stopServer()

        assertTrue(signals.isEmpty(), "signalled: $signals")
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
    fun `a stop before spawn publication still kills an unresponsive bootstrap`() {
        set("serverProcess", null)
        val rejected = PidProcess(4242L)
        mockkConstructor(ProcessBuilder::class)
        every { anyConstructed<ProcessBuilder>().start() } answers {
            manager.stopServer()
            rejected
        }

        assertFalse(manager.startServer())

        assertEquals(listOf(4242), killed)
        assertFalse(manager.hasLiveServer())
    }

    @Test
    fun `a rejected spawn reaps its editor note written during the stop wait`() {
        set("serverProcess", null)
        var rejectedRun = ""
        val rejected = PidProcess(4242L) {
            record(8123, owner = rejectedRun)
        }
        mockkConstructor(ProcessBuilder::class)
        val environment = mutableMapOf<String, String>()
        every { anyConstructed<ProcessBuilder>().environment() } returns environment
        every { anyConstructed<ProcessBuilder>().start() } answers {
            rejectedRun = environment.getValue("VSCODROID_SERVER_RUN_ID")
            manager.stopServer()
            rejected
        }

        assertFalse(manager.startServer())

        assertTrue(rejectedRun.isNotEmpty(), "the fixture must reach the actual spawn boundary")
        assertEquals(listOf(4242, 8123), killed)
        assertFalse(note.exists())
    }

    @Test
    fun `a retry ends its unrecorded editor before publishing the replacement owner`() {
        val previous = runId
        unrecordedChild(8123, previous)
        unrecordedChild(8124, "another-run")
        unrecordedChild(8125, previous, entry = "user-script.js")
        unrecordedChild(8126, previous, uid = 12346)
        unrecordedChild(8127, previous)
        assertTrue(File(temp, "proc/8127/environ").delete())
        manager.killRecordedProcess = { pid ->
            val owner = ProcessManager::class.java.getDeclaredField("serverRunId")
                .apply { isAccessible = true }.get(manager)
            assertEquals(previous, owner, "the replacement was published before the old editor ended")
            killed += pid
        }

        startFixture()

        assertEquals(listOf(8123), killed)
        assertNotEquals(previous, runId)
        manager.stopServer()
        assertEquals(listOf(8123), killed, "Stop must not signal an editor from another run")
    }

    @Test
    fun `a rejected spawn ends only its owned editor when no note was written`() {
        set("serverProcess", null)
        var rejectedRun = ""
        val rejected = PidProcess(4242L) {
            unrecordedChild(8123, rejectedRun)
            unrecordedChild(8124, "replacement-run")
            unrecordedChild(8125, rejectedRun, entry = "user-script.js")
            unrecordedChild(8126, rejectedRun, uid = 12346)
            unrecordedChild(8127, rejectedRun, executable = "/different-node")
            unrecordedChild(8128, rejectedRun)
            File(temp, "proc/8128/environ").appendText("X".repeat(131073))
            unrecordedChild(8129, rejectedRun)
            File(temp, "proc/8129/cmdline").appendText("X".repeat(16385))
            unrecordedChild(8130, rejectedRun, entry = "server-main.js ")
            File(temp, "proc/8130/cmdline").let { it.writeText(it.readText().dropLast(1)) }
            unrecordedChild(8131, rejectedRun, entry = "server-main.js ")
        }
        mockkConstructor(ProcessBuilder::class)
        val environment = mutableMapOf<String, String>()
        every { anyConstructed<ProcessBuilder>().environment() } returns environment
        every { anyConstructed<ProcessBuilder>().start() } answers {
            rejectedRun = environment.getValue("VSCODROID_SERVER_RUN_ID")
            manager.stopServer()
            rejected
        }

        assertFalse(manager.startServer())

        assertTrue(rejectedRun.isNotEmpty())
        assertEquals(listOf(4242, 8123), killed)
        assertFalse(note.exists())
    }

    @Test
    fun `a published bootstrap exiting before its note still leaves no owned editor`() {
        val owner = runId
        bootstrap(alive = true, exits = true) { unrecordedChild(8123, owner) }

        manager.stopServer()

        assertEquals(listOf(8123), killed)
        assertFalse(note.exists())
    }

    @Test
    fun `an unrelated note cannot hide an owned editor left before its note`() {
        record(8124, owner = "replacement")
        val replacement = note.readText()
        val owner = runId
        bootstrap(alive = true, exits = true) { unrecordedChild(8123, owner) }

        manager.stopServer()

        assertEquals(listOf(8123), killed)
        assertEquals(replacement, note.readText())
    }

    private fun unrecordedChild(
        pid: Int,
        owner: String,
        uid: Int = 12345,
        entry: String = "server-main.js",
        executable: String = Environment.getNodePath(context),
    ) {
        val directory = File(temp, "proc/$pid").apply { mkdirs() }
        File(directory, "status").writeText("Uid:\t$uid\t$uid\t$uid\t$uid\n")
        File(directory, "environ").writeText("VSCODROID_SERVER_RUN_ID=$owner\u0000")
        File(directory, "cmdline").writeText(
            executable + "\u0000--max-old-space-size=512\u0000" +
                File(temp, "server/vscode-reh/out/$entry").path + "\u0000",
        )
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

    @Test
    fun `reaping an unrecorded editor and starting again sweeps old run sockets`() {
        val firstRun = runId
        val firstSocket = editorBackendSocket(context, firstRun)!!.apply { writeText("") }
        val orphanSocket = editorBackendSocket(context, "older-unrecorded-run")!!.apply { writeText("") }
        val unrelated = File(temp, "keep.txt").apply { writeText("keep") }
        unrecordedChild(8123, firstRun)

        startFixture()

        assertEquals(listOf(8123), killed)
        assertFalse(firstSocket.exists(), "the reaped unrecorded run socket should be removed")
        assertFalse(orphanSocket.exists(), "an older orphaned run socket should be swept")
        assertTrue(unrelated.exists(), "files outside the run-socket pattern must survive")
    }

    @Test
    fun `run socket sweep keeps only the socket named by a live editor note`() {
        val liveRun = "live-recorded-run"
        val liveRecordedSocket = editorBackendSocket(context, liveRun)!!.apply { writeText("") }
        val deadOrphanSocket = editorBackendSocket(context, "dead-orphan-run")!!.apply { writeText("") }
        val unrelatedSocket = File(temp, "user.sock").apply { writeText("") }
        record(8123, port = manager.port + 1, owner = liveRun)
        val withSocketPath = JSONObject(note.readText()).put("socketPath", liveRecordedSocket.path)
        note.writeText(withSocketPath.toString())

        startFixture()

        assertTrue(liveRecordedSocket.exists(), "a live recorded pid in editor-server.pid keeps its socket")
        assertFalse(deadOrphanSocket.exists(), "an orphaned run socket without a live note is swept")
        assertTrue(unrelatedSocket.exists(), "non-run socket filenames must not be touched")

        assertTrue(File(temp, "proc/8123").deleteRecursively())
        startFixture()

        assertFalse(liveRecordedSocket.exists(), "once the recorded pid is gone from proc, its socket is swept")

        val unparseableNoteSocket = editorBackendSocket(context, "unparseable-note-run")!!.apply { writeText("") }
        note.writeText("{not-json")
        startFixture()

        assertTrue(unparseableNoteSocket.exists(), "an unreadable editor-server.pid prevents sweeping")
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
    class PidProcess(private val bootstrapPid: Long, private val waiting: () -> Unit = {}) : Process() {
        fun pid(): Long = bootstrapPid
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = throw UnsupportedOperationException()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { waiting(); return false }
        override fun exitValue(): Int = throw IllegalThreadStateException()
        override fun destroy() {}
        override fun destroyForcibly(): Process = this
        override fun isAlive(): Boolean = true
    }

    private fun set(name: String, value: Any?) {
        ProcessManager::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(manager, value)
        }
    }
}
