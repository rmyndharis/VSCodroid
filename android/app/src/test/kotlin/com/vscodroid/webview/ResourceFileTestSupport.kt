package com.vscodroid.webview

import io.mockk.every
import io.mockk.mockkStatic
import java.io.File
import java.io.FileInputStream

internal fun mockResourceFileOpening() {
    // Android descriptor handling is exercised by ResourceFileOnDeviceTest.
    mockkStatic(::openResourceFile)
    every { openResourceFile(any(), any()) } answers { FileInputStream(firstArg<File>()) }
}
