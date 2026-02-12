package com.vscodroid.keyboard

data class KeyDef(
    val key: String,
    val code: String,
    val keyCode: Int,
    /** True if this character requires Shift on a physical US keyboard layout. */
    val requiresShift: Boolean = false
)

object KeyMapping {
    private val mappings = mapOf(
        "Tab" to KeyDef("Tab", "Tab", 9),
        "Escape" to KeyDef("Escape", "Escape", 27),
        "ArrowLeft" to KeyDef("ArrowLeft", "ArrowLeft", 37),
        "ArrowUp" to KeyDef("ArrowUp", "ArrowUp", 38),
        "ArrowRight" to KeyDef("ArrowRight", "ArrowRight", 39),
        "ArrowDown" to KeyDef("ArrowDown", "ArrowDown", 40),
        "{" to KeyDef("{", "BracketLeft", 219, requiresShift = true),
        "}" to KeyDef("}", "BracketRight", 221, requiresShift = true),
        "(" to KeyDef("(", "Digit9", 57, requiresShift = true),
        ")" to KeyDef(")", "Digit0", 48, requiresShift = true),
        ";" to KeyDef(";", "Semicolon", 186),
        ":" to KeyDef(":", "Semicolon", 186, requiresShift = true),
        "\"" to KeyDef("\"", "Quote", 222, requiresShift = true),
        "/" to KeyDef("/", "Slash", 191),
        "[" to KeyDef("[", "BracketLeft", 219),
        "]" to KeyDef("]", "BracketRight", 221),
        "|" to KeyDef("|", "Backslash", 220, requiresShift = true),
        "\\" to KeyDef("\\", "Backslash", 220),
        "~" to KeyDef("~", "Backquote", 192, requiresShift = true),
        "`" to KeyDef("`", "Backquote", 192),
        "'" to KeyDef("'", "Quote", 222),
        "=" to KeyDef("=", "Equal", 187),
        "!" to KeyDef("!", "Digit1", 49, requiresShift = true),
        "#" to KeyDef("#", "Digit3", 51, requiresShift = true),
        "@" to KeyDef("@", "Digit2", 50, requiresShift = true),
        "&" to KeyDef("&", "Digit7", 55, requiresShift = true),
        "_" to KeyDef("_", "Minus", 189, requiresShift = true),
        "<" to KeyDef("<", "Comma", 188, requiresShift = true),
        ">" to KeyDef(">", "Period", 190, requiresShift = true),
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
