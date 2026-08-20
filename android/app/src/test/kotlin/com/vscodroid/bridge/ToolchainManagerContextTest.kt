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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What the bridge's `ToolchainManager` is allowed to hold.
 *
 * `install()` hands a listener to Play Core, which keeps it in a registry that
 * lives in that library and outlives every Activity here. The listener holds the
 * manager, so anything the manager holds keeps the Activity, its Context and its
 * view tree reachable for as long as the registration stands. The state callback
 * removes the registration only on COMPLETED, FAILED or CANCELED, and a
 * REQUIRES_USER_CONFIRMATION waiting on a dialog the user walked away from
 * reaches none of them.
 *
 * Two tests, and the second exists because the first is not enough. Naming the
 * constructor argument binds one link; the leak had two, and the one this file
 * originally missed was a lambda. Read them in order.
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

    /**
     * Nothing reachable from the manager is the Activity, by any route.
     *
     * The test above binds one link, the constructor argument, and that is exactly as
     * far as it goes. It passed while the leak was intact: `onStateChange` was
     * `{ ... -> onToolchainState(...) }`, an unqualified call to a member of
     * `AndroidBridge`, so the lambda captured the bridge and the bridge holds the
     * Activity. Measured in bytecode, the lambda compiled to a method whose first
     * parameter is `AndroidBridge`.
     *
     * So the question is asked of the object graph rather than of one argument. This is
     * the same lesson `ServiceWorkerRetentionTest` records in `MainActivity` and states
     * outright: a supplier that only calls a method captures just as completely as one
     * that names a field. A whitelist of field names would have missed this too.
     *
     * `WeakReference` and friends are not walked through, which is the point rather than
     * an exemption: a weak reference is not retention, and the fix is precisely to hold
     * the bridge through one.
     *
     * ⚠️ Its ceiling. This walks declared fields, so it sees what the JVM sees and not
     * what a native or `ThreadLocal` route might hold, and it stops at [NODE_CAP]. The
     * negative control below is what keeps a walk that quietly reached nothing from
     * reading as a pass.
     */
    @Test
    fun `no route from the manager reaches the Activity`() {
        val b = bridge()
        b.getInstalledToolchains("")

        val manager = AndroidBridge::class.java
            .getDeclaredMethod("getToolchainManager")
            .apply { isAccessible = true }
            .invoke(b)!!

        // The control, and it runs first. The bridge legitimately holds the Activity
        // for `confirmLargeDownload`, so a walk that cannot find it from there is
        // broken, and every assertion after it would pass by seeing nothing.
        assertTrue(
            reaches(b, activity),
            "the walk cannot find the Activity even from the bridge, which holds it " +
                "directly, so it proves nothing about the manager",
        )

        assertFalse(
            reaches(manager, activity),
            "the Activity is reachable from the ToolchainManager. Play Core keeps the " +
                "listener, the listener keeps the manager, so this keeps the Activity " +
                "and its view tree alive for the life of the process. Anything the " +
                "manager holds must reach a screen weakly or not at all.",
        )
    }

    /** Whether [target] is reachable from [root] by strong references alone. */
    private fun reaches(root: Any, target: Any): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<Any>()
        queue.add(root)
        seen.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < NODE_CAP) {
            val node = queue.removeFirst()
            visited++
            if (node === target) return true

            // A weak reference does not retain, so following it would report the very
            // shape this test exists to accept.
            if (node is java.lang.ref.Reference<*>) continue

            if (node is Array<*>) {
                node.filterNotNull().forEach { if (seen.add(it)) queue.add(it) }
                continue
            }

            var cls: Class<*>? = node.javaClass
            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    if (f.type.isPrimitive) continue
                    val v = try {
                        f.isAccessible = true
                        f.get(node)
                    } catch (e: Throwable) {
                        // A field the JDK refuses to open is one this cannot speak for.
                        // Skipped rather than failed: the control above is what says
                        // whether enough of the graph was reachable to mean anything.
                        null
                    } ?: continue
                    if (v === target) return true
                    if (seen.add(v)) queue.add(v)
                }
                cls = cls.superclass
            }
        }
        return false
    }

    private companion object {
        /** Enough for this graph; a bound so a cycle cannot hang the suite. */
        const val NODE_CAP = 20_000
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
