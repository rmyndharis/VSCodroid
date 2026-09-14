package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Where Python keeps its bytecode, which decides how fast every Python process
 * in the app starts.
 *
 * `PYTHONDONTWRITEBYTECODE=1` stood in the environment and the shipped tree
 * carries no `.pyc`, so every import re-parsed source on every run: measured on
 * an API 33 emulator, `python3 -m pip --version` took 328ms against 97ms with a
 * warm cache. The two variables do not combine, DONTWRITEBYTECODE wins, so a
 * build that brought it back would take the cost back with the prefix still set.
 */
class PythonBytecodeEnvTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private val cacheDir get() = File(filesDir, "cache")

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"
        }
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `bytecode is written, under the cache directory`() {
        val env = Environment.buildProcessEnvironment(context, 1234)

        assertFalse(
            "PYTHONDONTWRITEBYTECODE" in env,
            "PYTHONDONTWRITEBYTECODE is back; it wins over the prefix, so every Python " +
                "process re-parses its imports again",
        )
        assertEquals(
            "${cacheDir.absolutePath}/pycache",
            env["PYTHONPYCACHEPREFIX"],
            "bytecode would land beside the sources, in the asset tree and the user's project",
        )
    }

    /**
     * The storage screen counts [StorageManager.CLEARABLE_CACHE_DIRS] and
     * [StorageManager.clearCaches] names its directories by hand, so the name
     * has to reach both, or the bytecode grows where the Clear button cannot.
     */
    @Test
    fun `clearing caches removes the bytecode`() {
        assertTrue("pycache" in StorageManager.CLEARABLE_CACHE_DIRS)
        val pyc = File(cacheDir, "pycache/data/usr/lib/python3.14/json/__init__.cpython-314.pyc")
        pyc.parentFile!!.mkdirs()
        pyc.writeBytes(ByteArray(4096))

        val freed = StorageManager.clearCaches(context)

        assertFalse(File(cacheDir, "pycache").exists(), "Clear Caches left the bytecode behind")
        assertTrue(freed >= 4096, "the bytecode was not counted in what was freed: $freed")
    }

    /**
     * Bytecode of a source under the cache directory, which is where pip builds
     * from source, outlives the source; the rest is kept, because recompiling it
     * is the cost the prefix exists to avoid.
     */
    @Test
    fun `bytecode of temporary build trees is pruned and the rest is kept`() {
        val prefix = File(cacheDir, "pycache")
        val temporary = File(prefix, "${cacheDir.absolutePath.trimStart('/')}/tmp/pip-build-env-x/setuptools/__init__.cpython-314.pyc")
        val kept = File(prefix, "${filesDir.absolutePath.trimStart('/')}/usr/lib/python3.14/json/__init__.cpython-314.pyc")
        listOf(temporary, kept).forEach { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(64)) }

        StorageManager.pruneTemporaryBytecode(context)

        assertFalse(temporary.exists(), "bytecode of a deleted build tree was left behind")
        assertTrue(kept.exists(), "the standard library's bytecode was pruned with it")
    }

    /**
     * pip realpaths its build directories, so their bytecode is filed under the
     * canonical spelling of the cache directory, which in the app process is
     * `/data/data` behind the `/data/user/0` the context reports. Modelled here
     * with a symlinked cache directory.
     */
    @Test
    fun `bytecode filed under the canonical cache path is pruned too`() {
        val real = File(filesDir, "real-cache").apply { mkdirs() }
        val link = File(filesDir, "linked-cache")
        java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())
        every { context.cacheDir } returns link
        val prefix = File(link, "pycache")
        val canonical = File(prefix, "${real.canonicalPath.trimStart('/')}/tmp/pip-build-env-y/x.cpython-314.pyc")
        canonical.parentFile!!.mkdirs()
        canonical.writeBytes(ByteArray(64))

        StorageManager.pruneTemporaryBytecode(context)

        assertFalse(canonical.exists(), "bytecode under the canonical cache path was left behind")
    }

    @Test
    fun `pip's cache is under the cache directory and cleared with it`() {
        val env = Environment.buildProcessEnvironment(context, 1234)
        assertEquals("${cacheDir.absolutePath}/pip", env["PIP_CACHE_DIR"], "pip keeps its cache under files again")
        assertTrue("pip" in StorageManager.CLEARABLE_CACHE_DIRS)

        val wheel = File(cacheDir, "pip/wheels/ab/numpy.whl")
        wheel.parentFile!!.mkdirs()
        wheel.writeBytes(ByteArray(2048))
        val legacy = File(filesDir, "home/.cache/pip/http-v2/a")
        legacy.parentFile!!.mkdirs()
        legacy.writeBytes(ByteArray(16))

        StorageManager.clearCaches(context)
        StorageManager.removeLegacyPipCache(context)

        assertFalse(wheel.exists(), "Clear Caches left pip's cache behind")
        assertFalse(legacy.exists(), "the cache pip kept under files before is still there")
    }
}
