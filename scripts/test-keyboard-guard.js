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
 * caret or when the screen turns: Chromium keeps an EditContext composition's
 * range where it was when the page moves the selection (crbug 379170477), so a
 * keyboard that recomposes the word at the new caret writes it over the old
 * range, and a turn of the phone leaves a composing word underlined in the page
 * after the keyboard restarts for the new orientation. What makes the finish
 * work is order and state, which text matching cannot see: the blur and refocus
 * run in the same dispatch as the key, the editor's gesture or an explicit
 * `window.__vscodroidFinishComposition()` and before the editor's own handler,
 * only while the focused EditContext host is composing, and without the guard's
 * own focusin handler answering the refocus by changing `inputmode`, which
 * restarts input. An open suggest list is the exception, because ending a
 * composition makes the editor refilter the list and focus its first item
 * again: Tab accepting a suggestion and a tap on a row are finished after the
 * list has acted, and Up, Down, PageUp and PageDown that move the list's focus
 * not at all.
 * A turn uses a separate entry point: while the same word composes it waits up
 * to one second for the keyboard's own commit, with native input held open too.
 * A same-text compositionstart or textupdate from the keyboard finishes in the
 * next task; changes of text, selection, context or focus cancel that wait.
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
 * typing in a text box with the keyboard up counts as aiming at text, that
 * `window.__vscodroidKeyboardDismissed` ignores a dismissal whose hide report
 * preceded the last tap on text and otherwise waits out a fixed 4 s window
 * after text took focus before writing the hold only if the keyboard is still
 * reported down when that window ends, and
 * that a host blurred and focused again in one task keeps a keyboard that is
 * up, which needs the harness to tell tasks apart: a dispatch from here is a
 * task, and the microtasks queued in it run as it ends.
 *
 * A key that types a character, and Enter, end the word too (cases after the
 * caret keys): the keyboard's own events (key `Unidentified` or `Process`, key
 * code 229), chords with Ctrl, Alt or Meta and other named keys do not, nor does a
 * character while the suggest list is showing, nor anything with no word
 * composing or the focus outside an editor. NEGATIVE CONTROL, measured: against
 * MainActivity.kt before that rule 20 of the 161 cases fail, and each of these
 * breakages fails at least one: every key counting, a character counted in UTF-16
 * units, no key code 229 test, no Ctrl test, no Alt test, no Meta test, Shift
 * refused, isComposing read, the list not asked, a character finished after the
 * keybindings with the list showing, Enter not one of the caret keys, Enter not
 * finished after the list that takes it, a list taking Enter with no row focused,
 * and the key listener in the bubble phase.
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
const MAIN_ACTIVITY_PAGE_SCRIPTS = path.join(
    __dirname, '..', 'android/app/src/main/kotlin/com/vscodroid/MainActivityPageScripts.kt',
);

/**
 * The body of the raw string in `injectKeyboardGuard()`, with the indentation
 * `trimIndent()` removes taken off, which is the text the WebView is given.
 */
