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
 * path recorded in `settings.json`. Move the block below that return, a tidy-up
 * anyone might make, since "launch-time setup refresh" reads like first-run work
 * and every one of them keeps its own passing test while nothing calls them.
 *
 * They are guarded one at a time because a single `try` made the first failure
 * cost the rest, and the first is not the least likely to fail: `.npmrc` is
 * rewritten on the first launch after any reinstall, and a full disk turns that
 * into an exception that used to take the thirteen calls behind it, including the
 * only pass that gives disk back, down with it.
 *
 * Read out of the source, following [com.vscodroid.DownloadStateWiringTest],
 * which explains why an Activity's `onCreate` is not reachable from a JVM test
 * here and why Robolectric costs more than the gap.
 *
 * WHAT THIS CAN AND CANNOT PROVE, because a source-shaped check invites being
 * read as more than it is. It proves that each repair is named, that it is named
 * above the early return, that the name sits inside its own `repair()` guard, in
 * the order this class lists, and that the guarded body is a single
 * unconditional call and nothing else. That last one is not decoration: while
 * the check was "the line begins with `repair(` and contains the name",
 * `repair("tool symlinks") { if (false) setup.setupToolSymlinks() }` satisfied
 * every test here, measured, not supposed, and so would a `?.` on a null
 * receiver or a call handed the wrong argument.
 *
 * What stays out of reach:
 *
 *  - whether any repair does anything. Every one of them can return at its first
 *    line and this class is satisfied.
 *  - whether the receiver is the right object. `setup` is matched as text, so a
 *    second `FirstRunSetup` built over a different Context reads the same.
 *  - whether `repair()` still catches. Gutting the helper into `action()` leaves
 *    every line here unchanged.
 *  - anything a repair does from a lambda spanning several lines. The shape check
 *    refuses those outright rather than guessing, so introducing one is a
 *    deliberate change to this test and not a silent loss of cover.
 *
 * In this package rather than beside SplashActivity because every repair it names
 * belongs to `FirstRunSetup` or `ToolchainManager`; the source path is absolute
 * within the module, so the location is free.
 */
class LaunchRepairWiringTest {

    private val source = File("src/main/kotlin/com/vscodroid/SplashActivity.kt")

    /**
     * The full inventory, in call order, and the order is checked, by the last
     * test in this class rather than merely claimed here, which it was until the
     * check existed. It is a checklist rather than a description: a repair added
     * to `onCreate` and not added here fails that same test, which is how the two
     * stay in step.
     *
     * The order matters at one place and is worth keeping honest everywhere:
     * `repairTruncatedSetupFiles` has to precede the three appenders that extend
     * `.bashrc` only when it already exists.
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
        "createBashEnvFile",
        "updateSettingsNativeLibPaths",
        "ensureProjectsDir",
        "repairInstalledToolchains",
        "reconcileDeliveredPacks",
        "reclaimRevokedMirrors",
    )

    private fun onCreateBody(): String {
        check(source.isFile) {
            "SplashActivity.kt not found at ${source.absolutePath}, this test would " +
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
     * repairs anyway, matching raw text would find every one of them in a file
     * that calls none.
     */
    private fun code(body: String) = body
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    private fun guardedLines(body: String) =
        body.lines().map { it.trim() }.filter { it.startsWith("repair(") }

    /**
     * The name a guarded line calls, or null when the line is not a bare call.
     *
     * Bare means the whole body is one invocation: an optional receiver, a
     * name, or a constructor call, followed by a dot, then the method and an
     * empty argument list, then the closing brace. Nothing else fits, and the
     * exclusions are the point rather than a side effect. `if (false)` in front
     * of the call does not fit, nor does `?.` on a receiver that may be null,
     * nor a second statement after a semicolon, nor an argument, since every
     * repair here takes none and one appearing is a change worth reading rather
     * than waving through.
     *
     * It is a regular expression over source text, so it can be defeated by
     * anyone who wants to: `repair("x") { alwaysFails() }` parses perfectly. It
     * closes the accidents, not the deliberate acts.
     */
    private fun calledName(line: String): String? =
        BARE_REPAIR_CALL.find(line)?.groupValues?.get(1)

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
     * The body of a guard has to be a call that runs, not merely a call that is
     * written.
     *
     * `repair("tool symlinks") { if (false) setup.setupToolSymlinks() }` satisfies
     * every other assertion in this class, the name is there, it is above the
     * return, it is inside its own guard, and calls nothing. So does
     * `setup?.setupToolSymlinks()` on a receiver that has gone null, and so does a
     * call handed an argument that turns it into a no-op. Whether the guarded line
     * can actually reach the method is a different question from whether it
     * mentions it, and this is the one that asks it.
     */
    @Test
    fun `every guarded call is a bare call with nothing in front of it`() {
        for (line in guardedLines(code(onCreateBody()))) {
            assertTrue(
                calledName(line) != null,
                "this guarded line is not a plain call, so nothing here can say it runs:\n  " +
                    line +
                    "\nIf the body genuinely needs to be more than one call, teach calledName() " +
                    "the new shape deliberately rather than loosening it.",
            )
        }
    }

