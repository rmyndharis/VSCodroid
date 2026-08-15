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
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
        // `Logger.w` and `Logger.e` take a defaulted throwable rather than having
        // a two-argument overload, and mockk matches on the arity of the call.
        // A two-argument call left unstubbed reaches android.util.Log, which is
        // not mocked and throws, so both arities are stubbed.
        every { Logger.w(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs

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
        // The WebView's loaded URL and the WebViewClient are bound to the port and are
        // not rebuilt on restart, so it has to stay put. (This named the bridge's
        // allowed-origin check as the second binding until #144 removed that check.)
        manager.portField = 45678
        manager.serverProcessField = mockk<Process>(relaxed = true) {
            every { isAlive } returns false
        }

        assertTrue(startAndAwaitWatchdog())
        assertEquals(45678, manager.port, "restart must reuse the original port")
    }

    @Test
    fun `a file sitting where TMPDIR belongs is reported rather than ignored`() {
        // `exists()` answers "something is here" and the code meant "a usable
        // directory is here". They agree in every case anyone pictures — the path
        // is absent, or it is the directory we made last time — and part company
        // when a file is there, at which point `mkdirs()` cannot succeed and its
        // false was being discarded.
        //
        // Not cosmetic: this path is TMPDIR and TMUX_TMPDIR for the server, so
        // the consequence arrives later as temporary-file failures with nothing
        // pointing back here.
        val warnings = mutableListOf<String>()
        every { Logger.w(any(), any()) } answers { warnings += secondArg<String>() }

        val blocker = File(tempDir, "tmp")
        check(blocker.createNewFile()) { "could not create the fixture" }
        check(blocker.isFile) { "the fixture must be a file, or this proves nothing" }

        assertTrue(
            startAndAwaitWatchdog(),
            "a broken TMPDIR must not stop a start; a server with one is far better " +
                "than no server",
        )
        assertTrue(
            warnings.any { it.contains(blocker.path) },
            "the failure was discarded rather than reported: $warnings",
        )
    }

    @Test
    fun `a usable TMPDIR is left alone and says nothing`() {
        // Control for the case above. Without it, code that warned unconditionally
        // would satisfy it while telling the user their TMPDIR was broken on every
        // healthy start.
        val warnings = mutableListOf<String>()
        every { Logger.w(any(), any()) } answers { warnings += secondArg<String>() }

        check(File(tempDir, "tmp").mkdirs()) { "could not create the fixture" }

        assertTrue(startAndAwaitWatchdog())
        assertTrue(
            warnings.none { it.contains("TMPDIR") },
            "a working TMPDIR must not be reported as broken: $warnings",
        )
    }

    @Test
    fun `a restart does not care whether the port is still free`() {
        // Deliberate, and the opposite of what it looks like. A restart used to
        // refuse when something already held the port, on the reasoning that the
        // holder would satisfy the health probe and refill the restart budget.
        // The reasoning about the budget was right; the conclusion was not, and
        // this pins the behaviour that replaced it so nobody restores the check
        // without reading why it went.
        //
        // The holder in the case that matters is not a stranger. It is our own
        // editor server, surviving a SIGKILL of the parent that forked it -- a
        // live, working server that the open WebView is still talking to.
        // Refusing to start took that editor away from the user to fix
        // bookkeeping, and could not recover afterwards: the port is resolved only
        // while it is zero, so the refusal repeated for the life of the instance.
        //
        // StubServer(null) holds the port without serving on it, so this asserts
        // the start proceeds because of the port's state and not because of
        // anything the holder answers.
        val holder = StubServer(null)
        try {
            manager.portField = holder.port

            assertTrue(
                startAndAwaitWatchdog(),
                "a held port must not stop a restart; the surviving server is usually " +
                    "ours, and the user is still using it",
            )
            assertEquals(
                holder.port, manager.port,
                "and the port must still be the one the WebView is bound to",
            )
        } finally {
            holder.stop()
        }
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
    fun `stopping a second time touches nothing and reports nothing running`() {
        // Two callers now stop the same server on one Stop press. NodeService
        // does it inline when the notification action arrives, because a service
        // that is started *and* bound is not destroyed by stopSelf() alone, and
        // then Service.onDestroy does it again once the activity finishes and
        // releases the binding. The second call has to be harmless as a property
        // of this class rather than as an accident of ordering, because which
        // one runs second depends on how quickly the activity goes away.
        val process = mockk<Process>(relaxed = true) {
            every { isAlive } returns true
            every { waitFor(any(), any()) } returns true
        }
        manager.serverProcessField = process

        manager.stopServer()
        assertNull(manager.serverProcessField, "the first stop must release the process")

        manager.stopServer()

        assertNull(manager.serverProcessField, "the second stop must not resurrect anything")
        assertFalse(manager.isRunning(), "a stopped server must not report itself alive")
        // Once, from the first call. A destroy issued against an already-reaped
        // process is where a double stop would start signalling a PID the system
        // has since handed to something else.
        verify(exactly = 1) { process.destroy() }
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
    fun `the server is told to bind loopback, and nothing widens it`() {
        // The heap ceiling and the port are each pinned to the command line above.
        // The bind address was not, and it is the one of the three that cannot be
        // walked back after a release: a server on 0.0.0.0 puts the editor and its
        // connection token on whatever network the phone has joined.
        //
        // It is also load-bearing somewhere non-obvious. PortFinder probes
        // availability on 127.0.0.1 specifically, and its own comment records why:
        // Java sets SO_REUSEADDR by default, so a wildcard ServerSocket can bind a
        // port that is already held on loopback and report it free. That probe is
        // correct only while the server binds the address it probes. Widening the
        // bind here would not fail anything -- it would quietly turn PortFinder's
        // answer back into the wrong one, three files away.
        //
        // The literal is repeated rather than read from production, deliberately.
        // Asserting against the same constant the code uses would pass whatever
        // that constant became, which is the mutation this exists to catch.
        val output = StringBuilder()
        val printed = CountDownLatch(1)
        manager.onServerOutput = { line -> output.append(line).append('\n'); printed.countDown() }

        assertTrue(startAndAwaitWatchdog(), "the fixture server must start")
        assertTrue(printed.await(5, TimeUnit.SECONDS), "the spawned process never printed its arguments")

        assertTrue(
            output.contains("--host=127.0.0.1"),
            "the server must be told to bind loopback; got: $output"
        )
        // Both directions, because the first assertion alone still passes if a
        // second, wider bind argument is appended after it.
        assertFalse(
            output.contains("0.0.0.0"),
            "nothing may put the server on a routable address; got: $output"
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

    @Test
    fun `the watchdog names the signal that killed the server`() {
        // SignalNameTest pins the translation and nothing checked that the
        // watchdog performs it. Replacing the call with the bare number leaves
        // every one of those green: they call signalName directly, and the log
        // line is the only place the result was ever used.
        //
        // What is lost is only the diagnostic -- onServerCrashed still fires, so
        // the restart still happens -- which is why it could rot unnoticed. The
        // log is what someone reads to understand why the server died, and
        // "signal 11" makes them look it up while "SIGSEGV" tells them.
        //
        // 139, not 137: the branch above this one claims 137 for the
        // out-of-memory message, so a fixture using it never reaches the code
        // under test at all. 139 is 128 + SIGSEGV, and "SIGSEGV" is a string the
        // broken path cannot produce.
        manager.serverProcessField = mockk<Process>(relaxed = true) {
            every { waitFor() } returns 139
            every { isAlive } returns false
        }

        val named = CountDownLatch(1)
        every { Logger.w(any(), match<String> { it.contains("SIGSEGV") }, any()) } answers {
            named.countDown()
        }

        ProcessManager::class.java.getDeclaredMethod("startWatchdog")
            .apply { isAccessible = true }
            .invoke(manager)

        assertTrue(
            named.await(5, TimeUnit.SECONDS),
            "the watchdog must name the signal, not print its number"
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

/** Reaches `ProcessManager._isReady`, which is private production state. */
private var ProcessManager.readyField: Boolean
    get() = field("_isReady").getBoolean(this)
    set(value) = field("_isReady").setBoolean(this, value)

/** Reaches [ProcessManager._port], which is private production state. */
private var ProcessManager.portField: Int
    get() = field("_port").getInt(this)
    set(value) = field("_port").setInt(this, value)

/**
 * Reaches `ProcessManager.cachedToken`, which is private production state.
 *
 * Needed because the token is cached on first read and deliberately never
 * invalidated — correct in production, since the server reuses the file rather
 * than regenerating it, and inconvenient in a test that wants to change what the
 * file says after something has already read it.
 */
private var ProcessManager.cachedTokenField: String?
    get() = field("cachedToken").get(this) as String?
    set(value) = field("cachedToken").set(this, value)

private fun field(name: String) =
    ProcessManager::class.java.getDeclaredField(name).apply { isAccessible = true }

/**
 * Readiness: whether the server is *serving*, as opposed to whether its process
 * exists.
 *
 * The two were the same question to every caller until they were not.
 * `MainActivity` navigated its WebView the moment `isRunning()` was true, and
 * that is true from the instant the process is spawned — while the editor server
 * inside it is still seconds away from binding its port, and for the whole of a
 * restart after a crash. The user got a connection-refused page, and
 * `onReceivedError` only logs, so nothing took it away again.
 *
 * These pin the flag's transitions rather than the navigation, because the
 * navigation lives in an Activity and no JVM test can reach it. What they can do
 * is make sure the answer the Activity now trusts is the answer the health probe
 * actually gave.
 */
class ServerReadinessTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var manager: ProcessManager
    private lateinit var contextMock: Context
    private var stub: StubServer? = null

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
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
        stub?.stop()
        manager.stopServer()
        unmockkAll()
    }

    /** Points the manager at a loopback server that answers with [status]. */
    private fun serving(status: Int): StubServer =
        StubServer(status).also { stub = it; manager.portField = it.port }

    @Test
    fun `readiness comes from the probe answering, not from the process existing`() {
        // Kills: moving `_isReady = true` out of the `if (isServerHealthy())`
        // branch in waitForReady, or deriving readiness from process liveness
        // anywhere. There is no process at all here -- serverProcess is null and
        // isRunning() is false -- so a liveness-derived answer cannot pass.
        serving(200)

        assertFalse(manager.isRunning(), "the fixture must have no process, or it proves nothing")
        val ready = runBlocking { manager.waitForReady(timeoutMs = 3_000, pollIntervalMs = 25) }

        assertTrue(ready, "a server answering 200 is ready")
        assertTrue(manager.isReady(), "and the answer has to be recorded for the main thread")
    }

    @Test
    fun `the probe asks the route that answers before the token check`() {
        // Kills: probing `/` instead of `/version`. `/` answers 403 as soon as
        // the server requires a connection token, so a probe pointed at it can
        // only ever report a healthy start for a server that will serve the user
        // nothing but Forbidden. `/version` is answered before that check.
        val server = serving(200)

        runBlocking { manager.waitForReady(timeoutMs = 3_000, pollIntervalMs = 25) }

        assertEquals("GET /version", server.lastRequestLine()?.substringBeforeLast(' '))
    }

    @Test
    fun `a server that answers Forbidden is not ready`() {
        // Kills: relaxing the probe from `responseCode == 200` to `< 500` or
        // `< 400`. That exact relaxation shipped once. 403 is the value that
        // discriminates -- a 404 or a 500 would fail against the relaxed form
        // too, and so would prove less.
        serving(403)

        val ready = runBlocking { manager.waitForReady(timeoutMs = 400, pollIntervalMs = 25) }

        assertFalse(ready, "403 is an answer, but it is not a healthy one")
        assertFalse(manager.isReady(), "and it must not leave the flag set")
    }

    @Test
    fun `nothing listening is not ready`() {
        // The cold-start window itself: a port is allocated and the process is
        // spawning, but nothing is bound to it yet. This is the state the
        // Activity used to navigate into.
        val server = StubServer(200)
        manager.portField = server.port
        server.stop()

        val ready = runBlocking { manager.waitForReady(timeoutMs = 400, pollIntervalMs = 25) }

        assertFalse(ready)
        assertFalse(manager.isReady())
    }

    @Test
    fun `starting clears a readiness left over from the previous server`() {
        // Kills: deleting `_isReady = false` from startServer(). This instance is
        // reused across restarts -- it keeps its port on purpose -- so a stale
        // true would report the dead server's readiness for the whole of the new
        // server's startup, which is the window this work is about.
        //
        // The spawn is made to fail, and that is what makes the test mean what it
        // says. Written against a successful `/bin/echo` start it passed without
        // startServer clearing anything at all: echo exits before the assertion
        // and the watchdog's own clear had already run, so the test was measuring
        // the watchdog while claiming to measure startServer. A failed spawn
        // starts no watchdog -- the clear happens before the ProcessBuilder call,
        // the throw is caught, and nothing else in the class has run -- so
        // startServer is the only thing that can have cleared it.
        //
        // It is also the sharper case. A restart whose spawn fails must not leave
        // the dead server's readiness standing for a caller to act on.
        every { Environment.getNodePath(any()) } returns "/nonexistent/node"
        manager.readyField = true

        assertFalse(manager.startServer(), "the spawn must fail, or this proves nothing")

        assertFalse(manager.isReady(), "a server that is starting is not serving")
    }

    @Test
    fun `a probe after the poll has given up still records readiness`() {
        // Kills: dropping the `_isReady = true` record from probeReadiness, which
        // would leave waitForReady's bounded loop as the only writer again.
        //
        // This is the piece that removes the cliff. A start slower than the poll
        // used to leave the flag false for as long as the process lived, because
        // the only writer lived inside a loop that had already returned -- both
        // call sites then refused a server that was serving. Asking again has to
        // be able to change the answer, or asking again is pointless.
        //
        // Deliberately not preceded by waitForReady: this is the standalone
        // probe, on a manager that has never polled.
        serving(200)
        assertFalse(manager.isReady(), "the fixture must start out not ready")

        assertTrue(manager.probeReadiness(), "a server answering 200 is serving")
        assertTrue(manager.isReady(), "and asking again has to be able to change the answer")
    }

    @Test
    fun `a probe against a server that is not serving leaves the answer alone`() {
        // The other direction, and the reason the record is conditional: a probe
        // that fails must not clear a readiness established earlier, because a
        // single refused connection during a restart is not evidence the server
        // has stopped -- the watchdog owns that transition.
        //
        // The port is held rather than released. Stopping the stub and probing the
        // freed port asserts that nothing else on the machine is listening there,
        // which is not this test's to guarantee: a shared CI runner reused the port
        // between the stop and the probe, the probe got its 200, and the assertion
        // below failed for a reason that had nothing to do with readiness. A socket
        // that accepts and drops the connection is "not serving" in the only sense
        // the probe can observe, and cannot be taken by anyone else.
        val server = StubServer(null)
        try {
            manager.portField = server.port
            manager.readyField = true

            assertFalse(manager.probeReadiness())
            assertTrue(manager.isReady(), "one failed probe must not be treated as a stop")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `the not-serving fixture really is holding its port`() {
        // Control for the test above. Without it, a fixture that failed to bind at
        // all would produce the same refused probe and the same green result, and
        // the case would pass while testing nothing.
        val server = StubServer(null)
        try {
            assertTrue(server.port > 0, "the fixture must have bound a port")
            java.net.Socket().use { probe ->
                probe.connect(java.net.InetSocketAddress("127.0.0.1", server.port), 2000)
                assertTrue(probe.isConnected, "the port must accept connections")
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stopping clears readiness`() {
        // Kills: deleting `_isReady = false` from stopServer(). Without it a
        // stopped server still reports itself ready, and the next activity to
        // bind navigates straight at it.
        manager.readyField = true

        manager.stopServer()

        assertFalse(manager.isReady())
    }

    @Test
    fun `the watchdog clears readiness when the process dies`() {
        // Kills: deleting `_isReady = false` from startWatchdog, or putting it
        // after the isShuttingDown early return so only deliberate stops clear
        // it. The crash is the case that matters -- the process is respawned
        // within seconds and the flag has to be false for that whole window.
        val release = CountDownLatch(1)
        val process = mockk<Process>(relaxed = true) {
            every { isAlive } returns true
            every { waitFor() } answers { release.await(5, TimeUnit.SECONDS); 137 }
        }
        manager.serverProcessField = process
        manager.readyField = true

        val crashed = CountDownLatch(1)
        manager.onServerCrashed = { crashed.countDown() }
        ProcessManager::class.java.getDeclaredMethod("startWatchdog")
            .apply { isAccessible = true }
            .invoke(manager)

        // Positive control: without it the assertion below would also pass on a
        // fixture that was never ready to begin with.
        assertTrue(manager.isReady(), "the fixture must start out ready")

        release.countDown()
        assertTrue(crashed.await(5, TimeUnit.SECONDS), "the watchdog never saw the exit")
        assertFalse(manager.isReady(), "a process that has exited is not serving")
    }
}

/**
 * Adopting a server this instance did not start.
 *
 * The case: `assets/server.js` forks the editor server and forwards SIGTERM, but
 * a SIGKILLed `server.js` — routine here — forwards nothing and `fork()` sets no
 * PDEATHSIG, so the child outlives its parent still holding the port. Measured
 * on an emulator, spawning anyway produces a parent whose own child prints
 * EADDRINUSE and never exits: this class ends up watching a process whose death
 * means nothing while the process serving the user is untracked.
 *
 * Adoption removes the second process and puts the watch on the one that
 * matters. Both halves are pinned here, because either alone is worse than
 * neither: adopting without watching trades a loud failure for a silent one,
 * which is the trade the port refusal was removed for, running the other way.
 */
class AdoptionTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var manager: ProcessManager
    private lateinit var contextMock: Context
    private var stub: StubServer? = null

    /** Written where [Environment.getConnectionTokenPath] is stubbed to look. */
    private val token = "adopt-2f9c-token"

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs

        val tokenFile = File(tempDir, "token").apply { writeText(token) }

        mockkObject(Environment)
        every { Environment.getNodePath(any()) } returns "/bin/echo"
        every { Environment.getServerScript(any()) } returns "server.js"
        every { Environment.buildProcessEnvironment(any(), any()) } returns emptyMap()
        every { Environment.getExtensionsDir(any()) } returns "extensions"
        every { Environment.getUserDataDir(any()) } returns "data"
        every { Environment.getLogsDir(any()) } returns "logs"
        every { Environment.getConnectionTokenPath(any()) } returns tokenFile.path

        contextMock = mockk<Context>(relaxed = true)
        every { contextMock.cacheDir } returns tempDir
        every { contextMock.filesDir } returns tempDir

        manager = ProcessManager(contextMock)
    }

    @AfterEach
    fun tearDown() {
        stub?.stop()
        manager.stopServer()
        unmockkAll()
    }

    /** Points the manager at a loopback server answering [status] on every route. */
    private fun serving(status: Int): StubServer =
        StubServer(status).also { stub = it; manager.portField = it.port }

    @Test
    fun `a server that accepts our token is adopted rather than spawned over`() {
        // 200 to everything, including the tokened `/`, so the ownership probe
        // sees acceptance. The port is genuinely held by the stub, which is what
        // sends startServer down this branch at all.
        serving(200)

        assertTrue(manager.startServer(), "adopting is a successful start")
        assertTrue(manager.isAdopted(), "the server on the port is not ours to claim we spawned")
        assertNull(
            manager.serverProcessField,
            "adoption must not spawn a second server onto a port the first still holds",
        )
    }

    @Test
    fun `a holder that refuses our token is not adopted`() {
        // 403 is the discriminating answer and the only one that means "not ours".
        // A stranger can hold a loopback port on Android; only our own processes
        // can read the token file the probe presents.
        serving(403)

        assertFalse(manager.portHolderAcceptsOurToken(), "403 is a refusal, not an acceptance")
        manager.startServer()
        assertFalse(manager.isAdopted(), "a server that refuses our token is not ours to adopt")
    }

    @Test
    fun `a redirect counts as acceptance`() {
        // The server consumes the token on `/` and redirects while turning it into
        // the vscode-tkn cookie, so pinning 200 alone would call our own server a
        // stranger. The probe judges by what the answer is NOT.
        serving(302)

        assertTrue(manager.portHolderAcceptsOurToken())
    }

    @Test
    fun `an empty token cannot claim ownership of anything`() {
        // Kills a probe that treats a missing token as a pass. Before the server
        // has written one there is nothing to present, and "no answer to the
        // question" must not read as "yes".
        serving(200)
        File(tempDir, "token").writeText("")
        manager.cachedTokenField = null

        assertFalse(manager.portHolderAcceptsOurToken())
    }

    @Test
    fun `an adopted server that stops answering is reported as a crash`() {
        // The half that makes adoption safe. There is no Process behind an adopted
        // server, so nothing reports its death for free; without this the class
        // would report it healthy for as long as it ran.
        serving(200)
        assertTrue(manager.startServer())
        assertTrue(manager.probeReadiness(), "the fixture must start out serving")

        val crashed = CountDownLatch(1)
        manager.onServerCrashed = { crashed.countDown() }

        stub?.stop()

        assertTrue(
            crashed.await(30, TimeUnit.SECONDS),
            "the adopted server went away and nothing noticed",
        )
        assertFalse(manager.isReady(), "a server that stopped answering is not serving")
        assertFalse(manager.isAdopted(), "and it is no longer ours to serve")
    }

    @Test
    fun `a restart does not spawn a second server while one is adopted`() {
        // The start guard's other half. `isRunning()` answers false for an adopted
        // server because there is no Process, so without consulting adoption this
        // would spawn onto a port that is still held.
        serving(200)
        assertTrue(manager.startServer())

        assertFalse(manager.startServer(), "a second start must be refused while one is adopted")
        assertNull(manager.serverProcessField)
    }

    @Test
    fun `stopping says plainly that an adopted server cannot be stopped`() {
        // Not cosmetic. Before adoption this case still arrived, and it was worse:
        // serverProcess referenced a process that never served anything, so the
        // stop destroyed the wrong one and reported success while the real server
        // kept running.
        val warnings = mutableListOf<String>()
        every { Logger.w(any(), any()) } answers { warnings += secondArg<String>() }
        serving(200)
        assertTrue(manager.startServer())

        manager.stopServer()

        assertFalse(manager.isAdopted(), "the stop must at least end our relationship with it")
        assertTrue(
            warnings.any { it.contains("adopted") },
            "a stop that cannot stop anything must say so: $warnings",
        )
    }
}

/**
 * A loopback HTTP server small enough to have no dependencies.
 *
 * `com.sun.net.httpserver` is not on the Android unit-test compile classpath, and
 * the probe under test needs so little — a status line and a framed empty body —
 * that a raw socket says it in fewer lines than working around that would take.
 *
 * It records the request line so a test can assert *which* route was asked for,
 * which is half of what the probe's contract says.
 */
private class StubServer(status: Int?) {

    private val socket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    private val requestLine = AtomicReference<String?>(null)

    @Volatile
    private var running = true

    val port: Int get() = socket.localPort

    /** The request line of the most recent request, or null if there was none. */
    fun lastRequestLine(): String? = requestLine.get()

    init {
        thread(name = "stub-http", isDaemon = true) {
            while (running) {
                try {
                    socket.accept().use { client ->
                        // A null status is a port this holds but does not serve on:
                        // the connection is accepted and dropped without a reply, so
                        // the probe's `responseCode` throws and readiness is refused.
                        // Holding the port is the point -- releasing it and trusting
                        // that nothing else binds it is a race the CI runner lost.
                        if (status == null) return@use
                        val reader = client.getInputStream().bufferedReader()
                        requestLine.set(reader.readLine())
                        // Headers to the blank line, so the client sees a
                        // complete exchange rather than a reset.
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                        }
                        client.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 $status Stub\r\n" +
                                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                            )
                            flush()
                        }
                    }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            socket.close()
        } catch (e: Exception) {
            // Closing an already-closed socket is the normal path out of accept().
        }
    }
}

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
