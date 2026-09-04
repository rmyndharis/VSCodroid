package com.vscodroid

/**
 * Which `Logger` statements in a Kotlin source hand on a value the user would not
 * want in logcat: the URL carrying the connection token, or a device folder's
 * tree URI.
 *
 * Kept apart from the tests that use it because it is the part with behaviour of
 * its own: the cases in [NavigationTokenLoggingTest] and
 * `SafFolderLogCallSiteTest` drive it against sources whose answer is known,
 * which is only possible while it takes lines rather than a filename.
 *
 * Three passes over the text, none of them a Kotlin parser and none pretending to
 * be. [codeView] drops string prose while keeping what a `$` interpolation names,
 * so a message can talk about a token without being one. [statements] gathers a
 * `Logger` call across the lines it was wrapped onto, up to
 * [MAX_STATEMENT_LINES], by counting parentheses on that same prose-free view,
 * which is what keeps a `" ("` in a message from unbalancing the count.
 * [taintedNames] walks declarations to a fixpoint from the places a sensitive
 * value enters: a [SOURCES] call, and any name a signature or a property declares
 * as a [URI_NAME].
 *
 * Both seeds are here rather than one per guard on purpose. A guard written from
 * the one spelling that had just been fixed catches that spelling and nothing
 * else; the point of a reader is that interpolation, concatenation, an
 * intermediate local and a wrapped call all read the same to it, and that adding
 * a source closes every method in the file at once rather than one at a time.
 *
 * Its blind spots are the docstring on [NavigationTokenLoggingTest], the
 * one-line-at-a-time declaration read argued for on [taintedNames], and one more
 * that belongs to the text scanning rather than to the design: a double quote
 * written inside an interpolation inside a string, `trim('"')` is one, and
 * MainActivity has it, ends the literal early, because handling it properly
 * means recursing into interpolations. The damage is bounded to that one
 * statement, and it cannot hide an interpolated name: [leaks] asks twice, once of
 * the prose-free view and once of the raw text, and only the first is affected.
 */
internal object LogTaint {

    /** Where a value carrying the connection token enters a file. */
    private val SOURCES = listOf(
        Regex("""\bworkbenchUrl\("""),
        Regex("""\bgetConnectionToken\("""),
        Regex("""\b(?:wv|webView)\??\.url\b"""),
    )

    /**
     * A name a declaration gives the type `Uri`: a parameter, a property, a local.
     *
     * A SAF tree URI is the user's own directory spelled out
     * (`.../tree/primary%3ADocuments%2F<folder>`), so it is a source in its own
     * right and needs no call to arrive: `openSafFolder(uri: Uri, …)` is handed
     * one. A declaration is the only place a type is written down, which is why
     * this is a seed rather than something [SOURCES] could express.
     *
     * Missed by it: a `Uri` reaching a name through a type argument, as
     * `previous: Pair<File, Uri>?` does, and one arriving with no type written at
     * all. Both are the [taintedNames] ceiling, not a new one.
     */
    private val URI_NAME = Regex("""\b([A-Za-z_]\w*)\s*:\s*Uri\b""")

    /**
     * Calls that take the secret out of a value and leave the rest printable.
     *
     * `redactToken` replaces the `tkn=` parameter and nothing else, which is the
     * whole treatment a tokened URL needs.
     */
    private val REDACTION = listOf("redactToken")

    /**
     * Calls that print no part of the value at all, only a name for the thing it
     * points at.
     *
     * `getMirrorDir(uri).name` is the six-byte digest a device folder's mirror is
     * called after; `urlLogLabel` is an address cut down to a scheme and a host.
     * `syncToLocal` is that same digest arriving by the other route: it is
     * declared `syncToLocal(safUri: Uri, onProgress: (Int, Int) -> Unit): File`
     * (SafStorageManager.kt), takes the tree URI and answers the mirror directory
     * it copied into, whose name is the digest `getMirrorDir` answers for the same
     * URI. So a local declared from the call holds no part of the folder's path,
     * and printing its `name` is the redaction rather than a hole in it. Written
     * against that one signature: an overload taking something other than a tree
     * URI would have to be argued about here before it is added.
     *
     * The distinction from [REDACTION] is load-bearing and `PageSuppliedLogging`
     * states it too: a tree URI carries no `tkn=`, so wrapping one in
     * `redactToken` satisfies a search for the call and changes nothing about
     * what ships. Only [leaks] and [reducedLogs] accept these. [redactedLogs]
     * must not, or the control it answers stops distinguishing a redacted log
     * statement from a folder named by its digest.
     */
    private val REDUCTION = listOf("urlLogLabel", "getMirrorDir", "syncToLocal")

