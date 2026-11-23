package com.vscodroid.storage

import android.content.Context
import android.content.res.Resources
import com.vscodroid.R
import com.vscodroid.filesKeptInAppNotice
import com.vscodroid.uploadIncompleteNotice
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A write-back that gives up has to reach a screen, and nothing in the sync engine has
 * one.
 *
 * Two halves, and the failure this pins is the first one going missing quietly. The
 * engine's seam defaults to a no-op, deliberately: a default that reached for
 * `Looper.getMainLooper()` threw "not mocked" in every existing test that drives a
 * failing write-back. That makes the wiring load-bearing rather than decorative, and an
 * unwired seam is indistinguishable from the silence this was meant to end.
 */
class WriteBackNoticeWiringTest {

    private val activity = File("../../android/app/src/main/kotlin/com/vscodroid/MainActivity.kt")
    private val activityHelpers = File("../../android/app/src/main/kotlin/com/vscodroid/MainActivityHelpers.kt")

    private fun activitySource(): String {
        assertTrue(activity.isFile, "MainActivity.kt is not where this test expects it")
        assertTrue(activityHelpers.isFile, "MainActivityHelpers.kt is not where this test expects it")
        return activity.readText() + "\n" + activityHelpers.readText()
    }

    /**
     * A manager per case, so no case inherits a throttle another case has already moved.
     *
     * A relaxed [Context] is the whole of what construction needs: the constructor takes
     * shared preferences and builds a [SafSyncEngine], and neither is touched by the
     * claim below. [SafMirrorReclamationTest] builds one the same way.
     */
    private lateinit var safManager: SafStorageManager

    @BeforeEach
    fun setUp() {
        safManager = SafStorageManager(mockk<Context>(relaxed = true))
    }

