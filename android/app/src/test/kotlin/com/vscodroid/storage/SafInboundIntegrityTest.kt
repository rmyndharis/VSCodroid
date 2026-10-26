package com.vscodroid.storage

import android.os.FileObserver
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class SafInboundIntegrityTest : SafInitialSyncTestBase() {

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a replacement create retires its unread guard while siblings stay guarded`(ancestorOnly: Boolean) {
        val tree = named("tree")
        val engine = openWithUnreadGuard(tree, ancestorOnly)
        deviceTree(mapOf("root" to listOf(Child("sibling.txt", contents = "unread sibling"))))
        truncateOutputOnEveryWrite()
        val local = mirrorFile("notes.txt").apply { writeText("replacement edit") }
        engine.handleMirrorEvent(FileObserver.CREATE, local, mirror, tree)
        engine.runWriteBackLoop { false }
        assertEquals("replacement edit", written["doc-uri:doc:notes.txt"]?.toString(), "the replacement create never landed")
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "replacement edit"), Child("sibling.txt", contents = "unread sibling"))))
        local.writeText("next edit")

        engine.handleMirrorEvent(FileObserver.MODIFY, local, mirror, tree)
        engine.runWriteBackLoop { false }

        assertEquals("next edit", written["doc-uri:doc:notes.txt"]?.toString(), "the old unread marker blocked the new document's next save")
        assertTrue(lost.isEmpty(), "the successful replacement remained marked unread: $lost")

        val sibling = mirrorFile("sibling.txt").apply { writeText("local placeholder") }
        engine.handleMirrorEvent(FileObserver.CREATE, sibling, mirror, tree)
        engine.runWriteBackLoop { false }
        assertFalse(written.containsKey("doc-uri:doc:sibling.txt"), "a sibling's unread guard was retired with the replacement")
        assertEquals(listOf("sibling.txt"), lost)
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `an unstable replacement write keeps its unread guard`(ancestorOnly: Boolean, fails: Boolean) {
        val tree = named("tree")
        val engine = openWithUnreadGuard(tree, ancestorOnly)
        deviceTree(mapOf("root" to emptyList()))
        val local = mirrorFile("notes.txt").apply { writeText("replacement edit") }
        if (fails) {
            every { resolver.openOutputStream(any(), "wt") } throws IOException("device write unavailable")
        } else {
            every { resolver.openOutputStream(any(), "wt") } answers {
                object : ByteArrayOutputStream() {
                    override fun close() {
                        written[firstArg<android.net.Uri>().toString()] = this
                        local.writeText("changed while closing")
                    }
                }
            }
        }
        engine.handleMirrorEvent(FileObserver.CREATE, local, mirror, tree)
        engine.runWriteBackLoop { false }
        assertFalse(record.isFile && record.readText().contains("notes.txt\t"), "an unstable upload was recorded as delivered")
        assertEquals(if (fails) null else "replacement edit", written["doc-uri:doc:notes.txt"]?.toString())
        lost.clear()
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = if (fails) "" else "replacement edit"))))
        truncateOutputOnEveryWrite()
        local.writeText("later undelivered edit")

        engine.handleMirrorEvent(FileObserver.MODIFY, local, mirror, tree)
        engine.runWriteBackLoop { false }

        assertEquals(if (fails) null else "replacement edit", written["doc-uri:doc:notes.txt"]?.toString(), "an unstable upload retired its unread guard")
        assertEquals("later undelivered edit", local.readText())
        assertEquals(listOf("notes.txt"), lost)
    }

    private fun openWithUnreadGuard(tree: android.net.Uri, ancestorOnly: Boolean): SafSyncEngine {
        val engine = SafSyncEngine(context).apply { onWriteBackFailed = { lost += it.name } }
        deviceTree(
            mapOf("root" to if (ancestorOnly) emptyList() else listOf(Child("notes.txt", contents = "unread device bytes"), Child("sibling.txt", contents = "unread sibling"))),
            loading = if (ancestorOnly) setOf("root") else emptySet(),
        )
        every { resolver.openInputStream(any()) } throws IOException("device read unavailable")
        runBlocking { engine.initialSync(tree, mirror) { _, _ -> } }
        assertFalse(mirrorFile("notes.txt").exists())
        assertFalse(mirrorFile("sibling.txt").exists())
        return engine
    }

    private fun truncateOutputOnEveryWrite() {
        every { resolver.openOutputStream(any(), "wt") } answers {
            ByteArrayOutputStream().also { written[firstArg<android.net.Uri>().toString()] = it }
        }
    }

    @Test
    fun `a directory document that repeats an ancestor is not followed again`() {
        deviceTree(
            mapOf("root" to listOf(Child("loop", isDirectory = true, id = "root"))),
            maxQueries = 8,
        )

        sync()

        assertEquals(listOf("loop"), mirror.list()?.toList())
        assertFalse(mirrorFile("loop/loop").exists(), "the directory cycle was followed")
    }

    @Test
    fun `a provider chain is bounded before it can exhaust the call stack`() {
        val tree = (0..150).associate { depth ->
            (if (depth == 0) "root" else "dir:$depth") to
                listOf(Child("d", isDirectory = true, id = "dir:${depth + 1}"))
        }
        deviceTree(tree)

        sync()

        assertTrue(mirrorFile(List(128) { "d" }.joinToString("/")).isDirectory)
        assertFalse(mirrorFile(List(129) { "d" }.joinToString("/")).exists())
    }

    @Test
    fun `a delivered edit follows a later device deletion on every reopen`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("notes.txt", contents = "old"))))
        val engine = sync()
        val local = mirrorFile("notes.txt").apply { writeText("delivered edit") }
        write(engine, local, "notes.txt")
        assertEquals("delivered edit", written["doc-uri:doc:notes.txt"]?.toString())
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))

        sync()
        sync()

        assertFalse(local.exists(), "a delivered edit survived the device deletion and returned as new work")
        assertTrue(created.isEmpty(), "the deleted document was recreated")
    }

    @ParameterizedTest
    @ValueSource(strings = ["matching", "changed", "missing", "invalid"])
    fun `a device deletion removes only mirror bytes proved by a stable digest`(proof: String) {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("notes.txt", contents = "old"))))
        val engine = sync()
        val local = mirrorFile("notes.txt").apply { writeText("delivered") }
        write(engine, local, "notes.txt")
        assertEquals("delivered", written["doc-uri:doc:notes.txt"]?.toString())
        alterReceiptProof(local, proof)
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))

        sync()

        if (proof == "matching") {
            assertFalse(local.exists(), "the unchanged delivered copy did not follow the device deletion")
        } else {
            assertTrue(local.exists(), "the device deletion removed mirror bytes without matching digest proof")
            assertEquals(if (proof == "changed") "UNSENT!!!" else "delivered", local.readText())
        }
        assertTrue(created.isEmpty(), "the same reopen undid the device deletion")
    }

    @ParameterizedTest
    @ValueSource(strings = ["matching", "changed", "missing", "invalid"])
    fun `mirror reclaim requires stable digest proof for every file`(proof: String) {
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "old"))))
        val engine = sync()
        val local = mirrorFile("notes.txt").apply { writeText("delivered") }
        write(engine, local, "notes.txt")
        assertEquals("delivered", written["doc-uri:doc:notes.txt"]?.toString())
        alterReceiptProof(local, proof)

        assertEquals(proof == "matching", engine.holdsOnlyVouchedCopies(mirror), "reclaim trusted an unproved mirror copy")
        assertEquals(if (proof == "changed") "UNSENT!!!" else "delivered", local.readText())
    }

    @ParameterizedTest
    @CsvSource("false, changed", "true, changed", "false, missing", "true, missing", "false, invalid", "true, invalid", "false, matching", "true, matching")
    fun `a device replacement preserves mirror bytes without matching digest proof`(directory: Boolean, proof: String) {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("notes.txt", contents = "old"))))
        val engine = sync()
        val local = mirrorFile("notes.txt").apply { writeText("delivered") }
        write(engine, local, "notes.txt")
        alterReceiptProof(local, proof)
        deviceTree(
            mapOf(
                "root" to listOf(Child("keep.txt", contents = "k"), Child("notes.txt", isDirectory = directory, contents = "phone update", modified = local.lastModified() + 10_000)),
                "doc:notes.txt" to emptyList(),
            ),
        )

        sync()

        val preserved = mirror.listFiles().orEmpty().filter { it.name.startsWith("notes.txt${SafSyncEngine.LOCAL_COPY_SUFFIX}") }
        if (proof == "matching") {
            assertTrue(preserved.isEmpty(), "a proved stale copy was preserved unnecessarily")
        } else {
            assertEquals(1, preserved.size, "the device replacement removed the only unproved mirror copy")
            assertEquals(if (proof == "changed") "UNSENT!!!" else "delivered", preserved.single().readText())
        }
        if (directory) assertTrue(local.isDirectory)
        else assertEquals("phone update", local.readText())
    }

    private fun alterReceiptProof(local: File, proof: String) {
        if (proof == "changed") {
            val modified = local.lastModified()
            val size = local.length()
            local.writeText("UNSENT!!!")
            assertEquals(size, local.length())
            assertTrue(local.setLastModified(modified))
            assertEquals(modified, local.lastModified())
        } else if (proof == "missing" || proof == "invalid") {
            record.writeText(record.readLines().joinToString("\n") { line ->
                if (!line.startsWith("notes.txt\t")) line
                else line.split('\t').take(3).joinToString("\t") + if (proof == "invalid") "\tnot-a-digest" else ""
            })
        }
    }

    @Test
    fun `a short provider stream neither replaces the mirror nor vouches for the stub`() {
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "complete document"))))
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("comp".toByteArray()) }

        sync()

        assertFalse(mirrorFile("notes.txt").exists(), "a short stream was installed as a complete file")
        assertEquals(1, notCopied)
        assertFalse(record.readText().contains("notes.txt"), "the short stream was vouched for")
        assertEquals(emptyList<String>(), File(filesDir, "saf-inbound").list()?.toList())
    }

    @Test
    fun `a short refresh retains the earlier mirror copy and blocks its next save`() {
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "old copy"))))
        sync()
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "new full document", modified = DEVICE_TIME + 1000))))
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("new".toByteArray()) }

        val engine = sync()

        assertEquals("old copy", mirrorFile("notes.txt").readText())
        assertEquals(1, notCopied)
        write(engine, mirrorFile("notes.txt"), "notes.txt")
        assertTrue(written.isEmpty(), "the failed refresh did not guard the device tail")
    }

    @Test
    fun `a short device preservation stream refuses the newer mirror upload`() {
        mirrorFile("notes.txt").writeText("my complete edit")
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "foreign complete edit"))))
        record.writeText(SafSyncEngine.RECORD_HEADER)
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("fore".toByteArray()) }

        sync()

        assertEquals("my complete edit", mirrorFile("notes.txt").readText())
        assertTrue(written.isEmpty(), "the device tail was overwritten after its preservation ended early")
        assertFalse(mirror.list().orEmpty().any { it.contains(".device-") })
        assertEquals(listOf("notes.txt"), lost)
    }

    @Test
    fun `a missing record still preserves a device document before a newer mirror replaces it`() {
        mirrorFile("notes.txt").writeText("my complete edit")
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "foreign edit"))))

        sync()

        val aside = mirror.listFiles().orEmpty().filter { it.name.startsWith("notes.txt.device-") }
        assertEquals(1, aside.size, "mirror=${mirror.list()?.toList()}, record=${record.readText()}, written=$written")
        assertEquals("foreign edit", aside.single().readText())
        assertEquals("my complete edit", written["doc-uri:doc:notes.txt"]?.toString())
    }

    @Test
    fun `a later preservation never replaces an earlier device copy at the same timestamp`() {
        mirrorFile("notes.txt").writeText("first app edit")
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "original foreign edit"))))
        sync()
        assertEquals("original foreign edit", mirrorFile("notes.txt.device-$DEVICE_TIME").readText())
        mirrorFile("notes.txt").writeText("second app edit")
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "first app edit"))))

        sync()

        assertEquals("original foreign edit", mirrorFile("notes.txt.device-$DEVICE_TIME").readText())
    }

    @Test
    fun `a preserved device copy avoids a name the provider has listed but not fetched yet`() {
        mirrorFile("notes.txt").writeText("my app edit")
        deviceTree(
            mapOf(
                "root" to listOf(
                    Child("notes.txt", contents = "foreign edit"),
                    Child("notes.txt.device-$DEVICE_TIME", contents = "another document"),
                ),
            )
        )

        sync()

        assertTrue(mirror.listFiles().orEmpty().any { it.readText() == "foreign edit" }, "the preserved bytes were replaced by a listed sibling")
        assertEquals("another document", mirrorFile("notes.txt.device-$DEVICE_TIME").readText())
    }

    @Test
    fun `a device size change at its old timestamp is preserved before a newer mirror upload`() {
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "old"))))
        sync()
        mirrorFile("notes.txt").writeText("my newer edit")
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "foreign edit"))))

        sync()

        val aside = mirror.listFiles().orEmpty().single { it.name.startsWith("notes.txt.device-") }
        assertEquals("foreign edit", aside.readText())
        assertEquals("my newer edit", written["doc-uri:doc:notes.txt"]?.toString())
    }

    @Test
    fun `a partial open records fetched files and carries earlier unsettled identities`() {
        deviceTree(mapOf("root" to listOf(Child("old.txt", contents = "old"))))
        sync()
        val oldLine = record.readLines().single { it.startsWith("old.txt\t") }
        deviceTree(
            mapOf("root" to listOf(Child("fresh.txt", contents = "new"))),
            loading = setOf("root"),
        )

        sync()

        assertTrue(record.readLines().contains(oldLine), "the unsettled previous copy lost its identity")
        assertTrue(record.readLines().any { it.startsWith("fresh.txt\t") }, "the fetched file was not recorded")
        assertEquals("old", mirrorFile("old.txt").readText(), "a partial listing authorized a deletion")
    }

    @Test
    fun `the first partial open still records the files it fetched`() {
        deviceTree(
            mapOf("root" to listOf(Child("fresh.txt", contents = "new"))),
            errors = setOf("root"),
        )

        sync()

        assertTrue(record.isFile, "a partial open left the next open with no identity history")
        assertTrue(record.readLines().any { it.startsWith("fresh.txt\t") })
    }

    @Test
    fun `a fetched copy from a partial open is refreshed without a spurious local copy`() {
        deviceTree(
            mapOf("root" to listOf(Child("notes.txt", contents = "first"))),
            loading = setOf("root"),
        )
        sync()
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "second", modified = DEVICE_TIME + 1000))))

        sync()

        assertEquals("second", mirrorFile("notes.txt").readText())
        assertEquals(listOf("notes.txt"), mirror.list()?.toList(), "a sync-written copy was mistaken for local work")
        assertTrue(created.isEmpty())
    }

    @Test
    fun `a fetched file remains writable while unlisted siblings stay guarded`() {
        deviceTree(
            mapOf("root" to listOf(Child("known.txt", contents = "known"))),
            loading = setOf("root"),
        )
        val engine = sync()
        val file = mirrorFile("known.txt").apply { writeText("new edit") }

        write(engine, file, "known.txt")

        assertEquals("new edit", written["doc-uri:doc:known.txt"]?.toString())
        assertTrue(lost.isEmpty(), "a file fetched by this open was treated as unread")

        val unknown = mirrorFile("unlisted.txt").apply { writeText("local placeholder") }
        write(engine, unknown, "unlisted.txt")
        assertFalse(written.containsKey("doc-uri:doc:unlisted.txt"))
        assertEquals(listOf("unlisted.txt"), lost)
    }

    @Test
    fun `an error listing arms the unread guard for documents it did not return`() {
        deviceTree(mapOf("root" to emptyList()), errors = setOf("root"))
        val engine = sync()
        val unknown = mirrorFile("unlisted.txt").apply { writeText("local placeholder") }

        write(engine, unknown, "unlisted.txt")

        assertTrue(written.isEmpty(), "a failed listing did not arm the unread guard")
        assertEquals(listOf("unlisted.txt"), lost)
    }

    @Test
    fun `a per-file copy claim arms the guard even when mirror admission succeeds`() {
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "complete device copy"))))
        val field = SafSyncEngine::class.java.getDeclaredField("mirrorCopiesInFlight").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val claims = field.get(null) as MutableSet<String>
        val local = mirrorFile("notes.txt")
        assertTrue(claims.add(local.absolutePath))
        val engine = try {
            sync()
        } finally {
            claims.remove(local.absolutePath)
        }
        assertEquals(0, notCopied)
        assertFalse(local.exists(), "the claimed copy unexpectedly ran")
        local.writeText("placeholder")

        write(engine, local, "notes.txt")

        assertTrue(written.isEmpty(), "a copy claimed elsewhere left the device unguarded")
        assertEquals(listOf("notes.txt"), lost)
    }

    private fun write(engine: SafSyncEngine, file: File, relative: String) {
        val method = SafSyncEngine::class.java.getDeclaredMethod(
            "processWriteBack", WatchSession::class.java, SyncJob::class.java,
        )
        method.isAccessible = true
        method.invoke(
            engine,
            WatchSession(),
            SyncJob(
                SyncType.MODIFY, file.absolutePath, named("doc-uri:doc:$relative"),
                null, null, System.currentTimeMillis(), relativePath = relative,
                mirrorRoot = mirror,
            ),
        )
    }
}
