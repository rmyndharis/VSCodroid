package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * That a file created in the editor reaches the device folder under the name it was
 * given.
 *
 * `createDocument` takes a name and a MIME type, and the platform provider settles a
 * disagreement between them by rewriting the NAME. The rule below is not a guess about
 * it: it is `android.os.FileUtils.splitFileName` as disassembled from an API 33
 * emulator's own `framework.jar`, with the extension tables that ship inside the same
 * file. This app used to answer `text/plain` for `.kt`, `.java` and `.md`, none of which
 * those tables give to `text/plain`, so `Main.kt` was created on the device as
 * `Main.kt.txt`, the editor showed both names on the next open, and the one after that
 * made a third.
 *
 * The fixture is the provider rather than an assertion about the argument, because what
 * has to hold is what the device ends up holding. [CREATED_FILE_MIME_TYPE] is one way to
 * get there and the tests below would still pass if a later change found another.
 */
class SafCreatedDocumentNameTest {

    @TempDir
    lateinit var workspace: File

    private lateinit var engine: SafSyncEngine
    private lateinit var resolver: ContentResolver

    private val parentUri = mockk<Uri>(relaxed = true)
    private val treeUri = mockk<Uri>(relaxed = true)
    private val childrenUri = mockk<Uri>(relaxed = true)
    private val createdUri = mockk<Uri>(relaxed = true)

    /** Every name the fixture's provider actually stored, in the order it stored them. */
    private val stored = mutableListOf<String>()

    /** Every string that reached `android.util.Log`. */
    private val logged = mutableListOf<String>()

    /**
     * A name the provider rewrites whatever it is asked, or null to let it behave.
     *
     * The residual case: the platform runs every display name through
     * `buildValidFatFilename` first, so a name holding a character FAT cannot store comes
     * back changed however honest the MIME type was.
     */
    private var alwaysStoreAs: String? = null

