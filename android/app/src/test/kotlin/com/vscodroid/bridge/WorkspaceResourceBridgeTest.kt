package com.vscodroid.bridge

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkspaceResourceBridgeTest {
    @AfterEach fun tearDown() = unmockkAll()

    @Test
    fun onlyCurrentAuthenticatedPublicationsReplaceTheCompleteRootSet() {
        AuthTabWindow.beginPage()
        val generation = AuthTabWindow.pageGeneration()
        val security = mockk<SecurityManager>()
        every { security.validateToken(any()) } answers { firstArg<String>() == "session" }
        val seen = mutableListOf<List<String>>()
        val bridge = AndroidBridge(mockk<Context>(), security, mockk<ClipboardBridge>(), { false }, {},
            onWorkspaceRoots = { _, roots -> seen += roots; true })
        assertFalse(bridge.publishWorkspaceRoots("wrong", generation, "[\"/project\"]"))
        assertFalse(bridge.publishWorkspaceRoots("session", generation - 1, "[\"/project\"]"))
        assertFalse(bridge.publishWorkspaceRoots("session", generation, "[\"relative\"]"))
        assertFalse(bridge.publishWorkspaceRoots("session", generation, "[1]"))
        assertTrue(bridge.publishWorkspaceRoots("session", generation, "[\"/project\"]"))
        assertTrue(bridge.publishWorkspaceRoots("session", generation, "[]"))
        assertEquals(listOf(listOf("/project"), emptyList<String>()), seen)
    }
}
