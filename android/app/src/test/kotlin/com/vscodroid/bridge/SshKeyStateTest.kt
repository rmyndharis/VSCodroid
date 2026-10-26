package com.vscodroid.bridge

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What `~/.ssh` may be left in, and what is handed back about what is there.
 *
 * Existing private keys must survive generation requests, including when their
 * public file is missing. A private key can be imported or retained independently
 * of its public file; its contents must never be discarded to create a new pair.
 *
 * **A public key line's comment is not reported.** Its third field is
 * conventionally `user@host` or an email address, which for any key the user
 * imported is a personal identifier. `listSshKeys` is one of the relay commands
 * and every answer on that channel is broadcast to whatever else is listening on
 * the workbench's origin, which is every installed web extension; nothing in this
 * app or in the bundled extensions ever read the field.
 *
 * Generation cases call the real bridge method with bounded shell children in place
 * of the native binary. The children honor its output pathname and expose readiness
 * markers for controlled imports and interruption. Failed spawn is covered separately.
 */
class SshKeyStateTest {

    /** The real one, so the token the bridge accepts is not stubbed into existence. */
    private val security = SecurityManager()

    @TempDir
    lateinit var files: File

    private lateinit var context: Context
    private lateinit var bridge: AndroidBridge

    private val sshDir get() = File(files, "home/.ssh")
    private val privateKey get() = File(sshDir, "id_ed25519")
    private val publicKey get() = File(sshDir, "id_ed25519.pub")

    private companion object {
        /** A public key line in the shape OpenSSH writes, comment and all. */
        const val COMMENT = "alice@example.com"
        const val PUBLIC_LINE = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI0000000000 $COMMENT"

        // Only the shape of a key file matters here: the bytes are kept or they are not. The
        // body is not a key, and the header is written in two pieces so that a secret scanner
        // reading this source finds no key block in it.
        val PRIVATE_KEY_FIXTURE = listOf(
            "-----BEGIN OPENSSH " + "PRIVATE KEY-----",
            "dGVzdC1maXh0dXJlLW5vdC1hLWtleQ==",
            "-----END OPENSSH " + "PRIVATE KEY-----",
        ).joinToString("\n") + "\n"
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        every { context.filesDir } returns files
        // A native library directory with no ssh-keygen in it, so the generation
        // below fails at the spawn. That is the point rather than a limitation:
        // these cases are about the state of ~/.ssh on the way in and on the way
        // out, and a failed spawn is one of the failures that state has to
        // survive.
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = File(files, "no-such-lib-dir").absolutePath
        }

        bridge = AndroidBridge(
            context = context,
            security = security,
            clipboard = mockk(relaxed = true),
            onBackPressed = mockk(relaxed = true),
            onMinimize = mockk(relaxed = true),
        )
        assertTrue(sshDir.mkdirs() || sshDir.isDirectory)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a whole pair is reported rather than regenerated`() {
        privateKey.writeText("PRIVATE KEY")
        publicKey.writeText("$PUBLIC_LINE\n")

        val answer = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))

        assertTrue(answer.getBoolean("success"), "an existing pair must be reported: $answer")
        assertTrue(answer.optBoolean("existed"), "the caller is not told the key already existed")
        assertEquals(PUBLIC_LINE, answer.getString("publicKey"))
        assertEquals(
            "PRIVATE KEY", privateKey.readText(),
            "the existing private key was overwritten, which is the one thing this " +
                "branch exists to prevent",
        )
    }

    @Test
    fun `a private key without a public file is preserved with repair guidance`() {
        for (contents in listOf(PRIVATE_KEY_FIXTURE, "PRIVATE KEY\n")) {
            privateKey.writeText(contents)
            val original = privateKey.readBytes()

            val answer = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))

            assertFalse(answer.getBoolean("success"))
            assertTrue(privateKey.isFile, "generation deleted the existing private key")
            assertTrue(
                original.contentEquals(privateKey.readBytes()),
                "generation changed the existing private key",
            )
            assertFalse(publicKey.exists(), "generation must not create a replacement pair")
            val error = answer.getString("error")
            assertTrue(error.contains("ssh-keygen -y"), "the error must explain how to recover the public key")
            assertTrue(error.contains("~/.ssh/id_ed25519.pub"), "the error must identify the public file to repair")
        }
    }

    @Test
    fun `a generation that fails leaves nothing behind for the next one to trip on`() {
        // Nothing on disk to begin with, which is the ordinary first call.
        val stagesBefore = temporaryStages()
        val answer = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))

        assertFalse(answer.getBoolean("success"))
        assertFalse(privateKey.exists(), "a failed generation left a private key behind")
        assertFalse(publicKey.exists(), "a failed generation left a public key behind")
        assertEquals(stagesBefore, temporaryStages(), "failed spawn left a temporary generation directory")
    }

    @Test
    fun `an orphan public file is preserved without starting generation`() {
        val child = installChild("touch \"\$0.ran\"\nexit 3\n")
        publicKey.writeText("$PUBLIC_LINE\n")

        val answer = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))

        assertFalse(File(child.path + ".ran").exists(), "generation must not start over an orphan public file")
        assertFalse(answer.getBoolean("success"))
        assertEquals("$PUBLIC_LINE\n", publicKey.readText())
        assertFalse(privateKey.exists())
        assertTrue(answer.getString("error").contains("move"))
        assertTrue(answer.getString("error").contains("~/.ssh/id_ed25519.pub"))
    }

    @Test
    fun `the listing does not hand over the key's comment`() {
        publicKey.writeText("$PUBLIC_LINE\n")

