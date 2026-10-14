/**
 * Self-check for the UI scale: the script that applies it as the workbench page
 * is parsed, the relay branches that read and set it, and the command that
 * offers it.
 *
 *   node scripts/test-ui-scale.js
 *
 * The script is JavaScript inside the Kotlin raw string of
 * `MainActivity.kt`'s `uiScaleScript()`, which `addUiScaleScript` registers with
 * `WebViewCompat.addDocumentStartJavaScript`, so nothing compiles it and the
 * Kotlin suite can only see that it is registered. What decides whether it works
 * is order and state: the page's viewport element rewritten as the parser inserts
 * it, before the first layout; the three scale keys set and every other key kept;
 * only the sizes that leave the page at least 320 CSS px wide applied; and the
 * size put back to 100% once the page has drawn, if the WebView did not lay it
 * out narrower and draw it larger.
 *
 * This extracts the real script and the real relay, runs them under `vm` against
 * a fake page whose layout follows the measured WebView behaviour (a device-width
 * page laid out at the view's width divided by its initial scale, drawn at that
 * scale), or a broken one, and drives the real bundled extension's command
 * through the relay over a BroadcastChannel. Only Android, the page's layout and
 * the vscode API are faked.
 *
 * NEGATIVE CONTROL, measured: each of these changes fails a case. In the script:
 * applying the size at DOMContentLoaded, no gate or no top-frame test, no cap, a
 * cap by screen width alone, a stored size applied uncapped, no fall back, a fall
 * back that keeps the stored size, judging by the scale alone or at once, writing
 * only initial-scale, overwriting the element, no disconnect, and no offered
 * check in `set`. In the relay: answering before the page has judged, and no
 * branch for a missing hook. In the command: no narrow-screen check, every answer
 * reported as set, no current marker, and the size in force set again.
 *
 * Extraction is deliberately strict. If a raw string moves or changes shape this
 * fails saying so, rather than quietly checking an empty string.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const Module = require('module');
const vm = require('vm');
const { newestExtensionDir } = require('./lib/bundled-extension');

const MAIN_ACTIVITY = path.join(
    __dirname, '..', 'android/app/src/main/kotlin/com/vscodroid/MainActivity.kt',
);

/** How a literal dollar is written inside a Kotlin raw string. */
const DOLLAR_ESCAPE = "${'$'}";

/**
 * The raw string after [declaration], with the indentation `trimIndent()`
 * removes taken off, which is the text the WebView is given.
 */
function extract(declaration, mustMention) {
    const lines = fs.readFileSync(MAIN_ACTIVITY, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes(declaration));
    assert.notStrictEqual(
        fn, -1,
        `${declaration} is gone from MainActivity.kt, so this check has nothing to run. ` +
        'If it moved, point this at its new home rather than deleting the check.',
    );
    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close !== -1 && close > open + 1,
        `could not find the raw string after ${declaration}; its shape changed`);
    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
    // A bare $ is interpolated by Kotlin before the WebView sees the string.
    assert.ok(!js.split(DOLLAR_ESCAPE).join('').includes('$'),
        `the raw string after ${declaration} has a bare $, which Kotlin interpolates`);
    assert.ok(js.includes(mustMention),
        `the text after ${declaration} does not mention ${mustMention}, so the wrong block was extracted`);
    return js.split(DOLLAR_ESCAPE).join('$');
}

const SCRIPT = extract('internal fun uiScaleScript()', "'vscodroid.uiScale'");
const RELAY = extract('private fun injectBridgeRelay()', 'setUiScale');

/** The workbench page's own viewport element in Code - OSS 1.139.1 (workbench.html). */
const WORKBENCH_VIEWPORT =
    'width=device-width, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, user-scalable=no';
const KEY = 'vscodroid.uiScale';

/** The keys of a viewport `content`, as a map. */
function keys(content) {
    const map = {};
    for (const part of content.split(',')) {
        const [k, v] = part.split('=').map((x) => (x || '').trim());
        if (k) map[k] = v;
    }
    return map;
}

