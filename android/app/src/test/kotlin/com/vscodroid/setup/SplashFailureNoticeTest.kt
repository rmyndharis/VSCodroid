package com.vscodroid.setup

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import android.os.StatFs
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.R
import com.vscodroid.SplashActivity
import com.vscodroid.util.AppNotices
import com.vscodroid.util.Logger
import com.vscodroid.util.fitsInToast
import com.vscodroid.util.padForSystemBars
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Why a first-run toolchain failed, said on the screen that shows the failure.
 *
 * The progress row has room for one word, so the reason is a notice. The queue starts the
 * next pack the moment one settles, and after the last one it ends and starts the editor,
 * which finishes this screen. The reason used to be worked out after the row was painted and
 * the queue advanced, behind a test of whether the screen was finishing: for the last pack it
 * always was, so the one reason that mattered most (a single toolchain ticked on a full disk)
 * was never said, and the editor opened over a row that had flashed "Failed". A reason too
 * long for a toast goes on a dialog, and the screen's finish took that dialog down unread,
 * whichever pack it was about.
 *
 * Driven through the real screen methods (startDownloads, the state callback it installs,
 * handleDownloadState, downloadNext, endDownloadQueue, launchMain) and a real
 * [ToolchainManager] on the Play path, with Play's answers and the framework stubbed.
 */
class SplashFailureNoticeTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var screen: SplashActivity
    private val listeners = mutableSetOf<AssetPackStateUpdateListener>()
    private val events = mutableListOf<String>()
    private val notices = mutableListOf<String>()
    private var finishing = false
    private var dialogUp = false
    private var afterDialog: (() -> Unit)? = null

    private val queueRunning =
        SplashActivity::class.java.getDeclaredField("downloadQueueRunning").apply { isAccessible = true }

    private companion object {
        const val STORAGE_TEXT = "Not enough storage. Free some space and try again."
        const val PLAY_TEXT =
            "Play delivers this toolchain only to apps it installed. Install from the Play Store and try again."
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns Long.MAX_VALUE
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setData(any()) } returns mockk(relaxed = true)
        mockkStatic("com.vscodroid.util.ViewInsetsKt")
        every { any<View>().padForSystemBars(any()) } just Runs

        val packManager = mockk<AssetPackManager>(relaxed = true)
        every { packManager.registerListener(any()) } answers { listeners.add(firstArg()); Unit }
        every { packManager.unregisterListener(any()) } answers { listeners.remove(firstArg()); Unit }
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

        // What a notice does on the real screen, from Android 12 on: a text that fits is a toast,
        // which outlives the screen; a longer one is a dialog on it, which the screen's finish
        // takes down.
        mockkObject(AppNotices)
        every { AppNotices.showNotice(any(), any<CharSequence>(), any(), any(), any(), any()) } answers {
            val text = secondArg<CharSequence>()
            notices += text.toString()
            if (!fitsInToast(text, fontScale = 1f, sdkInt = 31)) dialogUp = true
        }
        every { AppNotices.afterDialog(any(), any()) } answers {
            if (dialogUp) afterDialog = secondArg()
            dialogUp
        }

        screen = mockk(relaxed = true)
        fun field(name: String, value: Any?) {
            SplashActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(screen, value)
        }
        field("tag", "SplashActivity")
        field("progressRows", mutableMapOf<String, Any>())
        field("downloadQueue", mutableListOf<String>())
        every { screen.applicationContext } returns context
        every { screen.isFinishing } answers { finishing }
        every { screen.isDestroyed } returns false
        every { screen.finish() } answers { events += "finish"; finishing = true }
        every { screen.startActivity(any<Intent>()) } answers { events += "editor" }
        every { screen["publishToolchainShortcut"]() } answers { events += "shortcut" }
        every { screen.runOnUiThread(any()) } answers { firstArg<Runnable>().run() }
        every { screen.getString(any()) } returns "status"
        every { screen.getString(R.string.toolchain_failed_storage) } returns STORAGE_TEXT
        every { screen.getString(R.string.toolchain_failed_play) } returns PLAY_TEXT
        every { screen.findViewById<View>(R.id.progressRoot) } returns mockk(relaxed = true)
        every { screen.findViewById<LinearLayout>(R.id.progressContainer) } returns mockk(relaxed = true)
        every { screen.findViewById<Button>(R.id.cancelButton) } returns mockk(relaxed = true)
        val rowType = Class.forName("com.vscodroid.SplashActivity\$ProgressRow")
        val rowConstructor = rowType.getDeclaredConstructor(TextView::class.java, ProgressBar::class.java, TextView::class.java)
            .apply { isAccessible = true }
        every { screen["buildProgressRow"](any<ToolchainRegistry.ToolchainInfo>()) } answers {
            Pair(
                mockk<View>(relaxed = true),
                rowConstructor.newInstance(mockk<TextView>(relaxed = true), mockk<ProgressBar>(relaxed = true), mockk<TextView>(relaxed = true)),
            )
        }
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { preferences.edit() } returns mockk(relaxed = true)
        every { screen.getSharedPreferences(any(), any()) } returns preferences
        every { screen["startDownloads"](any<List<String>>()) } answers { callOriginal() }
        every { screen["handleDownloadState"](any<String>(), any<Int>(), any<Int>()) } answers { callOriginal() }
        every { screen["downloadNext"]() } answers { callOriginal() }
        every { screen["endDownloadQueue"]() } answers { callOriginal() }
        every { screen["launchMain"]() } answers { callOriginal() }
    }

    @AfterEach
    fun tearDown() {
        val manager = SplashActivity::class.java.getDeclaredField("toolchainManager")
            .apply { isAccessible = true }.get(screen) as ToolchainManager?
        manager?.let {
            val executor = ToolchainManager::class.java.getDeclaredField("ioExecutor")
                .apply { isAccessible = true }.get(it) as ExecutorService
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
        queueRunning.set(null, false)
        unmockkAll()
    }

    private fun startDownloads(vararg packs: String) {
        SplashActivity::class.java.getDeclaredMethod("startDownloads", List::class.java)
            .apply { isAccessible = true }.invoke(screen, packs.toList())
    }

    private fun failed(pack: String, errorCode: Int) {
        val state = mockk<AssetPackState> {
            every { name() } returns pack
            every { status() } returns AssetPackStatus.FAILED
            every { totalBytesToDownload() } returns 1L
            every { bytesDownloaded() } returns 0L
            every { errorCode() } returns errorCode
        }
        listeners.toList().forEach { it.onStateUpdate(state) }
    }

    @Test
    fun `the last pack's reason is said before the end of the queue starts the editor`() {
        startDownloads("toolchain_ruby")

        failed("toolchain_ruby", AssetPackErrorCode.INSUFFICIENT_STORAGE)

        assertEquals(
            listOf(STORAGE_TEXT), notices,
            "the only pack ticked failed on a full disk and nothing said why: the reason was read " +
                "after the queue had ended and finished the screen",
        )
        assertEquals(listOf("editor", "shortcut", "finish"), events, "the end of the queue did not start the editor")
    }

    /**
     * The other side: the queue outlives the screen, and a failure that lands after the person has
     * pressed Back would put its sentence over the editor, about a screen that is gone.
     */
    @Test
    fun `a failure after the screen has gone says nothing`() {
        startDownloads("toolchain_ruby")
        finishing = true

        failed("toolchain_ruby", AssetPackErrorCode.INSUFFICIENT_STORAGE)

        assertEquals(emptyList<String>(), notices, "a reason was said over whatever the person went to")
        assertEquals(listOf("shortcut"), events, "a screen that is gone started the editor")
    }

    @Test
    fun `the editor waits for a reason on a dialog to be read`() {
        startDownloads("toolchain_java", "toolchain_ruby")

        // The first reason is too long for a toast and goes on a dialog; the second is a toast.
        failed("toolchain_java", AssetPackErrorCode.APP_NOT_OWNED)
        failed("toolchain_ruby", AssetPackErrorCode.INSUFFICIENT_STORAGE)

        assertEquals(listOf(PLAY_TEXT, STORAGE_TEXT), notices)
        assertEquals(
            emptyList<String>(), events.filter { it != "shortcut" },
            "the end of the queue started the editor and finished the screen, which takes down the " +
                "dialog that says why the first toolchain failed before anyone has read it",
        )
        assertNotNull(afterDialog, "nothing waits for the dialog to be closed")
        val waiting = afterDialog!!

        dialogUp = false
        waiting()

        assertEquals(listOf("editor", "finish"), events.filter { it != "shortcut" }, "closing the dialog did not start the editor")
    }
}
