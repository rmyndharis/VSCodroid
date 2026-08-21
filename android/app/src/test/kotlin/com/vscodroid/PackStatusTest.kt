package com.vscodroid

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for [isTerminalPackStatus] — the predicate that decides whether the
 * first-run download queue moves on.
 *
 * What these cover, and what they do not. They pin the predicate: which statuses
 * mean a pack is finished. They do NOT pin the wiring -- deleting the
 * `if (isTerminalPackStatus(status)) downloadNext()` call from
 * handleDownloadState leaves all four of them green, and the rest of the unit
 * suite with them, since nothing else calls handleDownloadState. Measured rather
 * than assumed. That call cannot be reached from a JVM test because it lives in an
 * Activity method and this project has no Robolectric. [DownloadStateWiringTest],
 * at the end of this file, is what covers the call itself.
 *
 * The predicate is still worth pinning, because getting it wrong is what stalled
 * the queue: the decision used to live inside two branches of a `when` with no
 * `else`, so CANCELED, NOT_INSTALLED and UNKNOWN advanced nothing and every
 * toolchain behind the stalled one went uninstalled.
 *
 * The cost of being wrong is not symmetric, and these tests encode that: a pack
 * wrongly treated as finished loses that toolchain, and there is no in-app way
 * to install it afterwards. A pack wrongly waited on loses that one and every
 * toolchain queued behind it. Losing one is better than losing the remainder.
 */
class PackStatusTest {

    @Test
    fun `states that are still going somewhere do not advance the queue`() {
        listOf(
            AssetPackStatus.DOWNLOADING,
            AssetPackStatus.TRANSFERRING,
            AssetPackStatus.PENDING,
            AssetPackStatus.WAITING_FOR_WIFI,
            AssetPackStatus.REQUIRES_USER_CONFIRMATION,
        ).forEach {
            assertFalse(isTerminalPackStatus(it), "status $it is still in progress")
        }
    }

    @Test
    fun `the three states that stalled setup now advance it`() {
        // The reported bug, exactly. CANCELED is what arrives when the download
        // is cancelled from the Play notification rather than from this screen.
        listOf(
            AssetPackStatus.CANCELED,
            AssetPackStatus.NOT_INSTALLED,
            AssetPackStatus.UNKNOWN,
        ).forEach {
            assertTrue(isTerminalPackStatus(it), "status $it must not stall the queue")
        }
    }

    @Test
    fun `the two states that always worked still advance it`() {
        assertTrue(isTerminalPackStatus(AssetPackStatus.COMPLETED))
        assertTrue(isTerminalPackStatus(AssetPackStatus.FAILED))
    }

    @Test
    fun `a status this build has never heard of advances it`() {
        // The default has to be "move on". A newer Play library adding a state
        // must not be able to reproduce the stall, and no code here can know
        // that state's name in advance.
        assertTrue(isTerminalPackStatus(9999), "an unknown status must not stall the queue")
    }
}

/**
 * Tests for [isCurrentDownload] — the check that decides whether a terminal
 * state is allowed to move the queue.
 *
 * This is the invariant the old code never expressed: it deduplicated by pack
 * name, which stops the same pack advancing twice and does nothing about a
 * different pack advancing once. The listener is registered for the app rather
 * than for one fetch, and every queued pack has a progress row, so a state
 * naming a pack that is not the current download reaches the handler.
 *
 * These have the same boundary as the tests above, and it is worth stating twice
 * because the first draft of this comment claimed otherwise: deleting the
 * isCurrentDownload guard from handleDownloadState leaves every test here green,
 * and the rest of the unit suite with them. Measured, with the results directory
 * cleared first. Nothing in this project can *run* a call inside an Activity
 * method -- there is no Robolectric -- so what these pin is the rule;
 * [DownloadStateWiringTest] pins that the code still consults it.
 */
class CurrentDownloadTest {

    private val queue = listOf("toolchain_ruby", "toolchain_java")

    @Test
    fun `the pack being downloaded advances the queue`() {
        assertTrue(isCurrentDownload("toolchain_ruby", queue, 0))
    }

    @Test
    fun `a pack still queued behind the current one does not`() {
        // The skip that mattered: java reporting anything while ruby is
        // downloading would have stepped over ruby and left it uninstalled,
        // with its row still reading "installing".
        assertFalse(isCurrentDownload("toolchain_java", queue, 0))
    }

    @Test
    fun `a pack the queue has already passed does not`() {
        // This is also how a repeat is handled. The first report moves the
        // index, so a second report for the same pack is no longer current --
        // no separate bookkeeping needed.
        assertFalse(isCurrentDownload("toolchain_ruby", queue, 1))
    }

    @Test
    fun `nothing advances the queue once it has passed the end`() {
        // Otherwise a late arrival reaches launchMain() a second time.
        assertFalse(isCurrentDownload("toolchain_java", queue, queue.size))
    }

    @Test
    fun `nothing advances the queue before it has started`() {
        // downloadNext() starts at -1 and pre-increments.
        assertFalse(isCurrentDownload("toolchain_go", queue, -1))
    }
}

