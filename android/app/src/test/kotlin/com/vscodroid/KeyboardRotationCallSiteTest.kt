package com.vscodroid

import android.webkit.ValueCallback
import android.webkit.WebView
import com.vscodroid.bridge.AuthTabWindow
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KeyboardRotationCallSiteTest {
    private lateinit var activity: MainActivity
    private lateinit var view: WebView
    private val events = mutableListOf<String>()
    private val reply = slot<ValueCallback<String>>()

    private fun setView(value: WebView?) {
        MainActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }.set(activity, value)
    }

    private fun turn() {
        every { activity["finishKeyboardCompositionOnTurn"]() } answers { callOriginal() }
        MainActivity::class.java.getDeclaredMethod("finishKeyboardCompositionOnTurn").apply {
            isAccessible = true
        }.invoke(activity)
    }

    @BeforeEach
    fun setUp() {
        events.clear()
        AuthTabWindow.beginPage()
        activity = mockk(relaxed = true)
        view = mockk(relaxed = true)
        setView(view)
        every { activity.isFinishing } returns false
        every { activity.isDestroyed } returns false
        every { view.isFocused } returns true
        every { view.hasWindowFocus() } returns true
        every { view.evaluateJavascript(any(), capture(reply)) } answers { events += "evaluate" }
        every { view.clearFocus() } answers { events += "clear" }
        every { view.requestFocus() } answers { events += "focus"; true }
    }

    @AfterEach
    fun tearDown() {
        AuthTabWindow.beginPage()
        unmockkAll()
    }

    @Test
    fun `an editable page releases native input only after its script answers`() {
        turn()
        assertEquals(listOf("evaluate"), events)
        reply.captured.onReceiveValue("true")
        assertEquals(listOf("evaluate", "clear", "focus"), events)
    }

    @Test
    fun `a page without an eligible editing host keeps native focus`() {
        turn()
        for (value in listOf("false", "null", "\"true\"", "{}")) reply.captured.onReceiveValue(value)
        assertEquals(listOf("evaluate"), events)
    }

    @Test
    fun `a callback from an earlier document leaves the current input alone`() {
        turn()
        AuthTabWindow.beginPage()
        reply.captured.onReceiveValue("true")
        assertEquals(listOf("evaluate"), events)
    }

    @Test
    fun `a replacement view leaves an earlier callback inert`() {
        turn()
        setView(mockk(relaxed = true))
        reply.captured.onReceiveValue("true")
        assertEquals(listOf("evaluate"), events)
    }

    @Test
    fun `a closing activity leaves its input alone`() {
        turn()
        every { activity.isFinishing } returns true
        reply.captured.onReceiveValue("true")
        assertEquals(listOf("evaluate"), events)
        every { activity.isFinishing } returns false
        every { activity.isDestroyed } returns true
        reply.captured.onReceiveValue("true")
        assertEquals(listOf("evaluate"), events)
    }

    @Test
    fun `focus lost while the script was running is not taken back`() {
        turn()
        every { view.isFocused } returns false
        reply.captured.onReceiveValue("true")
        every { view.isFocused } returns true
        every { view.hasWindowFocus() } returns false
        reply.captured.onReceiveValue("true")
        assertEquals(listOf("evaluate"), events)
    }

    @Test
    fun `no view needs no input work`() {
        setView(null)
        turn()
        assertEquals(emptyList<String>(), events)
    }
}
