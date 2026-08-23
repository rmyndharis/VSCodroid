package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import android.os.StatFs
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The install flow, which had no test of any kind.
 *
 * This is the production delivery channel for both audiences at once, and which one
 * a user gets is decided at runtime by [ToolchainManager.shouldUseHttpFallback]: a
 * Play install fetches an asset pack, everything else downloads a ZIP from a GitHub
 * release. Route a Play user down the HTTP path and the download is pointless where
 * Play would have served it; route a sideloaded user down the Play path and
 * `fetch()` fails quietly, because Play Asset Delivery has nothing to answer with on
 * an app it did not install. Neither shows up as an error anyone reads.
 *
 * The other half is [ToolchainManager.installFromDirectory], where both routes
 * converge. Its early returns are the reason first-run setup can finish: the queue
 * advances on reported state, so a pack that returns without reporting anything
 * stalls the progress screen forever rather than being skipped.
 *
 * No socket is opened here. The HTTP branch's background task checks free space
 * before it downloads, and the constructor of `StatFs` is stubbed to report none --
 * deliberately, rather than relying on the JVM's android.jar throwing `Stub!` at
 * whatever the task happens to touch first.
 */
class ToolchainInstallTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var packManager: AssetPackManager

    /** Written by the caller thread and by ioExecutor, so it has to be synchronized. */
    private val events: MutableList<Triple<String, Int, Int>> =
        Collections.synchronizedList(mutableListOf())

    /**
     * Every failure reason reported, so a case can assert on WHY and not only
     * that. The copy cases below are entirely about which of them the user is
     * shown: the same exception used to arrive as NETWORK on one delivery path
     * and INTERNAL on the other, and neither remedy was the right one.
     */
    private val reasons: MutableList<ToolchainFailure> =
        Collections.synchronizedList(mutableListOf())
    private val failed = CountDownLatch(1)

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // Play Core is reached through field initialisation, so it runs before any
        // method can be called on the manager.
        packManager = mockk(relaxed = true)
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        // No free space, so the HTTP branch stops at its pre-flight check and the
        // download is never attempted.
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns 0L

        packageManager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.vscodroid"

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun manager() = ToolchainManager(context).apply {
        onStateChange = { pack, status, pct, why ->
            if (why != null) reasons.add(why)
            events.add(Triple(pack, status, pct))
            if (status == AssetPackStatus.FAILED) failed.countDown()
        }
    }

    private fun installedBy(installer: String?) {
        val info = mockk<InstallSourceInfo>()
        every { info.installingPackageName } returns installer
        every { packageManager.getInstallSourceInfo(any()) } returns info
    }

    /** Private, and the only entry point both delivery routes share. */
    private fun installFromDirectory(m: ToolchainManager, pack: String, dir: File) =
        ToolchainManager::class.java
            .getDeclaredMethod("installFromDirectory", String::class.java, File::class.java)
            .apply { isAccessible = true }
            .invoke(m, pack, dir)

    private fun statuses() = synchronized(events) { events.map { it.second } }

    // ── which route a user gets ──────────────────────────────────────────

    @Test
    fun `a Play install fetches the asset pack and downloads nothing`() {
        installedBy("com.android.vending")

        manager().install("toolchain_java")

        verify(exactly = 1) { packManager.fetch(listOf("toolchain_java")) }
        // The HTTP branch reports PENDING synchronously, before it queues anything,
        // so an empty list here is what distinguishes the two routes.
        assertEquals(emptyList<Int>(), statuses(), "the HTTP branch ran on a Play install")
    }

    /**
     * Play's own legacy package name, which is still the installer of record on
     * a device whose app was installed by an older Store.
     *
     * Recognising only `com.android.vending` sent those devices down the HTTP
     * route: a Play install fetching native binaries from a GitHub release,
     * which is the one thing the Play route exists to avoid, on a device Play
     * would have served the pack to for nothing.
     *
     * NEGATIVE CONTROL: take the name out of PLAY_INSTALLERS and this fails on
     * the fetch, exactly as the sideload case one test below passes.
     */
    @Test
    fun `a Play install recorded under the legacy installer name fetches the pack`() {
        installedBy("com.google.android.feedback")

        manager().install("toolchain_java")

        verify(exactly = 1) { packManager.fetch(listOf("toolchain_java")) }
        assertEquals(emptyList<Int>(), statuses(), "the HTTP branch ran on a Play install")
    }

    @Test
    fun `a sideloaded install downloads over HTTP and never asks Play`() {
        installedBy("com.example.sideloader")

        manager().install("toolchain_java")

        assertTrue(failed.await(10, TimeUnit.SECONDS), "the HTTP task never reported an outcome")
        assertEquals(
            listOf(AssetPackStatus.PENDING, AssetPackStatus.FAILED), statuses(),
            "expected PENDING then the pre-flight refusal, got $events",
        )
        // The event sequence alone does not say WHICH failure this was: downloadViaHttp
        // reports FAILED from the disk-space branch and from three catch blocks, and a
        // runner with no network would produce exactly the same two events after three
        // retries. Deleting the space check would leave the assertion above green while
        // the test opened sockets. Only this line pins the branch.
        verify { Logger.e(any(), match { it.startsWith("Not enough disk space") }, any()) }
        verify(exactly = 0) { packManager.fetch(any()) }
    }

    @Test
    fun `an unreadable install source takes the HTTP route rather than none`() {
        // The catch in shouldUseHttpFallback reads an unreadable installer as
        // not-Play, which is what selects the HTTP route. A device that will not
        // say who installed the app is far more likely to be a sideload than a
        // Play install, and guessing Play there leaves the user with a toolchain
        // that never arrives and no error.
        every { packageManager.getInstallSourceInfo(any()) } throws SecurityException("denied")

        manager().install("toolchain_java")

        assertTrue(failed.await(10, TimeUnit.SECONDS), "the HTTP task never reported an outcome")
        assertEquals(AssetPackStatus.PENDING, statuses().first())
        verify(exactly = 0) { packManager.fetch(any()) }
    }

    @Test
    fun `an unknown toolchain reports FAILED instead of returning silently`() {
        installedBy("com.android.vending")

        manager().install("toolchain_haskell")

        assertEquals(listOf(AssetPackStatus.FAILED), statuses())
        verify(exactly = 0) { packManager.fetch(any()) }
    }

    // ── where both routes converge ───────────────────────────────────────

    @Test
    fun `a pack with no manifest reports FAILED rather than stalling the queue`() {
        val emptyPack = File(filesDir, "pack-empty").apply { mkdirs() }

        installFromDirectory(manager(), "toolchain_java", emptyPack)

        assertEquals(listOf(AssetPackStatus.FAILED), statuses())
    }

    @Test
    fun `a manifest with no name reports FAILED rather than stalling the queue`() {
        val pack = File(filesDir, "pack-nameless").apply { mkdirs() }
        File(pack, "toolchain_java.json").writeText("""{"installRoot":"usr/opt/java"}""")

        installFromDirectory(manager(), "toolchain_java", pack)

        assertEquals(listOf(AssetPackStatus.FAILED), statuses())
    }

    @Test
    fun `a valid manifest is recorded and reported COMPLETED`() {
        // The positive control for the three refusals above: without it they would
        // all still pass if installFromDirectory reported FAILED unconditionally.
        val pack = File(filesDir, "pack-java").apply { mkdirs() }
        File(pack, "toolchain_java.json").writeText("""{"name":"java","installRoot":"usr/opt/java"}""")

        installFromDirectory(manager(), "toolchain_java", pack)

        assertEquals(listOf(AssetPackStatus.COMPLETED), statuses())
        val state = File(filesDir, "home/.vscodroid/toolchains.json").readText()
        assertTrue(state.contains("\"java\""), "the install was reported but not recorded: $state")
    }

    // ── a copy that cannot finish ────────────────────────────────────────

    /**
     * A pack whose tree cannot be copied whole, however much room there is.
     *
     * The obstruction is a regular file where the copy has to create a
     * directory, which fails with a `FileNotFoundException` -- an `IOException`,
     * the same type `copyDirectoryTree` throws for a directory it cannot list
     * and the same one a full disk produces. Arranged this way because the
     * alternatives are not deterministic: filling a temporary filesystem is not
     * available here, and a permission trick answers differently depending on
     * who runs the suite.
     *
     * Both files sit under the manifest's `installRoot`, which is what the
     * reclaim below is asserted against.
     */
    private fun packWithBlockedCopy(): File {
        File(filesDir, "usr/opt/java").mkdirs()
        // Where a directory has to go.
        File(filesDir, "usr/opt/java/blocked").writeText("in the way")
        val pack = File(filesDir, "pack-java").apply { mkdirs() }
        File(pack, "toolchain_java.json").writeText("""{"name":"java","installRoot":"usr/opt/java"}""")
        File(pack, "usr/opt/java/blocked").mkdirs()
        File(pack, "usr/opt/java/blocked/inner").writeText("payload")
        File(pack, "usr/opt/java/ok").writeText("payload")
        return pack
    }

    /**
     * A disk that fills up during the copy is a storage problem on both delivery
     * paths, and used to be reported as neither.
     *
     * The exception unwound out of `installFromDirectory` into whichever catch
     * the caller had: `catch (e: IOException)` in the HTTP download, which
     * reports NETWORK -- "Download failed. Check your connection and try again."
     * for a transfer that had already succeeded -- and `catch (e: Exception)` on
     * the Play path, which reports INTERNAL and asks the user to read a log.
     * Neither remedy is the one that works.
     */
    @Test
    fun `a copy that cannot finish is reported as a storage problem`() {
        installFromDirectory(manager(), "toolchain_java", packWithBlockedCopy())

        assertEquals(listOf(AssetPackStatus.FAILED), statuses(), "expected one failure, got $events")
        assertEquals(
            listOf(ToolchainFailure.STORAGE), synchronized(reasons) { reasons.toList() },
            "a copy that ran out of room told the user to check their connection",
        )
        assertFalse(
            File(filesDir, "home/.vscodroid/toolchains.json").exists(),
            "a copy that failed partway was recorded as an install",
        )
    }

    /**
     * What a failed copy already wrote is taken back.
     *
     * The install record is written last, so a copy that throws leaves up to the
     * whole unpacked tree -- about 155 MB for Java 17 -- under no manifest at
     * all: `getInstalledToolchains` does not name it, the Toolchains screen
     * offers no Remove, the uninstall has no record to work from, and the repair
     * pass only visits records that exist. Clearing app data was the only way
     * back, and on the failure this is about, a full disk, those are exactly the
     * bytes the retry needs.
     */
    @Test
    fun `a copy that cannot finish takes back what it wrote`() {
        installFromDirectory(manager(), "toolchain_java", packWithBlockedCopy())

        assertFalse(
            File(filesDir, "usr/opt/java").exists(),
            "the partial tree is still there, and nothing names it: no card offers to " +
                "remove it and no uninstall can find it",
        )
    }

    /**
     * The other direction, and the reason the reclaim asks the record first: a
     * reinstall over a working copy must not delete the working copy when it
     * fails. Those files belong to the install that succeeded.
     */
    @Test
    fun `a failed reinstall leaves the installed tree it was overwriting`() {
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(
            """[{"name":"java","installRoot":"usr/opt/java"}]"""
        )

        installFromDirectory(manager(), "toolchain_java", packWithBlockedCopy())

        assertTrue(
            File(filesDir, "usr/opt/java").exists(),
            "the failed reinstall deleted the tree of the install that is still recorded",
        )
    }

    /**
     * An install whose record does not survive is not an install, and saying
     * COMPLETED over it is the part that makes the loss silent.
     *
     * `toolchains.json` is four lines written after ~160 MB has already been
     * copied, so it is exactly the write a device that has just filled up fails.
     * What follows from losing it: `getInstalledToolchains()` does not name the
     * toolchain, `toolchain-env.sh` is regenerated from a record that predates it
     * so none of its commands exist in a terminal, and no manifest survives to
     * tell an uninstall which files, symlinks and libraries those megabytes are
     * they cannot be removed from the UI at all. Reported as COMPLETED, the
     * picker shows 100% and Done, the first-run queue moves to the next pack, and
     * the card reads "Install" again after the next launch with nothing said.
     *
     * The failure is arranged as the rest of this package arranges it, by
     * occupying the temporary path `writeAtomically` derives from its
     * destination.
     */
    @Test
    fun `an install whose record cannot be written reports FAILED rather than COMPLETED`() {
        val pack = File(filesDir, "pack-java").apply { mkdirs() }
        File(pack, "toolchain_java.json").writeText("""{"name":"java","installRoot":"usr/opt/java"}""")
        // Non-empty, so the cleanup delete() cannot quietly reclaim it and let the
        // write through, the point is that this write fails.
        val blocker = File(filesDir, "home/.vscodroid/toolchains.json.tmp~")
        assertTrue(blocker.mkdirs(), "could not stage the blocked temp path")
        File(blocker, "occupied").writeText("x")

        installFromDirectory(manager(), "toolchain_java", pack)

        assertEquals(
            listOf(AssetPackStatus.FAILED), statuses(),
            "an unrecorded install was reported to the queue and the user as a finished one",
        )
        assertFalse(
            File(filesDir, "home/.vscodroid/toolchains.json").exists(),
            "the harness did not block the write, so this test proves nothing",
        )
    }
}
