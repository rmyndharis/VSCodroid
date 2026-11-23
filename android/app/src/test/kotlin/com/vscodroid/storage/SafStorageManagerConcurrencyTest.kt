package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.UriPermission
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import io.mockk.verify
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SafStorageManagerConcurrencyTest {

    @TempDir
    lateinit var root: File

    private lateinit var filesDir: File
    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var manager: SafStorageManager
    private var recentJson = "[]"

    private val folderAText = "content://tree/primary%3AFolderA"
    private val folderBText = "content://tree/primary%3AFolderB"
    private val folderA = mockk<Uri>()
    private val folderB = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        recentJson = "[]"
        filesDir = File(root, "files").apply { mkdirs() }

        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        every { folderA.toString() } returns folderAText
        every { folderA.lastPathSegment } returns "primary:FolderA"
        every { folderB.toString() } returns folderBText
        every { folderB.lastPathSegment } returns "primary:FolderB"

        mockkStatic(Uri::class)
        every { Uri.parse(folderAText) } returns folderA
        every { Uri.parse(folderBText) } returns folderB

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        every { resolver.persistedUriPermissions } returns listOf(
            mockk<UriPermission> {
                every { uri } returns folderA
                every { isReadPermission } returns true
                every { isWritePermission } returns true
            },
            mockk<UriPermission> {
                every { uri } returns folderB
                every { isReadPermission } returns true
                every { isWritePermission } returns true
            },
        )

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val written = slot<String>()
        every { editor.putString(any(), capture(written)) } answers {
            recentJson = written.captured
            editor
        }
        val prefs = mockk<SharedPreferences>(relaxed = true).also {
            every { it.getString(any(), any()) } answers { recentJson }
            every { it.edit() } returns editor
        }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        every { context.getSharedPreferences(any(), any()) } returns prefs

        manager = SafStorageManager(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(SafSyncEngine::class)
        unmockkAll()
    }

    @Test
    fun `sweepDiscardedMirrors retires journal entries moved by a concurrent reclaimMirror`() {
        val mirrorDir = manager.getMirrorDir(folderA).apply { mkdirs() }
        val note = File(mirrorDir, "notes.md").apply { writeText("unsent") }
        val journal = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
        journal.writeText(note.absolutePath)

        // Intercept StorageManager.deleteRecursive so the test runs on hosts without SecureDirectoryStream.
        mockkObject(StorageManager)
        every { StorageManager.deleteRecursive(any(), any(), any()) } answers {
            val target = firstArg<File>()
            target.deleteRecursively()
            0L
        }

        val renameEntered = CountDownLatch(1)
        val allowRename = CountDownLatch(1)
        val sweepDeleted = CountDownLatch(1)
        val sweepDone = CountDownLatch(1)
        val sweepError = AtomicReference<Throwable?>()

        mockkConstructor(SafSyncEngine::class)
        every { anyConstructed<SafSyncEngine>().renameUploadsUnder(any(), any()) } answers {
            renameEntered.countDown()
            assertTrue(allowRename.await(5, TimeUnit.SECONDS), "renameUploadsUnder gate timed out")
            callOriginal()
        }

        val reclaimer = SafStorageManager(context)
        val sweeper = SafStorageManager(context)

        val reclaimThread = thread(name = "reclaim-race", isDaemon = true) {
            reclaimer.reclaimMirror(mirrorDir.name, force = true)
        }
        assertTrue(renameEntered.await(5, TimeUnit.SECONDS), "reclaimMirror never reached renameUploadsUnder")

        val discarded = File(mirrorDir.parentFile, SafStorageManager.DISCARD_PREFIX + mirrorDir.name)
        assertTrue(discarded.isDirectory, "setup: mirror was not renamed aside")

        val sweepThread = thread(name = "sweep-race", isDaemon = true) {
            try {
                sweeper.sweepDiscardedMirrors()
            } catch (e: Throwable) {
                sweepError.set(e)
            } finally {
                sweepDone.countDown()
            }
        }

        // Wait until the sweeper has deleted the discarded directory on disk before letting
        // reclaimMirror execute renameUploadsUnder under grantLifecycleLock.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (discarded.exists() && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(discarded.exists(), "setup: sweeper did not delete the discarded mirror")
        // Give the sweeper a brief moment to either run clearUploadsUnder(discarded) early or block on grantLifecycleLock.
        Thread.sleep(100)
        sweepDeleted.countDown()

        allowRename.countDown()
        reclaimThread.join(5_000)
        assertTrue(sweepDone.await(5, TimeUnit.SECONDS), "sweep thread did not finish")
        sweepError.get()?.let { throw AssertionError("sweep failed", it) }

        val remaining = SafSyncEngine(context).uploadsInFlight()
        assertTrue(
            remaining.isEmpty(),
            "concurrent sweepDiscardedMirrors left orphaned discarded journal entries behind: $remaining",
        )
    }

    @Test
    fun `persistPermission resolves display name outside grantLifecycleLock`() {
        val queryEntered = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)

        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            queryEntered.countDown()
            assertTrue(releaseQuery.await(5, TimeUnit.SECONDS), "display name query gate timed out")
            mockk<Cursor>(relaxed = true) {
                every { moveToFirst() } returns true
                every { getString(0) } returns "FolderA"
            }
        }

        val persistThread = thread(name = "persist-slow-provider", isDaemon = true) {
            manager.persistPermission(folderA)
        }
        assertTrue(queryEntered.await(5, TimeUnit.SECONDS), "persistPermission never queried display name")

        val otherHash = manager.getMirrorDir(folderB).name
        val releaseThread = thread(name = "release-other-grant", isDaemon = true) {
            manager.releaseGrantFor(otherHash)
        }
        releaseThread.join(1_500)
        val blockedOnLock = releaseThread.isAlive

        releaseQuery.countDown()
        persistThread.join(5_000)
        releaseThread.join(5_000)

        assertFalse(
            blockedOnLock,
            "releaseGrantFor blocked on grantLifecycleLock while persistPermission waited on queryDisplayName",
        )
        assertTrue(manager.getPersistedFolders().any { it.uri == folderA && it.displayName == "FolderA" })
    }

    @Test
    fun `persistPermission overload uses pre-resolved display name without querying provider again`() {
        manager.persistPermission(folderA, "PreResolved")

        verify(exactly = 0) { resolver.query(any(), any(), any(), any(), any()) }
        assertTrue(manager.getPersistedFolders().any { it.uri == folderA && it.displayName == "PreResolved" && it.named })
    }
}
