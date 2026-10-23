package com.vscodroid.keyboard

import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.vscodroid.R
import com.vscodroid.ToolchainActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyRowDensityInstrumentedTest {
    @Test
    @Suppress("DEPRECATION")
    fun densityChangeRebuildsKeysAndRetainsTheSelectedPageAndModifiers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var row: ExtraKeyRow
        lateinit var context: ContextThemeWrapper
        var previousHeight = 0
        ActivityScenario.launch(ToolchainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    densityDpi = 320
                }
                context = ContextThemeWrapper(
                    instrumentation.targetContext.createConfigurationContext(config),
                    R.style.Theme_VSCodroid,
                )
                row = ExtraKeyRow(context)
                activity.setContentView(row)
                val pager = row.getChildAt(0) as ViewPager2
                previousHeight = row.rowHeightPx
                (pager.adapter as KeyPageAdapter).setToggleState("Ctrl", true)
                pager.setCurrentItem(2, false)
            }
            instrumentation.waitForIdleSync()
            capture("density-before.png", row)
            scenario.onActivity {
                val changed = Configuration(context.resources.configuration).apply { densityDpi = 480 }
                val metrics = DisplayMetrics().apply {
                    setTo(context.resources.displayMetrics)
                    densityDpi = 480
                    density = 3f
                    scaledDensity = 3f * changed.fontScale
                }
                context.resources.updateConfiguration(changed, metrics)
                row.dispatchConfigurationChanged(changed)
            }
            instrumentation.waitForIdleSync()
            capture("density-after.png", row)
            scenario.onActivity {
                val pager = row.getChildAt(0) as ViewPager2
                assertEquals(168, pager.layoutParams.height)
                assertTrue("the inset calculation still uses the old row height", row.rowHeightPx > previousHeight)
                assertEquals(2, pager.currentItem)
                assertTrue((pager.adapter as KeyPageAdapter).isToggleActive("Ctrl"))
            }
        }
    }

    private fun capture(name: String, row: ExtraKeyRow) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync {
            row.postOnAnimation { row.postOnAnimation { drawn.countDown() } }
        }
        assertTrue("the row did not draw", drawn.await(3, TimeUnit.SECONDS))
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
