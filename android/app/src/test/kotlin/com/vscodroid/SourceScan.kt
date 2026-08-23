package com.vscodroid

import java.io.File

/**
 * Reading a Kotlin source file the way the source-scanning cases in this suite do.
 *
 * Those cases exist because the property they check is a call being in the right
 * place inside an Activity, and no plain JVM test can build one. Every file that
 * needs that had grown its own copy of the same twenty lines, which is how the
 * next one acquires the same ceiling without being told about it. This is the one
 * place to fix it, and the one place the ceiling is written down.
 *
 * ⚠️ **The brace match is string- and comment-unaware.** It counts every `{` and
 * `}` it passes, including ones inside a string literal, so a body holding
 * unbalanced braces in text truncates or overruns. That is survivable and is
 * measured rather than assumed: [body] is used here on `injectBridgeRelay`, whose
 * body is a page of JavaScript in a raw string, and it extracts correctly because
 * that JavaScript's own braces balance. A body that stopped balancing would show
 * up as a case failing to find something it names, not as a silent pass, because
 * every caller asserts on what it found.
 *
 * Only the nearest declaration is found: [body] takes the exact text a
 * declaration starts with, not a name, so the caller decides how specific to be
 * (`private fun x(` rather than `x(`).
 */
internal object SourceScan {

    /** The file under `android/app`, as the test working directory sees it. */
    fun read(path: String): String {
        val file = File(path)
        check(file.isFile) {
            "$path not found at ${file.absolutePath}; a case reading it would otherwise " +
                "pass by looking at nothing"
        }
        return file.readText()
    }

    /**
     * The body of [declaration], from its opening brace to the matching close.
     *
     * Throws rather than answering empty when the declaration is gone, because
     * "found nothing" is what a renamed method and a satisfied assertion look
     * like from the same distance.
     */
    fun body(source: String, declaration: String): String {
        val start = source.indexOf(declaration)
        check(start >= 0) {
            "`$declaration` is not in this source, so the case reading it is measuring " +
                "nothing. If it moved or was renamed, point the case at the new site " +
                "rather than deleting it."
        }
        val open = source.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < source.length) {
            if (source[i] == '{') depth += 1
            if (source[i] == '}') {
                depth -= 1
                if (depth == 0) return source.substring(open, i + 1)
            }
            i += 1
        }
        throw AssertionError("Could not find the end of `$declaration`")
    }

    /**
     * [text] with its comments blanked rather than deleted.
     *
     * Blanked so that relative ORDER survives: several cases compare where one
     * call sits against another.
     *
     * A block counts as a comment only where it OPENS a line, which is how one is
     * written around code here and how every doc comment in these files begins.
     * The rule is not fussiness, it is the whole reason this is hand-written
     * rather than a lazy regex over the text. A wildcard mime type carries a
     * block opener inside a string literal, so the regex form treats `arrayOf`
     * with one in it as the start of a comment and blanks everything up to the
     * next real close. Measured over `MainActivity.kt`, which holds two of them:
     * about fifty lines of live code gone, `multiFileChooserLauncher.launch`
     * among the calls in the hole. A case searching for that then finds nothing,
     * which passes an assertion of absence and fails one of presence while
     * naming the wrong cause.
     *
     * The line form has no such anchor and cannot get one: a line comment opens
     * anywhere. So a `url` literal is still cut at its scheme separator, and a
     * case searching for one has to look at the raw text instead.
     *
     * Necessary rather than tidy, for two reasons that are the same reason twice.
     * Every rule in this repository is argued in prose beside the line it
     * governs, so a raw search finds the name in the explanation as readily as in
     * the code; and commenting a line out is how a call gets disabled while
     * something is being debugged, which leaves every character of it in the
     * file.
     */
    fun withoutComments(text: String): String {
        var inBlock = false
        return text.lines().joinToString("\n") { raw ->
            var line = raw
            if (inBlock) {
                val close = line.indexOf("*/")
                if (close < 0) return@joinToString ""
                inBlock = false
                line = line.substring(close + 2)
            }
            while (line.trimStart().startsWith("/*")) {
                val open = line.indexOf("/*")
                val close = line.indexOf("*/", open + 2)
                if (close < 0) {
                    inBlock = true
                    return@joinToString line.substring(0, open)
                }
                line = line.substring(0, open) + line.substring(close + 2)
            }
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }
}
