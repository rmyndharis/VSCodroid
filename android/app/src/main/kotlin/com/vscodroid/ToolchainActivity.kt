package com.vscodroid

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.setup.ToolchainManager
import com.vscodroid.util.drawBehindSystemBars
import com.vscodroid.util.padForSystemBars
import com.vscodroid.setup.ToolchainAction
import com.vscodroid.setup.ToolchainCardMode
import com.vscodroid.setup.ToolchainFailure
import com.vscodroid.setup.ToolchainPickerAdapter
import com.vscodroid.setup.ToolchainRegistry
import com.vscodroid.util.Logger
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Settings screen for managing on-demand toolchains.
 *
 * Reached from the launcher shortcut published by `publishToolchainShortcut` in
 * [SplashActivity], which is the only route a user has. This activity is not
 * exported and has no launcher entry of its own.
 *
 * [com.vscodroid.bridge.AndroidBridge.openToolchainSettings] can also start it,
 * and nothing calls that: it is dispatched by the `openToolchainSettings` command
 * on the BroadcastChannel relay, which no bundled extension sends. Naming it here
 * as the way in is what this comment used to do, and it sent readers looking for
 * a caller that does not exist.
 */
class ToolchainActivity : AppCompatActivity() {
    private val tag = "ToolchainActivity"

    private lateinit var toolchainManager: ToolchainManager
    private lateinit var adapter: ToolchainPickerAdapter

    /** Held as a field because the poll below posts on it and takes it back. */
    private lateinit var grid: RecyclerView

    /**
     * What [ToolchainManager.packsDownloading] said the last time this screen
     * asked, so the poll can push only when the answer has changed. Pushing an
     * unchanged snapshot costs a full rebind of every card once a second, and a
     * rebind is what takes accessibility focus off a button someone is on.
     */
    private var lastSeenDownloads: Map<String, Int> = emptyMap()

    /**
     * The "Remove Ruby?" confirmation, while one is on screen.
     *
     * Held because this Activity declares no `configChanges` (the manifest entry,
     * and two comments in [ToolchainManager] rest on it), so a rotation destroys
     * it with the dialog still attached to the old window token: the framework
     * logs `WindowLeaked` and the question disappears with the user's answer
     * unmade. Cleared by the dialog's own dismiss listener, so this never names a
     * window that has already gone.
     */
    private var removeDialog: AlertDialog? = null

    /**
     * The packs this screen asked for that have not reported a settled state.
     *
     * A set rather than a flag, because the screen offers a card per toolchain
     * and two of them can be in flight at once. Concurrent because a report does
     * not always arrive on this screen's thread. Play Core delivers a state
     * update on the main thread, so CANCELED, FAILED and NOT_INSTALLED reach
     * here from there, but `ToolchainManager` holds the one that matters most
     * back: COMPLETED is emitted only once the copy into `usr/` has run on its
     * `ioExecutor`, and an HTTP transfer reports from that executor throughout.
     * Those are the ones [shouldReleaseSubscription] has to order its read of
     * [destroyed] against.
     *
     * What it decides is in [shouldReleaseSubscription]: the Play Core
     * subscription now follows the download rather than the screen.
     *
     * It has a second reader, and the same fact answers both: a report for a pack
     * in here is an answer to something this screen asked for, and a report for
     * one that is not may be about a download begun by a screen that no longer
     * exists. [shouldSayAlreadyInstalling] is where that matters, and the reason
     * it matters is the retention above. Read before [shouldReleaseSubscription],
     * never after, because that function empties the set of anything settled.
     */
    private val outstanding: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Whether this screen is past `onDestroy`.
     *
     * Ours rather than `isDestroyed`, because [shouldReleaseSubscription] reads
     * it from whichever thread reported, and that flag is written by the
     * framework with nothing to order the read against. This one is written on
     * the line before the check it races with, and read after the set mutation it
     * races with, which is the pairing that makes the handoff safe.
     *
     * It answers one question only: who is left to hand the Play Core
     * subscription back. Whether a message may be put in front of the user is a
     * different question with a different answer, asked of `lifecycle` on the
     * main thread in [showPackState]. A screen the user pressed Home on is
     * stopped and very much alive, and this flag says nothing about it.
     */
    private val destroyed = AtomicBoolean(false)