/**
 * The wiring the two classes above cannot reach.
 *
 * Both of them say, correctly, that deleting a guard call from
 * handleDownloadState leaves their own tests green. That is true of every
 * predicate extracted out of an Activity: the rule can be pinned, the call to it
 * cannot, and both failures are silent. Losing the isCurrentDownload call lets a
 * state naming any other pack step the queue over whichever pack is genuinely
 * downloading; losing the isTerminalPackStatus call stops the queue advancing at
 * all and strands first-run setup on the progress screen.
 *
 * Robolectric would run the call and costs more than the gap: a JUnit 4 runner in
 * a module on `useJUnitPlatform()` with no vintage engine, `isIncludeAndroidResources`
 * this module deliberately leaves off, and an `android-all` download on every fresh
 * runner -- to reach one call site whose `onCreate` would have to be bypassed
 * anyway, since it runs FirstRunSetup, ToolchainManager and SafStorageManager
 * against a real filesDir. A second test engine is also a second way for tests to
 * be counted, or not counted, without anyone noticing.
 *
 * What distinguishes a wired handler from an unwired one is which names its body
 * compiles, so that is what this reads, following
 * [com.vscodroid.util.ThreadIdentityTest].
 */
class DownloadStateWiringTest {

    private val source = File("src/main/kotlin/com/vscodroid/SplashActivity.kt")