/** Plain data out of the page's realm, which deepStrictEqual compares by prototype. */
const plain = (value) => JSON.parse(JSON.stringify(value));

/**
 * One workbench document, as the WebView loads it.
 *
 * `engine` is how the WebView treats the element: 'webview' is the measured
 * behaviour, 'ignores' draws the page at 100% whatever it says, and 'wide' draws
 * it at the scale but lays it out at the view's full width, as a WebView with
 * wide viewport semantics would, so its right edge is off the screen.
 */
function newPage(options) {
    const opts = {
        screen: [411, 891], view: 411, engine: 'webview', top: true,
        host: '127.0.0.1', pathname: '/', meta: WORKBENCH_VIEWPORT, storage: new Map(),
        ...options,
    };
    const frames = [];
    const observers = [];
    const listeners = {};
    const warnings = [];
    const writes = [];
    const viewport = opts.meta === null ? null : { content: opts.meta, parsed: false };
    const element = viewport && {
        getAttribute: (name) => (name === 'content' ? viewport.content : null),
        setAttribute: (name, value) => {
            assert.strictEqual(name, 'content', `the script wrote ${name} on the viewport element`);
            viewport.content = String(value);
            writes.push(viewport.content);
        },
    };
    // Getters rather than Object.assign, which would copy their values once.
    const page = {
        engine: opts.engine,
        storage: opts.storage,
        warnings,
        writes,
        observers,
        get content() { return viewport && viewport.content; },
        get watching() { return observers.some((o) => o.connected); },
        get hook() { return page.win.__vscodroidUiScale; },
        get queuedFrames() { return frames.length; },
        state: () => plain(page.win.__vscodroidUiScale.state()),
        /** The parser inserting the nodes before the element, with mutations to report. */
        parseHead() { for (const o of observers.slice()) if (o.connected) o.fn([], o); },
        /** The parser inserting the element, and the observers told of it. */
        parse() {
            if (viewport) viewport.parsed = true;
            for (const o of observers.slice()) if (o.connected) o.fn([], o);
        },
        loaded() { for (const fn of listeners.DOMContentLoaded || []) fn(); },
        /** One frame: the animation frame callbacks queued before it, and no others. */
        frame() { for (const fn of frames.splice(0)) fn(); },
        layout,
    };

    // What the WebView makes of the element as it stands.
    function layout() {
        const parsed = viewport && viewport.parsed ? keys(viewport.content) : {};
        const asked = Number(parsed['initial-scale']) || 1;
        for (const k of ['minimum-scale', 'maximum-scale']) {
            if (parsed[k] !== undefined && Number(parsed[k]) !== asked) {
                // A page allowed to zoom away from its initial scale is not one this
                // check models, and the script never writes one.
                throw new Error(`the viewport element was left with ${k}=${parsed[k]} beside ` +
                    `initial-scale=${asked}: ${viewport.content}`);
            }
        }
        const scale = page.engine === 'ignores' ? 1 : asked;
        return {
            scale,
            visualWidth: opts.view / scale,
            layoutWidth: page.engine === 'webview' ? Math.floor(opts.view / scale) : opts.view,
        };
    }

    const win = {
        location: { hostname: opts.host, pathname: opts.pathname },
        screen: { width: opts.screen[0], height: opts.screen[1] },
        localStorage: {
            getItem: (k) => (opts.storage.has(k) ? opts.storage.get(k) : null),
            setItem: (k, v) => { opts.storage.set(k, String(v)); },
            removeItem: (k) => { opts.storage.delete(k); },
        },
        document: {
            querySelector: (selector) => {
                assert.strictEqual(selector, 'meta[name="viewport"]',
                    `the script looked for ${selector}, which this page does not model`);
                return viewport && viewport.parsed ? element : null;
            },
            get documentElement() {
                return { get clientWidth() { return layout().layoutWidth; } };
            },
            addEventListener: (type, fn) => { (listeners[type] = listeners[type] || []).push(fn); },
        },
        get visualViewport() {
            const l = layout();
            return { scale: l.scale, width: l.visualWidth };
        },
        requestAnimationFrame: (fn) => { frames.push(fn); },
        MutationObserver: class {
            constructor(fn) { this.fn = fn; this.connected = false; observers.push(this); }
            observe() { this.connected = true; }
            disconnect() { this.connected = false; }
        },
        console: { warn: (m) => warnings.push(m) },
    };
    win.window = win;
    win.top = opts.top ? win : {};
    page.win = win;
    vm.createContext(win);
    vm.runInContext(SCRIPT, win);
    return page;
}

