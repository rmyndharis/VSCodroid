/**
 * Self-check for the guard that ends a Left or Right at the edge of a text box.
 *
 *   node scripts/test-arrow-edge-guard.js
 *
 * The guard is JavaScript inside the Kotlin raw string of
 * `KeyInjector.setupModifierInterceptor()`, handed to `evaluateJavascript`, so
 * nothing compiles it and the Kotlin tests can only read its text. What it
 * decides is arithmetic over the caret, and text matching cannot see that go
 * wrong: swap the two edges and every string it looks for is still there,
 * while the guard cancels an ordinary Left at the end and Right at the start of
 * every text box in the workbench, hardware keyboards included, and leaves the
 * real edges to spatial navigation, which moves focus out of the box. Where it
 * decides is order, which text matching cannot see either: a guard that runs
 * after a container has stopped the key's propagation never runs at all, while
 * the key's default action and spatial navigation still do.
 *
 * This extracts the real script, runs it under `vm` against a small fake DOM,
 * once per user agent, and presses keys in boxes inside it. The fake dispatches
 * the way Chromium has since 89: capture listeners from the window down to the
 * target, at the target its capture listeners and then the rest, then the
 * bubble listeners back up to the window. A node's listeners are read when the
 * event reaches it, so one added to the target on the way down runs, after the
 * target's own, and a `once` listener is removed before it runs. A timer runs
 * once the dispatch is over, as a task after the key's own. Every box sits in a
 * shadow root, so a listener outside it sees the host as the target, and the
 * box is reached only through `composedPath()[0]`.
 *
 * NEGATIVE CONTROL, measured: against the guard as it was before it decided at
 * the target (main at 42ab78ed) 12 of the 47 cases fail, and against the guard
 * as it was before a timer removed its listener (main at 54352514) the two
 * cases about a listener left on a box fail. Each of these changes to the
 * guard fails at least one case: deciding in the window's capture or bubble
 * phase, or in the target's capture phase; `e.target` for the innermost
 * target; no timer, or a microtask or an immediate removal in its place;
 * stopping the key's propagation; no version gate, a gate at 150, Alt never
 * cancelled, or an unreadable user agent taken as 149; Alt only on a collapsed
 * caret, or on any target; no EditContext host, no number box or no email box;
 * the edges swapped; and ignoring `defaultPrevented`, the selection's end or
 * Shift.
 *
 * Extraction is deliberately strict. If the raw string moves or changes shape
 * this fails saying so, rather than quietly checking an empty string.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const KEY_INJECTOR = path.join(
    __dirname, '..', 'android/app/src/main/kotlin/com/vscodroid/keyboard/KeyInjector.kt',
);

/**
 * The body of the raw string in `setupModifierInterceptor()`, with the
 * indentation `trimIndent()` removes taken off. `$keyLookup` and
 * `$RELEASE_MODIFIERS_JS` are its interpolations, and the guard reads neither,
 * so an empty table and an empty function stand in;
 * `scripts/test-modifier-release.js` runs the real function.
 */
function extractInterceptor() {
    const lines = fs.readFileSync(KEY_INJECTOR, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('fun setupModifierInterceptor()'));
    assert.notStrictEqual(
        fn, -1,
        'setupModifierInterceptor() is gone from KeyInjector.kt, so this check has nothing to ' +
        'run. If the script moved, point this at its new home rather than deleting the check.',
    );

    const open = lines.findIndex((l, i) => i > fn && l.trim() === 'val js = """');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close !== -1 && close > open + 1,
        'could not find the raw string in setupModifierInterceptor(); its shape changed');

    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');

    const substituted = js.split('$keyLookup').join('{}')
        .split('$RELEASE_MODIFIERS_JS').join('function releaseModifiers() {}');
    assert.ok(!substituted.includes('$'),
        'the interceptor gained a Kotlin interpolation this check does not know how to fill');
    assert.ok(substituted.includes('var EDGE'),
        'the extracted text has no EDGE table, so the wrong block was extracted or the guard ' +
        'is gone');
    return substituted;
}

const INTERCEPTOR = extractInterceptor();

/** Android WebView user agents on either side of Chromium 149, and one well after it. */
const webView = (version) => 'Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro Build/TQ3A.230901.001; wv) ' +
    `AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/${version} Mobile Safari/537.36`;
