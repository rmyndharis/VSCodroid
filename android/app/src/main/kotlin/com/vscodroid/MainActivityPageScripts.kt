package com.vscodroid

import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import java.io.File
import com.vscodroid.MainActivity.Companion.KEY_FULL_SCREEN
import com.vscodroid.util.EditorLocale
import com.vscodroid.bridge.AndroidBridge
import com.vscodroid.bridge.ClipboardBridge
import com.vscodroid.bridge.SecurityManager
import com.vscodroid.keyboard.ExtraKeyRow
import com.vscodroid.keyboard.KeyInjector
import com.vscodroid.keyboard.KeyboardDismissal
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import com.vscodroid.webview.VSCodroidWebViewClient
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlinx.coroutines.launch
import androidx.core.content.edit

internal fun MainActivity.injectBridgeToken() {
    val targetView = webView ?: return
    val client = targetView.webViewClient as? VSCodroidWebViewClient ?: return
    val page = client.authPage(targetView) ?: return
    val token = JSONObject.quote(securityManager.getSessionToken())
    val origin = JSONObject.quote(page.origin)
    val generation = page.generation
    targetView.evaluateJavascript("""
        (function() {
            if (window !== window.top || location.origin !== $origin || location.pathname !== '/') return null;
            if (!AndroidBridge.isAuthPageCurrent($token, $generation)) return null;
            var metadata = window.__vscodroid = window.__vscodroid || {};
            metadata.authToken = $token;
            metadata.pageGeneration = $generation;
            metadata.authCallbacksReady = true;
            window.dispatchEvent(new Event('vscodroid-bridge-ready'));
            return [$generation, metadata.authCallbacksReady === true];
        })();
    """.trimIndent()) { result ->
        if (webView !== targetView || client.authPage(targetView)?.generation != generation) return@evaluateJavascript
        val verdict = authBootstrapVerdict(result, generation)
        if (verdict != true) client.completeAuthBootstrap(targetView, generation, false)
        // Sign-in registration failing costs the page its sign-in and nothing else: the key
        // row, the touch fixes and the rest below are not what it protects. Only a page
        // that did not answer for this document is left alone, since it is not the workbench.
        if (verdict == null) return@evaluateJavascript
        // Install JS interceptor so ExtraKeyRow Ctrl/Alt modifiers apply to soft keyboard input
        extraKeyRow?.keyInjector?.setupModifierInterceptor()
        // Inject safe area CSS for round-corner devices
        injectSafeAreaCSS()
        // Set up BroadcastChannel relay so browser extensions can reach AndroidBridge
        injectBridgeRelay()
        // Register memory pressure listener
        injectMemoryPressureHandler()
        // Enlarge touch targets for phone-sized screens.
        injectTouchTargetCSS()
        // Keeps the soft keyboard down until the user aims at text
        injectKeyboardGuard()
        // Keeps a context menu open while the keyboard is up, and Escape able to close it
        injectTouchContextMenu()
        // Lets a finger drag the caret
        injectCaretDrag()
        // Keeps an inline list edit (New File, Rename) in view when the keyboard rises
        injectListEditKeeper()
        // Makes Enter on the soft keyboard commit an inline edit or a Quick Open pick,
        // and the quick input's keys work while a word is composing
        injectComposingEnter()
        injectTerminalTypedCharacters()
        // Shows the page's background, not the dismantled workbench, from the moment the
        // page is told to leave until the next one paints
        injectLeaveCurtain()
        // Lets the editor's View > Toggle Full Screen hide the device's own bars
        injectFullScreenShim()
        // Route window.open() through AndroidBridge.
        injectWindowOpenOverride()
        // Answers a paste out of Android's clipboard, which the WebView will not
        injectClipboardReadFallback()
        // Keeps a downloaded blob readable long enough to be saved, and names it
        injectDownloadCapture()
        // Open in Browser, SSH keys and About are contributed by the bundled bridge
        // extension, which registers them through the extension API and reaches Android
        // over the relay below. They cannot be injected from here: the workbench is an
        // ES module and the AMD loader those injections needed does not exist.

        // Last, because everything above is what makes the page able to receive
        // an auth callback at all. From here a callback arriving through
        // onNewIntent goes straight into this page, which is the only page that
        // can consume one. A page whose registration failed receives none.
        if (verdict) {
            workbenchLoaded = true
            client.completeAuthBootstrap(targetView, generation, true)
        }
    }
}

/**
 * Injects CSS into VS Code for round-corner device safe areas.
 *
 * Pads the Activity Bar, Status Bar, title bar, side bar and panel by CSS
 * `env(safe-area-inset-*)`, with a 0px fallback. In practice each of those
 * normally resolves to 0px: [applyWindowInsetsPadding] exists to keep
 * `env(safe-area-inset-*)` at zero inside the page, and the container
 * padding is what keeps the editor clear of the bars and the cutout. The
 * rules only take effect if an inset reaches the page anyway.
 */
internal fun MainActivity.injectSafeAreaCSS() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (document.getElementById('vscodroid-safe-area-css')) return;

            // Ensure viewport-fit=cover meta tag exists
            var meta = document.querySelector('meta[name="viewport"]');
            if (meta) {
                var content = meta.getAttribute('content') || '';
                if (content.indexOf('viewport-fit') === -1) {
                    meta.setAttribute('content', content + ', viewport-fit=cover');
                }
            } else {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                meta.content = 'width=device-width, initial-scale=1.0, viewport-fit=cover';
                document.head.appendChild(meta);
            }

            var style = document.createElement('style');
            style.id = 'vscodroid-safe-area-css';
            style.textContent = [
                '/* VSCodroid: Safe area padding for round-corner devices */',
                '.part.activitybar {',
                '  padding-left: env(safe-area-inset-left, 0px);',
                '  padding-top: env(safe-area-inset-top, 0px);',
                '}',
                '.part.statusbar {',
                '  padding-left: env(safe-area-inset-left, 0px);',
                '  padding-right: env(safe-area-inset-right, 0px);',
                '  padding-bottom: env(safe-area-inset-bottom, 0px);',
                '}',
                '.part.titlebar {',
                '  padding-top: env(safe-area-inset-top, 0px);',
                '  padding-right: env(safe-area-inset-right, 0px);',
                '}',
                '.part.sidebar {',
                '  padding-left: env(safe-area-inset-left, 0px);',
                '}',
                '.part.panel {',
                '  padding-bottom: env(safe-area-inset-bottom, 0px);',
                '}'
            ].join('\n');
            document.head.appendChild(style);
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Tells the page which language to ask for, before it asks.
 *
 * The server decides the page's language from the request it serves the
 * workbench on: a `vscode.nls.locale` cookie if there is one, else the first
 * `Accept-Language` entry. Left alone, that means the header decides the
 * page rather than the app's own locale, and the two can disagree:
 * `android:localeConfig` lets someone set this app to Korean on an English
 * phone, and whether the WebView's header follows the app's locale or the
 * system's is not something this app controls. The failure is not a crash,
 * it is an editor in one language inside an app set to another, with
 * nothing on screen to explain it.
 *
 * So the cookie is written from [EditorLocale]'s answer for the app's own
 * locale, and the header is never consulted. `en` is written rather than nothing
 * when no bundle fits, because a cookie already on the WebView from an
 * earlier language would otherwise stand: the server reads any locale
 * starting with "en" as "serve the English that already ships".
 *
 * Called from [loadVSCode] rather than once per WebView, so a language
 * changed while the app is running is picked up by the next navigation
 * rather than only by the next process. Measured on an emulator: switching
 * the per-app language while the editor was open brought it back in the new
 * language without a restart.
 *
 * The address carries no port, and that is not an omission. A cookie is
 * scoped to a host and a path, never to a port, so this one is sent to the
 * editor server whatever port it ends up on. Naming the port would also put
 * a second expression for the workbench's own address in this file, which
 * `NavigationTokenLoggingTest` refuses: one address means the string that is
 * logged and the string that is loaded cannot drift apart.
 */
internal fun MainActivity.applyEditorLanguage() {
    val bundle = EditorLocale.forDevice(applicationContext.assets) ?: "en"
    try {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("http://127.0.0.1/", "vscode.nls.locale=$bundle; path=/")
        }
    } catch (e: Exception) {
        // The editor is still usable in English, so this is a log line and
        // not a failed start. Broad on purpose: CookieManager reaches into
        // the WebView provider, and a device whose provider is being updated
        // throws from anywhere inside it.
        Logger.w(tag, "Could not set the editor's language cookie: ${e.message}")
    }
}

/**
 * Switches on the workbench's persistent secret storage for the next load.
 *
 * Without this cookie the workbench keeps extension secrets in the page's
 * memory, so every sign-in and API key was gone after the app was closed,
 * reloaded after five minutes in the background, or recovered from a crash.
 * With it, the workbench seals them into its localStorage with a key it asks
 * [VSCodroidWebViewClient] for. Set on every load for the reason the language
 * cookie is: it is how the first load of a new process gets it.
 *
 * A missing cookie costs that one session its persistence and nothing more:
 * the workbench does not open the stored secrets at all, so it cannot lose
 * them.
 */
internal fun MainActivity.applySecretStorage() {
    try {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("http://127.0.0.1/", VSCodroidWebViewClient.secretStorageCookie())
        }
    } catch (e: Exception) {
        Logger.w(tag, "Could not switch on persistent secret storage: ${e.message}")
    }
}

/**
 * Keeps the soft keyboard down until the user actually aims at text.
 *
 * The editor's caret lives in a focusable element the workbench focuses
 * whenever it hands focus back to the editor: after the Explorer opens or
 * closes, after a file is opened from the tree, after a view is toggled. On
 * a desktop that is invisible. Here Chromium answers a focused editing host
 * by raising the input method, so browsing files covered half the screen
 * with a keyboard nobody asked for, which is what people report as "every
 * time I touch something the keyboard appears".
 *
 * The element is `div.native-edit-context`, not a textarea: this build of
 * the workbench uses the EditContext API. That was measured over the
 * DevTools protocol rather than assumed, after a first version of this
 * guard aimed at `textarea.inputarea`, matched nothing at all, and still
 * appeared to work because the keyboard happened not to rise in the one
 * case that was tried. Monaco also keeps a `textarea.ime-text-area` around,
 * which is not the focus target. The selector keeps `textarea.inputarea`
 * beside `.native-edit-context` because the same workbench still builds
 * that textarea as the editing host when `editor.editContext` is false or
 * the WebView has no EditContext.
 *
 * The mechanism is `inputmode="none"`, which tells Chromium to leave the
 * keyboard alone for an element that still takes key events, so a hardware
 * keyboard and the extra key row are unaffected. Three things have to
 * happen for it to hold:
 *
 * - it is set on whatever exists when this runs, and
 * - on any editing host that takes focus later, which is how opening a file
 *   is covered: that builds a new editor, and its element does not exist at
 *   the moment of the tap that opened it. Answering the focus is enough on
 *   its own, and it is answered by taking the focus away and giving it back
 *   with the attribute in place, because by the time the event arrives the
 *   browser has already decided to raise the keyboard. A MutationObserver
 *   over the document did the same job a moment earlier and was dropped: it
 *   ran on every line the editor rendered, which on a phone is every scroll.
 * - it is removed when a touch on text turns out to be a tap rather than a
 *   scroll, which is decided at pointerup by how far the finger travelled.
 *   Deciding it at pointerdown is the obvious shape and is wrong: dragging
 *   inside the editor is how a phone scrolls a file, so it put the keyboard
 *   up over half the screen on every scroll. The element usually already has
 *   focus by then, so no focus event follows; hence the same blur and
 *   refocus, inside the gesture, which is what raises the keyboard.
 *
 * `inputmode` is never changed on a host still composing a word, because
 * that writes the word in again; such a host is left for a later focus or
 * touch. The focus handler never meets one, since a host's composition is
 * finished when it loses focus.
 *
 * Measured on an API 37 emulator at 411dp, `dumpsys input_method` beside a
 * screenshot each time, each starting with the keyboard down. Tapping the
 * Explorer icon: was `mInputShown=true`, now false. Opening a file from the
 * tree: was true, now false. Dragging to scroll a file: was true, now false.
 * Tapping a word: true, with the caret on it and the keyboard up.
 *
 * With the keyboard already up, `inputmode` on the focused host takes it
 * down only where no hardware keyboard is attached. Chromium's
 * `ImeAdapterImpl.updateState` hides the keyboard when the focused
 * element's input mode turns to none, unless the configuration reports a
 * keyboard, and every emulator measured here does (`hw.keyboard=yes`, so
 * `qwerty` in its configuration): there a tap on an activity bar icon,
 * which leaves focus on the editor's host, left the keyboard over the
 * Explorer, and over the Search view with typing still going to the file.
 * On a phone the hold did take it down, except over a word still composing,
 * which apply() leaves alone, and it did so for every touch outside text,
 * a drag, a long press or a button that opens a menu as well (read from
 * Chromium's source, not measured on a phone). So the hold is no longer
 * written on the focused host while the keyboard is up, and the keyboard
 * is asked down only for a tap. On API 33 and 37 emulators a More Actions
 * menu and a drag along the activity bar then left that host without the
 * attribute, with the keyboard up.
 * A tap outside text (the same travel and duration test as a tap on text,
 * or a tap by the workbench's own touch gesture, which is looser and which
 * the activity bar, the status bar and the tabs act on: a touch on the
 * Search icon that slid 20 CSS px, or a press of 620 ms on the Explorer
 * icon, opened the view with the keyboard left up and typing still going to
 * the file, measured on an API 33 emulator with Gboard 12.4; with the
 * gesture's tap counted, a press of 620 ms and a slide of 20 px took it down
 * on API 33 and 37 emulators) therefore asks
 * for the keyboard down with `navigator.virtualKeyboard.hide()`
 * once the tap's own handlers have run, only if an editing host still has
 * focus and the keyboard is reported up, under `virtualKeyboardPolicy`
 * `manual`, which the dismissal hook, a tap on text or a one second timeout
 * puts back to `auto`, and the dismissal hook then holds that host. A tab,
 * which takes focus, needs none of it. Nor is
 * it done for a button that opens a menu, marked `aria-haspopup="true"` as
 * the views' and the editor's More Actions, Accounts, Manage and the
 * Application Menu are: the keyboard going down resizes the window, and
 * the menu the tap had just opened closed with it. Measured on an API 33
 * emulator with Gboard 12.4: with the keyboard up, the Explorer's Views and
 * More Actions and the editor's More Actions opened no lasting menu, and
 * with `navigator.virtualKeyboard.hide` stubbed out over DevTools the first
 * stayed open. Hiding
 * the keyboard ends a word it was composing, as Back does, which is why
 * this waits for patch 0025: before it, that end opened the suggestion
 * list, and a list already open stayed open, since nothing took focus from
 * the editor, floating over the view the tap had opened, where the next tap
 * accepted a suggestion into the file. Patch 0025 ends the editor's
 * suggestion session on any touch outside it and leaves that end nothing to
 * arm. Measured on an API 33 emulator with Gboard 12.4, as a DevTools
 * candidate: the Explorer icon put the keyboard down 43 ms after the tap,
 * also over an underlined word, which stayed as it was; the Search icon put
 * it down, and a tap in the search box raised it with focus in the box.
 *
 * A tap anywhere inside the editor counts as one on text, the margin and the
 * empty space under the last line included, because Monaco moves the caret
 * there. With the editor already focused, the usual state after a file is
 * opened, such a tap raises the keyboard: the refocus answers whatever was
 * under the finger. Measured on an API 33 emulator with Gboard 12.4, a tap
 * under the last line put the caret on it with the keyboard up. A touch that
 * takes focus out of the editor first leaves the keyboard down, which the
 * scrollbar does: after a tap on it focus was on the workbench, not the
 * editor.
 *
 * The hold comes back when the keyboard goes away. A user who puts it away
 * with Back or the navigation bar's hide key leaves the editor focused and
 * without `inputmode`, and Chromium raises the keyboard again for any touch
 * on a focused editable element, so the next scroll of the file brought it
 * back. [ExtraKeyRow] reports the keyboard going down from its inset
 * listener, and half a second later, if it is still away,
 * `window.__vscodroidKeyboardDismissed` puts the hold back on every host that
 * is not composing, which leaves a tap on text as the only way up again. A keyboard that went down because this script held it, or
 * because a touch outside text took focus away, finds nothing to change,
 * except a host that was still composing at that touch: apply() skipped it
 * then, and it gets the hold now, which the focus returning would give it
 * anyway. A keyboard reported down while nothing that takes text has focus
 * is not taken as put away: Chromium takes it down as focus leaves text,
 * and Enter does that on its way into an editor when Quick Open opens a
 * file not open yet, which leaves focus on the page while the file loads,
 * or when New File leaves it on the Explorer's list. Read as put away, it
 * cleared what the typing in the box had counted, and the file opened
 * held, with the keyboard down: measured on API 33, 36 and 37 emulators.
 * So that report clears nothing a tap or typing counted, and the new
 * editor's focus raises the keyboard again, measured on the API 33
 * emulator with Gboard 12.4 with the hook skipped in that gap over
 * DevTools, and with this rule built in on API 33 and 37 emulators, with
 * Gboard 12.4 and 17.7.4, for files opened from Quick Open and created in
 * New File. For a fixed 4 s window after a text field took focus, a
 * dismissal report waits for the end of that window and checks again: when
 * the file finishes loading and focuses its new host before the half-second
 * timer runs, while the keyboard is still rising or briefly bounces down
 * and up again inside 4 s, text already has focus at the call and holding
 * it then would take or keep the keyboard down; if the keyboard is still
 * reported down when the 4 s window ends it is held. A visible report
 * clears the pending hide mark and cancels any pending late check so it
 * does not fire stale, without ending the 4 s window early. A dismissal
 * whose hide report preceded the last tap on text is ignored, so a
 * half-second callback arriving after a tap cannot undo that tap. A scroll
 * inside the 4 s window can still raise a keyboard put away with
 * Back, as a scroll inside the half second always could. The test otherwise
 * is what has focus, so Back while an extension's
 * webview has focus, an iframe, is read the same way, as is the keyboard
 * Chromium takes down when a terminal that was typed into goes away: what a
 * tap or typing counted stays, and an editor focused next without a touch
 * can take the keyboard up again (read from the code, not measured).
 * Measured on the same emulator with the guard built in: a tap on a word,
 * then Back or the hide key, then a drag and a fling left the keyboard
 * down and a tap on a word raised it; the same scroll with the hook
 * replaced over DevTools raised it. Back while a word was underlined
 * ended the word first and the hold came back. Turning the phone with the
 * keyboard up never reported it down there, so on that emulator a
 * rotation does not reach the hook. On an API 36 emulator with Gboard 18.2
 * it does: each turn reports the keyboard down and, 150 to 650 ms later, up
 * again. That emulator reports a hardware keyboard, where a hold never
 * takes a keyboard down, so with the hook running at the report the
 * keyboard was up again after turning to landscape and back and the next
 * letter landed at the caret. On an API 36 emulator with `hw.keyboard=no`,
 * which reports none as a phone does, the same hook took the keyboard down
 * at the turn to landscape, and it stayed down, with `inputmode="none"` on
 * the host, for as long as it was watched; with the hook replaced by a
 * function that does nothing it stayed up. [KeyboardDismissal] therefore
 * calls the hook half a second after the report, and not if the keyboard is
 * back by then or a turn of the screen explains the report (a change of its
 * shape, [isScreenTurn]; a change of night mode or of the keyboard attached
 * is not one): any report within two seconds of the turn, and where it found
 * the keyboard up and the app refocuses the editor for it, one until
 * [KeyboardDismissal.AFTER_REFOCUS_MS] after that refocus
 * ([KeyboardDismissal.TURN_CAP_MS] after the turn at most). The cost is
 * that a scroll in that half second can raise a keyboard put away with Back,
 * as it did before the hold existed. Not measured: switching keyboards with
 * the globe key and leaving the app with the keyboard up, either of which
 * may report the same down and up; a hardware keyboard attached to a phone;
 * a floating or split keyboard; and keyboards other than Gboard.
 *
 * A read-only editor never lets the keyboard up, so a file made read-only
 * with File: Toggle Active Editor Read-only in Session or matched by
 * `files.readonlyInclude` can be read and scrolled with taps that only move
 * the caret. The marker is Monaco's: it writes `aria-autocomplete="none"` on
 * the EditContext host while its editor is read-only and `"both"` otherwise,
 * again on every option change (`ScreenReaderSupport._updateDomAttributes`
 * in the 1.139.1 bundle). Measured on the same emulator: with the session
 * toggle on, a tap on a word moved the caret and left the keyboard down and
 * a long press opened the menu with Copy and Select All; toggled off, a tap
 * on a word raised it. `files.readonlyInclude` goes through the same
 * read-only check to the same option (read from the bundle, not measured).
 *
 * The marker is an accessibility attribute, not an API, so it has limits.
 * Anything that calls the editor's `setAriaOptions` writes `"both"`, or
 * `"list"` when it names an active descendant, until the next option change. The Output panel's own call runs before its
 * editor has a model, when there is no host yet, and writes nothing, so
 * the Output editor, which is always read-only, is held like any other
 * (read from the bundle, not measured). On the textarea host nothing
 * rewrites the value from the read-only option after the host is created,
 * so on that path the gate is not applied at all and a read-only editor
 * behaves as before. `KeyboardGuardWiringTest`
 * checks the marker against the packaged bundle wherever the tree is
 * present, the release build's unit run included.
 *
 * The terminal's helper textarea is held and let up like the editor's host,
 * and a tap on the terminal counts as one on text. Left alone, as it was,
 * the terminal kept the keyboard up after a tap outside text, with typing
 * going to the shell behind the Source Control view, and after Back the
 * same tap raised the keyboard again: measured on an API 36 emulator with
 * Gboard 18.4. The cost is that a terminal focused from a tap outside text,
 * its tab in the panel say, opens with the keyboard down until it is
 * tapped, as a file opened from the Explorer does.
 *
 * Typing with the keyboard up counts as aiming at text, in a text box as in
 * an editor. A box can take focus without a tap on text: the Command
 * Palette opened from the Application Menu is typed into with the last
 * touch on that menu, and Enter on Terminal: Create New Terminal moved focus
 * into the new terminal, which was held, so the keyboard went down under a
 * terminal opened to be typed into: measured on an API 33 emulator with
 * Gboard 12.4. With this rule the terminal opened with the keyboard up on
 * API 33, 36 and 37 emulators. A file Enter opens from Quick Open is the
 * same case, as is one Enter creates in the Explorer's New File box: a file
 * open already kept the keyboard up on those three emulators, and one not
 * open yet needs the dismissal hook's rule above as well. A tap on the
 * palette's row is a tap outside text, and what it opens starts with the
 * keyboard down.
 *
 * A host blurred and focused again in one task, with the keyboard up, keeps
 * it. Patch 0024 restarts input that way when the editor has rewritten the
 * keyboard's text under a caret left at the same offset, and the key row
 * does after a latched chord. The focus handler took that focus for a new
 * one and held the host whenever the last touch had been outside text and
 * left the keyboard up, a drag, a long press or a button that opens a menu.
 * Written at that focus, `inputmode="none"` turns the focused element's
 * input mode to none, which on a phone takes the keyboard down (read from
 * Chromium's source, as above), so after a drag on the activity bar a
 * trackpad Down along a column would put it away there. The emulators
 * measured report a hardware keyboard and never show it. Such a focus now
 * holds nothing. A hold already on the host, which a turn of the phone
 * leaves on the API 36 emulator (above), comes off with a refocus of its
 * own, and the composition finish below takes one off between its blur
 * and its focus. That hold reached Chromium when it was written, so a
 * restart that meets it changes no input mode and takes nothing down.
 * A hold the focus handler put on with the keyboard up, to take it down,
 * is left until the keyboard is reported down or a tap on text lets it up.
 * Replayed in headless Chromium with the 1.139.1 bundle: after a drag
 * outside text, a cursor key along a column left the host focused without
 * the attribute, also with a word composed before or after the drag, where
 * the script before this left it held. A touch on the Explorer's tree is no
 * such touch: the list takes focus at the touch and the keyboard goes down
 * with it.
 *
 * The same script ends a word the keyboard is still composing when a key or
 * a tap moves the editor's caret, or when [onConfigurationChanged] asks for it on a turn of
 * the screen (`window.__vscodroidFinishCompositionOnTurn`, through
 * [finishKeyboardCompositionOnTurn]). Chromium
 * keeps an EditContext composition's range where it was when the page moves
 * the selection (crbug 379170477), so with a keyboard that composes, moving
 * the caret while a word was underlined made the keyboard's next edit land
 * on the word's old range: End while `kiwi` was underlined in
 * `alpha delta kiwi charlie` left `alpha delta charlie charlie`, a tap on
 * an empty line followed by `cha` typed `cchcha`, and Tab accepting
 * `alpha3` over `alp` left `alpha3ha3`. Blurring and refocusing the host
 * ends the composition in the same task as the caret move, so the keyboard
 * starts again from the new caret. That runs for the arrows, Home, End,
 * PageUp, PageDown, Tab, Enter, Backspace and Delete, and for a key that
 * types a character (below), from a window capture `keydown`, whatever
 * sends them, for the editor's own `-monaco-gesturetap` and
 * `-monaco-gesturecontextmenu` (a long press) inside `.monaco-editor`, and
 * for `window.__vscodroidFinishComposition`;
 * only for a focused host that has an `editContext` and is in the script's
 * `composing` set, and with `reapplying` set, so the focus handler leaves
 * the refocus and `inputmode` alone.
 *
 * It runs before the editor handles the key or the tap, except where an
 * open suggestion list takes a key without modifiers, or a tap. Ending a
 * composition makes the editor refilter the list and highlight its first
 * row again, so ending it first would make Tab or Enter accept the first
 * suggestion instead of the highlighted one. Up, Down, PageUp and PageDown
 * that move the list's highlight move no caret and end nothing. Tab or
 * Enter accepting the highlighted suggestion and a tap on a row end it after
 * the list has acted, still in the same task, from a listener added while
 * the event is on its way down: it runs after the ones already registered
 * where it is added, window for a key, where the workbench's keybindings run
 * in the bubble phase, and the target for a gesture, which does not bubble.
 * When the event is stopped before that listener, a zero timeout ends it
 * instead, in a later task. A long press on the list ends nothing. With no
 * list open, ending a composition that typed makes the editor schedule a
 * quick suggestion 10 ms later, patch 0025 scheduling none for one that
 * typed nothing, and patch 0023 cancels it when a cursor key then moves
 * the caret: otherwise the list opened at the key's destination, such as
 * after End on a line ending in an identifier.
 *
 * A key that types a character ends it too, and so does Enter, in the same
 * listener and for the same reason. The key row's characters and a hardware
 * keyboard's reach the page as real keys, and the page inserts the
 * character, or the newline, at the caret while the keyboard still holds its
 * composition over the old range, which the keyboard then writes its word
 * over. On an API 29 emulator with Gboard 8.0.4 and WebView 160, a tap
 * between the O and the R of `WORD ONE` and then `{` from the key row left
 * `RDD ONE`, and a real Enter from `adb shell input keyevent` left the same
 * `RDD ONE` with the newline gone; Auto Save wrote both. A key counts when
 * its `key` is one character, counted in code points so that one outside the
 * BMP is one, with no Ctrl, Alt or Meta held and a key code other than 229.
 * The keyboard's own events while it composes, which carry key
 * `Unidentified` (`Process` on a desktop) and key code 229, do not count,
 * since ending the word on them would end it at every letter: four letters
 * tapped on Gboard in a row grew one composition and ended nothing. A chord
 * does not count, and the key row's latched modifiers are announced as
 * chords. `isComposing` is not read: it is false on the row's keys and on
 * the keyboard's own events alike, so it separates nothing. Enter is
 * finished as Tab is, after the list when the list takes it, because the
 * editor binds both to the same command. With this code installed over
 * DevTools rather than built in, the same tap and `{` left `WO{RD ONE` in
 * three runs, still so after 10 s and on disk, and the same tap and Enter
 * left `WO` and `RD ONE` on two lines.
 *
 * No character counts while the suggestion list is showing. A commit
 * character accepts the list's focused row too, but from the editor's
 * typing, which runs once the key has been dispatched (`onWillType` is fired
 * when the text arrives), and ending the word before the key, or after the
 * keybindings as for Tab, makes the editor refilter the list and focus its
 * first row again before that accept. The word is then left as it was, and
 * so is the hazard: the old behaviour.
 *
 * Measured on an API 33 emulator with Gboard 12.4, with this code installed
 * over DevTools rather than built in: End, Home, PageUp, the trackpad in
 * all four directions, Gboard's Backspace at the start of a line, a tap
 * elsewhere in the file and a long press left the text intact; Down twice
 * then Tab inserted the third suggestion and a tap on a row inserted that
 * row, both intact; a double tap still selected the word; and typing on the
 * keyboard, the Command Palette and the Explorer's rename box never set it
 * off.
 * `scripts/test-keyboard-guard.js` runs this part of the script.
 *
 * Commands and extensions are not keys or gestures this guard handles.
 * Patch 0046 ends a composition still open after an edit changes the buffer.
 * Measured on the built editor with Gboard 12.4 and WebView 134 on API 33:
 * long-press Paste and hardware Ctrl+V each left `helloabXYZ` with one editor
 * restart; Edit > Paste left it with none, the menu having ended composition.
 * From the source, Undo and Redo, a snippet, an extension's edit, and Enter
 * accepting a suggestion also reach the rule if composition remains open.
 * That Enter route was not reached in the measured suggestion-list sequence:
 * Gboard 12.4 typed `alp` and a newline; on Gboard 8.0.4's baseline, composition
 * ended before Enter accepted `alpha1`. Find, Go to Line or another command
 * that moves the buffer to a different line's text is also a source-only route.
 * A within-line move that changes no buffer text and the textarea path remain
 * uncovered. The input method's synchronous work is excluded; later work such
 * as format-on-type can commit an unconverted word. Restarts coalesce per
 * pending batch, not across an edit made by composition-end itself.
 * Not measured: keyboards other than Gboard, Korean and Chinese input, and
 * other Monaco editors such as the Source Control message box and chat input.
 * What it costs: the word is committed as typed, so on a Japanese keyboard a
 * guarded key or tap commits kana unconverted; a chord the suggestion list
 * also binds, such as Shift+Tab or Ctrl+Down, ends the word first, so the list
 * acts from its first row.
 */
