package com.vscodroid.webview

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.vscodroid.util.Logger
import java.io.OutputStream
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
 * Everything [DownloadCoordinator] needs from the Activity it runs inside.
 *
 * An interface rather than the pile of lambdas the chrome client uses, because
 * these six are one collaborator seen from six sides and a test wants to
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

    /** Opens the document the user chose for writing, or null when it will not open. */
    fun openDestination(destination: Uri): OutputStream?

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
}

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
 * it, so every path that ends badly removes the document it created and says
 * so. Reporting is not optional on any path either: the defect this replaces
 * was a menu entry that did nothing and said nothing, and a fix that fails
 * quietly is the same defect wearing more code.
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
        var destination: Uri? = null,
        var stream: OutputStream? = null,
        /**
         * Whether a thread is inside the open, a `write` or the `close` of this
         * download's document right now, outside the monitor.
         *
         * The claim, and the whole of the exclusion. The stream comes from
         * `contentResolver.openOutputStream`, and neither that call nor a stream
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
    )

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
     * the user where to put it, or queues it when the picker is busy.
     */
    @Synchronized
    fun onDownloadStart(url: String, contentDisposition: String?) {
        // Consumed, not read. The page reports a name per click, so leaving it
        // behind would let a later download of the same URL that the page did
        // not name inherit this one's filename.
        val fileName = downloadFileName(url, contentDisposition, reportedNames.remove(url))
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
     * the download waiting for one loses its document. The picker creates the
     * file the moment the user confirms a name, so a result nobody is waiting
     * on has already put an empty file in their folder wearing the name of a
     * file they will not get.
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
            destination?.let { offThread("discard") { host.discardDestination(it) } }
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
        // Recorded before the stream is opened, so a failure to open still has
        // somewhere to point. The picker has already created the document by
        // the time it answers, so an unopenable one is an empty file sitting in
        // the user's chosen folder wearing the name of the file they wanted.
        request.destination = destination
        // Claimed here rather than on the provider thread, so that no teardown
        // can slip between deciding to open the document and the claim that
        // makes it wait. See [Pending.busy].
        request.busy = true
        // Handed over rather than made here. This is the first trip into the
        // provider and the last one that was still made from the UI thread under
        // the monitor: `openOutputStream` cold-starts the provider's process and
        // can go to a network, with no timeout on either, and this runs in the
        // activity-result callback, so a slow provider froze the editor from the
        // moment the picker closed and past five seconds it was an ANR. The
        // close and the discard were moved off for exactly this reason; see
        // [providerWork], which is single, so an open still queues behind the
        // close of the document before it.
        providerWork.execute {
            val stream = try {
                host.openDestination(destination)
            } catch (e: Exception) {
                // Everything the provider can throw, not only IOException.
                // Opening the document is a binder call into another app, and
                // one that was force-stopped, updated or had its grant revoked
                // between the picker answering and this line answers with
                // SecurityException or IllegalArgumentException instead. Nothing
                // above this catches: it would reach the uncaught handler on the
                // provider thread and take the process with it, and the download
                // would keep its claim for ever on the way out.
                Logger.w(tag, "Could not open the chosen document", e)
                null
            }
            mainThread.execute { onDestinationOpened(request, stream) }
        }
    }

    /**
     * The provider answered the open for [request], with null when the document
     * would not open.
     *
     * Back on the thread the picker's answer arrived on, because [mainThread]
     * says why, and back under the monitor, because the state this settles is
     * the same state every other answer settles.
     */
    @Synchronized
    private fun onDestinationOpened(request: Pending, stream: OutputStream?) {
        request.busy = false
        // Recorded before the test below, so that a download ended while its
        // document was opening hands [closeAndDiscard] a stream to close rather
        // than leaving one open on a document nobody will ever read back.
        request.stream = stream
        if (pending !== request) {
            // Ended while the provider was still thinking, by a teardown that
            // found this claimed and left the document to the thread inside it.
            // This is that thread's way out; `pending` no longer names the
            // request, exactly as it does not for a write that outlived one.
            closeAndDiscard(request)
            return
        }
        if (stream == null) {
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
            chunk.stream.write(chunk.bytes)
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
                    Logger.w(tag, "Write to the chosen document failed", failure)
                    fail(request, "writing to the chosen document failed")
                    false
                }
                else -> true
            }
        }
    }

    /** One claimed write: the download, the stream it owns, and the bytes for it. */
    private class Chunk(val request: Pending, val stream: OutputStream, val bytes: ByteArray)

    /**
     * Claims the live download for one write, or answers null when there is
     * nothing to write to. Called holding the monitor.
     */
    private fun claim(requestId: String, base64: String): Chunk? {
        val request = live(requestId) ?: return null
        val stream = request.stream ?: return null
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
        return Chunk(request, stream, bytes)
    }

    /**
     * The page has no more bytes. [error] is null on success and a reason
     * otherwise.
     *
     * Closing is part of the success test rather than cleanup after it. A
     * `content://` stream can buffer, so the write that actually reaches
     * storage may be the one `close` performs, and reporting success before it
     * returns would report on a file that had not been written yet.
     *
     * Which is also why the close is outside the monitor and claimed, exactly as
     * the write in [onBytes] is: a close that commits to a provider blocks for as
     * long as a write to one does.
     */
    fun onComplete(requestId: String, error: String?) {
        val request = synchronized(this) {
            val request = live(requestId) ?: return
            if (error != null) {
                fail(request, error)
                return
            }
            if (request.busy) {
                // The page says it is finished while a piece of its own is still
                // inside the provider. Nothing sequential produces that, and the
                // close cannot run under the write, so the download ends as a
                // failure and the thread inside the write removes the file.
                fail(request, "the page ended the download while it was still writing")
                return
            }
            request.busy = true
            request
        }
        val failure = try {
            request.stream?.close()
            null
        } catch (e: Exception) {
            // Everything, for the reason the write above gives: the claim below
            // is only given back if this returns.
            e
        }
        synchronized(this) {
            request.busy = false
            // Cleared however this ends, so the stream is never closed twice and
            // a teardown that deferred to this thread finds it already released.
            request.stream = null
            if (failure != null) {
                Logger.w(tag, "Closing the chosen document failed", failure)
                if (pending === request) fail(request, "the file could not be finished")
                else closeAndDiscard(request)
                return
            }
            // The bytes are committed, so the file is whole. A teardown that ran
            // during the close left the discard to this thread, and this is the
            // answer to it: a finished save is kept rather than deleted because
            // the page went away after it finished. Dropping the destination is
            // what makes that deferred discard a no-op.
            request.destination = null
            if (pending !== request) return
            pending = null
            host.report(DownloadOutcome.SAVED, request.fileName, null)
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
        host.report(DownloadOutcome.FAILED, request.fileName, detail)
        // Whether or not the page ever got as far as reading this one. A hold
        // already given up is told again here and answers nothing; a download
        // that failed before anyone asked for its bytes, which is every failure
        // up to and including the document not opening, is holding them still.
        host.releaseBytes(request.url)
        startNextIfIdle()
    }

    /**
     * Closes the stream and removes the document behind it, off this thread.
     *
     * The two calls are separate hand-offs and the close goes first, which is
     * the whole of the ordering they need. Its failure is swallowed on purpose:
     * this runs only on paths that have already decided the download failed, so
     * there is no verdict left for it to change, and letting a throw carry the
     * discard away with it would leave behind exactly the half-written file the
     * discard exists to prevent.
     *
     * What stays here is the state transition. The stream and the document are
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
        val stream = request.stream
        request.stream = null
        val destination = request.destination
        request.destination = null
        stream?.let { offThread("close") { it.close() } }
        destination?.let { offThread("discard") { host.discardDestination(it) } }
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