const BELOW_149 = webView('148.0.7778.288');
const AT_149 = webView('149.0.7827.238');
const LATER = webView('154.0.8037.92');

const captureOf = (options) => options === true || !!(options && options.capture);

/** Anything listeners can be added to, taking a capture flag or `{ capture, once }`. */
class Node {
    constructor(parent, props = {}) {
        Object.assign(this, { parent, listeners: [] }, props);
    }
    addEventListener(type, fn, options) {
        const capture = captureOf(options);
        if (this.listeners.some((l) => l.type === type && l.fn === fn && l.capture === capture)) return;
        this.listeners.push({ type, fn, capture, once: !!(options && options.once) });
    }
    removeEventListener(type, fn, options) {
        const capture = captureOf(options);
        this.listeners = this.listeners.filter((l) => {
            if (l.type !== type || l.fn !== fn || l.capture !== capture) return true;
            l.removed = true;
            return false;
        });
    }
}

/** A handler of the page's own, named so its cancelling is not taken for the guard's. */
const own = (name, fn) => Object.assign(fn, { owner: name });

/** A page with the interceptor installed, and a shadow host to put boxes in. */
function newPage(userAgent = BELOW_149, { keybindings = false } = {}) {
    const window = new Node(null);
    const document = new Node(window, { activeElement: null });
    const body = new Node(document, { tagName: 'BODY' });
    const page = { window, document, host: new Node(body, { tagName: 'DIV' }), log: [], timers: [] };
    // The workbench's keybinding service: a bubble listener on the window,
    // registered long before the interceptor is installed.
    if (keybindings) window.addEventListener('keydown', own('keybinding', (e) => page.log.push(`keybinding ${e.key}`)));
    const setTimeout = (fn) => page.timers.push(fn);
    vm.runInContext(INTERCEPTOR, vm.createContext({ window, document, navigator: { userAgent }, setTimeout }));
    return page;
}

/**
 * A keydown at `target`, dispatched through every phase, then the timers it set,
 * which a browser runs in a later task. Returns who cancelled it, in order.
 */
function press(page, target, key, mods = {}) {
    const cancelledBy = dispatch(page, target, key, mods);
    for (const fn of page.timers.splice(0)) fn();
    return cancelledBy;
}

function dispatch(page, target, key, mods) {
    const route = [];
    for (let n = target; n; n = n.parent) route.push(n);
    const cancelledBy = [];
    let running = null;
    let stopped = false;
    const event = {
        type: 'keydown',
        key,
        bubbles: true,
        defaultPrevented: false,
        ctrlKey: !!mods.ctrl,
        altKey: !!mods.alt,
        shiftKey: !!mods.shift,
        metaKey: !!mods.meta,
        target: page.host,
        composedPath: () => route.slice(),
        preventDefault() {
            cancelledBy.push(running);
            this.defaultPrevented = true;
        },
        stopPropagation() { stopped = true; },
    };
    function invoke(node, capture) {
        for (const l of node.listeners.slice()) {
            if (l.removed || l.type !== event.type || l.capture !== capture) continue;
            if (l.once) node.removeEventListener(l.type, l.fn, l.capture);
            running = l.fn.owner || 'guard';
            l.fn.call(node, event);
        }
    }
    for (const node of route.slice().reverse()) {
        invoke(node, true);
        if (stopped) return cancelledBy;
    }
    for (const node of route) {
        invoke(node, false);
        if (stopped) return cancelledBy;
    }
    return cancelledBy;
}

/**
 * Whether the guard itself cancels `key` in a box with `props`, in a container
 * inside the shadow host. `handled` gives the box a handler that uses the key
 * first; `stop` gives the box (`'box'`) or its container (`'container'`) a
 * handler that stops the key's propagation without cancelling it.
 */
function cancels(key, props, opts = {}) {
    const page = newPage(opts.ua);
    const container = new Node(page.host, { tagName: 'DIV' });
    const box = new Node(container, props);
    if (opts.handled) box.addEventListener('keydown', own('box', (e) => e.preventDefault()));
    const stop = own(opts.stop, (e) => e.stopPropagation());
    if (opts.stop === 'box') box.addEventListener('keydown', stop);
    if (opts.stop === 'container') container.addEventListener('keydown', stop);
    return press(page, box, key, opts).includes('guard');
}

const box = (value, start, end = start, tagName = 'INPUT') =>
    ({ tagName, type: tagName === 'INPUT' ? 'text' : 'textarea', value, selectionStart: start, selectionEnd: end });
