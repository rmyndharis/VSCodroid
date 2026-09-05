package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * That taking the app's old preferences out of the machine settings file happens
 * ONCE, and never again on that install.
 *
 * The pass exists because those preferences sit in the file the workbench reads as
 * the remote USER settings, which outranks the user's own; leaving them there is
 * why changing the theme or word wrap in Settings did nothing. Running it for ever
 * is the same defect inverted, and worse for being invisible: `Machine/settings.json`
 * is exactly what the Settings editor's **Remote** tab writes to, so a user who sets
 * one of the seventeen pairs there has it deleted again on the next launch, silently,
 * with the editor showing their choice until it reloads.
 *
 * The gate used to be `getPreviousVersionCode() < MOVED_DEFAULTS_VERSION_CODE`, with
 * that constant at 14 while the app shipped versionCode 13. One-shot only for as long
 * as someone remembered to ship 14, and until then true on every launch. Measured on
 * an API 37 emulator against that build: `"editor.wordWrap": "on"` written into the
 * machine file was gone after a single relaunch, while `"editor.fontSize": 16`
 * survived, because the pairs are matched value-exact and the app's old value was 14.
 *
 * So the signal is a durable per-install flag, and these cases pin the three
 * properties that make it one-shot rather than permanent.
 */
class MovedDefaultsOneShotTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var settingsFile: File

    /** The preferences, remembered rather than relaxed: the whole subject is state. */
    private val stored = mutableMapOf<String, Any>()

    private val nativeLibDir = "/data/app/~~new==/com.vscodroid-new==/lib/arm64"
    private val oldNativeLibDir = "/data/app/~~old==/com.vscodroid-old==/lib/arm64"

    /**
     * A document holding one pair the app used to write, at the root indent and with
     * the trailing comma the Settings editor leaves.
     *
     * `git.path` is deliberately stale so the refresh has something to write on its
     * own. Without it a run with nothing to prune returns before the write, and a
     * case asserting "the line survived" would pass on a method that never ran.
     */
    private fun settingsText() = """
        {
            "editor.wordWrap": "on",
            "git.path": "$oldNativeLibDir/libgit.so"
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(any(), any()) } answers {
            stored[firstArg()] = secondArg<Boolean>()
            editor
        }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getBoolean(any(), any()) } answers {
            stored[firstArg()] as? Boolean ?: secondArg()
        }
        every { prefs.getInt(any(), any()) } answers { stored[firstArg()] as? Int ?: secondArg() }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = nativeLibDir
        }

        settingsFile = File(Environment.getMachineSettingsPath(context))
        settingsFile.parentFile?.mkdirs()
        settingsFile.writeText(settingsText())
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun run() = FirstRunSetup(context).updateSettingsNativeLibPaths()

    @Test
    fun `the first pass takes the app's old preference out`() {
        run()

        assertFalse(
            settingsFile.readText().contains("editor.wordWrap"),
            "the pass left the app's own preference in the file the workbench ranks " +
                "above the user's settings, which is what it exists to remove",
        )
    }

    /**
     * The case the whole change is for. The user re-adds the same pair through the
     * Settings editor's Remote tab, which is an ordinary thing to do and writes
     * exactly this shape, and it has to survive every launch after the pass has run.
     */
    @Test
    fun `a pair the user writes afterwards is left alone`() {
        run()

        settingsFile.writeText(settingsText())
        run()

        assertTrue(
            settingsFile.readText().contains("\"editor.wordWrap\": \"on\""),
            "the pass ran a second time and deleted a setting the USER wrote. It is " +
                "gated on a durable flag precisely so it cannot: if the gate has gone " +
                "back to comparing versionCode, it is true on every launch until " +
                "someone ships the release that constant names",
        )
    }

    /**
     * A pass whose write fails has not taken anything out, so it must run again.
     *
     * Arranged the way UpdateSettingsPathsTest arranges it: a non-empty directory
     * where writeAtomically wants its temporary file. Recording the flag on that
     * path would leave the preferences in the file for ever with nothing to notice.
     */
    @Test
    fun `a failed write does not record the pass as done`() {
        val blocker = File(settingsFile.parentFile, "${settingsFile.name}.tmp~")
        assertTrue(blocker.mkdirs(), "could not stage the blocked temp path")
        File(blocker, "occupied").writeText("x")

        run()

        assertTrue(
            settingsFile.readText().contains("\"editor.wordWrap\": \"on\""),
            "the fixture is wrong: the write was expected to fail and leave the file alone",
        )
        assertFalse(
            stored["moved_defaults_pruned"] as? Boolean ?: false,
            "the pass recorded itself as done after a write that failed, so the app's " +
                "old preferences stay in the file and nothing will look again",
        )
    }

    /**
     * A document the pass could not read is not a document it cleaned.
     *
     * `pruneMovedDefaults` returns null for both, and they are opposite facts:
     * `firstPropertyIndent` answers null when the root object opens with a comment,
     * which it was deliberately made to do so a leading comment could not send the
     * prune into a nested object and delete the user's overrides there instead. The
     * app's own preferences are then still in the file. Recording the pass as done
     * for that leaves them for ever, which is the one thing the flag must not do,
     * and settings.json is JSONC that the editor invites the user to hand-edit.
     */
    @Test
    fun `a document the pass could not read is not recorded as done`() {
        // Settled first, so refreshManagedPaths has nothing to do and the prune sees
        // the document as written. That matters: the refresh inserts its managed
        // values directly after the opening brace, so on a stale document a leading
        // comment is no longer leading by the time the prune looks, and a fixture
        // that skips this step exercises the ordinary path while claiming to
        // exercise the decline.
        run()
        stored.clear()

        val settled = settingsFile.readText()
        settingsFile.writeText(
            settled.replaceFirst(
                "{\n",
                "{\n    // my own notes\n    \"editor.wordWrap\": \"on\",\n",
            ),
        )

        run()

        assertTrue(
            settingsFile.readText().contains("\"editor.wordWrap\": \"on\""),
            "the fixture is wrong: the prune was expected to decline this document. " +
                "File now:\n" + settingsFile.readText(),
        )
        assertFalse(
            stored["moved_defaults_pruned"] as? Boolean ?: false,
            "the pass recorded itself as done over a document it declined to read, so " +
                "the app's own preferences stay in the file that outranks the user's " +
                "settings and nothing will ever look again",
        )
    }

    /**
     * A fresh install never spends its one pass.
     *
     * The pass exists for documents written by an older release. An install that
     * begins now has never carried those preferences, so the only way its document
     * can hold one of the seventeen value-exact pairs is that the USER wrote it,
     * through the Settings editor's Remote tab which writes to this very file.
     * Spending the pass on the second launch therefore has exactly one possible
     * effect, and it is deleting a setting the user chose.
     */
    @Test
    fun `writing the settings file fresh records the pass as unnecessary`() {
        settingsFile.delete()
        stored.clear()

        FirstRunSetup(context).let { setup ->
            val create = FirstRunSetup::class.java.getDeclaredMethod("createDefaultSettings")
            create.isAccessible = true
            create.invoke(setup)
        }

        assertTrue(
            settingsFile.exists(),
            "the fixture is wrong: createDefaultSettings wrote nothing, so the case below " +
                "would pass on a method that never ran",
        )
        assertTrue(
            stored["moved_defaults_pruned"] as? Boolean ?: false,
            "a settings file this app just wrote was not recorded as needing no prune, so " +
                "the next launch spends the one pass over a document that can only have " +
                "gained one of those pairs from the user",
        )
    }
}
