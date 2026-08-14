package com.vscodroid.setup

import android.content.Context
import android.content.res.AssetManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What `toolchains.json` must survive, and which name forms reach it.
 *
 * The record matters more than its size suggests. It is the only list of what is
 * installed, and each entry carries the manifest naming the files, symlinks and
 * libraries an uninstall has to remove. Lose it and several hundred MB stay on
 * disk with nothing able to name them, while the app reports no toolchains at
 * all -- and `readState` turns an unparseable file into an empty array, so the
 * loss arrives looking like a legitimate answer rather than an error.
 */
class ToolchainStateIntegrityTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private lateinit var stateFile: File

    private val existingRecord =
        """[{"name":"ruby","installRoot":"usr/opt/ruby","binaries":[],"symlinks":{}}]"""

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // The constructor reaches Play Core through field initialisation, so it
        // runs before any method can be called.
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        assets = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets

        File(filesDir, "home/.vscodroid").mkdirs()
        stateFile = File(filesDir, "home/.vscodroid/toolchains.json")
        stateFile.writeText(existingRecord)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun manager() = ToolchainManager(context)

    /** Both are private; the executor is bypassed so the assertion is not a race. */
    private fun writeState(state: JSONArray) {
        ToolchainManager::class.java
            .getDeclaredMethod("writeState", JSONArray::class.java)
            .apply { isAccessible = true }
            .invoke(manager(), state)
    }

    private fun uninstallSync(name: String) {
        ToolchainManager::class.java
            .getDeclaredMethod("uninstallSync", String::class.java)
            .apply { isAccessible = true }
            .invoke(manager(), name)
    }

    private fun namesInState(): List<String> {
        val arr = JSONArray(stateFile.readText())
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    }

    /**
     * The reason the write became atomic.
     *
     * `writeText` truncates and then writes, so anything that goes wrong between
     * those two leaves a file that exists, parses as nothing, and is read as an
     * empty install list. This arranges that failure the way `UpdateSettingsPathsTest`
     * does -- a directory sits where the temporary file wants to be, so opening
     * it throws -- and requires the previous record to be exactly where it was.
     *
     * Reverting `writeState` to `stateFile.writeText(...)` turns this red: the
     * file would hold `[]` and the Ruby entry would be gone.
     */
    @Test
    fun `a failed write leaves the previous record intact`() {
        val blocker = File(filesDir, "home/.vscodroid/toolchains.json.tmp~")
        blocker.mkdirs()
        // Non-empty, so the cleanup delete cannot quietly remove it and let a
        // later attempt through -- the point is that this write fails.
        File(blocker, "occupied").writeText("x")

        writeState(JSONArray())

        assertEquals(
            listOf("ruby"), namesInState(),
            "a failed write destroyed the install record; every installed toolchain would " +
                "vanish from the app while its files stayed on disk"
        )
    }

    /**
     * The other direction, and it is what stops the test above from passing
     * against a `writeState` that never writes anything at all.
     */
    @Test
    fun `a successful write replaces the record and leaves no temporary file`() {
        writeState(JSONArray("""[{"name":"go"}]"""))

        assertEquals(listOf("go"), namesInState())
        assertFalse(
            File(filesDir, "home/.vscodroid/toolchains.json.tmp~").exists(),
            "the temporary file survived a successful write"
        )
    }

    /**
     * The pack-name form, which is the one JavaScript actually holds:
     * `getAvailableToolchains` hands out `packName`, so `removeToolchain`
     * receives `toolchain_ruby`.
     *
     * Before the resolution this logged "not found in state" and returned, so
     * the bridge's remove button did nothing at all while the same string passed
     * to `installToolchain` worked. Dropping [toolchainShortName] from
     * `uninstallSync` turns this red.
     */
    @Test
    fun `the pack name form resolves in the removal path`() {
        uninstallSync("toolchain_ruby")

        assertEquals(
            emptyList<String>(), namesInState(),
            "removeToolchain(\"toolchain_ruby\") left the entry in place"
        )
    }

    /**
     * The short form has to keep working, and this is not redundant with the
     * test above: a resolution written the wrong way round -- prepending the
     * prefix rather than resolving to the recorded form -- would satisfy that
     * one and break every caller that already passes a short name, which is
     * `ToolchainActivity` and the first-run queue.
     */
    @Test
    fun `the short name form still resolves in the removal path`() {
        uninstallSync("ruby")

        assertEquals(emptyList<String>(), namesInState(), "uninstall(\"ruby\") left the entry in place")
    }

    @Test
    fun `both name forms map to the recorded name`() {
        assertEquals("go", toolchainShortName("go"))
        assertEquals("go", toolchainShortName("toolchain_go"))
        assertEquals("java", toolchainShortName("toolchain_java"))
    }

    /**
     * A name the registry does not know comes back unchanged rather than being
     * reshaped. An entry can outlive its registry row -- a toolchain dropped in
     * a later release is still installed on the devices that have it -- and its
     * uninstall has to keep finding it, or those files can never be removed.
     */
    @Test
    fun `an unknown name is passed through untouched`() {
        assertEquals("rust", toolchainShortName("rust"))
        assertEquals("toolchain_rust", toolchainShortName("toolchain_rust"))
    }

    /**
     * The env file is generated from the record, so it has to be rewritten when
     * the record changes -- an uninstall that left it behind would keep the
     * removed toolchain on PATH in every new terminal.
     */
    @Test
    fun `removing the last toolchain removes the generated environment file`() {
        val envFile = File(filesDir, "home/.vscodroid/toolchain-env.sh")
        envFile.writeText("export RUBY_ROOT=/gone\n")

        uninstallSync("toolchain_ruby")

        assertFalse(envFile.exists(), "toolchain-env.sh outlived the toolchain it described")
    }

    /**
     * Uninstalling something that is not recorded must not touch what is. The
     * name resolution widened what reaches the lookup, so the miss path is worth
     * holding still.
     */
    @Test
    fun `an unrecorded toolchain removes nothing`() {
        uninstallSync("toolchain_go")

        assertEquals(listOf("ruby"), namesInState(), "an unrecorded name disturbed the record")
    }
}
