package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The helper the source-reading cases in this suite are built on.
 *
 * Those cases all have the same shape: extract a body, drop its comments, then
 * assert that a call is present or absent in what is left. The second step is
 * where a silent failure lives. Blanking too little is caught by the case itself
 * going red; blanking too much is not, because a name that has been deleted from
 * the text reads exactly like a call that was never made, and an assertion of
 * absence passes on it.
 *
 * So the blanking is checked here, on strings written for the purpose, rather
 * than inferred from the cases that use it.
 */
class SourceScanTest {

    /**
     * A block opener inside a string literal is not a comment.
     *
     * The reachable spelling is the wildcard mime type this app passes to the
     * file chooser, and it is not a curiosity. With the regex form this replaced,
     * the two of them in `MainActivity.kt` each swallowed everything up to the
     * next real close: about fifty lines of live code between them, with
     * `multiFileChooserLauncher.launch` among the calls in the hole. Anything a
     * case wanted to say about that call was then said about nothing.
     */
    @Test
    fun `a wildcard mime type does not blank the code after it`() {
        // The doc comment below is what gives the regex form its closing pair;
        // in the real file it is whatever doc comment comes next, which is why
        // the hole ran to the end of the following method rather than to the end
        // of the line.
        val source = """
            val types = arrayOf("*/*")
            multiFileChooserLauncher.launch(types)

            /** The next thing in the file. */
            fun other() = Unit
        """.trimIndent()

        val code = SourceScan.withoutComments(source)

        assertTrue(code.contains("multiFileChooserLauncher.launch")) {
            "a string literal was read as a comment opener, so the call after it is gone " +
                "and any case asserting on it is measuring an empty string: $code"
        }
    }

    /**
     * And the blanking still happens, which is the control the case above needs:
     * a helper that returned its input unchanged would satisfy it.
     *
     * Both forms, because either one disables a call. The block is the one every
     * doc comment in these files is written in, and the line comment is how a
     * call gets switched off while something is being debugged.
     */
    @Test
    fun `prose about a call cannot stand in for the call`() {
        val source = """
            /**
             * Calls startForegroundService, which is the point of it.
             */
            fun start() {
                // startForegroundService(intent)
                bindService(intent)
            }
        """.trimIndent()

        val code = SourceScan.withoutComments(source)

        assertTrue(!code.contains("startForegroundService")) {
            "the name survives in prose and in a call somebody commented out, so a search " +
                "for it answers yes for a method that does not make it: $code"
        }
        assertTrue(code.contains("bindService(intent)")) {
            "live code beside a comment was blanked with it: $code"
        }
    }

    /**
     * Lines survive, which is what lets a failure message quote one.
     *
     * The regex form collapsed each block comment to a single space, so a file
     * carrying more comment than code came out with its lines renumbered and its
     * offsets moved. Only the ORDER of two offsets meant anything, and a message
     * that printed a line number named a line in a file nobody has.
     */
    @Test
    fun `the line count is what it was`() {
        val source = """
            /*
             * two
             */
            val a = 1
        """.trimIndent()

        assertEquals(
            source.lines().size, SourceScan.withoutComments(source).lines().size,
            "a comment took its newlines with it, so every offset below it moved",
        )
    }

    @Test
    fun `URL schemes inside string literals survive withoutComments untouched`() {
        val source = """
            val remote = "https://example.com/path?a=1" // trailing comment
            val local = "http://127.0.0.1:13337/?folder=/work"
            val raw = ${"\"\"\""}https://raw.example.com/a//b${"\"\"\""}
            val escaped = "quote \" https://still.inside/url" // end
        """.trimIndent()

        val code = SourceScan.withoutComments(source)

        assertTrue(code.contains("\"https://example.com/path?a=1\"")) { "https URL truncated: $code" }
        assertTrue(code.contains("\"http://127.0.0.1:13337/?folder=/work\"")) { "http URL truncated: $code" }
        assertTrue(code.contains("https://raw.example.com/a//b")) { "raw string URL truncated: $code" }
        assertTrue(code.contains("https://still.inside/url")) { "escaped-quote string URL truncated: $code" }
        assertFalse(code.contains("trailing comment")) { "line comment after string survived: $code" }
        assertFalse(code.contains("// end")) { "trailing line comment survived: $code" }
        assertEquals(source.lines().size, code.lines().size)
    }

    @Test
    fun `inline and nested block comments are stripped cleanly while preserving line count`() {
        val source = """
            val a = /* inline comment */ 1
            /* outer
               /* nested comment */
               still inside outer
            */
            val b = 2
            val c = a /* mid */ + b
        """.trimIndent()

        val code = SourceScan.withoutComments(source)

        assertFalse(code.contains("inline comment")) { "mid-line block comment survived: $code" }
        assertFalse(code.contains("outer")) { "outer block comment survived: $code" }
        assertFalse(code.contains("nested comment")) { "nested block comment survived: $code" }
        assertFalse(code.contains("still inside outer")) { "post-nested outer comment text survived: $code" }
        assertFalse(code.contains("mid")) { "second mid-line block comment survived: $code" }
        assertTrue(code.contains("val a =")) { "code before inline comment lost: $code" }
        assertTrue(code.contains("1")) { "code after inline comment lost: $code" }
        assertTrue(code.contains("val b = 2")) { "code after nested block comment lost: $code" }
        assertEquals(source.lines().size, code.lines().size)
    }