const scaled = (s) => ({
    'initial-scale': String(s), 'minimum-scale': String(s), 'maximum-scale': String(s),
});

// ---- the document-start script on its own -----------------------------------

// Every frame of every origin runs the script, since the rule it is registered
// under cannot name the server's port, so everything but the workbench page has
// to be left exactly as it was, with a size stored and all.
for (const [label, where] of [
    ['an iframe', { top: false }],
    ['another host', { host: 'example.com' }],
    ['a dev server preview opened at its own root', { host: '10.0.2.2' }],
    ['the web worker extension host frame', { pathname: '/stable-abc/static/out/vs/workbench/services/extensions/worker/webWorkerExtensionHostIframe.html' }],
    ['a workspace file loaded as a page', { pathname: '/vscode-remote-resource' }],
]) {
    const page = newPage({ ...where, storage: new Map([[KEY, '1.25']]) });
    page.parse();
    assert.ok(
        page.hook === undefined && page.observers.length === 0 &&
            page.content === WORKBENCH_VIEWPORT && page.queuedFrames === 0,
        `${label} was touched by the UI scale script, which only the workbench page may be: ` +
        JSON.stringify({ hook: typeof page.hook, observers: page.observers.length, content: page.content }),
    );
}

// Nothing stored, the default: the page is not touched and not watched, and the
// hook answers 100% with the sizes this phone is offered.
{
    const page = newPage();
    page.parse();
    page.loaded();
    assert.ok(page.hook, 'the workbench page has no hook, so the command has nothing to talk to');
    assert.deepStrictEqual(page.state(), { scale: 1, choices: [1, 1.1, 1.25] },
        'a 411 dp phone with nothing chosen must be at 100% and offered 100, 110 and 125%');
    assert.ok(page.observers.length === 0 && page.writes.length === 0 && page.queuedFrames === 0,
        'with no size chosen the page must be left exactly as the workbench wrote it: ' +
        JSON.stringify(page.writes));
}

// A size chosen earlier is applied as the parser inserts the element, which is
// before the first layout, so the page is never laid out at 100% first.
{
    const page = newPage({ storage: new Map([[KEY, '1.25']]) });
    page.parseHead();
    assert.ok(page.watching && page.writes.length === 0,
        'the script stopped watching, or wrote, before the viewport element was there');
    page.parse();
    const now = keys(page.content);
    assert.deepStrictEqual(
        { ...now, ...scaled(1.25) }, now,
        'the element was not given initial, minimum and maximum scale 1.25 as it was parsed: ' +
        page.content,
    );
    assert.ok(now.width === 'device-width' && now['user-scalable'] === 'no',
        'the rewrite dropped the page\'s own keys: ' + page.content);
    assert.ok(!page.watching, 'the script went on watching every mutation of the page after the rewrite');
    assert.deepStrictEqual(plain(page.layout()), { scale: 1.25, visualWidth: 328.8, layoutWidth: 328 },
        'the page is not laid out at 1.25 from its first layout');
    page.frame();
    page.frame();
    page.loaded();
    assert.ok(
        page.writes.length === 1 && page.storage.get(KEY) === '1.25' && page.state().scale === 1.25 &&
            page.warnings.length === 0,
        'a size that took effect was undone: ' + JSON.stringify({ writes: page.writes, warnings: page.warnings }),
    );
}

