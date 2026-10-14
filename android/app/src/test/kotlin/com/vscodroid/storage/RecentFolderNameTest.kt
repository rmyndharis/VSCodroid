package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.UriPermission
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
import io.mockk.slot
import io.mockk.unmockkAll
import org.json.JSONArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The name the recent list keeps for a folder, and what is made from it.
 *
 * The list is read for its labels by Open Recent, the storage screen and the notices
 * about a folder, and `noteOpened` makes a link from it for a page on the hash path. When
 * the provider did not answer the name query at an open, the list stored the tree's
 * document id as the folder's name: an opaque one on a provider whose ids are not paths.
 * That was the label from then on, and the link `noteOpened` made from it was a second
 * path for the same copy, which the workbench, keeping open editors, unsaved changes and
 * terminals per path, was moved onto.
 *
 * Only an answered name is stored now. Where the provider does not answer, the name it
 * last gave stands, and without one the entry is unnamed: nothing is made from its
 * label, and the label itself is still the tree's last segment, never an empty string.
 *
 * The provider here is one whose tree id is `9f3a1c`, which is not the folder's name.
 */
class RecentFolderNameTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: SafStorageManager
    private lateinit var resolver: ContentResolver

    private var recentJson = "[]"

    private val treeText = "content://com.example.provider/tree/9f3a1c"
    private val tree = mockk<Uri>()

    /** A folder whose grant is gone, so that listing the folders prunes it and writes the list. */
    private val revokedText = "content://com.example.provider/tree/dead"
    private val revoked = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        every { tree.toString() } returns treeText
        every { tree.lastPathSegment } returns "9f3a1c"
        mockkStatic(Uri::class)
        every { Uri.parse(treeText) } returns tree
        every { revoked.toString() } returns revokedText
        every { revoked.lastPathSegment } returns "dead"
        every { Uri.parse(revokedText) } returns revoked

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "9f3a1c"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        every { resolver.persistedUriPermissions } returns listOf(
            mockk<UriPermission> {
                every { uri } returns tree
                every { isReadPermission } returns true
            }
        )

        val prefs = fakePrefs()
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        every { context.getSharedPreferences(any(), any()) } returns prefs

        manager = SafStorageManager(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun fakePrefs(): SharedPreferences {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val written = slot<String>()
        every { editor.putString(any(), capture(written)) } answers {
            recentJson = written.captured
            editor
        }
        return mockk<SharedPreferences>(relaxed = true).also {
            every { it.getString(any(), any()) } answers { recentJson }
            every { it.edit() } returns editor
        }
    }

    /** The provider answers the name query with [name], which may be null for no name. */
    private fun providerAnswers(name: String?) {
        every { resolver.query(any(), any(), any(), any(), any()) } returns mockk<Cursor>(relaxed = true) {
            every { moveToFirst() } returns true
            every { getString(0) } returns name
        }
    }

    /** The provider does not answer the name query, as a network folder offline does not. */
    private fun providerFails() {
        every { resolver.query(any(), any(), any(), any(), any()) } throws
            IllegalStateException("the provider did not answer")
    }

    private fun entry() = JSONArray(recentJson).getJSONObject(0)

    @Test
    fun `a name the provider answers is stored and is the label`() {
        providerAnswers("recipes")

        manager.persistPermission(tree)

        val folder = manager.getPersistedFolders().single()
        assertEquals("recipes", folder.displayName)
        assertTrue(folder.named)
        assertEquals("recipes", entry().getString("name"))
    }

    /**
     * The case this exists for. The provider said nothing, so no name is stored; the entry
     * is unnamed and the label is the tree's last segment, which is what is printed and is
     * not what a link is made from.
     */
    @Test
    fun `a name the provider did not answer is not stored`() {
        providerFails()

        manager.persistPermission(tree)

        assertFalse(entry().has("name"), "the fallback was stored as the folder's name: ${entry()}")
        val folder = manager.getPersistedFolders().single()
        assertFalse(folder.named)
        assertEquals("9f3a1c", folder.displayName)
    }

    @Test
    fun `a name that is blank is not an answer either`() {
        providerAnswers("   ")

        manager.persistPermission(tree)

        assertFalse(entry().has("name"))
        assertFalse(manager.getPersistedFolders().single().named)
    }

    /**
     * An open that is not answered must not turn a folder the list already names back into
     * an unnamed one: the name stood for the same folder a moment ago, and the link made
     * from it is the path the page is on.
     */
    @Test
    fun `the name the provider last gave stands when a later open is not answered`() {
        providerAnswers("recipes")
        manager.persistPermission(tree)
        providerFails()

        manager.persistPermission(tree)

        val folder = manager.getPersistedFolders().single()
        assertEquals("recipes", folder.displayName)
        assertTrue(folder.named)
        assertEquals("recipes", entry().getString("name"))
    }

    /** And an answered open names a folder that was unnamed. */
    @Test
    fun `an open that is answered names a folder that had no name`() {
        providerFails()
        manager.persistPermission(tree)
        providerAnswers("recipes")

        manager.persistPermission(tree)

        val folder = manager.getPersistedFolders().single()
        assertEquals("recipes", folder.displayName)
        assertTrue(folder.named)
    }

    /**
     * A list written by a build that stored the fallback as the name. Taken as a name it
     * would have a link made from it by the next page on the hash path, which is the
     * second route to the same second path.
     */
    @Test
    fun `a name that is the fallback, stored by an earlier build, is read as unnamed`() {
        recentJson = """[{"uri":"$treeText","name":"9f3a1c","lastOpened":5}]"""

        val folder = manager.getPersistedFolders().single()

        assertFalse(folder.named)
        assertEquals("9f3a1c", folder.displayName)
    }

    @Test
    fun `a stored name that differs from the fallback is a name`() {
        recentJson = """[{"uri":"$treeText","name":"recipes","lastOpened":5}]"""

        val folder = manager.getPersistedFolders().single()

        assertTrue(folder.named)
        assertEquals("recipes", folder.displayName)
    }

    /**
     * Every reader prints the label as it stands: Open Recent through the bridge, the
     * storage screen, and the notices. So whatever is stored, or not, the label is text.
     */
    @Test
    fun `no entry is listed with an empty label`() {
        val stored = listOf(
            """{"uri":"$treeText","lastOpened":5}""",
            """{"uri":"$treeText","name":"","lastOpened":5}""",
            """{"uri":"$treeText","name":"   ","lastOpened":5}""",
        )
        for (json in stored) {
            recentJson = "[$json]"

            val folder = manager.getPersistedFolders().single()

            assertEquals("9f3a1c", folder.displayName, "an entry like $json was listed as '${folder.displayName}'")
            assertFalse(folder.named)
        }
    }

    @Test
    fun `a tree whose last segment is blank is labelled Unknown`() {
        every { tree.lastPathSegment } returns "  "
        recentJson = """[{"uri":"$treeText","lastOpened":5}]"""

        assertEquals("Unknown", manager.getPersistedFolders().single().displayName)
    }

    /**
     * The list is rewritten when a revoked grant is pruned, from the entries it read. An
     * unnamed one must not come out of that with the fallback stored as its name, and a
     * fallback an earlier build stored is not kept either.
     */
    @Test
    fun `the write that prunes a revoked folder stores no fallback as a name`() {
        for (stored in listOf("", ",\"name\":\"9f3a1c\"")) {
            recentJson = """[{"uri":"$treeText"$stored,"lastOpened":5},""" +
                """{"uri":"$revokedText","name":"gone","lastOpened":4}]"""

            val folders = manager.getPersistedFolders()

            assertEquals(listOf(tree), folders.map { it.uri }, "setup: the revoked folder was not pruned")
            assertFalse(entry().has("name"), "a write of the list stored a name for an unnamed entry: ${entry()}")
            assertFalse(manager.getPersistedFolders().single().named)
        }
    }
}
