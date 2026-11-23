package com.vscodroid

import java.io.File

/**
 * Reading a Kotlin source file (and any embedded JavaScript raw strings) the way
 * the source-scanning cases in this suite do.
 *
 * Those cases exist because the property they check is a call being in the right
 * place inside an Activity or injected page script, and no plain JVM test can
 * build one. Every file that needs that had grown its own copy of the same twenty
 * lines, which is how the next one acquires the same ceiling without being told
 * about it. This is the one place to fix it.
 *
 * [withoutComments] and [body] run on a single-pass Kotlin lexer that tracks line
 * comments, nested block comments, double-quoted strings with escapes and
 * `${...}` template expressions, triple-quoted raw strings (including runs of 4+
 * quotes and `${...}` templates), character literals, and backtick identifiers.
 * Braces inside literals, comments, or default-parameter lambdas do not truncate
 * or overrun [body], and URL schemes inside strings survive [withoutComments]
 * intact.
 *
 * [withoutJsComments], [scriptBody], and [jsBody] provide the matching JavaScript
 * lexer for injected scripts carried inside Kotlin triple-quoted raw strings,
 * tracking JS line and block comments, single- and double-quoted strings,
 * template literals with `${...}` expressions, and regex literals (including
 * character classes such as `/[/]/`).
 *
 * Only the nearest declaration is found: [body] and [jsBody] take the exact text
 * a declaration starts with, not a name, so the caller decides how specific to be
 * (`private fun x(` rather than `x(`).
 */
internal object SourceScan {

    private enum class LexMode { CODE, STRING, RAW_STRING }

    private class TemplateFrame(val outerMode: LexMode, var braceDepth: Int)

    private enum class JsMode { CODE, TEMPLATE }

    private class JsTemplateFrame(var braceDepth: Int)

    private val JS_REGEX_PREFIX_KEYWORDS = setOf(
        "return", "case", "throw", "in", "instanceof", "typeof",
        "void", "delete", "new", "else", "do", "of", "await", "yield",
    )

    /** The source files that make up `MainActivity` and its extracted modules. */
    val MAIN_ACTIVITY_FILES = listOf(
        "src/main/kotlin/com/vscodroid/MainActivity.kt",
        "src/main/kotlin/com/vscodroid/MainActivityHelpers.kt",
        "src/main/kotlin/com/vscodroid/MainActivityPageScripts.kt",
        "src/main/kotlin/com/vscodroid/MainActivityDialogs.kt",
        "src/main/kotlin/com/vscodroid/MainActivitySafCoordinator.kt",
    )

    /** The file under `android/app`, as the test working directory sees it. */
    fun read(path: String): String {
        val file = File(path)
        check(file.isFile) {
            "$path not found at ${file.absolutePath}; a case reading it would otherwise " +
                "pass by looking at nothing"
        }
        return file.readText()
    }

    /** Concatenates every file in [paths], failing loudly if any is missing. */
    fun readAll(paths: List<String>): String = paths.joinToString("\n") { read(it) }

    /**
     * The body of Kotlin [declaration], from its opening brace to the matching close.
     *
     * Matches only live Kotlin code (never comments or string literals) and waits
     * until balanced parentheses reach depth zero before latching the opening `{`,
     * so default-parameter lambdas inside a parameter list never truncate the body.
     *
     * Throws rather than answering empty when the declaration is gone, because
     * "found nothing" is what a renamed method and a satisfied assertion look
     * like from the same distance.
     */
    fun body(source: String, declaration: String): String {
        val firstChar = declaration.firstOrNull()
        var start = -1
        if (firstChar != null) {
            scanKotlin(
                text = source,
                onCode = { index, ch, templateDepth ->
                    if (templateDepth == 0 && ch == firstChar && source.startsWith(declaration, index)) {
                        start = index
                        false
                    } else {
                        true
                    }
                },
                onLiteral = { _, _ -> true },
            )
        }
        check(start >= 0) {
            "`$declaration` is not in this source, so the case reading it is measuring " +
                "nothing. If it moved or was renamed, point the case at the new site " +
                "rather than deleting it."
        }

        var open = -1
        var close = -1
        var depth = 0
        var parenDepth = 0
        scanKotlin(
            text = source,
            startIndex = start,
            onCode = { index, ch, templateDepth ->
                if (templateDepth == 0) {
                    if (open < 0) {
                        when (ch) {
                            '(' -> parenDepth += 1
                            ')' -> if (parenDepth > 0) parenDepth -= 1
                            '{' -> if (parenDepth == 0) {
                                open = index
                                depth = 1
                            }
                        }
                    } else {
                        when (ch) {
                            '{' -> depth += 1
                            '}' -> {
                                depth -= 1
                                if (depth == 0) {
                                    close = index
                                    return@scanKotlin false
                                }
                            }
                        }
                    }
                }
                true
            },
            onLiteral = { _, _ -> true },
        )
        if (open >= 0 && close >= open) {
            return source.substring(open, close + 1)
        }
        throw AssertionError("Could not find the end of `$declaration`")
    }

