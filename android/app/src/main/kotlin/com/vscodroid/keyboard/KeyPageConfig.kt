package com.vscodroid.keyboard

import androidx.annotation.StringRes
import com.vscodroid.R

data class AlternateKey(val label: String, val value: String)

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

object KeyPages {
    val defaults: List<KeyPage> = listOf(
        // Page 1: Essential coding keys
        KeyPage(listOf(
            KeyItem.Button("Tab", "Tab", R.string.key_desc_tab),
            KeyItem.Button("Esc", "Escape", R.string.key_desc_escape),
            KeyItem.Button("Ctrl", "Ctrl", R.string.key_desc_ctrl, isToggle = true),
            KeyItem.Button("Alt", "Alt", R.string.key_desc_alt, isToggle = true),
            KeyItem.Button("Shift", "Shift", R.string.key_desc_shift, isToggle = true),
            KeyItem.GesturePad,
            KeyItem.Button("{}", "{", R.string.key_desc_curly_braces,
                alternates = listOf(AlternateKey("[", "["), AlternateKey("<", "<"))),
            KeyItem.Button("()", "(", R.string.key_desc_parentheses,
                alternates = listOf(AlternateKey("]", "]"), AlternateKey(">", ">"))),
        )),
        // Page 2: Common symbols
        KeyPage(listOf(
            KeyItem.Button(";", ";", R.string.key_desc_semicolon),
            KeyItem.Button(":", ":", R.string.key_desc_colon),
            KeyItem.Button("\"", "\"", R.string.key_desc_double_quote,
                alternates = listOf(AlternateKey("'", "'"), AlternateKey("`", "`"))),
            KeyItem.Button("/", "/", R.string.key_desc_forward_slash,
                alternates = listOf(AlternateKey("\\", "\\"))),
            KeyItem.Button("|", "|", R.string.key_desc_pipe),
            KeyItem.Button("`", "`", R.string.key_desc_backtick,
                alternates = listOf(AlternateKey("~", "~"))),
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
        // rather than merely shrink. Eight leaves about 41dp, which is what
        // pages 2 and 3 already carry.
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
            KeyItem.Button("Home", "Home", R.string.key_desc_home),
            KeyItem.Button("End", "End", R.string.key_desc_end),
            KeyItem.Button("PgUp", "PageUp", R.string.key_desc_page_up),
            KeyItem.Button("PgDn", "PageDown", R.string.key_desc_page_down),
        )),
    )
}