function extractGuard(name = 'injectKeyboardGuard', start = true) {
    const isPageScript = name === 'injectKeyboardGuard';
    const lines = fs.readFileSync(isPageScript ? MAIN_ACTIVITY_PAGE_SCRIPTS : MAIN_ACTIVITY, 'utf8').split('\n');
    const declaration = isPageScript ? `internal fun MainActivity.${name}()` : `private fun ${name}()`;
    const fn = lines.findIndex((l) => l.includes(declaration));
    assert.notStrictEqual(
        fn, -1,
        `${name}() is gone from MainActivity.kt, so this check has nothing to ` +
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
    let js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
    if (name === 'finishKeyboardCompositionOnTurn') js = js.replace('$start', String(start));
    // A `$` before a name or `{` is a Kotlin template; before `/` it is literal.
    assert.ok(!/\$[A-Za-z_{]/.test(js),
        'the guard gained a Kotlin interpolation this check does not know how to fill');
    assert.ok(js.includes(name === 'injectKeyboardGuard' ? 'function applyAll()' : '__vscodroidFinishComposition'),
        `the extracted ${name} script does not contain its expected entry point`);
    return js;
}

const GUARD = extractGuard();
const ROTATION = extractGuard('finishKeyboardCompositionOnTurn');
const ROTATION_RETRY = extractGuard('finishKeyboardCompositionOnTurn', false);
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
function newPage({ textarea = false, keybindings = false, terminal = false, imeVisible = true } = {}) {
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

    page.now = 0;
    const WALL_BASE = 1_700_000_000_000;
    const performance = { now: () => page.now };
    page.window.performance = performance;
    const FakeDate = class extends Date {
        constructor(...args) { super(...(args.length ? args : [WALL_BASE + page.now])); }
        static now() { return WALL_BASE + page.now; }
    };
    page.timers = [];
    let nextTimerId = 1;
    const setTimeout = (fn, ms = 0) => {
        const id = nextTimerId++;
        const entry = () => fn();
        entry.id = id;
        entry.delay = Math.max(0, Math.trunc(Number(ms) || 0));
        entry.at = page.now + entry.delay;
        page.timers.push(entry);
        return id;
    };
    const clearTimeout = (id) => {
        const idx = page.timers.findIndex((t) => t.id === id);
        if (idx !== -1) page.timers.splice(idx, 1);
    };
    page.window.setTimeout = setTimeout;
    page.window.clearTimeout = clearTimeout;
    page.microtasks = [];
    page.queueMicrotask = (fn) => { page.microtasks.push(fn); };
    // What ExtraKeyRow reports, and the API the guard hides the keyboard with.
    page.window.__vscodroidImeVisible = imeVisible;
    const navigator = { virtualKeyboard: { hide: () => log.push('hide') } };
    vm.runInContext(GUARD, vm.createContext({
        window: page.window, document: doc, Date: FakeDate, setTimeout, clearTimeout, queueMicrotask: page.queueMicrotask, navigator, performance, KeyboardEvent,
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

/** Advances the clock by `ms` and runs every timer that falls due along the way, in order. */
function advance(page, ms) {
    const end = page.now + ms;
    let steps = 0;
    while (true) {
        let next = -1;
        for (let i = 0; i < page.timers.length; i += 1) {
            if (page.timers[i].at <= end && (next === -1 || page.timers[i].at < page.timers[next].at)) next = i;
        }
        if (next === -1) break;
        assert.ok((steps += 1) <= 100, 'advance spun more than 100 timers without reaching the target time');
        const [timer] = page.timers.splice(next, 1);
        page.now = timer.at;
        task(page, timer);
    }
    page.now = end;
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
    'PageDown', 'Tab', 'Enter', 'Backspace', 'Delete']) {
    check(`${key} while a word composes finishes it before the editor sees the key`,
        typing(), (p) => press(p, key), finished(`editor ${key}`));
}
check('End with no word composing is left alone',
    typing({ composing: false }), (p) => press(p, 'End'), ['editor End']);
check('End after the word was committed is left alone',
    typing(), (p) => { p.host.editContext.end(); press(p, 'End'); }, ['editor End']);
for (const key of ['Unidentified', 'Escape', 'F1', 'Shift']) {
    check(`${key} while a word composes is left alone`,
        typing(), (p) => press(p, key), [`editor ${key}`]);
}

// A key that types a character. A key of the extra key row (`{` is a Shift
// press and then `{` with shiftKey) and a hardware keyboard's reach the page as
// real keys with the character as their `key`, and the page inserts the
// character at the caret while the keyboard still holds its composition over
// the old range: with a tap between the O and the R of `WORD ONE` and `{` from
// the row, the keyboard then wrote its word over that range and left `RDD ONE`,
// which Auto Save wrote. So the word is finished before the editor sees the
// key, as for a caret key, and the keyboard starts again from the text as it is
// after the character.
//
// What does not count matters as much. The keyboard's own events while it
// composes carry key `Unidentified` (`Process` on a desktop) and key code 229,
// and would end the word at every letter. A chord with Ctrl, Alt or Meta types
// nothing, and the row's latched modifiers reach the page as exactly that. A
// named key is not a character. A character typed with the suggest list showing
// is left to the editor, which accepts the focused row on a commit character
// after the key has been dispatched (see the KDoc of injectKeyboardGuard).
//
// Enter is the one named key that does the same damage, measured with a real
// Enter from `adb shell input keyevent` inside `WORD`: the page inserts the
// newline, the keyboard then writes `RD` over its stale range and `RDD ONE` is
// left, newline gone. With the list showing it is bound to the command Tab is
// (acceptSelectedSuggestion), so it is finished like Tab: after the list has
// acted when a row is focused, before otherwise.
for (const [key, mods] of [['{', { shiftKey: true }], ['(', { shiftKey: true }], ['.', {}], ['a', {}],
    ['A', { shiftKey: true }], ['7', {}], [' ', {}], ['é', {}]]) {
    check(`${JSON.stringify(key)}, a key that types a character, while a word composes finishes it before the editor sees the key`,
        typing(), (p) => pressWith(p, key, mods), finished(`editor ${key}`));
}
check('a character outside the BMP, whose key is two UTF-16 units, finishes it too',
    typing(), (p) => { assert.strictEqual('\u{1F600}'.length, 2); pressWith(p, '\u{1F600}', {}); },
    finished('editor \u{1F600}'));
check('a key that types a character counts whether or not it reports isComposing: the flag is not read',
    typing(), (p) => pressWith(p, '{', { shiftKey: true, isComposing: true, keyCode: 219, isTrusted: true }),
    finished('editor {'));
check('after a tap outside text, a key that types a character finishes the word once and never writes inputmode',
    typing(), (p) => { touch(p, p.tabs, 100); pressWith(p, '{', { shiftKey: true }); }, finished('editor {'));
check('two characters in a row finish the word once, the keyboard not having reopened it yet',
    typing(), (p) => { pressWith(p, 'a', {}); pressWith(p, 'b', {}); }, [...finished('editor a'), 'editor b']);
check('a word the keyboard reopens after the key is finished by the next character as well',
    typing(), (p) => { pressWith(p, 'a', {}); p.host.editContext.start(); log = []; pressWith(p, 'b', {}); },
    finished('editor b'));
for (const [name, key, mods] of [
    ['the keyboard\'s own event, key Unidentified with key code 229', 'Unidentified', { keyCode: 229, isComposing: true, isTrusted: true }],
    ['the same on a desktop, key Process', 'Process', { keyCode: 229, isComposing: true }],
    ['a letter an input method reports with key code 229, as desktop ones do', 'a', { keyCode: 229, isComposing: true }],
]) {
    check(`${name}, while a word composes is left alone`,
        typing(), (p) => pressWith(p, key, mods), [`editor ${key}`]);
}
for (const [name, mods] of [['Ctrl', { ctrlKey: true }], ['Alt', { altKey: true }], ['Meta', { metaKey: true }],
    ['Ctrl+Shift', { ctrlKey: true, shiftKey: true }], ['Alt+Shift', { altKey: true, shiftKey: true }]]) {
    check(`${name} with a letter, a chord, while a word composes is left alone`,
        typing(), (p) => pressWith(p, 's', mods), ['editor s']);
}
check('Ctrl with a character that needs Shift is a chord as well',
    typing(), (p) => pressWith(p, '{', { ctrlKey: true, shiftKey: true }), ['editor {']);
check('a chord from the key row, announced with its code and key code and not trusted, is left alone',
    typing(), (p) => pressWith(p, 's', { code: 'KeyS', keyCode: 83, which: 83, ctrlKey: true, isTrusted: false }),
    ['editor s']);
for (const key of ['Control', 'Alt', 'Meta', 'Dead', 'CapsLock', 'Insert', 'F12']) {
    check(`${key} while a word composes is left alone`,
        typing(), (p) => press(p, key), [`editor ${key}`]);
}
check('a key that types a character with no word composing is left alone',
    typing({ composing: false }), (p) => pressWith(p, '{', { shiftKey: true }), ['editor {']);
check('a key that types a character after the word was committed is left alone',
    typing(), (p) => { p.host.editContext.end(); pressWith(p, '{', { shiftKey: true }); }, ['editor {']);
check('a key that types a character on the textarea edit path, which has no EditContext, is left alone',
    typing({ textarea: true }), (p) => pressWith(p, '{', { shiftKey: true }), ['editor {']);
check('a key that types a character in a text box is left alone',
    typing(), (p) => {
        new El(p, 'box', 'input', [], p.document.body).focus();
        log = [];
        pressWith(p, 'a', {});
    }, []);
check('a key that types a character in the terminal is left alone',
    (() => { const p = newPage({ terminal: true }); p.term.focus(); log = []; return p; })(),
    (p) => pressWith(p, 'a', {}), []);
for (const [key, mods] of [['(', { shiftKey: true }], ['.', {}], ['a', {}]]) {
    check(`${key} with the suggest list open leaves the word composing and the list's focused row alone`,
        openList(typing({ keybindings: true }), { focused: 1 }), (p) => {
            pressWith(p, key, mods);
            log.push(`timers ${p.timers.length}`);
        },
        [`editor ${key}`, `keybinding ${key}`, 'timers 0']);
}
check('a key that types a character is finished before the editor while the list is not showing',
    typing({ keybindings: true }), (p) => pressWith(p, '(', { shiftKey: true }),
    ['blur host', 'focus host', 'editor (', 'keybinding (']);
check('the list closing hands the word back: the next character finishes it',
    openList(typing({ keybindings: true }), { focused: 1 }), (p) => {
        pressWith(p, '(', { shiftKey: true });
        p.widget.classList.remove('visible');
        log = [];
        pressWith(p, 'a', {});
    }, ['blur host', 'focus host', 'editor a', 'keybinding a']);
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
check('Enter the open list takes finishes the word after the keybinding has run, as Tab does',
    openList(typing({ keybindings: true }), { focused: 1 }), (p) => press(p, 'Enter'),
    ['editor Enter', 'keybinding Enter', 'blur host', 'focus host']);
check('Enter with the list open but nothing focused finishes before, as a caret key',
    openList(typing({ keybindings: true }), { focused: -1 }), (p) => press(p, 'Enter'),
    ['blur host', 'focus host', 'editor Enter', 'keybinding Enter']);
check('Shift+Enter with the list open is not the list\'s Enter, so it finishes before',
    openList(typing({ keybindings: true }), { focused: 1 }), (p) => pressWith(p, 'Enter', { shiftKey: true }),
    ['blur host', 'focus host', 'editor Enter', 'keybinding Enter']);
check('Enter with no word composing is left alone, with the list open too',
    openList(typing({ keybindings: true, composing: false }), { focused: 1 }), (p) => {
        press(p, 'Enter');
        log.push(`timers ${p.timers.length}`);
    },
    ['editor Enter', 'keybinding Enter', 'timers 0']);
check('Enter the list takes with the key stopped before window: the timeout finishes it, once',
    openList(typing({ keybindings: true }), { focused: 1 }), (p) => {
        p.editor.addEventListener('keydown', (e) => { log.push(`container ${e.key}`); e.stopPropagation(); });
        press(p, 'Enter');
        log.push('-- timeout');
        flushTimers(p);
    },
    ['editor Enter', 'container Enter', '-- timeout', 'blur host', 'focus host']);
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
check('an explicit finish of a composing word never writes inputmode',
    typing(), (p) => {
        touch(p, p.tabs, 100);
        p.window.__vscodroidImeVisible = false;
        log = [];
        task(p, () => { p.window.__vscodroidFinishComposition(); });
    }, ['blur host', 'focus host']);
check('a window resize event alone does not end a composing word',
    typing(), (p) => {
        dispatch(p, p.window, { type: 'resize' });
    }, []);
check('an explicit finish with no word composing does nothing',
    typing({ composing: false }), (p) => {
        task(p, () => { p.window.__vscodroidFinishComposition(); });
    }, []);
check('an explicit finish while the keyboard stays up leaves inputmode alone',
    typing(), (p) => {
        p.window.__vscodroidImeVisible = true;
        task(p, () => { p.window.__vscodroidFinishComposition(); });
    }, ['blur host', 'focus host']);
check('an explicit finish while a suggest row has focus does not blur the editor host',
    openList(typing()), (p) => {
        p.rows[0].focus();
        log = [];
        task(p, () => { p.window.__vscodroidFinishComposition(); });
    }, []);
check('a word composed after a screen turn finishes on the next caret key',
    typing(), (p) => {
        task(p, () => { p.window.__vscodroidFinishComposition(); });
        p.host.editContext.start();
        log = [];
        press(p, 'ArrowRight');
    }, ['blur host', 'focus host', 'editor ArrowRight']);
check('calling __vscodroidFinishComposition twice in a row blurs and focuses once',
    typing(), (p) => {
        task(p, () => {
            p.window.__vscodroidFinishComposition();
            p.window.__vscodroidFinishComposition();
        });
    }, ['blur host', 'focus host']);

function rotationVerdict(page, start = true) {
    return vm.runInNewContext(start ? ROTATION : ROTATION_RETRY, { window: page.window, document: page.document });
}

check('rotation waits for the keyboard commit before either input connection is finished',
    typing(), (p) => { log.push(`turn ${rotationVerdict(p)}`); }, ['turn wait']);

check('rotation releases native input after finishing an editable host',
    typing(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 1000);
        assert.strictEqual(rotationVerdict(p, false), true);
    }, ['blur host', 'focus host']);
check('rotation refreshes a host whose old composition already ended',
    typing({ composing: false }), (p) => { assert.strictEqual(rotationVerdict(p), true); }, []);
check('rotation before the keyboard guard loads leaves native input alone',
    typing(), (p) => {
        delete p.window.__vscodroidFinishCompositionOnTurn;
        assert.strictEqual(rotationVerdict(p), false);
    }, []);
check('rotation ignores a nonfunction composition hook',
    typing(), (p) => {
        p.window.__vscodroidFinishCompositionOnTurn = {};
        assert.strictEqual(rotationVerdict(p), false);
    }, []);
check('rotation leaves the textarea input path alone',
    typing({ textarea: true }), (p) => { assert.strictEqual(rotationVerdict(p), false); }, []);
check('rotation leaves a held keyboard down after finishing composition',
    typing(), (p) => {
        p.window.__vscodroidImeVisible = false;
        p.host.attrs.set('inputmode', 'none');
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 1000);
        assert.strictEqual(rotationVerdict(p, false), false);
    }, ['blur host', 'focus host']);
// What the app is told on a turn decides how long a keyboard put away afterwards stays the turn's. Only a
// plain text box is answered 'none', for which no refocus is ever made; every other no, the editor's
// textarea, a focus on a list row, a box that raises no keyboard, stays a plain false.
function plainBox(p, tag, classes, props = {}) {
    const box = new El(p, 'box', tag, classes, p.explorer);
    Object.assign(box, props);
    box.focus();
    log = [];
    return box;
}
check('rotation with the focus in the Quick Open input answers none',
    typing({ composing: false }), (p) => { plainBox(p, 'input', ['input']); assert.strictEqual(rotationVerdict(p), 'none'); }, []);
check('rotation with the focus in the find box, a textarea, answers none',
    typing({ composing: false }), (p) => { plainBox(p, 'textarea', ['input', 'mirror']); assert.strictEqual(rotationVerdict(p), 'none'); }, []);
check('rotation with the focus in a content editable answers none',
    typing({ composing: false }), (p) => { plainBox(p, 'div', ['rename'], { isContentEditable: true }); assert.strictEqual(rotationVerdict(p), 'none'); }, []);
check('rotation with the focus on an input that takes no text, a checkbox, answers false',
    typing({ composing: false }), (p) => { plainBox(p, 'input', ['checkbox'], { selectionStart: null }); assert.strictEqual(rotationVerdict(p), false); }, []);
check('rotation with the focus on a row of a list answers false',
    typing({ composing: false }), (p) => { plainBox(p, 'div', ['monaco-list-row']); assert.strictEqual(rotationVerdict(p), false); }, []);
check('rotation with the focus in the editor textarea answers false, not none',
    typing({ textarea: true, composing: false }), (p) => { assert.strictEqual(rotationVerdict(p), false); }, []);
check('rotation with focus on a suggest row leaves native input alone',
    openList(typing()), (p) => {
        p.rows[0].focus();
        log = [];
        assert.strictEqual(rotationVerdict(p), false);
    }, []);
check('rotation ignores a host that loses focus during composition recovery',
    typing(), (p) => {
        p.host.blur = () => { p.document.activeElement = p.document.body; };
        p.host.focus = () => {};
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 1000);
        assert.strictEqual(rotationVerdict(p, false), false);
    }, []);
check('rotation after a drag outside text leaves native input alone, which would hold the host',
    typing({ composing: false }), (p) => {
        drag(p, p.explorer);
        flushTimers(p);
        log = [];
        assert.strictEqual(rotationVerdict(p), false);
    }, []);
check('rotation after a tap on text again releases native input',
    typing({ composing: false }), (p) => {
        drag(p, p.explorer);
        flushTimers(p);
        touch(p, p.lines, 100, '-monaco-gesturetap');
        log = [];
        assert.strictEqual(rotationVerdict(p), true);
    }, []);

function wordPage() {
    const p = typing();
    const c = p.host.editContext;
    c.text = 'line two';
    c.selectionStart = c.selectionEnd = 8;
    return p;
}

// A composing keyboard replaces its open word at commit; once blur has ended
// that composition its same commit is an insertion at the selection instead.
function commitWord(p) {
    const c = p.host.editContext;
    const start = c.composing ? 5 : c.selectionStart;
    const end = c.composing ? 8 : c.selectionEnd;
    c.text = c.text.slice(0, start) + 'two' + c.text.slice(end);
    c.selectionStart = c.selectionEnd = start + 3;
    c.fire('textupdate');
    log.push(`commit ${start}..${end} ${c.text}`);
    c.end();
}

for (const ms of [0, 12, 61, 180, 294, 515, 999]) {
    check(`a keyboard commit at ${ms} ms replaces the open word before native recovery`,
        wordPage(), (p) => {
            assert.strictEqual(rotationVerdict(p), 'wait');
            advance(p, ms);
            commitWord(p);
            assert.strictEqual(rotationVerdict(p, false), true);
            assert.strictEqual(p.host.editContext.text, 'line two');
            assert.strictEqual(rotationVerdict(p, false), false, 'a completed turn is consumed');
        }, ['commit 5..8 line two', 'blur host', 'focus host']);
}
check('no keyboard commit: neither connection finishes before the bound, both may finish at it',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 999);
        assert.strictEqual(rotationVerdict(p, false), 'wait');
        advance(p, 1);
        assert.strictEqual(rotationVerdict(p, false), true);
        assert.strictEqual(p.host.editContext.composing, false);
    }, ['blur host', 'focus host']);
