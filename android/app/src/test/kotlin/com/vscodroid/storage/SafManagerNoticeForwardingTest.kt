package com.vscodroid.storage

import android.content.Context
import android.os.SystemClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What [SafStorageManager] passes on of the notices its engine raises, judged by calling the engine's seam the way a
 * delete, an open or a replay does and watching what reaches the screen's callback.
 *
 * The manager keeps its [SafSyncEngine] private and offers no accessor, so the engine is read off the field, as
 * [SafManagerEngineWiringTest] reads it. A relaxed [Context] is all construction needs here: nothing below reaches
 * the shared preferences or the provider.
 */
class SafManagerNoticeForwardingTest {

    private lateinit var manager: SafStorageManager
    private lateinit var engine: SafSyncEngine

    /** What `SystemClock.elapsedRealtime()` answers, which the manager's throttle reads. */
    private var now = 1_700_000_000_000L

    @BeforeEach
    fun setUpManager() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { now }
        manager = SafStorageManager(mockk<Context>(relaxed = true))
        engine = SafStorageManager::class.java.getDeclaredField("syncEngine")
            .apply { isAccessible = true }
            .get(manager) as SafSyncEngine
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * A delete that waited out another write reaches the screen once per burst, and shares its burst with a refused
     * delete, whose sentence also ends with the file still in the device folder. Unforwarded, the file came back at
     * the next open with nothing said; unthrottled, an `rm -r` that met a busy provider raised a notice per entry.
     */
    @Test
    fun `a busy delete is told once per burst, shared with a refused delete`() {
        val told = mutableListOf<String>()
        manager.onDeleteBusy { told += "busy:${it.name}" }
        manager.onDeleteRefused { told += "refused:${it.name}" }

        engine.onDeleteBusy(File("a.txt"))
        engine.onDeleteBusy(File("b.txt"))
        engine.onDeleteRefused(File("c.txt"))
        now += SafStorageManager.FAILURE_NOTICE_INTERVAL_MS
        engine.onDeleteBusy(File("d.txt"))

        assertEquals(listOf("busy:a.txt", "busy:d.txt"), told)
    }

    /** Once per open or replay, with the whole count, so nothing throttles it. */
    @Test
    fun `every open that keeps files inside the app is told`() {
        val told = mutableListOf<Triple<String, Int, String>>()
        manager.onFilesKeptInApp { folder, count, first -> told += Triple(folder.name, count, first) }

        engine.onFilesKeptInApp(File("Docs"), 2, "notes.md")
        engine.onFilesKeptInApp(File("Docs"), 1, "other.md")

        assertEquals(listOf(Triple("Docs", 2, "notes.md"), Triple("Docs", 1, "other.md")), told)
    }

    /** At most once per folder open, so nothing throttles it either. */
    @Test
    fun `every open whose changes were not all sent is told`() {
        val told = mutableListOf<String>()
        manager.onOpenChangesUnsent { folder, _ -> told += folder.name }

        engine.onOpenChangesUnsent(File("Docs"), false)
        engine.onOpenChangesUnsent(File("Photos"), false)

        assertEquals(listOf("Docs", "Photos"), told)
    }

    /** Each names its own file, so nothing throttles it: one folded into another's burst is a save nobody is told about. */
    @Test
    fun `every save that could not be kept whole is told`() {
        val told = mutableListOf<String>()
        manager.onHeldSaveIncomplete { told += it.name }

        engine.onHeldSaveIncomplete(File("notes.md"))
        engine.onHeldSaveIncomplete(File("todo.md"))

        assertEquals(listOf("notes.md", "todo.md"), told)
    }

    /**
     * The same failure for the folder whose watcher a failed switch put back reaches the screen as that, so its
     * sentence can say the changes were made while another folder was opening: the folder it names never opened.
     */
    @Test
    fun `a failed switch whose changes were not all sent is told as the switch's`() {
        val told = mutableListOf<Pair<String, Boolean>>()
        manager.onOpenChangesUnsent { folder, afterSwitch -> told += folder.name to afterSwitch }

        engine.onOpenChangesUnsent(File("Docs"), true)
        engine.onOpenChangesUnsent(File("Docs"), false)

        assertEquals(listOf("Docs" to true, "Docs" to false), told)
    }
}
