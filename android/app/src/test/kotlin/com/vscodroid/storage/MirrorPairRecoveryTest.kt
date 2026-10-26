package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class MirrorPairRecoveryTest : SafMirrorPairEnumerationFixture() {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a fresh pass completes an interrupted pair removal`(recordFirst: Boolean) {
        stopAfterDeleting(if (recordFirst) record else mirror, recordFirst)

        // Constructor and deletion mocks are already restored; recovery uses the
        // filesystem's own order and a new manager with no previous pass memo.
        SafStorageManager(context).reclaimRevokedMirrorsSync()

        assertPairGone()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a normal pass removes a committed pair in either order`(recordFirst: Boolean) {
        commitRemoval()
        assertEquals(2, reclaimInOrder(recordFirst, committed = true))
        assertPairGone()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `recovery of either committed entry preserves a regranted copy`(recordFirst: Boolean) {
        stopAfterDeleting(if (recordFirst) record else mirror, recordFirst)
        val replacement = regrantAndSync()
        val unique = File(mirror, ".git/HEAD")
        assertTrue(unique.parentFile.mkdirs())
        unique.writeText("UNIQUE")
        val savedRecord = record.readBytes()

        assertEquals(1, SafStorageManager(context).reclaimRevokedMirrorsSync())

        assertTrue(replacement.hasPersistedPermission(folder))
        assertEquals("DEVICE", File(mirror, "notes.md").readText())
        assertEquals("UNIQUE", unique.readText())
        assertArrayEquals(savedRecord, record.readBytes())
        assertFalse(discardedEntry(mirror).exists())
        assertFalse(discardedEntry(record).exists())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `an unsent edit preserves the directory and record in either order`(recordFirst: Boolean) {
        val unique = File(mirror, ".git/HEAD")
        assertTrue(unique.parentFile.mkdirs())
        unique.writeText("UNIQUE")
        val originalRecord = record.readText()

        assertEquals(0, reclaimInOrder(recordFirst))

        assertTrue(mirror.isDirectory)
        assertEquals("UNIQUE", unique.readText())
        assertEquals("DEVICE", File(mirror, "notes.md").readText())
        assertEquals(originalRecord, record.readText())
        assertFalse(File(mirror.parentFile, SafStorageManager.DISCARD_PREFIX + mirror.name).exists())
        assertFalse(File(mirror.parentFile, SafStorageManager.DISCARD_PREFIX + record.name).exists())
    }
}
