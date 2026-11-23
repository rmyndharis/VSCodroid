package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver
import android.os.SystemClock
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.security.MessageDigest

class SafPreOpenFailureBaselineTest {

    @TempDir
    lateinit var root: File

    private lateinit var mirror: File
    private lateinit var filesDir: File
    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri
    private lateinit var docUri: Uri

    private var deviceText = "baseline"
    private var deviceModified = 1_700_000_000_000L
    private var deviceSize = 8L
    private var failStampQueries = false
    private var nextOpenFailure: (() -> ByteArrayOutputStream?)? = null
    private val preserved = mutableListOf<List<File>>()

    @BeforeEach
    fun setUp() {
        mirror = File(root, "mirror").apply { mkdirs() }
        filesDir = File(root, "files").apply { mkdirs() }
        deviceText = "baseline"
        deviceModified = 1_700_000_000_000L
        deviceSize = deviceText.length.toLong()
        failStampQueries = false
        nextOpenFailure = null
        preserved.clear()

        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L

        // Provide a SecureDirectoryStream adapter so writeLocalToSafHoldingDocument runs
        // end-to-end on JVM hosts whose default FileSystem omits SecureDirectoryStream.
        mockkStatic(Files::class)
        every { Files.newDirectoryStream(any<Path>()) } answers {
            val dir = firstArg<Path>()
            val real = callOriginal()
            if (real is SecureDirectoryStream<Path>) real else LocalSecureDirectoryStream(dir, real)
        }

        treeUri = mockk<Uri>(relaxed = true).also { every { it.toString() } returns "tree:preopen" }
        docUri = mockk<Uri>(relaxed = true).also { every { it.toString() } returns "doc:notes.txt" }

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.getDocumentId(any()) } returns "notes.txt"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns mockk(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns docUri

        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            if (failStampQueries) throw IOException("provider offline")
            val cursor = mockk<Cursor>(relaxed = true)
            var row = -1
            every { cursor.extras } returns null
            every { cursor.moveToFirst() } returns true
            every { cursor.moveToNext() } answers { ++row == 0 }
            every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns 0
            every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE) } returns 1
            every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 2
            every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 3
            every { cursor.isNull(any()) } returns false
            every { cursor.getLong(0) } answers { deviceModified }
            every { cursor.getLong(1) } answers { deviceSize }
            every { cursor.getString(2) } returns "notes.txt"
            every { cursor.getString(3) } returns "notes.txt"
            cursor
        }
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream(deviceText.toByteArray())
        }
        every { resolver.openOutputStream(any(), "wt") } answers {
            val override = nextOpenFailure
            if (override != null) {
                override()
            } else {
                object : ByteArrayOutputStream() {
                    override fun close() {
                        super.close()
                        deviceText = toString(Charsets.UTF_8.name())
                        deviceSize = deviceText.length.toLong()
                        deviceModified += 1_000L
                    }
                }
            }
        }

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
        engine.onDeviceCopiesPreserved = { preserved += it }
        engine.resetCache(treeUri)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun seedBaseline(file: File, text: String) {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        val deviceStateClass = Class.forName("com.vscodroid.storage.DeviceState")
        val ctor = deviceStateClass.getDeclaredConstructor(Pair::class.java, ByteArray::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val state = ctor.newInstance(deviceModified to deviceSize, digest, text.length.toLong())
        @Suppress("UNCHECKED_CAST")
        val map = SafSyncEngine::class.java.getDeclaredField("deviceSeen")
            .apply { isAccessible = true }
            .get(engine) as MutableMap<String, Any>
        map[file.absolutePath] = state
    }

    private fun save(file: File, text: String) {
        file.writeText(text)
        engine.handleMirrorEvent(FileObserver.MODIFY, file, mirror, treeUri)
        engine.runWriteBackLoop { false }
    }

    private fun deviceConflictCopies(): List<File> =
        mirror.listFiles().orEmpty().filter { it.name.contains(SafSyncEngine.DEVICE_COPY_SUFFIX) }

    @Test
    fun `pre-open SecurityException with unchanged stamp preserves sha256 baseline`() {
        val file = File(mirror, "notes.txt").apply { writeText("baseline") }
        seedBaseline(file, "baseline")

        nextOpenFailure = { throw SecurityException("transient permission denial") }
        save(file, "first edit")

        // Provider recovers without anyone editing the device copy.
        nextOpenFailure = null
        save(file, "second edit")

        assertEquals("second edit", deviceText)
        assertTrue(
            deviceConflictCopies().isEmpty(),
            "a pre-open SecurityException wiped the SHA-256 baseline and caused a false conflict copy: ${deviceConflictCopies()}",
        )
        assertTrue(preserved.isEmpty(), "a false device conflict notice was emitted")
    }

    @Test
    fun `pre-open offline failure when stamp query also fails still preserves sha256 baseline`() {
        val file = File(mirror, "notes.txt").apply { writeText("baseline") }
        seedBaseline(file, "baseline")

        nextOpenFailure = {
            failStampQueries = true
            throw IOException("provider offline")
        }
        save(file, "first edit")

        // Provider comes back online with untouched bytes.
        failStampQueries = false
        nextOpenFailure = null
        save(file, "second edit")

        assertEquals("second edit", deviceText)
        assertTrue(
            deviceConflictCopies().isEmpty(),
            "an offline pre-open failure wiped the SHA-256 baseline: ${deviceConflictCopies()}",
        )
    }

    @Test
    fun `pre-open failure where broken provider moved stamp wipes baseline and preserves changed device copy`() {
        val file = File(mirror, "notes.txt").apply { writeText("baseline") }
        seedBaseline(file, "baseline")

        // Simulate a broken provider that alters/truncates the document before throwing/returning null.
        nextOpenFailure = {
            deviceText = "trunc"
            deviceSize = deviceText.length.toLong()
            deviceModified += 5_000L
            null
        }
        save(file, "first edit")

        // Verify deviceSeen digest was cleared so the moved state is distrusted.
        @Suppress("UNCHECKED_CAST")
        val map = SafSyncEngine::class.java.getDeclaredField("deviceSeen")
            .apply { isAccessible = true }
            .get(engine) as Map<String, Any>
        val state = checkNotNull(map[file.absolutePath])
        val shaField = state.javaClass.getDeclaredField("sha256").apply { isAccessible = true }
        assertEquals(
            null,
            shaField.get(state),
            "a pre-open failure that moved the provider stamp must invalidate the stored SHA-256 baseline",
        )
    }

    private class LocalSecureDirectoryStream(
        private val dir: Path,
        private val delegate: DirectoryStream<Path>,
    ) : SecureDirectoryStream<Path> {
        override fun iterator(): MutableIterator<Path> = delegate.iterator()
        override fun close() = delegate.close()
        override fun newDirectoryStream(path: Path, vararg options: LinkOption): SecureDirectoryStream<Path> {
            val child = dir.resolve(path)
            if (LinkOption.NOFOLLOW_LINKS in options && Files.isSymbolicLink(child)) {
                throw IOException("Refused symlink $child")
            }
            return LocalSecureDirectoryStream(child, Files.newDirectoryStream(child))
        }
        override fun newByteChannel(
            path: Path,
            options: MutableSet<out OpenOption>,
            vararg attrs: FileAttribute<*>,
        ): SeekableByteChannel = Files.newByteChannel(dir.resolve(path), options, *attrs)
        override fun deleteFile(path: Path) = Files.delete(dir.resolve(path))
        override fun deleteDirectory(path: Path) = Files.delete(dir.resolve(path))
        override fun move(srcpath: Path, targetdir: SecureDirectoryStream<Path>, targetpath: Path) {
            val targetRoot = (targetdir as LocalSecureDirectoryStream).dir
            Files.move(dir.resolve(srcpath), targetRoot.resolve(targetpath))
        }
        override fun <V : FileAttributeView> getFileAttributeView(type: Class<V>): V =
            Files.getFileAttributeView(dir, type, LinkOption.NOFOLLOW_LINKS)
        override fun <V : FileAttributeView> getFileAttributeView(
            path: Path,
            type: Class<V>,
            vararg options: LinkOption,
        ): V = Files.getFileAttributeView(dir.resolve(path), type, *options)
    }
}
