package com.vscodroid.bridge

import android.content.Context
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

class MirrorRemovalAdmissionTest {
    private val security = SecurityManager()
    private val pending = ArrayDeque<Runnable>()
    private val removed = mutableListOf<Pair<String, Boolean>>()
    private val replies = mutableListOf<Triple<String, Boolean, String>>()
    private lateinit var bridge: AndroidBridge

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), any()) } returns Unit
        every { Logger.e(any(), any(), any()) } returns Unit
        val context = mockk<Context>(relaxed = true)
        every { context.getString(STORAGE_BUSY) } returns "bridge busy"
        every { context.getString(RECLAIM_STALE_SESSION) } returns "stale session"
        bridge = AndroidBridge(
            context = context,
            security = security,
            clipboard = mockk(relaxed = true),
            onBackPressed = mockk(relaxed = true),
            onMinimize = mockk(relaxed = true),
            safManager = mockk<SafStorageManager>(relaxed = true),
            onListMirrors = { "[]" },
            onReclaimMirror = { hash, force -> removed += hash to force; "" },
            onAsyncAnswer = { id, ok, data -> replies += Triple(id, ok, data) },
            diskWork = Executor { pending.addLast(it) },
        )
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a removal cannot queue behind a storage walk`() {
        val token = security.getSessionToken()
        assertEquals("", bridge.listSafMirrors(token, "list"))
        assertEquals(1, pending.size)

        assertEquals("bridge busy", bridge.reclaimSafMirror(token, "abcdef123456", true, "remove"))
        assertEquals(1, pending.size)
        assertTrue(removed.isEmpty())
        assertTrue(replies.isEmpty())

        pending.removeFirst().run()
        assertEquals(listOf(Triple("list", true, "[]")), replies)
        assertEquals("", bridge.reclaimSafMirror(token, "abcdef123456", true, "after-list"))
        assertEquals(1, pending.size)
        pending.removeFirst().run()
        assertEquals(listOf("abcdef123456" to true), removed)
        assertEquals(Triple("after-list", true, ""), replies.last())
    }

    @Test
    fun `a repeated removal cannot leave a second consent request pending`() {
        val token = security.getSessionToken()
        assertEquals("", bridge.reclaimSafMirror(token, "abcdef123456", true, "first"))
        assertEquals("bridge busy", bridge.reclaimSafMirror(token, "abcdef123456", true, "second"))
        assertEquals(1, pending.size)
        assertTrue(removed.isEmpty())

        pending.removeFirst().run()
        assertEquals(listOf("abcdef123456" to true), removed)
        assertEquals(listOf(Triple("first", true, "")), replies)
    }

    @Test
    fun `ordinary storage work retains its existing bounded queue`() {
        val token = security.getSessionToken()
        repeat(4) { assertEquals("", bridge.listSafMirrors(token, "list-$it")) }
        assertEquals("bridge busy", bridge.listSafMirrors(token, "overflow"))
        assertEquals(4, pending.size)
        repeat(4) { pending.removeFirst().run() }
        assertEquals(4, replies.size)
        assertEquals("", bridge.reclaimSafMirror(token, "abcdef123456", true, "after-drain"))
        pending.removeFirst().run()
        assertEquals(listOf("abcdef123456" to true), removed)
    }

    @Test
    fun `a stale session cannot reserve a destructive slot`() {
        assertEquals("stale session", bridge.reclaimSafMirror("stale", "abcdef123456", true, "stale"))
        assertTrue(pending.isEmpty())
        assertEquals("", bridge.reclaimSafMirror(security.getSessionToken(), "abcdef123456", true, "live"))
        pending.removeFirst().run()
        assertEquals(listOf("abcdef123456" to true), removed)
    }
}
