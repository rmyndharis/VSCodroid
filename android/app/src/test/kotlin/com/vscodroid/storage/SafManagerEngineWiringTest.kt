package com.vscodroid.storage

import android.content.SharedPreferences
import android.content.UriPermission
import android.net.Uri
import android.os.FileObserver
import android.os.SystemClock
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What [SafStorageManager] asks of its engine, judged by what the engine then does.
 *
 * The manager keeps its [SafSyncEngine] private and offers no accessor, so the engine is
 * read off the field. That is what lets these cases watch the calls land: which method a
 * call reaches and with which argument changes what the engine does next, where a reading
 * of the manager's source can say only that the call is spelt there. The engine's watches
 * are the stand-in every JVM test uses ([SafSyncEngine.directoryWatchFactory]), since no
 * `FileObserver` can be built off a device.
 */
class SafManagerEngineWiringTest : SafInitialSyncTestBase() {

    private lateinit var manager: SafStorageManager
    private lateinit var folder: Uri
    private lateinit var engine: SafSyncEngine

    /** Every watch the engine placed, the open's and the watcher's alike, in the order it placed them. */
    private val watches = CopyOnWriteArrayList<FakeWatch>()

    /** The names of the documents made on the device, written from the write-back thread. */
    private val madeOnDevice = CopyOnWriteArrayList<String>()

    /**
     * What `SystemClock.elapsedRealtime()` answers: the write-back thread reads it on every idle
     * turn, and an unanswered read would end that thread under the case that watches it.
     */
    @Volatile private var now = 3_000L

    private class FakeWatch(val dir: File, val deliver: (Int, String?) -> Unit) : OpenWatch {
        @Volatile var running = false
        override fun start() { running = true }
        override fun stop() { running = false }
    }

    @BeforeEach
    fun setUpManager() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { now }
        every { context.applicationContext } returns context
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "[]"
        every { context.getSharedPreferences(any(), any()) } returns prefs
        folder = named("content://tree/primary%3AWired")
        val grant = mockk<UriPermission> {
            every { uri } returns folder
            every { isReadPermission } returns true
            every { isWritePermission } returns true
        }
        every { resolver.persistedUriPermissions } returns listOf(grant)
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            madeOnDevice += arg<String>(3)
            named("doc-uri:doc:${arg<String>(3)}")
        }
        deviceTree(mapOf("root" to listOf(Child("notes.md", contents = "DEVICE"))))

        manager = SafStorageManager(context)
        mirror = manager.getMirrorDir(folder)
        engine = SafStorageManager::class.java.getDeclaredField("syncEngine")
            .apply { isAccessible = true }
            .get(manager) as SafSyncEngine
        engine.directoryWatchFactory = { dir, deliver -> FakeWatch(dir, deliver).also { watches += it } }
    }

    /** However a case ended, no write-back thread is left polling. */
    @AfterEach
    fun shutDownManager() = manager.shutdownFileWatcher()

    private fun awaitTrue(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /**
     * What a person changes while a folder opens is recorded only where it is asked for, by the
     * open the editor waits on, and sent only by the watcher that starts after it. Asked for
     * nowhere, the deletion is fetched back and the late save waits for the next open.
     *
     * The file is made once the open has read the folder and before the watcher starts, so the
     * open's record is the only thing that can carry it to the device: the watcher's own watch
     * hears nothing of it.
     */
    @Test
    fun `the folder open records what changes while it runs, and the watcher sends it`() {
        runBlocking { manager.syncToLocal(folder) }
        assertEquals("DEVICE", File(mirror, "notes.md").readText(), "setup failed: the open did not fill the copy")
        val openWatch = watches.singleOrNull { it.dir == mirror && it.running }
        assertTrue(openWatch != null) {
            "the folder open did not watch the folder while it ran, so what the person changed then " +
                "is lost: a deletion is fetched back, and a save waits for the next open"
        }

        File(mirror, "late.md").writeText("made while the folder opened")
        openWatch!!.deliver(FileObserver.CREATE, "late.md")
        manager.startFileWatcher(mirror, folder)

        assertTrue(awaitTrue { "late.md" in madeOnDevice }) {
            "the watcher did not send what the open recorded, so a file made while the folder opened " +
                "stays inside the app. Made on the device: $madeOnDevice"
        }
    }

    /**
     * The manager's teardown has to reach the engine's terminal stop and not the one a folder
     * switch makes. This manager belongs to one Activity and the next builds its own, so a start
     * already inside this engine when the teardown runs leaves a watcher and a write-back thread
     * nothing can stop again, unless the engine refuses every start after it.
     */
    @Test
    fun `the manager's shutdown refuses a later start, which a folder switch's stop does not`() {
        manager.stopFileWatcher()
        manager.startFileWatcher(mirror, folder)
        assertTrue(engine.session.worker?.isAlive == true) {
            "setup failed: a start after an ordinary stop began no write-back, so the refusal below proves nothing"
        }

        manager.shutdownFileWatcher()
        manager.startFileWatcher(mirror, folder)

        assertNull(engine.session.worker) {
            "a start after the manager's shutdown left a write-back thread on an engine whose owner is " +
                "gone, so the teardown reached the ordinary stop that a start simply overtakes"
        }
    }

    /**
     * A failed write-back is told through the manager's throttle, which reads the clock that
     * cannot move backwards. A wall clock set back by more than the interval would leave every
     * later difference below it, and a folder refusing every save would say nothing until the
     * clock caught up; told beside the claim rather than through it, a provider refusing
     * everything buries the editor in notices.
     *
     * The clock ([now]) starts three seconds after boot, inside one interval of zero, so the first
     * failure also shows that nothing announced yet is not mistaken for an announcement at boot.
     */
    @Test
    fun `a write-back failure is told through the throttle, on the clock that cannot move backwards`() {
        val told = mutableListOf<String>()
        manager.onWriteBackFailed { told += it.name }

        engine.onWriteBackFailed(File("first.txt"))
        now += SafStorageManager.FAILURE_NOTICE_INTERVAL_MS - 1
        engine.onWriteBackFailed(File("inside.txt"))
        now += 1
        engine.onWriteBackFailed(File("after.txt"))

        assertEquals(
            listOf("first.txt", "after.txt"), told,
            "the first failure, then none inside the interval, then the next one after it: " +
                "`inside.txt` told means the throttle is not consulted or its stamp never moves, and " +
                "`after.txt` missing means the reading is not the monotonic clock",
        )
    }
}
