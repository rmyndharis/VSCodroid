package com.vscodroid.storage

import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class SafInitialSyncIdentityTest : SafInitialSyncTestBase() {
    @TempDir lateinit var elsewhere: File

    @Test
    fun `a low-space reopen leaves a redirected parent target timestamp untouched`() =
        reopen(lowSpace = true, redirect = true)

    @Test
    fun `a low-space reopen settles an unchanged mirror under its original parent`() =
        reopen(lowSpace = true, redirect = false)

    @Test
    fun `a reopen with available space copies the updated provider document`() =
        reopen(lowSpace = false, redirect = false)

    private fun reopen(lowSpace: Boolean, redirect: Boolean) {
        mirror = File(filesDir, "saf-mirrors/aaaaaaaaaaaa").apply { assertTrue(mkdirs()) }
        val relative = "docs/nested/notes.txt"
        val originalBytes = "THE IMPORTED DOCUMENT"
        val laterTime = DEVICE_TIME + 60_000L
        fun tree(contents: String, modified: Long) = linkedMapOf(
            "root" to listOf(Child("docs", isDirectory = true, id = "docs-id")),
            "docs-id" to listOf(Child("nested", isDirectory = true, id = "nested-id")),
            "nested-id" to listOf(Child("notes.txt", contents = contents, modified = modified, id = "notes-id")),
        )
        deviceTree(tree(originalBytes, DEVICE_TIME))
        var importOpens = 0
        every { resolver.openInputStream(match { it.toString() == "doc-uri:notes-id" }) } answers {
            importOpens++
            ByteArrayInputStream(originalBytes.toByteArray())
        }
        val engine = SafSyncEngine(context).apply {
            usableSpaceOf = { Long.MAX_VALUE }
            onDocumentsNotCopied = { count, _ -> notCopied = count }
        }
        runBlocking { engine.initialSync(named("tree"), mirror) { _, _ -> } }
        val local = mirrorFile(relative)
        assertEquals(1, importOpens, "the first sync must import the real provider stream")
        assertEquals(originalBytes, local.readText())
        assertEquals(DEVICE_TIME, local.lastModified())
        val importedIdentity = engine.identityLine(relative, local)
        assertTrue(record.readLines().drop(1).any {
            SafSyncEngine.splitRecordLine(it).first == importedIdentity
        }, "the first sync must produce its own record for the imported file")
        assertTrue(created.isEmpty())
        assertTrue(written.isEmpty())

        val privateRoot = File(elsewhere, "other-files").apply { assertTrue(mkdirs()) }
        val foreign = File(privateRoot, "nested/notes.txt").apply {
            assertTrue(parentFile.mkdirs())
            writeText(originalBytes)
            assertTrue(setLastModified(local.lastModified()))
        }
        val foreignTime = foreign.lastModified()
        assertEquals(local.lastModified(), foreignTime)
        assertEquals(local.length(), foreign.length())
        assertFalse(foreign.canonicalPath.startsWith(mirror.canonicalPath + File.separator))
        val originalParent = File(mirror, "docs")
        val movedParent = File(elsewhere, "original-docs")
        val providerBytes = if (lowSpace) originalBytes else "UPDATED PROVIDER DOCUMENT"
        deviceTree(tree(providerBytes, laterTime))
        val spaceChecks = mutableListOf<String>()
        engine.usableSpaceOf = { path ->
            spaceChecks += path.absolutePath
            if (lowSpace) 0L else Long.MAX_VALUE
        }
        var providerOpens = 0
        var redirects = 0
        var firstOpenSpaceChecks = emptyList<String>()
        every { resolver.openInputStream(match { it.toString() == "doc-uri:notes-id" }) } answers {
            providerOpens++
            if (providerOpens == 1) {
                firstOpenSpaceChecks = spaceChecks.toList()
                if (redirect) {
                    assertTrue(originalParent.renameTo(movedParent))
                    Files.createSymbolicLink(originalParent.toPath(), privateRoot.toPath())
                    redirects++
                    assertTrue(Files.isSymbolicLink(originalParent.toPath()))
                    assertEquals(foreign.canonicalPath, local.canonicalPath)
                }
            }
            ByteArrayInputStream(providerBytes.toByteArray())
        }
        notCopied = 0
        try {
            runBlocking { engine.initialSync(named("tree"), mirror) { _, _ -> } }

            assertTrue(providerOpens > 0, "the second sync never reached its provider-read callback")
            assertTrue(firstOpenSpaceChecks.contains(local.parentFile.absolutePath),
                "the second sync never attempted the actual copy's space-floor check")
            if (lowSpace) {
                assertTrue(firstOpenSpaceChecks.count { it == mirror.absolutePath } >= 2,
                    "the provider read must follow the low-space copy refusal and fallback check")
            } else {
                assertEquals(1, providerOpens, "the updated document must be copied once")
            }
            assertEquals(if (redirect) 1 else 0, redirects)
            assertEquals(foreignTime, foreign.lastModified(),
                "settling a mirror must not stamp a different file reached through a redirected parent")
            assertEquals(originalBytes, foreign.readText())
            if (redirect) {
                assertEquals(originalBytes, File(movedParent, "nested/notes.txt").readText())
            } else {
                assertEquals(providerBytes, local.readText())
                assertEquals(laterTime, local.lastModified(),
                    "the ordinary mirror must still receive the provider's timestamp")
                assertEquals(0, notCopied, "a settled document must not be reported as unread")
            }
            assertTrue(created.isEmpty(), "the reopen unexpectedly created a provider document")
            assertTrue(written.isEmpty(), "the reopen unexpectedly wrote back to the provider")
        } finally {
            if (redirects != 0) Files.deleteIfExists(originalParent.toPath())
        }
    }
}
