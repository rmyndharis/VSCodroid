package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import java.util.concurrent.TimeUnit

internal object WorkbenchPatchBehavior {
    fun run(section: String) {
        val process = ProcessBuilder("node", "scripts/test-workbench-layout.js", section)
            .directory(File("../.."))
            .redirectErrorStream(true)
            .start()
        check(process.waitFor(20, TimeUnit.SECONDS)) { "Workbench $section behavior timed out" }
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
    }
}
