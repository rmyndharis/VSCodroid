package com.vscodroid.webview

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** How a download ended, which is the one thing the user has to be told. */
enum class DownloadOutcome { SAVED, CANCELLED, FAILED }

/**
 * How many download names the page may have outstanding.
 *
 * A bound rather than a capacity. One click names one URL and the name is taken
 * back out the moment the download for it starts, so the steady state is zero
 * or one; anything above that is a click the platform decided not to turn into
 * a download, and those must not accumulate.
 */
private const val MAX_NAMES = 8

/**
 * How many downloads may wait for their turn at the picker.
 *
 * There is one picker and it takes the user seconds to answer, so downloads
 * started while it is open queue behind it. A bound rather than a capacity:
 * every waiting download is holding a file's bytes in the page, and the page
 * decides how many downloads to start.
 *
 * The page-side hold on a download's bytes is sized from this; see
 * `MainActivity.injectDownloadCapture`.
 */
internal const val MAX_QUEUED = 8

/**
 * How long the provider thread outlives the last call made on it.
 *
 * Long enough that a burst of downloads, or a picker the user takes a while to
 * answer, is served by one thread rather than a new one each time; short enough
 * that a screen the user has left behind is not still holding one.
 */
internal const val PROVIDER_IDLE_MS = 30_000L

/**
 * The thread provider calls nobody is waiting for are made on, and the way it
 * ends.
 *
 * It ends by running out of work, which is the only ending this class can be
 * given. Being told to stop is the obvious alternative and it is wrong here:
 * every teardown path defers to a thread already inside the provider
 * ([DownloadCoordinator.closeAndDiscard]), and that thread comes back through
 * the same hand-off on its way out, after `onDestroy` has returned. An executor
 * shut down by `onDestroy` answers that late hand-off with
 * `RejectedExecutionException`, thrown on the bridge or main thread, and the
 * user's part-written document is left in their folder, which is the one thing
 * the hand-off exists to remove. So the core thread is allowed to time out
 * instead: no submission is ever refused, and nothing is parked once the
 * downloads stop.
 *
 * That timeout is also standing in for something `Executors` used to do here.
 * `newSingleThreadExecutor` returns a wrapper that shuts its pool down when it
 * becomes unreachable (a finalizer up to API 35, a `Cleaner` from 36), which is
 * what kept an abandoned coordinator's thread from lasting for ever, at the cost
 * of waiting for a collection that may not come. A raw [ThreadPoolExecutor] has
 * no such wrapper, and needs none: once the queue is empty for [idleMs] the pool
 * holds no thread at all, so there is nothing left to reclaim.
 *
 * One thread and a FIFO queue, so the ordering the callers rely on survives the
 * thread itself: a close still precedes the discard queued behind it, and a task
 * handed over after the thread has gone starts a new one and still runs behind
 * whatever is already waiting.
 */
internal fun providerWorkExecutor(idleMs: Long = PROVIDER_IDLE_MS): Executor =
    ThreadPoolExecutor(1, 1, idleMs, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "download-provider").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

/**
 * A document as a descriptor opened read-only shows it, before this download writes to it.
 *
 * Each field is null when it cannot be read: [length] for anything that is not a regular file,
 * a pipe or a socket, which cannot be measured and is treated as the user's; [occupied] and
 * [free] where the provider or its file system will not say.
 */
class DocumentMeasure(
    /** Its length in bytes (`st_size`). */
    val length: Long?,
    /** The bytes its blocks occupy (`st_blocks` times 512), below [length] for a sparse file. */
    val occupied: Long? = null,
    /** The bytes its file system has free for this app (`f_bavail` times `f_frsize`). */
    val free: Long? = null,
)

/**
 * A document opened for writing from its first byte.
 *
 * [length] and [truncate] are there when the provider handed back a regular file, and null when
 * it handed back a pipe: a pipe has no length to read or cut, and a provider that serves one
 * takes what arrives through it as the whole document.
 */
class OpenDocument(
    val stream: OutputStream,
    val length: (() -> Long)? = null,
    val truncate: ((Long) -> Unit)? = null,
)

/**
 * The app low-storage threshold that a staged download leaves available.
 *
 * A save over an existing document holds the whole download there until its last byte arrives,
 * so a large one could take the internal storage that the editor's own state and every other app
 * write to, and the save would fail anyway once nothing was left. With the floor it fails before
 * that, with the document untouched. Saves to a new name never stage, and are not bounded here.
 */
internal const val STAGE_FLOOR_BYTES = StorageManager.LOW_STORAGE_BYTES

/** The size of each read when a staged download is copied into its document. */
private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * Makes [document] hold exactly the [written] bytes by cutting whatever lies beyond them, or
 * throws so that the download is reported as not saved.
 *
 * The platform's providers open mode "w" without truncating, and any provider is free to ignore
 * the "t" of "wt", so a download shorter than the file it replaces would otherwise keep that
 * file's tail and still be reported as saved. It never grows a document: one shorter than what
 * was written has been cut by another writer, and growing it back would report zeros as the
 * download. A length that does not match at the check fails the save; another writer's change
 * of the same length, or one that lands after the last check, is not seen. A pipe has nothing to
 * measure, and the provider behind it takes what arrived as the document.
 */
private fun settle(document: OpenDocument, written: Long) {
    val length = document.length ?: return
    val before = length()
    if (before == written) return
    val truncate = document.truncate
    if (before < written || truncate == null) {
        throw IOException("the document holds $before bytes where $written were written")
    }
    truncate(written)
    val now = length()
    if (now != written) throw IOException("the document holds $now bytes where $written were written")
}

/**
 * Everything [DownloadCoordinator] needs from the Activity it runs inside.
 *
 * An interface rather than the pile of lambdas the chrome client uses, because
 * these are one collaborator seen from several sides and a test wants to
 * watch them together: the ordering between opening a document, writing to it
 * and discarding it is most of what can go wrong here.
 */