check('a commit at the bound, dispatched before the poll, still replaces the word',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 1000);
        commitWord(p);
        assert.strictEqual(rotationVerdict(p, false), true);
    }, ['commit 5..8 line two', 'blur host', 'focus host']);
check('limit: a commit after the fallback can still insert a second copy',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 1000);
        assert.strictEqual(rotationVerdict(p, false), true);
        advance(p, 1);
        commitWord(p);
        assert.strictEqual(p.host.editContext.text, 'line twotwo');
    }, ['blur host', 'focus host', 'commit 8..8 line twotwo']);
check('a natural composition ending is followed by the page refresh and native eligibility',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        p.host.editContext.end();
        assert.strictEqual(rotationVerdict(p, false), true);
    }, ['blur host', 'focus host']);
check('after a drag outside text, a natural commit still gets the page refresh, without native refocus',
    wordPage(), (p) => {
        drag(p, p.explorer);
        flushTimers(p);
        log = [];
        assert.strictEqual(rotationVerdict(p), 'wait');
        commitWord(p);
        assert.strictEqual(rotationVerdict(p, false), false);
    }, ['commit 5..8 line two', 'blur host', 'focus host']);
check('typing during the wait cancels the turn instead of ending the changed word',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        const c = p.host.editContext;
        c.text = 'line twox';
        c.selectionStart = c.selectionEnd = 9;
        c.fire('textupdate');
        advance(p, 1000);
        assert.strictEqual(rotationVerdict(p, false), false);
        assert.strictEqual(c.composing, true);
    }, []);
check('typing and deleting back to the old word between polls still cancels the turn',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        const c = p.host.editContext;
        c.text += 'x';
        c.fire('textupdate');
        c.text = 'line two';
        c.fire('textupdate');
        advance(p, 1000);
        assert.strictEqual(rotationVerdict(p, false), false);
    }, []);
check('a buffer rewrite without a textupdate event cancels pending recovery',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        p.host.editContext.text = 'other text';
        advance(p, 1000);
        assert.strictEqual(rotationVerdict(p, false), false);
    }, []);
check('a moved selection cancels pending recovery',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        p.host.editContext.selectionStart = p.host.editContext.selectionEnd = 6;
        advance(p, 1000);
        assert.strictEqual(rotationVerdict(p, false), false);
    }, []);
check('a changed EditContext cancels pending recovery',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        p.host.editContext = new FakeEditContext('replacement');
        advance(p, 1000);
        assert.strictEqual(rotationVerdict(p, false), false);
    }, []);
check('a keyboard commit during the wait finishes the host in the next task, after commit and compositionend',
    wordPage(), (p) => {
        log.push(`start ${rotationVerdict(p)}`);
        advance(p, 12);
        task(p, () => commitWord(p));
        log.push(`mid ${log.length}`);
        advance(p, 0);
        log.push('next-task');
        log.push(`poll ${rotationVerdict(p, false)}`, `again ${rotationVerdict(p, false)}`);
    }, ['start wait', 'commit 5..8 line two', 'mid 2', 'blur host', 'focus host', 'next-task', 'poll true', 'again false']);
check('a same-text composition reopened by the keyboard during the wait finishes in the next task and keeps native recovery',
    wordPage(), (p) => {
        log.push(`start ${rotationVerdict(p)}`);
        advance(p, 100);
        p.host.editContext.end();
        advance(p, 323);
        task(p, () => {
            p.host.editContext.start();
            p.host.editContext.fire('textupdate');
        });
        log.push(`in-task composing ${p.host.editContext.composing}`);
        advance(p, 0);
        log.push(`after-task composing ${p.host.editContext.composing}`,
            `poll ${rotationVerdict(p, false)}`, `again ${rotationVerdict(p, false)}`);
    }, ['start wait', 'in-task composing true', 'blur host', 'focus host',
        'after-task composing false', 'poll true', 'again false']);
check('after a drag outside text, a same-text composition reopened during the wait refreshes the host without native refocus',
    wordPage(), (p) => {
        drag(p, p.explorer);
        flushTimers(p);
        log = [];
        log.push(`start ${rotationVerdict(p)}`);
        p.host.editContext.end();
        task(p, () => {
            p.host.editContext.start();
            p.host.editContext.fire('textupdate');
        });
        advance(p, 0);
        log.push(`composing ${p.host.editContext.composing}`,
            `poll ${rotationVerdict(p, false)}`);
    }, ['start wait', 'blur host', 'focus host', 'composing false', 'poll false']);
check('a bare compositionstart with unchanged text during the wait finishes in the next task instead of cancelling',
    wordPage(), (p) => {
        log.push(`start ${rotationVerdict(p)}`);
        p.host.editContext.end();
        p.host.editContext.start();
        advance(p, 0);
        log.push(`composing ${p.host.editContext.composing}`, `poll ${rotationVerdict(p, false)}`);
    }, ['start wait', 'blur host', 'focus host', 'composing false', 'poll true']);
check('a same-text textupdate without compositionend during the wait finishes in the next task without waiting out the second',
    wordPage(), (p) => {
        log.push(`start ${rotationVerdict(p)}`);
        advance(p, 200);
        p.host.editContext.fire('textupdate');
        advance(p, 0);
        log.push(`composing ${p.host.editContext.composing}`, `poll ${rotationVerdict(p, false)}`);
    }, ['start wait', 'blur host', 'focus host', 'composing false', 'poll true']);
check('a fresh composition typed by the person after the old one ends is left alone by the turn',
    wordPage(), (p) => {
        log.push(`start ${rotationVerdict(p)}`);
        p.host.editContext.end();
        task(p, () => {
            const c = p.host.editContext;
            c.start();
            c.text = 'line twox';
            c.selectionStart = c.selectionEnd = 9;
            c.fire('textupdate');
        });
        advance(p, 1000);
        log.push(`poll ${rotationVerdict(p, false)}`,
            `composing ${p.host.editContext.composing}`, `text ${p.host.editContext.text}`);
    }, ['start wait', 'poll false', 'composing true', 'text line twox']);
check('pagehide clears a pending turn finish timer',
    wordPage(), (p) => {
        log.push(`start ${rotationVerdict(p)}`);
        p.host.editContext.fire('textupdate');
        log.push(`before ${p.timers.length}`);
        dispatch(p, p.window, { type: 'pagehide' });
        log.push(`after ${p.timers.length}`);
        advance(p, 0);
        log.push(`poll ${rotationVerdict(p, false)}`);
    }, ['start wait', 'before 2', 'after 0', 'poll false']);