    /**
     * That Play asked for a cellular-data confirmation while this screen was not
     * in front, so the question can be put when it comes back.
     *
     * `showCellularDataConfirmation` puts a window up and needs a started
     * Activity behind it. Dropping the request instead would leave the pack
     * waiting on an answer nobody is ever asked for: REQUIRES_USER_CONFIRMATION
     * is not a settled status, so [shouldReleaseSubscription] keeps listening,
     * and nothing here builds on Play re-emitting a state to a listener that was
     * already subscribed. A flag rather than a pack name because the Play call is
     * per-app and takes none.
     *
     * Main thread only: written in [showPackState], which the state callback
     * reaches through `runOnUiThread`, and read in `onStart`. Cleared when the
     * question is put, and by any settled status that arrives first, so a
     * download cancelled while the screen was away does not leave it asking about
     * nothing. It dies with the screen.
     */
    private var confirmationDeferred = false

    /**
     * Re-asks the process what is downloading, because nothing tells this screen.
     *
     * Progress reaches the manager that began the transfer and nothing else, so a
     * download some other manager started is visible here only through the
     * process-wide snapshot. That snapshot was read once, at onStart, and the
     * thing it describes keeps moving: the bar sat at whichever percentage it
     * happened to read and the card went on offering Cancel after the install had
     * finished. A screen with no subscription can only ask again, and it asks only
     * while it is in front: the callback is taken back in onStop.
     */
    private val pollDownloads = object : Runnable {
        override fun run() {
            refreshDownloads()
            grid.postDelayed(this, DOWNLOAD_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate(), as the call it replaces required.
        drawBehindSystemBars()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toolchain)
        findViewById<android.view.View>(R.id.toolchainRoot).padForSystemBars()

        // The APPLICATION context, and the state callback below holds this screen
        // weakly, which are the two halves of one fix rather than two changes.
        // Both the things that outlive this Activity keep whatever the manager
        // holds: an HTTP transfer runs on the manager's own executor, and the Play
        // Core subscription is now kept until the download settles. AndroidBridge
        // records what changing only one half costs, an Activity and its whole
        // view tree still reachable through a lambda that merely called one of its
        // members. Nothing the manager reads needs a screen: filesDir, cacheDir,
        // assets, packageManager and packageName all answer the same on the
        // application context, and showConfirmationDialog takes the Activity as a
        // parameter.
        val manager = ToolchainManager(applicationContext)
        toolchainManager = manager

        // Toolbar back button
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Set up grid with MANAGER mode adapter
        adapter = ToolchainPickerAdapter(ToolchainCardMode.MANAGER)
        adapter.setInstalled(toolchainManager.getInstalledToolchains())

        adapter.onAction = { packName, action ->
            when (action) {
                ToolchainAction.INSTALL,
                ToolchainAction.RETRY -> {
                    // Recorded before the call, not after: install() can report a
                    // failure on this very thread, for an unknown pack or a
                    // missing download URL, and the removal that follows would
                    // then run against a set this pack was not in yet, leaving the
                    // subscription held for a download that never began.
                    outstanding.add(packName)
                    toolchainManager.install(packName)
                }
                ToolchainAction.REMOVE -> {
                    showRemoveConfirmation(packName)
                }
                ToolchainAction.CANCEL -> {
                    toolchainManager.cancel(packName)
                    // Dropped here as well as on the report the download now
                    // makes, because the two answer different moments. This one
                    // is immediate: an `onStop` between the tap and the download
                    // loop's next look at the flag would otherwise find the set
                    // non-empty and keep the subscription for a transfer that is
                    // already stopping. The report is the one that reaches the
                    // set this line cannot -- the cancel token is process-wide,
                    // so this button stops a transfer a destroyed screen began,
                    // and only that screen's own manager can take the pack out of
                    // that screen's set. Left to this line alone, the destroyed
                    // screen kept the pack and the Play Core registration with it
                    // for the life of the process, once per repetition.
                    outstanding.remove(packName)
                    // CANCELED, and specifically not the neighbouring
                    // NOT_INSTALLED, which is the same trap ToolchainManager
                    // documents where it had to pick a status for something that
                    // is not an uninstall. Cancelling stops a transfer and hands
                    // Play's delivery back; it deletes nothing from `usr/` and
                    // takes nothing out of `toolchains.json`. NOT_INSTALLED is
                    // this app's word for a completed uninstall, written at the
                    // end of uninstallLocked, and the card state reads it as
                    // exactly that and drops the pack from its installed set, so
                    // cancelling a re-download of an installed toolchain hid its
                    // Remove button until the screen was reopened. CANCELED
                    // carries no such meaning, so the card falls back to what the
                    // install record says.
                    adapter.updateState(packName, AssetPackStatus.CANCELED, 0)
                }
            }
        }

        grid = findViewById(R.id.toolchainGrid)
        grid.layoutManager = GridLayoutManager(this, 2)
        grid.adapter = adapter

        // Listen for download state changes.
        //
        // Every name in here is a local, deliberately. Reading a field, or calling
        // a member without qualifying it, captures this Activity as completely as
        // naming it would, and this lambda is reachable from a Play Core registry
        // and from an executor thread that both outlive the screen. That is the
        // shape AndroidBridge had to correct twice, the second time in bytecode.
        val screen = WeakReference(this)
        val pending = outstanding
        val screenGone = destroyed
        manager.onStateChange = { packName, status, percent, why ->
            // Read here and not in showPackState, because the call below takes a
            // settled pack out of the set. Asked afterwards this is false for
            // every terminal status, and a decline is terminal, so the one
            // question [shouldSayAlreadyInstalling] exists to answer would always
            // answer no. Read on this thread rather than the main one for the
            // same reason: the post below is delivered later still.
            val asked = packName in pending
            // The flag itself, not a reading of it: [shouldReleaseSubscription]
            // has to take the pack out of the set before it looks, or the two
            // threads read in the same order and the handoff falls between them.
            if (shouldReleaseSubscription(packName, status, pending, screenGone)) {
                manager.unregisterListener()
            }
            // Posted whatever this screen's state is, and gated on the far side.
            // Whether anything may be shown is a main-thread question twice over:
            // the screen can stop between a check made on this thread and the
            // message being delivered, and a LifecycleRegistry may only be read
            // from the thread that moves it. So there is one predicate and it
            // lives in showPackState, which runs there. Asked here instead, a
            // download ending after the user closed this screen put a toolchain
            // message over the editor.
            screen.get()?.let { live ->
                live.runOnUiThread { live.showPackState(packName, status, percent, why, asked) }
            }
        }
    }

    /**
     * The half of a state report that needs a screen, reached only through the
     * weak reference the callback holds, and only on the main thread.
     *
     * [asked] is whether this screen was waiting on [packName] when the report
     * arrived, taken by the caller because the set it comes from has been emptied
     * of settled packs by the time this runs. Only [shouldSayAlreadyInstalling]
     * needs it, and only because one status can now reach a screen that requested
     * nothing.
     */
    private fun showPackState(
        packName: String,
        status: Int,
        percent: Int,
        why: ToolchainFailure?,
        asked: Boolean,
    ) {
        // Outside the gate below, deliberately. This is this screen's own card,
        // it costs one row rebind, and it is what has to be right the moment the
        // user comes back. Only the half that interrupts them is gated.
        adapter.updateState(packName, status, percent)

        // Everything past here puts something in front of whoever is looking at
        // the device, and that is no longer necessarily this screen. `onStop`
        // hands the Play Core subscription back only when nothing is outstanding,
        // which is what keeps the COMPLETED that installs a delivered pack
        // reachable, and the price of that is state reports arriving at a screen
        // the user has left. Destroyed is not the test for it: a screen the user
        // pressed Home on is stopped and alive, and its Toast lands over the
        // editor just the same. Play's own confirmation window is worse, since it
        // takes the foreground.
        //
        // Asked of `lifecycle` rather than a flag of our own because there is
        // then nothing to keep in step, and this runs on the main thread, which
        // is both where the answer is authoritative and the only thread that may
        // ask it.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (status == AssetPackStatus.REQUIRES_USER_CONFIRMATION) {
                confirmationDeferred = true
            } else if (isTerminalPackStatus(status)) {
                confirmationDeferred = false
            }
            return
        }
        // Same reasoning as the first-run screen: the card carries a
        // status, not an explanation, and the explanation is the part
        // that tells the user whether to free space or move to wifi.
        if (why != null) {
            Toast.makeText(this, getString(why.message), Toast.LENGTH_LONG).show()
        }
        // A decline reports UNKNOWN, and without this the screen answers a
        // tap with nothing at all. The card cannot say it either: the pack
        // is not installed yet and no progress belongs to this manager, so
        // it draws the same Install it drew before, and the user taps again
        // and gets the same silence. The install is genuinely happening,
        // just not by this caller, so the sentence says so rather than
        // reporting a failure. Which is why it is said only to the caller:
        // [shouldSayAlreadyInstalling] carries how a decline reaches a screen
        // that asked for nothing, and what is left wrong when it does.
        if (shouldSayAlreadyInstalling(status, why, asked)) {
            Toast.makeText(
                this, getString(R.string.toolchain_already_installing), Toast.LENGTH_SHORT
            ).show()
        }
        if (status == AssetPackStatus.REQUIRES_USER_CONFIRMATION) {
            askForCellularConfirmation()
        }
    }

    /**
     * Puts Play's cellular-data question, from a screen that is in front.
     *
     * One method because two sites ask it: the report that arrives while the
     * screen is up, and the one [confirmationDeferred] held back until `onStart`.
     * The catch came with the first of those and is kept rather than argued for
     * again; the manager has one of its own around the same call.
     */
    private fun askForCellularConfirmation() {
        try {
            toolchainManager.showConfirmationDialog(this)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to show confirmation dialog", e)
        }
    }

    override fun onStart() {
        super.onStart()
        toolchainManager.registerListener()
        // Refresh installed state on resume (user may have installed from terminal)
        adapter.setInstalled(toolchainManager.getInstalledToolchains())
        // And what is downloading, which this screen is not told about at all when
        // another manager began the transfer: the cards started empty and offered
        // Install for a pack already downloading, with no progress and no Cancel.
        // Asked again on a timer from here on, because one reading goes stale while
        // the screen is still looking at it. Play's own downloads are not in this
        // map, and a Play transfer this screen did not begin therefore draws no
        // progress until Play reports one. What must not depend on a report is the
        // COMPLETED that installs a delivered pack, and that no longer does: the
        // subscription is held until the download settles rather than resting on a
        // re-emission Play Core promises nowhere. This comment used to assert that
        // re-delivery as a fact, which is the claim AndroidBridge refuses to build
        // on and nothing here has measured.
        refreshDownloads()
        grid.postDelayed(pollDownloads, DOWNLOAD_POLL_MS)
        // Put now, because Play asked while there was no started screen to put it
        // and nothing else will ask again. REQUIRES_USER_CONFIRMATION does not
        // settle, so no report follows it and the pack simply waits; suppressing
        // the question without this line would be the difference between a
        // download the user was rudely interrupted for and one that never
        // finishes.
        if (confirmationDeferred) {
            confirmationDeferred = false
            askForCellularConfirmation()
        }
    }

    override fun onStop() {
        // Before the listener, so nothing is posted onto a screen that is going
        // away. The callback is the only thing keeping this activity referenced
        // from the view's message queue.
        grid.removeCallbacks(pollDownloads)
        // Only when this screen has nothing running. [shouldReleaseSubscription]
        // carries why an install in flight keeps it, and hands it back itself once
        // the last one settles with no screen left to hear it.
        if (outstanding.isEmpty()) toolchainManager.unregisterListener()
        super.onStop()
    }

    override fun onDestroy() {
        // Set before the check, and read in [shouldReleaseSubscription] after the
        // removal, which together are the whole of it. This thread writes the
        // flag then tests the set; a download settling on another thread mutates
        // the set then reads the flag. Opposite orders, so the second of the two
        // to run always sees what the first produced: whichever that is releases
        // the subscription, and the call is idempotent, so both seeing it costs
        // nothing. Taking the reading at the call site instead, which is what
        // this used to do, put both threads in the same order and left one
        // interleaving where the screen found the set non-empty and the report
        // found a screen that was not gone yet, with nobody left to hand it back.
        destroyed.set(true)
        if (outstanding.isEmpty()) toolchainManager.unregisterListener()
        // The window this dialog is attached to goes with the Activity, and a
        // rotation destroys this one: nothing here declares configChanges. Left
        // showing, it is torn down as a leaked window and the removal it was
        // asking about is answered by nobody. Taken down here instead, which is
        // also what a recreated screen needs, since it puts the question again
        // only when the user taps Remove again.
        removeDialog?.dismiss()
        super.onDestroy()
    }

    /**
     * Pushes a fresh view of the process's downloads into the cards, if it has
     * changed since the last one.
     */
    private fun refreshDownloads() {
        val downloading = ToolchainManager.packsDownloading()
        val refresh = downloadRefreshFor(lastSeenDownloads, downloading)
        if (!refresh.push) return
        lastSeenDownloads = downloading
        adapter.setDownloading(downloading)
        // Only when a pack has LEFT the snapshot, which is the moment its outcome
        // becomes readable and the moment this screen would otherwise draw Install
        // for something that has just finished installing. Reading the record on
        // every percentage tick instead would be a file read a second for an
        // answer that cannot have changed.
        if (refresh.rereadInstalled) adapter.setInstalled(toolchainManager.getInstalledToolchains())
    }

    private fun showRemoveConfirmation(packName: String) {
        val info = ToolchainRegistry.find(packName) ?: return
        removeDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.toolchain_remove_confirm_title, info.displayName))
            .setMessage(getString(R.string.toolchain_remove_confirm_message, info.displayName))
            .setPositiveButton(getString(R.string.toolchain_remove)) { _, _ ->
                val shortName = packName.removePrefix("toolchain_")
                toolchainManager.uninstall(shortName)
                Logger.i(tag, "User confirmed removal of $shortName")
            }
            .setNegativeButton(android.R.string.cancel, null)
            // Answered, dismissed or cancelled with the back button, all three end
            // here, so the field never outlives the window it names and onDestroy
            // has nothing to dismiss for a question the user already closed.
            .setOnDismissListener { removeDialog = null }
            .show()
    }
}

