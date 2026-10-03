/**
 * Self-check for the keyup the page is sent for each modifier a key row chord
 * carried.
 *
 *   node scripts/test-modifier-release.js
 *
 * The row's Ctrl, Alt and Shift are latches, so the page sees a modifier go
 * down with a chord and, unless something says otherwise, never come up. The
 * workbench tells its toolbars about a modifier only when one goes down or
 * comes up, so after Alt+Left from the row the editor's split button went on
 * showing Split Editor Down, and running it on a tap. A real press releases its
 * modifiers with real key events (`navigationKeyEvents`, which the Kotlin suite
 * and `TextEntryInstrumentedTest` check). The two scripts that send a chord as
 * DOM events, the announce script in `KeyInjector.announceKeystroke` and the
 * modifier interceptor's `beforeinput` listener, call `releaseModifiers`. The
 * scripts and the function are JavaScript in Kotlin raw strings, which nothing
 * compiles and the Kotlin tests can only match as text.
 *
 * This extracts the scripts, fills in their interpolations, runs them under
 * `vm` against a fake document, and feeds every event they dispatch to a copy
 * of the workbench's `ModifierKeyEmitter`, transcribed from the shipped
 * workbench.js (Code - OSS 1.139.1, minified as `Vy`; upstream it is in
 * `src/vs/base/browser/dom.ts`). What it checks is what the workbench
 * concludes: that no modifier is held once a chord is over, and that nothing
 * reads as the lone Alt press and release that focuses the menu bar.
 *
 * NEGATIVE CONTROL, measured: against KeyInjector.kt at 54352514, which sent
 * no release, 16 of the 26 cases fail. Each of these changes to the release
 * fails at least one case: not calling it from either script, or from either
 * of the interceptor's two chords; releasing with the flag still set; sending
 * the keyup at what has focus rather than at the chord's own target; and
 * releasing in another order.
 *
 * Extraction is strict: if a raw string moves or changes shape this fails
 * saying so, rather than quietly running an empty script.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SOURCE = fs.readFileSync(path.join(
    __dirname, '..', 'android/app/src/main/kotlin/com/vscodroid/keyboard/KeyInjector.kt',
), 'utf8').split('\n');

/** The lines of the raw string that opens at `open`, dedented as `trimIndent()` would. */
function rawString(open, what) {
    const close = SOURCE.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close > open + 1, `could not find the raw string of ${what}; its shape changed`);
    const body = SOURCE.slice(open + 1, close);
    const indent = Math.min(...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length));
    return body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
}

/** The raw string assigned to `val js` inside `declaration`. */
function scriptOf(declaration) {
    const fn = SOURCE.findIndex((l) => l.includes(declaration));
    assert.notStrictEqual(fn, -1,
        `${declaration} is gone from KeyInjector.kt, so this check has nothing to run. If the ` +
        'script moved, point this at its new home rather than deleting the check.');
    return rawString(SOURCE.findIndex((l, i) => i > fn && l.trim() === 'val js = """'), declaration);
}

const RELEASE_OPEN = SOURCE.findIndex((l) => l.startsWith('private val RELEASE_MODIFIERS_JS = """'));
const RELEASE = RELEASE_OPEN === -1 ? null : rawString(RELEASE_OPEN, 'RELEASE_MODIFIERS_JS');

/** `js` with `$RELEASE_MODIFIERS_JS` and the other `fills` put in, refusing any it does not know. */
function fill(js, fills) {
    if (js.includes('$RELEASE_MODIFIERS_JS')) {
        assert.ok(RELEASE, 'a script interpolates RELEASE_MODIFIERS_JS, which is no longer defined');
        js = js.split('$RELEASE_MODIFIERS_JS').join(RELEASE);
    }
    for (const [name, value] of Object.entries(fills)) js = js.split(name).join(value);
    assert.ok(!js.includes('$'), 'a script gained a Kotlin interpolation this check does not know how to fill');
    return js;
}

const ANNOUNCE = scriptOf('private fun announceKeystroke(');
const INTERCEPTOR = fill(scriptOf('fun setupModifierInterceptor()'), { '$keyLookup': '{}' });

/**
 * The workbench's ModifierKeyEmitter, a capture listener on the window for
 * keydown and keyup. It fires, which is the only time a toolbar reads the
 * modifiers again, for a modifier newly down or one released, and for nothing
 * else. `fired` keeps what each firing reported.
 */
