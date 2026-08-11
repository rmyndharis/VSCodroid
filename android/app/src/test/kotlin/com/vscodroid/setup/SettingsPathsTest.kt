package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [refreshManagedPaths] — the settings.json values that embed
 * nativeLibraryDir and therefore go stale on every APK reinstall.
 *
 * Regression coverage for issue #3: a greedy regex used to rewrite these paths
 * swallowed the binary filename, leaving the terminal profile pointing at a
 * directory. VS Code validates profile paths with isFile(), so the profile was
 * silently dropped and the terminal profile picker went empty.
 *
 * settings.json is JSONC and belongs to the user, so these tests assert on the
 * document text rather than on parsed values: everything outside the two managed
 * values must survive byte for byte.
 */
class SettingsPathsTest {

    private val bash = "/data/app/~~new==/com.vscodroid-new==/lib/arm64/libbash.so"
    private val git = "/data/app/~~new==/com.vscodroid-new==/lib/arm64/libgit.so"

    private val oldDir = "/data/app/~~old==/com.vscodroid-old==/lib/arm64"

    private fun settings(
        bashPath: String,
        gitPath: String,
        args: String = """["-i"]""",
        preamble: String = "",
    ) = """
        {
        $preamble    "editor.fontSize": 14,
            "terminal.integrated.profiles.linux": {
                "bash": {
                    "path": "$bashPath",
                    "args": $args,
                    "icon": "terminal-bash"
                }
            },
            "git.path": "$gitPath",
            "workbench.colorTheme": "Monokai"
        }
    """.trimIndent()

    @Nested
    inner class Repair {

        @Test
        fun `restores a profile path truncated to a directory`() {
            // Exactly what shipped in v1.0.0: the binary filename stripped off.
            val result = refreshManagedPaths(settings(oldDir, oldDir), bash, git)

            requireNotNull(result) { "corrupted settings must be rewritten" }
            assertTrue(result.contains(""""path": "$bash""""), "bash path not restored:\n$result")
            assertTrue(result.contains(""""git.path": "$git""""), "git path not restored:\n$result")
        }

        @Test
        fun `refreshes paths left stale by a reinstall`() {
            val result = refreshManagedPaths(
                settings("$oldDir/libbash.so", "$oldDir/libgit.so"), bash, git,
            )

            requireNotNull(result)
            assertTrue(result.contains(""""path": "$bash""""))
            assertTrue(result.contains(""""git.path": "$git""""))
        }

        @Test
        fun `rewrites the terminal profile even when git path is already current`() {
            // The two values must be tracked independently: an implementation that
            // decided "changed" from git.path alone would leave the terminal broken,
            // which is the reported bug.
            val result = refreshManagedPaths(settings(oldDir, git), bash, git)

            requireNotNull(result) { "a stale bash path alone must trigger a rewrite" }
            assertTrue(result.contains(""""path": "$bash""""))
        }

        @Test
        fun `rewrites git path even when the terminal profile is already current`() {
            val result = refreshManagedPaths(settings(bash, oldDir), bash, git)

            requireNotNull(result) { "a stale git path alone must trigger a rewrite" }
            assertTrue(result.contains(""""git.path": "$git""""))
        }
    }

    @Nested
    inner class LeavesAlone {

        @Test
        fun `returns null when both paths are already correct`() {
            // Guards against rewriting the file on every launch, which is how the old
            // regex corrupted a healthy install on its second run.
            assertNull(refreshManagedPaths(settings(bash, git), bash, git))
        }

        @Test
        fun `preserves comments and the rest of the document verbatim`() {
            val notes = "    // my own notes\n    /* and a block */\n"

            val result = refreshManagedPaths(settings(oldDir, oldDir, preamble = notes), bash, git)

            // The whole document, byte for byte, with only the two managed values moved on.
            assertEquals(settings(bash, git, preamble = notes), result)
        }

        @Test
        fun `preserves a trailing comma in the profile args`() {
            // Legal JSONC. Re-serialising through a JSON object model would turn this
            // into ["-i", null] on Android and break the terminal it exists to fix.
            val result = refreshManagedPaths(
                settings(oldDir, oldDir, args = """["-i",]"""), bash, git,
            )

            requireNotNull(result)
            assertTrue(result.contains("""["-i",]"""), "args were reformatted:\n$result")
        }

        @Test
        fun `does not touch a git path the user chose themselves`() {
            val custom = "/data/user/0/com.vscodroid/files/usr/bin/git"

            val result = refreshManagedPaths(settings(oldDir, custom), bash, git)

            requireNotNull(result) { "the bash path still needed repairing" }
            assertTrue(result.contains(""""git.path": "$custom""""), "user git.path overwritten")
        }

        @Test
        fun `returns null when git path is absent rather than inventing one`() {
            val noGit = """{ "editor.fontSize": 14 }"""

            assertNull(refreshManagedPaths(noGit, bash, git))
        }

        @Test
        fun `returns null when the profile has been restructured`() {
            // A shape we do not recognise is left as the user wrote it.
            val restructured = """
                {
                    "terminal.integrated.profiles.linux": {
                        "zsh": { "path": "$oldDir/libzsh.so" }
                    }
                }
            """.trimIndent()

            assertNull(refreshManagedPaths(restructured, bash, git))
        }
    }
}
