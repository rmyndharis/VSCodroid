/**
 * Self-check for the keyboard guard: its composition finish, and when it
 * lets the keyboard up.
 *
 *   node scripts/test-keyboard-guard.js
 *
 * The keyboard guard is JavaScript inside the Kotlin raw string of
 * `MainActivity.injectKeyboardGuard()`, handed to `evaluateJavascript`, so
 * nothing compiles it and `KeyboardGuardWiringTest` can only match its text.
 * Part of it ends a soft keyboard's composition before the editor moves the
 * caret: Chromium keeps an EditContext composition's range where it was when
 * the page moves the selection (crbug 379170477), so a keyboard that
 * recomposes the word at the new caret writes it over the old range. What
 * makes the finish work is order and state, which text matching cannot see:
 * the blur and refocus run in the same dispatch as the key or the editor's
 * gesture and before the editor's own handler, only while the focused
 * EditContext host is composing, and without the guard's own focusin handler
 * answering the refocus by changing `inputmode`, which restarts input. An
 * open suggest list is the exception, because ending a composition makes the
 * editor refilter the list and focus its first item again: Tab accepting a
 * suggestion and a tap on a row are finished after the list has acted, and
 * Up, Down, PageUp and PageDown that move the list's focus not at all.
 *
 * This extracts the real script, runs it under `vm` against a small fake DOM
 * that dispatches events through the capture, target and bubble phases, and
 * drives it with keys, taps and compositions. The editor's own handlers are
 * listeners on the editing host and on the gesture targets, so a finish that
 * runs after them, twice, or not at all shows in the order of the log, as does
 * any write to `inputmode`.
 *
 * NEGATIVE CONTROL, measured: against the script as it was before the finish
 * (main at 832754c6) 27 of the 43 cases fail, and against a finish that ends
 * the composition before every key and tap, the 9 suggest list cases fail.
 * Each of these changes to the finish fails at least one case: the
 * after-listener always on window, always on the target, or added for the
 * capture phase; no timeout; no test that the after-listener sees the same
 * event; the list read without `visible`; no single-suggestion rule, no
 * navigation-key rule or no modifier test; Tab taken without a focused row,
 * finished before, or every list key finished after; the list tap finished
 * before, or a long press on the list finished; no composing, `editContext`
 * or `.monaco-editor` test; no `reapplying`; and the key or gesture listeners
 * in the bubble phase. Dropping the after-listener's own removal changes
 * nothing observable, since it acts once and only for its event.
 *
 * The cases after those check when the guard lets the keyboard up at all: the
 * hold put back when the keyboard goes away, which Kotlin reports through
 * `window.__vscodroidKeyboardDismissed`, and a read-only editor, whose
 * EditContext host Monaco marks with `aria-autocomplete="none"`, never lifting
 * it. Against main at 353ede38, before both, 8 of the 53 cases fail; the two
 * that pass there are the controls, a writable editor and the textarea path.
 *
 * The last cases check that a tap outside text asks a keyboard that is up to
 * go down, which `inputmode` on an already focused host does only where no
 * hardware keyboard is attached, and is not written there for it, that the
 * terminal's textarea is held down and let up as the editor's host is, that
 * typing in a text box with the keyboard up counts as aiming at text, and
 * that a host blurred and focused again in one task keeps a keyboard that is
 * up, which needs the harness to tell tasks apart: a dispatch from here is a
 * task, and the microtasks queued in it run as it ends.
 *
 * Extraction is deliberately strict. If the raw string moves or changes shape
 * this fails saying so, rather than quietly checking an empty string.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const util = require('util');
const vm = require('vm');

const MAIN_ACTIVITY = path.join(
    __dirname, '..', 'android/app/src/main/kotlin/com/vscodroid/MainActivity.kt',
);

/**
 * The body of the raw string in `injectKeyboardGuard()`, with the indentation
 * `trimIndent()` removes taken off, which is the text the WebView is given.
 */
function extractGuard() {
    const lines = fs.readFileSync(MAIN_ACTIVITY, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('private fun injectKeyboardGuard()'));
    assert.notStrictEqual(
        fn, -1,
        'injectKeyboardGuard() is gone from MainActivity.kt, so this check has nothing to ' +
        'run. If the script moved, point this at its new home rather than deleting the check.',
    );

    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close !== -1 && close > open + 1,
        'could not find the raw string in injectKeyboardGuard(); its shape changed');

    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
    // A `$` before a name or `{` is a Kotlin template; before `/` it is literal.
    assert.ok(!/\$[A-Za-z_{]/.test(js),
        'the guard gained a Kotlin interpolation this check does not know how to fill');
    assert.ok(js.includes('function applyAll()'),
        'the extracted text has no applyAll(), so the wrong block was extracted');
    return js;
}

const GUARD = extractGuard();
const GESTURES = ['-monaco-gesturetap', '-monaco-gesturecontextmenu'];

/** Blur, focus, attribute writes and the editor's own handlers, in order. */
let log = [];

class Target {
    constructor(name) {
        this.name = name;
        this.listeners = [];
    }
    addEventListener(type, fn, capture) {
        this.listeners.push({ type, fn, capture: capture === true });
    }
    removeEventListener(type, fn, capture) {
        this.listeners = this.listeners.filter(
            (l) => !(l.type === type && l.fn === fn && l.capture === (capture === true)));
    }
}

function run(node, event, capture) {
    for (const l of node.listeners.slice()) {
        if (l.type === event.type && l.capture === capture) l.fn(event);
    }
}

/**
 * A dispatch made from here, rather than from a listener or a microtask, is a
 * task of its own, and the microtasks queued in it run when it ends, as at a
 * browser's microtask checkpoint. `task()` makes several calls one task, as a
 * script that blurs and focuses an element in one go is.
 */
let depth = 0;
function task(page, fn) {
    depth += 1;
    try {
        fn();
    } finally {
        depth -= 1;
        if (depth === 0) {
            depth += 1;
            while (page.microtasks.length) page.microtasks.shift()();
            depth -= 1;
        }
    }
}