internal fun MainActivity.injectKeyboardGuard() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__vscodroidKeyboardGuard) return;
            window.__vscodroidKeyboardGuard = true;
            // What counts as aiming at text: anywhere inside an editor, and
            // any real input, which covers the Command Palette, the find
            // widget and every extension form.
            //
            // The whole editor rather than its lines, because everything
            // inside one belongs to the act of typing in it: the margin
            // (tapping a line number moves the caret), the empty space under
            // the last line, and the widgets the editor renders inside
            // itself. That last one is the reason it is not narrower. The
            // suggest list is a child of `.monaco-editor`, so a narrower
            // selector reads "tap a completion" as "not text" and takes the
            // keyboard away in the middle of typing, which is worse than the
            // problem this guard exists to solve.
            //
            // The trade, stated rather than discovered later: sticky scroll,
            // CodeLens, the minimap for anyone who turns it on, and the find
            // widget's buttons are all inside an editor too, so a tap on one
            // raises the keyboard when it leaves the editor focused. The
            // scrollbar does not: it takes focus out of the editor, so the
            // refocus in letTheKeyboardUp() has nothing to act on, measured
            // on an API 33 emulator. That is what every build before this
            // guard did for those targets and for every other one, so it is
            // where this change leaves them rather than something it
            // introduces; the alternative is a selector that has to name
            // each of them and be corrected on every VS Code bump that
            // renames one.
            //
            // The terminal is text in the same way: a tap on it is how the
            // shell is typed into, and its helper textarea is an editing host
            // like the editor's. Left out, a tap outside text with the
            // terminal focused left the keyboard up over the view it opened,
            // with what was typed going to the shell behind it, and once the
            // keyboard had been put away the same tap raised it again, as
            // Chromium raises it for a tap anywhere while an editable element
            // without inputmode="none" has focus: measured on an API 36
            // emulator with Gboard 18.4, the Source Control and Explorer icons
            // did both. The cost is that a terminal focused from a tap outside
            // text, its tab in the panel say, opens with the keyboard down
            // until it is tapped, as a file opened from the Explorer does.
            var TEXT = '.monaco-editor, textarea, input, [contenteditable="true"], .native-edit-context, .xterm';
            var EDITING_HOST = '.native-edit-context, .monaco-editor textarea.inputarea, .xterm-helper-textarea';
            var aimedAtText = false;
            // Set while this code is taking focus away and giving it back,
            // so the focus it causes is not treated as one to answer.
            var reapplying = false;
            // Where a touch on text went down, while it is still undecided
            // whether it is a tap or the beginning of a scroll. Null at any
            // other moment.
            var pendingTap = null;
            // How far a finger may travel and still be a tap, in CSS pixels.
            // Chromium's own touch slop is 8; this is looser because the
            // target is a line of code rather than a button.
            var TAP_SLOP = 12;
            // Longer than this and the finger was not tapping, whatever it
            // travelled. A press that reaches the editor's own hold gesture
            // opens a context menu instead, and raising the keyboard for it
            // destroys that menu: focus returns to the editing host, Android
            // resizes the window, and the workbench answers the resize by
            // hiding every context view. Measured on an API 36 emulator with
            // the keyboard down: pointerup at t+985ms, the editor's
            // `-monaco-gesturecontextmenu` at t+995ms, the window resize at
            // t+1621ms, and no menu on screen afterwards.
            //
            // 500 rather than the 700 the editor's own gesture waits for.
            // The gesture decides on touchend, from the same lift this
            // pointerup reports, and opens a menu only for a hold of at least
            // 700, so any press it turns into a menu is already past 500 here.
            // The margin costs only that a deliberately slow tap between 500
            // and 700ms leaves the keyboard down. That is a second tap, against
            // a menu that could not be opened at all. It holds while the page
            // keeps up: the gesture times the press with Date.now() as its
            // touchstart and touchend handlers run, this with the events' own
            // timestamps, so on an API 37 emulator under heavy host load, with
            // the handlers about 700 ms late, the gesture opened the editor's
            // menu for a press this read as a tap, and the keyboard it let up
            // rose once the menu was closed.
            var LONG_PRESS_MS = 500;
            // Where a touch outside text went down, until it is lifted, and
            // the host the keyboard was last asked down for, until it is
            // down. See hideTheKeyboard().
            var outsideTap = null;
            var manualHost = null;
            // Editing hosts holding a word the on-screen keyboard is still
            // composing. Chromium answers a changed `inputmode` on the
            // focused element by restarting input, which writes the composed
            // word in again, reversed and several times over: measured on an
            // API 33 emulator with Gboard, `xyz` then a tap on the Search icon
            // left `xyzzyxzyxzyx` in the file. So a composing host is left as
            // it is while the composition is open; a dismissal hold waiting on
            // it runs when the composition ends, and the next focus is answered
            // by the handler below.
            //
            // The editor composes through EditContext, whose composition
            // events fire on `element.editContext` and never reach the
            // document, measured with listeners on both. The document ones
            // cover the `textarea.inputarea` host this same workbench builds
            // when `editor.editContext` is false or the WebView has no
            // EditContext.
            var composing = new WeakSet();
            var watched = new WeakSet();
            var holdAfterComposition = null;
            // The host the page itself is blurring and focusing again (refocusHost), while it does.
            var refocusing = null;
            var compositionTurn = null;
            function cancelCompositionTurn() {
                if (!compositionTurn) return;
                if (compositionTurn.timer !== null) clearTimeout(compositionTurn.timer);
                clearTimeout(compositionTurn.expiry);
                compositionTurn = null;
            }
            // A word started in the focused host while the keyboard is not
            // held down is the user typing. Without this, a tap outside text
            // that leaves focus in a composing host clears aimedAtText while
            // the keyboard stays up, and the next refocus of that host takes
            // the keyboard away mid-typing. During a turn wait, a composition
            // opened by the keyboard with unchanged text is finished in the
            // next task instead of cancelling the turn or counting as a tap.
            function composeStarted(element) {
                composing.add(element);
                if (compositionTurn && compositionTurn.element === element) {
                    scheduleCompositionTurnFinish();
                    return;
                }
                if (element === document.activeElement && element.getAttribute('inputmode') !== 'none') aimedAtText = true;
            }
            function composeEnded(element) {
                composing.delete(element);
                if (holdAfterComposition === element) {
                    holdAfterComposition = null;
                    if (element === document.activeElement || element === refocusing) apply(element);
                }
            }
            function watch(element) {
                var context = element.editContext;
                if (!context || watched.has(context)) return;
                watched.add(context);
                context.addEventListener('compositionstart', function() { composeStarted(element); });
                context.addEventListener('compositionend', function() { composeEnded(element); });
                context.addEventListener('textupdate', function() {
                    if (!compositionTurn || compositionTurn.element !== element) return;
                    if (turnUnchanged()) {
                        scheduleCompositionTurnFinish();
                    } else {
                        cancelCompositionTurn();
                        if (element === document.activeElement && element.getAttribute('inputmode') !== 'none') aimedAtText = true;
                    }
                });
            }
            document.addEventListener('compositionstart', function(e) {
                if (e.target && e.target.matches && e.target.matches(EDITING_HOST)) composeStarted(e.target);
            }, true);
            document.addEventListener('compositionend', function(e) {
                if (e.target) composeEnded(e.target);
            }, true);
            // Typing in any box with the keyboard up is aiming at text too.
            // The Command Palette opened from a menu is typed into with the
            // last touch outside text, and Enter there can move focus into
            // an editing host, a new terminal or a file from Quick Open,
            // which the focus handler then held: the keyboard went down
            // under a terminal opened to be typed into. Only real typing
            // counts, not the untrusted events the key row's chords
            // dispatch, and only with the keyboard up: typing on a hardware
            // keyboard is no reason to bring the soft keyboard up.
            document.addEventListener('beforeinput', function(e) {
                if (e.isTrusted && window.__vscodroidImeVisible && e.target && e.target.closest && e.target.closest(TEXT)) aimedAtText = true;
            }, true);
            // An empty commit ends an EditContext composition without a
            // compositionend, and a host left in the set is one apply()
            // never lets the keyboard up for again. Losing focus is the
            // reliable end: Blink finishes the composition of the element
            // it takes focus from, and of the page when the page loses it.
            //
            // The element that lost focus is kept until the script that
            // blurred it has returned, the next microtask checkpoint, so
            // the focus handler can tell a host blurred and focused again
            // in one run of script, which never really lost focus, from
            // one that takes focus later.
            var blurred = null;
            document.addEventListener('focusout', function(e) {
                if (!reapplying && compositionTurn && compositionTurn.element === e.target) cancelCompositionTurn();
                if (e.target) {
                    composing.delete(e.target);
                    // Kept through the page's own refocus of the host: its focusin is skipped while reapplying.
                    if (holdAfterComposition === e.target && refocusing !== e.target) holdAfterComposition = null;
                }
                blurred = e.target;
                queueMicrotask(function() { blurred = null; });
            }, true);
            // A read-only editor takes no typing, so touching one is reading
            // it, and its host never loses the hold. Monaco writes
            // aria-autocomplete="none" on the EditContext host of a read-only
            // editor, again on every option change; the KDoc says where that
            // falls short. On the textarea host nothing rewrites the value
            // from the read-only option after the host is created, so it
            // would keep the keyboard from an editor made writable later,
            // and is left out: a read-only editor on that path behaves as
            // before.
            function writable(element) {
                return !element.editContext || element.getAttribute('aria-autocomplete') !== 'none';
            }
            // A host the focus handler held while the keyboard was up, to
            // take it down: a file switched to with a chord from the key
            // row after a drag outside text, say. Until the keyboard is
            // reported down or a tap on text lets it up, a refocus of it
            // keeps the hold, so that an input restart in the meantime does
            // not undo it: the editor restarts input when it first writes a
            // new host's buffer with the caret at offset 0, such as at Down
            // from the first line's start.
            var heldAtFocus = null;
            // Whether a refocus of this host leaves the keyboard where it
            // is: up, over an editor that takes typing, and not being taken
            // down by this script.
            function keepsTheKeyboard(element) {
                return window.__vscodroidImeVisible && element !== heldAtFocus && writable(element);
            }
            function apply(element) {
                watch(element);
                if (composing.has(element)) return;
                if (aimedAtText && writable(element)) element.removeAttribute('inputmode');
                else if (element.getAttribute('inputmode') !== 'none') element.setAttribute('inputmode', 'none');
            }
            function applyAll() {
                document.querySelectorAll(EDITING_HOST).forEach(apply);
            }
            // Lets the keyboard up for a touch that has turned out to be a
            // tap on text.
            //
            // The element usually already has focus by then, so no focus
            // event follows to act on; hence the blur and refocus, which
            // happens inside the gesture and is what raises the keyboard.
            // Only when the guard was actually holding it down: a tap on
            // text while the keyboard is already up must not reach the focus
            // from here, because blurring an element mid-composition ends
            // the composition, and composition is an ordinary path now that
            // the editor ships in Japanese, Korean and both Chinese scripts.
            // finishComposition() below ends one on purpose, and only where
            // a key or a tap moves the caret.
            function letTheKeyboardUp() {
                letUpAt = ++stamp;
                holdAfterComposition = null;
                cancelLateCheck();
                aimedAtText = true;
                heldAtFocus = null;
                restorePolicy();
                var focused = document.activeElement;
                var wasHeldDown = !!(focused && focused.getAttribute &&
                    focused.getAttribute('inputmode') === 'none');
                applyAll();
                if (wasHeldDown && focused.matches && focused.matches(EDITING_HOST) && writable(focused)) {
                    reapplying = true;
                    focused.blur();
                    focused.focus();
                    reapplying = false;
                }
            }
            // A tap outside text that leaves the editing host focused, as the
            // activity bar's icons do, left a keyboard that was up where it
            // was: inputmode on a host that already has focus takes a visible
            // keyboard down only where no hardware keyboard is attached, the
            // touch writes none on the focused host while the keyboard is up,
            // and apply() leaves a composing host alone besides. So the
            // keyboard is asked down directly, once the
            // tap's own handlers have run and only if a host still has focus,
            // under a manual policy, which makes Chromium hide it on request.
            // The policy goes back to auto when the keyboard is reported
            // down, or after a second if that report never comes, so the
            // next tap on text raises it as before. A second request first
            // puts back the host of the one before: only the last host is
            // remembered, and one left on manual would never raise the
            // keyboard for a tap again until the page reloads.
            function hideTheKeyboard() {
                restorePolicy();
                var host = document.activeElement;
                if (!window.__vscodroidImeVisible || !navigator.virtualKeyboard ||
                    !host || !host.matches || !host.matches(EDITING_HOST)) return;
                host.virtualKeyboardPolicy = 'manual';
                manualHost = host;
                navigator.virtualKeyboard.hide();
                setTimeout(restorePolicy, 1000);
            }
            function restorePolicy() {
                if (!manualHost) return;
                manualHost.virtualKeyboardPolicy = 'auto';
                manualHost = null;
                if (window.__vscodroidImeVisible === false && !aimedAtText) applyAll();
            }
            // Whether the workbench's own touch gesture took the touch as a
            // tap. The activity bar, the status bar and the tabs act on that
            // tap, which it grants for a press under 700 ms that stays within
            // 30 px on each axis, looser than the test here: a touch on the
            // Search icon that slid 20 px, or a press of 620 ms on the
            // Explorer icon, opened the view and left the keyboard up, with
            // typing still going to the file, measured on an API 33 emulator.
            // Its event is dispatched from touchend, after pointerup and
            // before the zero timeout that pointerup schedules.
            var gestureTapped = false;
            window.addEventListener('-monaco-gesturetap', function() { gestureTapped = true; }, true);
            document.addEventListener('pointerdown', function(e) {
                var target = e.target;
                outsideTap = null;
                gestureTapped = false;
                // A touch inside an open context menu decides nothing about the
                // keyboard, and letting it decide destroys the menu.
                //
                // The editor's menu is built in a shadow root whose host is a
                // CHILD OF THE EDITOR: `ContextView.setContainer` appends
                // `div.shadow-root-host` to the container it was given, and for
                // an editor menu that container is the editor's own DOM node.
                // A pointer event inside the shadow tree is retargeted to that
                // host, so `closest(TEXT)` below matches on the first parent and
                // reads a tap on a menu item as a tap on the file. Measured on an
                // API 36 emulator with the keyboard down: long press opens the
                // 29-item menu with the viewport at 845, then tapping an item
                // takes the viewport to 458 and the menu with it, because
                // letTheKeyboardUp() blurs and refocuses an editing host that is
                // still holding `inputmode="none"`.
                //
                // It reaches further than the items. The menu renders a
                // full-viewport `.context-view-block` to catch a dismissing tap,
                // so before this test EVERY point on the screen belonged to the
                // editor as far as the line below was concerned.
                //
                // An early return rather than falling through to the branch under
                // it: that branch clears `aimedAtText` and puts `inputmode="none"`
                // back on the editing hosts, which would hold whatever a menu
                // opened while the user was typing goes on to focus. Nothing
                // about the keyboard should change because a menu was touched.
                //
                // Both shapes are covered. A shadow-DOM menu retargets to the host
                // itself, which carries the class; a light-DOM one (the explorer,
                // the terminal, the menubar) leaves the target inside
                // `.context-view`, and `closest` reaches it there.
                //
                // So does a target already gone from the page. A touch outside
                // an open menu closes it from a window listener that runs before
                // this one, which removes the menu's block, the touch's target,
                // so `closest` finds no menu above it. Read as a tap outside
                // text, closing a menu with the keyboard up asked the keyboard
                // down, measured on an API 33 emulator.
                if (target && ((target.classList && target.classList.contains('shadow-root-host')) ||
                    (target.closest && target.closest('.context-view')) || target.isConnected === false)) {
                    pendingTap = null;
                    return;
                }
                if (target && target.closest && target.closest(TEXT)) {
                    // Undecided, and that is the point. Dragging inside the
                    // editor is how a phone scrolls a file, and it goes down
                    // on the same text a tap does, so raising the keyboard
                    // here puts it over half the screen on every scroll:
                    // the complaint this guard exists for, reached by
                    // another route. Measured on a file opened with the
                    // keyboard down, before this branch was written.
                    pendingTap = { id: e.pointerId, x: e.clientX, y: e.clientY, at: e.timeStamp };
                    return;
                }
                pendingTap = null;
                outsideTap = { id: e.pointerId, x: e.clientX, y: e.clientY, at: e.timeStamp,
                    opensMenu: !!(target && target.closest && target.closest('[aria-haspopup="true"]')) };
                aimedAtText = false;
                // Every host but the focused one while the keyboard is up. On
                // a phone Chromium takes the keyboard down as soon as the
                // focused element's inputmode turns to none, at the next frame,
                // unless a hardware keyboard is attached (ImeAdapterImpl's
                // updateState), so the hold alone would put it away for a drag,
                // a long press or a button that opens a menu, where it stays
                // up. Every emulator here reports a hardware keyboard, so none
                // of them shows the difference. A tap asks for the keyboard
                // down below, and the dismissal hook then holds that host.
                var focused = document.activeElement;
                document.querySelectorAll(EDITING_HOST).forEach(function(element) {
                    if (element !== focused || !window.__vscodroidImeVisible) apply(element);
                });
            }, true);
            document.addEventListener('pointerup', function(e) {
                // The same tap test as for text below, or the workbench's own,
                // which decides for the targets that act on it. A button that
                // opens a menu, such as a view's More Actions, keeps the
                // keyboard: putting it away resizes the window, and the menu the
                // tap had just opened closed with the resize.
                if (outsideTap && outsideTap.id === e.pointerId) {
                    var tap = outsideTap;
                    var moved = Math.abs(e.clientX - tap.x) + Math.abs(e.clientY - tap.y);
                    var tapped = moved <= TAP_SLOP && e.timeStamp - tap.at < LONG_PRESS_MS;
                    outsideTap = null;
                    if (!tap.opensMenu) setTimeout(function() { if (tapped || gestureTapped) hideTheKeyboard(); }, 0);
                    return;
                }
                // Keyed by pointer, because a second finger anywhere on the
                // page would otherwise answer for the first: the last touch
                // down wins the single slot, and lifting either one is read
                // as the end of that gesture.
                if (!pendingTap || pendingTap.id !== e.pointerId) return;
                var travelled = Math.abs(e.clientX - pendingTap.x) +
                    Math.abs(e.clientY - pendingTap.y);
                var held = e.timeStamp - pendingTap.at;
                pendingTap = null;
                // A scroll leaves the keyboard where it was, which is down.
                if (travelled > TAP_SLOP) return;
                // A caret drag of a character or two travels no further than a tap
                // does. injectCaretDrag marks the touch it takes, and a touch that
                // moved the caret is not a request for the keyboard.
                if (window.__vscodroidCaretDragged) return;
                // So does a long press, which asked for a menu and not a
                // keyboard. Both event timestamps come from the same clock,
                // so this is a duration and not a wall-clock read.
                if (held >= LONG_PRESS_MS) return;
                letTheKeyboardUp();
            }, true);
            document.addEventListener('pointercancel', function(e) {
                if (outsideTap && outsideTap.id === e.pointerId) outsideTap = null;
                if (pendingTap && pendingTap.id !== e.pointerId) return;
                // The gesture became the system's: a swipe from an edge, a
                // pull down, a second finger. Nothing was decided, so
                // nothing changes.
                pendingTap = null;
            }, true);
            // Focus is answered directly rather than watched for, because an
            // editing host built for a file that is being opened is focused
            // in the same breath as it is inserted: by the time anything
            // observing the document is told, the browser has already
            // decided to raise the keyboard. Blurring and refocusing puts
            // the attribute in place before focus is granted rather than a
            // moment after.
            var textFocusedAt = -Infinity;
            var textFocusedWallAt = -Infinity;
            document.addEventListener('focusin', function(e) {
                var target = e.target;
                // Watched on every focus, whatever else is decided here, so a
                // composition that starts after this is known to apply().
                if (target && target.matches && target.matches(EDITING_HOST)) watch(target);
                if (reapplying) return;
                if (target && target.matches && target.matches(TEXT)) {
                    textFocusedAt = performance.now();
                    textFocusedWallAt = Date.now();
                }
                // A touch on text is still in the air. Whether the keyboard
                // may come up is the pointerup handler's to answer, and
                // answering it here would raise it for a scroll.
                if (pendingTap) return;
                if (!target || !target.matches || !target.matches(EDITING_HOST)) return;
                // A host blurred and focused again in one task never lost
                // focus: patch 0024 restarts input that way after the
                // editor rewrites the keyboard's text under a caret left at
                // the same offset, and the key row does after a latched
                // chord. Holding it below after a touch outside text that
                // left the keyboard up (a drag, a long press, a button that
                // opens a menu) would put that keyboard away on a phone at
                // a trackpad Down along a column. So with the keyboard up
                // such a host is not held, and a hold already on it, which a
                // turn of the phone leaves, comes off with a refocus of its
                // own, because the focus it got was granted with the
                // attribute in place.
                if (target === blurred && keepsTheKeyboard(target)) {
                    if (target.getAttribute('inputmode') === 'none') {
                        target.removeAttribute('inputmode');
                        reapplying = true;
                        target.blur();
                        target.focus();
                        reapplying = false;
                    }
                    return;
                }
                if (aimedAtText && writable(target)) { target.removeAttribute('inputmode'); return; }
                if (window.__vscodroidImeVisible) heldAtFocus = target;
                if (target.getAttribute('inputmode') === 'none') return;
                target.setAttribute('inputmode', 'none');
                reapplying = true;
                target.blur();
                target.focus();
                reapplying = false;
            }, true);
            // An open IME composition does not follow the caret when the
            // page moves it (Chromium's EditContext keeps the old
            // composition range, crbug 379170477), so a keyboard that
            // recomposes the word at the new caret writes it over the old
            // range. Ending the composition in the same task as the key or
            // tap that moves the caret lets the keyboard start again from
            // the moved caret. Run as a separate step it loses: Gboard 12.4
            // reopened the word 24 to 68 ms after the refocus.
            function composingHost() {
                var element = document.activeElement;
                return element && element.editContext && composing.has(element) ? element : null;
            }
            function finishComposition() {
                var element = composingHost();
                if (!element) return;
                cancelCompositionTurn();
                refocusHost(element);
            }
            function refocusHost(element) {
                reapplying = true;
                refocusing = element;
                element.blur();
                // A word typed into a host held under a keyboard that is up,
                // after a drag outside text say, is ended by now, so the
                // hold comes off without writing it in again; left on, it
                // would put the keyboard away at this focus, as at the
                // refocus the focus handler answers.
                if (keepsTheKeyboard(element) && element.getAttribute('inputmode') === 'none') {
                    element.removeAttribute('inputmode');
                }
                element.focus();
                refocusing = null;
                reapplying = false;
            }
            window.__vscodroidFinishComposition = finishComposition;
            function turnUnchanged() {
                if (!compositionTurn) return false;
                var element = compositionTurn.element;
                var context = element.editContext;
                return element === document.activeElement && context === compositionTurn.context &&
                    context.text === compositionTurn.text && context.selectionStart === compositionTurn.start &&
                    context.selectionEnd === compositionTurn.end;
            }
            function isTerminalTextarea(element) {
                return Boolean(element && element.classList && element.classList.contains('xterm-helper-textarea'));
            }
            function nativeTurnReady(element) {
                return !!(aimedAtText && element === document.activeElement &&
                    (element.editContext || isTerminalTextarea(element)) &&
                    element.getAttribute('inputmode') !== 'none');
            }
            // A text box of the workbench that is neither the editor (its EditContext host, or the
            // `inputarea` textarea of the other edit path) nor the terminal: the Quick Open input,
            // the find box, a rename box. Nothing here is held down, and the keyboard returns by
            // itself for the box, so a turn has nothing to refocus for it.
            function plainTextBox(element) {
                if (!element || !element.classList || element.classList.contains('inputarea')) return false;
                return element.tagName === 'TEXTAREA' || element.isContentEditable === true ||
                    (element.tagName === 'INPUT' && element.selectionStart !== null);
            }
            function finishCompositionTurn() {
                if (!turnUnchanged()) { cancelCompositionTurn(); return; }
                var turn = compositionTurn;
                if (turn.timer !== null) {
                    clearTimeout(turn.timer);
                    turn.timer = null;
                }
                if (!turn.refocused || composing.has(turn.element)) {
                    turn.refocused = true;
                    refocusHost(turn.element);
                }
                turn.finished = true;
            }
            function scheduleCompositionTurnFinish() {
                if (!turnUnchanged()) { cancelCompositionTurn(); return; }
                // One refocus from here per turn: a keyboard that reopens the
                // word after it is left to the poll that ends the turn, or to
                // the turn's own clock, and is not refocused at every reopen.
                if (compositionTurn.timer !== null || compositionTurn.finished) return;
                var turn = compositionTurn;
                turn.timer = setTimeout(function() {
                    if (compositionTurn !== turn) return;
                    turn.timer = null;
                    finishCompositionTurn();
                }, 0);
            }
            window.addEventListener('pagehide', cancelCompositionTurn);
            // A keyboard can commit its own word or reopen a range at a turn.
            // Ending the composition before a commit makes that commit insert
            // a second copy; cancelling on a same-text compositionstart or
            // textupdate leaves a reopened range active without refocusing
            // either input connection. Finish in the task after such an event
            // so a commit and its compositionend complete first, while any
            // change to text or selection cancels the turn.
            window.__vscodroidFinishCompositionOnTurn = function(start) {
                var element = document.activeElement;
                if (start) {
                    cancelCompositionTurn();
                    if (!element) return false;
                    var context = element.editContext;
                    if (!context) {
                        // 'none' and not false: the app is told that no refocus will come, and the
                        // turn stops waiting for one. The other answers of false leave it waiting.
                        if (!isTerminalTextarea(element)) return plainTextBox(element) ? 'none' : false;
                        // The refocus blurs the field, xterm empties it,
                        // and the word still under composition is lost.
                        if (composing.has(element)) return false;
                        return nativeTurnReady(element);
                    }
                    if (!composing.has(element)) return nativeTurnReady(element);
                    compositionTurn = {
                        element: element, context: context, text: context.text,
                        start: context.selectionStart, end: context.selectionEnd,
                        until: performance.now() + 1000, timer: null,
                        refocused: false, finished: false
                    };
                    // A poll the activity does not make again leaves the turn
                    // standing, so it ends by this clock whether or not anyone
                    // polls: the activity's wait is a second, a poll under load
                    // has been seen to answer after three.
                    var turn = compositionTurn;
                    turn.expiry = setTimeout(function() {
                        if (compositionTurn === turn) cancelCompositionTurn();
                    }, 5000);
                }
                if (!turnUnchanged()) { cancelCompositionTurn(); return false; }
                // Commits arriving later than one second can still race the fallback.
                if (!compositionTurn.finished && composing.has(element) &&
                    performance.now() < compositionTurn.until) return 'wait';
                finishCompositionTurn();
                var ready = nativeTurnReady(element);
                cancelCompositionTurn();
                return ready;
            };
            // Ending a composition can make the editor refilter an open
            // suggest list and focus its first item again, so a Tab or tap
            // the list takes ends it only after the list has acted, still
            // in the same task. A listener added while the event is on its
            // way down runs after the ones already registered where it is
            // added: window for a key, where the workbench runs keybindings,
            // and the target for the editor's gestures, which do not bubble.
            // The timeout covers an event stopped before it gets there.
            function finishAfter(e) {
                var node = e.bubbles ? window : e.target;
                var done = false;
                function finish(event) {
                    if (done || (event && event !== e)) return;
                    done = true;
                    node.removeEventListener(e.type, finish);
                    finishComposition();
                }
                node.addEventListener(e.type, finish);
                setTimeout(finish, 0);
            }
            // Whether the open suggest list takes the key rather than the
            // caret, by the editor's own keybinding conditions for a key
            // pressed alone: Tab and Enter accept the focused suggestion
            // (Enter also depends on `acceptSuggestionOnEnter`, which is
            // not read: a list that does not take it is finished after the
            // keybindings, still before the newline is typed); Up, Down,
            // PageUp and PageDown move the list's focus unless it holds a
            // single suggestion that is already focused. The list is marked
            // `visible` 100 ms after it opens, so a key in those first
            // 100 ms counts as the caret's. So does a chord, even one the
            // list also binds, such as Shift+Tab or Ctrl+Down. Only the rows
            // the list has drawn are read, so with the focused row scrolled
            // out of view Tab counts as the caret's, and in a list drawn one
            // row high so do Up, Down, PageUp and PageDown. Each of these
            // ends the word first, and the list then acts from its first
            // row. Reading the list's `element-focused` class and a row's
            // `aria-setsize` instead would not depend on what is drawn.
            function suggestTakes(e) {
                var list = document.querySelector('.suggest-widget.visible');
                if (!list || e.ctrlKey || e.altKey || e.shiftKey || e.metaKey) return false;
                var focused = list.querySelector('.monaco-list-row.focused');
                if (e.key === 'Tab' || e.key === 'Enter') return !!focused;
                if (!/^(ArrowUp|ArrowDown|PageUp|PageDown)$/.test(e.key)) return false;
                return !focused || list.querySelectorAll('.monaco-list-row').length > 1;
            }
            // A key that types a character, alone or with Shift. The page
            // inserts it at the caret while the keyboard still holds its
            // composition over the old range, and the keyboard then writes
            // its word over that range: a tap between the O and the R of
            // `WORD ONE` and `{` from the key row left `RDD ONE`. Ended
            // first, as for a caret key, the keyboard starts again from the
            // text as it is after the character.
            //
            // Told apart by the event alone. The keyboard's own events
            // while it composes carry key `Unidentified` (`Process` on a
            // desktop) and key code 229 and no character, and ending the
            // word on them would end it at every letter. A chord with Ctrl,
            // Alt or Meta types nothing, and the key row's latched
            // modifiers are announced as exactly that. Every named key is
            // longer than one character; Enter, which moves the caret to
            // the next line, is one of the caret keys below. A character
            // outside the BMP is two UTF-16 units, so the key is counted in
            // code points, which the `u` flag makes `[\s\S]` match as one.
            // `isComposing` is not read. It is false on the key row's keys
            // and on the keyboard's own events alike (measured with Gboard
            // 8.0.4), so it separates nothing, and a WebView that set it on
            // the row's keys would switch this off.
            var ONE_CHARACTER = /^[\s\S]$/u;
            function typesCharacter(e) {
                return ONE_CHARACTER.test(e.key) && e.keyCode !== 229 && !e.ctrlKey && !e.altKey && !e.metaKey;
            }
            // Window capture runs before the editor's own keydown handler.
            // A key the open list takes moves no caret and ends nothing,
            // except Tab and Enter, whose accept does and is finished after
            // it. A character is left to the editor while the list is
            // showing: a commit character accepts the list's focused row
            // first, from the editor's typing, which runs once this keydown
            // has been dispatched, so ending the word here, or after the
            // keybindings as for Tab, would make the editor refilter the
            // list and focus its first row again before that accept.
            var CARET_KEYS = /^(Arrow(Left|Right|Up|Down)|Home|End|PageUp|PageDown|Tab|Enter|Backspace|Delete)$/;
            window.addEventListener('keydown', function(e) {
                if (!composingHost()) return;
                if (typesCharacter(e)) {
                    if (!document.querySelector('.suggest-widget.visible')) finishComposition();
                    return;
                }
                if (!CARET_KEYS.test(e.key)) return;
                if (!suggestTakes(e)) finishComposition();
                else if (e.key === 'Tab' || e.key === 'Enter') finishAfter(e);
            }, true);
            // A touch moves the caret only in the editor's own gestures,
            // dispatched from touchend: a tap, and a long press, which opens
            // the context menu at the pressed position. pointerdown is too
            // early: the keyboard reopens the word while the finger is still
            // down. On the suggest list, which sits inside the editor, a tap
            // accepts the row under it and a long press does nothing.
            function onEditorGesture(e) {
                var target = e.target;
                if (!target.closest || !target.closest('.monaco-editor') || !composingHost()) return;
                if (!target.closest('.suggest-widget')) finishComposition();
                else if (e.type === '-monaco-gesturetap') finishAfter(e);
            }
            window.addEventListener('-monaco-gesturetap', onEditorGesture, true);
            window.addEventListener('-monaco-gesturecontextmenu', onEditorGesture, true);
            // Called from Kotlin when the soft keyboard goes away. Without
            // it a keyboard put away with Back left the focused host without
            // inputmode, and the next touch on it, a scroll included, raised
            // the keyboard again. A host still composing keeps its attribute
            // until a real compositionend while it still has focus (and drops
            // the pending hold on focusout without writing inputmode on a
            // blurred host, where the next focus is answered by focusin) so
            // Chromium does not restart input mid-word, unless the keyboard
            // came back up or a tap on text let it up first. Kotlin calls it
            // half a second after the report and not for a turn of the phone,
            // because on a device with no hardware keyboard the hold written
            // here takes a visible keyboard down for real (KeyboardDismissal).
            // Passing true for byPerson marks a hide Kotlin knows came from
            // a Back key or an edge back gesture, with gestureAtMs giving the
            // wall-clock timestamp when that Back or gesture completed: that
            // still honors the hiddenAt < letUpAt guard (a hide already
            // answered by a tap on text is void, flag or no flag) and clears
            // heldAtFocus, and skips the 4 s wait after focus only when the
            // Back or gesture is not older than the latest text focus (so an
            // aborted edge peek before Quick Open focused its input still
            // waits out the focus window, while a real Back or pair after
            // that focus holds at once regardless of call delivery delay).
            //
            // When byPerson is omitted or not true (the hide was claimed by
            // no turn and had no seen Back or edge gesture behind it), only
            // a keyboard that goes down while text has focus counts as put
            // away, so Back with an extension's webview focused is not one.
            // Chromium takes it down itself when focus leaves text, and
            // Enter can do that on its way into an editor: Quick Open on a
            // file not open yet takes the old editor's host away and leaves
            // focus on the page while the file loads, and New File leaves it
            // on the Explorer's list. Read as a keyboard put away, that
            // cleared aimedAtText, and the file typed for opened held, with
            // the keyboard down. Kept, the new host's focus raises it again.
            // The file can also finish loading and focus its host before the
            // half-second timer fires, while the keyboard is still down from
            // the gap or not yet reported up: text then has focus at the
            // call, and holding it then takes the rising keyboard back down.
            // For a fixed 4 s window after a text field took focus, the
            // hook waits for the window's end and checks again: even if
            // Kotlin briefly reports the keyboard visible inside that window
            // before another hide, a dismissal inside 4 s defers until
            // the window closes, and if it is still reported down then the
            // hold is written. An incoming visible report clears hiddenAt,
            // clears any pending post-composition hold, and cancels any
            // pending late check so it does not fire stale.
            // A dismissal whose hide report preceded the last tap on text
            // is void, so a delayed callback from an already answered hide
            // neither arms a check nor holds the host. A scroll inside the
            // 4 s window can still raise the keyboard, as a scroll inside
            // the half second always could.
            var RECENT_FOCUS_MS = 4000;
            var lateCheck = 0;
            var imeVisible = window.__vscodroidImeVisible;
            var stamp = 0;
            var hiddenAt = imeVisible === false ? ++stamp : 0;
            var letUpAt = 0;
            function cancelLateCheck() {
                if (lateCheck) { clearTimeout(lateCheck); lateCheck = 0; }
            }
            Object.defineProperty(window, '__vscodroidImeVisible', {
                configurable: true,
                enumerable: true,
                get: function() { return imeVisible; },
                set: function(v) {
                    imeVisible = v;
                    if (v === false) {
                        hiddenAt = ++stamp;
                    } else if (v === true) {
                        hiddenAt = 0;
                        holdAfterComposition = null;
                        cancelLateCheck();
                    }
                }
            });
            window.addEventListener('pagehide', function() {
                holdAfterComposition = null;
                cancelLateCheck();
            });
            window.__vscodroidKeyboardDismissed = function(byPerson, gestureAtMs) {
                cancelLateCheck();
                if (hiddenAt && hiddenAt < letUpAt) return;
                heldAtFocus = null;
                var focusAge = performance.now() - textFocusedAt;
                var explicit = byPerson === true &&
                    !(Number.isFinite(gestureAtMs) && gestureAtMs < textFocusedWallAt);
                if (!explicit) {
                    var wait = RECENT_FOCUS_MS - focusAge;
                    if (wait > 0) {
                        lateCheck = setTimeout(function() {
                            lateCheck = 0;
                            if (window.__vscodroidImeVisible === false) window.__vscodroidKeyboardDismissed();
                        }, Math.ceil(wait));
                        return;
                    }
                }
                restorePolicy();
                var focused = document.activeElement;
                if (focused && focused.matches && focused.matches(TEXT)) aimedAtText = false;
                holdAfterComposition = focused && focused.matches &&
                    focused.matches(EDITING_HOST) && composing.has(focused) ? focused : null;
                applyAll();
            };
            applyAll();
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Keeps a context menu alive on a phone, and keeps Escape able to close it.
 *
 * With the keyboard up, a long press on a word opened the editor's menu and
 * closed it again about 60ms later, which reads as a menu that flickers or
 * never appears. Measured on an API 36 emulator with the menu's shadow root
 * watched by a MutationObserver and `HTMLElement.prototype.focus` wrapped
 * to record its caller: the menu is added, its action bar calls `focus()`,
 * the editor loses focus, Android takes the keyboard down, the viewport
 * grows from 458px to 845px, and the workbench answers that resize with
 * `Workbench.layout` -> `ContextView.layout` -> `hide`. That `layout` hides
 * any non-relayoutable context view on every layout unless the platform
 * is iOS, and every context menu is non-relayoutable. `onHide` then focuses
 * the editor again, the keyboard comes back, and the viewport shrinks to
 * 458px: a full keyboard bounce for a menu the user never saw. The stack
 * trace named each frame; this is not inferred from the order of events.
 *
 * Patch 0015 met the same chain on the menubar and excused Android from
 * the menubar's own resize listener. `ContextView.layout` is a different
 * listener and is not covered by it. It could be patched the same way.
 *
 * The chain is broken at its first link. On a coarse pointer, while the
 * soft keyboard is up, a `focus()` call that would move focus OUT of an
 * editing host and INTO a context menu is ignored: the editor keeps focus,
 * the keyboard stays where it is, no resize fires, and
 * `ContextView.layout` never runs. What the menu does by pointer still
 * works without focus, and it was measured: tapping an item runs it
 * (Rename Symbol opened its box), a submenu opens on tap, and a real long
 * press with the keyboard up opened a 22-item menu that was still there
 * 2.3 seconds later with the editor focused and the viewport unchanged.
 * Tapping outside does not close an unfocused menu on its own; the
 * listener below provides that.
 *
 * Only menus. Other context views, the action widget behind the chat
 * pickers and the select dropdowns among them, are laid out again on a
 * resize rather than hidden, and they close when they lose focus: refused
 * focus, they could never lose it, and a tap outside left them open. The
 * Quick Fix list is the exception that is not a context view's doing: its
 * controller hides it at every layout change, which patch 0028 relaxes to
 * a change of width. And only while the soft keyboard is up, which is the
 * only state in which moving focus changes the window: with it down, or
 * with a hardware keyboard in its place, a menu takes focus as it always
 * did and Enter and the arrow keys reach it rather than the file behind it.
 * [ExtraKeyRow] reports the keyboard's state to the page.
 *
 * What focus was doing for the menu is keyboard handling, and one key
 * matters on a phone: Escape, which the extra key row carries. A menu
 * without focus never sees it, so a capture listener forwards Escape to
 * an open menu's action bar when that bar does not hold focus, and stops
 * the editor from seeing the same press, which is what the editor got
 * before this change too (focus was in the menu).
 *
 * The gate is the editing host, not the menu. Focus moving into a menu
 * from anywhere else, the explorer say, moves no keyboard and is left
 * alone, so a mouse-and-keyboard tablet loses nothing.
 *
 * The same shadow root is why the keybinding labels in that menu could not
 * be styled from the page: the host is created with `:host { all: initial }`
 * and copies only VS Code's own menu CSS into itself, so the stylesheet
 * [injectTouchTargetCSS] installs never reaches it (measured:
 * `#vscodroid-touch-css` absent from the root, its 40px floor not applied,
 * the label `display: block`). A sheet adopted by the shadow root is the
 * one channel in, and it is added as the root is created by wrapping
 * `attachShadow` on the host the workbench names, plus once for a host
 * that already exists, because a menu opened before onPageFinished
 * injected this script has already attached its shadow root through the
 * unwrapped `attachShadow`. A folder switch is a new document with no
 * hosts in it, and a second run in the same document returns at the
 * `__vscodroidTouchContextMenu` guard. The
 * rule mirrors the light-DOM one beside the context-view rules in
 * [injectTouchTargetCSS], and `TouchContextMenuWiringTest` holds the two
 * to the same text.
 */
