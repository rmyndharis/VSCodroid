package com.vscodroid.storage

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files

class MirrorReclaimGateTest : SafMirrorPairEnumerationFixture() {
    @BeforeEach
    fun setUpRemovalPreferences() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "[]"
        every { context.getSharedPreferences(any(), any()) } returns prefs
        manager = SafStorageManager(context)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `automatic cleanup keeps an ordinary revoked copy and its record`(recordFirst: Boolean) {
        val savedRecord = record.readBytes()

        assertEquals(0, reclaimInOrder(recordFirst))

        assertTrue(mirror.isDirectory)
        assertEquals("DEVICE", File(mirror, "notes.md").readText())
        assertArrayEquals(savedRecord, record.readBytes())
        assertFalse(discarded(mirror).exists())
        assertFalse(discarded(record).exists())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a direct write during enumeration stays with the retained copy`(recordFirst: Boolean) {
        val savedRecord = record.readBytes()
        val unique = File(mirror, "terminal-only.txt")
        var enumerated = false
        val removed = manager.reclaimRevokedMirrorsSync { root ->
            val entries = root.listFiles() ?: error("the mirrors directory was unreadable")
            assertEquals(setOf(mirror.absolutePath, record.absolutePath), entries.map { it.absolutePath }.toSet())
            unique.writeText("UNIQUE TERMINAL BYTES")
            assertFalse(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror))
            enumerated = true
            order(entries, recordFirst)
        }

        assertTrue(enumerated)
        assertEquals(0, removed)
        assertEquals("UNIQUE TERMINAL BYTES", unique.readText())
        assertArrayEquals(savedRecord, record.readBytes())
        assertTrue(created.isEmpty() && written.isEmpty())
    }

    @Test
    fun `a synchronized unused copy still requires destructive consent`() {
        val savedRecord = record.readBytes()
        assertTrue(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror))
        assertFalse(manager.listMirrors().single().reclaimable)

        assertEquals(SafStorageManager.RECLAIM_REFUSED, manager.reclaimMirror(mirror.name, force = false))
        assertEquals(0, manager.sweepDiscardedMirrors())

        assertEquals("DEVICE", File(mirror, "notes.md").readText())
        assertArrayEquals(savedRecord, record.readBytes())
        assertFalse(discarded(mirror).exists())
    }

    @Test
    fun `a direct write after listing cannot be deleted by an unconfirmed request`() {
        assertFalse(manager.listMirrors().single().reclaimable)
        val unique = File(mirror, "terminal-only.txt")
        unique.writeText("UNIQUE TERMINAL BYTES")

        assertEquals(SafStorageManager.RECLAIM_REFUSED, manager.reclaimMirror(mirror.name, force = false))
        assertEquals(0, manager.sweepDiscardedMirrors())

        assertEquals("UNIQUE TERMINAL BYTES", unique.readText())
        assertTrue(record.isFile)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `automatic cleanup finishes a pair committed by confirmed removal`(recordFirst: Boolean) {
        assertTrue(manager.reclaimMirror(mirror.name, force = true) >= 0)
        assertTrue(discarded(mirror).isDirectory)
        assertTrue(discarded(record).isFile)

        val removed = manager.reclaimRevokedMirrorsSync { root ->
            val entries = root.listFiles() ?: error("the mirrors directory was unreadable")
            assertEquals(setOf(discarded(mirror).absolutePath, discarded(record).absolutePath), entries.map { it.absolutePath }.toSet())
            order(entries, recordFirst)
        }

        assertEquals(2, removed)
        assertPairGone()
    }

    @Test
    fun `automatic cleanup removes an orphan record while keeping a moved copy`() {
        val savedCopy = File(mirror.parentFile, "kept-project")
        assertTrue(mirror.renameTo(savedCopy))

        assertEquals(1, manager.reclaimRevokedMirrorsSync())

        assertFalse(record.exists())
        assertFalse(discarded(record).exists())
        assertEquals("DEVICE", File(savedCopy, "notes.md").readText())
    }

    @Test
    fun `ordinary and staging records remain while their copy exists`() {
        val partial = File(record.path + SafSyncEngine.PARTIAL_SUFFIX)
        partial.writeText("STAGING RECORD")
        val savedRecord = record.readBytes()

        assertEquals(0, manager.reclaimRevokedMirrorsSync())

        assertArrayEquals(savedRecord, record.readBytes())
        assertEquals("STAGING RECORD", partial.readText())
        assertEquals("DEVICE", File(mirror, "notes.md").readText())
    }

    @Test
    fun `orphan staging records are cleared without deleting a moved copy`() {
        val partial = File(record.path + SafSyncEngine.PARTIAL_SUFFIX)
        partial.writeText("STAGING RECORD")
        val savedCopy = File(mirror.parentFile, "kept-project")
        assertTrue(mirror.renameTo(savedCopy))

        assertEquals(2, manager.reclaimRevokedMirrorsSync())

        assertFalse(record.exists())
        assertFalse(partial.exists())
        assertEquals("DEVICE", File(savedCopy, "notes.md").readText())
    }

    @Test
    fun `a dangling ordinary link retains its record`() {
        val savedCopy = File(mirror.parentFile, "kept-project")
        assertTrue(mirror.renameTo(savedCopy))
        Files.createSymbolicLink(mirror.toPath(), File(mirror.parentFile, "absent-target").toPath())
        val savedRecord = record.readBytes()

        assertEquals(0, manager.reclaimRevokedMirrorsSync())

        assertTrue(Files.isSymbolicLink(mirror.toPath()))
        assertArrayEquals(savedRecord, record.readBytes())
        assertEquals("DEVICE", File(savedCopy, "notes.md").readText())
    }

    @Test
    fun `a directory named like a record is kept as a directory`() {
        assertTrue(record.delete())
        assertTrue(record.mkdir())
        File(record, "local-notes.txt").writeText("UNIQUE RECORD DIRECTORY BYTES")
        val savedCopy = File(mirror.parentFile, "kept-project")
        assertTrue(mirror.renameTo(savedCopy))

        assertEquals(0, manager.reclaimRevokedMirrorsSync())

        assertEquals("UNIQUE RECORD DIRECTORY BYTES", File(record, "local-notes.txt").readText())
        assertEquals("DEVICE", File(savedCopy, "notes.md").readText())
    }

    @Test
    fun `an unused copy permits confirmed removal and later physical deletion`() {
        assertTrue(manager.reclaimMirror(mirror.name, force = true) >= 0)
        assertTrue(discarded(mirror).isDirectory)
        assertFalse(mirror.exists())
        assertEquals(2, manager.sweepDiscardedMirrors())
        assertPairGone()
    }

    private fun discarded(entry: File) = File(entry.parentFile, SafStorageManager.DISCARD_PREFIX + entry.name)

    private fun order(entries: Array<File>, recordFirst: Boolean): Array<File> = entries.sortedBy {
        if (it.name.endsWith(SafSyncEngine.SYNCED_RECORD_SUFFIX) == recordFirst) 0 else 1
    }.toTypedArray()
}
