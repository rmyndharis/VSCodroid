package com.vscodroid.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.FileObserver
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SafDataPreservationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        SafDocumentsProvider.AUTHORITY, SafDocumentsProvider.ROOT_ID,
    )
    private lateinit var sandbox: File
    private lateinit var context: Context
    private lateinit var mirror: File
    private lateinit var engine: SafSyncEngine

    @Before
    fun setUp() {
        fixtureGrant(SafProviderGrantReceiver.GRANT)
        val target = instrumentation.targetContext
        assertEquals("The fixture owner did not grant tree access", PackageManager.PERMISSION_GRANTED,
            target.checkUriPermission(treeUri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        sandbox = File(target.cacheDir, "saf-preservation-${System.nanoTime()}")
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(sandbox, "cache").apply { mkdirs() }
        }
        mirror = File(context.filesDir, "mirror").apply { mkdirs() }
        engine = SafSyncEngine(context)
        control("reset", Bundle().apply { putByteArray("bytes", ORIGINAL) })
    }

    @After
    fun tearDown() {
        try {
            if (::engine.isInitialized) engine.shutdown()
            if (::sandbox.isInitialized) sandbox.deleteRecursively()
        } finally {
            fixtureGrant(SafProviderGrantReceiver.REVOKE)
        }
    }

    @Test
    fun saveDoesNotOpenProviderForWritingWhenUploadJournalCannotBeWritten() = runBlocking {
        engine.initialSync(treeUri, mirror) { _, _ -> }
        val local = File(mirror, SafDocumentsProvider.FILE_NAME)
        assertArrayEquals(ORIGINAL, local.readBytes())

        // A non-empty directory blocks both atomic replacement and fallback file writes.
        val journal = File(context.filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
        assertTrue(journal.mkdirs())
        File(journal, "blocker").writeText("keep")
        val failures = AtomicInteger()
        engine.onWriteBackFailed = { failures.incrementAndGet() }
        engine.startWatching(mirror, treeUri)

        local.writeBytes(EDITED)
        assertTrue("The save produced neither a refusal nor a provider write", await {
            failures.get() > 0 || writeOpens() > 0
        })
        engine.stopWatching()

        assertEquals("The provider was opened for writing without a durable journal", 0, writeOpens())
        assertArrayEquals("The device copy changed", ORIGINAL, providerBytes())
        assertArrayEquals("The only local edit was lost", EDITED, local.readBytes())
        assertTrue("The refused save must be reported", failures.get() > 0)
    }

    @Test
    fun ordinarySaveReachesProviderAndSurvivesReopening() = runBlocking {
        engine.initialSync(treeUri, mirror) { _, _ -> }
        val local = File(mirror, SafDocumentsProvider.FILE_NAME)
        assertArrayEquals(ORIGINAL, local.readBytes())
        engine.startWatching(mirror, treeUri)

        local.writeBytes(EDITED)
        assertTrue("The watcher never delivered the edited bytes", await {
            providerBytes().contentEquals(EDITED) && engine.uploadsInFlight().isEmpty()
        })
        engine.stopWatching()
        assertTrue("The control never opened a provider output descriptor", writeOpens() > 0)

        val reopened = File(context.filesDir, "reopened").apply { mkdirs() }
        engine.initialSync(treeUri, reopened) { _, _ -> }
        assertArrayEquals(EDITED, File(reopened, SafDocumentsProvider.FILE_NAME).readBytes())
        assertArrayEquals(EDITED, providerBytes())
    }

    @Test
    fun deliveredSaveFollowsExternalDeletionAcrossTwoReopens() = runBlocking {
        control("directoryReset", Bundle().apply { putByteArray("bytes", ORIGINAL) })
        engine.initialSync(treeUri, mirror) { _, _ -> }
        val local = File(mirror, SafDocumentsProvider.FILE_NAME)
        assertArrayEquals(ORIGINAL, local.readBytes())
        engine.startWatching(mirror, treeUri)

        local.writeBytes(EDITED)
        assertTrue("The watcher never delivered the complete edited bytes", await {
            providerBytes().contentEquals(EDITED) && engine.uploadsInFlight().isEmpty()
        })
        engine.stopWatching()
        assertTrue("The save never opened the provider output descriptor", writeOpens() > 0)
        val createsBeforeDeletion = control("stats").getInt("createCalls")
        val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, SafDocumentsProvider.FILE_NAME)
        assertTrue("The external deletion was refused", DocumentsContract.deleteDocument(context.contentResolver, document))
        assertFalse("The external deletion left the provider document", control("stats").getBoolean("exists"))

        repeat(2) { reopen ->
            engine.initialSync(treeUri, mirror) { _, _ -> }
            assertFalse("Reopen ${reopen + 1} retained the delivered mirror copy", local.exists())
            val stats = control("stats")
            assertFalse("Reopen ${reopen + 1} recreated the provider document", stats.getBoolean("exists"))
            assertEquals("Reopen ${reopen + 1} created a document after the deletion",
                createsBeforeDeletion, stats.getInt("createCalls"))
        }
    }

    @Test
    fun undeliveredSameStampEditSurvivesExternalDeletion() = runBlocking {
        control("directoryReset", Bundle().apply { putByteArray("bytes", ORIGINAL) })
        engine.initialSync(treeUri, mirror) { _, _ -> }
        val local = File(mirror, SafDocumentsProvider.FILE_NAME)
        engine.startWatching(mirror, treeUri)
        local.writeBytes(EDITED)
        assertTrue("The watcher never delivered the complete edited bytes", await {
            providerBytes().contentEquals(EDITED) && engine.uploadsInFlight().isEmpty()
        })
        engine.stopWatching()
        val deliveredTime = local.lastModified()
        val undelivered = ByteArray(EDITED.size) { 'z'.code.toByte() }
        assertFalse(EDITED.contentEquals(undelivered))
        local.writeBytes(undelivered)
        assertTrue("The preserved timestamp could not be restored", local.setLastModified(deliveredTime))
        assertEquals(deliveredTime, local.lastModified())
        assertEquals(EDITED.size.toLong(), local.length())
        val createsBeforeDeletion = control("stats").getInt("createCalls")
        val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, SafDocumentsProvider.FILE_NAME)
        assertTrue("The external deletion was refused", DocumentsContract.deleteDocument(context.contentResolver, document))

        engine.initialSync(treeUri, mirror) { _, _ -> }

        assertTrue("Reopening removed the only undelivered edit", local.isFile)
        assertArrayEquals("The only undelivered bytes changed", undelivered, local.readBytes())
        val stats = control("stats")
        assertFalse("The same reopen recreated the provider document", stats.getBoolean("exists"))
        assertEquals("The same reopen uploaded the unproved mirror copy", createsBeforeDeletion, stats.getInt("createCalls"))
    }

    @Test
    fun createDoesNotChangeProviderWhenUploadJournalCannotBeWritten() = runBlocking {
        control("reset", Bundle().apply { putBoolean("empty", true) })
        engine.initialSync(treeUri, mirror) { _, _ -> }
        val local = File(mirror, SafDocumentsProvider.FILE_NAME)
        assertFalse(local.exists())
        assertFalse(control("stats").getBoolean("exists"))

        val journal = File(context.filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
        assertTrue(journal.mkdirs())
        File(journal, "blocker").writeText("keep")
        val failures = AtomicInteger()
        engine.onWriteBackFailed = { failures.incrementAndGet() }
        engine.startWatching(mirror, treeUri)

        local.writeBytes(EDITED)
        assertTrue("The create produced neither a refusal nor a provider mutation", await {
            failures.get() > 0 || control("stats").getInt("createCalls") > 0
        })
        engine.stopWatching()

        val stats = control("stats")
        assertEquals("A provider document was created without a durable journal", 0, stats.getInt("createCalls"))
        assertEquals("The provider was opened for writing", 0, stats.getInt("writeOpens"))
        assertFalse("The failed create left a provider document", stats.getBoolean("exists"))
        assertArrayEquals("The only local file was lost", EDITED, local.readBytes())
        assertTrue("The refused create must be reported", failures.get() > 0)
    }

    @Test
    fun ordinaryCreateReachesProviderAndSurvivesReopening() = runBlocking {
        control("reset", Bundle().apply { putBoolean("empty", true) })
        engine.initialSync(treeUri, mirror) { _, _ -> }
        val local = File(mirror, SafDocumentsProvider.FILE_NAME)
        assertFalse(local.exists())
        engine.startWatching(mirror, treeUri)

        local.writeBytes(EDITED)
        assertTrue("The watcher never created the complete provider document", await {
            control("stats").getBoolean("exists") && providerBytes().contentEquals(EDITED) &&
                engine.uploadsInFlight().isEmpty()
        })
        engine.stopWatching()
        assertEquals("The control must create exactly one provider document", 1, control("stats").getInt("createCalls"))
        assertTrue("The control never wrote the new provider document", writeOpens() > 0)

        val reopened = File(context.filesDir, "reopened").apply { mkdirs() }
        engine.initialSync(treeUri, reopened) { _, _ -> }
        assertArrayEquals(EDITED, File(reopened, SafDocumentsProvider.FILE_NAME).readBytes())
        assertArrayEquals(EDITED, providerBytes())
    }

    @Test
    fun localEditDuringProviderReadSurvivesReopeningWithBothVersions() {
        val local = File(mirror, SafDocumentsProvider.FILE_NAME)
        val oldTime = 1_600_000_000_000L
        local.writeBytes(ByteArray(ORIGINAL.size) { 'a'.code.toByte() })
        assertTrue(local.setLastModified(oldTime))
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).writeText(
            SafSyncEngine.RECORD_HEADER + "\n${local.name}\t$oldTime\t${ORIGINAL.size}\n",
        )
        val intervening = ByteArray(ORIGINAL.size) { 'b'.code.toByte() }
        val failure = AtomicReference<Throwable>()
        control("blockRead")
        val worker = Thread {
            try {
                runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { start() }
        try {
            assertTrue("The provider never blocked its read", await { control("stats").getBoolean("readBlocked") })
            local.writeBytes(intervening)
            assertTrue(local.setLastModified(oldTime))
        } finally {
            control("releaseRead")
            worker.join(10_000)
        }
        assertFalse("The incoming copy did not finish", worker.isAlive)
        failure.get()?.let { throw AssertionError("The incoming copy failed", it) }
        assertArrayEquals(ORIGINAL, local.readBytes())
        val preserved = mirror.listFiles()!!.filter { it.name.startsWith(local.name + SafSyncEngine.LOCAL_COPY_SUFFIX) }
        assertEquals("The intervening edit needs one preserved copy", 1, preserved.size)
        assertArrayEquals(intervening, preserved.single().readBytes())
        assertArrayEquals(ORIGINAL, providerBytes())

        runBlocking { SafSyncEngine(context).initialSync(treeUri, mirror) { _, _ -> } }
        assertArrayEquals(ORIGINAL, local.readBytes())
        assertArrayEquals(intervening, preserved.single().readBytes())
        assertEquals("Reopening created another conflict copy", 2, mirror.listFiles()!!.size)
    }

    @Test
    fun directoryDeleteKeepsChildAddedAfterTheDeleteWasQueued() = runBlocking {
        openDirectoryFixture()
        val retained = AtomicInteger()
        engine.onKeptOnDevice = { _, directory, _ -> if (directory) retained.incrementAndGet() }
        queueDirectoryDelete()
        control("directoryAdd", Bundle().apply { putByteArray("bytes", EDITED) })
        assertFalse("The editor unexpectedly held the new child", File(mirror, SafDocumentsProvider.NEW_CHILD_ID).exists())

        engine.runWriteBackLoop { false }

        assertEquals("The provider received a destructive recursive delete", 0,
            control("directoryStats").getInt("deleteCalls"))
        assertTrue(control("directoryStats").getBoolean("exists"))
        assertArrayEquals(EDITED, directoryBytes(newChild = true))
        assertEquals("The retained directory was not reported", 1, retained.get())
    }

    @Test
    fun directoryDeleteKeepsChangedChildWithoutItsOwnDeleteEvent() = runBlocking {
        openDirectoryFixture()
        queueDirectoryDelete()
        control("directoryEdit", Bundle().apply { putByteArray("bytes", EDITED) })

        engine.runWriteBackLoop { false }

        assertEquals(0, control("directoryStats").getInt("deleteCalls"))
        assertTrue(control("directoryStats").getBoolean("exists"))
        assertArrayEquals(EDITED, directoryBytes())
    }

    @Test
    fun directoryDeleteKeepsChildrenWhenTheirListingFails() = runBlocking {
        openDirectoryFixture()
        queueDirectoryDelete()
        control("directoryFailListing")

        engine.runWriteBackLoop { false }

        assertEquals(0, control("directoryStats").getInt("deleteCalls"))
        assertTrue(control("directoryStats").getBoolean("exists"))
        assertArrayEquals(ORIGINAL, directoryBytes())
    }

    @Test
    fun ordinaryDirectoryDeleteRemovesKnownChildrenFromProvider() = runBlocking {
        openDirectoryFixture()
        queueDirectoryDelete()

        engine.runWriteBackLoop { false }

        assertEquals("The control never sent its directory delete", 1,
            control("directoryStats").getInt("deleteCalls"))
        assertFalse(control("directoryStats").getBoolean("exists"))
        assertFalse(control("directoryStats").getBoolean("knownExists"))
    }

    @Test
    fun watcherDirectoryDeleteKeepsChildAddedOnlyOnProvider() = runBlocking {
        openDirectoryFixture()
        control("directoryAdd", Bundle().apply { putByteArray("bytes", EDITED) })
        val retained = AtomicInteger()
        engine.onKeptOnDevice = { _, directory, _ -> if (directory) retained.incrementAndGet() }
        engine.startWatching(mirror, treeUri)

        assertTrue(File(mirror, SafDocumentsProvider.DIRECTORY_ID).deleteRecursively())
        assertTrue("The watcher produced neither a retention nor a directory delete", await {
            retained.get() > 0 || !control("directoryStats").getBoolean("exists")
        })
        engine.stopWatching()

        assertTrue("The provider-only child was removed", control("directoryStats").getBoolean("newExists"))
        assertArrayEquals(EDITED, directoryBytes(newChild = true))
        assertTrue("The directory retention was not reported", retained.get() > 0)
    }

    private suspend fun openDirectoryFixture() {
        control("directoryReset", Bundle().apply { putByteArray("bytes", ORIGINAL) })
        engine.initialSync(treeUri, mirror) { _, _ -> }
        assertArrayEquals("The control did not read the known descendant", ORIGINAL,
            File(mirror, SafDocumentsProvider.KNOWN_CHILD_ID).readBytes())
    }

    private fun queueDirectoryDelete() {
        val directory = File(mirror, SafDocumentsProvider.DIRECTORY_ID)
        assertTrue(directory.deleteRecursively())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, directory, mirror, treeUri)
    }

    private fun directoryBytes(newChild: Boolean = false): ByteArray = requireNotNull(
        control("directoryRead", Bundle().apply { putBoolean("newChild", newChild) }).getByteArray("bytes"),
    )

    @Test
    fun unrecordedPartialFileSurvivesRevokedMirrorCleanup() = runBlocking {
        persistFixtureGrant()
        val manager = SafStorageManager(context)
        val savedMirror = manager.getMirrorDir(treeUri)
        engine.initialSync(treeUri, savedMirror) { _, _ -> }
        val record = File(savedMirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        val originalRecord = record.readBytes()
        val local = File(savedMirror, "workspace-notes" + SafSyncEngine.PARTIAL_SUFFIX)
        local.writeBytes(EDITED)
        assertEquals("A live persistent grant must keep its mirror", 0, manager.reclaimRevokedMirrorsSync())
        fixtureGrant(SafProviderGrantReceiver.REVOKE)
        assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == treeUri })
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkUriPermission(
            treeUri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ))

        assertEquals(0, manager.reclaimRevokedMirrorsSync())

        assertArrayEquals(EDITED, local.readBytes())
        assertArrayEquals(originalRecord, record.readBytes())
    }

    @Test
    fun recordedMirrorRequiresConfirmationAfterItsGrantIsRevoked() = runBlocking {
        persistFixtureGrant()
        val manager = SafStorageManager(context)
        val savedMirror = manager.getMirrorDir(treeUri)
        engine.initialSync(treeUri, savedMirror) { _, _ -> }
        val record = File(savedMirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        val originalRecord = record.readBytes()
        assertArrayEquals(ORIGINAL, File(savedMirror, SafDocumentsProvider.FILE_NAME).readBytes())
        assertEquals("A live persistent grant must keep its mirror", 0, manager.reclaimRevokedMirrorsSync())
        assertTrue(savedMirror.exists())
        fixtureGrant(SafProviderGrantReceiver.REVOKE)
        assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == treeUri })
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkUriPermission(
            treeUri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ))

        assertEquals(0, manager.reclaimRevokedMirrorsSync())
        assertArrayEquals(ORIGINAL, File(savedMirror, SafDocumentsProvider.FILE_NAME).readBytes())
        assertArrayEquals(originalRecord, record.readBytes())
        assertFalse(manager.listMirrors().single { it.hash == savedMirror.name }.reclaimable)
        assertEquals(SafStorageManager.RECLAIM_REFUSED,
            manager.reclaimMirror(savedMirror.name, force = false))
        assertArrayEquals(ORIGINAL, File(savedMirror, SafDocumentsProvider.FILE_NAME).readBytes())
        assertArrayEquals(originalRecord, record.readBytes())

        assertTrue(manager.reclaimMirror(savedMirror.name, force = true) >= 0)
        assertEquals(2, manager.sweepDiscardedMirrors())

        assertFalse(savedMirror.exists())
        assertFalse(record.exists())
    }

    private fun persistFixtureGrant() {
        context.contentResolver.takePersistableUriPermission(treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue("The fixture tree must have a persistent read and write grant",
            context.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission && it.isWritePermission
            })
    }

    private fun providerBytes(): ByteArray = requireNotNull(control("read").getByteArray("bytes"))

    private fun writeOpens(): Int = control("stats").getInt("writeOpens")

    private fun control(method: String, extras: Bundle? = null): Bundle =
        requireNotNull(context.contentResolver.call(treeUri, method, null, extras))

    private fun fixtureGrant(action: String) {
        val component = "${instrumentation.context.packageName}/${SafProviderGrantReceiver::class.java.name}"
        val command = "timeout 15s am broadcast --receiver-foreground -a $action -n $component"
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
        assertTrue("Fixture grant command failed: $output", output.contains("Broadcast completed: result=1"))
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        do {
            if (condition()) return true
            Thread.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        return condition()
    }

    private companion object {
        val ORIGINAL = "Complete device copy\n".toByteArray()
        val EDITED = "Edited locally, with every byte preserved\n".toByteArray()
    }
}
