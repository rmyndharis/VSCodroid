package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the main-thread disk diagnostic is debug-only, log-only, and installed
 * where the design says.
 *
 * The ceiling first, because a guard whose limits are not written down gets
 * trusted past them. `StrictMode.setThreadPolicy` is an `android.jar` stub and
 * `app/build.gradle.kts` declares no `testOptions { unitTests { isReturnDefaultValues } }`,
 * so calling [MainThreadWatch.install] here throws "not mocked". Only the guard in
 * front of it is executable. Everything else below reads source text, which sees a
 * token and not a behaviour.
 *
 * So these cases cannot show that the policy fires on anything, that `install()`
 * still has a body, or that the call sits at the END of `onCreate` rather than the
 * start. That last one is the whole design: the launch path before it touches the
 * filesystem on the main thread on purpose and says so in a comment, and one of
 * its repairs alone walks a 146-line manifest doing an lstat per entry, so a
 * policy that covered it would bury every finding worth reading. Only a device run
 * answers those, and `androidTest/README.md` records why CI cannot do it here.
 *
 * What they do cover is the four ways this rots into something worse than nothing:
 * a guard inverted so release installs the policy, a penalty upgraded to one that
 * kills a debug build over a documented violation, a builder that replaces the
 * platform's own policy instead of adding to it, and the call drifting up into the
 * launch path where it floods.
 */
class MainThreadWatchTest {

    private val watch = File("src/main/kotlin/com/vscodroid/util/MainThreadWatch.kt")
    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")
    private val app = File("src/main/kotlin/com/vscodroid/VSCodroidApp.kt")
    private val splash = File("src/main/kotlin/com/vscodroid/SplashActivity.kt")

    /**
     * A file's source with comment lines dropped.
     *
     * Not decoration here. [MainThreadWatch]'s own KDoc names `penaltyDeath`,
     * `penaltyDialog`, `detectNetwork` and `detectAll` in prose, arguing against
     * each, so a guard reading the raw text would fail on the untouched file.
     * Anchored on `trimStart()` the way `ServerReadinessCallSiteTest.codeLines()`
     * is.
     */
    private fun code(file: File): String {
        check(file.isFile) {
            "${file.name} not found at ${file.absolutePath}, so this test would otherwise " +
                "pass by looking at nothing"
        }
        return file.readLines().filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.joinToString("\n")
    }

    /**
     * The only case here that is not a source read.
     *
     * `BuildConfig.DEBUG` rather than `Logger`'s debuggable check, and the
     * difference is what keeps the policy out of release rather than merely quiet
     * there: the debuggable flag is read from ApplicationInfo at runtime, which R8
     * cannot fold, so the StrictMode call would survive into the release DEX and a
     * re-signed debuggable release would install a policy in production.
     */
    @Test
    fun `the debug build is the one that watches`() {
        assertTrue(
            MainThreadWatch.shouldWatch(),
            "the unit suite runs the debug variant, so this must be true here. False means the " +
                "guard is inverted and the policy is installed in release instead of in debug",
        )
    }

    /**
     * Log only, and only the two disk detectors.
     *
     * `penaltyDeath` would kill the debug build within seconds of the editor
     * loading: the first violation on every launch is `publishedResourceRoots`,
     * which is deliberate and documented, and the emoji font load androidx.startup
     * schedules would trip it for a reason this repository cannot fix.
     * `penaltyDialog` would put a dialog over the workbench during the interaction
     * being investigated.
     *
     * `detectAll` would drag in `detectUnbufferedIo`, `detectResourceMismatches`
     * and `detectCustomSlowCalls` over code that has not been read for any of them.
     * `detectNetwork` would add nothing here: the platform already detects it and
     * installs `penaltyDeathOnNetwork` at this targetSdk, and the comment in
     * `MainActivity.setupServiceCallbacks` naming NetworkOnMainThreadException is
     * written around that. What keeps it in force once this runs is the seeding
     * case below, not a call here.
     */
    @Test
    fun `the policy logs and never kills, and detects only disk`() {
        val source = code(watch)

        assertTrue(
            source.contains("penaltyLog()"),
            "the policy has to report somewhere; found no penaltyLog() in ${watch.name}",
        )
        assertTrue(
            source.contains("detectDiskReads()") && source.contains("detectDiskWrites()"),
            "both disk detectors are the subject of this diagnostic",
        )
        // `penaltyDeath()` and not `penaltyDeath`, so that an explicit
        // `penaltyDeathOnNetwork()` is not refused under a message about killing
        // the debug build. That call would be a harmless restatement of what the
        // platform already installed, and the seeding case above is what keeps it
        // in force whether or not anyone writes it.
        for (forbidden in listOf(
            "penaltyDeath()", "penaltyDropBox", "penaltyDialog", "detectAll(", "detectNetwork("
        )) {
            assertTrue(
                !source.contains(forbidden),
                "$forbidden must not be in ${watch.name}. Each is argued against in its KDoc; " +
                    "reaching for one means reopening that argument rather than editing past it",
            )
        }
    }

    /**
     * The policy is added to the one already in force, never substituted for it.
     *
     * `StrictMode.setThreadPolicy` assigns the whole mask, and `ThreadPolicy.Builder()`
     * starts at mask 0. So building disk detection from a bare builder and installing
     * it deletes what the platform put there at process start: `initThreadDefaults`
     * calls `detectNetwork()` and `penaltyDeathOnNetwork()` for every app targeting
     * Honeycomb or later, and this app relies on main-thread network being fatal in
     * writing. Losing it would let main-thread HTTP succeed in a debug build and die
     * in release, which is worse than having no diagnostic at all.
     *
     * `Builder(ThreadPolicy)` copies the existing mask and every detect and penalty
     * call ORs onto it, so seeding is the whole of the fix. Asserted on the source
     * because `getThreadPolicy` is an android.jar stub that throws here.
     */
    @Test
    fun `the builder is seeded from the policy already in force`() {
        val source = code(watch)

        assertTrue(
            source.contains("StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy())"),
            "the builder must start from the live policy; a bare Builder() silently drops the " +
                "platform's detectNetwork and penaltyDeathOnNetwork for the whole session",
        )
        assertTrue(
            !source.contains("ThreadPolicy.Builder()"),
            "an empty ThreadPolicy.Builder() in ${watch.name} means the installed mask replaces " +
                "the platform's rather than adding to it",
        )
    }

    /**
     * One call, and it is in the Activity.
     *
     * Moving it to `VSCodroidApp.onCreate` or `SplashActivity.onCreate` to "cover
     * more" is the change that ends this diagnostic: everything on that path does
     * main-thread disk deliberately, so the log fills with violations against code
     * that already explains itself, and what gets deleted is the policy rather than
     * the noise.
     */
    @Test
    fun `the policy is installed from the activity and nowhere else`() {
        val calls = code(mainActivity).lines().count { it.contains("MainThreadWatch.install()") }
        assertEquals(
            1, calls,
            "MainActivity must install the policy exactly once; found $calls call site(s)",
        )

        for (file in listOf(app, splash)) {
            assertTrue(
                !code(file).contains("MainThreadWatch"),
                "${file.name} runs before the editor session and touches the filesystem on the " +
                    "main thread on purpose at dozens of call sites. Installing the policy there " +
                    "buries every finding worth reading",
            )
        }
    }
}
