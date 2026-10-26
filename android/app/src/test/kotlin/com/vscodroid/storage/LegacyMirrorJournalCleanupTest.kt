package com.vscodroid.storage

import android.content.SharedPreferences
import android.content.UriPermission
import android.net.Uri
import android.os.FileObserver
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Legacy journal keys must not be confused with a later copy under the same name. */
class LegacyMirrorJournalCleanupTest : SafInitialSyncTestBase() {
    private lateinit var manager: SafStorageManager
    private lateinit var folder: Uri
    private val granted = AtomicBoolean(false)
    private val clearEntered = CountDownLatch(1)
    private val resumeClear = CountDownLatch(1)
    private val workers = mutableListOf<Worker>()

    private class Worker(name: String, work: () -> Unit) {
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val thread = thread(name = name, isDaemon = true) {
            started.countDown()
            try { work() } catch (e: Throwable) { error.set(e) } finally { done.countDown() }
        }
        fun finish() {
            assertTrue(done.await(5, TimeUnit.SECONDS), "${thread.name} did not finish")
            error.get()?.let { throw AssertionError("${thread.name} failed", it) }
        }
    }

    @BeforeEach
    fun setUpLegacyCopy() {
        every { context.applicationContext } returns context
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "[]"
        every { context.getSharedPreferences(any(), any()) } returns prefs
        folder = named("content://tree/primary%3ALegacyProject")
        every { folder.lastPathSegment } returns "primary:LegacyProject"
        val folderText = folder.toString()
        mockkStatic(Uri::class)
        every { Uri.parse(folderText) } returns folder
        val permission = mockk<UriPermission> {
            every { uri } returns folder
            every { isReadPermission } returns true
            every { isWritePermission } returns true
        }
        every { resolver.persistedUriPermissions } answers {
            if (granted.get()) listOf(permission) else emptyList()
        }
        every { resolver.takePersistableUriPermission(folder, any()) } answers { granted.set(true) }
        every { resolver.releasePersistableUriPermission(folder, any()) } answers { granted.set(false) }
        manager = SafStorageManager(context)
        mirror = manager.getMirrorDir(folder)
        deviceTree(mapOf("root" to listOf(Child("notes.md", contents = "DEVICE"))))
        manager.persistPermission(folder)
        runBlocking { manager.syncToLocal(folder) }
        assertEquals("DEVICE", File(mirror, "notes.md").readText())
        assertTrue(record.isFile)
    }

    private fun pendingPath() = File(mirror, "notes.md").absolutePath
    private fun discarded(entry: File) = File(entry.parentFile, SafStorageManager.DISCARD_PREFIX + entry.name)

    private fun failedWrite(bytes: String): SafSyncEngine {
        val local = File(mirror, "notes.md").apply { writeText(bytes) }
        every { resolver.openOutputStream(any(), "wt") } throws IOException("no space left on device")
        val engine = SafSyncEngine(context)
        clearMocks(resolver, answers = false, recordedCalls = true, childMocks = false)
        engine.handleMirrorEvent(FileObserver.MODIFY, local, mirror, folder)
        engine.runWriteBackLoop { false }
        verify(atLeast = 1) { resolver.openOutputStream(any(), "wt") }
        assertEquals(bytes, local.readText())
        assertTrue(local.absolutePath in engine.uploadsInFlight(), "the actual refused save wrote no journal line")
        return engine
    }

    private fun leaveLegacyCommittedCopy(): SafSyncEngine {
        val engine = failedWrite("LEGACY UNIQUE")
        // Older committed removals left journal keys under the ordinary name.
        assertTrue(mirror.renameTo(discarded(mirror)))
        assertTrue(record.renameTo(discarded(record)))
        manager.releaseGrantFor(mirror.name)
        assertFalse(manager.hasPersistedPermission(folder))
        assertFalse(mirror.exists())
        assertEquals("LEGACY UNIQUE", File(discarded(mirror), "notes.md").readText())
        assertTrue(pendingPath() in engine.uploadsInFlight())
        return engine
    }

    private fun regrantWithFailedWrite(): SafSyncEngine {
        val replacement = SafStorageManager(context)
        replacement.persistPermission(folder)
        runBlocking { replacement.syncToLocal(folder) }
        assertTrue(replacement.hasPersistedPermission(folder))
        return failedWrite("FRESH UNIQUE")
    }

