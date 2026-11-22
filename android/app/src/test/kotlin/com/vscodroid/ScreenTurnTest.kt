package com.vscodroid

import android.content.pm.ActivityInfo.CONFIG_DENSITY
import android.content.pm.ActivityInfo.CONFIG_KEYBOARD
import android.content.pm.ActivityInfo.CONFIG_KEYBOARD_HIDDEN
import android.content.pm.ActivityInfo.CONFIG_NAVIGATION
import android.content.pm.ActivityInfo.CONFIG_ORIENTATION
import android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT
import android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE
import android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
import android.content.pm.ActivityInfo.CONFIG_UI_MODE
import android.content.res.Configuration
import com.vscodroid.keyboard.EdgeBackGesture
import com.vscodroid.keyboard.ExtraKeyRow
import com.vscodroid.keyboard.KeyboardDismissal
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Which changes of configuration the editor treats as a turn of the screen.
 *
 * A turn takes the keyboard down for a moment and brings it back, so it finishes the word the keyboard
 * composes and claims, for up to 13 s, a keyboard reported hidden as the turn's and not the person's. A
 * change of navigation type, of night mode or of the keyboard attached took that claim too: a keyboard the
 * person put away right after one was credited to it and came back at the next scroll.
 */
class ScreenTurnTest {
    private lateinit var activity: MainActivity
    private lateinit var dismissal: KeyboardDismissal
    private lateinit var edge: EdgeBackGesture
    private lateinit var row: ExtraKeyRow

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    @BeforeEach
    fun setUp() {
        activity = mockk(relaxed = true)
        dismissal = mockk(relaxed = true)
        edge = mockk(relaxed = true)
        row = mockk(relaxed = true)
        // Filled by the constructor, which a mock does not run.
        field("keyboardDismissal").set(activity, dismissal)
        field("edgeBackGesture").set(activity, edge)
        field("extraKeyRow").set(activity, row)
        every { activity["configurationChanged"](any<Configuration>()) } answers { callOriginal() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** What the framework's part of onConfigurationChanged leaves to the activity, for a change whose diff is [diff]. */
    private fun change(diff: Int?, keyboard: Int = Configuration.KEYBOARD_QWERTY): Configuration {
        field("lastConfiguration").set(activity, diff?.let { d -> mockk<Configuration> { every { diff(any()) } returns d } })
        val now = Configuration().apply { this.keyboard = keyboard }
        MainActivity::class.java.getDeclaredMethod("configurationChanged", Configuration::class.java)
            .apply { isAccessible = true }.invoke(activity, now)
        return now
    }

    private fun turnedTimes(times: Int) {
        verify(exactly = times) { dismissal.turned() }
        verify(exactly = times) { dismissal.edgeLifted() }
        verify(exactly = times) { edge.lifted() }
        verify(exactly = times) { activity["finishKeyboardCompositionOnTurn"]() }
    }

    @Test
    fun `a turn, a resize and a fold are turns of the screen`() {
        for (diff in listOf(
            CONFIG_ORIENTATION or CONFIG_SCREEN_SIZE,
            CONFIG_SCREEN_SIZE,
            CONFIG_SMALLEST_SCREEN_SIZE or CONFIG_SCREEN_SIZE or CONFIG_SCREEN_LAYOUT,
            CONFIG_SCREEN_LAYOUT,
            CONFIG_DENSITY or CONFIG_SCREEN_SIZE,
            CONFIG_UI_MODE or CONFIG_ORIENTATION,
        )) {
            assertTrue(isScreenTurn(diff), "a change of 0x${diff.toString(16)} reshapes the screen")
        }
    }

    @Test
    fun `navigation, night mode, a keyboard and density alone are not`() {
        for (diff in listOf(
            0,
            CONFIG_NAVIGATION,
            CONFIG_UI_MODE,
            CONFIG_KEYBOARD or CONFIG_KEYBOARD_HIDDEN or CONFIG_NAVIGATION,
            CONFIG_KEYBOARD_HIDDEN,
            CONFIG_DENSITY,
        )) {
            assertFalse(isScreenTurn(diff), "a change of 0x${diff.toString(16)} was taken for a turn")
        }
    }

    @Test
    fun `a change of navigation type or night mode leaves the keyboard and its word alone`() {
        change(CONFIG_NAVIGATION)
        change(CONFIG_UI_MODE)

        turnedTimes(0)
    }

    @Test
    fun `a turn finishes the word and claims the keyboard for the turn`() {
        change(CONFIG_ORIENTATION or CONFIG_SCREEN_SIZE)

        turnedTimes(1)
    }

    @Test
    fun `a change before any configuration was recorded is taken for a turn, as before`() {
        change(null)

        turnedTimes(1)
    }

    @Test
    fun `the next change is read against a copy of this one`() {
        val before = mockk<Configuration> { every { diff(any()) } returns CONFIG_NAVIGATION }
        field("lastConfiguration").set(activity, before)
        val now = Configuration()
        MainActivity::class.java.getDeclaredMethod("configurationChanged", Configuration::class.java)
            .apply { isAccessible = true }.invoke(activity, now)

        val recorded = field("lastConfiguration").get(activity)
        assertTrue(recorded is Configuration && recorded !== before && recorded !== now,
            "the next change is not read against this one, or against the framework's own object, which it may reuse")
    }

    /**
     * The two ends the cases above run between, read off the source because neither runs on the JVM: the
     * framework's half of onConfigurationChanged, and onCreate. Without the call no change is read at all;
     * without the copy onCreate takes, the first change of a screen has nothing to be read against and is taken
     * for a turn whatever it changed, which is the case above that keeps the old behaviour for want of a record.
     */
    @Test
    fun `every change is read, and the first against the configuration the screen started with`() {
        val source = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))

        val changed = SourceScan.body(source, "override fun onConfigurationChanged(newConfig: Configuration)")
        val framework = changed.indexOf("super.onConfigurationChanged(newConfig)")
        val read = Regex("""(?m)^\s*configurationChanged\(newConfig\)""").find(changed)?.range?.first ?: -1
        assertTrue(framework >= 0 && read > framework,
            "onConfigurationChanged no longer hands the change to configurationChanged after the framework's part: $changed")

        val created = SourceScan.body(source, "override fun onCreate(savedInstanceState: Bundle?)")
        val start = created.indexOf("super.onCreate(savedInstanceState)")
        val record = created.indexOf("lastConfiguration = Configuration(resources.configuration)")
        assertTrue(start >= 0 && record > start,
            "onCreate no longer records a copy of the configuration the screen starts with, so its first change " +
                "is taken for a turn of the screen whatever it changed")
    }

    @Test
    fun `a keyboard that is gone brings the row back, turn or not`() {
        change(CONFIG_KEYBOARD or CONFIG_KEYBOARD_HIDDEN or CONFIG_NAVIGATION, Configuration.KEYBOARD_NOKEYS)

        verify { row.hiddenForHardwareKeyboard = false }
        turnedTimes(0)
    }
}