/** As a browser does: capture from the window down, the target, then back up if it bubbles. */
function dispatch(page, target, init) {
    task(page, () => {
        const event = { bubbles: false, ...init, target };
        event.stopPropagation = () => { event.stopped = true; };
        const outer = [page.window, page.document];
        const ancestors = [];
        for (let n = target.parent; n; n = n.parent) ancestors.unshift(n);
        outer.push(...ancestors);
        const phases = [...outer.map((n) => [n, true]), [target, true], [target, false]];
        if (event.bubbles) phases.push(...outer.reverse().map((n) => [n, false]));
        for (const [n, capture] of phases) {
            run(n, event, capture);
            if (event.stopped) return;
        }
    });
}

/** `tag`, `.class` and `[name="value"]`, which is all the guard's selectors use. */
function matchesCompound(el, compound) {
    const rest = compound.replace(/^[a-z]+/, '').replace(/\.[\w-]+/g, '')
        .replace(/\[[\w-]+="[^"]*"\]/g, '');
    assert.strictEqual(rest, '',
        `the fake DOM cannot read the selector "${compound}"; teach matchesCompound it`);
    const tag = compound.match(/^[a-z]+/);
    if (tag && el.tagName !== tag[0].toUpperCase()) return false;
    for (const [, cls] of compound.matchAll(/\.([\w-]+)/g)) {
        if (!el.classes.includes(cls)) return false;
    }
    for (const [, name, value] of compound.matchAll(/\[([\w-]+)="([^"]*)"\]/g)) {
        if (el.getAttribute(name) !== value) return false;
    }
    return true;
}

/** A compound, or compounds separated by spaces: the last one inside the ones before it. */
function matchesSelector(el, selector) {
    const parts = selector.split(/\s+/);
    if (!matchesCompound(el, parts.pop())) return false;
    for (let n = el.parent; parts.length; n = n.parent) {
        if (!n) return false;
        if (matchesCompound(n, parts[parts.length - 1])) parts.pop();
    }
    return true;
}

class El extends Target {
    constructor(page, name, tag, classes, parent) {
        super(name);
        Object.assign(this, { page, tagName: tag.toUpperCase(), classes, parent, attrs: new Map() });
        page.elements.push(this);
    }
    get classList() {
        return {
            contains: (c) => this.classes.includes(c),
            add: (c) => { if (!this.classes.includes(c)) this.classes.push(c); },
            remove: (c) => { this.classes = this.classes.filter((x) => x !== c); },
        };
    }
    querySelectorAll(selectors) {
        return this.page.elements.filter((e) => {
            for (let n = e.parent; n; n = n.parent) if (n === this) return e.matches(selectors);
            return false;
        });
    }
    querySelector(selectors) { return this.querySelectorAll(selectors)[0] || null; }
    getAttribute(name) { return this.attrs.has(name) ? this.attrs.get(name) : null; }
    get virtualKeyboardPolicy() { return this.policy || ''; }
    set virtualKeyboardPolicy(value) { log.push(`${this.name} virtualKeyboardPolicy=${value}`); this.policy = value; }
    setAttribute(name, value) { log.push(`${this.name} ${name}=${value}`); this.attrs.set(name, String(value)); }
    removeAttribute(name) { log.push(`${this.name} -${name}`); this.attrs.delete(name); }
    matches(selectors) { return selectors.split(',').some((s) => matchesSelector(this, s.trim())); }
    closest(selectors) {
        for (let n = this; n; n = n.parent) if (n.matches(selectors)) return n;
        return null;
    }
    blur() {
        log.push(`blur ${this.name}`);
        const doc = this.page.document;
        if (doc.activeElement !== this) return;
        doc.activeElement = doc.body;
        // Blink finishes the composition of the element it takes focus from.
        if (this.editContext) this.editContext.end();
        dispatch(this.page, this, { type: 'focusout', bubbles: true });
    }
    focus() {
        log.push(`focus ${this.name}`);
        const doc = this.page.document;
        if (doc.activeElement === this) return;
        doc.activeElement = this;
        dispatch(this.page, this, { type: 'focusin', bubbles: true });
    }
    dispatchEvent(event) {
        dispatch(this.page, this, { ...event });
        return true;
    }
}

/** What the guard builds a key press from: the fields it is given. */
class KeyboardEvent {
    constructor(type, init) { Object.assign(this, init, { type }); }
}

/** Composition events fire on the EditContext object, never on the element. */
class FakeEditContext extends Target {
    fire(type) {
        for (const l of this.listeners.slice()) if (l.type === type) l.fn({ type, target: this });
    }
    start() { this.composing = true; this.fire('compositionstart'); }
    end() {
        if (!this.composing) return;
        this.composing = false;
        this.fire('compositionend');
    }
}

/**
 * A workbench with one editor, its suggest list, a tab bar and an Explorer
 * list, and the guard installed. The editing host is an EditContext element,
 * or with `textarea` the `textarea.inputarea` of the other edit path. With
 * `terminal`, a terminal in the panel as well: xterm's screen, which a touch
 * lands on, and its helper textarea, which takes the typing.
 */
function newPage({ textarea = false, keybindings = false, terminal = false } = {}) {
    const page = { elements: [], window: new Target('window'), document: new Target('document') };
    const doc = page.document;
    doc.body = new El(page, 'body', 'body', [], null);
    doc.activeElement = doc.body;
    doc.querySelectorAll = (selectors) => page.elements.filter((e) => e.matches(selectors));
    doc.querySelector = (selectors) => doc.querySelectorAll(selectors)[0] || null;

    page.tabs = new El(page, 'tabs', 'div', ['tabs-container'], doc.body);
    page.explorer = new El(page, 'explorer', 'div', ['monaco-list-rows'], doc.body);
    const editor = new El(page, 'editor', 'div', ['monaco-editor'], doc.body);
    page.lines = new El(page, 'lines', 'div', ['lines-content'], editor);
    page.editor = editor;
    page.widget = new El(page, 'widget', 'div', ['suggest-widget'], editor);
    page.suggest = new El(page, 'suggest', 'div', ['monaco-list-rows'], page.widget);
    page.rows = [0, 1].map((i) => new El(page, `row${i}`, 'div', ['monaco-list-row'], page.suggest));
    if (textarea) {
        page.host = new El(page, 'host', 'textarea', ['inputarea'], editor);
    } else {
        page.host = new El(page, 'host', 'div', ['native-edit-context'], editor);
        page.host.editContext = new FakeEditContext('editContext');
    }
    if (terminal) {
        const xterm = new El(page, 'xterm', 'div', ['xterm'], doc.body);
        page.screen = new El(page, 'screen', 'div', ['xterm-screen'], xterm);
        page.term = new El(page, 'term', 'textarea', ['xterm-helper-textarea'], xterm);
        // The terminal focuses its textarea on touchstart, which comes after
        // the guard's pointerdown and before its pointerup.
        page.screen.addEventListener('pointerdown', () => {
            if (doc.activeElement !== page.term) page.term.focus();
        });
    }

    // The workbench's own handlers, which the finish has to run before.
    page.host.addEventListener('keydown', (e) => log.push(`editor ${e.key}`));
    for (const target of [page.lines, page.suggest, page.explorer]) {
        for (const type of GESTURES) target.addEventListener(type, () => log.push(`${target.name} ${type}`));
    }

    // The workbench's keybinding service: a window listener in the bubble
    // phase, registered long before the guard, that runs the suggest list's
    // commands. Only where a case asks for it, so the other logs stay short.
    if (keybindings) page.window.addEventListener('keydown', (e) => log.push(`keybinding ${e.key}`));

    page.timers = [];
    const setTimeout = (fn) => { page.timers.push(fn); };
    page.microtasks = [];
    page.queueMicrotask = (fn) => { page.microtasks.push(fn); };
    // What ExtraKeyRow reports, and the API the guard hides the keyboard with.
    page.window.__vscodroidImeVisible = true;
    const navigator = { virtualKeyboard: { hide: () => log.push('hide') } };
    vm.runInContext(GUARD, vm.createContext({
        window: page.window, document: doc, setTimeout, queueMicrotask: page.queueMicrotask, navigator, KeyboardEvent,
    }));
    return page;
}

function press(page, key) {
    dispatch(page, page.document.activeElement, { type: 'keydown', key, bubbles: true });
}

/** A finger held on `target` for `ms`, then the gesture the editor makes of it, if any. */
function touch(page, target, ms, gesture) {
    const at = { bubbles: true, pointerId: 1, clientX: 20, clientY: 20 };
    dispatch(page, target, { ...at, type: 'pointerdown', timeStamp: 1000 });
    dispatch(page, target, { ...at, type: 'pointerup', timeStamp: 1000 + ms });
    // The editor dispatches its gestures on its own gesture target, not bubbling.
    if (gesture) dispatch(page, target, { type: gesture });
}

/** An editor the user has tapped into, with the keyboard up and a word underlined. */
function typing({ textarea = false, composing = true, keybindings = false } = {}) {
    const page = newPage({ textarea, keybindings });
    page.host.focus();
    touch(page, page.lines, 100, '-monaco-gesturetap');
    if (composing && textarea) dispatch(page, page.host, { type: 'compositionstart', bubbles: true });
    else if (composing) page.host.editContext.start();
    assert.strictEqual(page.document.activeElement, page.host,
        'setup: the tap on text left focus off the editing host');
    assert.strictEqual(page.host.getAttribute('inputmode'), null,
        'setup: the tap on text did not let the keyboard up');
    log = [];
    return page;
}

/** The suggest list as the editor shows it: `visible`, with `rows` rows, `focused` the focused one. */
function openList(page, { rows = 2, focused = 0 } = {}) {
    page.widget.classList.add('visible');
    page.rows.forEach((row, i) => {
        if (i >= rows) row.parent = null;
        if (i === focused) row.classList.add('focused');
    });
    return page;
}

function flushTimers(page) {
    const timers = page.timers.splice(0);
    for (const fn of timers) task(page, fn);
}

function pressWith(page, key, mods) {
    dispatch(page, page.document.activeElement, { type: 'keydown', key, bubbles: true, ...mods });
}

const cases = [];
function check(name, page, act, want) {
    act(page);
    cases.push([name, [...log], want]);
}
const finished = (then) => ['blur host', 'focus host', then];

for (const key of ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End', 'PageUp',
    'PageDown', 'Tab', 'Backspace', 'Delete']) {
    check(`${key} while a word composes finishes it before the editor sees the key`,
        typing(), (p) => press(p, key), finished(`editor ${key}`));
}
check('End with no word composing is left alone',
    typing({ composing: false }), (p) => press(p, 'End'), ['editor End']);
