package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import com.vscodroid.SourceScan
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SetupProgressReturnTest {
    @TempDir lateinit var filesDir: File
    @TempDir lateinit var cacheDir: File
    private lateinit var context: Context
    private lateinit var assets: AssetManager

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        assets = mockk()
        every { assets.list(any()) } returns emptyArray()
        every { assets.list("") } returns arrayOf("vscode-reh", "usr")
        every { assets.list("vscode-reh") } returns arrayOf("a.js", "b.js")
        every { assets.list("usr") } returns arrayOf("tool")
        every { assets.open(any()) } throws IOException("absent")
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getInt(any(), any()) } returns 0
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        SetupStepStrings.stub(context)
    }

    @AfterEach
    fun tearDown() {
        FirstRunSetup(context).onProgress = null
        unmockkAll()
    }

    private fun exercise(returnToFirst: Boolean, resumeBeforeStop: Boolean = false) {
        val serverStarted = AtomicBoolean()
        val toolsStarted = AtomicBoolean()
        val atServer = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val atTools = CountDownLatch(1)
        val releaseTools = CountDownLatch(1)
        val firstEvents = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val secondEvents = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val firstSink: (String, Int) -> Unit = { message, percent ->
            firstEvents += message to percent
            if (message == SetupStepStrings.text("setup_step_server")) serverStarted.set(true)
        }
        val secondSink: (String, Int) -> Unit = { message, percent ->
            secondEvents += message to percent
            if (message == SetupStepStrings.text("setup_step_tools")) toolsStarted.set(true)
        }
        every { assets.open("vscode-reh/a.js") } answers {
            if (serverStarted.get()) {
                atServer.countDown()
                check(releaseServer.await(20, TimeUnit.SECONDS))
            }
            ByteArrayInputStream("// complete a.js".toByteArray())
        }
        every { assets.open("vscode-reh/b.js") } answers {
            ByteArrayInputStream("// complete b.js".toByteArray())
        }
        every { assets.open("usr/tool") } answers {
            if (toolsStarted.get()) {
                atTools.countDown()
                check(releaseTools.await(20, TimeUnit.SECONDS))
            }
            ByteArrayInputStream("complete tool".toByteArray())
        }
        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        setup.onProgress = firstSink
        val work = FutureTask { runBlocking { setup.runSetup() } }
        val thread = Thread(work).apply { start() }
        try {
            assertTrue(atServer.await(10, TimeUnit.SECONDS))
            if (returnToFirst) {
                if (!resumeBeforeStop) FirstRunSetup.detachProgress(firstSink)
                FirstRunSetup(context).onProgress = secondSink
                releaseServer.countDown()
                assertTrue(atTools.await(10, TimeUnit.SECONDS))
                val current = SetupStepStrings.text("setup_step_tools") to 62
                assertEquals(current, secondEvents.last())
                val beforeReturn = firstEvents.size
                if (resumeBeforeStop) {
                    FirstRunSetup.attachProgress(firstSink)
                } else {
                    FirstRunSetup.detachProgress(secondSink)
                    setup.onProgress = firstSink
                }
                assertEquals(current, firstEvents.last(), "return must show the current step and value immediately")
                assertEquals(beforeReturn + 1, firstEvents.size)
                FirstRunSetup.detachProgress(secondSink)
            } else {
                toolsStarted.set(true)
                releaseServer.countDown()
                assertTrue(atTools.await(10, TimeUnit.SECONDS))
            }
        } finally {
            releaseServer.countDown()
            releaseTools.countDown()
            thread.join(10_000)
            if (thread.isAlive) {
                thread.interrupt()
                thread.join(5_000)
            }
            assertFalse(thread.isAlive, "extraction must finish")
        }
        assertEquals(FirstRunSetup.SetupResult.SUCCESS, work.get(1, TimeUnit.SECONDS))
        assertEquals(SetupStepStrings.text("setup_step_done") to 100, firstEvents.last())
        assertFalse(secondEvents.any { it.second == 100 })
        for (name in listOf("a.js", "b.js")) {
            assertEquals("// complete $name", File(filesDir, "server/vscode-reh/$name").readText())
        }
        assertEquals("complete tool", File(filesDir, "usr/tool").readText())
        println("return=$returnToFirst resumeBeforeStop=$resumeBeforeStop first=$firstEvents second=$secondEvents tree=complete")
    }

    @Test
    fun `the setup screen claims progress on resume and releases it on stop`() {
        val source = SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt")
        assertTrue("override fun onResume(" in source, "a paused setup screen must reclaim progress on resume")
        val resume = SourceScan.withoutComments(SourceScan.body(source, "override fun onResume("))
        val start = SourceScan.withoutComments(SourceScan.body(source, "override fun onStart("))
        val stop = SourceScan.withoutComments(SourceScan.body(source, "override fun onStop("))
        val destroy = SourceScan.withoutComments(SourceScan.body(source, "override fun onDestroy("))
        val create = SourceScan.withoutComments(SourceScan.body(source, "override fun onCreate("))
        assertTrue("progressSink?.let" in resume && "attachProgress(it)" in resume)
        assertFalse("attachProgress" in start, "progress ownership belongs to the resumed screen")
        assertTrue("detachProgress(progressSink)" in stop)
        assertTrue("detachProgress(progressSink)" in destroy)
        if ("override fun onPause(" in source) {
            val pause = SourceScan.withoutComments(SourceScan.body(source, "override fun onPause("))
            assertFalse("detachProgress" in pause, "a paused screen under a dialog still needs progress")
        }
        assertFalse("setup.onProgress = sink" in create, "creation must not take progress from a resumed screen")
    }

    @Test
    fun `a returning setup screen receives the current progress before extraction continues`() = exercise(true)

    @Test
    fun `a returning owner reclaims progress before the other screen stops`() = exercise(true, resumeBeforeStop = true)

    @Test
    fun `a setup screen that stays receives completion and the complete tree`() = exercise(false)

    @Test
    fun `a completed setup does not replay its progress to a later screen`() {
        exercise(false)
        val later = mutableListOf<Pair<String, Int>>()
        FirstRunSetup(context).onProgress = { message, percent -> later += message to percent }
        assertTrue(later.isEmpty())
    }

    @Test
    fun `a failed setup does not replay progress over its error`() {
        val fixtureFiles = setOf("vscode-reh/a.js", "vscode-reh/b.js", "usr/tool")
        every { assets.open(any()) } answers {
            val path = firstArg<String>()
            if (path !in fixtureFiles) throw IOException("absent")
            ByteArrayInputStream("fixture".toByteArray())
        }
        File(filesDir, "usr").writeText("blocked")
        val seen = mutableListOf<Pair<String, Int>>()
        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        setup.onProgress = { message, percent -> seen += message to percent }
        assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { setup.runSetup() })
        assertTrue(seen.isNotEmpty(), "the failing run must have reported progress")
        val later = mutableListOf<Pair<String, Int>>()
        setup.onProgress = { message, percent -> later += message to percent }
        assertTrue(later.isEmpty())
    }

    @Test
    fun `a report racing a sink claim leaves the newer value last`() {
        val setup = FirstRunSetup(context, 0, 0, 0, 0)
        val report = FirstRunSetup::class.java.getDeclaredMethod(
            "reportProgress", String::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val threads = Executors.newFixedThreadPool(2)
        try {
            repeat(32) {
                setup.onProgress = null
                report.invoke(setup, "earlier", 10)
                val shown = ConcurrentLinkedQueue<Pair<String, Int>>()
                val sink: (String, Int) -> Unit = { message, percent -> shown += message to percent }
                val start = CountDownLatch(1)
                val claim = threads.submit { start.await(); setup.onProgress = sink }
                val update = threads.submit { start.await(); report.invoke(setup, "newer", 20) }
                start.countDown()
                claim.get(5, TimeUnit.SECONDS)
                update.get(5, TimeUnit.SECONDS)
                assertEquals("newer" to 20, shown.lastOrNull())
                shown.clear()
                setup.onProgress = sink
                assertEquals(listOf("newer" to 20), shown.toList())
            }
        } finally {
            threads.shutdownNow()
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS))
            setup.onProgress = null
            every { assets.list("") } throws IOException("controlled failure")
            assertEquals(FirstRunSetup.SetupResult.ERROR, runBlocking { setup.runSetup() })
        }
    }
}
