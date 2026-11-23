package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies that [holdingWriteLock] resolves symlink targets to match [writeAtomically]
 * and that read-modify-write paths on `Machine/settings.json` and dotfiles serialize
 * on that resolved lock.
 */
class WriteLockSymlinkSerializationTest {

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
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache").apply { mkdirs() }
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"
        }
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `holdingWriteLock on a symlink serializes against writeAtomically through that symlink`() {
        val real = File(filesDir, "dotfiles/settings.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"vscodroid.antigravity.runEngine": true}""")
        }
        val link = File(filesDir, "home/.vscodroid/data/Machine/settings.json").apply {
            parentFile!!.mkdirs()
        }
        Files.createSymbolicLink(link.toPath(), real.toPath())

        val enteredWhileHeld = AtomicBoolean(false)
        val writerStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        val writer = Thread {
            writerStarted.countDown()
            runCatching {
                writeAtomically(link, throughLink = true) { out ->
                    enteredWhileHeld.set(true)
                    out.write("""{"writer": "concurrent"}""".toByteArray())
                }
            }.onFailure { failure.set(it) }
        }

        holdingWriteLock(link) {
            writer.start()
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertFalse(
                enteredWhileHeld.get(),
                "writeAtomically through a symlink bypassed holdingWriteLock on the same symlink",
            )
            // Re-entrant write through the symlink must succeed and keep the link intact.
            assertTrue(
                writeAtomically(link, throughLink = true) { out ->
                    out.write("""{"vscodroid.antigravity.runEngine": false}""".toByteArray())
                },
            )
        }

        writer.join(5_000)
        assertFalse(writer.isAlive)
        assertNull(failure.get())
        assertTrue(Files.isSymbolicLink(link.toPath()), "symlink must remain a symlink")
    }

    @Test
    fun `holdingWriteLock on a symlink and on its direct target share the same lock`() {
        val real = File(filesDir, "dotfiles/settings.json").apply {
            parentFile!!.mkdirs()
            writeText("{}")
        }
        val link = File(filesDir, "home/.vscodroid/data/Machine/settings.json").apply {
            parentFile!!.mkdirs()
        }
        Files.createSymbolicLink(link.toPath(), real.toPath())

        val secondEntered = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val second = Thread {
            started.countDown()
            holdingWriteLock(real) {
                secondEntered.set(true)
            }
        }

        holdingWriteLock(link) {
            second.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertFalse(
                secondEntered.get(),
                "holdingWriteLock on a symlink and on its resolved target must serialize",
            )
        }

        second.join(5_000)
        assertFalse(second.isAlive)
        assertTrue(secondEntered.get())
    }

    @Test
    fun `repairTruncatedSetupFiles serializes on symlinked settings and does not overwrite concurrent creation`() {
        val real = File(filesDir, "dotfiles/settings.json").apply {
            parentFile!!.mkdirs()
            writeText("")
        }
        val settings = File(Environment.getMachineSettingsPath(context)).apply {
            parentFile!!.mkdirs()
        }
        Files.createSymbolicLink(settings.toPath(), real.toPath())

        val userSettings = """{"workbench.colorTheme": "Solarized"}"""
        val repairStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val repairThread = Thread {
            repairStarted.countDown()
            runCatching { FirstRunSetup(context).repairTruncatedSetupFiles() }.onFailure { failure.set(it) }
        }

        holdingWriteLock(settings) {
            repairThread.start()
            assertTrue(repairStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertEquals(
                "",
                real.readText(),
                "repairTruncatedSetupFiles modified symlinked settings while holdingWriteLock was held",
            )
            assertTrue(
                writeAtomically(settings, throughLink = true) { it.write(userSettings.toByteArray()) },
            )
        }

        repairThread.join(5_000)
        assertFalse(repairThread.isAlive)
        assertNull(failure.get())
        assertTrue(Files.isSymbolicLink(settings.toPath()), "settings symlink must stay a symlink")
        assertEquals(
            userSettings,
            real.readText(),
            "repairTruncatedSetupFiles raced ahead of holdingWriteLock and overwrote populated settings",
        )
    }

    @Test
    fun `createDefaultSettings and migrateSettingsToMachinePath serialize under holdingWriteLock`() {
        val real = File(filesDir, "dotfiles/settings.json").apply { parentFile!!.mkdirs() }
        val settings = File(Environment.getMachineSettingsPath(context)).apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(settings.toPath(), real.toPath())

        val legacy = File(filesDir, "home/.vscodroid/User/settings.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"workbench.colorTheme": "Monokai"}""")
        }

        val createStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val createThread = Thread {
            createStarted.countDown()
            runCatching {
                FirstRunSetup::class.java.getDeclaredMethod("createDefaultSettings").apply {
                    isAccessible = true
                    invoke(FirstRunSetup(context))
                }
            }.onFailure { failure.set(it) }
        }

        // Hold the resolved lock while migrating legacy settings into the dangling symlink target.
        holdingWriteLock(settings) {
            createThread.start()
            assertTrue(createStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(150)
            FirstRunSetup::class.java.getDeclaredMethod("migrateSettingsToMachinePath").apply {
                isAccessible = true
                invoke(FirstRunSetup(context))
            }
        }

        createThread.join(5_000)
        assertFalse(createThread.isAlive)
        assertNull(failure.get())
        assertFalse(legacy.exists(), "legacy settings should have been migrated")
        assertTrue(Files.isSymbolicLink(settings.toPath()), "settings symlink must stay a symlink")
        assertTrue(
            real.readText().contains("Monokai"),
            "createDefaultSettings bypassed holdingWriteLock and overwrote migrated settings: ${real.readText()}",
        )
    }

    @Test
    fun `dotfile appenders serialize on symlinked bashrc under holdingWriteLock`() {
        val real = File(filesDir, "dotfiles/.bashrc").apply {
            parentFile!!.mkdirs()
            writeText("# VSCodroid bash configuration\nexport PROJECTS_DIR='/p'\n")
        }
        val bashrc = File(filesDir, "home/.bashrc").apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(bashrc.toPath(), real.toPath())

        val appenderStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val appender = Thread {
            appenderStarted.countDown()
            runCatching { FirstRunSetup(context).ensureToolchainEnvSourcing() }.onFailure { failure.set(it) }
        }

        holdingWriteLock(bashrc) {
            appender.start()
            assertTrue(appenderStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertFalse(
                real.readText().contains("toolchain-env.sh"),
                "ensureToolchainEnvSourcing modified symlinked bashrc while holdingWriteLock was held",
            )
            val updated = real.readText() + "\n# user custom line\n# toolchain-env.sh already sourced\n"
            assertTrue(writeAtomically(bashrc, throughLink = true) { it.write(updated.toByteArray()) })
        }

        appender.join(5_000)
        assertFalse(appender.isAlive)
        assertNull(failure.get())
        assertTrue(Files.isSymbolicLink(bashrc.toPath()), "bashrc symlink must stay a symlink")
        assertTrue(
            real.readText().contains("# user custom line"),
            "ensureToolchainEnvSourcing bypassed holdingWriteLock and clobbered concurrent edit: ${real.readText()}",
        )
    }

    @Test
    fun `missing parent directories created via mkdirs under a symlinked ancestor share one lock`() {
        val realRoot = File(filesDir, "real-root").apply { mkdirs() }
        val aliasRoot = File(filesDir, "alias-root")
        Files.createSymbolicLink(aliasRoot.toPath(), realRoot.toPath())

        val viaAlias = File(aliasRoot, "missing/nested/settings.json")
        val viaReal = File(realRoot, "missing/nested/settings.json")

        val concurrentEntered = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        val second = Thread {
            started.countDown()
            runCatching {
                writeAtomically(viaReal, throughLink = true) { out ->
                    concurrentEntered.set(true)
                    out.write("""{"from": "second"}""".toByteArray())
                }
            }.onFailure { failure.set(it) }
        }

        holdingWriteLock(viaAlias) {
            assertTrue(viaAlias.parentFile!!.mkdirs())
            second.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertFalse(
                concurrentEntered.get(),
                "writeAtomically bypassed holdingWriteLock after missing ancestors were created under a symlinked root",
            )
            assertTrue(writeAtomically(viaAlias, throughLink = true) { it.write("""{"from": "first"}""".toByteArray()) })
        }

        second.join(5_000)
        assertFalse(second.isAlive)
        assertNull(failure.get())
        assertTrue(concurrentEntered.get())
        assertEquals("""{"from": "second"}""", viaReal.readText())
    }

    @Test
    fun `two-hop symlink chain shares the same lock across every hop and target`() {
        val target = File(filesDir, "dotfiles/settings.json").apply {
            parentFile!!.mkdirs()
            writeText("{}")
        }
        val hop2 = File(filesDir, "links/hop2").apply { parentFile!!.mkdirs() }
        val hop1 = File(filesDir, "home/settings.json").apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(hop2.toPath(), target.toPath())
        Files.createSymbolicLink(hop1.toPath(), hop2.toPath())

        for ((label, contender) in listOf("hop2" to hop2, "target" to target)) {
            val enteredWhileHeld = AtomicBoolean(false)
            val started = CountDownLatch(1)
            val thread = Thread {
                started.countDown()
                holdingWriteLock(contender) {
                    enteredWhileHeld.set(true)
                }
            }
            holdingWriteLock(hop1) {
                thread.start()
                assertTrue(started.await(5, TimeUnit.SECONDS))
                Thread.sleep(120)
                assertFalse(
                    enteredWhileHeld.get(),
                    "holdingWriteLock on 2-hop symlink hop1 did not serialize with $label",
                )
            }
            thread.join(5_000)
            assertFalse(thread.isAlive)
            assertTrue(enteredWhileHeld.get())
        }
    }

    @Test
    fun `dangling symlinks under a symlinked ancestor share locks with direct target paths`() {
        val realRoot = File(filesDir, "real-dangling").apply { mkdirs() }
        val aliasRoot = File(filesDir, "alias-dangling")
        Files.createSymbolicLink(aliasRoot.toPath(), realRoot.toPath())

        // Case 1: target parent exists, leaf missing.
        val existingParentTarget = File(realRoot, "dotfiles/settings.json").apply { parentFile!!.mkdirs() }
        val linkExistingParent = File(aliasRoot, "home/settings.json").apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(linkExistingParent.toPath(), java.nio.file.Paths.get("../dotfiles/settings.json"))

        // Case 2: target parent is also missing (multiple missing segments).
        val missingParentTarget = File(realRoot, "gone/sub/settings.json")
        val linkMissingParent = File(aliasRoot, "home/missing.json").apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(linkMissingParent.toPath(), java.nio.file.Paths.get("../gone/sub/settings.json"))

        for ((label, pair) in listOf(
            "existing target parent" to (linkExistingParent to existingParentTarget),
            "missing target parent" to (linkMissingParent to missingParentTarget),
        )) {
            val (link, direct) = pair
            val enteredWhileHeld = AtomicBoolean(false)
            val started = CountDownLatch(1)
            val contender = Thread {
                started.countDown()
                holdingWriteLock(direct) {
                    enteredWhileHeld.set(true)
                }
            }
            holdingWriteLock(link) {
                contender.start()
                assertTrue(started.await(5, TimeUnit.SECONDS))
                Thread.sleep(120)
                assertFalse(
                    enteredWhileHeld.get(),
                    "dangling symlink ($label) did not serialize with its resolved target under symlinked ancestor",
                )
            }
            contender.join(5_000)
            assertFalse(contender.isAlive)
            assertTrue(enteredWhileHeld.get())
        }
    }
}