    /**
     * Read from the source rather than driven through the Activity, which needs a device.
     *
     * ⚠️ Its ceiling, measured rather than assumed: wrapping the call in `if (false)`
     * leaves this green. It catches the wiring being deleted, which is the likely
     * regression, and cannot catch it being made unreachable. The behaviour either side
     * of it is covered where it can be: the engine announcing is pinned by
     * InitialSyncWiringTest, and the throttle by the case below.
     */
    @Test
    fun `MainActivity asks to be told when a write-back gives up`() {
        val source = activitySource()

        // Anchored at the start of a line, and for the second pattern the character
        // class is the anchor: nothing between the indent and the string name may be a
        // slash, so a `//` cannot get in front of it. Unanchored, a substring search
        // finds `// safManager.onWriteBackFailed { file ->` as readily as the call
        // itself, and commenting a line out is how a developer disables something, so
        // wiring that is already dead is the one shape these two could not see.
        assertTrue(
            Regex("""(?m)^\s*safManager\.onWriteBackFailed\s*\{""").containsMatchIn(source),
            "nothing wires the write-back notice, so a save that never reached the " +
                "device folder is silent again",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_write_back_failed""").containsMatchIn(source),
            "the notice does not name the string that tells the user what happened",
        )
    }

    /**
     * The same silence a folder at a time, and a seam of its own because it says
     * something else: a folder created in the editor that reached the device short of
     * some of its files, or that stopped at the cap this app imposes rather than at
     * anything the device refused. Unwired, a folder that arrived incomplete is
     * indistinguishable from one that arrived whole, and the user finds out when they
     * open it somewhere else.
     *
     * Both wordings are named, and separately: the capped one exists because telling a
     * person the device refused their folder, when the limit is ours, sends them looking
     * at the wrong machine.
     *
     * Same ceiling and same anchoring as the case above.
     */
    @Test
    fun `MainActivity asks to be told when a folder did not arrive whole`() {
        val source = activitySource()

        assertTrue(
            Regex("""(?m)^\s*safManager\.onUploadIncomplete\s*\{""").containsMatchIn(source),
            "nothing wires the upload notice, so a folder that reached the device with " +
                "files missing looks exactly like one that arrived whole",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_upload_incomplete""").containsMatchIn(source),
            "the notice does not name the string that says how much did not arrive",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_upload_capped""").containsMatchIn(source),
            "the cap this app imposes is not named, so it is announced in the wording " +
                "for a device that refused and the user goes looking at their device",
        )
    }

    /**
     * The files an open keeps inside the app because it cannot tell them from documents deleted on the device. Told
     * of in the sentence for files the device did not get, they read as a failure to send, and saving one again,
     * which is what sends it, was not said; told of in a sentence that says to save again, a folder the device
     * refused, which no save sends, read the same. So it has its own wording, which names a file: one for a single
     * file and a plural for more.
     *
     * Same ceiling and same anchoring as the cases above.
     */
    @Test
    fun `MainActivity names a file an open keeps inside the app`() {
        val source = activitySource()

        assertTrue(
            Regex("""(?m)^\s*safManager\.onFilesKeptInApp\s*\{""").containsMatchIn(source),
            "nothing wires the notice for files kept inside the app, so they are told of as files the device refused",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_file_kept_in_app\b""").containsMatchIn(source),
            "a single kept file is not named in its own wording",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_files_kept_in_app\b""").containsMatchIn(source),
            "more than one kept file has no wording of its own",
        )
    }

    /**
     * The inbound direction, which is the one where the wording matters most and the
     * one a shared seam would have got wrong: these documents are on the device and did
     * not reach the editor, so "the only copy is inside VSCodroid" is the opposite of
     * true for them. Running out of room is its own wording again, because that one the
     * user can act on.
     *
     * The two string names are matched on separate lines rather than twice over one:
     * `\b` refuses the longer name, so the ordinary plural cannot be satisfied by the
     * out-of-room one and the pair stays two checks rather than one counted twice.
     */
    @Test
    fun `MainActivity asks to be told when documents did not reach the editor`() {
        val source = activitySource()

        assertTrue(
            Regex("""(?m)^\s*safManager\.onDocumentsNotCopied\s*\{""").containsMatchIn(source),
            "nothing wires the notice for documents that never arrived, so a folder " +
                "opens missing files and says nothing about it",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_documents_not_copied\b""").containsMatchIn(source),
            "the notice does not name the string that says which way the copy failed",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_documents_not_copied_no_room""").containsMatchIn(source),
            "running out of room is announced in the wording for a provider that " +
                "refused, so the one cause the user can fix is not the one they are told",
        )
    }

    /**
     * One seam, two sentences, each in two versions. A directory is kept for what is
     * inside it and a document is kept for what it is, so the second wording is named
     * here as separately as the capped upload is above: the directory sentence says the
     * thing holds files that never reached the editor, which of a single document is
     * simply false, and a user told that goes looking inside a file.
     */
    @Test
    fun `MainActivity asks to be told when a delete was declined to save the device copy`() {
        val source = activitySource()

        assertTrue(
            Regex("""(?m)^\s*safManager\.onKeptOnDevice\s*\{""").containsMatchIn(source),
            "nothing wires the notice for what was kept on the device, so a delete " +
                "that was declined to save an unread archive says nothing about it",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_directory_kept\b""").containsMatchIn(source),
            "the notice does not name the string that says the device, not the app, " +
                "holds the files",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_document_kept\b""").containsMatchIn(source),
            "a single kept document is announced in the wording for a directory, which " +
                "tells the user their file holds files",
        )
        // And each of the two again for a file the editor had and the user deleted, kept
        // for a change on the device: "the editor never had a copy of it" is false there.
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_document_changed_kept\b""").containsMatchIn(source),
            "a file kept because another app may have changed it is announced as one the " +
                "editor never had, which the user had open a moment ago",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_directory_changed_kept\b""").containsMatchIn(source),
            "a directory kept for such a file is announced as holding files the editor " +
                "never had",
        )
    }

    /**
     * A delete that gave up waiting for another write to the same storage has a sentence of its
     * own. Unwired, it is silent and the file comes back at the next open with nothing said; worded
     * as a refusal, it sends the person to delete in their file manager a file that nothing refused.
     *
     * Same ceiling and same anchoring as the cases above.
     */
    @Test
    fun `MainActivity asks to be told when a delete waited out another write`() {
        val source = activitySource()

        assertTrue(
            Regex("""(?m)^\s*safManager\.onDeleteBusy\s*\{""").containsMatchIn(source),
            "nothing wires the notice for a delete that waited out another write, so the " +
                "file comes back at the next open with nothing said",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_delete_busy\b""").containsMatchIn(source),
            "the notice does not name the string that says the storage was busy",
        )
    }

    /**
     * The replay of what a program changed while a folder opened can fail partway, and what it had not sent then
     * stays inside the app with nothing on screen to say so. Same ceiling and same anchoring as the cases above.
     */
    @Test
    fun `MainActivity asks to be told when the changes made while a folder opened were not all sent`() {
        val source = activitySource()

        assertTrue(
            Regex("""(?m)^\s*safManager\.onOpenChangesUnsent\s*\{""").containsMatchIn(source),
            "nothing wires the notice for a replay that failed, so what it did not send is never mentioned",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_open_changes_unsent\b""").containsMatchIn(source),
            "the notice does not name the string that says the changes made while the folder opened did not all go",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_switch_changes_unsent\b""").containsMatchIn(source),
            "the notice does not name the string for the folder whose watcher a failed switch put back, which " +
                "never opened then",
        )
    }

    /**
     * A save caught as an open put the device's newer copy in place, which could not be kept whole. Unwired, the person
     * is told at most that a copy was kept, or nothing where none could be made, and the editor shows the device's
     * version as if the save had never been. Same ceiling and same anchoring as the cases above.
     */
    @Test
    fun `MainActivity asks to be told when a save caught as a folder opened could not be kept whole`() {
        val source = activitySource()

        assertTrue(
            Regex("""(?m)^\s*safManager\.onHeldSaveIncomplete\s*\{""").containsMatchIn(source),
            "nothing wires the notice for a save that could not be kept whole, so the editor shows the device's version and says nothing",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_held_save_incomplete\b""").containsMatchIn(source),
            "the notice does not name the string that says the save was not kept whole",
        )
    }

    /** The base strings, read from the file AAPT2 compiles, so a sentence is formatted as it ships. */
    private val strings by lazy {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
    }

    /** The text of the string [name], or of its plural item for [quantity]. */
    private fun template(name: String, quantity: String? = null): String {
        val nodes = strings.getElementsByTagName(if (quantity == null) "string" else "plurals")
        val node = (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("name") == name }
        if (quantity == null) return node.textContent
        val items = node.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it) as Element }
            .single { it.getAttribute("quantity") == quantity }.textContent
    }

    /** Resources that format these notices from the base strings, as the platform does in English. */
    private fun englishResources(): Resources = mockk {
        for ((id, name) in listOf(
            R.string.saf_file_kept_in_app to "saf_file_kept_in_app",
            R.string.saf_upload_capped to "saf_upload_capped",
        )) {
            every { getString(id, *anyVararg()) } answers { template(name).format(*(args.last() as Array<*>)) }
        }
        for ((id, name) in listOf(
            R.plurals.saf_files_kept_in_app to "saf_files_kept_in_app",
            R.plurals.saf_upload_incomplete to "saf_upload_incomplete",
        )) {
            every { getQuantityString(id, any(), *anyVararg()) } answers {
                template(name, if (secondArg<Int>() == 1) "one" else "other").format(*(args.last() as Array<*>))
            }
        }
    }

    /**
     * The sentence a kept file is told in, built as it ships: the folder first, the file named,
     * and a count only when there is more than one. Arguments handed in another order than the
     * string's placeholders give a sentence that names the file as the folder, or fail to format.
     */
    @Test
    fun `a file kept inside the app is named in the sentence for its folder`() {
        val resources = englishResources()

        val one = filesKeptInAppNotice(resources, "Docs", 1, "notes.md")
        assertTrue(one.startsWith("Docs: ") && "notes.md" in one, one)
        verify { resources.getString(R.string.saf_file_kept_in_app, "Docs", "notes.md") }

        val three = filesKeptInAppNotice(resources, "Docs", 3, "notes.md")
        assertTrue(three.startsWith("Docs: ") && "notes.md" in three && "3" in three, three)
        verify { resources.getQuantityString(R.plurals.saf_files_kept_in_app, 3, 3, "Docs", "notes.md") }
    }

    @Test
    fun `a folder that did not arrive whole is named with what it lost`() {
        val resources = englishResources()

        val lost = uploadIncompleteNotice(resources, "Docs", 4, capped = false)
        assertTrue(lost.startsWith("Docs: ") && "4" in lost, lost)
        verify { resources.getQuantityString(R.plurals.saf_upload_incomplete, 4, 4, "Docs") }

        val capped = uploadIncompleteNotice(resources, "Docs", 4, capped = true)
        assertTrue(capped.startsWith("Docs ") && "4" !in capped, capped)
        verify { resources.getString(R.string.saf_upload_capped, "Docs") }
    }

    /**
     * The throttle belongs to the manager, so one refusing provider is one notice.
     *
     * Asserted as a rule rather than as a constant: the number is a product decision and
     * may move, but "the first failure speaks and the next one inside the interval does
     * not" is the behaviour.
     */
    @Test
    fun `the first failure is announced and a prompt second one is not`() {
        val interval = SafStorageManager.FAILURE_NOTICE_INTERVAL_MS
        // A reading well past the interval. Toy numbers put the first failure inside
        // the interval of a zero last-announced time and made this assert the opposite
        // of the shipped behaviour; the clause named in `the first failure is said even
        // seconds after boot` is what settles that case now, and it is asserted there
        // rather than smuggled in here. The caller supplies this from the monotonic
        // clock, not the wall clock.
        val now = 1_700_000_000_000L

        assertTrue(
            SafStorageManager.shouldAnnounce(now = now, lastAnnouncedAt = 0),
            "the first failure of a session has to be said",
        )
        assertFalse(
            SafStorageManager.shouldAnnounce(now = now + interval - 1, lastAnnouncedAt = now),
            "a provider that refuses everything would otherwise bury the editor",
        )
        assertTrue(
            SafStorageManager.shouldAnnounce(now = now + interval, lastAnnouncedAt = now),
            "the notice has to come back, or a later failure is silent again",
        )
    }

    /**
     * The first notice of a session is given however recently the device booted.
     *
     * The throttle's clock is milliseconds since boot, not since 1970 (which clock it
     * reads is asked of the manager in SafManagerEngineWiringTest), so "nothing announced
     * yet" stopped being a reading further back than any interval and became one that
     * can sit inside it. A device a few seconds up, with a provider already refusing,
     * would have swallowed the one notice that always has to be given.
     *
     * Negative control, measured: drop the `lastAnnouncedAt == NEVER_ANNOUNCED` clause
     * from `shouldAnnounce` and this goes red while every case above stays green, which
     * is why it is a case of its own.
     */
    @Test
    fun `the first failure is said even seconds after boot`() {
        assertTrue(
            SafStorageManager.shouldAnnounce(
                now = 3_000L,
                lastAnnouncedAt = SafStorageManager.NEVER_ANNOUNCED,
            ),
            "a folder that started failing before the device had been up for one " +
                "interval said nothing at all",
        )
    }

    /**
     * The predicate above says whether a failure is far enough from the last one to be
     * said. It cannot say who says it, and that is the half these four cover.
     *
     * Two threads genuinely reach the throttle: the `saf-writeback` daemon draining the
     * queue, and `Dispatchers.IO` running the write-backs `initialSync` issues itself.
     * With a plain read, compare and assign, both can read the same stale value, both
     * find the interval elapsed and both announce for one burst, which is the wall of
     * toasts the throttle exists to prevent.
     *
     * Driven through the reading rather than through threads, deliberately, and that is
     * a correctness point rather than a convenience one. Racing two callers does not
     * measure this: with both shapes released together from a `CyclicBarrier` on this
     * JDK, the read-compare-assign version announced twice in 0 of 50,000 two-thread
     * trials, 0 of 20,000 eight-thread trials and 0 of 2,000 sixteen-thread ones. The
     * window is one read, a subtraction and one write, and the throttle confines it to
     * the first instant of each interval, so a race test would have called the shape
     * this replaced green and pinned nothing. Handing one caller a reading a previous
     * caller has already consumed is that losing thread's exact situation, and it is
     * decidable.
     */
    @Test
    fun `the first failure of a session claims the notice`() {
        assertTrue(
            safManager.claimAnnouncement(now = 1_700_000_000_000L, last = 0),
            "the first failure of a session has to be said, and nothing has claimed it",
        )
    }

    @Test
    fun `a second caller holding the same stale reading loses the claim`() {
        val now = 1_700_000_000_000L

        assertTrue(safManager.claimAnnouncement(now = now, last = 0), "the first caller wins")
        assertFalse(
            safManager.claimAnnouncement(now = now, last = 0),
            "a caller holding a reading another caller has already consumed announced " +
                "as well, so one burst of failures reaches the user as two toasts and " +
                "the throttle only appears to hold",
        )
    }

    /**
     * The control that keeps the case above from passing for the wrong reason. A claim
     * that refused everything after the first would satisfy it and would also silence
     * every later failure for the rest of the session.
     */
    @Test
    fun `the claim comes back once the interval has passed`() {
        val interval = SafStorageManager.FAILURE_NOTICE_INTERVAL_MS
        val now = 1_700_000_000_000L

        assertTrue(safManager.claimAnnouncement(now = now, last = 0), "the first caller wins")
        assertTrue(
            safManager.claimAnnouncement(now = now + interval, last = now),
            "a failure a full interval later was refused, so a folder that starts " +
                "refusing again an hour on says nothing at all",
        )
    }

    /**
     * And the interval is still consulted. Without this a claim reduced to its
     * `compareAndSet` would pass every case above: the reading moves each time, so the
     * swap always succeeds and the throttle collapses into no throttle.
     */
    @Test
    fun `a claim inside the interval is refused even with nothing to race`() {
        val interval = SafStorageManager.FAILURE_NOTICE_INTERVAL_MS
        val now = 1_700_000_000_000L

        assertTrue(safManager.claimAnnouncement(now = now, last = 0), "the first caller wins")
        assertFalse(
            safManager.claimAnnouncement(now = now + interval - 1, last = now),
            "a failure inside the interval was announced, so a provider that refuses " +
                "everything buries the editor under its own error",
        )
    }
}
