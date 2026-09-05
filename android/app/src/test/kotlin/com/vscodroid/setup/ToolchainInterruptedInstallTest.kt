package com.vscodroid.setup

import android.content.Context
import android.os.StatFs
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * An install the system stops part-way through, and what the next launch does
 * about it.
 *
 * The install record is written last, so a copy killed in the middle, by the
 * low-memory killer or by a force stop, leaves up to the whole unpacked tree
 * (about 155 MB for Java 17) under nothing that names it. Every pass in
 * `repairInstalledToolchains` reads `toolchains.json`, which for such an install
 * was never written, so all of them stepped over it: no card offered a Remove,
 * no uninstall could find it, and clearing app data was the only way back.
 *
 * The marker each install writes before it copies is the one witness that
 * survives the kill, and these cases are about the four answers the pass has to
 * give it: reclaim, keep, reclaim a toolchain the app no longer offers, and throw
 * away a marker that says nothing.
 */
class ToolchainInterruptedInstallTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packManager: AssetPackManager

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        packManager = mockk(relaxed = true)
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        // Nothing here downloads, but the launch pass shares a process with code
        // that reads it, and android.jar's StatFs throws `Stub!` rather than
        // answering.
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns 0L

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun manager() = ToolchainManager(context)

    /** Private; the launch pass calls it, and only `repairInstalledToolchains` does. */
    private fun reclaimInterruptedInstalls(m: ToolchainManager) =
        ToolchainManager::class.java
            .getDeclaredMethod("reclaimInterruptedInstallsSync")
            .apply { isAccessible = true }
            .invoke(m)

    /** Private, and the point at which both delivery routes converge. */
    private fun installFromDirectory(m: ToolchainManager, pack: String, dir: File) =
        ToolchainManager::class.java
            .getDeclaredMethod("installFromDirectory", String::class.java, File::class.java)
            .apply { isAccessible = true }
            .invoke(m, pack, dir)

    /** What an install writes before it copies a byte. */
    private fun plantMarker(pack: String, body: String): File =
        File(filesDir, "home/.vscodroid/toolchain-installing/$pack.json").apply {
            parentFile?.mkdirs()
            writeText(body)
        }

    /** A tree with something in it, so its removal is visible. */
    private fun plantTree(path: String): File {
        val dir = File(filesDir, path).apply { mkdirs() }
        File(dir, "bin").mkdirs()
        File(dir, "bin/payload").writeText("a hundred and fifty megabytes, in spirit")
        return dir
    }

    private fun record(body: String) =
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(body)

    @Test
    fun `a tree no record names is reclaimed at the next launch`() {
        val marker = plantMarker(
            "toolchain_java",
            """{"name":"java","installRoot":"usr/opt/java"}""",
        )
        val tree = plantTree("usr/opt/java")

        reclaimInterruptedInstalls(manager())

        assertFalse(
            tree.exists(),
            "the half-copied tree is still there, and nothing names it: no card offers " +
                "to remove it and no uninstall can find it",
        )
        assertFalse(marker.exists(), "the marker outlived the reclaim it asked for")
    }

    /**
     * The other direction, and the one that would cost a user a working toolchain.
     *
     * A kill between the record write and the `finally` that removes the marker
     * leaves a marker for an install that finished. The record is what tells the
     * two apart, which is why the pass delegates the decision to
     * `reclaimPartialCopy` rather than deciding for itself.
     */
    @Test
    fun `a tree the record still names survives the reclaim`() {
        val marker = plantMarker(
            "toolchain_java",
            """{"name":"java","installRoot":"usr/opt/java"}""",
        )
        val tree = plantTree("usr/opt/java")
        record("""[{"name":"java","installRoot":"usr/opt/java"}]""")

        reclaimInterruptedInstalls(manager())

        assertTrue(
            File(tree, "bin/payload").exists(),
            "the reclaim deleted a toolchain the record calls installed",
        )
        assertFalse(marker.exists(), "the marker of a finished install was kept")
    }

    /**
     * A tree the record still names is kept, and then looked at again.
     *
     * Keeping it is right: a marker beside a record that names the toolchain is
     * either an install that finished and lost the delete of its own marker, or a
     * reinstall the system stopped part-way, and deleting a working copy on the
     * chance of the second would be the worse mistake. But the second leaves
     * damage that nothing else in the app will ever look for. `copyTo` carries
     * content and not permissions, and the install applies the manifest's execute
     * bits after the copy, so a reinstall stopped in between leaves the binaries
     * it reached without them -- while the record says `execBitsChecked`, which
     * is what makes `repairInstalledToolchainsSync` step over the entry on this
     * launch and on every launch after it. `java` answers "permission denied" for
     * good, and the marker, the one witness that the copy was interrupted, is
     * deleted by the same pass.
     *
     * The reclaim runs earlier in the pass than the repair, so the entry it clears
     * is walked on this launch rather than the next.
     */
    @Test
    fun `a reinstall the system interrupted is walked again by the repair`() {
        plantMarker("toolchain_java", """{"name":"java","installRoot":"usr/opt/java"}""")
        val tree = plantTree("usr/opt/java")
        val binary = File(tree, "bin/java").apply { writeText("half of one build") }
        record(
            """[{"name":"java","installRoot":"usr/opt/java",""" +
                """"binaries":["usr/opt/java/bin/java"],"execBitsChecked":true}]"""
        )
        assertFalse(binary.canExecute(), "the fixture starts from the wrong state")

        manager().repairInstalledToolchains()

        val deadline = System.currentTimeMillis() + 10_000
        while (!binary.canExecute() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(
            binary.canExecute(),
            "the interpreter of a toolchain the record calls installed has no execute " +
                "bit, and the entry is marked repaired, so no later launch will look",
        )
        assertTrue(
            File(tree, "bin/payload").exists(),
            "the tree of a toolchain the record still names was deleted",
        )
    }

    /**
     * A toolchain the app no longer offers strands the most of anyone, and it is
     * exactly the case a registry lookup cannot serve: `ToolchainRegistry.find`
     * answers null for a withdrawn pack and `RETIRED_TOOLCHAINS` carries sizes
     * and no paths, so the install root can only come from the marker. Go's tree
     * was 179 MB.
     */
    @Test
    fun `a withdrawn toolchain's orphan is reclaimed too`() {
        plantMarker("toolchain_go", """{"name":"go","installRoot":"usr/opt/go"}""")
        val tree = plantTree("usr/opt/go")

        reclaimInterruptedInstalls(manager())

        assertFalse(
            tree.exists(),
            "an orphan of a withdrawn toolchain was left behind, which is the one " +
                "nothing else in the app can name",
        )
    }

    /**
     * An install drops its own marker and no one else's.
     *
     * The delete used to sit in the `finally` that gives the pack claim back, on
     * the reasoning that a marker's lifetime is the claim's. It is not: the claim
     * is per process and the marker is what survives one. Keyed on the pack alone,
     * the delete fired on exits that had written nothing -- a delivery whose
     * manifest is missing takes the claim, reports CORRUPT and returns -- and the
     * marker it took was an EARLIER install's, the last thing on disk naming the
     * tree that install left behind.
     *
     * The reclaim at the end is the consequence, not decoration. A marker is only
     * worth keeping because a later launch acts on it, and that is the launch the
     * defect took away: no record, no card, no marker, and ~155 MB that nothing in
     * the app can name.
     *
     * Both delivery paths reach this, which is why it is driven at the point they
     * converge: a Play delivery whose `removePack` a process death interrupted
     * midway arrives here with no manifest in it.
     */
    @Test
    fun `an install with no manifest leaves an earlier install's marker alone`() {
        val marker = plantMarker(
            "toolchain_java",
            """{"name":"java","installRoot":"usr/opt/java"}""",
        )
        val stranded = plantTree("usr/opt/java")
        val delivery = File(filesDir, "delivered-toolchain_java").apply { mkdirs() }

        installFromDirectory(manager(), "toolchain_java", delivery)

        assertTrue(
            marker.exists(),
            "an install that wrote nothing deleted the marker of one that did",
        )

        reclaimInterruptedInstalls(manager())

        assertFalse(
            stranded.exists(),
            "the interrupted install's tree survived the next launch's reclaim, which " +
                "is what a lost marker costs: nothing else on disk names it",
        )
    }

    @Test
    fun `a marker nothing can parse is removed and nothing else is touched`() {
        val marker = plantMarker("toolchain_java", "not json at all")
        val other = plantTree("usr/opt/java")

        reclaimInterruptedInstalls(manager())

        assertFalse(
            marker.exists(),
            "a marker nothing can act on was kept, so it is examined on every launch " +
                "for ever",
        )
        assertTrue(
            File(other, "bin/payload").exists(),
            "an unreadable marker deleted a tree it could not name",
        )
    }

    /**
     * And a marker that is left after it names no toolchain, which is the other
     * half of the same question: a manifest that parses but carries no `name` is
     * one `reclaimPartialCopy` refuses, so the pass has to drop the marker itself
     * or examine it on every launch for the life of the install.
     */
    @Test
    fun `a marker naming no toolchain is removed without deleting anything`() {
        val marker = plantMarker("toolchain_java", """{"installRoot":"usr/opt/java"}""")
        val tree = plantTree("usr/opt/java")

        reclaimInterruptedInstalls(manager())

        assertFalse(marker.exists(), "a marker naming no toolchain was kept")
        assertTrue(
            File(tree, "bin/payload").exists(),
            "a marker with no name deleted a tree on the strength of its install root " +
                "alone, which is not the check reclaimPartialCopy makes",
        )
    }

    /**
     * The wiring, run rather than read: the pass is reached from the launch
     * repair, on its own executor, and its own try/catch means a throw elsewhere
     * in that block cannot take it out.
     *
     * `repairInstalledToolchains` hands the work to `ioExecutor`, so this waits
     * for the effect instead of for the call.
     */
    @Test
    fun `the launch repair pass reclaims an interrupted install`() {
        plantMarker("toolchain_java", """{"name":"java","installRoot":"usr/opt/java"}""")
        val tree = plantTree("usr/opt/java")

        manager().repairInstalledToolchains()

        val deadline = System.currentTimeMillis() + 10_000
        while (tree.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertFalse(
            tree.exists(),
            "the launch repair never reclaimed the interrupted install, so the pass " +
                "exists and nothing calls it",
        )
    }
}
