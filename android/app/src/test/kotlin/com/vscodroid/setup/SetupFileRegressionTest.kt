package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

private fun fixtureContext(files: File, record: File, assets: AssetManager? = null): Context {
    val stored = if (record.isFile) JSONObject(record.readText()) else JSONObject()
    val pending = linkedMapOf<String, Any>()
    val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    every { editor.putBoolean(any(), any()) } answers {
        pending[firstArg()] = secondArg<Boolean>()
        editor
    }
    every { editor.putStringSet(any(), any()) } answers {
        pending[firstArg()] = JSONArray(secondArg<Set<String>>().toList())
        editor
    }
    every { editor.commit() } answers {
        pending.forEach { (key, value) -> stored.put(key, value) }
        record.parentFile.mkdirs()
        record.writeText(stored.toString())
        pending.clear()
        true
    }
    val prefs = mockk<SharedPreferences>(relaxed = true)
    every { prefs.edit() } returns editor
    every { prefs.getBoolean(any(), any()) } answers {
        stored.optBoolean(firstArg(), secondArg())
    }
    every { prefs.getInt(any(), any()) } answers { stored.optInt(firstArg(), secondArg()) }
    every { prefs.getString(any(), any()) } answers {
        val key = firstArg<String>()
        if (stored.has(key)) stored.getString(key) else secondArg()
    }
    every { prefs.getStringSet(any(), any()) } answers {
        val array = stored.optJSONArray(firstArg())
        if (array == null) secondArg<Set<String>?>()?.toMutableSet() else
            (0 until array.length()).mapTo(HashSet()) { array.getString(it) }
    }
    val resources = mockk<Resources>()
    every { resources.configuration } returns Configuration().apply { smallestScreenWidthDp = 411 }
    return mockk<Context>(relaxed = true).also { context ->
        every { context.filesDir } returns files
        every { context.cacheDir } returns File(files, "cache")
        every { context.resources } returns resources
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/com.vscodroid-fixture/lib/arm64"
        }
        every { context.packageName } returns "com.vscodroid"
        every { context.getSharedPreferences(any(), any()) } returns prefs
        if (assets != null) every { context.assets } returns assets
    }
}

class SetupFileRegressionTest {
    @TempDir lateinit var root: File

