package com.vscodroid.storage

import android.os.FileObserver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The record an earlier build left beside a folder's copy, met by the opens after the app was updated.
 *
 * Builds up to 1.9.0 could drop the row of a copy: the copy then read as a file only the app had, and a deletion of
 * its document on the device was undone with the old bytes as soon as the device listed anything in the folder. A
 * record an open of this build has sorted carries a mark beside it ([SafSyncEngine.SORTED_RECORD_SUFFIX]), and one
 * without the mark was written by an earlier build. Over it, a file only the app has that is older than the record,
 * with no row and no save in flight, is kept inside the app with a row of its own and the person is told once; a
 * file made after the record is sent. A record this build wrote keeps sending such files, after any number of
 * updates: this build drops no row of a copy it did not fetch, and the files it leaves without a row (past the
 * watcher's cap, past the open's) are its own work still to send.
 */
class SafEarlierBuildRecordTest : SafInitialSyncTestBase() {

    private fun names() = created.map { it.second }

    /** A file only the app holds, with the time it had when the record was written or [after] it. */
    private fun mirrorOnly(name: String, after: Long = -60_000) =
        mirrorFile(name).apply {
            writeText("$name, only in the app")
            setLastModified(record.lastModified() + after)
        }

    @Test
    fun `a copy older than the record stays in the app once the device lists the folder again`() {
        deviceTree(mapOf("root" to emptyList<Child>()))
        sync()
        recordLeftByEarlierBuild()
        mirrorOnly("old.txt")

        sync()
        assertEquals(emptyList<String>(), names(), "the empty folder was sent a copy older than the record")
        assertEquals(listOf(1 to "old.txt"), keptInApp)

        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "added on the device"))))
        sync()
        assertEquals(emptyList<String>(), names(), "a document the device deleted was put back with its old bytes")
        assertEquals(listOf(1 to "old.txt"), keptInApp, "the copy kept inside the app was not told of")
        assertEquals(emptyList<Int>(), didNotReach, "the copy kept inside the app was told of as one the device refused")

        sync()
        assertEquals(emptyList<String>(), names(), "a later open put the old copy back")
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp, "a later open told of the same copy again")
        assertEquals("old.txt, only in the app", mirrorFile("old.txt").readText())
    }

    @Test
    fun `a file made after the record is sent at the first open after the update and an older one is kept`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        sync()
        recordLeftByEarlierBuild()
        mirrorOnly("old.txt")
        mirrorOnly("new.txt", after = 30_000)

        sync()

        assertEquals(listOf("new.txt"), names(), "the open sent the wrong files")
        assertEquals(listOf(1 to "old.txt"), keptInApp, "the copy kept inside the app was not told of")
        sync()
        assertEquals(listOf("new.txt"), names(), "a later open sent the copy kept inside the app")
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp)
    }

    /** The upload journal says the device never got the file, whatever its time: it is sent, not kept. */
    @Test
    fun `a file older than the record is sent when its create never landed`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        sync()
        recordLeftByEarlierBuild()
        val made = mirrorOnly("made.txt")
        File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE).writeText(made.absolutePath)

        sync()

        assertEquals(listOf("made.txt"), names(), "a file whose create never landed was kept inside the app")
        assertEquals(emptyList<Int>(), didNotReach)
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp)
    }

    /**
     * An open whose listing is partial still rewrites the record, and the record's new time read as one this build
     * wrote: the open after it, which could list the folder, sent the old copy. It keeps the record's time, so a file
     * made after the earlier build's record and before that rewrite is still sent.
     */
    @Test
    fun `a partial open after the update does not make the next open send an older copy`() {
        val folder = mapOf(
            "root" to listOf(Child("keep.txt", contents = "k"), Child("sub", isDirectory = true)),
            "doc:sub" to emptyList(),
        )
        deviceTree(folder)
        sync()
        recordLeftByEarlierBuild()
        val writtenByEarlierBuild = record.lastModified()
        mirrorOnly("old.txt")

        deviceTree(folder, errors = setOf("doc:sub"))
        sync()
        assertEquals(emptyList<String>(), names())
        assertFalse(sortedMark.exists(), "a partial open marked a record it could not sort")
        // Made after the earlier build's record and dated before the partial open's rewrite of it.
        mirrorFile("later.txt").apply {
            writeText("later.txt, only in the app")
            setLastModified(writtenByEarlierBuild + 30_000)
        }

        deviceTree(folder)
        sync()

        assertEquals(
            listOf("later.txt"),
            names(),
            "the open after a partial one sent a copy older than the record, or kept one made after it",
        )
        assertEquals(listOf(1 to "old.txt"), keptInApp)
    }

    /**
     * A save delivered while the folder is open rewrites the record too, and is no more the open that can sort it. It
     * keeps the record's time, so a file made after the earlier build's record and before the save is still sent.
     */
    @Test
    fun `a save delivered after the update does not make the next open send an older copy`() {
        deviceTree(mapOf("root" to emptyList<Child>()))
        sync()
        recordLeftByEarlierBuild()
        val writtenByEarlierBuild = record.lastModified()
        mirrorOnly("old.txt")
        val engine = sync()
        // Made after the earlier build's record and dated before the delivered save's rewrite of it.
        mirrorFile("later.txt").apply {
            writeText("later.txt, only in the app")
            setLastModified(writtenByEarlierBuild + 30_000)
        }
        val saved = mirrorFile("new.txt").apply { writeText("new") }
        engine.handleMirrorEvent(FileObserver.CREATE, saved, mirror, named("tree"))
        engine.runWriteBackLoop { false }
        assertEquals(listOf("new.txt"), names(), "the fixture's save did not reach the device")
        assertFalse(sortedMark.exists(), "a delivered save marked a record no open of this build has sorted")

        deviceTree(mapOf("root" to listOf(Child("new.txt", contents = "new"))))
        sync()

        assertFalse("old.txt" in names(), "the open after a delivered save sent a copy older than the record")
        assertTrue("later.txt" in names(), "the open after a delivered save kept a file made after the record")
    }

    /**
     * The rule followed the package's update time: every record dates from before the next update, this build's
     * own included, so after any update a file only the app holds that is older than the record was kept inside
     * the app for good, though this build drops no row. The mark an open of this build leaves says who wrote the
     * record, and an update does not take it away.
     */
    @Test
    fun `a record this build sorted sends an older file only the app holds after an update`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        sync()
        assertTrue(sortedMark.isFile, "the open that sorted the record left no mark")
        updatedSinceLastOpen()
        mirrorOnly("old.txt")

        sync()

        assertEquals(listOf("old.txt"), names(), "a file only the app holds was kept for being older than this build's record")
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp)
    }

    /** The record this build makes for a folder that is empty at its first open is this build's as well. */
    @Test
    fun `a record this build made for an empty folder sends an older file after an update`() {
        deviceTree(mapOf("root" to emptyList<Child>()))
        sync()
        assertTrue(sortedMark.isFile, "the record made for an empty folder carries no mark")
        updatedSinceLastOpen()
        mirrorOnly("old.txt")
        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "added on the device"))))

        sync()

        assertEquals(listOf("old.txt"), names(), "a file only the app holds was kept for being older than this build's record")
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp)
    }

    /**
     * `cp -r` of a folder with more files than the watcher creates in one pass: the files past the cap are never
     * tried, so they have no row and no line in the upload journal. A save delivered afterwards rewrites the record,
     * which makes them older than it, and the first open after the next update kept them inside the app for good,
     * with a count and no name; the folder removed from the app later took them with it.
     */
    @Test
    fun `a file this build left past the watcher's cap is sent at the first open after an update`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        val engine = sync()
        val big = File(mirror, "big").apply { mkdirs() }
        val copied = (1..SafSyncEngine.MAX_UPLOAD_ENTRIES + 1).map { n ->
            File(big, "f%04d.txt".format(n)).apply {
                writeText("file $n")
                setLastModified(System.currentTimeMillis() - 120_000)
            }
        }
        engine.handleMirrorEvent(FileObserver.CREATE or IN_ISDIR, big, mirror, named("tree"))
        engine.runWriteBackLoop { false }
        val sent = names().toSet()
        val left = copied.filterNot { it.name in sent }
        assertEquals(1, left.size, "precondition: the watcher did not stop at its cap")
        val saved = mirrorFile("new.txt").apply { writeText("new") }
        engine.handleMirrorEvent(FileObserver.CREATE, saved, mirror, named("tree"))
        engine.runWriteBackLoop { false }
        assertTrue(left.single().lastModified() < record.lastModified(), "precondition: the file is not older than the record")
        deviceTree(
            mapOf(
                "root" to listOf(
                    Child("keep.txt", contents = "k"),
                    Child("big", isDirectory = true),
                    Child("new.txt", contents = "new", modified = saved.lastModified()),
                ),
                "doc:big" to copied.filter { it.name in sent }.map {
                    Child(it.name, contents = it.readText(), modified = it.lastModified(), id = "doc:big/${it.name}")
                },
            ),
        )
        updatedSinceLastOpen(redateRecord = false)
        created.clear()

        sync()

        assertEquals(listOf(left.single().name), names(), "the file past the watcher's cap was kept inside the app")
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp)
    }

    /** More files older than the record than one open sends, in `a/`, all of which the open keeps inside the app. */
    private fun keptPastTheCap() = File(mirror, "a").apply { mkdirs() }.let { kept ->
        (0..SafSyncEngine.MAX_UPLOAD_ENTRIES).map { n ->
            File(kept, "f%04d.txt".format(n)).apply {
                writeText("file $n")
                setLastModified(record.lastModified() - 60_000)
            }
        }
    }

    /**
     * A tar or a `cp -p` into a folder an earlier build last opened leaves thousands of files older than its record,
     * which the first open after the update keeps inside the app. Each spent one of the files an open sends, though
     * none is ever sent until it changes, so a file only the app holds that the walk reached after them, here one
     * made after the record, was never sent, at that open or any later one, and nothing said so.
     */
    @Test
    fun `files kept inside the app do not stop a later file only the app holds from being sent`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        sync()
        recordLeftByEarlierBuild()
        keptPastTheCap()
        File(mirror, "z").mkdirs()
        mirrorOnly("z/new.txt", after = 30_000)

        sync()
        val sentAtFirstOpen = "new.txt" in names()
        assertEquals(listOf(SafSyncEngine.MAX_UPLOAD_ENTRIES + 1 to "a/f0000.txt"), keptInApp)
        if (!sentAtFirstOpen) sync()

        assertTrue("new.txt" in names(), "a file only the app holds, behind the files kept inside the app, was not sent")
        assertEquals("z/new.txt, only in the app", written["doc-uri:doc:new.txt"]?.toString())
        assertEquals(emptyList<Int>(), didNotReach, "the files kept inside the app were told of as a folder too large to send")
        // Nothing stands before it once the kept files spend none of the open's sends, so the open that keeps them sends it.
        assertTrue(sentAtFirstOpen, "the file was sent only at the second open")
    }

    /**
     * The opens after that one: the kept files have rows without a digest, which keep them out of what an open sends
     * until each changes, and they spent the same files the open could send. A file made after the open that kept
     * them, behind them in the walk, was never sent.
     */
    @Test
    fun `files an earlier open kept inside the app do not stop a file made since from being sent`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("z", isDirectory = true)), "doc:z" to emptyList()))
        sync()
        recordLeftByEarlierBuild()
        keptPastTheCap()
        sync()
        assertEquals(listOf(SafSyncEngine.MAX_UPLOAD_ENTRIES + 1 to "a/f0000.txt"), keptInApp)
        assertEquals(emptyList<String>(), names(), "precondition: the first open sent a file it keeps")

        mirrorFile("z/later.txt").writeText("made after the open that kept the others")
        sync()

        assertEquals(listOf("later.txt"), names(), "a file made since, behind the files kept inside the app, was not sent")
        assertEquals(emptyList<Int>(), didNotReach)
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp, "the files kept inside the app were told of again")
        // Changed, a kept file is the person's work again and is sent, so it counts once more.
        File(mirror, "a/f0000.txt").writeText("file 0, changed")
        created.clear()
        sync()
        assertEquals(listOf("a", "f0000.txt"), names(), "a kept file that changed was not sent")
    }

    private companion object {
        /** inotify's flag for an event about a directory, which FileObserver passes through. */
        const val IN_ISDIR = 0x40000000
    }
}
