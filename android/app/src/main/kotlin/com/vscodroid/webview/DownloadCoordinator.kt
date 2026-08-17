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
 * Everything [DownloadCoordinator] needs from the Activity it runs inside.
 *
 * An interface rather than the pile of lambdas the chrome client uses, because
 * these five are one collaborator seen from five sides and a test wants to
 * watch them together: the ordering between opening a document, writing to it
 * and discarding it is most of what can go wrong here.
 */
interface DownloadHost {
    /**
     * Starts the create-document picker for a file named [fileName]. Answers
     * whether anything took the request, on the same terms as the file chooser:
     * false means no picker opened, so no result will ever arrive and whoever
     * is waiting has to be told now.
     */
    fun askDestination(fileName: String): Boolean

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
     * One at a time, and the id is what makes that safe rather than a
     * simplification that leaks. The page is told which request it is answering
     * and every answer is checked against the live one, so bytes belonging to a
     * download that has already been displaced are dropped instead of being
     * written into the file the user is currently waiting for. Without the
     * check, cancelling one download and starting another would silently
     * interleave two files.
     */
    private var pending: Pending? = null

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
    )

    /**
     * The page is about to download [url] under [fileName], which is the
     * `download` attribute of the anchor it is clicking.
     *
     * Recorded rather than acted on. The platform decides whether a click
     * becomes a download, and this runs before it has, so the name is put
     * somewhere [onDownloadStart] can find it and nothing else happens.
     */
    @Synchronized
    fun onDownloadNamed(url: String, fileName: String) {
        reportedNames[url] = fileName
    }

    /**
     * A download has started in the page. Works out what to call it and asks
     * the user where to put it.
     */
    @Synchronized
    fun onDownloadStart(url: String, contentDisposition: String?) {
        // Anything still in flight loses, and loses loudly. It cannot be kept:
        // there is one create-document result callback and it is about to be
        // claimed by this download, so the previous one would wait for an
        // answer that now belongs to somebody else.
        abandon("replaced by a later download")

        // Consumed, not read. The page reports a name per click, so leaving it
        // behind would let a later download of the same URL that the page did
        // not name inherit this one's filename.
        val fileName = downloadFileName(url, contentDisposition, reportedNames.remove(url))
        requestCounter += 1
        val request = Pending("dl-$requestCounter", url, fileName)
        pending = request

        if (!host.askDestination(fileName)) {
            // No picker opened, so no result will arrive to close this out. The
            // user asked for a file and has to hear that they are not getting
            // one, here, because nothing downstream will ever run.
            Logger.w(tag, "No create-document picker started for $fileName")
            pending = null
            host.report(DownloadOutcome.FAILED, fileName, "no picker")
        }
    }

    /**
     * The create-document picker answered. [destination] is null when the user
     * backed out.
     *
     * Cancelling is the ordinary case and it is deliberately not silent. It is
     * also the cheapest to get right: the picker creates nothing until the user
     * confirms, so there is no document to remove and the only work is letting
     * go of the request.
     */
    @Synchronized
    fun onDestinationChosen(destination: Uri?) {
        val request = pending ?: return
        if (destination == null) {
            pending = null
            host.report(DownloadOutcome.CANCELLED, request.fileName, null)
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
     * @return true when they were written, false on anything else. The page
     *   stops reading when this says false, which is the only backpressure in
     *   the design and also how a failed write stops a transfer that would
     *   otherwise run to completion and report success over a file with a hole
     *   in it.
     */
    @Synchronized
    fun onBytes(requestId: String, base64: String): Boolean {
        val request = live(requestId) ?: return false
        val stream = request.stream ?: return false
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            fail(request, "the page sent bytes that are not base64")
            return false
        }
        return try {
            stream.write(bytes)
            true
        } catch (e: IOException) {
            Logger.w(tag, "Write to the chosen document failed", e)
            fail(request, "writing to the chosen document failed")
            false
        }
    }

    /**
     * The page has no more bytes. [error] is null on success and a reason
     * otherwise.
     *
     * Closing is part of the success test rather than cleanup after it. A
     * `content://` stream can buffer, so the write that actually reaches
     * storage may be the one `close` performs, and reporting success before it
     * returns would report on a file that had not been written yet.
     */
    @Synchronized
    fun onComplete(requestId: String, error: String?) {
        val request = live(requestId) ?: return
        if (error != null) {
            fail(request, error)
            return
        }
        try {
            request.stream?.close()
        } catch (e: IOException) {
            Logger.w(tag, "Closing the chosen document failed", e)
            request.stream = null
            fail(request, "the file could not be finished")
            return
        }
        pending = null
        host.report(DownloadOutcome.SAVED, request.fileName, null)
    }

    /**
     * Drops a download that can no longer be completed, without reporting.
     *
     * For the WebView going away underneath one. The page that owed the bytes
     * no longer exists, so nothing will ever arrive, and the document created
     * for it has to go rather than stay as an empty file the user did not ask
     * for. Silent because the user is watching a renderer crash recover, and a
     * download failure toast on top of that explains nothing.
     */
    @Synchronized
    fun onPageGone() = abandon("the page went away")

    private fun abandon(reason: String) {
        val request = pending ?: return
        Logger.w(tag, "Abandoning the download of ${request.fileName}: $reason")
        pending = null
        closeAndDiscard(request)
    }

    private fun fail(request: Pending, detail: String) {
        pending = null
        closeAndDiscard(request)
        host.report(DownloadOutcome.FAILED, request.fileName, detail)
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
            Logger.d(tag, "Ignoring bytes for $requestId; it is not the live download")
            return null
        }
        return request
    }
}
