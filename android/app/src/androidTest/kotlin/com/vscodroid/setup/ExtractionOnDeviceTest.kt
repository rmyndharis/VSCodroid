package com.vscodroid.setup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * The parts of bundled-extension extraction that a JVM test cannot answer.
 *
 * Everything else about this path is covered by unit tests, and those tests are
 * only as good as two assumptions they cannot check from a JVM:
 *
 *  - that `AssetManager.list()` answers an EMPTY ARRAY for a leaf. `extractAssetDir`
 *    decides "file or directory" on exactly that, and every unit test stubs it.
 *    If the real one answered null instead, extraction would return early and
 *    copy nothing, and the whole suite would still be green.
 *  - that `deleteRecursively()` succeeds on app-private storage. The retry after
 *    a failed unpack depends on it, and a shell `rm -rf` measured elsewhere is a
 *    different call, in a different process, under a different uid, in a
 *    different place.
 *
 * The last test drives the failure itself, on a filesystem taken to nearly full,
 * which is the condition the whole abort-and-retry design exists for and the one
 * no stub reproduces.
 *
 * ## What this needs
 *
 * The APK's own `assets/extensions`, and nothing else -- no server tree, no
 * binaries, no first-run setup. `getFilesDir()` is redirected into a sandbox so
 * the real AssetManager stays in play while nothing touches the app's actual
 * data. There is no `assumeTrue` here and there must not be one: every
 * precondition below is asserted, so a fixture that failed to materialise fails
 * the test rather than skipping it.
 */
@RunWith(AndroidJUnit4::class)
class ExtractionOnDeviceTest {

    private lateinit var appContext: Context
    private lateinit var sandbox: File
    private lateinit var context: Context

    /** The 29 MB one, so a nearly-full disk is certain to run out inside it. */
    private val fetched = "ms-python.python-2026.4.0"

    /**
     * One of the app's own bundled extensions, found rather than named.
     *
     * The directory carries the extension's version, and that version moves
     * whenever the manifest changes, which it must: the editor caches the
     * extension scan against the directory listing, so a manifest edited in
     * place is not read. A literal here therefore rots on a change that has
     * nothing to do with extraction, and it rots into "asset not found" rather
     * than into anything that names the real cause.
     */
    private val own: String by lazy {
        val dirs = appContext.assets.list("extensions").orEmpty()
            .filter { it.startsWith("vscodroid.vscodroid-") }
        assertTrue("no bundled VSCodroid extension under assets/extensions", dirs.isNotEmpty())
        dirs.sorted().first()
    }

    private val extensionsDir get() = File(sandbox, "home/.vscodroid/extensions")

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // filesDir, not cacheDir. The last test deliberately runs the partition
        // to nearly full, and that is exactly when the platform reclaims cache
        // directories -- the fixture would be deleted by the condition it exists
        // to create.
        sandbox = File(appContext.filesDir, "extraction-on-device")
        sandbox.deleteRecursively()
        assertTrue("could not create the sandbox", sandbox.mkdirs())