function modifierKeyEmitter() {
    const status = { altKey: false, ctrlKey: false, metaKey: false, shiftKey: false };
    const fired = [];
    const take = (e) => {
        status.altKey = e.altKey; status.ctrlKey = e.ctrlKey; status.metaKey = e.metaKey; status.shiftKey = e.shiftKey;
    };
    return {
        status,
        fired,
        keydown(e) {
            if (e.defaultPrevented || (e.keyCode === 18 && e.repeat)) return;
            if (e.altKey && !status.altKey) status.lastKeyPressed = 'alt';
            else if (e.ctrlKey && !status.ctrlKey) status.lastKeyPressed = 'ctrl';
            else if (e.metaKey && !status.metaKey) status.lastKeyPressed = 'meta';
            else if (e.shiftKey && !status.shiftKey) status.lastKeyPressed = 'shift';
            else if (e.keyCode !== 18) status.lastKeyPressed = undefined;
            else return;
            take(e);
            if (status.lastKeyPressed) fired.push({ ...status });
        },
        keyup(e) {
            if (e.defaultPrevented) return;
            if (!e.altKey && status.altKey) status.lastKeyReleased = 'alt';
            else if (!e.ctrlKey && status.ctrlKey) status.lastKeyReleased = 'ctrl';
            else if (!e.metaKey && status.metaKey) status.lastKeyReleased = 'meta';
            else if (!e.shiftKey && status.shiftKey) status.lastKeyReleased = 'shift';
            else status.lastKeyReleased = undefined;
            if (status.lastKeyPressed !== status.lastKeyReleased) status.lastKeyPressed = undefined;
            take(e);
            if (status.lastKeyReleased) fired.push({ ...status });
        },
    };
}

/** Anything listeners can be added to. A node that is not `connected` is out of the document. */
class Node {
    constructor(name) {
        Object.assign(this, { name, tagName: 'DIV', connected: true, listeners: [] });
    }
    addEventListener(type, fn, capture) {
        this.listeners.push({ type, fn, capture: capture === true || !!(capture && capture.capture) });
    }
    removeEventListener(type, fn) {
        this.listeners = this.listeners.filter((l) => l.type !== type || l.fn !== fn);
    }
}

/**
 * A page holding the workbench's emitter on its window and `focused` as the
 * active element. Every event a script dispatches is logged with the node it
 * was sent at; one sent at a node out of the document reaches that node alone.
 */
function newPage() {
    const window = new Node('window');
    const document = new Node('document');
    document.body = new Node('body');
    document.body.tagName = 'BODY';
    const page = { window, document, log: [], emitter: modifierKeyEmitter() };
    window.addEventListener('keydown', (e) => page.emitter.keydown(e), true);
    window.addEventListener('keyup', (e) => page.emitter.keyup(e), true);
    page.focused = document.activeElement = new Node('editor');
    page.window.__vscodroid = {};
    class KeyboardEvent {
        constructor(type, init) {
            Object.assign(this, { repeat: false, defaultPrevented: false }, init, { type });
        }
        preventDefault() { if (this.cancelable) this.defaultPrevented = true; }
        stopPropagation() {}
        stopImmediatePropagation() {}
        composedPath() { return [this.target]; }
    }
    const dispatchEvent = function(event) {
        event.target = this;
        page.log.push({ at: this.name, ...event });
        const run = (node, capture) => node.listeners
            .filter((l) => l.type === event.type && l.capture === capture)
            .forEach((l) => l.fn.call(node, event));
        if (this.connected) run(window, true);
        run(this, true);
        run(this, false);
        if (this.connected) run(window, false);
        return !event.defaultPrevented;
    };
    for (const node of [document.body, page.focused]) node.dispatchEvent = dispatchEvent;
    page.newNode = (name) => Object.assign(new Node(name), { dispatchEvent });
    page.context = vm.createContext({
        window,
        document,
        KeyboardEvent,
        navigator: { userAgent: 'Chrome/153.0.0.0' },
        setTimeout: () => {},
    });
    return page;
}

/** One announced press, as `announceKeystroke` builds it for `key` with these modifiers. */
function announce(page, key, code, keyCode, mods = {}) {
    vm.runInContext(fill(ANNOUNCE, {
        '${jsKey}': JSON.stringify(key),
        '${jsCode}': JSON.stringify(code),
        '${keyDef.keyCode}': String(keyCode),
        '${ctrlKey}': String(!!mods.ctrl),
        '${altKey}': String(!!mods.alt),
        '${effectiveShift}': String(!!mods.shift),
        '${metaKey}': String(!!mods.meta),
    }), page.context);
}

/** The interceptor installed on `page`, and its `beforeinput` listener. */
function intercept(page) {
    vm.runInContext(INTERCEPTOR, page.context);
    const listener = page.document.listeners.find((l) => l.type === 'beforeinput');
    assert.ok(listener, 'the interceptor installs no beforeinput listener');
    return (inputType, data) => listener.fn({
        inputType, data, preventDefault() {}, stopImmediatePropagation() {},
    });
}

const MODIFIER_KEYS = ['Alt', 'Control', 'Shift', 'Meta'];

/** The log as `type key [mods]`, the way a reader would write the sequence out. */
const sequence = (page) => page.log.map((e) => [e.type, e.key]
    .concat(['ctrlKey', 'altKey', 'shiftKey', 'metaKey'].filter((m) => e[m]).map((m) => m.slice(0, -3)))
    .join(' '));

