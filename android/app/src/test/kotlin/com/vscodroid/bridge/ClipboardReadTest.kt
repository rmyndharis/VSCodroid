package com.vscodroid.bridge

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * That reading the clipboard reads text, and does not open whatever else is on it.
 *
 * `ClipData.Item.coerceToText` is not a text accessor, which is what this method
 * used to treat it as. Its documented behaviour for an item holding no text but a
 * URI is to open that URI through the caller's own `ContentResolver`, read the
 * stream as text and return it, falling back to the display name and then to the
 * URI string. Copying a file in a file manager puts a `content://` URI on the
 * clipboard, so an ordinary user action turned a clipboard read into a provider
 * read performed with this app's identity, on the bridge thread, with no size or
 * time bound, and handed the document's contents to page JavaScript that had
 * asked for the clipboard.
 *
 * [ClipboardBridge.hasClipboardText] gates on the wildcard text mime type and the
 * did not consult it, so the guard and the read disagreed: the guard said there
 * was no text and the read returned a file.
 *
 * The coercion is kept for the clip it is right for. A styled or HTML clip is
 * a wildcard text clip and can carry no plain-text field, and rendering it is
 * `coerceToText` is for there.
 */
class ClipboardReadTest {

    private lateinit var context: Context
    private lateinit var manager: ClipboardManager
    private lateinit var clipboard: ClipboardBridge

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.e(any(), any(), any()) } just Runs

        manager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns manager
        clipboard = ClipboardBridge(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * One item on the clipboard, with the mime type its description declares and
     * the URI it carries.
     *
     * [uri] is not decoration: it is the whole of what makes `coerceToText` a
     * provider read, and a fixture without it cannot model the case these tests
     * exist for.
     */
    private fun clipOf(
        text: CharSequence?,
        mimeIsText: Boolean,
        uri: android.net.Uri? = null,
    ): ClipData.Item {
        val item = mockk<ClipData.Item>(relaxed = true)
        every { item.text } returns text
        every { item.uri } returns uri
        val description = mockk<ClipDescription>(relaxed = true)
        every { description.hasMimeType("text/*") } returns mimeIsText
        val clip = mockk<ClipData>(relaxed = true)
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns item
        every { clip.description } returns description
        every { manager.primaryClip } returns clip
        return item
    }

    @Test
    fun `text on the clipboard comes back as itself`() {
        val item = clipOf("fun main() {}", mimeIsText = true)

        assertEquals("fun main() {}", clipboard.readFromClipboard())
        verify(exactly = 0) { item.coerceToText(any()) }
    }

    @Test
    fun `a file copied from a file manager is not opened and read`() {
        // The clip a file manager actually leaves, which is not what this fixture
        // used to describe. It said the mime type was "the document's rather than
        // text" and set mimeIsText = false, a clip that cannot occur for a text
        // file: copying a .kt, a .md or a .txt declares text/plain, and
        // ClipDescription.hasMimeType matches the text/ wildcard against every declared type.
        // ClipData.newUri also appends text/uri-list when the provider resolves no
        // type. So the description says text either way, getText is null because an
        // item built from a URI has none, and the old guard let coerceToText open
        // the document through this app's ContentResolver.
        val item = clipOf(null, mimeIsText = true, uri = mockk(relaxed = true))

        assertNull(
            clipboard.readFromClipboard(),
            "a clipboard entry carrying a URI answered with something, which is the " +
                "document this app was able to open on the caller's behalf",
        )
        verify(exactly = 0) { item.coerceToText(any()) }
        assertFalse(
            clipboard.hasClipboardText(),
            "the guard says there is text to read for a clip the read declines, which " +
                "is the two halves disagreeing again with the sides swapped",
        )
    }

    @Test
    fun `a styled clip with no plain text field is still rendered`() {
        // The case the coercion is for, and the control that keeps the fix from
        // being "return item.text and nothing else": an HTML or styled clip is
        // the text/ wildcard, and its plain-text field can be absent.
        // No URI, which is what distinguishes it from the file-manager clip above.
        val item = clipOf(null, mimeIsText = true, uri = null)
        every { item.coerceToText(any()) } returns "rendered text"

        assertEquals("rendered text", clipboard.readFromClipboard())
        assertTrue(clipboard.hasClipboardText(), "the styled clip must still count as text")
    }

    @Test
    fun `an empty clipboard answers with nothing`() {
        val clip = mockk<ClipData>(relaxed = true)
        every { clip.itemCount } returns 0
        every { manager.primaryClip } returns clip

        assertNull(clipboard.readFromClipboard())
    }
}