    /**
     * The control for the check above, and it is not optional: a shape check that
     * accepted everything would pass that test on any source at all. Each line
     * here is a way a guarded repair can be present and dead, taken from the
     * ways this test was actually defeated or could be.
     */
    @Test
    fun `the shape check rejects a guarded line that cannot run`() {
        val dead = listOf(
            """repair("tool symlinks") { if (false) setup.setupToolSymlinks() }""",
            """repair("tool symlinks") { if (BuildConfig.DEBUG) setup.setupToolSymlinks() }""",
            """repair("tool symlinks") { setup?.setupToolSymlinks() }""",
            """repair("tool symlinks") { setup.setupToolSymlinks(dryRun = true) }""",
            """repair("tool symlinks") { log("x"); setup.setupToolSymlinks() }""",
            """repair("tool symlinks") { }""",
        )
        for (line in dead) {
            assertEquals(
                null, calledName(line),
                "the shape check accepted a body that does not call the repair: $line",
            )
        }

        // And the shapes that must keep passing, or the check above would fail
        // the real source for the wrong reason.
        assertEquals(
            "setupToolSymlinks",
            calledName("""repair("tool symlinks") { setup.setupToolSymlinks() }"""),
        )
        assertEquals(
            "repairInstalledToolchains",
            calledName("""repair("x") { ToolchainManager(this).repairInstalledToolchains() }"""),
        )
    }

    /**
     * The same mutation, against the real source rather than a string this test
     * wrote, and it is the one that proves the check is pointed at the file it
     * claims to read. The line is taken out of `onCreate`, switched off in
     * memory, and put back through the check, so the assertion fails if the
     * check is loosened, and the `check()` above it fails if the line it targets
     * has been reworded, rather than the whole thing quietly passing on source it
     * no longer recognises.
     */
    @Test
    fun `switching off a real guarded line is caught`() {
        val body = code(onCreateBody())
        val live = """repair("tool symlinks") { setup.setupToolSymlinks() }"""
        val dead = """repair("tool symlinks") { if (false) setup.setupToolSymlinks() }"""
        check(body.contains(live)) {
            "onCreate no longer contains $live, so this mutation is testing nothing; retarget it " +
                "at a line that is there"
        }

        val switchedOff = guardedLines(body.replace(live, dead))

        assertTrue(
            switchedOff.any { calledName(it) == null },
            "a repair wrapped in `if (false)` reads as guarded and named, and nothing here " +
                "notices that it never runs",
        )
    }

    /**
     * The other direction, and the order with it. Without this, one more repair
     * could be added inside the shared `try` this class exists to keep dismantled
     * and every assertion above would still pass, because they name only the
     * repairs they already know.
     *
     * Deliberately not phrased as a count. The prose here said "the fourteen" and
     * went stale the moment a fifteenth was added, while the assertion below kept
     * working because it compares the whole list rather than its length.
     *
     * Order is checked because the list above says "in call order" and because
     * one pair genuinely depends on it: `repairTruncatedSetupFiles` clears a
     * `.bashrc` that an older release left half-written, and the three appenders
     * behind it extend that file only when it already exists. Reordered, they
     * would extend the broken one and the clear would then throw their work away.
     */
    @Test
    fun `the guarded calls are these, in this order`() {
        val guarded = guardedLines(code(onCreateBody()))

        assertEquals(
            repairs,
            guarded.map { calledName(it) ?: it },
            "the guards in onCreate no longer match this list, in count, membership or order",
        )
    }

    private companion object {
        /**
         * `repair("...") { <receiver>.<name>() }` and nothing else. The receiver
         * is optional and may be a constructor call, which is how the ones built
         * on a fresh `ToolchainManager` or `SafStorageManager` are written.
         */
        val BARE_REPAIR_CALL =
            Regex("""^repair\("[^"]*"\)\s*\{\s*(?:[A-Za-z_]\w*(?:\([^()]*\))?\.)?([A-Za-z_]\w*)\(\)\s*}$""")
    }
}