check('End after the word was committed is left alone',
    typing(), (p) => { p.host.editContext.end(); press(p, 'End'); }, ['editor End']);
for (const key of ['a', 'Unidentified', 'Enter', 'Escape', 'F1', 'Shift']) {
    check(`${key} while a word composes is left alone`,
        typing(), (p) => press(p, key), [`editor ${key}`]);
}
check('a tap on text while a word composes finishes it before the editor moves the caret',
    typing(), (p) => touch(p, p.lines, 100, '-monaco-gesturetap'),
    finished('lines -monaco-gesturetap'));
check('a long press, which moves the caret and opens the menu, the same',
    typing(), (p) => touch(p, p.lines, 1000, '-monaco-gesturecontextmenu'),
    finished('lines -monaco-gesturecontextmenu'));
check('a tap on a suggestion finishes the word after the list has taken the tap',
    typing(), (p) => touch(p, p.suggest, 100, '-monaco-gesturetap'),
    ['suggest -monaco-gesturetap', 'blur host', 'focus host']);
check('a long press on the suggest list moves nothing and is left alone',
    typing(), (p) => touch(p, p.suggest, 1000, '-monaco-gesturecontextmenu'),
    ['suggest -monaco-gesturecontextmenu']);
check('Tab the open list takes finishes the word after the keybinding has run',
    openList(typing({ keybindings: true }), { focused: 1 }), (p) => press(p, 'Tab'),
    ['editor Tab', 'keybinding Tab', 'blur host', 'focus host']);
