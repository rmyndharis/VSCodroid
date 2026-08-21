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
        // Navigation and function keys. Each needs a row of its own here rather
        // than the [getKeyDefOrLetter] fallback, which derives the fields from
        // the first character: "F2" would be sent as KeyF with keyCode 70, which
        // VS Code resolves as the letter F, and "Home" as KeyH.
        "Home" to KeyDef("Home", "Home", 36),
        "End" to KeyDef("End", "End", 35),
        "PageUp" to KeyDef("PageUp", "PageUp", 33),
        "PageDown" to KeyDef("PageDown", "PageDown", 34),
        "F1" to KeyDef("F1", "F1", 112),
        "F2" to KeyDef("F2", "F2", 113),
        "F3" to KeyDef("F3", "F3", 114),
        "F4" to KeyDef("F4", "F4", 115),
        "F5" to KeyDef("F5", "F5", 116),
        "F6" to KeyDef("F6", "F6", 117),
        "F7" to KeyDef("F7", "F7", 118),
        "F8" to KeyDef("F8", "F8", 119),
        "F9" to KeyDef("F9", "F9", 120),
        "F10" to KeyDef("F10", "F10", 121),
        "F11" to KeyDef("F11", "F11", 122),
        "F12" to KeyDef("F12", "F12", 123),
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

    /**
     * The mapping for [key], or null when there is none.
     *
     * ⚠️ No production caller, and that is by design rather than neglect, so a sweep
     * for unused code should leave it. Production goes through [getKeyDefOrLetter],
     * which never returns null because the key row must produce something for any
     * character. This is the nullable half the tests use to assert what IS and is NOT
     * in the table, which is the only way to state that a key is absent.
     */
    fun getKeyDef(key: String): KeyDef? = mappings[key]

    /**
     * The character a latched Shift produces for [key] on the US layout this
     * table describes, or null when Shift produces nothing different.
     *
     * The table already carries both halves of every pair: `/` and `?` are both
     * Slash with keyCode 191 and differ only in [KeyDef.requiresShift], as are
     * `;` and `:`, `=` and `+`, `[` and `{`. Reading the pair back out of it
     * keeps one description of the layout instead of a second opinion, which is
     * the same reason [virtualKeyboardEvents] asks the layout rather than
     * carrying a table of its own.
     *
     * This matters for exactly three characters: `?`, `+` and `}`. None of them
     * is on any page of the row, and each has an unshifted mate that is, so a
     * latched Shift over `/`, `=` and `]` is the only route the row offers to
     * them. Dropping it types the base character, which is worse than typing
     * nothing: it is confidently wrong output.
     *
     * Shift is NOT a route to `*`, `%`, `^`, `$` or `)`. Those five sit on
     * Digit8, Digit5, Digit6, Digit4 and Digit0, and the table carries no
     * unshifted digit row at all, so the search below has nothing to match and
     * returns null; the row carries no digits either, so there is no key to hold
     * Shift over. Reaching one of them means putting the character itself on a
     * page. Every other shifted character in the table is already on a page, so
     * nothing beyond those three depends on this.
     */
    fun shiftedForm(key: String): String? {
        if (key.length == 1 && key[0].isLetter()) return key.uppercase()
        val base = mappings[key] ?: return null
        if (base.requiresShift) return key
        return mappings.values.firstOrNull { it.keyCode == base.keyCode && it.requiresShift }?.key
    }

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
