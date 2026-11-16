package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StatFs
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
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
import org.json.JSONObject
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
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = File(filesDir, "native").absolutePath
        }

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
            envFile.readText().contains("-rrubygems\" ;; esac"),
            "the record has the variable but the environment file does not: the backfill ran " +
                "after the derived files were written",
        )
    }
    /**
     * The backfill's value is a second copy of what the pack's script writes into the
     * manifest, and the two have to stay one: a script that moved to another option would
     * leave an install made before it with the old one, and nothing else would say so.
     */
    @Test
    fun `the value added to an old record is the one the Ruby pack's manifest carries`() {
        val script = File("../../scripts/download-ruby.sh")
        assertTrue(script.isFile, "${script.absolutePath} is missing; this would pass by reading nothing")

        val written = Regex(""""RUBYOPT":\s*"([^"]*)"""").find(script.readText())?.groupValues?.get(1)

        assertEquals(written, "-rrubygems")
        record("""[{"name":"ruby"}]""")
        backfill(ToolchainManager(context))
        assertEquals(written, envOf("ruby").getString("RUBYOPT"))
    }

    private val legacyGemPath = "${'$'}HOME/.gem/ruby:${'$'}FILESDIR/usr/lib/ruby/gems"

    private fun rubyWithGemPath(gemPath: String, rubyLib: String) {
        record(JSONArray().put(JSONObject().put("name", "ruby").put("env", JSONObject()
            .put("GEM_PATH", gemPath).put("RUBYLIB", rubyLib).put("RUBYOPT", "-rrubygems")))
            .toString())
    }

    private fun buildRubyManifest(assets: File, abi: String): Pair<Int, String> {
        val script = File("../../scripts/download-ruby.sh").readText()
        val start = script.indexOf("# RUBYOPT=-rrubygems:")
        assertTrue(start >= 0, "the manifest writer was not found")
        val end = script.indexOf("\nEOF", start)
        assertTrue(end > start, "the manifest writer has no end")
        val writer = script.substring(start, end + 4) + "\n"
        val process = ProcessBuilder("bash", "-c", writer).apply {
            environment().putAll(mapOf(
                "PACK_ASSETS" to assets.absolutePath, "RUBY_MINOR" to abi,
                "RUBY_VERSION" to abi, "BINARIES" to "[]", "SCRIPT_WRAPPERS" to "{}",
            ))
        }.redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test
    fun `the Ruby manifest searches bundled gems in its ABI directory`() {
        for (abi in listOf("3.4.0", "4.0.0")) {
            val assets = File(filesDir, "pack-$abi").apply { mkdirs() }
            File(assets, "usr/lib/ruby/gems/$abi").mkdirs()
            File(assets, "usr/lib/ruby/gems/README").writeText("not an ABI directory")
            val (exit, output) = buildRubyManifest(assets, abi)
            assertEquals(0, exit, output)
            val manifest = JSONObject(File(assets, "toolchain_ruby.json").readText())
            assertEquals("$legacyGemPath/$abi", manifest.getJSONObject("env").getString("GEM_PATH"))
        }
    }

    @Test
    fun `the Ruby manifest refuses missing or ambiguous bundled gem directories`() {
        for (abis in listOf(emptyList(), listOf("3.4.0", "4.0.0"))) {
            val assets = File(filesDir, "pack-${abis.size}").apply { mkdirs() }
            abis.forEach { File(assets, "usr/lib/ruby/gems/$it").mkdirs() }

            val (exit, output) = buildRubyManifest(assets, "4.0.0")

            assertTrue(exit != 0, "accepted ${abis.size} gem directories: $output")
            assertFalse(File(assets, "toolchain_ruby.json").exists())
        }
    }

    @Test
    fun `a legacy gem path follows the installed Ruby ABI and changes only once`() {
        for (abi in listOf("3.4.0", "4.0.0")) {
            rubyWithGemPath(legacyGemPath, "${'$'}FILESDIR/usr/lib/ruby/$abi:${'$'}FILESDIR/usr/lib/ruby/$abi/aarch64-linux-android")
            val manager = ToolchainManager(context)

            backfill(manager)

            assertEquals("$legacyGemPath/$abi", envOf("ruby").getString("GEM_PATH"))
            val once = stateFile().readText()
            backfill(manager)
            assertEquals(once, stateFile().readText())
        }
    }

    @Test
    fun `custom gem paths and records without a known ABI are unchanged`() {
        val cases = listOf(
            "${'$'}HOME/custom-gems" to "${'$'}FILESDIR/usr/lib/ruby/4.0.0",
            "$legacyGemPath/4.0.0" to "${'$'}FILESDIR/usr/lib/ruby/4.0.0",
            legacyGemPath to "",
            legacyGemPath to "${'$'}FILESDIR/usr/lib/ruby/../elsewhere",
            legacyGemPath to "/some/other/ruby/4.0.0",
        )
        for ((gemPath, rubyLib) in cases) {
            rubyWithGemPath(gemPath, rubyLib)
            val before = stateFile().readText()

            backfill(ToolchainManager(context))

            assertEquals(before, stateFile().readText(), "$gemPath with $rubyLib was rewritten")
        }
    }

    @Test
    fun `launch repair publishes the corrected gem path to every environment`() {
        rubyWithGemPath(legacyGemPath, "${'$'}FILESDIR/usr/lib/ruby/4.0.0:${'$'}FILESDIR/usr/lib/ruby/4.0.0/aarch64-linux-android")
        val manager = ToolchainManager(context)

        manager.repairInstalledToolchains()
        awaitLaunchPass(manager)

        val expected = "${filesDir.absolutePath}/home/.gem/ruby:${filesDir.absolutePath}/usr/lib/ruby/gems/4.0.0"
        assertEquals(expected, manager.getAllToolchainEnv()["GEM_PATH"])
        val derived = File(filesDir, "home/.vscodroid")
        assertTrue(File(derived, "toolchain-exec.tsv").readLines().contains("\tGEM_PATH\t$expected"))
        assertTrue(File(derived, "toolchain-env.sh").readLines().contains(
            "export GEM_PATH=\"${'$'}HOME/.gem/ruby:${'$'}PREFIX/../usr/lib/ruby/gems/4.0.0\"",
        ))
    }

    @Test
    fun `installing an older Ruby manifest publishes its corrected gem path without a launch pass`() {
        for (abi in listOf("3.4.0", "4.0.0")) {
            val assets = File(filesDir, "delivered-$abi").apply { mkdirs() }
            val payload = File(assets, "usr/lib/ruby/$abi/rubygems.rb")
            payload.parentFile.mkdirs()
            payload.writeText("payload")
            File(assets, "toolchain_ruby.json").writeText(JSONObject()
                .put("name", "ruby").put("installRoot", "usr/lib/ruby")
                .put("binaries", JSONArray()).put("env", JSONObject()
                    .put("GEM_PATH", legacyGemPath)
                    .put("RUBYLIB", "${'$'}FILESDIR/usr/lib/ruby/$abi"))
                .toString())
            val statuses = mutableListOf<Int>()
            val manager = ToolchainManager(context).apply {
                onStateChange = { _, status, _, _, _ -> statuses.add(status) }
            }

            val released = ToolchainManager::class.java
                .getDeclaredMethod("installFromDirectory", String::class.java, File::class.java)
                .apply { isAccessible = true }
                .invoke(manager, "toolchain_ruby", assets)

            assertEquals(true, released)
            assertEquals(listOf(AssetPackStatus.COMPLETED), statuses)
            assertEquals("payload", File(filesDir, "usr/lib/ruby/$abi/rubygems.rb").readText())
            val expected = "${filesDir.absolutePath}/home/.gem/ruby:${filesDir.absolutePath}/usr/lib/ruby/gems/$abi"
            val derived = File(filesDir, "home/.vscodroid")
            assertTrue(File(derived, "toolchain-exec.tsv").readLines().contains("\tGEM_PATH\t$expected"),
                "the install published the legacy gem path to the exec table")
            assertTrue(File(derived, "toolchain-env.sh").readLines().contains(
                "export GEM_PATH=\"${'$'}HOME/.gem/ruby:${'$'}PREFIX/../usr/lib/ruby/gems/$abi\"",
            ), "the install published the legacy gem path to the shell")
            assertEquals("$legacyGemPath/$abi", envOf("ruby").getString("GEM_PATH"))
            assertEquals("-rrubygems", envOf("ruby").getString("RUBYOPT"))
            assertEquals(expected, manager.getAllToolchainEnv()["GEM_PATH"])
        }
    }
}
