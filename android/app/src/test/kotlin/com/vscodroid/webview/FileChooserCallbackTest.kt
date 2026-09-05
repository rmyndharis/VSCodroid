package com.vscodroid.webview

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * That every `<input type=file>` gets exactly one answer.
 *
 * The platform contract has teeth. Returning true from `onShowFileChooser`
 * takes ownership of the callback, and a callback that is never invoked leaves
 * the element with a request outstanding for as long as the document lives: no
 * later chooser opens on that page, so the Explorer's `Upload...` silently does
 * nothing from then on and reopening the folder does not clear it. That is a
 * worse failure than declining the request outright, because it survives the
 * retry the user will make.
 *
 * So these drive the answer, not the request. Each case asserts what the page
 * received and how many times, which is the only thing the contract is about.
 */
class FileChooserCallbackTest {

    /** What the launcher was asked for, one entry per dispatch. */
    private val dispatched = mutableListOf<Boolean>()

    /** Whether the fake launcher reports that it started something. */
    private var launcherStarts = true

    private lateinit var client: VSCodroidWebChromeClient

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        dispatched.clear()
        launcherStarts = true
        client = VSCodroidWebChromeClient(navigationIsOurs = { false }) { allowMultiple ->
            dispatched += allowMultiple
            launcherStarts
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** Records every answer handed to one `<input type=file>`. */
    private class Element {
        val answers = mutableListOf<List<Uri>>()
        val callback = ValueCallback<Array<Uri>> { answers += it?.toList() ?: emptyList() }
    }

    private fun show(element: Element, mode: Int = FileChooserParams.MODE_OPEN): Boolean {
        val params = mockk<FileChooserParams>(relaxed = true)
        every { params.mode } returns mode
        return client.onShowFileChooser(mockk<WebView>(relaxed = true), element.callback, params)
    }

    /**
     * Cancelling is the common case, and it is the one that wedges the page if
     * it goes unanswered: the picker returns nothing and there is no event on
     * the way to say so.
     */
    @Test
    fun `a cancelled picker still answers the page`() {
        val element = Element()
        assertTrue(show(element), "the chooser was handled, so the callback is ours to invoke")

        client.onFileChooserResult(emptyList())

        assertEquals(listOf(emptyList<Uri>()), element.answers,
            "cancellation has to reach the page as an empty selection, exactly once")
    }

    /** The picked files reach the page, in the order the picker gave them. */
    @Test
    fun `the picked files reach the page`() {
        val element = Element()
        show(element, FileChooserParams.MODE_OPEN_MULTIPLE)
        val first = mockk<Uri>(relaxed = true)
        val second = mockk<Uri>(relaxed = true)

        client.onFileChooserResult(listOf(first, second))

        assertEquals(1, element.answers.size, "one request, one answer")
        assertEquals(2, element.answers[0].size, "both files were selected")
        assertSame(first, element.answers[0][0])
        assertSame(second, element.answers[0][1])
    }

    /**
     * Only one request can be tracked at a time, so the one being displaced has
     * to be answered on the way out. Dropping it is how a page ends up with a
     * request that can never be satisfied.
     */
    @Test
    fun `a second request answers the one it displaces`() {
        val first = Element()
        val second = Element()
        show(first)
        show(second)

        assertEquals(listOf(emptyList<Uri>()), first.answers,
            "the displaced request must be released, not dropped")
        assertEquals(emptyList<List<Uri>>(), second.answers,
            "the request that is still outstanding must not be answered early")

        val picked = mockk<Uri>(relaxed = true)
        client.onFileChooserResult(listOf(picked))

        assertEquals(1, second.answers.size, "the result belongs to the live request")
        assertSame(picked, second.answers[0].single())
        assertEquals(1, first.answers.size, "the displaced request is answered once, not twice")
    }

    /**
     * Exactly once, in the other direction. A launcher result can arrive for a
     * page that has already been replaced, and a second answer to the same
     * element is as much a contract breach as none.
     */
    @Test
    fun `a result arriving twice is delivered once`() {
        val element = Element()
        show(element)

        client.onFileChooserResult(listOf(mockk<Uri>(relaxed = true)))
        client.onFileChooserResult(listOf(mockk<Uri>(relaxed = true)))

        assertEquals(1, element.answers.size, "the second result has nobody left to answer")
    }

    /**
     * What the Activity reads to keep the resume reload off a page that is about
     * to be handed a file.
     *
     * The picker is another app, so the browse backgrounds this one, and coming
     * back from a long browse is the shape the forced reload was written for.
     * Reporting nothing outstanding there loses the selection with no message
     * anywhere, so the flag has to be true for exactly as long as the answer is
     * still owed.
     */
    @Test
    fun `an outstanding request is visible until it is answered`() {
        val element = Element()
        assertFalse(client.hasPendingFileChooser, "nothing is waiting before a request")

        show(element)
        assertTrue(client.hasPendingFileChooser, "the request is out while the picker is up")

        client.onFileChooserResult(emptyList())
        assertFalse(client.hasPendingFileChooser, "answering releases the element")
    }

    /** Nothing outstanding, nothing to answer, and nothing to crash on. */
    @Test
    fun `a result with nothing outstanding is harmless`() {
        client.onFileChooserResult(listOf(mockk<Uri>(relaxed = true)))
    }

    /**
     * A device with no document picker at all. The dispatch fails, and the page
     * has to hear about it now, because nothing else ever will.
     */
    @Test
    fun `a picker that cannot start answers immediately`() {
        launcherStarts = false
        val element = Element()

        assertTrue(show(element), "a path that answers the callback must claim the request too")

        assertEquals(listOf(emptyList<Uri>()), element.answers,
            "a chooser that never opened has to release the element straight away")
    }

    /**
     * Multi-select is offered only when the page asked for it. An input for one
     * file that is shown a multi-select picker lets the user choose several and
     * silently keeps one.
     */
    @Test
    fun `selection mode follows what the page asked for`() {
        show(Element(), FileChooserParams.MODE_OPEN)
        show(Element(), FileChooserParams.MODE_OPEN_MULTIPLE)

        assertEquals(listOf(false, true), dispatched)
    }
}
