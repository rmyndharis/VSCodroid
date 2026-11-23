package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver
import android.os.SystemClock
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SafWalkTreeAndObserverTest {

    @TempDir
    lateinit var root: File

    private lateinit var mirror: File
    private lateinit var filesDir: File
    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri
    private val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private data class Row(val id: String, val name: String, val isDir: Boolean = false)

    @BeforeEach
    fun setUp() {
        warnings.clear()
        mirror = File(root, "mirror").apply { mkdirs() }
        filesDir = File(root, "files").apply { mkdirs() }

        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } answers { warnings += secondArg<String>() }
        every { Logger.w(any(), any(), any()) } answers { warnings += secondArg<String>() }
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L

        treeUri = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "tree:test"
            every { it.authority } returns "com.vscodroid.test.observer"
        }

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            mockk<Uri>(relaxed = true).also {
                every { it.toString() } returns "children:${secondArg<String>()}"
                every { it.authority } returns "com.vscodroid.test.observer"
            }
        }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            mockk<Uri>(relaxed = true).also {
                every { it.toString() } returns "doc:${secondArg<String>()}"
                every { it.authority } returns "com.vscodroid.test.observer"
            }
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            firstArg<Uri>().toString().removePrefix("doc:")
        }

        resolver = mockk(relaxed = true)
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream(byteArrayOf())
        }
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
    }

    @AfterEach
    fun tearDown() {
        engine.shutdown()
        unmockkAll()
    }

    private fun cursorFor(
        rows: List<Row>,
        openCount: AtomicInteger? = null,
        maxOpen: AtomicInteger? = null,
    ): Cursor {
        openCount?.let { current ->
            val now = current.incrementAndGet()
            maxOpen?.accumulateAndGet(now, ::maxOf)
        }
        var closed = false
        var index = -1
        return mockk<Cursor>(relaxed = true) {
            every { extras } returns null
            every { getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
            every { getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
            every { getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE) } returns 2
            every { getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE) } returns 3
            every { getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns -1
            every { moveToNext() } answers { ++index < rows.size }
            every { getString(0) } answers { rows[index].id }
            every { getString(1) } answers { rows[index].name }
            every { getString(2) } answers {
                if (rows[index].isDir) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
            }
            every { getLong(3) } returns 10L
            every { close() } answers {
                if (!closed) {
                    closed = true
                    openCount?.decrementAndGet()
                }
            }
        }
    }

    private fun invokeWalkTree(
        result: MutableList<DocumentInfo>,
        skipped: MutableList<String>,
    ): Boolean = SafSyncEngine::class.java
        .getDeclaredMethod(
            "walkTree",
            Uri::class.java,
            String::class.java,
            String::class.java,
            MutableList::class.java,
            MutableList::class.java,
            Set::class.java,
        )
        .apply { isAccessible = true }
        .invoke(engine, treeUri, "root", "", result, skipped, emptySet<String>()) as Boolean

    private fun cachedDocId(relativePath: String): String? =
        SafSyncEngine::class.java
            .getDeclaredMethod("cachedDocId", Uri::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(engine, treeUri, relativePath) as String?

    @Test
    fun `walkTree closes each directory cursor before recursing into subdirectories`() {
        val tree = mapOf(
            "root" to listOf(Row("d0", "d0", isDir = true)),
            "d0" to listOf(Row("d1", "d1", isDir = true)),
            "d1" to listOf(Row("d2", "d2", isDir = true)),
            "d2" to listOf(Row("d3", "d3", isDir = true)),
            "d3" to listOf(Row("d4", "d4", isDir = true)),
            "d4" to listOf(Row("f", "leaf.txt", isDir = false)),
        )
        val openCursors = AtomicInteger(0)
        val maxOpenCursors = AtomicInteger(0)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val parent = firstArg<Uri>().toString().removePrefix("children:")
            cursorFor(tree[parent].orEmpty(), openCursors, maxOpenCursors)
        }

        engine.resetCache(treeUri)
        val result = mutableListOf<DocumentInfo>()
        val skipped = mutableListOf<String>()
        val complete = invokeWalkTree(result, skipped)

        assertTrue(complete)
        assertEquals(0, openCursors.get(), "every cursor must be closed when walkTree returns")
        assertEquals(
            1,
            maxOpenCursors.get(),
            "walkTree kept ${maxOpenCursors.get()} cursors open simultaneously while recursing",
        )
        assertEquals(
            listOf("d0", "d0/d1", "d0/d1/d2", "d0/d1/d2/d3", "d0/d1/d2/d3/d4", "d0/d1/d2/d3/d4/leaf.txt"),
            result.map { it.relativePath },
        )
    }

    @Test
    fun `walkTree leaves duplicate names out of docIdCache and never descends into duplicated directories`() {
        val queriedParents = mutableListOf<String>()
        val tree = mapOf(
            "root" to listOf(
                Row("dup-dir-1", "dup", isDir = true),
                Row("ok-file", "ok.txt", isDir = false),
                Row("dup-dir-2", "dup", isDir = true),
                Row("dup-file-1", "same.txt", isDir = false),
                Row("dup-file-2", "same.txt", isDir = false),
            ),
            "dup-dir-1" to listOf(Row("secret-child", "inside.txt", isDir = false)),
            "dup-dir-2" to listOf(Row("other-child", "other.txt", isDir = false)),
        )
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val parent = firstArg<Uri>().toString().removePrefix("children:")
            queriedParents += parent
            cursorFor(tree[parent].orEmpty())
        }

        engine.resetCache(treeUri)
        val result = mutableListOf<DocumentInfo>()
        val skipped = mutableListOf<String>()
        val complete = invokeWalkTree(result, skipped)

        assertFalse(complete)
        assertEquals(listOf("ok.txt"), result.map { it.relativePath })
        assertEquals(listOf("dup", "same.txt"), skipped)
        assertEquals("ok-file", cachedDocId("ok.txt"))
        assertNull(cachedDocId("dup"), "duplicate directory name remained poisoned in docIdCache")
        assertNull(cachedDocId("dup/inside.txt"), "child of duplicate directory remained poisoned in docIdCache")
        assertNull(cachedDocId("same.txt"), "duplicate file name remained poisoned in docIdCache")
        assertFalse(
            "dup-dir-1" in queriedParents || "dup-dir-2" in queriedParents,
            "walkTree descended into a duplicated directory name: $queriedParents",
        )
    }

    @Test
    fun `directory observer callback does not block the observer thread on slow provider queries`() {
        val providerEntered = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            providerEntered.countDown()
            releaseProvider.await(5, TimeUnit.SECONDS)
            cursorFor(listOf(Row("doc:notes.txt", "notes.txt")))
        }

        var deliver: ((Int, String?) -> Unit)? = null
        engine.directoryWatchFactory = { _, cb ->
            deliver = cb
            object : OpenWatch {
                override fun start() = Unit
                override fun stop() = Unit
            }
        }
        File(mirror, "notes.txt").writeText("edited")

        SafSyncEngine::class.java.getDeclaredField("isWatching")
            .apply { isAccessible = true }
            .setBoolean(engine, true)
        SafSyncEngine::class.java
            .getDeclaredMethod("watchTree", File::class.java, File::class.java, Uri::class.java, WatchSession::class.java)
            .apply { isAccessible = true }
            .invoke(engine, mirror, mirror, treeUri, engine.session)

        val callback = checkNotNull(deliver)
        val callbackReturned = CountDownLatch(1)
        val observerThread = Thread {
            callback(FileObserver.MODIFY, "notes.txt")
            callbackReturned.countDown()
        }.apply { isDaemon = true; start() }

        try {
            assertTrue(
                callbackReturned.await(500, TimeUnit.MILLISECONDS),
                "DirectoryObserver callback blocked the observer thread while waiting on ContentResolver.query",
            )
            assertTrue(providerEntered.await(2, TimeUnit.SECONDS), "dispatched handler never reached the provider")
        } finally {
            releaseProvider.countDown()
            observerThread.join(2_000)
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (engine.session.queue.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(
            listOf(File(mirror, "notes.txt").absolutePath),
            engine.session.queue.map { it.localPath },
            "dispatched event did not enqueue its write-back job",
        )
    }

    private fun enableLocalSecureDirectoryStreamIfNeeded() {
        mockkStatic(Files::class)
        every { Files.newDirectoryStream(any<Path>()) } answers {
            val dir = firstArg<Path>()
            val real = callOriginal()
            if (real is SecureDirectoryStream<Path>) real else LocalSecureDirectoryStream(dir, real)
        }
    }

    @Test
    fun `IN_Q_OVERFLOW with null path during active watch creates nested directories, updates modified files, and prunes deleted watches`() {
        enableLocalSecureDirectoryStreamIfNeeded()
        val unchanged = File(mirror, "unchanged.txt").apply { writeText("same") }
        val modified = File(mirror, "modified.txt").apply { writeText("before") }
        val removedDir = File(mirror, "removed_dir").apply { mkdirs() }
        val digest = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val recordFile = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        recordFile.writeText(
            listOf(
                SafSyncEngine.RECORD_HEADER,
                engine.identityLine("unchanged.txt", unchanged) + "\t$digest",
                engine.identityLine("modified.txt", modified) + "\t$digest",
            ).joinToString("\n"),
        )

        val childrenByParent = ConcurrentHashMap<String, MutableList<Row>>()
        childrenByParent["root"] = Collections.synchronizedList(
            mutableListOf(
                Row("unchanged.txt", "unchanged.txt"),
                Row("modified.txt", "modified.txt"),
            ),
        )
        val writtenBodies = ConcurrentHashMap<String, String>()
        val createdDocs = Collections.synchronizedList(mutableListOf<Triple<String, String, String>>())
        val writeBackFailures = Collections.synchronizedList(mutableListOf<String>())
        engine.onWriteBackFailed = { writeBackFailures += it.absolutePath }

        try {
            every { resolver.query(any(), any(), any(), any(), any()) } answers {
                val uri = firstArg<Uri>().toString()
                val projection = secondArg<Array<String>?>()
                if (uri.startsWith("children:")) {
                    val parentId = uri.removePrefix("children:")
                    cursorFor(childrenByParent[parentId].orEmpty().toList())
                } else if (projection != null && projection.contains(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) {
                    val docId = uri.removePrefix("doc:")
                    val displayName = docId.substringAfterLast('/')
                    mockk<Cursor>(relaxed = true) {
                        every { moveToFirst() } returns true
                        every { getString(0) } returns displayName
                    }
                } else {
                    null
                }
            }
            every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
                val parentId = secondArg<Uri>().toString().removePrefix("doc:")
                val mime = thirdArg<String>()
                val name = arg<String>(3)
                val newId = if (parentId == "root") name else "$parentId/$name"
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                childrenByParent.computeIfAbsent(parentId) { Collections.synchronizedList(mutableListOf()) }
                    .add(Row(newId, name, isDir))
                if (isDir) childrenByParent.computeIfAbsent(newId) { Collections.synchronizedList(mutableListOf()) }
                createdDocs += Triple(parentId, mime, name)
                mockk<Uri>(relaxed = true).also {
                    every { it.toString() } returns "doc:$newId"
                    every { it.authority } returns "com.vscodroid.test.observer"
                }
            }
            every { resolver.openOutputStream(any(), "wt") } answers {
                val docId = firstArg<Uri>().toString().removePrefix("doc:")
                object : ByteArrayOutputStream() {
                    override fun close() {
                        super.close()
                        writtenBodies[docId] = toString(Charsets.UTF_8.name())
                    }
                }
            }

            val watchedDirs = Collections.synchronizedSet(mutableSetOf<File>())
            var rootDeliver: ((Int, String?) -> Unit)? = null
            engine.directoryWatchFactory = { dir, cb ->
                if (dir == mirror) rootDeliver = cb
                object : OpenWatch {
                    override fun start() { watchedDirs.add(dir) }
                    override fun stop() { watchedDirs.remove(dir) }
                }
            }
            engine.resetCache(treeUri)
            SafSyncEngine::class.java.getDeclaredField("isWatching")
                .apply { isAccessible = true }
                .setBoolean(engine, true)
            SafSyncEngine::class.java
                .getDeclaredMethod("watchTree", File::class.java, File::class.java, Uri::class.java, WatchSession::class.java)
                .apply { isAccessible = true }
                .invoke(engine, mirror, mirror, treeUri, engine.session)
            assertTrue(removedDir in watchedDirs, "setup: removed_dir was not watched initially")

            // Simulate filesystem changes whose inotify events were dropped by an overflow.
            assertTrue(removedDir.delete())
            val newDir = File(mirror, "missed_dir").apply { mkdirs() }
            File(newDir, "nested.txt").writeText("created inside missed dir")
            modified.writeText("edited during overflow")

            checkNotNull(rootDeliver).invoke(SafSyncEngine.IN_Q_OVERFLOW, null)

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (
                (newDir !in watchedDirs ||
                    removedDir in watchedDirs ||
                    engine.session.queue.none { it.localPath == newDir.absolutePath } ||
                    engine.session.queue.none { it.localPath == modified.absolutePath }) &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(10)
            }

            assertTrue(
                warnings.any { it.contains("overflow", ignoreCase = true) },
                "IN_Q_OVERFLOW was dropped without logging a warning: $warnings",
            )
            assertTrue(newDir in watchedDirs, "overflow reconciliation did not watch newly created directory")
            assertFalse(removedDir in watchedDirs, "overflow reconciliation leaked watch for deleted directory")
            assertEquals(
                setOf(newDir.absolutePath, modified.absolutePath),
                engine.session.queue.map { it.localPath }.toSet(),
                "overflow reconciliation should queue the new directory subtree and modified file only",
            )

            engine.runWriteBackLoop { false }

            assertTrue(
                createdDocs.any { it.first == "root" && it.second == DocumentsContract.Document.MIME_TYPE_DIR && it.third == "missed_dir" },
                "newly created directory was not created on SAF: $createdDocs",
            )
            assertTrue(
                createdDocs.any { it.first == "missed_dir" && it.third == "nested.txt" },
                "nested file inside newly created directory was not created on SAF: $createdDocs",
            )
            assertEquals("created inside missed dir", writtenBodies["missed_dir/nested.txt"])
            assertEquals("edited during overflow", writtenBodies["modified.txt"])
            assertFalse(writtenBodies.containsKey("unchanged.txt"), "unchanged file should not be rewritten")
            assertTrue(writeBackFailures.isEmpty(), "overflow write-back reported failures: $writeBackFailures")
        } finally {
            recordFile.delete()
        }
    }

    @Test
    fun `observer dispatch queue saturation escalates to overflow reconciliation without dropping edits`() {
        val names = listOf("a.txt", "b.txt", "c.txt", "d.txt", "e.txt")
        val files = names.map { File(mirror, it).apply { writeText("before-$it") } }
        val digest = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
        val recordFile = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        recordFile.writeText(
            (listOf(SafSyncEngine.RECORD_HEADER) + files.map { engine.identityLine(it.name, it) + "\t$digest" })
                .joinToString("\n"),
        )
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val queryCount = AtomicInteger(0)

        try {
            every { resolver.query(any(), any(), any(), any(), any()) } answers {
                if (queryCount.getAndIncrement() == 0) {
                    firstEntered.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
                cursorFor(names.map { Row("doc:$it", it) })
            }

            var rootDeliver: ((Int, String?) -> Unit)? = null
            engine.observerQueueCapacity = 2
            engine.directoryWatchFactory = { dir, cb ->
                if (dir == mirror) rootDeliver = cb
                object : OpenWatch {
                    override fun start() = Unit
                    override fun stop() = Unit
                }
            }
            SafSyncEngine::class.java.getDeclaredField("isWatching")
                .apply { isAccessible = true }
                .setBoolean(engine, true)
            SafSyncEngine::class.java
                .getDeclaredMethod("watchTree", File::class.java, File::class.java, Uri::class.java, WatchSession::class.java)
                .apply { isAccessible = true }
                .invoke(engine, mirror, mirror, treeUri, engine.session)

            files.forEach { it.writeText("after-${it.name}") }

            val deliver = checkNotNull(rootDeliver)
            deliver(FileObserver.MODIFY, "a.txt")
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS), "first event did not start on observer dispatcher")

            // With capacity 2, b.txt and c.txt fill the queue while d.txt and e.txt saturate it.
            deliver(FileObserver.MODIFY, "b.txt")
            deliver(FileObserver.MODIFY, "c.txt")
            deliver(FileObserver.MODIFY, "d.txt")
            deliver(FileObserver.MODIFY, "e.txt")

            assertTrue(engine.session.overflowed, "queue saturation did not mark session overflowed")
            releaseFirst.countDown()

            val expectedPaths = files.map { it.absolutePath }.toSet()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!engine.session.queue.map { it.localPath }.toSet().containsAll(expectedPaths) &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(10)
            }

            assertEquals(
                expectedPaths,
                engine.session.queue.map { it.localPath }.toSet(),
                "overflow reconciliation after observer queue saturation did not recover saturated edits",
            )
            assertTrue(
                warnings.any { it.contains("saturated", ignoreCase = true) },
                "observer queue saturation did not log a warning: $warnings",
            )
        } finally {
            releaseFirst.countDown()
            recordFile.delete()
        }
    }

    @Test
    fun `stopWatching drains already queued observer events into write-back instead of dropping them`() {
        enableLocalSecureDirectoryStreamIfNeeded()
        val file = File(mirror, "queued_before_stop.txt").apply { writeText("saved right before stop") }
        val queryEntered = CountDownLatch(1)
        val allowQueryFinish = CountDownLatch(1)
        val written = ConcurrentHashMap<String, String>()

        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val uri = firstArg<Uri>().toString()
            if (uri.startsWith("children:")) {
                queryEntered.countDown()
                allowQueryFinish.await(5, TimeUnit.SECONDS)
                cursorFor(listOf(Row("queued_before_stop.txt", "queued_before_stop.txt")))
            } else {
                null
            }
        }
        every { resolver.openOutputStream(any(), "wt") } answers {
            val docId = firstArg<Uri>().toString().removePrefix("doc:")
            object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    written[docId] = toString(Charsets.UTF_8.name())
                }
            }
        }

        var rootDeliver: ((Int, String?) -> Unit)? = null
        engine.directoryWatchFactory = { dir, cb ->
            if (dir == mirror) rootDeliver = cb
            object : OpenWatch {
                override fun start() = Unit
                override fun stop() = Unit
            }
        }
        engine.startWatching(mirror, treeUri)

        checkNotNull(rootDeliver).invoke(FileObserver.MODIFY, file.name)
        assertTrue(queryEntered.await(2, TimeUnit.SECONDS), "event never entered observer dispatcher")

        // Release the in-flight observer lookup shortly after stopWatching begins so stopWatching
        // must wait for the observer dispatcher to finish enqueueing before stopping write-back.
        val releaser = Thread {
            Thread.sleep(80)
            allowQueryFinish.countDown()
        }.apply { isDaemon = true; start() }

        try {
            engine.stopWatching()
        } finally {
            allowQueryFinish.countDown()
            releaser.join(2_000)
        }

        assertEquals(
            "saved right before stop",
            written["queued_before_stop.txt"],
            "stopWatching dropped an event that was already queued on the observer dispatcher",
        )
    }

    @Test
    fun `pauseWatching drains already queued observer events before switching to paused recording`() {
        enableLocalSecureDirectoryStreamIfNeeded()
        val beforePause = File(mirror, "before_pause.txt").apply { writeText("saved before pause") }
        val duringPause = File(mirror, "during_pause.txt").apply { writeText("saved during pause") }
        val queryEntered = CountDownLatch(1)
        val allowQueryFinish = CountDownLatch(1)
        val written = ConcurrentHashMap<String, String>()

        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val uri = firstArg<Uri>().toString()
            if (uri.startsWith("children:")) {
                queryEntered.countDown()
                allowQueryFinish.await(5, TimeUnit.SECONDS)
                cursorFor(
                    listOf(
                        Row("before_pause.txt", "before_pause.txt"),
                        Row("during_pause.txt", "during_pause.txt"),
                    ),
                )
            } else {
                null
            }
        }
        every { resolver.openOutputStream(any(), "wt") } answers {
            val docId = firstArg<Uri>().toString().removePrefix("doc:")
            object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    written[docId] = toString(Charsets.UTF_8.name())
                }
            }
        }

        var rootDeliver: ((Int, String?) -> Unit)? = null
        engine.directoryWatchFactory = { dir, cb ->
            if (dir == mirror) rootDeliver = cb
            object : OpenWatch {
                override fun start() = Unit
                override fun stop() = Unit
            }
        }
        engine.startWatching(mirror, treeUri)

        checkNotNull(rootDeliver).invoke(FileObserver.MODIFY, beforePause.name)
        assertTrue(queryEntered.await(2, TimeUnit.SECONDS), "event never entered observer dispatcher")

        val releaser = Thread {
            Thread.sleep(80)
            allowQueryFinish.countDown()
        }.apply { isDaemon = true; start() }

        try {
            engine.pauseWatching()
            // Event delivered after pauseWatching goes into paused recording, not live write-back.
            checkNotNull(rootDeliver).invoke(FileObserver.MODIFY, duringPause.name)
        } finally {
            allowQueryFinish.countDown()
            releaser.join(2_000)
        }

        assertEquals(
            "saved before pause",
            written["before_pause.txt"],
            "pauseWatching dropped an event queued on the observer dispatcher before pause",
        )
        assertFalse(
            written.containsKey("during_pause.txt"),
            "an event delivered while paused should wait for resume rather than writing immediately",
        )
    }

    @Test
    fun `stalled observer task from a previous session does not block events after switching folders`() {
        enableLocalSecureDirectoryStreamIfNeeded()
        val mirror1 = File(root, "mirror1").apply { mkdirs() }
        val mirror2 = File(root, "mirror2").apply { mkdirs() }
        val tree1 = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "tree:one"
            every { it.authority } returns "com.vscodroid.test.observer"
        }
        val tree2 = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "tree:two"
            every { it.authority } returns "com.vscodroid.test.observer"
        }
        every { DocumentsContract.getTreeDocumentId(tree1) } returns "root1"
        every { DocumentsContract.getTreeDocumentId(tree2) } returns "root2"

        File(mirror1, "hung.txt").writeText("stalled")
        val fresh = File(mirror2, "fresh.txt").apply { writeText("second folder") }

        val stalledEntered = CountDownLatch(1)
        val releaseStalled = CountDownLatch(1)
        val stalledExited = CountDownLatch(1)
        val written2 = CountDownLatch(1)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val uri = firstArg<Uri>().toString()
            if (uri == "children:root1") {
                stalledEntered.countDown()
                try {
                    // Ignore interrupt during await to simulate an uninterruptible Binder IPC stall.
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < end) {
                        try {
                            if (releaseStalled.await(100, TimeUnit.MILLISECONDS)) break
                        } catch (_: InterruptedException) {
                            // Keep blocking until releaseStalled fires.
                        }
                    }
                    cursorFor(listOf(Row("hung.txt", "hung.txt")))
                } finally {
                    stalledExited.countDown()
                }
            } else {
                cursorFor(listOf(Row("fresh.txt", "fresh.txt")))
            }
        }
        every { resolver.openOutputStream(any(), "wt") } answers {
            if (firstArg<Uri>().toString() == "doc:fresh.txt") written2.countDown()
            ByteArrayOutputStream()
        }

        val delivers = ConcurrentHashMap<File, (Int, String?) -> Unit>()
        engine.observerDrainGraceMs = 150L
        engine.directoryWatchFactory = { dir, cb ->
            delivers[dir] = cb
            object : OpenWatch {
                override fun start() = Unit
                override fun stop() = Unit
            }
        }

        try {
            engine.startWatching(mirror1, tree1)
            checkNotNull(delivers[mirror1]).invoke(FileObserver.MODIFY, "hung.txt")
            assertTrue(stalledEntered.await(2, TimeUnit.SECONDS), "session 1 event never stalled in provider")

            // Switch to folder 2 while session 1's observer thread remains blocked in provider IPC.
            engine.startWatching(mirror2, tree2)

            val session2 = engine.session
            checkNotNull(delivers[mirror2]).invoke(FileObserver.MODIFY, fresh.name)

            assertTrue(
                written2.await(2, TimeUnit.SECONDS) ||
                    session2.queue.any { it.localPath == fresh.absolutePath },
                "session 2 event was blocked by session 1's stalled observer task",
            )
        } finally {
            releaseStalled.countDown()
            stalledExited.await(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `IN_Q_OVERFLOW with null path during open recording marks replay incomplete`() {
        var rootDeliver: ((Int, String?) -> Unit)? = null
        engine.directoryWatchFactory = { dir, cb ->
            if (dir == mirror) rootDeliver = cb
            object : OpenWatch {
                override fun start() = Unit
                override fun stop() = Unit
            }
        }
        val changes = SafSyncEngine::class.java
            .getDeclaredMethod("watchChangesDuringOpen", File::class.java)
            .apply { isAccessible = true }
            .invoke(engine, mirror) as SafSyncEngine.ChangesDuringOpen

        checkNotNull(rootDeliver).invoke(SafSyncEngine.IN_Q_OVERFLOW, null)

        val complete = engine.replayChangesDuringOpen(changes, mirror, treeUri)
        assertFalse(complete, "an overflowed open recording must report incomplete replay")
        assertTrue(
            warnings.any { it.contains("overflow", ignoreCase = true) },
            "IN_Q_OVERFLOW during open recording did not log a warning: $warnings",
        )
    }

    private class LocalSecureDirectoryStream(
        private val dir: Path,
        private val delegate: DirectoryStream<Path>,
    ) : SecureDirectoryStream<Path> {
        override fun iterator(): MutableIterator<Path> = delegate.iterator()
        override fun close() = delegate.close()
        override fun newDirectoryStream(path: Path, vararg options: LinkOption): SecureDirectoryStream<Path> {
            val child = dir.resolve(path)
            if (LinkOption.NOFOLLOW_LINKS in options && Files.isSymbolicLink(child)) {
                throw IOException("Refused symlink $child")
            }
            return LocalSecureDirectoryStream(child, Files.newDirectoryStream(child))
        }
        override fun newByteChannel(
            path: Path,
            options: MutableSet<out OpenOption>,
            vararg attrs: FileAttribute<*>,
        ): SeekableByteChannel = Files.newByteChannel(dir.resolve(path), options, *attrs)
        override fun deleteFile(path: Path) = Files.delete(dir.resolve(path))
        override fun deleteDirectory(path: Path) = Files.delete(dir.resolve(path))
        override fun move(srcpath: Path, targetdir: SecureDirectoryStream<Path>, targetpath: Path) {
            val targetRoot = (targetdir as LocalSecureDirectoryStream).dir
            Files.move(dir.resolve(srcpath), targetRoot.resolve(targetpath))
        }
        override fun <V : FileAttributeView> getFileAttributeView(type: Class<V>): V =
            Files.getFileAttributeView(dir, type, LinkOption.NOFOLLOW_LINKS)
        override fun <V : FileAttributeView> getFileAttributeView(
            path: Path,
            type: Class<V>,
            vararg options: LinkOption,
        ): V = Files.getFileAttributeView(dir.resolve(path), type, *options)
    }
}
