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
 * Two properties over the same directory.
 *
 * **A pair is whole or it is not there.** `ssh-keygen` writes the private key
 * before the public one, so an interruption between the two is a state this
 * method can produce on its own: the four-second timeout and its
 * `destroyForcibly`, an OOM kill, or the phantom-process limit. The guard tested
 * only the private key and then read the public one unconditionally, so every
 * later call took the "already exists" branch, threw `FileNotFoundException`, and
 * answered `{"success":false,"error":"<absolute path> (No such file or directory)"}`.
 * That is not a bad message, it is the end of the feature for that install:
 * nothing in the app regenerates, and the only way out was deleting the stranded
 * key from a terminal.
 *
 * **A public key line's comment is not reported.** Its third field is
 * conventionally `user@host` or an email address, which for any key the user
 * imported is a personal identifier. `listSshKeys` is one of the relay commands
 * and every answer on that channel is broadcast to whatever else is listening on
 * the workbench's origin, which is every installed web extension; nothing in this
 * app or in the bundled extensions ever read the field.
 *
 * The generation cases end at the spawn, deliberately. `ProcessBuilder` cannot
 * launch the bundled `ssh-keygen` on this classpath and is not asked to: what is
 * under test is the state of the directory on the way in and on the way out, and
 * a failed spawn is one of the failures that state has to survive.
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
    fun `a private key with no public half is not a dead end`() {
        // Exactly what a killed ssh-keygen leaves: the first of the two files.
        privateKey.writeText("PRIVATE KEY")

        val answer = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))

        // The spawn cannot succeed here, so what is asserted is which failure it
        // is. The old one was the read of a public key that is not there, and it
        // repeated for ever because nothing cleared what caused it.
        assertFalse(answer.getBoolean("success"))
        assertFalse(
            answer.getString("error").contains("id_ed25519.pub"),
            "the failure is still the unconditional read of a public key that is not " +
                "there, so every later call fails the same way: " + answer,
        )
        assertFalse(
            privateKey.exists(),
            "the half-written key is still in place, so the next call finds it, takes " +
                "the same branch and fails identically. Nothing in the app removes it.",
        )
    }

    @Test
    fun `a generation that fails leaves nothing behind for the next one to trip on`() {
        // Nothing on disk to begin with, which is the ordinary first call.
        val answer = JSONObject(bridge.generateSshKey(security.getSessionToken(), ""))

        assertFalse(answer.getBoolean("success"))
        assertFalse(privateKey.exists(), "a failed generation left a private key behind")
        assertFalse(publicKey.exists(), "a failed generation left a public key behind")
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
}
