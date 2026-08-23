package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * What the webview resource arm answers a `Range` header with.
 *
 * The arm used to answer every request `200 OK` with the whole file and no
 * `Accept-Ranges`, which Chromium's media loader reads as "seeking is limited to
 * what has already been buffered". `media-preview` is bundled, so dragging the
 * scrubber of an ordinary `.mp4` in the workspace is what meets it.
 *
 * The parse is tested as a value and the bounded body as a stream, because they
 * fail in different ways and neither can be observed through the response: a
 * `WebResourceResponse` cannot be constructed under the stub `android.jar`, so a
 * case that went through the interceptor could assert nothing about the body it
 * built.
 */
class ByteRangeTest {

    private val length = 1000L

    @Test
    fun `a closed range is taken as written`() {
        assertEquals(100L..199L, VSCodroidWebViewClient.byteRangeOf("bytes=100-199", length))
    }

    @Test
    fun `an open range runs to the last byte`() {
        assertEquals(100L..999L, VSCodroidWebViewClient.byteRangeOf("bytes=100-", length))
    }

    /**
     * The form a media loader uses to read a trailing index before it plays
     * anything, and the one an implementation that only handles `start-` gets
     * wrong by serving the first bytes instead of the last.
     */
    @Test
    fun `a suffix range is the last n bytes`() {
        assertEquals(900L..999L, VSCodroidWebViewClient.byteRangeOf("bytes=-100", length))
    }

    /** A player routinely asks for more than the file holds. */
    @Test
    fun `an end past the file is clamped rather than refused`() {
        assertEquals(900L..999L, VSCodroidWebViewClient.byteRangeOf("bytes=900-99999", length))
        assertEquals(0L..999L, VSCodroidWebViewClient.byteRangeOf("bytes=-99999", length))
    }

    /**
     * Everything a 200 still satisfies answers null, which is what keeps a header
     * this app cannot honour from turning into a failed load. RFC 9110 lets a
     * server ignore a `Range` it does not want to answer.
     */
    @Test
    fun `anything unanswerable falls back to the whole file`() {
        assertNull(VSCodroidWebViewClient.byteRangeOf(null, length), "no header")
        assertNull(VSCodroidWebViewClient.byteRangeOf("items=0-10", length), "another unit")
        assertNull(VSCodroidWebViewClient.byteRangeOf("bytes=0-10,20-30", length), "multi-range")
        assertNull(VSCodroidWebViewClient.byteRangeOf("bytes=abc-def", length), "not a number")
        assertNull(
            VSCodroidWebViewClient.byteRangeOf("bytes=100-19x", length),
            "an end that is present and unreadable is a malformed header, not an open range: " +
                "answering 100..999 sends a 206 for something nobody asked for",
        )
        assertNull(
            VSCodroidWebViewClient.byteRangeOf("bytes=100", length),
            "a spec with no dash is a malformed header, not an open range: Kotlin's " +
                "missing-delimiter behaviour made it answer 100..999, a 206 for a range " +
                "nobody asked for",
        )
        assertNull(VSCodroidWebViewClient.byteRangeOf("bytes=1000-1100", length), "past the end")
        assertNull(VSCodroidWebViewClient.byteRangeOf("bytes=200-100", length), "backwards")
        assertNull(VSCodroidWebViewClient.byteRangeOf("bytes=-0", length), "an empty suffix")
        assertNull(VSCodroidWebViewClient.byteRangeOf("bytes=0-", 0L), "an empty file")
    }

    /**
     * A 206 declares how many bytes it is sending and has to send exactly that
     * many. A `FileInputStream` seeked to the start of a range runs to the end of
     * the file, so without the bound the body is longer than its own
     * `Content-Length` on every range that is not a suffix.
     */
    @Test
    fun `the body stops at the end of the range`() {
        val source = ByteArrayInputStream(ByteArray(10) { it.toByte() })
        source.skip(2)

        val body = BoundedInputStream(source, 3).readBytes()

        assertArrayEquals(byteArrayOf(2, 3, 4), body, "the body ran past the range it declared")
    }

    /**
     * And the same through `skip`, which is the third way a consumer moves.
     *
     * `FilterInputStream.skip` forwards to the wrapped stream and touches no
     * counter of ours, so skipping slid the window past the end of the range
     * while the bound went on allowing the same number of bytes after it: the
     * body then ran off the end of what `Content-Range` declared.
     */
    @Test
    fun `skipping moves the end of the range with it`() {
        val source = ByteArrayInputStream(ByteArray(10) { it.toByte() })
        val body = BoundedInputStream(source, 3)

        assertEquals(1L, body.skip(1))

        assertArrayEquals(
            byteArrayOf(1, 2), body.readBytes(),
            "the skipped byte was not charged to the range, so the body ran past its end",
        )
    }

    /** And the same through the single-byte read, which is a separate override. */
    @Test
    fun `the single-byte read stops at the end of the range`() {
        val source = ByteArrayInputStream(ByteArray(10) { it.toByte() })
        val body = BoundedInputStream(source, 2)

        assertEquals(0, body.read())
        assertEquals(1, body.read())
        assertEquals(-1, body.read(), "a third byte was handed out past a two-byte range")
    }
}