check('focus leaving and returning between polls still cancels the turn',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        p.host.blur();
        p.host.focus();
        log = [];
        advance(p, 1000);
        assert.strictEqual(rotationVerdict(p, false), false);
    }, []);
check('a caret key during the wait still finishes immediately, and cancels pending native recovery',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        press(p, 'ArrowLeft');
        assert.strictEqual(rotationVerdict(p, false), false);
    }, finished('editor ArrowLeft'));
check('an editor tap during the wait still finishes immediately',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        touch(p, p.lines, 100, '-monaco-gesturetap');
        assert.strictEqual(rotationVerdict(p, false), false);
    }, finished('lines -monaco-gesturetap'));
check('a second turn starts a new bounded wait',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 600);
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 600);
        assert.strictEqual(rotationVerdict(p, false), 'wait');
        advance(p, 400);
        assert.strictEqual(rotationVerdict(p, false), true);
    }, ['blur host', 'focus host']);
// A keyboard that reopens the same word, in a task of its own and with the text
// and the caret as they were, every time the page ends its composition by a
// refocus. Nobody polls: the activity's chain stopped after a "wait".
function reopeningKeyboard(page, afterMs) {
    const host = page.host;
    const focus = host.focus.bind(host);
    host.focus = () => {
        const was = page.document.activeElement;
        focus();
        if (was === host) return;
        const reopen = () => {
            const c = host.editContext;
            if (page.document.activeElement !== host || c.composing) return;
            c.start();
            c.fire('textupdate');
        };
        reopen.id = -1;
        reopen.at = page.now + afterMs;
        page.timers.push(reopen);
    };
}
const refocusesIn = () => log.filter((entry) => entry === 'blur host').length;
function reopenAt(p, ms) {
    advance(p, ms);
    task(p, () => {
        p.host.editContext.start();
        p.host.editContext.fire('textupdate');
    });
}
check('a keyboard that reopens its word after every refocus, with nobody polling, gets one refocus and the reopen is left alone',
    wordPage(), (p) => {
        reopeningKeyboard(p, 100);
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 94);
        p.host.editContext.end();
        reopenAt(p, 175);
        for (let i = 0; i < 20; i += 1) advance(p, 500);
        log = [`refocuses ${refocusesIn()}`, `composing ${p.host.editContext.composing}`];
    }, ['refocuses 1', 'composing true']);
check('the turn ends by its own clock when nobody polls: a reopen after it is left alone and a late poll finds nothing',
    wordPage(), (p) => {
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 6000);
        reopenAt(p, 0);
        advance(p, 0);
        log.push(`poll ${rotationVerdict(p, false)}`, `composing ${p.host.editContext.composing}`);
        log = [`refocuses ${refocusesIn()}`, ...log.filter((entry) => entry !== 'blur host' && entry !== 'focus host')];
    }, ['refocuses 0', 'poll false', 'composing true']);
check('a poll that arrives after the refocus still ends a word the keyboard reopened and answers true once',
    wordPage(), (p) => {
        reopeningKeyboard(p, 100);
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 94);
        p.host.editContext.end();
        reopenAt(p, 175);
        advance(p, 150);
        log.push(`composing ${p.host.editContext.composing}`);
        log.push(`poll ${rotationVerdict(p, false)}`, `again ${rotationVerdict(p, false)}`);
        advance(p, 500);
        log = [`refocuses ${refocusesIn()}`, ...log.filter((entry) => entry !== 'blur host' && entry !== 'focus host'),
            `composing ${p.host.editContext.composing}`];
    }, ['refocuses 2', 'composing true', 'poll true', 'again false', 'composing true']);
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

const wallAt = (ms) => 1_700_000_000_000 + ms;

/** What Kotlin runs when the soft keyboard goes away. */
function keyboardGone(p, after = 4500, byPerson, gestureAtMs) {
    p.now += after;
    const hook = p.window.__vscodroidKeyboardDismissed;
    if (typeof hook !== 'function') log.push('no dismissal hook');
    else hook(byPerson, gestureAtMs);
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
/**
 * A touch that moves a few pixels and is lifted soon, as a caret drag of a character does.
 * `dragged` is the mark injectCaretDrag leaves on the touch it takes.
 */
function nudge(p, target, { dragged }) {
    const at = { bubbles: true, pointerId: 1, clientY: 20 };
    dispatch(p, target, { ...at, type: 'pointerdown', clientX: 20, timeStamp: 1000 });
    if (dragged) p.window.__vscodroidCaretDragged = true;
    dispatch(p, target, { ...at, type: 'pointerup', clientX: 29, timeStamp: 1100 });
}

check('a short caret drag, which travels no further than a tap, leaves a keyboard held down',
    typing({ composing: false }), (p) => { keyboardGone(p); log = []; nudge(p, p.lines, { dragged: true }); inputmode(p); },
    ['inputmode none']);
check('the same short touch, not taken as a caret drag, is a tap and lets the keyboard up',
    typing({ composing: false }), (p) => { keyboardGone(p); log = []; nudge(p, p.lines, { dragged: false }); },
    ['host -inputmode', 'blur host', 'focus host']);
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
function newFile(p, name = 'file') {
    const editor = new El(p, 'other', 'div', ['monaco-editor'], p.document.body);
    const host = new El(p, name, 'div', ['native-edit-context'], editor);
    host.editContext = new FakeEditContext(`${name}Context`);
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
// A file not open yet can also finish loading and focus its new host before
// the half-second dismissal timer runs, while the keyboard is still down from
// the gap or not yet reported back up. For a fixed 4 s window after a text
// field took focus, a dismissal report waits for the window's end and writes
// the hold only if the keyboard is still reported down then, even if Kotlin
// briefly reported the keyboard visible before a hide inside that window; an
// incoming visible report clears hiddenAt and cancels any pending late check so
// it does not fire stale, and a dismissal whose hide report preceded the last
// tap on text is void both inside and outside the window.
//
// NEGATIVE CONTROL: cases covering the fixed 4 s window, visible=true
// bounce recovery inside the window, and pre-tap-hide exclusion verify each
// guard condition against premature holds.
function keepLog(make) {
    const saved = [...log];
    log = [];
    const out = make();
    log = saved;
    return out;
}
function inWindowEditorOpen() {
    const p = paletteFromMenu();
    typeInto(p, p.palette);
    p.host.focus();
    p.host.blur();
    p.window.__vscodroidImeVisible = false;
    p.now = 1000;
    const file = newFile(p);
    file.focus();
    log = [];
    return { p, file };
}
check('the dismissal hook called 500 ms after an editor host took focus leaves it unheld when up again and holds it at 4 s when still down',
    inWindowEditorOpen(), ({ p, file }) => {
        keyboardGone(p, 500);
        log.push(`at 500`);
        inputmode(p, file);
        p.window.__vscodroidImeVisible = true;
        advance(p, 3500);
        log.push(`up at 4000`);
        inputmode(p, file);

        const { p: q, file: file2 } = keepLog(inWindowEditorOpen);
        keyboardGone(q, 500);
        advance(q, 3499);
        log.push(`down at 3999`);
        inputmode(q, file2);
        advance(q, 1);
        log.push(`down at 4000`);
        inputmode(q, file2);
    },
    ['at 500', 'inputmode null', 'up at 4000', 'inputmode null',
        'down at 3999', 'inputmode null',
        'host inputmode=none', 'file inputmode=none', 'down at 4000', 'inputmode none']);
check('the dismissal hook called 500 ms after a text box took focus leaves it unheld when up again and holds at 4 s when still down',
    typing({ composing: false }), (p) => {
        const setupBox = (q) => {
            q.now = 3000;
            const box = new El(q, 'box', 'input', [], q.document.body);
            box.focus();
            q.window.__vscodroidImeVisible = false;
            return q;
        };
        setupBox(p);
        log = [];
        keyboardGone(p, 500);
        log.push(`at 500`);
        inputmode(p);
        p.window.__vscodroidImeVisible = true;
        advance(p, 3500);
        log.push(`up at 4000`);
        inputmode(p);

        const q = keepLog(() => setupBox(typing({ composing: false })));
        keyboardGone(q, 500);
        advance(q, 3500);
        log.push(`down at 4000`);
        inputmode(q);
    },
    ['at 500', 'inputmode null', 'up at 4000', 'inputmode null',
        'host inputmode=none', 'down at 4000', 'inputmode none']);
check('the dismissal hook called 500 ms after a text field took focus at time 0 leaves it unheld when up again and holds at 4 s when still down',
    typing({ composing: false }), (p) => {
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500);
        log.push(`at 500`);
        inputmode(p);
        p.window.__vscodroidImeVisible = true;
        advance(p, 3500);
        log.push(`up at 4000`);
        inputmode(p);

        const q = keepLog(() => typing({ composing: false }));
        q.window.__vscodroidImeVisible = false;
        keyboardGone(q, 500);
        advance(q, 3500);
        log.push(`down at 4000`);
        inputmode(q);
    },
    ['at 500', 'inputmode null', 'up at 4000', 'inputmode null',
        'host inputmode=none', 'down at 4000', 'inputmode none']);
check('the dismissal hook called 3999 ms after a text field took focus waits 1 ms and holds only if still down',
    inWindowEditorOpen(), ({ p, file }) => {
        keyboardGone(p, 3999);
        log.push(`at 3999`);
        inputmode(p, file);
        p.window.__vscodroidImeVisible = true;
        advance(p, 1);
        log.push(`up at 4000`);
        inputmode(p, file);

        const { p: q, file: file2 } = keepLog(inWindowEditorOpen);
        keyboardGone(q, 3999);
        advance(q, 1);
        log.push(`down at 4000`);
        inputmode(q, file2);
    },
    ['at 3999', 'inputmode null', 'up at 4000', 'inputmode null',
        'host inputmode=none', 'file inputmode=none', 'down at 4000', 'inputmode none']);
check('the dismissal hook called 4000 ms after a text field took focus writes the hold',
    paletteFromMenu(), (p) => {
        typeInto(p, p.palette);
        p.now = 1000;
        const file = newFile(p);
        file.focus();
        log = [];
        keyboardGone(p, 4000);
        inputmode(p, file);
    },
    ['host inputmode=none', 'file inputmode=none', 'inputmode none']);
check('the dismissal hook called 4500 ms after a text field took focus writes the hold as today',
    paletteFromMenu(), (p) => {
        typeInto(p, p.palette);
        p.now = 1000;
        const file = newFile(p);
        file.focus();
        log = [];
        keyboardGone(p, 4500);
        inputmode(p, file);
    },
    ['host inputmode=none', 'file inputmode=none', 'inputmode none']);
check('a slow rise reaching the screen at 3500 ms leaves an inside-window dismissal unheld under the 4 s window',
    inWindowEditorOpen(), ({ p, file }) => {
        keyboardGone(p, 2500);
        log.push(`at 2500: timers ${p.timers.length}`);
        inputmode(p, file);
        advance(p, 500);
        log.push(`at 3000: timers ${p.timers.length}`);
        inputmode(p, file);
        advance(p, 500);
        p.window.__vscodroidImeVisible = true;
        log.push(`at 3500: timers ${p.timers.length}`);
        advance(p, 500);
        log.push(`at 4000: timers ${p.timers.length}`);
        inputmode(p, file);
    },
    ['at 2500: timers 1', 'inputmode null',
        'at 3000: timers 1', 'inputmode null',
        'at 3500: timers 0', 'at 4000: timers 0', 'inputmode null']);
check('the dismissal hook called with focus on the page 500 ms after a text field had focus holds nothing and keeps aimedAtText',
    paletteFromMenu(), (p) => {
        typeInto(p, p.palette);
        p.now = 1000;
        p.host.focus();
        p.host.blur();
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500);
        log = [];
        inputmode(p);
        const file = newFile(p);
        file.focus();
        inputmode(p, file);
    },
    ['inputmode null', 'focus file', 'file -inputmode', 'inputmode null']);
