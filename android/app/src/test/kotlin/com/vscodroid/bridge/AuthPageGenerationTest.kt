package com.vscodroid.bridge

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthPageGenerationTest {
    @Test
    fun `bootstrap accepts only the current native page`() {
        val security = SecurityManager()
        val bridge = AndroidBridge(
            context = mockk(relaxed = true), security = security,
            clipboard = mockk(relaxed = true), onBackPressed = { false }, onMinimize = {},
        )
        val token = security.getSessionToken()
        val old = AuthTabWindow.pageGeneration()
        assertTrue(bridge.isAuthPageCurrent(token, old))
        AuthTabWindow.beginPage()
        assertFalse(bridge.isAuthPageCurrent(token, old))
        assertTrue(bridge.isAuthPageCurrent(token, AuthTabWindow.pageGeneration()))
    }
}
