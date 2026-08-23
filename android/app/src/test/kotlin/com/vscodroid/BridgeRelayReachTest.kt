package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a caller on the bridge relay can and cannot make this app do.
 *
 * The relay is a `BroadcastChannel` opened in the workbench page, and the set of
 * callers it serves is wider than the bundled extension it was written for.
 * `product.json` carries no `webEndpointUrlTemplate`, and without one the
 * workbench starts the web extension host in a SAME-ORIGIN iframe and warns that
 * it has ("The web worker extension host is started in a same-origin iframe!" in
 * `out/vs/code/browser/workbench/workbench.js`). A `BroadcastChannel` is scoped by origin, so
 * every web extension installed from Open VSX shares this one, can post any of
 * the fourteen commands, and can read every answer posted back. The session token
 * the relay reads out of `window.__vscodroid` is no barrier: it lives on that same
 * origin, so anything able to post is able to read it.
 *
 * Most of the fourteen are fine under that reading, and the reasons are recorded
 * beside `MainActivity.injectBridgeRelay` rather than repeated here. What this
 * file pins is the three places where the answer was "not as it stands":
 *
 *  - a command nobody recognises must be answered rather than dropped;
 *  - `openRecentFolder` must not be able to put the system folder chooser on the
 *    user's screen for a URI they were never offered;
 *  - a forced mirror removal must be confirmed by this app, on this Activity,
 *    rather than on the caller's assurance that it drew a modal.
 *
 * Source reading, and the weaker layer for the reason `MirrorReclaimWiringTest`
 * gives: the relay is JavaScript inside a Kotlin string with no engine on this
 * classpath to run it, and the other two are Activity methods that show a dialog,
 * a Toast and a system picker. What it buys is that the branch is present at the
 * site; it cannot say the branch behaves.
 */
class BridgeRelayReachTest {

    private val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /**
     * A declaration's body with its comments blanked.
     *
     * Blanked after extraction rather than before, since the brace match is raw
     * and blanking a `//` inside a string literal elsewhere in this activity
     * would take a closing brace with it.
     */
    private fun code(declaration: String): String =
        SourceScan.withoutComments(SourceScan.body(source, declaration))

    private val relay by lazy { code("private fun injectBridgeRelay(") }

    @Test
    fun `the relay dispatcher is still what these cases are reading`() {
        assertTrue(relay.contains("d.cmd ===")) {
            "the relay no longer dispatches on d.cmd, so the case below is searching " +
                "text that is not the dispatcher any more"
        }
        assertTrue(relay.contains("BroadcastChannel('vscodroid-bridge')")) {
            "the relay no longer opens the channel the bundled extension posts on"
        }
    }

    /**
     * An unrecognised command is answered.
     *
     * The chain had no final `else`, so a name it did not know produced no reply
     * under that id at all. The caller's promise then died on its own deadline and
     * reported "Bridge timeout: is the app running on Android?", which accuses the
     * platform of not being there after five seconds, or after two minutes for a
     * storage command. It is also exactly what happens when a bridge method is
     * added and its relay branch is forgotten, which `docs/05-API_SPEC.md` names
     * as a hazard of this design and which is the moment a clear message is worth
     * most.
     */
    @Test
    fun `a command the relay does not know is answered rather than dropped`() {
        val chain = relay.substringAfter("d.cmd ===")
        val fallback = chain.indexOf("} else {")

        assertTrue(fallback >= 0) {
            "the relay's if/else-if chain has no final else, so a command it does not " +
                "recognise is silently dropped and the caller waits out its own deadline " +
                "to be told the app may not be running on Android"
        }
        val tail = chain.substring(fallback)
        assertTrue(tail.contains("ok: false") || tail.contains("ok:false")) {
            "the relay's final else does not answer with a failure, so an unknown " +
                "command still resolves nothing: " + tail.take(300)
        }
        assertTrue(tail.contains("d.id")) {
            "the answer for an unknown command is not posted under the caller's id, so " +
                "no promise is waiting for it: " + tail.take(300)
        }
    }

    /**
     * The system folder chooser is opened only because somebody asked for it.
     *
     * `openRecentFolder` is one of the fourteen relay commands and the URI travels
     * with the call, so a caller could pass one this app holds no grant for and
     * reach the fallback that opened the picker. The user then saw a document-tree
     * chooser they never asked for, from an app they trust, and picking a folder
     * in one persists a grant and copies the whole tree into `filesDir`.
     *
     * Nothing legitimate needed it. `SafStorageManager.getPersistedFolders` prunes
     * revoked grants before the recent list is handed over, so a URI arriving here
     * without one is either a lapse in the moment between the listing and the tap,
     * which the notice covers, or a URI nobody was ever offered.
     */
    @Test
    fun `a recent folder this app cannot open does not summon the folder picker`() {
        val open = code("fun openRecentSafFolder(")

        assertTrue(open.contains("hasPersistedPermission")) {
            "openRecentSafFolder no longer checks the grant, so this case is measuring " +
                "nothing about what happens when there is none"
        }
        assertTrue(!open.contains("openFolderPicker(")) {
            "openRecentSafFolder opens the folder picker again. The URI is caller " +
                "supplied and this command is on the relay, so any script on the " +
                "workbench's origin can put the system document chooser in front of the " +
                "user without ever calling openFolderPicker itself."
        }
    }