// An input with no selection API reports null, as Blink does for number,
// email and checkbox alike.
const input = (type, value) => ({ tagName: 'INPUT', type, value, selectionStart: null, selectionEnd: null });
// The editor's focused element: a DIV with an EditContext attached.
const editContextHost = { tagName: 'DIV', editContext: {} };

const cases = [
    ['Left at the start', cancels('ArrowLeft', box('abc', 0)), true],
    ['Right at the end', cancels('ArrowRight', box('abc', 3)), true],
    ['Right in an empty box', cancels('ArrowRight', box('', 0)), true],
    ['Right at the end of a textarea', cancels('ArrowRight', box('a\nb', 3, 3, 'TEXTAREA')), true],
    ['Left at the start, from WebView 149', cancels('ArrowLeft', box('abc', 0), { ua: AT_149 }), true],
    ['Left in the middle moves the caret', cancels('ArrowLeft', box('abc', 1)), false],
    ['Right at the start moves the caret', cancels('ArrowRight', box('abc', 0)), false],
    ['Left at the end moves the caret', cancels('ArrowLeft', box('abc', 3)), false],
    ['Left at the start of a second line moves the caret',
        cancels('ArrowLeft', box('a\nb', 2, 2, 'TEXTAREA')), false],
    ['a selection collapses, which moves', cancels('ArrowLeft', box('abc', 0, 3)), false],
    ['Shift+Left at the start: spatial navigation ignores Shift',
        cancels('ArrowLeft', box('abc', 0), { shift: true }), false],
    ['Ctrl+Left at the start: spatial navigation ignores Ctrl',
        cancels('ArrowLeft', box('abc', 0), { ctrl: true }), false],
    ['Meta+Right at the end: spatial navigation ignores Meta',
        cancels('ArrowRight', box('abc', 3), { meta: true }), false],

    // Below Chromium 149 an Alt+Left or Alt+Right has no command on Android,
    // so it reaches spatial navigation wherever the caret is.
    ['below 149, Alt+Left in the middle', cancels('ArrowLeft', box('abc', 1), { alt: true }), true],
    ['below 149, Alt+Right in the middle of a textarea',
        cancels('ArrowRight', box('abc def', 3, 3, 'TEXTAREA'), { alt: true }), true],
    ['below 149, Alt+Right with a selection, which nothing collapses',
        cancels('ArrowRight', box('abc', 1, 2), { alt: true }), true],
    ['below 149, Alt+Left on the editor\'s EditContext host',
        cancels('ArrowLeft', editContextHost, { alt: true }), true],
    ['below 149, Alt+Right in a number box', cancels('ArrowRight', input('number', '14'), { alt: true }), true],
    ['a user agent with no Chrome version counts as below 149',
        cancels('ArrowLeft', box('abc', 1), { alt: true, ua: 'Mozilla/5.0 (Linux; Android 13; wv)' }), true],
    ['Alt+Left on an input with no caret, such as a checkbox',
        cancels('ArrowLeft', input('checkbox', 'on'), { alt: true }), false],

    // From 149 they move to the start and end of the line, which Blink counts
    // as handled even where the caret already is.
    ['from 149, Alt+Left in the middle is the line move',
        cancels('ArrowLeft', box('abc', 1), { alt: true, ua: AT_149 }), false],
    ['from 149, Alt+Right at the end is the line move',
        cancels('ArrowRight', box('abc', 3), { alt: true, ua: AT_149 }), false],
    ['from 149, Alt+Right with a selection, which the line move collapses',
        cancels('ArrowRight', box('abc', 1, 2), { alt: true, ua: AT_149 }), false],
    ['from 149, Alt+Left on the editor\'s EditContext host',
        cancels('ArrowLeft', editContextHost, { alt: true, ua: AT_149 }), false],
    ['from 149, Alt+Left in a number box', cancels('ArrowLeft', input('number', '14'), { alt: true, ua: AT_149 }), false],
    ['well after 149, Alt+Left in the middle', cancels('ArrowLeft', box('abc', 1), { alt: true, ua: LATER }), false],

    // A number or email box takes text and reports no caret.
    ['Left in a number box', cancels('ArrowLeft', input('number', '14')), true],
    ['Right in a number box', cancels('ArrowRight', input('number', '14')), true],
    ['Left in an email box', cancels('ArrowLeft', input('email', 'a@b.c')), true],
    ['Shift+Right in a number box: spatial navigation ignores Shift',
        cancels('ArrowRight', input('number', '14'), { shift: true }), false],
    ['Left on the editor\'s EditContext host is the editor\'s own key',
        cancels('ArrowLeft', editContextHost), false],

    // Decided on the box, after its own listeners and before its container's.
    ['an arrow a handler already used', cancels('ArrowLeft', box('abc', 0), { handled: true }), false],
    ['Right at the end of a box whose container stops the key, as the Problems filter does',
        cancels('ArrowRight', box('err', 3), { stop: 'container' }), true],
    ['Left at the start of that box', cancels('ArrowLeft', box('err', 0), { stop: 'container' }), true],
    ['Right in the middle of that box moves the caret',
        cancels('ArrowRight', box('err', 1), { stop: 'container' }), false],
    ['Left at the start of a box that stops the key itself, as the chat model picker filter does',
        cancels('ArrowLeft', box('gpt', 0), { stop: 'box' }), true],

    ['Up is never pressed for real', cancels('ArrowUp', box('abc', 0)), false],
    ['Down is never pressed for real', cancels('ArrowDown', box('abc', 3)), false],
    ['Tab is not an arrow', cancels('Tab', box('abc', 3)), false],
    ['Home is not an arrow', cancels('Home', box('abc', 0)), false],
    ['a target that takes no text', cancels('ArrowLeft', { tagName: 'DIV' }), false],
];

