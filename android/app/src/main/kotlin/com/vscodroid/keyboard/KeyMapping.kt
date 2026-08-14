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
        "," to KeyDef(",", "Comma", 188),
        "." to KeyDef(".", "Period", 190),
        "-" to KeyDef("-", "Minus", 189),
        "+" to KeyDef("+", "Equal", 187, requiresShift = true),
        "*" to KeyDef("*", "Digit8", 56, requiresShift = true),
        "%" to KeyDef("%", "Digit5", 53, requiresShift = true),
        "?" to KeyDef("?", "Slash", 191, requiresShift = true),
        "^" to KeyDef("^", "Digit6", 54, requiresShift = true),
        "\$" to KeyDef("\$", "Digit4", 52, requiresShift = true),
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

    /**
     * The same table as a JS object literal, keyed by character, with each value the
     * triple `[code, keyCode, requiresShift]` and the flag as 0 or 1.
     *
     * The soft keyboard reaches VS Code through a `beforeinput` listener rather than
     * through [getKeyDefOrLetter], so that path has no way to read these definitions
     * from Kotlin. It is handed this instead — see
     * [KeyInjector.setupModifierInterceptor]. Both input paths then answer from one
     * table, which is what keeps a symbol typed on the soft keyboard equivalent to the
     * same symbol tapped on the key row.
     */
    fun toJsLookup(): String =
        mappings.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, def) ->
            "${jsQuote(key)}:[${jsQuote(def.code)},${def.keyCode},${if (def.requiresShift) 1 else 0}]"
        }

    /**
     * Quotes a string as a JS double-quoted literal. The table holds both `"` and `\`
     * as keys, so escaping is load-bearing rather than defensive: without it the
     * generated object is a syntax error and the whole interceptor fails to install.
     */
    internal fun jsQuote(s: String): String = buildString {
        append('"')
        for (c in s) {
            if (c == '"' || c == '\\') append('\\')
            append(c)
        }
        append('"')
    }
}
