package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.clearMocks
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class SameVersionPackageReplacementTest {
    @TempDir lateinit var filesDir: File
    @TempDir lateinit var cacheDir: File
    private lateinit var context: Context
    private lateinit var packageInfo: PackageInfo
    private var currentCode = 16L
    private val stored = mutableMapOf<String, Any>()
    private val payloads = linkedMapOf<String, String>()
    private val ownDir = "vscodroid.sample-1.0.0"
    private val first get() = File(filesDir, "server/vscode-reh/a.js")
    private val second get() = File(filesDir, "server/vscode-reh/b.js")
    private val ownSource get() = File(filesDir, "home/.vscodroid/extensions/$ownDir/extension.js")

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        payloads["vscode-reh/a.js"] = "// alpha"
        payloads["vscode-reh/b.js"] = "// bravo"
        payloads["extensions/$ownDir/package.json"] =
            """{"publisher":"vscodroid","name":"sample","version":"1.0.0"}"""
        payloads["extensions/$ownDir/extension.js"] = "// alpha"
        val assets = mockk<AssetManager>()
        every { assets.list(any()) } answers {
            val prefix = firstArg<String>().trimEnd('/') + "/"
            payloads.keys.filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }.distinct().toTypedArray()
        }
        every { assets.open(any()) } answers {
            ByteArrayInputStream((payloads[firstArg<String>()] ?: throw IOException("absent")).toByteArray())
        }
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            val key = firstArg<String>()
            val value = secondArg<String?>()
            if (value == null) stored.remove(key) else stored[key] = value
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
        every { prefs.getString(any(), any()) } answers { stored[firstArg<String>()] as? String ?: secondArg() }
        every { prefs.getInt(any(), any()) } answers { stored[firstArg<String>()] as? Int ?: secondArg() }
        every { prefs.getLong(any(), any()) } answers { stored[firstArg<String>()] as? Long ?: secondArg() }
        every { prefs.getBoolean(any(), any()) } answers { stored[firstArg<String>()] as? Boolean ?: secondArg() }
        every { prefs.getStringSet(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (stored[firstArg<String>()] as? Set<String>)?.toMutableSet() ?: secondArg<Set<String>?>()?.toMutableSet()
        }
        packageInfo = mockk(relaxed = true)
        packageInfo.versionName = "1.5.0"
        packageInfo.lastUpdateTime = 1_000L
        every { packageInfo.longVersionCode } answers { currentCode }
        val manager = mockk<PackageManager>()
        every { manager.getPackageInfo("com.vscodroid", 0) } returns packageInfo
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.packageName } returns "com.vscodroid"
        every { context.packageManager } returns manager
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = File(filesDir, "native").path
        }
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        SetupStepStrings.stub(context)
    }

    @AfterEach fun tearDown() = unmockkAll()
    private fun setup() = FirstRunSetup(context, 0, 0, 0, 0)

    private fun launchSetupDecision(): FirstRunSetup.SetupResult = runBlocking {
        val setup = setup()
        if (setup.isFirstRun()) setup.runSetup() else FirstRunSetup.SetupResult.SUCCESS
    }

    private fun replacementPackage() {
        packageInfo.lastUpdateTime = 2_000L
        payloads["vscode-reh/a.js"] = "// omega"
        payloads["extensions/$ownDir/extension.js"] = "// omega"
        assertEquals("// alpha".length, payloads.getValue("vscode-reh/a.js").length)
    }

    private fun interruptedPackage(): File {
        val blocker = File(second.parentFile, "${second.name}.tmp~")
        assertTrue(blocker.mkdirs())
        File(blocker, "occupied").writeText("x")
        assertEquals(FirstRunSetup.SetupResult.ERROR, launchSetupDecision())
        assertEquals("// alpha", first.readText())
        assertFalse(stored.containsKey("setup_version"))
        assertEquals("1.5.0/16/1000", stored["extraction_attempt"])
        return blocker
    }

    @Test
    fun `a replacement package refreshes completed assets at unchanged version labels`() {
        assertEquals(FirstRunSetup.SetupResult.SUCCESS, launchSetupDecision())
        assertEquals("1.5.0", stored["setup_version"])
        assertEquals(16, stored["setup_version_code"])
        assertEquals(1_000L, stored["setup_package_update_time"])
        assertEquals("// alpha", ownSource.readText())
        replacementPackage()

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, launchSetupDecision())

        assertEquals("// omega", first.readText())
        assertEquals("// omega", ownSource.readText())
        assertEquals(2_000L, stored["setup_package_update_time"])
    }

    @Test
    fun `a replacement package rewrites equal length assets from an interrupted package`() {
        val blocker = interruptedPackage()
        replacementPackage()
        assertTrue(blocker.deleteRecursively())

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, launchSetupDecision())

        assertEquals("// omega", first.readText())
        assertEquals("// bravo", second.readText())
        assertEquals("// omega", ownSource.readText())
    }

    @Test
    fun `an unchanged package finishes its interrupted extraction`() {
        val blocker = interruptedPackage()
        assertTrue(blocker.deleteRecursively())

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, launchSetupDecision())

        assertEquals("// alpha", first.readText())
        assertEquals("// bravo", second.readText())
        assertFalse(setup().isFirstRun())
    }

    @Test
    fun `a changed version code refreshes equal length assets after an interruption`() {
        val blocker = interruptedPackage()
        replacementPackage()
        currentCode = 17L
        assertTrue(blocker.deleteRecursively())

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, launchSetupDecision())

        assertEquals("// omega", first.readText())
        assertEquals(17, stored["setup_version_code"])
        assertFalse(setup().isFirstRun())
    }

    @Test
    fun `an interrupted replacement remains ineligible for length skips`() {
        val blocker = interruptedPackage()
        replacementPackage()
        assertEquals(FirstRunSetup.SetupResult.ERROR, launchSetupDecision())
        assertEquals("1.5.0/16/2000!mixed", stored["extraction_attempt"])
        first.writeText("// alpha")
        assertTrue(blocker.deleteRecursively())

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, launchSetupDecision())

        assertEquals("// omega", first.readText())
        assertFalse(stored.containsKey("extraction_attempt"))
    }

    @Test
    fun `a legacy completion refreshes a replacement once and records its identity`() {
        assertEquals(FirstRunSetup.SetupResult.SUCCESS, launchSetupDecision())
        stored.remove("setup_package_update_time")
        assertEquals("1.5.0", stored["setup_version"])
        assertEquals(16, stored["setup_version_code"])
        assertFalse(stored.containsKey("setup_package_update_time"))
        replacementPackage()
        assertTrue(setup().isFirstRun())

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, launchSetupDecision())

        assertEquals("// omega", first.readText())
        assertEquals("// omega", ownSource.readText())
        assertEquals(2_000L, stored["setup_package_update_time"])
        val assets = context.assets
        clearMocks(assets, answers = false)

        assertFalse(setup().isFirstRun())
        assertEquals(FirstRunSetup.SetupResult.SUCCESS, launchSetupDecision())
        verify(exactly = 0) { assets.open(any()) }
        assertEquals(2_000L, stored["setup_package_update_time"])
    }
}
