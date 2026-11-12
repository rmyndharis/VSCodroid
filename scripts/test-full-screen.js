/**
 * Self-check for the script that lets the editor's View > Toggle Full Screen hide
 * the device's status and navigation bars.
 *
 *   node scripts/test-full-screen.js
 *
 * The script is JavaScript inside the Kotlin raw string of
 * `MainActivity.injectFullScreenShim()`, so nothing compiles it and
 * `FullScreenWiringTest` can only match its text. What it has to get right is
 * behaviour. The workbench's toggle reads `document.fullscreen`, then calls
 * `requestFullscreen()` on its container or `exitFullscreen()` on the document, and
 * follows `fullscreenchange`: a request has to reach the bridge with the session
 * token, the state the page reads has to move with it, and the event has to be
 * raised once per change, never for a request that changes nothing. A request the
 * page cannot hand to the app has to fail, not pretend. Every page is told its state
 * once the workbench is listening, which is when its container appears, and until then
 * it reads as not full screen and raises no event: the workbench's own start (restoring
 * Zen Mode) asks for a toggle by reading `document.fullscreen` before it registers its
 * listener, an answer of "yes" that it has not been told about turns the request into an
 * exit, and an event raised before the listener exists is lost.
 *
 * This extracts the real script and runs it under `vm` against a stub page.
 *
 * NEGATIVE CONTROL, measured, each by editing the script and running this: not
 * raising `fullscreenchange` fails the cases that count events; raising it for a
 * request that changes nothing fails the repeat case; resolving without the bridge
 * call fails the three cases that read it; dropping the token check fails the
 * missing-token case; reading the state from a constant fails the cases that read
 * `document.fullscreen` after a change; dropping the announcement for a page that
 * starts on fails that case; announcing at install and not when the container appears
 * fails the case that has none yet; never announcing once it appears fails that case;
 * not letting the observer go fails the case that reads `live`; dropping the bridge
 * presence check fails the case without a bridge; dropping the guard fails the one that
 * installs twice. For what the page reads before it has been told, the same way:
 * answering `fullscreen` or `fullscreenElement` from the app's state and not from what
 * the page was told fails the cases that read them before the container exists; telling
 * the page before the workbench is listening, or counting it as listening from the start,
 * fails the restore case; arming without telling fails the announcement; recording what the
 * page was told after the event and not before fails the cases that read it inside the event.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const MAIN_ACTIVITY = path.join(
    __dirname, '..', 'android/app/src/main/kotlin/com/vscodroid/MainActivity.kt',
);
const INTERPOLATION = '${if (on) "true" else "false"}';

/** The raw string in `injectFullScreenShim()` with the saved state filled in. */
function extractScript(on) {
    const lines = fs.readFileSync(MAIN_ACTIVITY, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('private fun injectFullScreenShim()'));
    assert.notStrictEqual(
        fn, -1,
        'injectFullScreenShim() is gone from MainActivity.kt, so this check has nothing to ' +
        'run. If the script moved, point this at its new home rather than deleting the check.',
    );
    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close !== -1 && close > open + 1,
        'could not find the raw string in injectFullScreenShim(); its shape changed');
    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
    assert.ok(js.includes(INTERPOLATION),
        'the saved state is no longer interpolated the way this check fills it');
    const filled = js.split(INTERPOLATION).join(String(on));
    assert.ok(!filled.includes('$'),
        'the script gained a Kotlin interpolation this check does not know how to fill');
    assert.ok(filled.includes("'fullscreenchange'"),
        'the extracted text raises no fullscreenchange, so the wrong block was extracted');
    return filled;
}

/** A page with the script installed, `times` times, and what it did to the bridge. */
function page({ on = false, token = 'session', bridge = true, times = 1, container = true } = {}) {
    const calls = [];
    const events = [];
    // What a listener of the event reads while it runs, which is how the workbench decides
    // whether the window is full screen: it looks at `fullscreenElement` inside the handler.
    const seen = [];
    const observers = [];
    const state = { container };
    const documentElement = { name: 'html' };
    const document = {
        documentElement,
        dispatchEvent(event) {
            events.push(event.type);
            seen.push([this.fullscreen, this.fullscreenElement === documentElement]);
            return true;
        },
        querySelector(selector) {
            assert.strictEqual(selector, '.monaco-workbench', 'only the workbench container is asked for');
            return state.container ? { name: 'workbench' } : null;
        },
    };
    // Calls the callback the script registered whenever the case says the page changed.
    class MutationObserver {
        constructor(callback) { this.callback = callback; this.target = null; this.live = true; observers.push(this); }
        observe(target, options) { this.target = target; this.options = options; }
        disconnect() { this.live = false; }
    }
    // Every Element and Document the script patches is its own prototype here, so a
    // second page in the same process cannot inherit what the first one installed.
    class Element {}
    class Document {}
    const context = vm.createContext({
        window: { __vscodroid: token === null ? {} : { authToken: token } },
        document,
        Element,
        Document,
        Event: class { constructor(type) { this.type = type; } },
        MutationObserver,
        TypeError,
        Promise,
        AndroidBridge: bridge ? { setFullScreen: (t, v) => calls.push([t, v]) } : undefined,
    });
    Object.setPrototypeOf(document, Document.prototype);
    if (!bridge) delete context.AndroidBridge;
    for (let i = 0; i < times; i += 1) vm.runInContext(extractScript(on), context);
    return {
        document, container: new Element(), calls, events, seen, documentElement, observers,
        /** The workbench builds its container: the page changes, observers are told. */
        addContainer() {
            state.container = true;
            for (const o of observers.filter((x) => x.live)) o.callback([], o);
        },
    };
}

