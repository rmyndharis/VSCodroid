package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ToolchainProgressWiringTest {
    private fun source(path: String): String {
        val file = File("src/main/kotlin/com/vscodroid/$path")
        assertTrue(file.isFile, "missing source: ${file.absolutePath}")
        return file.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
    }

    @Test
    fun `the Activity carries HTTP identity through its posted report and poll`() {
        val activity = source("ToolchainActivity.kt")
        assertTrue(activity.contains("manager.onStateChange = { packName, status, percent, why, transferId ->"))
        assertTrue(activity.contains("live.showPackState(packName, status, percent, why, asked, transferId)"))
        assertTrue(activity.contains("adapter.updateState(packName, status, percent, transferId)"))
        assertTrue(activity.contains("val downloading = ToolchainManager.downloadProgress()"))
        assertTrue(activity.contains("adapter.setDownloading(downloading)"))
        val start = activity.indexOf("private fun showPackState(")
        val refresh = activity.indexOf("if (transferId == null) refreshDownloads()", start)
        val update = activity.indexOf("adapter.updateState(packName, status, percent, transferId)", start)
        assertTrue(start >= 0 && refresh > start && update > refresh)
    }

    @Test
    fun `the adapter forwards HTTP identity to the card model`() {
        val adapter = source("setup/ToolchainPickerAdapter.kt")
        assertTrue(adapter.contains("cards.updateState(packName, status, percent, transferId)"))
        assertTrue(adapter.contains("cards.setDownloading(percentByPack)"))
    }

    @Test
    fun `shared copy outcomes retain the HTTP identity`() {
        val manager = source("setup/ToolchainManager.kt")
        val start = manager.indexOf("private fun installFromDirectoryHoldingPack(")
        assertTrue(start >= 0)
        val end = manager.indexOf("\n    private fun ", start + 1)
        assertTrue(end > start)
        val copy = manager.substring(start, end)
        val failures = copy.lineSequence().filter { "fail(packName," in it }.toList()
        assertTrue(failures.isNotEmpty())
        assertTrue(failures.all { it.trim().endsWith(", transferId)") })
        assertTrue(copy.contains("report(packName, AssetPackStatus.COMPLETED, 100, transferId)"))
    }
}
