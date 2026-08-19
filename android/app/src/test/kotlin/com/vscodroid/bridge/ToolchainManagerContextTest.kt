package com.vscodroid.bridge

import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Which Context the bridge's `ToolchainManager` is built with.
 *
 * `install()` hands a listener to Play Core, which keeps it in a registry that
 * lives in that library and outlives every Activity here. The listener holds the
 * manager, so a manager built with the Activity keeps the Activity, its Context
 * and its view tree reachable for as long as the registration stands.
 * `AndroidBridge.onToolchainState` removes the registration only on COMPLETED,
 * FAILED or CANCELED, and a REQUIRES_USER_CONFIRMATION waiting on a dialog the
 * user walked away from reaches none of them.
 *
 * **The reference is the defect, not the registration**, and that distinction is
 * why this file asserts what it does. Releasing the registration at teardown
 * looks like the obvious fix and is worse than the leak: `installFromDirectory`
 * is reachable only from `handleStateUpdate`'s COMPLETED branch, nothing
 * reconciles pack state at launch (`getPackStates` appears nowhere in main), and
 * Play Core does not promise to re-emit a state to a listener that registers
 * afterwards. Unregistering mid-download would leave the pack downloaded and
 * never installed, with no error anywhere the user could see. Such a call would
 * also have had to survive `recreateWebView()`, which builds a second bridge
 * after a renderer crash and would orphan the first one's registration.
 *
 * So the assertion is about the Context handed over, which is what decides
 * retention, and it is made at the call Play Core actually receives rather than
 * by reading the source.
 */
class ToolchainManagerContextTest {

    private lateinit var packManager: AssetPackManager
    private lateinit var activity: android.content.Context
    private lateinit var application: android.content.Context

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs

        packManager = mockk(relaxed = true)
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        val filesDir = File(System.getProperty("java.io.tmpdir"), "tcmc")
        application = mockk(relaxed = true)
        every { application.filesDir } returns filesDir
        every { application.applicationContext } returns application

        activity = mockk(relaxed = true)
        every { activity.filesDir } returns filesDir
        every { activity.applicationContext } returns application
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun bridge() = AndroidBridge(
        context = activity,
        security = mockk<SecurityManager>(relaxed = true).also { every { it.validateToken(any()) } returns true },
        clipboard = mockk(relaxed = true),
        onBackPressed = mockk(relaxed = true),
        onMinimize = mockk(relaxed = true),
        onOpenFolderPicker = mockk(relaxed = true),
        onOpenRecentFolder = mockk(relaxed = true),
        onShowAbout = mockk(relaxed = true),
        safManager = mockk<SafStorageManager>(relaxed = true),
    )

    @Test
    fun `the manager whose listener escapes into Play Core holds no Activity`() {
        // The fixture is its own control: the two Contexts are distinct objects
        // and the Activity answers `applicationContext` with the other one, so an
        // assertion that passes has actually told them apart.
        assertNotSame(activity, application, "the fixture handed out one Context, so it proves nothing")

        bridge().getInstalledToolchains("")

        verify { AssetPackManagerFactory.getInstance(application) }
        verify(exactly = 0) { AssetPackManagerFactory.getInstance(activity) }
    }

    @Test
    fun `the bridge itself keeps the Activity, because its dialog needs one`() {
        // The other half, and why this is not simply "use the application context
        // everywhere". `confirmLargeDownload` resolves `context as? Activity` and
        // hands it to `showConfirmationDialog`, which needs a real Activity to
        // show Play's own cellular-data prompt. Only the manager, whose listener
        // outlives the Activity, is given the application context.
        //
        // Holding one here retains nothing extra: the bridge is reachable only
        // from the WebView, which the Activity owns and destroys.
        val b = bridge()

        val field = AndroidBridge::class.java.getDeclaredField("context").apply { isAccessible = true }

        assertSame(
            activity, field.get(b),
            "the bridge was given the application context, so `confirmLargeDownload` " +
                "can no longer resolve an Activity and every large download is diverted " +
                "to the toolchain screen instead of asking where the user already is.",
        )
    }
}