check('Tab with the list open but nothing focused finishes before, as a caret key',
    openList(typing({ keybindings: true }), { focused: -1 }), (p) => press(p, 'Tab'),
    ['blur host', 'focus host', 'editor Tab', 'keybinding Tab']);
for (const key of ['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown']) {
    check(`${key} the open list takes is left alone: it moves the list, not the caret`,
        openList(typing({ keybindings: true })), (p) => press(p, key),
        [`editor ${key}`, `keybinding ${key}`]);
}
check('ArrowDown with one focused suggestion moves the caret, so it finishes before',
    openList(typing({ keybindings: true }), { rows: 1 }), (p) => press(p, 'ArrowDown'),
    ['blur host', 'focus host', 'editor ArrowDown', 'keybinding ArrowDown']);
check('Shift+ArrowDown with the list open selects text, so it finishes before',
    openList(typing({ keybindings: true })), (p) => pressWith(p, 'ArrowDown', { shiftKey: true }),
    ['blur host', 'focus host', 'editor ArrowDown', 'keybinding ArrowDown']);
for (const key of ['ArrowLeft', 'ArrowRight', 'Home', 'End', 'Backspace', 'Delete']) {
    check(`${key} with the list open moves the caret, so it finishes before`,
        openList(typing({ keybindings: true })), (p) => press(p, key),
        ['blur host', 'focus host', `editor ${key}`, `keybinding ${key}`]);
}
check('Tab the list takes with the key stopped before window: the timeout finishes it, once',
    openList(typing({ keybindings: true }), { focused: 1 }), (p) => {
        p.editor.addEventListener('keydown', (e) => { log.push(`container ${e.key}`); e.stopPropagation(); });
        press(p, 'Tab');
        log.push('-- timeout');
        flushTimers(p);
        press(p, 'a');
    },
    ['editor Tab', 'container Tab', '-- timeout', 'blur host', 'focus host', 'editor a', 'container a']);
check('a key dispatched inside the Tab dispatch does not set the finish off early',
    openList(typing({ keybindings: true }), { focused: 1 }), (p) => {
        p.host.addEventListener('keydown', (e) => {
            if (e.key === 'Tab') dispatch(p, p.host, { type: 'keydown', key: 'Shift', bubbles: true });
        });
        press(p, 'Tab');
    },
    ['editor Tab', 'editor Shift', 'keybinding Shift', 'keybinding Tab', 'blur host', 'focus host']);
check('Tab with the list open and no word composing is left alone',
    openList(typing({ keybindings: true, composing: false }), { focused: 1 }), (p) => {
        press(p, 'Tab');
        log.push(`timers ${p.timers.length}`);
    },
    ['editor Tab', 'keybinding Tab', 'timers 0']);
check('a tap on a list outside the editor is left alone',
    typing(), (p) => touch(p, p.explorer, 100, '-monaco-gesturetap'),
    ['explorer -monaco-gesturetap']);
check('after a tap outside text, End finishes the word once and never writes inputmode',
    typing(), (p) => { touch(p, p.tabs, 100); press(p, 'End'); }, finished('editor End'));
check('the textarea edit path, which has no EditContext, is left alone',
    typing({ textarea: true }), (p) => press(p, 'End'), ['editor End']);

// When the keyboard comes up. The guard holds it down with inputmode="none" on
// the editing host; Chromium raises it for a touch on a focused host without
// that attribute, a scroll included, so the attribute is what these read.
const inputmode = (p, el = p.host) => log.push(`inputmode ${el.getAttribute('inputmode')}`);

/** The editor as Monaco marks a read-only one, focused and held down. */
function readOnly({ textarea = false } = {}) {
    const page = newPage({ textarea });
    page.host.attrs.set('aria-autocomplete', 'none');
    page.host.focus();
    log = [];
    return page;
}

/** What Kotlin runs when the soft keyboard goes away. */
function keyboardGone(p) {
    const hook = p.window.__vscodroidKeyboardDismissed;
    if (typeof hook !== 'function') log.push('no dismissal hook');
    else hook();
}

function drag(p, target) {
    const at = { bubbles: true, pointerId: 1, clientX: 20 };
    dispatch(p, target, { ...at, type: 'pointerdown', clientY: 20, timeStamp: 1000 });
    dispatch(p, target, { ...at, type: 'pointerup', clientY: 300, timeStamp: 1300 });
}

check('a tap on text in a read-only editor moves the caret and leaves the keyboard down',
    readOnly(), (p) => { touch(p, p.lines, 100, '-monaco-gesturetap'); inputmode(p); },
    ['lines -monaco-gesturetap', 'inputmode none']);
check('focus returning to a read-only editor after typing in a text box keeps the keyboard down',
    readOnly(), (p) => {
        const palette = new El(p, 'palette', 'input', [], p.document.body);
        palette.focus();
        touch(p, palette, 100);
        log = [];
        p.host.focus();
        inputmode(p);
    },
    ['focus host', 'inputmode none']);
check('the same editor made writable again lets the keyboard up for a tap on text',
    readOnly(), (p) => {
        p.host.attrs.set('aria-autocomplete', 'both');
        touch(p, p.lines, 100);
    },
    ['host -inputmode', 'blur host', 'focus host']);
check('a read-only editor on the textarea path, whose marker is not kept current, raises it as before',
    readOnly({ textarea: true }), (p) => touch(p, p.lines, 100),
    ['host -inputmode', 'blur host', 'focus host']);

check('the keyboard put away by the user is held down again, so a scroll leaves it down',
    typing({ composing: false }), (p) => { keyboardGone(p); drag(p, p.lines); inputmode(p); },
    ['host inputmode=none', 'inputmode none']);
