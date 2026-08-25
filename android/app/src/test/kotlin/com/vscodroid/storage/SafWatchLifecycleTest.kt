package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/**
 * When this engine may be started, and when it stops being startable at all.
 *
 * The Activity has four call sites and serialises three of them behind one mutex.
 * The fourth is `onDestroy`, deliberately on a detached thread outside that mutex,
 * because the stop it makes blocks for up to `DRAIN_GRACE_MS` and the teardown gains
 * nothing by waiting. What that hands the engine is a call that can arrive in the
 * middle of a start already running on `Dispatchers.IO`: inside the inner stop's own
 * drain, or inside the mirror walk. Cancelling the scope does not reach it, because
 * nothing in `startWatching` suspends.
 *
 * With the two methods sharing no monitor, whichever of them wrote `isWatching` last
 * decided the outcome, and the start winning left observers registered, a
 * `saf-writeback` thread polling and a live engine that no code path could reach
 * again: the replacement Activity builds its own [SafStorageManager], and its stop
 * goes to a different engine. So the lifecycle is serialised here, where all four
 * calls pass, and a teardown says so in a way a late start cannot undo.
 *
 * The mirror these tests name does not exist, which is what keeps them on the JVM:
 * `watchableDirectories` returns nothing for a path that is not a directory, so no
 * [android.os.FileObserver] is built, and building one runs a static initializer that
 * reaches native code. Everything decided here -- the refusal, the session hand-over,
 * the worker -- is decided before any observer would exist.
 */
class SafWatchLifecycleTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var parent: File

    private lateinit var engine: SafSyncEngine

    /** A mirror path with no directory at it. See the class comment. */
    private val mirror: File get() = File(parent, "folder-that-was-never-synced")

    private val treeUri: Uri = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        val resolver = mockk<ContentResolver>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
    }

    @AfterEach
    fun tearDown() {
        // However the assertions ended, no test may leave a write-back thread polling.
        engine.shutdown()
        unmockkAll()
    }

    @Test
    fun `a start that outlived its owner is refused rather than run late`() {
        engine.shutdown()
        val closed = engine.session

        engine.startWatching(mirror, treeUri)

        assertSame(
            closed, engine.session,
            "a start after the shutdown installed a session on an engine whose owner is " +
                "gone: nothing can stop what it starts, because the next Activity's " +
                "manager holds a different engine",
        )
        assertNull(
            engine.session.worker,
            "a start after the shutdown left a saf-writeback thread polling for the rest " +
                "of the process",
        )
    }

    @Test
    fun `the shutdown ends the drain the way a folder switch does`() {
        // Control on the other half: refusing later starts is worth nothing if the
        // watcher this call is closing keeps running.
        engine.startWatching(mirror, treeUri)
        val worker = engine.session.worker

        engine.shutdown()

        assertTrue(worker != null) { "setup failed: the start never began a write-back" }
        assertFalse(worker!!.isAlive, "the shutdown left the drain polling")
    }

    @Test
    fun `a folder switch still starts the next watcher from inside the stop it opens with`() {
        // Two things at once, and both are load-bearing. The refusal must apply to a
        // shutdown only, or every folder switch after the first leaves the folder on
        // screen unwatched. And `startWatching` calls `stopWatching` while holding the
        // lifecycle lock, so this deadlocks the moment that monitor stops being
        // reentrant -- which is why the timeout is here rather than a plain call.
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            engine.startWatching(mirror, treeUri)
            val first = engine.session

            engine.startWatching(mirror, treeUri)

            assertNotSame(first, engine.session, "the next folder was handed the previous session")
            assertFalse(
                first.running,
                "the previous folder's write-back was left running by the start that replaced it",
            )
            assertTrue(
                engine.session.worker?.isAlive == true,
                "the folder opened next has no drain, so its saves reach no device",
            )
        }
    }
}

/**
 * That the teardown path still reaches the terminal stop, and the two lifecycle
 * methods still exclude each other.
 *
 * Source reading, for the reason `ServerReadinessCallSiteTest` gives: what regresses
 * here is a call going to the wrong one of two near-identical methods, or a monitor
 * being dropped, neither of which changes a value a JVM test can observe. The
 * behaviour above pins what the engine does once the calls are wired this way; this
 * pins the wiring.
 */
class SafWatchLifecycleWiringTest {

    private val engineFile = File("src/main/kotlin/com/vscodroid/storage/SafSyncEngine.kt")
    private val managerFile = File("src/main/kotlin/com/vscodroid/storage/SafStorageManager.kt")

    /** The lines of a declaration's body, comments dropped, by brace matching. */
    private fun body(file: File, declaration: String): List<String> {
        check(file.isFile) {
            "${file.absolutePath} not found; this test would otherwise pass by looking " +
                "at nothing"
        }
        val source = file.readText()
        val start = source.indexOf(declaration)
        check(start >= 0) {
            "`$declaration` is gone from ${file.name}, so this test is measuring nothing. " +
                "If it moved or was renamed, point this at the new site rather than " +
                "deleting it."
        }
        val open = source.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < source.length) {
            if (source[i] == '{') depth += 1
            if (source[i] == '}') {
                depth -= 1
                if (depth == 0) {
                    return source.substring(open + 1, i).lines().map { it.trim() }.filterNot {
                        it.isEmpty() || it.startsWith("//") || it.startsWith("*") ||
                            it.startsWith("/*")
                    }
                }
            }
            i += 1
        }
        throw AssertionError("Could not find the end of `$declaration` in ${file.name}")
    }

    @Test
    fun `the lifecycle methods hold one monitor for the whole of what they do`() {
        for (declaration in listOf(
            "fun startWatching(mirrorDir: File, safUri: Uri) {",
            "fun stopWatching() {",
            "fun shutdown() {",
        )) {
            val first = body(engineFile, declaration).first()
            assertTrue(first == "synchronized(lifecycleLock) {") {
                "`$declaration` no longer holds the lifecycle lock across its whole body " +
                    "(it opens with `$first`). Each of these takes several steps to say " +
                    "whether the engine is live, and run against each other the last " +
                    "writer wins: a stop finishing inside a start's drain leaves a " +
                    "watcher and a write-back thread on an engine whose owner is gone."
            }
        }
    }

    @Test
    fun `the manager's shutdown reaches the engine's, not its ordinary stop`() {
        val delegation = body(managerFile, "fun shutdownFileWatcher() {")

        assertTrue(delegation.any { it.contains("syncEngine.shutdown()") }) {
            "shutdownFileWatcher no longer shuts the engine down, so `onDestroy` makes a " +
                "stop that a start already inside the engine simply overtakes. Found: " +
                delegation
        }
    }
}
