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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * What reading the recent-folder list costs the thread that asks and the threads that wait.
 *
 * `getPersistedFolders` prunes the entries whose grant the user has revoked, so it has to
 * ask the system server what is still granted, and the prune is a read-modify-write of one
 * preference so it has to hold `recentFoldersLock` while it rewrites. Asking per entry put
 * a chain of binder round trips inside that monitor, and the monitor is process-wide: the
 * bridge's disk-work executor, a sync on `Dispatchers.IO`, the reclaim thread and the
 * page-load lookup all wait on it from one side or the other.
 *
 * `persistedUriPermissions` answers for every grant at once, so one call answers for the
 * whole list, and taking it before the lock keeps a listing's round trip off the critical
 * section. One reading also judges every entry against one answer, which the per-entry
 * version could not promise.
 *
 * Scope, so the second case below is not read for more than it measures: this is a
 * listing that arrives with the monitor free. A call nested inside one of the
 * read-modify-writes (`releaseGrantFor`, `addToRecentFolders`, `updateLastOpened`) still
 * makes its round trip with the monitor held, which is why the second case parks only the
 * FIRST lookup: the writer thread it starts reaches the provider again and must not be
 * gated there, or it would be measuring the gate rather than the monitor. What the hoist
 * bounds is that cost, one round trip rather than one per entry, and the thread paying it
 * is a background one in every case (`DeviceFolderOpenThreadTest` pins the hops that keep
 * the activity's three callers off the main thread), so it is a wait and not a freeze.
 *
 * These are the two halves of it, measured separately: how many round trips a listing
 * costs, and whether an uncontended listing holds the monitor while it makes them.
 */
class RecentFolderGrantLookupTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: SafStorageManager
    private lateinit var resolver: ContentResolver
    private lateinit var prefs: SharedPreferences

    private var recentJson = "[]"

    /** How many times the system server was asked which trees are still granted. */
    private val lookups = AtomicInteger(0)

    /** Counted down once a caller is parked inside that lookup. */
    private val entered = CountDownLatch(1)

    /** Held closed until the test lets that caller finish. */
    private val release = CountDownLatch(1)

    /**
     * Only the first lookup is gated; every later one answers at once.
     *
     * Which is what makes the second case a statement about the monitor and nothing
     * else: `releaseGrantFor` asks the provider twice of its own accord, once directly
     * and once through the listing it makes with the lock held, and gating either of
     * those would park the writer thread on this latch rather than on the monitor the
     * case exists to measure.
     */
    private var gated = false

    private val names = listOf("Alpha", "Beta", "Gamma")
    private val uris = names.associateWith { mockk<Uri>() }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs

        mockkStatic(Uri::class)
        names.forEach { name ->
            val uri = uris.getValue(name)
            every { uri.toString() } returns text(name)
            every { uri.lastPathSegment } returns "primary:$name"
            every { Uri.parse(text(name)) } returns uri
        }

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        every { resolver.persistedUriPermissions } answers {
            lookups.incrementAndGet()
            if (gated) {
                gated = false
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            names.map { permissionFor(uris.getValue(it)) }
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

        recentJson = names.joinToString(",", "[", "]") {
            """{"uri":"${text(it)}","name":"$it","lastOpened":1}"""
        }
        manager = SafStorageManager(context)
    }

    @AfterEach
    fun tearDown() {
        // However the assertions ended, no case may leave a thread parked in the gate.
        release.countDown()
        unmockkAll()
    }

    private fun text(name: String) = "content://tree/primary%3A$name"

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
     * NEGATIVE CONTROL, run by hand: put `hasPersistedPermission(uri)` back in the prune
     * in place of the `uri !in granted` test. The count is then one per entry and this
     * goes red at three.
     */
    @Test
    fun `listing the recent folders asks for the grants once, not once per folder`() {
        assertEquals(
            names.size, manager.getPersistedFolders().size,
            "the fixture left nothing in the list, so the count below measures nothing",
        )

        assertEquals(
            1, lookups.get(),
            "one listing cost one binder round trip per entry, and every one of them is " +
                "made with the recent list's lock held",
        )
    }

    /**
     * The half the count cannot show: where those round trips happen.
     *
     * `releaseGrantFor` is the second thread here because its whole body is inside the
     * monitor, so its finishing is a statement about the monitor rather than about
     * timing. It is parked deterministically, inside the provider rather than after a
     * sleep.
     *
     * NEGATIVE CONTROL, run by hand: move the lookup back inside the lock, by having
     * `getPersistedFolders` wrap `readAndPruneRecentFolders(persistedReadUris())` in the
     * `synchronized` block instead of taking the snapshot before it. The second thread
     * then waits on a monitor held across the system server's answer and this goes red on
     * `isAlive`.
     */
    @Test
    fun `the grant lookup does not hold the recent list's lock`() {
        val hash = manager.getMirrorDir(uris.getValue("Alpha")).name
        gated = true

        val reader = thread(isDaemon = true) { manager.getPersistedFolders() }
        assertTrue(
            entered.await(5, TimeUnit.SECONDS),
            "setup failed: the listing never reached the system server, so nothing below " +
                "is measuring what another thread waits for",
        )

        val writer = thread(isDaemon = true) { manager.releaseGrantFor(hash) }
        writer.join(2_000)
        assertFalse(
            writer.isAlive,
            "a thread that only needs the recent list waited on a monitor a plain " +
                "listing was holding across a binder round trip, for as long as the " +
                "system server took to answer somebody else",
        )

        release.countDown()
        reader.join(5_000)
    }
}
