package com.vscodroid.webview

import com.vscodroid.AppNavigationMark
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which `beforeunload` this app is entitled to answer for the user.
 *
 * The callback is reached only when the workbench vetoed leaving, and it vetoes
 * only while it holds an edit it has not written a backup of yet. So answering
 * one that was not ours discards that edit silently, and the whole value of the
 * mark is that it stops being true the moment it has done its job.
 *
 * The window used to be the only guard. A folder open, or a reload after five
 * minutes in the background, marked and then left the answer armed for ten
 * seconds; anything the workbench navigated itself to inside those ten seconds,
 * a Close Folder or an Open Recent, was confirmed for the user over a veto that
 * meant unsaved work.
 */
class AppNavigationMarkTest {

    private val window = 10_000L

    @Test
    fun `a navigation this app marked is answered`() {
        val mark = AppNavigationMark(window)
        mark.mark(1_000)

        assertTrue(
            mark.consume(1_050),
            "the app's own navigation was not recognised, so the user gets a browser " +
                "modal over something they already chose",
        )
    }

    @Test
    fun `nothing is answered before anything is marked`() {
        assertFalse(
            AppNavigationMark(window).consume(0),
            "an unmarked page-initiated unload was claimed as the app's own",
        )
    }

    /**
     * The case this class exists for. One mark, two unloads: the second is the
     * page's own and must keep the dialog, however soon it arrives.
     */
    @Test
    fun `a second unload inside the window is not answered`() {
        val mark = AppNavigationMark(window)
        mark.mark(1_000)

        assertTrue(mark.consume(1_050), "the marked navigation was refused")
        assertFalse(
            mark.consume(1_100),
            "a page-initiated unload arriving while the mark was still inside its " +
                "window was confirmed for the user, which discards the unsaved work " +
                "the workbench vetoed leaving for",
        )
    }

    @Test
    fun `a mark whose load never answered expires`() {
        val mark = AppNavigationMark(window)
        mark.mark(1_000)

        assertFalse(
            mark.consume(1_000 + window),
            "a mark left by a navigation that never happened stayed armed past its " +
                "window, so a page-initiated unload much later was answered for",
        )
    }

    /** Each navigation gets its own answer; consuming one does not disarm the app. */
    @Test
    fun `marking again re-arms the answer`() {
        val mark = AppNavigationMark(window)
        mark.mark(1_000)
        mark.consume(1_050)

        mark.mark(2_000)

        assertTrue(
            mark.consume(2_050),
            "the second navigation this app started was treated as the page's own",
        )
    }
}