check('before any text field has focused, the dismissal hook at 500 ms still writes the hold',
    newPage(), (p) => {
        p.host.attrs.delete('inputmode');
        log = [];
        keyboardGone(p, 500);
        inputmode(p);
    },
    ['host inputmode=none', 'inputmode none']);
check('a second focus inside the wait extends the window and decides at the new window end',
    inWindowEditorOpen(), ({ p }) => {
        keyboardGone(p, 500);
        advance(p, 700);
        const second = newFile(p, 'second');
        second.focus();
        log = [];
        advance(p, 2800);
        log.push(`first end: timers ${p.timers.length}`);
        inputmode(p, second);
        advance(p, 1200);
        log.push(`second end down`);
        inputmode(p, second);

        const { p: q } = keepLog(inWindowEditorOpen);
        keyboardGone(q, 500);
        advance(q, 700);
        const secondUp = keepLog(() => {
            const h = newFile(q, 'second');
            h.focus();
            return h;
        });
        advance(q, 2800);
        q.window.__vscodroidImeVisible = true;
        advance(q, 1200);
        log.push(`second end up`);
        inputmode(q, secondUp);
    },
    ['first end: timers 1', 'inputmode null',
        'host inputmode=none', 'file inputmode=none', 'second inputmode=none',
        'second end down', 'inputmode none',
        'second end up', 'inputmode null']);
check('a second dismissal report inside the wait replaces the pending check rather than stacking one',
    inWindowEditorOpen(), ({ p, file }) => {
        keyboardGone(p, 500);
        log.push(`after first: timers ${p.timers.length}`);
        keyboardGone(p, 500);
        log.push(`after second: timers ${p.timers.length}`);
        advance(p, 3000);
        log.push(`at end: timers ${p.timers.length}`);
        inputmode(p, file);
    },
    ['after first: timers 1', 'after second: timers 1',
        'host inputmode=none', 'file inputmode=none', 'at end: timers 0', 'inputmode none']);
check('a keyboard reported visible inside the 4 s window does not end the wait early; a later dismissal still waits until 4 s and holds if still down',
    inWindowEditorOpen(), ({ p, file }) => {
        keyboardGone(p, 500);
        advance(p, 200);
        p.window.__vscodroidImeVisible = true;
        log.push(`at visible: timers ${p.timers.length}`);
        advance(p, 200);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500);
        log.push(`at 1400: timers ${p.timers.length}`);
        inputmode(p, file);
        advance(p, 1100);
        log.push(`at 2500: timers ${p.timers.length}`);
        inputmode(p, file);
        advance(p, 1500);
        log.push(`at 4000: timers ${p.timers.length}`);
        inputmode(p, file);
    },
    ['at visible: timers 0', 'at 1400: timers 1', 'inputmode null',
        'at 2500: timers 1', 'inputmode null',
        'host inputmode=none', 'file inputmode=none',
        'at 4000: timers 0', 'inputmode none']);
check('a dismissal after an early visible bounce inside 4 s still waits out the focus window when the keyboard returns',
    paletteFromMenu(), (p) => {
        typeInto(p, p.palette);
        p.window.__vscodroidImeVisible = false;
        p.now = 1000;
        const file = newFile(p);
        file.focus();
        log = [];
        advance(p, 252);
        p.window.__vscodroidImeVisible = true;
        advance(p, 162);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 824);
        log.push(`at 1238: timers ${p.timers.length}`);
        inputmode(p, file);
        advance(p, 1);
        p.window.__vscodroidImeVisible = true;
        advance(p, 2761);
        log.push(`at 4000: timers ${p.timers.length}`);
        inputmode(p, file);
    },
    ['at 1238: timers 1', 'inputmode null',
        'at 4000: timers 0', 'inputmode null']);
check('a tap on text after an early visible bounce and dismissal inside 4 s keeps the keyboard unheld when visibility returns',
    paletteFromMenu(), (p) => {
        typeInto(p, p.palette);
        p.window.__vscodroidImeVisible = false;
        p.now = 1000;
        const file = newFile(p);
        file.focus();
        log = [];
        advance(p, 300);
        p.window.__vscodroidImeVisible = true;
        advance(p, 300);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500);
        log.push(`at 1100: timers ${p.timers.length}`);
        advance(p, 4);
        p.window.__vscodroidImeVisible = true;
        touch(p, p.lines, 100);
        advance(p, 3000);
        log.push(`after 4000: timers ${p.timers.length}`);
        inputmode(p, file);
    },
    ['at 1100: timers 1', 'host -inputmode', 'file -inputmode',
        'after 4000: timers 0', 'inputmode null']);
check('a dismissal callback arriving inside the wait from a hide reported before a tap on text is void',
    inWindowEditorOpen(), ({ p, file }) => {
        keyboardGone(p, 209);
        advance(p, 300);
        p.window.__vscodroidImeVisible = true;
        advance(p, 776);
        p.window.__vscodroidImeVisible = false;
        advance(p, 282);
        touch(p, p.lines, 100);
        log = [];
        keyboardGone(p, 241);
        log.push(`at 1808: timers ${p.timers.length}`);
        inputmode(p, file);
        advance(p, 1000);
        log.push(`after limit`);
        inputmode(p, file);
    },
    ['at 1808: timers 0', 'inputmode null',
        'after limit', 'inputmode null']);
check('a dismissal callback arriving outside the 4 s limit from a hide reported before a tap on text is void, while a later hide still holds',
    typing({ composing: false }), (p) => {
        advance(p, 5000);
        p.window.__vscodroidImeVisible = false;
        advance(p, 300);
        touch(p, p.lines, 100);
        log = [];
        keyboardGone(p, 200);
        log.push(`stale hide`);
        inputmode(p);
        p.window.__vscodroidImeVisible = true;
        advance(p, 200);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500);
        log.push(`later hide`);
        inputmode(p);
    },
    ['stale hide', 'inputmode null',
        'host inputmode=none', 'later hide', 'inputmode none']);
check('a hide already present when the guard loads is also void once a tap on text answers it',
    newPage({ imeVisible: false }), (p) => {
        p.host.focus();
        advance(p, 3000);
        touch(p, p.lines, 100);
        log = [];
        keyboardGone(p, 500);
        inputmode(p);
    },
    ['inputmode null']);
check('before Kotlin has reported keyboard visibility, the late check at the window end does nothing',
    inWindowEditorOpen(), ({ p, file }) => {
        delete p.window.__vscodroidImeVisible;
        keyboardGone(p, 500);
        log.push(`pending ${p.timers.length}`);
        advance(p, 3500);
        log.push(`after ${p.timers.length}`);
        inputmode(p, file);
    },
    ['pending 1', 'after 0', 'inputmode null']);
check('pagehide clears a pending late check so nothing is left queued when the page goes away',
    inWindowEditorOpen(), ({ p, file }) => {
        keyboardGone(p, 500);
        log.push(`before ${p.timers.length}`);
        dispatch(p, p.window, { type: 'pagehide' });
        log.push(`after ${p.timers.length}`);
        advance(p, 3500);
        inputmode(p, file);
    },
    ['before 1', 'after 0', 'inputmode null']);
check('a tap on text inside the wait clears a pending late check before visibility is reported up',
    typing({ composing: false }), (p) => {
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500);
        log.push(`before ${p.timers.length}`);
        advance(p, 3400);
        touch(p, p.lines, 100);
        log.push(`after ${p.timers.length}`);
        advance(p, 100);
        inputmode(p);
    },
    ['before 1', 'host -inputmode', 'after 0', 'inputmode null']);
