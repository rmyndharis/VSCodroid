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
    )
}
