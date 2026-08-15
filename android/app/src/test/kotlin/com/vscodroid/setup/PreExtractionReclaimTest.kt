package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The reclaim of the pre-pivot trees has to happen before the storage check, not
 * behind it.
 *
 * An upgrade across the pivot carries two trees this version never reads again:
 * the pre-built VS Code Server under `server/vscode-reh`, and the standalone web
 * client under `server/vscode-web`. Together they are hundreds of MB, and
 * removing them is the one thing setup can do to make room for itself.
 *
 * Behind the pre-flight it was gated by the shortfall it cures. A device with
 * less free space than the asset total returned LOW_STORAGE before the deletion
 * was reached, the trees stayed where they were, and the Retry button measured
 * the same shortfall and made the same decision -- for ever, on precisely the
 * installs that had something to reclaim.
 *
 * Free space is stubbed rather than arranged. The pre-flight compares
 * `filesDir.usableSpace` against the asset total baked in at build time, and
 * both sides of that comparison are properties of the machine running the test:
 * a runner with no asset tree needs almost nothing, a developer's checkout needs
 * most of a gigabyte. Overriding the one method decides the branch outright.
 */
class PreExtractionReclaimTest {

    @TempDir
    lateinit var tmp: File

    @TempDir
    lateinit var cacheDir: File

    private lateinit var assets: AssetManager

    /**
     * A `filesDir` that reports no room.
     *
     * A subclass rather than a mock: `File(parent, child)` reads the parent's own
     * path field, which the superclass constructor fills in, so every path
     * derived from this one is the real temporary directory. Only the answer to
     * "how much room is there" changes.
     */
    private class NoRoomDir(real: File) : File(real.absolutePath) {
        override fun getUsableSpace(): Long = 1L
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // An APK with nothing in it. Extraction is never reached in these runs,
        // and if a change ever lets it be reached, an empty APK keeps the run
        // short rather than silently writing 900 MB into a temporary directory.
        assets = mockk()
        every { assets.list(any()) } returns emptyArray()
        every { assets.open(any()) } throws java.io.IOException("absent")
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun context(previousVersionCode: Int): Context {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns mockk(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getInt(any(), any()) } returns previousVersionCode

        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns NoRoomDir(tmp)
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(tmp, "external")
        return context
    }

    /** The two trees a pre-pivot install carries, each with something in it. */
    private fun stalePivotTrees(): Pair<File, File> {
        val server = File(tmp, "server/vscode-reh")
        val web = File(tmp, "server/vscode-web")
        for (dir in listOf(server, web)) {
            assertTrue(File(dir, "out").mkdirs(), "could not stage $dir")
            File(dir, "out/server-main.js").writeText("// the tree this version replaces")
        }
        return server to web
    }

    /**
     * The whole point. Setup still refuses -- one deletion does not conjure a
     * gigabyte -- but it refuses having freed what it could, so the next attempt
     * measures a different device.
     */
    @Test
    fun `an upgrade short of space still reclaims the trees it no longer reads`() {
        val (server, web) = stalePivotTrees()

        val result = runBlocking { FirstRunSetup(context(previousVersionCode = 10)).runSetup() }

        assertEquals(FirstRunSetup.SetupResult.LOW_STORAGE, result)
        assertFalse(
            server.exists(),
            "the previous server tree survived a low-storage refusal, so Retry measures the " +
                "same shortfall for ever with hundreds of MB sitting there unreachable",
        )
        assertFalse(web.exists(), "the orphaned web client tree survived a low-storage refusal")
    }

    /**
     * The control, and it is what makes the test above mean anything: the same
     * run, the same shortfall, the same trees, and an upgrade from a version that
     * postdates the pivot leaves them alone. Without this, a `deleteRecursively`
     * anywhere in the low-storage path would satisfy the assertion above.
     */
    @Test
    fun `an upgrade from a post-pivot version reclaims nothing`() {
        val (server, web) = stalePivotTrees()

        val result = runBlocking { FirstRunSetup(context(previousVersionCode = 11)).runSetup() }

        assertEquals(FirstRunSetup.SetupResult.LOW_STORAGE, result)
        assertTrue(server.exists(), "a post-pivot upgrade deleted the server tree it still uses")
        assertTrue(web.exists(), "a post-pivot upgrade deleted a tree the migration does not own")
    }

    /**
     * A fresh install has no previous versionCode, so there is no migration to
     * run and nothing to reclaim. Stated because the branch is decided by
     * `previousVersionCode > 0` and `0 < PIVOT_VERSION_CODE` is true -- reading
     * the second test alone, a fresh install looks like it should qualify.
     */
    @Test
    fun `a fresh install reclaims nothing`() {
        val (server, web) = stalePivotTrees()

        val result = runBlocking { FirstRunSetup(context(previousVersionCode = 0)).runSetup() }

        assertEquals(FirstRunSetup.SetupResult.LOW_STORAGE, result)
        assertTrue(server.exists(), "a fresh install deleted a tree no migration was asked for")
        assertTrue(web.exists(), "a fresh install deleted a tree no migration was asked for")
    }
}
