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

    private val queue = listOf("toolchain_go", "toolchain_ruby", "toolchain_java")

    @Test
    fun `the pack being downloaded advances the queue`() {
        assertTrue(isCurrentDownload("toolchain_ruby", queue, 1))
    }

    @Test
    fun `a pack still queued behind the current one does not`() {
        // The skip that mattered: java reporting anything while ruby is
        // downloading would have stepped over ruby and left it uninstalled,
        // with its row still reading "installing".
        assertFalse(isCurrentDownload("toolchain_java", queue, 1))
    }

    @Test
    fun `a pack the queue has already passed does not`() {
        // This is also how a repeat is handled. The first report moves the
        // index, so a second report for the same pack is no longer current --
        // no separate bookkeeping needed.
        assertFalse(isCurrentDownload("toolchain_go", queue, 1))
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

    private fun handleDownloadStateBody(): String {
        check(source.isFile) {
            "SplashActivity.kt not found at ${source.absolutePath} — this test would " +
                "otherwise pass by looking at nothing"
        }
        val text = source.readText()
        val declaration = text.indexOf("private fun handleDownloadState(")
        check(declaration >= 0) {
            "handleDownloadState is gone from SplashActivity.kt; this guard names it, so " +
                "renaming it means deciding again where the two calls are pinned"
        }

        val open = text.indexOf('{', declaration)
        var depth = 0
        var i = open
        while (i < text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}') depth--
            if (depth == 0) break
            i++
        }
        check(depth == 0) { "no closing brace found for handleDownloadState" }
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
        val raw = handleDownloadStateBody()

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
}
