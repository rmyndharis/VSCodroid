package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ToolchainPayloadNameTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `copying keeps every payload file when sibling names resemble temporary files`() {
        val src = File(root, "source").apply { mkdir() }
        val dest = File(root, "destination").apply { mkdir() }
        val backup = File(src, "tool.tmp~").apply { writeText("payload backup") }
        val tool = File(src, "tool").apply { writeText("payload tool") }

        copyDirectoryTree(src, dest) { directory ->
            if (directory == src) arrayOf(backup, tool) else directory.listFiles()
        }

        assertEquals("payload tool", File(dest, "tool").readText())
        assertTrue(File(dest, "tool.tmp~").isFile, "the second copy erased the first payload file")
        assertEquals("payload backup", File(dest, "tool.tmp~").readText())
        assertEquals(setOf("tool", "tool.tmp~"), dest.list()!!.toSet())
    }
}
