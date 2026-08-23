package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

/**
 * A setup that could not unpack the server tree must not report success.
 *
 * The server tree, the four bootstrap scripts and `usr/` are what start the
 * server and resolve everything on PATH. `extractAssetFile` logs a failed copy
 * and carries on, which is right when one file of a 390 MB unpack is missing
 * from the APK and wrong when the copy failed: the install reached the editor,
 * could never serve it, and `markSetupComplete()` certified that -- with
 * `isFirstRun()` keyed on versionName or versionCode, so nothing tried again
 * until the app updated.
 *
 * These drive the real `runSetup()`. The failure is arranged the way the rest of
 * this package arranges it, by occupying the temporary path `writeAtomically`
 * derives from its destination, which fails the write without depending on file
 * permissions.
 */
class SetupAbortsOnIncompleteTreeTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var cacheDir: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private lateinit var editor: SharedPreferences.Editor

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // One asset under the server tree, and nothing else in the APK. Every
        // other extraction answers "absent", which is not a failure.
        assets = mockk()
        every { assets.list(any()) } returns emptyArray()
        every { assets.open(any()) } throws java.io.IOException("absent")
        every { assets.list("vscode-reh") } returns arrayOf("server-main.js")
        every { assets.list("vscode-reh/server-main.js") } returns emptyArray()
        every { assets.open("vscode-reh/server-main.js") } answers
            { ByteArrayInputStream("// the server".toByteArray()) }

        editor = mockk(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getInt(any(), any()) } returns 0

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    /** Non-empty, so the cleanup delete() cannot quietly reclaim it. */
    private fun blockTheServerWrite() {
        val dest = File(filesDir, "server/vscode-reh/server-main.js")
        assertTrue(dest.parentFile!!.mkdirs(), "could not stage the server directory")
        val blocker = File(dest.parentFile, "${dest.name}.tmp~")
        assertTrue(blocker.mkdirs(), "could not stage the blocked temp path")
        File(blocker, "occupied").writeText("x")
    }

    /**
     * The control, and it is what makes the assertion below mean anything: with
     * nothing in the way the same run reports success, so a failure result is
     * the obstruction and not the harness.
     */
    @Test
    fun `a setup that unpacks what the APK carries reports success`() {
        val result = runBlocking { FirstRunSetup(context).runSetup() }

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result)
        assertEquals("// the server", File(filesDir, "server/vscode-reh/server-main.js").readText())
        verify { editor.putString(SETUP_VERSION_KEY, any()) }
    }

    @Test
    fun `a setup that cannot unpack the server tree reports an error`() {
        blockTheServerWrite()

        val result = runBlocking { FirstRunSetup(context).runSetup() }

        assertNotEquals(
            FirstRunSetup.SetupResult.SUCCESS,
            result,
            "setup reported success with a file of the server tree missing, so the install " +
                "reaches the editor and can never serve it",
        )
    }

    /**
     * The other way the tree can come up short, and the one that used to be
     * reported as a complete install.
     *
     * `AssetManager.list()` answers an empty array for a path that is not there,
     * so null means the platform itself could not answer. `extractAssetDir` read
     * that as an empty directory, which is indistinguishable from a leaf file, so
     * it returned true: nothing was written, nothing was collected as incomplete,
     * `markSetupComplete()` ran, and `isFirstRun()` went false for the life of
     * the install with no server tree under it.
     *
     * NEGATIVE CONTROL: restore `?: emptyArray()` in `extractAssetDir`. The run
     * then reports SUCCESS with `server/vscode-reh` never created, and both
     * assertions fail.
     */
    @Test
    fun `a server tree the platform cannot list is not certified as unpacked`() {
        every { assets.list("vscode-reh") } returns null

        val result = runBlocking { FirstRunSetup(context).runSetup() }

        assertNotEquals(
            FirstRunSetup.SetupResult.SUCCESS,
            result,
            "a listing the platform could not answer was read as an unpacked tree",
        )
        verify(exactly = 0) { editor.putString(SETUP_VERSION_KEY, any()) }
    }

    /**
     * And the same one level down, which is not a corollary: `extractAssetDir`
     * recurses into itself, so the refusal above is reached once per path in the
     * tree and a leaf is where all but a handful of them are.
     *
     * Aborting there too is the intended answer, and it is pinned here rather
     * than left to be inferred from a case that only stubs the root. Degrading a
     * leaf to the old `?: emptyArray()` sends it to `extractAssetFile`, which is
     * harmless exactly when the asset can still be opened and is the original
     * defect when it cannot: an asset it fails to open is reported as absent,
     * which answers true, so an unanswerable listing would certify a tree with a
     * file missing from it. The same reasoning as at the root, one level down.
     *
     * NEGATIVE CONTROL: restore `?: emptyArray()` in `extractAssetDir`. This
     * fixture can open the asset, so the leaf extracts and the run reports
     * SUCCESS, which is what both assertions below refuse.
     */
    @Test
    fun `an entry under the server tree the platform cannot list is not certified either`() {
        every { assets.list("vscode-reh/server-main.js") } returns null

        val result = runBlocking { FirstRunSetup(context).runSetup() }

        assertNotEquals(
            FirstRunSetup.SetupResult.SUCCESS,
            result,
            "a listing the platform could not answer was read as an unpacked file",
        )
        assertFalse(
            File(filesDir, "server/vscode-reh/server-main.js").exists(),
            "nothing was written, so reporting anything but a failure certifies an empty tree",
        )
        verify(exactly = 0) { editor.putString(SETUP_VERSION_KEY, any()) }
    }

    /**
     * And it must not be marked complete, which is the half that makes the
     * failure permanent rather than merely visible. `isFirstRun()` is keyed on
     * versionName or versionCode, so a run recorded as complete is never repeated
     * until the app updates -- the error screen would be shown once and then
     * forgotten.
     */
    @Test
    fun `a setup that cannot unpack the server tree is not recorded as complete`() {
        blockTheServerWrite()

        runBlocking { FirstRunSetup(context).runSetup() }

        // Named rather than `any()`, and that is not a loosening. A run also
        // records the build whose extraction is in flight, before the first byte
        // is copied, so that a retry of the same build may skip the files it
        // already wrote; that write is expected on a failed run and is the whole
        // point of it. What must not be written is the completion record.
        verify(exactly = 0) { editor.putString(SETUP_VERSION_KEY, any()) }
    }

    /**
     * And the failure has to be able to say what it was.
     *
     * The description itself is pinned by `SetupFailureCauseTest`, which drives
     * the pure function directly. What that cannot show is whether the catch ever
     * fills it in, and a describer nothing calls is the whole defect wearing a
     * fix: the screen falls back to "Setup failed" and the user is exactly where
     * they started. This drives the real run to a real failure and reads what was
     * left behind.
     */
    @Test
    fun `a failed setup leaves the cause behind for the screen to read`() {
        blockTheServerWrite()
        val setup = FirstRunSetup(context)

        runBlocking { setup.runSetup() }

        val failure = setup.lastFailure
        assertNotNull(failure, "setup failed and left nothing for the screen to show")
        assertTrue(
            failure!!.step.isNotEmpty(),
            "the failure names no step, so the screen can only say that something went wrong",
        )
        assertTrue(
            failure.detail.isNotEmpty(),
            "the failure carries no detail, so the exception still reaches only logcat",
        )
    }

    /**
     * The control. A run that succeeds must leave nothing behind, or the next
     * failure screen would show a cause from a run that worked.
     */
    @Test
    fun `a setup that succeeds leaves no cause behind`() {
        val setup = FirstRunSetup(context)

        runBlocking { setup.runSetup() }

        assertNull(setup.lastFailure, "a successful run recorded a failure cause")
    }

    /**
     * And the cause has to say what the failure WAS, not only that the tree is
     * short.
     *
     * `writeAtomically` caught its IOException, deleted the temporary file and
     * returned false without keeping the message, so the one failure this whole
     * subsystem is built around -- a disk that fills partway through -- reached
     * the user as "could not unpack vscode-reh" with the words "No space left on
     * device" nowhere in the string or in logcat, on a screen whose only control
     * is Retry.
     *
     * Asserted on the separator rather than on the operating system's wording:
     * with no cause the message ends at the tree's name, so the colon and a
     * following character is exactly the difference the fix makes, and it does
     * not depend on how a JVM phrases a write into an occupied path.
     */
    @Test
    fun `the failure detail carries the cause of the write that failed`() {
        blockTheServerWrite()
        val setup = FirstRunSetup(context)

        runBlocking { setup.runSetup() }

        val detail = setup.lastFailure!!.detail
        assertTrue(
            detail.contains("could not unpack vscode-reh: "),
            "the failure says only which tree is short and not why the write failed: $detail",
        )
    }

    private companion object {
        /** The completion record, as [FirstRunSetup] spells it. */
        const val SETUP_VERSION_KEY = "setup_version"
    }
}