async function main() {
    // A page that loads with full screen off.
    {
        const p = page();
        assert.strictEqual(p.document.fullscreen, false, 'off at load');
        assert.strictEqual(p.document.fullscreenElement, null, 'no element at load');
        assert.deepStrictEqual(p.events, [], 'a page that starts off announces nothing');
    }

    // A request reaches the bridge, moves the state, and raises one event.
    {
        const p = page();
        await p.container.requestFullscreen();
        assert.deepStrictEqual(p.calls, [['session', true]], 'the request carries the token');
        assert.strictEqual(p.document.fullscreen, true, 'state follows the request');
        assert.strictEqual(p.document.fullscreenElement, p.documentElement, 'an element while on');
        assert.deepStrictEqual(p.events, ['fullscreenchange'], 'one event for one change');
        assert.deepStrictEqual(p.seen, [[true, true]], 'a listener already reads the new state');

        // The same request again changes nothing: the app is told, the page is not.
        await p.container.requestFullscreen();
        assert.deepStrictEqual(p.events, ['fullscreenchange'], 'no event when nothing changed');
        assert.strictEqual(p.document.fullscreen, true);

        await p.document.exitFullscreen();
        assert.deepStrictEqual(p.calls.at(-1), ['session', false], 'the exit carries the token');
        assert.strictEqual(p.document.fullscreen, false, 'state follows the exit');
        assert.strictEqual(p.document.fullscreenElement, null, 'no element once off');
        assert.deepStrictEqual(p.events, ['fullscreenchange', 'fullscreenchange'], 'one event for the exit');
        assert.deepStrictEqual(p.seen, [[true, true], [false, false]], 'and reads the new state for the exit too');
    }

    // What the workbench does: read document.fullscreen, pick the call, ask twice in a row.
    {
        const p = page();
        const toggle = () => (p.document.fullscreen
            ? p.document.exitFullscreen() : p.container.requestFullscreen());
        await toggle();
        await toggle();
        await toggle();
        assert.deepStrictEqual(p.calls.map((c) => c[1]), [true, false, true], 'three toggles alternate');
        assert.strictEqual(p.document.fullscreen, true);
    }

    // A page that cannot reach the app fails the request and keeps its state.
    {
        const p = page({ token: null });
        await assert.rejects(() => p.container.requestFullscreen(), TypeError);
        assert.strictEqual(p.document.fullscreen, false, 'state unchanged without a token');
        assert.deepStrictEqual(p.calls, [], 'the bridge is not called without a token');
        assert.deepStrictEqual(p.events, [], 'no event for a request that failed');
    }
    {
        const p = page({ bridge: false });
        await assert.rejects(() => p.container.requestFullscreen(), TypeError);
        assert.strictEqual(p.document.fullscreen, false, 'state unchanged without a bridge');
        assert.deepStrictEqual(p.events, []);
    }

    // A page that loads while full screen is on says so once the workbench has built its
    // container, because the workbench follows the event only after that and an event
    // raised while it is still starting is lost. Until then it reads as not full screen,
    // which is what the workbench itself believes at that point.
    {
        const p = page({ on: true, container: false });
        assert.strictEqual(p.document.fullscreen, false, 'not told yet, so not full screen');
        assert.strictEqual(p.document.fullscreenElement, null, 'and no element either');
        assert.deepStrictEqual(p.events, [], 'nothing is announced before the container exists');
        assert.strictEqual(p.observers.length, 1, 'one observer waits for the container');
        assert.strictEqual(p.observers[0].target, p.document, 'it watches the document');
        // Read as text: an object made inside the vm has another realm's prototype.
        assert.strictEqual(JSON.stringify(p.observers[0].options), '{"childList":true,"subtree":true}');
        p.observers[0].callback([], p.observers[0]);
        assert.deepStrictEqual(p.events, [], 'a change that is not the container announces nothing');
        assert.strictEqual(p.observers[0].live, true, 'and keeps waiting');
        p.addContainer();
        assert.deepStrictEqual(p.events, ['fullscreenchange'], 'announced once when the container appears');
        assert.deepStrictEqual(p.seen, [[true, true]], 'a listener reads full screen while it runs');
        assert.strictEqual(p.document.fullscreen, true, 'full screen once it has been announced');
        assert.strictEqual(p.document.fullscreenElement, p.documentElement, 'with its element');
        assert.strictEqual(p.observers[0].live, false, 'the observer is let go');
        p.addContainer();
        assert.deepStrictEqual(p.events, ['fullscreenchange'], 'later changes to the page announce nothing more');
        await p.document.exitFullscreen();
        assert.strictEqual(p.document.fullscreen, false, 'can be turned off after a reload');
    }
    {
        const p = page({ on: true });
        assert.deepStrictEqual(p.events, ['fullscreenchange'], 'a page whose container exists announces at once');
        assert.strictEqual(p.document.fullscreen, true, 'and is full screen from then on');
        assert.strictEqual(p.observers.length, 0, 'and waits for nothing');
    }

    // The workbench restores Zen Mode while it starts, which is after its container exists
    // in the page and before the observer has run. It reads `document.fullscreen`, finds
    // "no", and asks for full screen, which the app is already in. That has to leave the
    // app in full screen and the event to come when the page is told, not before.
    {
        const p = page({ on: true, container: false });
        const toggle = () => (p.document.fullscreen
            ? p.document.exitFullscreen() : p.container.requestFullscreen());
        await toggle();
        assert.deepStrictEqual(p.calls, [['session', true]], 'the restore asks to enter, never to leave');
        assert.deepStrictEqual(p.events, [], 'no event before the workbench has been told');
        p.addContainer();
        assert.deepStrictEqual(p.events, ['fullscreenchange'], 'told once, when the container appears');
        assert.strictEqual(p.document.fullscreen, true, 'and full screen after that');
        assert.deepStrictEqual(p.calls, [['session', true]], 'nothing else reached the app');
    }

    // A request to leave that comes before the page has been told: the page never heard
    // that it was full screen, so there is nothing to retract, and the next request to
    // enter is a change like any other.
    {
        const p = page({ on: true, container: false });
        await p.document.exitFullscreen();
        assert.deepStrictEqual(p.calls, [['session', false]], 'the app is told to leave');
        assert.strictEqual(p.document.fullscreen, false);
        assert.deepStrictEqual(p.events, [], 'the page was never told it was full screen');
        p.addContainer();
        assert.deepStrictEqual(p.events, [], 'and the container appearing tells it nothing');
        assert.strictEqual(p.observers[0].live, false, 'the observer is let go all the same');
        await p.container.requestFullscreen();
        assert.deepStrictEqual(p.events, ['fullscreenchange'], 'entering afterwards is announced');
        assert.strictEqual(p.document.fullscreen, true);
    }
    {
        // A page that starts off waits for the container as well, and has nothing to say
        // when it appears.
        const p = page({ on: false, container: false });
        assert.strictEqual(p.observers.length, 1, 'a page that starts off waits for the container too');
        assert.deepStrictEqual(p.events, []);
        p.addContainer();
        assert.deepStrictEqual(p.events, [], 'and the container appearing tells it nothing');
        assert.strictEqual(p.observers[0].live, false, 'the observer is let go');
    }

    // The restore case for a page that starts off, as on the first start after an update
    // that gives Zen Mode its bars: the workbench asks for full screen before it listens.
    // The event has to wait for the container, because one raised now reaches nobody and
    // leaves the workbench out of step with the window.
    {
        const p = page({ on: false, container: false });
        await p.container.requestFullscreen();
        assert.deepStrictEqual(p.calls, [['session', true]], 'the app is asked to enter');
        assert.strictEqual(p.document.fullscreen, false, 'the page has not been told yet');
        assert.deepStrictEqual(p.events, [], 'and no event is raised while nobody listens');
        p.addContainer();
        assert.deepStrictEqual(p.events, ['fullscreenchange'], 'told once, when the container appears');
        assert.deepStrictEqual(p.seen, [[true, true]], 'a listener reads full screen while it runs');
        assert.strictEqual(p.document.fullscreen, true);
    }

    // The guard: a second run in the same page installs nothing and announces nothing.
    {
        const p = page({ on: true, times: 2 });
        assert.deepStrictEqual(p.events, ['fullscreenchange'], 'one announcement for two runs');
        const q = page({ on: true, times: 2, container: false });
        assert.strictEqual(q.observers.length, 1, 'one observer for two runs');
    }

    console.log('full screen: all cases passed');
}

main().catch((e) => { console.error(e); process.exit(1); });