    private fun bodyOf(declaration: String): String {
        check(source.isFile) {
            "SplashActivity.kt not found at ${source.absolutePath} — this test would " +
                "otherwise pass by looking at nothing"
        }
        val text = source.readText()
        val start = text.indexOf(declaration)
        check(start >= 0) {
            "$declaration is gone from SplashActivity.kt; this guard names it, so " +
                "renaming it means deciding again where its calls are pinned"
        }

        val open = text.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}') depth--
            if (depth == 0) break
            i++
        }
        check(depth == 0) { "no closing brace found for $declaration" }
        return text.substring(open, i + 1)
    }

    /**
     * The names are searched for in code, not in prose. Commenting the guard out --
     * `// if (!isCurrentDownload(...))` -- leaves the characters in place, so matching
     * raw text would report a disabled guard as a wired one, which is the failure this
     * class exists to prevent rather than to demonstrate.
     *
     * Applied after the brace walk, not before: stripping first would take a `//` inside
     * a string literal and the rest of its line with it, and losing a brace that way
     * would truncate the span and fail a correct file.
     */
    private fun code(body: String) = body
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    @Test
    fun `handleDownloadState still consults both predicates`() {
        val raw = bodyOf("private fun handleDownloadState(")

        // The brace matcher is the weak part, so it is bounded rather than trusted:
        // an extraction that ran to the end of the file would contain both names for
        // entirely the wrong reason. Measured on the raw span, not the stripped one --
        // this method is mostly comment, and stripping first would put a correct
        // extraction under the floor. 3,758 characters when this was written, in a
        // file of 23,504.
        check(raw.length in 500..8000) {
            "extracted ${raw.length} characters of handleDownloadState, which means the " +
                "extraction is wrong rather than the code"
        }

        val body = code(raw)

        assertTrue(
            body.contains("isCurrentDownload("),
            "handleDownloadState no longer asks whether the pack is the current download, " +
                "so a state naming any other pack can step the queue over the one downloading",
        )
        assertTrue(
            body.contains("isTerminalPackStatus("),
            "handleDownloadState no longer asks whether the status is terminal, so the " +
                "queue stops advancing and first-run setup stalls on the progress screen",
        )
    }

    /**
     * The same gap one method over: [AnnouncedReasonTest] pins what may be said
     * twice, and nothing there notices if the Toast stops asking.
     */
    @Test
    fun `startDownloads still filters a reason it has already given`() {
        // 1,961 characters when this was written, against handleDownloadState's
        // 3,758 in a file of 29,742. Bounded for the same reason as above: an
        // extraction that ran past its own method would find the name somewhere
        // else and report a deleted filter as a live one.
        val raw = bodyOf("private fun startDownloads(")
        check(raw.length in 500..8000) {
            "extracted ${raw.length} characters of startDownloads, which means the " +
                "extraction is wrong rather than the code"
        }

        assertTrue(
            code(raw).contains("reasonToAnnounce("),
            "the failure Toast is no longer filtered, so packs failing for one cause " +
                "queue one long Toast each, most of them over the editor",
        )
    }

    /**
     * Setup failure has to be spoken, because this screen has no other way to say it.
     *
     * Setup writes into one label for minutes, and a user who is not touching the screen
     * hears nothing between the window opening and MainActivity arriving. Without the
     * announcement "still extracting" and "gave up" sound identical, so the wait ends
     * only when the user decides to check a screen that has been finished for a while.
     * This is the only spoken channel for that transition: no layout here sets
     * `accessibilityLiveRegion`, and the two `announceForAccessibility` calls in the app
     * are both in this file.
     *
     * ⚠️ Source text, not behaviour, for the reason the two cases above are: the call
     * lives in an Activity method and this module has no Robolectric. It proves the call
     * is written, not that a screen reader speaks it, and `docs/DEVICE_TEST_CHECKLIST.md`
     * is the instrument for that half.
     */
    @Test
    fun `a setup failure is spoken as well as painted`() {
        // 3,192 characters when this was written, in a file of 34,279. Bounded like the
        // cases above, and load-bearing here for the same reason: this bodyOf counts
        // braces raw, so an extraction that ran on would find the name in a later
        // method and report a deleted announcement as a live one.
        val raw = bodyOf("private fun showSetupError(")
        check(raw.length in 500..8000) {
            "extracted ${raw.length} characters of showSetupError, which means the " +
                "extraction is wrong rather than the code"
        }

        // Anchored to the start of a line. Commenting a call out is how a developer
        // disables one while debugging, and a substring search cannot tell the two
        // apart; code() already blanks comments, and the anchor is what stops a call
        // reappearing mid-line inside surviving text from satisfying this.
        assertTrue(
            Regex("""(?m)^\s*statusText\.announceForAccessibility\(""")
                .containsMatchIn(code(raw)),
            "showSetupError no longer speaks the failure, so a screen reader user is " +
                "left on a screen that says nothing between the last progress step and " +
                "a Retry button they never hear about",
        )
    }

    /**
     * A finished download row is repainted at full opacity, and named out loud.
     *
     * Two defects in one call, which is why one function owns both. The row's status
     * text runs dimmed while it counts percentages, which is right for a number changing
     * every second and wrong for the one word the user has to read at the end: setting
     * only the colour left the dimming in place and "Failed" measured 3.28:1 against
     * this window, under the 4.5:1 AA asks of 12sp text. And the row moved from a
     * percentage to Done or Failed in silence, so a stalled download and a progressing
     * one sounded the same.
     *
     * The announcement names its pack because the queue speaks several of these, and
     * "Done" on its own says which one only to someone watching the rows.
     */
    @Test
    fun `a finished download row is repainted at full opacity and named out loud`() {
        // 998 characters when this was written. Bounded for the reason above.
        val raw = bodyOf("private fun showResult(")
        check(raw.length in 500..8000) {
            "extracted ${raw.length} characters of showResult, which means the " +
                "extraction is wrong rather than the code"
        }
        val body = code(raw)

        assertTrue(
            Regex("""(?m)^\s*view\.setTextColor\(""").containsMatchIn(body),
            "showResult paints no colour, so a terminal row is left in the grey it " +
                "counted percentages in",
        )
        assertTrue(
            Regex("""(?m)^\s*view\.alpha\s*=\s*1f""").containsMatchIn(body),
            "showResult no longer undims the row, so the result colour lands at the " +
                "opacity the percentages ran at and \"Failed\" measures 3.28:1 against " +
                "this window, under the 4.5:1 AA asks of 12sp text",
        )
        // One expression covers both halves of the scenario: the call being deleted, and
        // the pack name being dropped from it.
        assertTrue(
            Regex("""(?m)^\s*view\.announceForAccessibility\([^)]*packLabel""")
                .containsMatchIn(body),
            "showResult no longer says which pack finished, so a queue of downloads " +
                "either ends in silence or ends in several unattributed results",
        )
    }

    /**
     * And nothing paints a result beside that function.
     *
     * A predicate rather than a pair of named branches: the defect was a colour set
     * without the opacity that has to go with it, so what keeps it from coming back is
     * that the caller cannot set a colour at all, whatever branch a later result is
     * added to.
     */
    @Test
    fun `handleDownloadState paints no result colour of its own`() {
        val raw = bodyOf("private fun handleDownloadState(")
        check(raw.length in 500..8000) {
            "extracted ${raw.length} characters of handleDownloadState, which means the " +
                "extraction is wrong rather than the code"
        }

        assertFalse(
            code(raw).contains("setTextColor"),
            "handleDownloadState sets a text colour directly, which is how the defect " +
                "was shipped the first time: the colour without the opacity beside it. " +
                "Terminal rows go through showResult, which sets the pair together",
        )
    }

    /**
     * The control for the three extractions above, because a body that quietly ran past
     * its own function would satisfy every assertion in them by finding the token
     * somewhere else, and `bodyOf` here counts braces raw rather than skipping comments.
     *
     * Measured against the real bodies rather than guessed: `override fun onDestroy`
     * begins 6 characters past showSetupError's closing brace, and
     * `private fun handleDownloadState` 6 characters past showResult's. An extraction
     * that overran by one line would already carry the name this refuses, so the control
     * is tight rather than nominal.
     */
    @Test
    fun `each extracted body stops at the declaration that follows it`() {
        assertFalse(
            bodyOf("private fun showSetupError(").contains("override fun onDestroy"),
            "bodyOf ran past showSetupError and swallowed the next declaration, so its " +
                "assertions are really a file-wide search",
        )
        assertFalse(
            bodyOf("private fun showResult(").contains("private fun handleDownloadState"),
            "bodyOf ran past showResult and swallowed the next declaration, so its " +
                "assertions are really a file-wide search",
        )
    }
}