// Every other key of the element is kept, whatever order it is in, viewport-fit
// included: `injectSafeAreaCSS` adds it once per page and does not add it again.
{
    const page = newPage({
        meta: 'width=device-width, initial-scale=1.0, viewport-fit=cover, user-scalable=no',
        storage: new Map([[KEY, '1.1']]),
    });
    page.parse();
    assert.deepStrictEqual(
        keys(page.content),
        { width: 'device-width', 'viewport-fit': 'cover', 'user-scalable': 'no', ...scaled(1.1) },
        'the rewrite lost a key the page had, or missed a scale key it did not: ' + page.content,
    );
}

// Only sizes that leave the page at least 320 CSS px wide, judged by the
// screen's narrower side, so turning the phone, which does not reload the page,
// cannot make it narrower than that. A size chosen on a wider screen is kept and
// the largest this one allows is applied.
for (const [screen, choices] of [
    [[411, 891], [1, 1.1, 1.25]],
    [[891, 411], [1, 1.1, 1.25]],
    [[360, 780], [1, 1.1]],
    [[352, 760], [1, 1.1]],
    [[351, 760], [1]],
    [[800, 1280], [1, 1.1, 1.25, 1.5]],
]) {
    const page = newPage({ screen, view: screen[0], storage: new Map([[KEY, '1.5']]) });
    page.parse();
    const largest = choices[choices.length - 1];
    assert.deepStrictEqual(page.state(), { scale: largest, choices },
        `a ${screen.join('x')} screen must be offered ${choices} and show a stored 150% at the ` +
        `largest of those: ${JSON.stringify(page.state())}`);
    assert.strictEqual(keys(page.content)['initial-scale'], largest === 1 ? '1.0' : String(largest),
        `a ${screen.join('x')} screen was drawn at a size it is not offered: ${page.content}`);
    assert.strictEqual(page.storage.get(KEY), '1.5', 'the size chosen was overwritten by the cap');
}

// A size that does not take effect is put back to 100% and forgotten, once the
// page has drawn: whether the WebView ignored the element, or drew the page
// larger while still laying it out at the view's full width.
for (const engine of ['ignores', 'wide']) {
    const page = newPage({ engine, storage: new Map([[KEY, '1.25']]) });
    page.parse();
    page.frame();
    assert.ok(keys(page.content)['initial-scale'] === '1.25' && page.storage.get(KEY) === '1.25',
        `the size was judged (${engine}) before the page had been laid out and drawn at it`);
    page.frame();
    assert.deepStrictEqual(
        { ...keys(page.content), width: undefined, 'user-scalable': undefined },
        { width: undefined, 'user-scalable': undefined, ...scaled(1) },
        `a size that did not take effect (${engine}) was left in place: ${page.content}`,
    );
    assert.ok(!page.storage.has(KEY) && page.state().scale === 1 && page.warnings.length === 1,
        `a size that did not take effect (${engine}) is tried again on every load: ` +
        JSON.stringify({ stored: page.storage.get(KEY), state: page.state(), warnings: page.warnings }));
}

// A page without the element is watched only while it is parsed, and says 100%.
{
    const page = newPage({ meta: null, storage: new Map([[KEY, '1.25']]) });
    page.parse();
    assert.ok(page.watching, 'the script stopped waiting for the element before the page was parsed');
    page.loaded();
    assert.ok(!page.watching && page.state().scale === 1,
        'a page without a viewport element is watched for the rest of its life, or claims a size');
}

// Anything else stored is no size at all.
for (const stored of ['abc', '0', '-1', '']) {
    const page = newPage({ storage: new Map([[KEY, stored]]) });
    page.parse();
    assert.ok(page.state().scale === 1 && page.writes.length === 0,
        `a stored ${JSON.stringify(stored)} changed the page`);
}

// ---- the relay and the command -----------------------------------------------

