package com.vscodroid.setup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * Real APK assets and Android storage, isolated from the installed editor's data.
 *
 * What an update does to the older copy of an extension a named profile lists depends on
 * whose extension it is. One the user installed (here the ESLint extension, which the build
 * fetches) keeps its older copy for as long as any profile lists it. One of OURS (the
 * process monitor) is the app and not the user's choice: the profile's entry follows the
 * update and the older copy goes. Each test names the fixture it runs against.
 */
@RunWith(AndroidJUnit4::class)
class ProfileExtensionPreservationOnDeviceTest {
    private lateinit var appContext: Context
    private lateinit var sandbox: File
    private lateinit var context: Context
    private lateinit var bundledDir: String
    private lateinit var bundledPackage: ByteArray
    private lateinit var bundledCode: ByteArray
    private lateinit var extensionId: String
    private lateinit var currentVersion: String
    private lateinit var oldDir: String
    private lateinit var oldPackage: ByteArray

    private val preferencePrefix = "profile-extension-preservation-"

    /** A file of the fixture's own that a stale copy would not match. */
    private lateinit var codeFile: String
    private val oldVersion = "0.0.0"
    private val extensionsDir get() = File(sandbox, "home/.vscodroid/extensions")
    private val defaultManifest get() = File(extensionsDir, "extensions.json")
    private val profileManifest get() = File(sandbox, "home/.vscodroid/data/User/profiles/saved/extensions.json")

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        sandbox = File(appContext.filesDir, "profile-extension-preservation")
        assertTrue("could not clear the previous sandbox", sandbox.deleteRecursively())
        assertTrue("could not create the sandbox", sandbox.mkdirs())
        context = isolatedContext()
        assertTrue(context.getSharedPreferences("vscodroid_setup", Context.MODE_PRIVATE).edit().clear().commit())
        assertEquals(sandbox, context.applicationContext.filesDir)
    }

    /**
     * One of OUR extensions, which an update replaces everywhere: the process monitor,
     * always unpacked and small.
     */
    private fun useOwnExtension() = useFixture("vscodroid.vscodroid-process-monitor-", "extension.js")

    /**
     * One the user could have installed, which the build fetches and an update leaves
     * alone wherever a profile still lists its older copy.
     */
    private fun useFetchedExtension() = useFixture("dbaeumer.vscode-eslint-", "License.txt")

    private fun useFixture(directoryPrefix: String, codeFile: String) {
        this.codeFile = codeFile
        val candidates = appContext.assets.list("extensions").orEmpty()
            .filter { it.startsWith(directoryPrefix) }
        assertEquals("the APK must contain one $directoryPrefix extension", 1, candidates.size)
        bundledDir = candidates.single()
        bundledPackage = appContext.assets.open("extensions/$bundledDir/package.json").use { it.readBytes() }
        bundledCode = appContext.assets.open("extensions/$bundledDir/$codeFile").use { it.readBytes() }
        assertTrue("the bundled extension has no file to compare", bundledCode.isNotEmpty())
        val pkg = JSONObject(String(bundledPackage, Charsets.UTF_8))
        extensionId = "${pkg.getString("publisher")}.${pkg.getString("name")}".lowercase()
        currentVersion = pkg.getString("version")
        assertTrue("the fixture needs a released numeric version", currentVersion.matches(Regex("[0-9]+(\\.[0-9]+)*")))
        assertTrue("the fixture needs a version newer than zero", currentVersion.split('.').any { it.toInt() > 0 })
        oldDir = "$extensionId-$oldVersion"
        copyAssetTree("extensions/$bundledDir", File(extensionsDir, oldDir))
        oldPackage = pkg.put("version", oldVersion).toString().toByteArray()
        File(extensionsDir, "$oldDir/package.json").writeBytes(oldPackage)
        defaultManifest.writeText(JSONArray().put(entry(oldDir, oldVersion)).toString())
    }

    @After
    fun tearDown() {
        assertTrue("could not remove the test sandbox", sandbox.deleteRecursively())
        assertTrue(appContext.getSharedPreferences("${preferencePrefix}vscodroid_setup", Context.MODE_PRIVATE)
            .edit().clear().commit())
    }

    private fun isolatedContext(): Context = object : ContextWrapper(appContext) {
        override fun getFilesDir(): File = sandbox
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$preferencePrefix$name", mode)
    }

    private fun copyAssetTree(path: String, destination: File) {
        val children = appContext.assets.list(path) ?: error("could not list $path")
        if (children.isEmpty()) {
            destination.parentFile!!.mkdirs()
            appContext.assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else {
            assertTrue("could not create $destination", destination.mkdirs())
            for (child in children) copyAssetTree("$path/$child", File(destination, child))
        }
    }

    private fun entry(directory: String, version: String, id: String = extensionId): JSONObject = JSONObject()
        .put("identifier", JSONObject().put("id", id))
        .put("version", version)
        .put("relativeLocation", directory)
        .put("location", JSONObject().put("\$mid", 1).put("scheme", "file")
            .put("path", File(extensionsDir, directory).absolutePath))
        .put("metadata", JSONObject().put("source", "bundled").put("isBuiltin", true))

    private fun writeProfile(pathOnly: Boolean = false): ByteArray {
        val saved = entry(oldDir, oldVersion)
        if (pathOnly) saved.remove("relativeLocation")
        val bytes = JSONArray().put(saved).toString().toByteArray()
        assertTrue(profileManifest.parentFile!!.mkdirs())
        profileManifest.writeBytes(bytes)
        return bytes
    }

    private fun extractBundledExtensions() {
        FirstRunSetup::class.java.getDeclaredMethod("extractBundledExtensions")
            .apply { isAccessible = true }.invoke(FirstRunSetup(context))
    }

    private fun assertOldCopyIntact() {
        assertTrue("the saved profile's extension was deleted", File(extensionsDir, oldDir).isDirectory)
        assertArrayEquals(oldPackage, File(extensionsDir, "$oldDir/package.json").readBytes())
        assertArrayEquals(bundledCode, File(extensionsDir, "$oldDir/$codeFile").readBytes())
    }

    private fun assertDefaultAdvanced() {
        assertArrayEquals(bundledPackage, File(extensionsDir, "$bundledDir/package.json").readBytes())
        assertArrayEquals(bundledCode, File(extensionsDir, "$bundledDir/$codeFile").readBytes())
        val entries = JSONArray(defaultManifest.readText())
        val selected = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.getJSONObject("identifier").getString("id") == extensionId }
        assertEquals("the default profile must select one version", 1, selected.size)
        assertEquals(bundledDir, selected.single().getString("relativeLocation"))
        assertEquals(currentVersion, selected.single().getString("version"))
    }

    @Test
    fun namedProfileKeepsItsVersionWhileDefaultAdvances() {
        useFetchedExtension()
        val savedManifest = writeProfile()

        extractBundledExtensions()

        assertOldCopyIntact()
        assertArrayEquals(savedManifest, profileManifest.readBytes())
        assertDefaultAdvanced()
    }

    @Test
    fun absolutePathProfileReferenceKeepsItsVersion() {
        useFetchedExtension()
        val savedManifest = writeProfile(pathOnly = true)

        extractBundledExtensions()

        assertOldCopyIntact()
        assertArrayEquals(savedManifest, profileManifest.readBytes())
        assertDefaultAdvanced()
    }

    @Test
    fun unreferencedSupersededVersionIsRemoved() {
        useOwnExtension()
        extractBundledExtensions()

        assertFalse("an unused older version survived", File(extensionsDir, oldDir).exists())
        assertDefaultAdvanced()
    }

    @Test
    fun malformedProfileManifestPreservesItsVersion() {
        useOwnExtension()
        writeProfile()
        val malformed = "[{truncated".toByteArray()
        profileManifest.writeBytes(malformed)

        extractBundledExtensions()

        assertOldCopyIntact()
        assertArrayEquals(malformed, profileManifest.readBytes())
        assertDefaultAdvanced()
    }

    @Test
    fun unreadableProfileManifestPreservesItsVersion() {
        useOwnExtension()
        val savedManifest = writeProfile()
        try {
            Os.chmod(profileManifest.path, 0)
            try {
                profileManifest.inputStream().use { it.read() }
                throw AssertionError("the profile manifest remained readable at mode 0000")
            } catch (expected: IOException) {
                // The actual app uid cannot read the profile's references.
            }

            extractBundledExtensions()

            assertOldCopyIntact()
            assertDefaultAdvanced()
        } finally {
            Os.chmod(profileManifest.path, 384)
        }
        assertArrayEquals(savedManifest, profileManifest.readBytes())
    }

    @Test
    fun removingTheReferenceReclaimsTheOldVersionAfterReopen() {
        useFetchedExtension()
        writeProfile()
        extractBundledExtensions()
        assertOldCopyIntact()
        assertDefaultAdvanced()

        profileManifest.writeText("[]")
        context = isolatedContext()
        extractBundledExtensions()

        assertFalse("the released profile reference still protects the old copy", File(extensionsDir, oldDir).exists())
        assertEquals("[]", profileManifest.readText())
        assertDefaultAdvanced()
    }

    @Test
    fun namedProfileFollowsOurOwnExtensionToTheNewVersion() {
        useOwnExtension()
        writeProfile()

        extractBundledExtensions()

        assertFalse("the profile kept the app on its old extension", File(extensionsDir, oldDir).exists())
        assertDefaultAdvanced()
        val listed = profileEntries().filter { it.getJSONObject("identifier").getString("id") == extensionId }
        assertEquals("the profile must list the extension once", 1, listed.size)
        assertEquals(bundledDir, listed.single().getString("relativeLocation"))
        assertEquals(currentVersion, listed.single().getString("version"))
        assertEquals(
            File(extensionsDir, bundledDir).absolutePath,
            listed.single().getJSONObject("location").getString("path"),
        )
    }

    @Test
    fun namedProfileEntryWithAPathAloneFollowsToo() {
        useOwnExtension()
        writeProfile(pathOnly = true)

        extractBundledExtensions()

        assertFalse(File(extensionsDir, oldDir).exists())
        val entry = profileEntries().single { it.getJSONObject("identifier").getString("id") == extensionId }
        assertFalse("a key the profile never wrote was added", entry.has("relativeLocation"))
        assertEquals(
            File(extensionsDir, bundledDir).absolutePath,
            entry.getJSONObject("location").getString("path"),
        )
    }

    @Test
    fun aProfileThatListsOurNewCopyAlreadyDropsTheOldEntry() {
        useOwnExtension()
        writeProfile()
        // The profile was opened by an app that listed the new copy as well.
        val both = JSONArray(profileManifest.readText()).put(entry(bundledDir, currentVersion))
        profileManifest.writeText(both.toString())

        extractBundledExtensions()

        assertFalse(File(extensionsDir, oldDir).exists())
        val listed = profileEntries().filter { it.getJSONObject("identifier").getString("id") == extensionId }
        assertEquals(listOf(bundledDir), listed.map { it.getString("relativeLocation") })
    }

    private fun profileEntries(): List<JSONObject> {
        val entries = JSONArray(profileManifest.readText())
        return (0 until entries.length()).map { entries.getJSONObject(it) }
    }

    @Test
    fun defaultProfileAdvancesBesideAGalleryCopyOnlyAnotherProfileLists() {
        useOwnExtension()
        // A fetched extension, so that the decision not to unpack a copy the user already
        // has applies to it: the process monitor above is ours and is always unpacked.
        val fetchedDir = appContext.assets.list("extensions").orEmpty()
            .single { it.startsWith("dbaeumer.vscode-eslint-") }
        val fetchedPackage = appContext.assets.open("extensions/$fetchedDir/package.json").use { it.readBytes() }
        val pkg = JSONObject(String(fetchedPackage, Charsets.UTF_8))
        val id = "${pkg.getString("publisher")}.${pkg.getString("name")}".lowercase()
        val olderDefault = "$id-0.0.0"
        val gallery = "$id-0.0.1-universal"
        for ((dir, version) in listOf(olderDefault to "0.0.0", gallery to "0.0.1")) {
            copyAssetTree("extensions/$fetchedDir", File(extensionsDir, dir))
            File(extensionsDir, "$dir/package.json")
                .writeBytes(JSONObject(String(fetchedPackage, Charsets.UTF_8)).put("version", version).toString().toByteArray())
        }
        defaultManifest.writeText(
            JSONArray().put(entry(oldDir, oldVersion)).put(entry(olderDefault, "0.0.0", id)).toString(),
        )
        assertTrue(profileManifest.parentFile!!.mkdirs())
        profileManifest.writeText(JSONArray().put(entry(gallery, "0.0.1", id)).toString())

        extractBundledExtensions()

        assertArrayEquals(fetchedPackage, File(extensionsDir, "$fetchedDir/package.json").readBytes())
        assertFalse("the superseded copy was left behind", File(extensionsDir, olderDefault).exists())
        assertTrue("the named profile's own copy was deleted", File(extensionsDir, gallery).isDirectory)
        val entries = JSONArray(defaultManifest.readText())
        val listed = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.getJSONObject("identifier").getString("id") == id }
            .map { it.getString("relativeLocation") }
        assertEquals("the default profile must keep the extension at its new version", listOf(fetchedDir), listed)
    }
}