    /**
     * [text] with its Kotlin comments stripped while preserving line structure.
     *
     * Block comments collapse to a single space plus any newlines they spanned so
     * adjacent tokens stay separated and line numbers remain unchanged. Line
     * comments stop before the line break. String literals, raw strings, character
     * literals, and backtick identifiers pass through untouched, so URL schemes
     * or wildcard MIME types inside literals are never mistaken for comment markers.
     */
    fun withoutComments(text: String): String {
        val out = StringBuilder(text.length)
        scanKotlin(
            text = text,
            onCode = { _, ch, _ ->
                out.append(ch)
                true
            },
            onLiteral = { _, ch ->
                out.append(ch)
                true
            },
            onCommentNewline = { ch -> out.append(ch) },
            onBlockCommentEnd = { out.append(' ') },
        )
        return out.toString()
    }

    /**
     * [js] with JavaScript line (`//`) and block (`/* ... */`) comments stripped
     * while preserving line structure, single- and double-quoted strings, template
     * literals (including `${...}` expressions), and regex literals.
     */
    fun withoutJsComments(js: String): String {
        val out = StringBuilder(js.length)
        scanJs(
            text = js,
            onCode = { _, ch, _ ->
                out.append(ch)
                true
            },
            onLiteral = { _, ch ->
                out.append(ch)
                true
            },
            onCommentNewline = { ch -> out.append(ch) },
            onBlockCommentEnd = { out.append(' ') },
        )
        return out.toString()
    }

    /**
     * The JavaScript payload inside Kotlin [declaration]'s triple-quoted raw
     * string literal(s), with JavaScript comments stripped via [withoutJsComments].
     *
     * Throws when [declaration] is missing or carries no triple-quoted string, so
     * tests cannot pass on an empty string.
     */
    fun scriptBody(source: String, declaration: String): String {
        val fnBody = body(source, declaration)
        val payloads = extractRawStrings(fnBody)
        check(payloads.isNotEmpty()) {
            "`$declaration` has no triple-quoted script literal, so the case reading it " +
                "is measuring nothing."
        }
        return payloads.joinToString("\n") { withoutJsComments(it) }
    }

    /**
     * The body of JavaScript [declaration] inside [js], from its opening brace to
     * the matching close.
     *
     * Matches only live JS code (never comments, strings, templates, or regexes)
     * and waits until balanced parentheses reach depth zero before latching `{`.
     */
    fun jsBody(js: String, declaration: String): String {
        val firstChar = declaration.firstOrNull()
        var start = -1
        if (firstChar != null) {
            scanJs(
                text = js,
                onCode = { index, ch, templateDepth ->
                    if (templateDepth == 0 && ch == firstChar && js.startsWith(declaration, index)) {
                        start = index
                        false
                    } else {
                        true
                    }
                },
                onLiteral = { _, _ -> true },
            )
        }
        check(start >= 0) {
            "`$declaration` is not in this script, so the case reading it is measuring " +
                "nothing. If it moved or was renamed, point the case at the new site " +
                "rather than deleting it."
        }

        var open = -1
        var close = -1
        var depth = 0
        var parenDepth = 0
        scanJs(
            text = js,
            startIndex = start,
            onCode = { index, ch, templateDepth ->
                if (templateDepth == 0) {
                    if (open < 0) {
                        when (ch) {
                            '(' -> parenDepth += 1
                            ')' -> if (parenDepth > 0) parenDepth -= 1
                            '{' -> if (parenDepth == 0) {
                                open = index
                                depth = 1
                            }
                        }
                    } else {
                        when (ch) {
                            '{' -> depth += 1
                            '}' -> {
                                depth -= 1
                                if (depth == 0) {
                                    close = index
                                    return@scanJs false
                                }
                            }
                        }
                    }
                }
                true
            },
            onLiteral = { _, _ -> true },
        )
        if (open >= 0 && close >= open) {
            return js.substring(open, close + 1)
        }
        throw AssertionError("Could not find the end of `$declaration`")
    }

    private fun extractRawStrings(kotlinText: String): List<String> {
        val results = mutableListOf<String>()
        var currentStart = -1
        scanKotlin(
            text = kotlinText,
            onCode = { _, _, _ -> true },
            onLiteral = { _, _ -> true },
            onRawStringStart = { contentStart, templateDepth ->
                if (templateDepth == 0) currentStart = contentStart
            },
            onRawStringEnd = { contentEnd, templateDepth ->
                if (templateDepth == 0 && currentStart >= 0 && contentEnd >= currentStart) {
                    results += kotlinText.substring(currentStart, contentEnd)
                    currentStart = -1
                }
            },
        )
        return results
    }

    private inline fun scanKotlin(
        text: String,
        startIndex: Int = 0,
        onCode: (index: Int, ch: Char, templateDepth: Int) -> Boolean,
        onLiteral: (index: Int, ch: Char) -> Boolean,
        onCommentNewline: (ch: Char) -> Unit = {},
        onBlockCommentEnd: () -> Unit = {},
        onRawStringStart: (contentStart: Int, templateDepth: Int) -> Unit = { _, _ -> },
        onRawStringEnd: (contentEnd: Int, templateDepth: Int) -> Unit = { _, _ -> },
    ) {
        val len = text.length
        var i = startIndex
        var mode = LexMode.CODE
        val templates = ArrayDeque<TemplateFrame>()

        while (i < len) {
            when (mode) {
                LexMode.CODE -> {
                    val c = text[i]
                    when {
                        c == '/' && i + 1 < len && text[i + 1] == '/' -> {
                            i += 2
                            while (i < len && text[i] != '\n' && text[i] != '\r') {
                                i += 1
                            }
                        }

                        c == '/' && i + 1 < len && text[i + 1] == '*' -> {
                            var commentDepth = 1
                            i += 2
                            while (i < len && commentDepth > 0) {
                                when {
                                    text[i] == '/' && i + 1 < len && text[i + 1] == '*' -> {
                                        commentDepth += 1
                                        i += 2
                                    }

                                    text[i] == '*' && i + 1 < len && text[i + 1] == '/' -> {
                                        commentDepth -= 1
                                        i += 2
                                    }

                                    else -> {
                                        if (text[i] == '\n' || text[i] == '\r') {
                                            onCommentNewline(text[i])
                                        }
                                        i += 1
                                    }
                                }
                            }
                            onBlockCommentEnd()
                        }

                        c == '"' && i + 2 < len && text[i + 1] == '"' && text[i + 2] == '"' -> {
                            for (k in 0 until 3) {
                                if (!onLiteral(i + k, '"')) return
                            }
                            i += 3
                            onRawStringStart(i, templates.size)
                            mode = LexMode.RAW_STRING
                        }

                        c == '"' -> {
                            if (!onLiteral(i, '"')) return
                            i += 1
                            mode = LexMode.STRING
                        }

                        c == '\'' -> {
                            if (!onLiteral(i, '\'')) return
                            i += 1
                            while (i < len) {
                                val ch = text[i]
                                if (!onLiteral(i, ch)) return
                                i += 1
                                if (ch == '\\' && i < len) {
                                    if (!onLiteral(i, text[i])) return
                                    i += 1
                                } else if (ch == '\'' || ch == '\n' || ch == '\r') {
                                    break
                                }
                            }
                        }

                        c == '`' -> {
                            // Kotlin backtick identifiers do not support backslash escapes
                            // and cannot span line breaks.
                            if (!onLiteral(i, '`')) return
                            i += 1
                            while (i < len) {
                                val ch = text[i]
                                if (!onLiteral(i, ch)) return
                                i += 1
                                if (ch == '`' || ch == '\n' || ch == '\r') {
                                    break
                                }
                            }
                        }

