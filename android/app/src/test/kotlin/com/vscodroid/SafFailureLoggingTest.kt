package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What `MainActivity` writes to the log when a device folder or a download's destination fails.
 *
 * `Logger.w` and `Logger.e` are not gated on a debuggable build, so every line ships. The platform's exceptions
 * for these cases quote what they were asked about: `takePersistableUriPermission` and the document providers name
 * the tree URI (`.../tree/primary%3ADocuments%2F<folder>`, the user's own directory), a `FileNotFoundException`
 * names a device path, and `DocumentsContract.deleteDocument` names the document the page chose to save to. Passing
 * the exception to the logger writes all of that, with its stack, where anything holding `READ_LOGS` and any bug
 * report can read it. So these are logged by class, as `SafStorageManager.persistPermission` already does.
 *
 * [LogTaint] follows names, and a caught exception called `e` carries no name it recognises, so this reads the
 * statements themselves: the three that handle a folder or a destination, and every other statement in the file
 * that passes an exception on, which has to be one of the few below that names no user data.
 */
class SafFailureLoggingTest {

    private val source = SourceScan.readAll(SourceScan.MAIN_ACTIVITY_FILES)

    private val classOnly = listOf(
        "SAF permission revoked during sync",
        "SAF sync failed",
        "Could not remove the unfinished download",
    )

    /** The statement that logs [message], from `Logger.` to the end of its line. */
    private fun statement(message: String): String {
        val at = source.indexOf("\"$message")
        check(at >= 0) { "`$message` is not logged in MainActivity any more; point this case at the new line" }
        return source.substring(source.lastIndexOf("Logger.", at), source.indexOf('\n', at))
    }

    @Test
    fun `a folder or destination failure is logged by its class`() {
        for (message in classOnly) {
            val line = statement(message)
            assertTrue("e.javaClass.simpleName" in line) { "`$message` does not name the exception's class: $line" }
            assertFalse(Regex(""",\s*e\)\s*$""").containsMatchIn(line)) {
                "`$message` hands the exception to the logger, with the folder or document its message names: $line"
            }
        }
    }

    /**
     * The statements that still pass an exception on. Each is a picker or a launch repair whose exception names an
     * intent or an app path, nothing the user chose. A new one has to be added here on purpose.
     */
    @Test
    fun `the only statements that pass an exception on are these`() {
        val passes = Regex("""Logger\.[ew]\(\s*tag,\s*"([^"]*)"\s*,\s*e\s*\)""").findAll(source).map { it.groupValues[1] }.toSet()

        assertEquals(
            setOf(
                "No document creator on this device",
                "The document creator is no longer registered",
                "Launch-time refresh of the exec preload failed",
                "Resume-time refresh of the exec preload failed",
                "No document tree picker on this device",
                "The folder picker is no longer registered",
                "No document picker on this device",
            ),
            passes,
        ) {
            "a statement in MainActivity passes a caught exception to the logger, which writes its message and stack " +
                "into a log that ships: log the class instead, unless the exception can name nothing the user chose"
        }
    }

    /**
     * The coordinator that saves a download reads the document the person chose, and the exceptions of that
     * provider name it exactly as the ones above do: a `SecurityException` or a `FileNotFoundException` for a
     * grant that was revoked between the picker and the open. Nothing in it passes an exception, of any name, to a
     * level that ships.
     */
    @Test
    fun `the download coordinator passes no exception to a shipped log level`() {
        val downloads = SourceScan.read("src/main/kotlin/com/vscodroid/webview/DownloadCoordinator.kt")
        val passes = Regex("""Logger\.[ew]\(\s*tag,\s*"([^"]*)"\s*,\s*\w+\s*\)""")
            .findAll(downloads).map { it.groupValues[1] }.toList()

        assertEquals(emptyList<String>(), passes) {
            "DownloadCoordinator passes a caught exception to the logger, which writes its message and stack, " +
                "and with them the document the person chose, into a log that ships: log the class instead: $passes"
        }
    }
}
