package com.vscodroid.storage

import android.content.SharedPreferences
import android.content.UriPermission
import android.net.Uri
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SafNamedRegrantCleanupTest : SafInitialSyncTestBase() {
    private lateinit var manager: SafStorageManager
    private lateinit var replacement: SafStorageManager
    private lateinit var folder: Uri
    private val granted = AtomicBoolean(false)
    private val recent = AtomicReference("[]")
    private val armSnapshot = AtomicBoolean(false)
    private val reclaimReads = AtomicInteger()
    private val snapshotEntered = CountDownLatch(1)
    private val letSnapshotReturn = CountDownLatch(1)
    private val takeSucceeded = CountDownLatch(1)
    private val events = ConcurrentLinkedQueue<String>()
    private val workers = mutableListOf<Worker>()

    private class Worker(val name: String, work: () -> Unit) {
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val thread = thread(name = name, isDaemon = true) {
            started.countDown()
            try { work() } catch (e: Throwable) { error.set(e) } finally { done.countDown() }
        }
        fun finish() {
            assertTrue(done.await(5, TimeUnit.SECONDS), "$name did not finish")
            error.get()?.let { throw AssertionError("$name failed", it) }
        }
    }

    @BeforeEach
    fun setUpMirror() {
        every { context.applicationContext } returns context
        folder = named("content://tree/primary%3AProject")
        every { folder.lastPathSegment } returns "primary:Project"
        mockkStatic(Uri::class)
        val folderText = folder.toString()
        every { Uri.parse(folderText) } returns folder
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } answers { recent.get() }
        every { prefs.edit() } answers {
            val editor = mockk<SharedPreferences.Editor>(relaxed = true)
            var pending: String? = null
            every { editor.putString(any(), any()) } answers {
                pending = secondArg<String>()
                editor
            }
            every { editor.apply() } answers { pending?.let { recent.set(it) }; Unit }
            every { editor.commit() } answers { pending?.let { recent.set(it) }; true }
            editor
        }
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { resolver.takePersistableUriPermission(any(), any()) } answers {
            granted.set(true)
            if (Thread.currentThread().name == "regrant") {
                events.add("new take succeeded")
                takeSucceeded.countDown()
            }
            Unit
        }
        every { resolver.releasePersistableUriPermission(any(), any()) } answers { granted.set(false) }
        every { resolver.persistedUriPermissions } answers {
            val snapshot = if (granted.get()) listOf(mockk<UriPermission> {
                every { uri } returns folder
                every { isReadPermission } returns true
                every { isWritePermission } returns true
            }) else emptyList()
            if (armSnapshot.get() && Thread.currentThread().name == "cleanup" &&
                reclaimReads.incrementAndGet() == 1
            ) {
                events.add("empty cleanup snapshot taken")
                assertTrue(snapshot.isEmpty())
                snapshotEntered.countDown()
                assertTrue(letSnapshotReturn.await(20, TimeUnit.SECONDS), "snapshot gate timed out")
                events.add("old cleanup snapshot returned")
            }
            snapshot
        }
        manager = SafStorageManager(context)
        replacement = SafStorageManager(context)
        mirror = manager.getMirrorDir(folder)
        deviceTree(mapOf("root" to listOf(Child("notes.md", contents = "DEVICE"))))
        manager.persistPermission(folder)
        runBlocking { manager.syncToLocal(folder) }
        assertTrue(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror))
        assertTrue(record.isFile)
        resolver.releasePersistableUriPermission(folder, 3)
        assertFalse(manager.hasPersistedPermission(folder))
        assertEquals(setOf(mirror.name, record.name), mirror.parentFile.list()!!.toSet())
    }

    private fun worker(name: String, work: () -> Unit) = Worker(name, work).also { workers.add(it) }

    private fun folderInfo() = SafFolderInfo(folder, "Project", 0L, mirror.absolutePath)

    private fun retireCurrentMirror() {
        assertTrue(manager.reclaimMirror(mirror.name, force = true) >= 0)
        manager.sweepDiscardedMirrors()
        assertFalse(mirror.exists())
        assertFalse(record.exists())
        assertFalse(granted.get())
    }

    private fun cleanupAgainstRegrant(afterNewSync: () -> Unit) {
        armSnapshot.set(true)
        val old = worker("cleanup") { manager.reclaimRevokedMirrorsSync() }
        try {
            assertTrue(snapshotEntered.await(5, TimeUnit.SECONDS), "cleanup never took its empty grant snapshot")
            val new = worker("regrant") {
                replacement.persistPermission(folder)
                runBlocking { replacement.syncToLocal(folder) }
                afterNewSync()
            }
            assertTrue(new.started.await(5, TimeUnit.SECONDS))
            if (new.done.await(5, TimeUnit.SECONDS)) {
                new.finish()
                assertTrue(granted.get(), "the completed regrant must be held")
                assertTrue(mirror.isDirectory)
            } else {
                assertEquals(1L, takeSucceeded.count, "a take escaped ordering without completing")
            }
            letSnapshotReturn.countDown()
            old.finish()
            new.finish()
        } finally {
            letSnapshotReturn.countDown()
        }
        assertTrue(replacement.hasPersistedPermission(folder))
        assertEquals("DEVICE", File(mirror, "notes.md").readText(), "cleanup must leave workspace bytes intact")
        assertTrue(record.isFile, "the completed sync record must remain intact")
    }

    @Test
    fun `a regranted and synced copy keeps its current named path after an older cleanup`() {
        val link = manager.namedPathFor(mirror, "Project")!!
        assertTrue(Files.isSymbolicLink(link.toPath()))
        retireCurrentMirror()

        cleanupAgainstRegrant {
            assertEquals(link, replacement.namedPathFor(mirror, "Project"))
            replacement.noteOpened(link.absolutePath, folderInfo())
            assertEquals("DEVICE", File(link, "notes.md").readText())
        }

        assertAll(
            { assertTrue(Files.isSymbolicLink(link.toPath()), "the old cleanup removed the current workspace path") },
            { assertEquals(link, replacement.existingNamedPath(mirror)) },
            { assertEquals("DEVICE", if (File(link, "notes.md").isFile) File(link, "notes.md").readText() else null) },
            { assertTrue(replacement.wasShownByName(mirror), "the isolated link cleanup must leave the later marker alone") },
        )
    }

    @Test
    fun `a current named opening keeps its shown marker after an older cleanup`() {
        val link = manager.namedPathFor(mirror, "Project")!!
        manager.noteOpened(link.absolutePath, folderInfo())
        assertTrue(manager.wasShownByName(mirror))
        Files.delete(link.toPath())
        Files.delete(link.parentFile.toPath())
        retireCurrentMirror()

        cleanupAgainstRegrant {
            assertEquals(link, replacement.namedPathFor(mirror, "Project"))
            replacement.noteOpened(link.absolutePath, folderInfo())
            assertTrue(replacement.wasShownByName(mirror))
        }

        assertAll(
            { assertTrue(Files.isSymbolicLink(link.toPath()), "the isolated marker cleanup must leave the later link alone") },
            { assertTrue(replacement.wasShownByName(mirror), "the old cleanup removed the current opening marker") },
        )
    }

    @Test
    fun `a completed regrant sync keeps its final record when an older partial cleanup resumes`() {
        retireCurrentMirror()
        val partial = File(record.path + SafSyncEngine.PARTIAL_SUFFIX)
        partial.writeText("old staging metadata")
        var completedRecord: String? = null

        cleanupAgainstRegrant {
            assertFalse(partial.exists(), "the actual completed sync must consume its deterministic staging name")
            completedRecord = record.readText()
        }

        assertEquals(completedRecord, record.readText())
        assertFalse(partial.exists())
        assertTrue(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror))
    }

    @Test
    fun `a copy that stays gone still loses its unused named path and marker`() {
        val link = manager.namedPathFor(mirror, "Project")!!
        manager.noteOpened(link.absolutePath, folderInfo())
        retireCurrentMirror()
        manager.reclaimRevokedMirrorsSync()
        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertFalse(manager.wasShownByName(mirror))
    }

    private fun finishWorkers() {
        letSnapshotReturn.countDown()
        workers.forEach { it.thread.join(5_000) }
        workers.filter { it.thread.isAlive }.forEach { it.thread.interrupt() }
        workers.forEach { it.thread.join(5_000) }
        assertTrue(workers.none { it.thread.isAlive }, "a fixture worker did not terminate")
        workers.forEach { it.error.get()?.let { e -> throw AssertionError("${it.name} failed", e) } }
    }

    @AfterEach
    fun finishMirrorWorkers() = finishWorkers()
}