const vscodeCalls = { info: [], warning: [], error: [], picks: [] };
let pick = () => undefined;
const commands = new Map();
const vscodeStub = {
    commands: {
        registerCommand: (id, fn) => { commands.set(id, fn); return { dispose() {} }; },
        executeCommand: async () => {},
    },
    window: {
        showInformationMessage: async (m) => { vscodeCalls.info.push(m); },
        showWarningMessage: async (m) => { vscodeCalls.warning.push(m); },
        showErrorMessage: async (m) => { vscodeCalls.error.push(m); },
        showQuickPick: async (items, options) => {
            vscodeCalls.picks.push({ items, options });
            return pick(items);
        },
        createStatusBarItem: () => ({ show() {}, hide() {}, dispose() {} }),
    },
    workspace: {
        workspaceFolders: [],
        onDidChangeWorkspaceFolders: () => ({ dispose() {} }),
        onDidCloseTextDocument: () => ({ dispose() {} }),
    },
    env: { clipboard: { writeText: async () => {} } },
    Uri: { joinPath: (uri) => uri },
};
const realLoad = Module._load;
Module._load = function (request) {
    if (request === 'vscode') return vscodeStub;
    return realLoad.apply(this, arguments);
};

const tick = () => new Promise((r) => setTimeout(r, 0));

/** Waits for the channel and the awaits behind it, for at most fifty turns. */
async function until(condition, what) {
    for (let i = 0; i < 50; i += 1) {
        if (condition()) return;
        await tick();
    }
    assert.fail(what);
}

