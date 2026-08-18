package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * Tests that no half-written `.bashrc` can survive, and -- the sharper half --
 * that none can certify itself as finished.
 *
 * Every writer on this file is guarded by something the file itself contains:
 * the prompt rewrite returns early once the current version marker is present,
 * the npm block once `npm()` is, the toolchain line once the filename is. Each
 * of those strings sits near the *start* of what its writer emits, and the
 * writes truncated in place. So a write cut short by process death left a file
 * that satisfied its own guard while missing everything after the cut -- an
 * unbalanced function body, no `PROJECTS_DIR`, no `cd` into the workspace --
 * and no later launch would touch it again. The terminal opens on a syntax
 * error, permanently, and the marker says the work was done.
 *
 * Ordering the marker last would narrow the window; writing through a temporary
 * file closes it, because nothing appears under the real name until all of it
 * has. These pin the second, so the ordering inside the block stays free.
 *
 * The failure is arranged as in [UpdateSettingsPathsTest]: by occupying the
 * temporary path [writeAtomically] derives from the destination.
 */
class BashrcAtomicityTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var bashrc: File

    /** The v1.0.0 prompt shape: no versioned markers, `PS1` left empty. */
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

    /** The marker the prompt rewrite writes, and the guard it reads back. */
    private val currentMarker = ">>> vscodroid prompt"

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"
        }
        // createBashrc interpolates this into PROJECTS_DIR; pinned so the file
        // it writes does not depend on what a relaxed mock invents.
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        bashrc = File(filesDir, "home/.bashrc")
        bashrc.parentFile?.mkdirs()
        bashrc.writeText(legacyBashrc)
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    /** Non-empty, so the cleanup `delete()` cannot quietly reclaim it. */
    private fun blockTheWrite(): File =
        File(bashrc.parentFile, "${bashrc.name}.tmp~").also {
            assertTrue(it.mkdirs(), "could not stage the blocked temp path")
            File(it, "occupied").writeText("x")
        }

    /** What [FirstRunSetup.createNpmWrappers] needs on disk before it will do anything. */
    private fun bundleNpm() {
        val cli = File(filesDir, "usr/lib/node_modules/npm/bin/npm-cli.js")
        cli.parentFile?.mkdirs()
        cli.writeText("// npm")
    }

    @Test
    fun `rewrites the legacy prompt and keeps everything after it`() {
        // The control. Every failure assertion below would also hold for a
        // method that had stopped doing anything at all.
        FirstRunSetup(context).ensurePromptFix()

        val written = bashrc.readText()
        assertTrue(written.contains(currentMarker), "the prompt block was not brought up to date")
        assertTrue(written.contains("export PROJECTS_DIR"), "the rewrite ate what followed the prompt")
        assertTrue(written.contains("alias ll="), "the rewrite ate what followed the prompt")
        assertFalse(written.contains("PS1=''"), "the legacy empty prompt survived the rewrite")
    }

    @Test
    fun `a failed prompt rewrite leaves neither a partial file nor its marker`() {
        blockTheWrite()

        FirstRunSetup(context).ensurePromptFix()

        assertEquals(
            legacyBashrc,
            bashrc.readText(),
            "the shell configuration was modified by a write that could not be completed",
        )
        assertFalse(
            bashrc.readText().contains(currentMarker),
            "the marker was left behind by an incomplete write, so every later launch " +
                "reads it as done and the file is never repaired",
        )
    }

    @Test
    fun `the prompt rewrite completes on a later launch once the write can succeed`() {
        val blocker = blockTheWrite()
        FirstRunSetup(context).ensurePromptFix()

        blocker.deleteRecursively()
        FirstRunSetup(context).ensurePromptFix()

        assertTrue(bashrc.readText().contains(currentMarker), "the rewrite never recovered")
        assertTrue(bashrc.readText().contains("export PROJECTS_DIR"), "the retry ate what followed the prompt")
    }

    @Test
    fun `appends the toolchain sourcing line`() {
        FirstRunSetup(context).ensureToolchainEnvSourcing()

        assertTrue(bashrc.readText().contains("toolchain-env.sh"), "the sourcing line was not appended")
        assertTrue(bashrc.readText().contains("alias ll="), "the append lost what was already there")
    }

    @Test
    fun `a failed toolchain sourcing append leaves the file as it was`() {
        blockTheWrite()

        FirstRunSetup(context).ensureToolchainEnvSourcing()

        assertEquals(
            legacyBashrc,
            bashrc.readText(),
            "a partial append survived; `toolchain-env.sh` near its start is this writer's " +
                "own guard, so the half-written line would never be completed",
        )
    }

    /**
     * An append must not rewrite what it appends to.
     *
     * These two writers used `appendText`, which never read the file. Routing
     * them through an atomic write turned each into a read-modify-write, and
     * `File.readText` decodes UTF-8 with CodingErrorAction.REPLACE -- so any
     * byte in the user's `.bashrc` that is not valid UTF-8 comes back as U+FFFD
     * and is written out as EF BF BD. A single-byte Latin-1 accent in a comment
     * or an alias, which a terminal editor will happily put there, is silently
     * replaced by adding an unrelated line to the end of the file.
     *
     * Atomicity was worth having; this was not part of the trade. The bytes are
     * carried through untouched now and only the new block is encoded.
     */
    @Test
    fun `an append leaves bytes the file already held exactly as they were`() {
        // 0xE9 is Latin-1 'e' with an acute accent and is not valid UTF-8 on
        // its own. Spelled as a byte rather than as a source literal, so the
        // test does not depend on how this file is encoded.
        val original = "# caf".toByteArray() +
            byteArrayOf(0xE9.toByte()) +
            " -- written by hand\nalias l='ls'\n".toByteArray()
        bashrc.writeBytes(original)

        FirstRunSetup(context).ensureToolchainEnvSourcing()

        val after = bashrc.readBytes()
        assertTrue(after.size > original.size, "nothing was appended; the harness is wrong")
        assertArrayEquals(
            original,
            after.copyOf(original.size),
            "the existing bytes were re-encoded by the append; a byte that is not valid " +
                "UTF-8 came back as U+FFFD",
        )
        assertTrue(String(after).contains("toolchain-env.sh"), "the sourcing line was not appended")
    }

    /**
     * The third writer on this file, and the one that runs most often.
     *
     * The two appends carry the user's bytes through untouched; this one
     * rewrites the whole file, and it fires from SplashActivity on every launch
     * whenever the current marker is absent -- which is every device's first
     * launch after a PROMPT_VERSION bump. Decoding as UTF-8 to do the surgery
     * would replace any byte that is not valid UTF-8, so the same Latin-1 accent
     * the append preserves would be destroyed by the rewrite instead.
     */
    @Test
    fun `the prompt rewrite leaves bytes it does not own exactly as they were`() {
        val tail = "# caf".toByteArray() +
            byteArrayOf(0xE9.toByte()) +
            " -- written by hand\nalias l='ls'\n".toByteArray()
        bashrc.writeBytes(legacyBashrc.toByteArray() + tail)

        FirstRunSetup(context).ensurePromptFix()

        // Latin-1 makes a byte-subsequence search into a string search, since
        // every byte maps to exactly one character.
        val after = String(bashrc.readBytes(), Charsets.ISO_8859_1)
        assertTrue(after.contains(currentMarker), "the prompt block was not brought up to date")
        assertTrue(
            after.contains(String(tail, Charsets.ISO_8859_1)),
            "the bytes after the prompt block were re-encoded by the rewrite",
        )
        // EF BF BD is U+FFFD encoded as UTF-8, which is what a lossy decode
        // leaves behind. Spelled as bytes so this file stays ASCII.
        val replacementChar = String(
            byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte()),
            Charsets.ISO_8859_1,
        )
        assertFalse(
            after.contains(replacementChar),
            "a byte was replaced with U+FFFD, so the rewrite decoded what it should have copied",
        )
    }

    @Test
    fun `appends the npm and claude functions`() {
        bundleNpm()

        FirstRunSetup(context).createNpmWrappers()

        val written = bashrc.readText()
        assertTrue(written.contains("npm()"), "the npm function was not appended")
        assertTrue(written.contains("npx()"), "the npx function was not appended")
        assertTrue(written.contains("claude()"), "the claude function was not appended")
        assertTrue(written.contains("alias ll="), "the append lost what was already there")
    }

    @Test
    fun `a failed npm wrapper append leaves the file as it was`() {
        bundleNpm()
        blockTheWrite()

        FirstRunSetup(context).createNpmWrappers()

        assertEquals(
            legacyBashrc,
            bashrc.readText(),
            "a partial append survived; `npm()` opens this writer's own block, so a body " +
                "cut short by process death would satisfy the guard and never be repaired",
        )
    }

    /**
     * `.npmrc` is written by the same call and matters for a different reason.
     *
     * It carries `script-shell`, which points npm at the bundled bash. Without
     * that line npm falls back to `/bin/sh`. That path exists on Android, as a
     * symlink into `/system/bin`, so the failure is not ENOENT: it is mksh
     * running a script written for bash, which dies on `[[`, arrays or `source`.
     * Nothing on screen connects that to storage.
     *
     * The file also sits outside every repair: `repairTruncatedSetupFiles` covers
     * `.bashrc` and `settings.json`, and an emptied `.npmrc` cannot be told from
     * one a user emptied deliberately. The rewrite here only runs when the content
     * differs, which an empty file does, but only on a launch with room to write,
     * which is not the launch that emptied it.
     */
    @Test
    fun `a failed npmrc write leaves the previous file rather than emptying it`() {
        bundleNpm()
        val npmrc = File(filesDir, "home/.npmrc").apply { writeText("script-shell=/old/bash\n") }
        blockTheNpmrcWrite()

        FirstRunSetup(context).createNpmWrappers()

        assertEquals(
            "script-shell=/old/bash\n",
            npmrc.readText(),
            "the previous .npmrc was destroyed by a write that could not finish; an empty " +
                "one means no script-shell, and npm then hands lifecycle scripts to " +
                "/bin/sh, which on Android is mksh rather than the bash they assume",
        )
    }

    @Test
    fun `the npmrc write is what the failure case is withholding`() {
        // Control. Without it, a createNpmWrappers that stopped writing .npmrc at
        // all would satisfy the case above.
        bundleNpm()
        val npmrc = File(filesDir, "home/.npmrc").apply { writeText("script-shell=/old/bash\n") }

        FirstRunSetup(context).createNpmWrappers()

        assertTrue(
            npmrc.readText().contains("libbash.so"),
            "the unblocked write did not point script-shell at the bundled bash: " +
                npmrc.readText(),
        )
    }

    /** Blocks only `.npmrc`, so the `.bashrc` append ahead of it still succeeds. */
    private fun blockTheNpmrcWrite(): File =
        File(filesDir, "home/.npmrc.tmp~").also {
            assertTrue(it.mkdirs(), "could not stage the blocked temp path")
            File(it, "occupied").writeText("x")
        }

    /**
     * The repair has to REWRITE, not just clear.
     *
     * Both writers of these files live in `runSetupLocked`, which an install
     * that is already marked complete never re-enters, and every per-launch
     * repair opens with `if (bashrc.exists())`. So deleting a truncated file
     * without replacing it turns a bad `.bashrc` into no `.bashrc` -- worse, and
     * with nothing that would ever put it back. This is the assertion that
     * catches that, and it caught it: the first version of the repair only
     * deleted.
     */
    @Test
    fun `an emptied bashrc is cleared and written again, not just cleared`() {
        bashrc.writeText("")

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(bashrc.isFile, "the repair removed the file and left nothing in its place")
        val written = bashrc.readText()
        assertTrue(written.contains("export PROJECTS_DIR"), "the rewritten file is not the one we write")
        assertTrue(written.contains(currentMarker), "the rewritten file has no prompt block")
    }

    @Test
    fun `a bashrc cut off after our header is cleared and written again`() {
        // The other shape an interrupted write leaves: our first line, and then
        // nothing that should have followed it.
        bashrc.writeText("# VSCodroid bash configuration\n# >>> vscodroid prompt")

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(bashrc.readText().contains("export PROJECTS_DIR"), "the truncated file was kept")
    }

    /**
     * The line this repair must not cross.
     *
     * A file the user wrote themselves is theirs, and a partial file that got
     * far enough to look plausible cannot be told from one they shortened. Both
     * are left alone, and that is the deliberate limit of the repair rather
     * than an oversight -- clearing them would destroy real work to fix a state
     * we would only be guessing at.
     */
    @Test
    fun `a bashrc the user wrote is never touched`() {
        val theirs = "# my own shell setup\nalias g='git'\n"
        bashrc.writeText(theirs)

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertEquals(theirs, bashrc.readText(), "a file the user wrote was cleared")
    }

    @Test
    fun `a plausible-looking partial file is left alone and not guessed at`() {
        // Has our header AND the export, so nothing here can tell it from a
        // complete file that the user later trimmed. Left as it is, on purpose.
        val partial = "# VSCodroid bash configuration\nexport PROJECTS_DIR='/data/projects'\n"
        bashrc.writeText(partial)

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertEquals(partial, bashrc.readText(), "a file the repair cannot classify was rewritten anyway")
    }

    @Test
    fun `a healthy bashrc is left exactly as it is`() {
        // The control. Without it every assertion above would also hold for a
        // repair that had stopped doing anything.
        val before = bashrc.readText()

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertEquals(before, bashrc.readText(), "the repair rewrote a file that was fine")
    }

    private fun createBashrc() {
        FirstRunSetup::class.java
            .getDeclaredMethod("createBashrc")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    @Test
    fun `writes the shell configuration on a first run`() {
        bashrc.delete()

        createBashrc()

        assertTrue(bashrc.isFile, "no .bashrc was written")
        assertTrue(bashrc.readText().contains("export PROJECTS_DIR"), "the file is not the one we write")
    }

    /**
     * The first-run write is not like the three above it, and the difference is
     * which loop gets to try again.
     *
     * Those three run from SplashActivity on every launch, so a failure heals by
     * itself next time. This one runs only inside `runSetupLocked`, whose
     * `markSetupComplete()` sits at the end of the same try block, and
     * `isFirstRun()` is keyed on versionName -- so a failure that merely logs
     * lets setup be certified with no .bashrc at all, and nothing writes one
     * until the app updates. The every-launch repairs cannot fill the gap
     * either: `createNpmWrappers`, `ensureToolchainEnvSourcing` and
     * `ensurePromptFix` all open with `if (bashrc.exists())`, so with the file
     * absent all three no-op forever too.
     *
     * Failing loudly reaches `runSetupLocked`'s catch, which SplashActivity
     * turns into an error screen with a Retry button. Atomicity is what makes
     * that retry worth pressing: the file is absent rather than truncated, so
     * the retry's own `if (!bashrc.exists())` is true and it writes a complete
     * one. On main the truncated file satisfied that guard and the retry skipped
     * it, so neither half alone produces a working shell.
     */
    @Test
    fun `a failed first-run write leaves nothing behind and fails loudly`() {
        bashrc.delete()
        blockTheWrite()

        val thrown = assertThrows(InvocationTargetException::class.java) { createBashrc() }

        assertTrue(
            thrown.cause is IOException,
            "the failure must reach runSetupLocked's catch so markSetupComplete is skipped; " +
                "it surfaced as ${thrown.cause}",
        )
        assertFalse(
            bashrc.exists(),
            "a truncated .bashrc was left behind; the retry's own exists() guard would skip it",
        )
    }
}
