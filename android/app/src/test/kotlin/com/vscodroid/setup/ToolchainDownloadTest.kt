package com.vscodroid.setup

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Cancelling one toolchain download, and deciding whether a transfer arrived
 * whole.
 *
 * The cancellation half is reached by reflection because the state it is about
 * is internal by necessity -- the alternative would be exposing a download's
 * cancellation flag on the public surface so that a test could see it, which
 * makes the class worse to keep the test simpler.
 */
class ToolchainDownloadTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // -- reflection helpers into the download bookkeeping --

    @Suppress("UNCHECKED_CAST")
    private fun outstandingOf(m: ToolchainManager): MutableMap<String, Any> =
        ToolchainManager::class.java.getDeclaredField("httpDownloads")
            .apply { isAccessible = true }
            .get(m) as MutableMap<String, Any>

    private fun newDownloadToken(): Any =
        Class.forName("com.vscodroid.setup.ToolchainManager\$HttpDownload")
            .declaredConstructors
            .first()
            .apply { isAccessible = true }
            .newInstance()

    private fun isCancelled(token: Any): Boolean =
        token.javaClass.getDeclaredField("cancelled")
            .apply { isAccessible = true }
            .getBoolean(token)

    /**
     * The bug this replaced, stated as a test.
     *
     * There was one `@Volatile httpCancelled` for the whole class, so cancelling
     * Go set the flag every other download read. Ruby sitting queued behind it
     * on the same single-thread executor aborted at its first check, before it
     * had transferred a byte, and the user who cancelled one download lost two.
     *
     * Restoring a single shared flag turns this red on its second assertion.
     */
    @Test
    fun `cancelling one pack does not cancel another pack's download`() {
        val manager = ToolchainManager(context)
        val outstanding = outstandingOf(manager)
        val javaToken = newDownloadToken()
        val rubyToken = newDownloadToken()
        outstanding["toolchain_java"] = javaToken
        outstanding["toolchain_ruby"] = rubyToken

        manager.cancel("toolchain_java")

        assertTrue(isCancelled(javaToken), "the pack the user cancelled was not cancelled")
        assertFalse(
            isCancelled(rubyToken),
            "cancelling one pack cancelled another one's download, which is what a single " +
                "shared flag did to every queued toolchain"
        )
    }

    /**
     * `cancel` accepts either name form, the same as `install` and the removal
     * path, and has to reach the pack recorded under its pack name either way.
     */
    @Test
    fun `the short name form cancels the right pack`() {
        val manager = ToolchainManager(context)
        val outstanding = outstandingOf(manager)
        val javaToken = newDownloadToken()
        val rubyToken = newDownloadToken()
        outstanding["toolchain_java"] = javaToken
        outstanding["toolchain_ruby"] = rubyToken

        manager.cancel("java")

        assertTrue(isCancelled(javaToken), "cancel(\"java\") did not reach toolchain_java")
        assertFalse(isCancelled(rubyToken), "cancel(\"java\") reached toolchain_ruby")
    }

    /**
     * A name no toolchain answers to must not disturb a download in flight. The
     * lookup returns before anything is touched.
     */
    @Test
    fun `cancelling an unknown toolchain disturbs nothing`() {
        val manager = ToolchainManager(context)
        val outstanding = outstandingOf(manager)
        val javaToken = newDownloadToken()
        outstanding["toolchain_java"] = javaToken

        manager.cancel("toolchain_rust")

        assertFalse(isCancelled(javaToken), "an unknown name cancelled a running download")
    }

    /**
     * Cancelling a pack with nothing outstanding is a no-op rather than an
     * error: the toolchain screen offers Cancel from a state the download may
     * already have left.
     */
    @Test
    fun `cancelling a pack with no download outstanding is harmless`() {
        val manager = ToolchainManager(context)
        val outstanding = outstandingOf(manager)
        val rubyToken = newDownloadToken()
        outstanding["toolchain_ruby"] = rubyToken

        manager.cancel("toolchain_java")

        assertFalse(isCancelled(rubyToken), "cancelling an idle pack reached a different one")
    }

    /**
     * Each request carries its own flag object. This is what makes the isolation
     * structural rather than incidental -- there is no shared cell for one
     * download's bookkeeping to write into another's, and nothing resets a flag
     * that a running download has not read yet.
     */
    @Test
    fun `each download gets its own cancellation flag`() {
        assertNotSame(newDownloadToken(), newDownloadToken())
    }

    // -- transfer completeness --

    /**
     * A body that stopped early must not be treated as the file.
     *
     * `extractZip` reads entry by entry and is perfectly happy to extract a
     * truncated archive up to the cut, so what installs is a toolchain missing
     * whatever came after it -- recorded in `toolchains.json` as complete, with
     * the failure surfacing later as a binary that is not there.
     */
    @Test
    fun `a short body is not a complete transfer`() {
        assertFalse(isCompleteTransfer(declaredBytes = 179_000_000, receivedBytes = 12_000))
        assertFalse(isCompleteTransfer(declaredBytes = 100, receivedBytes = 99))
    }

    /**
     * Longer than declared is refused as well. It cannot happen on a well-behaved
     * connection, which is the reason to refuse it: something is wrong with the
     * transfer and the archive is not the one that was described.
     *
     * This is also what kills the tempting `receivedBytes >= declaredBytes`.
     */
    @Test
    fun `a body longer than declared is not a complete transfer`() {
        assertFalse(isCompleteTransfer(declaredBytes = 100, receivedBytes = 101))
    }

    @Test
    fun `an exact match is a complete transfer`() {
        assertTrue(isCompleteTransfer(declaredBytes = 179_000_000, receivedBytes = 179_000_000))
    }

    /**
     * No `Content-Length` means no evidence, and no evidence must not read as
     * evidence of failure. The header is optional and a chunked response omits
     * it; refusing those would fail-closed on downloads that are perfectly
     * healthy, which is a worse outcome than the one being prevented.
     *
     * Dropping the `declaredBytes <= 0` guard turns both of these red.
     */
    @Test
    fun `an absent content length accepts whatever arrived`() {
        assertTrue(isCompleteTransfer(declaredBytes = -1, receivedBytes = 179_000_000))
        assertTrue(isCompleteTransfer(declaredBytes = 0, receivedBytes = 179_000_000))
    }

    /**
     * The degenerate pairing, kept explicit: nothing declared and nothing
     * received is not a failure to report here. An empty body is caught further
     * along, where the ZIP fails to open.
     */
    @Test
    fun `an absent content length with an empty body is still accepted here`() {
        assertTrue(isCompleteTransfer(declaredBytes = -1, receivedBytes = 0))
    }
}
