package com.vscodroid.keyboard

import androidx.annotation.StringRes
import com.vscodroid.R

/**
 * One entry in a key's long-press popup, on the same terms as [KeyItem.Button].
 *
 * [contentDescriptionRes] carries no default for the reason the button's does
 * not: the popup drew each alternate with its glyph and nothing else, so the
 * only thing a screen reader could announce was the character itself. That is
 * the failure the row's descriptions exist to prevent, and this layer is the
 * one place it survived; `'` and `\` are on no page at all, so the popup is
 * their only route and the popup said nothing about them. Requiring the
 * resource makes an unnamed alternate a compile error rather than a silent one,
 * and puts the words where a translator can reach them.
 */
data class AlternateKey(
    val label: String,
    val value: String,
    @StringRes val contentDescriptionRes: Int,
)

sealed class KeyItem {
    /**
     * One key on the row: what it types, what is drawn on it, and what is said.
     *
     * The three are deliberately different kinds of thing. [value] is a DOM key
     * name the page receives and [label] is the glyph painted on the key. Both
     * are characters rather than language, and neither belongs in strings.xml:
     * resolving [label] through a locale would change what the keyboard shows
     * and, for the many keys whose label is their value, what it types.
     *
     * [contentDescriptionRes] is the half a screen reader reads, so it is the
     * half that is a sentence and the only half a translator ever sees. It
     * carries no default. It used to default to [label], which meant a key added
     * without one compiled and shipped, and a reader hearing "{}" was told
     * nothing at all; requiring it makes that a compile error instead of
     * something a test has to catch afterwards.
     */
    data class Button(
        val label: String,
        val value: String,
        @StringRes val contentDescriptionRes: Int,
        val isToggle: Boolean = false,
        val alternates: List<AlternateKey> = emptyList()
    ) : KeyItem()

    // No description here, deliberately. It used to carry one and nothing ever
    // read it: KeyPageAdapter resolves contentDescriptionRes in the Button
    // branch only, so the description actually spoken is the one GestureTrackpad
    // sets on itself. A field that looks like the place to change the label, and
    // silently is not, is worse than no field.
    data object GesturePad : KeyItem()
}

data class KeyPage(val items: List<KeyItem>)

/**
 * The smallest side a touch target is allowed to have, in dp.
 *
 * Android's own guideline, and the number [ExtraKeyButton] already asks for
 * through `minWidth`. That request is inert on this row: every child is given a
 * weight and a zero width, so `LinearLayout` remeasures it with an EXACTLY spec
 * at its share and the clamp never runs. Dividing the row is therefore the only
 * lever there is, which is what [KeyPages.forSmallestWidthDp] pulls.
 */
internal const val MIN_TOUCH_TARGET_DP = 48

/**
 * The share of the row a key and a trackpad each claim, in tenths of a weight
 * unit.
 *
 * In tenths so the packing arithmetic below stays integer: the pad's share is
 * one and a half. `keyLayoutParams` and `padLayoutParams` build the real
 * `LayoutParams` from these same two numbers, and they have to. They used to be
 * written out twice, here and as `1f` and `1.5f` in `KeyPageAdapter`, so
 * widening the pad over there would have left [KeyPages.forSmallestWidthDp]
 * packing pages against the old share, putting every key on a narrow phone back
 * under the touch target with the whole JVM suite green.
 */
internal const val KEY_WEIGHT_TENTHS = 10
internal const val PAD_WEIGHT_TENTHS = 15

/** The share of the row one item claims, in tenths, per `KeyPageAdapter`. */
private fun KeyItem.weightTenths(): Int = when (this) {
    is KeyItem.Button -> KEY_WEIGHT_TENTHS
    is KeyItem.GesturePad -> PAD_WEIGHT_TENTHS
}

