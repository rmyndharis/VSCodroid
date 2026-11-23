package com.vscodroid.service

import android.app.Service
import android.content.Intent
import com.vscodroid.R
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies that a refused foreground promotion for [NodeService.ACTION_HOLD] stops
 * quietly without writing a terminal startup notice or firing [NodeService.onServerGaveUp],
 * while a refused promotion for an actual server start still announces both.
 */
class NodeServiceHoldRefusedTest {

    private lateinit var service: NodeService
    private val gaveUpCalls = mutableListOf<Boolean>()
    private var stopSelfCount = 0

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        gaveUpCalls.clear()
        stopSelfCount = 0

        service = spyk(mockk<NodeService>(relaxed = true), recordPrivateCalls = true)
        NodeService::class.java.getDeclaredField("tag").apply {
            isAccessible = true
            set(service, "NodeService")
        }
        every { service.getString(R.string.error_server_start) } returns "Failed to start server"
        every { service["promoteToForeground"](any<Boolean>()) } returns false
        every { service.stopSelf() } answers { stopSelfCount++ }
        service.onServerGaveUp = { gaveUpCalls += it }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun command(action: String?) = mockk<Intent> { every { this@mockk.action } returns action }

    @Test
    fun `refused ACTION_HOLD stops without writing terminal startupNotice or invoking onServerGaveUp`() {
        val result = service.onStartCommand(command(NodeService.ACTION_HOLD), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(1, stopSelfCount, "refused setup hold should still stop the service")
        assertNull(
            service.lastStartupNotice(),
            "refused setup hold must not poison lastStartupNotice for a later server bind",
        )
        assertTrue(
            gaveUpCalls.isEmpty(),
            "refused setup hold must not invoke onServerGaveUp: $gaveUpCalls",
        )
    }

    @Test
    fun `refused server start still writes terminal startupNotice and invokes onServerGaveUp(false)`() {
        val result = service.onStartCommand(command(null), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(1, stopSelfCount)
        val notice = service.lastStartupNotice()
        assertEquals(StartupNotice("Failed to start server", terminal = true), notice)
        assertEquals(listOf(false), gaveUpCalls)
    }
}
