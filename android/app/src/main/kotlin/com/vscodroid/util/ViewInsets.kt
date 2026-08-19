package com.vscodroid.util

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads [this] by the system bars and display cutout, on top of [basePx] of
 * padding per side, so an edge-to-edge window keeps its content out of the
 * status bar, navigation bar and punch-hole camera.
 *
 * For plain screens only. The editor keeps its own listener
 * ([com.vscodroid.keyboard.ExtraKeyRow.setupWithRootView]) because it also
 * folds the IME inset in. Never attach this to a WebView: the render engine
 * ignores the view's own padding — pad its container instead.
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
 * and `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` from `Api28` and `Api30`.
 * Measured from the release dex with `dexdump`, de-obfuscated through this
 * build's own mapping. Note `Api35`: the references are not confined to the
 * pre-35 compat half, so trimming the old implementations by minSdk would not
 * have cleared them. Dropping the call is what removes the classes.
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
 *
 * What is kept is the part the call sites actually needed: light icons pinned
 * regardless of the device theme. This app is always dark and has no
 * `values-night`, so the `auto` default drew dark icons on a dark background
 * whenever the device was in light mode.
 */
fun Activity.drawBehindSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
}
