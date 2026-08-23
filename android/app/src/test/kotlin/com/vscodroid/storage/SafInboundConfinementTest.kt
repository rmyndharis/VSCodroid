package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * Where a device document is allowed to land.
 *
 * The engine states its symbolic-link policy in its own words on every path out of the
 * mirror: `writeLocalToSaf`, `uploadPlan`, `watchableDirectories` and
 * `holdsOnlyVouchedCopies` each refuse a link, explaining that a mirror is routinely a
 * checked-out repository and a link inside one is attacker-supplied in the ordinary case.
 * The copy *into* the mirror asked nothing at all, and it is the direction that needs the
 * wider question: `isLink` deliberately answers about an entry rather than about its
 * parents, and what carries a document out of the folder the user granted is a link one
 * or more levels above it. `mkdirs` follows one, and so does the rename that moves a
 * finished copy into place, so a single `docs -> ..` planted by a checkout or a terminal
 * put every document under `docs/` wherever it pointed, this app's own private storage
 * included.
 *
 * A file-level link is not the case that matters and is already safe: the copy writes
 * beside its destination and renames, and a rename replaces the link rather than writing
 * through it. The case below is the directory one, which is why the fixture answers per
 * parent rather than handing one cursor to every query.
 */
class SafInboundConfinementTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var elsewhere: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context

    private val record: File get() = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)

    private data class Child(
        val name: String,
        val isDirectory: Boolean = false,
        val contents: String = "",
    )

    @BeforeEach
    fun setUp() {
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

        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
    }

    @AfterEach
    fun tearDown() {
        record.delete()
        unmockkAll()
    }

    private fun named(text: String): Uri =
        mockk<Uri>(relaxed = true).also { every { it.toString() } returns text }

    private fun deviceTree(tree: Map<String, List<Child>>) {
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val parent = firstArg<Uri>().toString().removePrefix("children:")
            tree[parent]?.let { cursorOver(it) }
        }
        every { resolver.openInputStream(any()) } answers {
            val docId = firstArg<Uri>().toString().removePrefix("doc-uri:")
            val child = tree.values.flatten().firstOrNull { "doc:${it.name}" == docId }
            ByteArrayInputStream((child?.contents ?: "").toByteArray())
        }
    }

    private fun cursorOver(children: List<Child>): Cursor {
        var row = -1
        return mockk<Cursor>(relaxed = true) {
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
            every { isNull(any<Int>()) } returns false
            every { getString(0) } answers { "doc:${children[row].name}" }
            every { getString(1) } answers { children[row].name }
            every { getString(2) } answers {
                if (children[row].isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                else "text/plain"
            }
            every { getLong(3) } answers { children[row].contents.toByteArray().size.toLong() }
            every { getLong(4) } returns 1_700_000_000_000L
        }
    }

    private fun sync() = runBlocking {
        SafSyncEngine(context).initialSync(named("tree"), mirror) { _, _ -> }
    }

    @Test
    fun `a document under a linked directory is not written outside the mirror`() {
        val target = File(elsewhere, "target").apply { mkdirs() }
        Files.createSymbolicLink(File(mirror, "docs").toPath(), target.toPath())
        assertTrue(
            Files.isSymbolicLink(File(mirror, "docs").toPath()),
            "the link was not created, so nothing below is being tested",
        )
        deviceTree(
            mapOf(
                "root" to listOf(Child("docs", isDirectory = true)),
                "doc:docs" to listOf(Child("secrets.txt", contents = "the user's own file")),
            )
        )

        sync()

        assertEquals(
            emptyList<String>(), target.list()?.toList(),
            "a device document was written through a link and landed outside the folder " +
                "the user granted, where nothing in this engine may look for it again",
        )
    }

    /**
     * The control, and the reason the case above cannot be satisfied by copying nothing.
     * The identical shape with a real directory still brings the document down.
     */
    @Test
    fun `the same document under a real directory still reaches the mirror`() {
        File(mirror, "docs").mkdirs()
        deviceTree(
            mapOf(
                "root" to listOf(Child("docs", isDirectory = true)),
                "doc:docs" to listOf(Child("secrets.txt", contents = "the user's own file")),
            )
        )

        sync()

        assertEquals("the user's own file", File(mirror, "docs/secrets.txt").readText())
    }

    /**
     * And a link at the leaf keeps working the way it already did: the copy replaces the
     * link rather than writing through it, so the target is untouched and the mirror
     * holds a real file afterwards.
     */
    @Test
    fun `a link at the leaf is replaced rather than written through`() {
        // Stamped older than the device document below. `lastModified()` on the mirror
        // path follows the link to this file, so leaving it at the wall clock would make
        // the mirror look strictly newer and the copy would never be attempted, which is
        // the opposite of what this case exists to prove.
        val secret = File(elsewhere, "id_ed25519").apply {
            writeText("PRIVATE KEY MATERIAL")
            setLastModified(1_600_000_000_000L)
        }
        Files.createSymbolicLink(File(mirror, "notes.md").toPath(), secret.toPath())
        deviceTree(mapOf("root" to listOf(Child("notes.md", contents = "from the device"))))

        sync()

        assertEquals("PRIVATE KEY MATERIAL", secret.readText(), "the link's target was written")
        assertEquals("from the device", File(mirror, "notes.md").readText())
        assertFalse(
            Files.isSymbolicLink(File(mirror, "notes.md").toPath()),
            "the mirror entry is still a link, so the next save reads through it",
        )
    }
}
