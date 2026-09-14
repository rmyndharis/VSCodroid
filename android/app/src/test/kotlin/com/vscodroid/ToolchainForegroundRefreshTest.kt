package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the exec table is rebuilt when the editor comes back to the foreground.
 *
 * A command `pip install` writes runs only once the table has a row for it, and the
 * launch pass in SplashActivity is not reached when Back or Home and the launcher
 * icon bring the singleTask editor back. Source-level, because the property is a call
 * made from an Activity callback, which no JVM test here can build.
 */
class ToolchainForegroundRefreshTest {

    private val source = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))

    @Test
    fun `returning to the editor rebuilds the exec table off the main thread`() {
        assertTrue(
            SourceScan.body(source, "override fun onStart()").contains("refreshToolchainCommands()"),
            "onStart no longer refreshes the toolchain commands, so a command pip installed " +
                "keeps failing with `bad interpreter` after the user leaves and comes back",
        )
        val refresh = SourceScan.body(source, "private fun refreshToolchainCommands()")
        val launch = refresh.indexOf("Dispatchers.IO")
        val call = refresh.indexOf("regenerateDerivedFiles()")
        assertTrue(
            launch >= 0 && call > launch,
            "the exec table is rebuilt on the main thread, which lists usr/bin and writes " +
                "two files every time the editor comes to the front",
        )
    }
}
