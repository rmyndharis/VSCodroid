package com.vscodroid.webview

import com.vscodroid.LogTaint
import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That opening a device folder does not spell the user's directory into logcat.
 *
 * A SAF tree URI is the path itself, percent-encoded:
 * `content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FClientProject`
 * names the employer, the project and the folder layout of whoever is holding
 * the phone. `Logger.i` is not gated on a debuggable build, so the line ships,
 * and logcat is readable by anything holding `READ_LOGS` and by whoever a device
 * bug report is sent to.
 *
 * The redaction one frame down already exists: `SafStorageManager` names the same
 * folder by the digest its mirror is called after, and `SafFolderPathLoggingTest`
 * holds it there. That guard drives `SafStorageManager` and `SafSyncEngine`
 * directly and never opens `MainActivity.kt`, so the caller one frame up was
 * outside the population it covers and went on printing the value in full.
 *
 * Read through `LogTaint`, and that is the substance of this file rather than a
 * detail of it. The first version of this guard searched the text of
 * `openSafFolder` for `${'$'}uri`, which is the one spelling the leak had happened to
 * be written in. A concatenation, a `uri.toString()`, an intermediate local, a
 * statement the formatter wrapped and a rename of the parameter all walk past a
 * search like that, and so does the same leak in any of the four other methods
 * here that are handed a `Uri`. `LogTaint` follows the value instead: every name
 * a declaration types as `Uri` is a source, taint runs through declarations to a
 * fixpoint, statements are read whole, and the whole file is in scope, so this
 * case is about device folders reaching the log rather than about one line.
 *
 * Source reading, for the reason `DownloadListenerWiringTest` gives: there is no
 * seam. `openSafFolder` shows a dialog, takes a permission and launches a
 * coroutine, none of which a plain JVM test has.
 *
 * What this deliberately does not certify: the `Logger.e` calls in the same body
 * hand over a `SecurityException`, and nothing on this side can read the message
 * inside one. That the throwables reaching them are already redacted is held by
 * `SafFolderPathLoggingTest`, which drives the frames that raise them.
 */
class SafFolderLogCallSiteTest {

    private val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")

    private val opened by lazy {
        SourceScan.withoutComments(SourceScan.body(source, "private fun openSafFolder("))
    }

    @Test
    fun `the body being checked was actually found`() {
        assertTrue(opened.isNotBlank() && opened.contains("persistPermission")) {
            "openSafFolder no longer looks like the function these cases were written " +
                "against, so the ones reading its body are searching an empty string " +
                "and would pass over anything. Point them at wherever a device folder " +
                "is opened now."
        }
    }

    @Test
    fun `no log statement prints a device folder's tree URI`() {
        assertEquals(
            emptyList<String>(), LogTaint.leaks(source.lines()),
            "a SAF tree URI is the user's own directory written out, and these lines " +
                "ship: Logger.i is not gated on a debuggable build. Name the folder by " +
                "its mirror digest, which is what the statement at the top of " +
                "openSafFolder already does.",
        )
    }

    @Test
    fun `the line still names the folder by its mirror`() {
        val named = opened.lines()
            .any { it.contains("Logger.") && it.contains("getMirrorDir(") }

        assertTrue(named) {
            "nothing in openSafFolder says which folder is being opened, so a bug report " +
                "can no longer be lined up with one. Redaction here means naming the " +
                "folder by the digest the rest of the app already calls it by, not " +
                "deleting the statement. Found:\n" +
                opened.lines().filter { it.contains("Logger.") }
                    .joinToString("\n") { "  ${it.trim()}" }
        }
    }

    // --- The reader itself, driven against fixed snippets. ------------------
    //
    // The case above points LogTaint at one file, where the only measurable
    // outcome is "found nothing", and a reader that always finds nothing passes
    // it. `NavigationTokenLoggingTest` pins the behaviour the tokened URL needs;
    // these pin the two parts a device folder needs and it does not.

    /** A stand-in for `openSafFolder`, with the log statement swapped in. */
    private fun openSource(vararg body: String): List<String> =
        listOf("    private fun openSafFolder(uri: Uri, navigate: Boolean) {") +
            body + listOf("    }")

    @Test
    fun `a URI handed in as a parameter is a source`() {
        // The seed the tokened-URL cases never exercise: every one of them starts
        // from a `val` initialiser, and a device folder never has one. Drop the
        // parameter pass and this is the only case that notices.
        assertTrue(
            LogTaint.leaks(
                openSource("""        Logger.i(tag, "Opening the device folder " + uri)"""),
            ).isNotEmpty(),
            "a Uri arrives as a parameter, not as a declaration, and the reader did " +
                "not treat it as a source",
        )
    }

    @Test
    fun `naming the folder by its mirror answers for it, printing it does not`() {
        // Both directions of the same call, because only one of them is a
        // reduction. Widen the span this takes out and the leak sitting after it
        // on the same line is swallowed with it, which is the failure this pins.
        val reduced = openSource(
            """        Logger.i(tag, "Opening ${'$'}{safManager.getMirrorDir(uri).name}")""",
        )
        val beside = openSource(
            """        Logger.i(tag, "Opening ${'$'}{safManager.getMirrorDir(uri).name} " + uri)""",
        )

        assertEquals(emptyList<String>(), LogTaint.leaks(reduced))
        assertTrue(
            LogTaint.leaks(beside).isNotEmpty(),
            "a folder named by its digest cannot answer for the tree URI printed " +
                "beside it on the same line",
        )
        assertEquals(
            emptyList<String>(), LogTaint.redactedLogs(reduced),
            "naming a folder by its mirror is not redaction and must not answer the " +
                "control that asks whether the tokened URL still reaches the log; " +
                "letting it count there is how that control goes on passing after the " +
                "statement it was written about has gone",
        )
    }

    @Test
    fun `a mirror named on a line of its own is not the folder that made it`() {
        // The false accusation a reader that taints by proximity makes here, and
        // the reason this file uses LogTaint rather than the scan in
        // `PageSuppliedLoggingTest`: that one taints `mirrorDir` from the sync
        // call and reddens the safe statement below. `File.name` under
        // `saf-mirrors` IS the digest; a reader that cannot print it has nothing
        // left to say and gets narrowed back by whoever it stops.
        val safe = openSource(
            """        val mirrorDir = withContext(Dispatchers.IO) {""",
            """            safManager.syncToLocal(uri) { done, total -> }""",
            """        }""",
            """        Logger.i(tag, "Synced " + mirrorDir.name)""",
        )

        assertEquals(emptyList<String>(), LogTaint.leaks(safe))
    }
}
