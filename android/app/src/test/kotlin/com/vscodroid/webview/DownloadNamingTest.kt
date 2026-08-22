package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a downloaded file is called.
 *
 * Not cosmetic. The name is offered to the create-document picker and the file
 * is created under it, so a wrong one costs the user a retype and an empty one
 * offers a file that cannot be created at all. Four sources feed it and they
 * disagree often, which is why the order between them is asserted here rather
 * than left to whichever branch happens to run first.
 *
 * The sanitising half is the part with a security shape: the best source is the
 * page's own `download` attribute, which is text the page chose. A candidate
 * carrying a path separator would be handed to the picker as a path, and what a
 * documents provider does with one is the provider's decision and not this
 * app's.
 */
class DownloadNamingTest {

    /**
     * The page's own report wins, because it is the only source that saw the
     * anchor the user clicked. Every case below is a URL and a header that would
     * each give a different answer.
     */
    @Test
    fun `the name the page reported outranks every other source`() {
        val name = downloadFileName(
            url = "http://127.0.0.1:5000/vscode-remote-resource?path=%2Fhome%2Fu%2Ffrom-url.txt",
            contentDisposition = "attachment; filename=\"from-header.txt\"",
            suggested = "from-page.txt",
        )

        assertEquals("from-page.txt", name)
    }

    /** With no report from the page, a real header is the next thing that knows. */
    @Test
    fun `a content-disposition header is used when the page said nothing`() {
        val name = downloadFileName(
            url = "http://127.0.0.1:5000/vscode-remote-resource?path=%2Fhome%2Fu%2Ffrom-url.txt",
            contentDisposition = "attachment; filename=\"from-header.txt\"",
            suggested = null,
        )

        assertEquals("from-header.txt", name)
    }

    /**
     * The extended form carries its encoding, so it is the one that survives a
     * non-ASCII name. Both forms are usually present together and the plain one
     * is the lossy copy, so reading it first would discard the good name in
     * exactly the case the two differ.
     */
    @Test
    fun `the encoded filename form is preferred over the plain one`() {
        val name = downloadFileName(
            url = "http://host/x",
            contentDisposition =
                "attachment; filename=\"ascii-fallback.txt\"; filename*=UTF-8''caf%C3%A9%20menu.txt",
            suggested = null,
        )

        assertEquals("café menu.txt", name)
    }

    /** An unquoted `filename` is legal and is read. */
    @Test
    fun `an unquoted filename is read`() {
        val name = downloadFileName(
            url = "http://host/x",
            contentDisposition = "attachment; filename=plain.txt; size=10",
            suggested = null,
        )

        assertEquals("plain.txt", name)
    }

    /**
     * The editor's remote-resource endpoint carries the file's POSIX path in a
     * query parameter, and the URL's own last segment is the endpoint's name.
     * Reading the segment instead would call every file in the workspace
     * `vscode-remote-resource`, which is a plausible-looking name and therefore
     * the worst kind of wrong.
     */
    @Test
    fun `the path parameter is read rather than the endpoint name`() {
        val name = downloadFileName(
            url = "http://127.0.0.1:5000/vscode-remote-resource" +
                "?path=%2Fdata%2Fprojects%2Fapp%2Fbuild.gradle.kts&tkn=secret",
            contentDisposition = null,
            suggested = null,
        )

        assertEquals("build.gradle.kts", name)
    }

    /** An ordinary file URL still answers with its last segment. */
    @Test
    fun `a plain url falls back to its last segment`() {
        val name = downloadFileName("https://host/a/b/archive.tar.gz", null, null)

        assertEquals("archive.tar.gz", name)
    }

    /**
     * A blob URL is a UUID with no extension and names nothing. Offering it
     * would put an unopenable name in front of the user while looking like a
     * real answer, so the last-segment rule requires a dot and this falls
     * through to the placeholder instead.
     */
    @Test
    fun `a blob url gives the placeholder rather than its uuid`() {
        val name = downloadFileName(
            "blob:http://127.0.0.1:5000/6f1d2c30-9a4b-4c1e-8f77-2b0a5d3e91cc", null, null,
        )

        assertEquals(FALLBACK_DOWNLOAD_NAME, name)
    }

    /**
     * Every candidate is reduced to one path segment, including the page's own
     * report, which is the one an attacker controls.
     */
    @Test
    fun `a name carrying a path is reduced to its last segment`() {
        assertEquals("passwd", safeFileName("../../../etc/passwd"))
        assertEquals("evil.sh", safeFileName("""C:\Windows\evil.sh"""))
        assertEquals("file.txt", safeFileName("/absolute/file.txt"))
    }

