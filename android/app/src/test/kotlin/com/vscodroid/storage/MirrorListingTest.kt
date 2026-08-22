package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.UriPermission
import android.net.Uri
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What [SafStorageManager.listMirrors] reports, which is the only place in the app that
 * can name a device folder's local copy.
 *
 * The copies are already measured: `getStorageBreakdown` reports all of them as one
 * number under `saf_mirrors`, correctly marked as something the cache clear cannot
 * touch. What was missing is which folder is holding the disk, and for the copies that
 * matter most the app had stopped being able to say. `addToRecentFolders` keeps ten
 * folders and releases the grant of the one that falls off; the recent-list entry that
 * carried the folder's name goes with it, and the copy stays on disk. The launch pass
 * then declines to reclaim it whenever it holds a file the device does not, which one
 * `npm install` guarantees for ever, because `SKIP_DIRECTORIES` keeps `node_modules`
 * out of the sync record by construction.
 *
 * So the row that is invisible, nameless and unreclaimable is exactly the row worth
 * showing, and the first case here is that one.
 */
class MirrorListingTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: SafStorageManager
    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var mirrorsDir: File
    private var recentJson = "[]"

    private val liveUriText = "content://tree/primary%3ACurrentProject"
    private val orphanUriText = "content://tree/primary%3AOldProject"
    private val liveUri = mockk<Uri>()
    private val orphanUri = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        every { liveUri.toString() } returns liveUriText
        every { orphanUri.toString() } returns orphanUriText
        every { liveUri.lastPathSegment } returns "primary:CurrentProject"
        every { orphanUri.lastPathSegment } returns "primary:OldProject"

        resolver = mockk(relaxed = true)
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        // The manager unwraps whatever it is given to the application context, so a
        // relaxed mock that answers a different object for it hands the manager a
        // filesDir that is not the one below.
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        every { context.getSharedPreferences(any(), any()) } returns fakePrefs()
        // The recent list is stored as JSON and read back through Uri.parse, so a
        // fixture that writes a list has to hand the same mock back: the name and
        // the mirror both resolve from the URI string.
        mockkStatic(Uri::class)
        every { Uri.parse(liveUriText) } returns liveUri
        every { Uri.parse(orphanUriText) } returns orphanUri

        manager = SafStorageManager(context)
        mirrorsDir = File(filesDir, "saf-mirrors").apply { mkdirs() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * A preferences double backed by one string, because the recent list is what
     * supplies the display name and the whole point of the first case is a mirror
     * whose entry is not in it.
     */
    private fun fakePrefs(): SharedPreferences {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val written = slot<String>()
        every { editor.putString(any(), capture(written)) } answers {
            recentJson = written.captured
            editor
        }
        return mockk<SharedPreferences>(relaxed = true).also { prefs ->
            every { prefs.getString(any(), any()) } answers { recentJson }
            every { prefs.edit() } returns editor
        }
    }

    private fun permissionFor(granted: Uri): UriPermission = mockk<UriPermission> {
        every { uri } returns granted
        every { isReadPermission } returns true
    }

    /** A mirror every file of which the record vouches for, as [SafSyncEngine] writes it. */
    private fun vouchedMirror(uri: Uri): File {
        val dir = manager.getMirrorDir(uri).apply { mkdirs() }
        val file = File(dir, "main.kt").apply { writeText("fun main() {}") }
        File(dir.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).writeText(
            SafSyncEngine.RECORD_HEADER + "\n" + SafSyncEngine(context).identityLine("main.kt", file)
        )
        check(SafSyncEngine(context).holdsOnlyVouchedCopies(dir)) {
            "the fixture wrote a record the engine does not accept"
        }
        return dir
    }

    /**
     * The mirror the app can no longer name, which is the one the user most needs to
     * see. Its grant was released when an eleventh folder was opened, and the recent
     * list entry went with it.
     */
    @Test
    fun `a copy with no live grant is still listed, and unnamed`() {
        val orphan = vouchedMirror(orphanUri)
        every { resolver.persistedUriPermissions } returns emptyList()

        val listed = manager.listMirrors()

        assertEquals(listOf(orphan.name), listed.map { it.hash })
        assertFalse(listed.single().granted)
        assertNull(
            listed.single().displayName,
            "an orphan has no name anywhere in the app, and inventing one hides that",
        )
    }

    @Test
    fun `a granted copy carries the name and time the recent list holds`() {
        val dir = vouchedMirror(liveUri)
        recentJson = """[{"uri":"$liveUri","name":"CurrentProject","lastOpened":1700000000}]"""
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        val listed = manager.listMirrors().single()

        assertEquals(dir.name, listed.hash)
        assertTrue(listed.granted)
        assertEquals("CurrentProject", listed.displayName)
        assertEquals(1700000000L, listed.lastOpened)
    }

    /**
     * `saf-mirrors` is not private scratch space: first-run setup exports it into every
     * terminal as `SAF_MIRRORS_DIR` and the WebView publishes it as a resource root, so
     * a person can leave a file there and some will. The reclaim pass refuses to touch
     * those and this listing must not offer them for removal either.
     */
    @Test
    fun `a directory a person left beside the mirrors is not listed`() {
        val mirror = vouchedMirror(liveUri)
        File(mirrorsDir, "scratch").mkdirs()
        File(mirrorsDir, "notes.md").writeText("mine")
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        assertEquals(
            listOf(mirror.name), manager.listMirrors().map { it.hash },
            "a directory this app did not create was offered for removal",
        )
    }

    /**
     * A mirror is two entries, `<hash>` and `<hash>.synced`. Only the first is a folder
     * the user has any concept of; the record is an implementation detail that goes with
     * it, and listing it makes the screen claim two folders where there is one.
     *
     * `MIRROR_ENTRY` matches both names on purpose, because the reclaim pass has to
     * recognise both. What separates them here is that the record is a file: it sits
     * beside the mirror rather than inside it, which is also why it does not defeat the
     * gate that walks the mirror. The fixture asserts that shape before relying on it,
     * because a test that only counted rows would pass just as happily against a filter
     * that had stopped seeing the record at all.
     */
    @Test
    fun `the record beside a copy is not a second row`() {
        val mirror = vouchedMirror(liveUri)
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        assertTrue(record.isFile, "the fixture did not write the record beside the mirror")
        assertTrue(
            SafStorageManager.MIRROR_ENTRY.matches(record.name),
            "the record's name no longer looks like one of ours, so this case is not the " +
                "one it is named for",
        )
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        val listed = manager.listMirrors()

        assertEquals(1, listed.size, "listed: ${listed.map { it.hash }}")
        assertEquals(mirror.name, listed.single().hash)
    }

    /**
     * The same exclusion when the record's name is worn by a directory.
     *
     * `saf-mirrors` is exported into every terminal as `SAF_MIRRORS_DIR`, so a
     * directory can be created there under any name, and `isDirectory` alone is
     * therefore not the test. Without the suffix clause this row appears as a folder
     * the user never opened, and choosing it sets a record aside while leaving the
     * mirror it describes behind, which is the mixed state that can never be resolved.
     */
    @Test
    fun `a directory wearing the record's name is not listed as a folder`() {
        val mirror = vouchedMirror(liveUri)
        File(mirrorsDir, "abc123def456" + SafSyncEngine.SYNCED_RECORD_SUFFIX).mkdirs()
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        assertEquals(listOf(mirror.name), manager.listMirrors().map { it.hash })
    }

    @Test
    fun `an entry set aside by an interrupted removal is not offered`() {
        val mirror = vouchedMirror(liveUri)
        File(mirrorsDir, SafStorageManager.DISCARD_PREFIX + "abc123def456").mkdirs()
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        assertEquals(
            listOf(mirror.name), manager.listMirrors().map { it.hash },
            "a removal already in progress was offered as a folder to remove",
        )
    }

    /**
     * The case that pins the root cause of the whole feature. `node_modules` is in
     * [SafSyncEngine.SKIP_DIRECTORIES], so nothing under it is ever copied down, ever
     * written back, or ever recorded. It is therefore invisible to the upload journal
     * and visible to the record walk, which is what makes the mirror permanently
     * unreclaimable the moment a user runs an install in the folder they are working in.
     */
    @Test
    fun `a copy holding files the device folder lacks is listed as not reclaimable`() {
        val dir = vouchedMirror(liveUri)
        File(dir, "node_modules/lodash").mkdirs()
        File(dir, "node_modules/lodash/index.js").writeText("module.exports = {}")
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        assertFalse(
            manager.listMirrors().single().reclaimable,
            "a mirror holding an npm install was reported as a disposable copy",
        )
    }

    /** The control for the case above: the same mirror without the install. */
    @Test
    fun `a copy the device folder fully holds is listed as reclaimable`() {
        vouchedMirror(liveUri)
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        assertTrue(manager.listMirrors().single().reclaimable)
    }

    /**
     * The other half of the gate, and it is a separate question: the journal records a
     * write-back this app ATTEMPTED and could not deliver, which is a file the device
     * does not have under a name it does.
     */
    @Test
    fun `a copy holding an undelivered write is listed as not reclaimable`() {
        val dir = vouchedMirror(liveUri)
        File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
            .writeText(File(dir, "main.kt").absolutePath + "\n")
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        assertFalse(
            manager.listMirrors().single().reclaimable,
            "a mirror holding a write that never reached the device was called a copy",
        )
    }

    @Test
    fun `the largest copy is listed first`() {
        val small = vouchedMirror(liveUri)
        val big = vouchedMirror(orphanUri)
        File(big, "bulk.bin").writeText("x".repeat(10_000))
        every { resolver.persistedUriPermissions } returns emptyList()

        val listed = manager.listMirrors()

        assertEquals(listOf(big.name, small.name), listed.map { it.hash })
        assertTrue(
            listed.first().bytes > listed.last().bytes,
            "sizes: ${listed.map { it.hash to it.bytes }}",
        )
    }

    /**
     * A link contributes nothing to the size it is inside, because its target's bytes
     * are not in the directory being measured. A mirror is routinely a checked-out
     * repository, so a link in one is ordinary, and charging its target would report a
     * figure the removal cannot possibly free.
     */
    @Test
    fun `a link inside a copy is not charged to it`() {
        val dir = vouchedMirror(liveUri)
        val outside = File(filesDir, "outside").apply { mkdirs() }
        File(outside, "big.bin").writeText("x".repeat(50_000))
        java.nio.file.Files.createSymbolicLink(File(dir, "link").toPath(), outside.toPath())
        every { resolver.persistedUriPermissions } returns emptyList()

        assertTrue(
            manager.listMirrors().single().bytes < 1_000,
            "the size charged the target of a link that lives outside the mirror",
        )
    }

    @Test
    fun `an empty mirrors directory lists nothing`() {
        every { resolver.persistedUriPermissions } returns emptyList()

        assertEquals(emptyList<MirrorInfo>(), manager.listMirrors())
    }
}
