package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the three places that behave differently on Android 10 are reached on Android 10
 * and on no other release.
 *
 * `SDK_INT` is 0 on the JVM, so no unit test can run these branches as a device would.
 * `EnvironmentTest` asks `projectsDirFor` about release 29 directly, which says nothing
 * about how the app calls it: a `getProjectsDir` that named another release would leave
 * every case there green. This holds the three call sites to their shape, source level
 * like [FullScreenWiringTest] and with the same ceiling.
 */
class Android10WiringTest {

    /** Comments blanked and white space collapsed, so a reflow does not fail a case. */
    private fun source(path: String): String =
        SourceScan.withoutComments(SourceScan.read(path)).replace(Regex("\\s+"), " ")

    @Test
    fun `the workspace location is asked for the release the device is running`() {
        assertTrue(
            source("src/main/kotlin/com/vscodroid/util/Environment.kt").contains(
                "fun getProjectsDir(context: Context): String = projectsDirFor(context, Build.VERSION.SDK_INT)",
            ),
            "getProjectsDir no longer hands projectsDirFor the release the device runs, so the " +
                "Android 10 answer is never given, or is given everywhere.",
        )
    }

    @Test
    fun `a bug report skips the exit record on Android 10 and reads it everywhere else`() {
        val text = source("src/main/kotlin/com/vscodroid/util/CrashReporter.kt")
        assertTrue(
            text.contains(
                "if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) { " +
                    "sb.appendLine(\"(not recorded on Android 10)\") } else {",
            ),
            "the bug report's recent exits are no longer skipped on Android 10 alone: " +
                "getHistoricalProcessExitReasons is API 30 and raises there, or the record " +
                "is gone from every later release.",
        )
    }

    @Test
    fun `the install source uses the old call on Android 10 and the new one everywhere else`() {
        val text = source("src/main/kotlin/com/vscodroid/setup/ToolchainManager.kt")
        assertTrue(
            text.contains(
                "val installer = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) { " +
                    "@Suppress(\"DEPRECATION\") context.packageManager.getInstallerPackageName(context.packageName) } " +
                    "else { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }",
            ),
            "shouldUseHttpFallback no longer asks the old installer call on Android 10 alone: " +
                "getInstallSourceInfo is API 30 and raises there, or later releases lose the " +
                "installer record that picks Play Asset Delivery.",
        )
    }
}
