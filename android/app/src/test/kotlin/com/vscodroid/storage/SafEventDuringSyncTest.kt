package com.vscodroid.storage

import android.os.FileObserver
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * What the watcher may send to the device while an open of the same folder is still reading it.
 *
 * An open fills the mirror from the device folder, and until it has listed and copied
 * everything it cannot tell a file the user is editing from a document it has not got to
 * yet. A save the user's tool reports in that window, for a path the device holds a document
 * for, would open that document with "wt" and truncate what the open has not read. The open
 * therefore marks the whole mirror as unread for as long as it runs, so that such an event
 * is refused where it arrives, announced, and never queued: it has no right to a place in a
 * queue that a drain would reach after the open began replacing the file.
 *
 * What is pinned is that refusal at the door. The drain's own admission check would stop the
 * same write later, but it only drops it, and what it says about it afterwards is the same
 * "kept inside the app" the refusal says here, so each is worth holding on its own.
 */
class SafEventDuringSyncTest : SafInitialSyncTestBase() {

    @Test
    fun `a save reported while the open is still reading is refused and not queued`() {
        deviceTree(mapOf("root" to listOf(Child("notes.md", contents = "what the device holds"))))
        val firstRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { resolver.openInputStream(any()) } answers {
            firstRead.countDown()
            release.await(10, TimeUnit.SECONDS)
            ByteArrayInputStream("what the device holds".toByteArray())
        }
        val engine = SafSyncEngine(context)
        engine.onWriteBackFailed = { lost += it.name }
        val failure = AtomicReference<Throwable?>()
        val opening = Thread {
            try {
                runBlocking { engine.initialSync(named("tree"), mirror) { _, _ -> } }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }.apply { isDaemon = true; start() }

        try {
            assertTrue(firstRead.await(5, TimeUnit.SECONDS), "the open never reached its first read")
            // The user's tool saves the file that this open is fetching.
            val file = File(mirror, "notes.md").apply { writeText("typed while the folder was opening") }
            engine.handleMirrorEvent(FileObserver.MODIFY, file, mirror, named("tree"))

            assertTrue(engine.session.queue.isEmpty(), "a save for a document the open has not read was queued")
            assertEquals(listOf("notes.md"), lost, "the refused save was not announced")
        } finally {
            release.countDown()
            opening.join(5_000)
        }
        assertFalse(opening.isAlive, "the open did not finish")
        assertEquals(null, failure.get())
    }

    @Test
    fun `the same save after the open has finished is queued`() {
        deviceTree(mapOf("root" to listOf(Child("notes.md", contents = "what the device holds"))))
        val engine = SafSyncEngine(context)
        engine.onWriteBackFailed = { lost += it.name }
        runBlocking { engine.initialSync(named("tree"), mirror) { _, _ -> } }

        val file = File(mirror, "notes.md").apply { writeText("typed after the folder opened") }
        engine.handleMirrorEvent(FileObserver.MODIFY, file, mirror, named("tree"))

        assertEquals(1, engine.session.queue.size, "an ordinary save was refused: the guard outlived the open")
        assertEquals(emptyList<String>(), lost)
    }
}
