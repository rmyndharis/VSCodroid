package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommentedManagedSettingsTest {
    private val shell = "/data/user/0/com.vscodroid/files/usr/bin/bash"
    private val git = "/data/app/current/lib/arm64/libgit.so"
    private val wrapper = "/data/user/0/com.vscodroid/files/usr/bin/node"

    private fun activeLine(text: String, key: String, value: String): Boolean =
        Regex("(?m)^\\s*\"${Regex.escape(key)}\"\\s*:\\s*${Regex.escape(value)}(?=,|\\s|$)")
            .containsMatchIn(text)

    @Test
    fun `commented wrapper and signature examples do not suppress active settings`() {
        for (comment in listOf(
            "// \"claudeCode.claudeProcessWrapper\": \"/custom/old-wrapper\",\n" +
                "// \"extensions.verifySignature\": true,",
            "/* \"claudeCode.claudeProcessWrapper\": \"/custom/old-wrapper\", " +
                "\"extensions.verifySignature\": true */",
        )) {
            val before = """{
$comment
    "python.locator": "js",
    "python.useEnvironmentsExtension": false
}"""
            val after = refreshManagedPaths(before, shell, git, wrapper) ?: before
            assertTrue(after.contains(comment), "the commented examples changed")
            assertTrue(activeLine(after, "claudeCode.claudeProcessWrapper", "\"$wrapper\""))
            assertTrue(activeLine(after, "extensions.verifySignature", "false"))
        }
    }

    @Test
    fun `commented Python and layout examples are preserved while live pins are inserted`() {
        val comment = "/* \"python.locator\": \"native\", " +
            "\"python.useEnvironmentsExtension\": true, \"vscodroid.layout.compactScreen\": false */"
        val before = """{
$comment
    "claudeCode.claudeProcessWrapper": "$wrapper",
    "extensions.verifySignature": false
}"""
        val paths = refreshManagedPaths(before, shell, git, wrapper) ?: before
        val after = settingsWithLayoutDefaults(paths, compactScreen = true) ?: paths
        assertTrue(after.contains(comment), "the commented examples changed")
        assertTrue(activeLine(after, "python.locator", "\"js\""))
        assertTrue(activeLine(after, "python.useEnvironmentsExtension", "false"))
        assertTrue(activeLine(after, "vscodroid.layout.compactScreen", "true"))
    }

    @Test
    fun `managed values change without replacing comments in their prefixes`() {
        val parked = "/* \"git.path\": \"/data/app/parked/libgit.so\", " +
            "\"claudeCode.claudeProcessWrapper\": \"/data/app/parked/libldmusl.so\" */"
        val before = """{
$parked
    "git.path": /* git prefix */ "/data/app/old/libgit.so",
    "terminal.integrated.profiles.linux": { /* profiles */ "bash": {
        "path": /* shell prefix */ "/data/app/old/libbash.so",
        "args": /* args prefix */ ["-i"]
    } },
    "terminal.integrated.shellIntegration.enabled": /* integration prefix */ false,
    "claudeCode.claudeProcessWrapper": /* wrapper prefix */ "/data/app/old/libldmusl.so",
    "extensions.verifySignature": false,
    "python.locator": /* locator prefix */ "native",
    "python.useEnvironmentsExtension": /* environment prefix */ true,
    "vscodroid.layout.compactScreen": /* layout prefix */ false
}"""

        val paths = requireNotNull(refreshManagedPaths(before, shell, git, wrapper))
        val after = requireNotNull(settingsWithLayoutDefaults(paths, compactScreen = true))

        assertTrue(after.contains(parked))
        for ((prefix, value) in listOf(
            "git" to "\"$git\"", "shell" to "\"$shell\"", "args" to "[]",
            "integration" to "true", "wrapper" to "\"$wrapper\"",
            "locator" to "\"js\"", "environment" to "false", "layout" to "true",
        )) {
            assertTrue(after.contains("/* $prefix prefix */ $value"), prefix)
        }
        assertEquals(1, Regex("/\\* profiles \\*/").findAll(after).count())
    }

    @Test
    fun `active custom paths stay unchanged beside commented managed examples`() {
        val before = """{
    // "git.path": "/data/app/old/libgit.so",
    /* "claudeCode.claudeProcessWrapper": "/data/app/old/libldmusl.so" */
    "git.path": "/custom/git",
    "terminal.integrated.profiles.linux": { "bash": { "path": "/custom/bash", "args": ["-i"] } },
    "terminal.integrated.shellIntegration.enabled": false,
    "claudeCode.claudeProcessWrapper": "/custom/wrapper",
    "extensions.verifySignature": true,
    "python.locator": "js",
    "python.useEnvironmentsExtension": false
}"""

        assertNull(refreshManagedPaths(before, shell, git, wrapper))
    }
}
