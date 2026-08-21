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
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A removal whose rename the filesystem refuses is not a removal.
 *
 * The rename is the commit point of [SafStorageManager.reclaimMirror], and everything
 * else it does is arranged around that: the grant and the recent-list row are given up
 * because the mirror is going, and the byte count it answers with is what the user is
 * told they got back. A rename that does not happen leaves the mirror whole, so a byte
 * count is a lie about disk the user does not have, and a released grant is worse than
 * a lie: nothing in the app can take a grant back, so the folder that is still on disk
 * can no longer be opened, named or reclaimed by anything the app does.
 *
 * The refusal is arranged the way one arrives in the field. An earlier removal set the
 * mirror aside and the sweep never finished it, the user re-granted the same folder,
 * and the hash the folder maps to is the same hash, so the second removal renames onto
 * a `discarded-` directory that is still there and not empty, which is what the
 * filesystem refuses.
 */
class MirrorReclaimRefusedRenameTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: SafStorageManager
    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var mirrorsDir: File
    private var recentJson = "[]"

    private val folderUriText = "content://tree/primary%3AProject"
    private val folderUri = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        every { folderUri.toString() } returns folderUriText
        every { folderUri.lastPathSegment } returns "primary:Project"

        resolver = mockk(relaxed = true)
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.contentResolver } returns resolver
        every { context.getSharedPreferences(any(), any()) } returns fakePrefs()
        mockkStatic(Uri::class)
        every { Uri.parse(folderUriText) } returns folderUri
        every { resolver.persistedUriPermissions } returns emptyList()

        manager = SafStorageManager(context)
        mirrorsDir = File(filesDir, "saf-mirrors").apply { mkdirs() }
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
        return mockk<SharedPreferences>(relaxed = true).also { prefs ->
            every { prefs.getString(any(), any()) } answers { recentJson }
            every { prefs.edit() } returns editor
        }
    }

    private fun permissionFor(granted: Uri): UriPermission = mockk<UriPermission> {
        every { uri } returns granted
        every { isReadPermission } returns true
    }

    /** A mirror whose every file the record vouches for, so the gate lets it through. */
    private fun vouchedMirror(): File {
        val dir = manager.getMirrorDir(folderUri).apply { mkdirs() }
        File(dir, "src").mkdirs()
        val file = File(dir, "src/main.kt").apply { writeText("fun main() {}") }
        File(dir.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).writeText(
            SafSyncEngine.RECORD_HEADER + "\n" +
                SafSyncEngine(context).identityLine("src/main.kt", file)
        )
        check(SafSyncEngine(context).holdsOnlyVouchedCopies(dir)) {
            "the fixture wrote a record the engine does not accept, so this case would " +
                "fail on the gate rather than on the rename it is about"
        }
        return dir
    }

    @Test
    fun `a rename the filesystem refuses is not reported as a completed removal`() {
        val dir = vouchedMirror()
        recentJson = """[{"uri":"$folderUri","name":"Project","lastOpened":1700000000}]"""
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(folderUri))
        assertEquals(
            1, manager.getPersistedFolders().size,
            "the fixture never put the folder in Open Recent, so what follows is not " +
                "measuring the removal",
        )

        // The leftover an unfinished sweep leaves behind. Renaming onto a directory that
        // is not empty is what the filesystem refuses, so this is the whole arrangement.
        val leftover = File(mirrorsDir, SafStorageManager.DISCARD_PREFIX + dir.name)
        File(leftover, "half-deleted").apply { mkdirs() }
        // Proven on a directory of no consequence rather than on the mirror: a platform
        // that allowed this rename would carry the mirror away and leave the case
        // measuring the removal of something that was already gone.
        val probe = File(mirrorsDir, "probe-source").apply { mkdirs() }
        assertTrue(
            !probe.renameTo(leftover),
            "this platform allows a rename onto a directory that is not empty, so the " +
                "removal below would commit and this case would be measuring nothing",
        )

        val answer = manager.reclaimMirror(dir.name, force = false)

        assertEquals(
            SafStorageManager.RECLAIM_FAILED, answer,
            "a removal that did not happen answered with the bytes it would have freed",
        )
        assertTrue(
            File(dir, "src/main.kt").isFile,
            "the copy is still on disk, so the answer above decides what the user is told",
        )
        verify(exactly = 0) { resolver.releasePersistableUriPermission(any(), any()) }
        assertEquals(
            1, manager.getPersistedFolders().size,
            "the folder left Open Recent although its copy is still there, and nothing " +
                "in the app can put the grant back",
        )
    }
}
