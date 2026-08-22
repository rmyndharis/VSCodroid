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
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * but only `stopServer()` ever cleared that field: the crash path left the
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
        // directory is here". They agree in every case anyone pictures (the path
        // is absent, or it is the directory we made last time), and part company
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
    fun `a start onto a held port records that it can never serve`() {
        // The other half of the test above. Starting anyway is right, but the
        // process it starts cannot bind: its editor server prints EADDRINUSE and
        // then does not exit, so liveness answers yes for as long as the app runs
        // and every caller that waits on liveness waits forever. The one moment
        // the two cases can be told apart is the spawn, so it is recorded there.
        val holder = StubServer(null)
        try {
            manager.portField = holder.port

            assertTrue(startAndAwaitWatchdog())
            assertTrue(
                manager.spawnedOntoHeldPort(),
                "a spawn onto a port something else holds must be recorded as doomed, " +
                    "or nothing downstream can stop waiting on it",
            )
        } finally {
            holder.stop()
        }
    }

    @Test
    fun `a start onto a free port is not recorded as doomed`() {
        // Control. A flag that answered true unconditionally would satisfy the
        // test above while killing every slow but healthy start after thirty
        // seconds, the failure running the other way, and the worse of the two.
        manager.portField = PortFinder.findAvailablePort()

        assertTrue(startAndAwaitWatchdog())
        assertFalse(
            manager.spawnedOntoHeldPort(),
            "a server that had its port to itself must be allowed to be slow",
        )
    }

    @Test
    fun `allocates a port on the first start`() {
        assertTrue(startAndAwaitWatchdog())
        assertNotEquals(0, manager.port, "the first start must allocate a port")
        assertFalse(
            manager.spawnedOntoHeldPort(),
            "a freshly allocated port is free by construction",
        )
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
    fun `a ceiling set in settings json reaches the command line, clamped`() {
        // The wire for the override arm, and the counterpart of `the derived heap
        // ceiling reaches the command line`. The pure functions above settle what
        // the number should be; nothing there notices if the settings file is never
        // opened, and the derived arm would go on producing a plausible number.
        //
        // The fixture asks for more than the device can hold on purpose, so the
        // number that has to appear is the CLAMPED one. A test that asked for
        // something already legal would pass against a wiring that skipped the
        // clamp, which is the half that carries the safety argument.
        //
        // 3 GiB of visible RAM gives an override maximum of 768 (a quarter is 768,
        // which is also where the floor on that maximum sits), so a request of 4096
        // must arrive as 768. That collides with HEAP_CEILING_MAX_MB, so the sizes
        // are chosen again below to keep the assertion discriminating.
        val totalMb = 6L * 1024
        val am = mockk<ActivityManager>(relaxed = true) {
            every { isLowRamDevice } returns false
            every { getMemoryInfo(any()) } answers {
                firstArg<ActivityManager.MemoryInfo>().totalMem = totalMb * 1024 * 1024
            }
        }
        every { contextMock.getSystemService(ActivityManager::class.java) } returns am

        val settings = File(tempDir, "Machine/settings.json").apply {
            parentFile!!.mkdirs()
            // One key per line, which is what every writer of this file produces:
            // the workbench's settings editor, and FirstRunSetup.insertSetting.
            // The reader is anchored to the start of a line, so a fixture that put
            // the key beside the brace would be rejected for a reason that has
            // nothing to do with the wiring under test.
            writeText("{\n    \"vscodroid.server.heapCeilingMb\": 4096,\n}\n")
        }
        every { Environment.getMachineSettingsPath(any()) } returns settings.path

        // A quarter of 6144 is 1536, so the request is clamped to the absolute cap.
        val expected = heapOverrideMaxMb(totalMb)
        assertEquals(HEAP_OVERRIDE_ABS_MAX_MB, expected, "the fixture must exercise the clamp")
        assertNotEquals(
            heapCeilingMb(totalMb, isLowRam = false), expected,
            "the fixture must not pick the number the derived arm would also produce",
        )
        assertNotEquals(
            4096, expected,
            "the fixture must not pick a number an unclamped wiring would also produce",
        )

        val output = StringBuilder()
        val printed = CountDownLatch(1)
        manager.onServerOutput = { line -> output.append(line).append('\n'); printed.countDown() }

        assertTrue(startAndAwaitWatchdog(), "the fixture server must start")
        assertTrue(printed.await(5, TimeUnit.SECONDS), "the spawned process never printed its arguments")

        assertTrue(
            output.contains("--max-old-space-size=$expected"),
            "the clamped override must reach the command line; got: $output",
        )
        assertTrue(
            manager.heapOverrideInEffect(),
            "a spawn that honoured the user's number must say so, or the kill latch " +
                "charges the wrong party",
        )
    }

    @Test
    fun `exactly one heap flag reaches the command line`() {
        // The Worker shim in patches/0003 matches --max-old-space-size=(\d+) and
        // takes the LAST it sees when re-expressing it as a resource limit, so a
        // second flag would silently decide the Extension Host's budget while the
        // main isolate used the first. Cheap to assert, and invisible otherwise.
        val output = StringBuilder()
        val printed = CountDownLatch(1)
        manager.onServerOutput = { line -> output.append(line).append('\n'); printed.countDown() }

        assertTrue(startAndAwaitWatchdog(), "the fixture server must start")
        assertTrue(printed.await(5, TimeUnit.SECONDS), "the spawned process never printed its arguments")

        assertEquals(
            1,
            Regex("--max-old-space-size=").findAll(output).count(),
            "there must be exactly one heap flag on the command line; got: $output",
        )
    }

    @Test
    fun `a ceiling that has spent its budget does not reach the command line`() {
        // The latch's arithmetic is pinned by HeapOverrideLatchTest and its counting
        // site by HeapLatchCallSiteTest. Neither notices if the START never consults
        // the count, which is the arm that actually protects the user: without it
        // the value is disabled in a preference nobody reads and the server keeps
        // being spawned with it forever.
        val totalMb = 6L * 1024
        val am = mockk<ActivityManager>(relaxed = true) {
            every { isLowRamDevice } returns false
            every { getMemoryInfo(any()) } answers {
                firstArg<ActivityManager.MemoryInfo>().totalMem = totalMb * 1024 * 1024
            }
        }
        every { contextMock.getSystemService(ActivityManager::class.java) } returns am

        val settings = File(tempDir, "Machine/settings.json").apply {
            parentFile!!.mkdirs()
            writeText("{\n    \"vscodroid.server.heapCeilingMb\": 4096,\n}\n")
        }
        every { Environment.getMachineSettingsPath(any()) } returns settings.path

        // The value recorded as seen must be the same 4096, or heapKillsForValue
        // hands back a fresh budget and this proves nothing. That is the whole
        // fixture: a budget spent against THIS value.
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true) {
            every { getInt(PREF_HEAP_VALUE_SEEN, any()) } returns 4096
            every { getInt(PREF_HEAP_KILLS, any()) } returns HEAP_OVERRIDE_KILL_BUDGET
        }
        every { contextMock.getSharedPreferences(HEAP_PREFS_NAME, any()) } returns prefs

        val output = StringBuilder()
        val printed = CountDownLatch(1)
        manager.onServerOutput = { line -> output.append(line).append('\n'); printed.countDown() }

        assertTrue(startAndAwaitWatchdog(), "the fixture server must start")
        assertTrue(printed.await(5, TimeUnit.SECONDS), "the spawned process never printed its arguments")

        assertTrue(
            output.contains("--max-old-space-size=${heapCeilingMb(totalMb, isLowRam = false)}"),
            "a suspended ceiling must fall back to the derived one; got: $output",
        )
        assertFalse(
            output.contains("--max-old-space-size=${heapOverrideMaxMb(totalMb)}"),
            "the suspended value must not reach the command line at all; got: $output",
        )
        assertFalse(
            manager.heapOverrideInEffect(),
            "a value that was not honoured must not be charged for the next kill",
        )
    }

    @Test
    fun `the value honoured and the kills against it are written down`() {
        // The read side of the latch is covered above and by HeapOverrideLatchTest;
        // nothing covered the WRITE, and the pair written here is the latch's whole
        // memory. heapKillsForValue compares the value recorded as seen against the
        // one asked for now, and NodeService counts kills onto the number stored
        // beside it. Delete this one statement and both reads answer 0 for ever: a
        // value that has killed the server ten times is honoured on the eleventh
        // start, the notice telling the user why their ceiling stopped applying
        // never appears, and nothing else in the suite goes red.
        val totalMb = 6L * 1024
        val am = mockk<ActivityManager>(relaxed = true) {
            every { isLowRamDevice } returns false
            every { getMemoryInfo(any()) } answers {
                firstArg<ActivityManager.MemoryInfo>().totalMem = totalMb * 1024 * 1024
            }
        }
        every { contextMock.getSystemService(ActivityManager::class.java) } returns am

        val settings = File(tempDir, "Machine/settings.json").apply {
            parentFile!!.mkdirs()
            writeText("{\n    \"vscodroid.server.heapCeilingMb\": 4096,\n}\n")
        }
        every { Environment.getMachineSettingsPath(any()) } returns settings.path

        // A preference file that remembers, rather than a relaxed mock that
        // discards: what is being asserted is what a later read would find, and a
        // mock that swallows every put would satisfy a `verify` on the put alone.
        val stored = mutableMapOf<String, Int>()
        var commits = 0
        val editor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)
        every { editor.putInt(any(), any()) } answers {
            stored[firstArg()] = secondArg()
            editor
        }
        every { editor.commit() } answers { commits++; true }
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true) {
            every { getInt(any(), any()) } answers { stored[firstArg<String>()] ?: secondArg() }
            every { edit() } returns editor
        }
        every { contextMock.getSharedPreferences(HEAP_PREFS_NAME, any()) } returns prefs

        assertTrue(startAndAwaitWatchdog(), "the fixture server must start")

        assertEquals(
            4096, stored[PREF_HEAP_VALUE_SEEN],
            "the value the user asked for was not recorded, so the next start cannot " +
                "tell a value that has been killing this device from one they changed. " +
                "Stored: $stored",
        )
        assertEquals(
            0, stored[PREF_HEAP_KILLS],
            "the count against this value was not recorded, so every kill charged to " +
                "it lands on a number nothing keeps. Stored: $stored",
        )
        // PortFinder writes to this same preference file and ends with apply(), so
        // counting commits rather than edits is what keeps this about the latch.
        // One commit, because a switch to apply() here is the regression that
        // records the pair and then loses it to the SIGKILL it is recording.
        assertEquals(
            1, commits,
            "the pair must be committed, not applied: what it has to survive is the " +
                "kill of this app's own process, and apply() is what loses that race",
        )
    }

    @Test
    fun `a device that ignores the request is not charged for its kills`() {
        // The attribution bug this closes was in the obvious spelling: setting the
        // flag from "a request was present" rather than "a request was taken". On a
        // low-RAM device the request is not taken, the derived floor is what runs,
        // and a flag set anyway hands the next SIGKILL to a value the device never
        // ran with. Three of those disable a setting the user never got to try, and
        // nothing anywhere would say why.
        //
        // 8 GB so the derived arm reaches the cap and not the floor: the assertion
        // below is that 256 came from the low-RAM flag, and on a small total it
        // would come from the arithmetic too.
        val am = mockk<ActivityManager>(relaxed = true) {
            every { isLowRamDevice } returns true
            every { getMemoryInfo(any()) } answers {
                firstArg<ActivityManager.MemoryInfo>().totalMem = 8L * 1024 * 1024 * 1024
            }
        }
        every { contextMock.getSystemService(ActivityManager::class.java) } returns am

        val settings = File(tempDir, "Machine/settings.json").apply {
            parentFile!!.mkdirs()
            writeText("{\n    \"vscodroid.server.heapCeilingMb\": 1024,\n}\n")
        }
        every { Environment.getMachineSettingsPath(any()) } returns settings.path

        val output = StringBuilder()
        val printed = CountDownLatch(1)
        manager.onServerOutput = { line -> output.append(line).append('\n'); printed.countDown() }

        assertTrue(startAndAwaitWatchdog(), "the fixture server must start")
        assertTrue(printed.await(5, TimeUnit.SECONDS), "the spawned process never printed its arguments")

        assertTrue(
            output.contains("--max-old-space-size=$HEAP_CEILING_MIN_MB"),
            "a low-RAM device must ignore the request; got: $output",
        )
        assertFalse(
            manager.heapOverrideInEffect(),
            "the request was not taken, so its budget must not be spent on this server's kills",
        )
    }

    @Test
    fun `a directory where the settings file belongs is read as no setting`() {
        // The fail-safe direction, through the guard rather than through the catch.
        // This comment used to say a directory was the cheapest way to make
        // readText throw, and it is not: `takeIf { it.isFile }` answers false for a
        // directory and the read never happens, so `asked` is null and
        // requestedHeapCeiling returns before its catch is anywhere near. The catch
        // is exercised by the case below instead.
        //
        // What is left is still worth pinning, and it is the branch a device
        // actually meets: no readable setting means the number the device ran
        // before the key existed, and the start happens at all.
        val am = mockk<ActivityManager>(relaxed = true) {
            every { isLowRamDevice } returns false
            every { getMemoryInfo(any()) } answers {
                firstArg<ActivityManager.MemoryInfo>().totalMem = 3L * 1024 * 1024 * 1024
            }
        }
        every { contextMock.getSystemService(ActivityManager::class.java) } returns am
        val notAFile = File(tempDir, "Machine/settings.json").apply { mkdirs() }
        every { Environment.getMachineSettingsPath(any()) } returns notAFile.path

        val output = StringBuilder()
        val printed = CountDownLatch(1)
        manager.onServerOutput = { line -> output.append(line).append('\n'); printed.countDown() }

        assertTrue(startAndAwaitWatchdog(), "a settings path that is not a file must not stop a start")
        assertTrue(printed.await(5, TimeUnit.SECONDS), "the spawned process never printed its arguments")

        assertTrue(
            output.contains("--max-old-space-size=${heapCeilingMb(3L * 1024, isLowRam = false)}"),
            "the derived ceiling must survive a settings path that is not a file; got: $output",
        )
        assertFalse(
            manager.heapOverrideInEffect(),
            "nothing was honoured, so nothing may be charged for the next kill",
        )
    }

    @Test
    fun `a throw while reading the setting leaves the derived ceiling alone`() {
        // The catch inside requestedHeapCeiling, which nothing entered until this.
        // The case above cannot reach it, and the two fall back to DIFFERENT
        // numbers, which is what makes this worth a case of its own: without the
        // inner catch the throw travels to heapCeilingForDevice's catch, and that
        // one abandons the derivation entirely for the flat HEAP_CEILING_DEFAULT_MB.
        // A 3 GB device would be handed 512 where it had been running 384.
        //
        // The throw comes from the mocked path lookup rather than from a file made
        // unreadable, because file permissions are the property of the machine
        // running the suite and not of the code under test: a run as root reads a
        // mode 000 file happily and the case would pass by never throwing. Where
        // inside the try it is raised does not matter; that the start survives it
        // with the derived number does.
        val am = mockk<ActivityManager>(relaxed = true) {
            every { isLowRamDevice } returns false
            every { getMemoryInfo(any()) } answers {
                firstArg<ActivityManager.MemoryInfo>().totalMem = 3L * 1024 * 1024 * 1024
            }
        }
        every { contextMock.getSystemService(ActivityManager::class.java) } returns am
        every { Environment.getMachineSettingsPath(any()) } throws
            IllegalStateException("the settings path could not be resolved")

        val derived = heapCeilingMb(3L * 1024, isLowRam = false)
        assertNotEquals(
            HEAP_CEILING_DEFAULT_MB, derived,
            "the fixture must not pick a size the outer catch would also produce",
        )

        val output = StringBuilder()
        val printed = CountDownLatch(1)
        manager.onServerOutput = { line -> output.append(line).append('\n'); printed.countDown() }

        assertTrue(startAndAwaitWatchdog(), "a throw while reading the setting must not stop a start")
        assertTrue(printed.await(5, TimeUnit.SECONDS), "the spawned process never printed its arguments")

        assertTrue(
            output.contains("--max-old-space-size=$derived"),
            "a throw while reading the setting must leave the DERIVED ceiling, not " +
                "collapse the device onto the flat default; got: $output",
        )
        assertFalse(
            manager.heapOverrideInEffect(),
            "nothing was honoured, so nothing may be charged for the next kill",
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

    @Test
    fun `the watchdog tells an OOM kill from an ordinary signal`() {
        // 137 is 128 + SIGKILL, and on this platform that is nearly always the
        // low-memory killer or the phantom-process limit: the two causes a
        // reader has to act on, and the two that no other exit code announces.
        //
        // The assertion is on the words, and it has to be. 137 is a member of
        // the 129..192 range the branch below it covers, so deleting the 137
        // line does not remove a log line: it falls through and warns
        // "Server killed by SIGKILL" instead. A test asserting that a warning
        // was logged, or that the exit code reached onServerCrashed, stays green
        // through exactly that deletion and proves nothing.
        manager.serverProcessField = mockk<Process>(relaxed = true) {
            every { waitFor() } returns 137
            every { isAlive } returns false
        }

        val diagnosed = CountDownLatch(1)
        every {
            Logger.w(any(), match<String> { it.contains("OOM or phantom limit") }, any())
        } answers {
            diagnosed.countDown()
        }

        ProcessManager::class.java.getDeclaredMethod("startWatchdog")
            .apply { isAccessible = true }
            .invoke(manager)

        assertTrue(
            diagnosed.await(5, TimeUnit.SECONDS),
            "137 must be reported as memory or the process limit, not as a bare signal"
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
 * invalidated, correct in production, since the server reuses the file rather
 * than regenerating it, and inconvenient in a test that wants to change what the
 * file says after something has already read it.
 */
private var ProcessManager.cachedTokenField: String?
    get() = field("cachedToken").get(this) as String?
    set(value) = field("cachedToken").set(this, value)

/**
 * Reaches `ProcessManager.procDir`, which is private production state.
 *
 * The machines this suite runs on have no `/proc`, so without redirecting it the
 * adoption tests could only ever exercise the branch where the recorded process
 * is missing, passing, while never once adopting anything.
 */
private var ProcessManager.procDirField: File
    get() = field("procDir").get(this) as File
    set(value) = field("procDir").set(this, value)

/**
 * Reaches `ProcessManager.heapOverrideActive`, which is private production state.
 *
 * Set rather than read: the tests that need it are asserting that something CLEARS
 * the flag, and a field that starts false makes those assertions pass against a
 * clear that was deleted. Putting it up first is what stands in for the earlier
 * spawn in the same instance that would have left it there.
 */
private var ProcessManager.heapOverrideActiveField: Boolean
    get() = field("heapOverrideActive").getBoolean(this)
    set(value) = field("heapOverrideActive").setBoolean(this, value)

private fun field(name: String) =
    ProcessManager::class.java.getDeclaredField(name).apply { isAccessible = true }

/**
 * Readiness: whether the server is *serving*, as opposed to whether its process
 * exists.
 *
 * The two were the same question to every caller until they were not.
 * `MainActivity` navigated its WebView the moment `isRunning()` was true, and
 * that is true from the instant the process is spawned, while the editor server
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
        //
        // The port is held rather than freed, for the reason already written out
        // in `a probe against a server that is not serving leaves the answer
        // alone`. Stopping the stub first would make this assert that nothing
        // else on the machine is listening on that port, which is not this
        // test's to guarantee: on a shared runner something else takes the freed
        // port between the stop and the probe, the probe gets its 200, and the
        // assertion fails for a reason that has nothing to do with readiness.
        // Measured on CI 2026-08-16, one failure out of 909 tests.
        //
        // It still tests what it says it does. The probe cannot tell a refused
        // connection from one that is accepted and dropped; both are "not
        // serving" in the only sense it can observe, and only the second cannot
        // be taken by someone else.
        val server = StubServer(null)
        try {
            manager.portField = server.port

            val ready = runBlocking { manager.waitForReady(timeoutMs = 400, pollIntervalMs = 25) }

            assertFalse(ready)
            assertFalse(manager.isReady())
        } finally {
            server.stop()
        }
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
 * a SIGKILLed `server.js` (routine here) forwards nothing and `fork()` sets no
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

    /**
     * The commit the packaged tree records, which is what the real `/version`
     * answers with: the route ends the response with `productService.commit`.
     *
     * Written into a `product.json` fixture below, so a holder of the port has to
     * produce it before adoption will hand it the WebView and the token.
     */
    private val commit = "a5b500951314efd502d07465bd138dfbd714a960"

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
        File(tempDir, "server/vscode-reh").mkdirs()
        File(tempDir, "server/$REH_PRODUCT_FILE").writeText("""{"commit":"$commit"}""")

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

    /**
     * Points the manager at a loopback server answering [status] on every route,
     * and reporting [reports] as the build it is.
     *
     * The default is this app's own commit, so the fixture stands for the case
     * adoption is for: the editor server that survived its bootstrap. A test that
     * wants a stranger passes something else.
     */
    private fun serving(status: Int, reports: String = commit): StubServer =
        StubServer(status, reports).also { stub = it; manager.portField = it.port }

    /**
     * Writes the note `assets/server.js` leaves naming the editor server it
     * forked, and a matching `/proc` entry, then points the manager at both.
     *
     * [entry] is what `/proc/<pid>/cmdline` will say. The default is what the real
     * bootstrap forks; a test passes something else to stand for a pid that has
     * been recycled into an unrelated process.
     */
    private fun recordEditorServer(pid: Int, port: Int, entry: String = "server-main.js") {
        File(tempDir, "server").mkdirs()
        File(tempDir, "server/editor-server.pid")
            .writeText("""{"pid":$pid,"port":$port}""")
        File(tempDir, "proc/$pid").mkdirs()
        // NUL-separated, the way the kernel writes argv.
        File(tempDir, "proc/$pid/cmdline")
            .writeText("/lib/libnode.so /data/server/vscode-reh/out/$entry ")
        manager.procDirField = File(tempDir, "proc")
    }

    /** Holds the port without answering, so any HTTP probe would fail. */
    private fun holdingPortSilently(): StubServer =
        StubServer(null).also { stub = it; manager.portField = it.port }

    @Test
    fun `a live editor server of ours on the port is adopted rather than spawned over`() {
        // Two things have to be true before a start hands the user someone else's
        // process: the note says the holder is an editor server of ours, and the
        // port answers. This fixture supplies both.
        //
        // The ownership half is still decided from the note and never by asking
        // the holder, `the ownership test never sends the connection token to
        // the port holder` pins that at the socket. What the start adds is a
        // liveness question, and the assertions below pin exactly what it may ask:
        // /version, which the server answers before it checks the token, and
        // nothing else.
        val holder = serving(200)
        recordEditorServer(pid = 4242, port = holder.port)

        assertTrue(manager.startServer(), "adopting is a successful start")
        assertTrue(manager.isAdopted(), "the server on the port is not ours to claim we spawned")
        assertNull(
            manager.serverProcessField,
            "adoption must not spawn a second server onto a port the first still holds",
        )
        val asked = holder.lastRequestLine()
        assertEquals(
            "GET /version", asked?.substringBeforeLast(' '),
            "adoption may ask the port whether it serves, on the one route that is " +
                "answered before the token check, and ask nothing else",
        )
        assertFalse(
            asked!!.contains("tkn"),
            "the liveness probe must not carry the connection token: $asked",
        )
    }

    @Test
    fun `adoption does not charge the user's ceiling for a server it never gave`() {
        // An adopted server was spawned by a bootstrap that is gone, and it carries
        // whatever ceiling that bootstrap gave it. Nothing here can learn what that
        // was. Leaving the flag set from an earlier spawn means the next kill of
        // this server spends a life belonging to a value it never ran with, and
        // three of those disable a setting that was never actually tried.
        //
        // The flag is put up first, exactly as a previous spawn in this same
        // ProcessManager instance would leave it. Asserting it without doing that
        // proves nothing: the field starts false and the assertion passes against a
        // clear that was deleted.
        val holder = serving(200)
        recordEditorServer(pid = 4242, port = holder.port)
        manager.heapOverrideActiveField = true

        assertTrue(manager.startServer(), "adopting is a successful start")
        assertTrue(manager.isAdopted(), "the fixture must reach the adoption branch")
        assertFalse(
            manager.heapOverrideInEffect(),
            "an adopted server's ceiling is unknowable, so it must be charged to nobody",
        )
    }

    @Test
    fun `stopping ends the charge as well as the server`() {
        // A crash report can already be on its way to the service when Stop is
        // pressed. Nothing of ours is running with the user's number after this
        // point, so that report must not spend one of its lives.
        manager.heapOverrideActiveField = true
        manager.stopServer()
        assertFalse(manager.heapOverrideInEffect())
    }

    @Test
    fun `a recorded server that is not answering on the port is not adopted`() {
        // The note is written at the fork, so it names a process that was asked to
        // bind the port rather than one that did. A server spawned onto a port
        // something else holds prints EADDRINUSE and then does not exit, so a
        // bootstrap killed by the OOM killer or the phantom-process limit can
        // leave behind a child that is alive, is an editor server, and matches the
        // note perfectly while having never held the port.
        //
        // Adopting it is a session that never answers: the adoption watch calls it
        // lost after two missed probes, the restart reads the same note and adopts
        // the same process, and the budget runs out with the terminal state
        // reached and no real spawn ever attempted. Nothing clears that note, so
        // the next cold start does it again.
        //
        // The holder here accepts connections and answers nothing, which is what
        // the port looks like when the recorded process is not the one on it.
        val holder = holdingPortSilently()
        recordEditorServer(pid = 4242, port = holder.port)
        assertTrue(
            manager.portHeldByOurEditorServer(),
            "the note has to pass the ownership test, or the refusal below proves nothing",
        )

        val exited = CountDownLatch(1)
        manager.onServerCrashed = { exited.countDown() }
        assertTrue(manager.startServer(), "declining to adopt still has to start something")

        // Before the latch, deliberately. An adopted start spawns nothing, so the
        // watchdog never fires and the latch below times out, which would report
        // this as a stuck watchdog and send the reader to the wrong file. The two
        // assertions that name the defect go first.
        assertFalse(
            manager.isAdopted(),
            "a recorded server that is not serving on the port is not ours to serve",
        )
        assertNotNull(
            manager.serverProcessField,
            "declining to adopt has to fall through to a spawn; the alternative is a " +
                "start that neither adopts nor spawns",
        )

        // Awaited for the reason ProcessManagerTest's helper gives: /bin/echo exits
        // at once, and a watchdog thread outliving the test logs through a Logger
        // mock that unmockkAll() has already torn down.
        assertTrue(exited.await(5, TimeUnit.SECONDS), "watchdog never reported the exit")
    }

    @Test
    fun `a holder serving some other build is not adopted`() {
        // The path that was left open. The note proves an editor server of ours is
        // alive; the port proves something is serving there. Neither says they are
        // the same process, and the read that would say so, /proc/net/tcp, is
        // refused to an app outright. A child that lost the race for the port wedges
        // on EADDRINUSE without exiting, so it goes on matching the note perfectly
        // while a foreign process holds the socket.
        //
        // A bare 200 was the whole of the second half, and everything that accepts a
        // connection and answers something satisfies it. Adopting then points the
        // WebView at that holder with the connection token in the URL. Asking which
        // build answered costs the same request and no disclosure.
        val holder = serving(200, reports = "0000000000000000000000000000000000000000")
        recordEditorServer(pid = 4242, port = holder.port)
        manager.killRecordedProcess = { }
        assertTrue(
            manager.portHeldByOurEditorServer(),
            "the note has to pass the ownership test, or the refusal below proves nothing",
        )

        val exited = CountDownLatch(1)
        manager.onServerCrashed = { exited.countDown() }
        assertTrue(manager.startServer(), "declining to adopt still has to start something")

        assertFalse(
            manager.isAdopted(),
            "a holder that is not this build is not ours to hand the token to",
        )
        assertNotNull(
            manager.serverProcessField,
            "declining to adopt has to fall through to a spawn",
        )
        val asked = holder.lastRequestLine()
        assertEquals(
            "GET /version", asked?.substringBeforeLast(' '),
            "the identity question is asked on the one route answered before the token check",
        )
        assertFalse(
            asked!!.contains("tkn"),
            "asking who the holder is must never present the token to it: $asked",
        )
        assertTrue(exited.await(5, TimeUnit.SECONDS), "watchdog never reported the exit")
    }

    @Test
    fun `a holder that answers without naming a build is not adopted`() {
        // 200 and an empty body is what a web server on the port gives for free, and
        // it is exactly what the test accepted before it asked for an identity.
        val holder = serving(200, reports = "")
        recordEditorServer(pid = 4242, port = holder.port)
        manager.killRecordedProcess = { }

        val exited = CountDownLatch(1)
        manager.onServerCrashed = { exited.countDown() }
        assertTrue(manager.startServer())

        assertFalse(manager.isAdopted(), "answering is not the same as being ours")
        assertTrue(exited.await(5, TimeUnit.SECONDS), "watchdog never reported the exit")
    }

    @Test
    fun `a tree that records no commit declines to adopt rather than accepting anyone`() {
        // Fail closed. With nothing to compare against, the identity test would be a
        // bare 200 again, so a build that cannot say what it is does not adopt at
        // all. That costs a spawn; the other direction costs the session.
        File(tempDir, "server/$REH_PRODUCT_FILE").delete()
        val holder = serving(200)
        recordEditorServer(pid = 4242, port = holder.port)
        manager.killRecordedProcess = { }

        val exited = CountDownLatch(1)
        manager.onServerCrashed = { exited.countDown() }
        assertTrue(manager.startServer())

        assertFalse(manager.isAdopted(), "an unidentifiable build must not adopt on a status line")
        assertTrue(exited.await(5, TimeUnit.SECONDS), "watchdog never reported the exit")
    }

    @Test
    fun `the ownership test never sends the connection token to the port holder`() {
        // The defect this replaced: the test built `/?tkn=<token>` and sent it to
        // whoever held the port, before anything about them was known. Binding a
        // loopback port on Android needs no permission, so the one party the test
        // existed to identify was handed the credential first.
        //
        // Asserted at the socket rather than by reading the source: the stub
        // records the request line it received, and there must not be one.
        val holder = serving(200)
        recordEditorServer(pid = 4242, port = holder.port)

        assertTrue(manager.portHeldByOurEditorServer())
        assertNull(
            holder.lastRequestLine(),
            "the ownership test must not contact the port holder at all",
        )
    }

    @Test
    fun `a port held by something we have no note for is not adopted`() {
        // No note: either this app never started a server on this port, or the
        // note was cleared when the last one exited. Both mean the holder is a
        // stranger, and a stranger is spawned over rather than adopted.
        serving(200)

        assertFalse(manager.portHeldByOurEditorServer())
        manager.startServer()
        assertFalse(manager.isAdopted(), "an unrecorded holder is not ours to adopt")
    }

    @Test
    fun `a note written for a different port does not vouch for this one`() {
        // Guards the reason the port is written alongside the pid. A server that
        // is genuinely ours, genuinely alive, and on a different port says nothing
        // about who holds this one.
        val holder = holdingPortSilently()
        recordEditorServer(pid = 4242, port = holder.port + 1)

        assertFalse(manager.portHeldByOurEditorServer())
    }

    @Test
    fun `a recorded process that has exited is not adopted`() {
        val holder = holdingPortSilently()
        recordEditorServer(pid = 4242, port = holder.port)
        File(tempDir, "proc/4242").deleteRecursively()

        assertFalse(manager.portHeldByOurEditorServer(), "a dead pid vouches for nothing")
    }

    @Test
    fun `a recycled pid running something else is not adopted`() {
        // Android reuses pids freely, so "the number is still in /proc" is not the
        // question. What the process IS decides it.
        val holder = holdingPortSilently()
        recordEditorServer(pid = 4242, port = holder.port, entry = "some-other-program")

        assertFalse(manager.portHeldByOurEditorServer())
    }

    @Test
    fun `an adopted server that stops answering is reported as a crash`() {
        // The half that makes adoption safe. There is no Process behind an adopted
        // server, so nothing reports its death for free; without this the class
        // would report it healthy for as long as it ran.
        //
        // Serving, unlike the ownership tests above, because this one is about
        // readiness after adoption rather than about the adoption decision.
        val holder = serving(200)
        recordEditorServer(pid = 4242, port = holder.port)
        assertTrue(manager.startServer())
        assertTrue(manager.probeReadiness(), "the fixture must start out serving")

        val crashed = CountDownLatch(1)
        manager.onServerCrashed = { crashed.countDown() }

        // Stops answering without releasing the port, for the reason
        // StubServer.status documents. Freeing it here was the same dependency
        // `nothing listening is not ready` was fixed for, and it was missed
        // because the sweep matched `x.stop()` and this site reads `stub?.stop()`.
        stub?.status = null

        assertTrue(
            crashed.await(30, TimeUnit.SECONDS),
            "the adopted server went away and nothing noticed",
        )
        assertFalse(manager.isReady(), "a server that stopped answering is not serving")
        assertFalse(manager.isAdopted(), "and it is no longer ours to serve")
    }

    @Test
    fun `adoption says that the surviving server has lost its DNS proxy`() {
        // The proxy runs inside the bootstrap and its address reaches the editor
        // server once, in the environment it is forked with. Adopting means that
        // bootstrap is gone, so the survivor holds the address of a proxy that no
        // longer exists and nothing can change the environment of a running
        // process: the Open VSX gallery, the agent host and every git, npm or
        // curl in a terminal fail to reach the network for the rest of the
        // session, while the workbench on screen looks healthy.
        //
        // The app cannot repair that from here, which is exactly why the line has
        // to exist, it is the only thing connecting the symptom to the cause,
        // and Logger.w is not gated on a debuggable build.
        val warnings = mutableListOf<String>()
        every { Logger.w(any(), any()) } answers { warnings += secondArg<String>() }
        // Serving, because a port that answers nothing is not adopted at all now
        // and the warning belongs to the adoption branch.
        val holder = serving(200)
        recordEditorServer(pid = 4242, port = holder.port)

        assertTrue(manager.startServer())

        assertTrue(
            warnings.any { it.contains("HTTPS_PROXY") },
            "adoption must record that outbound traffic through the proxy is dead: $warnings",
        )
    }

    @Test
    fun `a restart does not spawn a second server while one is adopted`() {
        // The start guard's other half. `isRunning()` answers false for an adopted
        // server because there is no Process, so without consulting adoption this
        // would spawn onto a port that is still held.
        val holder = serving(200)
        recordEditorServer(pid = 4242, port = holder.port)
        assertTrue(manager.startServer())

        assertFalse(manager.startServer(), "a second start must be refused while one is adopted")
        assertNull(manager.serverProcessField)
    }

    @Test
    fun `stopping ends the adopted server rather than leaving it running`() {
        // This used to be "say plainly that it cannot be stopped", which was honest and
        // still cost an idle Node process for the life of the app, holding its heap and
        // one of the 32 slots the phantom-process limit allows. No Process handle exists
        // for a server this instance did not spawn; the note names its pid, which is the
        // way in.
        val killed = mutableListOf<Int>()
        manager.killRecordedProcess = { killed += it }
        val holder = serving(200)
        recordEditorServer(pid = 4242, port = holder.port)
        assertTrue(manager.startServer())

        manager.stopServer()

        assertEquals(listOf(4242), killed, "the stop must end the process the note names")
        assertFalse(manager.isAdopted(), "the stop must also end our relationship with it")
    }

    /**
     * The direction that costs someone else something. A pid is recycled the moment its
     * process exits, and the kernel refuses a kill across uids, so what stays reachable is
     * this app's own processes: a terminal the user is typing in, a language server.
     * Nothing but the cmdline separates those from the server this means to end.
     */
    @Test
    fun `a recycled pid that is no longer an editor server is left alone`() {
        val killed = mutableListOf<Int>()
        manager.killRecordedProcess = { killed += it }
        val holder = serving(200)
        recordEditorServer(pid = 4242, port = holder.port)
        assertTrue(manager.startServer())
        // Recycled between adoption and the stop, into something of ours that is not it.
        File(tempDir, "proc/4242/cmdline").writeText("/lib/libbash.so -i ")

        manager.stopServer()

        assertTrue(killed.isEmpty(), "a pid that is not an editor server must not be signalled")
    }

    @Test
    fun `a pid that has already gone is not signalled`() {
        val killed = mutableListOf<Int>()
        manager.killRecordedProcess = { killed += it }
        val holder = serving(200)
        recordEditorServer(pid = 4242, port = holder.port)
        assertTrue(manager.startServer())
        File(tempDir, "proc/4242/cmdline").delete()

        manager.stopServer()

        assertTrue(killed.isEmpty(), "nothing to signal once the process is gone")
    }

    @Test
    fun `the note is consumed even when nothing is killed`() {
        // Otherwise a pid this declines to kill is reconsidered on every later call, and
        // the note goes on vouching for a process that is not there.
        manager.killRecordedProcess = { }
        val holder = serving(200)
        recordEditorServer(pid = 4242, port = holder.port)
        assertTrue(manager.startServer())
        File(tempDir, "proc/4242/cmdline").writeText("/lib/libbash.so -i ")

        manager.stopServer()

        assertFalse(
            File(tempDir, "server/editor-server.pid").exists(),
            "the note must not survive the attempt",
        )
    }

    /**
     * What the reap does to the spawn that follows it. The flag exists so the
     * service can tell a server that will never bind from one that is merely
     * slow, and it was answered from the state before the reap: a start that
     * had just taken this app's own holder off the port recorded its
     * replacement as born doomed, and a healthy server slower than the
     * readiness budget was killed once for nothing, out of the restart budget.
     *
     * Driven through the port question rather than a socket, because the
     * verdict reads the port and a socket's disappearance is not the same
     * claim on every JVM: closing a listener while another thread sits in
     * accept() lands at different moments on different ones, and a test that
     * races its own fixture proves nothing about the code under it. The
     * question's own semantics, including what TIME_WAIT remnants do to it,
     * are pinned in [PortFinderTest]; what this pins is the verdict's
     * arithmetic: held at the first ask, freed by the reap, therefore slow-
     * allowed rather than doomed.
     */
    @Test
    fun `a start onto a port the reap just freed is not recorded as doomed`() {
        val killed = mutableListOf<Int>()
        val holder = holdingPortSilently()
        recordEditorServer(pid = 7311, port = holder.port)
        manager.killRecordedProcess = { killed += it }
        val asked = intArrayOf(0)
        mockkObject(PortFinder)
        // First ask: the holder is there. Second ask, after the reap: not.
        every { PortFinder.isPortAvailable(holder.port) } answers {
            asked[0]++ > 0
        }

        manager.startServer()

        assertEquals(2, asked[0], "the port is asked once before the reap and once after it")
        assertEquals(listOf(7311), killed, "the setup is the reap case or the flag proves nothing")
        assertFalse(
            manager.spawnedOntoHeldPort(),
            "a port this start freed itself is free as far as the spawn is " +
                "concerned, and the server it spawned must be allowed to be slow"
        )
    }

    /**
     * The other half, and the half that keeps the reap from buying silence: a
     * signalled pid is not the only thing that can hold the port. The recorded
     * server can be a wedged child that never bound it, because the bootstrap
     * writes the note at the fork and a foreign process took the port first,
     * and a reap that ends that child leaves the foreign holder exactly where
     * it was. Treating the signal as the release then records the replacement
     * as healthy while it wedges on EADDRINUSE without exiting, and the
     * liveness-bounded wait that follows never ends: the failure the budget
     * used to report becomes a "still starting" that says nothing, for as long
     * as the app runs. The port question here never changes its answer, which
     * is what a holder the reap cannot reach looks like.
     */
    @Test
    fun `a start onto a port something else still holds stays doomed after a reap`() {
        val killed = mutableListOf<Int>()
        manager.killRecordedProcess = { killed += it }
        val holder = holdingPortSilently()
        recordEditorServer(pid = 7311, port = holder.port)
        mockkObject(PortFinder)
        every { PortFinder.isPortAvailable(holder.port) } returns false

        manager.startServer()

        assertEquals(listOf(7311), killed, "the reap must have run for this to mean anything")
        assertTrue(
            manager.spawnedOntoHeldPort(),
            "a port the reap did not actually free is still held, and the spawn " +
                "onto it must stay diagnosable rather than merely slow"
        )
    }

    /**
     * The other call site. A server of ours that holds the port and answers nothing is
     * refused adoption just above, and leaving it there guarantees the spawn hits
     * EADDRINUSE: that child does not exit, so the launch ends with two processes where
     * the user wanted one, and the survivor outlives every retry.
     */
    @Test
    fun `a server of ours holding the port without serving is ended before spawning`() {
        val killed = mutableListOf<Int>()
        manager.killRecordedProcess = { killed += it }
        val holder = holdingPortSilently()
        recordEditorServer(pid = 7311, port = holder.port)

        manager.startServer()

        assertEquals(
            listOf(7311),
            killed,
            "a recorded server that answers nothing must be ended, not spawned over",
        )
    }

    @Test
    fun `a healthy adopted server is not killed on the way in`() {
        // The reap belongs to the stop and to the unresponsive case. Adoption itself must
        // leave the server serving the user exactly where it is.
        val killed = mutableListOf<Int>()
        manager.killRecordedProcess = { killed += it }
        val holder = serving(200)
        recordEditorServer(pid = 4242, port = holder.port)

        assertTrue(manager.startServer())

        assertTrue(killed.isEmpty(), "adopting a working server must not end it")
    }
}

/**
 * A loopback HTTP server small enough to have no dependencies.
 *
 * `com.sun.net.httpserver` is not on the Android unit-test compile classpath, and
 * the probe under test needs so little (a status line and a framed empty body)
 * that a raw socket says it in fewer lines than working around that would take.
 *
 * It records the request line so a test can assert *which* route was asked for,
 * which is half of what the probe's contract says.
 */
private class StubServer(initialStatus: Int?, initialBody: String = "") {

    /**
     * What this answers with, or null for a port it holds without serving on.
     *
     * Mutable so a test can stop a server answering WITHOUT freeing its port.
     * Freeing it and then requiring that nothing answers there asserts something
     * about the whole machine, which a shared runner does not honour.
     */
    @Volatile
    var status: Int? = initialStatus

    /**
     * The body it answers with, which for `/version` is the commit the holder
     * claims to be.
     *
     * It exists because a status line stopped being the whole answer: adoption
     * compares this against the commit the packaged tree records, so a stub that
     * always framed an empty body could only ever stand for a holder that is not
     * ours.
     */
    @Volatile
    var body: String = initialBody

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
                        val payload = body.toByteArray()
                        client.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 $status Stub\r\n" +
                                    "Content-Length: ${payload.size}\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray()
                            )
                            write(payload)
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
    fun `the low-RAM flag takes the floor whatever the total says`() {
        // The flag as an argument, not as a device. Nothing here reads
        // ActivityManager.isLowRamDevice, so this says nothing about whether the
        // OEM's flag ever reaches the derivation: passing a constant false at the
        // call site leaves it green while every low-RAM phone gets the ceiling its
        // total earns. `a low-RAM device gets the floor on the command line` is
        // what covers that wire.
        assertEquals(HEAP_CEILING_MIN_MB, heapCeilingMb(8L * 1024, isLowRam = true))
    }

    @Test
    fun `an unreadable total falls back rather than silently taking the floor`() {
        // totalMem has been seen reporting 0 on emulators, and 0/8 clamps to the
        // floor, which would look like a considered decision.
        assertEquals(HEAP_CEILING_DEFAULT_MB, heapCeilingMb(0, isLowRam = false))
        assertEquals(HEAP_CEILING_DEFAULT_MB, heapCeilingMb(-1, isLowRam = false))
    }

    @Test
    fun `the derived band binds exactly where the arithmetic says`() {
        // `the band holds at both ends` uses 1 GB and 64 GB, which are far enough
        // inside each clamp that moving either constant by a few hundred MB leaves
        // it green. These are the breakpoints themselves, so a retune of the band
        // has to come here and state the new ones.
        assertEquals(256, heapCeilingMb(2048, isLowRam = false), "the floor binds at 2048")
        assertEquals(257, heapCeilingMb(2056, isLowRam = false), "just above it the eighth wins")
        assertEquals(767, heapCeilingMb(6143, isLowRam = false), "just under the cap the eighth wins")
        assertEquals(768, heapCeilingMb(6144, isLowRam = false), "the cap binds at 6144")
    }
}

/**
 * The override arm: what a user may ask for, and what they are given instead.
 *
 * The clamp is the entire safety argument for exposing this at all, so each bound
 * has a test that fails when only that bound is removed. Read them together with
 * [heapOverrideMaxMb]'s documentation, which is where the reasoning lives.
 */
class HeapOverrideTest {

    @Test
    fun `a request below the floor is raised to it`() {
        // The floor applies to the override in the RAISING direction. Below 256 the
        // editor cannot open a real project, so a request for less costs the user
        // the editor and buys them nothing.
        assertEquals(HEAP_CEILING_MIN_MB, heapCeilingMb(3700, isLowRam = false, requestedMb = 64))
    }

    @Test
    fun `on a small device the fraction is what bounds a request`() {
        // 3700 MiB is a nominal 4 GB phone. A quarter of it is 925, well under the
        // absolute cap, so this is the case the fraction alone decides. Asking for
        // the absolute cap here must not get it.
        assertEquals(925, heapCeilingMb(3700, isLowRam = false, requestedMb = HEAP_OVERRIDE_ABS_MAX_MB))
    }

    @Test
    fun `on a large device the absolute cap is what bounds a request`() {
        // 15500 MiB is a nominal 16 GB tablet, where a quarter is about 3875. The
        // fraction has stopped protecting: three isolates at 3875 is more V8 old
        // space than the device can hold beside the renderer, which is why the
        // absolute bound exists on top of it.
        assertEquals(
            HEAP_OVERRIDE_ABS_MAX_MB,
            heapCeilingMb(15500, isLowRam = false, requestedMb = 4096),
        )
    }

    @Test
    fun `the override ceiling never sits below what the device would get anyway`() {
        // A 2 GB phone computes a quarter of 500, which is below the 768 the derived
        // arm can reach. Without the floor on the override maximum, a user asking
        // for more than the derived value would be handed LESS than it, and the
        // setting would read as having broken something.
        assertEquals(HEAP_CEILING_MAX_MB, heapOverrideMaxMb(2000))
        assertEquals(
            HEAP_CEILING_MAX_MB,
            heapCeilingMb(2000, isLowRam = false, requestedMb = 1200),
        )
    }

    @Test
    fun `a low-RAM device ignores the request entirely`() {
        // The OEM flag is the manufacturer saying totalMem overstates what this
        // device can spare, and a user cannot know better than the OEM about their
        // own hardware. Ordering, not arithmetic: this passes only while the flag is
        // tested ABOVE the override arm.
        assertEquals(
            HEAP_CEILING_MIN_MB,
            heapCeilingMb(2000, isLowRam = true, requestedMb = 1024),
        )
    }

    @Test
    fun `an unknown total ignores the request entirely`() {
        // Also ordering. The override's only protection is a clamp computed from the
        // total, so with no total there is nothing to clamp against and honouring a
        // request there would be honouring it unbounded.
        assertEquals(
            HEAP_CEILING_DEFAULT_MB,
            heapCeilingMb(0, isLowRam = false, requestedMb = 1024),
        )
    }

    @Test
    fun `a request may lower as well as raise`() {
        // This exists to stop a defensive `coerceAtLeast(derived)` being added later
        // as an obvious safety improvement. It would take away the only thing a user
        // on a struggling device can do from here, and nothing else would go red.
        assertEquals(400, heapCeilingMb(15500, isLowRam = false, requestedMb = 400))
    }

    @Test
    fun `no request leaves every existing device on exactly the derived value`() {
        // The backward-compatibility claim, asserted rather than assumed: an install
        // with no key in settings.json must produce a byte-identical command line.
        for (total in listOf(1024L, 2048L, 3700L, 6144L, 7600L, 15500L)) {
            assertEquals(
                heapCeilingMb(total, isLowRam = false),
                heapCeilingMb(total, isLowRam = false, requestedMb = null),
                "a null request changed the answer at $total MiB",
            )
        }
    }
}

/**
 * Reading the requested value out of the user's settings.json.
 *
 * settings.json is JSONC, it belongs to the user, and this is a text search over
 * it rather than a parse. The case that matters most is the commented-out one.
 */
class HeapOverrideReaderTest {

    @Test
    fun `the key is read from a document shaped like the one this app writes`() {
        val doc = """
            {
                "terminal.integrated.defaultProfile.linux": "bash",
                "vscodroid.server.heapCeilingMb": 1024,
                "extensions.verifySignature": false,
            }
        """.trimIndent()
        assertEquals(1024, heapOverrideFromSettings(doc))
    }

    @Test
    fun `a commented-out key is not honoured`() {
        // The negative control this reader exists to survive. Without the (?m)^\s*
        // anchor an example a user left in their file, or one this project could put
        // there itself, is read as a setting, and nothing looks wrong until the
        // device starts dying. 8192 is chosen so a regression is unmistakable in the
        // failure message rather than a plausible number.
        val doc = """
            {
                // "vscodroid.server.heapCeilingMb": 8192,
                "extensions.verifySignature": false,
            }
        """.trimIndent()
        assertNull(heapOverrideFromSettings(doc), "a commented-out example was honoured")
    }

    @Test
    fun `a key that is only mentioned inside another value is not honoured`() {
        // The other half of the anchor: a key name appearing mid-line, here inside a
        // string somebody wrote, is not a setting either. On its own line, so the
        // line-start anchor is not what rejects it and the quoting is.
        val doc = """
            {
                "note": "set \"vscodroid.server.heapCeilingMb\": 4096 to tune it",
            }
        """.trimIndent()
        assertNull(heapOverrideFromSettings(doc))
    }

    @Test
    fun `a document written on one line is a known and accepted miss`() {
        // Stated rather than hidden, because it is the price of the anchor and
        // somebody will eventually meet it. A settings.json collapsed onto one line
        // is not read, and the user silently gets the derived ceiling.
        //
        // Accepted for two reasons. Nothing writes this file that way: the
        // workbench's settings editor and FirstRunSetup.insertSetting both put one
        // key per line. And the failure direction is the safe one, where honouring
        // a commented-out example is not. If this ever has to change, it must
        // change without letting `a commented-out key is not honoured` go green on
        // a comment.
        assertNull(heapOverrideFromSettings("""{"vscodroid.server.heapCeilingMb": 1024}"""))
    }

    @Test
    fun `an absent key reads as absent`() {
        assertNull(heapOverrideFromSettings("""{ "extensions.verifySignature": false }"""))
        assertNull(heapOverrideFromSettings(""))
    }

    @Test
    fun `a value of the wrong type reads as absent rather than as a number`() {
        // A quoted number is a mistake, and the safe reading of a mistake is the
        // derived value. Relaxing `\d+` to something that accepts it would also
        // start accepting the quotes as part of the number.
        //
        // Each on its own line, so a failure here means the type check failed and
        // not that the anchor did the work.
        assertNull(heapOverrideFromSettings("{\n    \"vscodroid.server.heapCeilingMb\": \"1024\",\n}"))
        assertNull(heapOverrideFromSettings("{\n    \"vscodroid.server.heapCeilingMb\": true,\n}"))
        assertNull(heapOverrideFromSettings("{\n    \"vscodroid.server.heapCeilingMb\": null,\n}"))
    }

    @Test
    fun `a fractional value is read as its whole part and then clamped`() {
        // Not a null, and worth pinning rather than leaving to be discovered.
        // `\d+` stops at the point, so 1024.9 reads as 1024 and 10.5 reads as 10,
        // which the floor then raises to 256. Both are safe: the clamp is what
        // stands between any misreading here and the device, which is the reason
        // the reader is allowed to be this simple.
        assertEquals(1024, heapOverrideFromSettings("{\n    \"vscodroid.server.heapCeilingMb\": 1024.9,\n}"))
        assertEquals(
            HEAP_CEILING_MIN_MB,
            heapCeilingMb(3700, isLowRam = false, requestedMb = 10),
        )
    }
}

/**
 * The latch that turns a user's value off when it keeps killing the server.
 *
 * All three predicates are pure so they can be pinned without a Context, which is
 * what lets the boundary cases below be stated as arithmetic rather than as a
 * sequence of fake crashes.
 */
class HeapOverrideLatchTest {

    @Test
    fun `the budget boundary is the pair, not one side of it`() {
        assertFalse(heapOverrideSuspended(HEAP_OVERRIDE_KILL_BUDGET - 1))
        assertTrue(heapOverrideSuspended(HEAP_OVERRIDE_KILL_BUDGET))
        assertTrue(heapOverrideSuspended(HEAP_OVERRIDE_KILL_BUDGET + 1))
    }

    @Test
    fun `only a SIGKILL with the value in effect spends a life`() {
        // 137 is 128 + SIGKILL, which is what a low-memory kill reaches the watchdog
        // as. The other three cases are the ones a looser rule would swallow.
        assertEquals(2, heapKillsAfter(137, overrideInEffect = true, current = 1))
        assertEquals(
            1, heapKillsAfter(137, overrideInEffect = false, current = 1),
            "a derived ceiling must not spend a budget nobody is using",
        )
        assertEquals(
            1, heapKillsAfter(134, overrideInEffect = true, current = 1),
            "134 is V8's own heap-limit abort: the ceiling working, not failing",
        )
        assertEquals(
            1, heapKillsAfter(ADOPTED_SERVER_LOST, overrideInEffect = true, current = 1),
            "an adopted server never ran with this value",
        )
    }

    @Test
    fun `changing the value hands back a full budget`() {
        // Without this the count is permanent, and a value disabled after three
        // kills could never be re-enabled by lowering it. The only way out would be
        // clearing app data, which destroys filesDir and with it the user's
        // projects, toolchains and extensions.
        assertEquals(3, heapKillsForValue(storedValue = 1024, storedKills = 3, currentValue = 1024))
        assertEquals(0, heapKillsForValue(storedValue = 1024, storedKills = 3, currentValue = 900))
        assertEquals(0, heapKillsForValue(storedValue = 0, storedKills = 3, currentValue = 1024))
    }
}
