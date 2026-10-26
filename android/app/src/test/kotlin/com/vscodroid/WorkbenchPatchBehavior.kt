package com.vscodroid

import com.vscodroid.setup.finishTestProcess
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File

internal object WorkbenchPatchBehavior {
    fun run(section: String) {
        val result = finishTestProcess(ProcessBuilder("node", "scripts/test-workbench-layout.js", section)
            .directory(File("../.."))
            .redirectErrorStream(true)
            .start(), timeoutMillis = 20_000)
        assertEquals(0, result.first, result.second)
    }
}
