package com.vscodroid.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class StorageBreakdownSinglePassTest {

    @TempDir
    lateinit var root: File

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var externalDir: File
    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        filesDir = File(root, "files").apply { mkdirs() }
        cacheDir = File(root, "cache").apply { mkdirs() }
        externalDir = File(root, "external").apply { mkdirs() }
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        every { context.getExternalFilesDir(null) } returns externalDir
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun writeFile(dir: File, relative: String, size: Int): File =
        File(dir, relative).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(size))
        }

    /**
     * Instruments both traversal modes (SecureDirectoryStream on Linux/Android and
     * Files.readAttributes fallback on hosts without SecureDirectoryStream) so visit counts
     * are strictly non-vacuous everywhere.
     */
    private fun installVisitCounters(): Pair<ConcurrentHashMap<Path, AtomicInteger>, ConcurrentHashMap<Path, AtomicInteger>> {
        val entryVisits = ConcurrentHashMap<Path, AtomicInteger>()
        val rootOpens = ConcurrentHashMap<Path, AtomicInteger>()

        mockkStatic(Files::class)
        every { Files.readAttributes(any<Path>(), BasicFileAttributes::class.java, *anyVararg<LinkOption>()) } answers {
            val p = firstArg<Path>().normalize()
            entryVisits.computeIfAbsent(p) { AtomicInteger(0) }.incrementAndGet()
            callOriginal()
        }
        every { Files.newDirectoryStream(any<Path>()) } answers {
            val dir = firstArg<Path>().normalize()
            rootOpens.computeIfAbsent(dir) { AtomicInteger(0) }.incrementAndGet()
            val real = callOriginal()
            if (real is SecureDirectoryStream<Path>) {
                CountingSecureDirectoryStream(dir, real, entryVisits)
            } else {
                real
            }
        }
        return entryVisits to rootOpens
    }

    @Test
    fun `getStorageBreakdown walks filesDir and cacheDir in a single pass with exact category totals`() {
        val serverFile = writeFile(filesDir, "server/bin/node.js", 100)
        writeFile(filesDir, "home/.vscodroid/extensions/ext/main.js", 200)
        writeFile(filesDir, "home/.vscodroid/data/User/settings.json", 300)
        writeFile(filesDir, "home/.vscodroid/data/logs/server.log", 400)
        writeFile(filesDir, "usr/bin/script.sh", 500)
        writeFile(filesDir, "saf-mirrors/0123456789ab/readme.md", 600)
        writeFile(filesDir, "projects/app/index.ts", 700)
        writeFile(filesDir, "home/.bashrc", 50)

        val outside = writeFile(root, "outside/big.bin", 9_999)
        Files.createSymbolicLink(File(filesDir, "usr/bin/linked").toPath(), outside.toPath())

        val cacheFile = writeFile(cacheDir, "npm-cache/pkg.tgz", 800)
        writeFile(cacheDir, "pip/wheel.whl", 90)
        writeFile(cacheDir, "webview_vscodroid/cache.dat", 1_000)

        val (entryVisits, rootOpens) = installVisitCounters()

        val breakdown = StorageManager.getStorageBreakdown(context)

        assertEquals(100L, breakdown.getLong("vscode_server"))
        assertEquals(200L, breakdown.getLong("extensions"))
        assertEquals(300L, breakdown.getLong("user_data"))
        assertEquals(400L, breakdown.getLong("logs"))
        assertEquals(500L, breakdown.getLong("tools"))
        assertEquals(600L, breakdown.getLong("saf_mirrors"))
        assertEquals(700L, breakdown.getLong("projects"))
        assertEquals(890L, breakdown.getLong("cache"))
        assertEquals(
            100L + 200L + 300L + 400L + 500L + 600L + 700L + 50L + 800L + 90L + 1_000L,
            breakdown.getLong("total"),
        )

        assertEquals(
            1,
            entryVisits[serverFile.toPath().normalize()]?.get() ?: 0,
            "filesDir categorized file must be visited exactly once",
        )
        assertEquals(
            1,
            entryVisits[cacheFile.toPath().normalize()]?.get() ?: 0,
            "cacheDir categorized file must be visited exactly once",
        )
        assertEquals(
            1,
            rootOpens[filesDir.toPath().normalize()]?.get() ?: 0,
            "filesDir root must be opened exactly once",
        )
        assertEquals(
            1,
            rootOpens[cacheDir.toPath().normalize()]?.get() ?: 0,
            "cacheDir root must be opened exactly once",
        )
    }

    @Test
    fun `getStorageBreakdown counts external projects directory once when outside filesDir`() {
        val serverFile = writeFile(filesDir, "server/app.js", 120)
        val extProjectFile = writeFile(externalDir, "projects/demo/main.kt", 350)
        val (entryVisits, rootOpens) = installVisitCounters()

        val breakdown = StorageManager.getStorageBreakdown(context)

        assertEquals(120L, breakdown.getLong("vscode_server"))
        assertEquals(350L, breakdown.getLong("projects"))
        assertEquals(470L, breakdown.getLong("total"))
        assertEquals(
            1,
            entryVisits[serverFile.toPath().normalize()]?.get() ?: 0,
            "filesDir file must be visited exactly once",
        )
        assertEquals(
            1,
            entryVisits[extProjectFile.toPath().normalize()]?.get() ?: 0,
            "external projects file must be visited exactly once",
        )
        assertEquals(
            1,
            rootOpens[File(externalDir, "projects").toPath().normalize()]?.get() ?: 0,
            "external projects root must be opened exactly once",
        )
    }

    private class CountingSecureDirectoryStream(
        private val dir: Path,
        private val delegate: SecureDirectoryStream<Path>,
        private val visits: ConcurrentHashMap<Path, AtomicInteger>,
    ) : SecureDirectoryStream<Path> {
        override fun iterator(): MutableIterator<Path> = delegate.iterator()
        override fun close() = delegate.close()
        override fun newDirectoryStream(path: Path, vararg options: LinkOption): SecureDirectoryStream<Path> =
            CountingSecureDirectoryStream(
                dir.resolve(path).normalize(),
                delegate.newDirectoryStream(path, *options),
                visits,
            )
        override fun newByteChannel(
            path: Path,
            options: MutableSet<out OpenOption>,
            vararg attrs: FileAttribute<*>,
        ): SeekableByteChannel = delegate.newByteChannel(path, options, *attrs)
        override fun deleteFile(path: Path) = delegate.deleteFile(path)
        override fun deleteDirectory(path: Path) = delegate.deleteDirectory(path)
        override fun move(srcpath: Path, targetdir: SecureDirectoryStream<Path>, targetpath: Path) {
            val target = if (targetdir is CountingSecureDirectoryStream) targetdir.delegate else targetdir
            delegate.move(srcpath, target, targetpath)
        }
        override fun <V : FileAttributeView> getFileAttributeView(type: Class<V>): V =
            delegate.getFileAttributeView(type)
        override fun <V : FileAttributeView> getFileAttributeView(
            path: Path,
            type: Class<V>,
            vararg options: LinkOption,
        ): V {
            if (type == BasicFileAttributeView::class.java) {
                visits.computeIfAbsent(dir.resolve(path).normalize()) { AtomicInteger(0) }.incrementAndGet()
            }
            return delegate.getFileAttributeView(path, type, *options)
        }
    }
}
