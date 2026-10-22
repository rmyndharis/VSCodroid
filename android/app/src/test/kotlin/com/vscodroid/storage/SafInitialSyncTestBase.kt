package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A device folder with real structure behind a [ContentResolver], and an `initialSync` to
 * drive over it.
 *
 * The device is a map from each parent's document id (`root` for the tree itself) to its
 * children, which is enough to describe a folder, a file of the other kind under the same
 * name, or a document that is gone. Created documents and what they were written with are
 * recorded by name, and the notices the sync raises are collected, so a case asserts on
 * what the sync left behind and not on which calls it made.
 */
abstract class SafInitialSyncTestBase {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    protected lateinit var resolver: ContentResolver
    protected lateinit var context: Context

    /** Every `createDocument` the sync performed, as (mime type, display name). */
    protected val created = mutableListOf<Pair<String, String>>()

    /** What each created document was written with, by document URI. */
    protected val written = mutableMapOf<String, ByteArrayOutputStream>()

    /** Files the sync said exist only inside the app, by name. */
    protected val lost = mutableListOf<String>()

    /** How many documents the last sync reported as not copied across. */
    protected var notCopied = 0

    protected val record: File get() = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)

    protected data class Child(
        val name: String,
        val isDirectory: Boolean = false,
        val contents: String = "",
    )

    @BeforeEach
    fun setUpDevice() {
        created.clear()
        written.clear()

        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            named("children:${secondArg<String>()}")
        }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            named("doc-uri:${secondArg<String>()}")
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            firstArg<Uri>().toString().removePrefix("doc-uri:")
        }
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            created.add(thirdArg<String>() to arg<String>(3))
            named("doc-uri:doc:${arg<String>(3)}")
        }

        resolver = mockk(relaxed = true)
        every { resolver.openOutputStream(any(), "wt") } answers {
            written.getOrPut(firstArg<Uri>().toString()) { ByteArrayOutputStream() }
        }
        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
    }

    @AfterEach
    fun tearDownDevice() {
        record.delete()
        unmockkAll()
    }

    protected fun named(text: String): Uri =
        mockk<Uri>(relaxed = true).also { every { it.toString() } returns text }

    /**
     * [loading] names the parents whose listing the provider flags as still loading, which
     * is how a provider backed by a network says the rows so far are not all of them.
     */
    protected fun deviceTree(tree: Map<String, List<Child>?>, loading: Set<String> = emptySet()) {
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val parent = firstArg<Uri>().toString().removePrefix("children:")
            tree[parent]?.let { cursorOver(it, parent in loading) }
        }
        every { resolver.openInputStream(any()) } answers {
            val docId = firstArg<Uri>().toString().removePrefix("doc-uri:")
            val child = tree.values.filterNotNull().flatten()
                .firstOrNull { "doc:${it.name}" == docId }
            ByteArrayInputStream((child?.contents ?: "").toByteArray())
        }
    }

    private fun cursorOver(children: List<Child>, loading: Boolean): Cursor {
        var row = -1
        return mockk<Cursor>(relaxed = true) {
            every { extras } returns if (loading) {
                mockk<Bundle>(relaxed = true) {
                    every { getBoolean(DocumentsContract.EXTRA_LOADING) } returns true
                    every { containsKey(DocumentsContract.EXTRA_ERROR) } returns false
                }
            } else {
                null
            }
            every { moveToNext() } answers { ++row < children.size }
            every { getColumnIndexOrThrow(any()) } answers {
                when (firstArg<String>()) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                    else -> 3
                }
            }
            every { getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns 4
            every { getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE) } returns 2
            every { isNull(any<Int>()) } returns false
            every { getString(0) } answers { "doc:${children[row].name}" }
            every { getString(1) } answers { children[row].name }
            every { getString(2) } answers {
                if (children[row].isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                else "text/plain"
            }
            every { getLong(3) } answers { children[row].contents.toByteArray().size.toLong() }
            every { getLong(4) } returns DEVICE_TIME
        }
    }

    protected fun sync() = runBlocking {
        lost.clear()
        notCopied = 0
        val engine = SafSyncEngine(context)
        engine.onWriteBackFailed = { lost += it.name }
        engine.onDocumentsNotCopied = { count, _ -> notCopied = count }
        engine.initialSync(named("tree"), mirror) { _, _ -> }
    }

    protected fun mirrorFile(name: String) = File(mirror, name)

    protected companion object {
        /** Old enough that no mirror file is ever kept merely for being newer. */
        const val DEVICE_TIME = 1_700_000_000_000L
    }
}
