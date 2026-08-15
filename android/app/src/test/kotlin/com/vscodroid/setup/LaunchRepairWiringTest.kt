package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where the per-launch repairs sit in `SplashActivity.onCreate`, and how they are
 * guarded.
 *
 * Both are load-bearing and neither is visible from the repairs themselves. They
 * run above the `isFirstRun()` early return because an install that is not new
 * still needs them: Android hands out a fresh `nativeLibraryDir` on every
 * reinstall, which dangles every absolute symlink in `usr/bin` and stales every
 * path recorded in `settings.json`. Move the block below that return -- a tidy-up
 * anyone might make, since "launch-time setup refresh" reads like first-run work
 * -- and every one of them keeps its own passing test while nothing calls them.
 *
 * They are guarded one at a time because a single `try` made the first failure
 * cost the rest, and the first is not the least likely to fail: `.npmrc` is
 * rewritten on the first launch after any reinstall, and a full disk turns that
 * into an exception that used to take the twelve calls behind it -- including the
 * only pass that gives disk back -- down with it.
 *
 * Read out of the source, following [com.vscodroid.DownloadStateWiringTest],
 * which explains why an Activity's `onCreate` is not reachable from a JVM test
 * here and why Robolectric costs more than the gap. This is a check on the shape
 * of a call site rather than on behaviour, and it is worth saying so plainly:
 * what it can prove is that each repair is named, is named before the return, and
 * is named inside its own guard. It cannot prove any of them work.
 *
 * In this package rather than beside SplashActivity because every repair it names
 * belongs to `FirstRunSetup` or `ToolchainManager`; the source path is absolute
 * within the module, so the location is free.
 */
class LaunchRepairWiringTest {

    private val source = File("src/main/kotlin/com/vscodroid/SplashActivity.kt")

    /**
     * The full inventory, in call order. It is a checklist rather than a
     * description: a repair added to `onCreate` and not added here fails the last
     * test in this class, which is how the two stay in step.
     */
    private val repairs = listOf(
        "setupToolSymlinks",
        "setupGitCore",
        "setupGitCaBundle",
        "setupRipgrepVscodeSymlink",
        "setupCopilotAndroidAliases",
        "repairTruncatedSetupFiles",
        "createNpmWrappers",
        "ensureToolchainEnvSourcing",
        "ensurePromptFix",
        "updateSettingsNativeLibPaths",
        "ensureProjectsDir",
        "repairInstalledToolchains",
        "reclaimRevokedMirrors",
    )

    private fun onCreateBody(): String {
        check(source.isFile) {
            "SplashActivity.kt not found at ${source.absolutePath} — this test would " +
                "otherwise pass by looking at nothing"
        }
        val text = source.readText()
        val declaration = text.indexOf("override fun onCreate(")
        check(declaration >= 0) { "onCreate is gone from SplashActivity.kt" }

        val open = text.indexOf('{', declaration)
        var depth = 0
        var i = open
        while (i < text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}') depth--
            if (depth == 0) break
            i++
        }
        check(depth == 0) { "no closing brace found for onCreate" }
        val body = text.substring(open, i + 1)
        // The brace matcher is the weak part, so it is bounded rather than
        // trusted: an extraction that ran to the end of the file would contain
        // every name for entirely the wrong reason. 5,000-odd characters when
        // this was written, most of it comment.
        check(body.length in 1_000..12_000) {
            "extracted ${body.length} characters of onCreate, which means the extraction " +
                "is wrong rather than the code"
        }
        return body
    }

    /**
     * Names are searched for in code, not in prose. Commenting a call out leaves
     * its characters in place, and the comments in this method name most of the
     * repairs anyway -- matching raw text would find every one of them in a file
     * that calls none.
     */
    private fun code(body: String) = body
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    private fun guardedLines(body: String) =
        body.lines().map { it.trim() }.filter { it.startsWith("repair(") }

    @Test
    fun `every launch-time repair runs before the first-run early return`() {
        val body = code(onCreateBody())
        val cut = body.indexOf("if (!setup.isFirstRun())")
        check(cut >= 0) {
            "the isFirstRun early return is gone from onCreate; this test is about what " +
                "runs above it, so its absence is a decision rather than a detail"
        }
        val beforeReturn = body.substring(0, cut)

        for (name in repairs) {
            assertTrue(
                beforeReturn.contains("$name("),
                "$name() no longer runs above the isFirstRun() early return, so on every " +
                    "launch of an install that is not new it does not run at all",
            )
        }
    }

    @Test
    fun `every launch-time repair is guarded on its own`() {
        val body = code(onCreateBody())
        val guarded = guardedLines(body)

        for (name in repairs) {
            val callers = body.lines().map { it.trim() }.filter { it.contains("$name(") }
            assertEquals(
                1, callers.size,
                "expected exactly one call to $name() in onCreate, found ${callers.size}",
            )
            assertTrue(
                callers.single() in guarded,
                "$name() is called outside repair(), so a failure anywhere near it costs " +
                    "the other repairs in the same block rather than only itself",
            )
        }
    }

    /**
     * The other direction. Without it, a fourteenth repair could be added inside
     * the shared `try` this class exists to keep dismantled, and every assertion
     * above would still pass because it names only the thirteen it knows.
     */
    @Test
    fun `the guarded calls contain nothing this test does not name`() {
        val guarded = guardedLines(code(onCreateBody()))

        assertEquals(
            repairs.size, guarded.size,
            "onCreate guards ${guarded.size} repairs and this test names ${repairs.size}; " +
                "add the new one to the list so its placement is checked too:\n" +
                guarded.joinToString("\n"),
        )
        for (line in guarded) {
            assertTrue(
                repairs.any { line.contains("$it(") },
                "a guarded call this test does not name: $line",
            )
        }
    }
}