interface DownloadHost {
    /**
     * Starts the create-document picker for [requestId], offering [fileName].
     *
     * The answer comes back at [DownloadCoordinator.onDestinationChosen]
     * carrying the same [requestId], or at
     * [DownloadCoordinator.onDestinationUnavailable] when no picker opened at
     * all. Nothing is returned from here because a download that waited its
     * turn is started from whatever thread finished the one before it, while a
     * picker can only be launched from the main thread.
     */
    fun askDestination(requestId: String, fileName: String)

    /**
     * Measures the document the user chose through a descriptor opened read-only ("r"), so that
     * looking cannot truncate it, or answers null when it will not open for reading. Asked before
     * the document is opened for writing: only a regular file measured as empty is written
     * straight into, and only such a document may be removed.
     */
    fun measureDestination(destination: Uri): DocumentMeasure?

    /**
     * The display name of the document the user chose in the picker, or null when the provider
     * gives none or cannot be read.
     */
    fun destinationName(destination: Uri): String? = null

    /**
     * Opens the document the user chose for writing from its first byte, in [mode] ("w" for a
     * document measured empty, "wt" and then "w" for one the user already had), or answers null
     * when it will not open. A provider that does not accept [mode] throws.
     */
    fun openDestination(destination: Uri, mode: String): OpenDocument?

    /** Removes a document this download created and did not fill. */
    fun discardDestination(destination: Uri)

    /** Asks the page to read [url] and push its bytes back under [requestId]. */
    fun requestBytes(requestId: String, url: String)

    /**
     * Tells the page it may let go of the bytes it is holding for [url].
     *
     * The page pins a download's blob from the click that started it, because
     * the user has a picker to answer before anyone asks for the bytes, and the
     * hold is sized for a whole queue of pickers. A download that reaches its
     * end without ever being read leaves that hold to expire on its own, so the
     * file stays resident in the page for minutes after the user was told it
     * was not saved, and a multi-select of files the queue refused holds all of
     * them at once. Reading the bytes releases the hold by itself, so this is
     * only ever the downloads that were never read.
     *
     * Not called from [DownloadCoordinator.onPageGone]: the document that took
     * the hold is the one that has gone, so there is nobody left to tell and
     * nothing left holding anything.
     */
    fun releaseBytes(url: String)

    /** Tells the user how a download ended. [detail] is for the log, not the screen. */
    fun report(outcome: DownloadOutcome, fileName: String, detail: String?)

    /**
     * Whether the page can read the bytes behind [url], which is the only way a download reaches
     * its document ([requestBytes]). One it cannot read is refused before the picker opens, so the
     * person is never asked to name a file that is then not saved. The default refuses nothing;
     * the editor answers with [pageCanReadDownload].
     */
    fun pageCanRead(url: String): Boolean = true
}

/**
 * Whether the workbench page served on [port] can read the bytes of a download from [url].
 *
 * The page reads them itself (`readerFor` in `MainActivity.injectDownloadCapture`): a blob it
 * made off the object it holds, anything else with `fetch`, under a `connect-src` of
 * `'self' ws: wss: https:`. So it reads a blob of its own origin, a file of its own origin, and
 * a file of the resource authority, which the interceptor answers for this origin with CORS
 * (`VSCodroidWebViewClient.resourceHeaders`). It cannot read a blob another frame made (a
 * webview's, the Simple Browser's), because the policy does not list `blob:` and the object is
 * not the page's; nor a `data:` URL; nor a file on another origin, which plain `http:` the
 * policy refuses and `https:` only a CORS answer could admit.
 *
 * Matched as text and not parsed: the platform hands the URL over canonical, and a parser that
 * refuses a character Chromium leaves unescaped in a query would refuse a download that works.
 *
 * A remote `https:` file whose server allows any origin is refused although the page could read
 * it: the URL cannot say so, and only a request could find out.
 */
internal fun pageCanReadDownload(url: String, port: Int): Boolean {
    if (port <= 0) return false
    val origin = "http://127.0.0.1:$port/"
    if (url.startsWith(origin) || url.startsWith("blob:$origin")) return true
    if (!url.startsWith("https://")) return false
    val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.endsWith(".$DOWNLOAD_RESOURCE_AUTHORITY") && '@' !in authority && ':' !in authority
}

/** Where extension files are served from to webviews; see [pageCanReadDownload]. */
private const val DOWNLOAD_RESOURCE_AUTHORITY = "vscode-resource.vscode-cdn.net"

/**
 * How long after a refused download the next refusal is kept off the screen. A page that starts
 * downloads in a loop would otherwise cover the editor in notices, one each.
 */
internal const val UNREADABLE_NOTICE_MS = 30_000L