check('fractional performance.now timestamps round the wait up so the late check runs in one timer',
    paletteFromMenu(), (p) => {
        typeInto(p, p.palette);
        p.window.__vscodroidImeVisible = false;
        p.now = 1000.7;
        const file = newFile(p);
        file.focus();
        log = [];
        keyboardGone(p, 499.6);
        advance(p, 3500);
        log.push(`at 3500: timers ${p.timers.length}`);
        advance(p, 1);
        log.push(`at 3501: timers ${p.timers.length}`);
        inputmode(p, file);
    },
    ['at 3500: timers 1', 'host inputmode=none', 'file inputmode=none',
        'at 3501: timers 0', 'inputmode none']);
check('a hold put on at focus is cleared when the keyboard is reported down inside the 4 s window',
    typing({ composing: false }), (p) => {
        drag(p, p.explorer);
        const file = newFile(p);
        restartsAt(p, 'ArrowDown', file);
        p.now = 1000;
        file.focus();
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500);
        p.window.__vscodroidImeVisible = true;
        const palette = new El(p, 'palette', 'input', [], p.document.body);
        palette.focus();
        typeInto(p, palette);
        file.focus();
        advance(p, 4500);
        drag(p, p.explorer);
        log = [];
        press(p, 'ArrowDown');
        inputmode(p, file);
    },
    ['blur file', 'focus file', 'inputmode null']);

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

// The page's side of the HeliBoard runs that lost the keyboard: the dismissal hook, called 1.2, 1.9 and 2.4 s after the focus the turn gave the editor host,
// waits for the end of the 4 s and then holds a keyboard that is still reported down. That is the guard doing what it is written to do for a keyboard put away; the fault was the call, which `KeyboardDismissal` made for a report that was the turn's (its own test holds that the
// call is no longer made inside the turn's window).
check('the dismissal hook called 1.2, 1.9 and 2.4 s after the turn\'s focus on the host waits for the end of the 4 s and then holds a keyboard still reported down',
    typing({ composing: false }), (p) => {
        const later = (q, ms) => {
            q.window.__vscodroidImeVisible = false;
            keyboardGone(q, ms);
            log.push(`at ${ms}`);
            inputmode(q);
            advance(q, 4000 - ms);
            log.push(`at 4000`);
            inputmode(q);
        };
        later(p, 1200);
        later(keepLog(() => typing({ composing: false })), 1900);
        later(keepLog(() => typing({ composing: false })), 2400);
    },
    ['at 1200', 'inputmode null', 'host inputmode=none', 'at 4000', 'inputmode none',
        'at 1900', 'inputmode null', 'host inputmode=none', 'at 4000', 'inputmode none',
        'at 2400', 'inputmode null', 'host inputmode=none', 'at 4000', 'inputmode none']);

// ---- Turn and Quick Open dismissal interactions and terminal turn recovery

// A turn inside 4 s of a Quick Open focus claims the single hidden report and never calls __vscodroidKeyboardDismissed,
// so the focus-window timer is never armed and the editor stays unheld.
check('a turn inside 4 s of Quick Open focus claims the hide and does not call the dismissal hook',
    inWindowEditorOpen(), ({ p, file }) => {
        advance(p, 400);
        p.window.__vscodroidImeVisible = true;
        advance(p, 300);
        // Turn happens 700 ms after Quick Open focus: IME goes down at 800 ms, Kotlin owns the report and withholds keyboardGone.
        p.window.__vscodroidImeVisible = false;
        advance(p, 3500);
        log.push(`after 4000: timers ${p.timers.length}`);
        inputmode(p, file);
    },
    ['after 4000: timers 0', 'inputmode null']);

// Quick Open opened late in a turn's window whose hide lands just past the turn window:
// Kotlin calls __vscodroidKeyboardDismissed inside 4 s of the new file's focus, so the focus-window wait defers it;
// when the keyboard comes back up inside 4 s, the wait cancels and keeps the new file unheld.
check('Quick Open focus-window wait overrides a default dismissal when the newly focused file raises the keyboard inside 4 s',
    inWindowEditorOpen(), ({ p, file }) => {
        keyboardGone(p, 1100);
        log.push('at 1100');
        inputmode(p, file);
        advance(p, 600);
        p.window.__vscodroidImeVisible = true;
        advance(p, 2300);
        log.push('at 4000');
        inputmode(p, file);
    },
    ['at 1100', 'inputmode null', 'at 4000', 'inputmode null']);

// Explicit Back key or edge gesture inside 4 s of Quick Open focus passes byPerson=true, holding immediately
// and surviving a transient imeVisible=true bounce.
check('an explicit Back dismissal inside 4 s of Quick Open focus holds immediately and survives a visibility bounce',
    inWindowEditorOpen(), ({ p, file }) => {
        advance(p, 300);
        p.window.__vscodroidImeVisible = true;
        advance(p, 200);
        // Person swipes Back at 500 ms; IME hides and Kotlin calls __vscodroidKeyboardDismissed(true) at 1000 ms.
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500, true);
        log.push(`at 1000 after Back: inputmode ${file.getAttribute('inputmode')}`);
        // A brief visibility bounce at 1200..1300 ms leaves the person's explicit dismissal in force.
        advance(p, 200);
        p.window.__vscodroidImeVisible = true;
        advance(p, 100);
        p.window.__vscodroidImeVisible = false;
        advance(p, 2700);
        log.push(`at 4000 after Back: inputmode ${file.getAttribute('inputmode')}`);
    },
    ['host inputmode=none', 'file inputmode=none', 'at 1000 after Back: inputmode none',
        'at 4000 after Back: inputmode none']);

// Default dismissal without the explicit Back flag: the same sequence without byPerson=true
// keeps the 4 s focus-window deferral so transient hides do not dismiss the new file early.
check('default dismissal without the explicit flag inside 4 s of Quick Open focus keeps the focus wait and clears on visibility bounce',
    inWindowEditorOpen(), ({ p, file }) => {
        advance(p, 300);
        p.window.__vscodroidImeVisible = true;
        advance(p, 200);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500);
        log.push(`at 1000 default: inputmode ${file.getAttribute('inputmode')}`);
        advance(p, 200);
        p.window.__vscodroidImeVisible = true;
        advance(p, 100);
        p.window.__vscodroidImeVisible = false;
        advance(p, 2700);
        log.push(`at 4000 default: inputmode ${file.getAttribute('inputmode')}`);
    },
    ['at 1000 default: inputmode null', 'at 4000 default: inputmode null']);

// Inside a turn's window, tapping outside text (e.g. Explorer icon) with the keyboard up hides the keyboard via
// virtualKeyboard.hide(). Without a seen Back or edge cancel inside the turn window, __vscodroidKeyboardDismissed
// is withheld and restorePolicy resets virtualKeyboardPolicy=auto after 1 s, leaving the focused editor unheld.
check('tapping Explorer inside a turn window hides the keyboard with no dismissal callback, leaving the focused editor unheld',
    typing({ composing: false }), (p) => {
        advance(p, 3000);
        touch(p, p.explorer, 100);
        flushTimers(p);
        // Keyboard hides, producing one hidden report inside turnOwns: Kotlin withholds __vscodroidKeyboardDismissed.
        p.window.__vscodroidImeVisible = false;
        advance(p, 1000);
        inputmode(p);
    },
    ['host virtualKeyboardPolicy=manual', 'hide', 'host virtualKeyboardPolicy=auto', 'host inputmode=none', 'inputmode none']);

// Explicit Back dismissal whose hide report arrived before a tap on text is void even with byPerson=true:
// the tap already answered the hide and the reopened keyboard stays up with no hold written.
check('an explicit Back dismissal arriving after a tap on text has answered its hide is void and leaves the keyboard unheld',
    typing({ composing: false }), (p) => {
        advance(p, 5000);
        p.window.__vscodroidImeVisible = false;
        advance(p, 200);
        touch(p, p.lines, 100);
        log = [];
        keyboardGone(p, 200, true);
        inputmode(p);
        p.window.__vscodroidImeVisible = true;
        advance(p, 500);
        inputmode(p);
    },
    ['inputmode null', 'inputmode null']);

// Turning with the integrated terminal focused refocuses xterm's helper textarea cleanly without writing inputmode=none.
check('turning with the integrated terminal focused refocuses xterm without a hold',
    terminalTyping(), (p) => {
        const turnRefocus = rotationVerdict(p);
        log.push(`turnRefocus ${turnRefocus}`, `termInputmode ${p.term.getAttribute('inputmode')}`);
    },
    ['turnRefocus true', 'termInputmode null']);

// Turning while the integrated terminal field is composing asks for no native refocus:
// clearFocus()/requestFocus() blurs xterm's helper textarea, xterm empties it on blur,
// and the composing word is lost before its finalizer can read it. Once composition
// ends, a later turn refocuses as before.
check('turning while the integrated terminal field is composing asks for no refocus and keeps its text',
    terminalTyping(), (p) => {
        p.term.value = 'test';
        p.term.addEventListener('focusout', () => { p.term.value = ''; });
        dispatch(p, p.term, { type: 'compositionstart', bubbles: true });
        const turnRefocus = rotationVerdict(p);
        if (turnRefocus) {
            p.term.blur();
            p.term.focus();
        }
        log.push(`turnRefocus ${turnRefocus}`, `termValue ${p.term.value}`, `termInputmode ${p.term.getAttribute('inputmode')}`);
        dispatch(p, p.term, { type: 'compositionend', bubbles: true });
        log.push(`afterEnd ${rotationVerdict(p)}`);
    },
    ['turnRefocus false', 'termValue test', 'termInputmode null', 'afterEnd true']);

