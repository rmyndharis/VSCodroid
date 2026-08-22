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
     * How many modifier triples have been pushed at the page.
     *
     * Not a modifier's state and not a copy of one: the row keeps none of that,
     * and `ExtraKeyToggleStateTest` holds it to that. This counts pushes, which
     * is the one thing about the page the poll cannot read back off it.
     *
     * The answer to [KeyInjector.queryModifierState] is a snapshot the renderer
     * took when the query script ran, and scripts run in the order they were
     * submitted, so a push made after the query was submitted cannot be in that
     * answer: the modifier the user has just latched comes back clear. Read
     * before the question and again in the answer, this is what separates "the
     * page has spent this modifier", which is what the poll exists to notice,
     * from "the page has not been told about it yet", which looks identical.
     */
    private var modifierPushCount = 0

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
        /**
         * The one [KeyInjector.queryModifierState] allowed to be waiting for its
         * answer, held as the injector that was asked. See [OutstandingModifierQuery].
         *
         * The re-post below is unconditional, which is what keeps the chain alive
         * when no answer ever comes. On its own that also gave up the bound the old
         * callback-anchored re-post got for free: a renderer slow to reply was sent
         * a fresh query every 200 ms with nothing waiting on the last one, for as
         * long as a modifier stayed latched. Asking again only once the previous
         * answer is in restores exactly one outstanding query, without making the
         * poll depend on an answer arriving.
         *
         * On the runnable rather than on the row, because it is the poll's own
         * bookkeeping and no other member reads it. It is deliberately not a
         * modifier's state, which this class keeps nowhere and reads from the
         * adapter instead; `ExtraKeyToggleStateTest` holds the row to that.
         */
        private val outstanding = OutstandingModifierQuery()

        override fun run() {
            val injector = keyInjector
            if (injector == null) {
                // There is no page on the other side to be holding a modifier
                // for. The injector is dropped when the renderer dies
                // (MainActivity.recreateWebView), and the page that was told
                // about this latch went with it, so a row still painted as
                // latched is promising a modifier to a page that never heard of
                // it. Clearing also ends the chain, at a place that decided to.
                //
                // Reached only where no replacement injector has arrived: the
                // Activity being destroyed, and the crash during a cold start,
                // where recreateWebView rebuilds the injector through loadVSCode
                // and therefore only once a port is bound. A crash with the
                // editor up hands this row a new injector on the same main
                // thread turn, before this tick runs at all. Either way the
                // answer still owed is owed by an injector that is gone, so it
                // is given up here rather than left standing over the next one.
                outstanding.abandon()
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
            // The tick is what survives an answer that never comes; the answer is
            // what says whether asking again is worth anything. See [outstanding]
            // for why the two are separated, and [OutstandingModifierQuery] for why
            // the question is "does this injector owe an answer" rather than "is an
            // answer owed": the second is unanswerable once the injector that owed
            // it is gone, and stays true for the life of the row.
            if (!outstanding.claim(injector)) return
            // Taken before the question, because [modifierPushCount] is only
            // meaningful against the moment the query was submitted.
            val pushedWhenAsked = modifierPushCount
            injector.queryModifierState { jsCtrl, jsAlt, jsShift ->
                if (!outstanding.release(injector)) {
                    // An answer about a page this row no longer has: it was asked
                    // of an injector that has since been replaced or dropped. Its
                    // flags describe a page that is gone, and releasing here would
                    // free the claim the current injector's query is holding.
                    return@queryModifierState
                }
                if (modifierPushCount != pushedWhenAsked) {
                    // A modifier was latched while this answer was in the air, so
                    // the answer predates it and reports it as clear. Applying it
                    // would darken the key the user has just pressed while the
                    // page goes on holding the modifier, and this is the one
                    // place a modifier is cleared without pushing the new value
                    // out, so nothing would correct that: the next ordinary
                    // character typed on the soft keyboard is cancelled and
                    // delivered as a chord instead. Ending the chain is skipped
                    // with the rest; the push that moved the count came from
                    // handleKeyAction, which calls startModifierSync after it and
                    // is the site that decides whether the poll goes on at all.
                    return@queryModifierState
                }
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
        val injector = keyInjector ?: return
        // Counted only where a push actually happens. With no injector there is
        // no page to have been told anything, so nothing an outstanding answer
        // could be describing has moved. See [modifierPushCount].
        modifierPushCount += 1
        injector.setModifierState(ctrlActive, altActive, shiftActive)
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

/**
 * The one modifier query allowed to be in the air, and whose answer it is.
 *
 * The poll re-posts itself before it asks anything, so the tick survives an
 * answer that never comes. This is the other half: it keeps exactly one query
 * outstanding, so a renderer slow to reply is not sent a fresh one five times a
 * second for as long as a modifier stays latched.
 *
 * What it records is the injector that was asked, not merely that something was.
 * The injector owns the answer's lifetime and therefore this record's: an answer
 * travels back through `WebView.evaluateJavascript`, so a WebView destroyed
 * before it replies never delivers one. A plain "a query is out" flag would then
 * stay set for the rest of the Activity, because the row outlives the page.
 * `MainActivity.recreateWebView` replaces only the WebView: it drops the
 * injector, then reaches `initBridge` through `loadVSCode` on the same main
 * thread turn and hands this same row a new `KeyInjector`, so the poll's
 * no-injector branch is not even reached on that path. Every later tick would
 * have returned at the guard without asking, and the row would never again learn
 * that the page had spent a latched modifier: the next row key goes out as a
 * chord, and a whole trackpad drag does, since the arrow path deliberately keeps
 * the modifier.
 *
 * Asked against the injector, that cannot happen: a claim by an injector other
 * than the one owing an answer displaces it, because an answer nobody can
 * deliver is an answer nobody is waiting for. The record is therefore released
 * on three occasions and can be stuck on none of them: [claim] by a new
 * injector, [release] by the answer arriving, [abandon] when the row is left
 * with no injector at all. Bounded at one reference, and that reference is
 * replaced or dropped rather than accumulated.
 *
 * Main thread only, which is where both writers run: the tick is a
 * `View.postDelayed` and the answer is an `evaluateJavascript` callback.
 */
internal class OutstandingModifierQuery {

    /**
     * The injector owing an answer, or null when none is.
     *
     * Identity only. It is never called through and never dereferenced; it says
     * whose answer is still owed, which is the one thing a boolean could not.
     */
    private var askedOf: KeyInjector? = null

    /**
     * Takes the single outstanding slot for [injector], reporting whether the
     * caller may now ask. False only while that same injector already owes an
     * answer, which is the back-pressure; a different injector always takes it,
     * displacing a claim whose answer can no longer arrive.
     */
    fun claim(injector: KeyInjector): Boolean {
        if (askedOf === injector) return false
        askedOf = injector
        return true
    }

    /**
     * Frees the slot for an answer from [injector], reporting whether that
     * answer is the one being waited for. False for an answer from an injector
     * that has since been replaced or dropped: it describes a page that is gone,
     * and it must not free a slot a later query is holding.
     */
    fun release(injector: KeyInjector): Boolean {
        if (askedOf !== injector) return false
        askedOf = null
        return true
    }

    /** Gives up on the answer entirely, for a row left with no injector. */
    fun abandon() {
        askedOf = null
    }
}
