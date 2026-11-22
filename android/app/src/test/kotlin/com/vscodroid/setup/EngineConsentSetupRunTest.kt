package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The setup that turns a restored `vscodroid.antigravity.runEngine` off, run rather than read.
 *
 * The consent is given for the apps on one phone and the machine settings file carries it to the
 * next one with a backup. What a restore leaves is the file and no record that any build ever
 * finished setup here, because the preferences are not restored. So setup turns the setting off
 * where no build has completed, and must leave an install that has completed alone: an upgrade
 * keeps the answer its person gave, and withdrawing it every time would turn the engine off after
 * each update. Both halves are security relevant, and a test that matched the source text for the
 * call let either of them break with every case green.
 *
 * Driven through the real `runSetup()` against a real temporary tree, the way
 * `ExtractionResumeTest` drives it, with preferences that remember.
 */
class EngineConsentSetupRunTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var cacheDir: File

    private lateinit var context: Context
    private val strings = mutableMapOf<String, String>()
    private val ints = mutableMapOf<String, Int>()

    private val settings by lazy { File(filesDir, "home/.vscodroid/data/Machine/settings.json") }
    private val key = "vscodroid.antigravity.runEngine"

    /** What a restore puts back: the person's file, with the setting on and settings of theirs beside it. */
    private val restored = "{\n    \"git.path\": \"/x/libgit.so\",\n    \"$key\": true,\n    \"update.mode\": \"none\"\n}\n"

    @BeforeEach
    fun setUp() {
        mockkStatic(Files::class)
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        val assets = mockk<AssetManager>()
        every { assets.list(any()) } returns emptyArray()
        every { assets.open(any()) } throws IOException("absent")
        every { assets.list("vscode-reh") } returns arrayOf("a.js", "b.js")
        every { assets.list("vscode-reh/a.js") } returns emptyArray()
        every { assets.list("vscode-reh/b.js") } returns emptyArray()
        every { assets.open("vscode-reh/a.js") } answers { ByteArrayInputStream("// alpha".toByteArray()) }
        every { assets.open("vscode-reh/b.js") } answers { ByteArrayInputStream("// bravo".toByteArray()) }

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers { strings[firstArg()] = secondArg(); editor }
        every { editor.putInt(any(), any()) } answers { ints[firstArg()] = secondArg(); editor }
        every { editor.remove(any()) } answers { strings.remove(firstArg<String>()); ints.remove(firstArg<String>()); editor }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers { strings[firstArg<String>()] ?: secondArg() }
        every { prefs.getInt(any(), any()) } answers { ints[firstArg<String>()] ?: secondArg() }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun setup() = FirstRunSetup(
        context,
        assetBytes = 0,
        largestAssetBytes = 0,
        bundledUsrBytes = 0,
        bundledExtensionBytes = 0,
    )

    private fun restore() {
        settings.parentFile.mkdirs()
        settings.writeText(restored)
    }

    private fun run() = runBlocking { setup().runSetup() }

    /** Whether any spelling of the setting is still on, by the reader that turns it off. */
    private fun stillOn() = withEngineConsentWithdrawn(settings.readText()) != null

    @Test
    fun `a restore, with no build ever completed here, comes up with the setting off and the rest kept`() {
        restore()

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, run())

        assertFalse(stillOn(), "the engine's consent travelled to another phone: ${settings.readText()}")
        val text = settings.readText()
        assertTrue(text.contains("\"$key\": false"), text)
        assertTrue(text.contains("\"git.path\": \"/x/libgit.so\"") && text.contains("\"update.mode\": \"none\""), "the rest of the file moved: $text")
    }

    @Test
    fun `an install a build has completed on keeps the setting through a setup of its own`() {
        // A completion record with no version code: not an upgrade, so the first-run steps run again, and
        // the person's answer is not theirs to be asked again.
        strings["setup_version"] = "1.0.0"
        restore()

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, run())

        assertTrue(stillOn(), "setup turned the engine off on an install that had already completed it")
    }

    @Test
    fun `an upgrade keeps the setting`() {
        strings["setup_version"] = "1.0.0"
        ints["setup_version_code"] = 5
        restore()

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, run())

        assertTrue(stillOn(), "an update turned the engine off for the person who had turned it on")
    }

    @Test
    fun `a first setup that was interrupted and retried withdraws it on the retry`() {
        restore()
        val blocker = blockAtomicWritesTo(File(filesDir, "server/vscode-reh/b.js"))
        assertEquals(FirstRunSetup.SetupResult.ERROR, run(), "the staged failure did not fail the run")
        assertTrue(stillOn(), "setup withdrew the setting before it got as far as the settings")
        blocker.close()

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, run())

        assertFalse(stillOn(), "a retried first setup left the consent standing: ${settings.readText()}")
    }

    @Test
    fun `without a settings file there is nothing to withdraw and no failure`() {
        assertFalse(settings.exists())

        setup().withdrawRestoredEngineConsent()

        assertFalse(settings.exists(), "withdrawing created a settings file")
    }

    @Test
    fun `a settings file that does not mention the setting is left byte for byte`() {
        settings.parentFile.mkdirs()
        val before = "{\n    // mine\n    \"update.mode\": \"none\",\n}\n"
        settings.writeText(before)

        setup().withdrawRestoredEngineConsent()

        assertEquals(before, settings.readText())
    }

    /**
     * A second launch's settings refresh runs beside setup and outside the setup lock (Home and a relaunch
     * during the unpack). One that read the restored file before the withdrawal and wrote after it put the
     * person's `true` back for good, since nothing withdraws it once setup has completed. Each of the two
     * holds the file's write lock from its read to its write, so one lands wholly before the other.
     */
    @Test
    fun `a settings refresh beside setup cannot put a withdrawn consent back`() {
        restore()

        whileWaitingOnTheLock(other = { setup().updateSettingsNativeLibPaths() }) {
            setup().withdrawRestoredEngineConsent()
        }

        assertTrue(
            settings.readText().contains("vscodroid.layout.compactScreen"),
            "the refresh wrote nothing, so it proves nothing: ${settings.readText()}",
        )
        assertFalse(stillOn(), "a refresh beside setup put the restored consent back: ${settings.readText()}")
    }

    /**
     * The other order. A withdrawal that read the file before a refresh and wrote after it took the refresh's
     * paths and layout fact back out until the next launch; under the lock it reads what the refresh wrote.
     */
    @Test
    fun `a withdrawal beside a settings refresh keeps what the refresh wrote`() {
        restore()

        whileWaitingOnTheLock(other = { setup().withdrawRestoredEngineConsent() }) {
            setup().updateSettingsNativeLibPaths()
        }

        assertFalse(stillOn(), "the consent was left standing: ${settings.readText()}")
        assertTrue(
            settings.readText().contains("vscodroid.layout.compactScreen"),
            "the withdrawal wrote back what it read before the refresh: ${settings.readText()}",
        )
    }

    /**
     * Holds the settings file's write lock, starts [other] on its own thread, and runs [inside] (the lock lets
     * the thread that holds it in) once [other] waits on that lock. A writer that takes the lock only to write
     * has read the file by then, so [inside] lands between its read and its write; one that holds it from its
     * read has read nothing yet.
     */
    private fun whileWaitingOnTheLock(other: () -> Unit, inside: () -> Unit) {
        var failure: Throwable? = null
        val thread = Thread { runCatching(other).onFailure { failure = it } }
        holdingWriteLock(settings) {
            thread.start()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (thread.state != Thread.State.BLOCKED ||
                thread.stackTrace.firstOrNull()?.methodName !in setOf("holdingWriteLock", "writeAtomicallyTo")
            ) {
                check(thread.isAlive && System.nanoTime() < deadline) { "the other writer never waited for the settings file's lock" }
                Thread.sleep(5)
            }
            inside()
        }
        thread.join(10_000)
        assertFalse(thread.isAlive, "the other writer did not finish")
        assertNull(failure, "the other writer failed: $failure")
    }

    @Test
    fun `a settings file that cannot be rewritten fails loudly and keeps what it had`() {
        restore()
        val blocker = blockAtomicWritesTo(settings)

        val failure = assertThrows(IOException::class.java) { setup().withdrawRestoredEngineConsent() }

        blocker.close()
        assertTrue(failure.message.orEmpty().contains("could not write"), failure.toString())
        assertEquals(restored, settings.readText(), "a failed write changed the file")
    }
}