// Known consequence when the activity withholds the dismissal hook for an unseen hide inside a turn window:
// aimedAtText stays true and inputmode stays null across a finger scroll, whereas a dismissal outside the window holds it.
check('an unseen hide inside a turn window withholds the dismissal hook and leaves the focused editor unheld across a scroll',
    typing({ composing: false }), (p) => {
        advance(p, 4500);
        p.window.__vscodroidImeVisible = false;
        drag(p, p.lines);
        inputmode(p);
        keyboardGone(p, 500);
        drag(p, p.lines);
        inputmode(p);
    },
    ['inputmode null', 'host inputmode=none', 'inputmode none']);

// An aborted edge gesture (a peek taken back) before Quick Open's input takes focus has a gesture age older than
// the focus age, so a transient hidden report within 2 s of that peek enters the ordinary 4 s focus wait instead of
// holding immediately: when the keyboard comes back up inside 4 s nothing is held, and when it stays down it holds at 4 s.
check('an aborted edge gesture before Quick Open takes focus does not make a transient hide explicit and leaves the ordinary wait in place',
    typing({ composing: false }), (p) => {
        // Peek completed at t=1020; Quick Open input takes focus at t=1400; transient hide at t=1700;
        // Kotlin invokes __vscodroidKeyboardDismissed(true, wallAt(1020)) at t=2200 (< textFocusedWallAt 1400).
        p.now = 1400;
        const quickOpen = new El(p, 'quickOpen', 'input', [], p.document.body);
        quickOpen.focus();
        log = [];
        advance(p, 300);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500, true, wallAt(1020));
        log.push(`at 2200: timers ${p.timers.length}`);
        inputmode(p);
        advance(p, 300);
        p.window.__vscodroidImeVisible = true;
        log.push(`at 2500 up: timers ${p.timers.length}`);
        const file = newFile(p);
        file.focus();
        advance(p, 3000);
        inputmode(p, file);

        // If the keyboard instead stays down through the end of the 4 s focus window, the ordinary wait writes the hold then.
        const q = keepLog(() => {
            const page = typing({ composing: false });
            page.now = 1400;
            const box = new El(page, 'quickOpen', 'input', [], page.document.body);
            box.focus();
            return page;
        });
        advance(q, 300);
        q.window.__vscodroidImeVisible = false;
        keyboardGone(q, 500, true, wallAt(1020));
        advance(q, 3199);
        log.push(`at 5399: timers ${q.timers.length}`);
        inputmode(q);
        advance(q, 1);
        log.push(`at 5400: timers ${q.timers.length}`);
        inputmode(q);
    },
    ['at 2200: timers 1', 'inputmode null', 'at 2500 up: timers 0',
        'focus file', 'file -inputmode', 'inputmode null',
        'at 5399: timers 1', 'inputmode null',
        'host inputmode=none', 'at 5400: timers 0', 'inputmode none']);

// A real Back key or edge gesture made after Quick Open's input takes focus has a wall-clock timestamp at or after the focus timestamp,
// so it skips the 4 s wait, holds at once, and survives a visibility bounce.
check('a Back or edge gesture made after Quick Open takes focus still holds at once and survives a visibility bounce',
    typing({ composing: false }), (p) => {
        // Quick Open input focuses at t=1400; Back or edge pair at t=1920; hide at t=2100;
        // Kotlin invokes __vscodroidKeyboardDismissed(true, wallAt(1920)) at t=2600 (>= textFocusedWallAt 1400).
        p.now = 1400;
        const quickOpen = new El(p, 'quickOpen', 'input', [], p.document.body);
        quickOpen.focus();
        log = [];
        advance(p, 700);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500, true, wallAt(1920));
        log.push(`at 2600: timers ${p.timers.length}`);
        inputmode(p);
        // Transient visibility bounce and opening a file from Quick Open keep the person's hold in place.
        advance(p, 200);
        p.window.__vscodroidImeVisible = true;
        advance(p, 100);
        p.window.__vscodroidImeVisible = false;
        const file = newFile(p);
        file.focus();
        advance(p, 2500);
        inputmode(p, file);
    },
    ['host inputmode=none', 'at 2600: timers 0', 'inputmode none',
        'focus file', 'file inputmode=none', 'blur file', 'focus file', 'inputmode none']);

// Because the gesture and focus timestamps share one wall clock, evaluateJavascript delivery lag cannot flip a pre-focus peek into an explicit dismissal.
check('an evaluateJavascript delivery delay does not let an aborted peek before focus mark the dismissal explicit',
    typing({ composing: false }), (p) => {
        // Peek completes at t=1370 (30 ms before focus at t=1400); transient hide at t=1700;
        // evaluateJavascript arrives 100 ms after judgment (t=2300) carrying wallAt(1370).
        p.now = 1400;
        const quickOpen = new El(p, 'quickOpen', 'input', [], p.document.body);
        quickOpen.focus();
        log = [];
        advance(p, 300);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 600, true, wallAt(1370));
        log.push(`at 2300: timers ${p.timers.length}`);
        inputmode(p);
        advance(p, 200);
        p.window.__vscodroidImeVisible = true;
        log.push(`at 2500 up: timers ${p.timers.length}`);
        const file = newFile(p);
        file.focus();
        advance(p, 3000);
        inputmode(p, file);
    },
    ['at 2300: timers 1', 'inputmode null', 'at 2500 up: timers 0',
        'focus file', 'file -inputmode', 'inputmode null']);

// A dismissal while a word is still composing leaves inputmode untouched during the composition so
// Chromium does not restart input and double the word, then writes the hold as soon as compositionend
// fires on the still-focused host so a finger scroll leaves the keyboard down while a later tap on text still raises it.
check('a dismissal while a word is composing holds at compositionend without doubling the word, keeps the hold across a scroll, and lets a tap raise the keyboard',
    wordPage(), (p) => {
        // Explicit dismissal right after typing (inside the 4 s focus window) while "two" is composing.
        p.now = 1400;
        advance(p, 300);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500, true, wallAt(1680));
        inputmode(p);
        commitWord(p);
        log.push(`text ${p.host.editContext.text}`);
        drag(p, p.lines);
        inputmode(p);
        touch(p, p.lines, 100);
        inputmode(p);
        log.push(`after tap ${p.host.editContext.text}`);

        // Default dismissal after the focus window behaves the same way, and so does a textarea host.
        const q = keepLog(() => wordPage());
        q.window.__vscodroidImeVisible = false;
        keyboardGone(q);
        inputmode(q);
        commitWord(q);
        drag(q, q.lines);
        inputmode(q);
        assert.strictEqual(q.host.editContext.text, 'line two');

        const t = keepLog(() => typing({ textarea: true }));
        t.window.__vscodroidImeVisible = false;
        keyboardGone(t);
        inputmode(t);
        dispatch(t, t.host, { type: 'compositionend', bubbles: true });
        drag(t, t.lines);
        inputmode(t);
    },
    ['inputmode null', 'commit 5..8 line two', 'host inputmode=none', 'text line two',
        'inputmode none', 'host -inputmode', 'blur host', 'focus host', 'inputmode null', 'after tap line two',
        'inputmode null', 'commit 5..8 line two', 'host inputmode=none', 'inputmode none',
        'inputmode null', 'host inputmode=none', 'inputmode none']);

// If focus leaves a composing host while a hold is pending, focusout drops the pending hold without writing
// inputmode on the blurring host (avoiding input restart and word doubling), a delayed compositionend on an
// unfocused host stays silent, and the next focus is answered by focusin.
check('focusout drops a pending hold without writing inputmode on a blurred host and answers the next focus in focusin',
    wordPage(), (p) => {
        p.now = 1400;
        advance(p, 300);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500, true, wallAt(1680));
        // Focus leaves the composing host (focusout before compositionend): pending hold is dropped,
        // so inputmode is not written mid-composition and closing commit leaves the word intact.
        p.document.activeElement = p.document.body;
        dispatch(p, p.host, { type: 'focusout', bubbles: true });
        inputmode(p);
        commitWord(p);
        log.push(`text ${p.host.editContext.text}`);
        inputmode(p);
        const file = newFile(p);
        file.focus();
        inputmode(p, file);

        // Even when Blink blurs the host before ending EditContext, neither blur nor focusout writes inputmode
        // while losing focus; returning focus to the host answers via focusin.
        const q = keepLog(() => wordPage());
        q.now = 1400;
        advance(q, 300);
        q.window.__vscodroidImeVisible = false;
        keyboardGone(q, 500, true, wallAt(1680));
        q.host.blur();
        inputmode(q);
        q.host.focus();
        inputmode(q);
    },
    ['inputmode null', 'commit 5..8 line two', 'text line two', 'inputmode null',
        'focus file', 'file inputmode=none', 'blur file', 'focus file', 'inputmode none',
        'blur host', 'inputmode null', 'focus host', 'host inputmode=none', 'blur host', 'focus host', 'inputmode none']);

// If the keyboard comes back up before the open composition ends, the pending hold is cancelled
// so compositionend leaves inputmode alone and a scroll does not find the host held.
check('a dismissal while composing is cancelled when the keyboard becomes visible again before compositionend',
    wordPage(), (p) => {
        p.now = 1400;
        advance(p, 300);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500, true, wallAt(1680));
        inputmode(p);
        p.window.__vscodroidImeVisible = true;
        commitWord(p);
        log.push(`text ${p.host.editContext.text}`);
        drag(p, p.lines);
        inputmode(p);

        const q = keepLog(() => wordPage());
        q.window.__vscodroidImeVisible = false;
        keyboardGone(q);
        q.window.__vscodroidImeVisible = true;
        commitWord(q);
        drag(q, q.lines);
        inputmode(q);
        assert.strictEqual(q.host.editContext.text, 'line two');
    },
    ['inputmode null', 'commit 5..8 line two', 'text line two', 'inputmode null',
        'commit 5..8 line two', 'inputmode null']);