check('after the keyboard was put away, a tap on text lets it up again',
    typing({ composing: false }), (p) => { keyboardGone(p); log = []; touch(p, p.lines, 100); },
    ['host -inputmode', 'blur host', 'focus host']);
check('a second report of the keyboard going away writes nothing',
    typing({ composing: false }), (p) => { keyboardGone(p); keyboardGone(p); },
    ['host inputmode=none']);
check('the keyboard going away after a tap outside text holds the host the tap left focused',
    typing({ composing: false }), (p) => { touch(p, p.tabs, 100); log = []; keyboardGone(p); },
    ['host inputmode=none']);
check('the keyboard going away under a word still composing leaves inputmode alone',
    typing(), (p) => { keyboardGone(p); inputmode(p); },
    ['inputmode null']);
check('the keyboard going away from a text box leaves the box alone, and a tap brings it back',
    newPage(), (p) => {
        const rename = new El(p, 'rename', 'input', [], p.explorer);
        rename.focus();
        touch(p, rename, 100);
        log = [];
        keyboardGone(p);
        touch(p, rename, 100);
        inputmode(p, rename);
    },
    ['host inputmode=none', 'host -inputmode', 'inputmode null']);


// A tap outside text with the keyboard up. The activity bar's icons leave focus
// on the editing host, and the guard asks for the keyboard down directly once the
// tap's handlers have run (the timers flushed below), under a manual policy it
// puts back afterwards. While the keyboard is up it writes no hold on the focused
// host at the touch: on a phone, Chromium takes the keyboard down as soon as the
// focused element's inputmode turns to none, so a hold there would put it away
// for a drag, a long press or a button that opens a menu as well. The emulators
// measured report a hardware keyboard, where that never happens, which is why a
// hold there looked harmless. The hosts that are not focused are held as before.
//
// NEGATIVE CONTROL, measured: against main (f66e462f), whose guard asks for
// nothing and holds every host at a touch outside text, every case here fails
// but the tap in an open menu, the tap with the keyboard already down and the
// tap on text once the keyboard is reported down; against the guard that held
// the focused host at that touch (3ad8920e), the 8 cases that expect no hold on
// it fail; against one that does not put the earlier host back first, the last
// case fails.
check('a tap outside text with the editor focused and the keyboard up asks the keyboard down',
    typing({ composing: false }), (p) => { touch(p, p.explorer, 100); log.push('-- timeout'); flushTimers(p); },
    ['-- timeout', 'host virtualKeyboardPolicy=manual', 'hide']);
check('the same tap while a word composes asks it down too, and leaves inputmode alone',
    typing(), (p) => { touch(p, p.explorer, 100); flushTimers(p); },
    ['host virtualKeyboardPolicy=manual', 'hide']);
// A status bar item, such as Copy Bug Report, is a gesture target like the
// activity bar's icons: it runs its command on the gesture's tap and leaves
// focus on the editor. Copy reads the editor's text, so the command has to
// meet the host still focused with its word still composing, and the request
// comes after it, with nothing ended or rewritten by the page.
check('a tap on a status bar item runs its command on the editor as it was, then asks the keyboard down',
    typing(), (p) => {
        const item = new El(p, 'item', 'div', ['statusbar-item'], p.document.body);
        const copy = new El(p, 'copy', 'a', ['statusbar-item-label'], item);
        copy.attrs.set('role', 'button');
        copy.addEventListener('-monaco-gesturetap', () => log.push(
            `copy on ${p.document.activeElement.name}, composing ${p.host.editContext.composing}`));
        touch(p, copy, 100, '-monaco-gesturetap');
        flushTimers(p);
    },
    ['copy on host, composing true', 'host virtualKeyboardPolicy=manual', 'hide']);
check('the policy goes back to auto when the keyboard is reported down, and the host is held',
    typing({ composing: false }), (p) => { touch(p, p.explorer, 100); flushTimers(p); log = []; keyboardGone(p); },
    ['host virtualKeyboardPolicy=auto', 'host inputmode=none']);
check('and after a second when that report never comes',
    typing({ composing: false }), (p) => { touch(p, p.explorer, 100); flushTimers(p); log = []; flushTimers(p); },
    ['host virtualKeyboardPolicy=auto']);
check('a tap on text right after puts the policy back, so the tap can raise the keyboard',
    typing({ composing: false }), (p) => { touch(p, p.explorer, 100); flushTimers(p); log = []; touch(p, p.lines, 100); },
    ['host virtualKeyboardPolicy=auto', 'host -inputmode']);
check('the same tap once the keyboard is reported down lets it up with the refocus',
    typing({ composing: false }), (p) => {
        touch(p, p.explorer, 100);
        flushTimers(p);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p);
        log = [];
        touch(p, p.lines, 100);
    },
    ['host -inputmode', 'blur host', 'focus host']);
check('a tap outside text on the textarea path asks the keyboard down as well',
    typing({ textarea: true, composing: false }), (p) => { touch(p, p.explorer, 100); flushTimers(p); },
    ['host virtualKeyboardPolicy=manual', 'hide']);
check('a drag outside text asks nothing and holds nothing on the focused host',
    typing({ composing: false }), (p) => { drag(p, p.explorer); flushTimers(p); },
    []);
check('a long press outside text the same',
    typing({ composing: false }), (p) => { touch(p, p.explorer, 1000); flushTimers(p); },
    []);
check('a tap that takes focus into a text box asks nothing',
    typing({ composing: false }), (p) => {
        const search = new El(p, 'search', 'input', [], p.explorer);
        touch(p, p.explorer, 100);
        search.focus();
        flushTimers(p);
    },
    ['focus search']);
check('a tap on a button that opens a menu asks nothing and holds nothing, so the menu it opened stays open',
    typing({ composing: false }), (p) => {
        const more = new El(p, 'more', 'a', ['action-label'], p.explorer);
        more.attrs.set('aria-haspopup', 'true');
        touch(p, more, 100);
        flushTimers(p);
    },
    []);
