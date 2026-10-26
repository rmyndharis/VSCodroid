package com.vscodroid.setup

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.downloadRefreshFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolchainCardDownloadOrderTest {
    private val pack = "toolchain_ruby"
    private fun state() = ToolchainCardState(ToolchainCardMode.MANAGER)
    private fun snapshot(id: Long, percent: Int = 42) =
        mapOf(pack to ToolchainDownloadProgress(id, percent))

    @Test
    fun `a new transfer replaces earlier terminal reports`() {
        for (status in listOf(
            AssetPackStatus.FAILED, AssetPackStatus.CANCELED, AssetPackStatus.COMPLETED,
            AssetPackStatus.UNKNOWN, AssetPackStatus.NOT_INSTALLED,
        )) {
            for (installed in listOf(false, true)) {
                val state = state()
                state.updateState(pack, status, 0, 1)
                state.setInstalled(if (installed) listOf("ruby") else emptyList())

                state.setDownloading(snapshot(2))

                assertEquals(ToolchainAction.CANCEL, state.card(pack).action)
                assertEquals(42, state.card(pack).progressPercent)
            }
        }
    }

    @Test
    fun `a current terminal report defeats a delayed snapshot of that transfer`() {
        for (status in listOf(AssetPackStatus.COMPLETED, AssetPackStatus.CANCELED, AssetPackStatus.FAILED)) {
            val state = state()
            state.setDownloading(snapshot(1))
            state.updateState(pack, status, 100, 1)

            state.setDownloading(snapshot(1, 77))

            assertNull(state.card(pack).progressPercent)
            assertFalse(state.card(pack).action == ToolchainAction.CANCEL)
        }
    }

    @Test
    fun `same transfer reports still replace its snapshot percentage`() {
        val state = state()
        state.setDownloading(snapshot(1))
        state.updateState(pack, AssetPackStatus.DOWNLOADING, 77, 1)

        state.setDownloading(snapshot(1, 42))

        assertEquals(77, state.card(pack).progressPercent)
    }

    @Test
    fun `an older callback cannot replace a newer transfer snapshot`() {
        val state = state()
        state.setDownloading(snapshot(2))

        assertEquals(-1, state.updateState(pack, AssetPackStatus.FAILED, 0, 1))

        assertEquals(ToolchainAction.CANCEL, state.card(pack).action)
    }

    @Test
    fun `an older snapshot cannot revive after a newer transfer completed`() {
        val state = state()
        state.updateState(pack, AssetPackStatus.COMPLETED, 100, 2)

        state.setDownloading(snapshot(1))

        assertEquals(ToolchainAction.REMOVE, state.card(pack).action)
        assertNull(state.card(pack).progressPercent)
    }

    @Test
    fun `an ended external transfer does not restore an obsolete failure`() {
        val state = state()
        state.updateState(pack, AssetPackStatus.FAILED, 0, 1)
        state.setDownloading(snapshot(2))

        state.setDownloading(emptyMap())
        state.setInstalled(listOf("ruby"))

        assertEquals(ToolchainAction.REMOVE, state.card(pack).action)
    }

    @Test
    fun `a decline cannot end a transfer owned by another manager`() {
        val state = state()
        state.setDownloading(snapshot(1))

        state.updateState(pack, AssetPackStatus.UNKNOWN, 0)

        assertEquals(ToolchainAction.CANCEL, state.card(pack).action)
    }

    @Test
    fun `an unscoped report cannot revive a terminal HTTP transfer through its seed`() {
        for (status in listOf(
            AssetPackStatus.COMPLETED, AssetPackStatus.CANCELED, AssetPackStatus.FAILED,
        )) {
            val state = state()
            state.setDownloading(snapshot(1))
            state.updateState(pack, status, 100, 1)
            val settled = state.card(pack)

            assertEquals(-1, state.updateState(pack, AssetPackStatus.UNKNOWN, 0))

            assertEquals(settled, state.card(pack))
            assertNull(state.card(pack).progressPercent)
        }
    }

    @Test
    fun `an unscoped report preserves the current HTTP percentage`() {
        val state = state()
        state.setDownloading(snapshot(1))
        state.updateState(pack, AssetPackStatus.DOWNLOADING, 77, 1)

        state.updateState(pack, AssetPackStatus.PENDING, 0)

        assertEquals(77, state.card(pack).progressPercent)
        assertEquals(ToolchainAction.CANCEL, state.card(pack).action)
    }

    @Test
    fun `a declined removal still clears its wait while HTTP owns the card`() {
        val state = state()
        state.setInstalled(listOf("ruby"))
        state.setDownloading(snapshot(1))
        state.setRemoving(pack)

        state.updateState(pack, AssetPackStatus.UNKNOWN, 0)
        state.setDownloading(emptyMap())

        assertEquals(ToolchainAction.REMOVE, state.card(pack).action)
    }

    @Test
    fun `a settled Play query cannot clear HTTP progress`() {
        val state = state()
        state.setDownloading(snapshot(1))
        state.updateState(pack, AssetPackStatus.DOWNLOADING, 77, 1)

        assertEquals(-1, state.clearSettledDownload(pack))

        assertEquals(77, state.card(pack).progressPercent)
    }

    @Test
    fun `a new Play transfer can begin after the HTTP snapshot ends`() {
        val state = state()
        state.setDownloading(snapshot(1))
        state.updateState(pack, AssetPackStatus.COMPLETED, 100, 1)
        state.setDownloading(emptyMap())

        state.updateState(pack, AssetPackStatus.REQUIRES_USER_CONFIRMATION, 0)

        assertEquals(ToolchainAction.CANCEL, state.card(pack).action)
        assertEquals(0, state.card(pack).progressPercent)
    }

    @Test
    fun `a new transfer with the same percentage still reaches the cards`() {
        val refresh = downloadRefreshFor(snapshot(1), snapshot(2))

        assertTrue(refresh.push)
        assertFalse(refresh.rereadInstalled)
    }
}
