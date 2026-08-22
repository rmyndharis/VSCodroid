package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.UriPermission
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Two threads updating the recent-folder list at once.
 *
 * The list is one JSON string in one preference, and four operations rewrite it from a
 * value they read first: the prune inside `getPersistedFolders`, `addToRecentFolders`,
 * `updateLastOpened` and `releaseGrantFor`. `SharedPreferences` is atomic per put; the
 * read and the write are two calls, and nothing used to hold them together.
 *
 * Three threads genuinely reach those. The UI thread picks a folder, a `Dispatchers.IO`
 * thread finishes a sync, and the WebView's bridge thread asks for the recent list and
 * removes device-folder copies. Interleaved, the later writer saves a list it read before
 * the earlier one wrote: a folder just picked drops out of Open Recent, or a folder whose
 * mirror has just been removed keeps a row pointing at a directory that is not there,
 * which is the state `releaseGrantFor` exists to prevent.
 *
 * Deterministic rather than a race: the gate is inside the fake preferences, so one
 * thread is parked INSIDE the critical section while the other tries to enter, and
 * nothing here depends on timing to produce the interleaving. The gated read hands back
 * the value it snapshotted before parking, because a read-modify-write computes from what
 * it read, not from what the preference holds when it finally gets around to writing.
 *
 * NEGATIVE CONTROL, run by hand: drop the `synchronized(recentFoldersLock)` from either
 * `getPersistedFolders` or `addToRecentFolders`. The remover finishes inside the join
 * timeout, so the first assertion goes red, and the removal is then computed from a list
 * that does not yet hold the folder just picked, so the second goes red from the other
 * direction. Two independent failures for one mutation.
 */
class RecentFolderUpdateTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: SafStorageManager
    private lateinit var resolver: ContentResolver
    private lateinit var prefs: SharedPreferences

    private var recentJson = "[]"

    private val pickedText = "content://tree/primary%3APicked"
    private val removedText = "content://tree/primary%3ARemoved"
    private val picked = mockk<Uri>()
    private val removed = mockk<Uri>()

    /** Counted down once the first writer is parked inside its update. */
    private val entered = CountDownLatch(1)

    /** Held closed until the test lets that writer finish. */
    private val release = CountDownLatch(1)

    /** Only the first read is gated; every later one answers at once. */
    private var gated = true

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        every { picked.toString() } returns pickedText
        every { picked.lastPathSegment } returns "primary:Picked"
        every { removed.toString() } returns removedText
        every { removed.lastPathSegment } returns "primary:Removed"

        mockkStatic(Uri::class)
        every { Uri.parse(pickedText) } returns picked
        every { Uri.parse(removedText) } returns removed

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        // Both grants live, so neither folder is pruned for a reason this case is not
        // about: what is being watched is the two updates, not the prune.
        every { resolver.persistedUriPermissions } returns
            listOf(permissionFor(picked), permissionFor(removed))

        prefs = fakePrefs()
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.contentResolver } returns resolver
        every { context.getSharedPreferences(any(), any()) } returns prefs

        manager = SafStorageManager(context)
    }

    @AfterEach
    fun tearDown() {
        // However the assertions ended, no case may leave a thread parked in the gate.
        release.countDown()
        unmockkAll()
    }

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

    private fun permissionFor(granted: Uri): UriPermission = mockk<UriPermission> {
        every { uri } returns granted
        every { isReadPermission } returns true
    }

    /**
     * Parks the first read of the list inside whoever made it, handing back the value it
     * saw rather than the value the preference holds when it is let go: an update
     * computes from what it read.
     */
    private fun gateTheFirstRead() {
        every { prefs.getString(any(), any()) } answers {
            if (gated) {
                gated = false
                val snapshot = recentJson
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                snapshot
            } else {
                recentJson
            }
        }
    }

    @Test
    fun `two threads cannot interleave a read-modify-write of the recent list`() {
        recentJson = """[{"uri":"$removedText","name":"Removed","lastOpened":1}]"""
        val removedHash = manager.getMirrorDir(removed).name
        assertEquals(
            listOf(removed), manager.getPersistedFolders().map { it.uri },
            "the fixture never put the folder in Open Recent, so what follows is not " +
                "measuring the removal",
        )
        gateTheFirstRead()

        val adder = thread(isDaemon = true) { manager.persistPermission(picked) }
        assertTrue(
            entered.await(5, TimeUnit.SECONDS),
            "setup failed: the first writer never reached the list, so nothing below is " +
                "measuring what happens when the second one arrives while it is inside",
        )

        val remover = thread(isDaemon = true) { manager.releaseGrantFor(removedHash) }
        remover.join(300)
        assertTrue(
            remover.isAlive,
            "the second writer read the list while the first was inside its update",
        )

        release.countDown()
        adder.join(5_000)
        remover.join(5_000)

        val listed = manager.getPersistedFolders().map { it.uri }
        assertTrue(
            picked in listed,
            "the folder the user just picked is not in Open Recent: the removal saved a " +
                "list it had read before the add wrote one",
        )
        assertFalse(
            removed in listed,
            "a folder whose mirror has been removed kept its row, which points Open " +
                "Recent at a directory that is not there",
        )
    }
}
