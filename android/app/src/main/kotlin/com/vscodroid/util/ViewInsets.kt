package com.vscodroid.util

import android.view.View
import androidx.core.view.ViewCompat
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