    /**
     * How many lines one `Logger` call may be gathered across.
     *
     * A cap rather than a walk to the closing parenthesis, because the count runs
     * on the prose-free view and the last blind spot in this object's docstring, a
     * double quote written inside an interpolation, ends a literal early and can
     * leave that count unbalanced. Uncapped, one such statement swallows the rest
     * of the file and every name in it is reported as leaked from a single line.
     * A bounded truncation is the cheaper of the two failures, and what it costs
     * where it happens is the tail of a message rather than a name: measured
     * today, 0 of 48 statements in MainActivity.kt reach it, 0 of 17 in
     * SafStorageManager.kt, and 1 of 69 in SafSyncEngine.kt, a `Logger.i` whose
     * message is a 16-line `if` with a paragraph of comment inside it. Reading
     * stops at the twelfth of those lines, so the `else` branch of that message
     * is not seen. The only name it interpolates is the `doc.relativePath` the
     * `if` branch prints, which is read, so the truncation costs nothing there.
     */
    private const val MAX_STATEMENT_LINES = 12

    private val DECLARATION = Regex("""\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=]*?)?=(.*)""")
    private val INTERPOLATION = Regex("""\$\{[^}]*}|\$[A-Za-z_]\w*""")
    private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

    /** Statements printing a tainted value with nothing hiding it. */
    fun leaks(source: List<String>): List<String> {
        val tainted = taintedNames(source)
        return statements(source).filter { st ->
            val code = stripTreated(st.code)
            val raw = stripTreated(st.raw)
            tainted.any { mentions(code, it) } ||
                INTERPOLATION.findAll(raw).any { hole ->
                    IDENTIFIER.findAll(hole.value).any { it.value in tainted }
                }
        }.map { "${it.line}: ${it.raw}" }
    }

    /** Statements printing a tainted value through the redactor. */
    fun redactedLogs(source: List<String>): List<String> = logsTreatedBy(source, REDACTION)

    /**
     * Statements naming a tainted value by something that is not the value.
     *
     * The control the device-folder side needs. Asking whether some line of a body
     * holds both `Logger.` and `getMirrorDir(` answers a different question in two
     * ways that both matter: a formatter wrapping the statement onto two lines
     * fails it while nothing about the log has changed, and a reader that has
     * stopped recognising where a tree URI enters the file passes it while the
     * guard beside it has gone blind. This asks the reader instead, so the control
     * and the thing it certifies stand or fall together.
     *
     * Separate from [redactedLogs] rather than one call taking a list, because the
     * two lists must stay apart for the reason [REDUCTION] gives.
     */
    fun reducedLogs(source: List<String>): List<String> = logsTreatedBy(source, REDUCTION)

    private fun logsTreatedBy(source: List<String>, calls: List<String>): List<String> {
        val tainted = taintedNames(source)
        return statements(source).filter { st ->
            argumentsOf(st.raw, calls).any { arg -> tainted.any { mentions(arg, it) } }
        }.map { "${it.line}: ${it.raw}" }
    }

    private class Statement(val line: Int, val raw: String, val code: String)