/**
 * Runs one download at a time, from the platform's download hook to a file the
 * user chose.
 *
 * Every download the editor starts arrives here, and the shape of the job is
 * fixed by two things that cannot be changed from this side. The first is that
 * the destination is a SAF document the user picks *after* the download has
 * already begun, so there is a gap between "a download started" and "there is
 * somewhere to put it" that has to be held open. The second is that the bytes
 * do not come from this process: the editor hands the platform a `blob:` URL
 * for anything it could read into memory, and a blob belongs to the page. So
 * the page is asked for the bytes and pushes them back in pieces, which is why
 * this is a state machine and not a copy loop.
 *
 * The rule the whole class exists to keep is that **a download that did not
 * happen never looks like one that did**. A partial file left behind with the
 * name the user chose is indistinguishable from a complete one until they open
 * it, so every path that ends badly removes the document it created, when that
 * document can be told from one the user had (see below), and says so. Reporting
 * is not optional on any path either, with one exception: a download the page
 * cannot read is refused before it has a picker, and a refusal within
 * [UNREADABLE_NOTICE_MS] of the last one said is logged rather than shown, so
 * that a page starting downloads in a loop cannot bury the editor. The defect
 * this replaces was a menu entry that did nothing and said nothing, and a fix
 * that fails quietly is the same defect wearing more code.
 *
 * The other half of that rule is that **a file the user already had is not
 * written to before every byte of the download has arrived**. The picker answers
 * "Overwrite" with the existing document itself and nothing in the answer says
 * which kind it is, so the document is measured once through a read-only
 * descriptor before anything opens it for writing. Only a regular file measured
 * as empty is written straight into, and only such a document is ever removed.
 * That cannot tell a document the picker has just created from an empty file the
 * user chose to overwrite, so an empty file that was theirs is removed too when its
 * download fails; and a document the provider will not measure, a new one among
 * them, is left in the folder, empty, when its download fails.
 * Anything else, including a document that cannot be measured, is the user's: it
 * is filled from a copy of the bytes held in app storage after the last of them
 * has arrived, and is cut to the download's length, because the platform's
 * providers open mode "w" without truncating and a shorter download would
 * otherwise keep the old file's tail. The copy starts only when the
 * destination's file system reports room for it; a copy that fails after it
 * started leaves the document partly written, and says so.
 *
 * One at a time is a rule about the picker, not a simplification. Downloading a
 * multi-select starts one download per file at once, and only one picker can be
 * open and answerable: a second one launched over the first produces two
 * results that nothing can tell apart, and the file the user named for one gets
 * the bytes of the other. So the rest queue, keep their own names, and get
 * their own picker in turn.
 *
 * Thread confinement is not available. [onDownloadStart] and
 * [onDestinationChosen] arrive on the UI thread, while [onBytes] and
 * [onComplete] arrive on the WebView's bridge thread, so the state is guarded
 * outright rather than by a convention that has no way to hold.
 */
