package com.vscodroid.bridge

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That nothing a page hands the bridge reaches logcat in the clear.
 *
 * `Logger.i`, `Logger.w` and `Logger.e` are not gated on a debuggable build, so
 * they ship, and logcat is readable by anything holding `READ_LOGS`. The page on
 * the other side of these methods is the workbench, which holds the connection
 * token, so a string it passes can carry one. `logToNative` states that reasoning
 * and redacts; this makes it true of every method rather than of the one whose
 * author thought of it.
 *
 * Written as a predicate over the parameters, not as a list of known sites. Four
 * sites were already redacted by hand and three more were not, and the three were
 * missed because a reader sweeping for `url` and `uri` does not match `name` or
 * `uriString`. A list would have been written from the same sweep and inherited
 * the same blind spot.
 */
class PageSuppliedLoggingTest {

    private val bridge = File("src/main/kotlin/com/vscodroid/bridge/AndroidBridge.kt")

    /**
     * One chunk per `@JavascriptInterface`, from the annotation to the next one.
     *
     * A superset of the method body rather than the body exactly: a private helper
     * declared between two bridge methods falls into the earlier chunk. That is the
     * safe direction. Such a helper is reached from the bridge and holds the same
     * values, so examining it too is not a false alarm, while missing it would be a
     * real one.
     */
    private fun bridgeMethods(): List<String> {
        assertTrue(
            bridge.isFile,
            "${bridge.absolutePath} is missing; this test would otherwise pass by reading nothing",
        )
        return bridge.readText().split("@JavascriptInterface").drop(1)
    }

    @Test
    fun `every page supplied value reaching a log call is redacted`() {
        val methods = bridgeMethods()
        // The control for the split above. check-bridge-api-spec.py counts 31 bridge
        // methods, so a split that returns a handful means the annotation moved or
        // the file did, and every assertion below would then pass by examining almost
        // nothing.
        assertTrue(
            methods.size >= 25,
            "only ${methods.size} @JavascriptInterface methods were found; the scan is " +
                "looking at the wrong thing, and passing here would mean nothing",
        )

        val offenders = mutableListOf<String>()
        var examined = 0
        for (chunk in methods) {
            val signature = Regex("""fun\s+(\w+)\s*\(([^)]*)\)""").find(chunk) ?: continue
            val name = signature.groupValues[1]
            // authToken is the session token the page already holds, and it is never
            // logged; it is excluded so its absence cannot be mistaken for coverage.
            val params = signature.groupValues[2]
                .split(",")
                .mapNotNull { it.substringBefore(":").trim().ifEmpty { null } }
                .filter { it != "authToken" && it.matches(Regex("""\w+""")) }
            if (params.isEmpty()) continue

            // A parameter renamed on the way to the log line is the case that made
            // this necessary: `openRecentFolder` logged `uri`, a local built by
            // `Uri.parse(uriString)`, so a search for the parameter name found
            // nothing. Locals assigned from a parameter carry its taint.
            val tainted = params.toMutableSet()
            for (m in Regex("""val\s+(\w+)\s*=\s*([^\n]+)""").findAll(chunk)) {
                if (tainted.any { Regex("""\b$it\b""").containsMatchIn(m.groupValues[2]) }) {
                    tainted += m.groupValues[1]
                }
            }

            for (call in Regex("""Logger\.[diwe]\(([^\n]*)""").findAll(chunk)) {
                val text = call.groupValues[1]
                examined++
                for (value in tainted) {
                    val interpolated = Regex("""\$\{?$value\b""").containsMatchIn(text)
                    val redacted = Regex("""redactToken\(\s*$value\b""").containsMatchIn(text)
                    if (interpolated && !redacted) {
                        offenders += "$name logs $value unredacted: ${text.trim()}"
                    }
                }
            }
        }

        // The second control. If no bridge method logs any of its own values, the
        // loop above proves nothing, and a refactor that moved every log line out
        // would leave this test green while removing everything it watches.
        assertTrue(
            examined > 0,
            "no log call inside any bridge method was examined, so this test is vacuous",
        )
        assertTrue(
            offenders.isEmpty(),
            "a value the page supplied reaches a shipping log line in the clear:\n" +
                offenders.joinToString("\n") { "  $it" },
        )
    }

    /**
     * The one site a predicate over `AndroidBridge` cannot reach.
     *
     * `DownloadCoordinator` takes the file name from `onDownloadNamed`, which the
     * page calls, and the failure detail from `onComplete(requestId, error)`, whose
     * error string the page also writes. Both are renamed twice on the way to
     * `MainActivity`'s `DownloadHost.report`, so no scan anchored on a bridge
     * parameter name can follow them, which is exactly why this line stayed in the
     * clear while its siblings were fixed.
     */
    @Test
    fun `the download report redacts both values it prints`() {
        val activity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")
        assertTrue(
            activity.isFile,
            "${activity.absolutePath} is missing; this test would otherwise pass by reading nothing",
        )
        val report = activity.readText()
            .substringAfter("override fun report(outcome: DownloadOutcome", "")
        assertTrue(
            report.isNotEmpty(),
            "DownloadHost.report is gone from MainActivity; if it moved, point this at its " +
                "new home rather than deleting the check",
        )
        val line = Regex("""(?m)^\s*Logger\.w\(tag, "Download of.*""").find(report)
        assertTrue(line != null, "the download failure is no longer logged at all")
        val text = line!!.value
        assertTrue(
            Regex("""redactToken\(\s*fileName\b""").containsMatchIn(text),
            "the page-supplied file name reaches logcat in the clear: ${text.trim()}",
        )
        assertTrue(
            Regex("""redactToken\(\s*detail\b""").containsMatchIn(text),
            "the page-supplied failure detail reaches logcat in the clear: ${text.trim()}",
        )
    }
}
