package com.vscodroid.service

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The server's `File not found: <folder>/.vscode/settings.json` line stays out of
 * `server.log`.
 *
 * The page asks for that file on every load of a folder, before it paints, and the
 * usual answer is that there is none. The server logs the miss as an error line
 * carrying the absolute path, so the last 200 lines a bug report quotes were
 * mostly the name of the folder the user had open, over and over, and the gave-up
 * page showed them too.
 *
 * NEGATIVE CONTROL: deleting the guard in `startOutputReader` reddens the second
 * case; widening the predicate to every `File not found` reddens the third.
 */
class SettingsProbeMissTest {

    @Test
    fun `the miss of a folder's settings file is the line that is dropped`() {
        assertTrue(isSettingsProbeMiss(
            "File not found: /data/user/0/com.vscodroid/files/saf-mirrors/by-name/8e440ff38c8e/Notes/.vscode/settings.json"
        ))
        assertTrue(isSettingsProbeMiss("File not found: /data/user/0/com.vscodroid/files/projects/.vscode/settings.json"))
    }

    @Test
    fun `the reader consults it before the line is logged`() {
        val reader = SourceScan.withoutComments(
            SourceScan.body(
                SourceScan.read("src/main/kotlin/com/vscodroid/service/ProcessManager.kt"),
                "private fun startOutputReader(",
            )
        )
        val guard = reader.indexOf("isSettingsProbeMiss(line)")
        val logged = reader.indexOf("serverLog.append(line)")

        assertTrue(guard >= 0) { "the output reader no longer drops the settings probe's miss" }
        assertTrue(logged > guard) { "the line is appended before the guard is asked" }
    }

    @Test
    fun `no other line is dropped`() {
        // A missing file the server could not serve is still the server saying
        // something, and so is anything that merely mentions the settings file.
        assertFalse(isSettingsProbeMiss("File not found: /data/user/0/com.vscodroid/files/home/notes.txt"))
        assertFalse(isSettingsProbeMiss("File not found: /p/.vscode/settings.json.bak"))
        assertFalse(isSettingsProbeMiss("File not found: /p/.vscode/tasks.json"))
        assertFalse(isSettingsProbeMiss("Error: ENOENT reading /p/.vscode/settings.json"))
        assertFalse(isSettingsProbeMiss("[17:02:11] File not found: /p/.vscode/settings.json"))
        assertFalse(isSettingsProbeMiss(""))
    }
}
