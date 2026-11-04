package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files

class StorageAppRootAliasTest {
    @TempDir
    lateinit var root: File

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cleanup accepts lexical and canonical targets under a trusted app root alias`(canonical: Boolean) {
        val realData = File(root, "data/data").apply { mkdirs() }
        val userParent = File(root, "data/user").apply { mkdirs() }
        Files.createSymbolicLink(File(userParent, "0").toPath(), realData.toPath())
        File(realData, "com.example.app").mkdir()
        val contextFiles = File(userParent, "0/com.example.app/files").apply { mkdir() }
        val owned = File(contextFiles, "usr/lib/jvm/current").apply { mkdirs() }
        File(owned, "java").writeText("owned toolchain bytes")

        val removed = StorageManager.deleteRecursive(if (canonical) owned.canonicalFile else owned, contextFiles.parentFile)

        assertFalse(owned.exists(), "the owned path was rejected against its context alias")
        assertEquals(21L, removed)
    }
}
