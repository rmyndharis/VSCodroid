package com.vscodroid.storage

import android.content.SharedPreferences
import android.content.UriPermission
import android.net.Uri
import com.vscodroid.util.StorageManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.io.File

abstract class SafMirrorPairEnumerationFixture : SafInitialSyncTestBase() {
    protected lateinit var manager: SafStorageManager
    protected lateinit var folder: Uri
    private class StopPass : Error()

    @BeforeEach
    fun setUpPair() {
        every { context.applicationContext } returns context
        every { resolver.persistedUriPermissions } returns emptyList()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "[]"
        every { context.getSharedPreferences(any(), any()) } returns prefs
        manager = SafStorageManager(context)
        folder = named("content://tree/primary%3APair")
        every { folder.lastPathSegment } returns "primary:Pair"
        mirror = manager.getMirrorDir(folder)
        assertTrue(mirror.mkdirs())
        deviceTree(mapOf("root" to listOf(Child("notes.md", contents = "DEVICE"))))
        sync()
        assertTrue(mirror.isDirectory)
        assertTrue(record.isFile)
        assertEquals("DEVICE", File(mirror, "notes.md").readText())
        assertTrue(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror))
    }

    protected fun discardedEntry(entry: File) = File(entry.parentFile, SafStorageManager.DISCARD_PREFIX + entry.name)

    protected fun commitRemoval() {
        assertTrue(manager.reclaimMirror(mirror.name, force = true) >= 0)
        assertFalse(mirror.exists())
        assertFalse(record.exists())
        assertEquals("DEVICE", File(discardedEntry(mirror), "notes.md").readText())
        assertTrue(discardedEntry(record).isFile)
    }

    protected fun regrant(): SafStorageManager {
        var granted = false
        val permission = mockk<UriPermission> {
            every { uri } returns folder
            every { isReadPermission } returns true
            every { isWritePermission } returns true
        }
        every { resolver.persistedUriPermissions } answers { if (granted) listOf(permission) else emptyList() }
        every { resolver.takePersistableUriPermission(folder, any()) } answers { granted = true }
        mockkStatic(Uri::class)
        val folderText = folder.toString()
        every { Uri.parse(folderText) } returns folder
        return SafStorageManager(context).also {
            it.persistPermission(folder)
            assertTrue(it.hasPersistedPermission(folder))
        }
    }

    protected fun regrantAndSync(): SafStorageManager = regrant().also {
        runBlocking { it.syncToLocal(folder) }
        assertEquals("DEVICE", File(mirror, "notes.md").readText())
        assertTrue(record.isFile)
    }

    protected fun reclaimInOrder(recordFirst: Boolean, committed: Boolean = false): Int = manager.reclaimRevokedMirrorsSync { root ->
        assertEquals(mirror.parentFile.absolutePath, root.absolutePath)
        val actual = root.listFiles() ?: error("the real root was unreadable")
        val byPath = actual.associateBy { it.absolutePath }
        val first = if (committed) discardedEntry(mirror) else mirror
        val second = if (committed) discardedEntry(record) else record
        assertEquals(setOf(first.absolutePath, second.absolutePath), byPath.keys)
        assertTrue(actual.all { it.exists() })
        if (recordFirst) arrayOf(byPath.getValue(second.absolutePath), byPath.getValue(first.absolutePath))
        else arrayOf(byPath.getValue(first.absolutePath), byPath.getValue(second.absolutePath))
    }

    protected fun stopAfterDeleting(entry: File, recordFirst: Boolean) {
        commitRemoval()
        val deletedPath = File(entry.parentFile, SafStorageManager.DISCARD_PREFIX + entry.name).absolutePath
        var stopped = false
        mockkObject(StorageManager)
        every { StorageManager.deleteRecursive(any()) } answers {
            val deleting = firstArg<File>()
            val removed = callOriginal()
            if (deleting.absolutePath == deletedPath) {
                assertFalse(deleting.exists(), "the original deletion did not complete")
                stopped = true
                throw StopPass()
            }
            removed
        }
        try {
            assertThrows(StopPass::class.java) { reclaimInOrder(recordFirst, committed = true) }
            assertTrue(stopped, "the pass did not reach the requested completed deletion")
            val remaining = if (entry == record) discardedEntry(mirror) else discardedEntry(record)
            assertTrue(remaining.exists(), "the pass did not stop with the other committed entry intact")
        } finally {
            unmockkObject(StorageManager)
        }
    }

    protected fun assertPairGone() {
        assertFalse(mirror.exists(), "the ordinary directory survived")
        assertFalse(record.exists(), "the ordinary record survived")
        assertFalse(File(mirror.parentFile, SafStorageManager.DISCARD_PREFIX + mirror.name).exists())
        assertFalse(File(mirror.parentFile, SafStorageManager.DISCARD_PREFIX + record.name).exists())
    }
}