/** The workbench's conclusions once the scripts are done. */
function outcome(page) {
    const { status, fired } = page.emitter;
    return {
        // What a toolbar last read: Split Editor Down while this is true.
        toolbarAlt: fired.length > 0 && fired[fired.length - 1].altKey,
        held: ['altKey', 'ctrlKey', 'shiftKey', 'metaKey'].filter((m) => status[m]),
        // The menu bar focuses itself on an Alt pressed and released with nothing between.
        menuBarAlt: fired.some((s) => !s.altKey && !s.ctrlKey && !s.shiftKey && !s.metaKey &&
            s.lastKeyPressed === 'alt' && s.lastKeyReleased === 'alt'),
        modifierPressed: page.log.some((e) => e.type === 'keydown' && MODIFIER_KEYS.includes(e.key)),
    };
}

const cases = [];
const settled = (name, page) => {
    const o = outcome(page);
    cases.push([`${name}: no toolbar is left on its Alt action`, o.toolbarAlt, false]);
    cases.push([`${name}: no modifier is left held`, o.held.join('+'), '']);
    cases.push([`${name}: nothing reads as a lone Alt for the menu bar`, o.menuBarAlt || o.modifierPressed, false]);
};

// Alt and the row's Esc, the measured case on the announce route.
{
    const page = newPage();
    announce(page, 'Escape', 'Escape', 27, { alt: true });
    cases.push(['Alt+Esc comes up as Esc, then Alt with nothing held', sequence(page).join(', '),
        'keydown Escape alt, keyup Escape alt, keyup Alt']);
    settled('Alt+Esc', page);
}

// Every modifier at once: each comes up in turn, leaving the others held.
{
    const page = newPage();
    announce(page, 'F5', 'F5', 116, { ctrl: true, alt: true, shift: true });
    cases.push(['Ctrl+Alt+Shift+F5 releases Alt, then Ctrl, then Shift', sequence(page).slice(2).join(', '),
        'keyup Alt ctrl shift, keyup Control shift, keyup Shift']);
    settled('Ctrl+Alt+Shift+F5', page);
}

// A key with nothing held sends nothing more.
{
    const page = newPage();
    announce(page, 'Tab', 'Tab', 9);
    cases.push(['a plain Tab is a keydown and a keyup and nothing else', sequence(page).join(', '),
        'keydown Tab, keyup Tab']);
}

// A chord that moves focus, as F1 opening the Command Palette does: the
// release goes where the chord's keyup went, which the window still hears.
{
    const page = newPage();
    const palette = page.newNode('palette');
    page.focused.addEventListener('keydown', () => { page.document.activeElement = palette; });
    announce(page, 'F1', 'F1', 112, { alt: true });
    cases.push(['the release after a chord that moved focus goes where the chord went',
        page.log.filter((e) => e.key === 'Alt').map((e) => e.at).join(','), 'editor']);
    settled('a chord that moved focus', page);
}

// A chord that takes its own target out of the document, as closing a box
// does. The window then hears neither its keyup nor the release, and the
// toolbars stay as the chord left them. What must not happen is the window
// hearing the release alone: after an Alt chord whose keyup it never heard,
// that reads as Alt pressed and released by itself, and focuses the menu bar.
{
    const page = newPage();
    page.focused.addEventListener('keydown', () => {
        page.focused.connected = false;
        page.document.activeElement = page.newNode('next');
    });
    announce(page, 'Escape', 'Escape', 27, { alt: true });
    const o = outcome(page);
    cases.push(['a chord that removed its target never reads as a lone Alt', o.menuBarAlt || o.modifierPressed, false]);
}

// Two Alt chords in a row are two chords, not an Alt pressed and released.
{
    const page = newPage();
    announce(page, 'Escape', 'Escape', 27, { alt: true });
    announce(page, 'F1', 'F1', 112, { alt: true });
    settled('two Alt chords', page);
}

// The interceptor: a letter typed on the soft keyboard with Alt latched.
{
    const page = newPage();
    const input = intercept(page);
    page.window.__vscodroid.alt = true;
    input('insertText', 'p');
    cases.push(['soft keyboard p with Alt latched comes up as p, then Alt', sequence(page).join(', '),
        'keydown p alt, keyup p alt, keyup Alt']);
    settled('soft keyboard Alt+p', page);
}

// The interceptor's other chord: an edit that stands for a key.
{
    const page = newPage();
    const input = intercept(page);
    page.window.__vscodroid.ctrl = true;
    input('deleteContentBackward', null);
    cases.push(['Backspace with Ctrl latched comes up as Backspace, then Ctrl', sequence(page).join(', '),
        'keydown Backspace ctrl, keyup Backspace ctrl, keyup Control']);
    settled('soft keyboard Ctrl+Backspace', page);
}

// Input the interceptor has no chord for sends nothing, so it releases nothing.
{
    const page = newPage();
    const input = intercept(page);
    page.window.__vscodroid.shift = true;
    input('insertText', 'a');
    page.window.__vscodroid.ctrl = true;
    input('insertFromPaste', 'abc');
    cases.push(['a lone Shift and a paste dispatch nothing', sequence(page).join(', '), '']);
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