        context = object : ContextWrapper(appContext) {
            override fun getFilesDir(): File = sandbox
            // Prefixed, so rememberBundledIds cannot write into the real app's
            // preferences while this runs on a shared device.
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("extraction-on-device-$name", mode)
        }
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
        appContext.getSharedPreferences("extraction-on-device-vscodroid_setup", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun extractBundledExtensions() {
        FirstRunSetup::class.java
            .getDeclaredMethod("extractBundledExtensions")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    /**
     * The premise under every unit test on this path, asserted against the real
     * platform rather than against a stub of it.
     */
    @Test
    fun theAssetManagerContractTheUnitTestsAssume() {
        val assets = appContext.assets

        val top = assets.list("extensions")
        assertNotNull("assets/extensions is not in the APK", top)
        assertTrue("no bundled extensions in the APK", top!!.isNotEmpty())
        assertTrue("the fetched extensions were never downloaded into the APK", top.contains(fetched))

        val children = assets.list("extensions/$own")
        assertNotNull(children)
        assertTrue("package.json missing from $own", children!!.contains("package.json"))

        // The leaf test. extractAssetDir treats an empty listing as "this is a
        // file"; null would send it down the IOException branch instead, and a
        // non-empty listing would make it recurse forever.
        val leaf = assets.list("extensions/$own/package.json")
        assertNotNull("list() answered null for a leaf; extractAssetDir assumes empty", leaf)
        assertEquals("list() answered non-empty for a leaf", 0, leaf!!.size)

        // And the absent-asset contract, which extractAssetFile treats as
        // routine rather than as failure.
        try {
            assets.open("extensions/$own/no-such-file.js").close()
            fail("open() did not throw for an absent asset")
        } catch (expected: IOException) {
            // what extractAssetFile catches
        }

        // available() on a freshly opened asset is its UNCOMPRESSED length, and
        // that is the premise the resume path rests on: extractAssetFile skips a
        // destination whose length already equals this number. Nothing in the
        // APK is stored uncompressed (there is no noCompress rule), so a stream
        // reporting the packed size instead would let a retry pass over a file
        // it had not finished writing. Asserted against the bytes rather than
        // against a constant, so it stays true whatever the asset is.
        assets.open("extensions/$own/package.json").use { stream ->
            val reported = stream.available().toLong()
            val actual = stream.readBytes().size.toLong()
            assertEquals(
                "available() is not the uncompressed length, so the resume skip " +
                    "in extractAssetFile would compare against the wrong number",
                actual,
                reported,
            )
            assertTrue("the asset used for this contract is empty", actual > 0)
        }
    }

    /**
     * A1's dependency, measured with the call A1 makes, in the place it makes it.
     */
    @Test
    fun deleteRecursivelyClearsAPartialExtractionFromAppPrivateStorage() {
        val partial = File(extensionsDir, fetched)
        assertTrue(File(partial, "out/client").mkdirs())
        File(partial, "package.json").writeText("{}")
        File(partial, "out/client/extension.js").writeText("x")
        assertTrue("the fixture did not stage a partial tree", partial.isDirectory)

        assertTrue("deleteRecursively failed on app-private storage", partial.deleteRecursively())
        assertFalse("the partial tree survived", partial.exists())
    }

    /**
     * The whole tree, at the size it actually ships, through the real
     * AssetManager. The unit tests drive one or two synthetic extensions; this
     * is 3787 files across nine directories.
     */
    @Test
    fun extractsTheRealBundledTree() {
        extractBundledExtensions()

        val present = extensionsDir.list()?.toList().orEmpty()
        for (name in appContext.assets.list("extensions")!!) {
            assertTrue("$name was not unpacked", present.contains(name))
        }
        assertTrue(File(extensionsDir, "$fetched/package.json").isFile)
        assertTrue(File(extensionsDir, "$own/extension.js").isFile)

        // Counted, not sampled. Nine directories and two files would satisfy
        // everything above while the tree was essentially empty, and this ran
        // fast enough on the first pass that "did it really copy 57 MB" was a
        // fair question to ask of it. Compare against the APK rather than a
        // literal, so the number cannot go stale.
        // Counted per extension directory, not over the whole tree: the parent
        // also holds `extensions.json`, which this method generates rather than
        // unpacks. Counting it as an asset is what made the first version of
        // this assertion read 3788 against 3787.
        val names = appContext.assets.list("extensions")!!
        val files = names.sumOf { File(extensionsDir, it).walkTopDown().count { f -> f.isFile } }
        val bytes = names.sumOf { n ->
            File(extensionsDir, n).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        assertEquals("unpacked file count does not match the APK", countAssetFiles("extensions"), files)
        assertTrue("only $bytes bytes landed; the tree is tens of MB", bytes > 40L * 1024 * 1024)
        assertTrue("the manifest was not generated", File(extensionsDir, "extensions.json").isFile)
    }

    /** Recursive count of leaf assets under [path]. */
    private fun countAssetFiles(path: String): Int {
        val children = appContext.assets.list(path) ?: return 0
        if (children.isEmpty()) return 1
        return children.sumOf { countAssetFiles("$path/$it") }
    }

    /**
     * A1 itself, on the class the defect is specific to.
     *
     * A fetched extension is selected for unpacking only while its directory is
     * absent, so a copy that failed partway used to leave exactly the evidence
     * that removed it from the next attempt. Reproducing that needs a real
     * out-of-space failure: any obstruction placed by hand has to live inside
     * the extension directory, which creates it, which makes it the
     * already-there case instead.
     *
     * So the disk is taken to nearly full. Directory creation still succeeds --
     * it needs almost nothing -- and the 29 MB of file copies inside it do not.
     */
    @Test
    fun aFetchedExtensionWhoseUnpackRanOutOfSpaceIsRetriedNotSkipped() {
        val filler = File(sandbox, "filler.bin")
        try {
            fillNearlyFull(filler)
            assertTrue(
                "could not take the filesystem low enough to fail a copy; " +
                    "${sandbox.usableSpace} bytes still free",
                sandbox.usableSpace < 4L * 1024 * 1024,
            )

            try {
                extractBundledExtensions()
                fail("extraction reported success with no room to write")
            } catch (e: InvocationTargetException) {
                assertTrue(
                    "aborted with ${e.cause} rather than an IOException",
                    e.cause is IOException,
                )
            }

            assertFalse(
                "the half-unpacked directory survived; the next attempt reads it as " +
                    "installed and skips the extension for the life of the version",
                File(extensionsDir, fetched).exists(),
            )
        } finally {
            filler.delete()
        }

        // With room again, the attempt that was aborted has to actually happen.
        extractBundledExtensions()
        assertTrue(
            "the retry did not re-unpack the extension whose copy had failed",
            File(extensionsDir, "$fetched/package.json").isFile,
        )
    }

    /** Writes until the filesystem refuses, leaving under a few MB free. */
    private fun fillNearlyFull(filler: File) {
        val chunk = ByteArray(4 * 1024 * 1024)
        try {
            filler.outputStream().use { out ->
                while (sandbox.usableSpace > 4L * 1024 * 1024) {
                    out.write(chunk)
                    out.flush()
                }
            }
        } catch (e: IOException) {
            // The filesystem got there first, which is the point.
        }
    }
}
