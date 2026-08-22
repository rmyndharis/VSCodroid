package com.vscodroid

import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.vscodroid.util.Logger
import java.io.File

/**
 * The mapping exists because Android's trim constants are not ordered by
 * severity, so these assert meanings rather than a threshold.
 */
class MemoryPressureTest {

    @Test
    fun `backgrounding is not memory pressure`() {
        // The whole defect: 20 is larger than RUNNING_CRITICAL's 15, and arrives
        // on every app switch, so a >= comparison killed every idle language
        // server each time the user looked at another app.
        assertEquals(PRESSURE_NONE, memoryPressureOf(TRIM_MEMORY_UI_HIDDEN))
    }

    @Test
    fun `UI_HIDDEN outranks a genuine critical warning numerically`() {
        // Stated as a test so the reason the mapping exists cannot be optimised
        // back into a comparison by someone who assumes the constants are ordered.
        assert(TRIM_MEMORY_UI_HIDDEN > TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(PRESSURE_CRITICAL, memoryPressureOf(TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(PRESSURE_NONE, memoryPressureOf(TRIM_MEMORY_UI_HIDDEN))
    }

    @Test
    fun `real pressure still sheds idle work`() {
        for (level in listOf(
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE,
        )) {
            assertEquals(PRESSURE_CRITICAL, memoryPressureOf(level), "level $level")
        }
    }

    @Test
    fun `mild levels are reported without killing anything`() {
        assertEquals(PRESSURE_MODERATE, memoryPressureOf(TRIM_MEMORY_RUNNING_LOW))
        assertEquals(PRESSURE_NONE, memoryPressureOf(TRIM_MEMORY_RUNNING_MODERATE))
    }

    @Test
    fun `an unknown level is not treated as pressure`() {
        // A constant Android adds later must not clear a threshold by being
        // numerically large, which is exactly how this broke.
        assertEquals(PRESSURE_NONE, memoryPressureOf(999))
        assertEquals(PRESSURE_NONE, memoryPressureOf(-1))
    }
}

/**
 * The wire between that mapping and the file the process monitor reads.
 *
 * Every test above pins [memoryPressureOf] and none of them touched the code
 * that calls it. Replacing the call with the `>=` comparison it replaced (the
 * original defect, restored verbatim) left the whole suite green, because the
 * decision sat inside `onTrimMemory`, which cannot be invoked without an
 * Activity, and the recording sat in a private method that reached `cacheDir`
 * for the one thing it needed.
 *
 * What breaks when this rots is the detector itself: the process monitor stops
 * seeing memory pressure, silently, in an app whose whole design is built
 * around a 32-process limit and 400-700 MB of RAM. A dead detector is worse
 * than no detector, because it produces confidence.
 */
class MemoryPressureWireTest {

    @TempDir
    lateinit var tmpDir: File

    private val file get() = File(tmpDir, "vscodroid-memory-pressure")

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `real pressure reaches the file the monitor reads`() {
        // First, because the test below asserts that nothing was written and
        // "nothing was written" is also what a broken fixture produces. This
        // proves the writing path works here before that one claims it did not
        // run.
        assertEquals(PRESSURE_CRITICAL, applyMemoryPressure(tmpDir, TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(
            PRESSURE_CRITICAL, file.readText(),
            "the severity has to reach ${file.name}, which is what process-monitor.js opens"
        )
    }

    @Test
    fun `backgrounding writes nothing, whatever its number`() {
        // TRIM_MEMORY_UI_HIDDEN is 20 and RUNNING_CRITICAL is 15, so a `>=`
        // comparison calls an ordinary app switch critical. This is the value
        // that discriminates: any fixture below 15 would pass against the
        // comparison too.
        val sentinel = "left alone"
        file.writeText(sentinel)

        assertEquals(PRESSURE_NONE, applyMemoryPressure(tmpDir, TRIM_MEMORY_UI_HIDDEN))
        assertEquals(
            sentinel, file.readText(),
            "an app switch must not be recorded as pressure; it arrives on every backgrounding"
        )
    }

    @Test
    fun `a failed write is swallowed rather than thrown at the caller`() {
        // The destination is a directory, so writeText throws. This runs inside
        // onTrimMemory, a lifecycle callback: letting it escape would turn a
        // missing hint into a crash on every app switch.
        assertTrue(file.mkdirs(), "the fixture must actually block the write")

        assertEquals(
            PRESSURE_CRITICAL, applyMemoryPressure(tmpDir, TRIM_MEMORY_RUNNING_CRITICAL),
            "the severity is still the answer even when it could not be recorded"
        )

        // Both the Activity and the application class reach this write, and the
        // one that fails after a swipe has no Activity behind it. A tag naming
        // one caller sends whoever reads the line looking for a lifecycle bug in
        // a component that was already gone.
        verify {
            Logger.d("MemoryPressure", match { it.startsWith("Failed to write memory pressure") })
        }
    }
}

/**
 * Which component reports pressure once there is no Activity left to report it.
 *
 * The server the process monitor watches outlives the Activity: NodeService
 * returns START_STICKY and the manifest declares no `android:stopWithTask`. So
 * with the Activity as the only writer, swiping the task away leaves Node
 * running while nothing fills the file `readMemoryPressure` opens, and the idle
 * language-server kill that reading gates can never fire again. That is the
 * state the system reclaims memory from, and the failure is silent: it looks
 * exactly like no language server having been idle.
 *
 * `Application` is a ComponentCallbacks2 for the whole process lifetime, so an
 * override there fires whether or not an Activity exists.
 *
 * These read the class and its source instead of driving the callback, which is
 * weaker than [MemoryPressureWireTest] above and worth being exact about.
 * `onTrimMemory` cannot be invoked from a JVM test at all: its first statement
 * is `super.onTrimMemory(level)`, the unit-test android.jar answers that with
 * `RuntimeException: Method onTrimMemory in android.app.Application not mocked`,
 * and no stub on the instance intercepts it, because a super call is not
 * dispatched virtually. What the override is allowed to contain is therefore
 * that super call and one call out, and one call out is what this pins.
 * Everything decided or written behind it is covered above.
 */
class ProcessWideMemoryPressureTest {

    private val source = File("src/main/kotlin/com/vscodroid/VSCodroidApp.kt")
    private val manifest = File("src/main/AndroidManifest.xml")

    /**
     * Both source-reading cases below ask whether something is still WRITTEN in a
     * file, and a plain search answers yes just as readily from a line someone
     * disabled while debugging and did not put back, which is the regression they
     * exist to catch, so the comments come out first.
     *
     * Which forms are stripped follows how a line is actually disabled. An editor
     * wraps the selected statement in a block comment, so that form goes first and
     * across lines; a developer typing it out puts `//` in front, which the line
     * filter takes afterwards. Only a leading `//` is dropped: a trailing one
     * cannot disable the code it sits behind, and dropping the whole line would
     * lose that code.
     */
    private fun kotlinWithoutComments(file: File): List<String> =
        file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .filterNot { it.trimStart().startsWith("//") }

    /** The same, for the one comment form XML has. */
    private fun xmlWithoutComments(file: File): String =
        file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    @Test
    fun `the manifest points the process at this class`() {
        check(manifest.isFile) {
            "AndroidManifest.xml not found at ${manifest.absolutePath}, so this test would " +
                "otherwise pass by looking at nothing"
        }

        // The override below only runs while <application> names this class.
        // Pointing that attribute at another Application subclass (a startup
        // initializer, a multidex or injection base class) while leaving this
        // one in the tree restores the defect exactly: the process again has no
        // writer once the Activity is gone, and the two tests below stay green
        // because the class still declares the override and still calls out.
        // Comments are taken out first. XML has no way to disable one attribute,
        // so swapping the application class means commenting out the whole
        // <application> element and writing another beside it. The commented-out
        // one still holds this exact text, which is what a raw search would go on
        // finding long after nothing read it.
        assertTrue(
            xmlWithoutComments(manifest).contains("android:name=\".VSCodroidApp\""),
            "the process reports pressure through whichever class <application> names; " +
                "VSCodroidApp is not that class, so its override is never called"
        )
    }

    @Test
    fun `the process, not only the Activity, hears a trim callback`() {
        assertTrue(
            VSCodroidApp::class.java.declaredMethods.any { it.name == "onTrimMemory" },
            "the manifest names VSCodroidApp as the application class, and once the " +
                "Activity is gone it is the only component left to hear the callback"
        )
    }

    @Test
    fun `it records the severity where the monitor looks for it`() {
        check(source.isFile) {
            "VSCodroidApp.kt not found at ${source.absolutePath}, so this test would " +
                "otherwise pass by looking at nothing"
        }

        val body = kotlinWithoutComments(source)
            .dropWhile { !it.contains("override fun onTrimMemory(") }
            .drop(1)
            .takeWhile { !it.startsWith("    }") }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")

        // Says which of the two ways this can fail happened. An override that was
        // commented out leaves nothing to search, and "the call is not in an empty
        // string" is a true but useless thing for a failure to report.
        assertTrue(
            body.isNotBlank(),
            "${source.name} has no live onTrimMemory body, so nothing reports pressure " +
                "for the process once the Activity is gone"
        )

        // The whole call, not just the name: the directory has to be the one
        // process-monitor.js resolves TMPDIR to, and the level has to arrive
        // unexamined, because applyMemoryPressure maps it and comparing it
        // instead is what killed every idle language server on every app switch.
        assertTrue(
            body.contains("""applyMemoryPressure(File(cacheDir, "tmp"), level)"""),
            "the application override must record pressure the same way the Activity " +
                "does; found instead: $body"
        )
    }
}