object KeyPages {
    val defaults: List<KeyPage> = listOf(
        // Page 1: Essential coding keys
        KeyPage(listOf(
            KeyItem.Button("tab", "Tab", R.string.key_desc_tab),
            KeyItem.Button("esc", "Escape", R.string.key_desc_escape),
            KeyItem.Button("ctrl", "Ctrl", R.string.key_desc_ctrl, isToggle = true),
            KeyItem.Button("alt", "Alt", R.string.key_desc_alt, isToggle = true),
            KeyItem.Button("shift", "Shift", R.string.key_desc_shift, isToggle = true),
            KeyItem.GesturePad,
            KeyItem.Button("{}", "{", R.string.key_desc_curly_braces,
                alternates = listOf(
                    AlternateKey("[", "[", R.string.key_desc_left_bracket),
                    AlternateKey("<", "<", R.string.key_desc_less_than),
                )),
            // `)` is here because no other route on the row reaches it. The key
            // itself sends `(`, and a latched Shift cannot resolve the pair the
            // way it resolves `}` over `]`: shiftedForm returns `(` unchanged
            // because it already requires Shift, and `)` sits on Digit0, which
            // no page carries. Auto-closing brackets supply it most of the time,
            // which is why it went unnoticed, and never when the pair was not
            // auto-closed or the setting is off.
            KeyItem.Button("()", "(", R.string.key_desc_parentheses,
                alternates = listOf(
                    AlternateKey(")", ")", R.string.key_desc_closing_parenthesis),
                    AlternateKey("]", "]", R.string.key_desc_right_bracket),
                    AlternateKey(">", ">", R.string.key_desc_greater_than),
                )),
        )),
        // Page 2: Common symbols
        KeyPage(listOf(
            KeyItem.Button(";", ";", R.string.key_desc_semicolon),
            KeyItem.Button(":", ":", R.string.key_desc_colon),
            KeyItem.Button("\"", "\"", R.string.key_desc_double_quote,
                alternates = listOf(
                    AlternateKey("'", "'", R.string.key_desc_apostrophe),
                    AlternateKey("`", "`", R.string.key_desc_backtick),
                )),
            KeyItem.Button("/", "/", R.string.key_desc_forward_slash,
                alternates = listOf(AlternateKey("\\", "\\", R.string.key_desc_backslash))),
            KeyItem.Button("|", "|", R.string.key_desc_pipe),
            KeyItem.Button("`", "`", R.string.key_desc_backtick,
                alternates = listOf(AlternateKey("~", "~", R.string.key_desc_tilde))),
            KeyItem.Button("&", "&", R.string.key_desc_ampersand),
            KeyItem.Button("_", "_", R.string.key_desc_underscore),
        )),
        // Page 3: Brackets & operators
        KeyPage(listOf(
            KeyItem.Button("[", "[", R.string.key_desc_left_bracket),
            KeyItem.Button("]", "]", R.string.key_desc_right_bracket),
            KeyItem.Button("<", "<", R.string.key_desc_less_than),
            KeyItem.Button(">", ">", R.string.key_desc_greater_than),
            KeyItem.Button("=", "=", R.string.key_desc_equals),
            KeyItem.Button("!", "!", R.string.key_desc_exclamation),
            KeyItem.Button("#", "#", R.string.key_desc_hash),
            KeyItem.Button("@", "@", R.string.key_desc_at_sign),
        )),
        // Pages 4 and 5: function and navigation keys. Nothing else on the row
        // reaches them. No other page carries one, and the trackpad emits arrows
        // only (TrackpadGesture.accumulate), so any binding on a function key or
        // on Home, End, PageUp and PageDown wanted a hardware keyboard.
        //
        // Sixteen keys are split over two pages of eight rather than crowded
        // onto one, and eight is a ceiling rather than a preference. Every
        // button gets LayoutParams(0, MATCH_PARENT, 1f) in KeyPageAdapter, so
        // LinearLayout measures it with an EXACTLY spec at its share of the row
        // and ExtraKeyButton's own 48dp minWidth cannot widen it back. Sixteen
        // on a 360dp portrait row leaves about 18.5dp a key, well under the
        // 48dp minimum touch target, and four-character labels like PgDn clip
        // rather than merely shrink. Eight leaves about 45dp, which is what
        // pages 2 and 3 already carry, and is still short: [forSmallestWidthDp]
        // is what takes the pages the rest of the way on a narrow phone.
        // Page 4: F1 to F8
        KeyPage(listOf(
            KeyItem.Button("F1", "F1", R.string.key_desc_f1),
            KeyItem.Button("F2", "F2", R.string.key_desc_f2),
            KeyItem.Button("F3", "F3", R.string.key_desc_f3),
            KeyItem.Button("F4", "F4", R.string.key_desc_f4),
            KeyItem.Button("F5", "F5", R.string.key_desc_f5),
            KeyItem.Button("F6", "F6", R.string.key_desc_f6),
            KeyItem.Button("F7", "F7", R.string.key_desc_f7),
            KeyItem.Button("F8", "F8", R.string.key_desc_f8),
        )),
        // Page 5: F9 to F12 and the four navigation keys
        KeyPage(listOf(
            KeyItem.Button("F9", "F9", R.string.key_desc_f9),
            KeyItem.Button("F10", "F10", R.string.key_desc_f10),
            KeyItem.Button("F11", "F11", R.string.key_desc_f11),
            KeyItem.Button("F12", "F12", R.string.key_desc_f12),
            KeyItem.Button("home", "Home", R.string.key_desc_home),
            KeyItem.Button("end", "End", R.string.key_desc_end),
            KeyItem.Button("PgUp", "PageUp", R.string.key_desc_page_up),
            KeyItem.Button("PgDn", "PageDown", R.string.key_desc_page_down),
        )),
    )

