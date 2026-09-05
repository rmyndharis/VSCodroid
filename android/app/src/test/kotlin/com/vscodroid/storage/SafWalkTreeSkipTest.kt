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
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The wire between [SafSyncEngine.shouldSkip] and the walk that is supposed to
 * obey it.
 *
 * `ShouldSkipTest` covers fourteen directory names across two parameterised
 * cases and never checked that the walk consults the answer. Dropping the
 * `if (shouldSkip(name, isDir)) continue` line leaves all of them green, and
 * mirrors `node_modules` and `.git` into the local copy.
 *
 * The cost is not a slow sync. Every mirrored file is also watched and
 * reconciled, so a single `node_modules` turns a folder the user picked into
 * tens of thousands of entries in the app's own storage, on a phone. The sync
 * still finishes, which is why nothing would report it.
 *
 * The walk is driven through the ContentResolver the engine already takes with
 * its Context: a two-row cursor, one skippable directory and one ordinary one,
 * so the assertion is what came back rather than what the predicate says.
 */
class SafWalkTreeSkipTest {

    private lateinit var engine: SafSyncEngine
    private val treeUri = mockk<Uri>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkObjectLogger()
        // The URI builders are static and would reach a real provider.
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns treeUri
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns treeUri
    }

    private fun mockkObjectLogger() {
        io.mockk.mockkObject(Logger)
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * A cursor over the given (name, isDir) rows, answering only what walkTree
     * asks of it.
     */
    private fun cursorOver(rows: List<Pair<String, Boolean>>): Cursor {
        var index = -1
        return mockk<Cursor>(relaxed = true) {
            every { getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
            every { getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
            every { getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE) } returns 2
            every { getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE) } returns 3
            every { getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns -1
            every { moveToNext() } answers { ++index < rows.size }
            every { getString(0) } answers { "doc-${rows[index].first}" }
            every { getString(1) } answers { rows[index].first }
            every { getString(2) } answers {
                if (rows[index].second) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
            }
            every { getLong(3) } returns 0L
            every { close() } just Runs
        }
    }

    /** What the last [walk] reported as skipped, in the order it met them. */
    private val skipped = mutableListOf<String>()

    /** Runs the real walk over [rows] and returns the relative paths it kept. */
    private fun walk(rows: List<Pair<String, Boolean>>): List<String> {
        // `returns` and not `answers`: the cursor is built once, so the recursive
        // call walkTree makes for a directory row gets the same, already-exhausted
        // cursor and stops. Switching to `answers` would hand each level a fresh
        // one and recurse until the stack runs out.
        val resolver = mockk<ContentResolver> {
            every { query(any(), any(), any(), any(), any()) } returns cursorOver(rows)
        }
        val context = mockk<Context>(relaxed = true) {
            every { contentResolver } returns resolver
        }
        engine = SafSyncEngine(context)

        val result = mutableListOf<DocumentInfo>()
        skipped.clear()
        // Five parameters, and the fifth is not decoration: what the walk leaves
        // out is device content no later phase can see, so it is reported rather
        // than dropped and the delete guard is armed from it. Reflection cannot
        // be told by the compiler that the arity moved, so this call is the one
        // place that has to follow it by hand.
        SafSyncEngine::class.java
            .getDeclaredMethod(
                "walkTree", Uri::class.java, String::class.java,
                String::class.java, MutableList::class.java, MutableList::class.java
            )
            .apply { isAccessible = true }
            .invoke(engine, treeUri, "root", "", result, skipped)
        return result.map { it.relativePath }
    }

    @Test
    fun `an ordinary directory is kept`() {
        // First, because the test below asserts an absence, and an absence is
        // also what a walk that returned nothing at all produces. This proves
        // the fixture can produce a row before that one claims one was dropped.
        assertEquals(
            listOf("src"), walk(listOf("src" to true)),
            "the fixture must be able to yield a row"
        )
    }

    @Test
    fun `a skippable directory never reaches the result`() {
        // Both rows through one walk, so "src is here and node_modules is not"
        // comes from the same run: a walk that dropped everything would fail the
        // first half.
        val kept = walk(listOf("node_modules" to true, "src" to true))

        assertEquals(listOf("src"), kept, "node_modules must not be mirrored")
        // Left out is not the same as forgotten. Nothing under a skipped directory
        // ever becomes a DocumentInfo, so no later phase can record it as device
        // content this sync did not read, and the DELETE guard proves "the device
        // holds the only copy" from a set that cannot contain it. Unreported, an
        // `rm -rf` of the parent in the terminal takes the device's node_modules
        // and .git with it, permanently, with nothing set aside.
        assertEquals(
            listOf("node_modules"), skipped,
            "the walk dropped node_modules without reporting it, so the delete guard " +
                "cannot see the device content the mirror never held",
        )
    }

    @Test
    fun `a display name that climbs out of the mirror never becomes a path`() {
        // COLUMN_DISPLAY_NAME is text a provider chose, not a filename this app made. The
        // walk composes it into a relative path and the copy turns that into a real one,
        // creating directories along the way -- so a name that is not a single segment
        // lets the copy write outside the mirror, into app-private storage.
        //
        // reconcileDeletions already refuses to act on a path that resolves out of the
        // mirror. This is the same refusal one step earlier, where the path is made.
        //
        // What refuses this particular value is the separator clause, not the parent
        // reference: climbing out needs a separator, so no fixture can exercise one
        // without the other. The bare forms are the test after next. This case is here
        // for the shape a provider would actually send, and because it is the only one
        // that shows the guard being consulted from the walk at all.
        val kept = walk(listOf("../../escaped.txt" to false, "src" to true))

        assertEquals(
            listOf("src"), kept,
            "a name that is not a path segment must never reach the copy"
        )
    }

    @Test
    fun `a display name carrying a separator never becomes a path`() {
        assertEquals(listOf("src"), walk(listOf("a/b.txt" to false, "src" to true)))
        assertEquals(listOf("src"), walk(listOf("a\\b.txt" to false, "src" to true)))
    }

    @Test
    fun `a bare parent or self reference never becomes a path`() {
        // Read the failure carefully if this ever goes red. The offending row is a
        // directory, so without the guard it recurses -- and the recursion is handed the
        // same, already-advanced cursor, which hands it the `src` row. The diff then reads
        // `[.., ../src]`, not the `[.., src]` a hand-trace predicts. Adding a third row to
        // either fixture redistributes rows between levels in a way the row list does not
        // show.
        assertEquals(listOf("src"), walk(listOf(".." to true, "src" to true)))
        assertEquals(listOf("src"), walk(listOf("." to true, "src" to true)))
    }

    @Test
    fun `an ordinary name with spaces is still mirrored`() {
        // The rule refuses separators and parent references, not names that merely look
        // unusual. A filter that also dropped spaces would silently skip most of a
        // person's documents, and it would do it without one line of anything to read.
        assertTrue(
            walk(listOf("My Notes.txt" to false)).contains("My Notes.txt"),
            "a space is an ordinary character in a filename"
        )
    }

    @Test
    fun `a file whose name matches a skipped directory is still kept`() {
        // shouldSkip returns false for anything that is not a directory, and the
        // walk passes isDir through rather than deciding on the name alone. A
        // file called .git is a real thing -- a worktree pointer -- and dropping
        // it would break the repository it points at.
        assertTrue(
            walk(listOf(".git" to false)).contains(".git"),
            "a file is not a directory, whatever it is called"
        )
    }
}
