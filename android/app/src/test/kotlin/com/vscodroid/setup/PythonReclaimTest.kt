package com.vscodroid.setup

import android.content.Context
import android.content.res.AssetManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests the Python reconciliation call site -- the order it does its two jobs
 * in, not the decision about which entries are superseded, which
 * [SupersededPythonTest] already covers.
 *
 * Reclaiming the previous version's tree and extracting the current one used to
 * sit on the same path, with the extraction first and its "not enough room"
 * branch returning out of the whole method. So the cleanup was gated behind a
 * storage check that the cleanup itself is what relieves: on a device with no
 * room, the ~29 MB sitting in `usr/lib` under a version nothing can load could
 * never be reclaimed, and every later launch made the same measurement and took
 * the same branch. Free space is the one condition under which that tree is
 * worth anything, and it was the one condition under which it was kept.
 */
class PythonReclaimTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager

    /** The runtime this build of the APK carries. */
    private val shipped = "libpython3.13.so"

    private val libDir by lazy { File(filesDir, "usr/lib") }
    private val stalePayload by lazy { File(libDir, "python3.12/os.py") }
    private val staleRuntime by lazy { File(libDir, "libpython3.12.so") }

    /**
     * A `filesDir` that reports a fixed amount of free space.
     *
     * A subclass rather than a mock: `usableSpace` is the only thing that has to
     * lie, and every other call on this object -- the path the code joins
     * `usr/lib` onto -- has to keep working normally.
     */
    private class CappedDir(path: String, private val free: Long) : File(path) {
        override fun getUsableSpace(): Long = free
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        assets = mockk()
        every { assets.list("usr/lib") } returns arrayOf(shipped)

        libDir.mkdirs()
        stalePayload.parentFile?.mkdirs()
        stalePayload.writeText("the stdlib of a version whose interpreter is gone")
        staleRuntime.writeText("the runtime that shipped with it")
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun contextWith(freeBytes: Long): Context = mockk<Context>(relaxed = true).also {
        every { it.filesDir } returns CappedDir(filesDir.path, freeBytes)
        every { it.assets } returns assets
    }

    private fun reconcile(context: Context) {
        FirstRunSetup::class.java
            .getDeclaredMethod("reconcilePythonRuntimeLocked")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    /**
     * The defect. The current runtime is absent, so the method wants to extract
     * it; there is no room, so it cannot. Reclaiming the superseded tree is both
     * the only thing it can still do and the thing that makes the extraction
     * possible next time.
     */
    @Test
    fun `reclaims a superseded Python when there is no room to extract`() {
        assertTrue(staleRuntime.exists() && stalePayload.exists(), "the fixture did not stage a stale tree")
        assertFalse(File(libDir, shipped).exists(), "the fixture must not stage the current runtime")

        reconcile(contextWith(freeBytes = 1L))

        assertFalse(
            staleRuntime.exists(),
            "the superseded runtime survived: the cleanup sits behind the storage check " +
                "whose failure it would relieve, so the space is never given back",
        )
        assertFalse(File(libDir, "python3.12").exists(), "the superseded stdlib survived")
        // Proves the low-storage branch is the one that ran. Without this the
        // test would also pass on a build that quietly found room and extracted,
        // which is a different code path and not the one under test.
        verify {
            Logger.w(any(), match { it.startsWith("Not extracting Python") }, any())
        }
    }

    /**
     * The path that already worked, kept working. Reclaiming has always run when
     * the current runtime is in place, and reordering must not have made it
     * conditional on anything new.
     */
    @Test
    fun `still reclaims when the current runtime is already installed`() {
        File(libDir, shipped).writeText("the runtime this build ships")
        File(libDir, "python3.13").mkdirs()

        reconcile(contextWith(freeBytes = 1L))

        assertFalse(staleRuntime.exists(), "the superseded runtime survived")
        assertFalse(File(libDir, "python3.12").exists(), "the superseded stdlib survived")
        assertTrue(File(libDir, shipped).exists(), "the current runtime was deleted")
        assertTrue(File(libDir, "python3.13").exists(), "the current stdlib was deleted")
    }

    /**
     * An APK that carries no Python at all is a legitimate build shape, and it
     * must not be read as "everything installed is stale". Reordering the
     * cleanup ahead of the extraction moves it closer to this early return, so
     * the guard is worth pinning.
     */
    @Test
    fun `deletes nothing when the APK carries no Python`() {
        every { assets.list("usr/lib") } returns emptyArray()

        reconcile(contextWith(freeBytes = 1L))

        assertTrue(staleRuntime.exists(), "an APK with no Python was read as every version being stale")
        assertTrue(stalePayload.exists(), "an APK with no Python was read as every version being stale")
    }
}