    /**
     * The removal that deletes files existing nowhere else is confirmed here.
     *
     * `reclaimSafMirror`'s own contract says `force` may be set only "after the
     * user has confirmed a modal that says so". That is a promise the caller makes
     * and this side cannot check, and the caller is any script on the workbench's
     * origin. What `force` skips is the check that every file in the copy is also
     * on the device, so what it deletes is by construction work that exists in no
     * other place: anything under `node_modules`, `.git`, `__pycache__` or
     * `.gradle`, and anything written while no watcher was running.
     */
    @Test
    fun `a forced removal asks the user before anything is deleted`() {
        val remove = code("private fun removeDeviceFolderCopy(")

        val asked = remove.indexOf("confirmForcedRemoval()")
        val deleted = remove.indexOf("safManager.reclaimMirror(")

        assertTrue(asked >= 0) {
            "a forced removal is taken on the caller's word again: nothing in " +
                "removeDeviceFolderCopy asks the user, and force deletes files that " +
                "exist nowhere else"
        }
        assertTrue(deleted >= 0) {
            "the removal no longer goes through safManager.reclaimMirror, so the order " +
                "checked below is measuring nothing"
        }
        assertTrue(asked < deleted) {
            "the user is asked only after the removal has already happened, which is " +
                "not a confirmation"
        }
        // The answer is captured rather than matched, so the case can say what a
        // decline is answered WITH and not merely that it is answered.
        //
        // It used to reject one wrong resource by name, which every other resource
        // satisfied: the sentence that replaced the rejected one was the
        // filesystem-refusal sentence, and the user who had just pressed Cancel
        // read "That folder's local copy could not be removed. Please try again."
        //
        // The bundled extension draws every non-empty answer as "Could not remove
        // that folder's local copy: <this>", so the only answer that does not
        // report a cancel as a failure of the app's is no answer at all, which for
        // this method is its success value.
        val decline = Regex("""if \(force && !confirmForcedRemoval\(\)\) return ([^\n]*)""")
            .find(remove)
        assertTrue(decline != null) {
            "the answer is not obeyed: a confirmation whose result is dropped names the " +
                "call and reads as correct at a glance while every forced removal " +
                "proceeds. Found: " +
                (remove.lines().firstOrNull { it.contains("confirmForcedRemoval") }?.trim()
                    ?: remove.take(200))
        }
        assertEquals("\"\"", decline!!.groupValues[1].trim()) {
            "a declined confirmation is answered with a sentence, and the extension draws " +
                "any sentence as a refusal: the user is told the app could not do the " +
                "thing they had just told it not to do, and invited to try again"
        }
    }

    /**
     * And the dialog it draws cannot itself crash the app.
     *
     * This is reached from the bridge's disk-work thread, which knows nothing
     * about the screen's lifecycle, and showing a dialog on a window that is going
     * away throws. The latch has to be released on that branch too, or the removal
     * waits out its whole bound for a dialog nobody drew, holding the one disk
     * thread and everything queued behind it.
     */
    @Test
    fun `the confirmation gives up rather than drawing on a window that is going`() {
        val confirm = code("private fun confirmForcedRemoval(")

        assertTrue(confirm.contains("isFinishing") && confirm.contains("isDestroyed")) {
            "the confirmation shows its dialog without asking whether the activity is " +
                "still there, and this runs on a thread that cannot know"
        }
        assertTrue(confirm.contains("runOnUiThread")) {
            "the dialog is built on whatever thread called in, which for this method is " +
                "the bridge's disk worker"
        }
        val guard = confirm.indexOf("isFinishing")
        val release = confirm.indexOf("countDown()")
        // The build, not the type name: the handle the timeout dismisses through
        // is declared above the guard and names the same class.
        val dialog = confirm.indexOf("AlertDialog.Builder(")
        assertTrue(guard in 0 until release) {
            "nothing releases the wait after the lifecycle guard"
        }
        assertTrue(release < dialog) {
            "the lifecycle guard returns without releasing the wait, so a removal that " +
                "arrives as the screen goes holds the bridge's one disk thread for the " +
                "whole of its bound"
        }
    }

    /**
     * And a question nobody answered is taken off the screen.
     *
     * The wait is bounded, so the dialog can outlive it. Left up, it goes on
     * offering Remove for a removal that has already been refused: pressing it
     * does nothing, and a second attempt stacks a second dialog on the first.
     */
    @Test
    fun `an unanswered confirmation is dismissed rather than left offering Remove`() {
        val confirm = code("private fun confirmForcedRemoval(")

        val waited = confirm.indexOf("answered.await(")
        val dismissed = confirm.indexOf("dismiss()")

        assertTrue(waited >= 0) { "the bounded wait is gone; this case is measuring nothing" }
        assertTrue(dismissed > waited) {
            "nothing takes the dialog down after the wait expires, so a question the user " +
                "answers late is answered into nothing"
        }
    }
}
