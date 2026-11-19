package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The order of a launch in `SplashActivity.onCreate`: every launch, a first run or not, lays the splash out and runs
 * the file repairs off the main thread, and none skips them. Read out of the source, as
 * [com.vscodroid.setup.LaunchRepairWiringTest] reads the repairs themselves, because no JVM test here can build the
 * Activity. What the wiring hands its work to is tested by behaviour in [FirstRunRepairsTest].
 */
class SplashFirstRunRepairsTest {

    private val source = SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt")

    private fun code(declaration: String) = SourceScan.withoutComments(SourceScan.body(source, declaration))

    private val fileRepairs = listOf(
        "setupToolSymlinks", "setupGitCore", "setupGitCaBundle", "setupOpensslConfig", "setupRipgrepVscodeSymlink",
        "setupCopilotAndroidAliases", "repairTruncatedSetupFiles", "createNpmWrappers", "ensureToolchainEnvSourcing",
        "ensurePromptFix", "ensureStartupDirGuard", "createBashEnvFile", "ensurePipConfig", "ensureExecPreload",
        "updateSettingsNativeLibPaths", "ensureProjectsDir", "createStorageSymlinks",
    )

    /** The two that hand their work to threads of their own, and stay on the main thread, last. */
    private val handingRepairs = listOf("repairInstalledToolchains", "reclaimRevokedMirrors")

    private class Launch(val files: String, val rest: String, val path: String)

    /** The two blocks of onCreate, and onCreate with both emptied: what a launch runs, in order. */
    private fun launch(): Launch {
        val create = code("override fun onCreate(")
        for (block in listOf("fun repairFiles(", "fun continueAfterRepairs(")) {
            assertTrue(block in create) {
                "onCreate has no `$block` block, so a launch that will run setup still runs its repairs on the main " +
                    "thread before anything is laid out"
            }
        }
        val files = SourceScan.body(create, "fun repairFiles(")
        val rest = SourceScan.body(create, "fun continueAfterRepairs(")
        return Launch(files, rest, create.replace(files, "{}").replace(rest, "{}"))
    }

    private fun repairNames(block: String) = block.lines().map { it.trim() }.filter { it.startsWith("repair(") }
        .map { line -> (fileRepairs + handingRepairs).firstOrNull { "$it(" in line } ?: line }

    private fun firstRunBranch(path: String): String {
        assertTrue("if (setup.isFirstRun()) {" in path) { "onCreate does not ask, before the repairs, if setup runs" }
        return SourceScan.body(path, "if (setup.isFirstRun()) {")
    }

    @Test
    fun `on a first run the splash is laid out before the first repair`() {
        val launch = launch()
        val branch = firstRunBranch(launch.path)
        val splash = branch.indexOf("showFirstRunSplash(setup)")
        val start = branch.indexOf("startFirstRunRepairs(::repairFiles, ::continueAfterRepairs)")
        assertTrue(splash >= 0 && start > splash) {
            "the first-run branch does not lay the splash out and then start the repairs off the main thread:\n$branch"
        }
        assertTrue("return" in branch.substring(start)) { "the first-run branch goes on to repair on the main thread" }
        val splashSetUp = code("private fun showFirstRunSplash(")
        assertTrue("showSplashLayout()" in splashSetUp) { "the first-run splash lays out nothing" }
        assertFalse(launch.path.lines().any { it.trim().startsWith("repair(") }) {
            "a launch repair runs in onCreate outside the two blocks, so on the main thread before the splash"
        }
    }

