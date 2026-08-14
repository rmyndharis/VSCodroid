package com.vscodroid

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Toolchains screen drew its toolbar under the status bar: measured
 * bounds [0,0][1080,168] against a 128px bar, title colliding with the
 * clock. This pins the fix: with edge-to-edge enforced (targetSdk 36),
 * the toolbar must start below the top system inset.
 */
@RunWith(AndroidJUnit4::class)
class ToolchainInsetsTest {

    @Test
    fun toolbarStartsBelowTheStatusBar() {
        ActivityScenario.launch(ToolchainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(R.id.toolchainRoot)
                val insets = ViewCompat.getRootWindowInsets(root)
                assertNotNull("window insets not ready", insets)
                val bars = insets!!.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
                )
                val toolbar = activity.findViewById<View>(R.id.toolbar)
                val onScreen = IntArray(2)
                toolbar.getLocationOnScreen(onScreen)
                assertTrue(
                    "toolbar top ${onScreen[1]} is under the ${bars.top}px status bar",
                    onScreen[1] >= bars.top
                )
            }
        }
    }
}