    @Test
    fun `legacy journal keys are retired when no ordinary copy returns`() {
        val engine = leaveLegacyCommittedCopy()

        assertEquals(2, manager.sweepDiscardedMirrors())

        assertFalse(pendingPath() in engine.uploadsInFlight())
        assertFalse(discarded(mirror).exists())
        assertFalse(discarded(record).exists())
    }

    @Test
    fun `a fresh ordinary copy keeps its refused save while the legacy copy is swept`() {
        leaveLegacyCommittedCopy()
        val fresh = regrantWithFailedWrite()
        val freshRecord = record.readBytes()

        assertEquals(2, manager.sweepDiscardedMirrors())

        assertEquals("FRESH UNIQUE", File(mirror, "notes.md").readText())
        assertArrayEquals(freshRecord, record.readBytes())
        assertTrue(pendingPath() in fresh.uploadsInFlight())
        assertFalse(discarded(mirror).exists())
        assertFalse(discarded(record).exists())
    }

    @Test
    fun `a regrant after the legacy absence check keeps its new refused save`() {
        leaveLegacyCommittedCopy()
        mockkConstructor(SafSyncEngine::class)
        every { anyConstructed<SafSyncEngine>().clearUploadsUnder(any()) } answers {
            val root = firstArg<File>()
            if (Thread.currentThread().name == "legacy-sweep" && root.absolutePath == mirror.absolutePath) {
                assertFalse(mirror.exists(), "the barrier must follow the actual absent-root check")
                clearEntered.countDown()
                assertTrue(resumeClear.await(15, TimeUnit.SECONDS), "the journal-clear barrier timed out")
            }
            callOriginal()
        }
        val sweeper = SafStorageManager(context)
        val freshEngine = AtomicReference<SafSyncEngine?>()
        val freshRecord = AtomicReference<ByteArray?>()
        try {
            val old = Worker("legacy-sweep") { assertEquals(2, sweeper.sweepDiscardedMirrors()) }.also { workers.add(it) }
            assertTrue(clearEntered.await(5, TimeUnit.SECONDS), "cleanup never reached the real original-prefix clear")
            val new = Worker("legacy-regrant") {
                freshEngine.set(regrantWithFailedWrite())
                freshRecord.set(record.readBytes())
            }.also { workers.add(it) }
            assertTrue(new.started.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            fun blockedAtGrant() = new.thread.state == Thread.State.BLOCKED &&
                new.thread.stackTrace.firstOrNull()?.methodName == "persistPermission"
            while (System.nanoTime() < deadline && new.done.count != 0L && !blockedAtGrant()) {
                Thread.sleep(10)
            }
            assertTrue(new.done.count == 0L || blockedAtGrant(), "the regrant neither completed nor waited for the grant lock")
            if (new.done.count == 0L) {
                new.finish()
                assertTrue(pendingPath() in freshEngine.get()!!.uploadsInFlight())
            } else {
                assertFalse(granted.get(), "a new grant escaped serialization before its copy was ready")
            }
            resumeClear.countDown()
            old.finish()
            new.finish()

            assertEquals("FRESH UNIQUE", File(mirror, "notes.md").readText())
            assertArrayEquals(freshRecord.get(), record.readBytes())
            assertTrue(pendingPath() in freshEngine.get()!!.uploadsInFlight(),
                "the legacy sweep retired the new copy's actual refused-save record")
            assertFalse(discarded(mirror).exists())
            assertFalse(discarded(record).exists())
        } finally {
            finishWorkers()
            unmockkConstructor(SafSyncEngine::class)
        }
    }

    private fun finishWorkers() {
        resumeClear.countDown()
        workers.forEach { it.thread.join(5_000) }
        workers.filter { it.thread.isAlive }.forEach { it.thread.interrupt() }
        workers.forEach { it.thread.join(5_000) }
        assertTrue(workers.none { it.thread.isAlive }, "a fixture worker did not terminate")
        workers.forEach { it.error.get()?.let { error -> throw AssertionError("${it.thread.name} failed", error) } }
    }

    @AfterEach
    fun finishLegacyWorkers() = finishWorkers()
}
