package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import com.vscodroid.SourceScan
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies that both asset walks avoid per-file [AssetManager.list] calls,
 * open each extracted file once per walk, track opened streams for leaks,
 * and preserve every abort and refusal.
 */
class FirstRunAssetWalkTest {

    @TempDir lateinit var filesDir: File
    @TempDir lateinit var cacheDir: File

    private class TrackedStream(
        private val delegate: InputStream,
        val path: String,
    ) : InputStream() {
        var closed: Boolean = false
            private set
        var readCalls: Int = 0
            private set

        override fun read(): Int {
            readCalls++
            return delegate.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            readCalls++
            return delegate.read(b, off, len)
        }

        override fun available(): Int = delegate.available()

        override fun skip(n: Long): Long = delegate.skip(n)

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private val modelFiles = linkedMapOf(
        "vscode-reh/server-main.js" to "// server main".toByteArray(),
        "vscode-reh/out/server.js" to "// out server".toByteArray(),
        "vscode-reh/out/bin/node" to "elf-node".toByteArray(),
        "usr/bin/bash" to "elf-bash".toByteArray(),
        "usr/lib/python3.14/site.py" to "# site".toByteArray(),
        "extensions/vscodroid.bundled-1.0.0/package.json" to
            """{"publisher":"vscodroid","name":"bundled","version":"1.0.0"}""".toByteArray(),
        "extensions/vscodroid.bundled-1.0.0/out/ext.js" to "// ext".toByteArray(),
        "extensions/acme.skipped-1.0.0/package.json" to
            """{"publisher":"acme","name":"skipped","version":"1.0.0"}""".toByteArray(),
        "extensions/acme.skipped-1.0.0/ext.js" to ByteArray(512) { 7 },
        "server.js" to "// server.js".toByteArray(),
        "process-monitor.js" to "// process-monitor.js".toByteArray(),
        "platform-fix.js" to "// platform-fix.js".toByteArray(),
        "dns-proxy.js" to "// dns-proxy.js".toByteArray(),
        "xdg-open.js" to "// xdg-open.js".toByteArray(),
        "nls/zh-hans.json" to """{"hello":"ni hao"}""".toByteArray(),
        "NOTICE.md" to "notices".toByteArray(),
    )

    private lateinit var assets: AssetManager
    private lateinit var context: Context
    private lateinit var editor: SharedPreferences.Editor
    private val stored = mutableMapOf<String, Any>()
    private val listCalls = mutableListOf<String>()
    private val openCalls = mutableListOf<String>()
    private val openSucceeded = mutableListOf<String>()
    private val openFailed = mutableListOf<String>()
    private val trackedStreams = mutableListOf<TrackedStream>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        stored.clear()
        stored["bundled_extension_ids"] = setOf("acme.skipped")
        File(filesDir, "home/.vscodroid/extensions").apply {
            mkdirs()
            File(this, "extensions.json").writeText("[]")
        }
        resetCounters()

        assets = mockk()
        every { assets.list(any()) } answers {
            val path = firstArg<String>()
            listCalls += path
            val prefix = if (path.isEmpty()) "" else "$path/"
            modelFiles.keys
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }
                .distinct()
                .toTypedArray()
        }
        every { assets.open(any()) } answers {
            val path = firstArg<String>()
            openCalls += path
            val bytes = modelFiles[path]
            if (bytes == null) {
                openFailed += path
                throw FileNotFoundException("Not a file: $path")
            }
            openSucceeded += path
            TrackedStream(ByteArrayInputStream(bytes), path).also { trackedStreams += it }
        }

