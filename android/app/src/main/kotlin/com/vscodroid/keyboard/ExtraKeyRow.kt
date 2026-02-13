package com.vscodroid.keyboard

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.vscodroid.R
import com.vscodroid.util.Logger

class ExtraKeyRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val tag = "ExtraKeyRow"

    var keyInjector: KeyInjector? = null

    private var ctrlActive = false
    private var altActive = false
    private var shiftActive = false

    private val viewPager: ViewPager2
    private val dotContainer: LinearLayout
    private val dots = mutableListOf<ImageView>()
    private lateinit var adapter: KeyPageAdapter

    /**
     * Polls JS modifier flags to detect when the soft keyboard consumed a modifier.
     * When the JS interceptor handles Ctrl+key from the soft keyboard, it resets the
     * JS flags. This runnable detects that and syncs the Kotlin visual state.
     */
    private val modifierSyncRunnable: Runnable = object : Runnable {
        override fun run() {
            keyInjector?.queryModifierState { jsCtrl, jsAlt, jsShift ->
                if (ctrlActive && !jsCtrl) {
                    ctrlActive = false
                    adapter.setToggleState("Ctrl", false)
                    Logger.d(tag, "Ctrl consumed by soft keyboard")
                }
                if (altActive && !jsAlt) {
                    altActive = false
                    adapter.setToggleState("Alt", false)
                    Logger.d(tag, "Alt consumed by soft keyboard")
                }
                if (shiftActive && !jsShift) {
                    shiftActive = false
                    adapter.setToggleState("Shift", false)
                    Logger.d(tag, "Shift consumed by soft keyboard")
                }
                if (ctrlActive || altActive || shiftActive) {
                    postDelayed(modifierSyncRunnable, 200)
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.colorSurface))

        // ViewPager2 for swipeable key pages
        viewPager = ViewPager2(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(56))
            offscreenPageLimit = 1
        }
        addView(viewPager)

        // Dot page indicator
        dotContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(2)
                bottomMargin = dpToPx(4)
            }
        }
        addView(dotContainer)

        setupAdapter()
        setupDots()
        setupPageChangeCallback()
    }

    fun setupWithRootView(rootView: View) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            // Pad container: top for status bar, bottom for max(nav bar, keyboard).
            // This ensures the WebView content area shrinks when the keyboard opens,
            // and ExtraKeyRow (gravity=bottom) sits right above it.
            val bottomInset = maxOf(systemBars.bottom, ime.bottom)
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomInset)

            visibility = if (imeVisible) View.VISIBLE else View.GONE
            if (!imeVisible) {
                resetModifiersIfNeeded()
            }
            Logger.d(tag, "IME visible=$imeVisible, bottomInset=$bottomInset")
            insets
        }
    }

    private fun setupAdapter() {
        adapter = KeyPageAdapter(
            pages = KeyPages.defaults,
            onKeyAction = { key, isActive, button -> handleKeyAction(key, isActive, button) },
            onArrowKey = { direction ->
                // Don't reset modifiers here — trackpad fires many arrows per drag.
                // Shift must stay active during the entire drag for text selection.
                keyInjector?.injectKey(direction, ctrlKey = ctrlActive, altKey = altActive, shiftKey = shiftActive)
            },
            onDragEnd = {
                resetModifiersIfNeeded()
            },
            onLongPress = { button, alternates -> showLongPressPopup(button, alternates) }
        )
        viewPager.adapter = adapter
    }

    private fun setupDots() {
        val pageCount = KeyPages.defaults.size
        dotContainer.removeAllViews()
        dots.clear()

        for (i in 0 until pageCount) {
            val size = dpToPx(8)
            val dot = ImageView(context).apply {
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setSize(size, size)
                }
                setImageDrawable(drawable)
                layoutParams = LayoutParams(size, size).apply {
                    marginStart = dpToPx(4)
                    marginEnd = dpToPx(4)
                }
            }
            dots.add(dot)
            dotContainer.addView(dot)
        }
        updateDots(0)
    }

    private fun updateDots(selectedPosition: Int) {
        val activeColor = context.getColor(R.color.colorExtraKeyActive)
        val inactiveColor = 0x55FFFFFF
        for ((i, dot) in dots.withIndex()) {
            (dot.drawable as? GradientDrawable)?.setColor(
                if (i == selectedPosition) activeColor else inactiveColor
            )
        }
    }

    private fun setupPageChangeCallback() {
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
            }
        })
    }

    private fun handleKeyAction(key: String, isActive: Boolean, button: ExtraKeyButton) {
        when (key) {
            "Ctrl" -> {
                ctrlActive = isActive
                syncModifierState()
                startModifierSync()
                Logger.d(tag, "Ctrl toggled: $ctrlActive")
            }
            "Alt" -> {
                altActive = isActive
                syncModifierState()
                startModifierSync()
                Logger.d(tag, "Alt toggled: $altActive")
            }
            "Shift" -> {
                shiftActive = isActive
                syncModifierState()
                startModifierSync()
                Logger.d(tag, "Shift toggled: $shiftActive")
            }
            "{" -> {
                // Only inject opening brace — Monaco auto-closes and positions cursor inside
                keyInjector?.injectKey("{", ctrlKey = ctrlActive, altKey = altActive, shiftKey = shiftActive)
                resetModifiersIfNeeded()
            }
            "(" -> {
                // Only inject opening paren — Monaco auto-closes and positions cursor inside
                keyInjector?.injectKey("(", ctrlKey = ctrlActive, altKey = altActive, shiftKey = shiftActive)
                resetModifiersIfNeeded()
            }
            else -> {
                keyInjector?.injectKey(key, ctrlKey = ctrlActive, altKey = altActive, shiftKey = shiftActive)
                resetModifiersIfNeeded()
            }
        }
    }

    private fun showLongPressPopup(button: ExtraKeyButton, alternates: List<AlternateKey>) {
        LongPressPopup(context, alternates) { selectedKey ->
            keyInjector?.injectKey(selectedKey, ctrlKey = ctrlActive, altKey = altActive, shiftKey = shiftActive)
            resetModifiersIfNeeded()
        }.show(button)
    }

    /** Push current Kotlin modifier state to the JS interceptor. */
    private fun syncModifierState() {
        keyInjector?.setModifierState(ctrlActive, altActive, shiftActive)
    }

    /** Start polling JS state to detect when soft keyboard consumed a modifier. */
    private fun startModifierSync() {
        removeCallbacks(modifierSyncRunnable)
        if (ctrlActive || altActive || shiftActive) {
            postDelayed(modifierSyncRunnable, 200)
        }
    }

    private fun resetModifiersIfNeeded() {
        if (ctrlActive) {
            ctrlActive = false
            adapter.setToggleState("Ctrl", false)
        }
        if (altActive) {
            altActive = false
            adapter.setToggleState("Alt", false)
        }
        if (shiftActive) {
            shiftActive = false
            adapter.setToggleState("Shift", false)
        }
        syncModifierState()
        removeCallbacks(modifierSyncRunnable)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}
