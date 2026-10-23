package com.vscodroid.setup

import android.content.Context
import android.os.StatFs
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Ruby's `RUBYOPT=-rrubygems`, for an install made before the pack's manifest carried it.
 *
 * The record keeps the manifest it was installed with, so a variable added to
 * `scripts/download-ruby.sh` reaches new installs only. Without the backfill a Ruby
 * installed by an earlier build keeps an interpreter that starts without RubyGems, and a
 * script's `require` of any installed gem fails.
 */
class ToolchainEnvBackfillTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

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
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns 0L

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun stateFile() = File(filesDir, "home/.vscodroid/toolchains.json")

    private fun record(body: String) = stateFile().writeText(body)

    private fun envOf(name: String) = JSONArray(stateFile().readText()).let { state ->
        (0 until state.length()).map { state.getJSONObject(it) }.first { it.getString("name") == name }
            .getJSONObject("env")
    }

    /** Private; the launch pass calls it, and only `repairInstalledToolchains` does. */
    private fun backfill(m: ToolchainManager) =
        ToolchainManager::class.java
            .getDeclaredMethod("backfillToolchainEnvSync")
            .apply { isAccessible = true }
            .invoke(m)

    private val rubyBefore = """
        [{"name":"ruby","installRoot":"usr/lib/ruby","binaries":[],
          "env":{"GEM_HOME":"${'$'}HOME/.gem/ruby"}}]
    """.trimIndent()

    @Test
    fun `a Ruby installed before the variable existed gets it and keeps its own`() {
        record(rubyBefore)

        backfill(ToolchainManager(context))

        val env = envOf("ruby")
        assertEquals("-rrubygems", env.getString("RUBYOPT"))
        assertEquals("\$HOME/.gem/ruby", env.getString("GEM_HOME"), "an existing variable was lost")
    }

    @Test
    fun `a value the record already holds is not overwritten`() {
        record(
            """[{"name":"ruby","env":{"RUBYOPT":"-W0 -rrubygems"}}]"""
        )

        backfill(ToolchainManager(context))

        assertEquals("-W0 -rrubygems", envOf("ruby").getString("RUBYOPT"))
    }

    @Test
    fun `another toolchain is left as it was and nothing is written for it`() {
        val java = """[{"name":"java","env":{"JAVA_HOME":"x"}}]"""
        record(java)
        val before = stateFile().readText()

        backfill(ToolchainManager(context))

        assertEquals(before, stateFile().readText(), "a toolchain with nothing to add was rewritten")
        assertFalse(stateFile().readText().contains("RUBYOPT"))
    }

    @Test
    fun `a record with no environment object gets one, and a second pass changes nothing`() {
        record("""[{"name":"ruby","installRoot":"usr/lib/ruby"}]""")
        val manager = ToolchainManager(context)

        backfill(manager)
        val once = stateFile().readText()
        backfill(manager)

        assertEquals("-rrubygems", envOf("ruby").getString("RUBYOPT"))
        assertEquals(once, stateFile().readText(), "a record that already has it was rewritten")
    }

    @Test
    fun `a record that is not readable is left as it is`() {
        record("""not json at all""")

        backfill(ToolchainManager(context))

        assertEquals("""not json at all""", stateFile().readText(), "a damaged record was overwritten")
    }

    @Test
    fun `the launch pass writes it into the environment file of that launch`() {
        record(rubyBefore)
        val manager = ToolchainManager(context)

        manager.repairInstalledToolchains()
        awaitLaunchPass(manager)

        val envFile = File(filesDir, "home/.vscodroid/toolchain-env.sh")
        assertTrue(envFile.exists(), "the launch pass wrote no environment file")
        assertTrue(
            envFile.readText().contains("export RUBYOPT=\"-rrubygems\""),
            "the record has the variable but the environment file does not: the backfill ran " +
                "after the derived files were written",
        )
    }
}
