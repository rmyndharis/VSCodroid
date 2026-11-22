package com.vscodroid.keyboard

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [windowHeightForInsets], the window height the key row decides from.
 *
 * The row stands down when the page above the keyboard would be left with less
 * than 120dp, and comes back once the page has 240dp, so a window height that is
 * short by the navigation bar moves both lines by that much. Every measured figure
 * below was read off an emulator with the keyboard up: the window from `dumpsys
 * window`, the WebView's bounds from `dumpsys activity top`, the display metrics
 * from the row's own log line. The page each case ends with is the height the
 * WebView was given there, which is what the decision is meant to be about. The
 * one state where the arithmetic still comes out short, a navigation mode switched
 * on Android 10 without a turn of the screen, is in the function's own KDoc.
 */
class KeyRowWindowHeightTest {

    private fun page(window: Int, top: Int, bottom: Int, row: Int) = window - top - bottom - row

    /** The function as the listener calls it, on the 1080x2400 display these were measured on. */
    private fun height(sdk: Int, metrics: Int, bar: Int, cutoutTop: Int, cutoutBottom: Int, display: Int = 2400) =
        windowHeightForInsets(
            sdkInt = sdk,
            displayMetricsHeightPx = metrics,
            navigationBarBottomPx = bar,
            cutoutTopPx = cutoutTop,
            cutoutBottomPx = cutoutBottom,
            displayHeightPx = { display },
        )

    @Test
    fun `on Android 10 the navigation bar below the page is counted back into the window`() {
        // API 29, three-button navigation, portrait, 1080x2400 at 420dpi: the
        // metrics said 2274 of a 2400px window, the bar is 126px. The row then
        // computed a page of 1147px while the WebView had 1273px.
        val window = height(sdk = 29, metrics = 2274, bar = 126, cutoutTop = 0, cutoutBottom = 0)

        assertEquals(2400, window)
        assertEquals(1273, page(window, top = 63, bottom = 867, row = 197))
    }

    @Test
    fun `on Android 10 a gesture bar is counted back in either orientation`() {
        // Gesture navigation on the same emulator: a 42px bar under the page in
        // portrait and in landscape, and the metrics short by exactly that.
        assertEquals(2400, height(29, 2358, 42, 0, 0))
        val landscape = height(29, 1038, 42, 0, 0, display = 1080)
        assertEquals(1080, landscape)
        // Landscape with the keyboard up: the WebView had 331px with the row
        // gone, so 134px with it, and the row had read 92px.
        assertEquals(134, page(landscape, top = 63, bottom = 686, row = 197))
    }

    @Test
    fun `on Android 10 a cutout at the top is counted back with the bar`() {
        // The tall cutout overlay, 126px, with three buttons: the metrics said
        // 2148 and the WebView had 1210px under a 126px top inset.
        val window = height(29, 2148, 126, 126, 0)

        assertEquals(2400, window)
        assertEquals(1210, page(window, top = 126, bottom = 867, row = 197))
    }

    @Test
    fun `a cutout under the navigation bar is inside the bar's inset and is not counted twice`() {
        // The double cutout overlay, 84px at each end, with three buttons. The bar
        // sits above the lower cutout, so its inset from the window's bottom edge
        // is 210px, and the metrics held back 84 + 126 + 84. Adding the lower
        // cutout to the bar's inset would count it twice.
        val ten = height(29, 2106, 210, 84, 84)
        assertEquals(2400, ten)
        assertEquals(1126, page(ten, top = 126, bottom = 951, row = 197))

        // Android 12 sets the bar above the lower cutout the same way.
        val twelve = height(31, 2106, 210, 84, 84)
        assertEquals(2400, twelve)
        assertEquals(1116, page(twelve, top = 126, bottom = 961, row = 197))

        // Gesture navigation on Android 13: 84 + 63 + 84 held back.
        val thirteen = height(33, 2169, 147, 84, 84)
        assertEquals(2400, thirteen)
        assertEquals(1152, page(thirteen, top = 84, bottom = 967, row = 197))
    }

    @Test
    fun `a lower cutout with the bar beside the page is counted back on its own`() {
        // Not measured: the shape the larger-of-two rule exists for, a cutout on
        // the edge the page ends at while three buttons stand at the side.
        assertEquals(1080, height(29, 1000, 0, 0, 80, display = 1080))
    }

    @Test
    fun `in full screen the hidden bar is still counted back`() {
        // Full screen hides the bars, so the padding gives the page their room,
        // but the metrics keep the reservation: Android 10 still said 2274, and
        // the WebView ran from the top edge to the keyboard, 1336px.
        val ten = height(29, 2274, 126, 0, 0)
        assertEquals(2400, ten)
        assertEquals(1336, page(ten, top = 0, bottom = 867, row = 197))

        // Android 13: the status bar hidden, the 136px cutout kept as the top inset.
        val thirteen = height(33, 2201, 63, 136, 0)
        assertEquals(2400, thirteen)
        assertEquals(1184, page(thirteen, top = 136, bottom = 883, row = 197))
    }

    @Test
    fun `a navigation bar beside the page adds nothing`() {
        // Three buttons in landscape put the bar on the right. The metrics then
        // already held the whole 1080px height, and the row read the page right.
        val window = height(29, 1080, 0, 0, 0, display = 1080)

        assertEquals(1080, window)
        assertEquals(260, page(window, top = 63, bottom = 560, row = 197))
    }

