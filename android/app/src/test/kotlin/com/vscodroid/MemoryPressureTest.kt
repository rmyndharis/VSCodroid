package com.vscodroid

import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.vscodroid.util.Logger
import java.io.File

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

/**
 * The wire between that mapping and the file the process monitor reads.
 *
 * Every test above pins [memoryPressureOf] and none of them touched the code
 * that calls it. Replacing the call with the `>=` comparison it replaced — the
 * original defect, restored verbatim — left the whole suite green, because the
 * decision sat inside `onTrimMemory`, which cannot be invoked without an
 * Activity, and the recording sat in a private method that reached `cacheDir`
 * for the one thing it needed.
 *
 * What breaks when this rots is the detector itself: the process monitor stops
 * seeing memory pressure, silently, in an app whose whole design is built
 * around a 32-process limit and 400-700 MB of RAM. A dead detector is worse
 * than no detector, because it produces confidence.
 */
class MemoryPressureWireTest {

    @TempDir
    lateinit var tmpDir: File

    private val file get() = File(tmpDir, "vscodroid-memory-pressure")

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `real pressure reaches the file the monitor reads`() {
        // First, because the test below asserts that nothing was written and
        // "nothing was written" is also what a broken fixture produces. This
        // proves the writing path works here before that one claims it did not
        // run.
        assertEquals(PRESSURE_CRITICAL, applyMemoryPressure(tmpDir, TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(
            PRESSURE_CRITICAL, file.readText(),
            "the severity has to reach ${file.name}, which is what process-monitor.js opens"
        )
    }

    @Test
    fun `backgrounding writes nothing, whatever its number`() {
        // TRIM_MEMORY_UI_HIDDEN is 20 and RUNNING_CRITICAL is 15, so a `>=`
        // comparison calls an ordinary app switch critical. This is the value
        // that discriminates: any fixture below 15 would pass against the
        // comparison too.
        val sentinel = "left alone"
        file.writeText(sentinel)

        assertEquals(PRESSURE_NONE, applyMemoryPressure(tmpDir, TRIM_MEMORY_UI_HIDDEN))
        assertEquals(
            sentinel, file.readText(),
            "an app switch must not be recorded as pressure; it arrives on every backgrounding"
        )
    }

    @Test
    fun `a failed write is swallowed rather than thrown at the caller`() {
        // The destination is a directory, so writeText throws. This runs inside
        // onTrimMemory, a lifecycle callback: letting it escape would turn a
        // missing hint into a crash on every app switch.
        assertTrue(file.mkdirs(), "the fixture must actually block the write")

        assertEquals(
            PRESSURE_CRITICAL, applyMemoryPressure(tmpDir, TRIM_MEMORY_RUNNING_CRITICAL),
            "the severity is still the answer even when it could not be recorded"
        )
    }
}
