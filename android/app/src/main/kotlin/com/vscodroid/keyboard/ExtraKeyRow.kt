package com.vscodroid.keyboard

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
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

    // The modifiers are not stored here. These read and write the adapter's
    // toggle map, which is also what a (re)bound button is painted from, so the
    // value handed to KeyInjector and the value on screen are one value rather
    // than two kept equal by hand. Writing one repaints the live button too, so
    // no site has to remember a second call, forgetting that is what left this
    // row believing Ctrl was held while the map it paints from said otherwise.
    private var ctrlActive: Boolean
        get() = adapter.isToggleActive("Ctrl")
        set(value) { adapter.setToggleState("Ctrl", value) }

    private var altActive: Boolean
        get() = adapter.isToggleActive("Alt")
        set(value) { adapter.setToggleState("Alt", value) }

    private var shiftActive: Boolean
        get() = adapter.isToggleActive("Shift")
        set(value) { adapter.setToggleState("Shift", value) }

    private val viewPager: ViewPager2
    private val dotContainer: LinearLayout
    private val dots = mutableListOf<ImageView>()
    private lateinit var adapter: KeyPageAdapter

    /** The alternates window, while one is open. See [showLongPressPopup]. */
    private var longPressPopup: LongPressPopup? = null

    /**
     * Polls JS modifier flags to detect when the soft keyboard consumed a modifier.
     * When the JS interceptor handles Ctrl+key from the soft keyboard, it resets the
     * JS flags. This runnable detects that and syncs the Kotlin visual state.
     *
     * Shift is spent by the same listener without being intercepted, so it arrives
     * here too. It has to: nothing else clears a Shift while the keyboard is up,
     * and the poll only stops once every modifier is idle, so a latch that never
     * came back false would leave this round trip running for as long as the
     * keyboard was open.
     */
    private val modifierSyncRunnable: Runnable = object : Runnable {
        override fun run() {
            val injector = keyInjector
            if (injector == null) {
                // There is no page on the other side to be holding a modifier
                // for. The injector is dropped when the renderer dies
                // (MainActivity.recreateWebView), and the page that was told
                // about this latch went with it, so a row still painted as
                // latched is promising a modifier to a page that never heard of
                // it. Clearing also ends the chain, at a place that decided to.
                resetModifiersIfNeeded()
                return
            }
            // Queued before the question is asked, never from inside its answer.
            // That answer comes back through WebView.evaluateJavascript, and on
            // the renderer-crash path the WebView being asked is destroyed before
            // it can reply, so a re-post living in the callback ended the poll for
            // good on precisely the event it exists to recover from: the
            // replacement page starts with every flag clear while this row still
            // shows Ctrl held, and nothing else clears a modifier while the
            // keyboard is up. Ending the chain is now stated rather than implied,
            // below and in startModifierSync and resetModifiersIfNeeded, which are
            // the three places that know it should stop.
            postDelayed(modifierSyncRunnable, 200)
            injector.queryModifierState { jsCtrl, jsAlt, jsShift ->
                if (ctrlActive && !jsCtrl) {
                    ctrlActive = false
                    Logger.d(tag, "Ctrl consumed by soft keyboard")
                }
                if (altActive && !jsAlt) {
                    altActive = false
                    Logger.d(tag, "Alt consumed by soft keyboard")
                }
                if (shiftActive && !jsShift) {
                    shiftActive = false
                    Logger.d(tag, "Shift consumed by soft keyboard")
                }
                if (!ctrlActive && !altActive && !shiftActive) {
                    removeCallbacks(modifierSyncRunnable)
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
            // The display cutout is its own inset type, not part of systemBars().
            // Portrait hides that: the status bar is at least as tall as the
            // cutout. Landscape does not: the punch-hole sits on a side edge
            // where systemBars() reports 0, putting the camera over the
            // activity bar. Combining the types takes the max per edge.
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            // Pad container: top for status bar, bottom for max(nav bar, keyboard).
            // This ensures the WebView content area shrinks when the keyboard opens.
            // The container is a vertical LinearLayout, so this row then takes its
            // own height out of the WebView rather than covering the page.
            val bottomInset = maxOf(bars.bottom, ime.bottom)
            v.setPadding(bars.left, bars.top, bars.right, bottomInset)

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
                // Don't reset modifiers here: trackpad fires many arrows per drag.
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
                // Five 8dp circles that say which page is showing in colour and
                // in nothing else. A screen reader stopped on each of them in
                // turn and had nothing to read out, so the row ended in five
                // silent stops. The page they encode is published once, on the
                // container, by [updateDots].
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
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
        dotContainer.contentDescription = pageIndicatorText(selectedPosition)
    }

    /** How many pages the row has and which one it is showing, as a sentence. */
    private fun pageIndicatorText(position: Int): String =
        context.getString(R.string.key_page_indicator, position + 1, dots.size)

    private fun setupPageChangeCallback() {
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                // A swipe replaces every key on the row and moves accessibility
                // focus nowhere, so the description written above is only read
                // by someone who goes looking for the dots. Said out loud, it
                // is the only signal that the keys under the finger changed.
                // The call is inert when no service is listening.
                dotContainer.announceForAccessibility(pageIndicatorText(position))
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
            else -> {
                keyInjector?.injectKey(key, ctrlKey = ctrlActive, altKey = altActive, shiftKey = shiftActive)
                resetModifiersIfNeeded()
            }
        }
    }

    private fun showLongPressPopup(button: ExtraKeyButton, alternates: List<AlternateKey>) {
        // Held rather than discarded, because the popup is a window and not a
        // child of this row: nothing tears it down with the view it is anchored
        // to. A row detached with one open, which is what an activity being
        // finished or recreated does, leaves a window on a dead token, and the
        // only other way to close it is the user touching outside it. Keeping
        // the last one also means a second long press replaces the popup rather
        // than stacking a window over it.
        longPressPopup?.dismiss()
        longPressPopup = LongPressPopup(context, alternates) { selectedKey ->
            keyInjector?.injectKey(selectedKey, ctrlKey = ctrlActive, altKey = altActive, shiftKey = shiftActive)
            resetModifiersIfNeeded()
        }.also { it.show(button) }
    }

    override fun onDetachedFromWindow() {
        longPressPopup?.dismiss()
        longPressPopup = null
        super.onDetachedFromWindow()
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
        // Guarded rather than assigned unconditionally: a write repaints the
        // button, and this runs after every ordinary key press, so clearing three
        // already-idle modifiers would rebuild three backgrounds per keystroke.
        if (ctrlActive) ctrlActive = false
        if (altActive) altActive = false
        if (shiftActive) shiftActive = false
        syncModifierState()
        removeCallbacks(modifierSyncRunnable)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}
