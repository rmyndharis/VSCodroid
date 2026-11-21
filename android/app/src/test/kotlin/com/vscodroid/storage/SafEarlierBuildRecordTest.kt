package com.vscodroid.storage

import android.os.FileObserver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The record an earlier build left beside a folder's copy, met by the opens after the app was updated.
 *
 * Builds up to 1.9.0 could drop the row of a copy: the copy then read as a file only the app had, and a deletion of
 * its document on the device was undone with the old bytes as soon as the device listed anything in the folder. An
 * open tells such a record from one this build wrote by its time, which is older than the app's last update. Over
 * it, a file only the app has that is older than the record, with no row and no save in flight, is kept inside the
 * app with a row of its own and the person is told once; a file made after the record is sent. A record this build
 * wrote keeps sending such files: this build drops no row of a copy it did not fetch.
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
        updatedSinceLastOpen()
        mirrorOnly("old.txt")

        sync()
        assertEquals(emptyList<String>(), names(), "the empty folder was sent a copy older than the record")
        assertEquals(listOf(1), didNotReach)

        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "added on the device"))))
        sync()
        assertEquals(emptyList<String>(), names(), "a document the device deleted was put back with its old bytes")
        assertEquals(listOf(1), didNotReach, "the copy kept inside the app was not told of")

        sync()
        assertEquals(emptyList<String>(), names(), "a later open put the old copy back")
        assertEquals(emptyList<Int>(), didNotReach, "a later open told of the same copy again")
        assertEquals("old.txt, only in the app", mirrorFile("old.txt").readText())
    }

    @Test
    fun `a file made after the record is sent at the first open after the update and an older one is kept`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        sync()
        updatedSinceLastOpen()
        mirrorOnly("old.txt")
        mirrorOnly("new.txt", after = 30_000)

        sync()

        assertEquals(listOf("new.txt"), names(), "the open sent the wrong files")
        assertEquals(listOf(1), didNotReach, "the copy kept inside the app was not told of")
        sync()
        assertEquals(listOf("new.txt"), names(), "a later open sent the copy kept inside the app")
        assertEquals(emptyList<Int>(), didNotReach)
    }

    /** The upload journal says the device never got the file, whatever its time: it is sent, not kept. */
    @Test
    fun `a file older than the record is sent when its create never landed`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        sync()
        updatedSinceLastOpen()
        val made = mirrorOnly("made.txt")
        File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE).writeText(made.absolutePath)

        sync()

        assertEquals(listOf("made.txt"), names(), "a file whose create never landed was kept inside the app")
        assertEquals(emptyList<Int>(), didNotReach)
    }

    /**
     * An open whose listing is partial still rewrites the record, and the record's new time read as one this build
     * wrote: the open after it, which could list the folder, sent the old copy.
     */
    @Test
    fun `a partial open after the update does not make the next open send an older copy`() {
        val folder = mapOf(
            "root" to listOf(Child("keep.txt", contents = "k"), Child("sub", isDirectory = true)),
            "doc:sub" to emptyList(),
        )
        deviceTree(folder)
        sync()
        updatedSinceLastOpen()
        mirrorOnly("old.txt")

        deviceTree(folder, errors = setOf("doc:sub"))
        sync()
        assertEquals(emptyList<String>(), names())

        deviceTree(folder)
        sync()

        assertEquals(emptyList<String>(), names(), "the open after a partial one sent a copy older than the record")
        assertEquals(listOf(1), didNotReach)
    }

    /** A save delivered while the folder is open rewrites the record too, and is no more the open that can sort it. */
    @Test
    fun `a save delivered after the update does not make the next open send an older copy`() {
        deviceTree(mapOf("root" to emptyList<Child>()))
        sync()
        updatedSinceLastOpen()
        mirrorOnly("old.txt")
        val engine = sync()
        val saved = mirrorFile("new.txt").apply { writeText("new") }
        engine.handleMirrorEvent(FileObserver.CREATE, saved, mirror, named("tree"))
        engine.runWriteBackLoop { false }
        assertEquals(listOf("new.txt"), names(), "the fixture's save did not reach the device")

        deviceTree(mapOf("root" to listOf(Child("new.txt", contents = "new"))))
        sync()

        assertFalse("old.txt" in names(), "the open after a delivered save sent a copy older than the record")
    }
}