    /** What onCreate does after the first-run branch: the statements of every other launch. */
    private fun ordinaryLaunch(launch: Launch): List<String> {
        val branch = firstRunBranch(launch.path)
        val after = launch.path.substring(launch.path.indexOf(branch) + branch.length)
        return after.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    @Test
    fun `every ordinary launch lays the splash out and repairs off the main thread`() {
        val launch = launch()
        assertEquals(
            listOf("showLaunchSplash()", "startLaunchRepairs(::repairFiles, ::continueAfterRepairs)"),
            ordinaryLaunch(launch).take(2),
        ) {
            "an ordinary launch does not lay the splash out and then start its repairs, with nothing ahead of " +
                "them that skips a repair: what they mend can change while the process lives (a certificate " +
                "authority installed in Android Settings, a shell file a person edits)"
        }
        assertFalse("oneLaunchRepairAtATime(" in launch.path) { "a launch still runs its file repairs on the main thread" }
        assertTrue("showSplashLayout()" in code("private fun showLaunchSplash(")) { "the launch splash lays out nothing" }
        val start = code("private fun startLaunchRepairs(")
        assertTrue(
            Regex("""lifecycleScope\.launch \{\s*repairFilesOffMain\(Dispatchers\.IO, files, after\) """ +
                """\{ !isFinishing && !isDestroyed }\s*}""").containsMatchIn(start),
        ) {
            "an ordinary launch's repairs are not run through repairFilesOffMain on Dispatchers.IO, in a scope a " +
                "destroyed activity cancels, with what follows refused to a finishing or destroyed one:\n$start"
        }
        for (call in listOf("showSplashLayout(", "showLaunchSplash(", "showFirstRunSplash(", "setContentView(")) {
            assertFalse(call in launch.files) { "the file repairs lay out a screen: $call" }
        }
    }

    @Test
    fun `nineteen repairs in one order, run by both paths`() {
        val launch = launch()
        assertEquals(fileRepairs, repairNames(launch.files)) { "the file repairs are not these, in this order" }
        val restRepairs = repairNames(launch.rest)
        assertEquals(handingRepairs, restRepairs) { "the two that hand their work on are not the rest's only repairs" }
        val lastRepair = launch.rest.lines().indexOfLast { it.trim().startsWith("repair(") }
        val ordinaryBranch = launch.rest.lines().indexOfFirst { "if (!setup.isFirstRun())" in it }
        assertTrue(lastRepair in 0 until ordinaryBranch) { "the rest does something before its two repairs are done" }
        assertEquals(2, Regex("""::repairFiles\b""").findAll(launch.path).count()) {
            "the file repairs are not handed exactly once to each path"
        }
        val rest = Regex("""::continueAfterRepairs\b|(?<!fun )\bcontinueAfterRepairs\(\)""")
        assertEquals(2, rest.findAll(launch.path).count()) {
            "the rest does not follow exactly once on each of the two paths: a first run and every other launch"
        }
    }

    @Test
    fun `a first run repairs on the I O dispatcher, and nothing follows on a finishing or destroyed activity`() {
        val start = code("private fun startFirstRunRepairs(")
        assertTrue(
            Regex("""lifecycleScope\.launch \{\s*try \{\s*repairFilesOffMain\(Dispatchers\.IO, files, after\) """ +
                """\{ !isFinishing && !isDestroyed }""").containsMatchIn(start),
        ) {
            "the first-run repairs are not run through repairFilesOffMain on Dispatchers.IO, in a scope a destroyed " +
                "activity cancels, with what follows refused to a finishing or destroyed one:\n$start"
        }
    }

    @Test
    fun `a first run holds the process for setup from the start and gives the hold back once`() {
        val start = code("private fun startFirstRunRepairs(")
        val take = start.indexOf("heldThroughRepairs = holdProcessForSetup()")
        assertTrue(take >= 0 && take < start.indexOf("lifecycleScope.launch")) {
            "the setup hold is not taken while the activity is starting, so a user who leaves during the repairs " +
                "leaves setup without it"
        }
        val giveBack = start.substring(start.indexOf("finally"))
        assertTrue("if (heldThroughRepairs) {" in giveBack && "releaseSetupHold()" in giveBack) {
            "a first run that ends without a setup run keeps the hold"
        }
        val run = code("private fun runSetupWithRetry(")
        assertTrue(run.indexOf("val heldEarlier = heldThroughRepairs.also { heldThroughRepairs = false }") in 0 until
            run.indexOf("lifecycleScope.launch")) { "the setup run does not take the first run's hold over first" }
        assertTrue("if (heldEarlier) releaseSetupHold()" in run.substring(run.indexOf("finally"))) {
            "the setup run does not give back the hold it took over"
        }
        assertTrue(code("private fun holdProcessForSetup(").contains("SetupHolds.taken()")) { "a hold is not counted" }
        assertTrue(code("private fun releaseSetupHold(").trim().removePrefix("{").trim()
            .startsWith("if (!SetupHolds.givenBack()) return")) {
            "a release is passed to the service while another holder still needs the hold"
        }
    }

    @Test
    fun `the first-run branch attaches its progress when it runs after onResume`() {
        val rest = launch().rest
        val sink = rest.indexOf("progressSink = sink")
        val attach = Regex(
            """if \(lifecycle\.currentState\.isAtLeast\(Lifecycle\.State\.RESUMED\)\) \{""" +
                """\s*FirstRunSetup\.attachProgress\(sink\)"""
        ).find(rest)?.range?.first ?: -1
        assertTrue(sink >= 0 && attach > sink && attach < rest.indexOf("runSetupWithRetry(")) {
            "the first-run branch runs after onResume and leaves its progress sink unattached: setup reports to nobody"
        }
    }

    @Test
    fun `the splash laid out ahead of the repairs is not laid out a second time`() {
        val show = code("private fun showSplashLayout(")
        val guard = show.indexOf("if (findViewById<View>(R.id.splashRoot) != null) return")
        assertTrue(guard >= 0 && guard < show.indexOf("setContentView(")) {
            "showSplashLayout inflates the splash again over the one put up ahead of the repairs"
        }
    }
}
