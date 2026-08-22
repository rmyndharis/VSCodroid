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
 * How old the grants a listing prunes against are allowed to be.
 *
 * `getPersistedFolders` asks the system server which trees are still granted, and then
 * deletes from the saved list every row that reading does not name, and saves what is
 * left. The reading is taken before `recentFoldersLock` on purpose, so that the monitor
 * three threads share is never held across a binder round trip; what that costs is the
 * one ordering the lock used to make impossible. A reading can now predate the list it
 * judges: the user confirms the picker in between, `persistPermission` takes the grant
 * and saves the row, and the listing then arrives at the monitor holding a reading from
 * before the grant existed.
 *
 * Pruning against it deletes the folder the user has just picked while its grant stays
 * held, and nothing puts it back. The mirror is then opened by the workbench's own recent
 * list with no watcher behind it, so saves stop reaching the device folder with nothing on
 * screen saying so, the folder is refused a reopen on the next cold start, and the storage
 * screen shows an unnamed granted hash the reclaim pass will not touch.
 *
 * Deterministic rather than a race: the gate is inside the fake system server, so the
 * reader is parked with its reading already taken while the pick is made, and it computes
 * from what it read rather than from what the server holds when it is let go.
 */
class RecentFolderPruneSnapshotTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: SafStorageManager
    private lateinit var resolver: ContentResolver
    private lateinit var prefs: SharedPreferences

    private var recentJson = "[]"

    private val existingText = "content://tree/primary%3AExisting"
    private val pickedText = "content://tree/primary%3APicked"
    private val revokedText = "content://tree/primary%3ARevoked"
    private val existing = mockk<Uri>()
    private val picked = mockk<Uri>()
    private val revoked = mockk<Uri>()

    /** The trees the system server still answers for, as the test moves them. */
    private val grants = mutableListOf<Uri>()

    /** Counted down once the listing is parked with its reading already taken. */
    private val entered = CountDownLatch(1)

    /** Held closed until the test lets that listing finish. */
    private val release = CountDownLatch(1)

    /** Set by a case just before the reading it wants parked; only that one is gated. */
    @Volatile
    private var gated = false

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        every { existing.toString() } returns existingText
        every { existing.lastPathSegment } returns "primary:Existing"
        every { picked.toString() } returns pickedText
        every { picked.lastPathSegment } returns "primary:Picked"
        every { revoked.toString() } returns revokedText
        every { revoked.lastPathSegment } returns "primary:Revoked"

        mockkStatic(Uri::class)
        every { Uri.parse(existingText) } returns existing
        every { Uri.parse(pickedText) } returns picked
        every { Uri.parse(revokedText) } returns revoked

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        // The answer is taken before the gate, not after it: a reading that parks and
        // then reports what the server holds on the way out is not a stale reading at
        // all, and every case here turns on the reading being older than the list.
        every { resolver.persistedUriPermissions } answers {
            val answer = synchronized(grants) { grants.toList() }.map { permissionFor(it) }
            if (gated) {
                gated = false
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            answer
        }
        // Taking the grant is what the picker's confirmation does, and the row the
        // manager saves next is what a stale reading prunes away.
        every { resolver.takePersistableUriPermission(any(), any()) } answers {
            synchronized(grants) { grants.add(firstArg()) }
            Unit
        }

        prefs = fakePrefs()
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        // The manager unwraps whatever it is given to the application context, so a
        // relaxed mock that answers a different object for it hands the manager a
        // filesDir that is not the one below.
        every { context.applicationContext } returns context
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

    private fun rowFor(uri: String, name: String) = """{"uri":"$uri","name":"$name","lastOpened":1}"""

    /**
     * NEGATIVE CONTROL, run by hand: have `getPersistedFolders` pass its reading straight
     * to `readAndPruneRecentFolders` again, dropping the `takeIf` that compares
     * `grantsTaken`. The listing then judges the just-saved row against a reading taken
     * before the grant, prunes it and writes the list back without it, and both
     * assertions below go red. Removing only the `grantsTaken.incrementAndGet()` from
     * `persistPermission` reddens them the same way, which is what says the counter has
     * to be bumped at the grant rather than anywhere later.
     */
    @Test
    fun `a listing does not prune away a folder granted after it read the grants`() {
        recentJson = "[${rowFor(existingText, "Existing")}]"
        synchronized(grants) { grants.add(existing) }
        assertEquals(
            listOf(existing), manager.getPersistedFolders().map { it.uri },
            "the fixture left nothing in the list, so what follows is not measuring a prune",
        )

        gated = true
        val reader = thread(isDaemon = true) { manager.getPersistedFolders() }
        assertTrue(
            entered.await(5, TimeUnit.SECONDS),
            "setup failed: the listing never reached the system server, so its reading " +
                "was not taken before the pick below",
        )

        manager.persistPermission(picked)
        assertTrue(
            pickedText in recentJson,
            "setup failed: the pick never reached the saved list, so the listing has " +
                "nothing to prune away",
        )

        release.countDown()
        reader.join(5_000)
        assertFalse(reader.isAlive, "the listing never finished, so nothing below is settled")

        assertTrue(
            pickedText in recentJson,
            "the listing judged the saved list against grants read before the pick and " +
                "wrote it back without the folder the user had just picked, whose grant " +
                "is still held: it is gone from Open Recent, reopens with no watcher " +
                "behind it, and the reclaim pass will not touch its mirror",
        )
        assertTrue(
            picked in manager.getPersistedFolders().map { it.uri },
            "the folder the user just picked is not in the recent list",
        )
    }

    /**
     * The control, and the half that says the guard above is not simply "never prune".
     *
     * A grant the user revoked in system settings is exactly what the prune is for, and
     * no grant has been taken here, so the reading cannot be older than the list.
     *
     * NEGATIVE CONTROL, run by hand: have `getPersistedFolders` pass `null` in place of
     * its reading, which is the shape that makes the case above pass by pruning nothing
     * at all. Both assertions here go red, while the case above stays green.
     */
    @Test
    fun `a listing still prunes and saves away a folder whose grant is gone`() {
        recentJson = "[${rowFor(existingText, "Existing")},${rowFor(revokedText, "Revoked")}]"
        synchronized(grants) { grants.add(existing) }

        assertEquals(
            listOf(existing), manager.getPersistedFolders().map { it.uri },
            "a folder whose grant the user revoked is still offered in Open Recent",
        )
        assertFalse(
            revokedText in recentJson,
            "the pruned list was not written back, so the revoked row returns on the " +
                "next listing and on the next launch",
        )
    }
}
