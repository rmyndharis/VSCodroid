package com.vscodroid.storage

import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * That [HostDiskIndependence] is in force, and that what it stands down is still what ships.
 *
 * The first case fails if the service registration or the autodetection switch goes: a new
 * engine would then report the room of the machine, and the cases that open a folder would be
 * back to depending on it. The second keeps the app's own default honest now that no other
 * test reads it: it has to use available space rather than whole-volume capacity.
 */
class HostDiskIndependenceTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `a new engine does not ask the disk how much room there is`() {
        val engine = SafSyncEngine(mockk<Context>(relaxed = true))

        assertEquals(Long.MAX_VALUE, engine.usableSpaceOf(dir))
    }

    @Test
    fun `the default the app ships reads available space`() {
        val volume = object : File("unused-volume") {
            override fun getUsableSpace() = 123L
            override fun getTotalSpace() = 987L
        }

        assertEquals(123L, HostDiskIndependence.shipped(volume))
    }
}
