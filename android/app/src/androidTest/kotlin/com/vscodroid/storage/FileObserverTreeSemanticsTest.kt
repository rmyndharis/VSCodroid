package com.vscodroid.storage

import android.os.FileObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The platform behaviour [SafSyncEngine]'s write-back is built on, measured instead of
 * reasoned about.
 *
 * The engine watched the mirror with a single [FileObserver] over its root and a comment
 * claiming that covered the tree. It does not, and the consequence was silent: a file
 * saved one directory down updated the local copy and the device copy never moved. The
 * fix puts one observer on every directory, and three claims hold it up. Two of them
 * were read out of `FileObserver.java` in the SDK sources; the third comes from inotify's
 * ABI, whose Android side is native code that is not shipped for reading at all. Every
 * one of them is measurable here in milliseconds, so none of them needs to stay an
 * argument.
 *
 * ## What this needs
 *
 * A temporary directory, and nothing else — no server, no first-run extraction, no SAF
 * grant, no picker. That is deliberate. `ServerHealthTest` passes its three cases on a
 * clean install by skipping all of them, because the state it needs is produced by a
 * class that sorts after it; this file has no such state to be missing, so there is
 * nothing here that a skip could hide. There is no `assumeTrue` below, and there must
 * not be one: an assertion that cannot fail is worse than an absent one.
 *
 * Each claim about an event *not* arriving is preceded by a control on the same observer
 * and the same queue. An empty queue is also what a broken fixture produces, and without
 * the control these tests would pass just as well against an observer that never worked.
 */
@RunWith(AndroidJUnit4::class)
class FileObserverTreeSemanticsTest {

    private data class Event(val mask: Int, val name: String?)

    private lateinit var root: File
    private val observers = mutableListOf<FileObserver>()

    @Before
    fun setUp() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        root = File(cache, "fileobserver-semantics").apply {
            deleteRecursively()
            mkdirs()
        }
        assertTrue("the fixture directory could not be created", root.isDirectory)
    }

    @After
    fun tearDown() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        root.deleteRecursively()
    }

    /**
     * Starts an observer on [dir] and returns the queue its events land in.
     *
     * The observer is held in [observers] for the whole test. That is not tidiness:
     * FileObserver's shared ObserverThread keeps only a WeakReference to it, so an
     * observer nothing else references stops delivering the moment it is collected —
     * which is why the engine keeps its own map of them rather than starting and
     * forgetting them.
     */
    private fun watch(dir: File): LinkedBlockingQueue<Event> {
        val seen = LinkedBlockingQueue<Event>()
        val observer = object : FileObserver(dir, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                seen.offer(Event(event, path))
            }
        }
        observers += observer
        observer.startWatching()
        return seen
    }

    /** The first event reported for [name] within [timeoutMs], or null if none is. */
    private fun LinkedBlockingQueue<Event>.firstFor(name: String, timeoutMs: Long): Event? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val event = poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (event.name == name) return event
        }
    }

    @Test
    fun rootWatch_doesNotSee_aFileCreatedInASubdirectory() {
        // The defect itself. `src` exists before the watch starts, so nothing here
        // depends on a creation event for the directory.
        val nested = File(root, "src").apply { mkdirs() }
        val seen = watch(root)

        File(root, "top.txt").writeText("beside the watched directory")
        assertNotNull(
            "the observer reported nothing even for a file in the directory it watches, " +
                "so the absence asserted below would prove nothing",
            seen.firstFor("top.txt", ARRIVES_MS)
        )

        File(nested, "nested.txt").writeText("one directory down")
        assertNull(
            "a single watch on the root reported a change one directory down, which would " +
                "make the per-directory registration unnecessary. The engine shipped for " +
                "months not syncing nested saves, and this absence is why",
            seen.firstFor("nested.txt", ABSENT_MS)
        )
    }

    @Test
    fun subdirectoryWatch_seesTheSameChange_thatTheRootWatchMisses() {
        // The counterpart of the test above: same file, same path, same event, and the
        // only difference is which directory carries the watch. Together the two are the
        // measurement the fix rests on.
        val nested = File(root, "src").apply { mkdirs() }
        val seen = watch(nested)

        File(nested, "nested.txt").writeText("one directory down")

        assertNotNull(
            "a watch registered on the subdirectory did not report a file created in it, " +
                "which would leave the engine no way to see nested saves at all",
            seen.firstFor("nested.txt", ARRIVES_MS)
        )
    }

    @Test
    fun reportedPath_isTheEntryName_notAPathFromAnyRoot() {
        // DirectoryObserver resolves against the directory it watches, not the mirror
        // root, and that is only correct if this holds. If Android reported a path
        // relative to some root instead, every nested write-back would resolve to the
        // wrong document -- silently, since the SAF lookup would simply miss.
        val nested = File(root, "src/deeper").apply { mkdirs() }
        val seen = watch(nested)

        File(nested, "nested.txt").writeText("two directories down")
        val event = seen.poll(ARRIVES_MS, TimeUnit.MILLISECONDS)

        assertNotNull("no event arrived, so nothing below is being measured", event)
        assertEquals(
            "the reported path is not the bare entry name",
            "nested.txt", event!!.name
        )
    }

    @Test
    fun directoryEvents_carryTheIsDirFlag_andFileEventsDoNot() {
        // The engine reads this bit to tell a deleted directory from a deleted file,
        // because by the time a delete is reported there is nothing left to stat. Its
        // value came from inotify's ABI rather than from anything readable in the SDK.
        val seen = watch(root)

        File(root, "plain.txt").writeText("a file")
        val fileEvent = seen.firstFor("plain.txt", ARRIVES_MS)
        assertNotNull("no event for the file, so the comparison below is empty", fileEvent)
        assertEquals(
            "a file event carried the directory flag",
            0, fileEvent!!.mask and IN_ISDIR
        )

        assertTrue("could not create the directory", File(root, "subdir").mkdirs())
        val dirEvent = seen.firstFor("subdir", ARRIVES_MS)
        assertNotNull("no event for the directory", dirEvent)
        assertNotEquals(
            "a directory event did not carry the directory flag, so the engine cannot " +
                "tell a deleted directory from a deleted file and leaks its watches",
            0, dirEvent!!.mask and IN_ISDIR
        )
    }

    @Test
    fun theEnginesIsDirConstant_matchesTheFlagThePlatformActuallySets() {
        // Ties the number in the source to the number measured above. Written as its own
        // case because the test before it would keep passing with the engine's constant
        // set to anything at all -- it compares against this file's copy, not the
        // engine's.
        val field = SafSyncEngine::class.java
            .getDeclaredField("IN_ISDIR")
            .apply { isAccessible = true }

        assertEquals(
            "the engine's directory flag is not the one inotify sets",
            IN_ISDIR, field.getInt(null)
        )
    }

    private companion object {
        /** The same events the engine's own observers subscribe to. */
        private val WATCH_MASK = FileObserver.MODIFY or FileObserver.CREATE or
            FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO

        /** inotify's `IN_ISDIR`, from its ABI. What this file exists to confirm. */
        private const val IN_ISDIR = 0x40000000

        /**
         * Generous: delivery is sub-millisecond in practice, and a loaded emulator is
         * the only thing this needs to survive.
         */
        private const val ARRIVES_MS = 5_000L

        /**
         * How long an event is given to fail to arrive. Short is safe here only because
         * every use is preceded by a control that did arrive on the same queue, which
         * establishes that delivery is working and fast.
         */
        private const val ABSENT_MS = 2_000L
    }
}