    @Test
    fun `metrics Android 10 left describing the old bar do not push the window past the display`() {
        // Gesture navigation switched to three buttons in portrait, the screen not
        // turned since: the metrics still said 2358, the bar's inset was 126, and
        // the WebView had 1273px. The plain sum would have been 2484.
        val portrait = height(29, 2358, 126, 0, 0)
        assertEquals(2400, portrait)
        assertEquals(1273, page(portrait, top = 63, bottom = 867, row = 197))

        // Three buttons switched to gestures in landscape: the metrics kept the
        // full 1080 of a bar on the right while the 42px gesture bar sat below.
        val landscape = height(29, 1080, 42, 0, 0, display = 1080)
        assertEquals(1080, landscape)
        assertEquals(134, page(landscape, top = 63, bottom = 686, row = 197))
    }

    @Test
    fun `a display not known yet bounds nothing`() {
        assertEquals(2400, height(29, 2274, 126, 0, 0, display = Int.MAX_VALUE))
    }

    @Test
    fun `Android 11 to 14 count back the navigation bar and the cutout`() {
        // Measured on 12, 13 and 14; 11 takes the same branch. API 31, three
        // buttons: the same 126px as Android 10.
        val twelve = height(31, 2274, 126, 0, 0)
        assertEquals(2400, twelve)
        assertEquals(1263, page(twelve, top = 63, bottom = 877, row = 197))

        // API 33, gesture navigation and a 136px cutout at the top: the metrics
        // said 2201, short by the cutout and the 63px bar together. The row had
        // computed 985px for a WebView of 1184px, 76dp short.
        val thirteen = height(33, 2201, 63, 136, 0)
        assertEquals(2400, thirteen)
        assertEquals(1184, page(thirteen, top = 136, bottom = 883, row = 197))

        // API 34 on the same screen said the same 2201 and laid out the same page.
        assertEquals(2400, height(34, 2201, 63, 136, 0))
    }

    @Test
    fun `from Android 15 the metrics are the window and are taken as they are`() {
        // API 35 on the 1080x2400 screen, a 63px gesture bar and a 136px cutout:
        // the metrics said 2400 and the row read the WebView's 1184px. API 36,
        // 1440x3120 at 560dpi, an 84px bar and a 144px cutout: the metrics said
        // 3120 and the row read the WebView's 1538px. Adding the bar or the cutout
        // here would move a decision that is right today, and the display is
        // never asked.
        val neverAsked = { error("the display was read on a release that does not need it") }
        fun window(sdk: Int, metrics: Int, bar: Int, cutoutTop: Int) = windowHeightForInsets(
            sdkInt = sdk,
            displayMetricsHeightPx = metrics,
            navigationBarBottomPx = bar,
            cutoutTopPx = cutoutTop,
            cutoutBottomPx = 0,
            displayHeightPx = neverAsked,
        )

        val fifteen = window(35, 2400, 63, 136)
        assertEquals(2400, fifteen)
        assertEquals(1184, page(fifteen, top = 136, bottom = 883, row = 197))
        val sixteen = window(36, 3120, 84, 144)
        assertEquals(3120, sixteen)
        assertEquals(1538, page(sixteen, top = 144, bottom = 1176, row = 262))
    }

    @Test
    fun `the row decides from the window height with the bars it reads ignoring their visibility`() {
        // The arithmetic above only matters if the listener asks it, with the
        // release the device runs and the bar as the configuration left it out:
        // that is whether the bar is showing or not, so a page in full screen,
        // where the bar is hidden, is measured against the same window.
        val row = SourceScan.withoutComments(
            SourceScan.read("src/main/kotlin/com/vscodroid/keyboard/ExtraKeyRow.kt"),
        ).replace(Regex("\\s+"), " ")
        val listener = SourceScan.body(row, "fun setupWithRootView(")
        val height = SourceScan.body(row, "private fun windowHeightPx(")

        assertTrue(
            listener.contains("val pageHeightPx = windowHeightPx(insets) - bars.top - bottomInset - rowHeightPx"),
            "the listener no longer takes the page from the window height below, so the row " +
                "decides from a window short by the navigation bar on Android 10 to 14.",
        )
        assertTrue(
            !listener.contains("displayMetrics.heightPixels"),
            "the listener reads the display metrics as the window height again, which below " +
                "Android 15 leave the navigation bar and the cutout out.",
        )
        assertTrue(
            height.contains("sdkInt = Build.VERSION.SDK_INT") &&
                height.contains("displayMetricsHeightPx = resources.displayMetrics.heightPixels") &&
                height.contains(
                    "insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())",
                ) &&
                height.contains("insets.getInsets(WindowInsetsCompat.Type.displayCutout())") &&
                height.contains("displayHeightPx = ::displayHeightPx"),
            "windowHeightPx no longer hands windowHeightForInsets the release the device runs, " +
                "the metrics, the navigation bar as it is with the bar shown, the cutout and " +
                "the display.",
        )
        assertTrue(
            height.contains("navigationBarBottomPx = navigationBars.bottom") &&
                height.contains("cutoutTopPx = cutout.top") &&
                height.contains("cutoutBottomPx = cutout.bottom"),
            "windowHeightPx hands windowHeightForInsets the wrong edge of a bar or the cutout.",
        )
    }
}