    private fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    private fun mentions(text: String, name: String): Boolean =
        Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text)

    /**
     * The line with its string prose removed and its interpolations kept.
     *
     * An identifier inside a literal can only be referred to through `$`, so what
     * is left after this is every position where a name means a value.
     */
    private fun codeView(line: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("\"\"\"", i) -> {
                    val end = line.indexOf("\"\"\"", i + 3)
                    val inner = if (end < 0) line.substring(i + 3) else line.substring(i + 3, end)
                    out.append(' ').append(interpolations(inner)).append(' ')
                    i = if (end < 0) line.length else end + 3
                }
                line[i] == '"' -> {
                    var j = i + 1
                    while (j < line.length && !(line[j] == '"' && line[j - 1] != '\\')) j++
                    val inner = line.substring(i + 1, j.coerceAtMost(line.length))
                    out.append(' ').append(interpolations(inner)).append(' ')
                    i = j + 1
                }
                else -> out.append(line[i++])
            }
        }
        return out.toString()
    }

    private fun interpolations(inner: String): String =
        INTERPOLATION.findAll(inner).joinToString(" ") { it.value }

    /** Every `Logger` call, gathered across the lines it was wrapped onto. */
    private fun statements(source: List<String>): List<Statement> {
        val code = source.map { if (isComment(it)) "" else codeView(it) }
        val out = mutableListOf<Statement>()
        var i = 0
        while (i < source.size) {
            if (!code[i].contains("Logger.")) {
                i++
                continue
            }
            val raw = StringBuilder()
            val whole = StringBuilder()
            var depth = 0
            var j = i
            while (j < source.size && j - i < MAX_STATEMENT_LINES) {
                raw.append(if (isComment(source[j])) "" else source[j].trim()).append(' ')
                whole.append(code[j]).append(' ')
                depth += code[j].count { it == '(' } - code[j].count { it == ')' }
                if (depth <= 0) break
                j++
            }
            out += Statement(i + 1, raw.toString().trim(), whole.toString())
            i = j + 1
        }
        return out
    }

    /**
     * Names holding a value that came, however indirectly, from a [SOURCES] hit or
     * a [URI_NAME] declaration.
     *
     * A declaration is read one line at a time: `val x =` with its value on the
     * next line is not followed, and neither is the rest of any initialiser a
     * formatter wrapped. Deliberate, and the most consequential limit here, so it
     * is written down rather than waiting to be found and "fixed".
     *
     * Measured, on the file this reader is pointed at. Reading each right-hand
     * side to the end of its statement instead pulls in at least a dozen further
     * names, and one of them is `message`. That one name turns eight correct
     * statements into reported leaks: every `Logger` line in `MainActivity` that
     * prints a caught exception's message. The names here are file-scoped, and
     * that is what makes a word that common unaffordable to follow;
     * `NavigationTokenLoggingTest` argues the scope and pins this limit as a
     * case, so the trade stays measured rather than asserted.
     */
    private fun taintedNames(source: List<String>): Set<String> {
        val code = source.filterNot(::isComment).map(::codeView)
        val names = linkedSetOf<String>()
        for (line in code) {
            for (m in URI_NAME.findAll(line)) names += m.groupValues[1]
        }
        // Declarations are walked to a fixpoint rather than a fixed number of
        // passes, so that a chain assigned in the other order is still followed
        // however many hops long it is. It terminates because `names` only grows
        // and nothing but an identifier written in this file can enter it.
        var grew = true
        while (grew) {
            val before = names.size
            for (line in code) {
                for (m in DECLARATION.findAll(line)) {
                    val name = m.groupValues[1]
                    val initialiser = m.groupValues[2]
                    val seeded = SOURCES.any { it.containsMatchIn(initialiser) }
                    val derived = names.toList()
                        .any { mentions(stripTreated(initialiser), it) }
                    if (seeded || derived) names += name
                }
            }
            grew = names.size > before
        }
        return names
    }

    /** The spans of every call to one of [calls], argument list included. */
    private fun spansOf(text: String, calls: List<String>): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val call = calls
                .map { it to text.indexOf("$it(", from) }
                .filter { it.second >= 0 }
                .minByOrNull { it.second } ?: return spans
            val at = call.second
            var depth = 0
            var i = at + call.first.length
            var end = -1
            while (i < text.length) {
                if (text[i] == '(') depth++
                else if (text[i] == ')' && --depth == 0) {
                    end = i
                    break
                }
                i++
            }
            if (end < 0) {
                spans += at..text.lastIndex
                return spans
            }
            spans += at..end
            from = end + 1
        }
    }

    /** [text] with everything a reader may treat as handled taken out of it. */
    private fun stripTreated(text: String): String {
        var out = text
        for (span in spansOf(text, REDACTION + REDUCTION).reversed()) out = out.removeRange(span)
        return out
    }

    /** What each call to one of [calls] in [text] was handed. */
    private fun argumentsOf(text: String, calls: List<String>): List<String> =
        spansOf(text, calls).map { text.substring(it).substringAfter('(').removeSuffix(")") }
}
