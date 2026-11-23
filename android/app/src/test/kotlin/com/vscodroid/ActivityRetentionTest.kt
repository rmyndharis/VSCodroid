package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What the device-folder machinery is allowed to hold once the Activity is gone.
 *
 * `SafSyncEngine` starts the `saf-writeback` daemon and `stopWatching` waits two
 * seconds for it before leaving it running, rather than discarding writes the user
 * expects on their device. That worker's runnable holds the engine, the engine
 * holds its `Context`, and every notice callback the Activity hands the manager holds
 * whatever it closed over. Built on the Activity, all of that kept a destroyed MainActivity and
 * its whole inflated view tree alive for the length of an unbounded drain, on a
 * device where the Node server and the extension host are competing for the same
 * heap. `onDestroy` clears the service callbacks and nothing else, and the started
 * foreground service keeps the process alive so nothing reclaims it wholesale.
 *
 * Read off the source: a leak that takes a live SAF drain to observe cannot be
 * built in a plain JVM test, and the property being pinned is a property of the
 * text (what the constructor and the lambdas name), not of a value at runtime.
 *
 * NEGATIVE CONTROL, measured rather than assumed:
 *  - changing `SafStorageManager(applicationContext)` back to
 *    `SafStorageManager(this)` reddens `the manager is built on the application
 *    context`.
 *  - putting `this@MainActivity` back as the notice's context in any one of the
 *    notices reddens `no device-folder notice closes over the Activity`.
 *  - changing one notice's `appContext.getString(` back to `getString(` reddens the
 *    same case, since an unqualified resource lookup is a call on the Activity.
 */
class ActivityRetentionTest {

    private val source = SourceScan.readAll(SourceScan.MAIN_ACTIVITY_FILES)

    /** The braced block that follows [marker], to its closing brace. */
    private fun blockAt(marker: String): String = SourceScan.body(source, marker)

    /** Comment text removed, so an explanation cannot satisfy a search for the rule. */
    private fun code(text: String): String = SourceScan.withoutComments(text)

    /**
     * Every notice the Activity hands the manager, found by its shape rather than listed by
     * name: a list written out by hand covered the first five and none of the five added
     * after them, which is how a notice that captures the Activity would get in unread.
     */
    private val notices: List<String> by lazy {
        val found = Regex("""(?m)^\s*safManager\.on\w+\s*\{""").findAll(code(source))
            .map { it.value.trim() }.toList()
        // The control: a pattern that stopped matching would hand both cases below an
        // empty list, and they would pass by reading nothing.
        assertTrue(found.size >= 10 && "safManager.onWriteBackFailed {" in found) {
            "found only ${found.size} device-folder notices in MainActivity.kt: $found"
        }
        found
    }

    @Test
    fun `the manager is built on the application context`() {
        val built = code(source).lines().filter { it.contains("SafStorageManager(") }

        assertEquals(
            listOf("safManager = SafStorageManager(applicationContext)"),
            built.map { it.trim() },
            "the manager outlives this Activity by design: its engine leaves the " +
                "write-back worker running when a drain outruns the two-second wait in " +
                "onDestroy, so an Activity handed to it here is retained, with its view " +
                "tree, for as long as that drain takes",
        )
    }

    @Test
    fun `no device-folder notice closes over the Activity`() {
        notices.forEach { marker ->
            val block = code(blockAt(marker))

            assertTrue(block.isNotBlank()) { "the block after '$marker' is empty; this case is measuring nothing" }
            assertTrue(!block.contains("this@MainActivity")) {
                "$marker hands the engine a strong reference to this Activity, which the " +
                    "write-back worker then holds for the length of an unbounded drain"
            }
            assertTrue(!block.contains("runOnUiThread")) {
                "$marker posts through the Activity, which captures it for the same reason. " +
                    "A Handler on the main looper does the same job holding nothing."
            }
            // Reading a field captures the Activity as surely as naming it, and
            // this is the field nearest to hand: the upload notice needs the
            // manager to turn a mirror hash back into the folder's own name. The
            // spelling that holds nothing is a local taken beside appContext.
            assertTrue(!block.contains("safManager")) {
                "$marker reaches for the manager through the Activity, so the write-back " +
                    "worker holds the Activity and its view tree for the length of an " +
                    "unbounded drain"
            }
            // Every resource lookup qualified, because an unqualified one is a call
            // on the Activity and captures it exactly as naming it would. Judged a
            // line at a time rather than by a lookbehind, since the qualifier is one
            // hop further away in `appContext.resources.getQuantityString`.
            val unqualified = block.lines().filter { line ->
                (line.contains("getString(") || line.contains("getQuantityString(")) &&
                    !line.contains("appContext.")
            }
            assertEquals(
                emptyList<String>(), unqualified.map { it.trim() },
                "$marker looks up a resource on the Activity, so the lambda captures it",
            )
        }
    }

    @Test
    fun `the notices still say what they are for`() {
        // Without this the case above is satisfied by deleting the notices, which
        // would trade a bounded leak for the silence they exist to end: a save that
        // never reached the device folder looks exactly like one that did.
        // WriteBackNoticeWiringTest pins the wiring itself; this only refuses a
        // fix that removes the subject.
        notices.forEach { marker ->
            assertTrue(code(blockAt(marker)).contains("AppNotices.showNotice(")) {
                "$marker no longer puts anything on screen"
            }
        }
    }
}
