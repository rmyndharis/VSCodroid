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
 * fixpoint, a statement is read across the lines a formatter wrapped it onto up
 * to the cap that reader states, and the whole file is in scope, so this case is
 * about device folders reaching the log rather than about one line. The one limit
 * worth naming here rather than only there, because the control below is written
 * so as not to depend on it: a declaration is read one line at a time, so a `val`
 * whose value sits on the next line carries no taint.
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
        // Asked of the reader rather than of the text, and the difference is what
        // this control is for. Searching a line for `Logger.` and `getMirrorDir(`
        // together answers a question about formatting: wrap that one statement
        // onto four lines and it fails while nothing about the log has changed,
        // and break the seed that makes a tree URI a source and it passes while
        // the case above has gone blind and stopped guarding anything. Reading it
        // through `reducedLogs` ties this control to the same machinery, so the
        // two fail together or not at all.
        //
        // File-scoped, not body-scoped, because that is the scope the reader
        // works in. MainActivity has exactly one reduced log statement today, the
        // one at the top of openSafFolder, and `the body being checked was
        // actually found` is what still says openSafFolder is the right site.
        val reduced = LogTaint.reducedLogs(source.lines())

        assertTrue(reduced.isNotEmpty()) {
            "nothing in openSafFolder says which folder is being opened, so a bug report " +
                "can no longer be lined up with one. Redaction here means naming the " +
                "folder by the digest the rest of the app already calls it by, not " +
                "deleting the statement. Found:\n" +
                opened.lines().filter { it.contains("Logger.") }
                    .joinToString("\n") { "  ${it.trim()}" } +
                "\nReduced statements in the whole file: " + reduced
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
    fun `the sync call is a reduction, so the mirror it returns is not the folder that made it`() {
        // The false accusation a reader that taints by proximity makes here, and
        // the reason this file uses LogTaint rather than the scan in
        // `PageSuppliedLoggingTest`: that one has no notion of a reduction and
        // reads a right-hand side to the end of the statement, so it taints
        // `mirrorDir` from the sync call and reddens the safe statement below.
        // `File.name` under `saf-mirrors` IS the digest; a reader that cannot
        // print it has nothing left to say and gets narrowed back by whoever it
        // stops.
        //
        // Both spellings, because the verdict has to come from the rule and not
        // from where a formatter put the line break. Read one line at a time, the
        // wrapped form is clean for a reason that has nothing to do with the sync
        // call: the declaration ends before it. `syncToLocal` being a reduction
        // is what makes the one-line form clean too, and deleting that entry is
        // what this case exists to fail on.
        val wrapped = openSource(
            """        val mirrorDir = withContext(Dispatchers.IO) {""",
            """            safManager.syncToLocal(uri) { done, total -> }""",
            """        }""",
            """        Logger.i(tag, "Synced " + mirrorDir.name)""",
        )
        val oneLine = openSource(
            """        val mirrorDir = safManager.syncToLocal(uri) { done, total -> }""",
            """        Logger.i(tag, "Synced " + mirrorDir.name)""",
        )

        assertEquals(emptyList<String>(), LogTaint.leaks(wrapped))
        assertEquals(
            emptyList<String>(), LogTaint.leaks(oneLine),
            "the verdict on a mirror must come from the reduction rule, not from where " +
                "the formatter put the line break: a directory named after the digest of " +
                "a tree URI carries no part of the path that URI spells out, however the " +
                "call that returned it was laid out",
        )
    }
}
