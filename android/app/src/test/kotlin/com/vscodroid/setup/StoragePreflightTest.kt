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

    /** The cap on the `usr/` credit. Non-zero, or that credit cannot be exercised. */
    private val bundledUsrBytes = 4 * mb

    /** The cap on the extensions credit, non-zero for the same reason. */
    private val bundledExtensionBytes = 2 * mb

    /** One of the extensions this build bundles, as `assets/extensions` lists it. */
    private val bundledExtension = "esbenp.prettier-vscode-12.4.0"

    /**
     * The slack the gate adds, read out of the decision rather than repeated
     * here: it is private, and a copy of it would go stale the moment it moved.
     * With nothing on disk and nothing in the APK it is the whole answer.
     */
    private val slack = FirstRunSetup.requiredExtractionBytes(0, 0, 0, 0)

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

    /**
     * A `filesDir` with no room while the npm cache is on disk, and [free] once
     * it is gone.
     *
     * Stubbed rather than arranged for the reason [RoomDir] is, and it has to
     * answer twice: the whole question is whether the gate re-measures after
     * reclaiming, and a fixed figure cannot tell a run that cleared the caches
     * from one that did not.
     */
    private class ReclaimingDir(
        real: File,
        private val cacheDir: File,
        private val free: Long,
    ) : File(real.absolutePath) {
        override fun getUsableSpace(): Long =
            if (File(cacheDir, "npm-cache").exists()) 1L else free
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
        FirstRunSetup(
            context(free, previousVersionCode),
            assetBytes,
            largestAssetBytes,
            bundledUsrBytes,
            bundledExtensionBytes,
        )

    /** As [setup], on a device whose free space answers differently once the cache is gone. */
    private fun setupReclaiming(free: Long): FirstRunSetup {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns mockk(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getInt(any(), any()) } returns 11

        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns ReclaimingDir(filesDir, cacheDir, free)
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        return FirstRunSetup(
            context,
            assetBytes,
            largestAssetBytes,
            bundledUsrBytes,
            bundledExtensionBytes,
        )
    }

    /** Writes [bytes] into one extension directory, the way an install leaves it. */
    private fun stageExtension(dirName: String, bytes: Int) {
        val dir = File(filesDir, "home/.vscodroid/extensions/$dirName")
        assertTrue(dir.mkdirs(), "could not stage $dirName")
        File(dir, "extension.js").writeText("x".repeat(bytes))
    }

    /** Writes [bytes] to one path under `usr/`, wherever the caller puts it. */
    private fun stageUsrFile(relativePath: String, bytes: Int) {
        val file = File(filesDir, "usr/$relativePath")
        assertTrue(file.parentFile!!.mkdirs(), "could not stage $relativePath")
        file.writeText("x".repeat(bytes))
    }

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

    /**
     * The slack has to cover what its own doc says it covers, and 64 MiB did not.
     *
     * `EXTRACTED_ASSET_BYTES` is the sum of logical file lengths. What the unpack
     * actually consumes is that sum rounded up to a filesystem block per file,
     * plus a block for each directory it creates, and the slack is the only term
     * standing for either. Measured over the shipped tree on 2026-08-23: 23,494
     * files and 5,021 directories, 809.5 MiB logical and 872.8 MiB at 4 KiB
     * blocks, so 63.3 MiB of rounding from the files alone before the
     * directories add about 19.6 MiB more. Against that the old 64 MiB asked
     * 873.5 MiB for an unpack consuming roughly 892 on ext4: the gate passed a
     * device, the bar ran for minutes, and the write then met ENOSPC, which is
     * the one direction the constant's own doc names as the one to avoid.
     *
     * f2fs with inline_data keeps the 17,270 files under ~3.4 KiB inside the
     * inode and lands near 821 MiB, which is why the shortfall was invisible on
     * most devices and why the figures below are the ext4 ones.
     *
     * The counts are recorded rather than walked, deliberately. Walking
     * `src/main/assets` would need an `assumeTrue` for the CI runner, which stubs
     * those directories empty, and a case that always skips reports nothing on
     * every merge. Re-measure and update these two numbers when the tree moves;
     * the durable fix is for the build to compute the figure block-rounded, at
     * which point this case can go.
     */
    @Test
    fun `the slack covers the block rounding of the tree this release ships`() {
        val shippedFiles = 23_494L
        val shippedDirectories = 5_021L
        val blockBytes = 4_096L

        // 872.8 MiB block-rounded against 809.5 MiB logical, in bytes.
        val fileRounding = 915_128_320L - 848_756_736L
        val directoryBlocks = shippedDirectories * blockBytes
        val overhead = fileRounding + directoryBlocks

        assertTrue(
            slack >= overhead,
            "the slack is ${slack / mb} MiB against ${overhead / mb} MiB of on-disk overhead " +
                "for $shippedFiles files and $shippedDirectories directories, so the " +
                "pre-flight admits a device the unpack then fills",
        )
    }

    @Test
    fun `a fresh install has to fit the whole tree`() {
        assertEquals(
            assetBytes + slack,
            FirstRunSetup.requiredExtractionBytes(
                assetBytes,
                largestAssetBytes,
                installedBytes = 0,
                extractedTreeBytes = 0,
            ),
            "an install with nothing on disk must still be asked for the whole tree; the room " +
                "to hold a second copy of one file is not part of it, because there is no " +
                "first copy to hold",
        )
    }

    /**
     * The case the gate got wrong, and the reason the headroom reads a tree of its
     * own rather than the total.
     *
     * A fresh install is not a device with an empty `filesDir`. SplashActivity's
     * per-launch repair block runs before this gate, and `setupGitCaBundle` writes
     * `usr/etc/tls/cert.pem` on the way through, so `installedBytes` already
     * carries a few hundred KB of credit by the time the gate is asked. Charged
     * off that, the rewrite headroom fired on an install with nothing unpacked and
     * the gate demanded 986 MB where the unpack needs 873, refusing devices that
     * fit on a screen whose only other control is a Retry that measures the same
     * thing again.
     */
    @Test
    fun `a repair file under usr does not make a fresh install pay the rewrite headroom`() {
        val repairBytes = 320L * 1024

        assertEquals(
            assetBytes - repairBytes + slack,
            FirstRunSetup.requiredExtractionBytes(
                assetBytes,
                largestAssetBytes,
                installedBytes = repairBytes,
                extractedTreeBytes = 0,
            ),
            "a launch-time repair writing into usr/ must not be read as an extracted tree; " +
                "charging the headroom for it refuses fresh installs that fit",
        )
    }

    @Test
    fun `an install that already holds the tree asks only for the room to rewrite one file`() {
        val required =
            FirstRunSetup.requiredExtractionBytes(
                assetBytes,
                largestAssetBytes,
                installedBytes = assetBytes,
                extractedTreeBytes = assetBytes,
            )

        assertEquals(largestAssetBytes + slack, required)
        assertTrue(
            required < assetBytes + slack,
            "an upgrade is asked for as much as a fresh install, which is what bricks the " +
                "upgrade after next on the splash screen",
        )
    }

    /**
     * The headroom is bounded by what is on disk to rewrite, not switched on by
     * a tree being present at all.
     *
     * An upgrade from before the pivot has just had `server/vscode-reh` deleted a
     * few lines above the gate and keeps only the two bootstrap scripts a
     * pre-pivot release put beside it, about 34 KB. As a step from zero to the
     * whole figure that charged the device for a second copy of a 113 MiB file
     * that is not there, and refused installs that fit by roughly that width, on
     * the one upgrade path with nothing else to give back.
     */
    @Test
    fun `a tree too small to hold the biggest file is not charged for a copy of it`() {
        val leftovers = 34L * 1024

        assertEquals(
            (assetBytes - leftovers) + leftovers + slack,
            FirstRunSetup.requiredExtractionBytes(
                assetBytes,
                largestAssetBytes,
                installedBytes = leftovers,
                extractedTreeBytes = leftovers,
            ),
            "a tree of a few KB was charged the room to rewrite the biggest file in the " +
                "APK; nothing already on disk can cost more to replace than the bytes " +
                "already on disk",
        )
    }

    @Test
    fun `a half-written tree is asked for the rest of it`() {
        val required = FirstRunSetup.requiredExtractionBytes(
            assetBytes,
            largestAssetBytes,
            installedBytes = assetBytes / 2,
            extractedTreeBytes = assetBytes / 2,
        )

        assertEquals(assetBytes / 2 + largestAssetBytes + slack, required)
        assertTrue(
            required > FirstRunSetup.requiredExtractionBytes(
                assetBytes, largestAssetBytes, assetBytes, assetBytes,
            ),
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
                extractedTreeBytes = assetBytes * 2,
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
     * The gate itself, on the state a real fresh install is actually in.
     *
     * The arithmetic is pinned elsewhere, but the defect this closes lived at the
     * call site: the headroom was decided from the whole credit, and SplashActivity
     * writes into `usr/` before the gate is ever reached, so a device with nothing
     * unpacked was charged for a rewrite it was not about to do. Every other case
     * here leaves `usr/` empty, so passing the credit again instead of the
     * extracted tree would satisfy all of them.
     *
     * Sized so the two answers differ: enough room for the tree and the slack, not
     * enough for the rewrite headroom on top.
     */
    @Test
    fun `a fresh install is not charged the headroom for a file the repairs left in usr`() {
        File(filesDir, "usr/etc/tls").mkdirs()
        File(filesDir, "usr/etc/tls/cert.pem").writeText("a repair wrote this before the gate ran")

        val result = runBlocking {
            setup(free = assetBytes + slack, previousVersionCode = 0).runSetup()
        }

        assertNotEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE,
            result,
            "a device with room for the whole tree was refused because a launch-time " +
                "repair had left a file in usr/, which the rewrite headroom must not read",
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
     * The extensions directory is credited for the bundled directories in it and
     * for nothing else.
     *
     * It is the same `--extensions-dir` the server installs gallery extensions
     * into, and the gate offered the whole of it with a literal `foreignBytes =
     * 0`, which asserts the directory is ours alone. That was worth 60 KB while
     * everything bundled here was ours; this release bundles five extensions from
     * the gallery, so the cap that credit is measured against grew 800-fold and a
     * device with any gallery installs at all was credited the whole bundled tree
     * for bytes not one of which was on disk.
     *
     * Sized so the credit decides the run: with it the device fits, without it it
     * does not.
     */
    @Test
    fun `a gallery extension does not buy room the unpack still needs`() {
        stageServerTree(assetBytes / 2)
        stageExtension("someone.else-1.0.0", (2 * mb).toInt())

        val result = runBlocking { setup(free = 5 * mb + slack).runSetup() }

        assertEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE,
            result,
            "an extension the user took from the gallery was credited as bytes the unpack " +
                "writes over, so the gate passed a device short of that much room",
        )
    }

    /**
     * The control, and it is what stops the case above passing because the credit
     * stopped working. The same directory, the same size, holding a directory
     * this build does bundle: that one IS bytes extraction writes over, and the
     * same device fits.
     */
    @Test
    fun `a bundled extension already on disk does buy that room`() {
        every { assets.list("extensions") } returns arrayOf(bundledExtension)
        stageServerTree(assetBytes / 2)
        stageExtension(bundledExtension, (2 * mb).toInt())

        val result = runBlocking { setup(free = 5 * mb + slack).runSetup() }

        assertNotEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE,
            result,
            "a bundled extension already unpacked was not credited, so the gate asked an " +
                "upgrade for room it is not going to use",
        )
    }

    /**
     * `usr/` is credited for the entries the APK names and for nothing else.
     *
     * The estimate this replaces came out of `toolchains.json`, which records the
     * installs that FINISHED. `ToolchainManager` takes its install root back on
     * both failures it can see, but a process killed mid-copy reaches neither
     * and leaves about 155 MB in `usr/` that nothing names, the part written
     * into the shared `usr/bin` and `usr/lib` is never reclaimed on any exit,
     * and `npm install -g` and pip never had a record at all. Every one of those
     * bytes was subtracted from nothing and credited as a byte the unpack writes
     * over. The cap kept that harmless while the bundled part of `usr/` was
     * complete, and stopped keeping it harmless exactly where the gate is worth
     * having: a retry after an aborted unpack, where `usr/` is the tree left
     * partial because it is extracted last.
     *
     * Sized so the credit decides the run, like the extensions pair above.
     */
    @Test
    fun `a toolchain in usr does not buy room the unpack still needs`() {
        every { assets.list("usr") } returns arrayOf("lib")
        every { assets.list("usr/lib") } returns arrayOf("libcrypto.so.3")
        stageServerTree(assetBytes / 2)
        stageUsrFile("lib/jvm/java-17-openjdk/runtime", (2 * mb).toInt())

        val result = runBlocking { setup(free = 5 * mb + slack).runSetup() }

        assertEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE,
            result,
            "a toolchain whose install never wrote its record was credited as bytes the " +
                "unpack writes over, so the gate passed a device short of that much room",
        )
    }

    /**
     * The control, and it is what stops the case above passing for a build that
     * simply stopped crediting `usr/` at all. The same directory, the same size,
     * at a path the APK does name: those bytes ARE bytes extraction writes over,
     * and the same device fits.
     */
    @Test
    fun `a bundled library already in usr does buy that room`() {
        every { assets.list("usr") } returns arrayOf("lib")
        every { assets.list("usr/lib") } returns arrayOf("libcrypto.so.3")
        stageServerTree(assetBytes / 2)
        stageUsrFile("lib/libcrypto.so.3", (2 * mb).toInt())

        val result = runBlocking { setup(free = 5 * mb + slack).runSetup() }

        assertNotEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE,
            result,
            "a bundled library already unpacked was not credited, so the gate asked an " +
                "upgrade for room it is not going to use",
        )
    }

    /**
     * The caches are reclaimed before the refusal, not offered behind it.
     *
     * `StorageManager.clearCaches` had exactly one caller, the bridge command the
     * bundled saf-bridge extension sends from inside the loaded workbench. A
     * LOW_STORAGE return guarantees that workbench never loads, so on an updater
     * whose `cacheDir/npm-cache` held more than the shortfall, the only remedy
     * the app owns sat behind the screen the refusal was keeping shut, and the
     * Retry button measured the same device for ever.
     *
     * Free space here answers differently once the cache is gone, which is what
     * lets the run be decided by the reclaim rather than by the figure.
     */
    @Test
    fun `a shortfall the caches would cover is reclaimed rather than refused`() {
        val cached = File(cacheDir, "npm-cache/_cacache")
        assertTrue(cached.mkdirs(), "could not stage the npm cache")
        File(cached, "blob").writeText("x".repeat(4096))

        val result = runBlocking { setupReclaiming(assetBytes + slack).runSetup() }

        assertNotEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE,
            result,
            "setup refused over room its own cache directory was holding, and the only " +
                "way to free it is a command inside the editor this refusal never opens",
        )
        assertFalse(
            File(cacheDir, "npm-cache").exists(),
            "the cache the gate needed was never cleared",
        )
    }

    /**
     * The figure the user is shown is the one the string asks for.
     *
     * `SplashActivity` builds the message from [FirstRunSetup.storageToFreeMb]
     * the moment a run returns LOW_STORAGE, and the string says "Free at least
     * %1$d MB". What must be freed is what the device is MISSING; what the gate
     * computes is what has to BE free, and the two differ by everything the
     * device already has. Quoting the second told a user 200 MB short to clear
     * 873 and sent them to delete photos they did not need to lose.
     *
     * Both figures are asserted, because the test this replaces was sized so
     * they were the same number and could not have told them apart.
     */
    @Test
    fun `the storage message names what the user has to free`() {
        stageServerTree(assetBytes / 2)
        val free = largestAssetBytes + slack

        val result = runBlocking { setup(free = free).runSetup() }

        assertEquals(FirstRunSetup.SetupResult.LOW_STORAGE, result)
        val required = assetBytes / 2 + largestAssetBytes + slack
        assertEquals(
            (required - free + 999_999L) / 1_000_000L,
            FirstRunSetup.storageToFreeMb(),
            "the message names the whole demand rather than the shortfall, so a device short " +
                "by 4 MB is told to free 70",
        )
        assertTrue(
            FirstRunSetup.storageToFreeMb() < required / 1_000_000L,
            "the shortfall and the demand are the same number here, so this case cannot tell " +
                "them apart",
        )
    }

    /**
     * The unit the string promises, which neither case above can tell apart.
     *
     * `error_storage_full` says MB, the user checks it against Android's storage
     * screen, and that screen has been decimal since API 26. Dividing by 1 MiB
     * and calling the answer MB understates the demand by 4.6%, so a user who
     * frees exactly what was asked is refused again, which is the same failure
     * the rounding above exists to prevent, only smaller.
     *
     * A shortfall chosen so the two units cannot give the same answer: one whole
     * MiB is 1.048576 MB, which rounds up to 2.
     */
    @Test
    fun `the figure is in the decimal MB the string names`() {
        stageServerTree(assetBytes / 2)
        val required = assetBytes / 2 + largestAssetBytes + slack
        val free = required - mb

        assertEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE,
            runBlocking { setup(free = free).runSetup() },
        )
        assertEquals(
            2L,
            FirstRunSetup.storageToFreeMb(),
            "a shortfall of one MiB is 1.05 MB, so this is 2 in the unit the string names " +
                "and 1 in the unit the machine counts in. Reading 1 here means the figure " +
                "went back to MiB while the string still says MB",
        )
    }

    /**
     * Rounded up, and that is not a detail. Truncation was harmless while the
     * figure over-stated the answer by everything already free; against the
     * shortfall itself it names up to a megabyte less than the retry measures,
     * so a user who frees exactly what was asked is refused again by the
     * remainder, with nothing on screen to say why.
     */
    @Test
    fun `the figure is rounded up so freeing it is enough`() {
        stageServerTree(assetBytes / 2)
        val required = assetBytes / 2 + largestAssetBytes + slack
        // One byte more than two whole MB short.
        val free = required - (2 * mb + 1)

        assertEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE,
            runBlocking { setup(free = free).runSetup() },
        )
        assertEquals(
            3L,
            FirstRunSetup.storageToFreeMb(),
            "truncation names 2 MB, and a user who frees 2 MB is refused again by one byte",
        )
    }
}
