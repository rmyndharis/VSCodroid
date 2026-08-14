package com.vscodroid.storage

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * That the engine's watch registration is wired to the decisions that were pinned in the
 * JVM tests, rather than merely containing them.
 *
 * `SafWatchCoverageTest` pins `watchableDirectories`, and `SafWriteBackFilterTest` pins
 * `shouldWriteBack`, but neither can reach the code that calls them: constructing a
 * `DirectoryObserver` runs `FileObserver`'s static initializer, which starts a thread
 * whose constructor calls native code, and a plain JVM test cannot load the class at all.
 * The gap that leaves is not academic. Replacing the walk in `watchTree` with
 * `listOf(dir)` restores the exact regression this work fixed, and every JVM test stays
 * green; so does deleting the line that starts the observers, and so does deleting the
 * `shouldWriteBack` check from `onEvent`.
 *
 * Nothing here needs SAF. `watchTree` only ever looks at directories, and `onEvent`
 * queues a job whether or not the document behind it resolves — so a tree URI pointing
 * at an authority that does not exist is enough, and the resolution simply misses.
 *
 * The write-back thread is deliberately not started: the queue is the observable, and a
 * running drain would empty it under the assertions.
 */
@RunWith(AndroidJUnit4::class)
class SafWatchWiringTest {

    private lateinit var mirror: File
    private lateinit var engine: SafSyncEngine

    /**
     * A structurally valid tree URI for an authority that does not exist. Structurally
     * valid matters: `getTreeDocumentId` throws on anything else, and that would come out
     * of `onEvent` as a swallowed error rather than a queued job.
     */
    private val treeUri: Uri =
        DocumentsContract.buildTreeDocumentUri("com.vscodroid.test.absent", "root")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        mirror = File(context.cacheDir, "watch-wiring").apply {
            deleteRecursively()
            mkdirs()
        }
        File(mirror, "src/deeper").mkdirs()
        File(mirror, ".vscode").mkdirs()
        File(mirror, "node_modules/pkg").mkdirs()

        engine = SafSyncEngine(context)
        // The observers drop every event unless the engine considers itself watching, and
        // startWatching() would also start the drain that empties what is asserted below.
        SafSyncEngine::class.java.getDeclaredField("isWatching")
            .apply { isAccessible = true }
            .setBoolean(engine, true)
        SafSyncEngine::class.java
            .getDeclaredMethod("watchTree", File::class.java, File::class.java, Uri::class.java)
            .apply { isAccessible = true }
            .invoke(engine, mirror, mirror, treeUri)
    }

    @After
    fun tearDown() {
        engine.stopWatching()
        mirror.deleteRecursively()
    }

    /**
     * The queued jobs, read as an untyped collection.
     *
     * `SyncJob` is `internal`, and whether that reaches an instrumented source set is a
     * question this file does not need to answer: a data class prints its fields, and the
     * local path is one of them.
     */
    private fun queued(): List<String> {
        val queue = SafSyncEngine::class.java.getDeclaredField("writeBackQueue")
            .apply { isAccessible = true }
            .get(engine) as Collection<*>
        return queue.map { it.toString() }
    }

    /**
     * Read under the engine's own monitor, not the map's. The observer thread mutates
     * this map while these tests poll it, and an unsynchronized read races a
     * `ConcurrentModificationException` — a flaky failure that would look like the defect
     * being tested.
     */
    private fun watchedDirectoryNames(): List<String> {
        val type = SafSyncEngine::class.java
        val lock = type.getDeclaredField("watchersLock").apply { isAccessible = true }.get(engine)!!
        val watchers = type.getDeclaredField("watchers")
            .apply { isAccessible = true }
            .get(engine) as Map<*, *>
        return synchronized(lock) { watchers.keys.map { (it as File).name } }
    }

    /** Whether any queued job names [fragment] within [timeoutMs]. */
    private fun queues(fragment: String, timeoutMs: Long): Boolean =
        awaitTrue(timeoutMs) { queued().any { job -> job.contains(fragment) } }

    private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    @Test
    fun aSaveTwoDirectoriesDown_isQueuedForWriteBack() {
        // The regression, end to end. With one watch on the mirror root this file is
        // invisible, and the engine shipped that way: the local copy updated and the
        // device copy never moved.
        File(mirror, "src/deeper/nested.txt").writeText("saved in the editor")

        assertTrue(
            "a save two directories down produced no write-back job, which is the whole defect",
            queues("nested.txt", ARRIVES_MS)
        )
    }

    @Test
    fun aWorkspaceSettingsFile_isQueuedForWriteBack() {
        // `.vscode` is deliberately absent from SKIP_DIRECTORIES, so it is mirrored in.
        // The write-back filter used to drop anything whose path began with a dot, which
        // meant settings edited in the editor never reached the device.
        File(mirror, ".vscode/settings.json").writeText("""{"editor.fontSize":14}""")

        assertTrue(
            "a dotfile directory that the walk mirrors in was not written back out",
            queues("settings.json", ARRIVES_MS)
        )
    }

    @Test
    fun aScratchFile_isNotQueued_whileAnOrdinaryOneBesideItIs() {
        // Both land in the same watched directory, so the control and the claim share an
        // observer: an empty queue is also what a broken fixture produces.
        File(mirror, "real.txt").writeText("keep me")
        File(mirror, "scratch.tmp").writeText("about to be renamed away")

        assertTrue(
            "the ordinary file produced no job, so the absence below proves nothing",
            queues("real.txt", ARRIVES_MS)
        )
        assertFalse(
            "a scratch file the writer is about to rename away was queued for upload",
            queues("scratch.tmp", ABSENT_MS)
        )
    }

    @Test
    fun deletingAWatchedDirectory_releasesItsWatch() {
        assertTrue(
            "the nested directory was never watched, so its release cannot be observed",
            "deeper" in watchedDirectoryNames()
        )

        File(mirror, "src/deeper").deleteRecursively()

        assertTrue(
            "the watch on a deleted directory was never released; observers and kernel " +
                "descriptors accumulate toward the cap for the life of the folder",
            awaitTrue(ARRIVES_MS) { "deeper" !in watchedDirectoryNames() }
        )
    }

    @Test
    fun aSkippedDirectory_isNeverWatched() {
        // The cap survives only because the directories that generate most of the count
        // are excluded. One node_modules is thousands of directories on its own.
        val watched = watchedDirectoryNames()

        assertTrue("the fixture registered no watches at all", watched.contains("src"))
        assertFalse("node_modules was watched", watched.contains("node_modules"))
        assertFalse("a directory inside node_modules was watched", watched.contains("pkg"))
    }

    private companion object {
        private const val ARRIVES_MS = 5_000L
        private const val ABSENT_MS = 1_500L
        private const val POLL_MS = 25L
    }
}
