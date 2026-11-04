package com.vscodroid.storage

import android.os.FileObserver
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SafDirectoryWatchTeardownTest {
    @Test
    fun aDirectoryWalkCannotRegisterObserversAfterShutdown() = checkWalkFinishingLate(false)

    @Test
    fun aDirectoryWalkCannotAdoptTheReplacementWatchSession() = checkWalkFinishingLate(true)

    @Test
    fun aStaleDirectoryRemovalCannotReleaseReplacementObservers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mirror = File(context.cacheDir, "stale-directory-removal").apply { deleteRecursively(); mkdirs() }
        val tree = DocumentsContract.buildTreeDocumentUri("com.vscodroid.test.absent", "root")
        val engine = SafSyncEngine(context)
        try {
            engine.startWatching(mirror, tree)
            val closing = engine.session
            engine.startWatching(mirror, tree)
            SafSyncEngine::class.java.getDeclaredMethod("unwatchTree", File::class.java, WatchSession::class.java)
                .apply { isAccessible = true }.invoke(engine, mirror, closing)
            val type = SafSyncEngine::class.java
            val lock = type.getDeclaredField("watchersLock").apply { isAccessible = true }.get(engine)!!
            val watchers = type.getDeclaredField("watchers").apply { isAccessible = true }.get(engine) as Map<*, *>
            assertTrue("The stale removal released the replacement root", synchronized(lock) { watchers.containsKey(mirror) })
        } finally {
            engine.shutdown()
            mirror.deleteRecursively()
        }
    }

    private fun checkWalkFinishingLate(replace: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "late-directory-watch").apply { deleteRecursively(); mkdirs() }
        val mirror = File(fixture, "old").apply { mkdir() }
        val child = File(mirror, "child").apply { mkdir() }
        val replacement = File(fixture, "new").apply { mkdir() }
        val tree = DocumentsContract.buildTreeDocumentUri("com.vscodroid.test.absent", "root")
        val engine = SafSyncEngine(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val slowChild = object : File(child.path) {
            override fun listFiles(): Array<File>? {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "The directory walk was not released" }
                return super.listFiles()
            }
        }
        val callback = Thread {
            try {
                engine.handleMirrorEvent(FileObserver.CREATE, slowChild, mirror, tree)
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }
        try {
            engine.startWatching(mirror, tree)
            callback.start()
            assertTrue("The callback did not enter directory enumeration", entered.await(5, TimeUnit.SECONDS))
            if (replace) engine.startWatching(replacement, tree) else engine.shutdown()
            release.countDown()
            callback.join(5_000)
            assertFalse("The directory callback did not finish", callback.isAlive)
            failure.get()?.let { throw AssertionError("The directory callback failed", it) }
            val type = SafSyncEngine::class.java
            val lock = type.getDeclaredField("watchersLock").apply { isAccessible = true }.get(engine)!!
            val watchers = type.getDeclaredField("watchers").apply { isAccessible = true }.get(engine) as Map<*, *>
            val paths = synchronized(lock) { watchers.keys.map { (it as File).absolutePath } }
            assertTrue(
                "A closing directory registered observers into the current session: $paths",
                if (replace) paths == listOf(replacement.absolutePath) else paths.isEmpty(),
            )
        } finally {
            release.countDown()
            callback.join(5_000)
            engine.shutdown()
            fixture.deleteRecursively()
        }
    }
}