    /**
     * Candidates that survive every character rule and still name no file. They
     * have to be refused rather than trimmed, so the next source gets its turn.
     */
    @Test
    fun `names that point at a directory are refused`() {
        assertNull(safeFileName("."))
        assertNull(safeFileName(".."))
        assertNull(safeFileName("some/dir/"))
        assertNull(safeFileName("   "))
        assertNull(safeFileName(""))
        assertNull(safeFileName(null))
    }

    /** Control characters are dropped, and a name that was only those is refused. */
    @Test
    fun `control characters are removed`() {
        assertEquals("report.txt", safeFileName("re\u0000port\u0007.txt"))
        assertNull(safeFileName("\u0001\u0002\u007F"))
    }

    /**
     * A refused candidate hands over to the next source rather than emptying the
     * name. Falling through is the whole reason [safeFileName] answers null.
     */
    @Test
    fun `a refused candidate falls through to the next source`() {
        val name = downloadFileName(
            url = "https://host/a/real-name.txt",
            contentDisposition = "attachment; filename=\"..\"",
            suggested = "   ",
        )

        assertEquals("real-name.txt", name)
    }

    /** Nothing knows anything. The placeholder is the answer, never an empty string. */
    @Test
    fun `a url with nothing to read still gives a usable name`() {
        assertEquals(FALLBACK_DOWNLOAD_NAME, downloadFileName("https://host/", null, null))
        assertEquals(FALLBACK_DOWNLOAD_NAME, downloadFileName("", null, ""))
    }

    /**
     * Long names are cut rather than passed on. The limit is about the
     * filesystem, so it has to apply to the page's report too, which is the
     * source with no length rule of its own.
     */
    @Test
    fun `an overlong name is cut to something a filesystem accepts`() {
        val name = downloadFileName("https://host/x", null, "a".repeat(500) + ".txt")

        assertEquals(200, name.length)
    }

    /**
     * The same limit for a name that is not ASCII, which is where a character
     * count stops answering the question.
     *
     * The limit is about bytes, because that is what a filesystem counts: 200 CJK
     * characters are 600 bytes and 200 emoji are 800, so a cut at 200 characters
     * hands the picker a name three to four times over the limit the constant
     * exists to respect. What a documents provider does with one is its own
     * decision, and both of the realistic ones (truncating the name the user just
     * typed, or refusing to create the document) are failures they cannot read.
     *
     * The round trip is the second half and it is not decoration. Cutting inside a
     * character, or between the halves of a surrogate pair, yields a name whose
     * bytes are not the name: it comes back with a replacement character where the
     * user's own text was.
     */
    @Test
    fun `an overlong multibyte name is cut by bytes, not by characters`() {
        val cjk = downloadFileName("https://host/x", null, "\u6f22".repeat(300) + ".txt")

        assertTrue(
            cjk.toByteArray(Charsets.UTF_8).size <= 200,
            "the name is ${cjk.toByteArray(Charsets.UTF_8).size} bytes, past the limit most " +
                "filesystems impose",
        )
        assertTrue(cjk.isNotEmpty(), "the whole name was cut away, which is not a name")

        val emoji = downloadFileName("https://host/x", null, "\uD83D\uDE00".repeat(200))

        assertTrue(
            emoji.toByteArray(Charsets.UTF_8).size <= 200,
            "the name is ${emoji.toByteArray(Charsets.UTF_8).size} bytes",
        )
        assertEquals(
            emoji, String(emoji.toByteArray(Charsets.UTF_8), Charsets.UTF_8),
            "the cut landed inside a character, so the name does not survive being written",
        )
    }

    /**
     * Percent-encoding is undone so the user sees the name rather than its
     * transport form, and a stray percent is a character rather than a syntax
     * error: a decoder that throws would cancel a download over a filename.
     */
    @Test
    fun `encoded segments are decoded and a bad escape is left alone`() {
        assertEquals("my file.txt", downloadFileName("https://host/my%20file.txt", null, null))
        assertEquals("100%.csv", downloadFileName("https://host/100%.csv", null, null))
    }

    /** A fragment is not part of any name. */
    @Test
    fun `a fragment is not read as part of the name`() {
        assertEquals("page.html", downloadFileName("https://host/page.html#section", null, null))
    }

    /**
     * `+` means a space in a query parameter and means itself everywhere else,
     * and one decoder serves both. Reading a path segment under the query rule
     * turns a C++ file into one with two spaces in the middle of its name.
     */
    @Test
    fun `a plus in a path segment is a plus, not a space`() {
        assertEquals("c++notes.txt", downloadFileName("https://host/a/c++notes.txt", null, null))
        assertEquals(
            "a+b.txt",
            downloadFileName("https://host/x", "attachment; filename*=UTF-8''a%2Bb.txt", null),
        )
        // And the one place the query rule is right still gets it.
        assertEquals(
            "my file.txt",
            downloadFileName("https://host/r?path=/w/my+file.txt", null, null),
        )
    }
}
