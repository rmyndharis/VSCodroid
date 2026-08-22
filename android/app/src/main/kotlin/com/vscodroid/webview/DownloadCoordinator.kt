package com.vscodroid.webview

import android.net.Uri
import com.vscodroid.util.Logger
import java.io.IOException
import java.io.OutputStream
import java.util.Base64

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
 * Everything [DownloadCoordinator] needs from the Activity it runs inside.
 *
 * An interface rather than the pile of lambdas the chrome client uses, because
 * these five are one collaborator seen from five sides and a test wants to
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
class DownloadCoordinator(private val host: DownloadHost) {

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
         * Whether a thread is inside `write` or `close` on [stream] right now,
         * outside the monitor.
         *
         * The claim, and the whole of the exclusion. The stream comes from
         * `contentResolver.openOutputStream`, and a stream to a cloud or MTP
         * provider has no timeout, so the call cannot be made while holding the
         * monitor: every teardown here arrives on the UI thread (`onDestroy`,
         * `onPageFinished`, `recreateWebView`) and would queue behind a provider
         * free to take as long as it likes. What the monitor still guarantees is
         * that this is set before the call and cleared after it, so no second
         * writer starts and no teardown closes the stream underneath one: the
         * download is claimed, not unguarded.
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
            destination?.let { host.discardDestination(it) }
            startNextIfIdle()
            return
        }
        if (destination == null) {
            pending = null
            host.report(DownloadOutcome.CANCELLED, request.fileName, null)
            startNextIfIdle()
            return
        }
        // Recorded before the stream is opened, so a failure to open still has
        // somewhere to point. The picker has already created the document by
        // the time it answers, so an unopenable one is an empty file sitting in
        // the user's chosen folder wearing the name of the file they wanted.
        request.destination = destination
        val stream = try {
            host.openDestination(destination)
        } catch (e: IOException) {
            Logger.w(tag, "Could not open the chosen document", e)
            null
        }
        if (stream == null) {
            fail(request, "the chosen document could not be opened")
            return
        }
        request.stream = stream
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
        } catch (e: IOException) {
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
        } catch (e: IOException) {
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
        startNextIfIdle()
    }

    /**
     * Closes the stream and removes the document behind it.
     *
     * The close comes first and its failure is swallowed on purpose: this runs
     * only on paths that have already decided the download failed, so there is
     * no verdict left for it to change, and a throw here would skip the discard
     * and leave behind exactly the half-written file the discard exists to
     * prevent.
     */
    private fun closeAndDiscard(request: Pending) {
        if (request.busy) {
            // Left to the thread inside the provider, which is the only one that
            // can free the stream without closing it under a write in progress.
            // It re-enters here on its way out, by which time `pending` no longer
            // names this request. Waiting for that thread instead is the stall
            // this whole split exists to avoid: the caller here is routinely the
            // UI thread.
            Logger.d(tag, "Deferring the discard of a download still inside a write")
            return
        }
        try {
            request.stream?.close()
        } catch (e: IOException) {
            Logger.d(tag, "Ignoring a close failure on a download already lost: ${e.message}")
        }
        request.stream = null
        request.destination?.let { host.discardDestination(it) }
        request.destination = null
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
