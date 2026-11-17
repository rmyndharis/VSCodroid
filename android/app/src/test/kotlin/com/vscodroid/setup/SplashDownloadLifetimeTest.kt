package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import android.os.StatFs
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.SplashActivity
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class SplashDownloadLifetimeTest {
    @TempDir
    lateinit var filesDir: File

    private var manager: ToolchainManager? = null

    @AfterEach
    fun tearDown() {
        manager?.let {
            val executor = ToolchainManager::class.java.getDeclaredField("ioExecutor")
                .apply { isAccessible = true }.get(it) as ExecutorService
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "the install outlived its fixture")
        }
        queueRunning.set(null, false)
        unmockkAll()
    }

    // Process-wide, so a case that ends with it set would hide the picker from every later one.
    private val queueRunning =
        SplashActivity::class.java.getDeclaredField("downloadQueueRunning").apply { isAccessible = true }

    @Test
    fun `a destroyed splash keeps listening until every selected pack settles`() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns Long.MAX_VALUE
        val listeners = mutableSetOf<AssetPackStateUpdateListener>()
        val requests = mutableListOf<String>()
        val secondRequested = CountDownLatch(1)
        val packManager = mockk<AssetPackManager>(relaxed = true)
        every { packManager.registerListener(any()) } answers { listeners.add(firstArg()); Unit }
        every { packManager.unregisterListener(any()) } answers { listeners.remove(firstArg()); Unit }
        every { packManager.fetch(any()) } answers {
            synchronized(requests) { requests.addAll(firstArg<List<String>>()) }
            if ("toolchain_ruby" in firstArg<List<String>>()) secondRequested.countDown()
            mockk(relaxed = true)
        }
        every { packManager.removePack(any()) } answers { completedPackRemoval() }
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager
        val packages = mockk<PackageManager>(relaxed = true)
        every { packages.getInstallSourceInfo(any()) } returns mockk<InstallSourceInfo> {
            every { installingPackageName } returns "com.android.vending"
        }
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"
        every { context.packageManager } returns packages
        File(filesDir, "home/.vscodroid").mkdirs()
        val m = ToolchainManager(context).also { manager = it }
        val screen = spyk(mockk<SplashActivity>(relaxed = true), recordPrivateCalls = true)
        every { screen.isFinishing } returns true
        every { screen.isDestroyed } returns true
        every { screen.getString(any()) } returns "Done"
        every { screen.getColor(any()) } returns 0
        // What the queue records when it ends: the first-run picker counts as answered there.
        val answered = mutableListOf<Pair<String, Boolean>>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(any(), any()) } answers { answered += firstArg<String>() to secondArg<Boolean>(); editor }
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { preferences.edit() } returns editor
        every { preferences.getBoolean(any(), any()) } answers {
            answered.lastOrNull { it.first == firstArg<String>() }?.second ?: secondArg<Boolean>()
        }
        every { screen.getSharedPreferences(any(), any()) } returns preferences
        // The screen the system makes in place of the destroyed one (font size, language, display size),
        // and one in a process that never wrote the preference.
        val shouldShowPicker = SplashActivity::class.java.getDeclaredMethod("shouldShowPicker").apply { isAccessible = true }
        val again = spyk(mockk<SplashActivity>(relaxed = true), recordPrivateCalls = true)
        every { again.getSharedPreferences(any(), any()) } returns preferences
        val unanswered = spyk(mockk<SplashActivity>(relaxed = true), recordPrivateCalls = true)
        every { unanswered.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
        every { screen invokeNoArgs "launchMain" } returns Unit
        fun field(name: String, value: Any?) {
            SplashActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(screen, value)
        }
        field("tag", "SplashActivity")
        field("toolchainManager", m)
        field("downloadQueue", mutableListOf("toolchain_java", "toolchain_ruby"))
        field("currentDownloadIndex", 0)
        field("cancelled", false)
        val rowType = Class.forName("com.vscodroid.SplashActivity\$ProgressRow")
        val rowConstructor = rowType.getDeclaredConstructor(TextView::class.java, ProgressBar::class.java, TextView::class.java)
            .apply { isAccessible = true }
        val rows = mutableMapOf<String, Any>()
        for (pack in listOf("toolchain_java", "toolchain_ruby")) {
            val name = mockk<TextView>(relaxed = true)
            every { name.text } returns pack
            rows[pack] = rowConstructor.newInstance(name, mockk<ProgressBar>(relaxed = true), mockk<TextView>(relaxed = true))
        }
        field("progressRows", rows)
        val state = SplashActivity::class.java.getDeclaredMethod(
            "handleDownloadState", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        m.onStateChange = { pack, status, percent, _, _ -> state.invoke(screen, pack, status, percent) }
        // What startDownloads does before it asks for the first pack.
        queueRunning.set(null, true)
        m.registerListener()
        m.install("toolchain_java")

        val destroy = SplashActivity::class.java.getDeclaredMethod("onDestroy").apply { isAccessible = true }
        try {
            destroy.invoke(screen)
        } catch (e: InvocationTargetException) {
            // Framework state is absent on a JVM mock; the real override runs before its super call.
            val cause = e.cause ?: throw e
            assertTrue(
                cause is RuntimeException &&
                    cause.message?.startsWith("Method onDestroy in android.app.Activity not mocked.") == true,
                "unexpected teardown failure: $cause",
            )
        }
        assertEquals(1, listeners.size, "teardown removed the listener of the pack still downloading")

        val delivered = File(filesDir, "delivered").apply { mkdirs() }
        File(delivered, "toolchain_java.json").writeText("""{"name":"java","installRoot":"usr/lib/jvm/test"}""")
        File(delivered, "usr/lib/jvm/test").mkdirs()
        File(delivered, "usr/lib/jvm/test/payload").writeText("java payload")
        every { packManager.getPackLocation("toolchain_java") } returns mockk {
            every { assetsPath() } returns delivered.path
        }
        fun update(pack: String, status: Int): AssetPackState = mockk {
            every { name() } returns pack
            every { status() } returns status
            every { totalBytesToDownload() } returns 1L
            every { bytesDownloaded() } returns 1L
            every { errorCode() } returns 0
        }
        listeners.toList().forEach { it.onStateUpdate(update("toolchain_java", AssetPackStatus.COMPLETED)) }
        assertTrue(secondRequested.await(5, TimeUnit.SECONDS), "the completed pack did not start the next selected pack")
        assertEquals("java payload", File(filesDir, "usr/lib/jvm/test/payload").readText())
        assertEquals(listOf("toolchain_java", "toolchain_ruby"), synchronized(requests) { requests.toList() })
        assertEquals(emptyList<Pair<String, Boolean>>(), answered, "the picker was answered while a pack was still to be fetched")
        assertFalse(
            shouldShowPicker.invoke(again) as Boolean,
            "a splash made again while Ruby was still downloading offered the picker over it, and a second " +
                "Continue would start a second queue",
        )
        listeners.toList().forEach { it.onStateUpdate(update("toolchain_ruby", AssetPackStatus.CANCELED)) }
        assertTrue(listeners.isEmpty(), "the finished queue kept its Play listener")
        assertEquals(listOf("toolchain_picker_shown" to true), answered, "the end of the queue did not answer the picker")
        assertFalse(m.onStateChange != null, "the finished queue kept the departed screen callback")
        assertFalse(shouldShowPicker.invoke(again) as Boolean, "the picker answered by the end of the queue is offered again")
        assertTrue(
            shouldShowPicker.invoke(unanswered) as Boolean,
            "the queue ended with no splash left and still keeps the picker down for the rest of the process",
        )
    }
}