check('a touch on the block of a menu closed before the guard saw it asks nothing',
    typing({ composing: false }), (p) => {
        // What the menu keeper leaves: it closes the menu from a window
        // listener first, which takes the block the touch landed on off the page.
        const block = new El(p, 'block', 'div', ['context-view-block'], null);
        block.isConnected = false;
        touch(p, block, 100);
        flushTimers(p);
    },
    []);
check('a tap in an open context menu asks nothing',
    typing({ composing: false }), (p) => {
        const menu = new El(p, 'menu', 'div', ['context-view'], p.document.body);
        touch(p, menu, 100);
        flushTimers(p);
    },
    []);
check('with the keyboard already down a tap outside text asks nothing',
    typing({ composing: false }), (p) => { p.window.__vscodroidImeVisible = false; touch(p, p.explorer, 100); flushTimers(p); },
    ['host inputmode=none']);
// Two requests inside the second the first one waits for its report, the
// second for a host that took focus without a tap, such as the chat input
// focused by a command: the first host gets auto back, or it would keep manual
// and never raise the keyboard for a tap again.
check('a second request for another host first puts the policy back on the host before',
    typing({ composing: false }), (p) => {
        const chat = new El(p, 'chat', 'div', ['monaco-editor'], p.document.body);
        const input = new El(p, 'input', 'div', ['native-edit-context'], chat);
        input.editContext = new FakeEditContext('inputContext');
        touch(p, p.explorer, 100);
        flushTimers(p);
        input.focus();
        touch(p, p.explorer, 100);
        log = [];
        // The zero timeout of the second tap runs before the one second
        // timeout of the first.
        const second = p.timers.pop();
        if (second) second();
        flushTimers(p);
        log.push(`host ${p.host.virtualKeyboardPolicy}`);
    },
    ['host virtualKeyboardPolicy=auto', 'input virtualKeyboardPolicy=manual', 'hide',
        'input virtualKeyboardPolicy=auto', 'host auto']);

// The terminal's helper textarea is an editing host too. With the terminal
// focused and the keyboard up, a tap on an activity bar icon left the keyboard
// over the view it opened, with typing still going to the shell, and after the
// keyboard was put away the same tap raised it again, as Chromium raises it for
// a tap anywhere while an editable element without inputmode="none" has focus.
//
// NEGATIVE CONTROL, measured: against the guard without the terminal in its
// selectors every case below but the drag fails, and with its textarea an
// editing host but its screen not text the setup's tap on the terminal is read
// as one outside text and the setup fails.
function terminalTyping() {
    const page = newPage({ terminal: true, keybindings: true });
    touch(page, page.screen, 100);
    flushTimers(page);
    assert.strictEqual(page.document.activeElement, page.term,
        'setup: the tap on the terminal left focus off its textarea');
    assert.strictEqual(page.term.getAttribute('inputmode'), null,
        'setup: the tap on the terminal did not let the keyboard up');
    log = [];
    return page;
}
check('with the terminal focused and the keyboard up, a tap outside text asks the keyboard down',
    terminalTyping(), (p) => { touch(p, p.explorer, 100); flushTimers(p); },
    ['host inputmode=none', 'term virtualKeyboardPolicy=manual', 'hide']);
check('the keyboard put away from the terminal is held down, so a tap outside text leaves it down',
    terminalTyping(), (p) => {
        keyboardGone(p);
        p.window.__vscodroidImeVisible = false;
        touch(p, p.explorer, 100);
        flushTimers(p);
        inputmode(p, p.term);
    },
    ['host inputmode=none', 'term inputmode=none', 'inputmode none']);
check('after that, a tap on the terminal lets the keyboard up again',
    terminalTyping(), (p) => { keyboardGone(p); log = []; touch(p, p.screen, 100); },
    ['host -inputmode', 'term -inputmode', 'blur term', 'focus term']);
check('a drag to scroll the terminal changes nothing',
    terminalTyping(), (p) => { drag(p, p.screen); flushTimers(p); },
    []);
check('a terminal focused after a tap outside text, from its tab say, has the keyboard put down',
    newPage({ terminal: true }), (p) => {
        p.host.focus();
        touch(p, p.lines, 100);
        log = [];
        touch(p, p.tabs, 100);
        p.term.focus();
        flushTimers(p);
    },
    ['term inputmode=none', 'focus term', 'term virtualKeyboardPolicy=manual', 'hide']);

// Typing with the keyboard up is aiming at text, in a text box too. The Command
// Palette opened from the Application Menu is typed into with the last touch on
// that menu, and Enter on Terminal: Create New Terminal focused a new terminal,
// which the focus handler held, so the keyboard went down under it.
//
// NEGATIVE CONTROL, measured: against the guard without its beforeinput
// listener the first case fails, as do the later cases of a file typed into
// after the Command Palette and of the two files Enter opens through a gap,
// and the four after it pass; without the listener's isTrusted test, its test
// that the keyboard is up, or its test that the target is text, the case for
// that test fails alone.
function paletteFromMenu() {
    const page = newPage();
    page.host.focus();
    touch(page, page.lines, 100);
    const menu = new El(page, 'menu', 'div', ['menubar-menu-button'], page.document.body);
    menu.attrs.set('aria-haspopup', 'true');
    touch(page, menu, 100);
    flushTimers(page);
    page.palette = new El(page, 'palette', 'input', [], page.document.body);
    page.palette.focus();
    log = [];
    return page;
}
function typeInto(p, target, isTrusted = true) {
    dispatch(p, target, { type: 'beforeinput', inputType: 'insertCompositionText', bubbles: true, isTrusted });
}
function newTerminal(p) {
    const xterm = new El(p, 'xterm', 'div', ['xterm'], p.document.body);
    new El(p, 'term', 'textarea', ['xterm-helper-textarea'], xterm).focus();
    flushTimers(p);
}
const held = ['focus term', 'term inputmode=none', 'blur term', 'focus term'];
check('a terminal that Enter in the Command Palette opens after typing there keeps the keyboard up',
    paletteFromMenu(), (p) => { typeInto(p, p.palette); newTerminal(p); },
    ['focus term', 'term -inputmode']);
