package com.vscodroid

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Toolchains screen drew its toolbar under the status bar (measured
 * bounds [0,0][1080,168] against a 128px bar, title colliding with the
 * clock) and its grid under the navigation bar. These pin the fix on both
 * edges: with edge-to-edge enforced (targetSdk 36), the toolbar must start
 * below the top system inset and the grid must stop above the bottom one.
 *
 * Measurements are polled with a deadline: insets arrive with the first
 * ViewRootImpl traversal, which can land after RESUMED on a slow emulator,
 * and asserting on whatever onActivity happens to see first is a race.
 */
@RunWith(AndroidJUnit4::class)
class ToolchainInsetsTest {

    private class Measured(
        val bars: Insets,
        val toolbarTop: Int,
        val gridBottom: Int,
        val windowHeight: Int,
    )

    private fun measure(scenario: ActivityScenario<ToolchainActivity>): Measured {
        val deadline = System.currentTimeMillis() + 5_000
        var out: Measured? = null
        while (out == null && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(R.id.toolchainRoot)
                val insets = ViewCompat.getRootWindowInsets(root)
                val toolbar = activity.findViewById<View>(R.id.toolbar)
                val grid = activity.findViewById<View>(R.id.toolchainGrid)
                if (insets != null && toolbar.height > 0 && grid.height > 0) {
                    val loc = IntArray(2)
                    toolbar.getLocationOnScreen(loc)
                    val toolbarTop = loc[1]
                    grid.getLocationOnScreen(loc)
                    out = Measured(
                        bars = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars() or
                                WindowInsetsCompat.Type.displayCutout()
                        ),
                        toolbarTop = toolbarTop,
                        gridBottom = loc[1] + grid.height,
                        windowHeight = activity.window.decorView.height,
                    )
                }
            }
            if (out == null) Thread.sleep(50)
        }
        return out ?: fail("insets or layout never arrived within 5s").let { throw AssertionError() }
    }

    @Test
    fun toolbarStartsBelowTheStatusBar() {
        ActivityScenario.launch(ToolchainActivity::class.java).use { scenario ->
            val m = measure(scenario)
            assertTrue(
                "toolbar top ${m.toolbarTop} is under the ${m.bars.top}px status bar",
                m.toolbarTop >= m.bars.top
            )
        }
    }

    @Test
    fun gridStopsAboveTheNavigationBar() {
        ActivityScenario.launch(ToolchainActivity::class.java).use { scenario ->
            val m = measure(scenario)
            val navTop = m.windowHeight - m.bars.bottom
            assertTrue(
                "grid bottom ${m.gridBottom} runs under the navigation bar starting at $navTop",
                m.gridBottom <= navTop
            )
        }
    }
}
