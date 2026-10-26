package com.vscodroid.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedStoragePressureTest {
    @Test
    fun sharedOrUnselectedVolumesAreRefusedBeforeWriting() {
        for ((enabled, sameDevice, total) in listOf(
            Triple(false, false, 64L * 1024 * 1024),
            Triple(true, true, 64L * 1024 * 1024),
            Triple(true, false, 8L * 1024 * 1024 * 1024),
            Triple(true, false, 64L * 1024 * 1024),
        )) {
            var written = 0L
            assertFalse(createBoundedStoragePressure(enabled, sameDevice, total,
                { 8L * 1024 * 1024 * 1024 }, { written += it.size }))
            assertEquals(0L, written)
        }
    }

    @Test
    fun unchangedCapacityCannotExceedTheByteBudget() {
        var written = 0L
        assertFalse(createBoundedStoragePressure(true, false, 64L * 1024 * 1024,
            { 16L * 1024 * 1024 }, { written += it.size }, { 0L }))
        assertEquals(64L * 1024 * 1024, written)
    }

    @Test
    fun deadlineStopsAllocationAndBoundedPressureCanSucceed() {
        var calls = 0
        var written = 0L
        assertFalse(createBoundedStoragePressure(true, false, 64L * 1024 * 1024,
            { 16L * 1024 * 1024 }, { written += it.size },
            { if (calls++ == 0) 0L else 10_000_000_000L }))
        assertEquals(0L, written)

        var free = 4L * 1024 * 1024 + 128 * 1024
        assertTrue(createBoundedStoragePressure(true, false, 64L * 1024 * 1024,
            { free }, { free -= it.size; written += it.size }, { 0L }))
        assertEquals(128L * 1024, written)
    }
}