check('reached without typing it is held, as after the tap outside text',
    paletteFromMenu(), (p) => newTerminal(p), held);
check('an untrusted beforeinput, as a chord from the key row sends, counts for nothing',
    paletteFromMenu(), (p) => { typeInto(p, p.palette, false); newTerminal(p); }, held);
check('typing with the keyboard down, on a hardware keyboard, counts for nothing',
    paletteFromMenu(), (p) => {
        p.window.__vscodroidImeVisible = false;
        typeInto(p, p.palette);
        newTerminal(p);
    },
    held);
// An editable the guard's text selector does not name, such as an element with
// contenteditable="plaintext-only", is not a text box it holds or lets up.
check('typing into an element that is not text counts for nothing',
    paletteFromMenu(), (p) => {
        const box = new El(p, 'box', 'div', [], p.document.body);
        box.attrs.set('contenteditable', 'plaintext-only');
        typeInto(p, box);
        newTerminal(p);
    },
    held);

// A host blurred and focused again in one task keeps a keyboard that is up.
// Patch 0024 restarts input that way once the editor's handler for a key has
// returned, when the key rewrote the keyboard's text under a caret left at the
// same offset, as Down along a column does, and the key row does after a
// latched chord. After a touch outside text that leaves the keyboard up, a
// drag, a long press or a tap on a button that opens a menu, the focus handler
// held the host at that focus, as it does a host focused anew; and a turn of the
// phone that reports the keyboard down and up again leaves a hold on it.
//
// NEGATIVE CONTROL, measured: against the guard before this (f8d9ec80) the 7
// cases that end without a hold fail and the 4 that end with one pass, and
// against main (f66e462f) the same 7 fail. Each part of the change fails a
// case of its own when taken out: the lift in finishComposition, the word
// started over the hold a turn of the phone left; the refocus after the focus
// handler's lift, the turn of the phone; the end-of-task clear, and marking
// the host held at its focus only where the hold is written, the later-task
// case; keepsTheKeyboard's keyboard test, the keyboard-down case; its writable
// test, the read-only case; its test of the host held at its focus, the
// later-task and the file cases; that host's clear when the keyboard is
// reported down, the Command Palette case; and its clear at a tap on text,
// the 4 cases of a touch outside text that leaves the keyboard up, in which
// nothing else clears it after the setup's focus.
function restartsAt(p, key, host = p.host) {
    host.addEventListener('keydown', (e) => {
        if (e.key === key) p.queueMicrotask(() => { host.blur(); host.focus(); });
    });
    return p;
}
function menuButton(p) {
    const more = new El(p, 'more', 'a', ['action-label'], p.explorer);
    more.attrs.set('aria-haspopup', 'true');
    return more;
}
function newFile(p) {
    const editor = new El(p, 'other', 'div', ['monaco-editor'], p.document.body);
    const host = new El(p, 'file', 'div', ['native-edit-context'], editor);
    host.editContext = new FakeEditContext('fileContext');
    return host;
}
const restarted = ['editor ArrowDown', 'blur host', 'focus host'];
const letGo = [...restarted, 'host -inputmode', 'blur host', 'focus host', 'inputmode null'];
for (const [how, outside] of [
    ['a drag', (p) => drag(p, p.explorer)],
    ['a long press', (p) => touch(p, p.explorer, 1000)],
    ['a tap on a button that opens a menu', (p) => touch(p, menuButton(p), 100)],
]) {
    check(`after ${how} outside text with the keyboard up, the input restart at Down holds nothing`,
        restartsAt(typing({ composing: false }), 'ArrowDown'), (p) => {
            outside(p);
            flushTimers(p);
            press(p, 'ArrowDown');
            inputmode(p);
        },
        [...restarted, 'inputmode null']);
}
check('after a turn of the phone that reports the keyboard down and up again, the same',
    restartsAt(typing({ composing: false }), 'ArrowDown'), (p) => {
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p);
        p.window.__vscodroidImeVisible = true;
        press(p, 'ArrowDown');
        inputmode(p);
    },
    ['host inputmode=none', ...letGo]);
check('a word composing through the drag, ended at Up and then restarted, is not held',
    restartsAt(typing(), 'ArrowUp'), (p) => {
        drag(p, p.explorer);
        press(p, 'ArrowUp');
        inputmode(p);
    },
    ['blur host', 'focus host', 'editor ArrowUp', 'blur host', 'focus host', 'inputmode null']);
check('a word started over the hold a turn of the phone left, and ended by a caret key, takes the hold off as it ends',
    typing({ composing: false }), (p) => {
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p);
        p.window.__vscodroidImeVisible = true;
        p.host.editContext.start();
        press(p, 'ArrowLeft');
        inputmode(p);
    },
    ['host inputmode=none', 'blur host', 'host -inputmode', 'focus host', 'editor ArrowLeft', 'inputmode null']);
check('with the keyboard down, the restart at Down leaves the hold on',
    restartsAt(typing({ composing: false }), 'ArrowDown'), (p) => {
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p);
        press(p, 'ArrowDown');
        inputmode(p);
    },
    ['host inputmode=none', ...restarted, 'inputmode none']);
check('blurred in one task and focused in a later one, the host is focused anew and stays held',
    typing({ composing: false }), (p) => {
        // The hold a turn of the phone leaves, with the keyboard up again.
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p);
        p.window.__vscodroidImeVisible = true;
        p.host.blur();
        task(p, () => {
            p.host.focus();
            p.queueMicrotask(() => { p.host.blur(); p.host.focus(); });
        });
        inputmode(p);
    },
    ['host inputmode=none', 'blur host', 'focus host', 'blur host', 'focus host', 'inputmode none']);
check('a read-only editor restarted in one task with the keyboard up stays held',
    readOnly(), (p) => {
        touch(p, p.lines, 100);
        log = [];
        task(p, () => { p.host.blur(); p.host.focus(); });
        inputmode(p);
    },
    ['blur host', 'focus host', 'inputmode none']);