/**
 * How often the Toolchains screen re-asks the process what is downloading.
 *
 * A second is well under the pace a progress bar has to keep and well over the
 * cost of the question, which is one snapshot of a map that holds an entry per
 * transfer in flight, usually none. It runs only while this screen is in front.
 */
private const val DOWNLOAD_POLL_MS = 1_000L

/**
 * Whether the Play Core subscription can be handed back now that [packName] has
 * reported [status], dropping that pack from [outstanding] once it has settled.
 *
 * The decision `onStop` used to make on its own, and it made it wrong. Dropping
 * the subscription mid-download is what `AndroidBridge` refuses to do with the
 * same manager, for a reason that applies here word for word: the COMPLETED
 * branch of the manager's state handler is the only thing that copies a
 * delivered pack into `usr/`, and Play Core promises nothing about re-emitting a
 * state to a listener that subscribes afterwards. `reconcileDeliveredPacks`
 * repairs such a pack at the next launch that runs `SplashActivity`, and
 * resuming a live task runs none, so a toolchain the user has already paid for
 * stays unusable for the rest of that task's life.
 *
 * So the subscription follows the download rather than the screen, and the two
 * conditions here are what stops it outliving both: nothing left to hear, and no
 * screen left to hear it. A pack that never settles, a confirmation prompt the
 * user walked away from, keeps it for the life of the process. That is the same
 * bound `AndroidBridge` accepts, and it costs nothing but the manager now that
 * the manager holds the application context.
 *
 * [isTerminalPackStatus] decides what settles, so UNKNOWN counts: that is how
 * `ToolchainManager` declines a request for a pack another install already
 * holds, and a declined caller hears nothing further about it. That predicate
 * also counts a status this build has never heard of as settled, where
 * `AndroidBridge` names its three outright. The difference is deliberate and
 * cheap here: releasing early on such a status is the behaviour this whole
 * function replaces, repaired at the next launch by the reconcile, while a set
 * that never drains holds the subscription for the life of the process.
 *
 * [screenGone] is the flag itself and not a reading of it, and the read below
 * happens after the removal. That ordering is load-bearing: `onDestroy` writes
 * the flag and then tests the set, so this has to mutate the set and then test
 * the flag for one of the two to see the final state. Taken as a `Boolean`
 * argument the read happened at the call site, before the removal, which put
 * both threads in the same order and left the interleaving where a report is
 * preempted between the two: the screen tests a set the last pack has not left
 * yet and skips, then the report empties it and reads a flag it took before the
 * screen was gone and skips too, and Play Core keeps a listener nothing will
 * ever hand back.
 *
 * File scope so it can be tested without an Activity; this project's unit tests
 * have no Robolectric, the same reason [downloadRefreshFor] below lives here.
 */
