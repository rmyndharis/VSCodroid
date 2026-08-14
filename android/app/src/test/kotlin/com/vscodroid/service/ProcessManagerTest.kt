package com.vscodroid.service

import android.content.Context
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.Runs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/**
 * Tests for [ProcessManager]'s start guard and port allocation.
 *
 * Regression coverage for issue #3: the guard tested `serverProcess != null`,
 * but only `stopServer()` ever cleared that field — the crash path left the
 * dead Process referenced. Every automatic restart after an unexpected exit
 * was therefore refused, and the app stayed wedged until it was relaunched.
 *
 * [Environment] is stubbed so the spawned command is `/bin/echo`, which lets a
 * start actually succeed without the bundled Node binary. The private state is
 * reached by reflection rather than widening the production API for a test.
 */
class ProcessManagerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var manager: ProcessManager

    @BeforeEach
    fun setUp() {
        // Mock Logger to avoid android.util.Log crashes in JVM tests
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkObject(Environment)
        every { Environment.getNodePath(any()) } returns "/bin/echo"
        every { Environment.getServerScript(any()) } returns "server.js"
        every { Environment.buildProcessEnvironment(any(), any()) } returns emptyMap()
        every { Environment.getExtensionsDir(any()) } returns "extensions"
        every { Environment.getUserDataDir(any()) } returns "data"
        every { Environment.getLogsDir(any()) } returns "logs"

        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns tempDir
        every { context.filesDir } returns tempDir

        manager = ProcessManager(context)
    }

    @AfterEach
    fun tearDown() {
        manager.stopServer()
        unmockkAll()
    }

    @Test
    fun `refuses to start while the process is alive`() {
        manager.serverProcessField = mockk<Process>(relaxed = true) {
            every { isAlive } returns true
        }

        assertFalse(manager.startServer(), "a live server must not be started twice")
        assertEquals(0, manager.port, "the guard must reject before a port is taken")
    }

    @Test
    fun `starts again after the process has died`() {
        // The crash path leaves this reference in place; it must not block a restart.
        val dead = mockk<Process>(relaxed = true) { every { isAlive } returns false }
        manager.serverProcessField = dead

        assertTrue(startAndAwaitWatchdog(), "a dead server must be restartable")
        assertNotEquals(dead, manager.serverProcessField, "the dead reference must be replaced")
    }

    @Test
    fun `keeps the port across a restart`() {
        // The WebView's loaded URL and the bridge's allowed-origin check are bound
        // to the port and are not rebuilt on restart, so it has to stay put.
        manager.portField = 45678
        manager.serverProcessField = mockk<Process>(relaxed = true) {
            every { isAlive } returns false
        }

        assertTrue(startAndAwaitWatchdog())
        assertEquals(45678, manager.port, "restart must reuse the original port")
    }

    @Test
    fun `allocates a port on the first start`() {
        assertTrue(startAndAwaitWatchdog())
        assertNotEquals(0, manager.port, "the first start must allocate a port")
    }

    @Test
    fun `the shutdown flag is set before the process is destroyed`() {
        // The watchdog decides crash-versus-stop by reading isShuttingDown, and it
        // wakes the moment the child dies. So the flag has to be true *before*
        // destroy() is called; set afterwards, the watchdog can read a stale false,
        // call it a crash, and restart the server the user just asked to stop.
        //
        // On a phone that is not cosmetic. The server holds the foreground service
        // and the extension host, so a stop that silently restarts leaves the
        // process alive after the user believed they had ended it.
        //
        // Asserted at the moment of destroy() rather than by watching for a
        // restart. A behavioural test was tried first and did not discriminate:
        // with the assignment moved one line down it still passed, because the
        // child takes long enough to die that the flag is set before the watchdog
        // can observe it. A test of ordering has to observe the ordering; racing
        // it only measures how fast the machine is.
        val flagWhenDestroyed = AtomicBoolean(false)
        val process = mockk<Process>(relaxed = true) {
            every { isAlive } returns true
            every { destroy() } answers { flagWhenDestroyed.set(manager.isShuttingDownField) }
        }
        manager.serverProcessField = process

        manager.stopServer()

        assertTrue(
            flagWhenDestroyed.get(),
            "isShuttingDown must already be true when the process is destroyed"
        )
    }

    @Test
    fun `a stop that does not finish in time force-kills rather than waiting on`() {
        // Two invariants in one, and the issue that prompted this asked for
        // neither directly. The wait must be BOUNDED -- the notification's Stop
        // action reaches here on the main thread, and anything dispatched behind
        // it waits too, which is what users saw as a freeze. And when the budget
        // elapses the process must be killed outright, because shortening a wait
        // without the forcible kill trades a freeze for an orphaned Node.
        val process = mockk<Process>(relaxed = true) {
            every { isAlive } returns true
            // The server ignores SIGTERM in this scenario -- the case the budget
            // exists for.
            every { waitFor(any(), any()) } returns false
        }
        manager.serverProcessField = process

        manager.stopServer()

        verify(exactly = 1) { process.waitFor(any(), any()) }
        verify(exactly = 1) { process.destroyForcibly() }
        // The unbounded overload would hang the caller forever, which is the
        // shape this replaced elsewhere in the app.
        verify(exactly = 0) { process.waitFor() }
    }

    // -- Connection token --

    @Test
    fun `reads the token the server wrote`() {
        writeTokenFile("  6f1e4c2a-token\n")

        assertEquals("6f1e4c2a-token", manager.connectionToken, "surrounding whitespace must be trimmed")
    }

    @Test
    fun `has no token before the server has written one`() {
        every { Environment.getUserDataDir(any()) } returns File(tempDir, "empty").absolutePath

        assertNull(manager.connectionToken, "an absent file must not produce a token")
    }

    @Test
    fun `treats an empty token file as no token`() {
        // An empty string would otherwise be appended as `tkn=`, which the server
        // rejects like any wrong token -- a 403 that looks nothing like its cause.
        writeTokenFile("   \n")

        assertNull(manager.connectionToken, "a blank file must not produce a token")
    }

    @Test
    fun `reads the token file once`() {
        val token = writeTokenFile("cached-token")
        assertEquals("cached-token", manager.connectionToken)

        assertTrue(token.delete(), "test could not remove the token file")
        assertEquals(
            "cached-token", manager.connectionToken,
            "the token must be cached; the workbench asks for it on every intercepted request"
        )
    }

    /**
     * Writes the token where the server actually puts it, through the same
     * derivation production uses.
     *
     * The `data/` level is the whole point: the server rewrites the user-data
     * path to `<server-data-dir>/data` before it resolves the token, so a path
     * built straight from `--user-data-dir` lands one directory too high and
     * finds nothing. [Environment.getConnectionTokenPath] is left unstubbed so
     * that derivation is what runs here.
     */
    private fun writeTokenFile(contents: String): File {
        val userDataDir = File(tempDir, "user-data")
        every { Environment.getUserDataDir(any()) } returns userDataDir.absolutePath

        val token = File(Environment.getConnectionTokenPath(mockk(relaxed = true)))
        assertEquals(
            File(userDataDir, "data/token").absolutePath, token.absolutePath,
            "the token path must stay under data/, where the server writes it",
        )
        token.parentFile!!.mkdirs()
        return token.apply { writeText(contents) }
    }

    /**
     * Starts the server and waits for the watchdog to report `/bin/echo` exiting.
     *
     * Waiting is what keeps the watchdog thread from outliving the test and
     * logging through the Logger mock after `unmockkAll()` has torn it down,
     * which collides with the next test class re-mocking the same object in this
     * JVM. It also pins the watchdog itself: without it the latch never fires,
     * and the watchdog is the mechanism the whole restart depends on.
     */
    private fun startAndAwaitWatchdog(): Boolean {
        val exited = CountDownLatch(1)
        manager.onServerCrashed = { exited.countDown() }
        val started = manager.startServer()
        assertTrue(exited.await(5, TimeUnit.SECONDS), "watchdog never reported the exit")
        return started
    }
}

