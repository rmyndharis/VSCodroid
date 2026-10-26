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
import com.vscodroid.util.StorageManager
import com.vscodroid.bridge.AndroidBridge
import com.vscodroid.bridge.ClipboardBridge
import com.vscodroid.bridge.SecurityManager
import org.json.JSONArray
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SafWorkspaceUsageTest : SafInitialSyncTestBase() {
    private lateinit var manager: SafStorageManager
    private lateinit var replacement: SafStorageManager
    private lateinit var folder: Uri
    private val granted = AtomicBoolean(false)
    private val recent = AtomicReference("[]")
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
            Unit
        }
        every { resolver.releasePersistableUriPermission(any(), any()) } answers { granted.set(false) }
        every { resolver.persistedUriPermissions } answers {
            val snapshot = if (granted.get()) listOf(mockk<UriPermission> {
                every { uri } returns folder
                every { isReadPermission } returns true
                every { isWritePermission } returns true
            }) else emptyList()
            snapshot
        }
        manager = SafStorageManager(context)
        replacement = SafStorageManager(context)
        mirror = manager.getMirrorDir(folder)
        assertTrue(mirror.mkdirs())
        deviceTree(mapOf("root" to listOf(Child("notes.md", contents = "DEVICE"), Child("project.code-workspace", contents = "{\"folders\":[]}"))))
        manager.persistPermission(folder)
        runBlocking { SafSyncEngine(context).initialSync(folder, mirror) { _, _ -> } }
        assertTrue(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror))
        assertTrue(record.isFile)
        assertTrue(manager.hasPersistedPermission(folder))
        assertEquals(setOf(mirror.name, record.name), mirror.parentFile.list()!!.toSet())
    }

    private fun worker(name: String, work: () -> Unit) = Worker(name, work).also { workers.add(it) }

    private fun publicationPath(kind: String): String = when (kind) {
        "direct" -> mirror.absolutePath
        "subdirectory" -> File(mirror, "src").apply { mkdir() }.absolutePath
        "workspace" -> File(mirror, "project.code-workspace").absolutePath
        "named" -> replacement.namedPathFor(mirror, "Project")!!.absolutePath
        else -> error("unknown path kind")
    }

    private fun raceClaim(kind: String) {
        val target = publicationPath(kind)
        val sizeReached = CountDownLatch(1)
        val finishSize = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        val answer = AtomicReference<Long?>()
        mockkObject(StorageManager)
        every { StorageManager.dirSize(any()) } answers {
            val size = callOriginal()
            if (firstArg<File>().absolutePath == mirror.absolutePath &&
                Thread.currentThread().name == "removal" && armed.compareAndSet(true, false)) {
                sizeReached.countDown()
                check(finishSize.await(15, TimeUnit.SECONDS)) { "the real size walk was not released" }
            }
            size
        }
        try {
            val old = worker("removal") { answer.set(manager.reclaimMirror(mirror.name, force = true)) }
            assertTrue(sizeReached.await(5, TimeUnit.SECONDS), "removal did not complete its real size walk")
            val publication = worker("publication") {
                assertTrue(replacement.claimWorkspacePaths(listOf(target)), "publication was rejected before removal committed")
                File(mirror, "retained.txt").writeText("UNIQUE LOCAL BYTES")
            }
            publication.finish()
            finishSize.countDown()
            old.finish()
            assertAll(
                { assertEquals(SafStorageManager.RECLAIM_IN_USE, answer.get()) },
                { assertTrue(mirror.isDirectory) },
                { assertTrue(record.isFile) },
                { assertEquals("DEVICE", File(mirror, "notes.md").readText()) },
                { assertEquals("UNIQUE LOCAL BYTES", File(mirror, "retained.txt").readText()) },
                { assertTrue(replacement.hasPersistedPermission(folder)) },
                { assertTrue(replacement.getPersistedFolders().any { it.uri == folder }) },
                { assertFalse(File(mirror.parentFile, SafStorageManager.DISCARD_PREFIX + mirror.name).exists()) },
            )
        } finally {
            finishSize.countDown()
            workers.forEach { it.thread.join(5_000) }
            unmockkObject(StorageManager)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["direct", "subdirectory", "workspace", "named"])
    fun `a synchronous publication claim wins against a removal still sizing`(kind: String) = raceClaim(kind)

    @Test
    fun `a replacement manager sees ownership retained by an earlier manager`() {
        assertTrue(manager.claimWorkspacePaths(listOf(mirror.absolutePath)))
        assertEquals(SafStorageManager.RECLAIM_IN_USE, replacement.reclaimMirror(mirror.name, force = true))
        assertTrue(mirror.isDirectory)
        assertTrue(replacement.hasPersistedPermission(folder))
    }

    @Test
    fun `a resolved member outside its configuration container is protected through the native bridge`() {
        val config = File(filesDir, "projects/example.code-workspace").apply {
            parentFile.mkdirs()
            writeText("{\"folders\":[{\"path\":\"${mirror.absolutePath}\"}]}")
        }
        val security = mockk<SecurityManager>()
        every { security.validateToken("session") } returns true
        val bridge = AndroidBridge(context, security, mockk<ClipboardBridge>(relaxed = true),
            onBackPressed = { false }, onMinimize = {}, safManager = replacement)
        assertTrue(bridge.claimWorkspacePaths("session", JSONArray(listOf(config.absolutePath, mirror.absolutePath)).toString()))
        assertEquals(SafStorageManager.RECLAIM_IN_USE, manager.reclaimMirror(mirror.name, force = true))
        assertEquals("DEVICE", File(mirror, "notes.md").readText())
    }

    @Test
    fun `a rejected bridge token leaves an unused copy reclaimable`() {
        val security = mockk<SecurityManager>()
        every { security.validateToken("stale") } returns false
        val bridge = AndroidBridge(context, security, mockk<ClipboardBridge>(relaxed = true),
            onBackPressed = { false }, onMinimize = {}, safManager = replacement)
        assertFalse(bridge.claimWorkspacePaths("stale", JSONArray(listOf(mirror.absolutePath)).toString()))
        assertTrue(manager.reclaimMirror(mirror.name, force = true) >= 0)
        assertFalse(mirror.exists())
    }

    @Test
    fun `an unused copy remains removable after an unrelated workspace publication`() {
        val ordinary = File(filesDir, "projects").apply { mkdirs() }
        assertTrue(replacement.claimWorkspacePaths(listOf(ordinary.absolutePath)))
        assertTrue(replacement.claimWorkspacePaths(emptyList()))
        assertTrue(manager.reclaimMirror(mirror.name, force = true) >= 0)
        assertFalse(mirror.exists())
        assertFalse(record.exists())
        assertFalse(replacement.hasPersistedPermission(folder))
    }

    @Test
    fun `a removal that commits first refuses later publication of the missing copy`() {
        assertTrue(manager.reclaimMirror(mirror.name, force = true) >= 0)
        assertFalse(replacement.claimWorkspacePaths(listOf(mirror.absolutePath)))
        assertFalse(mirror.exists())
        assertFalse(replacement.hasPersistedPermission(folder))
    }

    @Test
    fun `closing or abandoning an attempt retains its process ownership until restart`() {
        assertTrue(replacement.claimMirrorOpen(mirror))
        assertTrue(replacement.claimWorkspacePaths(emptyList()))
        assertEquals(SafStorageManager.RECLAIM_IN_USE, manager.reclaimMirror(mirror.name, force = true))
        assertTrue(mirror.isDirectory)
    }

    @Test
    fun `workspace claims never wait behind a removal relocating the upload journal`() {
        val journalLock = SafSyncEngine::class.java.getDeclaredField("uploadJournalLock")
            .apply { isAccessible = true }.get(null)
        val answer = AtomicReference<Long?>()
        synchronized(journalLock) {
            val removal = worker("journal-removal") { answer.set(manager.reclaimMirror(mirror.name, force = true)) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (mirror.exists() && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(mirror.exists(), "removal did not commit its real directory rename before waiting for the journal")
            val claim = worker("publication") {
                assertFalse(replacement.claimWorkspacePaths(listOf(mirror.absolutePath)), "an already removed directory cannot be published")
            }
            claim.finish()
            assertEquals(1L, removal.done.count, "the journal synchronization boundary was not reached")
        }
        workers.first { it.name == "journal-removal" }.finish()
        assertTrue(answer.get()!! >= 0)
        assertFalse(replacement.hasPersistedPermission(folder))
    }

    private fun finishWorkers() {
        workers.forEach { it.thread.join(5_000) }
        workers.filter { it.thread.isAlive }.forEach { it.thread.interrupt() }
        workers.forEach { it.thread.join(5_000) }
        assertTrue(workers.none { it.thread.isAlive }, "a fixture worker did not terminate")
        workers.forEach { it.error.get()?.let { e -> throw AssertionError("${it.name} failed", e) } }
    }

    @AfterEach
    fun finishMirrorWorkers() = finishWorkers()
}