                        c == '{' -> {
                            if (templates.isNotEmpty()) {
                                templates.last().braceDepth += 1
                            }
                            if (!onCode(i, '{', templates.size)) return
                            i += 1
                        }

                        c == '}' -> {
                            if (templates.isNotEmpty()) {
                                val top = templates.last()
                                top.braceDepth -= 1
                                if (top.braceDepth == 0) {
                                    templates.removeLast()
                                    if (!onLiteral(i, '}')) return
                                    i += 1
                                    mode = top.outerMode
                                    continue
                                }
                            }
                            if (!onCode(i, '}', templates.size)) return
                            i += 1
                        }

                        else -> {
                            if (!onCode(i, c, templates.size)) return
                            i += 1
                        }
                    }
                }

                LexMode.STRING -> {
                    val c = text[i]
                    when {
                        c == '\\' && i + 1 < len -> {
                            if (!onLiteral(i, c)) return
                            if (!onLiteral(i + 1, text[i + 1])) return
                            i += 2
                        }

                        c == '$' && i + 1 < len && text[i + 1] == '{' -> {
                            if (!onLiteral(i, '$')) return
                            if (!onLiteral(i + 1, '{')) return
                            i += 2
                            templates.addLast(TemplateFrame(LexMode.STRING, 1))
                            mode = LexMode.CODE
                        }

                        c == '"' -> {
                            if (!onLiteral(i, '"')) return
                            i += 1
                            mode = LexMode.CODE
                        }

                        else -> {
                            if (!onLiteral(i, c)) return
                            i += 1
                        }
                    }
                }

                LexMode.RAW_STRING -> {
                    val c = text[i]
                    when {
                        c == '"' && i + 2 < len && text[i + 1] == '"' && text[i + 2] == '"' -> {
                            var runEnd = i + 3
                            while (runEnd < len && text[runEnd] == '"') {
                                runEnd += 1
                            }
                            for (k in i until runEnd) {
                                if (!onLiteral(k, '"')) return
                            }
                            onRawStringEnd(runEnd - 3, templates.size)
                            i = runEnd
                            mode = LexMode.CODE
                        }

                        c == '$' && i + 1 < len && text[i + 1] == '{' -> {
                            if (!onLiteral(i, '$')) return
                            if (!onLiteral(i + 1, '{')) return
                            i += 2
                            templates.addLast(TemplateFrame(LexMode.RAW_STRING, 1))
                            mode = LexMode.CODE
                        }

                        else -> {
                            if (!onLiteral(i, c)) return
                            i += 1
                        }
                    }
                }
            }
        }
    }

    private inline fun scanJs(
        text: String,
        startIndex: Int = 0,
        onCode: (index: Int, ch: Char, templateDepth: Int) -> Boolean,
        onLiteral: (index: Int, ch: Char) -> Boolean,
        onCommentNewline: (ch: Char) -> Unit = {},
        onBlockCommentEnd: () -> Unit = {},
    ) {
        val len = text.length
        var i = startIndex
        var mode = JsMode.CODE
        var regexAllowed = true
        val templates = ArrayDeque<JsTemplateFrame>()

        while (i < len) {
            when (mode) {
                JsMode.CODE -> {
                    val c = text[i]
                    when {
                        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C' -> {
                            if (!onCode(i, c, templates.size)) return
                            i += 1
                        }

                        c == '/' && i + 1 < len && text[i + 1] == '/' -> {
                            i += 2
                            while (i < len && text[i] != '\n' && text[i] != '\r') {
                                i += 1
                            }
                        }

                        c == '/' && i + 1 < len && text[i + 1] == '*' -> {
                            i += 2
                            while (i < len) {
                                if (text[i] == '*' && i + 1 < len && text[i + 1] == '/') {
                                    i += 2
                                    break
                                }
                                if (text[i] == '\n' || text[i] == '\r') {
                                    onCommentNewline(text[i])
                                }
                                i += 1
                            }
                            onBlockCommentEnd()
                        }

                        c == '"' || c == '\'' -> {
                            val quote = c
                            if (!onLiteral(i, quote)) return
                            i += 1
                            while (i < len) {
                                val ch = text[i]
                                if (ch == '\\' && i + 1 < len) {
                                    if (!onLiteral(i, ch)) return
                                    if (!onLiteral(i + 1, text[i + 1])) return
                                    i += 2
                                } else {
                                    if (!onLiteral(i, ch)) return
                                    i += 1
                                    if (ch == quote || ch == '\n' || ch == '\r') break
                                }
                            }
                            regexAllowed = false
                        }

                        c == '`' -> {
                            if (!onLiteral(i, '`')) return
                            i += 1
                            mode = JsMode.TEMPLATE
                        }

                        c == '/' -> {
                            val regexEnd = if (regexAllowed) findJsRegexEnd(text, i) else -1
                            if (regexEnd > i) {
                                for (k in i until regexEnd) {
                                    if (!onLiteral(k, text[k])) return
                                }
                                i = regexEnd
                                regexAllowed = false
                            } else {
                                if (!onCode(i, '/', templates.size)) return
                                i += 1
                                regexAllowed = true
                            }
                        }

                        c.isLetter() || c == '_' || c == '$' -> {
                            val wordStart = i
                            while (i < len && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) {
                                if (!onCode(i, text[i], templates.size)) return
                                i += 1
                            }
                            regexAllowed = text.substring(wordStart, i) in JS_REGEX_PREFIX_KEYWORDS
                        }

                        c.isDigit() -> {
                            if (!onCode(i, c, templates.size)) return
                            i += 1
                            regexAllowed = false
                        }

                        c == '{' -> {
                            if (templates.isNotEmpty()) {
                                templates.last().braceDepth += 1
                            }
                            if (!onCode(i, '{', templates.size)) return
                            i += 1
                            regexAllowed = true
                        }

                        c == '}' -> {
                            if (templates.isNotEmpty()) {
                                val top = templates.last()
                                top.braceDepth -= 1
                                if (top.braceDepth == 0) {
                                    templates.removeLast()
                                    if (!onLiteral(i, '}')) return
                                    i += 1
                                    mode = JsMode.TEMPLATE
                                    continue
                                }
                            }
                            if (!onCode(i, '}', templates.size)) return
                            i += 1
                            regexAllowed = true
                        }

                        (c == '+' && i + 1 < len && text[i + 1] == '+') ||
                            (c == '-' && i + 1 < len && text[i + 1] == '-') -> {
                            if (!onCode(i, c, templates.size)) return
                            if (!onCode(i + 1, text[i + 1], templates.size)) return
                            i += 2
                            regexAllowed = false
                        }

                        c == ')' || c == ']' || c == '.' -> {
                            if (!onCode(i, c, templates.size)) return
                            i += 1
                            regexAllowed = false
                        }

                        else -> {
                            if (!onCode(i, c, templates.size)) return
                            i += 1
                            regexAllowed = true
                        }
                    }
                }

                JsMode.TEMPLATE -> {
                    val c = text[i]
                    when {
                        c == '\\' && i + 1 < len -> {
                            if (!onLiteral(i, c)) return
                            if (!onLiteral(i + 1, text[i + 1])) return
                            i += 2
                        }

                        c == '$' && i + 1 < len && text[i + 1] == '{' -> {
                            if (!onLiteral(i, '$')) return
                            if (!onLiteral(i + 1, '{')) return
                            i += 2
                            templates.addLast(JsTemplateFrame(1))
                            mode = JsMode.CODE
                            regexAllowed = true
                        }

                        c == '`' -> {
                            if (!onLiteral(i, '`')) return
                            i += 1
                            mode = JsMode.CODE
                            regexAllowed = false
                        }

                        else -> {
                            if (!onLiteral(i, c)) return
                            i += 1
                        }
                    }
                }
            }
        }
    }

    private fun findJsRegexEnd(text: String, slashIndex: Int): Int {
        val len = text.length
        var j = slashIndex + 1
        var inClass = false
        while (j < len) {
            val ch = text[j]
            if (ch == '\n' || ch == '\r') return -1
            if (ch == '\\') {
                if (j + 1 >= len || text[j + 1] == '\n' || text[j + 1] == '\r') return -1
                j += 2
                continue
            }
            if (ch == '[') {
                inClass = true
                j += 1
                continue
            }
            if (ch == ']') {
                inClass = false
                j += 1
                continue
            }
            if (ch == '/' && !inClass) {
                j += 1
                while (j < len && text[j].isLetter()) {
                    j += 1
                }
                return j
            }
            j += 1
        }
        return -1
    }
}
