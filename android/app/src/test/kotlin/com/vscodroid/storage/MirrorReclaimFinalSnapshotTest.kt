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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MirrorReclaimFinalSnapshotTest : SafInitialSyncTestBase() {
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
            if (armSnapshot.get() && Thread.currentThread().name == "reclaim" &&
                reclaimReads.incrementAndGet() == 1
            ) {
                events.add("empty orphan-record snapshot taken")
                assertTrue(snapshot.isEmpty())
                snapshotEntered.countDown()
                assertTrue(letSnapshotReturn.await(20, TimeUnit.SECONDS), "snapshot gate timed out")
                events.add("old orphan-record snapshot returned")
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

    @Test
    fun `orphan record cleanup completes its decision before a new grant is taken`() {
        val retainedCopy = File(mirror.parentFile, "kept-project")
        assertTrue(mirror.renameTo(retainedCopy))
        val removed = AtomicInteger(-1)
        try {
            armSnapshot.set(true)
            val old = worker("reclaim") { removed.set(manager.reclaimRevokedMirrorsSync()) }
            assertTrue(snapshotEntered.await(5, TimeUnit.SECONDS), "the real orphan record grant read was never reached")
            val new = worker("regrant") {
                replacement.persistPermission(folder)
                runBlocking { replacement.syncToLocal(folder) }
                File(mirror, ".git").mkdirs()
                File(mirror, ".git/HEAD").writeText("UNIQUE")
                events.add("new open and unique bytes complete")
            }
            assertTrue(new.started.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline && new.done.count != 0L &&
                !(new.thread.state == Thread.State.BLOCKED &&
                    new.thread.stackTrace.firstOrNull()?.methodName == "persistPermission")
            ) {
                Thread.sleep(10)
            }
            assertEquals(Thread.State.BLOCKED, new.thread.state, "the regrant did not wait for the orphan decision: $events")
            assertEquals("persistPermission", new.thread.stackTrace.firstOrNull()?.methodName)
            assertEquals(1L, takeSucceeded.count, "a new take escaped the orphan-record decision: $events")
            letSnapshotReturn.countDown()
            old.finish()
            new.finish()
            assertEquals(1, removed.get(), "the orphan record cleanup never ran")
            assertEquals("DEVICE", File(retainedCopy, "notes.md").readText())
            assertTrue(replacement.hasPersistedPermission(folder), "the regrant disappeared: $events")
            assertTrue(mirror.isDirectory, "a new mirror was removed using an old snapshot: $events")
            assertTrue(record.isFile, "the live mirror's record was removed: $events")
            assertEquals("UNIQUE", File(mirror, ".git/HEAD").readText(), "unique bytes were removed: $events")
        } finally { finishWorkers() }
    }

    @Test
    fun `an ordinary mirror that stays revoked retains its bytes and record`() {
        val savedRecord = record.readBytes()
        assertEquals(0, manager.reclaimRevokedMirrorsSync())
        assertEquals("DEVICE", File(mirror, "notes.md").readText())
        assertArrayEquals(savedRecord, record.readBytes())
    }

    @Test
    fun `a pair committed by confirmed removal is still physically reclaimed`() {
        assertTrue(manager.reclaimMirror(mirror.name, force = true) >= 0)
        assertEquals(2, manager.reclaimRevokedMirrorsSync())
        assertTrue(mirror.parentFile.list()!!.isEmpty())
    }

    @Test
    fun `an unsent edit still prevents automatic reclamation`() {
        File(mirror, ".git").mkdirs()
        File(mirror, ".git/HEAD").writeText("UNIQUE")
        assertEquals(0, manager.reclaimRevokedMirrorsSync())
        assertEquals("UNIQUE", File(mirror, ".git/HEAD").readText())
        assertTrue(record.isFile)
    }

    @Test
    fun `a grant visible before the pass preserves both entries`() {
        replacement.persistPermission(folder)
        assertEquals(0, manager.reclaimRevokedMirrorsSync())
        assertTrue(mirror.isDirectory)
        assertTrue(record.isFile)
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