internal fun MainActivity.injectTouchContextMenu() {
    webView?.evaluateJavascript(
        """
        (function() {
            // A new document starts without the keyboard's state; the last one
            // reported is carried in. Unknown stays unset.
            var reported = ${extraKeyRow?.imeVisible?.toString() ?: "undefined"};
            if (reported !== undefined) window.__vscodroidImeVisible = reported;
            if (window.__vscodroidTouchContextMenu) return;
            window.__vscodroidTouchContextMenu = true;
            var EDITING_HOST = '.native-edit-context, .monaco-editor textarea.inputarea, .xterm-helper-textarea';
            var COARSE = '(pointer: coarse)';
            // The rule reaches both kinds of menu: a light-DOM one through the
            // page stylesheet, a shadow-DOM one through the adopted sheet below.
            var KEYBINDING_RULE = '@media (pointer: coarse) { .monaco-menu .keybinding { display: none !important; } }';

            // Inside a context view, whether it lives in the page or in the
            // shadow root of the host the workbench names for it. `closest`
            // does not cross a shadow boundary upward, so the host is tested
            // by itself as well.
            function inContextView(el) {
                if (!el || !el.closest) return false;
                if (el.classList && el.classList.contains('shadow-root-host')) return true;
                var root = el.getRootNode ? el.getRootNode() : null;
                var host = root && root.host;
                if (host && host.classList && host.classList.contains('shadow-root-host')) return true;
                return !!el.closest('.context-view');
            }

            // A context menu, which the workbench hides on a resize, as opposed
            // to the other context views it lays out again. `closest` works
            // inside the menu's shadow tree.
            function inMenu(el) {
                if (!inContextView(el)) return false;
                if (el.classList && el.classList.contains('shadow-root-host')) return true;
                return !!el.closest('.monaco-menu-container');
            }

            var focus = HTMLElement.prototype.focus;
            HTMLElement.prototype.focus = function() {
                var held = document.activeElement;
                // `!== false`: until the keyboard's state has been reported,
                // refuse as before.
                if (held && held.matches && held.matches(EDITING_HOST) &&
                    window.__vscodroidImeVisible !== false &&
                    inMenu(this) && window.matchMedia(COARSE).matches) {
                    return;
                }
                return focus.apply(this, arguments);
            };

            // A tap on a menubar item is not also a tap on the menubar button.
            //
            // The menubar renders its menu, and every submenu, inside the button
            // that opened it, and the workbench's touch gestures hand one tap
            // event to every registered target that contains the finger's first
            // touch, innermost first. The item's turn runs it, and the menubar's
            // action runner closes the menu on `onWillRun`, synchronously, before
            // the item's own action starts. The same tap then reaches the button,
            // which reads a closed menu and opens it again: its own guard skips a
            // tap from inside the menu only while the menu is still open. Measured
            // on an API 33 emulator with a stack on each focus():
            // `setUnfocusedState`, then `onMenuTriggered` from `onTouchEnd` 13ms
            // later, then `showCustomMenu`.
            //
            // What that cost depends on the item. After `New Text File` the new
            // editor takes focus and closes the reopened menu again. After an
            // item that opens a quick pick, `File > New File...`, reopening the
            // menu takes focus off the pick's box, and the pick, which closes on
            // blur, is gone before it is seen; when it survives, the menu is left
            // open behind it.
            //
            // Recognised by where the tap started, and on the event itself. By the
            // button's turn the menu has been removed, so the item the tap started
            // on is detached and no longer inside anything: measured, `isConnected`
            // false and `closest` empty. The first turn still sees the menu whole,
            // so that is where the tap is marked. Stopped in the capture phase at
            // the document, before the button's own listener, which is the only
            // phase that sees it: the event does not bubble. A tap on the button
            // itself was never marked and passes.
            document.addEventListener('-monaco-gesturetap', function(e) {
                var origin = e.initialTarget;
                if (origin && origin.closest && origin.closest('.menubar-menu-items-holder')) {
                    e.__vscodroidMenuItemTap = true;
                }
                var target = e.target;
                if (e.__vscodroidMenuItemTap && target && target.classList &&
                    target.classList.contains('menubar-menu-button')) {
                    e.stopImmediatePropagation();
                }
                if (origin && origin.closest &&
                    (origin.closest('.action-container') || origin.closest('.dropdown-action-container')) &&
                    target && target.classList &&
                    target.classList.contains('monaco-dropdown-with-primary')) {
                    e.stopImmediatePropagation();
                }
            }, true);

            // Every open menu's action bar, across the page and the shadow hosts.
            function actionBars() {
                var roots = [document];
                document.querySelectorAll('.shadow-root-host').forEach(function(h) {
                    if (h.shadowRoot) roots.push(h.shadowRoot);
                });
                var bars = [];
                roots.forEach(function(r) {
                    r.querySelectorAll('.context-view .monaco-menu .actions-container').forEach(function(b) { bars.push(b); });
                });
                return bars;
            }
            window.addEventListener('keydown', function(e) {
                // Our own forward, on its way down to the menu. A menu in the
                // LIGHT DOM is an ordinary node of this document, so the event
                // dispatched below passes this same capture listener before it
                // reaches the bar; focus has not moved, so without this the
                // handler would forward it again, and again, until the stack
                // gave out. The key would never arrive either way. A shadow-DOM
                // menu never showed it: the synthetic event is not `composed`,
                // so it does not leave the shadow tree, which is why the editor
                // menu closed correctly while the terminal's did not.
                if (e.__vscodroidForwarded) return;
                if (e.key !== 'Escape') return;
                var bars = actionBars();
                // Innermost first. Escape closes one level, so with a submenu open
                // it has to reach the submenu; forwarding to the outermost bar
                // instead took the whole stack down in one press, which is not what
                // the key means anywhere else.
                for (var i = bars.length - 1; i >= 0; i--) {
                    // A menu holding focus handles its own Escape. Asked of the
                    // menu's own root, because `document.activeElement` for a
                    // shadow-DOM menu is the HOST, which the bar does not contain:
                    // read from the document alone this never matched there, and a
                    // menu that did hold focus was forwarded a key it was already
                    // going to get.
                    var menuRoot = bars[i].getRootNode();
                    var focusedHere = menuRoot.activeElement || document.activeElement;
                    if (bars[i].contains(focusedHere)) continue;
                    e.stopImmediatePropagation();
                    e.preventDefault();
                    var forwarded = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true });
                    forwarded.__vscodroidForwarded = true;
                    bars[i].dispatchEvent(forwarded);
                    return;
                }
            }, true);

            // Tapping outside an open menu closes it.
            //
            // The menu renders a full-viewport `.context-view-block` to catch a
            // dismissing click, and on this WebView nothing closes the menu from
            // it: measured with a real Android tap outside a 29-item menu, and
            // again with a synthetic mouse press, the menu stayed open both
            // times. It used to appear to work for the wrong reason. The tap was
            // read as a tap on the file, the keyboard came up, the window
            // resized, and the workbench hid every context view; the menu went
            // away with the user's file half covered by a keyboard they had not
            // asked for. Excluding those taps from the keyboard guard fixed that
            // and left the dismissal with nothing behind it, so it is provided
            // here rather than left to a side effect.
            //
            // Decided by geometry, not by containment: a touch anywhere inside a
            // shadow-DOM menu retargets to the same host, so asking which node
            // was hit cannot tell the inside of the menu from the outside. Every
            // open menu is measured, and a point inside any of them is a menu
            // interaction this leaves alone.
            //
            // Measured on the menu, not its action bar. A menu taller than the
            // space it has scrolls inside `.monaco-menu`, which clips a bar laid
            // out at its full height, so the bar's rectangle reaches past the
            // visible menu and a tap just outside it counted as inside.
            //
            // Innermost first, so a submenu and its parent both close, which is
            // what tapping away from the whole stack means.
            //
            // The tap that closes the menu does nothing else. Closing it removes
            // the full-viewport block the tap landed on, synchronously, so the
            // touch events that follow go to a detached node and the click this
            // tap still produces is hit-tested again on whatever lies underneath:
            // measured in Chromium, the row under the block received mousedown,
            // mouseup and click. A file row would open, a status bar entry would
            // run. So the pointerdown is cancelled, which drops the compatibility
            // mouse events, and the one click that follows the same finger's lift
            // is swallowed; a drag or a long press produces none, so the swallow
            // is dropped shortly after the lift either way.
            window.addEventListener('pointerdown', function (e) {
                if (!window.matchMedia(COARSE).matches) return;
                if (!inContextView(e.target)) return;
                var bars = actionBars();
                if (!bars.length) return;
                for (var i = 0; i < bars.length; i++) {
                    var r = (bars[i].closest('.monaco-menu') || bars[i]).getBoundingClientRect();
                    if (e.clientX >= r.left && e.clientX <= r.right &&
                        e.clientY >= r.top && e.clientY <= r.bottom) {
                        return;
                    }
                }
                e.preventDefault();
                var pointer = e.pointerId;
                var swallow = function (c) {
                    c.stopImmediatePropagation();
                    c.preventDefault();
                    done();
                };
                var lifted = function (u) {
                    if (u.pointerId !== pointer) return;
                    setTimeout(done, 400);
                };
                var done = function () {
                    window.removeEventListener('click', swallow, true);
                    window.removeEventListener('pointerup', lifted, true);
                    window.removeEventListener('pointercancel', lifted, true);
                };
                window.addEventListener('click', swallow, true);
                window.addEventListener('pointerup', lifted, true);
                window.addEventListener('pointercancel', lifted, true);
                for (var j = bars.length - 1; j >= 0; j--) {
                    var away = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true });
                    away.__vscodroidForwarded = true;
                    bars[j].dispatchEvent(away);
                }
            }, true);

            function adopt(root) {
                try {
                    var sheet = new CSSStyleSheet();
                    sheet.replaceSync(KEYBINDING_RULE);
                    root.adoptedStyleSheets = root.adoptedStyleSheets.concat(sheet);
                } catch (e) { /* a WebView without constructable sheets keeps the labels */ }
            }
            var attachShadow = Element.prototype.attachShadow;
            Element.prototype.attachShadow = function(init) {
                var root = attachShadow.call(this, init);
                if (this.classList && this.classList.contains('shadow-root-host')) adopt(root);
                return root;
            };
            document.querySelectorAll('.shadow-root-host').forEach(function(h) {
                if (h.shadowRoot) adopt(h.shadowRoot);
            });
            document.addEventListener('mouseover', function(e) {
                if (!window.matchMedia(COARSE).matches) return;
                if (e.target && e.target.closest && e.target.closest('.quick-input-box')) {
                    e.stopPropagation();
                }
            }, true);
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Keeps a row being edited inline in view when the keyboard shrinks the list.
 *
 * New File or Rename on a row in the lower part of the Explorer made the
 * keyboard rise and fall without end. Four workbench behaviours make the
 * loop, none of them wrong on a desktop:
 *
 * - The keyboard and the key row reach the page as a resize, and the
 *   workbench lays the list out again with only its height changed. The
 *   scroll position is kept, so a row near the bottom is now below the fold.
 * - A list draws only the rows in view, with no buffer. A row outside is
 *   taken down, and taking it down removes its input box without finishing
 *   the edit, so the edit stays open with nothing on screen to type into.
 * - With the focused input gone the keyboard goes down, the list grows, and
 *   the row is drawn again. Drawing it builds a new input box and focuses it
 *   at once, which raises the keyboard, and the loop starts over.
 * - The Explorer reveals the row once, when the edit starts, before the
 *   keyboard exists, and never again. Chromium's own scroll of a focused
 *   field into view is thrown back by the Explorer's list, which, like most
 *   lists, does not set `scrollToActiveElement`. The Settings tree and the
 *   chat list set it and apply that scroll instead.
 *
 * So the row's place is read in the capture phase, before the workbench's own
 * resize listener lays out and takes the row down, and the list is scrolled
 * in an animation frame, which runs after that layout and before the frame is
 * drawn. Scrolling at once is not enough: the list can scroll only as far as
 * its content height minus its current height, so until it has shrunk, a
 * short tree or a row near its end has no room to move. The scroll goes
 * through the touch-scroll event the list listens for, which sets its scroll
 * position the way a finger would.
 *
 * What is brought into view is the bottom of the input box, not of the row.
 * In the Explorer the box is as tall as its row, so the two agree. A Settings
 * row holds a whole setting, and a list setting grows by a line per entry,
 * so the row can be taller than the list with the box near its top; lining
 * up the row's bottom scrolled that box out above the list, and nothing
 * scrolls it back while it keeps focus.
 *
 * The scroll overshoots by one row on purpose. The Explorer cancels an edit
 * when a scroll leaves the edited row outside the list's visible height, and
 * that height is the list's own figure, not the element height read here;
 * landing exactly on the bottom edge, a pixel short would silently cancel
 * New File rather than leave the row barely hidden. That visible height
 * starts below a tree's sticky headers, which are whole rows of the folders
 * above, as many as fit in 40% of the list. So the margin is capped at the
 * row's own top that many rows down: in a list shorter than two rows, a full
 * row would push the row's top out of view, and in a list 55 to 65 pixels
 * tall, the one band where a header fits and a full row of margin still
 * leaves room, it would slide the row under that header, and either cancels
 * the edit the same way. Neither arose in landscape with Gboard up on the
 * emulator: the Explorer's list stayed 120 pixels tall there and the side
 * bar clipped it. A header being pushed up by the end of its folder can
 * reach further than whole rows, but only over rows of that folder, which
 * end above where the edited row lands.
 * The Settings tree has no cancel on scroll, but without the cap a Settings
 * row taller than the list would be scrolled out entirely and taken down
 * with its input. Near the end of the tree the list clamps the scroll, which
 * leaves the row in view anyway.
 *
 * Aimed at any list row holding an input box, not at the Explorer's class
 * names: Ports, the debug watch list, breakpoints and terminal tabs build
 * their inline edits the same way. The loop was reproduced for the Explorer
 * only; whether those loop too has not been. Measured on an API 33 emulator
 * with a 30-folder tree, before the box and the sticky headers were taken
 * into account: New File on the 27th folder kept the keyboard up over 48
 * samples in portrait and 76 in landscape, and Enter created the file in
 * both. With them, in portrait: the same New File kept it up over 12 samples
 * and created the file, and editing the first `files.exclude` pattern left
 * its box on screen, where before it sat off the top of the page. The other
 * lists were not run. `ListEditKeeperWiringTest` holds the names the script
 * reads to the shipped workbench.
 */
internal fun MainActivity.injectListEditKeeper() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__vscodroidListEditKeeper) return;
            window.__vscodroidListEditKeeper = true;
            window.addEventListener('resize', function() {
                var input = document.activeElement;
                var box = input && input.closest && input.closest('.monaco-inputbox');
                var row = box && input.closest('.monaco-list-row');
                var rows = row && row.parentNode;
                if (!rows || !rows.classList.contains('monaco-list-rows')) return;
                // Read now: by the frame the row may already be taken down.
                var height = row.offsetHeight;
                var rowTop = parseFloat(row.style.top);
                var bottom = rowTop + box.getBoundingClientRect().bottom - row.getBoundingClientRect().top;
                requestAnimationFrame(function() {
                    // The rows container is offset by minus the scroll position.
                    var viewHeight = rows.parentNode.clientHeight;
                    var hidden = bottom + parseFloat(rows.style.top) - viewHeight;
                    if (!(hidden > 0)) return;
                    // A row's margin, but never past the row's own top or under
                    // the whole rows sticky headers may take.
                    var top = rowTop + parseFloat(rows.style.top);
                    var sticky = Math.floor(0.4 * viewHeight / height) * height;
                    var scroll = new CustomEvent('-monaco-gesturechange', { cancelable: true });
                    scroll.translationX = 0;
                    scroll.translationY = -Math.max(hidden, Math.min(hidden + height, top - sticky));
                    rows.dispatchEvent(scroll);
                });
            }, true);
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Makes Enter on the soft keyboard reach the workbench as Enter, both while a
 * word is still being composed and when it arrives without a `code`, and
 * lets the quick input's keys reach their keybindings while a word is
 * composed.
 *
 * After a word is typed into a workbench input box, Gboard keeps it as an
 * active composition, underlined, and its Enter key arrives as a keydown with
 * `key` Enter, `keyCode` 13, an empty `code` and `isComposing` true, on every
 * press. The workbench's `StandardKeyboardEvent` maps any composing keydown
 * to KeyCode 114, the IME key, so no check for Enter matches it. Measured on
 * an API 33 emulator with real Gboard taps: New File and Rename in the
 * Explorer never committed, and Quick Open and the Command Palette never
 * accepted. On a desktop that mapping is right, because a composing Enter
 * there confirms an IME conversion; Gboard composes every Latin word too.
 *
 * So a composing Enter on an input or textarea is stopped at the window in
 * the capture phase, before the input's own listeners and the keybinding
 * service see it, and an Enter the workbench recognises is dispatched on the
 * same element in its place. Its `keyCode` is defined on the event itself,
 * because the workbench derives the key from `keyCode` and not from `key`.
 * The input already holds the composed word, so its value is left alone.
 * An unmodified key's default follows the replacement's: it is cancelled only
 * when a workbench handler cancelled the replacement. Cancelling it always
 * is the obvious shape and swallows a newline, because a multi-line setting
 * is a textarea whose Enter no handler inserts.
 *
 * A `compositionend` carrying the composed word is dispatched on the element
 * just before the replacement. The key arrives inside the composition, and a
 * widget that holds its work back until the composition ends would otherwise
 * act on stale state: the action list's filter box, which the chat model
 * picker and the Run and Debug configuration dropdown open focused, filters
 * only on `compositionend` and keeps list focus on the checked or first row
 * until then, so Enter accepted that row and not the one typed. It filters
 * synchronously unless the picker supplies an `onFilter` delegate; one that
 * does can still accept a row from the list before the filter.
 *
 * Not the editor and not the terminal. Both take Enter through an edit path
 * of their own, the editor's edit context and xterm's helper textarea, and
 * handle it correctly today. The editor exclusion is its whole element, so
 * the find and rename boxes the editor renders inside itself are left as
 * they were.
 *
 * Not a Chinese or Japanese conversion. When the composition holds Han,
 * kana or Bopomofo, Enter is left to confirm it, as it does on a desktop.
 * Measured on the same emulator with Gboard's Japanese romaji layout in an
 * Explorer rename: the first Enter arrives composing and confirms the
 * conversion, the composition ends, and a second Enter commits. The ceiling
 * is what that test can see: a Pinyin composition is Latin letters until a
 * candidate is picked, so Enter there submits the raw letters.
 *
 * Hangul is replaced like a Latin word. A Korean syllable has no conversion
 * step; it is already the input's value, so a composing Enter has nothing to
 * confirm. Measured with Gboard's Korean 2-Bulsik layout in an Explorer
 * rename while Hangul was excluded: the first Enter arrived composing and
 * did nothing, the composition then ended, and only a second Enter
 * committed. Replaced, one Enter commits the name.
 *
 * The replacement was measured on the same emulator with real taps on
 * Gboard 12.4, the version its system image ships: Rename commits a Latin
 * word on one Enter, Quick Open opens the file and closes, the editor takes
 * exactly one newline, and `Gam` then Enter in the Run and Debug
 * configuration dropdown selects Gamma, where before the `compositionend`
 * it kept Alpha. Gboard 18.1 types Latin letters into these boxes without a
 * composition, so there the replacement is not reached for them. A
 * multi-line setting was not run.
 *
 * A non-composing Enter in a single-line input is the second case. Gboard
 * sends its action key there (GO) as `key` Enter, `keyCode` 13 and an empty
 * `code`, and the keybinding service resolves keys from `code`, so the
 * Command Palette, Quick Open and every input box, which accept only through
 * a keybinding, ignored it. The real event is given `code` Enter and goes on,
 * not replaced. Not while the key row has Ctrl or Alt latched: the row
 * builds that chord from the `beforeinput` an unhandled Enter is followed by,
 * and an accepted Enter has none. Unlike the replacement, the editor is not
 * excluded, because its rename box is such an input. The editor's own Enter
 * and the terminal's carry a `code` on Gboard 18.2 but not on 12.4, where
 * they are filled too, so Enter keybindings inside the editor (suggest
 * accept, rename accept) now match there as they already do on 18.2 and on
 * a desktop. Measured with real taps on Gboard 12.4 (API 33) and 18.2
 * (API 36), with this listener installed over DevTools in a debug build:
 * each of those accepts once, the Explorer commits once, the editor and
 * terminal take one Enter each, and a latched Ctrl still gives Ctrl+Enter.
 * Suggest and rename accept were not run. A composing Enter that Gboard
 * 12.4 commits as text, with no keydown, is beyond any listener here.
 *
 * A Shift latched alone is spent on that Enter and not applied to it, which
 * is how [KeyInjector.setupModifierInterceptor] treats a lone Shift on any
 * soft keyboard edit. That interceptor spends it in the `beforeinput` an
 * unhandled Enter is followed by, and an accepted Enter has none, so the
 * latch stood and the next key on the row went out shifted, `/` as `?` and
 * a trackpad drag as a selection. Applying it as well is the obvious shape
 * and changes what the Enter does. The quick input reads Shift off the key
 * event into the state its pickers act on, and on Shift Quick Open and the
 * `#` symbol search do not open the highlighted item: they add it to the
 * last chat input that had focus, if any, and stay open. On Gboard 12.4,
 * whose editor Enter is filled here too, it would reach the editor's
 * Shift+Enter bindings: in a Python file the bundled Python extension runs
 * the line in the terminal instead of inserting a newline, and the find box
 * goes to the previous match. A trusted plain Enter with a `code` spends
 * the same row latch without changing its physical modifier flags. An
 * identically shaped hardware Enter follows that same next-keypress rule;
 * Ctrl, Alt and Meta chords keep their existing handling. The replacement of
 * a composing Enter spends a lone Shift without applying it too; once a
 * binding accepts it no `beforeinput` follows either, so
 * on Gboard 12.4 a Shift latched over Quick Open's composing file name stood
 * after the file opened (measured on API 33, two runs of three).
 *
 * With Ctrl or Alt latched, the replacement instead carries every latched
 * modifier, including Shift, as the non-composing Enter's `beforeinput`
 * does. It spends them before dispatch. As on that path, an unhandled
 * chord inserts no newline and leaves no modifier for the next letter.
 *
 * The third case is not Enter. The key row presses PageUp, PageDown, Home,
 * End and the trackpad's Left and Right as real keys (`NAVIGATION_KEYS` in
 * `TextEntry.kt`), and a real key pressed while an input holds a
 * composition carries `isComposing`, which `StandardKeyboardEvent` maps to
 * KeyCode 114 as it does for Enter, and the keybinding service dispatches
 * nothing for that. The quick input does the following through keybindings
 * and nothing else, so while Gboard underlined the typed word none of it
 * happened: PageDown and PageUp, alone or with Alt or Ctrl, page the list
 * (`quickInput.pageNext`, `quickInput.pagePrevious`); Ctrl+Home and
 * Ctrl+End go to its first and last item (`quickInput.first`,
 * `quickInput.last`); and Right with the caret at the end of the box opens
 * Quick Open's highlighted item without closing it
 * (`quickInput.acceptInBackground`). So a real keydown of one of those,
 * composing, on an element inside `.quick-input-widget` and outside any
 * editor there has `isComposing` defined false in the capture phase, and
 * the keybinding service dispatches it as it does when nothing is composed.
 * The composition is left alone. Home and End without Ctrl, and Left, are
 * bound to nothing there, and their default, moving the caret, runs whether
 * or not the key is composing. Up and Down are announced, and an announced
 * key is never composing.
 *
 * Every other target keeps `isComposing`, and with it the workbench's rule
 * that a composing key runs no binding, which on a desktop keeps a key the
 * IME is using from also running a command. There the key's default is what
 * it is pressed for, and it runs anyway; in the quick input these keys do
 * nothing except through a binding, which is why it is the exception. The
 * exception is the quick input's own box: an editor inside the widget,
 * which is where Quick Chat puts its chat input, keeps the rule like any
 * other editor, and is excluded as it is from the Enter replacement. A
 * Chinese or Japanese composition is not excluded, as it is for Enter: the
 * row sends these keys to the WebView directly, so the keyboard never sees
 * them and none is meant for its candidates. A key a script built is left
 * as built, since only a real key carries a real composition. Read from the
 * 1.139.1 bundle and run by `scripts/test-composing-enter.js`; not measured
 * on a device.
 *
 * `ComposingEnterWiringTest` holds the script and the bundle to it, and
 * `scripts/test-composing-enter.js` runs it.
 */
internal fun MainActivity.injectComposingEnter() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__vscodroidComposingEnter) return;
            window.__vscodroidComposingEnter = true;
            // What the keyboard shows as composed, until the composition ends.
            var composing = '';
            function track(e) { composing = e.data || ''; }
            window.addEventListener('compositionstart', track, true);
            window.addEventListener('compositionupdate', track, true);
            window.addEventListener('compositionend', function() { composing = ''; }, true);
            var CONVERSION = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Bopomofo}]/u;
            // The keys the quick input binds, of those the key row presses
            // for real. See the KDoc for each.
            function quickInputBinds(e) {
                var key = e.key;
                return key === 'PageUp' || key === 'PageDown' || key === 'ArrowRight' ||
                    (e.ctrlKey && (key === 'Home' || key === 'End'));
            }
            window.addEventListener('keydown', function(e) {
                if (e.key === 'Enter' && e.code === '' && !e.isComposing) {
                    var mod = window.__vscodroid || {};
                    if (mod.ctrl || mod.alt) return;
                    Object.defineProperty(e, 'code', { value: 'Enter' });
                    // Spent, never applied. See the KDoc.
                    mod.shift = false;
                    return;
                }
                if (e.key === 'Enter' && e.code !== '' && !e.isComposing && e.isTrusted &&
                    !e.ctrlKey && !e.altKey && !e.metaKey) {
                    var latch = window.__vscodroid;
                    if (latch && !latch.ctrl && !latch.alt) latch.shift = false;
                }
                if (e.isComposing && e.isTrusted && quickInputBinds(e) &&
                    e.target.closest('.quick-input-widget') && !e.target.closest('.monaco-editor')) {
                    Object.defineProperty(e, 'isComposing', { value: false });
                    return;
                }
                if (e.key !== 'Enter' || !e.isComposing || CONVERSION.test(composing)) return;
                var target = e.target;
                if (!target || (target.tagName !== 'INPUT' && target.tagName !== 'TEXTAREA')) return;
                if (target.closest('.monaco-editor, .xterm')) return;
                e.stopImmediatePropagation();
                var latch = window.__vscodroid;
                var ctrl = !!(latch && latch.ctrl), alt = !!(latch && latch.alt);
                var shift = !!(latch && latch.shift && (ctrl || alt));
                if (latch) latch.ctrl = latch.alt = latch.shift = false;
                target.dispatchEvent(new CompositionEvent('compositionend', { data: composing, bubbles: true }));
                var enter = new KeyboardEvent('keydown', {
                    key: 'Enter', code: 'Enter', ctrlKey: ctrl, altKey: alt, shiftKey: shift,
                    bubbles: true, cancelable: true
                });
                Object.defineProperty(enter, 'keyCode', { value: 13 });
                Object.defineProperty(enter, 'which', { value: 13 });
                if (!target.dispatchEvent(enter)) e.preventDefault();
                // The modifier interceptor cancels an unhandled chord's edit too.
                if (ctrl || alt) e.preventDefault();
            }, true);
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Keeps xterm's soft-key snapshot and composition-end timers behind the input they read,
 * and sends an ended composition before the next key, the next composition or the field's
 * blur can make xterm drop it.
 */
internal fun MainActivity.injectTerminalTypedCharacters() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__vscodroidTerminalTypedCharacters) return;
            window.__vscodroidTerminalTypedCharacters = true;
            var timeout = window.setTimeout, cancel = window.clearTimeout;
            var key = null, held = null, ended = [];
            function isTerm(el) {
                return !!(el && el.classList && el.classList.contains('xterm-helper-textarea'));
            }
            function finish(pending) {
                var i = ended.indexOf(pending);
                if (pending === held) held = null;
                else if (i >= 0) ended.splice(i, 1);
                else return;
                cancel.call(window, pending.id);
                pending.callback.apply(window, pending.args);
            }
            // Every pending send of an ended composition on the field, oldest first. The first sends and clears
            // xterm's flag, so another armed for the same composition then sends nothing, as on xterm's own timers.
            function finishEnded(target) {
                ended.filter(function(p) { return p.target === target; }).forEach(function(p) { finish(p); });
            }
            window.addEventListener('keydown', function(e) {
                // Finish the soft-key comparison before a following key can clear the field,
                // and finish any pending composition end update before the next key handles it.
                if (held && e.target === held.target && (e.keyCode !== 229 || held.comp)) finish(held);
                // A key makes xterm finalize only the current composition and skip an ended
                // one whose own send has not run yet, so send that one first.
                if (e.keyCode !== 229) finishEnded(e.target);
                key = e.isTrusted && e.keyCode === 229 && isTerm(e.target) ? e : null;
            }, true);
            window.addEventListener('keydown', function() { key = null; }, false);
            window.addEventListener('compositionupdate', function(e) {
                if (held && e.target === held.target) finish(held);
                key = e.isTrusted && isTerm(e.target) ? e : null;
            }, true);
            window.addEventListener('compositionupdate', function() { key = null; }, false);
            window.addEventListener('compositionstart', function(e) {
                // Send an ended composition before the next one starts, as its own timer would have.
                finishEnded(e.target);
            }, true);
            window.addEventListener('blur', function(e) {
                // xterm empties its field in the field's own blur handler, and the compositionend of a word the
                // blur commits comes just before it, in the same task: send what is pending while the field holds it.
                // Not for the blur the key row's chord makes over a composing letter: that one puts the field's
                // own text back right after, and xterm's send has to read it then, as it always did.
                if (e.target === window.__vscodroidChordBlur) return;
                finishEnded(e.target);
            }, true);
            window.addEventListener('compositionend', function(e) {
                // Not asked to be trusted: Android's WebView delivers compositionend without the trusted
                // mark, and xterm arms its send for any compositionend on its field.
                key = isTerm(e.target) ? e : null;
            }, true);
            window.addEventListener('compositionend', function() { key = null; }, false);
            window.addEventListener('input', function(e) {
                if (held && e.target === held.target) finish(held);
            }, true);
            window.setTimeout = function(callback, delay) {
                if (key && key.eventPhase !== 0 && (delay === 0 || delay === undefined) && typeof callback === 'function') {
                    var text = Function.prototype.toString.call(callback);
                    if (!held &&
                        ((key.type === 'keydown' && text.indexOf('_textareaChangeTimer') >= 0) ||
                            (key.type === 'compositionupdate' && text.indexOf('_compositionPosition.end') >= 0))) {
                        var pending = {
                            target: key.target, callback: callback,
                            args: Array.prototype.slice.call(arguments, 2),
                            comp: key.type === 'compositionupdate'
                        };
                        held = pending;
                        // A cancelled edit may produce no input; do not retain its timer indefinitely.
                        pending.id = timeout.call(window, function() { finish(pending); }, 100);
                        return pending.id;
                    }
                    if (key.type === 'compositionend' && text.indexOf('_isSendingComposition') >= 0) {
                        // The composition's own send keeps its time but stays within reach of the next key.
                        var end = {
                            target: key.target, callback: callback,
                            args: Array.prototype.slice.call(arguments, 2)
                        };
                        ended.push(end);
                        end.id = timeout.call(window, function() { finish(end); }, 0);
                        return end.id;
                    }
                }
                return timeout.apply(this, arguments);
            };
            window.clearTimeout = function(id) {
                if (held && held.id === id) held = null;
                if (ended.length > 0) {
                    ended = ended.filter(function(p) { return p.id !== id; });
                }
                return cancel.apply(this, arguments);
            };
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Lets a finger drag the caret: touch within reach of it, drag sideways, and it
 * follows, and the text scrolls when the finger comes to the editor's edge.
 *
 * Dragging in the editor otherwise only scrolls it, and the editor puts a caret
 * down only for a tap, so moving it a few characters meant tapping again and again
 * or reaching for the trackpad on the key row.
 *
 * Which touch is a caret drag is decided at its first movement, never at its
 * start, so a tap, a long press that stays put and a scroll go on as before:
 *  - it is a finger. A stylus's drag is already a selection in the editor;
 *  - the touch went down on the text, within `GRAB_X` either side of the caret,
 *    `GRAB_UP` above it and `GRAB_DOWN` below. More room below, so a finger under
 *    the line keeps the caret in sight, and it keeps that distance for the whole
 *    drag. A touch on something over the text, a menu item or a suggestion, is
 *    left alone;
 *  - the editor has one caret and nothing selected. With a selection or several
 *    cursors a drag would throw them away;
 *  - the first movement is sideways. Up or down first is how the editor scrolls,
 *    and a finger that goes down on the caret to scroll still does.
 * A long press that creeps sideways before it is lifted is a drag too, where the
 * editor would have opened its menu for it.
 *
 * From then on the touch is the script's: its moves are cancelled and kept from the
 * editor, whose own gesture would scroll, and so is its lift, which the editor
 * would take for a tap where it went down. A second finger that lands meanwhile is
 * kept from the editor too, or its lift would be read as a tap. The editor redraws
 * its lines as the text scrolls, and a touch whose first element has gone out of
 * the page stops reaching the page's touch listeners, moves and lift both, while
 * the pointer events carry on (measured: a drag held at the lower edge lost its
 * touchend about one run in two). So the drag is also followed and ended from the
 * pointer events, and a touchend that still arrives afterwards is kept from the
 * editor as well. Those pointer moves are kept from it too, because it reads them as
 * a mouse over its text and opens the hover of the word the finger rests on, a box
 * that covers the code being dragged over.
 *
 * The keyboard guard reads a touch that ends within a few pixels of where it began,
 * and soon, as a tap, and raises a keyboard it was holding down. A drag of a
 * character or two is that short, so from the moment it is taken until the next
 * touch it is marked in `window.__vscodroidCaretDragged`, and the guard lets the
 * touch go by.
 *
 * The caret is moved the way a finger moves it, by the editor's own tap
 * (`-monaco-gesturetap`, which the lines listen for on their content node)
 * dispatched at the point the finger is at, less the distance it was from the caret
 * when it went down. That keeps the keyboard guard in step, because it listens for
 * the same event to end a composing word, and it asks nothing of the editor that a
 * tap does not. One tap per animation frame, and none for a finger that has not
 * moved. Within `EDGE` of the editor's edge a wheel event scrolls it, faster the
 * nearer the edge and by the time a frame took, so a 120 Hz screen is not twice as
 * quick. The scrolling rests when the text stops moving and starts again when the
 * finger does. A finger that goes down inside a band, on the last line say, may run
 * along it: it scrolls toward that edge only once it has been out of the band or has
 * moved `ARM` toward the edge. An editor smaller than `MIN_EDGE_SIZE`, the four
 * lines a phone in landscape leaves with the keyboard up, does not scroll at all,
 * because the bands would cover every line. The wheel event goes to the editor's root, where the editor listens,
 * and does not bubble: an editor with nothing to scroll that does not keep a wheel
 * for itself, such as the commit message box or a notebook cell, would pass it up to
 * the list around it. That event is not trusted and the editor scrolls the opposite
 * way to its delta, measured, which is why the signs are reversed.
 *
 * The point a tap is aimed at is the first element under it that belongs to the
 * lines, because the sash on the editor's lower edge is on top of the last pixels
 * and a tap at one of them found nothing. A color swatch is passed over, to the
 * text behind it: the editor opens a color's picker from a tap that begins on the
 * swatch, and a drag across one opened it over the code being dragged over, measured
 * on an API 36 emulator with the finger held there.
 *
 * Measured on an API 36 emulator (WebView 134) with `adb shell input`: a sideways
 * drag from the caret moved it to within one character of the finger, a drag across
 * wrapped lines landed on the line under the finger less the grab distance, the
 * text scrolled with the caret following at either edge, the keyboard stayed down
 * for a drag begun with it down and up for one begun with it up, a tap beside the
 * caret still placed it, a drag begun upward scrolled and claimed nothing, a long
 * press on the caret opened the editor menu, and a drag with a word selected did
 * nothing. Not measured: a finger, which is what decides whether the grab distances
 * and the edge speed feel right, a stylus, a word still composing, and an editor in
 * a notebook or the commit message box.
 *
 * `CaretDragWiringTest` holds the script to where it is installed and to the
 * names it takes from the workbench, and `scripts/test-caret-drag.js` runs it.
 */
internal fun MainActivity.injectCaretDrag() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__vscodroidCaretDrag) return;
            window.__vscodroidCaretDrag = true;
            // How far from the caret a touch may go down and still hold it, in CSS px.
            var GRAB_X = 24;
            var GRAB_UP = 8;
            var GRAB_DOWN = 40;
            // The travel that tells a drag from a press.
            var START_TRAVEL = 4;
            // Within EDGE of the editor's edge the text scrolls, by up to EDGE_SPEED wheel
            // units a 60 Hz frame. INSET keeps the aim off the sash on the edge.
            var EDGE = 32;
            var EDGE_SPEED = 12;
            var INSET = 8;
            // An editor smaller than this has no room for the bands: they would cover every
            // line, and a finger on any of them would scroll the text away.
            var MIN_EDGE_SIZE = 104;
            // A finger that goes down inside a band may run along it. It scrolls toward that
            // edge once it has been out of the band, or has moved this far toward the edge.
            var ARM = 16;
            // Scrolling frames in a row with the text where it was, after which the
            // scrolling rests until the finger moves: there is nothing more to scroll.
            var STILL_FRAMES = 4;

            var root = document.documentElement;
            var style = document.createElement('style');
            style.id = 'vscodroid-caret-drag-css';
            style.textContent = '.vscodroid-caret-drag .monaco-editor.focused .cursors-layer > .cursor { ' +
                'box-shadow: 0 0 0 3px var(--vscode-editorCursor-foreground, #aeafad); }';
            document.head.appendChild(style);

            var drag = null;
            // The pointer that went down last, and what it is, for the touch that follows it.
            var lastPointer = -1;
            var lastPointerType = '';
            // The touch whose drag a pointer event ended, while its own touchend may still
            // arrive and has to be kept from the editor.
            var finished = -1;
            window.__vscodroidCaretDragged = false;

            function touchOf(list, id) {
                for (var i = 0; i < list.length; i++) {
                    if (list[i].identifier === id) return list[i];
                }
                return null;
            }

            function clamp(value, low, high) {
                return Math.min(Math.max(value, low), high);
            }

            function tapAt(x, y) {
                var stack = document.elementsFromPoint(x, y);
                var el = null;
                for (var i = 0; i < stack.length; i++) {
                    // Never a color swatch: the editor opens a color's picker from a tap
                    // that begins on it, and a finger resting there would cover the code.
                    if (drag.lines.contains(stack[i]) && !stack[i].closest('.colorpicker-color-decoration')) {
                        el = stack[i];
                        break;
                    }
                }
                if (!el) return;
                var ev = document.createEvent('CustomEvent');
                ev.initEvent('-monaco-gesturetap', false, true);
                ev.initialTarget = el;
                ev.tapCount = 1;
                ev.pageX = x;
                ev.pageY = y;
                ev.clientX = x;
                ev.clientY = y;
                ev.translationX = 0;
                ev.translationY = 0;
                drag.lines.dispatchEvent(ev);
            }

            function edgeStep(pos, low, high, armedLow, armedHigh) {
                if (armedLow && pos < low + EDGE) return -EDGE_SPEED * Math.min(1, (low + EDGE - pos) / EDGE);
                if (armedHigh && pos > high - EDGE) return EDGE_SPEED * Math.min(1, (pos - (high - EDGE)) / EDGE);
                return 0;
            }

            // Which edges the finger may scroll toward, see ARM.
            function armEdges(at) {
                var armed = drag.armed;
                var box = at.box;
                if (at.y >= box.top + EDGE || drag.y0 - drag.y >= ARM) armed.top = true;
                if (at.y <= box.bottom - EDGE || drag.y - drag.y0 >= ARM) armed.bottom = true;
                if (at.x >= box.left + EDGE || drag.x0 - drag.x >= ARM) armed.left = true;
                if (at.x <= box.right - EDGE || drag.x - drag.x0 >= ARM) armed.right = true;
            }

            // The finger, kept inside the editor, and the editor's box.
            function where() {
                var box = drag.scroller.getBoundingClientRect();
                return {
                    box: box,
                    x: clamp(drag.x, box.left + 1, box.right - 1),
                    y: clamp(drag.y, box.top + 1, box.bottom - 1)
                };
            }

            // Puts the caret where the finger is, less the distance it began from it, and
            // off the sash on the editor's edge.
            function aim(at, force) {
                var x = clamp(at.x - drag.offX, at.box.left + 1, at.box.right - 1);
                var y = clamp(at.y - drag.offY, at.box.top + INSET, at.box.bottom - INSET);
                if (force || x !== drag.lastX || y !== drag.lastY) {
                    drag.lastX = x;
                    drag.lastY = y;
                    tapAt(x, y);
                }
            }

            function frame(now) {
                if (!drag || !drag.claimed) return;
                drag.frame = 0;
                var at = where();
                armEdges(at);
                var armed = drag.armed;
                var sx = at.box.width < MIN_EDGE_SIZE ? 0 :
                    edgeStep(at.x, at.box.left, at.box.right, armed.left, armed.right);
                var sy = at.box.height < MIN_EDGE_SIZE ? 0 :
                    edgeStep(at.y, at.box.top, at.box.bottom, armed.top, armed.bottom);
                var scrolling = sx !== 0 || sy !== 0;
                if (scrolling) {
                    var text = drag.lines.getBoundingClientRect();
                    drag.still = text.left === drag.textLeft && text.top === drag.textTop ? drag.still + 1 : 0;
                    drag.textLeft = text.left;
                    drag.textTop = text.top;
                    var scale = drag.at && now ? clamp((now - drag.at) / 16.7, 0.25, 3) : 1;
                    drag.at = now || 0;
                    // Dispatched on the editor's root and not bubbling, see the KDoc.
                    drag.editor.dispatchEvent(new WheelEvent('wheel', {
                        deltaX: -sx * scale, deltaY: -sy * scale, deltaMode: 0,
                        bubbles: false, cancelable: true, clientX: at.x, clientY: at.y
                    }));
                } else {
                    drag.at = 0;
                    drag.still = 0;
                    drag.textLeft = undefined;
                    drag.textTop = undefined;
                }
                aim(at, scrolling);
                if (scrolling && drag.still < STILL_FRAMES) drag.frame = requestAnimationFrame(frame);
            }

            function follow(x, y) {
                drag.x = x;
                drag.y = y;
                drag.still = 0;
                if (!drag.frame) drag.frame = requestAnimationFrame(frame);
            }

            function endDrag() {
                if (drag.frame) cancelAnimationFrame(drag.frame);
                drag.frame = 0;
                root.classList.remove('vscodroid-caret-drag');
            }

            // The end of a taken drag, from whichever event reports it first. The editor
            // redraws its lines as the text scrolls, and a touch whose first element has
            // gone out of the page stops reaching the page's touch listeners: measured, the
            // moves and the touchend stopped while the pointer events carried on.
            function finish(commit) {
                var done = drag;
                if (done.frame) cancelAnimationFrame(done.frame);
                done.frame = 0;
                if (commit) aim(where(), false);
                endDrag();
                finished = done.id;
                drag = null;
            }

            window.addEventListener('pointerdown', function(e) {
                if (drag && drag.claimed) return;
                lastPointer = e.pointerId;
                lastPointerType = e.pointerType;
                finished = -1;
                window.__vscodroidCaretDragged = false;
            }, true);

            window.addEventListener('touchstart', function(e) {
                if (drag && drag.claimed) {
                    // A second finger, while one drags: the editor is not told of it, so
                    // it cannot read its lift as a tap.
                    if (e.touches.length > 1) {
                        e.stopImmediatePropagation();
                        return;
                    }
                    // A lone touch: the last drag's lift never came.
                    endDrag();
                }
                drag = null;
                if (e.touches.length !== 1 || lastPointerType !== 'touch') return;
                var t = e.touches[0];
                var editor = document.querySelector('.monaco-editor.focused');
                if (!editor) return;
                var lines = editor.querySelector('.lines-content');
                if (!lines || !lines.contains(e.target)) return;
                // This editor's own: a peek, an inline chat or a comment thread holds an
                // editor of its own inside this one, with a caret and selections of its own.
                var mine = function(el) { return el.closest('.monaco-editor') === editor; };
                var carets = Array.prototype.filter.call(editor.querySelectorAll('.cursors-layer > .cursor'), mine);
                var selected = Array.prototype.some.call(editor.querySelectorAll('.selected-text'), mine);
                if (carets.length !== 1 || selected) return;
                var r = carets[0].getBoundingClientRect();
                if (!r.height) return;
                if (t.clientX < r.left - GRAB_X || t.clientX > r.right + GRAB_X ||
                    t.clientY < r.top - GRAB_UP || t.clientY > r.bottom + GRAB_DOWN) return;
                drag = {
                    id: t.identifier, pointer: lastPointer, editor: editor, lines: lines,
                    x0: t.clientX, y0: t.clientY,
                    offX: t.clientX - r.left, offY: t.clientY - (r.top + r.height / 2),
                    claimed: false, frame: 0, x: t.clientX, y: t.clientY, lastX: -1, lastY: -1,
                    at: 0, still: 0, textLeft: undefined, textTop: undefined,
                    armed: { top: false, bottom: false, left: false, right: false }
                };
            }, { capture: true, passive: true });

            window.addEventListener('touchmove', function(e) {
                if (!drag) return;
                var t = touchOf(e.touches, drag.id);
                if (!drag.claimed) {
                    if (!t || e.touches.length !== 1) {
                        drag = null;
                        return;
                    }
                    var dx = t.clientX - drag.x0;
                    var dy = t.clientY - drag.y0;
                    if (Math.max(Math.abs(dx), Math.abs(dy)) < START_TRAVEL) return;
                    if (Math.abs(dy) > Math.abs(dx)) {
                        drag = null;
                        return;
                    }
                    var scroller = drag.editor.querySelector('.monaco-scrollable-element');
                    if (!scroller) {
                        drag = null;
                        return;
                    }
                    drag.scroller = scroller;
                    drag.claimed = true;
                    window.__vscodroidCaretDragged = true;
                    root.classList.add('vscodroid-caret-drag');
                }
                e.preventDefault();
                e.stopImmediatePropagation();
                if (t) follow(t.clientX, t.clientY);
            }, { capture: true, passive: false });

            window.addEventListener('pointermove', function(e) {
                if (!drag || e.pointerId !== drag.pointer) return;
                // Not the editor's: it would show the hover of the word under the finger.
                e.stopPropagation();
                if (drag.claimed) follow(e.clientX, e.clientY);
            }, true);

            function pointerEnded(e) {
                if (!drag || !drag.claimed || e.pointerId !== drag.pointer) return;
                finish(e.type === 'pointerup');
            }
            window.addEventListener('pointerup', pointerEnded, true);
            window.addEventListener('pointercancel', pointerEnded, true);

            function lifted(e) {
                // The touchend of a drag a pointer event already ended.
                if (finished !== -1 && touchOf(e.changedTouches, finished)) {
                    finished = -1;
                    if (e.type === 'touchend') {
                        e.preventDefault();
                        e.stopImmediatePropagation();
                    }
                    return;
                }
                if (!drag || !touchOf(e.changedTouches, drag.id)) return;
                if (!drag.claimed) {
                    drag = null;
                    return;
                }
                finish(e.type === 'touchend');
                // The editor was never told this touch moved, so it would read the lift
                // as a tap where it went down.
                if (e.type === 'touchend') {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                }
            }
            window.addEventListener('touchend', lifted, { capture: true, passive: false });
            window.addEventListener('touchcancel', lifted, true);
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Puts the page's own background over the workbench from the moment it is told
 * to leave, so that the half-dismantled workbench is never what is on screen
 * while the next page loads.
 *
 * The workbench dismantles itself inside `beforeunload`: its lifecycle service
 * runs the whole shutdown from that listener, with no veto, before the event
 * has finished dispatching. In that one task the terminals are disposed, the
 * `modern-ui` classes come off the workbench container, and the styles the
 * terminal and the diff decorations added are removed, so the page drops to its
 * classic skin (upper-case panel titles, the editor's status items, a tab strip
 * without its labels) and the extension host's contributions are gone. The
 * WebView then holds that frame until the next document paints: on an API 33
 * emulator, with Developer: Reload Window, the dismantled page stayed on screen
 * for about half a second and then the screen went blank until the new page
 * came. A page cannot stop the workbench doing this, only what is painted.
 *
 * This listener raises a full-window element in the colour the page paints
 * itself with, which `assets/server.js` keeps on the editor's background and
 * which is what the new page starts on, in the same dispatch. Nothing is drawn
 * between a task's end and the next frame, so the first frame painted after the
 * dismantling is the curtain: measured on the same emulator, with frames cut
 * every 50 ms, the intact page was followed at once by the plain colour and no
 * frame of the dismantled one. It does not matter which listener runs first. On
 * the shipped build this one runs ahead of the workbench's, since the workbench
 * registers its own long after the page's load event: the curtain was on the
 * root 44 ms before `modern-ui` came off. Added by hand to a page that is
 * already running, behind it, the dismantling was already done and the first
 * frame was still the curtain.
 *
 * A veto is the one case where leaving is not certain. The workbench cancels
 * the event (`vetoBeforeUnload`) when it holds work it has no backup of, and
 * the platform then asks the user. The check runs in the next task, after every
 * listener has had its turn, and lowers the curtain for a cancelled event, so
 * the page the user is asked about is the intact one: measured, with the
 * dialog's "Stay on this page" the page is intact and no element is left on the
 * root. `beforeunload` also fires for a navigation that then never replaces
 * the page: a link to `mailto:` or another scheme goes to another app, and the
 * client refuses a path on this origin that is not the workbench. The workbench
 * itself is untouched by those (its opener wraps the assignment in
 * `withExpectedShutdown`, which turns its own listener off), so only the curtain
 * is left, and [liftLeaveCurtain] takes it down when the client declines the
 * navigation. Measured on an API 33 emulator before it did: a link to a scheme
 * no app answers left the screen blank but for the toast for five seconds, and
 * a `mailto:` link in a Markdown preview kept the curtain over an intact
 * workbench. The timer stays for anything the client never hears of, so a
 * navigation that does not commit does not leave a window nobody can see
 * through, and `pageshow` lowers it for a page restored from the back-forward
 * cache.
 *
 * No colour known, no curtain: the root has none until the page script that
 * `assets/server.js` adds runs, and a guess would be a dark flash under a light
 * theme. Taps are not let through, because what is behind it is no longer a
 * workbench that can answer them.
 *
 * `LeaveCurtainWiringTest` holds the script to where it is installed, and
 * `scripts/test-leave-curtain.js` runs it.
 */
internal fun MainActivity.injectLeaveCurtain() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__vscodroidLeaveCurtain) return;
            window.__vscodroidLeaveCurtain = true;
            var curtain = null;
            var failsafe = 0;
            function lower() {
                if (failsafe) { clearTimeout(failsafe); failsafe = 0; }
                if (curtain) { curtain.remove(); curtain = null; }
            }
            // The root's colour is the one the page script keeps on the editor's
            // background; the body's is the workbench's own where that never ran.
            function pageColor() {
                var color = document.documentElement.style.backgroundColor;
                if (!color && document.body) color = getComputedStyle(document.body).backgroundColor;
                return color && color !== 'transparent' && color !== 'rgba(0, 0, 0, 0)' ? color : '';
            }
            window.addEventListener('beforeunload', function(e) {
                lower();
                var color = pageColor();
                if (!color) return;
                curtain = document.createElement('div');
                curtain.setAttribute('aria-hidden', 'true');
                curtain.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;' +
                    'z-index:2147483647;background:' + color;
                document.documentElement.appendChild(curtain);
                var leaving = curtain;
                // Whether the workbench vetoed is known only once its own listener
                // has run, which may be after this one, so it is asked a task later.
                setTimeout(function() {
                    if (curtain === leaving && (e.defaultPrevented || e.returnValue)) lower();
                }, 0);
                failsafe = setTimeout(lower, 5000);
            }, true);
            window.addEventListener('pageshow', lower);
            // The app took the navigation that fired beforeunload for itself, to
            // another app or to a refusal, so nothing is coming to replace this page.
            window.addEventListener('vscodroid-navigation-declined', lower);
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Makes the editor's View > Toggle Full Screen (F11) hide the device's status and
 * navigation bars.
 *
 * The workbench's toggle reads `document.fullscreen`, then asks its container to
 * `requestFullscreen()` or the document to `exitFullscreen()`. A WebView refuses the
 * request (it answers a `fullscreenerror`, which the workbench logs and goes on),
 * so the command did nothing. These answer it instead: the request goes to
 * [AndroidBridge.setFullScreen], the page's own state follows, and the
 * `fullscreenchange` the workbench listens for is raised, so the page lays itself
 * out for what the window now is.
 *
 * The state is the app's, recorded in the preferences and read when this is
 * installed. The page is told of it with the same event, and not before the
 * workbench is listening for it: the workbench builds its container and restores
 * its layout first, and registers its listener when its start-up returns. Restoring
 * Zen Mode asks for full screen in that gap, by reading `document.fullscreen`. Until
 * it has been told the page reads as not full screen and takes such a request
 * without raising an event. A page that answered yes turned the request into an
 * exit, so the editor came back from a restart with the bars shown and the choice
 * lost, and an event raised in the gap by a page that started with full screen off
 * was lost, which left the workbench out of step with the window until its next resize.
 *
 * `scripts/test-full-screen.js` runs the script and `FullScreenWiringTest` holds it
 * to where it is installed.
 */
internal fun MainActivity.injectFullScreenShim() {
    val on = workspacePrefs.getBoolean(KEY_FULL_SCREEN, false)
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__vscodroidFullScreen) return;
            // What the app is in, and what the page has been told: the page starts as
            // not full screen and is told only through `fullscreenchange`.
            var on = ${if (on) "true" else "false"};
            var known = false;
            // Whether the workbench is listening, so a change can be told as it happens.
            var armed = false;
            function token() { return (window.__vscodroid || {}).authToken; }
            function property(name, read) {
                try { Object.defineProperty(document, name, { configurable: true, get: read }); } catch (e) {}
            }
            function tell() {
                if (known === on) return;
                known = on;
                document.dispatchEvent(new Event('fullscreenchange'));
            }
            function arm() { armed = true; tell(); }
            function set(next) {
                var t = token();
                if (!t || typeof AndroidBridge === 'undefined') {
                    return Promise.reject(new TypeError('Full screen is not available'));
                }
                AndroidBridge.setFullScreen(t, next);
                on = next;
                if (armed) tell();
                return Promise.resolve();
            }
            property('fullscreen', function() { return known; });
            property('fullscreenElement', function() { return known ? document.documentElement : null; });
            Element.prototype.requestFullscreen = function() { return set(true); };
            Document.prototype.exitFullscreen = function() { return set(false); };
            window.__vscodroidFullScreen = true;
            // The workbench builds its container and restores its layout, which can ask
            // for full screen, before it follows `fullscreenchange`: it registers that
            // listener when its start-up returns, and this runs while it is starting. An
            // event raised before then is lost, so the page is told when the container
            // appears, which is a microtask after that return.
            if (document.querySelector('.monaco-workbench')) {
                arm();
            } else {
                new MutationObserver(function(records, observer) {
                    if (!document.querySelector('.monaco-workbench')) return;
                    observer.disconnect();
                    arm();
                }).observe(document, { childList: true, subtree: true });
            }
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Tells the page that a navigation it was about to leave for was declined here,
 * so the curtain [injectLeaveCurtain] raised for it comes down at once and not
 * after its failsafe. A page with no curtain ignores it.
 */
internal fun MainActivity.liftLeaveCurtain() {
    webView?.evaluateJavascript(
        "window.dispatchEvent(new Event('vscodroid-navigation-declined'));",
        null
    )
}

/**
 * Injects CSS to enlarge touch targets when the pointer is a fingertip.
 * Targets WCAG 2.5.5 minimum 44×44px for primary actions, 36px for list items.
 *
 * The test is `pointer: coarse`, not a viewport width, because what these rules
 * compensate for is the fingertip, and a fingertip does not change size with the
 * screen. A phone held in landscape is wider than any width threshold that still
 * excludes tablets, so a width test left exactly the orientation people turn to
 * for code width with desktop-sized targets. Conversely a tablet driven by a
 * mouse or a DeX-style desktop reports `fine` and correctly gets none of this.
 *
 * Height would have been the wrong axis for the same reason it looks tempting:
 * `windowSoftInputMode="adjustResize"` on MainActivity in the manifest shrinks the
 * window when the soft keyboard opens, and the WebView takes what is left
 * (`layout_weight="1"`), so a height threshold would switch the sizing on and off
 * while the user types.
 *
 * It has to be a media query rather than anything sampled in Kotlin: this runs
 * once per page load, and MainActivity's `configChanges` in the manifest absorbs
 * `orientation`, `screenSize` and `screenLayout` among others, and its
 * `onConfigurationChanged` does not inject this again, so nothing re-invokes the
 * injection when the window changes. Line numbers are left off deliberately: both citations here named
 * the right attribute and the wrong line within a day of being written. Letting the browser hold the condition costs nothing and never goes
 * stale.
 */
internal fun MainActivity.injectTouchTargetCSS() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (document.getElementById('vscodroid-touch-css')) return;
            var s = document.createElement('style');
            s.id = 'vscodroid-touch-css';
            s.textContent = [
                '/* VSCodroid: Enlarged touch targets for touch input */',
                '@media (pointer: coarse) {',
                // NO height floor on a row, tab or status entry, deliberately.
                // Three used to be here and all three were measured on an API 36
                // emulator to make the thing they were meant to help worse.
                //
                // The workbench writes those heights from JavaScript, as inline
                // styles, and a stylesheet `min-height` clamps over an inline
                // `height` by the box model, so the element paints at the floor
                // while the layout around it keeps advancing at the number JS
                // chose. `!important` is not what does it and dropping it would
                // not have helped.
                //
                //   .monaco-list-row     floored 36, rows pitched 22  -> 14px overlap
                //   .tabs-container .tab floored 40, title area 35    -> 5px overflow
                //   .statusbar-item      floored 32, status bar 22    -> 10px clipped
                //
                // `ListView.updateItemInDOM` sets `top`, `height` and `lineHeight`
                // per row from the delegate's `getHeight()`, and nearly every
                // workbench delegate answers 22: the explorer, open editors,
                // contributed tree views, search results, terminal tabs, quick
                // pick entries and the select-box dropdown. So the floor did not
                // enlarge one row, it made each row cover the top 14px of the
                // next. Rows are absolutely positioned siblings inserted in index
                // order with no z-index, and the list hit-tests by walking
                // `event.target` up to a `data-index`, so in that band the LATER
                // row both paints and takes the tap: pressing the lower edge of a
                // filename opened the file beneath it. A floor meant to make
                // targets easier to hit was making them land on the wrong row.
                //
                // There is no supported way to raise a virtualized row from CSS.
                // The height is an `IListVirtualDelegate.getHeight()` return, no
                // setting in the bundle governs it, and only lists built with
                // `supportDynamicHeights` re-measure the DOM. Raising it for real
                // means patching the delegate constants before the Code - OSS
                // build, which is a server rebuild and a rebase on every bump.
                // Until someone wants that, an honest 22px row beats a 36px one
                // that eats its neighbour.
                // The 44 on the next line is also TOUCH_ACTION_HEIGHT in patch 0030,
                // which sizes the overflow from it. ActivityBarItemSizeTest fails when
                // the two differ; change both together.
                '  .activitybar .action-item { min-height: 44px !important; }',
                '  .activitybar .action-label { min-height: 44px !important; }',
                // Horizontal padding only. The status bar is a fixed 22px part
                // (`minimumHeight === maximumHeight`), so widening an entry is
                // real estate the layout actually has; making it taller is not.
                '  .statusbar-item { padding: 0 8px !important; }',
                // The right-hand entries are a wrapping row in a bar that clips what
                // wraps, and the wrap drops the highest-priority entries first. At 411px
                // with a file open that hid six of twelve (Ln/Col, Spaces, UTF-8, LF,
                // language mode and status), and the padding above makes each entry
                // wider. One row that the script below scrolls by hand keeps them all
                // reachable.
                '  .part.statusbar > .right-items { flex-wrap: nowrap !important; overflow-x: auto !important; scrollbar-width: none !important; min-width: 0 !important; }',
                '  .part.statusbar > .right-items::-webkit-scrollbar { display: none !important; }',
                '  .context-view .action-item { min-height: 40px !important; }',
                '  .context-view .monaco-menu .action-menu-item { height: auto !important; min-height: 40px !important; }',
                '  .context-view .action-label:not(.separator) { max-height: none !important; padding: 6px 12px 6px 2em !important; }',
                // A chord beside a menu item is a promise a finger cannot keep.
                // The items run on a tap regardless (measured: Rename Symbol
                // opened its box from a touch with no focus in the menu), so
                // on a phone the label is width taken from a 411px viewport
                // to advertise a route the user does not have. The menus the
                // editor itself opens live in a shadow root this sheet cannot
                // reach; [injectTouchContextMenu] adopts the same rule there.
                '  .monaco-menu .keybinding { display: none !important; }',
                // The three floors above are for buttons, and a menu separator
                // is an .action-item too: it sits inside the activity bar when
                // the compact menubar is open and inside .context-view for a
                // right-click menu, so both floors land on a 1px divider and
                // render it as a blank band. Measured on an API 37 emulator at
                // 411px portrait, with the build-time menu CSS in play: the File
                // menu's seven separators were 71px each and the menu 1427px in
                // an 810px viewport, which is most of why it overflows at all.
                // Both halves have to go, the label's and the item's, because
                // either one alone still holds the row open.
                '  .activitybar .action-label.separator,',
                '  .context-view .action-label.separator { min-height: 0 !important; padding: 0 !important; line-height: normal !important; }',
                // Dropped whole by a WebView without :has() (before Chromium 105),
                // which leaves the divider as it was rather than breaking the rules
                // around it.
                '  .activitybar .action-item:has(> .action-label.separator),',
                '  .context-view .action-item:has(> .action-label.separator) { min-height: 0 !important; }',
                // Unprefixed, so it is wider than its name: the workbench also uses
                // .slider for the colour picker, not only the scrollbar. Harmless
                // there because that strip is already far wider than 12px, but
                // narrow it and this rule starts deciding its width.
                '  .slider { min-width: 12px !important; }',
                // The chrome's own text, which no setting of the workbench's reaches.
                // `editor.fontSize` governs the editor and nothing else; the
                // workbench styles itself from hundreds of literal pixel
                // `font-size` declarations in workbench.css, this build registers
                // no window zoom action and ignores `window.zoomLevel`, and
                // `WebSettings.textZoom` is pinned at 100. What does reach it is
                // VSCodroid: UI Scale, which scales the whole page through its
                // viewport (addUiScaleScript). These sizes are the 100% it scales.
                //
                // Measured on an API 37 emulator through the DevTools protocol, at
                // the 411 CSS px viewport a phone gives: a pane header was 11px, a
                // status bar item 12px and a tab label 13px, against 16px of editor
                // text beside them. Each rule below stays under the height the
                // workbench itself gives that element (a 22px status bar entry, a
                // 35px tab, a 22px list row), so nothing here can push text past
                // the row that holds it. Those were this project's own floors
                // until they were removed for desynchronising the layout; the
                // numbers are now the workbench's, which is why they are smaller.
                //
                // The activity bar badge is deliberately left at 9px: it is drawn
                // as a circle sized to its own glyph, so growing the text there
                // distorts the shape rather than the reading.
                '  .pane-header .title { font-size: 13px !important; }',
                '  .part.statusbar .statusbar-item { font-size: 13px !important; }',
                '  .tabs-container .tab .label-name { font-size: 14px !important; }',
                '  .monaco-list-row { font-size: 13px !important; }',
                '  .part.titlebar .agent-status-pill { box-sizing: border-box !important; height: 34px !important; }',
                '  .part.titlebar .agent-status-badge-section.sparkle .action-container { min-width: 36px !important; padding: 0 !important; box-sizing: border-box !important; }',
                '  .part.titlebar .agent-status-badge-section.sparkle .action-container .action-label { width: 100% !important; height: 100% !important; padding: 0 !important; box-sizing: border-box !important; }',
                '  .part.titlebar .agent-status-badge-section.sparkle .dropdown-action-container { width: 32px !important; box-sizing: border-box !important; }',
                '  .part.titlebar .agent-status-badge-section.sparkle .dropdown-action-container .monaco-dropdown,',
                '  .part.titlebar .agent-status-badge-section.sparkle .dropdown-action-container .dropdown-label,',
                '  .part.titlebar .agent-status-badge-section.sparkle .dropdown-action-container .action-label { width: 100% !important; height: 100% !important; padding: 0 !important; box-sizing: border-box !important; display: flex !important; align-items: center !important; justify-content: center !important; }',
                '}',
                // Gated on width, not on the pointer: what goes wrong here is a
                // 480px box in a narrower viewport, and a phone with a mouse or a
                // trackpad attached answers `pointer: fine` with the same screen.
                // 533px is where 90vw reaches 480px, above which nothing changes.
                //
                // A modal dialog is 480px wide on a 411px phone, and the
                // overflow is not shared: `.monaco-dialog-modal-block` centres
                // the box, so 34.5px hangs off each side and the left half is
                // simply unreachable. `min-width` beats `max-width` in CSS, so
                // the `max-width:90vw` sitting beside it in the same rule never
                // binds and cannot.
                //
                // Which button is lost is not luck. `rearrangeButtons` takes
                // its Linux branch here, because the WebView's user agent says
                // Linux, and that branch puts the PRIMARY action last in DOM
                // order against a right-aligned row: the affirmative button is
                // the one off the screen. On the external-link prompt that is
                // "Open", which is the whole point of the dialog, and on the
                // GitHub device-code sign-in it is the only way forward.
                //
                // `min()` rather than 0, so nothing changes where there is room:
                // a tablet keeps the 480px this reads as the designed width, and
                // only a viewport narrower than that is clamped to fit.
                '@media (max-width: 533px) {',
                '  .monaco-dialog-box { min-width: min(480px, 90vw) !important; }',
                // Clamping the box alone still loses buttons: the row is
                // `white-space:nowrap` with `overflow:hidden`, so four actions
                // that no longer fit are clipped rather than moved. Wrapping is
                // what makes the narrower box actually show them, and the 67px
                // indent that aligns them under the message is worth more as
                // width on a phone than as alignment.
                '  .monaco-dialog-box > .dialog-buttons-row > .dialog-buttons { flex-wrap: wrap !important; }',
                '  .monaco-dialog-box:not(.align-vertical) > .dialog-buttons-row > .dialog-buttons { margin-left: 0 !important; }',
                // Narrow portrait walkthrough steps clip trailing shortcut keycaps (such as [T])
                // because .step-container lacks min-width: 0, .shortcut-message sets white-space: pre
                // on a non-wrapping flex row, and .monaco-button forces min-width: max-content.
                '  .gettingStartedSlideDetails .getting-started-step .step-container { min-width: 0 !important; }',
                '  .gettingStartedSlideDetails .getting-started-step .shortcut-message { flex-wrap: wrap !important; white-space: normal !important; row-gap: 4px !important; }',
                '  .gettingStartedSlideDetails .getting-started-step .shortcut-message .monaco-keybinding { flex-wrap: wrap !important; row-gap: 2px !important; }',
                '  .gettingStartedSlideDetails .getting-started-step .step-description-container .monaco-button { max-width: 100% !important; min-width: 0 !important; }',
                '}'
            ].join('\n');
            document.head.appendChild(s);

            // A pan that starts on a status bar entry is taken by the workbench's gesture
            // layer, so the browser never scrolls the row itself: the touch events stop
            // before the bubble phase and no pointercancel follows. The row is row-reverse,
            // so scrollLeft runs from 0 at its right end down to minus the overflow.
            // Off-screen items stay visible in the accessibility tree and keyboard focus
            // order, and focusing any item brings it into view.
            var STRIP_SELECTOR = '.part.statusbar > .right-items';
            var focusedStrip = null;
            function scrollItemIntoStrip(row, target) {
                if (!row || !target || typeof row.getBoundingClientRect !== 'function') return;
                var item = target.closest ? target.closest('.statusbar-item') : target;
                if (!item || typeof item.getBoundingClientRect !== 'function') return;
                var sr = row.getBoundingClientRect();
                if (!(sr.width > 0)) return;
                var ir = item.getBoundingClientRect();
                var overflow = Math.max(0, row.scrollWidth - row.clientWidth);
                if (ir.left < sr.left) {
                    row.scrollLeft = Math.max(-overflow, Math.min(0, row.scrollLeft + (ir.left - sr.left)));
                } else if (ir.right > sr.right) {
                    row.scrollLeft = Math.max(-overflow, Math.min(0, row.scrollLeft + (ir.right - sr.right)));
                }
            }
            function attachStatusStrip() {
                if (typeof document.querySelector !== 'function') return false;
                var row = document.querySelector(STRIP_SELECTOR);
                if (!row) return false;
                if (row !== focusedStrip && typeof row.addEventListener === 'function') {
                    focusedStrip = row;
                    row.addEventListener('focusin', function(e) {
                        scrollItemIntoStrip(row, e.target);
                    });
                }
                return true;
            }
            if (!attachStatusStrip() && typeof MutationObserver === 'function' && document.body) {
                var bootObs = new MutationObserver(function() {
                    if (attachStatusStrip()) bootObs.disconnect();
                });
                bootObs.observe(document.body, { childList: true, subtree: true });
            }
            var strip = null, startX = 0, startLeft = 0;
            window.addEventListener('touchstart', function(e) {
                strip = e.touches.length === 1 && e.target.closest
                    ? e.target.closest(STRIP_SELECTOR) : null;
                if (strip) { startX = e.touches[0].clientX; startLeft = strip.scrollLeft; }
            }, { capture: true, passive: true });
            window.addEventListener('touchmove', function(e) {
                if (!strip) return;
                if (e.touches.length !== 1) { strip = null; return; }
                var overflow = strip.scrollWidth - strip.clientWidth;
                strip.scrollLeft = Math.max(-overflow, Math.min(0, startLeft - (e.touches[0].clientX - startX)));
            }, { capture: true, passive: true });
            var endPan = function() { strip = null; };
            window.addEventListener('touchend', endPan, { capture: true, passive: true });
            window.addEventListener('touchcancel', endPan, { capture: true, passive: true });
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Overrides window.open() to route external URLs through AndroidBridge.
 * VS Code web's link handling (Markdown preview, "Open in Browser") calls window.open(),
 * which is blocked by shouldOverrideUrlLoading. This intercepts at the JS level instead.
 */
internal fun MainActivity.injectWindowOpenOverride() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__vscodroidOpenPatched) return;
            window.__vscodroidOpenPatched = true;
            var orig = window.open;
            window.open = function(url) {
                // `new URL(...)` is a valid first argument and has no indexOf, so the
                // tests below would throw on it. Read it as the text it stands for.
                if (url != null) url = String(url);
                // The editor asking for a second window, which on a device is
                // this one. The workbench builds that URL from its own origin
                // and pathname and puts no connection token on it, so handing
                // it to the system browser opened a browser showing
                // "Forbidden." and left a popup-blocked dialog over the
                // editor. Navigating in place is what the workbench already
                // does for Close Workspace and Open Folder, on the branch
                // where it has a window to reuse.
                //
                // A prefix test, not a search: an OAuth `redirect_uri` naming
                // 127.0.0.1 in its query would match a substring search and
                // blank the editor mid sign-in. A dev server on another port
                // is a different origin and still reaches the bridge, which
                // is the branch openExternalUrl's localhost handling exists
                // for.
                //
                // Returns the window rather than null: the caller reads
                // `!!window.open(...)` and draws its own popup-blocked
                // message for a falsy answer.
                //
                // Only the workbench's own address, `/` with at most a query
                // or a fragment, is navigated to. Any other path on this
                // origin is refused here and not later: the server serves a
                // workspace's own HTML through /vscode-remote-resource, and a
                // page loaded from there shares the editor's storage and
                // bridge. Kotlin refuses that load too, but only after the
                // workbench has run beforeunload and stopped its extension
                // host, which left the editor half dead; measured on an
                // emulator.
                var origin = window.location.origin;
                var root = origin + '/';
                var lowerUrl = url ? url.toLowerCase() : '';
                var lowerOrigin = origin.toLowerCase();
                var isRoot = url && lowerUrl.slice(0, root.length) === lowerOrigin + '/';
                var isBare = url && (lowerUrl === lowerOrigin ||
                    (lowerUrl.slice(0, lowerOrigin.length) === lowerOrigin &&
                        (url.charAt(lowerOrigin.length) === '?' || url.charAt(lowerOrigin.length) === '#')));
                if (isRoot || isBare) {
                    var next = isRoot ? url.charAt(root.length) : '';
                    if (next === '' || next === '?' || next === '#') {
                        window.location.href = url;
                    }
                    return window;
                }
                if (url && /^https?:/i.test(url) && typeof AndroidBridge !== 'undefined') {
                    var t = (window.__vscodroid || {}).authToken;
                    // Only claim the click if the bridge actually opened it.
                    // `openExternalUrl` answers with a reason when the launch itself
                    // fails, not when it disapproves of the destination: SecurityManager
                    // has no URL allow-list and says so at the point one used to
                    // stand. Reading this as a destination filter is the mistake to
                    // avoid, because it invites re-deriving the fall-through around
                    // a constraint that is gone.
                    // Swallowing the refusal here meant the click did nothing and
                    // said nothing; falling through lets the WebView navigation
                    // path open it, which is where opening anything already lives.
                    //
                    // Compared against the empty string, never used as a bare
                    // condition. The bridge answers with the reason it did not
                    // open, so success is the falsy value and every failure is
                    // truthy: a bare `if` reads backwards, claims every click it
                    // failed to open, and lets the one it did open through to the
                    // WebView as well.
                    if (t && AndroidBridge.openExternalUrl(url, t) === '') { return null; }
                }
                return orig.apply(window, arguments);
            };
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Answers the workbench's clipboard READ out of Android's clipboard.
 *
 * Copy works and paste does not, and the asymmetry is the WebView's: it
 * grants the page `clipboard-write` and refuses `clipboard-read`. There is
 * no site-permission UI in a WebView to grant it in, and
 * `WebChromeClient.onPermissionRequest` has no clipboard resource to grant
 * even if there were, so nothing the user can do makes the read succeed.
 *
 * What the workbench does with the rejection is the visible half. Its one
 * `IClipboardService.readText` catches it and raises a STICKY notification
 * telling the user to grant a permission that does not exist here, and the
 * promise only settles when they dismiss it, resolving the empty string. So
 * every paste costs a modal that cannot be acted on and pastes nothing.
 *
 * [ClipboardBridge.readFromClipboard] has been able to answer this the whole
 * time. It is token-validated, it declines to open a `content://` URI through
 * our own ContentResolver, and it is unit-tested. It simply had no caller in
 * the page: the only hits outside the built tree were the bridge itself and
 * its test. This is that caller.
 *
 * On the prototype rather than on `navigator.clipboard`, because the call
 * site resolves the method off the ACTIVE window's navigator on every paste
 * rather than capturing it once, and the prototype is what every one of those
 * lookups ends at. The instance is patched instead only where the interface
 * global is missing, which is not this WebView but costs two lines to be sure
 * of.
 *
 * Injected after load, which is soon enough and provably so: nothing captures
 * `readText` at bundle-evaluation time. The one thing read that early is a
 * capability probe that tests the method for truthiness to decide whether to
 * register the terminal's paste command, and a wrapper is still a function,
 * so the probe answers the same either way.
 *
 * The original is tried FIRST and this only answers its rejection. A WebView
 * that one day grants the permission then keeps its own answer, and the
 * fallback costs a rejected promise per paste until it does.
 *
 * A null answer is the empty string, not an error: the bridge returns null
 * for a clipboard holding no text, and pasting nothing is the correct outcome
 * there. A missing bridge or a missing token rethrows instead, so a genuine
 * failure still reaches the user as the error it is rather than as a paste
 * that silently did nothing.
 *
 * Fixes the editor, the terminal, the command palette and every extension's
 * `vscode.env.clipboard.readText()` at once: all of them are that one service.
 */
internal fun MainActivity.injectClipboardReadFallback() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__vscodroidClipboardPatched) return;
            function fallback(reason) {
                var t = (window.__vscodroid || {}).authToken;
                if (!t || typeof AndroidBridge === 'undefined') { throw reason; }
                var text = AndroidBridge.readFromClipboard(t);
                return (text === null || text === undefined) ? '' : text;
            }
            function wrap(orig) {
                return function() {
                    var self = this, args = arguments;
                    try {
                        return Promise.resolve(orig.apply(self, args)).catch(fallback);
                    } catch (e) {
                        return Promise.reject(e).catch(fallback);
                    }
                };
            }
            var proto = window.Clipboard && window.Clipboard.prototype;
            if (proto && typeof proto.readText === 'function') {
                proto.readText = wrap(proto.readText);
            } else if (navigator.clipboard && typeof navigator.clipboard.readText === 'function') {
                navigator.clipboard.readText = wrap(navigator.clipboard.readText.bind(navigator.clipboard));
            } else {
                return;
            }
            window.__vscodroidClipboardPatched = true;
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Lets a file the editor downloads be saved to the device.
 *
 * Two jobs, both of which have to happen inside the page because both are
 * about objects that only exist there.
 *
 * The first is keeping the bytes reachable. The editor reads a file into
 * memory, wraps it in a blob, hands the platform a `blob:` URL for it and
 * revokes that URL on the very next task. Saving needs the user to choose a
 * destination first, which takes seconds, so by the time there is anywhere
 * to write the URL names nothing at all and the download would fail every
 * time. Revocation is therefore deferred, and only for URLs a download is
 * actually using, so nothing else in the workbench is kept alive by this.
 * The Blob itself is kept alongside, because the bytes have to be read off
 * the object: the page is served under `connect-src 'self' ws: wss: https:`,
 * so a request for a `blob:` URL is refused before it starts, and that is
 * every download of a file the editor could hold in memory.
 *
 * The second is the name. A blob has none, and the platform's download hook
 * is given the URL rather than the anchor, so `App.kt` would arrive as a
 * UUID. The anchor knows, at the moment it is clicked, and a bridge call
 * blocks the page until it returns, so reporting the name here puts it on
 * the Android side before the download hook can ask for it.
 *
 * Both hooks call through unconditionally. Nothing here decides whether a
 * click downloads, and a page that stopped downloading because our
 * bookkeeping threw would be a worse failure than the one being fixed.
 */
internal fun MainActivity.injectDownloadCapture() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__vscodroidDownload) return;

            // Long enough to outlast the queue behind it. Only one download
            // holds the create-document picker at a time, so a file clicked as
            // part of a multi-select waits behind up to MAX_QUEUED pickers
            // before anyone asks for its bytes, and each of those is a trip
            // into another app that takes the user as long as it takes. At two
            // minutes the later files were revoked while their own picker was
            // still on screen, so the user chose a folder and a name and was
            // handed a failure. The hold is released as soon as the bytes are
            // being read (see readerFor), and again the moment Android gives
            // up on a download it never read (see release), so this ceiling
            // is the backstop for the one case neither covers: a download
            // whose end nobody can report, because the page it belonged to
            // is the thing that went away.
            var HOLD_MS = 600000;
            var MAX_TRACKED = 8;

            // url -> the object behind it. The page is asked for the bytes
            // of a download by URL, and the bytes have to come off this
            // object rather than off the URL: see readerFor below.
            var made = new Map();
            var held = new Map();

            function token() { return (window.__vscodroid || {}).authToken; }

            var create = URL.createObjectURL.bind(URL);
            URL.createObjectURL = function(source) {
                var url = create(source);
                try {
                    made.set(url, source);
                    // Bounded, because the workbench mints object URLs for
                    // all sorts of things and remembering every one of them
                    // would pin its bytes for the life of the page. A
                    // download claims its own entry on the click, which is
                    // the task straight after this one.
                    if (made.size > MAX_TRACKED) made.delete(made.keys().next().value);
                } catch (e) { /* the URL matters more than the record of it */ }
                return url;
            };

            var revoke = URL.revokeObjectURL.bind(URL);
            URL.revokeObjectURL = function(url) {
                if (held.has(url)) return;
                made.delete(url);
                revoke(url);
            };

            // Each hold releases itself, so a download nobody completes
            // costs one blob for the length of HOLD_MS rather than for the
            // life of the page.
            function hold(url) {
                if (held.has(url)) return;
                held.set(url, made.get(url));
                made.delete(url);
                setTimeout(function() { held.delete(url); revoke(url); }, HOLD_MS);
            }

            // The bytes of url, as a reader.
            //
            // A blob is read off the object and never off its URL. The
            // workbench page is served under
            // connect-src 'self' ws: wss: https:, which does not list
            // blob:, so fetching a blob: URL is refused before it leaves
            // the page: "Connecting to 'blob:...' violates the following
            // Content Security Policy directive". Reading a Blob is not a
            // request and no directive governs it. Anything else is a real
            // URL the policy already allows.
            function readerFor(url) {
                var blob = held.get(url);
                if (blob && blob.stream) {
                    var reader = blob.stream().getReader();
                    // The hold has done its job the moment the reader exists:
                    // the reader keeps the bytes alive by itself, so the page
                    // gets the memory back now rather than at the end of the
                    // budget above. Only on this branch, because the fetch
                    // below has not read anything yet and revoking under it
                    // would refuse the very request the hold exists for.
                    held.delete(url);
                    revoke(url);
                    return Promise.resolve(reader);
                }
                return fetch(url).then(function(response) {
                    if (!response.ok) throw new Error('status ' + response.status);
                    return response.body.getReader();
                });
            }

            var click = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function() {
                try {
                    var name = this.getAttribute('download');
                    var url = this.href;
                    if (name !== null && url) {
                        if (url.lastIndexOf('blob:', 0) === 0) hold(url);
                        var t = token();
                        if (t && window.AndroidBridge) {
                            AndroidBridge.noteDownloadName(t, url, name);
                        }
                    }
                } catch (e) { /* the click matters more than the record of it */ }
                return click.apply(this, arguments);
            };

            function encode(bytes) {
                var text = '';
                for (var i = 0; i < bytes.length; i += 0x8000) {
                    text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
                }
                return btoa(text);
            }

            window.__vscodroidDownload = {
                // Lets go of a download Android has finished with without
                // ever reading it: refused for being one of too many at
                // once, cancelled at the picker, or failed before the
                // bytes were asked for. Without this the blob stays
                // pinned here for the whole of HOLD_MS after the user has
                // been told the file is not coming, and a multi-select
                // pins every file the queue turned away at once.
                release: function(url) {
                    if (!held.has(url)) return;
                    held.delete(url);
                    revoke(url);
                },
                // Reads url and pushes it back in pieces under id. Answers
                // whether it started, which is the one failure Android
                // cannot be told about any other way.
                send: function(url, id) {
                    var t = token();
                    var bridge = window.AndroidBridge;
                    if (!t || !bridge) return false;
                    // Started on a promise so that a reader this page
                    // cannot open at all is reported like any other failed
                    // read, rather than thrown back at the caller that has
                    // already been told the read began.
                    Promise.resolve().then(function() {
                        return readerFor(url);
                    }).then(function(reader) {
                        return (function pump() {
                            return reader.read().then(function(step) {
                                if (step.done) {
                                    bridge.finishDownload(t, id, '');
                                    return;
                                }
                                // A refused chunk has already been explained
                                // on the Android side. Reporting it again
                                // here would replace that reason with this
                                // one, which says nothing.
                                if (!bridge.writeDownloadChunk(t, id, encode(step.value))) {
                                    reader.cancel();
                                    return;
                                }
                                // Yield between pieces: every bridge call
                                // blocks this thread, so a large file would
                                // otherwise freeze the editor for the whole
                                // transfer.
                                return new Promise(function(go) {
                                    setTimeout(go, 0);
                                }).then(pump);
                            });
                        })();
                    }).catch(function(e) {
                        bridge.finishDownload(t, id, String((e && e.message) || e) || 'failed');
                    });
                    return true;
                }
            };
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Injects a BroadcastChannel relay into the WebView main page.
 *
 * Browser extensions run in a Web Worker, which has its own global scope and does
 * not see objects added to a page with addJavascriptInterface. This relay listens on
 * a BroadcastChannel in the page and forwards calls to AndroidBridge, which is what
 * lets an extension reach it at all.
 *
 * ⚠️ **Who can post here is every script on the workbench's origin, not the
 * bundled extension.** `product.json` carries no `webEndpointUrlTemplate`, and
 * without one the workbench starts the web extension host in a SAME-ORIGIN
 * iframe and says so (`console.warn("The web worker extension host is started
 * in a same-origin iframe!")` in `out/vs/code/browser/workbench/workbench.js`).
 * Browser extensions run inside a Dedicated Web Worker (which has no `window`
 * or DOM access and cannot directly read `window.__vscodroid.authToken` or call
 * `AndroidBridge`), but because the worker shares the workbench origin, every
 * web extension the user installs from Open VSX can open the `vscodroid-bridge`
 * `BroadcastChannel`. The token read below is no barrier there because this
 * page-side relay automatically attaches `window.__vscodroid.authToken` when
 * forwarding messages from that channel. Answers are posted back onto the same
 * channel, so every listener sees every reply, including replies to commands
 * somebody else asked for.
 *
 * What that means for what may be dispatched here, worked out per command
 * rather than assumed:
 *
 *  - The caller ALREADY has more than this channel gives it. A web extension
 *    can open a terminal and run anything as this app's uid, so every reading
 *    command here (`getRecentFolders`, `getStorageBreakdown`, `listSafMirrors`,
 *    `getSshPublicKey`, `listSshKeys`, `generateBugReport`) discloses what the
 *    caller could already read off the filesystem, apart from the system's
 *    record of how this app's own processes ended, which `generateBugReport`
 *    quotes: times, reasons and memory, nothing of the user's. They stay, and
 *    what changes instead is that they hand over no more than they must:
 *    `listSshKeys` no longer reports a key's comment, which is conventionally
 *    an email address.
 *  - Commands that put an Android surface on screen (`openFolderPicker`,
 *    `openToolchainSettings`, `showAboutDialog`) are visible and dismissible,
 *    and none of them changes anything on its own. They stay.
 *  - `generateSshKey` never overwrites a pair, and `clearCaches` deletes only
 *    regenerable caches. Neither loses the user's own work.
 *  - `toggleExtraKeyRow` persists a choice rather than showing a surface, so
 *    any caller can hide the row, silently while the keyboard is down, and
 *    hide it again after the user brings it back, since every reply is seen
 *    by every listener. That is accepted on the first bullet's terms: such a
 *    caller already has a terminal. What it costs is a convenience, not the
 *    user's work, and the user can always run the command again.
 *  - `getUiScale` and `setUiScale` reach no Android code: the page answers
 *    them from its own viewport and localStorage. Any caller can change the
 *    size unasked, as with the key row, but only to a size this screen is
 *    offered, and the user can run the command again.
 *  - `openExternalUrl` is the one command that reaches outside the app at all,
 *    and it is the one that was narrowed: see `AndroidBridge.openExternalUrl`,
 *    which now refuses this app's own `vscodroid://callback`.
 *  - `openRecentFolder` carries the URI with the call, so the folder it opens
 *    is the caller's choice rather than the user's. What bounds it is the
 *    grant: the recent list a legitimate caller picks from is pruned of
 *    revoked grants before it is handed over
 *    (`SafStorageManager.getPersistedFolders`), and a URI this app holds no
 *    grant for is now refused with a notice instead of being answered with the
 *    system folder chooser. See [openRecentSafFolder].
 *  - `reclaimSafMirror` with `force` is the only command here that destroys
 *    user data, and its own documentation says the caller must have confirmed
 *    a modal saying so. A caller is exactly what cannot be asked to promise
 *    that, so [removeDeviceFolderCopy] now asks the user itself.
 */
internal fun MainActivity.injectBridgeRelay() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (typeof AndroidBridge === 'undefined') return;
            if (window.__vscodroidRelayActive) return;
            window.__vscodroidRelayActive = true;
            var ch = new BroadcastChannel('vscodroid-bridge');
            // How an answer that could not be given while the caller waited
            // gets back to it. A bridge call does not return to JavaScript
            // until the Kotlin method has finished, and the thread it holds
            // is this page's own, so a method that walks the disk freezes the
            // workbench for as long as the walk. Those methods hand back at
            // once and Android posts the answer here against the same id the
            // caller sent; the extension already routes a reply by id.
            window.__vscodroidBridgeReply = function(id, ok, payload) {
                ch.postMessage(ok
                    ? {id: id, ok: true, data: payload}
                    : {id: id, ok: false, error: payload});
            };
            ch.onmessage = function(e) {
                var d = e.data;
                var token = (window.__vscodroid || {}).authToken;
                if (!token || !d || !d.cmd) return;
                try {
                    var result;
                    if (d.cmd === 'openFolderPicker') {
                        AndroidBridge.openFolderPicker(token);
                        ch.postMessage({id: d.id, ok: true});
                    } else if (d.cmd === 'getRecentFolders') {
                        result = AndroidBridge.getRecentFolders(token);
                        ch.postMessage({id: d.id, ok: true, data: result});
                    } else if (d.cmd === 'openRecentFolder') {
                        AndroidBridge.openRecentFolder(token, d.uri);
                        ch.postMessage({id: d.id, ok: true});
                    } else if (d.cmd === 'getStorageBreakdown') {
                        // Answered later, by id, through the hook above. A
                        // non-empty answer HERE is a refusal decided before
                        // any work started, so it is posted as the error.
                        result = AndroidBridge.getStorageBreakdown(token, d.id);
                        if (result !== '') ch.postMessage({id: d.id, ok: false, error: result});
                    } else if (d.cmd === 'clearCaches') {
                        result = AndroidBridge.clearCaches(token, d.id);
                        if (result !== '') ch.postMessage({id: d.id, ok: false, error: result});
                    } else if (d.cmd === 'generateBugReport') {
                        result = AndroidBridge.generateBugReport(token);
                        ch.postMessage({id: d.id, ok: true, data: result});
                    } else if (d.cmd === 'openToolchainSettings') {
                        AndroidBridge.openToolchainSettings(token);
                        ch.postMessage({id: d.id, ok: true});
                    } else if (d.cmd === 'generateSshKey') {
                        result = AndroidBridge.generateSshKey(token, d.comment || '');
                        ch.postMessage({id: d.id, ok: true, data: result});
                    } else if (d.cmd === 'getSshPublicKey') {
                        result = AndroidBridge.getSshPublicKey(token);
                        ch.postMessage({id: d.id, ok: true, data: result});
                    } else if (d.cmd === 'listSshKeys') {
                        result = AndroidBridge.listSshKeys(token);
                        ch.postMessage({id: d.id, ok: true, data: result});
                    } else if (d.cmd === 'showAboutDialog') {
                        AndroidBridge.showAboutDialog(token);
                        ch.postMessage({id: d.id, ok: true});
                    } else if (d.cmd === 'toggleExtraKeyRow') {
                        result = AndroidBridge.toggleExtraKeyRow(token);
                        ch.postMessage({id: d.id, ok: true, data: result});
                    } else if (d.cmd === 'getUiScale' && window.__vscodroidUiScale) {
                        // This and the next are answered in the page, with no
                        // bridge call: the scale is the page's own viewport and
                        // is kept in its localStorage. The document-start
                        // script from addUiScaleScript leaves the hook.
                        ch.postMessage({id: d.id, ok: true, data: window.__vscodroidUiScale.state()});
                    } else if (d.cmd === 'setUiScale' && window.__vscodroidUiScale) {
                        window.__vscodroidUiScale.set(d.scale, function(scale) {
                            ch.postMessage({id: d.id, ok: true, data: scale});
                        });
                    } else if (d.cmd === 'getUiScale' || d.cmd === 'setUiScale') {
                        // No hook: the WebView cannot run document-start scripts.
                        ch.postMessage({
                            id: d.id, ok: false,
                            error: 'The installed Android System WebView cannot scale the interface. ' +
                                'Update it from Google Play, then reopen VSCodroid.'
                        });
                    } else if (d.cmd === 'openExternalUrl') {
                        // The only branch here whose bridge method can decline. Every
                        // other one either returns data or cannot fail in a way the
                        // caller could act on, which is why they post ok:true flatly.
                        // Posting ok:true for this one turned a blocked URL into a
                        // resolved promise, and the caller's error handler never ran.
                        //
                        // The reason comes from the bridge, which is the only
                        // side that knows it. This used to post one fixed sentence
                        // for every refusal, blaming a missing app even when the
                        // session token was stale or Android had refused the URL
                        // outright, and a user following that advice installs
                        // something that cannot help.
                        //
                        // Empty means opened. Anything else is the reason, so the
                        // comparison is against the empty string rather than a
                        // bare truthiness test, which would read backwards.
                        result = AndroidBridge.openExternalUrl(d.url, token);
                        ch.postMessage(result === ''
                            ? {id: d.id, ok: true}
                            : {id: d.id, ok: false, error: result});
                    } else if (d.cmd === 'listSafMirrors') {
                        result = AndroidBridge.listSafMirrors(token, d.id);
                        if (result !== '') ch.postMessage({id: d.id, ok: false, error: result});
                    } else if (d.cmd === 'reclaimSafMirror') {
                        // openExternalUrl's convention, now applying twice
                        // over. Empty from the call below means the removal
                        // was accepted and its outcome follows by id; the
                        // outcome itself is empty for a removal and a
                        // sentence for a refusal, and the Kotlin side decides
                        // which of the two it posted. Posting ok:true flatly
                        // for either would tell the user their disk had been
                        // freed when the removal was refused because the
                        // folder is still open.
                        result = AndroidBridge.reclaimSafMirror(
                            token, d.hash, d.force === true, d.id);
                        if (result !== '') ch.postMessage({id: d.id, ok: false, error: result});
                    } else {
                        // A command this chain does not know is answered rather
                        // than dropped. Without this the caller's promise died
                        // on its own deadline and reported "Bridge timeout: is
                        // the app running on Android?", accusing the platform
                        // of not being there, after five seconds or after two
                        // minutes for a storage command. It is also the exact
                        // failure of adding a bridge method and forgetting its
                        // relay branch, which is the moment a clear message is
                        // worth most.
                        ch.postMessage({
                            id: d.id, ok: false,
                            error: 'VSCodroid does not know the command ' + d.cmd
                        });
                    }
                } catch(err) {
                    ch.postMessage({id: d.id, ok: false, error: String(err)});
                }
            };
        })();
        """.trimIndent(),
        null
    )
}

