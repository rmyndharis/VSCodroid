package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class SkippedExtensionSpaceTest {
    @TempDir lateinit var filesDir: File
    @TempDir lateinit var cacheDir: File
    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private lateinit var prefs: SharedPreferences
    private val bundled = "acme.sample-1.0.0"
    private val id = "acme.sample"
    private val payload = ByteArray(6 * 1_048_576)
    private val payloadRead = AtomicLong()
    private val tree = linkedMapOf<String, ByteArray>()
    private val extensions get() = File(filesDir, "home/.vscodroid/extensions")
    private val slack = FirstRunSetup.requiredExtractionBytes(0, 0, 0, 0)
    private val available = slack + 1_048_576

    enum class Skip { NEWER, GALLERY, REMOVED }

    private class RoomDir(real: File, private val free: Long) : File(real.path) {
        override fun getUsableSpace() = free
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        tree["vscode-reh/server-main.js"] = "// complete server".toByteArray()
        for (name in listOf("server.js", "process-monitor.js", "platform-fix.js", "dns-proxy.js", "xdg-open.js")) {
            tree[name] = "// complete script".toByteArray()
        }
        tree["extensions/$bundled/extension.js"] = payload
        tree["extensions/$bundled/package.json"] =
            """{"publisher":"acme","name":"sample","version":"1.0.0","engines":{"vscode":"*"}}""".toByteArray()
        assets = mockk()
        every { assets.list(any()) } answers {
            val path = firstArg<String>()
            val prefix = if (path.isEmpty()) "" else "$path/"
            tree.keys.filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }.distinct().toTypedArray()
        }
        every { assets.open(any()) } answers {
            val path = firstArg<String>()
            val bytes = tree[path] ?: throw IOException("absent")
            object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (path == "extensions/$bundled/extension.js" && n > 0) payloadRead.addAndGet(n.toLong())
                    return n
                }
            }
        }
        prefs = mockk(relaxed = true)
        every { prefs.edit() } returns mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getInt(any(), any()) } returns 11
        every { prefs.getStringSet(any(), any()) } returns emptySet()
        context = mockk(relaxed = true)
        every { context.filesDir } returns RoomDir(filesDir, available)
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = File(filesDir, "native-libs").path
        }
        SetupStepStrings.stub(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun setup() = FirstRunSetup(
        context,
        tree.values.sumOf { it.size.toLong() },
        payload.size.toLong(),
        0,
        tree.filterKeys { it.startsWith("extensions/") }.values.sumOf { it.size.toLong() },
    )

    private fun stage(skip: Skip): String? {
        extensions.mkdirs()
        val present = when (skip) {
            Skip.NEWER -> "acme.sample-2.0.0"
            Skip.GALLERY -> "acme.sample-0.5.0-linux-arm64"
            Skip.REMOVED -> null
        }
        val manifest = JSONArray()
        if (present != null) {
            val directory = File(extensions, present).apply { mkdirs() }
            File(directory, "package.json").writeText(
                """{"publisher":"acme","name":"sample","version":"${if (skip == Skip.NEWER) "2.0.0" else "0.5.0"}"}""",
            )
            manifest.put(JSONObject().put("identifier", JSONObject().put("id", id))
                .put("relativeLocation", present))
        } else {
            every { prefs.getStringSet("bundled_extension_ids", any()) } returns setOf(id)
        }
        File(extensions, "extensions.json").writeText(manifest.toString())
        return present
    }

    @ParameterizedTest
    @EnumSource(Skip::class)
    fun `an extension the extractor skips does not consume the setup space budget`(skip: Skip) {
        val present = stage(skip)
        assertTrue(bundledDirsToExtract(
            listOfNotNull(present), listOf(bundled),
            uninstalled = if (skip == Skip.REMOVED) setOf(id) else emptySet(),
            listed = setOfNotNull(present),
        ).isEmpty())
        val setup = setup()
        val space = setup.extractionSpace()
        val unfilteredNeed = FirstRunSetup.requiredExtractionBytes(
            tree.values.sumOf { it.size.toLong() }, payload.size.toLong(),
            space.serverBytes + space.usrBytes + space.extensionBytes,
            space.largestExistingFile, space.missingOverhead,
        )
        FirstRunSetup::class.java.getDeclaredMethod("extractBundledExtensions").apply {
            isAccessible = true
            invoke(setup)
        }
        assertFalse(File(extensions, bundled).exists())
        assertEquals(0, payloadRead.get())
        println("skip=$skip available=$available fullAssetNeed=$unfilteredNeed space=$space extractorPayloadRead=${payloadRead.get()}")

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, runBlocking { setup.runSetup() })

        assertFalse(File(extensions, bundled).exists())
        assertEquals(0, payloadRead.get())
        assertEquals("// complete server", File(filesDir, "server/vscode-reh/server-main.js").readText())
    }

    @Test
    fun `a skipped large extension does not set headroom for a smaller server replacement`() {
        stage(Skip.NEWER)
        val server = File(filesDir, "server/vscode-reh/server-main.js").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(2 * 1_048_576))
        }

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, runBlocking { setup().runSetup() })

        assertEquals("// complete server", server.readText())
        assertEquals(0L, payloadRead.get())
    }

    @Test
    fun `an extension still needed must fit before setup starts`() {
        assertEquals(listOf(bundled), bundledDirsToExtract(emptyList(), listOf(bundled)))
        assertEquals(FirstRunSetup.SetupResult.LOW_STORAGE, runBlocking { setup().runSetup() })
        assertEquals(0, payloadRead.get())
    }
}