        editor = mockk(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            val value = secondArg<String?>()
            if (value == null) stored.remove(firstArg()) else stored[firstArg()] = value
            editor
        }
        every { editor.putInt(any(), any()) } answers { stored[firstArg()] = secondArg<Int>(); editor }
        every { editor.putLong(any(), any()) } answers { stored[firstArg()] = secondArg<Long>(); editor }
        every { editor.putBoolean(any(), any()) } answers { stored[firstArg()] = secondArg<Boolean>(); editor }
        every { editor.putStringSet(any(), any()) } answers {
            stored[firstArg()] = HashSet(secondArg<Set<String>>())
            editor
        }
        every { editor.remove(any()) } answers { stored.remove(firstArg<String>()); editor }
        every { editor.commit() } returns true

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers { stored[firstArg()] as? String ?: secondArg() }
        every { prefs.getInt(any(), any()) } answers { stored[firstArg()] as? Int ?: secondArg() }
        every { prefs.getLong(any(), any()) } answers { stored[firstArg()] as? Long ?: secondArg() }
        every { prefs.getBoolean(any(), any()) } answers { stored[firstArg()] as? Boolean ?: secondArg() }
        every { prefs.getStringSet(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (stored[firstArg()] as? Set<String>)?.toMutableSet() ?: secondArg<Set<String>?>()?.toMutableSet()
        }

        val pm = mockk<PackageManager>(relaxed = true)
        val info = mockk<PackageInfo>(relaxed = true).apply {
            versionName = "1.9.0"
            @Suppress("DEPRECATION")
            versionCode = 190
            lastUpdateTime = 1_000L
        }
        every { info.longVersionCode } returns 190L
        @Suppress("DEPRECATION")
        every { pm.getPackageInfo("com.vscodroid", 0) } returns info

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.packageName } returns "com.vscodroid"
        every { context.packageManager } returns pm
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = File(filesDir, "native").path
        }
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        SetupStepStrings.stub(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun resetCounters() {
        listCalls.clear()
        openCalls.clear()
        openSucceeded.clear()
        openFailed.clear()
        trackedStreams.clear()
    }

    private fun invokeExtractAssetDir(
        setup: FirstRunSetup,
        assetPath: String,
        destPath: String,
        required: Boolean,
    ): Boolean {
        val method = FirstRunSetup::class.java.getDeclaredMethod(
            "extractAssetDir",
            String::class.java,
            String::class.java,
            Function1::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return method.invoke(setup, assetPath, destPath, null, required) as Boolean
    }

    private fun readFirstWriteFailure(setup: FirstRunSetup): String? {
        val field = FirstRunSetup::class.java.getDeclaredField("firstWriteFailure")
            .apply { isAccessible = true }
        return field.get(setup) as? String
    }

    private fun assertAllOpenedStreamsClosed() {
        assertTrue(
            trackedStreams.all { it.closed },
            "leaked open stream(s): ${trackedStreams.filterNot { it.closed }.map { it.path }}",
        )
    }

    @Test
    fun `extractionSpace lists only root and the eleven directories without listing files`() {
        val setup = FirstRunSetup(context, 0, 0, 0, 0)

        val space = setup.extractionSpace()

        val expectedDirs = setOf(
            "",
            "vscode-reh",
            "vscode-reh/out",
            "vscode-reh/out/bin",
            "usr",
            "usr/bin",
            "usr/lib",
            "usr/lib/python3.14",
            "extensions",
            "extensions/vscodroid.bundled-1.0.0",
            "extensions/vscodroid.bundled-1.0.0/out",
            "extensions/acme.skipped-1.0.0",
        )
        assertEquals(
            12,
            listCalls.size,
            "expected 1 root + 11 dir list calls with zero file or duplicate listings, saw $listCalls",
        )
        assertEquals(expectedDirs, listCalls.toSet())
        assertEquals(1, listCalls.count { it == "extensions" }, "extensions listing must be reused")
        assertFalse(listCalls.any { it in modelFiles }, "files must not be passed to assets.list")
        assertFalse("nls" in listCalls, "nls must never be walked by extractionSpace")
        for (knownRoot in listOf("vscode-reh", "usr", "extensions")) {
            assertFalse(knownRoot in openCalls, "known directory root $knownRoot should list directly")
        }
        val expectedSkipped = modelFiles.getValue("extensions/acme.skipped-1.0.0/package.json").size +
            modelFiles.getValue("extensions/acme.skipped-1.0.0/ext.js").size
        assertEquals(expectedSkipped.toLong(), space.skippedExtensionBytes)
        val measuredFiles = modelFiles.keys - setOf("nls/zh-hans.json", "NOTICE.md")
        for (file in measuredFiles) {
            assertEquals(1, openCalls.count { it == file }, "$file must be opened once in extractionSpace")
        }
    }

    @Test
    fun `extractAssetDir lists only directories and opens each extracted file once`() {
        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        val method = FirstRunSetup::class.java.getDeclaredMethod(
            "extractAssetDir",
            String::class.java,
            String::class.java,
            Function1::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val ok = method.invoke(setup, "vscode-reh", "server/vscode-reh", null, true) as Boolean

        assertTrue(ok)
        assertEquals(
            listOf("vscode-reh", "vscode-reh/out", "vscode-reh/out/bin"),
            listCalls,
            "expected only the 3 directory list calls, saw $listCalls",
        )
        val serverFiles = listOf(
            "vscode-reh/server-main.js",
            "vscode-reh/out/server.js",
            "vscode-reh/out/bin/node",
        )
        for (file in serverFiles) {
            assertEquals(1, openCalls.count { it == file }, "$file must be opened once during extractAssetDir")
        }
        assertFalse("vscode-reh" in openCalls, "known root vscode-reh should list directly")
        assertEquals("elf-node", File(filesDir, "server/vscode-reh/out/bin/node").readText())
    }

    @Test
    fun `full setup skips file listings across both walks and extracts the model tree`() {
        val setup = FirstRunSetup(context, 0, 0, 0, 0)

        val result = runBlocking { setup.runSetup() }

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result) { "failure=${setup.lastFailure}" }
        val filePaths = modelFiles.keys.filterNot { it == "nls/zh-hans.json" || it == "NOTICE.md" }
        assertEquals(
            emptyList<String>(),
            listCalls.filter { it in filePaths },
            "full setup must never pass regular files to assets.list",
        )
        assertFalse("nls" in listCalls)
        for (file in filePaths - setOf(
            "extensions/acme.skipped-1.0.0/package.json",
            "extensions/acme.skipped-1.0.0/ext.js",
        )) {
            assertEquals(
                2,
                openCalls.count { it == file },
                "$file should open once in extractionSpace and once in extraction, saw ${openCalls.count { it == file }}",
            )
        }
        for (skippedFile in listOf(
            "extensions/acme.skipped-1.0.0/package.json",
            "extensions/acme.skipped-1.0.0/ext.js",
        )) {
            assertEquals(1, openCalls.count { it == skippedFile }, "skipped $skippedFile should open only in preflight")
        }
        assertFalse(File(filesDir, "home/.vscodroid/extensions/acme.skipped-1.0.0").exists())
        assertTrue(File(filesDir, "home/.vscodroid/extensions/vscodroid.bundled-1.0.0/out/ext.js").isFile)
        assertFalse(File(filesDir, "nls/zh-hans.json").exists())
        assertFalse(File(filesDir, "server/NOTICE.md").exists())
    }

    @Test
    fun `extractionSpace rejects an entry that neither opens nor lists`() {
        every { assets.list("vscode-reh/out") } returns arrayOf("server.js", "missing-or-empty")
        every { assets.list("vscode-reh/out/missing-or-empty") } returns emptyArray()
        every { assets.open("vscode-reh/out/missing-or-empty") } throws IOException("unreadable entry")

        assertThrows(IOException::class.java) { FirstRunSetup(context, 0, 0, 0, 0).extractionSpace() }
    }

    @Test
    fun `extractionSpace rejects a nested directory whose listing returns null`() {
        every { assets.open("vscode-reh/out") } throws IOException("directory")
        every { assets.list("vscode-reh/out") } returns null

        assertThrows(IOException::class.java) { FirstRunSetup(context, 0, 0, 0, 0).extractionSpace() }
    }

    @Test
    fun `unopenable empty folder or leaf aborts required extraction while attempting siblings`() {
        val broken = "vscode-reh/out/empty-or-broken"
        every { assets.list("vscode-reh/out") } returns arrayOf("server.js", "empty-or-broken", "bin")
        every { assets.open(broken) } throws IOException("cannot open")
        every { assets.list(broken) } returns emptyArray()

        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        // Skip extractionSpace throwing on empty-or-broken by exercising extractAssetDir directly.
        val method = FirstRunSetup::class.java.getDeclaredMethod(
            "extractAssetDir",
            String::class.java,
            String::class.java,
            Function1::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val ok = method.invoke(setup, "vscode-reh", "server/vscode-reh", null, false) as Boolean

        assertFalse(ok)
        assertTrue(File(filesDir, "server/vscode-reh/out/bin/node").isFile, "remaining siblings must still extract")
    }

    @Test
    fun `nested directory whose listing returns null aborts setup`() {
        every { assets.open("vscode-reh/out") } throws IOException("directory")
        every { assets.list("vscode-reh/out") } returns null

        // extractAssetDir is called directly: the pre-flight refuses this tree before the unpack is reached.
        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        val method = FirstRunSetup::class.java.getDeclaredMethod(
            "extractAssetDir",
            String::class.java,
            String::class.java,
            Function1::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val ok = method.invoke(setup, "vscode-reh", "server/vscode-reh", null, false) as Boolean

        assertFalse(ok)
        assertNotEquals(FirstRunSetup.SetupResult.SUCCESS, runCatching {
            runBlocking { setup.runSetup() }
        }.getOrDefault(FirstRunSetup.SetupResult.ERROR))
    }

    @Test
    fun `resumeSameBuild skips matching-length file, invokes onBytes once, and repairs truncated file`() {
        val serverBytes = modelFiles.getValue("vscode-reh/server-main.js")
        val truncatedBytes = "//".toByteArray()
        val opensForOut = AtomicInteger(0)
        every { assets.open("vscode-reh/out/server.js") } answers {
            openCalls += "vscode-reh/out/server.js"
            openSucceeded += "vscode-reh/out/server.js"
            val stream: InputStream = if (opensForOut.getAndIncrement() == 1) {
                object : InputStream() {
                    override fun available(): Int = modelFiles.getValue("vscode-reh/out/server.js").size
                    override fun read(): Int = throw IOException("simulated first-run interruption")
                }
            } else {
                ByteArrayInputStream(modelFiles.getValue("vscode-reh/out/server.js"))
            }
            TrackedStream(stream, "vscode-reh/out/server.js").also { trackedStreams += it }
        }

        val firstRun = FirstRunSetup(context, 0, 0, 0, 0)
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { firstRun.runSetup() })

        val matchDest = File(filesDir, "server/vscode-reh/server-main.js")
        assertTrue(matchDest.isFile && matchDest.readBytes().contentEquals(serverBytes))
        // Tamper at exact asset length so only resumeSameBuild length-match skip keeps our byte.
        matchDest.writeBytes(serverBytes.copyOf().also { it[0] = 'X'.code.toByte() })
        val shortDest = File(filesDir, "server/vscode-reh/out/server.js").apply {
            parentFile?.mkdirs()
            writeBytes(truncatedBytes)
        }

        val reports = mutableListOf<Pair<String, Int>>()
        val retryRun = FirstRunSetup(context, 0, 0, 0, 0).apply {
            onProgress = { msg, pct -> reports += msg to pct }
        }
        val result = runBlocking { retryRun.runSetup() }

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result)
        assertEquals('X'.code.toByte(), matchDest.readBytes()[0], "resumeSameBuild must keep matching-length file")
        assertTrue(shortDest.readBytes().contentEquals(modelFiles.getValue("vscode-reh/out/server.js")))
        assertEquals(100, reports.lastOrNull()?.second)
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `root empty-string null listing refuses preflight and runSetup`() {
        every { assets.list("") } returns null
        val setup = FirstRunSetup(context, 0, 0, 0, 0)

        assertThrows(IOException::class.java) { setup.extractionSpace() }
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { setup.runSetup() })
        verify(exactly = 0) { editor.putString("setup_version", any()) }
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `known root null listing refuses preflight, extractAssetDir, and runSetup`() {
        every { assets.list("vscode-reh") } returns null
        val setup = FirstRunSetup(context, 0, 0, 0, 0)

        assertThrows(IOException::class.java) { setup.extractionSpace() }
        assertFalse(invokeExtractAssetDir(setup, "vscode-reh", "server/vscode-reh", false))
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { setup.runSetup() })
        verify(exactly = 0) { editor.putString("setup_version", any()) }
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `extensions root null listing refuses preflight when extensions in roots`() {
        every { assets.list("extensions") } returns null
        val setup = FirstRunSetup(context, 0, 0, 0, 0)

        assertThrows(IOException::class.java) { setup.extractionSpace() }
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { setup.runSetup() })
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `nested folder null listing refuses preflight, extractAssetDir, and runSetup`() {
        every { assets.open("vscode-reh/out") } answers {
            openCalls += "vscode-reh/out"
            openFailed += "vscode-reh/out"
            throw FileNotFoundException("directory")
        }
        every { assets.list("vscode-reh/out") } returns null
        val setup = FirstRunSetup(context, 0, 0, 0, 0)

        assertThrows(IOException::class.java) { setup.extractionSpace() }
        assertFalse(invokeExtractAssetDir(setup, "vscode-reh", "server/vscode-reh", false))
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { setup.runSetup() })
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `root list throwing IOException refuses preflight and runSetup`() {
        every { assets.list("") } throws IOException("root list boom")
        val setup = FirstRunSetup(context, 0, 0, 0, 0)

        assertThrows(IOException::class.java) { setup.extractionSpace() }
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { setup.runSetup() })
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `nested folder list that always throws IOException refuses preflight, extractAssetDir, and runSetup`() {
        every { assets.open("vscode-reh/out") } answers {
            openCalls += "vscode-reh/out"
            openFailed += "vscode-reh/out"
            throw IOException("dir cannot open")
        }
        every { assets.list("vscode-reh/out") } answers {
            listCalls += "vscode-reh/out"
            throw IOException("folder list failed")
        }
        val setup = FirstRunSetup(context, 0, 0, 0, 0)

        assertThrows(IOException::class.java) { setup.extractionSpace() }
        assertFalse(invokeExtractAssetDir(setup, "vscode-reh", "server/vscode-reh", false))
        assertNotNull(readFirstWriteFailure(setup))
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { FirstRunSetup(context, 0, 0, 0, 0).runSetup() })
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `nested folder list that throws IOException only in the unpack refuses extractAssetDir and runSetup`() {
        val calls = AtomicInteger(0)
        every { assets.open("vscode-reh/out") } answers {
            openCalls += "vscode-reh/out"
            openFailed += "vscode-reh/out"
            throw IOException("dir cannot open")
        }
        every { assets.list("vscode-reh/out") } answers {
            listCalls += "vscode-reh/out"
            if (calls.getAndIncrement() == 0) arrayOf("server.js", "bin") else throw IOException("folder list failed")
        }
        val setup = FirstRunSetup(context, 0, 0, 0, 0)

        // First call during preflight succeeds; second call during extraction throws.
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { setup.runSetup() })
        assertNotNull(readFirstWriteFailure(setup))
        assertFalse(invokeExtractAssetDir(setup, "vscode-reh", "server/vscode-reh", false))
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `leaf that neither opens nor lists refuses preflight, extractAssetDir with siblings, and runSetup`() {
        every { assets.list("vscode-reh/out") } returns arrayOf("server.js", "broken", "bin")
        every { assets.open("vscode-reh/out/broken") } answers {
            openCalls += "vscode-reh/out/broken"
            openFailed += "vscode-reh/out/broken"
            throw IOException("unopenable broken entry")
        }
        every { assets.list("vscode-reh/out/broken") } returns emptyArray()
        val setup = FirstRunSetup(context, 0, 0, 0, 0)

        assertThrows(IOException::class.java) { setup.extractionSpace() }
        assertFalse(invokeExtractAssetDir(setup, "vscode-reh", "server/vscode-reh", false))
        assertTrue(File(filesDir, "server/vscode-reh/out/bin/node").isFile, "sibling files must still unpack")
        assertEquals("unopenable broken entry", readFirstWriteFailure(setup))
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { setup.runSetup() })
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `empty listing where required refuses bundled extension and python stdlib`() {
        val setup = FirstRunSetup(context, 0, 0, 0, 0)

        // Case 1: required bundled extension root returns empty listing and cannot open.
        every { assets.list("extensions/vscodroid.bundled-1.0.0") } returns emptyArray()
        every { assets.open("extensions/vscodroid.bundled-1.0.0") } throws IOException("missing ext")
        assertThrows(IOException::class.java) { setup.extractionSpace() }
        assertFalse(
            invokeExtractAssetDir(
                setup,
                "extensions/vscodroid.bundled-1.0.0",
                "home/.vscodroid/extensions/vscodroid.bundled-1.0.0",
                true,
            ),
        )
        assertNotNull(readFirstWriteFailure(setup))

        // Case 1b: full runSetup where bundled extension only lists empty on second walk still aborts.
        val extListCalls = AtomicInteger(0)
        every { assets.list("extensions/vscodroid.bundled-1.0.0") } answers {
            listCalls += "extensions/vscodroid.bundled-1.0.0"
            if (extListCalls.getAndIncrement() == 0) {
                arrayOf("package.json", "out")
            } else {
                emptyArray()
            }
        }
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { FirstRunSetup(context, 0, 0, 0, 0).runSetup() })
        verify(exactly = 0) { editor.putString("setup_version", any()) }

        // Case 2: required python stdlib root returns empty listing and cannot open.
        val setupPy = FirstRunSetup(context, 0, 0, 0, 0)
        every { assets.list("usr/lib/python3.14") } returns emptyArray()
        every { assets.open("usr/lib/python3.14") } throws IOException("missing stdlib")
        assertFalse(invokeExtractAssetDir(setupPy, "usr/lib/python3.14", "usr/lib/python3.14", true))

        // Case 3: extensions root in "" returns empty listing in extractionSpace().
        every { assets.list("extensions") } returns emptyArray()
        every { assets.open("extensions") } throws IOException("empty extensions root")
        assertThrows(IOException::class.java) { FirstRunSetup(context, 0, 0, 0, 0).extractionSpace() }

        // Case 4: optional root with required=false still tolerates absent optional tree.
        val stubSetup = FirstRunSetup(context, 0, 0, 0, 0)
        every { assets.list("vscode-reh") } returns emptyArray()
        every { assets.open("vscode-reh") } throws IOException("absent optional tree")
        assertTrue(invokeExtractAssetDir(stubSetup, "vscode-reh", "server/vscode-reh", false))
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `redirected symlink destination directory refuses and protects symlink target`() {
        val outsideDir = File(cacheDir, "outside-dir").apply { mkdirs() }
        val sentinel = File(outsideDir, "keep.txt").apply { writeText("do-not-overwrite") }
        val targetOut = File(filesDir, "server/vscode-reh/out").apply { parentFile?.mkdirs() }
        Files.createSymbolicLink(targetOut.toPath(), outsideDir.toPath())

        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        val result = runBlocking { setup.runSetup() }

        assertEquals(FirstRunSetup.SetupResult.ERROR, result)
        assertTrue(readFirstWriteFailure(setup)?.contains("symbolic link") == true)
        assertEquals("do-not-overwrite", sentinel.readText())
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `redirected symlink destination parent refuses, closes stream, and protects target`() {
        val outsideDir = File(cacheDir, "outside-server").apply { mkdirs() }
        val sentinel = File(outsideDir, "server.js").apply { writeText("untouched") }
        val serverDir = File(filesDir, "server")
        Files.createSymbolicLink(serverDir.toPath(), outsideDir.toPath())

        every { assets.list("") } returns arrayOf("server.js")
        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        val result = runBlocking { setup.runSetup() }

        assertEquals(FirstRunSetup.SetupResult.ERROR, result)
        assertTrue(readFirstWriteFailure(setup)?.contains("symbolic link") == true)
        assertEquals("untouched", sentinel.readText())
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `asset stream failing mid-copy aborts setup, closes stream, and keeps prior destination`() {
        val existingServer = File(filesDir, "server/vscode-reh/out/server.js").apply {
            parentFile?.mkdirs()
            writeText("previous-valid-server")
        }
        val opens = AtomicInteger(0)
        every { assets.open("vscode-reh/out/server.js") } answers {
            val idx = opens.getAndIncrement()
            openCalls += "vscode-reh/out/server.js"
            openSucceeded += "vscode-reh/out/server.js"
            val stream: InputStream = if (idx == 0) {
                ByteArrayInputStream("// good length for preflight".toByteArray())
            } else {
                object : InputStream() {
                    private var emitted = false
                    override fun available(): Int = 128
                    override fun read(): Int = if (!emitted) {
                        emitted = true
                        '/'.code
                    } else {
                        throw IOException("truncated mid-copy")
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!emitted) {
                            emitted = true
                            b[off] = '/'.code.toByte()
                            return 1
                        }
                        throw IOException("truncated mid-copy")
                    }
                }
            }
            TrackedStream(stream, "vscode-reh/out/server.js").also { trackedStreams += it }
        }

        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        val result = runBlocking { setup.runSetup() }

        assertEquals(FirstRunSetup.SetupResult.ERROR, result)
        assertEquals("truncated mid-copy", readFirstWriteFailure(setup))
        assertEquals("previous-valid-server", existingServer.readText())
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `stream failing on available closes opened stream and aborts setup`() {
        every { assets.open("vscode-reh/out/server.js") } answers {
            openCalls += "vscode-reh/out/server.js"
            openSucceeded += "vscode-reh/out/server.js"
            TrackedStream(
                object : InputStream() {
                    override fun available(): Int = throw IOException("bad fd")
                    override fun read(): Int = -1
                },
                "vscode-reh/out/server.js",
            ).also { trackedStreams += it }
        }

        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        assertFalse(invokeExtractAssetDir(setup, "vscode-reh", "server/vscode-reh", false))
        assertEquals("bad fd", readFirstWriteFailure(setup))
        assertTrue(File(filesDir, "server/vscode-reh/out/bin/node").isFile, "sibling files must still unpack")
        val result = runBlocking { setup.runSetup() }

        assertEquals(FirstRunSetup.SetupResult.ERROR, result)
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `stream failing on available during resumeSameBuild on root script refuses and closes stream`() {
        val outCalls = AtomicInteger(0)
        every { assets.open("vscode-reh/out/server.js") } answers {
            openCalls += "vscode-reh/out/server.js"
            openSucceeded += "vscode-reh/out/server.js"
            val s: InputStream = if (outCalls.getAndIncrement() == 1) {
                object : InputStream() {
                    override fun available(): Int = 100
                    override fun read(): Int = throw IOException("interrupt step 1")
                }
            } else {
                ByteArrayInputStream(modelFiles.getValue("vscode-reh/out/server.js"))
            }
            TrackedStream(s, "vscode-reh/out/server.js").also { trackedStreams += it }
        }
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { FirstRunSetup(context, 0, 0, 0, 0).runSetup() })

        // Now resumeSameBuild is true on retry; call 0 is Run 2 preflight,
        // and call 1 fails inside extractAssetFile -> extractAssetStream.
        val scriptCalls = AtomicInteger(0)
        every { assets.open("server.js") } answers {
            openCalls += "server.js"
            openSucceeded += "server.js"
            val s: InputStream = if (scriptCalls.getAndIncrement() == 1) {
                object : InputStream() {
                    override fun available(): Int = throw IOException("available boom in resumeSameBuild")
                    override fun read(): Int = -1
                }
            } else {
                ByteArrayInputStream(modelFiles.getValue("server.js"))
            }
            TrackedStream(s, "server.js").also { trackedStreams += it }
        }

        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { FirstRunSetup(context, 0, 0, 0, 0).runSetup() })
        assertTrue(trackedStreams.isNotEmpty())
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `persistent available IOException on nested child during resumeSameBuild refuses setup and attempts siblings`() {
        val outCalls = AtomicInteger(0)
        every { assets.open("vscode-reh/out/server.js") } answers {
            openCalls += "vscode-reh/out/server.js"
            openSucceeded += "vscode-reh/out/server.js"
            val call = outCalls.getAndIncrement()
            val s: InputStream = when {
                call == 0 || call == 2 -> ByteArrayInputStream(modelFiles.getValue("vscode-reh/out/server.js"))
                call == 1 -> object : InputStream() {
                    override fun available(): Int = 100
                    override fun read(): Int = throw IOException("interrupt step 1")
                }
                else -> object : InputStream() {
                    private var availCalls = 0
                    override fun available(): Int {
                        // Call 0 succeeds for zero-byte check; call 1 inside extractAssetStream fails.
                        if (availCalls++ >= 1) throw IOException("available boom under out/")
                        return 100
                    }
                    override fun read(): Int = -1
                }
            }
            TrackedStream(s, "vscode-reh/out/server.js").also { trackedStreams += it }
        }
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { FirstRunSetup(context, 0, 0, 0, 0).runSetup() })

        // Force sibling extraction on retry to confirm remaining children are still attempted.
        File(filesDir, "server/vscode-reh/out/bin/node").delete()
        resetCounters()

        val retrySetup = FirstRunSetup(context, 0, 0, 0, 0)
        val retryResult = runBlocking { retrySetup.runSetup() }
        assertAllOpenedStreamsClosed()
        assertEquals(FirstRunSetup.SetupResult.ERROR, retryResult)
        assertEquals("available boom under out/", readFirstWriteFailure(retrySetup))
        assertTrue(File(filesDir, "server/vscode-reh/out/bin/node").isFile, "sibling bin/node must still extract")
        verify(exactly = 0) { editor.putString("setup_version", any()) }
    }

    @Test
    fun `persistent available IOException on direct child of optional root during resumeSameBuild refuses setup and attempts siblings`() {
        val mainCalls = AtomicInteger(0)
        every { assets.open("vscode-reh/server-main.js") } answers {
            openCalls += "vscode-reh/server-main.js"
            openSucceeded += "vscode-reh/server-main.js"
            val call = mainCalls.getAndIncrement()
            val s: InputStream = when {
                call == 0 || call == 2 -> ByteArrayInputStream(modelFiles.getValue("vscode-reh/server-main.js"))
                call == 1 -> object : InputStream() {
                    override fun available(): Int = 100
                    override fun read(): Int = throw IOException("interrupt direct child")
                }
                else -> object : InputStream() {
                    private var availCalls = 0
                    override fun available(): Int {
                        if (availCalls++ >= 1) throw IOException("available boom on direct child")
                        return 100
                    }
                    override fun read(): Int = -1
                }
            }
            TrackedStream(s, "vscode-reh/server-main.js").also { trackedStreams += it }
        }
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { FirstRunSetup(context, 0, 0, 0, 0).runSetup() })

        File(filesDir, "server/vscode-reh/out/bin/node").delete()
        resetCounters()

        val retrySetup = FirstRunSetup(context, 0, 0, 0, 0)
        val retryResult = runBlocking { retrySetup.runSetup() }
        assertAllOpenedStreamsClosed()
        assertEquals(FirstRunSetup.SetupResult.ERROR, retryResult)
        assertEquals("available boom on direct child", readFirstWriteFailure(retrySetup))
        assertTrue(File(filesDir, "server/vscode-reh/out/bin/node").isFile, "siblings after direct child failure must still unpack")
        verify(exactly = 0) { editor.putString("setup_version", any()) }
    }

    @Test
    fun `extractAssetDir catches only assets list IOException and lets root extractAssetFile failure abort`() {
        every { assets.list("vscode-reh") } returns emptyArray()
        every { assets.open("vscode-reh") } answers {
            openCalls += "vscode-reh"
            openSucceeded += "vscode-reh"
            TrackedStream(
                object : InputStream() {
                    override fun available(): Int = throw IOException("root file available boom")
                    override fun read(): Int = -1
                },
                "vscode-reh",
            ).also { trackedStreams += it }
        }
        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        FirstRunSetup::class.java.getDeclaredField("resumeSameBuild").apply {
            isAccessible = true
            setBoolean(setup, true)
        }

        val thrown = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            invokeExtractAssetDir(setup, "vscode-reh", "server/vscode-reh", false)
        }
        assertTrue(thrown.cause is IOException)
        assertEquals(
            1,
            openCalls.count { it == "vscode-reh" },
            "narrowed catch must not re-open optional root as file on child/stream failure",
        )
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { setup.runSetup() })
        assertAllOpenedStreamsClosed()

        val source = SourceScan.read("src/main/kotlin/com/vscodroid/setup/FirstRunSetup.kt")
        val body = SourceScan.withoutComments(SourceScan.body(source, "private fun extractAssetDir("))
        val catchIdx = body.indexOf("catch (e: IOException)")
        val emptyIdx = body.indexOf("if (assets.isEmpty())")
        val childrenIdx = body.indexOf("extractAssetChildren(")
        assertTrue(catchIdx > body.indexOf("context.assets.list(assetPath)"))
        assertTrue(emptyIdx > catchIdx, "empty listing handling must sit outside the root list catch")
        assertTrue(childrenIdx > emptyIdx, "extractAssetChildren must sit outside the root list catch")
    }

    @Test
    fun `nls tree is never walked or extracted while extension package nls files extract`() {
        val pkgNls = """{"title":"Bundled"}""".toByteArray()
        every { assets.list("extensions/vscodroid.bundled-1.0.0") } answers {
            listCalls += "extensions/vscodroid.bundled-1.0.0"
            arrayOf("package.json", "package.nls.json", "out")
        }
        every { assets.open("extensions/vscodroid.bundled-1.0.0/package.nls.json") } answers {
            openCalls += "extensions/vscodroid.bundled-1.0.0/package.nls.json"
            openSucceeded += "extensions/vscodroid.bundled-1.0.0/package.nls.json"
            TrackedStream(ByteArrayInputStream(pkgNls), "extensions/vscodroid.bundled-1.0.0/package.nls.json")
                .also { trackedStreams += it }
        }

        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        val result = runBlocking { setup.runSetup() }

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result)
        assertFalse(listCalls.any { it == "nls" || it.startsWith("nls/") })
        assertFalse(openCalls.any { it.startsWith("nls/") })
        assertFalse(File(filesDir, "nls/zh-hans.json").exists())
        assertTrue(File(filesDir, "home/.vscodroid/extensions/vscodroid.bundled-1.0.0/package.nls.json").isFile)
    }

    @Test
    fun `skipped extension nested subdirs sum into skippedExtensionBytes without inflating overhead`() {
        val deepBytes = ByteArray(512) { 7 }
        every { assets.list("extensions/acme.skipped-1.0.0") } answers {
            listCalls += "extensions/acme.skipped-1.0.0"
            arrayOf("package.json", "nested")
        }
        every { assets.open("extensions/acme.skipped-1.0.0/nested") } answers {
            openCalls += "extensions/acme.skipped-1.0.0/nested"
            openFailed += "extensions/acme.skipped-1.0.0/nested"
            throw FileNotFoundException("dir")
        }
        every { assets.list("extensions/acme.skipped-1.0.0/nested") } answers {
            listCalls += "extensions/acme.skipped-1.0.0/nested"
            arrayOf("deep")
        }
        every { assets.open("extensions/acme.skipped-1.0.0/nested/deep") } answers {
            openCalls += "extensions/acme.skipped-1.0.0/nested/deep"
            openFailed += "extensions/acme.skipped-1.0.0/nested/deep"
            throw FileNotFoundException("dir")
        }
        every { assets.list("extensions/acme.skipped-1.0.0/nested/deep") } answers {
            listCalls += "extensions/acme.skipped-1.0.0/nested/deep"
            arrayOf("ext.js")
        }
        every { assets.open("extensions/acme.skipped-1.0.0/nested/deep/ext.js") } answers {
            openCalls += "extensions/acme.skipped-1.0.0/nested/deep/ext.js"
            openSucceeded += "extensions/acme.skipped-1.0.0/nested/deep/ext.js"
            TrackedStream(ByteArrayInputStream(deepBytes), "extensions/acme.skipped-1.0.0/nested/deep/ext.js")
                .also { trackedStreams += it }
        }

        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        val space = setup.extractionSpace()

        val expectedSkipped =
            modelFiles.getValue("extensions/acme.skipped-1.0.0/package.json").size.toLong() +
                deepBytes.size.toLong()
        assertEquals(expectedSkipped, space.skippedExtensionBytes)
        assertTrue(space.largestSelectedAssetBytes < 512L, "skipped 512B asset leaked into largestSelectedAssetBytes")
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `huge asset preflight reads available without calling read and closes stream`() {
        val hugeSize = 128 * 1024 * 1024
        every { assets.list("vscode-reh") } returns arrayOf("server-main.js", "out", "huge.pak")
        every { assets.open("vscode-reh/huge.pak") } answers {
            openCalls += "vscode-reh/huge.pak"
            openSucceeded += "vscode-reh/huge.pak"
            TrackedStream(
                object : InputStream() {
                    override fun available(): Int = hugeSize
                    override fun read(): Int = throw AssertionError("preflight must not call read()")
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        throw AssertionError("preflight must not call read(buf)")
                    override fun skip(n: Long): Long = throw AssertionError("preflight must not call skip()")
                },
                "vscode-reh/huge.pak",
            ).also { trackedStreams += it }
        }

        val space = FirstRunSetup(context, 0, 0, 0, 0).extractionSpace()

        assertEquals(hugeSize.toLong(), space.largestSelectedAssetBytes)
        val hugeStreams = trackedStreams.filter { it.path == "vscode-reh/huge.pak" }
        assertEquals(1, hugeStreams.size)
        assertEquals(0, hugeStreams.single().readCalls)
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `synthetic non-empty stream and directory at same path extracts opened file without descending`() {
        // Characterizes open-first when a synthetic overlay answers both open() with bytes and list().
        // AGP merges all Gradle asset sources into one directory tree before packaging, so an APK built
        // by this project cannot place a non-empty file and a directory at the same asset path.
        every { assets.list("vscode-reh") } returns arrayOf("server-main.js", "out", "dual")
        every { assets.open("vscode-reh/dual") } answers {
            openCalls += "vscode-reh/dual"
            openSucceeded += "vscode-reh/dual"
            TrackedStream(ByteArrayInputStream("as-file".toByteArray()), "vscode-reh/dual")
                .also { trackedStreams += it }
        }
        every { assets.list("vscode-reh/dual") } answers {
            listCalls += "vscode-reh/dual"
            arrayOf("child.js")
        }
        every { assets.open("vscode-reh/dual/child.js") } answers {
            openCalls += "vscode-reh/dual/child.js"
            openSucceeded += "vscode-reh/dual/child.js"
            TrackedStream(ByteArrayInputStream("child-payload".toByteArray()), "vscode-reh/dual/child.js")
                .also { trackedStreams += it }
        }

        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        val result = runBlocking { setup.runSetup() }

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result)
        assertEquals("as-file", File(filesDir, "server/vscode-reh/dual").readText())
        assertFalse("vscode-reh/dual" in listCalls)
        assertFalse(File(filesDir, "server/vscode-reh/dual/child.js").exists())
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `trailing-slash zip directory entry that opens as empty stream still extracts children`() {
        every { assets.list("vscode-reh") } returns arrayOf("server-main.js", "sub/")
        every { assets.open("vscode-reh/sub/") } answers {
            openCalls += "vscode-reh/sub/"
            openSucceeded += "vscode-reh/sub/"
            TrackedStream(ByteArrayInputStream(ByteArray(0)), "vscode-reh/sub/")
                .also { trackedStreams += it }
        }
        every { assets.list("vscode-reh/sub/") } answers {
            listCalls += "vscode-reh/sub/"
            arrayOf("inner.js")
        }
        every { assets.open("vscode-reh/sub//inner.js") } answers {
            openCalls += "vscode-reh/sub//inner.js"
            openSucceeded += "vscode-reh/sub//inner.js"
            TrackedStream(ByteArrayInputStream("inner".toByteArray()), "vscode-reh/sub//inner.js")
                .also { trackedStreams += it }
        }

        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        val space = setup.extractionSpace()
        assertTrue(space.missingOverhead > 0L)
        assertTrue("vscode-reh/sub//inner.js" in openCalls, "preflight must walk children of empty-stream dir")
        assertAllOpenedStreamsClosed()

        resetCounters()
        val result = runBlocking { FirstRunSetup(context, 0, 0, 0, 0).runSetup() }

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result)
        assertTrue(
            File(filesDir, "server/vscode-reh/sub/inner.js").isFile,
            "trailing-slash directory entry was extracted as 0-byte file and skipped children",
        )
        assertEquals("inner", File(filesDir, "server/vscode-reh/sub/inner.js").readText())
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `zero-length regular file extracts as empty file and null list refuses cleanly`() {
        every { assets.list("vscode-reh") } returns arrayOf("server-main.js", "empty.txt")
        every { assets.open("vscode-reh/empty.txt") } answers {
            openCalls += "vscode-reh/empty.txt"
            openSucceeded += "vscode-reh/empty.txt"
            TrackedStream(ByteArrayInputStream(ByteArray(0)), "vscode-reh/empty.txt")
                .also { trackedStreams += it }
        }
        every { assets.list("vscode-reh/empty.txt") } answers {
            listCalls += "vscode-reh/empty.txt"
            emptyArray()
        }

        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        assertEquals(FirstRunSetup.SetupResult.SUCCESS, runBlocking { setup.runSetup() })
        val extracted = File(filesDir, "server/vscode-reh/empty.txt")
        assertTrue(extracted.isFile)
        assertEquals(0L, extracted.length())
        assertAllOpenedStreamsClosed()

        // A zero-length stream whose directory listing returns null must refuse and close its stream.
        every { assets.list("vscode-reh/empty.txt") } returns null
        assertThrows(IOException::class.java) { FirstRunSetup(context, 0, 0, 0, 0).extractionSpace() }
        assertAllOpenedStreamsClosed()
        assertFalse(invokeExtractAssetDir(FirstRunSetup(context, 0, 0, 0, 0), "vscode-reh", "server/vscode-reh", false))
        assertAllOpenedStreamsClosed()
    }

    @Test
    fun `zero-length regular file whose listing throws is an empty file in both walks`() {
        every { assets.list("vscode-reh") } returns arrayOf("server-main.js", "empty.txt")
        every { assets.open("vscode-reh/empty.txt") } answers {
            openCalls += "vscode-reh/empty.txt"
            openSucceeded += "vscode-reh/empty.txt"
            TrackedStream(ByteArrayInputStream(ByteArray(0)), "vscode-reh/empty.txt")
                .also { trackedStreams += it }
        }
        every { assets.list("vscode-reh/empty.txt") } throws IOException("listing unavailable")

        val space = FirstRunSetup(context, 0, 0, 0, 0).extractionSpace()
        assertTrue(space.missingOverhead > 0L)
        assertAllOpenedStreamsClosed()

        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        assertTrue(invokeExtractAssetDir(setup, "vscode-reh", "server/vscode-reh", false))
        assertEquals(null, readFirstWriteFailure(setup))
        val extracted = File(filesDir, "server/vscode-reh/empty.txt")
        assertTrue(extracted.isFile)
        assertEquals(0L, extracted.length())
        assertAllOpenedStreamsClosed()

        val result = runBlocking { FirstRunSetup(context, 0, 0, 0, 0).runSetup() }
        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result)
    }

    @Test
    fun `model tree reports list and open counts across preflight and full run`() {
        val setup = FirstRunSetup(context, 0, 0, 0, 0)

        resetCounters()
        setup.extractionSpace()
        val w1Lists = listCalls.size
        val w1OpenAttempts = openCalls.size
        val w1OpenOk = openSucceeded.size
        val w1OpenFail = openFailed.size
        assertAllOpenedStreamsClosed()

        resetCounters()
        val result = runBlocking { FirstRunSetup(context, 0, 0, 0, 0).runSetup() }
        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result)
        assertAllOpenedStreamsClosed()
        val totalLists = listCalls.size
        val totalOpenAttempts = openCalls.size
        val totalOpenOk = openSucceeded.size
        val totalOpenFail = openFailed.size
        val w2Lists = totalLists - w1Lists
        val w2OpenAttempts = totalOpenAttempts - w1OpenAttempts
        val w2OpenOk = totalOpenOk - w1OpenOk
        val w2OpenFail = totalOpenFail - w1OpenFail

        println(
            "MODEL_COUNTS walk1_list=$w1Lists walk1_open_total=$w1OpenAttempts " +
                "walk1_open_ok=$w1OpenOk walk1_open_fail=$w1OpenFail " +
                "walk2_list=$w2Lists walk2_open_total=$w2OpenAttempts " +
                "walk2_open_ok=$w2OpenOk walk2_open_fail=$w2OpenFail " +
                "total_list=$totalLists total_open_total=$totalOpenAttempts " +
                "total_open_ok=$totalOpenOk total_open_fail=$totalOpenFail",
        )
        assertEquals(12, w1Lists)
        assertEquals(14, w1OpenOk, "preflight measures the 12 selected + 2 skipped = 14 files")
        assertEquals(22, w1OpenAttempts, "preflight opens every entry once: 14 files and 8 folders that do not open")
        assertEquals(8, w1OpenFail)
        assertEquals(13, w2Lists, "the unpack lists 13 folders and no file")
        assertEquals(18, w2OpenAttempts)
        assertEquals(12, w2OpenOk, "the unpack opens each selected file once")
        assertEquals(6, w2OpenFail)
    }
}
