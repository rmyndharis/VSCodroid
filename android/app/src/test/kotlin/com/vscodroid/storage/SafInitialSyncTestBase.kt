package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
import java.security.MessageDigest

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

    /** The counts of files an open said did not reach the device folder, one entry per notice. */
    protected val didNotReach = mutableListOf<Int>()

    /** The files an open said it keeps inside the app, as (count, first file) per notice. */
    protected val keptInApp = mutableListOf<Pair<Int, String>>()

    /** The `.local-` copies an open said it kept beside their files, by name. */
    protected val localCopies = mutableListOf<String>()

    protected val record: File get() = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)

    /** The mark beside [record] that an open of this build sorted the files only the app holds. */
    protected val sortedMark: File get() = File(mirror.path + SafSyncEngine.SORTED_RECORD_SUFFIX)

    protected data class Child(
        val name: String,
        val isDirectory: Boolean = false,
        val contents: String = "",
        val modified: Long = DEVICE_TIME,
        val size: Long = contents.toByteArray().size.toLong(),
        val id: String = "doc:$name",
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
        sortedMark.delete()
        unmockkAll()
    }

    protected fun named(text: String): Uri =
        mockk<Uri>(relaxed = true).also { every { it.toString() } returns text }

    /**
     * [loading] names the parents whose listing the provider flags as still loading, which
     * is how a provider backed by a network says the rows so far are not all of them.
     */
    protected fun deviceTree(
        tree: Map<String, List<Child>?>,
        loading: Set<String> = emptySet(),
        errors: Set<String> = emptySet(),
        maxQueries: Int = Int.MAX_VALUE,
    ) {
        var queries = 0
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val parent = firstArg<Uri>().toString().removePrefix("children:")
            val children = if (tree.containsKey(parent)) tree[parent]
                else if (created.any { it.first == DocumentsContract.Document.MIME_TYPE_DIR && "doc:${it.second}" == parent }) emptyList()
                else null
            if (++queries > maxQueries) null
            else children?.let { cursorOver(it, parent in loading, parent in errors) }
        }
        every { resolver.openInputStream(any()) } answers {
            val docId = firstArg<Uri>().toString().removePrefix("doc-uri:")
            val child = tree.values.filterNotNull().flatten()
                .firstOrNull { it.id == docId }
            ByteArrayInputStream((child?.contents ?: "").toByteArray())
        }
    }

    private fun cursorOver(children: List<Child>, loading: Boolean, error: Boolean): Cursor {
        var row = -1
        return mockk<Cursor>(relaxed = true) {
            every { extras } returns if (loading || error) {
                mockk<Bundle>(relaxed = true) {
                    every { getBoolean(DocumentsContract.EXTRA_LOADING) } returns loading
                    every { containsKey(DocumentsContract.EXTRA_ERROR) } returns error
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
            every { getString(0) } answers { children[row].id }
            every { getString(1) } answers { children[row].name }
            every { getString(2) } answers {
                if (children[row].isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                else "text/plain"
            }
            every { getLong(3) } answers { children[row].size }
            every { getLong(4) } answers { children[row].modified }
        }
    }

    protected fun sync() = runBlocking {
        lost.clear()
        notCopied = 0
        didNotReach.clear()
        keptInApp.clear()
        localCopies.clear()
        val engine = SafSyncEngine(context)
        engine.onWriteBackFailed = { lost += it.name }
        engine.onDocumentsNotCopied = { count, _ -> notCopied = count }
        engine.onUploadIncomplete = { _, count, _ -> didNotReach += count }
        engine.onFilesKeptInApp = { _, count, first -> keptInApp += count to first }
        engine.onLocalCopiesPreserved = { copies -> localCopies += copies.map { it.name } }
        engine.initialSync(named("tree"), mirror) { _, _ -> }
        engine
    }

    protected fun mirrorFile(name: String) = File(mirror, name)

    /**
     * The app was updated after the last open wrote [record]: the package's update time is later than the
     * record's. With [redateRecord] the record is dated a minute before an update a second ago, so a case that
     * times files against the record does so after this; without it the update is put a second after the record
     * as it stands. An update alone says nothing of which build wrote the record: [recordLeftByEarlierBuild].
     */
    protected fun updatedSinceLastOpen(redateRecord: Boolean = true) {
        val updatedAt = if (redateRecord) System.currentTimeMillis() - 1_000 else record.lastModified() + 1_000
        if (redateRecord) check(record.setLastModified(updatedAt - 60_000)) { "could not date the record before the update" }
        val info = PackageInfo().apply { lastUpdateTime = updatedAt }
        val packages = mockk<PackageManager>(relaxed = true)
        every { packages.getPackageInfo(any<String>(), any<Int>()) } returns info
        every { context.packageManager } returns packages
    }

    /**
     * [record] is one an earlier build wrote, 1.10.1 or older, and the app has been updated since: it carries no
     * [sortedMark], which only an open of this build that sorted the files only the app holds leaves.
     */
    protected fun recordLeftByEarlierBuild() {
        check(record.isFile) { "there is no record to have been written by an earlier build" }
        sortedMark.delete()
        updatedSinceLastOpen()
    }

    /** Runs [then] each time the engine has read a file holding [contents] through to its digest. */
    protected fun afterReading(contents: String, then: () -> Unit) {
        mockkStatic(MessageDigest::class)
        every { MessageDigest.getInstance("SHA-256") } answers {
            val real = callOriginal()
            val read = ByteArrayOutputStream()
            object : MessageDigest("SHA-256") {
                override fun engineUpdate(input: Byte) {
                    read.write(input.toInt())
                    real.update(input)
                }
                override fun engineUpdate(input: ByteArray, offset: Int, length: Int) {
                    read.write(input, offset, length)
                    real.update(input, offset, length)
                }
                override fun engineReset() {
                    read.reset()
                    real.reset()
                }
                override fun engineDigest(): ByteArray {
                    if (read.toString() == contents) then()
                    return real.digest()
                }
            }
        }
    }

    protected companion object {
        /** Old enough that no mirror file is ever kept merely for being newer. */
        const val DEVICE_TIME = 1_700_000_000_000L
    }
}
