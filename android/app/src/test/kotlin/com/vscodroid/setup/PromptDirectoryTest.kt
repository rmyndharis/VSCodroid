package com.vscodroid.setup

import android.content.Context
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What the terminal prompt shows inside a device folder's copy.
 *
 * A copy lives at `saf-mirrors/<hash>`, and the prompt shows it as `[saf]`, because a
 * twelve-character digest in front of every command says nothing. A folder opened by
 * its name is the link `saf-mirrors/by-name/<hash>/<name>` to that copy, and a terminal
 * there keeps the link's spelling: node-pty exports `PWD` as the directory it starts
 * the shell in (`unixTerminal.js`), and bash keeps an inherited `PWD` that names its
 * working directory. The v2 prompt stripped only through `saf-mirrors/`, so there it
 * printed `[saf]/<hash>/<name>`, the hash the named path exists to hide.
 *
 * Real bash against the real generated file, started the way node-pty starts it,
 * because which spelling bash settles on is the whole question.
 */
class PromptDirectoryTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var externalDir: File

    private lateinit var context: Context
    private lateinit var copy: File
    private lateinit var named: File
    private val home: File get() = File(filesDir, "home")
    private val bashrc: File get() = File(home, ".bashrc")
    private val projects: File get() = File(externalDir, "projects")

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationContext } returns context
        // Named for the reason StartupDirGuardTest gives: PROJECTS_DIR is spelled
        // from it, and a relaxed mock would make that `/projects`.
        every { context.getExternalFilesDir(null) } returns externalDir

        home.mkdirs()
        projects.mkdirs()
        copy = File(filesDir, "saf-mirrors/8e440ff38c8e").apply { File(this, "src").mkdirs() }
        // The link the app makes, so that the case follows its layout.
        named = requireNotNull(SafStorageManager(context).namedPathFor(copy, "recipes")) {
            "the named path could not be made"
        }
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun createBashrc() {
        FirstRunSetup::class.java
            .getDeclaredMethod("createBashrc")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    /**
     * The prompt's directory in a shell started in [dir] as node-pty starts one: in that
     * directory, with `PWD` set to it as the terminal was asked for it.
     *
     * `BASH_ENV`, `SSH_CLIENT` and `SSH2_CLIENT` are removed for the reason
     * `NonInteractiveShellEnvTest` documents.
     */
    private fun promptIn(dir: File): String {
        assumeTrue(File("/bin/bash").canExecute(), "no /bin/bash on this host")
        val builder = ProcessBuilder(
            "/bin/bash", "-c",
            ". \"\$HOME/.bashrc\"; __vscodroid_prompt; printf '%s\\n' \"\$__vscodroid_dir\"",
        ).directory(dir).redirectErrorStream(true)
        builder.environment().apply {
            remove("BASH_ENV")
            remove("SSH_CLIENT")
            remove("SSH2_CLIENT")
            put("HOME", home.path)
            put("PWD", dir.path)
        }
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bash did not finish")
        val lines = out.lines().filter { it.isNotEmpty() }
        assertEquals(1, lines.size, "the shell printed something besides the prompt: $lines")
        return lines.single()
    }

    /** The control: without it a harness that never reached the copy would pass below. */
    @Test
    fun `the copy's own path shows as the device folder`() {
        createBashrc()

        assertEquals("[saf]", promptIn(copy))
        assertEquals("[saf]/src", promptIn(File(copy, "src")))
    }

    @Test
    fun `a folder opened by its name shows as the device folder too`() {
        createBashrc()

        assertEquals(
            "[saf]", promptIn(named),
            "a terminal in a folder opened by its name showed the copy's hash in its prompt",
        )
        assertEquals("[saf]/src", promptIn(File(named, "src")))
    }

    /**
     * A device that has the app already keeps its `.bashrc`, so the change reaches it
     * only through [FirstRunSetup.ensurePromptFix], which rewrites a block whose version
     * is not the current one. A changed block under the old version would reach new
     * installs and nobody else.
     */
    @Test
    fun `a v2 block already on the device is brought up to date`() {
        bashrc.writeText(
            "# VSCodroid bash configuration\n" + PROMPT_BLOCK_V2 +
                "\n\nexport PROJECTS_DIR='${projects.path}'\n",
        )

        FirstRunSetup(context).ensurePromptFix()

        assertEquals(
            "[saf]", promptIn(named),
            "the prompt block already on the device still shows the copy's hash",
        )
        assertEquals("[saf]/src", promptIn(File(named, "src")))
    }

    private companion object {
        /**
         * The v2 block as releases before v3 wrote it, byte for byte, markers written
         * out. Frozen: it stands for the file already on a device, whatever this build
         * writes. The arrow is escaped only to keep this file ASCII.
         */
        val PROMPT_BLOCK_V2 = """
            # >>> vscodroid prompt v2 >>>
            # PROMPT_COMMAND computes the directory, PS1 renders it. The \[ \] markers tell
            # readline which bytes take no width; without them Ctrl+L and any wrapped line
            # redraw over the prompt. An earlier build printed the prompt straight out of
            # PROMPT_COMMAND with an empty PS1, dating from when the terminal was a pipe
            # rather than a PTY: readline could not measure that at all, and VS Code's
            # shell integration ended up wrapping an empty string.
            __vscodroid_prompt() {
                local dir="${'$'}PWD"
                # The tilde must be escaped. bash expands tildes in a substitution's
                # replacement text, so a bare one turns back into the home path and the
                # whole substitution collapses into a no-op. bash 3.2 does not do this,
                # so a macOS shell cannot reproduce it; only a device can.
                dir="${'$'}{dir/#${'$'}HOME/\~}"
                [[ "${'$'}dir" == /* ]] && dir="${'$'}{dir/#${'$'}PROJECTS_DIR/projects}"
                # Abbreviate SAF mirror paths: /data/.../saf-mirrors/<hash>/... ${'\u2192'} [saf]/...
                # At the mirror root there is nothing after the hash, so stripping has to be
                # conditional: stripping unconditionally leaves the hash itself standing,
                # which is the one thing this abbreviation exists to hide.
                if [[ "${'$'}dir" == *saf-mirrors/* ]]; then
                    dir="${'$'}{dir#*saf-mirrors/}"
                    case "${'$'}dir" in
                        */*) dir="[saf]/${'$'}{dir#*/}" ;;
                        *)   dir="[saf]" ;;
                    esac
                fi
                __vscodroid_dir="${'$'}dir"
            }
            PROMPT_COMMAND=__vscodroid_prompt
            PS1='\[\033[32m\]${'$'}{__vscodroid_dir}\[\033[0m\] \${'$'} '
            # <<< vscodroid prompt v2 <<<
        """.trimIndent()
    }
}
