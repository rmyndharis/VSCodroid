package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [refreshManagedPaths] — the settings.json values this app manages.
 *
 * Two jobs are covered. `git.path` embeds nativeLibraryDir and goes stale on every
 * APK reinstall, so it is re-pointed. The terminal profile is migrated off
 * nativeLibraryDir entirely, onto the `usr/bin/bash` symlink, and carries the rest
 * of the shell-integration fix with it.
 *
 * Regression coverage for issue #3: a greedy regex used to rewrite these paths
 * swallowed the binary filename, leaving the terminal profile pointing at a
 * directory. VS Code validates profile paths with isFile(), so the profile was
 * silently dropped and the terminal profile picker went empty.
 *
 * settings.json is JSONC and belongs to the user, so these tests assert on the
 * document text rather than on parsed values: everything outside the managed
 * values must survive byte for byte.
 */
class SettingsPathsTest {

    /** Where the profile now points: a symlink setupToolSymlinks() keeps current. */
    private val shell = "/data/user/0/com.vscodroid/files/usr/bin/bash"
    private val git = "/data/app/~~new==/com.vscodroid-new==/lib/arm64/libgit.so"

    private val oldDir = "/data/app/~~old==/com.vscodroid-old==/lib/arm64"

    /** What the Claude Code extension is launched with; a filesDir symlink. */
    private val wrapper = "/data/user/0/com.vscodroid/files/usr/bin/node"

    private fun settings(
        bashPath: String,
        gitPath: String,
        args: String = """["-i"]""",
        shellIntegration: String = "false",
        preamble: String = "",
        claudeWrapper: String? = wrapper,
        verifySignature: Boolean = true,
    ) = """
        {
        $preamble    "editor.fontSize": 14,${claudeWrapper?.let { "\n    \"claudeCode.claudeProcessWrapper\": \"$it\"," } ?: ""}${if (verifySignature) "\n        \"extensions.verifySignature\": false," else ""}
            "terminal.integrated.profiles.linux": {
                "bash": {
                    "path": "$bashPath",
                    "args": $args,
                    "icon": "terminal-bash"
                }
            },
            "git.path": "$gitPath",
            "terminal.integrated.shellIntegration.enabled": $shellIntegration,
            "workbench.colorTheme": "Monokai"
        }
    """.trimIndent()