    @BeforeEach
    fun quietPlatformLogging() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
    }

    @AfterEach
    fun restorePlatformLogging() = unmockkObject(Logger)

    private val record get() = File(root, "preferences.json")
    private val files get() = File(root, "files").apply { mkdirs() }
    private fun context(assets: AssetManager? = null) = fixtureContext(files, record, assets)
    private fun setup(context: Context) = FirstRunSetup(context, 1024, 128, 0, 0)

    private fun settings() = """
        {
            "[markdown]": {
            "editor.wordWrap": "on",
            "editor.fontSize": 17
            },
            "editor.wordWrap": "on",
            "git.path": "/custom/git"
        }
    """.trimIndent()

    @Test
    fun `equal indentation does not make a nested preference a root default`() {
        val result = requireNotNull(pruneMovedDefaults(settings(), true))
        val parsed = JSONObject(result)
        assertAll(
            { assertFalse(parsed.has("editor.wordWrap"), "the root default must still be removed") },
            { assertEquals("on", parsed.getJSONObject("[markdown]").optString("editor.wordWrap")) },
            { assertEquals(17, parsed.getJSONObject("[markdown]").getInt("editor.fontSize")) },
            { assertEquals("/custom/git", parsed.getString("git.path")) },
        )
    }

    @Test
    fun `a settings rewrite preserves the nested preference through the next startup`() {
        val firstContext = context()
        val file = File(Environment.getMachineSettingsPath(firstContext))
        file.parentFile.mkdirs()
        file.writeText(settings())

        setup(firstContext).updateSettingsNativeLibPaths()
        val first = file.readText()
        val flag = JSONObject(record.readText()).getBoolean("moved_defaults_pruned")
        setup(context()).updateSettingsNativeLibPaths()

        assertAll(
            { assertTrue(flag, "a successful pass must commit its one-time record") },
            { assertFalse(JSONObject(first).has("editor.wordWrap")) },
            { assertEquals("on", JSONObject(first).getJSONObject("[markdown]").optString("editor.wordWrap")) },
            { assertEquals(first, file.readText(), "the next startup should be settled") },
        )
    }

    @Test
    fun `a refused settings write keeps all bytes and the retry preserves nested preferences`() {
        val firstContext = context()
        val file = File(Environment.getMachineSettingsPath(firstContext))
        file.parentFile.mkdirs()
        file.writeText(settings())
        val blocker = File(file.parentFile, "${file.name}.tmp~").apply { mkdirs() }
        val occupied = File(blocker, "occupied").apply { writeText("fixture") }

        setup(firstContext).updateSettingsNativeLibPaths()
        val afterRefusal = file.readText()
        val prematureFlag = record.isFile && JSONObject(record.readText()).optBoolean("moved_defaults_pruned")
        assertTrue(occupied.delete())
        assertTrue(blocker.delete())
        setup(context()).updateSettingsNativeLibPaths()
        val afterRetry = JSONObject(file.readText())

        assertAll(
            { assertEquals(settings(), afterRefusal, "atomic refusal must preserve the original") },
            { assertFalse(prematureFlag, "a refused write must leave the pass retryable") },
            { assertFalse(afterRetry.has("editor.wordWrap")) },
            { assertEquals("on", afterRetry.getJSONObject("[markdown]").optString("editor.wordWrap")) },
            { assertTrue(JSONObject(record.readText()).getBoolean("moved_defaults_pruned")) },
        )
    }

    private fun migrate(from: Int) {
        val assets = mockk<AssetManager>()
        every { assets.list(any()) } returns emptyArray()
        FirstRunSetup::class.java.getDeclaredMethod("runPreExtractionMigrations", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(setup(context(assets)), from)
    }

    private val legacyRoots get() = listOf("server/vscode-reh", "server/vscode-web")

    @Test
    fun `legacy cleanup unlinks nested project links and its retry keeps the replacement tree`() {
        val projects = legacyRoots.mapIndexed { index, path ->
            val project = File(root, "projects/keep$index").apply { mkdirs() }
            File(project, "index.js").writeText("project-$index")
            val old = File(files, path).apply { mkdirs() }
            File(old, "old.js").writeText("obsolete")
            val modules = File(old, "node_modules").apply { mkdirs() }
            Files.createSymbolicLink(File(modules, "project").toPath(), project.toPath())
            project
        }

        migrate(10)
        val removed = legacyRoots.map { !File(files, it).exists() }
        val keptBytes = projects.map { File(it, "index.js").takeIf(File::isFile)?.readText() }
        val firstFlag = JSONObject(record.readText()).getBoolean("pivot_tree_reclaimed")
        legacyRoots.forEach { path ->
            val replacement = File(files, path).apply { mkdirs() }
            File(replacement, "new.js").writeText("partial replacement")
        }
        migrate(10)

        assertAll(
            { assertTrue(removed.all { it }, "both obsolete trees must be removed") },
            { assertEquals(listOf("project-0", "project-1"), keptBytes, "linked project files were deleted") },
            { assertTrue(firstFlag, "the cleanup must commit its record") },
            { assertTrue(legacyRoots.all { File(files, "$it/new.js").readText() == "partial replacement" }) },
        )
    }

    @Test
    fun `a legacy root that is a project link is unlinked without clearing the project`() {
        val projects = legacyRoots.mapIndexed { index, path ->
            val project = File(root, "projects/root$index").apply { mkdirs() }
            File(project, "index.js").writeText("project-$index")
            File(files, path).parentFile.mkdirs()
            Files.createSymbolicLink(File(files, path).toPath(), project.toPath())
            project
        }
        migrate(10)
        assertAll(
            { assertTrue(legacyRoots.none { Files.isSymbolicLink(File(files, it).toPath()) }) },
            { assertEquals(listOf("project-0", "project-1"), projects.map {
                File(it, "index.js").takeIf(File::isFile)?.readText()
            }) },
        )
    }

    @Test
    fun `dangling legacy root links are removed before the completion record`() {
        legacyRoots.forEachIndexed { index, path ->
            File(files, path).parentFile.mkdirs()
            Files.createSymbolicLink(File(files, path).toPath(), File(root, "missing$index").toPath())
        }
        migrate(10)
        assertAll(
            { assertTrue(legacyRoots.none { Files.isSymbolicLink(File(files, it).toPath()) }) },
            { assertTrue(JSONObject(record.readText()).getBoolean("pivot_tree_reclaimed")) },
        )
    }

    @Test
    fun `post-pivot trees and their linked projects are left alone`() {
        legacyRoots.forEachIndexed { index, path ->
            val project = File(root, "projects/post$index").apply { mkdirs() }
            File(project, "index.js").writeText("keep")
            val old = File(files, path).apply { mkdirs() }
            File(old, "current.js").writeText("current")
            Files.createSymbolicLink(File(old, "project").toPath(), project.toPath())
        }
        migrate(11)
        assertAll(
            { assertTrue(legacyRoots.all { File(files, "$it/current.js").readText() == "current" }) },
            { assertTrue((0..1).all { File(root, "projects/post$it/index.js").readText() == "keep" }) },
            { assertFalse(record.exists(), "no pivot record is owed for this version") },
        )
    }

    @Test
    fun `retirement keeps identifiable gallery copies while removing old bundled copies`() {
        val gallery = "eamodio.gitlens-2026.8.1-universal"
        val bundled = "eamodio.gitlens-2026.2.1114"
        assertEquals(listOf(bundled), retiredFetchedExtensionDirs(listOf(gallery, bundled)))
    }

    @Test
    fun `a gallery install in both profiles survives the first retirement pass and later startup`() {
        val shipped = "vscodroid.vscodroid-welcome-1.0.0"
        val assets = mockk<AssetManager>()
        every { assets.list(any()) } returns emptyArray()
        every { assets.list("extensions") } returns arrayOf(shipped)
        every { assets.list("extensions/$shipped") } returns arrayOf("package.json")
        every { assets.open("extensions/$shipped/package.json") } answers {
            ByteArrayInputStream("""{"publisher":"vscodroid","name":"vscodroid-welcome","version":"1.0.0"}""".toByteArray())
        }
        val extensions = File(files, "home/.vscodroid/extensions").apply { mkdirs() }
        val gallery = File(extensions, "eamodio.gitlens-2026.8.1-universal")
        val oldBundle = File(extensions, "eamodio.gitlens-2026.2.1114").apply { mkdirs() }
        File(oldBundle, "package.json").writeText("""{"publisher":"eamodio","name":"gitlens","version":"2026.2.1114"}""")
        val default = File(extensions, "extensions.json")
        val named = File(files, "home/.vscodroid/data/User/profiles/named/extensions.json")
        named.parentFile.mkdirs()
        val state = File(files, "home/.vscodroid/data/User/globalStorage/eamodio.gitlens/state.json")
        state.parentFile.mkdirs()
        state.writeText("user preferences")

        fun installGallery() {
            gallery.mkdirs()
            File(gallery, "package.json").writeText("""{"publisher":"eamodio","name":"gitlens","version":"2026.8.1"}""")
            val entry = JSONObject().put("identifier", JSONObject().put("id", "eamodio.gitlens"))
                .put("version", "2026.8.1").put("relativeLocation", gallery.name)
                .put("location", JSONObject().put("path", gallery.path).put("scheme", "file"))
                .put("metadata", JSONObject().put("source", "gallery").put("targetPlatform", "universal"))
            val manifest = JSONArray().put(entry).toString()
            default.writeText(manifest)
            named.writeText(manifest)
        }
        fun extract() {
            FirstRunSetup::class.java.getDeclaredMethod("extractBundledExtensions")
                .apply { isAccessible = true }.invoke(setup(context(assets)))
        }
        fun listsGallery(file: File): Boolean {
            val entries = JSONArray(file.readText())
            return (0 until entries.length()).any {
                entries.getJSONObject(it).optString("relativeLocation") == gallery.name
            }
        }

        installGallery()
        extract()
        val survivedFirst = gallery.isDirectory
        val listedFirst = listsGallery(default) && listsGallery(named)
        val oldRemoved = !oldBundle.exists()
        val swept = JSONObject(record.readText()).getJSONArray("retired_extension_ids_swept")
        installGallery()
        extract()

        assertAll(
            { assertTrue(survivedFirst, "a clearly identified user gallery install was removed") },
            { assertTrue(listedFirst, "the first pass removed the user's default-profile entry") },
            { assertTrue(oldRemoved, "ordinary bundled cleanup must still work") },
            { assertEquals("eamodio.gitlens", swept.getString(0)) },
            { assertTrue(gallery.isDirectory && listsGallery(default) && listsGallery(named)) },
            { assertEquals("user preferences", state.readText(), "user state outside the install tree must remain") },
        )
    }

    @Test
    fun `settings pruning follows structure through arrays comments and escaped strings`() {
        val source = """
            {
                "text": "braces } and { with \"quotes\"",
                "nested": [{
                "editor.wordWrap": "on",
                "other": true
                }],
                /* } ] { [ */
                "editor.wordWrap": "on",
                "end": true
            }
        """.trimIndent()
        val line = "\n    \"editor.wordWrap\": \"on\","
        val root = source.lastIndexOf(line)
        assertTrue(root > source.indexOf(line))
        assertEquals(source.removeRange(root, root + line.length), pruneMovedDefaults(source, true))
    }

    @Test
    fun `retirement preserves every supported gallery platform suffix`() {
        val platforms = listOf("win32-x64", "win32-arm64", "linux-x64", "linux-arm64", "linux-armhf",
            "alpine-x64", "alpine-arm64", "darwin-x64", "darwin-arm64", "web", "universal", "unknown")
        val bare = "eamodio.gitlens-2026.2.1114"
        val gallery = platforms.map { "eamodio.gitlens-2026.8.1-$it" }
        assertEquals(listOf(bare), retiredFetchedExtensionDirs(gallery + bare + "eamodio.gitlensx-1.0"))
    }

    @Test
    fun `an empty legacy tree is reclaimed and a refused removal keeps the completion flag pending`() {
        legacyRoots.forEach { File(files, it).mkdirs() }
        val refused = File(files, legacyRoots.first())
        mockkObject(StorageManager)
        try {
            every { StorageManager.deleteRecursive(any()) } answers {
                if (firstArg<File>() == refused) 0L else callOriginal()
            }
            migrate(10)
            assertTrue(refused.isDirectory)
            assertFalse(File(files, legacyRoots.last()).exists())
            assertFalse(record.isFile && JSONObject(record.readText()).optBoolean("pivot_tree_reclaimed"))
        } finally {
            unmockkObject(StorageManager)
        }
        migrate(10)
        assertFalse(refused.exists())
        assertTrue(JSONObject(record.readText()).getBoolean("pivot_tree_reclaimed"))
    }

    @Test
    fun `legacy cleanup refuses a redirected server ancestor and leaves the pass retryable`() {
        val project = File(root, "projects/redirected-server").apply { mkdirs() }
        for (name in listOf("vscode-reh", "vscode-web")) {
            File(project, name).mkdirs()
            File(project, "$name/index.js").writeText("project-$name")
        }
        val redirected = File(files, "server")
        Files.createSymbolicLink(redirected.toPath(), project.toPath())

        repeat(2) { migrate(10) }

        assertAll(
            { assertEquals("project-vscode-reh", File(project, "vscode-reh/index.js").readText()) },
            { assertEquals("project-vscode-web", File(project, "vscode-web/index.js").readText()) },
            { assertTrue(Files.isSymbolicLink(redirected.toPath())) },
            { assertFalse(record.isFile && JSONObject(record.readText()).optBoolean("pivot_tree_reclaimed")) },
        )
    }

    @Test
    fun `server package cleanup also refuses the redirected server ancestor`() {
        val assets = mockk<AssetManager>()
        every { assets.list(any()) } returns emptyArray()
        every { assets.list("vscode-reh/node_modules/@github") } returns arrayOf("copilot-sdk")
        val project = File(root, "projects/redirected-packages").apply { mkdirs() }
        val packageRoot = File(project, "vscode-reh/node_modules/@github/copilot-linux-arm64")
            .apply { mkdirs() }
        val source = File(packageRoot, "index.js").apply { writeText("unique project runtime") }
        val redirected = File(files, "server")
        Files.createSymbolicLink(redirected.toPath(), project.toPath())

        FirstRunSetup::class.java.getDeclaredMethod("runPreExtractionMigrations", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(setup(context(assets)), 11)

        assertAll(
            { assertEquals("unique project runtime", source.readText()) },
            { assertTrue(Files.isSymbolicLink(redirected.toPath())) },
            { assertFalse(record.exists(), "a post-pivot pass does not owe a pivot record") },
        )
    }

}
