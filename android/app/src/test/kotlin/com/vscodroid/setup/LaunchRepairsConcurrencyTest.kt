package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vscodroid.oneLaunchRepairAtATime
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Two launches at once over one install, on real files: the `.bashrc` writers among the launch repairs each read the
 * file, decide, and write it whole, so two different ones running at once can drop each other's block. Through
 * [oneLaunchRepairAtATime], as SplashActivity runs them, two launches leave the file as one launch does.
 */
class LaunchRepairsConcurrencyTest {

    @TempDir
    lateinit var root: File

    private val legacyBashrc = """
        # VSCodroid bash configuration
        # Prompt via PROMPT_COMMAND
        __vscodroid_prompt() {
            echo -n "${'$'}PWD ${'$'} "
        }
        PROMPT_COMMAND=__vscodroid_prompt
        PS1=''
        export PROJECTS_DIR='/data/projects'
        alias ll='ls -la'
    """.trimIndent() + "\n"

    @BeforeEach
    fun quiet() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** An install whose `.bashrc` predates three of the writers, with npm extracted so its writer has work too. */
    private fun install(name: String): Context {
        val filesDir = File(root, name)
        File(filesDir, "home/.bashrc").apply { parentFile?.mkdirs() }.writeText(legacyBashrc)
        File(filesDir, "usr/lib/node_modules/npm/bin/npm-cli.js").apply { parentFile?.mkdirs() }.writeText("// npm")
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"
        }
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        return context
    }

    /** The `.bashrc` writers of one launch, in the order SplashActivity runs them. */
    private fun bashrcRepairs(context: Context) {
        val setup = FirstRunSetup(context)
        setup.repairTruncatedSetupFiles()
        setup.createNpmWrappers()
        setup.ensureToolchainEnvSourcing()
        setup.ensurePromptFix()
        setup.ensureStartupDirGuard()
    }

    private fun bashrcOf(context: Context) = File(context.filesDir, "home/.bashrc").readText()

    @Test
    fun `two launches at once leave the bashrc as one launch does`() {
        val one = install("one")
        bashrcRepairs(one)
        val expected = bashrcOf(one)
        check(expected != legacyBashrc) { "the writers had nothing to do, so this case measures nothing" }

        repeat(20) { round ->
            val both = install("both-$round")
            val go = CountDownLatch(1)
            val launches = Executors.newFixedThreadPool(2)
            try {
                val done = (1..2).map {
                    launches.submit {
                        go.await()
                        oneLaunchRepairAtATime { bashrcRepairs(both) }
                    }
                }
                go.countDown()
                done.forEach { it.get(10, TimeUnit.SECONDS) }
            } finally {
                launches.shutdownNow()
            }
            assertEquals(expected, bashrcOf(both), "round $round: two launches at once left a different .bashrc")
        }
    }
}
