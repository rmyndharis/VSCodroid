package com.vscodroid.service

import android.app.ActivityManager
import android.content.Context
import com.vscodroid.util.Environment
import com.vscodroid.util.PortFinder
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
    private lateinit var contextMock: Context

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

        contextMock = mockk<Context>(relaxed = true)
        every { contextMock.cacheDir } returns tempDir
        every { contextMock.filesDir } returns tempDir

        manager = ProcessManager(contextMock)
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

    @Test
    fun `the derived heap ceiling reaches the command line`() {
        // The wire, not the predicate. HeapCeilingTest pins how the number is
        // computed and says nothing about whether it is used: replacing
        // "--max-old-space-size=$heapMb" with a literal 512 left all of those
        // green, because heapMb stayed referenced by the log line beside it and
        // the file still compiled.
        //
        // The command line is already observable. startServer spawns /bin/echo
        // in this fixture and redirects stderr into stdout, so the process
        // prints its own arguments and onServerOutput receives them. Nothing had
        // to be added to production code to see them -- the seam was already
        // there, unused.
        //
        // 3 GB is chosen so the expected ceiling is 384, which no literal in the
        // production path happens to equal. Asserting against 512 would have
        // passed against the very mutation this exists to catch.
        val am = mockk<ActivityManager>(relaxed = true) {
            every { isLowRamDevice } returns false
            every { getMemoryInfo(any()) } answers {
                firstArg<ActivityManager.MemoryInfo>().totalMem = 3L * 1024 * 1024 * 1024
            }
        }
        every { contextMock.getSystemService(ActivityManager::class.java) } returns am

        val expected = heapCeilingMb(3L * 1024, isLowRam = false)
        assertNotEquals(
            HEAP_CEILING_DEFAULT_MB, expected,
            "the fixture must not pick the value a regression would also produce"
        )

        val output = StringBuilder()
        val printed = CountDownLatch(1)
        manager.onServerOutput = { line -> output.append(line).append('\n'); printed.countDown() }

        // Through the helper, not startServer() directly: the output latch fires
        // when echo prints, which is before it exits, so waiting on that alone
        // would leave the watchdog thread running into the next test class and
        // logging through a Logger mock that unmockkAll() has already torn down.
        assertTrue(startAndAwaitWatchdog(), "the fixture server must start")
        assertTrue(printed.await(5, TimeUnit.SECONDS), "the spawned process never printed its arguments")

        assertTrue(
            output.contains("--max-old-space-size=$expected"),
            "the derived ceiling must reach the command line; got: $output"
        )
    }

    @Test
    fun `the port on the command line is the one PortFinder handed out`() {
        // `allocates a port on the first start` asserts only that the port is not
        // zero, which stays true if the wiring is replaced by any number at all.
        // What matters is that the port the server is told to listen on is the
        // remembered one: PortFinder exists so the WebView origin survives a cold
        // start, and a port chosen anywhere else silently empties the workbench's
        // IndexedDB.
        //
        // 41234 is outside the scan range PortFinder itself would return, so a
        // reimplementation that scans instead of asking cannot produce it.
        mockkObject(PortFinder)
        every { PortFinder.getOrAllocatePort(any()) } returns 41234

        val output = StringBuilder()
        val printed = CountDownLatch(1)
        manager.onServerOutput = { line -> output.append(line).append('\n'); printed.countDown() }

        assertTrue(startAndAwaitWatchdog(), "the fixture server must start")
        assertTrue(printed.await(5, TimeUnit.SECONDS), "the spawned process never printed its arguments")

        assertEquals(41234, manager.port, "the manager must report the allocated port")
        assertTrue(
            output.contains("--port=41234"),
            "the allocated port must reach the command line; got: $output"
        )
    }

    @Test
    fun `a restart clears the shutdown flag so the next crash is still a crash`() {
        // stopServer() sets isShuttingDown so the watchdog does not read a
        // deliberate stop as a crash. Nothing cleared it on the way back in until
        // startServer() did, and without that the flag stays set for the rest of
        // the process: the next real crash is logged as a graceful shutdown and
        // onServerCrashed never fires, so the automatic restart that exists for
        // exactly that case never runs. The app sits with a dead server and a
        // log line saying it shut down cleanly.
        // Through the helper for the first start too, so that watchdog has already
        // finished before the flag is set. Started bare, it can still be between
        // its flag check and its callback when the second start installs one --
        // and then it counts the latch down itself, which would let this pass
        // with the reset deleted.
        assertTrue(startAndAwaitWatchdog(), "the fixture server must start")
        manager.stopServer()

        // Fails by timeout if the flag survived: the watchdog returns early and
        // the callback is never invoked.
        assertTrue(
            startAndAwaitWatchdog(),
            "a server started after a deliberate stop must still report its exit"
        )
    }

    @Test
    fun `a low-RAM device gets the floor on the command line`() {
        // The pure function is covered; this is the only thing that runs the
        // branch reading the flag off a device. It also settles a claim worth
        // recording: ActivityManager.MemoryInfo() is constructible in a plain JVM
        // test and its fields are writable, so heapCeilingForDevice does not
        // always fall through to its catch -- the try block completes here and
        // produces a value the catch cannot.
        //
        // 8 GB with the flag set, so the expected 256 can only come from the flag
        // being read: on totalMem alone 8 GB derives the maximum, not the floor.
        val am = mockk<ActivityManager>(relaxed = true) {
            every { isLowRamDevice } returns true
            every { getMemoryInfo(any()) } answers {
                firstArg<ActivityManager.MemoryInfo>().totalMem = 8L * 1024 * 1024 * 1024
            }
        }
        every { contextMock.getSystemService(ActivityManager::class.java) } returns am

        assertNotEquals(
            HEAP_CEILING_MIN_MB, heapCeilingMb(8L * 1024, isLowRam = false),
            "the fixture must not pick a size that reaches the floor without the flag"
        )

        val output = StringBuilder()
        val printed = CountDownLatch(1)
        manager.onServerOutput = { line -> output.append(line).append('\n'); printed.countDown() }

        assertTrue(startAndAwaitWatchdog(), "the fixture server must start")
        assertTrue(printed.await(5, TimeUnit.SECONDS), "the spawned process never printed its arguments")

        assertTrue(
            output.contains("--max-old-space-size=$HEAP_CEILING_MIN_MB"),
            "the low-RAM flag must reach the command line; got: $output"
        )
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