/** Reaches [ProcessManager.serverProcess], which is private production state. */
private var ProcessManager.serverProcessField: Process?
    get() = field("serverProcess").get(this) as Process?
    set(value) = field("serverProcess").set(this, value)

/** Reaches [ProcessManager.isShuttingDown], which is private production state. */
private val ProcessManager.isShuttingDownField: Boolean
    get() = field("isShuttingDown").getBoolean(this)

/** Reaches [ProcessManager._port], which is private production state. */
private var ProcessManager.portField: Int
    get() = field("_port").getInt(this)
    set(value) = field("_port").setInt(this, value)

private fun field(name: String) =
    ProcessManager::class.java.getDeclaredField(name).apply { isAccessible = true }

/**
 * The bootstrap reports a killed child as 128 + signal. Before that, every signal
 * was collapsed to a clean zero, so the branch naming SIGKILL was unreachable and
 * a server killed for running out of memory was logged as having exited cleanly.
 */
class SignalNameTest {

    @Test
    fun `SIGKILL is the 137 the watchdog already looked for`() {
        assertEquals("SIGKILL", signalName(137 - 128))
    }

    @Test
    fun `the signals that actually end this process are named`() {
        assertEquals("SIGSEGV", signalName(11))
        assertEquals("SIGTERM", signalName(15))
        assertEquals("SIGABRT", signalName(6))
    }

    @Test
    fun `an unfamiliar signal is reported rather than hidden`() {
        assertEquals("signal 31", signalName(31))
    }
}

/**
 * The ceiling was a literal 512 on every device. These assert the shape of the
 * replacement rather than the constants, because the constants are a budget and
 * may be retuned; what must not change is that a 2 GB phone and a 16 GB phone
 * stop getting the same answer.
 */
class HeapCeilingTest {

    @Test
    fun `a four gigabyte device keeps what every device used to get`() {
        // The value this replaced. Stated so a retune has to notice it moved the
        // midpoint, rather than discovering it from a bug report.
        assertEquals(HEAP_CEILING_DEFAULT_MB, heapCeilingMb(4L * 1024, isLowRam = false))
    }

    @Test
    fun `a small device gets less than a large one`() {
        val small = heapCeilingMb(2L * 1024, isLowRam = false)
        val large = heapCeilingMb(12L * 1024, isLowRam = false)
        assert(small < large) { "2 GB got $small, 12 GB got $large" }
    }

    @Test
    fun `the band holds at both ends`() {
        assertEquals(HEAP_CEILING_MIN_MB, heapCeilingMb(1L * 1024, isLowRam = false))
        assertEquals(HEAP_CEILING_MAX_MB, heapCeilingMb(64L * 1024, isLowRam = false))
    }

    @Test
    fun `a device the manufacturer flagged as low-RAM gets the floor`() {
        // Whatever totalMem says: the flag is the OEM stating the device is
        // constrained in ways the number does not show.
        assertEquals(HEAP_CEILING_MIN_MB, heapCeilingMb(8L * 1024, isLowRam = true))
    }

    @Test
    fun `an unreadable total falls back rather than silently taking the floor`() {
        // totalMem has been seen reporting 0 on emulators, and 0/8 clamps to the
        // floor, which would look like a considered decision.
        assertEquals(HEAP_CEILING_DEFAULT_MB, heapCeilingMb(0, isLowRam = false))
        assertEquals(HEAP_CEILING_DEFAULT_MB, heapCeilingMb(-1, isLowRam = false))
    }
}