/**
 * Registers the page-side consumer of the memory-pressure callback.
 *
 * `onTrimMemory` fires `window.__vscodroid?.onLowMemory?.(level)` and nothing
 * was listening, so this exists to give that call somewhere to land.
 *
 * It records the level and does nothing else, and the two things it used to do
 * are worth naming so neither comes back. It called `gc()`, which does not
 * exist in a WebView: the function is behind V8's `--expose-gc` and the branch
 * was dead on every device. And it walked `performance.getEntries()` and
 * revoked every entry whose name began with `blob:`, which is a list of blob
 * resources the page has FETCHED rather than ones this app created: the
 * shipped workbench keeps its own `_blobUrlCache` and hands the same URL back
 * on a later load, so revoking them broke the next load of an image, a media
 * element or a worker built from one. `TRIM_MEMORY_BACKGROUND` maps to
 * critical, so that fired on ordinary backgroundings whenever the system was
 * reclaiming, and the damage outlived the pressure. Nothing handed those URLs
 * over and nothing asked whether they were still in use.
 *
 * The one revocation this app does own is in [injectDownloadCapture], which
 * tracks the URLs it holds and releases them itself.
 */
internal fun MainActivity.injectMemoryPressureHandler() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__vscodroidMemoryHandlerActive) return;
            window.__vscodroidMemoryHandlerActive = true;
            window.__vscodroid = window.__vscodroid || {};
            window.__vscodroid.onLowMemory = function(level) {
                console.warn('[VSCodroid] Memory pressure: level=' + level);
            };
        })();
        """.trimIndent(),
        null
    )
}
