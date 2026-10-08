package com.vscodroid.util

import android.app.Activity
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.pow

/**
 * Pads [this] by the system bars and display cutout, on top of [basePx] of
 * padding per side, so an edge-to-edge window keeps its content out of the
 * status bar, navigation bar and punch-hole camera.
 *
 * For plain screens only. The editor keeps its own listener
 * ([com.vscodroid.keyboard.ExtraKeyRow.setupWithRootView]) because it also
 * folds the IME inset in. Never attach this to a WebView: the render engine
 * ignores the view's own padding; pad its container instead.
 */
fun View.padForSystemBars(basePx: Int = 0) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.setPadding(
            basePx + bars.left,
            basePx + bars.top,
            basePx + bars.right,
            basePx + bars.bottom,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * Draws this activity's window behind the system bars, with light bar icons.
 *
 * Replaces `androidx.activity.enableEdgeToEdge()`, and the reason is the bundle
 * rather than the behaviour. Play Console reports three deprecated edge-to-edge
 * APIs, and all three are inside that one call: `Window.setStatusBarColor` and
 * `Window.setNavigationBarColor` from `EdgeToEdgeApi26`, `Api29` and `Api35`,
 * and the display-cutout write from `Api28` and `Api30`. Measured from the
 * release dex with `dexdump`, de-obfuscated through this build's own mapping.
 * Note `Api35`: the references are not confined to the pre-35 compat half, so
 * trimming the old implementations by minSdk would not have cleared them.
 * Dropping the call is what removes the classes.
 *
 * ⚠️ The two cutout implementations write **different** values, and this said
 * `SHORT_EDGES` for both until it was read from the bytecode.
 * `Api28.adjustLayoutInDisplayCutoutMode` stores `1` (`SHORT_EDGES`);
 * `Api30` stores `3` (`ALWAYS`), and `Api35` extends `Api30` without overriding
 * it. minSdk is 33, so the only implementation that ever ran here wrote
 * `ALWAYS`. That is why the theme names `always`: it is what this app already
 * had, not a new choice, and changing it to `shortEdges` to match the old
 * comment would be a real behaviour change.
 *
 * Nothing is lost by dropping it, because `targetSdk` is 36:
 *
 *  - API 35+ enforces edge-to-edge with no opt-out, and the colour setters
 *    `enableEdgeToEdge` calls there are already no-ops.
 *  - API 33 and 34 still need the opt-in, which is the `setDecorFitsSystemWindows`
 *    below. That one is not among the three Play names, and Play Core's asset
 *    pack code already puts it in the bundle regardless.
 *  - The scrims were `Color.TRANSPARENT` at every call site, so the deprecated
 *    setters were being asked for what the platform now does by itself.
 *  - The display cutout moves to `android:windowLayoutInDisplayCutoutMode` in
 *    the theme, which is declarative and names `always`, the value API 35+
 *    forces anyway.
 *  - Contrast enforcement moves to `android:enforceStatusBarContrast` and
 *    `android:enforceNavigationBarContrast`, both `false`. This is the piece
 *    the first version of this migration dropped: `Api29.setUp` calls
 *    `setStatusBarContrastEnforced(false)` unconditionally and
 *    `setNavigationBarContrastEnforced(nightMode == 0)`, and every call site
 *    passed `SystemBarStyle.dark()`, whose nightMode is `MODE_NIGHT_YES`, so
 *    both arrived as false. The defaults differ, and calling them both true was
 *    wrong: `PhoneWindow.generateLayout` defaults `enforceStatusBarContrast` to
 *    false and `enforceNavigationBarContrast` to **true**, so the omission cost
 *    the navigation bar a scrim and the status line restates the default.
 *
 * What is kept is the part the call sites actually needed: light icons pinned
 * regardless of the device theme. The theme's window is dark and there is no
 * `values-night`, so the `auto` default drew dark icons on a dark background
 * whenever the device was in light mode. Where the editor's theme is light,
 * [paintWindow] gives the window that colour and turns the icons dark.
 *
 * Everything else this replaces is an attribute, so nothing about the window is
 * set from code here beyond the two lines below. [ThemeEdgeToEdgeTest] holds the
 * attributes in place, because a theme value has no compiler to lose it.
 */
fun Activity.drawBehindSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
}

/**
 * Gives this window [color] as its background, with bar icons that read on it.
 *
 * The window background is what shows where no view draws: behind the
 * transparent status and navigation bars, below the extra key row while the soft
 * keyboard slides away, and below the WebView's last frame while the WebView
 * holds that frame for a navigation at the height it had with the keyboard up.
 * The WebView's own background does not show in that last space: on an API 36
 * emulator (WebView 153) a magenta one never appeared there, where the theme's
 * #1E1E1E did, as a dark band under a light editor theme.
 */
fun Activity.paintWindow(@ColorInt color: Int) {
    window.setBackgroundDrawable(color.toDrawable())
    val light = isLightColor(color)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = light
        isAppearanceLightNavigationBars = light
    }
}

/**
 * Whether black reads better on [color] than white: its WCAG relative luminance
 * is above the 0.18 or so at which the two contrast with it equally.
 */
fun isLightColor(@ColorInt color: Int): Boolean {
    fun linear(shift: Int): Double {
        val c = (color shr shift and 0xFF) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    val luminance = 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    // Black's ratio, (L + 0.05) / 0.05, against white's, 1.05 / (L + 0.05).
    return (luminance + 0.05) * (luminance + 0.05) > 0.05 * 1.05
}