    @Test
    fun `wildcard mime types in two strings do not swallow live code between them`() {
        val source = """
            val first = arrayOf("*/*")
            liveCallBetweenStrings()
            val second = arrayOf("*/*")
        """.trimIndent()

        val code = SourceScan.withoutComments(source)

        assertTrue(code.contains("\"*/*\"")) { "first wildcard string altered: $code" }
        assertTrue(code.contains("liveCallBetweenStrings()")) { "live code between two */* strings swallowed: $code" }
        assertEquals(2, Regex("""\*/\*""").findAll(code).count()) { "both wildcard strings should survive: $code" }
    }

    @Test
    fun `braces in strings raw strings chars templates and comments do not break body`() {
        val source = """
            // fun target() { commented out
            fun target() {
                val closeInString = "}"
                val escapedInString = "\"} /*"
                val openInRaw = ${"\"\"\""}{ unbalanced } }${"\"\"\""}
                val fourQuotesRaw = ${"\"\"\"\""}quoted } end${"\"\"\"\""}
                val closeChar = '}'
                val openChar = '{'
                val quoteChar = '\''
                val templated = "prefix ${'$'}{if (true) "}" else "{"} suffix }"
                /* block comment with } and /* nested } */ still } */
                // line comment with }
                realBodyTail()
            }
            fun nextFunction() {
                shouldNotBeIncluded()
            }
        """.trimIndent()

        val body = SourceScan.body(source, "fun target(")

        assertTrue(body.contains("realBodyTail()")) {
            "body() truncated early on a brace inside a literal or comment: $body"
        }
        assertFalse(body.contains("nextFunction")) {
            "body() overran past target() into nextFunction(): $body"
        }
        assertFalse(body.contains("shouldNotBeIncluded")) {
            "body() overran into nextFunction() body: $body"
        }
    }

    @Test
    fun `slash-star-slash does not close a block comment immediately`() {
        val source = "val a = /*/ hiddenCall() still in comment */ 1"

        val code = SourceScan.withoutComments(source)

        assertFalse(code.contains("hiddenCall")) { "block comment closed on opening slash-star-slash: $code" }
        assertTrue(code.contains("val a =") && code.contains("1")) { "surrounding live code lost: $code" }
    }

    @Test
    fun `raw strings preserve non-escaping backslashes and support template expressions`() {
        val source = """
            fun sample() {
                val slash = ${"\"\"\""}\${"\"\"\""}
                val slashQuote = ${"\"\"\""}\" ${"\"\"\""}
                val tpl = ${"\"\"\""}before ${'$'}{/* inner comment */ if (true) "}" else "{"} after${"\"\"\""}
                afterRawStrings()
            }
            fun trailing() = Unit
        """.trimIndent()

        val code = SourceScan.withoutComments(source)
        val body = SourceScan.body(source, "fun sample(")

        assertFalse(code.contains("inner comment")) { "comment inside raw-string template survived: $code" }
        assertTrue(code.contains("afterRawStrings()")) { "backslash at end of raw string swallowed live code: $code" }
        assertTrue(body.contains("afterRawStrings()")) { "raw string backslash or template broke body(): $body" }
        assertFalse(body.contains("trailing")) { "body() overran past sample(): $body" }
    }

    @Test
    fun `nested template expressions strip inner comments and preserve inner strings`() {
        val source = """
            fun nested() {
                val msg = "outer ${'$'}{"inner ${'$'}{/* deep */ "http://127.0.0.1}"}"}" // line comment
                liveAfterNested()
            }
        """.trimIndent()

        val code = SourceScan.withoutComments(source)
        val body = SourceScan.body(source, "fun nested(")

        assertFalse(code.contains("deep")) { "comment inside nested template survived: $code" }
        assertFalse(code.contains("line comment")) { "trailing line comment survived: $code" }
        assertTrue(code.contains("http://127.0.0.1}")) { "URL inside nested template string altered: $code" }
        assertTrue(body.contains("liveAfterNested()")) { "nested template braces truncated body(): $body" }
    }

    @Test
    fun `backtick identifiers do not escape closing backtick and do not confuse body or comments`() {
        val source = """
            fun `name with { } /* and trailing \`() {
                // real comment after backtick
                val ok = 1
            }
            fun next() = Unit
        """.trimIndent()

        val code = SourceScan.withoutComments(source)
        val body = SourceScan.body(source, "fun `name with")

        assertFalse(code.contains("real comment")) { "trailing backslash inside backtick escaped closing backtick: $code" }
        assertTrue(body.contains("val ok = 1")) { "braces or slash-star inside backtick broke body(): $body" }
        assertFalse(body.contains("fun next")) { "body() overran past backtick function: $body" }
    }

