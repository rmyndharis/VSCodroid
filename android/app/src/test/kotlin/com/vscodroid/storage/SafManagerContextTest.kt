package com.vscodroid.storage

import android.content.Context
import io.mockk.every
import io.mockk.mockk
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
 * What a [SafStorageManager] is allowed to hold.
 *
 * Both production callers build one with an Activity, and `SplashActivity` then calls
 * `reclaimRevokedMirrors`, which starts a detached daemon thread. That thread runs an
 * instance method, so it captures the manager and through it whatever Context the
 * manager holds, and the file already states that the thread outlives the activity that
 * started it. Its duration is a recursive delete of a mirror, which the same file puts
 * at seconds to tens of seconds for a project tree, and that window is precisely when
 * `MainActivity`, the WebView renderer and the Node server are all starting: the
 * tightest memory moment the app has, with a finished Activity and its ContextImpl held
 * reachable through it.
 *
 * Asked of the object graph rather than of the constructor argument, which is the lesson
 * `ToolchainManagerContextTest` records after a fix that named the right argument and
 * left the leak intact one link along. Here that second link is real and is a language
 * trap rather than a lambda: a constructor parameter shadows a property of the same name
 * inside an initialiser, so `SafSyncEngine(context)` written next to a
 * `context.applicationContext` property hands the engine the Activity while the code
 * reads as though it does not.
 *
 * Negative controls, both measured:
 *  - `private val context: Context = context` in place of the unwrapping makes
 *    `the Activity is not reachable` red.
 *  - `SafSyncEngine(context)` in place of `SafSyncEngine(this.context)` makes the same
 *    case red, which is what says this reads the graph and not one field.
 */
class SafManagerContextTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var activity: Context
    private lateinit var application: Context

    @BeforeEach
    fun setUp() {
        application = mockk(relaxed = true)
        every { application.filesDir } returns filesDir
        every { application.applicationContext } returns application

        activity = mockk(relaxed = true)
        every { activity.filesDir } returns filesDir
        every { activity.applicationContext } returns application
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `the Activity is not reachable from the manager`() {
        // The fixture is its own control: two distinct Contexts, and the Activity
        // answers `applicationContext` with the other one, so a pass has actually
        // told them apart.
        assertNotSame(activity, application, "the fixture handed out one Context")

        val manager = SafStorageManager(activity)

        // Runs first. The manager is supposed to hold the application context, so a
        // walk that cannot find that one is broken and every assertion after it would
        // pass by seeing nothing.
        assertTrue(
            reaches(manager, application),
            "the walk cannot find the Context the manager is meant to hold, so it " +
                "proves nothing about the one it must not",
        )

        assertFalse(
            reaches(manager, activity),
            "the Activity is reachable from the SafStorageManager, so the detached " +
                "saf-reclaim thread started from SplashActivity keeps the finished " +
                "Activity and its ContextImpl alive for the whole of a project-sized " +
                "recursive delete, while MainActivity and the WebView are starting",
        )
    }

    /**
     * Whether [target] is reachable from [root] by strong references alone.
     *
     * Lifted from `ToolchainManagerContextTest`, whose reasoning applies unchanged: a
     * weak reference is not retention and is not followed, the walk sees declared
     * fields and so cannot speak for a native or `ThreadLocal` route, and [NODE_CAP]
     * bounds it so a cycle cannot hang the suite.
     */
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
}
