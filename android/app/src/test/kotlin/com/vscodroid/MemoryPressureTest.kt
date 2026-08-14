package com.vscodroid

import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The mapping exists because Android's trim constants are not ordered by
 * severity, so these assert meanings rather than a threshold.
 */
class MemoryPressureTest {

    @Test
    fun `backgrounding is not memory pressure`() {
        // The whole defect: 20 is larger than RUNNING_CRITICAL's 15, and arrives
        // on every app switch, so a >= comparison killed every idle language
        // server each time the user looked at another app.
        assertEquals(PRESSURE_NONE, memoryPressureOf(TRIM_MEMORY_UI_HIDDEN))
    }

    @Test
    fun `UI_HIDDEN outranks a genuine critical warning numerically`() {
        // Stated as a test so the reason the mapping exists cannot be optimised
        // back into a comparison by someone who assumes the constants are ordered.
        assert(TRIM_MEMORY_UI_HIDDEN > TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(PRESSURE_CRITICAL, memoryPressureOf(TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(PRESSURE_NONE, memoryPressureOf(TRIM_MEMORY_UI_HIDDEN))
    }

    @Test
    fun `real pressure still sheds idle work`() {
        for (level in listOf(
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE,
        )) {
            assertEquals(PRESSURE_CRITICAL, memoryPressureOf(level), "level $level")
        }
    }

    @Test
    fun `mild levels are reported without killing anything`() {
        assertEquals(PRESSURE_MODERATE, memoryPressureOf(TRIM_MEMORY_RUNNING_LOW))
        assertEquals(PRESSURE_NONE, memoryPressureOf(TRIM_MEMORY_RUNNING_MODERATE))
    }

    @Test
    fun `an unknown level is not treated as pressure`() {
        // A constant Android adds later must not clear a threshold by being
        // numerically large, which is exactly how this broke.
        assertEquals(PRESSURE_NONE, memoryPressureOf(999))
        assertEquals(PRESSURE_NONE, memoryPressureOf(-1))
    }
}