// Characterization (pre-existing behavior left unchanged): when visibility returns before compositionend,
// aimedAtText was already cleared when dismissal fired so a later focus still starts held until a tap on text.
check('characterization - returning visibility before compositionend leaves prior aimedAtText false on subsequent focus',
    wordPage(), (p) => {
        p.now = 1400;
        advance(p, 300);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500, true, wallAt(1680));
        p.window.__vscodroidImeVisible = true;
        commitWord(p);
        const file = newFile(p);
        file.focus();
        inputmode(p, file);
    },
    ['commit 5..8 line two', 'focus file', 'file inputmode=none', 'blur file', 'focus file', 'inputmode none']);

// Characterization: no recorded device trace shows an EditContext composition ending without compositionend
// (nor without focusout), so an artificial textupdate-only clear keeps the host unheld until compositionend.
check('characterization - an EditContext composition cleared without compositionend waits for compositionend',
    wordPage(), (p) => {
        p.now = 1400;
        advance(p, 300);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500, true, wallAt(1680));
        const c = p.host.editContext;
        c.composing = false;
        c.text = 'line ';
        c.selectionStart = c.selectionEnd = 5;
        c.fire('textupdate');
        drag(p, p.lines);
        inputmode(p);
    },
    ['inputmode null']);

// When no word is composing at the dismissal, the hold is written immediately as before, stays on
// across a scroll, and comes off on a tap on text.
check('a dismissal with no word composing writes the hold immediately, keeps it across a scroll, and lets a tap raise the keyboard',
    typing({ composing: false }), (p) => {
        p.host.editContext.text = 'line two';
        p.now = 1400;
        advance(p, 300);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500, true, wallAt(1680));
        log.push(`text ${p.host.editContext.text}`);
        drag(p, p.lines);
        inputmode(p);
        touch(p, p.lines, 100);
        inputmode(p);
    },
    ['host inputmode=none', 'text line two', 'inputmode none',
        'host -inputmode', 'blur host', 'focus host', 'inputmode null']);

// A Back while a word composes leaves the hold waiting for compositionend. A turn inside that wait finishes the word
// with the page's own refocus, which runs with reapplying set: its focusout drops the pending hold, and its focusin is
// skipped while reapplying, so the hold is never written and the next finger scroll finds the host open.
check('a turn while a dismissal waits for compositionend still leaves the host held',
    wordPage(), (p) => {
        // The order Blink gives a blur the page makes on a composing EditContext host (measured on Android 16, WebView 134):
        // blur and focusout with the body already active, then compositionend.
        p.host.blur = function () {
            log.push(`blur ${this.name}`);
            if (p.document.activeElement !== this) return;
            p.document.activeElement = p.document.body;
            dispatch(p, this, { type: 'focusout', bubbles: true });
            this.editContext.end();
        };
        p.now = 1400;
        advance(p, 300);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500, true, wallAt(1680));
        assert.strictEqual(p.host.getAttribute('inputmode'), null, 'setup: the hold waits for compositionend');
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 1000);
        assert.strictEqual(rotationVerdict(p, false), false);
        log = [];
        inputmode(p);
        drag(p, p.lines);
        inputmode(p);
    },
    ['inputmode none', 'inputmode none']);

// The terminal's word ended a moment before the turn's check (14 ms before it in one of five runs on a device): the check
// finds nothing composing and asks for the refocus, whose blur empties xterm's field. The word is kept only if its own
// send has run by that blur.
check('a terminal word that ended just before the turn check gets the refocus',
    terminalTyping(), (p) => {
        dispatch(p, p.term, { type: 'compositionstart', bubbles: true });
        dispatch(p, p.term, { type: 'compositionend', bubbles: true });
        log.push(`turnRefocus ${rotationVerdict(p)}`);
    },
    ['turnRefocus true']);

// After the page refocused a composing host whose word went on (the keyboard kept it through the refocus), the host is
// no longer the one being refocused: a real blur of it drops the pending hold as before, and its late compositionend
// writes nothing on the blurred host.
check('after the page refocused a host whose word went on, a real blur still drops the pending hold',
    wordPage(), (p) => {
        p.now = 1400;
        advance(p, 300);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500, true, wallAt(1680));
        const blur = p.host.blur;
        p.host.blur = function () {
            log.push(`blur ${this.name}`);
            if (p.document.activeElement !== this) return;
            p.document.activeElement = p.document.body;
            dispatch(p, this, { type: 'focusout', bubbles: true });
        };
        p.window.__vscodroidFinishComposition();
        p.host.blur = blur;
        assert.strictEqual(p.host.editContext.composing, true, 'setup: the word went on through the refocus');
        log = [];
        p.host.blur();
        inputmode(p);
    },
    ['blur host', 'inputmode null']);

// The turn finished from the page's timer (a same-text update while the word composes) and then by the poll that ends
// it, after the keyboard reopened the word: two refocuses. A hold that waited for the word's end is written once, at the
// first, and the second leaves it in place.
check('a turn refocused from the timer and again by the poll writes a waiting hold once and keeps it',
    wordPage(), (p) => {
        p.now = 1400;
        advance(p, 300);
        p.window.__vscodroidImeVisible = false;
        keyboardGone(p, 500, true, wallAt(1680));
        reopeningKeyboard(p, 100);
        log = [];
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 200);
        p.host.editContext.fire('textupdate');
        advance(p, 0);
        advance(p, 150);
        const poll = rotationVerdict(p, false);
        advance(p, 500);
        drag(p, p.lines);
        const refocuses = refocusesIn();
        const holds = log.filter((entry) => entry === 'host inputmode=none').length;
        log = [`poll ${poll}`, `refocuses ${refocuses}`, `holds ${holds}`];
        inputmode(p);
    },
    ['poll false', 'refocuses 2', 'holds 1', 'inputmode none']);

// ---- the page's hold kept through its own refocus

/** A dismissal while a word composes: the hold waits for compositionend. Blink's measured order for a page-made blur: blur, focusout, compositionend. */
function waitingHold(p) {
    p.host.blur = function () {
        log.push(`blur ${this.name}`);
        if (p.document.activeElement !== this) return;
        p.document.activeElement = p.document.body;
        dispatch(p, this, { type: 'focusout', bubbles: true });
        this.editContext.end();
    };
    p.now = 1400;
    advance(p, 300);
    p.window.__vscodroidImeVisible = false;
    keyboardGone(p, 500, true, wallAt(1680));
    assert.strictEqual(p.host.getAttribute('inputmode'), null, 'setup: the hold waits for compositionend');
}

check('a hold that waits for a word is written once by the timer path of a turn whose keyboard reopens after every refocus, and nobody polls',
    wordPage(), (p) => {
        waitingHold(p);
        reopeningKeyboard(p, 100);
        log = [];
        assert.strictEqual(rotationVerdict(p), 'wait');
        advance(p, 200);
        p.host.editContext.fire('textupdate');
        for (let i = 0; i < 20; i += 1) advance(p, 500);
        const holds = log.filter((entry) => entry === 'host inputmode=none').length;
        log = [`refocuses ${refocusesIn()}`, `holds ${holds}`];
        inputmode(p);
    },
    ['refocuses 1', 'holds 1', 'inputmode none']);

check('focus that moves elsewhere inside the refocus leaves the written hold on the host and nothing waiting',
    wordPage(), (p) => {
        waitingHold(p);
        const focus = p.host.focus.bind(p.host);
        p.host.focus = () => { p.explorer.focus(); };
        log = [];
        p.window.__vscodroidFinishComposition();
        p.host.focus = focus;
        log = [`active ${p.document.activeElement.name}`, `composing ${p.host.editContext.composing}`];
        inputmode(p);
        // a later word that begins and ends on the blurred host writes nothing more
        p.host.editContext.start();
        p.host.editContext.end();
        inputmode(p);
        // and a tap on text takes the hold off the host that never got its focus back
        touch(p, p.lines, 100, '-monaco-gesturetap');
        inputmode(p);
    },
    ['active explorer', 'composing false', 'inputmode none', 'inputmode none', 'host -inputmode', 'lines -monaco-gesturetap', 'inputmode null']);

check('a refocus interrupted by a throw leaves refocusing set; a real blur of the host after it keeps the hold and writes it on the blurred host (recorded, not reachable from the DOM)',
    wordPage(), (p) => {
        waitingHold(p);
        const blur = p.host.blur;
        // blur() throwing before any event: the page's own refocus left half done.
        p.host.blur = function () { log.push(`blur ${this.name}`); throw new Error('blur failed'); };
        log = [];
        try { p.window.__vscodroidFinishComposition(); } catch (e) { log.push('threw'); }
        p.host.blur = blur;
        // The person's own blur of the host (a tap elsewhere), the word still open, then the word ends while the host is blurred.
        p.host.blur();
        p.host.editContext.fire('compositionend');
        inputmode(p);
    },
    ['blur host', 'threw', 'blur host', 'host inputmode=none', 'inputmode none']);

check('with the keyboard visible during the refocus, the written hold is taken off again by the refocus itself',
    wordPage(), (p) => {
        waitingHold(p);
        p.window.__vscodroidImeVisible = true;
        log = [];
        p.window.__vscodroidFinishComposition();
        inputmode(p);
    },
    ['blur host', 'focus host', 'inputmode null']);

check('a tap on text while the hold waits ends it, in either order of the refocus and the tap',
    wordPage(), (p) => {
        waitingHold(p);
        log = [];
        touch(p, p.lines, 100, '-monaco-gesturetap');
        inputmode(p);
        log.push(`active ${p.document.activeElement.name}`);
    },
    ['blur host', 'focus host', 'lines -monaco-gesturetap', 'inputmode null', 'active host']);

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
