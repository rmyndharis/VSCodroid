package com.vscodroid

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.widget.ProgressBar
import android.widget.TextView
import com.vscodroid.setup.FirstRunSetup
import com.vscodroid.setup.SetupStepStrings
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SetupProgressIndeterminateTest {

    @TempDir lateinit var filesDir: File
    @TempDir lateinit var cacheDir: File

    private data class ScreenWidgets(
        val statusText: TextView,
        val progressBar: ProgressBar,
        val states: MutableList<Pair<Boolean, Int>>,
        @Volatile var indeterminate: Boolean,
        @Volatile var progress: Int,
        @Volatile var text: String,
    )

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun fakeWidgets(): ScreenWidgets {
        val states = mutableListOf<Pair<Boolean, Int>>()
        var indeterminate = true
        var progress = 0
        var text = ""
        lateinit var ref: ScreenWidgets

        val statusText = mockk<TextView>(relaxed = true)
        every { statusText.setText(any<CharSequence>()) } answers {
            text = firstArg<CharSequence>().toString()
            ref.text = text
        }

        val progressBar = mockk<ProgressBar>(relaxed = true)
        every { progressBar.isIndeterminate = any() } answers {
            indeterminate = firstArg()
            ref.indeterminate = indeterminate
        }
        every { progressBar.isIndeterminate } answers { indeterminate }
        every { progressBar.progress = any() } answers {
            progress = firstArg()
            ref.progress = progress
            states += indeterminate to progress
        }
        every { progressBar.progress } answers { progress }

        ref = ScreenWidgets(statusText, progressBar, states, indeterminate, progress, text)
        return ref
    }

    @Test
    fun `applySetupProgress stays indeterminate at zero percent and turns determinate above zero`() {
        val widgets = fakeWidgets()

        applySetupProgress(widgets.statusText, widgets.progressBar, "Checking...", 0)
        assertTrue(widgets.indeterminate)
        assertEquals(0, widgets.progress)
        assertEquals("Checking...", widgets.text)

        applySetupProgress(widgets.statusText, widgets.progressBar, "Extracting...", 12)
        assertFalse(widgets.indeterminate)
        assertEquals(12, widgets.progress)

        applySetupProgress(widgets.statusText, widgets.progressBar, "Restarting...", 0)
        assertTrue(widgets.indeterminate)
        assertEquals(0, widgets.progress)
    }

    @Test
    fun `progress bar stays indeterminate before first report and clears on percent above zero including replay`() {
        val assets = mockk<AssetManager>()
        val sawPositiveProgress = AtomicBoolean(false)
        val midExtractReached = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val payloads = mapOf(
            "vscode-reh/a.js" to "// a".toByteArray(),
            "usr/tool" to "// tool".toByteArray(),
        )
        every { assets.list(any()) } returns emptyArray()
        every { assets.list("") } returns arrayOf("vscode-reh", "usr")
        every { assets.list("vscode-reh") } returns arrayOf("a.js")
        every { assets.list("usr") } returns arrayOf("tool")
        every { assets.open(any()) } answers {
            val path = firstArg<String>()
            if (path == "usr/tool" && sawPositiveProgress.get()) {
                midExtractReached.countDown()
                check(allowFinish.await(15, TimeUnit.SECONDS))
            }
            ByteArrayInputStream(payloads[path] ?: throw IOException("absent: $path"))
        }

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getInt(any(), any()) } returns 0

        val pm = mockk<PackageManager>(relaxed = true)
        val info = mockk<PackageInfo>(relaxed = true).apply {
            versionName = "1.9.0"
            lastUpdateTime = 1_000L
        }
        every { info.longVersionCode } returns 190L
        @Suppress("DEPRECATION")
        every { pm.getPackageInfo("com.vscodroid", 0) } returns info

        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.packageName } returns "com.vscodroid"
        every { context.packageManager } returns pm
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = File(filesDir, "native").path
        }
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        SetupStepStrings.stub(context)

        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        // Before any progress report arrives (during extractionSpace preflight), a freshly
        // created screen starts indeterminate and stays indeterminate after attach.
        val firstScreen = fakeWidgets()
        val firstSink: (String, Int) -> Unit = { msg, pct ->
            applySetupProgress(firstScreen.statusText, firstScreen.progressBar, msg, pct)
            if (pct > 0) sawPositiveProgress.set(true)
        }
        setup.onProgress = firstSink
        assertTrue(firstScreen.indeterminate)

        val pool = Executors.newSingleThreadExecutor()
        val task = FutureTask { runBlocking { setup.runSetup() } }
        pool.execute(task)
        try {
            assertTrue(midExtractReached.await(10, TimeUnit.SECONDS))
            synchronized(firstScreen.states) {
                assertTrue(firstScreen.states.any { !it.first && it.second > 0 })
            }
            assertFalse(firstScreen.indeterminate)

            // Recreate the Activity mid-run: new screen starts indeterminate, then
            // attachProgress replays lastProgress with percent > 0 so it becomes determinate.
            FirstRunSetup.detachProgress(firstSink)
            val recreatedScreen = fakeWidgets()
            assertTrue(recreatedScreen.indeterminate)
            val recreatedSink: (String, Int) -> Unit = { msg, pct ->
                applySetupProgress(recreatedScreen.statusText, recreatedScreen.progressBar, msg, pct)
            }
            FirstRunSetup(context).onProgress = recreatedSink
            assertFalse(recreatedScreen.indeterminate)
            assertTrue(recreatedScreen.progress > 0)

            allowFinish.countDown()
            assertEquals(FirstRunSetup.SetupResult.SUCCESS, task.get(10, TimeUnit.SECONDS))
            assertFalse(recreatedScreen.indeterminate)
            assertEquals(100, recreatedScreen.progress)
        } finally {
            allowFinish.countDown()
            FirstRunSetup(context).onProgress = null
            pool.shutdownNow()
        }
    }

    @Test
    fun `SplashActivity sets indeterminate at start and retry and delegates progress updates`() {
        val source = SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt")
        val onCreate = SourceScan.withoutComments(SourceScan.body(source, "override fun onCreate("))
        val showSetupError = SourceScan.withoutComments(SourceScan.body(source, "private fun showSetupError("))

        assertTrue("progressBar.isIndeterminate = true" in onCreate)
        assertTrue("applySetupProgress(statusText, progressBar, message, percent)" in onCreate)
        assertTrue("progressBar.isIndeterminate = true" in showSetupError)
    }

    private fun setupProgressContext(
        usableSpaceBytes: Long? = null,
        previousVersion: Int = 0,
    ): Pair<Context, AssetManager> {
        val assets = mockk<AssetManager>()
        every { assets.list(any()) } returns emptyArray()
        every { assets.open(any()) } throws IOException("absent")

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getInt(any(), any()) } answers {
            if (firstArg<String>() == "previous_version_code") previousVersion else 0
        }

        val pm = mockk<PackageManager>(relaxed = true)
        val info = mockk<PackageInfo>(relaxed = true).apply {
            versionName = "1.9.0"
            lastUpdateTime = 1_000L
        }
        every { info.longVersionCode } returns 190L
        @Suppress("DEPRECATION")
        every { pm.getPackageInfo("com.vscodroid", 0) } returns info

        val exposedFilesDir: File = if (usableSpaceBytes == null) {
            filesDir
        } else {
            object : File(filesDir.path) {
                override fun getUsableSpace(): Long = usableSpaceBytes
            }
        }

        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns exposedFilesDir
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.packageName } returns "com.vscodroid"
        every { context.packageManager } returns pm
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = File(filesDir, "native").path
        }
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        SetupStepStrings.stub(context)
        return context to assets
    }

    @Test
    fun `preflight recreation stays indeterminate while mid-unpack attachProgress replays determinate and completion reaches 100`() {
        val (context, assets) = setupProgressContext()
        val preflightStarted = CountDownLatch(1)
        val releasePreflight = CountDownLatch(1)
        val midExtractStarted = CountDownLatch(1)
        val releaseExtract = CountDownLatch(1)
        val preflightLatchHit = AtomicBoolean(false)
        val usrOpens = AtomicInteger(0)

        every { assets.list("") } returns arrayOf("vscode-reh", "usr")
        every { assets.list("vscode-reh") } returns arrayOf("server-main.js")
        every { assets.list("usr") } returns arrayOf("tool")
        every { assets.open("vscode-reh/server-main.js") } answers {
            if (preflightLatchHit.compareAndSet(false, true)) {
                preflightStarted.countDown()
                check(releasePreflight.await(15, TimeUnit.SECONDS))
            }
            ByteArrayInputStream("// server".toByteArray())
        }
        every { assets.open("usr/tool") } answers {
            // 0 = preflight extractionSpace(); 1 = extraction walk after > 0% progress has reported.
            if (usrOpens.getAndIncrement() == 1) {
                midExtractStarted.countDown()
                check(releaseExtract.await(15, TimeUnit.SECONDS))
            }
            ByteArrayInputStream("// tool".toByteArray())
        }

        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        val screen1 = fakeWidgets()
        val sink1: (String, Int) -> Unit = { msg, pct ->
            applySetupProgress(screen1.statusText, screen1.progressBar, msg, pct)
        }
        FirstRunSetup.attachProgress(sink1)

        val executor = Executors.newSingleThreadExecutor()
        val future = FutureTask { runBlocking { setup.runSetup() } }
        executor.execute(future)
        try {
            assertTrue(preflightStarted.await(10, TimeUnit.SECONDS))
            assertTrue(screen1.indeterminate, "screen 1 must stay indeterminate during preflight")

            // Recreate Activity during preflight before any progress report arrives.
            FirstRunSetup.detachProgress(sink1)
            val screen2 = fakeWidgets()
            val sink2: (String, Int) -> Unit = { msg, pct ->
                applySetupProgress(screen2.statusText, screen2.progressBar, msg, pct)
            }
            FirstRunSetup.attachProgress(sink2)
            assertTrue(screen2.indeterminate, "recreated screen during preflight must remain indeterminate")

            releasePreflight.countDown()
            assertTrue(midExtractStarted.await(10, TimeUnit.SECONDS))
            assertFalse(screen2.indeterminate, "positive progress must switch screen 2 out of indeterminate")
            assertTrue(screen2.progress > 0)

            // Recreate Activity mid-unpack after progress > 0: attachProgress replays lastProgress.
            FirstRunSetup.detachProgress(sink2)
            val screen3 = fakeWidgets()
            assertTrue(screen3.indeterminate)
            val sink3: (String, Int) -> Unit = { msg, pct ->
                applySetupProgress(screen3.statusText, screen3.progressBar, msg, pct)
            }
            FirstRunSetup.attachProgress(sink3)
            assertFalse(screen3.indeterminate, "replay on attachProgress must clear indeterminate")
            assertTrue(screen3.progress > 0)

            releaseExtract.countDown()
            assertEquals(FirstRunSetup.SetupResult.SUCCESS, future.get(10, TimeUnit.SECONDS))
            assertFalse(screen3.indeterminate)
            assertEquals(100, screen3.progress)

            // Completion must clear lastProgress so a subsequent screen stays fresh.
            val screenAfterSuccess = fakeWidgets()
            FirstRunSetup.attachProgress { msg, pct ->
                applySetupProgress(screenAfterSuccess.statusText, screenAfterSuccess.progressBar, msg, pct)
            }
            assertTrue(screenAfterSuccess.indeterminate)
            assertEquals(0, screenAfterSuccess.progress)
        } finally {
            releasePreflight.countDown()
            releaseExtract.countDown()
            FirstRunSetup(context).onProgress = null
            executor.shutdownNow()
        }
    }

    @Test
    fun `LOW_STORAGE and ERROR clear lastProgress so later attach does not replay over error state`() {
        val (lowSpaceContext, lowAssets) = setupProgressContext(usableSpaceBytes = 1024L)
        every { lowAssets.list("") } returns arrayOf("vscode-reh")
        every { lowAssets.list("vscode-reh") } returns arrayOf("big.js")
        every { lowAssets.open("vscode-reh/big.js") } answers {
            ByteArrayInputStream(ByteArray(1_048_576))
        }
        val lowResult = runBlocking { FirstRunSetup(lowSpaceContext).runSetup() }
        assertEquals(FirstRunSetup.SetupResult.LOW_STORAGE, lowResult)

        val seenAfterLowStorage = mutableListOf<Pair<String, Int>>()
        FirstRunSetup.attachProgress { msg, pct -> seenAfterLowStorage += msg to pct }
        assertTrue(seenAfterLowStorage.isEmpty(), "LOW_STORAGE must leave no replayable progress")
        FirstRunSetup(lowSpaceContext).onProgress = null

        val (errContext, errAssets) = setupProgressContext()
        val errOpenCount = AtomicInteger(0)
        every { errAssets.list("") } returns arrayOf("vscode-reh")
        every { errAssets.list("vscode-reh") } returns arrayOf("a.js")
        every { errAssets.open("vscode-reh/a.js") } answers {
            if (errOpenCount.getAndIncrement() == 0) {
                ByteArrayInputStream("ok".toByteArray())
            } else {
                throw IOException("boom")
            }
        }
        val seenDuringError = mutableListOf<Pair<String, Int>>()
        val setup = FirstRunSetup(errContext, 0, 0, 0, 0)
        setup.onProgress = { msg, pct -> seenDuringError += msg to pct }
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { setup.runSetup() })
        assertTrue(seenDuringError.isNotEmpty())

        val seenAfterError = mutableListOf<Pair<String, Int>>()
        FirstRunSetup.attachProgress { msg, pct -> seenAfterError += msg to pct }
        assertTrue(seenAfterError.isEmpty(), "ERROR must clear lastProgress")
        FirstRunSetup(errContext).onProgress = null
    }

    @Test
    fun `SplashActivity sets indeterminate on first run and upgrade text and resets progress on Retry`() {
        val source = SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt")
        val onCreate = SourceScan.withoutComments(SourceScan.body(source, "override fun onCreate("))
        val showSetupError = SourceScan.withoutComments(SourceScan.body(source, "private fun showSetupError("))

        val indetIdx = onCreate.indexOf("progressBar.isIndeterminate = true")
        val upgradeIdx = onCreate.indexOf("status_updating_app")
        val runIdx = onCreate.indexOf("runSetupWithRetry(setup, statusText, progressBar)")
        assertTrue(indetIdx in 0 until upgradeIdx, "isIndeterminate = true must precede upgrade text")
        assertTrue(upgradeIdx in 0 until runIdx, "upgrade text must run before setup starts")

        val goneIdx = showSetupError.indexOf("progressBar.visibility = View.GONE")
        val retryVisibleIdx = showSetupError.indexOf("progressBar.visibility = View.VISIBLE")
        val retryIndetIdx = showSetupError.indexOf("progressBar.isIndeterminate = true")
        val retryZeroIdx = showSetupError.indexOf("progressBar.progress = 0")
        val retryRunIdx = showSetupError.indexOf("runSetupWithRetry(setup, statusText, progressBar)")
        assertTrue(goneIdx in 0 until retryVisibleIdx)
        assertTrue(retryVisibleIdx < retryIndetIdx)
        assertTrue(retryIndetIdx < retryZeroIdx)
        assertTrue(retryZeroIdx < retryRunIdx)
    }
}
