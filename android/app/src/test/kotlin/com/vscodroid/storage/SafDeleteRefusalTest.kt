package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * That a device folder refusing a deletion is an answer, not a shrug.
 *
 * `DocumentsContract.deleteDocument` reports a refusal by returning false as
 * readily as by throwing, and both used to end at a log line: the return value
 * was discarded and the exception swallowed. The mirror entry is unlinked either
 * way, so the file left the editor while the device kept it, and the next open
 * copied it back. What a user reports is a file they deleted coming back on its
 * own, which is the shape of the "bugs when you try to make a file" complaint.
 *
 * The contract asserted here is the one the caller now branches on. The branch
 * itself is two lines; this is the half that was silently thrown away.
 */
class SafDeleteRefusalTest {

    private lateinit var engine: SafSyncEngine
    private val docUri = mockk<Uri>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0

        mockkStatic(DocumentsContract::class)

        val resolver = mockk<ContentResolver>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        engine = SafSyncEngine(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun deleteFromSaf(): Boolean =
        SafSyncEngine::class.java
            .getDeclaredMethod("deleteFromSaf", Uri::class.java)
            .apply { isAccessible = true }
            .invoke(engine, docUri) as Boolean

    @Test
    fun `a provider that agrees reports success`() {
        every { DocumentsContract.deleteDocument(any(), any()) } returns true

        assertTrue(deleteFromSaf(), "a deletion the device folder accepted was reported as refused")
    }

    /**
     * The case that was invisible. A provider is free to answer false rather
     * than throw, and a read-only remount or a stale document id does exactly
     * that.
     */
    @Test
    fun `a provider that returns false reports a refusal`() {
        every { DocumentsContract.deleteDocument(any(), any()) } returns false

        assertFalse(deleteFromSaf(), "a refusal the provider reported by value was read as success")
    }

    /**
     * And the case that was caught and dropped. A network-backed provider that
     * is momentarily offline throws here, and the caller used to carry on as
     * though the document were gone.
     */
    @Test
    fun `a provider that throws reports a refusal rather than propagating`() {
        every { DocumentsContract.deleteDocument(any(), any()) } throws
            IllegalStateException("provider is offline")

        assertFalse(deleteFromSaf(), "a provider failure was read as success")
    }
}
