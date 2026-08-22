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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

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
        // The map is process-wide, so a token this class parked in it outlives
        // the test that put it there and outlives this class. Nothing in
        // production leaves one behind -- every download drops its own in a
        // `finally` -- but these cases put them in by hand and never run a
        // download to take them out. Left there, the next class to install
        // `toolchain_java` is declined by a download that has never existed, and
        // it fails on an assertion that names no cause.
        outstanding().clear()
        unmockkAll()
    }

    // -- reflection helpers into the download bookkeeping --

    /**
     * The per-request cancellation tokens, which live on the companion rather
     * than on a manager: a rotation builds a second manager while the first
     * one's download carries on, and an instance field put the running
     * transfer's token out of everyone's reach.
     *
     * A manager is handed to `Field.get` even though the field is static, which
     * ignores it. That is deliberate: the same call reads an instance field too,
     * so putting the map back on the instance makes the case below fail on its
     * assertion rather than on reflection, and the failure names the behaviour
     * instead of the plumbing.
     */
    @Suppress("UNCHECKED_CAST")
    private fun outstanding(m: ToolchainManager = ToolchainManager(context)): MutableMap<String, Any> =
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
        val tokens = outstanding()
        val javaToken = newDownloadToken()
        val rubyToken = newDownloadToken()
        tokens["toolchain_java"] = javaToken
        tokens["toolchain_ruby"] = rubyToken

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
        val tokens = outstanding()
        val javaToken = newDownloadToken()
        val rubyToken = newDownloadToken()
        tokens["toolchain_java"] = javaToken
        tokens["toolchain_ruby"] = rubyToken

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
        val tokens = outstanding()
        val javaToken = newDownloadToken()
        tokens["toolchain_java"] = javaToken

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
        val tokens = outstanding()
        val rubyToken = newDownloadToken()
        tokens["toolchain_ruby"] = rubyToken

        manager.cancel("toolchain_java")

        assertFalse(isCancelled(rubyToken), "cancelling an idle pack reached a different one")
    }

    /**
     * A cancel reaches the download whichever manager began it.
     *
     * `ToolchainActivity` declares no `configChanges`, so a rotation mid-download
     * destroys the Activity and builds a second manager. The transfer carries on
     * inside the first one, and while the token map was an instance field it went
     * with the manager the user can no longer reach: not the rebuilt screen, not
     * `AndroidBridge`, whose lazily built manager is a third instance again.
     * Force-stopping the app was the only way to stop a download on mobile data.
     *
     * Making the map an instance field again turns this red.
     */
    @Test
    fun `a cancel reaches a download another manager started`() {
        val downloading = ToolchainManager(context)
        val token = newDownloadToken()
        outstanding(downloading)["toolchain_java"] = token

        ToolchainManager(context).cancel("toolchain_java")

        assertTrue(
            isCancelled(token),
            "a manager built after the screen was recreated could not reach the download " +
                "the first one started, so nothing short of a force-stop ends it",
        )
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

    // -- where a redirect is allowed to lead --

    /**
     * Redirects are followed by hand, and that is what removes the platform's own
     * refusal to follow `https` into `http`. `network_security_config.xml` permits
     * cleartext app-wide, for the loopback server the editor runs on, so nothing
     * underneath refuses the hop either.
     *
     * Both artifacts that decide whether a toolchain ZIP may be installed travel
     * this path: the payload, and the `sha256` manifest it is checked against,
     * whose URL is derived beside it. A chain that drops to cleartext therefore
     * carries the evidence and the thing it vouches for over the same unprotected
     * hop, and an on-path answer supplies a hostile archive together with a
     * manifest naming its digest. What that installs is unpacked into
     * `filesDir/usr` and turned into shell functions `.bashrc` sources for every
     * new terminal.
     */
    @Test
    fun `a redirect out of https into cleartext is refused`() {
        assertThrows(IOException::class.java) {
            nextRedirectUrl(
                "https://github.com/o/r/releases/latest/download/toolchain_java.zip",
                "http://cdn.example/toolchain_java.zip",
            )
        }
    }

    /** And by the relative form, which resolves against the current URL. */
    @Test
    fun `a relative redirect keeps the scheme it started from`() {
        assertEquals(
            "https://github.com/o/r/elsewhere.zip",
            nextRedirectUrl("https://github.com/o/r/toolchain_java.zip", "/o/r/elsewhere.zip"),
        )
    }

    /**
     * The control, and the reason this is a downgrade check rather than an
     * https-only one: an ordinary hop between two https addresses is what every
     * real download takes, and refusing it would refuse every install.
     */
    @Test
    fun `an ordinary https hop is followed`() {
        assertEquals(
            "https://objects.githubusercontent.com/signed",
            nextRedirectUrl(
                "https://github.com/o/r/releases/latest/download/toolchain_java.zip",
                "https://objects.githubusercontent.com/signed",
            ),
        )
    }

    /**
     * A chain that began in cleartext may stay there. Nothing is being protected
     * once it has started that way, and this is what keeps a loopback fixture --
     * and the editor's own `127.0.0.1` server -- reachable.
     */
    @Test
    fun `a cleartext chain is left alone`() {
        assertEquals(
            "http://127.0.0.1:9/tagged/toolchain_test.zip",
            nextRedirectUrl(
                "http://127.0.0.1:9/latest/toolchain_test.zip",
                "http://127.0.0.1:9/tagged/toolchain_test.zip",
            ),
        )
    }

    /** An upgrade is not a downgrade, and is followed like any other hop. */
    @Test
    fun `a redirect from cleartext into https is followed`() {
        assertEquals(
            "https://example/toolchain_test.zip",
            nextRedirectUrl("http://example/toolchain_test.zip", "https://example/toolchain_test.zip"),
        )
    }
}
