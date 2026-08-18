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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * What the first-run storage gate asks for, and of whom.
 *
 * The gate used to ask every install for the whole asset tree plus slack,
 * 874 MiB at the current pin, whether or not the device already held 810 MiB
 * of it. That was survivable only by coincidence: while `PIVOT_VERSION_CODE`
 * was still ahead of every installed build, each upgrade that reached the check
 * had its previous server tree deleted a few lines earlier and measured a device
 * with that room given back. That coincidence has expired, an upgrade from the
 * Code - OSS tree deletes nothing, and the
 * demand would have been 874 MiB free on top of the 810 MiB already occupied:
 * refused on the splash screen, with a Retry button that measures the same thing
 * for ever and a `MainActivity` that never runs, so nothing the app offers can
 * free a byte.
 *
 * The same arithmetic is what makes a retry after a half-finished unpack
 * possible. A fresh install with 900 MiB free passes the gate, writes 810 MiB,
 * loses one file and aborts; on the retry the old gate measured the ~90 MiB
 * left and asked for 874 again, 810 of which was the partial tree the app had
 * just written and would not delete.
 *
 * **Every figure here is supplied by the test.** `BuildConfig.EXTRACTED_ASSET_BYTES`
 * and `LARGEST_ASSET_BYTES` are measured from `src/main/assets` at build time, so
 * they are 810 MiB and 113 MiB on a developer's checkout and zero on the CI
 * runner, which stubs the asset directories empty. With a zero-byte tree every
 * branch of the gate computes the same number and no test could tell them apart
 * it would pass in CI while proving nothing, which is why [FirstRunSetup]
 * takes the two figures as parameters.
 *
 * The scale is small on purpose: `EXTRACTION_SLACK_BYTES` is a real constant this
 * test cannot set, so it is read back out of [FirstRunSetup.requiredExtractionBytes]
 * and the tree figures are chosen around it. Staging a tree means writing real
 * bytes, and 8 MiB is enough to separate every branch.
 */
class StoragePreflightTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var cacheDir: File

    private lateinit var assets: AssetManager

    private val mb = 1_048_576L

    /** Stand-ins for the APK's asset tree, chosen to sit clear of the slack. */
    private val assetBytes = 8 * mb
    private val largestAssetBytes = 2 * mb

    /**
     * The slack the gate adds, read out of the decision rather than repeated
     * here: it is private, and a copy of it would go stale the moment it moved.
     * With nothing on disk and nothing in the APK it is the whole answer.
     */
    private val slack = FirstRunSetup.requiredExtractionBytes(0, 0, 0)

    /**
     * A `filesDir` that reports a fixed amount of free space.
     *
     * A subclass rather than a mock, following [PreExtractionReclaimTest]:
     * `File(parent, child)` reads the parent's own path field, which the
     * superclass constructor fills in, so every path derived from this one is
     * the real temporary directory and only the answer to "how much room is
     * there" changes.
     */
    private class RoomDir(real: File, private val free: Long) : File(real.absolutePath) {
        override fun getUsableSpace(): Long = free
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // An APK with nothing in it: the gate is what these runs are about, and
        // the figures it works from are supplied to the constructor instead.
        assets = mockk()
        every { assets.list(any()) } returns emptyArray()
        every { assets.open(any()) } throws java.io.IOException("absent")
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    /**
     * @param previousVersionCode 11 rather than 0 in the upgrade cases, and it
     *   matters: `runPreExtractionMigrations` deletes the server tree for
     *   anything older than the pivot, which would empty the tree these tests
     *   stage before the gate ever measured it. 11 is also the case the gate was
     *   wrong about, the upgrade that has no reclaim of its own.
     */
    private fun context(free: Long, previousVersionCode: Int = 11): Context {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns mockk(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getInt(any(), any()) } returns previousVersionCode

        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns RoomDir(filesDir, free)
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        return context
    }

    private fun setup(free: Long, previousVersionCode: Int = 11) =
        FirstRunSetup(context(free, previousVersionCode), assetBytes, largestAssetBytes)

    /**
     * Writes [bytes] of server tree, spread over a few files so the walk has
     * something to walk. Real bytes rather than a sparse trick: 8 MiB costs
     * nothing and a sparse file would measure the same thing by a route the
     * device never takes.
     */
    private fun stageServerTree(bytes: Long) {
        val chunk = ByteArray(64 * 1024)
        val dir = File(filesDir, "server/vscode-reh/out")
        assertTrue(dir.mkdirs(), "could not stage the server tree")
        var remaining = bytes
        var index = 0
        while (remaining > 0) {
            val size = minOf(remaining, 1024L * 1024L)
            File(dir, "part-$index").outputStream().use { out ->
                var left = size
                while (left > 0) {
                    val n = minOf(left, chunk.size.toLong()).toInt()
                    out.write(chunk, 0, n)
                    left -= n
                }
            }
            remaining -= size
            index++
        }
        assertEquals(
            bytes,
            installedExtractionBytes(File(filesDir, "server")),
            "the staged tree is not the size this test thinks it is",
        )
    }

    // ---- the arithmetic ----

    @Test
    fun `a fresh install has to fit the whole tree`() {
        assertEquals(
            assetBytes + slack,
            FirstRunSetup.requiredExtractionBytes(assetBytes, largestAssetBytes, installedBytes = 0),
            "an install with nothing on disk must still be asked for the whole tree; the room " +
                "to hold a second copy of one file is not part of it, because there is no " +
                "first copy to hold",
        )
    }

    @Test
    fun `an install that already holds the tree asks only for the room to rewrite one file`() {
        val required =
            FirstRunSetup.requiredExtractionBytes(assetBytes, largestAssetBytes, installedBytes = assetBytes)

        assertEquals(largestAssetBytes + slack, required)
        assertTrue(
            required < assetBytes + slack,
            "an upgrade is asked for as much as a fresh install, which is what bricks the " +
                "upgrade after next on the splash screen",
        )
    }

    @Test
    fun `a half-written tree is asked for the rest of it`() {
        val required = FirstRunSetup.requiredExtractionBytes(
            assetBytes,
            largestAssetBytes,
            installedBytes = assetBytes / 2,
        )

        assertEquals(assetBytes / 2 + largestAssetBytes + slack, required)
        assertTrue(
            required > FirstRunSetup.requiredExtractionBytes(assetBytes, largestAssetBytes, assetBytes),
            "a damaged tree is treated like a complete one, so the gate waves through a device " +
                "that then runs out of disk mid-unpack",
        )
    }

    @Test
    fun `more on disk than the APK carries does not turn into credit`() {
        assertEquals(
            largestAssetBytes + slack,
            FirstRunSetup.requiredExtractionBytes(
                assetBytes,
                largestAssetBytes,
                installedBytes = assetBytes * 2,
            ),
            "a pin that drops files leaves more on disk than it ships, and the surplus must " +
                "not be subtracted from the headroom the rewrite needs",
        )
    }

    // ---- the measurement ----

    @Test
    fun `the footprint is the bytes under the tree`() {
        val deep = File(filesDir, "server/vscode-reh/node_modules/x")
        assertTrue(deep.mkdirs())
        File(deep, "a").writeText("a".repeat(1000))
        File(filesDir, "server/server.js").writeText("b".repeat(24))

        assertEquals(1024, installedExtractionBytes(File(filesDir, "server")))
    }

    @Test
    fun `an absent tree is worth nothing`() {
        assertEquals(0, installedExtractionBytes(File(filesDir, "server")))
    }

    /**
     * The Copilot aliases link every entry of `copilot-linux-arm64`, the largest
     * of which is 113 MiB, and the extension side links a whole `sdk` directory
     * holding another 96 MiB. Counting a link as its target credits the install
     * for space that is not there, which is the direction that lets the gate pass
     * a device it should refuse.
     */
    @Test
    fun `a symlink cannot credit the same bytes twice`() {
        val real = File(filesDir, "server/vscode-reh/node_modules/@github/copilot-linux-arm64")
        assertTrue(real.mkdirs())
        File(real, "runtime.node").writeText("x".repeat(4096))

        val alias = File(real.parentFile, "copilot-android-arm64")
        assertTrue(alias.mkdirs())
        assumeTrue(
            runCatching {
                Files.createSymbolicLink(
                    File(alias, "runtime.node").toPath(),
                    File(real, "runtime.node").toPath(),
                )
            }.isSuccess,
            "this filesystem does not allow creating symlinks",
        )

        assertEquals(
            4096,
            installedExtractionBytes(File(filesDir, "server")),
            "the aliased runtime.node was counted twice",
        )
    }

    @Test
    fun `a symlinked directory is not walked into`() {
        val real = File(filesDir, "server/vscode-reh/extensions/copilot/sdk")
        assertTrue(real.mkdirs())
        File(real, "runtime.node").writeText("x".repeat(4096))

        assumeTrue(
            runCatching {
                Files.createSymbolicLink(
                    File(real.parentFile!!.parentFile, "sdk-alias").toPath(),
                    real.toPath(),
                )
            }.isSuccess,
            "this filesystem does not allow creating symlinks",
        )

        assertEquals(
            4096,
            installedExtractionBytes(File(filesDir, "server")),
            "the walk followed a directory symlink and counted its contents a second time",
        )
    }

    // ---- the gate, driven through runSetup() ----

    /**
     * The upgrade the old gate would have refused. Free space here is exactly
     * what rewriting the tree needs and nowhere near what unpacking it from
     * nothing would.
     */
    @Test
    fun `an upgrade that already holds the tree is not refused for want of room`() {
        stageServerTree(assetBytes)

        val result = runBlocking { setup(free = largestAssetBytes + slack).runSetup() }

        assertNotEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE,
            result,
            "an install already holding the tree was told to free the size of the tree again, " +
                "which is the state no Retry can reach and no in-app cleanup can fix",
        )
    }

    /**
     * The control, and it is what makes the test above mean anything: same free
     * space, same figures, nothing on disk, and the answer has to flip. Without
     * it, a gate that had simply been loosened would satisfy the assertion above.
     */
    @Test
    fun `a fresh install with only that much room is still refused`() {
        val result = runBlocking { setup(free = largestAssetBytes + slack, previousVersionCode = 0).runSetup() }

        assertEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE,
            result,
            "a device with room for a tenth of the tree was allowed to start unpacking it",
        )
    }

    /**
     * The reason the credit is measured rather than read off the tree's
     * existence, and the retry case, in the state it leaves behind. A tree an
     * interrupted attempt half-wrote is present, and present is exactly what an
     * existence test would call complete.
     */
    @Test
    fun `a half-written tree is refused when only a complete one would fit`() {
        stageServerTree(assetBytes / 2)

        val result = runBlocking { setup(free = largestAssetBytes + slack).runSetup() }

        assertEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE,
            result,
            "the gate read a half-written tree as a complete one, so it passed a device that " +
                "then runs out of disk partway and reports Setup failed instead",
        )
    }

    /**
     * The figure the user is shown has to be the one the run actually wanted.
     * `SplashActivity` builds the message from [FirstRunSetup.requiredStorageMb]
     * the moment a run returns LOW_STORAGE, and a static whole-tree figure there
     * would tell a device that needs 70 MiB to free 874, which is both
     * unreachable and untrue.
     */
    @Test
    fun `the storage message names the figure the refusal was made on`() {
        stageServerTree(assetBytes / 2)

        val result = runBlocking { setup(free = largestAssetBytes + slack).runSetup() }

        assertEquals(FirstRunSetup.SetupResult.LOW_STORAGE, result)
        assertEquals(
            (assetBytes / 2 + largestAssetBytes + slack) / mb,
            FirstRunSetup.requiredStorageMb(),
            "the storage message quotes a figure the gate did not use",
        )
    }
}
