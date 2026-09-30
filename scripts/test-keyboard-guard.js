/**
 * Self-check for the keyboard guard's composition finish.
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
 * answering the refocus by changing `inputmode`, which restarts input.
 *
 * This extracts the real script, runs it under `vm` against a small fake DOM
 * that dispatches events through the capture, target and bubble phases, and
 * drives it with keys, taps and compositions. The editor's own handlers are
 * listeners on the editing host and on the gesture targets, so a finish that
 * runs after them, twice, or not at all shows in the order of the log, as does
 * any write to `inputmode`.
 *
 * NEGATIVE CONTROL, measured: against the script as it was before the finish
 * (main at 832754c6) the 15 cases that expect a finish fail and the other 10
 * pass. Dropping `reapplying` from the finish fails the same 15, the tap
 * outside text by a second blur and refocus that writes `inputmode="none"`.
 * A keydown listener in the bubble phase fails every key case, gesture
 * listeners there every gesture case, and dropping the composing,
 * `editContext` or `.monaco-editor` test fails the cases that hold it.
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
}

function run(node, event, capture) {
    for (const l of node.listeners.slice()) {
        if (l.type === event.type && l.capture === capture) l.fn(event);
    }
}

/** As a browser does: capture from the window down, the target, then back up if it bubbles. */
function dispatch(page, target, init) {
    const event = { bubbles: false, ...init, target };
    const outer = [page.window, page.document];
    const ancestors = [];
    for (let n = target.parent; n; n = n.parent) ancestors.unshift(n);
    outer.push(...ancestors);
    for (const n of outer) run(n, event, true);
    run(target, event, true);
    run(target, event, false);
    if (event.bubbles) for (const n of outer.reverse()) run(n, event, false);
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
    get classList() { return { contains: (c) => this.classes.includes(c) }; }
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
function newPage({ textarea = false } = {}) {
    const page = { elements: [], window: new Target('window'), document: new Target('document') };
    const doc = page.document;
    doc.body = new El(page, 'body', 'body', [], null);
    doc.activeElement = doc.body;
    doc.querySelectorAll = (selectors) => page.elements.filter((e) => e.matches(selectors));

    page.tabs = new El(page, 'tabs', 'div', ['tabs-container'], doc.body);
    page.explorer = new El(page, 'explorer', 'div', ['monaco-list-rows'], doc.body);
    const editor = new El(page, 'editor', 'div', ['monaco-editor'], doc.body);
    page.lines = new El(page, 'lines', 'div', ['lines-content'], editor);
    const widget = new El(page, 'widget', 'div', ['suggest-widget'], editor);
    page.suggest = new El(page, 'suggest', 'div', ['monaco-list-rows'], widget);
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

    vm.runInContext(GUARD, vm.createContext({ window: page.window, document: doc }));
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
function typing({ textarea = false, composing = true } = {}) {
    const page = newPage({ textarea });
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
check('a tap on a suggestion, a list inside the editor, the same',
    typing(), (p) => touch(p, p.suggest, 100, '-monaco-gesturetap'),
    finished('suggest -monaco-gesturetap'));
check('a tap on a list outside the editor is left alone',
    typing(), (p) => touch(p, p.explorer, 100, '-monaco-gesturetap'),
    ['explorer -monaco-gesturetap']);
check('after a tap outside text, End finishes the word once and never writes inputmode',
    typing(), (p) => { touch(p, p.tabs, 100); press(p, 'End'); }, finished('editor End'));
check('the textarea edit path, which has no EditContext, is left alone',
    typing({ textarea: true }), (p) => press(p, 'End'), ['editor End']);

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
