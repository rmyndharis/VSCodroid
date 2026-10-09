/**
 * Self-check for the guard that ends a Left or Right at the edge of a text box,
 * and, in the same script, for a latched Ctrl or Alt over a keyboard that
 * composes (the cases at the end say how that one is driven).
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
 * once the dispatch is over, as a task after the key's own, and a microtask
 * right after the listener that queued it: the browser runs the microtask
 * queue between the listeners of a key it dispatches itself. Every box sits in a
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
 * so an empty table, unless a case needs the chord table, and an empty
 * function stand in; `scripts/test-modifier-release.js` runs the real function.
 */
function extractInterceptor(keyLookup = '{}') {
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

    const substituted = js.split('$keyLookup').join(keyLookup)
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
    const page = { window, document, host: new Node(body, { tagName: 'DIV' }), log: [], timers: [], microtasks: [] };
    // The workbench's keybinding service: a bubble listener on the window,
    // registered long before the interceptor is installed.
    if (keybindings) window.addEventListener('keydown', own('keybinding', (e) => page.log.push(`keybinding ${e.key}`)));
    const setTimeout = (fn) => page.timers.push(fn);
    const queueMicrotask = (fn) => page.microtasks.push(fn);
    vm.runInContext(INTERCEPTOR, vm.createContext({ window, document, navigator: { userAgent }, setTimeout, queueMicrotask }));
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
            for (const fn of page.microtasks.splice(0)) fn();
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


// A latch over a keyboard that composes. Gboard 12.4 types every letter on the
// EditContext path as a composition: the character goes to whichever
// EditContext the host has, with no beforeinput, and the editor's own
// textupdate listener types it. A keyboard that commits instead fires a
// cancelable beforeinput on the host first. The interceptor lends the host an
// empty EditContext while Ctrl or Alt is latched, so the next character becomes
// the chord, and gives the editor's back afterwards, blurring and focusing the
// host while it keeps focus, which restarts input, so that the keyboard reads
// the editor's text again rather than keeping the chord's letter in its copy.
//
// NEGATIVE CONTROL, measured: against the interceptor as it was before the
// swap (main at f66e462f) 9 of these 13 cases fail, each with the letter typed
// into the file, the Backspace sent plain, or no restart after the chord. The
// four that pass hold what main already did: a chord that takes focus
// elsewhere, typing in the box it took focus to, focus leaving the host, and a
// Backspace with nothing latched. With the swap but without the restart
// (1fda5cdf) 10 fail: the 8 that expect a blur and focus after the chord or
// after the latch is cleared, and the two that need focus to have left the
// host before the EditContext is given back, typing in Quick Open and a blur
// and focus in one task. With the restart but the EditContext given back as
// focus leaves (099d0235) those two fail alone, the box left empty as on the
// emulator. With the restart made before the chord instead of after it, the
// 7 cases whose chord comes from the empty EditContext or the Backspace
// listener fail. Without the Backspace listener the Backspace case fails: a
// plain Backspace, the empty EditContext still lent and Ctrl still latched, as
// measured on an API 33 emulator with Gboard 12.4. Without the empty
// EditContext marked as hooked, the blur and focus case fails alone: the focus
// hooks the lent EditContext, its compositionstart spends the latch, and the
// letter composed there goes nowhere, with no chord.
{
    const LATCH_INTERCEPTOR = extractInterceptor('{"/":["Slash",191,0]}');

    function latchPage() {
        const log = [];
        const timers = [];
        const listen = (node) => Object.assign(node, {
            listeners: [],
            addEventListener(type, fn, options) {
                this.listeners.push({ type, fn, capture: captureOf(options) });
            },
            removeEventListener(type, fn, options) {
                const capture = captureOf(options);
                this.listeners = this.listeners.filter((l) => !(l.type === type && l.fn === fn && l.capture === capture));
            },
        });
        const window = listen({});
        const document = listen({});
        let page = null;
        function dispatch(target, event) {
            let stopped = false;
            Object.assign(event, {
                target,
                defaultPrevented: false,
                preventDefault() { if (this.cancelable) this.defaultPrevented = true; },
                stopPropagation() { stopped = true; },
                stopImmediatePropagation() { stopped = true; },
                composedPath: () => [target, document, window],
            });
            const phases = [[window, true], [document, true], [target, true], [target, false]];
            if (event.bubbles) phases.push([document, false], [window, false]);
            for (const [node, capture] of phases) {
                for (const l of node.listeners.slice()) {
                    if (l.type !== event.type || l.capture !== capture) continue;
                    l.fn.call(node, event);
                    if (stopped) return !event.defaultPrevented;
                }
            }
            return !event.defaultPrevented;
        }
        class FakeEditContext {
            constructor(name = 'empty') { this.name = name; listen(this); }
            fire(type, init = {}) {
                for (const l of this.listeners.slice()) if (l.type === type) l.fn({ type, ...init });
            }
        }
        const own = new FakeEditContext('editor');
        own.addEventListener('textupdate', (e) => log.push(`editor typed ${e.text}`));
        // The EditContext the keyboard's input goes to, as Blink keeps it: the
        // focused element's, taken when the element gains focus or is given
        // another while focused, and given up once focus has left the element
        // if the element still has it. One swapped in while focus is leaving
        // is not the one given up, so the one taken out stays active.
        const ime = { active: own };
        let attached = own;
        const host = listen({
            tagName: 'DIV',
            classList: { contains: (name) => name === 'native-edit-context' },
            get editContext() { return attached; },
            set editContext(ec) {
                if (document.activeElement === host && ime.active === attached) ime.active = ec;
                attached = ec;
            },
            blur() {
                log.push('blur host');
                if (document.activeElement !== host) return;
                document.activeElement = null;
                // Blink finishes the composition of the element it takes focus from.
                if (attached.composing) { attached.composing = false; attached.fire('compositionend'); }
                dispatch(host, { type: 'focusout', bubbles: true });
                if (ime.active === attached) ime.active = null;
            },
            focus() {
                log.push('focus host');
                document.activeElement = host;
                ime.active = attached;
                dispatch(host, { type: 'focusin', bubbles: true });
            },
            dispatchEvent(event) { return dispatch(host, event); },
        });
        // The workbench's keybinding service, as far as a chord goes. A chord
        // that opens Quick Open moves focus into its box.
        const quickOpen = { tagName: 'INPUT', value: '', classList: { contains: () => false } };
        host.addEventListener('keydown', (e) => {
            log.push(`chord ${e.key}${e.ctrlKey ? ' ctrl' : ''}${e.altKey ? ' alt' : ''}${e.shiftKey ? ' shift' : ''} ${e.code} ${e.keyCode}`);
            if (page.chordTakesFocus && e.ctrlKey) { host.blur(); document.activeElement = quickOpen; }
        });
        document.activeElement = host;
        window.__vscodroid = {};
        const event = function (type, init) { return { type, ...init }; };
        vm.runInContext(LATCH_INTERCEPTOR, vm.createContext({
            window, document, navigator: { userAgent: AT_149 },
            EditContext: FakeEditContext,
            InputEvent: event,
            KeyboardEvent: event,
            setTimeout: (fn) => { timers.push(fn); },
        }));
        page = {
            log, host, own,
            flush() { for (const fn of timers.splice(0)) fn(); },
            // What ExtraKeyRow pushes, all three flags at once.
            latch(mods = {}) {
                const m = window.__vscodroid;
                m.ctrl = !!mods.ctrl; m.alt = !!mods.alt; m.shift = !!mods.shift;
                page.flush();
            },
            // A composing keyboard: no beforeinput, the text goes to the host's
            // EditContext, the lent one included, opening a composition there.
            compose(text) {
                const ec = host.editContext;
                if (!ec.composing) { ec.composing = true; ec.fire('compositionstart'); }
                ec.fire('textupdate', { text });
            },
            // A committing keyboard: no beforeinput on this path either, and
            // the text goes to the host's EditContext with no composition, as
            // measured on an API 36 emulator with Gboard 18.4.1.
            commit(text) {
                host.editContext.fire('textupdate', { text });
            },
            // What the keyboard types where focus is now: into the active
            // EditContext if one is, otherwise into Quick Open's box.
            typeIme(text) {
                if (ime.active) ime.active.fire('textupdate', { text });
                else if (document.activeElement === quickOpen) quickOpen.value += text;
            },
            quickOpen,
            // A key the keyboard sends as a press, as Gboard sends Backspace
            // when there is nothing before the caret: no code, no modifiers.
            key(key, keyCode) {
                dispatch(host, { type: 'keydown', key, code: '', keyCode, bubbles: true, cancelable: true });
            },
            mods: () => window.__vscodroid,
        };
        return page;
    }

    const latchCase = (name, act, want) => {
        const page = latchPage();
        const got = act(page);
        cases.push([name, JSON.stringify(got), JSON.stringify(want)]);
    };
    const state = (p) => ({ log: p.log, editContext: p.host.editContext.name, ctrl: p.mods().ctrl, alt: p.mods().alt });

    latchCase('Ctrl latched, a composing keyboard\'s p is Ctrl+P and reaches no text',
        (p) => { p.latch({ ctrl: true }); p.compose('p'); return state(p); },
        { log: ['chord p ctrl KeyP 80', 'blur host', 'focus host'], editContext: 'editor', ctrl: false, alt: false });
    latchCase('Ctrl latched over a word still composing: the word ends first, then p is the chord',
        (p) => { p.compose('al'); p.latch({ ctrl: true }); p.compose('p'); return state(p); },
        { log: ['editor typed al', 'blur host', 'focus host', 'chord p ctrl KeyP 80', 'blur host', 'focus host'], editContext: 'editor', ctrl: false, alt: false });
    latchCase('Ctrl+/ resolves through the chord table',
        (p) => { p.latch({ ctrl: true }); p.compose('/'); return state(p); },
        { log: ['chord / ctrl Slash 191', 'blur host', 'focus host'], editContext: 'editor', ctrl: false, alt: false });
    latchCase('Alt latched, a composing keyboard\'s x is Alt+X',
        (p) => { p.latch({ alt: true }); p.compose('x'); return state(p); },
        { log: ['chord x alt KeyX 88', 'blur host', 'focus host'], editContext: 'editor', ctrl: false, alt: false });
    // The keyboard committed the chord's letter into the empty EditContext and
    // keeps it in its own copy of the text: Gboard 12.4 then built the next
    // word from that copy, `ab`, Ctrl, `s` and `c` leaving `ababsc`. So input
    // is restarted, after the chord has run, which the order of the log shows.
    latchCase('a committing keyboard\'s letter is the chord too, once, then input restarts',
        (p) => { p.latch({ ctrl: true }); p.commit('s'); return state(p); },
        { log: ['chord s ctrl KeyS 83', 'blur host', 'focus host'], editContext: 'editor', ctrl: false, alt: false });
    latchCase('a chord that takes focus elsewhere, as Ctrl+P does, restarts nothing',
        (p) => { p.chordTakesFocus = true; p.latch({ ctrl: true }); p.commit('p'); return state(p); },
        { log: ['chord p ctrl KeyP 80', 'blur host'], editContext: 'editor', ctrl: false, alt: false });
    // Given back while focus was still leaving, the editor's EditContext was
    // the one Blink took from the keyboard, and the empty one went on taking
    // what was typed in Quick Open: on an API 33 emulator with Gboard 12.4,
    // `sugg` typed after Ctrl then `p` left the box empty.
    latchCase('what is typed in the box a chord moved focus to reaches that box',
        (p) => { p.chordTakesFocus = true; p.latch({ ctrl: true }); p.commit('p'); p.flush(); p.typeIme('sugg'); return { log: p.log, box: p.quickOpen.value, editContext: p.host.editContext.name }; },
        { log: ['chord p ctrl KeyP 80', 'blur host'], box: 'sugg', editContext: 'editor' });
    latchCase('a blur and focus in one task, as the keyboard guard makes, keeps the EditContext lent',
        (p) => { p.latch({ ctrl: true }); p.host.blur(); p.host.focus(); p.flush(); p.compose('p'); return state(p); },
        { log: ['blur host', 'focus host', 'chord p ctrl KeyP 80', 'blur host', 'focus host'], editContext: 'editor', ctrl: false, alt: false });
    latchCase('clearing the latch gives the editor its EditContext back, restarts input, and the next letter is typed',
        (p) => { p.latch({ ctrl: true }); p.latch({}); p.compose('p'); return state(p); },
        { log: ['blur host', 'focus host', 'editor typed p'], editContext: 'editor', ctrl: false, alt: false });
    latchCase('focus leaving the host gives the editor its EditContext back, and restarts nothing',
        (p) => { p.latch({ ctrl: true }); p.host.blur(); p.flush(); return { log: p.log, editContext: p.host.editContext.name }; },
        { log: ['blur host'], editContext: 'editor' });
    latchCase('a selection-only update waits for the character',
        (p) => { p.latch({ ctrl: true }); p.compose(''); p.compose('p'); return state(p); },
        { log: ['chord p ctrl KeyP 80', 'blur host', 'focus host'], editContext: 'editor', ctrl: false, alt: false });
    // The empty EditContext has nothing before the caret to delete, so the
    // keyboard's Backspace comes as a key press, which the editor would take
    // as a plain Backspace with the latch still on.
    latchCase('Ctrl latched, the keyboard\'s Backspace sent as a key press is Ctrl+Backspace',
        (p) => { p.latch({ ctrl: true }); p.key('Backspace', 8); return state(p); },
        { log: ['chord Backspace ctrl Backspace 8', 'blur host', 'focus host'], editContext: 'editor', ctrl: false, alt: false });
    latchCase('with nothing latched that Backspace reaches the editor as it came',
        (p) => { p.key('Backspace', 8); return state(p); },
        { log: ['chord Backspace  8'], editContext: 'editor', ctrl: false, alt: false });
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
