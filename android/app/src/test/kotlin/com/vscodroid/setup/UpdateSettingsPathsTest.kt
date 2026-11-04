package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * Tests for [FirstRunSetup.updateSettingsNativeLibPaths], the call site, not the
 * decision.
 *
 * [refreshManagedPaths] and [writeAtomically] are both covered on their own, and both
 * were green while the method that consults them could be changed to point the terminal
 * profile at the wrong file, or to truncate the user's settings on a failed write.
 * Nothing asserted that the right answer reached the file the workbench reads.
 *
 * So these run the real method against a real settings.json and read the file back.
 * `java.io.File` is the JVM's, so nothing here needs an Android runtime; the only
 * Android pieces are [Context] for its paths and [Logger] for `android.util.Log`.
 */
class UpdateSettingsPathsTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var settingsFile: File

    /** Where the APK lives now. Android moves this on every reinstall. */
    private val nativeLibDir = "/data/app/~~new==/com.vscodroid-new==/lib/arm64"

    /** Where it lived before, so the managed values in the fixture are stale. */
    private val oldNativeLibDir = "/data/app/~~old==/com.vscodroid-old==/lib/arm64"

    private fun settingsText(bashPath: String) = """
        {
            "editor.fontSize": 14,
            "terminal.integrated.profiles.linux": {
                "bash": {
                    "path": "$bashPath",
                    "args": ["-i"],
                    "icon": "terminal-bash"
                }
            },
            "git.path": "$oldNativeLibDir/libgit.so",
            "terminal.integrated.shellIntegration.enabled": false,
            "workbench.colorTheme": "Monokai"
        }
    """.trimIndent()

    private fun bashProfilePath(json: String): String =
        Regex(""""path"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: error("no profile path in settings.json:\n$json")

    @BeforeEach
    fun setUp() {
        mockkStatic(Files::class)
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = nativeLibDir
        }

        settingsFile = File(Environment.getMachineSettingsPath(context))
        settingsFile.parentFile?.mkdirs()
        settingsFile.writeText(settingsText("$oldNativeLibDir/libbash.so"))
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * The profile has to name the `usr/bin/bash` symlink, not the `.so` it points at:
     * the ptyHost decides whether to inject shell integration by switching on the
     * executable's basename, and `libbash.so` matches nothing. Asserting on the
     * basename rather than the whole string is deliberate: the directory is allowed
     * to move, the name is not.
     */
    @Test
    fun `the refreshed profile still names a file called bash`() {
        FirstRunSetup(context).updateSettingsNativeLibPaths()

        val path = bashProfilePath(settingsFile.readText())
        assertEquals("bash", File(path).name, "profile path was rewritten to $path")
        assertFalse(path.endsWith(".so"), "profile must not point at the .so: $path")
    }

    /**
     * The whole point of refreshing: the stale install directory must be gone.
     *
     * This also underwrites the failure test below. If the fixture were already
     * current, `refreshManagedPaths` would return null and the method would return
     * before writing anything, and a test asserting "the file was not modified"
     * would pass without the write ever being reached.
     */
    @Test
    fun `refreshing rewrites the paths that move on reinstall`() {
        val before = settingsFile.readText()

        FirstRunSetup(context).updateSettingsNativeLibPaths()

        val written = settingsFile.readText()
        assertNotEquals(before, written, "the fixture was already current: the write path was never reached")
        assertFalse(
            written.contains(oldNativeLibDir),
            "the previous install directory survived the refresh",
        )
        assertTrue(written.contains("$nativeLibDir/libgit.so"), "git.path was not refreshed")
        verify { Logger.i(any(), match { it.contains("Refreshed managed paths") }) }
    }

    /**
     * settings.json belongs to the user. When the write cannot be completed the file
     * must keep what it had, rather than being truncated and left empty or half
     * written: the workbench reads an empty settings.json as "no settings" rather
     * than as an error, so the loss is silent.
     *
     * The failure is injected at the temporary-file open in [writeAtomically],
     * preserving the real writer and its error handling.
     */
    @Test
    fun `a failed write leaves the user's settings exactly as they were`() {
        val before = settingsFile.readText()
        val blocker = blockAtomicWritesTo(settingsFile)

        FirstRunSetup(context).updateSettingsNativeLibPaths()

        assertEquals(before, settingsFile.readText(), "settings.json was modified despite the write failing")
        assertTrue(settingsFile.exists(), "settings.json disappeared")
        // The open must have been attempted rather than bypassed by an early return.
        assertTrue(blocker.attempts.get() > 0, "the atomic temporary open was not attempted")
        verify(exactly = 0) { Logger.i(any(), match { it.contains("Refreshed managed paths") }) }
    }

    /**
     * A refresh with nothing to change must not rewrite the file at all:
     * `refreshManagedPaths` returns null and the method returns before writing.
     * Asserting only on the contents would pass either way, since rewriting the same
     * text leaves the same bytes; the log line is what distinguishes them.
     */
    @Test
    fun `settings already pointing at the current install are not rewritten`() {
        FirstRunSetup(context).updateSettingsNativeLibPaths()
        val afterFirst = settingsFile.readText()
        clearMocks(Logger, answers = false)

        FirstRunSetup(context).updateSettingsNativeLibPaths()

        assertEquals(afterFirst, settingsFile.readText(), "a no-op refresh changed the file")
        verify(exactly = 0) { Logger.i(any(), match { it.contains("Refreshed managed paths") }) }
    }
}