// Cancelling stops no binding: the keybinding service on the window still
// gets the key, after the guard.
{
    const page = newPage(BELOW_149, { keybindings: true });
    const field = new Node(page.host, box('abc', 3));
    const cancelledBy = press(page, field, 'ArrowRight');
    cases.push(['the keybinding service still gets a key the guard cancelled',
        cancelledBy.includes('guard') && page.log.includes('keybinding ArrowRight'), true]);
}

// One listener per press, gone once the press is over.
{
    const page = newPage();
    const field = new Node(page.host, box('abc', 1));
    press(page, field, 'ArrowLeft');
    press(page, field, 'ArrowRight', { alt: true });
    cases.push(['the box keeps no listener after its presses', field.listeners.length === 0, true]);
}

// The terminal's textarea takes every arrow in its own capture listener, which
// cancels it and stops its propagation, so the guard's listener there never
// runs. It has to go when the key is over all the same, or each trackpad step
// in a terminal leaves one more on the textarea.
{
    const page = newPage();
    const textarea = new Node(page.host, box('', 0, 0, 'TEXTAREA'));
    textarea.addEventListener('keydown', own('terminal', (e) => {
        e.preventDefault();
        e.stopPropagation();
    }), true);
    for (const key of ['ArrowRight', 'ArrowRight', 'ArrowLeft', 'ArrowRight', 'ArrowLeft']) press(page, textarea, key);
    cases.push(['a box that stops every arrow in its capture listener, as the terminal does, keeps only its own',
        textarea.listeners.length, 1]);
}

// A press stopped on its way down never reaches the box, so its listener does
// not run then. It is gone once the key is over, and must not act on a later
// key at the box either.
{
    const page = newPage();
    const container = new Node(page.host, { tagName: 'DIV' });
    const field = new Node(container, box('abc', 3));
    const stopOnTheWayDown = own('container', (e) => e.stopPropagation());
    container.addEventListener('keydown', stopOnTheWayDown, true);
    press(page, field, 'ArrowRight');
    container.removeEventListener('keydown', stopOnTheWayDown, true);
    cases.push(['a press stopped on its way down leaves no listener on the box', field.listeners.length, 0]);
    cases.push(['a key typed after a press stopped on its way down is not cancelled',
        press(page, field, 'a').includes('guard'), false]);
    cases.push(['and the next Right at the end is cancelled once',
        press(page, field, 'ArrowRight').filter((by) => by === 'guard').length, 1]);
}

let failed = 0;
for (const [name, got, want] of cases) {
    if (got === want) {
        console.log(`ok   ${name}`);
    } else {
        failed += 1;
        console.error(`FAIL ${name}: got ${JSON.stringify(got)}, want ${JSON.stringify(want)}`);
    }
}
console.log(`\n${cases.length - failed}/${cases.length} passed`);
process.exit(failed ? 1 : 0);
