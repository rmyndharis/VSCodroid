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
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * What `repairTruncatedSetupFiles` must leave behind when its rewrite fails.
 *
 * The repair exists for an install an older release left with a zero-byte
 * `.bashrc` or settings.json, and the condition that produced those is a full
 * disk, which is still the condition when the repair runs. So the rewrite
 * failing is not an exotic case here, it is the expected one, and until now it
 * was the one path that could make the install permanently worse: the file was
 * deleted first to satisfy the writers' `!exists()` guards, and a rewrite that
 * then failed left nothing. Absence was outside the repair's own re-entry test,
 * outside every per-launch appender (`createNpmWrappers`,
 * `ensureToolchainEnvSourcing`, `ensurePromptFix`, `ensureStartupDirGuard`,
 * `updateSettingsNativeLibPaths`, all `exists()`-gated) and outside
 * `createBashrc`/`createDefaultSettings`, which `runSetupLocked` reaches only
 * when versionName or versionCode moves. One launch that could not write turned
 * a self-healing install into one that recovers at the next app update or not
 * at all.
 *
 * Writing through [writeAtomically]'s rename instead of deleting first is the
 * whole fix, and what these measure is the property that follows from it: after
 * a failed rewrite the broken file is still there, so the repair fires again on
 * the next launch and freeing space is enough. The last test here is where that
 * property stops being unconditional: the appenders behind the repair can see a
 * file it could not rewrite, and for one of the two shapes what they add takes
 * the retry away. `repairTruncatedSetupFiles` documents which and why.
 *
 * The failure is injected at the temporary-file open in [writeAtomically].
 */
class TruncatedRepairRetryTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    private val bashrc by lazy { File(filesDir, "home/.bashrc") }
    private val settings by lazy { File(Environment.getMachineSettingsPath(context)) }

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
            nativeLibraryDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"
        }
        // Pinned so the files these writers produce do not depend on what a
        // relaxed mock invents for the projects directory.
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        bashrc.parentFile?.mkdirs()
        settings.parentFile?.mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun block(dest: File) = blockAtomicWritesTo(dest)

    /**
     * NEGATIVE CONTROL: restore `&& bashrc.delete()` to the branch condition in
     * `repairTruncatedSetupFiles`. The blocked launch then removes the file and
     * writes nothing, so `bashrc.isFile` fails; keep the delete and skip the
     * blocker and the second launch never re-enters the branch, so the healing
     * assertion fails too.
     */
    @Test
    fun `a blocked bashrc rewrite keeps the broken file and heals on the next launch`() {
        bashrc.writeText("")
        val blocker = block(bashrc)

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(
            bashrc.isFile,
            "the repair deleted the .bashrc it could not rewrite; nothing writes one again " +
                "until the app updates",
        )
        assertEquals("", bashrc.readText(), "the destination is supposed to be untouched")

        // The user frees space and launches again. That is the whole value of
        // leaving the empty file: it is what the repair re-enters through.
        blocker.close()
        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(
            bashrc.readText().contains("export PROJECTS_DIR"),
            "the install did not heal once there was room to write",
        )
    }

    /**
     * The header proves a cut-short copy of ours only when nothing follows it. A person who kept the
     * header and rewrote the export line (`PROJECTS_DIR=...` without `export`) has a file of their own
     * with the same shape, so its text is kept aside before the file is replaced.
     */
    @Test
    fun `text after the header is kept aside before the file is replaced`() {
        val own = "# VSCodroid bash configuration\nPROJECTS_DIR=\$HOME/code\nalias ll='ls -l'\n"
        bashrc.writeText(own)

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(bashrc.readText().contains("export PROJECTS_DIR"), "the file was not repaired")
        assertEquals(
            own, File(filesDir, "home/.vscodroid/bashrc.before-repair").readText(),
            "the person's text was replaced with no copy of it",
        )
    }

    /** The copy is of the file's bytes, not of what a UTF-8 decoder made of them. */
    @Test
    fun `the copy kept aside holds the bytes of the file`() {
        val own = "# VSCodroid bash configuration\n# caf".toByteArray() +
            byteArrayOf(0xE9.toByte()) + "\nalias ll='ls -l'\n".toByteArray()
        bashrc.writeBytes(own)

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(bashrc.readText().contains("export PROJECTS_DIR"), "the file was not repaired")
        assertArrayEquals(
            own, File(filesDir, "home/.vscodroid/bashrc.before-repair").readBytes(),
            "a byte that is not UTF-8 was changed in the only copy of the file",
        )
    }

    @Test
    fun `a file whose text could not be kept aside is left as it is`() {
        val own = "# VSCodroid bash configuration\nalias ll='ls -l'\n"
        bashrc.writeText(own)
        val blocker = block(File(filesDir, "home/.vscodroid/bashrc.before-repair"))

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertEquals(own, bashrc.readText(), "the file was replaced although its text could not be kept")

        // Room again: the next launch keeps the text and repairs.
        blocker.close()
        FirstRunSetup(context).repairTruncatedSetupFiles()
        assertTrue(bashrc.readText().contains("export PROJECTS_DIR"))
        assertEquals(own, File(filesDir, "home/.vscodroid/bashrc.before-repair").readText())
    }

    /**
     * The place a text is kept in is one name, and the text kept there by an earlier repair is the only
     * copy of what that repair replaced. A second repair wrote its own text over it.
     */
    @Test
    fun `a second repair keeps the text an earlier one set aside`() {
        val first = "# VSCodroid bash configuration\nalias first='true'\n"
        val second = "# VSCodroid bash configuration\nalias second='true'\n"
        val aside = File(filesDir, "home/.vscodroid/bashrc.before-repair")
        bashrc.writeText(first)
        FirstRunSetup(context).repairTruncatedSetupFiles()

        bashrc.writeText(second)
        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertEquals(second, aside.readText(), "the text the second repair replaced was not kept")
        val earlier = aside.parentFile!!.listFiles { file -> file.name.startsWith(aside.name + ".") }.orEmpty()
        assertEquals(listOf(first), earlier.map { it.readText() }, "the text the first repair kept is gone")
    }

    /** The name an earlier text moves to is one nothing holds, also when its time is taken. */
    @Test
    fun `an earlier text moves to a free name when the name of its time is taken`() {
        val aside = File(filesDir, "home/.vscodroid/bashrc.before-repair")
        bashrc.writeText("# VSCodroid bash configuration\nalias first='true'\n")
        FirstRunSetup(context).repairTruncatedSetupFiles()
        val taken = File(aside.path + "." + aside.lastModified()).apply { writeText("held by an earlier move") }

        bashrc.writeText("# VSCodroid bash configuration\nalias second='true'\n")
        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertEquals("held by an earlier move", taken.readText(), "a text kept earlier was written over")
        val kept = aside.parentFile!!.listFiles { file -> file.name.startsWith(aside.name) }.orEmpty().map { it.readText() }
        assertTrue(kept.any { it.contains("alias first") } && kept.any { it.contains("alias second") }, "$kept")
    }

    /** What is at the name and cannot be read as a file is moved aside as well, never written over. */
    @Test
    fun `something at the name that is no file is moved aside before the text is kept`() {
        val aside = File(filesDir, "home/.vscodroid/bashrc.before-repair")
        File(aside, "inside").apply { parentFile!!.mkdirs(); writeText("a directory someone made") }
        val own = "# VSCodroid bash configuration\nalias ll='ls -l'\n"
        bashrc.writeText(own)

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertEquals(own, aside.readText(), "the text was not kept")
        val moved = aside.parentFile!!.listFiles { file -> file.name.startsWith(aside.name + ".") }.orEmpty()
        assertEquals(listOf("a directory someone made"), moved.map { File(it, "inside").readText() })
    }

    /** A rewrite that failed after the text was kept finds the same text again, and keeps it once. */
    @Test
    fun `the same text met again is kept once`() {
        val own = "# VSCodroid bash configuration\nalias ll='ls -l'\n"
        val aside = File(filesDir, "home/.vscodroid/bashrc.before-repair")
        bashrc.writeText(own)
        FirstRunSetup(context).repairTruncatedSetupFiles()

        bashrc.writeText(own)
        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertEquals(own, aside.readText())
        assertEquals(
            listOf(aside.name),
            aside.parentFile!!.list { _, name -> name.startsWith(aside.name) }.orEmpty().toList(),
            "a second copy of the same text was kept",
        )
    }

    /** A `.bashrc` that is a link is repaired in the file it names, and stays a link. */
    @Test
    fun `an emptied bashrc that is a link is written again through the link`() {
        val theirs = File(filesDir, "dotfiles/.bashrc").apply { parentFile!!.mkdirs(); writeText("") }
        bashrc.delete()
        java.nio.file.Files.createSymbolicLink(bashrc.toPath(), theirs.toPath())

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(java.nio.file.Files.isSymbolicLink(bashrc.toPath()), "the link was replaced by a copy")
        assertTrue(theirs.readText().contains("export PROJECTS_DIR"), "the file the link names was not repaired")
    }

    @Test
    fun `a header with nothing after it is replaced with no copy kept`() {
        bashrc.writeText("# VSCodroid bash configuration\n")

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(bashrc.readText().contains("export PROJECTS_DIR"))
        assertFalse(File(filesDir, "home/.vscodroid/bashrc.before-repair").exists(), "a cut-short copy of ours was kept")
    }

    /**
     * What a first write cut short by a power loss leaves on f2fs: the file's length, and NUL bytes where its
     * text was. Nobody writes that as their shell setup, and the shell reads it as nothing at all.
     */
    @Test
    fun `a bashrc of NUL bytes is written again with no copy kept`() {
        bashrc.writeBytes(ByteArray(200))

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(bashrc.readText().contains("export PROJECTS_DIR"), "a .bashrc of NUL bytes was left as it was")
        assertFalse(File(filesDir, "home/.vscodroid/bashrc.before-repair").exists(), "NUL bytes were kept as a person's text")
    }

    /**
     * The system git configuration this app writes, cut short the same way. git refuses a config file it cannot
     * parse, so every git command in the terminal and in the editor fails until it is written again.
     */
    @Test
    fun `a git system config of NUL bytes is written again`() {
        val gitconfig = File(filesDir, "usr/etc/gitconfig").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64)) }

        FirstRunSetup(context).setupGitCore()

        assertEquals("[core]\n\tpager = cat\n", gitconfig.readText(), "a gitconfig of NUL bytes was left as it was")
    }

    /**
     * The same for settings.json, where it costs more: the editor cannot read the file, so every machine
     * setting is gone (the terminal profile, git.path, the Claude launcher), and no per-launch writer can
     * add one back to a document it cannot read.
     */
    @Test
    fun `a settings file of NUL bytes is written again`() {
        settings.writeBytes(ByteArray(200))

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(
            settings.readText().contains("terminal.integrated.profiles.linux"),
            "a settings.json of NUL bytes was left as it was",
        )
    }

    /**
     * The settings half, which cannot even buy space by clearing: deleting a
     * zero-byte file frees nothing, so the disk that emptied it is still full
     * when the rewrite runs.
     *
     * NEGATIVE CONTROL: restore `&& settings.delete()` to the condition in
     * `repairTruncatedSetupFiles`. The first assertion fails on the blocked
     * launch, and with the blocker removed the second launch finds no file to
     * classify and writes nothing.
     */
    @Test
    fun `a blocked settings rewrite keeps the empty file and heals on the next launch`() {
        settings.writeText("")
        val blocker = block(settings)

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(
            settings.isFile,
            "the repair deleted the settings.json it could not rewrite, which is outside " +
                "every guard that would have tried again",
        )

        blocker.close()
        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(
            settings.readText().contains("terminal.integrated.profiles.linux"),
            "the managed settings did not come back once there was room to write",
        )
    }

    /**
     * The half of the retry that survives the appenders, which is what turns the
     * KDoc's bound into a measured one.
     *
     * SplashActivity runs this repair immediately before `createNpmWrappers` and
     * `ensureToolchainEnvSourcing`, and not deleting is what lets those two see a
     * file the repair judged unusable and could not rewrite. For the header shape
     * their append changes nothing the classification reads: the header is still
     * first and `PROJECTS_DIR` is still absent, so the next launch repairs it
     * anyway. The empty shape is where that stops being true, and the KDoc says
     * where the line is and why widening the test to reach it would cost more
     * than it buys.
     *
     * The blocker is freed before the append on purpose. It is the only route to
     * a failed rewrite followed by a successful append: both writes derive the
     * same temporary-file open for the destination, so whatever stops one stops
     * the other.
     *
     * NEGATIVE CONTROL: change `text.startsWith(BASHRC_HEADER)` in
     * `repairTruncatedSetupFiles` to `text.trim() == BASHRC_HEADER`, the same
     * classification asked of the whole file rather than of its opening.
     * Measured: the append then de-classifies the file and the last assertion
     * below fails on its own, with the other three tests in this class green.
     * Restoring `&& bashrc.delete()` to the branch condition reddens this test
     * as well, at the "untouched" assertion rather than the healing one, since
     * `readText` is then left with no file to read.
     */
    @Test
    fun `an append between launches leaves the header shape repairable`() {
        // createNpmWrappers returns early unless npm was extracted, and an
        // appender that does not run would satisfy every assertion below.
        File(filesDir, "usr/lib/node_modules/npm/bin").mkdirs()
        File(filesDir, "usr/lib/node_modules/npm/bin/npm-cli.js").writeText("//")

        val header = "# VSCodroid bash configuration\n"
        bashrc.writeText(header)
        val blocker = block(bashrc)
        val setup = FirstRunSetup(context)

        setup.repairTruncatedSetupFiles()
        assertEquals(header, bashrc.readText(), "the destination is supposed to be untouched")

        // Room appears between the two calls. Nothing else lets the append below
        // succeed where the rewrite above failed.
        blocker.close()
        setup.createNpmWrappers()
        assertTrue(bashrc.readText().contains("npm()"), "the append under test never ran")
        assertFalse(
            bashrc.readText().contains("export PROJECTS_DIR"),
            "the append was supposed to leave the file still needing repair",
        )

        setup.repairTruncatedSetupFiles()
        assertTrue(
            bashrc.readText().contains("export PROJECTS_DIR"),
            "an append behind the repair cost the header shape its retry",
        )
    }

    /**
     * The launch before there is anything to repair, which is every fresh
     * install and every launch after Clear Data.
     *
     * SplashActivity runs this repair above the `isFirstRun()` branch, and
     * `home/` is made by `createDirectories()` inside the setup that has not run
     * yet. So the create arm saw a `.bashrc` that was absent for the ordinary
     * reason, tried to write one into a directory that was not there, and could
     * only fail: an ERROR line saying the terminal has no prompt, no PATH exports
     * and no npm, on 100% of first launches. In a release build logcat is the
     * only trace this project has, and a line that is always wrong is the kind
     * that teaches a reader to skip the ones that are not.
     *
     * NEGATIVE CONTROL: drop `&& bashrc.parentFile?.isDirectory == true` from the
     * branch. The write then fails and the ERROR is logged, so the last assertion
     * reddens.
     */
    @Test
    fun `a launch before the first extraction says nothing about a bashrc that cannot exist yet`() {
        // Recursive because setUp also stages the machine-settings directory
        // under `home`, and a fresh install has neither.
        assertTrue(
            bashrc.parentFile!!.deleteRecursively(),
            "could not stage a home directory that the first extraction has not made yet",
        )

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertFalse(
            bashrc.parentFile!!.exists(),
            "the repair made the home directory, which belongs to createDirectories",
        )
        verify(exactly = 0) { Logger.e(any(), any(), any()) }
    }

    /**
     * The control for both, and it is not decoration: a repair that had stopped
     * doing anything would satisfy every "the file is still there" assertion
     * above.
     */
    @Test
    fun `files that are fine are not rewritten`() {
        val theirs = "# my own shell setup\nalias g='git'\n"
        bashrc.writeText(theirs)
        val theirSettings = """{"editor.fontSize": 20}"""
        settings.writeText(theirSettings)

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertEquals(theirs, bashrc.readText(), "a file the user wrote was replaced")
        assertEquals(theirSettings, settings.readText(), "a settings file with content was replaced")
    }

    /**
     * A `.bashrc` that is a link is a file the person keeps elsewhere, often in a repository other
     * machines read, and `writeBashrc` writes this device's paths into whatever the link names. A file
     * that opens with this app's header and holds text of its own after it, and has no PROJECTS_DIR line
     * because they took it out, is theirs: the header proves nothing about what follows it. It is left as
     * it is, where an empty file or the header alone is still rewritten through the link.
     */
    @Test
    fun `a linked bashrc that opens with the header and holds text of its own is left alone`() {
        val target = File(filesDir, "dotfiles/bashrc").apply { parentFile.mkdirs() }
        val own = "# VSCodroid bash configuration\nalias ll='ls -l'\n"
        target.writeText(own)
        Files.createSymbolicLink(bashrc.toPath(), target.toPath())

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(Files.isSymbolicLink(bashrc.toPath()), "the link was replaced")
        assertEquals(own, target.readText(), "the file the link names was rewritten with this device's paths")
    }

    /** What this app writes as `.bashrc` today, read back from a file the repair wrote, and the link put back. */
    private fun ownBashrcText(target: File): String {
        Files.deleteIfExists(bashrc.toPath())
        FirstRunSetup(context).repairTruncatedSetupFiles()
        val ours = bashrc.readText()
        assertTrue(ours.contains("export PROJECTS_DIR"), "the repair wrote no .bashrc of its own")
        bashrc.delete()
        Files.createSymbolicLink(bashrc.toPath(), target.toPath())
        return ours
    }

    /**
     * A longer start of this app's own text behind a link is text after the header like any other, and stays
     * as it is. No release leaves one there: up to 1.0.0 the whole file, under 2 KB, went through the link in a
     * single write, which a full disk refuses whole; 1.1.0 to 1.9.0 renamed over the link; and 1.10.0 and
     * later rename through it, which writes all of the text or none.
     */
    @Test
    fun `a linked bashrc that holds a longer start of our text is left alone`() {
        val target = File(filesDir, "dotfiles/bashrc").apply { parentFile.mkdirs() }
        val ours = ownBashrcText(target)
        val cut = ours.substring(0, ours.indexOf("export PROJECTS_DIR") / 2)
        assertTrue(cut.startsWith("# VSCodroid bash configuration\n# >>>"), "the cut does not reach the prompt block")
        target.writeText(cut)

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(Files.isSymbolicLink(bashrc.toPath()), "the link was replaced")
        assertEquals(cut, target.readText(), "the file the link names was rewritten with this device's paths")
        assertFalse(File(filesDir, "home/.vscodroid/bashrc.before-repair").exists(), "a file left alone was kept aside")
    }

    @Test
    fun `a linked bashrc that is only the header is still rewritten through the link`() {
        val target = File(filesDir, "dotfiles/bashrc").apply { parentFile.mkdirs() }
        target.writeText("# VSCodroid bash configuration\n")
        Files.createSymbolicLink(bashrc.toPath(), target.toPath())

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(Files.isSymbolicLink(bashrc.toPath()), "the link was replaced")
        assertTrue(target.readText().contains("export PROJECTS_DIR"), "a cut-short copy of ours was not repaired")
    }

    /**
     * A `.bashrc` link whose file has gone, from a dotfiles folder emptied or checked out to a branch without it.
     * The link says where the person keeps their shell setup, so the app's text is written there and the link
     * stays; the rename used to land on the link and replace it with a copy.
     */
    @Test
    fun `a dangling linked bashrc stays a link`() {
        val target = File(filesDir, "projects/dotfiles/bashrc").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(bashrc.toPath(), Paths.get("../projects/dotfiles/bashrc"))

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(Files.isSymbolicLink(bashrc.toPath()), "the link was replaced by a copy")
        assertTrue(target.readText().contains("export PROJECTS_DIR"), "the file the link names was not written")
    }

    /**
     * The same link once the folder it points into was renamed, or was a device folder's copy the app took back.
     * Writing there would mean making that folder again, where the person moved it away, so the link is left as
     * it is, and a launch that cannot help it logs no error.
     */
    @Test
    fun `a linked bashrc whose folder has gone stays a link and nothing is made`() {
        Files.createSymbolicLink(bashrc.toPath(), Paths.get("../projects/dotfiles/bashrc"))

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertTrue(Files.isSymbolicLink(bashrc.toPath()), "the link was replaced by a copy")
        assertFalse(File(filesDir, "projects/dotfiles").exists(), "the folder the person moved was made again")
        verify(exactly = 0) { Logger.e(any(), any(), any()) }
    }

    /**
     * Setup's own writers meet the same link on the first launch after an update. Throwing there stops setup on
     * every update for as long as the folder is away, and writing over the link loses it. A launch while the folder
     * is away writes nothing and says no error; the first launch after it is back writes both files through their
     * links, or the install ran without its shell setup and its machine settings (the terminal profile, git.path,
     * claudeProcessWrapper) until the next update.
     */
    @Test
    fun `setup leaves rc links whose folders have gone as they are and carries on`() {
        Files.createSymbolicLink(bashrc.toPath(), Paths.get("../projects/dotfiles/bashrc"))
        val settingsTarget = File(filesDir, "projects/dotfiles/settings.json")
        Files.createSymbolicLink(settings.toPath(), settingsTarget.toPath())
        val setup = FirstRunSetup(context)

        for (writer in listOf("createBashrc", "createDefaultSettings")) {
            FirstRunSetup::class.java.getDeclaredMethod(writer).apply { isAccessible = true }.invoke(setup)
        }

        assertTrue(Files.isSymbolicLink(bashrc.toPath()), "the .bashrc link was replaced by a copy")
        assertTrue(Files.isSymbolicLink(settings.toPath()), "the settings link was replaced by a copy")
        assertFalse(File(filesDir, "projects/dotfiles").exists(), "the folder the person moved was made again")

        setup.repairTruncatedSetupFiles()

        assertFalse(File(filesDir, "projects/dotfiles").exists(), "a launch made the folder the person moved again")
        verify(exactly = 0) { Logger.e(any(), any(), any()) }

        File(filesDir, "projects/dotfiles").mkdirs()
        setup.repairTruncatedSetupFiles()

        assertTrue(Files.isSymbolicLink(bashrc.toPath()), "the .bashrc link was replaced once its folder was back")
        assertTrue(Files.isSymbolicLink(settings.toPath()), "the settings link was replaced once its folder was back")
        assertTrue(
            File(filesDir, "projects/dotfiles/bashrc").readText().contains("export PROJECTS_DIR"),
            "the file the .bashrc link names was not written once its folder was back",
        )
        assertTrue(
            settingsTarget.isFile && settingsTarget.readText().contains("terminal.integrated.profiles.linux"),
            "the file the settings link names was not written once its folder was back",
        )
    }

    /** The other three files setup writes through a link, each with the name its file has in a dotfiles folder. */
    private val loginFiles = mapOf(".ssh/config" to "ssh_config", ".bash_profile" to "bash_profile", ".tmux.conf" to "tmux.conf")

    /**
     * `~/.ssh/config`, `~/.bash_profile` and `~/.tmux.conf` were written by setup only, which runs when the app
     * updates. A link to one of them whose file has gone, in a dotfiles folder that is still there, was written by
     * nothing until then: git over ssh failed on every clone, fetch and push while it passed the config with `-F`,
     * the terminal's ssh ran without the config, and tmux windows came up without `.bashrc`. A launch writes each file
     * where its link points, and the link stays.
     */
    @Test
    fun `a launch writes the file a dotfile link names when it has gone from a folder that is there`() {
        val dots = File(filesDir, "projects/dotfiles").apply { mkdirs() }
        File(filesDir, "home/.ssh").mkdirs()
        for ((name, target) in loginFiles) {
            Files.createSymbolicLink(File(filesDir, "home/$name").toPath(), File(dots, target).toPath())
        }

        FirstRunSetup(context).repairTruncatedSetupFiles()

        for ((name, target) in loginFiles) {
            assertTrue(Files.isSymbolicLink(File(filesDir, "home/$name").toPath()), "$name was replaced by a copy")
            assertTrue(File(dots, target).isFile, "the file the $name link names was not written")
        }
        assertTrue(
            File(dots, "ssh_config").readText().contains("IdentityFile ${filesDir.absolutePath}/home/.ssh/id_ed25519"),
            "the file the ssh config link names was not written",
        )
        assertTrue(File(dots, "bash_profile").readText().contains(". \"\$HOME/.bashrc\""), "the profile was not written")
        assertTrue(File(dots, "tmux.conf").readText().contains("set -g mouse on"), "the tmux file was not written")
    }

    /**
     * The same links while the folder they point into has gone: nothing is made and nothing is said at error level,
     * because the folder is the person's to bring back. The first launch after it is back writes the files.
     */
    @Test
    fun `a launch writes dotfile links whose folder has gone once the folder is back`() {
        val dots = File(filesDir, "projects/dotfiles")
        File(filesDir, "home/.ssh").mkdirs()
        for ((name, target) in loginFiles) {
            Files.createSymbolicLink(File(filesDir, "home/$name").toPath(), File(dots, target).toPath())
        }

        FirstRunSetup(context).repairTruncatedSetupFiles()

        assertFalse(dots.exists(), "a launch made the folder the person moved again")
        verify(exactly = 0) { Logger.e(any(), any(), any()) }

        dots.mkdirs()
        FirstRunSetup(context).repairTruncatedSetupFiles()

        for ((name, target) in loginFiles) {
            assertTrue(Files.isSymbolicLink(File(filesDir, "home/$name").toPath()), "$name was replaced by a copy")
            assertTrue(File(dots, target).isFile, "the file the $name link names was not written once its folder was back")
        }
    }

    /** A plain file the person deleted is theirs to have deleted; only setup writes it again, as before. */
    @Test
    fun `a launch does not make a dotfile that is not a link`() {
        File(filesDir, "home/.ssh").mkdirs()

        FirstRunSetup(context).repairTruncatedSetupFiles()

        for (name in loginFiles.keys) assertFalse(File(filesDir, "home/$name").exists(), "a launch made $name")
    }
}
