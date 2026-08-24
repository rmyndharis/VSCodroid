package com.vscodroid

import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The mapping exists because Android's trim constants are not ordered by
 * severity, so these assert meanings rather than a threshold.
 */
class MemoryPressureTest {

    @Test
    fun `backgrounding is not memory pressure`() {
        // The whole defect: 20 is larger than RUNNING_CRITICAL's 15, and arrives
        // on every app switch, so a >= comparison reported every app switch as
        // critical, and while the monitor killed on that word, killed every idle
        // language server each time the user looked at another app.
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
    fun `real pressure is reported as critical`() {
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
    fun `mild levels are reported as moderate or nothing`() {
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
 * What the page is asked to do when the callback reaches it.
 *
 * The handler is JavaScript inside a Kotlin string, evaluated in a WebView, so
 * there is nothing here that can run it; what this pins is its content. Two
 * things used to be in it and neither may come back.
 *
 * The first is `URL.revokeObjectURL` over every `blob:` name in
 * `performance.getEntries()`. Resource timing lists what the page has FETCHED,
 * not what this app created, so that revoked blob URLs belonging to the
 * workbench: it keeps a `_blobUrlCache` and hands the same URL back on a later
 * load, so an image re-attached to the DOM, a media element re-buffering or a
 * worker rebuilt from a cached URL failed afterwards. `TRIM_MEMORY_BACKGROUND`
 * maps to critical, so it fired on ordinary backgroundings under any system
 * pressure, and the breakage outlasted the pressure that caused it.
 *
 * The second is `gc()`, which is behind V8's `--expose-gc` and does not exist in
 * a WebView. The branch never ran on any device and reads as a memory measure
 * that is being taken.
 */
class MemoryPressureHandlerScriptTest {

    private val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /**
     * The injected handler with its comments blanked, because the paragraph above
     * the script names both of the things this refuses and a raw search would find
     * them there. [SourceScan] owns the extraction and the ceiling it carries.
     */
    private val script: String by lazy {
        SourceScan.withoutComments(
            SourceScan.body(source, "private fun injectMemoryPressureHandler(")
        )
    }

    @Test
    fun `the handler is still there to be checked`() {
        assertTrue(script.contains("onLowMemory")) {
            "the handler no longer registers window.__vscodroid.onLowMemory, so the " +
                "callback onTrimMemory fires lands nowhere and both cases below pass " +
                "by looking at nothing"
        }
    }

    @Test
    fun `nothing revokes a blob URL the page still owns`() {
        assertTrue(!script.contains("revokeObjectURL")) {
            "the memory-pressure handler revokes blob URLs again. The list it walks is " +
                "what the page fetched, not what this app made, so this breaks the next " +
                "load of any blob-backed resource the workbench cached, on every " +
                "backgrounding under system pressure."
        }
        assertTrue(!script.contains("performance.getEntries")) {
            "the handler is reading resource timing again, which is where the revocation " +
                "above got its list from"
        }
    }

    @Test
    fun `nothing calls a garbage collector a WebView does not have`() {
        assertTrue(!script.contains("gc()")) {
            "gc() is behind V8's --expose-gc and is absent in a WebView, so this branch " +
                "cannot run and reads as a memory measure that is being taken"
        }
    }
}