async function main() {
    const storage = new Map();
    const page = newPage({ storage });
    page.parse();
    page.loaded();
    // The relay as Kotlin injects it into that same page once it has loaded.
    Object.assign(page.win, {
        AndroidBridge: {},
        BroadcastChannel,
        __vscodroid: { authToken: 'test-token' },
    });
    vm.runInContext(RELAY, page.win);

    require(path.join(newestExtensionDir('vscodroid.vscodroid-saf-bridge-'), 'extension.js')).activate({
        subscriptions: [],
        extensionUri: { path: '/ext' },
        globalState: { get: (k, d) => d, update: async () => {} },
    });
    const uiScale = commands.get('vscodroid.uiScale');
    assert.ok(uiScale, 'the bundled extension no longer registers vscodroid.uiScale');

    /** Runs the command with `choose` picking from the quick pick, drawing frames as asked. */
    async function run(choose) {
        for (const list of Object.values(vscodeCalls)) list.length = 0;
        pick = choose;
        const writes = page.writes.length;
        let done = false;
        const running = uiScale().then(() => { done = true; });
        // A size being set is answered once the page has drawn at it.
        await until(() => done || page.queuedFrames > 0, 'the command neither finished nor set a size');
        while (!done) {
            page.frame();
            await until(() => done || page.queuedFrames > 0, 'the command waited for nothing');
        }
        await running;
        return { ...plain(vscodeCalls), rewrote: page.writes.length > writes };
    }
    const labels = (r) => r.picks.length === 1 && r.picks[0].items.map((i) => [i.label, i.description || '']);

    // The quick pick offers what the page says this screen allows, marked with
    // the size in force, and the size picked is set, kept and said.
    let r = await run((items) => items.find((i) => i.label === '125%'));
    assert.deepStrictEqual(labels(r), [['100%', 'current'], ['110%', ''], ['125%', '']],
        'the quick pick did not offer the sizes the page allows on a 411 dp phone: ' + JSON.stringify(r.picks));
    assert.ok(
        r.info.length === 1 && r.info[0] === 'UI scale set to 125%.' && r.warning.length + r.error.length === 0,
        'setting 125% did not say so: ' + JSON.stringify(r),
    );
    assert.ok(storage.get(KEY) === '1.25' && keys(page.content)['initial-scale'] === '1.25',
        'the size picked was not applied to the page and kept: ' + JSON.stringify([storage.get(KEY), page.content]));

    // Kept across a reload or a restart: the next document is drawn at it from
    // its first layout.
    {
        const next = newPage({ storage });
        next.parse();
        assert.strictEqual(next.layout().scale, 1.25, 'the size chosen did not survive a reload');
    }

    // Picking the size in force, or nothing, changes nothing and says nothing.
    for (const [label, choose] of [
        ['the current size', (items) => items.find((i) => i.label === '125%')],
        ['nothing', () => undefined],
    ]) {
        r = await run(choose);
        assert.ok(!r.rewrote && r.info.length + r.warning.length + r.error.length === 0,
            `picking ${label} changed the page or said something: ` + JSON.stringify(r));
    }
    assert.deepStrictEqual(labels(r), [['100%', ''], ['110%', ''], ['125%', 'current']],
        'the quick pick does not mark the size in force');

    // 100% is the page as the workbench wrote it, and nothing stays stored.
    r = await run((items) => items.find((i) => i.label === '100%'));
    assert.ok(r.info[0] === 'UI scale set to 100%.' && !storage.has(KEY) &&
            keys(page.content)['initial-scale'] === '1',
        'going back to 100% did not take: ' + JSON.stringify([r, storage.get(KEY), page.content]));

    // A size the WebView does not apply is put back and the user is told, rather
    // than told it was set.
    page.engine = 'ignores';
    r = await run((items) => items.find((i) => i.label === '110%'));
    assert.ok(
        r.info.length === 0 && r.warning.length === 1 && /110% did not take effect/.test(r.warning[0]) &&
            !storage.has(KEY) && keys(page.content)['initial-scale'] === '1',
        'a size that did not take effect was reported as set, or left in place: ' +
        JSON.stringify([r, storage.get(KEY), page.content]),
    );
    page.engine = 'webview';

    // A screen with no room for a larger size says so instead of offering 100% alone.
    page.win.screen = { width: 340, height: 720 };
    r = await run(() => assert.fail('a screen with nothing to offer was shown a quick pick'));
    assert.ok(r.info.length === 1 && /too narrow/.test(r.info[0]) && r.picks.length === 0,
        'a screen too narrow for a larger size was not told so: ' + JSON.stringify(r));
    page.win.screen = { width: 411, height: 891 };

    // Any script on the origin can post here. A size this screen is not offered
    // is refused by the page, which answers with the size in force.
    const raw = new BroadcastChannel('vscodroid-bridge');
    const answers = [];
    raw.onmessage = (e) => { if (e.data && e.data.id === 'raw') answers.push(e.data); };
    raw.postMessage({ cmd: 'setUiScale', id: 'raw', scale: 1.5 });
    await until(() => answers.length > 0, 'a size this screen is not offered was not answered');
    assert.ok(answers[0].ok === true && answers[0].data === 1 && !storage.has(KEY) &&
            keys(page.content)['initial-scale'] === '1',
        'a size this screen is not offered was applied: ' + JSON.stringify([answers[0], page.content]));
    raw.close();

    // A WebView that cannot run document-start scripts leaves no hook, and the
    // command says what to do about it.
    const hook = page.win.__vscodroidUiScale;
    // Cleared rather than deleted: a property the context defined survives a delete
    // from outside it.
    page.win.__vscodroidUiScale = undefined;
    r = await run(() => assert.fail('the quick pick opened with no hook to answer it'));
    assert.ok(r.error.length === 1 && /Android System WebView/.test(r.error[0]) && r.picks.length === 0,
        'with no hook in the page the command did not say why: ' + JSON.stringify(r));
    page.win.__vscodroidUiScale = hook;

    fs.writeSync(1,
        'ok -- the UI scale is applied to the workbench page as its viewport element is parsed, ' +
        'with every other key kept and nothing else touched; only sizes that leave the page 320 CSS ' +
        'px wide are offered or applied; a size that does not take effect goes back to 100% once the ' +
        'page has drawn; and the command offers, sets, keeps and reports it through the relay\n');
}

// Exit explicitly: the relay's BroadcastChannel keeps the event loop alive.
main().then(
    () => process.exit(0),
    (e) => { fs.writeSync(2, `${e.stack || e.message}\n`); process.exit(1); },
);
