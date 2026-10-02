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

/** As a browser does: capture from the window down, the target, then back up if it bubbles. */
function dispatch(page, target, init) {
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
 * or with `textarea` the `textarea.inputarea` of the other edit path.
 */
function newPage({ textarea = false, keybindings = false } = {}) {
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
    vm.runInContext(GUARD, vm.createContext({ window: page.window, document: doc, setTimeout }));
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
    for (const fn of timers) fn();
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
check('the keyboard going away after a tap outside text writes nothing',
    typing({ composing: false }), (p) => { touch(p, p.tabs, 100); log = []; keyboardGone(p); },
    []);
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
