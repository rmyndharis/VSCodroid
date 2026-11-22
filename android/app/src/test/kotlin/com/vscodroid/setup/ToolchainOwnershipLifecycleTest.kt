package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.system.Os
import android.system.StructStat
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

class ToolchainOwnershipLifecycleTest {
    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private val reports = mutableListOf<Pair<Int, ToolchainFailure?>>()
    private val oldRoot = "usr/lib/jvm/java-17-openjdk"
    private val newRoot = "usr/lib/jvm/java-21-openjdk"
    private val ruby = """{"name":"ruby","installRoot":"usr/lib/ruby"}"""
    private val damaged = """[{"name":"ruby","installRoot":"usr/lib/ruby"},{"name":"""
    private val stateFile get() = File(filesDir, "home/.vscodroid/toolchains.json")

    @BeforeEach
    fun setUp() {
        mockkStatic(Files::class)
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        // Android calls delegate to real filesystem operations in the fixture.
        mockkStatic(Os::class)
        every { Os.lstat(any()) } answers {
            val path = File(firstArg<String>()).toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw FileNotFoundException(path.toString())
            }
            mockk<StructStat>(relaxed = true)
        }
        every { Os.symlink(any(), any()) } answers {
            Files.createSymbolicLink(File(secondArg<String>()).toPath(), File(firstArg<String>()).toPath())
            Unit
        }
        every { Os.readlink(any()) } answers {
            Files.readSymbolicLink(File(firstArg<String>()).toPath()).toString()
        }
        every { Os.rename(any(), any()) } answers {
            Files.move(
                File(firstArg<String>()).toPath(), File(secondArg<String>()).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            Unit
        }

        val assets = mockk<AssetManager>(relaxed = true)
        every { assets.list("usr/lib") } returns arrayOf("libbase.so")
        val appInfo = mockk<ApplicationInfo>(relaxed = true)
        appInfo.nativeLibraryDir = File(filesDir, "native").absolutePath
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"
        every { context.assets } returns assets
        every { context.applicationInfo } returns appInfo
        stateFile.parentFile!!.mkdirs()
        reports.clear()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun manager() = ToolchainManager(context).apply {
        onStateChange = { _, status, _, reason, _ -> reports.add(status to reason) }
    }

    private fun file(path: String, text: String = "payload") = File(filesDir, path).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun link(path: String, target: String): File = File(filesDir, path).apply {
        parentFile?.mkdirs()
        Files.createSymbolicLink(toPath(), File(filesDir, target).toPath())
    }

    private fun marker(pack: String, manifest: String) =
        file("home/.vscodroid/toolchain-installing/$pack.json", manifest)

    private fun reclaim(m: ToolchainManager = manager()) {
        ToolchainManager::class.java.getDeclaredMethod("reclaimInterruptedInstallsSync")
            .apply { isAccessible = true }.invoke(m)
    }

    private fun uninstall(name: String, m: ToolchainManager = manager()) {
        ToolchainManager::class.java.getDeclaredMethod("uninstallSync", String::class.java)
            .apply { isAccessible = true }.invoke(m, name)
    }

    private fun install(m: ToolchainManager, dir: File): Boolean =
        ToolchainManager::class.java
            .getDeclaredMethod("installFromDirectory", String::class.java, File::class.java)
            .apply { isAccessible = true }.invoke(m, "toolchain_java", dir) as Boolean

    private fun javaPack(root: String = newRoot): File = File(filesDir, "staging").apply {
        mkdirs()
        File(this, "toolchain_java.json").writeText(
            """{"name":"java","displayName":"Java 21","installRoot":"$root","binaries":["$root/bin/java"]}""",
        )
        File(this, "$root/bin").mkdirs()
        File(this, "$root/bin/java").writeText("new java payload")
    }

    private fun oldJava(root: String = oldRoot) {
        stateFile.writeText(
            """[{"name":"java","displayName":"Java 17","installRoot":"$root","execBitsChecked":true}]""",
        )
        file("$root/bin/java", "old java payload")
        file("$root/retained.txt", "retained installed bytes")
    }

    private fun blockStateWrite() = blockAtomicWritesTo(stateFile)

    @Test
    fun `uninstall removes canonical managed paths when the context uses its app data alias`() {
        val realData = File(filesDir, "data").apply { mkdir() }
        val userParent = File(filesDir, "user").apply { mkdir() }
        Files.createSymbolicLink(File(userParent, "0").toPath(), realData.toPath())
        File(realData, "com.vscodroid").mkdir()
        filesDir = File(userParent, "0/com.vscodroid/files").apply { mkdir() }
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        stateFile.parentFile!!.mkdirs()
        oldJava()

        uninstall("java")

        assertFalse(File(filesDir, oldRoot).exists(), "the canonical install root survived the alias-based uninstall")
        assertEquals(0, JSONArray(stateFile.readText()).length())
        assertTrue(reports.any { it.first == AssetPackStatus.NOT_INSTALLED })
    }

    private fun assertSettledOrNamed(root: String, mark: File) {
        assertTrue(
            !File(filesDir, root).exists() || mark.isFile,
            "the incoming root survived without a state entry or its install marker",
        )
        if (File(filesDir, root).exists()) {
            assertEquals(root, JSONObject(mark.readText()).getString("installRoot"))
        }
    }

    @Test
    fun `unreadable state preserves a marked installed tree across launch passes`() {
        assertThrows(JSONException::class.java) { JSONArray(damaged) }
        stateFile.writeText(damaged)
        val payload = file("usr/lib/ruby/user-gem/source.rb", "unique local source")
        val mark = marker("toolchain_ruby", ruby)

        repeat(2) { reclaim() }

        assertTrue(payload.isFile, "unreadable ownership was treated as permission to delete")
        assertEquals("unique local source", payload.readText())
        assertTrue(mark.isFile, "the ownership witness disappeared while state was unreadable")
        assertEquals(damaged, stateFile.readText())
    }

    @Test
    fun `install preserves an unreadable record and succeeds after the record is recovered`() {
        assertThrows(JSONException::class.java) { JSONArray(damaged) }
        stateFile.writeText(damaged)
        val rubyPayload = file("usr/lib/ruby/retained.rb", "old ruby bytes")
        val pack = javaPack()
        val m = manager()

        install(m, pack)

        assertEquals(damaged, stateFile.readText(), "install replaced unreadable ownership metadata")
        assertTrue(reports.none { it.first == AssetPackStatus.COMPLETED }, "install reported completion")
        assertTrue(reports.any { it.first == AssetPackStatus.FAILED }, "install did not report refusal")
        assertEquals(
            listOf(AssetPackStatus.FAILED to ToolchainFailure.INTERNAL), reports,
            "an unreadable record was reported as something the user can fix by freeing space",
        )
        assertEquals("old ruby bytes", rubyPayload.readText())

        stateFile.writeText("[$ruby]")
        reports.clear()
        assertTrue(install(m, pack))
        assertEquals(setOf("ruby", "java"), m.getInstalledToolchains().toSet())
        assertEquals(listOf(AssetPackStatus.COMPLETED), reports.map { it.first })
        assertEquals("old ruby bytes", rubyPayload.readText())
    }

    /**
     * A link that cannot be made leaves a tree that does not run. It used to be logged and the install recorded as
     * complete, with only Remove offered afterwards.
     */
    @Test
    fun `an install whose link cannot be made fails with the files reclaimed and no record`() {
        val pack = javaPack().also {
            File(it, "toolchain_java.json").writeText(
                """{"name":"java","displayName":"Java 21","installRoot":"$newRoot","binaries":["$newRoot/bin/java"],""" +
                    """"symlinks":{"java":"$newRoot/bin/java"}}""",
            )
        }
        every { Os.symlink(any(), any()) } throws errno(ENOSPC)
        val m = manager()

        assertFalse(install(m, pack))

        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.STORAGE), reports)
        assertEquals(emptySet<String>(), m.getInstalledToolchains().toSet(), "a tree with no links was recorded as installed")
        assertFalse(File(filesDir, "$newRoot/bin/java").exists(), "the copy was left behind with nothing naming it")
    }

    /**
     * A name the pack links into `usr/bin` can be taken by a folder that holds files. `File.delete` leaves such a
     * folder where it is, and the link is then refused with EEXIST. Freeing space does nothing for that, so the person
     * is not told to: only a disk or a quota with no room left is a lack of storage.
     */
    @Test
    fun `a link refused because a folder of files holds its name is not reported as a lack of storage`() {
        val pack = javaPack().also {
            File(it, "toolchain_java.json").writeText(
                """{"name":"java","displayName":"Java 21","installRoot":"$newRoot","binaries":["$newRoot/bin/java"],""" +
                    """"symlinks":{"java":"$newRoot/bin/java"}}""",
            )
        }
        val kept = file("usr/bin/java/notes.txt", "the person's file")
        every { Os.symlink(any(), any()) } answers {
            try {
                Files.createSymbolicLink(File(secondArg<String>()).toPath(), File(firstArg<String>()).toPath())
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                throw errno(EEXIST)
            }
            Unit
        }
        val m = manager()

        assertFalse(install(m, pack))

        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.INTERNAL), reports)
        assertEquals(emptySet<String>(), m.getInstalledToolchains().toSet(), "a tree with a missing link was recorded as installed")
        assertEquals("the person's file", kept.readText(), "the folder at the link's name lost its files")
    }

    @Test
    fun `only a refusal for room is a lack of storage`() {
        assertEquals(ToolchainFailure.STORAGE, linkFailure(errno(ENOSPC)))
        assertEquals(ToolchainFailure.STORAGE, linkFailure(errno(EDQUOT)))
        for (other in listOf(EEXIST, EACCES, EROFS)) {
            assertEquals(ToolchainFailure.INTERNAL, linkFailure(errno(other)), "errno $other was taken for a lack of room")
        }
        assertEquals(ToolchainFailure.INTERNAL, linkFailure(java.io.IOException("not an errno")))
    }

    /**
     * A removal that did not happen is said as one, and not as a failed install: FAILED drew the card as Failed with a
     * Retry that installs, which downloads the whole pack again over a record that still names every file. UNKNOWN
     * leaves the card on Remove, which is still true.
     */
    @Test
    fun `uninstall keeps an unreadable record and says the removal failed`() {
        stateFile.writeText(damaged)
        val payload = file("usr/lib/ruby/kept.rb", "kept")

        uninstall("ruby")

        assertEquals(damaged, stateFile.readText())
        assertEquals("kept", payload.readText())
        assertEquals(listOf(AssetPackStatus.UNKNOWN to ToolchainFailure.REMOVAL), reports)
    }

    /** A removal that stops on an error is said as a removal failure too, by the catch every removal runs inside. */
    @Test
    fun `a removal that stops on an error says the removal failed`() {
        every { Files.isSymbolicLink(match { it.fileName?.toString() == "toolchains.json" }) } throws
            IllegalStateException("the record cannot be looked at")
        val m = manager()

        m.uninstall("ruby")
        awaitLaunchPass(m)

        assertEquals(listOf(AssetPackStatus.UNKNOWN to ToolchainFailure.REMOVAL), reports)
    }

    @Test
    fun `a removal that cannot take the files keeps the toolchain on Remove and says the removal failed`() {
        val payload = file("home/project/java-21-openjdk/source.java", "unique project source")
        link("usr/lib/jvm", "home/project")
        stateFile.writeText("""[{"name":"java","installRoot":"$newRoot"}]""")

        uninstall("java")

        assertEquals("unique project source", payload.readText())
        assertEquals(listOf("java"), manager().getInstalledToolchains(), "the record lost a toolchain with its files")
        assertEquals(listOf(AssetPackStatus.UNKNOWN to ToolchainFailure.REMOVAL), reports)
    }

    @Test
    fun `absent ownership reclaims an ordinary interrupted tree`() {
        val payload = file("usr/lib/ruby/partial.rb")
        val mark = marker("toolchain_ruby", ruby)

        repeat(2) { reclaim() }

        assertFalse(payload.exists())
        assertFalse(File(filesDir, "usr/lib/ruby").exists())
        assertFalse(mark.exists())
    }

    @Test
    fun `valid ownership preserves an ordinary recorded tree`() {
        stateFile.writeText("[$ruby]")
        val payload = file("usr/lib/ruby/retained.rb", "installed bytes")
        val mark = marker("toolchain_ruby", ruby)

        repeat(2) { reclaim() }

        assertEquals("installed bytes", payload.readText())
        assertTrue(mark.exists(), "a legacy marker must remain retryable without completion identity")
        assertEquals(listOf("ruby"), manager().getInstalledToolchains())
    }

    @Test
    fun `valid ownership keeps earlier records when another pack is installed`() {
        stateFile.writeText("[$ruby]")
        val payload = file("usr/lib/ruby/retained.rb", "installed bytes")
        val m = manager()

        assertTrue(install(m, javaPack()))

        assertEquals(setOf("ruby", "java"), m.getInstalledToolchains().toSet())
        assertEquals("installed bytes", payload.readText())
        assertEquals(listOf(AssetPackStatus.COMPLETED), reports.map { it.first })
    }

    @Test
    fun `failed record write keeps moved incoming bytes reclaimable across launch passes`() {
        oldJava()
        val previous = stateFile.readText()
        val blocker = blockStateWrite()
        val pack = javaPack()
        val m = manager()

        assertFalse(install(m, pack))
        repeat(2) { reclaim() }

        assertEquals(previous, stateFile.readText())
        assertEquals("old java payload", File(filesDir, "$oldRoot/bin/java").readText())
        assertEquals(listOf(AssetPackStatus.FAILED), reports.map { it.first })
        assertSettledOrNamed(newRoot, File(filesDir, "home/.vscodroid/toolchain-installing/toolchain_java.json"))

        blocker.close()
        reports.clear()
        assertTrue(install(m, pack))
        assertEquals(newRoot, JSONArray(stateFile.readText()).getJSONObject(0).getString("installRoot"))
        assertFalse(File(filesDir, oldRoot).exists())
        assertEquals("new java payload", File(filesDir, "$newRoot/bin/java").readText())
    }

    @Test
    fun `a marker for a moved root survives or reclaims the root on repeated launches`() {
        oldJava()
        file("$newRoot/bin/java", "interrupted new bytes")
        val mark = marker("toolchain_java", """{"name":"java","installRoot":"$newRoot"}""")

        repeat(2) { reclaim() }

        assertEquals("old java payload", File(filesDir, "$oldRoot/bin/java").readText())
        assertEquals(oldRoot, JSONArray(stateFile.readText()).getJSONObject(0).getString("installRoot"))
        assertSettledOrNamed(newRoot, mark)
    }

    @Test
    fun `copy failure at a moved root keeps its reclaim witness`() {
        oldJava()
        val pack = javaPack()
        blockAtomicWritesTo(File(filesDir, "$newRoot/bin/java"))

        assertFalse(install(manager(), pack))
        repeat(2) { reclaim() }

        assertEquals("old java payload", File(filesDir, "$oldRoot/bin/java").readText())
        assertEquals(oldRoot, JSONArray(stateFile.readText()).getJSONObject(0).getString("installRoot"))
        assertEquals(listOf(AssetPackStatus.FAILED), reports.map { it.first })
        assertEquals(ToolchainFailure.STORAGE, reports.single().second)
        assertSettledOrNamed(newRoot, File(filesDir, "home/.vscodroid/toolchain-installing/toolchain_java.json"))
    }

    /**
     * A path in `usr/` that holds the wrong kind of entry for what the pack writes there: a folder at a file's name,
     * a file at a folder's name, or a link where the pack writes into a folder. Freeing space changes none of them, so
     * none is a lack of storage. Each is found before the first byte is written, so the person's entry, the tree the
     * record names and its execute-bit mark are all left as they were, and no marker asks a later launch to settle a
     * copy that never started.
     */
    @Test
    fun `a path the pack cannot write to is not a lack of storage, and nothing is written`() {
        file("home/project/elsewhere.txt", "the person's other file")
        // Each case occupies the path and answers whether the occupant is still as it was. The link comes last, so
        // the tree it sits in is never deleted recursively.
        val occupations = mapOf<String, () -> () -> Boolean>(
            "a folder at a file's name" to {
                val notes = file("$newRoot/bin/java/notes.txt", "the person's file")
                ({ notes.readText() == "the person's file" })
            },
            "a file at a folder's name" to {
                val bin = file("$newRoot/bin", "the person's file")
                ({ bin.readText() == "the person's file" })
            },
            "a link where the pack writes into a folder" to {
                val bin = link("$newRoot/bin", "home/project")
                ({ Files.isSymbolicLink(bin.toPath()) })
            },
        )
        for ((why, occupy) in occupations) {
            File(filesDir, "usr").deleteRecursively()
            oldJava()
            val before = stateFile.readText()
            val occupantKept = occupy()
            reports.clear()

            assertFalse(install(manager(), javaPack()), why)

            assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.INTERNAL), reports, why)
            assertEquals(before, stateFile.readText(), "$why: the record changed for a copy that never started")
            assertEquals("old java payload", File(filesDir, "$oldRoot/bin/java").readText(), why)
            assertFalse(
                File(filesDir, "home/.vscodroid/toolchain-installing/toolchain_java.json").exists(),
                "$why: a marker was left for a copy that never started",
            )
            assertFalse(File(filesDir, "home/project/java").exists(), "$why: the copy wrote through the link")
            assertEquals("the person's other file", File(filesDir, "home/project/elsewhere.txt").readText(), why)
            assertTrue(occupantKept(), "$why: the entry in the pack's way was changed")
        }
    }

    /**
     * An earlier install's marker that this one cannot settle stops it before it writes, and none of the three ways
     * that happens is answered by freeing space.
     */
    @Test
    fun `a pending marker that cannot be settled is not reported as a lack of storage`() {
        file("home/project/kept.txt", "unique project file")
        link("usr/opt/linked", "home/project")
        val markers = mapOf(
            "a marker that is not JSON" to """{"name":""",
            "a marker of another toolchain" to ruby,
            "a marker whose files cannot be removed" to """{"name":"java","installRoot":"usr/opt/linked/java"}""",
        )
        for ((why, text) in markers) {
            reports.clear()
            val mark = marker("toolchain_java", text)

            assertFalse(install(manager(), javaPack()), why)

            assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.INTERNAL), reports, why)
            assertEquals(text, mark.readText(), "$why: the earlier marker was replaced")
        }
        assertEquals("unique project file", File(filesDir, "home/project/kept.txt").readText())
    }

    private fun linkedPack(manifestTail: String): File = javaPack().also {
        File(it, "toolchain_java.json").writeText(
            """{"name":"java","displayName":"Java 21","installRoot":"$newRoot","binaries":["$newRoot/bin/java"],""" +
                manifestTail + "}",
        )
    }

    /** A refusal that is not for room decides the reason, whatever is refused after it. */
    @Test
    fun `a link refused for anything but room keeps the failure internal when a later link is refused for room`() {
        val pack = linkedPack(""""symlinks":{"java":"$newRoot/bin/java","jshell":"$newRoot/bin/java"}""")
        var calls = 0
        every { Os.symlink(any(), any()) } answers { throw errno(if (calls++ == 0) EEXIST else ENOSPC) }

        assertFalse(install(manager(), pack))

        assertEquals(2, calls, "the fixture never reached the second link")
        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.INTERNAL), reports)
    }

    @Test
    fun `a library link refused for anything but room is not reported as a lack of storage`() {
        val pack = linkedPack(""""libSymlinks":{"libjvm.so.25":"libjvm.so"}""")
        File(pack, "usr/lib").mkdirs()
        File(pack, "usr/lib/libjvm.so").writeText("library payload")
        every { Os.symlink(any(), any()) } throws errno(EEXIST)
        val m = manager()

        assertFalse(install(m, pack))

        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.INTERNAL), reports)
        assertEquals(emptySet<String>(), m.getInstalledToolchains().toSet(), "a tree with no library link was recorded")
    }

    private val pendingMarker get() = File(filesDir, "home/.vscodroid/toolchain-installing/toolchain_java.json")

    private fun repairedMark(): Boolean = JSONArray(stateFile.readText()).getJSONObject(0).getBoolean("execBitsChecked")

    private fun repair(m: ToolchainManager = manager()) {
        ToolchainManager::class.java.getDeclaredMethod("repairInstalledToolchainsSync")
            .apply { isAccessible = true }.invoke(m)
    }

    /**
     * A reinstall stopped partway over a recorded tree has replaced files without their execute bits, so the first
     * launch after it asks the repair pass to walk the tree. The marker stays until a retry finishes, and the tree
     * does not change while it stands, so asking again on every later launch only re-read the whole tree, under the
     * state lock, on each of them.
     */
    @Test
    fun `a reinstall marker that stays asks for the execute-bit walk once, not on every launch`() {
        val markers = mapOf(
            "a marker with an install identity" to """{"name":"java","installRoot":"$newRoot","installId":"attempt-1"}""",
            "a marker written before install identities" to """{"name":"java","installRoot":"$newRoot"}""",
        )
        for ((why, text) in markers) {
            oldJava(newRoot)
            marker("toolchain_java", text)

            reclaim()
            assertFalse(repairedMark(), "$why: the first launch after the interrupted copy did not ask for the walk")
            repair()
            assertTrue(repairedMark(), "$why: control, the walk marks the entry")
            repeat(2) { reclaim() }

            assertTrue(repairedMark(), "$why: a later launch asked for the walk again over a tree nothing touched")
            assertTrue(pendingMarker.isFile, "$why: the unfinished reinstall lost its marker")
        }
    }

    @Test
    fun `a reinstall that failed in this process is not walked again on every launch`() {
        oldJava(newRoot)
        val blocker = blockAtomicWritesTo(File(filesDir, "$newRoot/bin/java"))

        assertFalse(install(manager(), javaPack()))
        assertEquals(listOf(AssetPackStatus.FAILED to ToolchainFailure.STORAGE), reports)
        assertFalse(repairedMark(), "the failed copy did not ask for the walk")
        assertTrue(pendingMarker.isFile, "control: the unfinished reinstall keeps its marker")
        repair()
        assertTrue(repairedMark(), "control: the walk marks the entry")
        repeat(2) { reclaim() }

        assertTrue(repairedMark(), "a launch asked again for the walk the failure had already asked for")
        blocker.close()
    }

    @Test
    fun `failed record write preserves a same-root installed tree`() {
        oldJava(newRoot)
        blockStateWrite()
        val previous = stateFile.readText()

        assertFalse(install(manager(), javaPack()))

        assertEquals(previous, stateFile.readText())
        assertEquals("retained installed bytes", File(filesDir, "$newRoot/retained.txt").readText())
        assertTrue(File(filesDir, "$newRoot/bin/java").isFile)
        assertEquals(listOf(AssetPackStatus.FAILED), reports.map { it.first })
    }

    @Test
    fun `failed first install reclaims an unrecorded ordinary root`() {
        blockStateWrite()

        assertFalse(install(manager(), javaPack()))

        assertFalse(File(filesDir, newRoot).exists())
        assertFalse(File(filesDir, "home/.vscodroid/toolchain-installing/toolchain_java.json").exists())
        assertFalse(stateFile.exists())
        assertEquals(listOf(AssetPackStatus.FAILED), reports.map { it.first })
    }

    @Test
    fun `uninstall preserves project bytes behind an install-root ancestor link`() {
        val payload = file("home/project/java-21-openjdk/source.java", "unique project source")
        val ancestor = link("usr/lib/jvm", "home/project")
        stateFile.writeText("""[{"name":"java","installRoot":"$newRoot"}]""")

        uninstall("java")

        assertTrue(payload.isFile, "uninstall descended through a redirected root ancestor")
        assertEquals("unique project source", payload.readText())
        assertTrue(Files.isSymbolicLink(ancestor.toPath()))
    }

    @Test
    fun `interrupted reclaim preserves project bytes behind an install-root ancestor link`() {
        val payload = file("home/project/java-21-openjdk/source.java", "unique project source")
        link("usr/lib/jvm", "home/project")
        marker("toolchain_java", """{"name":"java","installRoot":"$newRoot"}""")

        repeat(2) { reclaim() }

        assertTrue(payload.isFile, "interrupted reclaim descended through a redirected root ancestor")
        assertEquals("unique project source", payload.readText())
    }

    @Test
    fun `uninstall preserves project bytes behind an owned-path ancestor link`() {
        val payload = file("home/project/c++/source.hpp", "unique header source")
        link("usr/include", "home/project")
        stateFile.writeText("""[{"name":"cpp","ownedPaths":["usr/include/c++"]}]""")

        uninstall("cpp")

        assertTrue(payload.isFile, "uninstall descended through a redirected owned-path ancestor")
        assertEquals("unique header source", payload.readText())
    }

    @Test
    fun `uninstall preserves a binary behind a redirected bin directory`() {
        val payload = file("home/project/ruby", "unique project file")
        link("usr/bin", "home/project")
        stateFile.writeText("""[{"name":"ruby","binaries":["usr/bin/ruby"]}]""")

        uninstall("ruby")

        assertTrue(payload.isFile, "a manifest binary deleted a project file through its ancestor")
        assertEquals("unique project file", payload.readText())
    }

    @Test
    fun `uninstall preserves a link name behind a redirected bin directory`() {
        val payload = file("home/project/ruby", "unique project file")
        link("usr/bin", "home/project")
        stateFile.writeText("""[{"name":"ruby","symlinks":{"ruby":"usr/lib/ruby/bin/ruby"}}]""")

        uninstall("ruby")

        assertTrue(payload.isFile, "a manifest link name deleted a project file through its ancestor")
        assertEquals("unique project file", payload.readText())
    }

    @Test
    fun `uninstall preserves a library behind a redirected lib directory`() {
        val payload = file("home/project/libruby.so", "unique project library")
        link("usr/lib", "home/project")
        stateFile.writeText("""[{"name":"ruby","libs":["libruby.so"]}]""")

        uninstall("ruby")

        assertTrue(payload.isFile, "a manifest library deleted a project file through its ancestor")
        assertEquals("unique project library", payload.readText())
    }

    @Test
    fun `uninstall preserves a library link name behind a redirected lib directory`() {
        val payload = file("home/project/libruby.so.4", "unique project file")
        link("usr/lib", "home/project")
        stateFile.writeText("""[{"name":"ruby","libSymlinks":{"libruby.so.4":"libruby.so"}}]""")

        uninstall("ruby")

        assertTrue(payload.isFile, "a library link name deleted a project file through its ancestor")
        assertEquals("unique project file", payload.readText())
    }

    @Test
    fun `uninstall removes an ordinary owned tree and preserves its neighbour`() {
        file("$newRoot/bin/java")
        file("usr/include/c++/header.hpp")
        val kept = file("usr/include/user.hpp", "user header")
        stateFile.writeText("""[{"name":"java","installRoot":"$newRoot","ownedPaths":["usr/include/c++"]}]""")

        uninstall("java")

        assertFalse(File(filesDir, newRoot).exists())
        assertFalse(File(filesDir, "usr/include/c++").exists())
        assertEquals("user header", kept.readText())
        assertEquals(listOf(AssetPackStatus.NOT_INSTALLED), reports.map { it.first })
    }

    @Test
    fun `uninstall unlinks a root link without visiting its target`() {
        val payload = file("home/project/source.java", "unique project source")
        val rootLink = link(newRoot, "home/project")
        stateFile.writeText("""[{"name":"java","installRoot":"$newRoot"}]""")

        uninstall("java")

        assertEquals("unique project source", payload.readText())
        assertFalse(Files.exists(rootLink.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    private fun manifestOwnsTree(manifest: JSONObject, root: String): Boolean {
        val trees = mutableListOf<String>()
        manifest.optString("installRoot", "").takeIf { it.isNotEmpty() }?.let(trees::add)
        manifest.optJSONArray("ownedPaths")?.let { paths ->
            for (i in 0 until paths.length()) trees.add(paths.getString(i))
        }
        return trees.any { root == it || root.startsWith("$it/") }
    }

    private fun hasOwnershipWitness(root: String): Boolean {
        val recorded = if (stateFile.isFile) JSONArray(stateFile.readText()) else JSONArray()
        if ((0 until recorded.length()).any { manifestOwnsTree(recorded.getJSONObject(it), root) }) {
            return true
        }
        return File(filesDir, "home/.vscodroid/toolchain-installing").listFiles().orEmpty().any { mark ->
            mark.name.endsWith(".json") && runCatching {
                manifestOwnsTree(JSONObject(mark.readText()), root)
            }.getOrDefault(false)
        }
    }

    @Test
    fun `an unreadable base library listing retains an unsettled library marker`() {
        every { context.assets.list("usr/lib") } throws java.io.IOException("assets unavailable")
        val library = file("usr/lib/libpartial.so", "partial library bytes")
        val mark = marker("toolchain_cpp", """{"name":"cpp","libs":["libpartial.so"]}""")

        repeat(2) { reclaim() }

        assertEquals("partial library bytes", library.readText())
        assertTrue(mark.isFile, "unknown base ownership discarded the remaining library's witness")
        assertFalse(stateFile.exists())
    }

    @Test
    fun `a null base library listing retains an unsettled library marker`() {
        every { context.assets.list("usr/lib") } returns null
        val library = file("usr/lib/libpartial.so", "partial library bytes")
        val mark = marker("toolchain_cpp", """{"name":"cpp","libs":["libpartial.so"]}""")

        repeat(2) { reclaim() }

        assertEquals("partial library bytes", library.readText())
        assertTrue(mark.isFile, "a missing ownership listing was treated as settled cleanup")
        assertFalse(stateFile.exists())
    }

    @Test
    fun `a new valid install cannot strand an earlier differently rooted install`() {
        file("$oldRoot/bin/java", "earlier interrupted bytes")
        marker("toolchain_java", """{"name":"java","installRoot":"$oldRoot"}""")

        install(manager(), javaPack())
        repeat(2) { reclaim() }

        assertTrue(
            !File(filesDir, oldRoot).exists() || hasOwnershipWitness(oldRoot),
            "a valid later install replaced the only witness for the earlier root",
        )
    }

    @Test
    fun `a valid retry with the same pending footprint can complete`() {
        oldJava()
        file("$newRoot/bin/java", "interrupted incoming bytes")
        marker(
            "toolchain_java",
            """{"name":"java","installRoot":"$newRoot","binaries":["$newRoot/bin/java"]}""",
        )

        assertTrue(install(manager(), javaPack()))

        assertEquals(newRoot, JSONArray(stateFile.readText()).getJSONObject(0).getString("installRoot"))
        assertFalse(File(filesDir, oldRoot).exists())
        assertEquals("new java payload", File(filesDir, "$newRoot/bin/java").readText())
        assertFalse(File(filesDir, "home/.vscodroid/toolchain-installing/toolchain_java.json").exists())
        assertEquals(listOf(AssetPackStatus.COMPLETED), reports.map { it.first })
    }

    @Test
    fun `proven base and other toolchain libraries settle a partial library marker`() {
        val base = file("usr/lib/libbase.so", "base bytes")
        val shared = file("usr/lib/libshared.so", "shared bytes")
        val partial = file("usr/lib/libpartial.so", "partial bytes")
        stateFile.writeText("""[{"name":"ruby","libs":["libshared.so"]}]""")
        val mark = marker(
            "toolchain_cpp",
            """{"name":"cpp","libs":["libbase.so","libshared.so","libpartial.so"]}""",
        )

        repeat(2) { reclaim() }

        assertEquals("base bytes", base.readText())
        assertEquals("shared bytes", shared.readText())
        assertFalse(partial.exists())
        assertFalse(mark.exists(), "known owners already account for every retained library")
        assertEquals(listOf("ruby"), manager().getInstalledToolchains())
    }

    @Test
    fun `an added owned path stays recoverable beside a same-root installed record`() {
        stateFile.writeText("[$ruby]")
        val installed = file("usr/lib/ruby/installed.rb", "installed bytes")
        val extra = file("usr/share/ruby-extra/partial.rb", "extra partial bytes")
        val mark = marker(
            "toolchain_ruby",
            """{"name":"ruby","installRoot":"usr/lib/ruby","ownedPaths":["usr/share/ruby-extra"]}""",
        )

        repeat(2) { reclaim() }

        assertEquals("installed bytes", installed.readText())
        assertEquals("extra partial bytes", extra.readText())
        assertTrue(mark.isFile, "the same name did not establish ownership of the added path")

        uninstall("ruby")
        repeat(2) { reclaim() }

        assertFalse(extra.exists())
        assertFalse(mark.exists())
        assertEquals(emptyList<String>(), manager().getInstalledToolchains())
    }

    /**
     * What `Os` throws on a device. The JVM's stand-in for the class keeps no errno from its constructor and has no
     * message, so both are put in here.
     */
    private fun errno(number: Int): android.system.ErrnoException =
        mockk<android.system.ErrnoException> { every { message } returns "symlink failed: errno $number" }.also {
            android.system.ErrnoException::class.java.getField("errno").setInt(it, number)
        }

    private companion object {
        // Linux's numbers, as a device reports them.
        const val EACCES = 13
        const val EEXIST = 17
        const val ENOSPC = 28
        const val EROFS = 30
        const val EDQUOT = 122
    }
}