    @BeforeEach
    fun setUp() {
        stored.clear()
        logged.clear()
        alwaysStoreAs = null

        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } answers { logged += secondArg<String>(); 0 }
        every { android.util.Log.d(any(), any()) } answers { logged += secondArg<String>(); 0 }
        every { android.util.Log.w(any(), any<String>()) } answers { logged += secondArg<String>(); 0 }
        every { android.util.Log.w(any(), any<String>(), any<Throwable>()) } answers {
            logged += secondArg<String>(); 0
        }
        every { android.util.Log.e(any(), any()) } answers { logged += secondArg<String>(); 0 }

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getDocumentId(parentUri) } returns "parent-doc"
        every { DocumentsContract.getDocumentId(createdUri) } returns "created-doc"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns childrenUri
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns createdUri
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            stored += alwaysStoreAs ?: platformNameFor(arg(2), arg(3))
            createdUri
        }

        resolver = mockk(relaxed = true)
        every { resolver.openOutputStream(any(), "wt") } returns ByteArrayOutputStream()
        every { resolver.query(childrenUri, any(), any(), any(), any()) } returns emptyFolder()
        every { resolver.query(createdUri, any(), any(), any(), any()) } answers {
            cursorNaming(stored.last())
        }

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        engine = SafSyncEngine(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * What the platform provider stores a document as, given the type and the name it was
     * asked for.
     *
     * `FileSystemProvider.createDocument` (framework.jar, classes4.dex) hands both to
     * `FileUtils.buildUniqueFile`, whose `splitFileName` (classes2.dex, source lines
     * 1201-1222) keeps the requested extension only while the type is the one the
     * platform's table gives that extension, or the extension is the one it gives that
     * type, and otherwise appends the type's own extension to the whole display name.
     * `buildFile` joins the two with a dot, or returns the name alone when the extension
     * is empty.
     */
    private fun platformNameFor(mimeType: String, displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        val name = if (dot >= 0) displayName.substring(0, dot) else displayName
        val ext = if (dot >= 0) displayName.substring(dot + 1) else null
        val mimeTypeFromExt = ext?.let { TYPE_OF_EXTENSION[it.lowercase()] } ?: DEFAULT_TYPE
        val extFromMimeType = if (mimeType == DEFAULT_TYPE) null else EXTENSION_OF_TYPE[mimeType]
        return if (mimeType == mimeTypeFromExt || ext == extFromMimeType) {
            if (ext == null) name else "$name.$ext"
        } else {
            if (extFromMimeType == null) displayName else "$displayName.$extFromMimeType"
        }
    }

    /** A cursor over a device folder holding nothing. */
    private fun emptyFolder(): Cursor = mockk(relaxed = true) {
        every { getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
        every { getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
        every { moveToNext() } returns false
        every { close() } just Runs
    }

    /** A one-row cursor answering `COLUMN_DISPLAY_NAME` with [name]. */
    private fun cursorNaming(name: String): Cursor = mockk(relaxed = true) {
        every { getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 0
        every { moveToFirst() } returns true
        every { getString(0) } returns name
        every { close() } just Runs
    }

    /** Runs the real create for a local file of [contents] named [name]. */
    private fun create(name: String, contents: String = "the only copy") {
        val localFile = File(workspace, name).apply { writeText(contents) }
        SafSyncEngine::class.java
            .getDeclaredMethod(
                "createInSaf", File::class.java, Uri::class.java, Uri::class.java
            )
            .apply { isAccessible = true }
            .invoke(engine, localFile, parentUri, treeUri)
    }

    /**
     * Negative control for every case below it: put `"text/plain"` back in place of
     * [SafSyncEngine.CREATED_FILE_MIME_TYPE] and the three source files go red while
     * `notes.txt` stays green, which is the pair that distinguishes a fixed type from a
     * fixture that cannot rewrite anything.
     */
    @Test
    fun `a Kotlin source file is stored under its own name`() {
        create("Main.kt")

        assertEquals(listOf("Main.kt"), stored)
    }

    @Test
    fun `a Java source file is stored under its own name`() {
        create("App.java")

        assertEquals(listOf("App.java"), stored)
    }

    @Test
    fun `a Markdown file is stored under its own name`() {
        create("README.md")

        assertEquals(listOf("README.md"), stored)
    }

    /**
     * The other side of the same rule: an extension the platform does know still comes
     * through unchanged, so the fix is not paid for by the files it used to get right.
     */
    @Test
    fun `a plain text file is stored under its own name`() {
        create("notes.txt")

        assertEquals(listOf("notes.txt"), stored)
    }

    /**
     * That the fixture can mangle a name at all.
     *
     * Without this the four cases above would pass just as well against a provider model
     * that returned the display name whatever it was handed, and the defect they exist
     * for would be undetectable by all of them.
     */
    @Test
    fun `the provider this models does rewrite a name it is given the wrong type for`() {
        assertEquals("Main.kt.txt", platformNameFor("text/plain", "Main.kt"))
        assertEquals("README.md.txt", platformNameFor("text/plain", "README.md"))
        assertEquals("App.java.txt", platformNameFor("text/plain", "App.java"))
    }

    /**
     * The name is read back, because the type is not the only way a provider can decide
     * to store a document as something else.
     *
     * Negative control: drop the `mirrorNamedAsStored` call from `createOneInSaf` and this
     * goes red while the case below it stays green.
     */
    @Test
    fun `a provider that stores another name says so`() {
        alwaysStoreAs = "a_b.txt"

        create("a:b.txt")

        assertTrue(
            logged.any { it.contains("a:b.txt") && it.contains("a_b.txt") },
            "the device folder holds the file under a name nothing here mentions, so " +
                "the duplicate it produces on the next open has no explanation: $logged",
        )
    }

    /**
     * The control for the read-back. A check that announced a rename on every create
     * would pass the case above and say nothing true, and the wording it prints is the
     * one a bug report is read with.
     *
     * The name is deliberately one the modelled provider keeps whatever type it is
     * handed, so that this stays green under the mutation the cases above are controlled
     * by and answers for the check rather than for the type.
     */
    @Test
    fun `a provider that honours the name says nothing about it`() {
        create("notes.txt")

        assertTrue(logged.isNotEmpty(), "nothing was logged at all, so this proves nothing")
        assertTrue(
            logged.none { it.contains("stored notes.txt as") },
            "an ordinary create was reported as a rename: $logged",
        )
    }

    /**
     * The repair, and the case the read-back existed without for a while.
     *
     * Saying it is not enough. The mirror keeping the refused name is what makes the next
     * open fetch the stored name beside it, and the open after that create a third
     * document, one more on every open for as long as the folder is used. The mirror is
     * this app's own copy, so it is the side that moves.
     */
    @Test
    fun `the mirror follows a name the device folder rewrote`() {
        alwaysStoreAs = "a_b.txt"

        create("a:b.txt")

        assertEquals(
            listOf("a_b.txt"), workspace.list()!!.sorted(),
            "the mirror kept a name the device folder refused, so reopening the folder " +
                "fetches the stored name beside it and the open after that makes a third",
        )
    }

    /**
     * `rename(2)` replaces its destination without a word, and here that destination is
     * the device's own copy, fetched by an earlier sync. The duplicate is the lesser loss.
     */
    @Test
    fun `a stored name the mirror already holds does not replace that copy`() {
        File(workspace, "a_b.txt").writeText("the device copy")
        alwaysStoreAs = "a_b.txt"

        create("a:b.txt", "the local copy")

        assertEquals(listOf("a:b.txt", "a_b.txt"), workspace.list()!!.sorted())
        assertEquals("the device copy", File(workspace, "a_b.txt").readText())
    }

    /**
     * A display name is whatever text the provider returned, and nothing promises it is
     * one path segment. `walkTree` refuses such a name on the way in; the rename out is
     * the same question one direction over, and the file would leave the mirror entirely.
     */
    @Test
    fun `a stored name that is not one path segment leaves the mirror alone`() {
        alwaysStoreAs = "../escaped.txt"

        create("a:b.txt")

        assertEquals(listOf("a:b.txt"), workspace.list()!!.sorted())
        assertFalse(
            File(workspace.parentFile, "escaped.txt").exists(),
            "the mirror file was renamed out of the mirror",
        )
    }

    /**
     * The ceiling the repair leaves behind, pinned rather than described.
     *
     * Nothing remembers that the folder refused a name, and the mirror no longer carries
     * it, so a producer still holding the old spelling starts the whole thing again: the
     * lookup misses, because the device has never held that name, and a second document
     * is made beside the first. A checked-out repository is where it bites, since the
     * tracked spelling is the one the folder will not store, so the working tree reads
     * dirty against a path the mirror gave up and every `git checkout` leaves one more
     * document. Bounded by that producer, against one more on every open of the folder
     * before the mirror followed at all, which is why it ships this way.
     *
     * Whoever closes it turns this case red. The numbers are here so they know what to
     * expect: one document and one mirror file, both under the stored name.
     */
    @Test
    fun `writing the refused name again makes one more document`() {
        alwaysStoreAs = "a_b.txt"
        create("a:b.txt")

        // What `buildUniqueFile` answers once the first name is taken. Set by hand
        // because the fixture cannot work it out: its folder cursor is always empty,
        // which is also why the lookup misses on the way back in.
        alwaysStoreAs = "a_b (1).txt"
        create("a:b.txt")

        assertEquals(listOf("a_b.txt", "a_b (1).txt"), stored)
        assertEquals(listOf("a_b (1).txt", "a_b.txt"), workspace.list()!!.sorted())
    }

    private companion object {
        const val DEFAULT_TYPE = "application/octet-stream"

        /**
         * The extension table that ships inside the emulator's own `framework.jar`, for
         * the entries these cases turn on: `res/android.mime.types:85` `text/plain txt`,
         * `res/debian.mime.types:360` `text/markdown md markdown`, `:382` `text/x-java
         * java`, and no `kt` anywhere in any of the three files.
         */
        val TYPE_OF_EXTENSION = mapOf(
            "txt" to "text/plain",
            "md" to "text/markdown",
            "java" to "text/x-java",
            "json" to "application/json",
        )

        /** The same table read the other way, which is the direction that renames. */
        val EXTENSION_OF_TYPE = mapOf(
            "text/plain" to "txt",
            "text/markdown" to "md",
            "text/x-java" to "java",
            "application/json" to "json",
        )
    }
}
