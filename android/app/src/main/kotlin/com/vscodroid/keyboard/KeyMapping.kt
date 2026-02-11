package com.vscodroid.keyboard

data class KeyDef(
    val key: String,
    val code: String,
    val keyCode: Int
)

object KeyMapping {
    private val mappings = mapOf(
        "Tab" to KeyDef("Tab", "Tab", 9),
        "Escape" to KeyDef("Escape", "Escape", 27),
        "ArrowLeft" to KeyDef("ArrowLeft", "ArrowLeft", 37),
        "ArrowUp" to KeyDef("ArrowUp", "ArrowUp", 38),
        "ArrowRight" to KeyDef("ArrowRight", "ArrowRight", 39),
        "ArrowDown" to KeyDef("ArrowDown", "ArrowDown", 40),
        "{" to KeyDef("{", "BracketLeft", 219),
        "}" to KeyDef("}", "BracketRight", 221),
        "(" to KeyDef("(", "Digit9", 57),
        ")" to KeyDef(")", "Digit0", 48),
        ";" to KeyDef(";", "Semicolon", 186),
        ":" to KeyDef(":", "Semicolon", 186),
        "\"" to KeyDef("\"", "Quote", 222),
        "/" to KeyDef("/", "Slash", 191),
        "[" to KeyDef("[", "BracketLeft", 219),
        "]" to KeyDef("]", "BracketRight", 221),
        "|" to KeyDef("|", "Backslash", 220),
        "\\" to KeyDef("\\", "Backslash", 220),
        "~" to KeyDef("~", "Backquote", 192),
        "`" to KeyDef("`", "Backquote", 192),
        "'" to KeyDef("'", "Quote", 222),
        "=" to KeyDef("=", "Equal", 187),
        "!" to KeyDef("!", "Digit1", 49),
        "#" to KeyDef("#", "Digit3", 51),
        "@" to KeyDef("@", "Digit2", 50),
        "&" to KeyDef("&", "Digit7", 55),
        "_" to KeyDef("_", "Minus", 189),
        "<" to KeyDef("<", "Comma", 188),
        ">" to KeyDef(">", "Period", 190),
        "Enter" to KeyDef("Enter", "Enter", 13),
        "Backspace" to KeyDef("Backspace", "Backspace", 8),
        " " to KeyDef(" ", "Space", 32),
    )

    fun getKeyDef(key: String): KeyDef? = mappings[key]

    fun getKeyDefOrLetter(key: String): KeyDef {
        return mappings[key] ?: run {
            val char = key.firstOrNull() ?: ' '
            val upper = char.uppercaseChar()
            KeyDef(key, "Key$upper", upper.code)
        }
    }
}
