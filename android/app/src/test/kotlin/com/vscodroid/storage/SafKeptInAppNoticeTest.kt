package com.vscodroid.storage

import android.os.FileObserver
import android.provider.DocumentsContract
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Which notice a file that did not reach the device folder is told of by, at each place that tells of it.
 *
 * Two different things were said in one sentence. A file the device folder refused at an open, or an entry a folder
 * made in the editor did not bring along, did not reach the device: it may be a folder, or a file whose folder never
 * got there, and saving it again does not send it. A file an open keeps inside the app because it cannot tell it from
 * a document deleted on the device is another case: the person may want it there or may have deleted it there on
 * purpose, and saving it again does send it. Each has its own seam, and the second names a file. The phase 3 rows of
 * an earlier build's record are told of in [SafEarlierBuildRecordTest], and a file the watcher does not send after
 * an open in [SafQueuedWriteBackTest].
 */
class SafKeptInAppNoticeTest : SafInitialSyncTestBase() {

    @Test
    fun `a file the device folder refuses at an open is told of as not reached`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        sync()
        mirrorFile("new.txt").writeText("made while the folder was closed")
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } returns null

        sync()

        assertEquals(listOf(1), didNotReach)
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp)
    }

    /** Over a folder the device lists as empty, files older than the record are kept and named, in path order. */
    @Test
    fun `files an open keeps inside the app are told of by count and the first of them`() {
        deviceTree(mapOf("root" to emptyList<Child>()))
        sync()
        listOf("b.txt", "a.txt").forEach { name ->
            mirrorFile(name).apply {
                writeText(name)
                setLastModified(record.lastModified() - 60_000)
            }
        }

        sync()

        assertEquals(listOf(2 to "a.txt"), keptInApp)
        assertEquals(emptyList<Int>(), didNotReach)
    }

    @Test
    fun `a folder made in the editor that arrives short of a file is told of as not reached`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        val engine = sync()
        val made = File(mirror, "made").apply { mkdirs() }
        File(made, "a.txt").writeText("a")
        File(made, "b.txt").writeText("b")
        every { DocumentsContract.createDocument(any(), any(), any(), "b.txt") } returns null

        engine.handleMirrorEvent(FileObserver.CREATE or IN_ISDIR, made, mirror, named("tree"))
        engine.runWriteBackLoop { false }

        assertEquals(listOf(1), didNotReach)
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp)
    }

    private companion object {
        /** inotify's flag for an event about a directory, which FileObserver passes through. */
        const val IN_ISDIR = 0x40000000
    }
}