class DownloadCoordinator(
    private val host: DownloadHost,
    /**
     * The one thread the provider is spoken to from when nobody is waiting for
     * the answer.
     *
     * Closing a `content://` stream commits its bytes and deleting the document
     * behind it is a second trip into the same provider, and neither has a
     * timeout: a cloud or MTP provider takes as long as it takes. Every teardown
     * arrives on the UI thread ([onPageGone] from `onDestroy`, from
     * `onPageFinished` and from `recreateWebView`) and the picker's answer does
     * too, so those two calls cannot be made where they are decided, and the
     * monitor cannot be held across them either or the next UI-thread caller
     * queues behind the provider as well.
     *
     * Single, so the close of a document still precedes the delete of it.
     * Daemon, so it never holds the process up, and one per coordinator, which
     * is one per Activity. What an Activity leaves behind is therefore a thread
     * with no work and no queue, and [providerWorkExecutor] is where that thread
     * ends: it is not parked for the life of the process, nor until the
     * coordinator is collected, but until the work stops for [PROVIDER_IDLE_MS].
     * Nothing here shuts it down, and that function says why not.
     *
     * A constructor argument rather than a private field so that a test can
     * drive it: with the work handed to a thread this class owned privately,
     * "the caller did not wait" and "the work never happened" look the same
     * from outside.
     */
    private val providerWork: Executor = providerWorkExecutor(),
    /**
     * Where the answer to an off-thread open is brought back to.
     *
     * The picker's answer arrives on the main thread and everything it decides
     * used to be decided there, the open included. Only one of the host calls
     * that decision makes still needs that thread: [DownloadHost.requestBytes]
     * reaches the page through `WebView.evaluateJavascript`, which refuses every
     * thread but the one the WebView was made on, while `askDestination`,
     * `releaseBytes` and `report` hop for themselves. So the open goes to
     * [providerWork] and the decision comes back here, rather than following the
     * open onto a thread the page cannot be spoken to from.
     *
     * A constructor argument for the reason [providerWork] is: run inline, "the
     * caller did not wait" and "the work never happened" look the same from
     * outside. The default builds its handler when it is first used rather than
     * when this class is, so constructing a coordinator still costs nothing and
     * needs no looper.
     */
    private val mainThread: Executor = Executor { work -> Handler(Looper.getMainLooper()).post(work) },
    /**
     * Where the bytes of a download saved over an existing document wait until the last of
     * them has arrived. Asked for on the provider thread, when a download needs it, so an
     * Activity can pass its cache directory before it has one. Null is the platform's
     * temporary directory.
     */
    private val stagingDir: () -> File? = { null },
    /** The free space on the staging volume, or null if unknown, checked before each piece. */
    private val stagingRoom: () -> Long? = { null },
    /** A monotonic clock in milliseconds, which spaces out the notices of refused downloads. */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    private val tag = "DownloadCoordinator"

    /**
     * The download being worked on, or null.
     *
     * The page is told which request it is answering and every answer is
     * checked against this one, so bytes belonging to a download that is
     * already over are dropped instead of being written into the file the user
     * is waiting for now. Without the check, a download that failed while the
     * page was still reading it would spill the rest of its file into the next
     * one.
     */
    private var pending: Pending? = null

    /**
     * Downloads started while the picker was busy, in the order they arrived.
     *
     * Multi-select download starts one download per file, and there is one
     * picker: two of them open at once produce two results that cannot be told
     * apart from one another, and the file the user named for one is filled
     * with the bytes of the other. So the later ones wait here and are offered
     * their own picker, under their own name, when their turn comes.
     */
    private val waiting = ArrayDeque<Pending>()

    /**
     * The request the picker on screen belongs to, or null when none is open.
     *
     * Kept apart from [pending] because the two stop agreeing exactly when it
     * matters. A picker outlives the download that opened it: the renderer can
     * die under it, and the request is then dropped while the picker is still
     * on screen. Its result still arrives, having already created a document,
     * and the id is what says that document belongs to nobody and has to go.
     * It is also what keeps a second picker from being opened over the first.
     */
    private var awaitingPicker: String? = null

    private var requestCounter = 0L

    /** When a refused download was last said on screen ([UNREADABLE_NOTICE_MS]), or null. */
    private var unreadableNoticeAt: Long? = null

    /**
     * Names the page has reported for URLs it is about to download.
     *
     * Filled by [onDownloadNamed], which the page calls from the anchor click
     * itself, before the platform has been asked for anything. That ordering is
     * the whole reason this is a map rather than a question asked when the
     * download arrives: a bridge call blocks the page until it returns, so by
     * the time the click reaches the platform the name is already here, and
     * [onDownloadStart] stays synchronous. Asking the page afterwards would put
     * a callback between a download starting and the picker opening, and a
     * callback that does not arrive is a download that does nothing, which is
     * the failure being fixed.
     *
     * Bounded and insertion-ordered, so a page that names downloads nobody ever
     * starts loses its oldest entries instead of growing for the life of the
     * document. Ordinary use holds one.
     */
    private val reportedNames = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>?) = size > MAX_NAMES
    }

    private class Pending(
        val id: String,
        val url: String,
        val fileName: String,
        var chosenName: String? = null,
        var destination: Uri? = null,
        var sink: Sink? = null,
        /**
         * Whether [destination] was measured empty before this download opened it for writing,
         * which makes it the one kind a download that ends badly may remove. Settled on the
         * provider thread before anything is written, and false until then.
         */
        var owned: Boolean = false,
        /**
         * Whether a thread is inside the open, a `write` or the `close` of this
         * download's document right now, outside the monitor.
         *
         * The claim, and the whole of the exclusion. The document comes from
         * `contentResolver.openFileDescriptor`, and neither that call nor a stream
         * to a cloud or MTP provider has any timeout, so none of the three can be
         * made while holding the monitor: every teardown here arrives on the UI
         * thread (`onDestroy`, `onPageFinished`, `recreateWebView`) and would
         * queue behind a provider free to take as long as it likes. What the
         * monitor still guarantees is that this is set before the call and
         * cleared after it, so no second writer starts and no teardown closes or
         * deletes the document underneath one: the download is claimed, not
         * unguarded.
         *
         * The open is claimed for the same reason the write is, and for one of
         * its own: a teardown that finds the download unclaimed deletes the
         * document, and a delete that overtakes the open it raced leaves the
         * provider holding a document created after it.
         */
        var busy: Boolean = false,
        /**
         * Whether [onComplete] has already claimed this download to commit its sink.
         *
         * Once set, late or duplicate completion and chunk calls from the page are
         * ignored so [finishClaimed] produces the single terminal outcome.
         */
        var finishing: Boolean = false,
        /**
         * Whether this stands for a picker answer that belongs to no download in flight, so that
         * what is known of it is the document the picker made or returned and nothing else.
         */
        val stray: Boolean = false,
    )

    /**
     * Where a download's bytes go while they arrive, and how they become the document.
     *
     * [finish] makes what was written the document's whole content, or throws. [abandon] lets go
     * without making anything final, and is all a download that ends badly does to its sink.
     */
    private abstract class Sink {
        /** Bytes written so far. */
        var written = 0L
            protected set

        abstract fun write(bytes: ByteArray)

        abstract fun finish()

        abstract fun abandon()
    }

    /**
     * Writes straight into a document measured empty, which is what the picker gives a new name.
     * A download that ends badly loses nothing by having started, and the document is removed as
     * it always was.
     */
    private class DirectSink(private val document: OpenDocument) : Sink() {
        override fun write(bytes: ByteArray) {
            document.stream.write(bytes)
            written += bytes.size
        }

        override fun finish() {
            document.stream.use { stream ->
                stream.flush()
                settle(document, written)
            }
        }

        override fun abandon() = document.stream.close()
    }

    /**
     * Holds the bytes of a download saved over a document the user already had in app storage
     * until the last of them has arrived, and only then opens the document and fills it. A
     * download that ends before that leaves the document exactly as it was, and so does one its
     * destination has no room for ([checkRoom]). A copy that fails once it has started leaves
     * the document partly written and is reported as failed: a document URI offers no atomic
     * replace, and the user confirmed "Overwrite".
     *
     * The stage file is unlinked as soon as it is open, so its bytes live as long as this
     * descriptor and no longer: a download that fails, and a process that dies during one, leave
     * nothing behind in app storage.
     */
    private inner class StagedSink(private val destination: Uri) : Sink() {
        private val stage: RandomAccessFile

        init {
            val file = File.createTempFile("download", ".part", stagingDir())
            try {
                stage = RandomAccessFile(file, "rw")
            } finally {
                if (!file.delete()) Logger.w(tag, "A download stage could not be unlinked")
            }
        }

        override fun write(bytes: ByteArray) {
            val room = stagingRoom()
            if (room != null && room - bytes.size < STAGE_FLOOR_BYTES) {
                throw IOException("app storage has $room bytes free, and staging stops at $STAGE_FLOOR_BYTES")
            }
            stage.write(bytes)
            written += bytes.size
        }

        override fun finish() {
            try {
                checkRoom(destination, written)
                val document = openForWriting(destination)
                    ?: throw IOException("the chosen document could not be opened")
                document.stream.use { out ->
                    stage.seek(0)
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var left = written
                    while (left > 0) {
                        val read = stage.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (read < 0) throw IOException("the stage ended $left bytes early")
                        out.write(buffer, 0, read)
                        left -= read
                    }
                    out.flush()
                    settle(document, written)
                }
            } finally {
                stage.close()
            }
        }

        override fun abandon() = stage.close()
    }

    /**
     * [DownloadHost.measureDestination], or null when the provider threw: a document
     * nothing can be learned about is one nothing may remove.
     */
    private fun measure(destination: Uri): DocumentMeasure? =
        try {
            host.measureDestination(destination)
        } catch (e: Exception) {
            // The class and not the exception: its message names the document the page chose to save to.
            Logger.w(tag, "Could not measure the chosen document: ${e.javaClass.simpleName}")
            null
        }

    /**
     * Throws, before [destination] is opened for writing, when its file system reports less room
     * than replacing it with [needed] bytes takes. What the replace gives back is counted only
     * as far as the document's blocks hold it ([DocumentMeasure.occupied], at most its length),
     * so a sparse file earns what it occupies and no more, and blocks nobody reports earn
     * nothing.
     *
     * An estimate taken a moment before the copy, not a reservation: another writer can still
     * take the room. Where the room cannot be measured (a pipe, a document that will not open for
     * reading, a file system that will not say) the replace goes ahead as it always did.
     */
    private fun checkRoom(destination: Uri, needed: Long) {
        val measure = measure(destination) ?: return
        val free = measure.free ?: return
        val credit = minOf(measure.occupied ?: 0L, measure.length ?: 0L)
        if (free + credit < needed) {
            throw IOException("the destination has $free bytes free and $credit to give back where $needed are needed")
        }
    }

    /**
     * Opens a document the user already had to be replaced: "wt", which asks the provider to drop
     * what it holds, and once more with "w" when that open throws IllegalArgumentException,
     * UnsupportedOperationException or FileNotFoundException, whatever the cause. A provider that
     * refuses the mode answers with any of the three, and FileNotFoundException is also how every
     * other failed open arrives, so nothing tells a refused mode apart; the retry costs one more
     * open, and the copy starts only if it succeeds. Either way [settle] cuts the result to
     * length, so a provider that ignores the "t" still ends up holding the download alone.
     */
    private fun openForWriting(destination: Uri): OpenDocument? =
        try {
            host.openDestination(destination, "wt")
        } catch (e: Exception) {
            if (e !is IllegalArgumentException &&
                e !is UnsupportedOperationException &&
                e !is FileNotFoundException
            ) throw e
            Logger.d(tag, "Opening the chosen document with wt failed (${e.javaClass.simpleName}); trying once more with w")
            host.openDestination(destination, "w")
        }

    /**
     * The page is about to download [url] under [fileName], which is the
     * `download` attribute of the anchor it is clicking.
     *
     * Recorded rather than acted on. The platform decides whether a click
     * becomes a download, and this runs before it has, so the name is put
     * somewhere [onDownloadStart] can find it and nothing else happens.
     *
     * The name is the page's to write, and every statement in this class that
     * prints one goes through `redactToken` first for that reason. They are all
     * `Logger.w`, which is not gated on a debuggable build and therefore ships,
     * and the page on the other side of the bridge is the workbench, which holds
     * the connection token. `MainActivity`'s `DownloadHost.report` redacts the
     * same value where it reports the outcome; these three sites hold it too, so
     * redacting only there left the value in logcat by another route.
     */
    @Synchronized
    fun onDownloadNamed(url: String, fileName: String) {
        reportedNames[url] = fileName
    }

    /**
     * A download has started in the page. Works out what to call it and asks
     * the user where to put it, or queues it when the picker is busy, or
     * refuses it when the page cannot read it ([DownloadHost.pageCanRead]).
     */
    @Synchronized
    fun onDownloadStart(url: String, contentDisposition: String?) {
        // Consumed, not read. The page reports a name per click, so leaving it
        // behind would let a later download of the same URL that the page did
        // not name inherit this one's filename.
        val fileName = downloadFileName(url, contentDisposition, reportedNames.remove(url))
        if (!host.pageCanRead(url)) {
            refuseUnreadable(fileName)
            return
        }
        if (url.startsWith("blob:") &&
            (pending?.url == url || waiting.any { it.url == url })
        ) {
            // The page has one hold for this URL, still owned by the earlier request.
            host.report(DownloadOutcome.FAILED, fileName, "this blob download is already in progress")
            return
        }
        requestCounter += 1
        val request = Pending("dl-$requestCounter", url, fileName)

        if (pending != null || awaitingPicker != null) {
            if (waiting.size >= MAX_QUEUED) {
                // Refused rather than dropped from the other end: the downloads
                // already waiting were asked for first, and a queue that
                // silently forgets its tail is the failure this class exists to
                // rule out.
                Logger.w(
                    tag,
                    "Refusing ${redactToken(fileName)}; " +
                        "${waiting.size} downloads already waiting"
                )
                host.report(DownloadOutcome.FAILED, fileName, "too many downloads at once")
                host.releaseBytes(request.url)
                return
            }
            waiting.addLast(request)
            return
        }
        start(request)
    }

    /**
     * Turns away a download the page cannot read, before any picker or queue, and says so once per
     * [UNREADABLE_NOTICE_MS]. The URL stays out of the log: a `data:` URL is the file itself.
     */
    private fun refuseUnreadable(fileName: String) {
        val now = monotonicMs()
        val last = unreadableNoticeAt
        if (last != null && now - last < UNREADABLE_NOTICE_MS) {
            Logger.d(tag, "Refusing ${redactToken(fileName)} without a notice: the editor page cannot read it")
            return
        }
        unreadableNoticeAt = now
        host.report(DownloadOutcome.FAILED, fileName, "the editor page cannot read this download")
    }

    /** Hands [request] the picker. Only ever called with nothing else holding it. */
    private fun start(request: Pending) {
        pending = request
        awaitingPicker = request.id
        host.askDestination(request.id, request.fileName)
    }

    /**
     * Gives the picker to the next download waiting for it, if it is free.
     *
     * Called from every path that ends a download, including the ones that end
     * it badly. A download that fails must not take the queue behind it down,
     * and a queue that stops being drained is a page waiting on files that will
     * never be asked for.
     */
    private fun startNextIfIdle() {
        if (pending != null || awaitingPicker != null) return
        start(waiting.removeFirstOrNull() ?: return)
    }

    /**
     * No picker opened for [requestId], so no result will ever arrive for it.
     *
     * The user asked for a file and has to hear that they are not getting one,
     * from here, because nothing downstream will run.
     */
    @Synchronized
    fun onDestinationUnavailable(requestId: String) {
        if (awaitingPicker == requestId) awaitingPicker = null
        val request = live(requestId)
        if (request == null) {
            startNextIfIdle()
            return
        }
        Logger.w(tag, "No create-document picker started for ${redactToken(request.fileName)}")
        fail(request, "no picker")
    }

    /**
     * The create-document picker answered [requestId]. [destination] is null
     * when the user backed out.
     *
     * The id is checked rather than assumed, and a result that does not match
     * the download waiting for one is never written to. When the user typed a
     * new name, the picker created the file the moment they confirmed it, so a
     * result nobody is waiting on has put an empty file in their folder wearing
     * the name of a file they will not get, and that file is removed. When they
     * chose a file they already had and confirmed "Overwrite", the result is
     * that file, and it is left alone unless it was empty, which loses nothing.
     * The same measurement settles both, live or not; see [takeDestination].
     *
     * Cancelling is the ordinary case and it is deliberately not silent. It is
     * also the cheapest to get right: the picker creates nothing until the user
     * confirms, so there is no document to remove and the only work is letting
     * go of the request.
     */
    @Synchronized
    fun onDestinationChosen(requestId: String?, destination: Uri?) {
        if (requestId != null && requestId == awaitingPicker) awaitingPicker = null
        val request = pending?.takeIf { it.id == requestId }
        if (request == null) {
            Logger.w(tag, "Destination for $requestId belongs to no download in flight")
            // Settled as a live answer is, and then ended at once: nothing is
            // written, and the document goes only if it measures empty.
            destination?.let { takeDestination(Pending(requestId ?: "", "", "", stray = true), it, open = false) }
            startNextIfIdle()
            return
        }
        if (destination == null) {
            pending = null
            host.report(DownloadOutcome.CANCELLED, request.fileName, null)
            host.releaseBytes(request.url)
            startNextIfIdle()
            return
        }
        takeDestination(request, destination, open = true)
    }

    /**
     * Records [destination] as [request]'s and settles, on the provider thread
     * and before anything opens it for writing, the two things everything after
     * depends on: whether it measures empty ([measure]), which is the only kind a
     * download that ends badly may remove, and, when [open], where the bytes go.
     * An empty document is written directly; any other is written from a copy held
     * in app storage once the last byte has arrived ([StagedSink]).
     *
     * Called holding the monitor.
     */
    private fun takeDestination(request: Pending, destination: Uri, open: Boolean) {
        // Recorded before the document is opened, so a failure to open still has
        // somewhere to point. A document the picker created is already in the
        // user's chosen folder by now, so an unopenable one is an empty file
        // wearing the name of the file they wanted.
        request.destination = destination
        // Claimed here rather than on the provider thread, so that no teardown
        // can slip between deciding to open the document and the claim that
        // makes it wait. See [Pending.busy].
        request.busy = true
        // Handed over rather than made here. This is the first trip into the
        // provider and the last one that was still made from the UI thread under
        // the monitor: opening a document cold-starts the provider's process and
        // can go to a network, with no timeout on either, and this runs in the
        // activity-result callback, so a slow provider froze the editor from the
        // moment the picker closed and past five seconds it was an ANR. The
        // close and the discard were moved off for exactly this reason; see
        // [providerWork], which is single, so an open still queues behind the
        // close of the document before it.
        providerWork.execute {
        // Measured before any open for writing: a provider that truncates on
            // that open would otherwise make every document look empty.
            val owned = measure(destination)?.length == 0L
            val chosenName = if (!open && !request.stray) null else try {
                host.destinationName(destination)?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Logger.d(tag, "Could not read back the chosen document name (${e.javaClass.simpleName})")
                null
            }
            val sink = if (!open) null else try {
                // "w", as a new name was always opened: an empty document has nothing for
                // a "t" to drop, and a provider that refuses "wt" must not cost it its save.
                if (owned) host.openDestination(destination, "w")?.let { DirectSink(it) } else StagedSink(destination)
            } catch (e: Exception) {
                // Everything the provider can throw, not only IOException.
                // Opening the document is a binder call into another app, and
                // one that was force-stopped, updated or had its grant revoked
                // between the picker answering and this line answers with
                // SecurityException or IllegalArgumentException instead. Nothing
                // above this catches: it would reach the uncaught handler on the
                // provider thread and take the process with it, and the download
                // would keep its claim for ever on the way out.
                // The class and not the exception, for the reason [measure] gives.
                Logger.w(tag, "Could not open the chosen document: ${e.javaClass.simpleName}")
                null
            }
            mainThread.execute { onDestinationOpened(request, sink, owned, chosenName) }
        }
    }

    /**
     * The provider answered for [request]: [owned] says whether the picker
     * created the document for it, and [sink] is where its bytes go, null when
     * the document would not open or no bytes are coming.
     *
     * Back on the thread the picker's answer arrived on, because [mainThread]
     * says why, and back under the monitor, because the state this settles is
     * the same state every other answer settles.
     */
    @Synchronized
    private fun onDestinationOpened(request: Pending, sink: Sink?, owned: Boolean, chosenName: String?) {
        request.busy = false
        request.owned = owned
        if (chosenName != null) request.chosenName = chosenName
        // Recorded before the test below, so that a download ended while its
        // document was opening hands [closeAndDiscard] a sink to let go of rather
        // than leaving one open on a document nobody will ever read back.
        request.sink = sink
        if (pending !== request) {
            // Ended while the provider was still thinking, by a teardown that
            // found this claimed and left the document to the thread inside it.
            // This is that thread's way out; `pending` no longer names the
            // request, exactly as it does not for a write that outlived one.
            closeAndDiscard(request)
            // A picker answer for a download that was already gone, as when the screen was made
            // again while the picker was up: the person chose a name and confirmed it, and gets
            // no file. A teardown that ended its own download says nothing, as always, because
            // the person is watching the page that went; here nothing else tells them.
            if (request.stray && !chosenName.isNullOrBlank()) {
                host.report(DownloadOutcome.FAILED, chosenName, "the download this answered was gone")
            }
            return
        }
        if (sink == null) {
            fail(request, "the chosen document could not be opened")
            return
        }
        host.requestBytes(request.id, request.url)
    }

    /**
     * Bytes for [requestId], base64 as the bridge can only carry text.
     *
     * The write happens outside the monitor and the download is claimed for the
     * length of it. That split is not an optimisation: a provider stream has no
     * timeout, so a write can sit inside the provider for as long as that
     * provider likes, and under the monitor it held every other caller with it.
     * Three of those arrive on the UI thread ([onPageGone] from `onDestroy`,
     * from `onPageFinished` and from `recreateWebView`), which turns a slow save
     * into an ANR. [Pending.busy] is what keeps the split honest.
     *
     * @return true when they were written, false on anything else. The page
     *   stops reading when this says false, which is the only backpressure in
     *   the design and also how a failed write stops a transfer that would
     *   otherwise run to completion and report success over a file with a hole
     *   in it.
     */
    fun onBytes(requestId: String, base64: String): Boolean {
        val chunk = synchronized(this) { claim(requestId, base64) } ?: return false
        val failure = try {
            chunk.sink.write(chunk.bytes)
            null
        } catch (e: Exception) {
            // Everything, because the claim taken above has to be given back
            // whatever the provider throws. A RuntimeException on its way out of
            // here leaves [Pending.busy] set for good: every teardown then defers
            // to a writer that is already gone, so the stream is never closed,
            // the user's document is never removed and the queue behind it never
            // drains again.
            e
        }
        return synchronized(this) {
            val request = chunk.request
            request.busy = false
            when {
                // Something ended this download while the write was outstanding
                // and could not free the stream, because this thread was inside
                // it. Identity against `pending` rather than a flag of its own:
                // every path that drops a request clears or replaces it, so one
                // test answers for all of them, the queued download that has
                // since been started in its place included.
                pending !== request -> {
                    closeAndDiscard(request)
                    false
                }
                failure != null -> {
                    Logger.w(tag, "Write to the chosen document failed: ${failure.javaClass.simpleName}")
                    fail(request, "writing to the chosen document failed")
                    false
                }
                else -> true
            }
        }
    }

    /** One claimed write: the download, the sink it owns, and the bytes for it. */
    private class Chunk(val request: Pending, val sink: Sink, val bytes: ByteArray)

    /**
     * Claims the live download for one write, or answers null when there is
     * nothing to write to. Called holding the monitor.
     */
    private fun claim(requestId: String, base64: String): Chunk? {
        val request = live(requestId) ?: return null
        val sink = request.sink ?: return null
        if (request.finishing) return null
        if (request.busy) {
            // Ended rather than merely refused. Returning false stops the page
            // reading and the page does not report a refused chunk back, so a
            // request left in `pending` here would never end: the queue behind
            // it would never drain and every later download would be refused.
            Logger.w(
                tag,
                "Two writers for ${redactToken(request.fileName)}; ending the download"
            )
            fail(request, "the page sent two pieces of this download at once")
            return null
        }
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            fail(request, "the page sent bytes that are not base64")
            return null
        }
        request.busy = true
        return Chunk(request, sink, bytes)
    }

    /**
     * The page has no more bytes. [error] is null on success and a reason
     * otherwise.
     *
     * Finishing is part of the success test rather than cleanup after it. A
     * `content://` stream can buffer, so the write that actually reaches
     * storage may be the one `close` performs, and reporting success before it
     * returns would report on a file that had not been written yet. Finishing
     * also cuts the document to the bytes written, and for a document the user
     * already had it is where that document is filled from app storage.
     *
     * Which is also why the finish is outside the monitor and claimed, exactly
     * as the write in [onBytes] is: a close that commits to a provider blocks
     * for as long as a write to one does.
     */
    fun onComplete(requestId: String, error: String?) {
        val (request, staged) = synchronized(this) {
            val request = live(requestId) ?: return
            if (request.sink == null) return
            if (request.finishing) return
            if (request.busy) {
                // A write is still inside the provider, so the sink cannot be
                // closed here; end the download and let the writer's exit close
                // and discard the file once it returns.
                fail(request, error ?: "the page ended the download while it was still writing")
                return
            }
            if (error != null) {
                fail(request, error)
                return
            }
            request.busy = true
            request.finishing = true
            request to (request.sink is StagedSink)
        }
        // A save over a document the user already had is copied into it from app storage here, whole,
        // and a provider takes as long as it takes. Every call the page makes blocks the page's own
        // thread until it returns (which is why the page yields between the pieces of a download), so
        // that copy is handed to [providerWork] and this returns at once: the page ignores the answer,
        // the outcome is reported from the provider thread, and the claim above keeps every other path
        // off the request until [finishClaimed] gives it back. A direct sink's finish is only a close,
        // and is made here as it always was.
        if (staged) providerWork.execute { finishClaimed(request) } else finishClaimed(request)
    }

    /** What [onComplete] does once the download is claimed, on the thread that holds the claim. */
    private fun finishClaimed(request: Pending) {
        // A copy into a document the person already had. It runs after the call that asked for it has
        // returned, so the page can be gone by the time it ends, and it is answered then all the same:
        // the person confirmed the overwrite, and what the copy did to their file is not a thing to
        // leave unsaid. A save into a document the picker made says nothing once its page is gone.
        val replacing = request.sink is StagedSink
        val failure = try {
            request.sink?.finish()
            null
        } catch (e: Exception) {
            // Everything, for the reason the write above gives: the claim below
            // is only given back if this returns.
            e
        }
        synchronized(this) {
            request.busy = false
            // Cleared however this ends, so the sink is never finished or let go
            // of twice and a teardown that deferred to this thread finds it
            // already released.
            request.sink = null
            if (failure != null) {
                Logger.w(tag, "Closing the chosen document failed: ${failure.javaClass.simpleName}")
                if (pending === request) fail(request, "the file could not be finished")
                else {
                    closeAndDiscard(request)
                    if (replacing) host.report(DownloadOutcome.FAILED, request.chosenName ?: request.fileName, "the file could not be finished")
                }
                return
            }
            // The bytes are committed, so the file is whole. A teardown that ran
            // during the close left the discard to this thread, and this is the
            // answer to it: a finished save is kept rather than deleted because
            // the page went away after it finished. Dropping the destination is
            // what makes that deferred discard a no-op.
            request.destination = null
            if (pending !== request) {
                if (replacing) host.report(DownloadOutcome.SAVED, request.chosenName ?: request.fileName, null)
                return
            }
            pending = null
            host.report(DownloadOutcome.SAVED, request.chosenName ?: request.fileName, null)
            startNextIfIdle()
        }
    }

    /**
     * Drops every download that can no longer be completed.
     *
     * For the WebView going away underneath one. The page that owed the bytes
     * no longer exists, so nothing will ever arrive for the download in flight
     * or for any of the ones queued behind it, and the document created for it
     * has to go rather than stay as an empty file the user did not ask for.
     * The one in flight goes silently, because the user is watching a renderer
     * crash recover and a download failure toast on top of that explains
     * nothing.
     *
     * The downloads queued behind it are reported rather than dropped in
     * silence. They own no document yet, so nothing is left on disk either way,
     * but each one is a file the user asked for and would otherwise simply not
     * arrive.
     *
     * A picker still on screen is left alone, because it belongs to the user
     * rather than to the page. Its result arrives naming a download that is
     * gone, and [onDestinationChosen] removes the document it created.
     */
    @Synchronized
    fun onPageGone() {
        // Reported, unlike the download in flight below. Every entry here names a
        // URL the departing document owned, so none of them can be read by the
        // page that replaced it and dropping them is right; what was wrong was
        // doing it in silence. Nothing was created on disk for these, so there is
        // no half-written file to explain, but the user did ask for each one and
        // was getting one file out of five with no word about the other four,
        // which is the silence this class exists to end. The queue is bounded at
        // MAX_QUEUED, so the number of notices is bounded with it.
        while (waiting.isNotEmpty()) {
            val dropped = waiting.removeFirst()
            host.report(DownloadOutcome.FAILED, dropped.fileName, "the page went away first")
        }
        val request = pending ?: return
        Logger.w(
            tag,
            "Abandoning the download of ${redactToken(request.fileName)}: the page went away"
        )
        pending = null
        closeAndDiscard(request)
    }

    private fun fail(request: Pending, detail: String) {
        pending = null
        closeAndDiscard(request)
        host.report(DownloadOutcome.FAILED, request.chosenName ?: request.fileName, detail)
        // Whether or not the page ever got as far as reading this one. A hold
        // already given up is told again here and answers nothing; a download
        // that failed before anyone asked for its bytes, which is every failure
        // up to and including the document not opening, is holding them still.
        host.releaseBytes(request.url)
        startNextIfIdle()
    }

    /**
     * Lets go of the sink and removes the document behind it when the picker
     * created that document for this download, off this thread.
     *
     * The two calls are separate hand-offs and the close goes first, which is
     * the whole of the ordering they need. Its failure is swallowed on purpose:
     * this runs only on paths that have already decided the download failed, so
     * there is no verdict left for it to change, and letting a throw carry the
     * discard away with it would leave behind exactly the half-written file the
     * discard exists to prevent.
     *
     * What stays here is the state transition. The sink and the document are
     * taken off the request under the monitor, so the request is over the moment
     * this returns and nothing can close or delete either of them twice; the two
     * calls that reach the provider are what goes to [providerWork], because
     * this is called from the UI thread on every teardown path and from the
     * bridge thread under the monitor on the rest.
     */
    private fun closeAndDiscard(request: Pending) {
        if (request.busy) {
            // Left to the thread inside the provider, which is the only one that
            // can free the document without closing it under a write in progress
            // or deleting it out from under the open that is creating the stream.
            // It re-enters here on its way out, by which time `pending` no longer
            // names this request. Waiting for that thread instead is the stall
            // this whole split exists to avoid: the caller here is routinely the
            // UI thread.
            Logger.d(tag, "Deferring the discard of a download still inside the provider")
            return
        }
        val sink = request.sink
        request.sink = null
        val destination = request.destination
        request.destination = null
        sink?.let { offThread("close") { it.abandon() } }
        // The one place a download removes a document, whichever way it ended:
        // a failure, a page that went away, a picker answer for a download that
        // was already gone, a write or close that outlived its download. Only a
        // document measured empty before the download wrote to it goes, which
        // loses nothing. Anything else is the user's, and a download that ended
        // before its last byte arrived has written nothing into it.
        if (request.owned) destination?.let { offThread("discard") { host.discardDestination(it) } }
    }

    /**
     * Hands the provider one call nobody is waiting for, and swallows what it
     * throws.
     *
     * Swallowed twice over. The caller has already decided this download failed,
     * and there is no thread left to tell: this runs on [providerWork], where an
     * exception on the way out reaches the process's uncaught handler rather
     * than a caller who could do something about it.
     */
    private fun offThread(what: String, work: () -> Unit) {
        providerWork.execute {
            try {
                work()
            } catch (e: Exception) {
                Logger.d(tag, "Ignoring a failed $what on a download already lost: ${e.message}")
            }
        }
    }

    /** The pending download when [requestId] names it, or null when it names a stale one. */
    private fun live(requestId: String): Pending? {
        val request = pending
        if (request == null || request.id != requestId) {
            Logger.d(tag, "Ignoring an answer for $requestId; it is not the live download")
            return null
        }
        return request
    }
}