    @Nested
    inner class Repair {

        @Test
        fun `restores a profile path truncated to a directory`() {
            // Exactly what shipped in v1.0.0: the binary filename stripped off.
            val result = refreshManagedPaths(settings(oldDir, oldDir), shell, git, wrapper)

            requireNotNull(result) { "corrupted settings must be rewritten" }
            assertTrue(result.contains(""""path": "$shell""""), "bash path not restored:\n$result")
            assertTrue(result.contains(""""git.path": "$git""""), "git path not restored:\n$result")
        }

        @Test
        fun `refreshes paths left stale by a reinstall`() {
            val result = refreshManagedPaths(
                settings("$oldDir/libbash.so", "$oldDir/libgit.so"), shell, git, wrapper,
            )

            requireNotNull(result)
            assertTrue(result.contains(""""path": "$shell""""))
            assertTrue(result.contains(""""git.path": "$git""""))
        }

        @Test
        fun `rewrites the terminal profile even when git path is already current`() {
            // The two values must be tracked independently: an implementation that
            // decided "changed" from git.path alone would leave the terminal broken,
            // which is the reported bug.
            val result = refreshManagedPaths(settings(oldDir, git), shell, git, wrapper)

            requireNotNull(result) { "a stale bash path alone must trigger a rewrite" }
            assertTrue(result.contains(""""path": "$shell""""))
        }

        @Test
        fun `rewrites git path even when the terminal profile is already current`() {
            val result = refreshManagedPaths(settings(shell, oldDir), shell, git, wrapper)

            requireNotNull(result) { "a stale git path alone must trigger a rewrite" }
            assertTrue(result.contains(""""git.path": "$git""""))
        }
    }

    @Nested
    inner class ShellIntegrationMigration {

        @Test
        fun `clears the -i arg that blocked shell integration`() {
            // VS Code injects its bash integration only when the profile's args are
            // empty or a known login form. "-i" is neither, so it silently skipped.
            val result = refreshManagedPaths(settings(oldDir, oldDir), shell, git, wrapper)

            requireNotNull(result)
            assertTrue(result.contains(""""args": []"""), "args not cleared:\n$result")
        }

        @Test
        fun `turns shell integration on as part of the same move`() {
            val result = refreshManagedPaths(settings(oldDir, oldDir), shell, git, wrapper)

            requireNotNull(result)
            assertTrue(
                result.contains(""""terminal.integrated.shellIntegration.enabled": true"""),
                "shell integration not enabled:\n$result",
            )
        }

        @Test
        fun `leaves shell integration off once the profile has already moved`() {
            // The migration is one-shot by construction: it only fires on the launch
            // that moves the path off /data/app/. Afterwards the user owns the
            // setting, and turning it back off must stick.
            val alreadyMoved = settings(shell, git, args = "[]", shellIntegration = "false")

            assertNull(refreshManagedPaths(alreadyMoved, shell, git, wrapper))
        }

        @Test
        fun `does not touch shell integration when only git path is stale`() {
            // A reinstall refreshes git.path on its own; that is not a migration and
            // must not reach over into a setting the user may have turned off.
            val result = refreshManagedPaths(
                settings(shell, oldDir, args = "[]", shellIntegration = "false"), shell, git, wrapper,
            )

            requireNotNull(result) { "the git path still needed refreshing" }
            assertTrue(
                result.contains(""""terminal.integrated.shellIntegration.enabled": false"""),
                "shell integration flipped without a profile move:\n$result",
            )
        }
    }

    @Nested
    inner class LeavesAlone {

        @Test
        fun `returns null when both paths are already correct`() {
            // Guards against rewriting the file on every launch, which is how the old
            // regex corrupted a healthy install on its second run.
            assertNull(refreshManagedPaths(settings(shell, git, args = "[]"), shell, git, wrapper))
        }

        @Test
        fun `preserves comments and the rest of the document verbatim`() {
            val notes = "    // my own notes\n    /* and a block */\n"

            val result = refreshManagedPaths(settings(oldDir, oldDir, preamble = notes), shell, git, wrapper)

            // The whole document, byte for byte, with only the managed values moved on.
            assertEquals(
                settings(shell, git, args = "[]", shellIntegration = "true", preamble = notes),
                result,
            )
        }

        @Test
        fun `preserves a trailing comma in the profile args`() {
            // Legal JSONC. Re-serialising through a JSON object model would turn this
            // into ["-i", null] on Android and break the terminal it exists to fix.
            // The args migration matches only the exact shape this app wrote, so a
            // hand-edited one is left alone rather than reformatted.
            val result = refreshManagedPaths(
                settings(oldDir, oldDir, args = """["-i",]"""), shell, git, wrapper,
            )

            requireNotNull(result)
            assertTrue(result.contains("""["-i",]"""), "args were reformatted:\n$result")
        }

        @Test
        fun `does not touch a git path the user chose themselves`() {
            val custom = "/system/bin/git"

            val result = refreshManagedPaths(settings(oldDir, custom), shell, git, wrapper)

            requireNotNull(result) { "the bash path still needed repairing" }
            assertTrue(result.contains(""""git.path": "$custom""""), "user git.path overwritten")
        }

        @Test
        fun `returns null when git path is absent rather than inventing one`() {
            val noGit = """{ "claudeCode.claudeProcessWrapper": "$wrapper", """ +
                """"extensions.verifySignature": false, "editor.fontSize": 14 }"""

            assertNull(refreshManagedPaths(noGit, shell, git, wrapper))
        }

        @Test
        fun `returns null when the profile has been restructured`() {
            // A shape we do not recognise is left as the user wrote it.
            val restructured = """
                {
                    "claudeCode.claudeProcessWrapper": "$wrapper",
                    "extensions.verifySignature": false,
                    "terminal.integrated.profiles.linux": {
                        "zsh": { "path": "$oldDir/libzsh.so" }
                    }
                }
            """.trimIndent()

            assertNull(refreshManagedPaths(restructured, shell, git, wrapper))
        }
    }

    /**
     * The Claude Code extension refuses to start without this setting —
     * resolveClaudeBinary() throws "Unsupported platform" rather than falling back
     * to PATH — so it has to be added to settings.json that predate it, not only
     * refreshed. Insertion touches a user's document, so what these cover is mostly
     * what must NOT change.
     */
    @Nested
    inner class ClaudeWrapper {

        @Test
        fun `adds the wrapper when it is absent`() {
            val result = refreshManagedPaths(
                settings(shell, git, args = "[]", claudeWrapper = null), shell, git, wrapper,
            )

            requireNotNull(result) { "a missing wrapper must be added" }
            assertTrue(
                result.contains(""""claudeCode.claudeProcessWrapper": "$wrapper""""),
                "wrapper not inserted:\n$result",
            )
        }

        @Test
        fun `leaves everything else byte for byte`() {
            val before = settings(shell, git, args = "[]", claudeWrapper = null)
            val result = requireNotNull(refreshManagedPaths(before, shell, git, wrapper))

            val inserted = """    "claudeCode.claudeProcessWrapper": "$wrapper",""" + "\n"
            assertEquals(before, result.replace(inserted, ""), "more than one line changed")
        }

        @Test
        fun `keeps comments and blank lines above the first property`() {
            val notes = "    // hand written\n\n"
            val before = settings(shell, git, args = "[]", claudeWrapper = null, preamble = notes)
            val result = requireNotNull(refreshManagedPaths(before, shell, git, wrapper))

            assertTrue(result.contains("// hand written"), "comment lost:\n$result")
        }

        @Test
        fun `leaves a wrapper the user pointed somewhere of their own`() {
            // The sibling paths document this rule and honour it; this one did
            // not, and rewrote whatever it found at every launch.
            val chosen = "/data/user/0/com.vscodroid/files/home/my-claude-wrapper.sh"
            val before = settings(shell, git, args = "[]", claudeWrapper = chosen)
            val result = refreshManagedPaths(before, shell, git, wrapper)

            if (result != null) {
                assertTrue(result.contains(chosen), "the user's wrapper was overwritten:\n$result")
                assertTrue(!result.contains(wrapper), "the managed value was written anyway:\n$result")
            }
        }

        @Test
        fun `does not add a second wrapper beside the user's own`() {
            // The trap in anchoring: once a user value stops matching, treating
            // "did not match" as "not present" writes the key twice.
            val chosen = "/data/user/0/com.vscodroid/files/home/my-claude-wrapper.sh"
            val before = settings(shell, git, args = "[]", claudeWrapper = chosen)
            val result = refreshManagedPaths(before, shell, git, wrapper) ?: before

            val occurrences = Regex(""""claudeCode\.claudeProcessWrapper"""").findAll(result).count()
            assertEquals(1, occurrences, "the key appears $occurrences times:\n$result")
        }

        @Test
        fun `re-points the shape production actually writes`() {
            // Every other case here uses the legacy filesDir shape, so the
            // /data/app/ alternative in CLAUDE_WRAPPER was matched by nothing.
            // Measured: deleting that alternative left all nine of these green,
            // while no real install would ever have its wrapper re-pointed.
            //
            // Production writes this shape and only this shape --
            // Environment.getMuslLoaderPath() returns
            // "${nativeLibraryDir}/libldmusl.so" -- and nativeLibraryDir moves on
            // every reinstall, which is the entire reason the value is rewritten.
            val stale = settings(shell, git, args = "[]",
                claudeWrapper = "$oldDir/libldmusl.so")
            val result = requireNotNull(refreshManagedPaths(stale, shell, git, wrapper)) {
                "a wrapper under the old nativeLibraryDir must be re-pointed"
            }

            assertTrue(result.contains(""""claudeCode.claudeProcessWrapper": "$wrapper""""))
            assertTrue(!result.contains(oldDir), "stale nativeLibraryDir survived:\n$result")
        }

        @Test
        fun `re-points a wrapper the user has stale`() {
            val stale = settings(shell, git, args = "[]",
                claudeWrapper = "/data/user/0/com.vscodroid/files/usr/bin/node-old")
            val result = requireNotNull(refreshManagedPaths(stale, shell, git, wrapper))

            assertTrue(result.contains(""""claudeCode.claudeProcessWrapper": "$wrapper""""))
            assertTrue(!result.contains("node-old"), "stale value survived:\n$result")
        }

        @Test
        fun `turns signature verification off for installs that predate it`() {
            // Code - OSS has no vsda, so verification cannot run and refuses the
            // install when it cannot. Without this, every marketplace install stops
            // at a security prompt the user can only click past.
            val before = settings(shell, git, args = "[]", verifySignature = false)
            val result = requireNotNull(refreshManagedPaths(before, shell, git, wrapper)) {
                "a missing extensions.verifySignature must be added"
            }

            assertTrue(
                result.contains(""""extensions.verifySignature": false"""),
                "setting not inserted:\n$result",
            )
        }

        @Test
        fun `leaves signature verification alone once the user has an opinion`() {
            // Either value counts as an opinion: someone who turned it back on meant to.
            val on = settings(shell, git, args = "[]")
                .replace(""""extensions.verifySignature": false""", """"extensions.verifySignature": true""")

            assertNull(refreshManagedPaths(on, shell, git, wrapper),
                "a deliberate re-enable was overwritten")
        }

        @Test
        fun `is idempotent`() {
            val once = settings(shell, git, args = "[]", claudeWrapper = null)
                .let { requireNotNull(refreshManagedPaths(it, shell, git, wrapper)) }

            assertNull(refreshManagedPaths(once, shell, git, wrapper),
                "a second pass rewrote a settled document")
        }
    }
}
