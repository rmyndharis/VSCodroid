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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Who `runSetup` hands the foreground hold to.
 *
 * SplashActivity keeps NodeService in the foreground for the length of the
 * unpack and releases it in a `finally`, on the argument that the unpack blocks
 * with no suspension point, so a cancelled scope is only observed once the
 * work has returned. That holds for the instance doing the work. A second
 * Splash created during it parks on the setup lock, and `Mutex.lock` suspends:
 * destroying the waiter throws it out of the lock at once, and a hold taken
 * before `runSetup` was given back by the waiter's finally while the winner
 * was still writing. NodeService answers a release with no server behind it by
 * leaving the foreground and stopping, which is the state the hold exists to
 * prevent.
 *
 * So `runSetup` takes the callback and invokes it only from the instance that
 * has the lock and work to do. Driven through the real `runSetup()` against a
 * device with no room, so the winner is refused at the storage gate the moment
 * the callback lets it go and nothing is unpacked.
 */
class FirstRunSetupHoldTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var cacheDir: File

    private lateinit var assets: AssetManager

    private val mb = 1_048_576L

    /** A `filesDir` with one byte of room, so the gate refuses without a copy. */
    private class RoomDir(real: File) : File(real.absolutePath) {
        override fun getUsableSpace(): Long = 1L
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        assets = mockk()
        every { assets.list(any()) } returns emptyArray()
        every { assets.open(any()) } throws IOException("absent")
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    /**
     * @param done whether the record says this build already set up. A relaxed
     *   `PackageInfo` reports versionName null and versionCode 0, which the
     *   class reads as "0" and 0, so a stored "0"/0 is the same build and a
     *   stored null is a fresh install.
     */
    private fun setup(done: Boolean): FirstRunSetup {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns mockk(relaxed = true)
        every { prefs.getString(any(), any()) } returns if (done) "0" else null
        every { prefs.getInt(any(), any()) } returns 0

        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns RoomDir(filesDir)
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        return FirstRunSetup(context, 8 * mb, 2 * mb, 4 * mb, 2 * mb)
    }

    /**
     * NEGATIVE CONTROL: invoke `beforeUnpack` before `setupMutex.withLock`
     * instead of inside it, and the waiter is asked.
     */
    @Test
    fun `a waiter cancelled on the lock is never asked to hold`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val winnerAsked = AtomicInteger()
        // The winner parks inside the callback, which runs with the lock held,
        // so the lock stays taken for exactly as long as this test wants it.
        val winner = thread {
            runBlocking {
                setup(done = false).runSetup {
                    winnerAsked.incrementAndGet()
                    entered.countDown()
                    release.await()
                }
            }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the winner never reached the callback")

            var waiterAsked = false
            runBlocking {
                val waiter = launch { setup(done = false).runSetup { waiterAsked = true } }
                // Let it run as far as the lock, where it has to park.
                repeat(10) { yield() }
                assertTrue(waiter.isActive, "the waiter did not park on the lock")
                waiter.cancelAndJoin()
            }
            assertFalse(
                waiterAsked,
                "a second Splash parked on the setup lock was asked to hold, so its " +
                    "finally releases the hold while the first instance is still writing",
            )
        } finally {
            release.countDown()
            winner.join(10_000)
        }
        assertEquals(1, winnerAsked.get(), "the instance that had the work was not asked exactly once")
    }

    @Test
    fun `the instance that finds setup done is not asked either`() {
        // A waiter that outlives the winner reaches the lock to find the work
        // done; a hold taken there would be a promote and a demote over nothing.
        var asked = false
        val result = runBlocking { setup(done = true).runSetup { asked = true } }
        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result)
        assertFalse(asked, "an instance with nothing to unpack was asked to hold")
    }

    @Test
    fun `the instance with work to do is asked before it runs`() {
        // The control for the two above: without it, a runSetup that dropped
        // the callback outright would satisfy both.
        val asked = AtomicInteger()
        val result = runBlocking { setup(done = false).runSetup { asked.incrementAndGet() } }
        assertEquals(FirstRunSetup.SetupResult.LOW_STORAGE, result, "the gate did not refuse; the harness is wrong")
        assertEquals(1, asked.get(), "the instance that ran setup was not asked to hold exactly once")
    }
}
