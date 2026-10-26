package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.UriPermission
import android.net.Uri
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RecentFolderReacquisitionTest {
    @TempDir lateinit var filesDir: File
    private lateinit var manager: SafStorageManager
    private lateinit var replacement: SafStorageManager
    private lateinit var resolver: ContentResolver
    private val grants = linkedSetOf<Uri>()
    private val recent = AtomicReference("[]")
    private val releaseEntered = CountDownLatch(1)
    private val letReleaseFinish = CountDownLatch(1)
    private val takeSucceeded = CountDownLatch(1)
    private val nameEntered = CountDownLatch(1)
    private val letNameFinish = CountDownLatch(1)
    private val armRelease = AtomicBoolean(false)
    private val armName = AtomicBoolean(false)
    private val events = ConcurrentLinkedQueue<String>()
    private val workers = mutableListOf<Worker>()
    private lateinit var oldest: Uri
    private lateinit var nextOldest: Uri
    private lateinit var picked: Uri

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
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkStatic(Uri::class)
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        every { resolver.persistedUriPermissions } answers {
            synchronized(grants) { grants.toList() }.map { granted ->
                mockk<UriPermission> {
                    every { uri } returns granted
                    every { isReadPermission } returns true
                    every { isWritePermission } returns true
                }
            }
        }
        every { resolver.takePersistableUriPermission(any(), any()) } answers {
            val uri = firstArg<Uri>()
            synchronized(grants) { grants.add(uri) }
            if (Thread.currentThread().name == "reacquire") {
                events.add("new take succeeded")
                takeSucceeded.countDown()
            }
            Unit
        }
        every { resolver.releasePersistableUriPermission(any(), any()) } answers {
            val uri = firstArg<Uri>()
            if (armRelease.get() && uri == oldest && Thread.currentThread().name == "evict") {
                events.add("old release entered")
                releaseEntered.countDown()
                check(letReleaseFinish.await(20, TimeUnit.SECONDS)) { "old release gate timed out" }
                events.add("old release committed")
            }
            synchronized(grants) { grants.remove(uri) }
            Unit
        }
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            if (armName.get() && Thread.currentThread().name == "reacquire") {
                events.add("new name query entered")
                nameEntered.countDown()
                assertTrue(letNameFinish.await(20, TimeUnit.SECONDS), "name query gate timed out")
            }
            null
        }

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
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        every { context.contentResolver } returns resolver
        every { context.getSharedPreferences(any(), any()) } returns prefs
        manager = SafStorageManager(context)
        replacement = SafStorageManager(context)
        val seeded = (0..9).map { uri("Seed$it") }
        oldest = seeded[0]
        nextOldest = seeded[1]
        picked = uri("Picked")
        seeded.forEach(manager::persistPermission)
        assertEquals(oldest, manager.getPersistedFolders().last().uri)
        assertEquals(10, synchronized(grants) { grants.size })
    }

    private fun uri(label: String): Uri {
        val text = "content://tree/primary%3A$label"
        val uri = mockk<Uri>()
        every { uri.toString() } returns text
        every { uri.lastPathSegment } returns "primary:$label"
        every { Uri.parse(text) } returns uri
        return uri
    }

    private fun worker(name: String, work: () -> Unit) = Worker(name, work).also { workers.add(it) }

    private fun startEviction(): Worker {
        armRelease.set(true)
        val old = worker("evict") { manager.persistPermission(picked) }
        assertTrue(releaseEntered.await(5, TimeUnit.SECONDS), "eviction did not reach release")
        assertFalse(recent.get().contains(oldest.toString()), "old row was never evicted")
        return old
    }

    private fun assertReacquiredFolderSurvives() {
        assertTrue(replacement.hasPersistedPermission(oldest), "new grant was released: $events")
        val rows = replacement.getPersistedFolders().map { it.uri }
        assertTrue(oldest in rows, "new recent row was lost: $events")
        assertTrue(picked in rows, "the new folder was lost")
        assertFalse(nextOldest in rows, "an actual eviction stopped trimming rows")
        assertFalse(replacement.hasPersistedPermission(nextOldest), "an actual eviction kept its grant")
        assertEquals(10, rows.size)
    }

    @Test
    fun `an old eviction cannot release a folder whose new row is complete`() {
        try {
            val old = startEviction()
            val new = worker("reacquire") {
                replacement.persistPermission(oldest)
                events.add("new row complete")
            }
            assertTrue(new.started.await(5, TimeUnit.SECONDS))
            // A correct global ordering may block before the new take. Do not wait
            // for that take before releasing the operation that owns the ordering.
            if (new.done.await(5, TimeUnit.SECONDS)) {
                new.finish()
                assertTrue(takeSucceeded.count == 0L)
                assertTrue(recent.get().contains(oldest.toString()))
            } else {
                assertEquals(1L, takeSucceeded.count, "take escaped ordering but row did not finish: $events")
            }
            letReleaseFinish.countDown()
            old.finish()
            new.finish()
            assertReacquiredFolderSurvives()
        } finally { finishWorkers() }
    }

    @Test
    fun `a successful new take remains held while its name and row are pending`() {
        try {
            val old = startEviction()
            armName.set(true)
            val new = worker("reacquire") {
                replacement.persistPermission(oldest)
                events.add("new row complete")
            }
            assertTrue(new.started.await(5, TimeUnit.SECONDS))
            if (takeSucceeded.await(5, TimeUnit.SECONDS)) {
                assertTrue(nameEntered.await(5, TimeUnit.SECONDS), "take succeeded but name query was not reached")
                assertFalse(recent.get().contains(oldest.toString()), "the name gate did not precede the row")
                assertFalse(new.done.count == 0L)
            } else {
                assertFalse(new.done.count == 0L, "reacquisition completed without taking a grant")
            }
            letReleaseFinish.countDown()
            old.finish()
            letNameFinish.countDown()
            new.finish()
            assertReacquiredFolderSurvives()
        } finally { finishWorkers() }
    }

    @Test
    fun `a genuinely evicted folder still loses its grant`() {
        manager.persistPermission(picked)
        assertFalse(manager.hasPersistedPermission(oldest))
        assertFalse(oldest in manager.getPersistedFolders().map { it.uri })
        assertTrue(manager.hasPersistedPermission(picked))
        assertEquals(10, manager.getPersistedFolders().size)
    }

    private fun finishWorkers() {
        letReleaseFinish.countDown()
        letNameFinish.countDown()
        workers.forEach { it.thread.join(5_000) }
        workers.filter { it.thread.isAlive }.forEach { it.thread.interrupt() }
        workers.forEach { it.thread.join(5_000) }
        assertTrue(workers.none { it.thread.isAlive }, "a fixture worker did not terminate")
        workers.forEach { it.error.get()?.let { e -> throw AssertionError("${it.name} failed", e) } }
    }

    @AfterEach
    fun tearDown() {
        try { finishWorkers() } finally { unmockkAll() }
    }
}