    @Test
    fun `default parameter lambdas do not truncate body and fragments still match`() {
        val source = """
            fun withDefault(cb: () -> Unit = { defaultBody() }, n: Int = 1) {
                realBody()
            }
            fun next() = Unit
        """.trimIndent()

        val body = SourceScan.body(source, "fun withDefault(")

        assertTrue(body.contains("realBody()")) { "default parameter lambda truncated body(): $body" }
        assertFalse(body.contains("defaultBody()")) { "body() latched onto default parameter lambda: $body" }
        assertFalse(body.contains("fun next")) { "body() overran past withDefault(): $body" }

        val fragment = "foo(bar) { insideFragment() }\nval after = 1"
        val fragmentBody = SourceScan.body(fragment, ") {")
        assertTrue(fragmentBody.contains("insideFragment()")) { "fragment caller failed: $fragmentBody" }
        assertFalse(fragmentBody.contains("val after")) { "fragment body overran: $fragmentBody" }
    }

    @Test
    fun `body rejects a deleted declaration mentioned only inside a string literal`() {
        val source = """
            val doc = "private fun deletedHelper() { fakeBody() }"
            val raw = ${"\"\"\""}private fun deletedHelper() { fakeRawBody() }${"\"\"\""}
            fun alive() = Unit
        """.trimIndent()

        assertThrows(IllegalStateException::class.java) {
            SourceScan.body(source, "private fun deletedHelper(")
        }
    }

    @Test
    fun `withoutJsComments scriptBody and jsBody handle JS comments strings templates regexes and division`() {
        val js = """
            // js line comment with function apply(el) { fake(); }
            /* js block /* not nested */
            /*/ hiddenInSlashStarSlash() */
            const url = 'http://127.0.0.1:13337//path'; // strip me
            const dbl = "https://example.com/*not a comment*/";
            const tpl = `prefix ${'$'}{/* inner */ a / b / c} // keep inside template`;
            const reClass = /[/]\/\/not a comment/g;
            const reUrl = /https?:\/\/example\.com/i;
            const ratio = Math.floor(0.4 * viewHeight / height) / 2;
            const fakeStr = "function target(el) { wrong(); }";
            function target(el, cb = () => { fallback(); }) {
                const s = "}";
                const r = /[}]/;
                return realJsBody(el, ratio, reClass, reUrl);
            }
            function other() { notTarget(); }
        """.trimIndent()

        val stripped = SourceScan.withoutJsComments(js)

        assertFalse(stripped.contains("strip me")) { "JS line comment survived: $stripped" }
        assertFalse(stripped.contains("js line comment")) { "JS leading line comment survived: $stripped" }
        assertFalse(stripped.contains("not nested")) { "JS block comment survived: $stripped" }
        assertFalse(stripped.contains("hiddenInSlashStarSlash")) { "JS slash-star-slash closed comment early: $stripped" }
        assertFalse(stripped.contains("inner")) { "JS comment inside template expression survived: $stripped" }
        assertTrue(stripped.contains("'http://127.0.0.1:13337//path'")) { "JS single-quoted URL corrupted: $stripped" }
        assertTrue(stripped.contains("\"https://example.com/*not a comment*/\"")) { "JS double-quoted string corrupted: $stripped" }
        assertTrue(stripped.contains("// keep inside template")) { "JS template literal text stripped: $stripped" }
        assertTrue(stripped.contains("/[/]\\/\\/not a comment/g")) { "JS regex with char-class slash corrupted: $stripped" }
        assertTrue(stripped.contains("/https?:\\/\\/example\\.com/i")) { "JS URL regex corrupted: $stripped" }
        assertTrue(stripped.contains("0.4 * viewHeight / height) / 2")) { "JS division misread as regex: $stripped" }
        assertEquals(js.lines().size, stripped.lines().size)

        val targetBody = SourceScan.jsBody(stripped, "function target(")
        assertTrue(targetBody.contains("realJsBody")) { "jsBody missed real target body: $targetBody" }
        assertFalse(targetBody.contains("wrong()")) { "jsBody matched JS string literal instead of code: $targetBody" }
        assertFalse(targetBody.contains("fallback()")) { "jsBody truncated on JS default arrow body: $targetBody" }
        assertFalse(targetBody.contains("notTarget")) { "jsBody overran into other(): $targetBody" }

        assertThrows(IllegalStateException::class.java) {
            SourceScan.jsBody(stripped, "function apply(el)")
        }

        val ktSource = "private fun injectSample() {\n" +
            "    val js = \"\"\"\n" +
            "        // shadow-root-host mentioned in JS comment with aimedAtText\n" +
            "        if (el.closest('.shadow-root-host')) return;\n" +
            "        const endpoint = 'http://127.0.0.1';\n" +
            "    \"\"\".trimIndent()\n" +
            "}\n"
        val extracted = SourceScan.scriptBody(ktSource, "private fun injectSample(")
        assertFalse(extracted.contains("aimedAtText")) { "scriptBody did not strip JS comments: $extracted" }
        assertTrue(extracted.contains("el.closest('.shadow-root-host')")) { "scriptBody lost JS code: $extracted" }
        assertTrue(extracted.contains("'http://127.0.0.1'")) { "scriptBody corrupted JS URL: $extracted" }
    }
}
