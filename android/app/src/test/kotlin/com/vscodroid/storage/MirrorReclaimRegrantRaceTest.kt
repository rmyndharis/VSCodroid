package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** Retention applies to ordinary copies before and after a folder is granted again. */
class MirrorReclaimRegrantRaceTest : SafMirrorPairEnumerationFixture() {
    @Test
    fun `a real regrant after enumeration preserves the current copy and its record`() {
        var regranted = false
        var savedRecord: ByteArray? = null
        val unique = File(mirror, "terminal-only.txt")
        val removed = manager.reclaimRevokedMirrorsSync { root ->
            val entries = root.listFiles() ?: error("the real mirrors directory was unreadable")
            assertEquals(setOf(mirror.name, record.name), entries.map { it.name }.toSet())
            val replacement = regrantAndSync()
            assertTrue(replacement.hasPersistedPermission(folder))
            unique.writeText("UNIQUE")
            savedRecord = record.readBytes()
            regranted = true
            entries
        }

        assertTrue(regranted, "the pass never enumerated the actual pair")
        assertEquals(0, removed)
        assertEquals("UNIQUE", unique.readText())
        assertEquals("DEVICE", File(mirror, "notes.md").readText())
        assertArrayEquals(savedRecord, record.readBytes())
        assertFalse(discardedEntry(mirror).exists())
        assertFalse(discardedEntry(record).exists())
    }

    @Test
    fun `an orphan record is retained when a real regrant precedes cleanup`() {
        val kept = File(mirror.parentFile, "kept-project")
        assertTrue(mirror.renameTo(kept))
        val replacement = regrant()
        val savedRecord = record.readBytes()
        assertFalse(mirror.exists(), "this control must reach the orphan-record branch")

        assertEquals(0, manager.reclaimRevokedMirrorsSync())

        assertTrue(replacement.hasPersistedPermission(folder))
        assertEquals("DEVICE", File(kept, "notes.md").readText())
        assertFalse(mirror.exists())
        assertArrayEquals(savedRecord, record.readBytes())
    }

    @Test
    fun `a mirror whose folder is never granted again is retained`() {
        val savedRecord = record.readBytes()

        assertEquals(0, manager.reclaimRevokedMirrorsSync())

        assertEquals("DEVICE", File(mirror, "notes.md").readText())
        assertArrayEquals(savedRecord, record.readBytes())
    }

    @Test
    fun `an orphan record without any new grant is removed while its moved copy stays`() {
        val kept = File(mirror.parentFile, "kept-project")
        assertTrue(mirror.renameTo(kept))

        assertEquals(1, manager.reclaimRevokedMirrorsSync())

        assertFalse(record.exists())
        assertFalse(discardedEntry(record).exists())
        assertEquals("DEVICE", File(kept, "notes.md").readText())
    }
}
