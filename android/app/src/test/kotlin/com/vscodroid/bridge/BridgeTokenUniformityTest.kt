package com.vscodroid.bridge

import android.content.Context
import android.webkit.JavascriptInterface
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Every method exposed to the page through `@JavascriptInterface` consults the session
 * token before doing anything. This enumerates them by reflection and calls each one
 * with a token the validator rejects, so a method that never asks is caught — including
 * one added later, which is the point: the rule holds for the class, not for the list
 * that existed when this was written.
 *
 * Checking the signature instead would not do it. A first parameter of type String says
 * nothing about whether it is a token, and two methods here take an unrelated String
 * first — a shape check passes them while they consult nothing.
 */
class BridgeTokenUniformityTest {

    private lateinit var security: SecurityManager
    private lateinit var bridge: AndroidBridge

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        security = mockk(relaxed = true)
        every { security.validateToken(any()) } returns false

        bridge = AndroidBridge(
            context = mockk<Context>(relaxed = true),
            security = security,
            clipboard = mockk(relaxed = true),
            onBackPressed = { false },
            onMinimize = { },
        )
    }

    /** A value of the right type for each parameter; the call only has to reach the check. */
    private fun argFor(type: Class<*>): Any? = when (type) {
        String::class.java -> "not-the-session-token"
        Int::class.javaPrimitiveType, Int::class.java -> 0
        Long::class.javaPrimitiveType, Long::class.java -> 0L
        Boolean::class.javaPrimitiveType, Boolean::class.java -> false
        else -> null
    }

    private fun bridgeMethods(): List<Method> = AndroidBridge::class.java.declaredMethods
        .filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
        .sortedBy { it.name }

    @Test
    fun `every bridge method consults the session token`() {
        val methods = bridgeMethods()
        check(methods.isNotEmpty()) {
            "No @JavascriptInterface methods found — this test would otherwise pass by " +
                "enumerating nothing"
        }

        val silent = mutableListOf<String>()
        for (m in methods) {
            clearMocks(security, answers = false)
            every { security.validateToken(any()) } returns false
            val args = m.parameterTypes.map { argFor(it) }.toTypedArray()
            try {
                m.invoke(bridge, *args)
            } catch (_: Throwable) {
                // A method that throws on a rejected token has still consulted it; the
                // verify below is what decides.
            }
            try {
                verify(atLeast = 1) { security.validateToken(any()) }
            } catch (_: Throwable) {
                silent.add(m.name)
            }
        }

        assertEquals(
            emptyList<String>(), silent,
            "these bridge methods never consult the session token, so they are exposed on " +
                "different terms from the other ${methods.size - silent.size}"
        )
    }
}
