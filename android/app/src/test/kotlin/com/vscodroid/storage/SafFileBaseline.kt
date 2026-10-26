package com.vscodroid.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Completes an upload so deletion tests start with evidence of the delivered bytes. */
internal fun deliverFileBaseline(
    engine: SafSyncEngine,
    resolver: ContentResolver,
    localFile: File,
    document: Uri,
    mirrorRoot: File,
) {
    localFile.writeText("known provider bytes")
    val delivered = ByteArrayOutputStream()
    every { resolver.openOutputStream(document, "wt") } returns delivered
    every { resolver.openInputStream(document) } answers {
        ByteArrayInputStream(delivered.toByteArray())
    }
    every { resolver.query(document, any(), any(), any(), any()) } answers {
        mockk<Cursor>(relaxed = true) {
            every { moveToFirst() } returns true
            every { getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns 0
            every { getColumnIndex(DocumentsContract.Document.COLUMN_SIZE) } returns 1
            every { isNull(any<Int>()) } returns false
            every { getLong(0) } returns 1_700_000_000_000L
            every { getLong(1) } answers { delivered.size().toLong() }
        }
    }
    SafSyncEngine::class.java
        .getDeclaredMethod("writeLocalToSaf", File::class.java, Uri::class.java, File::class.java)
        .apply { isAccessible = true }
        .invoke(engine, localFile, document, mirrorRoot)
    assertEquals(localFile.readText(), delivered.toString(Charsets.UTF_8),
        "the provider did not receive the baseline")
}
