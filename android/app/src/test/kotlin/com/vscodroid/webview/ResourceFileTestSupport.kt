package com.vscodroid.webview

import io.mockk.every
import io.mockk.mockkStatic
import java.io.File
import java.io.FileInputStream

/** Rules that publish nothing and refuse nothing, for a client built for something else. */
internal fun noResourceRules() = ResourceRules { emptyList<String>() to emptyList() }

internal fun mockResourceFileOpening() {
    // Android descriptor handling is exercised by ResourceFileOnDeviceTest.
    mockkStatic(::openResourceFile)
    every { openResourceFile(any(), any()) } answers { FileInputStream(firstArg<File>()) }
}