internal fun shouldReleaseSubscription(
    packName: String,
    status: Int,
    outstanding: MutableSet<String>,
    screenGone: AtomicBoolean,
): Boolean {
    if (!isTerminalPackStatus(status)) return false
    outstanding.remove(packName)
    return screenGone.get() && outstanding.isEmpty()
}

/**
 * Whether the "already installing" line answers a tap this screen made.
 *
 * A decline is a manager's answer to an install request: [status] UNKNOWN with no
 * [why], reported at the three places `ToolchainManager` refuses a pack that some
 * other install or download already holds. Two of the three are reached
 * synchronously from `install()` on the calling thread, so they can only ever
 * answer the caller. The third cannot: it is reported from the COMPLETED handler
 * on the manager's own executor, and every registered listener hears Play's
 * COMPLETED, not only the one whose screen asked for the pack.
 *
 * That third case became reachable when [shouldReleaseSubscription] made the
 * subscription follow the download rather than the screen. A rotation
 * mid-download destroys this screen with its listener still registered and keeps
 * it that way until the pack settles, and the rebuilt screen registers a second
 * one. Play then delivers COMPLETED to both managers, exactly one of them wins
 * the process-wide claim, and the loser declines. When the loser is the rebuilt
 * screen's manager, this line appeared on a screen where the user had tapped
 * nothing at all. [asked] is the whole difference between an answer and an
 * overheard remark, and it is why the caller reads the set before that function
 * empties it.
 *
 * What this deliberately does not repair is the card behind the line. The decline
 * is still recorded as this screen's last word on the pack, and
 * `ToolchainCardState.managerCard` reads UNKNOWN as neither in flight nor
 * installed, so the card offers Install for a toolchain another manager is
 * installing right now. Nothing truer is available at that instant: the claim is
 * taken before the copy, so the install record still says the pack is absent.
 * Dropping the decline instead would be worse, not better, because it would leave
 * the card on the TRANSFERRING that preceded it, offering Cancel for a transfer
 * that has finished, with nothing that ever corrects it. Recorded, `onStart`
 * corrects it: it re-reads the install record, so leaving this screen and coming
 * back draws Remove. Correcting it in place would mean re-reading that record on
 * the poll until some other manager's install ends, which is a file read a second
 * against the one comment in `refreshDownloads` that refuses exactly that.
 *
 * File scope so it can be tested without an Activity; this project's unit tests
 * have no Robolectric, the same reason [shouldReleaseSubscription] above and
 * [downloadRefreshFor] below live here.
 */
internal fun shouldSayAlreadyInstalling(
    status: Int,
    why: ToolchainFailure?,
    asked: Boolean,
): Boolean = asked && status == AssetPackStatus.UNKNOWN && why == null

/** What one reading of the download snapshot asks the screen to do. */
internal data class DownloadRefresh(val push: Boolean, val rereadInstalled: Boolean)

/**
 * The decision behind `ToolchainActivity.refreshDownloads`, at file scope so it
 * can be tested without an Activity; this project's unit tests have no
 * Robolectric, the same reason [isTerminalPackStatus] and [isCurrentDownload]
 * live beside SplashActivity.
 *
 * Two separate questions, and folding them into one would lose the cheaper half:
 * anything at all changing is worth a repaint, while only a pack leaving the map
 * is worth re-reading the install record from disk.
 */
internal fun downloadRefreshFor(
    previous: Map<String, Int>,
    current: Map<String, Int>,
): DownloadRefresh = DownloadRefresh(
    push = current != previous,
    rereadInstalled = (previous.keys - current.keys).isNotEmpty(),
)