// A file opened with the keyboard up is held at its focus to take the keyboard
// down, and the editor restarts input in the same task when it first writes a
// new host's buffer with the caret at offset 0.
check('a file held at its focus with the keyboard up stays held through a restart in that task',
    typing({ composing: false }), (p) => {
        drag(p, p.explorer);
        log = [];
        const file = newFile(p);
        task(p, () => {
            file.focus();
            p.queueMicrotask(() => { file.blur(); file.focus(); });
        });
        inputmode(p, file);
    },
    ['focus file', 'file inputmode=none', 'blur file', 'focus file', 'blur file', 'focus file', 'inputmode none']);
check('once that hold has taken the keyboard down it counts for nothing: typed into later, the file is let go',
    typing({ composing: false }), (p) => {
        drag(p, p.explorer);
        const file = newFile(p);
        restartsAt(p, 'ArrowDown', file);
        file.focus();
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p);
        // The Command Palette typed into, then Escape, which gives the file focus back.
        p.window.__vscodroidImeVisible = true;
        const palette = new El(p, 'palette', 'input', [], p.document.body);
        palette.focus();
        typeInto(p, palette);
        file.focus();
        drag(p, p.explorer);
        log = [];
        press(p, 'ArrowDown');
        inputmode(p, file);
    },
    ['blur file', 'focus file', 'inputmode null']);

// Chromium takes the keyboard down itself when focus leaves text, and that is
// not the keyboard put away. Enter in Quick Open on a file not open yet takes
// the old editor's host away and leaves focus on the page while the file loads,
// and Enter in the Explorer's New File box leaves it on the Explorer's list; the
// keyboard went down in that gap, and read as put away, the file typed for
// opened held.
//
// NEGATIVE CONTROL, measured: against the guard before this (3ad8920e), and with
// only its test of what has focus taken out of the dismissal hook, the two
// cases that end without a hold fail and the control passes.
check('a file Quick Open opens after typing there, through a gap with the page focused, is not held',
    paletteFromMenu(), (p) => {
        typeInto(p, p.palette);
        // Enter: the old editor's host takes focus, then goes as its view is
        // rebuilt for the file, and the keyboard goes down with nothing focused.
        p.host.focus();
        p.host.blur();
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p);
        log = [];
        const file = newFile(p);
        file.focus();
        inputmode(p, file);
    },
    ['focus file', 'file -inputmode', 'inputmode null']);
check('a file created with Enter in the New File box, through focus on the Explorer list, is not held',
    newPage(), (p) => {
        const list = new El(p, 'list', 'div', ['monaco-list'], p.document.body);
        const box = new El(p, 'box', 'input', [], list);
        // The long press that opens the Explorer's menu, then New File...
        touch(p, list, 1000);
        box.focus();
        typeInto(p, box);
        list.focus();
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p);
        log = [];
        const file = newFile(p);
        file.focus();
        inputmode(p, file);
    },
    ['focus file', 'file -inputmode', 'inputmode null']);
check('the keyboard put away with Back while the box typed into has focus still counts',
    paletteFromMenu(), (p) => {
        typeInto(p, p.palette);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p);
        log = [];
        newTerminal(p);
    },
    held);

// A touch outside text that the workbench's own gesture takes as a tap opens
// what the tap is for, and the keyboard goes down for it as for any tap: the
// gesture grants one for a press under 700 ms that stays within 30 px on each
// axis, looser than the guard's own test.
//
// NEGATIVE CONTROL, measured: with the gesture's tap taken out of the test the
// two cases with it fail and the three controls pass; against the guard before
// this (3ad8920e) all five fail, as it also held the focused host at the touch.
function slide(p, target, dy, ms, gesture) {
    const at = { bubbles: true, pointerId: 1, clientX: 20 };
    dispatch(p, target, { ...at, type: 'pointerdown', clientY: 20, timeStamp: 1000 });
    dispatch(p, target, { ...at, type: 'pointerup', clientY: 20 + dy, timeStamp: 1000 + ms });
    if (gesture) dispatch(p, target, { type: gesture });
}
const askedDown = ['host virtualKeyboardPolicy=manual', 'hide'];
check('a touch outside text that slides 20 px, which the workbench takes as a tap, asks the keyboard down',
    typing({ composing: false }), (p) => { slide(p, p.explorer, 20, 250, '-monaco-gesturetap'); flushTimers(p); },
    ['explorer -monaco-gesturetap', ...askedDown]);
check('a press of 620 ms outside text, which the workbench takes as a tap, asks it down too',
    typing({ composing: false }), (p) => { slide(p, p.explorer, 0, 620, '-monaco-gesturetap'); flushTimers(p); },
    ['explorer -monaco-gesturetap', ...askedDown]);
check('a press the workbench takes as a hold, which opens a menu, asks nothing',
    typing({ composing: false }), (p) => { slide(p, p.explorer, 0, 750, '-monaco-gesturecontextmenu'); flushTimers(p); },
    ['explorer -monaco-gesturecontextmenu']);
check('a slide the workbench takes as a scroll asks nothing',
    typing({ composing: false }), (p) => { slide(p, p.explorer, 40, 250); flushTimers(p); },
    []);
check('a button that opens a menu asks nothing for the workbench\'s tap either',
    typing({ composing: false }), (p) => {
        const more = menuButton(p);
        more.addEventListener('-monaco-gesturetap', () => log.push('menu opens'));
        slide(p, more, 20, 250, '-monaco-gesturetap');
        flushTimers(p);
    },
    ['menu opens']);

let failed = 0;
for (const [name, got, want] of cases) {
    if (util.isDeepStrictEqual(got, want)) {
        console.log(`ok   ${name}`);
    } else {
        failed += 1;
        console.error(`FAIL ${name}\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`);
    }
}
console.log(`\n${cases.length - failed}/${cases.length} passed`);
process.exit(failed ? 1 : 0);