    /**
     * [defaults], repacked so that no key on a row [smallestWidthDp] dp wide is
     * narrower than [MIN_TOUCH_TARGET_DP].
     *
     * That holds at every width, not only at the ones a phone has. The single
     * exception is a row with no room for even one item at that size, where the
     * loop below gives that item a page to itself and it is already as wide as
     * the row can make it. No window Android hands out is that narrow.
     *
     * The pages above are sized for the width this was built on. A row divides
     * itself exactly, so page 1's 8.5 shares are 48.4dp at 411dp and 42.4dp at
     * 360dp, which is a very common portrait width and well under the guideline;
     * at 320dp it is 37.6dp. Every key of every page is affected, on the control
     * this app's whole touch story rests on, so the fix cannot be per key.
     *
     * The argument is `smallestScreenWidthDp` rather than the current width,
     * deliberately. Rotating does not move it, so a phone turned on its side
     * keeps the portrait page count and gets keys wider than they need to be,
     * which is the harmless direction to be wrong in and costs no rebuild.
     *
     * Multi-window is the direction that is not harmless. The value tracks the
     * ACTIVITY's window, not the device, so dropping a full-screen session into
     * a narrow split-screen pane lowers it, and `MainActivity` declares
     * `smallestScreenSize` in its `configChanges`, so the activity survives
     * that resize. Keys sized for 411dp then divide a row half that wide and go
     * back under [MIN_TOUCH_TARGET_DP]. [ExtraKeyRow.onConfigurationChanged]
     * repacks for it, comparing this function's result against the pages the
     * row is already showing so that a change which does not move a page
     * boundary rebuilds nothing: a rebuild resets the pager to page 1 and hands
     * the adapter's toggle map, which is where a latched modifier lives, across
     * by hand.
     *
     * Repacked from [defaults] rather than written out a second time: two hand-
     * maintained page sets is two places for a key to go missing from, and the
     * one nobody develops on is the one it goes missing from. Order is preserved,
     * so a key stays in the same neighbourhood it was in.
     */
    fun forSmallestWidthDp(smallestWidthDp: Int): List<KeyPage> {
        // A width that cannot belong to a device is no information, not an
        // instruction to put one key on a page: `Configuration` reports
        // SMALLEST_SCREEN_WIDTH_DP_UNDEFINED, which is 0, for a value it has not
        // resolved, and 0 divided by anything would repack these five pages into
        // forty. 320dp is Android's own floor for a phone, so that is what an
        // unresolved width is read as. `<= 0` and not `== 0` because a negative
        // is just as much not a width, and would pack the same forty pages.
        //
        // A sentinel test and NOT `coerceAtLeast(320)`, which is the shape this
        // guard first took. 320 is a floor on a phone's screen, and what arrives
        // here is a window's smallest side: the activity is resizeable and
        // declares `smallestScreenSize`, so a freeform or multi-pane window
        // narrower than that reports its real width and keeps this row alive
        // across the resize. A floor sizes those pages for 320 while the row
        // goes on dividing the width it actually has, which puts every key back
        // under [MIN_TOUCH_TARGET_DP] in exactly the case this function exists
        // for, and does it silently.
        val widthDp = if (smallestWidthDp <= 0) 320 else smallestWidthDp
        val maxTenths = widthDp * 10 / MIN_TOUCH_TARGET_DP
        if (defaults.all { page -> page.items.sumOf { it.weightTenths() } <= maxTenths }) {
            return defaults
        }
        val repacked = mutableListOf<KeyPage>()
        var current = mutableListOf<KeyItem>()
        var tenths = 0
        for (item in defaults.flatMap { it.items }) {
            val share = item.weightTenths()
            // `current.isNotEmpty()` so an item wider than a whole row still gets
            // a page rather than an endless empty one. No device is that narrow;
            // the loop must terminate anyway.
            if (current.isNotEmpty() && tenths + share > maxTenths) {
                repacked.add(KeyPage(current))
                current = mutableListOf()
                tenths = 0
            }
            current.add(item)
            tenths += share
        }
        if (current.isNotEmpty()) repacked.add(KeyPage(current))
        return repacked
    }
}
