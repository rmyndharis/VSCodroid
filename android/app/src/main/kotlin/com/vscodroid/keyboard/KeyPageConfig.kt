package com.vscodroid.keyboard

data class AlternateKey(val label: String, val value: String)

sealed class KeyItem {
    data class Button(
        val label: String,
        val value: String,
        val isToggle: Boolean = false,
        val contentDescription: String = label,
        val alternates: List<AlternateKey> = emptyList()
    ) : KeyItem()

    data class GesturePad(
        val contentDescription: String = "Arrow key trackpad"
    ) : KeyItem()
}

data class KeyPage(val items: List<KeyItem>)

object KeyPages {
    val defaults: List<KeyPage> = listOf(
        // Page 1: Essential coding keys
        KeyPage(listOf(
            KeyItem.Button("Tab", "Tab", contentDescription = "Tab key"),
            KeyItem.Button("Esc", "Escape", contentDescription = "Escape key"),
            KeyItem.Button("Ctrl", "Ctrl", isToggle = true, contentDescription = "Control modifier"),
            KeyItem.Button("Alt", "Alt", isToggle = true, contentDescription = "Alt modifier"),
            KeyItem.Button("Shift", "Shift", isToggle = true, contentDescription = "Shift modifier"),
            KeyItem.GesturePad(),
            KeyItem.Button("{}", "{", contentDescription = "Curly braces",
                alternates = listOf(AlternateKey("[", "["), AlternateKey("<", "<"))),
            KeyItem.Button("()", "(", contentDescription = "Parentheses",
                alternates = listOf(AlternateKey("]", "]"), AlternateKey(">", ">"))),
        )),
        // Page 2: Common symbols
        KeyPage(listOf(
            KeyItem.Button(";", ";", contentDescription = "Semicolon"),
            KeyItem.Button(":", ":", contentDescription = "Colon"),
            KeyItem.Button("\"", "\"", contentDescription = "Double quote",
                alternates = listOf(AlternateKey("'", "'"), AlternateKey("`", "`"))),
            KeyItem.Button("/", "/", contentDescription = "Forward slash",
                alternates = listOf(AlternateKey("\\", "\\"))),
            KeyItem.Button("|", "|", contentDescription = "Pipe"),
            KeyItem.Button("`", "`", contentDescription = "Backtick",
                alternates = listOf(AlternateKey("~", "~"))),
            KeyItem.Button("&", "&", contentDescription = "Ampersand"),
            KeyItem.Button("_", "_", contentDescription = "Underscore"),
        )),
        // Page 3: Brackets & operators
        KeyPage(listOf(
            KeyItem.Button("[", "[", contentDescription = "Left bracket"),
            KeyItem.Button("]", "]", contentDescription = "Right bracket"),
            KeyItem.Button("<", "<", contentDescription = "Less than"),
            KeyItem.Button(">", ">", contentDescription = "Greater than"),
            KeyItem.Button("=", "=", contentDescription = "Equals"),
            KeyItem.Button("!", "!", contentDescription = "Exclamation"),
            KeyItem.Button("#", "#", contentDescription = "Hash"),
            KeyItem.Button("@", "@", contentDescription = "At sign"),
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
            KeyItem.Button("F1", "F1", contentDescription = "Function key F1"),
            KeyItem.Button("F2", "F2", contentDescription = "Function key F2"),
            KeyItem.Button("F3", "F3", contentDescription = "Function key F3"),
            KeyItem.Button("F4", "F4", contentDescription = "Function key F4"),
            KeyItem.Button("F5", "F5", contentDescription = "Function key F5"),
            KeyItem.Button("F6", "F6", contentDescription = "Function key F6"),
            KeyItem.Button("F7", "F7", contentDescription = "Function key F7"),
            KeyItem.Button("F8", "F8", contentDescription = "Function key F8"),
        )),
        // Page 5: F9 to F12 and the four navigation keys
        KeyPage(listOf(
            KeyItem.Button("F9", "F9", contentDescription = "Function key F9"),
            KeyItem.Button("F10", "F10", contentDescription = "Function key F10"),
            KeyItem.Button("F11", "F11", contentDescription = "Function key F11"),
            KeyItem.Button("F12", "F12", contentDescription = "Function key F12"),
            KeyItem.Button("Home", "Home", contentDescription = "Home key"),
            KeyItem.Button("End", "End", contentDescription = "End key"),
            KeyItem.Button("PgUp", "PageUp", contentDescription = "Page up"),
            KeyItem.Button("PgDn", "PageDown", contentDescription = "Page down"),
        )),
    )
}