        val listing = bridge.listSshKeys(security.getSessionToken())
        val keys = JSONArray(listing)

        assertEquals(1, keys.length(), "the listing did not find the key: $listing")
        val key = keys.getJSONObject(0)
        assertEquals("id_ed25519", key.getString("name"))
        assertEquals("ssh-ed25519", key.getString("type"))
        assertFalse(
            key.has("comment"),
            "the comment field is back. It is conventionally an email address, and this " +
                "command answers onto a channel every web extension on the workbench's " +
                "origin can read.",
        )
        assertFalse(
            listing.contains(COMMENT),
            "the comment reached the caller by some other key in the object: $listing",
        )
    }

    @Test
    fun `an orphan public file is preserved before a child can replace it`() {
        val child = installChild(
            "printf '%s\n' 'DUMMY PRIVATE' > \"\$key_file\"\n" +
                "printf '%s\n' 'REPLACED PUBLIC' > \"\$key_file.pub\"\n" +
                "touch \"\$0.ran\"\nexit 3\n",
        )
        publicKey.writeText("$PUBLIC_LINE\n")

        val answer = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))

        assertFalse(File(child.path + ".ran").exists(), "the replacing child must never be started")
        assertFalse(answer.getBoolean("success"))
        assertEquals("$PUBLIC_LINE\n", publicKey.readText())
        assertFalse(privateKey.exists())
    }

    @Test
    fun `a completed child returns the generated pair`() {
        io.mockk.mockkStatic(android.system.Os::class)
        every { android.system.Os.chmod(any(), any()) } just Runs
        val child = installChild(
            "printf '%s\n' 'DUMMY PRIVATE' > \"\$key_file\"\n" +
                "printf '%s\n' '$PUBLIC_LINE' > \"\$key_file.pub\"\n" +
                "touch \"\$0.ran\"\nexit 0\n",
        )

        val answer = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))

        assertTrue(File(child.path + ".ran").isFile, "the controlled child never completed")
        assertTrue(answer.getBoolean("success"), answer.toString())
        assertEquals(PUBLIC_LINE, answer.getString("publicKey"))
        assertEquals("DUMMY PRIVATE\n", privateKey.readText())
        assertEquals("$PUBLIC_LINE\n", publicKey.readText())
        assertStageRemoved(child)
    }

    @Test
    fun `an interrupted generation settles the child before returning failure`() {
        val childFile = installChild(
            "printf '%s\n' \"\$\$\" > \"\$0.pid\"\n" +
                "printf '%s\n' 'DUMMY PRIVATE' > \"\$key_file\"\n" +
                "touch \"\$0.ready\"\nexec /bin/sleep 8\n",
        )
        val pidFile = File(childFile.path + ".pid")
        val readyFile = File(childFile.path + ".ready")
        val targetFile = File(childFile.path + ".target")
        val answer = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val interruptRestored = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            try {
                answer.set(bridge.generateSshKey(security.getSessionToken(), ""))
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                interruptRestored.set(Thread.currentThread().isInterrupted)
            }
        }.apply { isDaemon = true }
        var child: java.lang.ProcessHandle? = null
        worker.start()
        try {
            val readyDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while ((!pidFile.isFile || !readyFile.isFile) && System.nanoTime() < readyDeadline) {
                Thread.sleep(10)
            }
            assertTrue(pidFile.isFile && readyFile.isFile, "the child never reached its bounded wait")
            child = java.lang.ProcessHandle.of(pidFile.readText().trim().toLong()).orElseThrow()
            assertTrue(child.isAlive, "the child exited before the interrupt schedule")
            assertTrue(targetFile.isFile, "the child did not record its supplied output pathname")
            assertTrue(File(targetFile.readText().trim()).isFile,
                "the controlled child did not create its dummy private output")

            worker.interrupt()
            worker.join(2_000)

            assertFalse(worker.isAlive, "generateSshKey did not return after its caller was interrupted")
            failure.get()?.let { throw AssertionError("the generation worker threw", it) }
            val result = JSONObject(requireNotNull(answer.get()) { "the worker returned no answer" })
            assertFalse(result.getBoolean("success"), result.toString())
            try {
                child.onExit().get(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                // The assertion below records the unsettled child before cleanup.
            }
            assertFalse(child.isAlive, "failure returned while its controlled child remained alive")
            assertTrue(interruptRestored.get(), "generation swallowed the caller's interruption")
            assertFalse(privateKey.exists(), "interrupted generation retained its new private file")
            assertFalse(publicKey.exists(), "interrupted generation retained its new public file")
            assertStageRemoved(childFile)
        } finally {
            val remaining = child ?: if (pidFile.isFile) {
                java.lang.ProcessHandle.of(pidFile.readText().trim().toLong()).orElse(null)
            } else null
            try {
                remaining?.descendants()?.use { descendants ->
                    descendants.forEach { it.destroyForcibly() }
                }
                remaining?.destroyForcibly()
                if (remaining != null) {
                    try {
                        remaining.onExit().get(1, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: java.util.concurrent.TimeoutException) {
                        throw AssertionError("the controlled child did not stop during cleanup")
                    }
                }
            } finally {
                worker.interrupt()
                worker.join(2_000)
                assertFalse(worker.isAlive, "the bounded generation worker did not stop during cleanup")
            }
        }
    }

    @Test
    fun `a failed child leaves a private key imported while it was running`() {
        val childFile = installChild(
            "printf '%s\n' \"\$\$\" > \"\$0.pid\"\n" +
                "touch \"\$0.ready\"\n" +
                "count=0\nwhile [ ! -f \"\$0.release\" ] && [ \"\$count\" -lt 250 ]; do\n" +
                "  sleep 0.01\n  count=\$((count + 1))\ndone\n" +
                "if [ -f \"\$0.release\" ]; then touch \"\$0.released\"; fi\nexit 3\n",
        )
        val pidFile = File(childFile.path + ".pid")
        val ready = File(childFile.path + ".ready")
        val release = File(childFile.path + ".release")
        val answer = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val worker = Thread {
            try { answer.set(bridge.generateSshKey(security.getSessionToken(), "")) }
            catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true }
        worker.start()
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while ((!pidFile.isFile || !ready.isFile) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(pidFile.isFile && ready.isFile, "the child did not reach the controlled wait")
            assertTrue(java.lang.ProcessHandle.of(pidFile.readText().trim().toLong()).orElseThrow().isAlive)
            privateKey.writeText("IMPORTED USER KEY")
            release.writeText("continue")
            worker.join(2_000)
            assertFalse(worker.isAlive, "generation did not return after the child failed")
            failure.get()?.let { throw AssertionError("the generation worker threw", it) }
            assertTrue(File(childFile.path + ".released").isFile, "the child failed before the import schedule")
            assertFalse(JSONObject(requireNotNull(answer.get())).getBoolean("success"))
            assertTrue(privateKey.isFile, "failed generation deleted a key imported by another writer")
            assertEquals("IMPORTED USER KEY", privateKey.readText())
        } finally {
            release.writeText("continue")
            val child = if (pidFile.isFile) java.lang.ProcessHandle.of(pidFile.readText().trim().toLong()).orElse(null) else null
            child?.destroyForcibly()
            if (child != null) child.onExit().get(1, java.util.concurrent.TimeUnit.SECONDS)
            worker.interrupt()
            worker.join(2_000)
            assertFalse(worker.isAlive, "the generation worker did not settle during cleanup")
        }
    }

    @Test
    fun `a failed child removes only its temporary output`() {
        val child = installChild(
            "printf '%s\n' 'DUMMY PRIVATE' > \"\$key_file\"\n" +
                "printf '%s\n' '$PUBLIC_LINE' > \"\$key_file.pub\"\n" +
                "touch \"\$0.ran\"\nexit 3\n",
        )

        val answer = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))

        assertTrue(File(child.path + ".ran").isFile)
        assertFalse(answer.getBoolean("success"))
        assertFalse(privateKey.exists())
        assertFalse(publicKey.exists())
        assertStageRemoved(child)
    }

    @Test
    fun `a timed out generation stops its child before removing temporary output`() {
        val childFile = installChild(
            "printf '%s\n' \"\$\$\" > \"\$0.pid\"\n" +
                "printf '%s\n' 'DUMMY PRIVATE' > \"\$key_file\"\n" +
                "printf '%s\n' '$PUBLIC_LINE' > \"\$key_file.pub\"\n" +
                "touch \"\$0.ready\"\nexec /bin/sleep 8\n",
        )
        val pidFile = File(childFile.path + ".pid")
        try {
            val answer = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))

            assertTrue(File(childFile.path + ".ready").isFile, "the child never reached its bounded wait")
            assertFalse(answer.getBoolean("success"))
            assertTrue(answer.getString("error").contains("did not finish"), answer.toString())
            val child = java.lang.ProcessHandle.of(pidFile.readText().trim().toLong()).orElse(null)
            assertFalse(child?.isAlive == true, "the timed out child was still alive after failure returned")
            assertFalse(privateKey.exists())
            assertFalse(publicKey.exists())
            assertStageRemoved(childFile)
        } finally {
            val child = if (pidFile.isFile) {
                java.lang.ProcessHandle.of(pidFile.readText().trim().toLong()).orElse(null)
            } else null
            child?.destroyForcibly()
            if (child != null) child.onExit().get(1, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    fun `a private key imported before publication is not replaced`() =
        successfulChildMeetsImport(importPublic = false)

    @Test
    fun `a public file imported before publication prevents a new private key`() =
        successfulChildMeetsImport(importPublic = true)

    private fun successfulChildMeetsImport(importPublic: Boolean) {
        io.mockk.mockkStatic(android.system.Os::class)
        every { android.system.Os.chmod(any(), any()) } just Runs
        val childFile = installChild(
            "printf '%s\n' \"\$\$\" > \"\$0.pid\"\n" +
                "printf '%s\n' 'DUMMY PRIVATE' > \"\$key_file\"\n" +
                "printf '%s\n' '$PUBLIC_LINE' > \"\$key_file.pub\"\n" +
                "touch \"\$0.ready\"\n" +
                "count=0\nwhile [ ! -f \"\$0.release\" ] && [ \"\$count\" -lt 250 ]; do\n" +
                "  sleep 0.01\n  count=\$((count + 1))\ndone\n" +
                "if [ -f \"\$0.release\" ]; then touch \"\$0.released\"; fi\nexit 0\n",
        )
        val pidFile = File(childFile.path + ".pid")
        val ready = File(childFile.path + ".ready")
        val release = File(childFile.path + ".release")
        val answer = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val worker = Thread {
            try { answer.set(bridge.generateSshKey(security.getSessionToken(), "")) }
            catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true }
        worker.start()
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while ((!pidFile.isFile || !ready.isFile) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(pidFile.isFile && ready.isFile, "the child did not reach the publication schedule")
            assertTrue(java.lang.ProcessHandle.of(pidFile.readText().trim().toLong()).orElseThrow().isAlive)
            val imported = if (importPublic) publicKey else privateKey
            imported.writeText("IMPORTED USER FILE\n")
            release.writeText("continue")
            worker.join(2_000)

            assertFalse(worker.isAlive, "generation did not finish after the publication schedule")
            failure.get()?.let { throw AssertionError("the generation worker threw", it) }
            assertTrue(File(childFile.path + ".released").isFile)
            val result = JSONObject(requireNotNull(answer.get()))
            assertFalse(result.getBoolean("success"), result.toString())
            assertEquals("IMPORTED USER FILE\n", imported.readText())
            assertStageRemoved(childFile)
            if (importPublic) {
                assertFalse(privateKey.exists(), "a public file already present must prevent private publication")
                val retry = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))
                assertFalse(retry.getBoolean("success"), "retry must keep refusing the orphan public file")
                assertTrue(retry.getString("error").contains("move"))
                assertFalse(privateKey.exists())
                assertEquals("IMPORTED USER FILE\n", publicKey.readText())
            } else {
                assertFalse(publicKey.exists(), "a refused private publication must not install the public file")
            }
        } finally {
            release.writeText("continue")
            val child = if (pidFile.isFile) java.lang.ProcessHandle.of(pidFile.readText().trim().toLong()).orElse(null) else null
            try {
                child?.destroyForcibly()
                if (child != null) child.onExit().get(1, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                worker.interrupt()
                worker.join(2_000)
                assertFalse(worker.isAlive, "the generation worker did not settle during cleanup")
            }
        }
    }

    @Test
    fun `a later public publication failure keeps the new private key for recovery`() {
        val child = installChild(
            "printf '%s\n' 'DUMMY PRIVATE' > \"\$key_file\"\n" +
                "printf '%s\n' '$PUBLIC_LINE' > \"\$key_file.pub\"\n" +
                "touch \"\$0.ran\"\nexit 0\n",
        )
        var publicAttempts = 0
        var boundaryArmed = false
        io.mockk.mockkStatic(android.system.Os::class)
        every { android.system.Os.chmod(any(), any()) } answers {
            if (!boundaryArmed) {
                assertTrue(File(child.path + ".ran").isFile,
                    "the publication fault must be armed after the child completed")
                io.mockk.mockkStatic(java.nio.file.Files::class)
                every { java.nio.file.Files.createLink(publicKey.toPath(), any()) } answers {
                    publicAttempts++
                    assertTrue(privateKey.isFile, "the private file must be admitted before this I/O failure")
                    assertEquals("DUMMY PRIVATE\n", privateKey.readText())
                    assertFalse(publicKey.exists(), "this failure must not stand in for an imported public file")
                    throw java.io.IOException("public publication failed")
                }
                boundaryArmed = true
            }
            Unit
        }

        val answer = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))

        assertTrue(File(child.path + ".ran").isFile, "the controlled child never completed: $answer")
        assertTrue(boundaryArmed, "the publication boundary was never armed: $answer")
        assertEquals(1, publicAttempts, "the public publication boundary was not reached: $answer")
        assertFalse(answer.getBoolean("success"))
        assertEquals("DUMMY PRIVATE\n", privateKey.readText())
        assertFalse(publicKey.exists())
        assertTrue(answer.getString("error").contains("ssh-keygen -y"))
        assertTrue(answer.getString("error").contains("inspect"))
        assertStageRemoved(child)

        val retry = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))
        assertFalse(retry.getBoolean("success"), "retry must refuse the retained private-only state")
        assertTrue(retry.getString("error").contains("without its public file"))
        assertEquals("DUMMY PRIVATE\n", privateKey.readText())
        assertFalse(publicKey.exists())
        assertEquals(1, publicAttempts, "retry must not attempt another publication")
        assertTrue(temporaryStages().isEmpty(), "private-only refusal created temporary output")
    }

    private fun temporaryStages(): Set<String> =
        files.listFiles().orEmpty().filter { it.name.startsWith(".ssh-keygen-") }.map { it.name }.toSet()

    private fun assertStageRemoved(child: File) {
        val receipt = File(child.path + ".target")
        assertTrue(receipt.isFile, "the child did not record its supplied output path")
        val target = File(receipt.readText().trim())
        val parent = requireNotNull(target.parentFile)
        assertTrue(parent.name.startsWith(".ssh-keygen-"), "the child did not receive temporary output ownership")
        assertFalse(java.nio.file.Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS),
            "temporary private output remains")
        assertFalse(java.nio.file.Files.exists(File(target.path + ".pub").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS),
            "temporary public output remains")
        assertFalse(java.nio.file.Files.exists(parent.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS),
            "the temporary generation directory remains")
    }

    private fun installChild(body: String): File {
        val lib = File(files, "controlled-lib").apply { assertTrue(mkdirs() || isDirectory) }
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = lib.absolutePath
        }
        return File(lib, "libssh-keygen.so").apply {
            writeText(
                "#!/bin/sh\nset -eu\nkey_file=\n" +
                    "while [ \"\$#\" -gt 0 ]; do\n" +
                    "  if [ \"\$1\" = -f ]; then shift; key_file=\$1; fi\n" +
                    "  shift\ndone\n" +
                    "[ -n \"\$key_file\" ]\n" +
                    "printf '%s\n' \"\$key_file\" > \"\$0.target\"\n" + body,
            )
            check(setExecutable(true))
        }
    }

}
