package com.vscodroid.storage

import android.net.Uri
import android.database.Cursor
import android.os.FileObserver
import android.os.SystemClock
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class SafSharedMutationWindowTest : SafInitialSyncTestBase() {
    @TempDir
    lateinit var secondMirror: File

    @AfterEach
    fun removeSecondRecord() {
        File(secondMirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
    }

    private fun document(identity: String): Uri = mockk<Uri>(relaxed = true).also { uri ->
        every { uri.toString() } returns "content://test/document/$identity"
        every { uri.authority } returns "test"
        every { DocumentsContract.getDocumentId(uri) } returns identity
    }

    private fun mutableDevice(tree: MutableMap<String, List<Child>>) {
        deviceTree(tree)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            document(secondArg())
        }
        every { resolver.query(match { it.toString().startsWith("content://test/document/") }, any(), any(), any(), any()) } answers {
            val identity = DocumentsContract.getDocumentId(firstArg<Uri>())
            val child = tree.values.flatten().firstOrNull { it.id == identity }
            child?.let {
                mockk<Cursor>(relaxed = true) {
                    every { moveToFirst() } returns true
                    every { getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE) } returns 0
                    every { getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns 1
                    every { getColumnIndex(DocumentsContract.Document.COLUMN_SIZE) } returns 2
                    every { isNull(any<Int>()) } returns false
                    every { getString(0) } returns if (it.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
                    every { getLong(1) } returns it.modified
                    every { getLong(2) } returns it.size
                }
            }
        }
        every { resolver.openInputStream(any()) } answers {
            val identity = DocumentsContract.getDocumentId(firstArg<Uri>())
            ByteArrayInputStream(tree.values.flatten().first { it.id == identity }.contents.toByteArray())
        }
        every { resolver.openOutputStream(any(), "wt") } answers {
            val identity = DocumentsContract.getDocumentId(firstArg<Uri>())
            object : ByteArrayOutputStream() {
                override fun close() {
                    for ((parent, children) in tree.toMap()) {
                        tree[parent] = children.map {
                            if (it.id == identity) it.copy(contents = toString("UTF-8"), size = size().toLong()) else it
                        }
                    }
                    super.close()
                }
            }
        }
        every { DocumentsContract.createDocument(resolver, any(), any(), any()) } answers {
            val parent = DocumentsContract.getDocumentId(secondArg<Uri>())
            val name = arg<String>(3)
            val directory = thirdArg<String>() == DocumentsContract.Document.MIME_TYPE_DIR
            val identity = if (parent == "root") name else "$parent/$name"
            tree[parent] = tree.getValue(parent) + Child(name, isDirectory = directory, id = identity)
            if (directory) tree[identity] = emptyList()
            document(identity)
        }
    }

    @Test
    fun `a save retains ownership between device proof and stream admission`() {
        val tree = mutableMapOf("root" to listOf(Child("notes.md", contents = "baseline")))
        mutableDevice(tree)
        var now = 1_000L
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { now }
        val firstTree = named("parent")
        val secondTree = named("child")
        val replacing = SafSyncEngine(context)
        val saving = SafSyncEngine(context)
        runBlocking {
            replacing.initialSync(firstTree, mirror) { _, _ -> }
            saving.initialSync(secondTree, secondMirror) { _, _ -> }
        }
        val first = mirrorFile("notes.md").apply { writeText("first grant content") }
        val second = File(secondMirror, "notes.md").apply { writeText("second grant content") }
        var proofFinished = false
        var attempted = false
        val firstDocument = document("doc:notes.md")
        every { firstDocument.authority } answers {
            if (proofFinished && !attempted) {
                attempted = true
                saving.handleMirrorEvent(FileObserver.MODIFY, second, secondMirror, secondTree)
                saving.runWriteBackLoop { false }
            }
            "test"
        }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            if (firstArg<Uri>() === firstTree && secondArg<String>() == "doc:notes.md") firstDocument else document(secondArg())
        }
        every { resolver.openInputStream(firstDocument) } answers {
            object : ByteArrayInputStream(tree.getValue("root").single().contents.toByteArray()) {
                override fun close() {
                    super.close()
                    proofFinished = true
                }
            }
        }
        replacing.handleMirrorEvent(FileObserver.MODIFY, first, mirror, firstTree)
        replacing.runWriteBackLoop { false }
        assertTrue(attempted, "the proof-to-admission window was not exercised")
        now += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
        assertTrue(saving.retryHeldBack(WatchSession(secondMirror)), "the competing save bypassed the completed proof")
        assertTrue(tree.values.flatten().any { it.contents == "second grant content" })
        assertTrue(secondMirror.listFiles().orEmpty().any {
            it.name.startsWith("notes.md${SafSyncEngine.DEVICE_COPY_SUFFIX}") && it.readText() == "first grant content"
        }, "the retry did not preserve the completed first save")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a directory delete cannot consume a child save from another grant`(create: Boolean) {
        val tree = mutableMapOf("root" to listOf(
            Child("folder", isDirectory = true, id = "folder"),
            Child("sentinel.md", contents = "present"),
        ), "folder" to listOf(Child("known.md", contents = "baseline", id = "folder/known.md")))
        mutableDevice(tree)
        val firstTree = named("parent")
        val secondTree = named("child")
        val deleting = SafSyncEngine(context)
        val saving = SafSyncEngine(context)
        runBlocking {
            deleting.initialSync(firstTree, mirror) { _, _ -> }
            saving.initialSync(secondTree, secondMirror) { _, _ -> }
        }
        val saved = File(secondMirror, if (create) "folder/new.md" else "folder/known.md")
        var attempted = false
        every { DocumentsContract.deleteDocument(resolver, any()) } answers {
            attempted = true
            saved.writeText("unique child work")
            saving.handleMirrorEvent(if (create) FileObserver.CREATE else FileObserver.MODIFY, saved, secondMirror, secondTree)
            saving.runWriteBackLoop { false }
            tree["folder"] = emptyList()
            tree["root"] = tree.getValue("root").filterNot { it.id == "folder" }
            true
        }
        val removed = mirrorFile("folder")
        assertTrue(removed.deleteRecursively())
        deleting.handleMirrorEvent(FileObserver.DELETE or 0x40000000, removed, mirror, firstTree)
        deleting.runWriteBackLoop { false }
        assertTrue(attempted, "the directory was not deleted through the scheduled save window")
        runBlocking { saving.initialSync(secondTree, secondMirror) { _, _ -> } }
        assertTrue(
            saved.isFile && saved.readText() == "unique child work" || tree.values.flatten().any { it.contents == "unique child work" },
            "the child work vanished from both the device and its mirror",
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `an opening upload retains ownership from proof until replacement`(interrupted: Boolean) {
        val tree = mutableMapOf("root" to listOf(Child("notes.md", contents = "baseline")))
        mutableDevice(tree)
        var now = 1_000L
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { now }
        val firstTree = named("parent")
        val secondTree = named("child")
        val opening = SafSyncEngine(context)
        val saving = SafSyncEngine(context)
        runBlocking {
            opening.initialSync(firstTree, mirror) { _, _ -> }
            saving.initialSync(secondTree, secondMirror) { _, _ -> }
        }
        mirrorFile("notes.md").apply { writeText("newer opening content"); setLastModified(1_800_000_000_000L) }
        if (interrupted) File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE).writeText(mirrorFile("notes.md").absolutePath)
        val saved = File(secondMirror, "notes.md").apply { writeText("other grant content") }
        var attempted = false
        every { Logger.i(any(), match {
            it.startsWith("Writing back a newer mirror copy: notes.md") || it.startsWith("Kept the mirror of notes.md:")
        }) } answers {
            if (!attempted) {
                attempted = true
                saving.handleMirrorEvent(FileObserver.MODIFY, saved, secondMirror, secondTree)
                saving.runWriteBackLoop { false }
            }
        }
        runBlocking { opening.initialSync(firstTree, mirror) { _, _ -> } }
        assertTrue(attempted, "the opening upload did not reach its replacement window")
        now += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
        assertTrue(saving.retryHeldBack(WatchSession(secondMirror)), "the competing save was allowed past the pending replacement")
        assertTrue(tree.values.flatten().any { it.contents == "other grant content" })
        assertTrue(secondMirror.listFiles().orEmpty().any {
            it.name.startsWith("notes.md${SafSyncEngine.DEVICE_COPY_SUFFIX}") && it.readText() == "newer opening content"
        }, "the eventual save did not preserve the completed opening upload")
    }

    @Test
    fun `a save from another grant remains recoverable across a pending delete`() {
        val tree = mutableMapOf("root" to listOf(
            Child("notes.md", contents = "baseline"),
            Child("sentinel.md", contents = "keep the enumeration nonempty"),
        ))
        deviceTree(tree)
        val firstTree = named("parent")
        val secondTree = named("child")
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            val identity = secondArg<String>()
            mockk<Uri>(relaxed = true).also { uri ->
                every { uri.toString() } returns "content://test/tree/${firstArg<Uri>()}/document/$identity"
                every { uri.authority } returns "test"
                every { DocumentsContract.getDocumentId(uri) } returns identity
            }
        }
        every { resolver.openInputStream(any()) } answers {
            val identity = DocumentsContract.getDocumentId(firstArg<Uri>())
            val child = tree.values.flatten().first { it.id == identity }
            ByteArrayInputStream(child.contents.toByteArray())
        }
        every { resolver.openOutputStream(any(), "wt") } answers {
            val identity = DocumentsContract.getDocumentId(firstArg<Uri>())
            object : ByteArrayOutputStream() {
                override fun close() {
                    tree["root"] = tree.getValue("root").map {
                        if (it.id == identity) it.copy(contents = toString("UTF-8"), size = size().toLong()) else it
                    }
                    super.close()
                }
            }
        }
        val deleting = SafSyncEngine(context)
        val saving = SafSyncEngine(context)
        runBlocking {
            deleting.initialSync(firstTree, mirror) { _, _ -> }
            saving.initialSync(secondTree, secondMirror) { _, _ -> }
        }
        val deleted = mirrorFile("notes.md")
        val saved = File(secondMirror, "notes.md")
        saved.writeText("unique work from the other grant")
        var saveAttempted = false
        every { DocumentsContract.deleteDocument(resolver, any()) } answers {
            saveAttempted = true
            saving.handleMirrorEvent(FileObserver.MODIFY, saved, secondMirror, secondTree)
            saving.runWriteBackLoop { false }
            val identity = DocumentsContract.getDocumentId(secondArg<Uri>())
            tree["root"] = tree.getValue("root").filterNot { it.id == identity }
            true
        }
        assertTrue(deleted.delete())
        deleting.handleMirrorEvent(FileObserver.DELETE, deleted, mirror, firstTree)
        deleting.runWriteBackLoop { false }
        assertTrue(saveAttempted, "the delete did not reach the scheduled save window")
        runBlocking { saving.initialSync(secondTree, secondMirror) { _, _ -> } }
        assertTrue(
            saved.isFile && saved.readText() == "unique work from the other grant" ||
                tree.values.flatten().any { it.contents == "unique work from the other grant" },
            "the completed save vanished from both the device and its mirror",
        )
    }
}
