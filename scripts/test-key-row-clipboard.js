/**
 * Self-check for copy and cut with Ctrl latched on the extra key row.
 *
 * Runs the shipped modifier interceptor against controlled editor clipboard
 * listeners, text boxes, bindings and Android clipboard answers. It verifies
 * operation order and the synthetic-only Ctrl+V route, without a real WebView,
 * Monaco model or Android clipboard transaction.
 *
 *   node scripts/test-key-row-clipboard.js
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const KEY_INJECTOR = path.join(
    __dirname, '..', 'android/app/src/main/kotlin/com/vscodroid/keyboard/KeyInjector.kt',
);

/** The body of the raw string in `setupModifierInterceptor()`, as `trimIndent()` leaves it. */
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
    const indent = Math.min(...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length));
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
    const releaseOpen = lines.findIndex((l) => l.includes('private val RELEASE_MODIFIERS_JS = """'));
    const releaseClose = lines.findIndex((l, i) => i > releaseOpen && l.trim().startsWith('"""'));
    assert.ok(releaseOpen !== -1 && releaseClose > releaseOpen, 'could not find the modifier-release helper');
    const release = lines.slice(releaseOpen + 1, releaseClose).join('\n');
    const substituted = js.split('$keyLookup').join('{}')
        .split('$RELEASE_MODIFIERS_JS').join(release);
    assert.ok(!substituted.includes('$'),
        'the interceptor gained a Kotlin interpolation this check does not know how to fill');
    assert.ok(substituted.includes('var EDGE'),
        'the extracted text has no EDGE table, so the wrong block was extracted');
    return substituted;
}

const INTERCEPTOR = extractInterceptor();

const captureOf = (options) => options === true || !!(options && options.capture);

/**
 * A page with the interceptor installed. `options.bound` names the letters the
 * keybinding service answers with Ctrl, `options.terminalCancels` is whether xterm
 * takes Ctrl+C, X and V, and `options.clipboard` is what Android's clipboard holds.
 */
function newPage(options = {}) {
    const { bound = [], terminalCancels = true, clipboard = null, token = 'tok', bridge = true, copyWorks = true, lend = false } = options;
    const bridgeLog = [];
    const timers = [];
    const frames = [];
    const hostLog = [];
    const execLog = [];
    const events = [];
    const operations = [];
    const state = { clipboard };

    const listen = (node) => Object.assign(node, {
        listeners: [],
        addEventListener(type, fn, opts) { this.listeners.push({ type, fn, capture: captureOf(opts) }); },
        removeEventListener(type, fn, opts) {
            const capture = captureOf(opts);
            this.listeners = this.listeners.filter((l) => !(l.type === type && l.fn === fn && l.capture === capture));
        },
    });
    const window = listen({});
    const document = listen({});

    function dispatch(target, event) {
        if (['keydown', 'keyup', 'copy', 'cut', 'paste'].includes(event.type)) {
            events.push({ type: event.type, key: event.key, ctrl: !!event.ctrlKey });
        }
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
        const outer = window.event;
        window.event = event;
        try {
            for (const [node, capture] of phases) {
                for (const l of node.listeners.slice()) {
                    if (l.type !== event.type || l.capture !== capture) continue;
                    l.fn.call(node, event);
                    if (stopped) return !event.defaultPrevented;
                }
            }
            return !event.defaultPrevented;
        } finally {
            window.event = outer;
        }
    }
    const element = (tagName, classes = [], props = {}) => {
        const el = listen({ tagName, classList: { contains: (name) => classes.includes(name) }, ...props });
        el.dispatchEvent = (event) => dispatch(el, event);
        return el;
    };

    // The controlled editor fills clipboard data and applies selected-text cuts.
    // A copy with no selection supplies the current line.
    const doc = { text: 'alpha beta\n    indented', from: 0, to: 10 };
    const taken = () => (doc.from === doc.to
        ? doc.text.split('\n')[0] + '\n'
        : doc.text.slice(doc.from, doc.to));
    // With `lend`, the editor's host has an EditContext of the editor's own, which the script swaps for an
    // empty one while Ctrl is latched, and takes focus away from and gives back to restart input.
    class FakeEditContext {
        constructor(name) { this.name = name; this.text = ''; this.selectionStart = 0; this.selectionEnd = 0; listen(this); }
        updateText() {}
        updateSelection() {}
        dispatchEvent(event) { for (const l of this.listeners.slice()) if (l.type === event.type) l.fn(event); return true; }
        fire(type, init = {}) { return this.dispatchEvent({ type, ...init }); }
    }
    const own = new FakeEditContext('editor');
    const editor = element('DIV', ['native-edit-context'], { editContext: own });
    if (lend) {
        let attached = own;
        Object.defineProperty(editor, 'editContext', { get: () => attached, set: (ec) => { attached = ec; } });
        editor.blur = () => { hostLog.push('blur host'); document.activeElement = null; dispatch(editor, { type: 'focusout', bubbles: true }); };
        editor.focus = () => {
            hostLog.push('focus host');
            if (document.activeElement === editor) return;
            document.activeElement = editor;
            dispatch(editor, { type: 'focusin', bubbles: true });
        };
    }
    editor.addEventListener('copy', (e) => { operations.push('copy'); e.preventDefault(); e.clipboardData.setData('text/plain', taken()); });
    editor.addEventListener('cut', (e) => {
        operations.push('cut');
        e.preventDefault();
        e.clipboardData.setData('text/plain', taken());
        if (doc.from !== doc.to) { doc.text = doc.text.slice(0, doc.from) + doc.text.slice(doc.to); doc.to = doc.from; }
    });
    const box = element('INPUT', [], { type: 'text', value: 'hello world', selectionStart: 0, selectionEnd: 5, readOnly: false });
    const secret = element('INPUT', [], { type: 'password', value: 'hunter2', selectionStart: 0, selectionEnd: 7, readOnly: false });
    const terminal = element('TEXTAREA', ['xterm-helper-textarea'], { value: '', selectionStart: 0, selectionEnd: 0 });
    const terminalSaw = [];
    terminal.addEventListener('keydown', (e) => {
        terminalSaw.push(e.key);
        if (terminalCancels) e.preventDefault();
    });
    document.activeElement = editor;

    // The keybinding service: it cancels a key it has a binding for. It listens late, as the
    // workbench does, after the interceptor's own listeners.
    const answered = [];
    window.addEventListener('keydown', (e) => {
        if (e.ctrlKey && !e.shiftKey && bound.includes(e.key)) {
            answered.push(e.key);
            e.preventDefault();
        }
    }, false);

    class DataTransfer {
        constructor() { this.store = {}; }
        setData(type, value) { this.store[type] = String(value); }
        getData(type) { return this.store[type] === undefined ? '' : this.store[type]; }
        get types() { return Object.keys(this.store); }
    }
    // A plain function, so that `new` returns the object it builds.
    const make = function (type, init) { return { type, ...init }; };
    const AndroidBridge = {
        readFromClipboard(t) {
            bridgeLog.push(`read ${t}`);
            if (t !== token) throw new Error('token refused');
            return state.clipboard;
        },
        copyToClipboard(t, text) {
            operations.push('clipboard');
            bridgeLog.push(`copy ${t} ${JSON.stringify(text)}`);
            if (t !== token) throw new Error('token refused');
            if (!copyWorks) return false;
            state.clipboard = text;
            return true;
        },
    };
    // A real deletion fires beforeinput at the focused box before changing its value.
    document.execCommand = (name, ui, value) => {
        execLog.push(value === undefined ? name : `${name}:${value}`);
        const el = document.activeElement;
        if (!el || typeof el.value !== 'string') return false;
        const start = el.selectionStart;
        const end = el.selectionEnd;
        if (name === 'delete' && start !== end) {
            if (!dispatch(el, { type: 'beforeinput', inputType: 'deleteContentBackward', bubbles: true, cancelable: true })) return true;
            el.value = el.value.slice(0, start) + el.value.slice(end);
            el.selectionStart = el.selectionEnd = start;
            return true;
        }
        return false;
    };

    window.__vscodroid = { authToken: token };
    vm.runInContext(INTERCEPTOR, vm.createContext({
        window, document, navigator: { userAgent: '' },
        KeyboardEvent: make, InputEvent: make, ClipboardEvent: make, DataTransfer,
        ...(bridge ? { AndroidBridge } : {}),
        ...(lend ? { EditContext: FakeEditContext, TextUpdateEvent: make } : {}),
        setTimeout(fn, delay = 0) { timers.push({ fn, delay }); },
        requestAnimationFrame(fn) { frames.push(fn); },
    }));
    return {
        window, document, editor, box, secret, terminal, doc, state, bridgeLog, execLog, terminalSaw, answered, events, operations,
        focus(el) { document.activeElement = el; },
        hostLog,
        // The tasks queued so far for this moment, not what they queue and not a timer that waits.
        flush() {
            const due = timers.filter((x) => x.delay === 0);
            for (const x of due) timers.splice(timers.indexOf(x), 1);
            for (const x of due) x.fn();
        },
        // The timers that fall due within `ms` more.
        advance(ms) {
            const due = timers.filter((x) => x.delay <= ms);
            for (const x of due) timers.splice(timers.indexOf(x), 1);
            for (const x of due) x.fn();
        },
        // One animation frame: the callbacks asked for so far, not what they ask for in turn.
        frame() { for (const fn of frames.splice(0)) fn(); },
        // What the keyboard types into whichever EditContext the host has now.
        commit(text) { editor.editContext.fire('textupdate', { text }); },
        latch(mods = { ctrl: true }) {
            const m = window.__vscodroid;
            m.ctrl = !!mods.ctrl; m.alt = !!mods.alt; m.shift = !!mods.shift;
        },
        /** The soft keyboard types `text` into the focused element; true when the page would insert it. */
        type(text) {
            return dispatch(document.activeElement, { type: 'beforeinput', inputType: 'insertText', data: text, bubbles: true, cancelable: true });
        },
        mods: () => ({ ctrl: window.__vscodroid.ctrl, alt: window.__vscodroid.alt, shift: window.__vscodroid.shift }),
    };
}

const cases = [];
const test = (name, fn) => cases.push({ name, fn });

// The editor.

test('Ctrl then c in the editor copies the selection to Android\'s clipboard and types nothing', () => {
    const p = newPage({ clipboard: 'old' });
    p.latch();
    const typed = p.type('c');
    assert.strictEqual(typed, false, 'the letter was left for the page to insert');
    assert.strictEqual(p.state.clipboard, 'alpha beta');
    assert.strictEqual(p.doc.text, 'alpha beta\n    indented', 'a copy changed the document');
    assert.deepStrictEqual(p.mods(), { ctrl: false, alt: false, shift: false }, 'the latch outlived the key');
});

test('Ctrl then c with nothing selected copies the line, as the menu does', () => {
    const p = newPage();
    p.doc.from = p.doc.to = 3;
    p.latch();
    p.type('c');
    assert.strictEqual(p.state.clipboard, 'alpha beta\n');
});

test('Ctrl then x in the editor puts the selection on the clipboard and removes it from the document', () => {
    const p = newPage({ clipboard: 'old' });
    p.latch();
    p.type('x');
    assert.strictEqual(p.state.clipboard, 'alpha beta');
    assert.strictEqual(p.doc.text, '\n    indented');
    assert.deepStrictEqual(p.operations, ['copy', 'clipboard', 'cut']);
});

test('a failed editor clipboard write leaves the selection in place', () => {
    const p = newPage({ clipboard: 'old', copyWorks: false });
    p.latch();
    p.type('x');
    assert.strictEqual(p.doc.text, 'alpha beta\n    indented');
    assert.deepStrictEqual([p.doc.from, p.doc.to], [0, 10]);
    assert.strictEqual(p.state.clipboard, 'old');
    assert.deepStrictEqual(p.operations, ['copy', 'clipboard']);
});

test('Ctrl then v in the editor only sends the synthetic key', () => {
    for (const lend of [false, true]) {
        const p = newPage({ clipboard: 'one\n    two', lend });
        p.latch();
        if (lend) { p.flush(); p.commit('v'); }
        else assert.strictEqual(p.type('v'), false);
        p.advance(100);
        assert.deepStrictEqual(p.bridgeLog, [], 'Ctrl+V read or wrote Android clipboard');
        assert.deepStrictEqual(p.execLog, []);
        assert.deepStrictEqual(p.events, [
            { type: 'keydown', key: 'v', ctrl: true },
            { type: 'keyup', key: 'v', ctrl: true },
            { type: 'keyup', key: 'Control', ctrl: false },
        ], 'Ctrl+V emitted an extra event');
        assert.strictEqual(p.doc.text, 'alpha beta\n    indented');
        assert.strictEqual(p.editor.editContext.name, 'editor');
    }
});

test('a latch, and any other chord, restarts input in the task of the key as before', () => {
    const p = newPage({ clipboard: 'old', lend: true });
    p.latch();
    p.flush();
    p.commit('a');
    assert.strictEqual(p.editor.editContext.name, 'editor');
    assert.deepStrictEqual(p.hostLog, ['blur host', 'focus host']);
});

test('a copy or a cut gives the EditContext back at once, as every other chord does', () => {
    for (const letter of ['c', 'x']) {
        const p = newPage({ clipboard: 'old', lend: true });
        p.latch();
        p.flush();
        p.commit(letter);
        assert.strictEqual(p.editor.editContext.name, 'editor');
        assert.deepStrictEqual(p.hostLog, ['blur host', 'focus host']);
    }
});

// An ordinary text box.

test('Ctrl then c in a text box copies its selection', () => {
    const p = newPage({ clipboard: 'old' });
    p.focus(p.box);
    p.latch();
    assert.strictEqual(p.type('c'), false);
    assert.strictEqual(p.state.clipboard, 'hello');
    assert.strictEqual(p.box.value, 'hello world');
});

test('Ctrl then c in a text box with nothing selected leaves the clipboard alone', () => {
    const p = newPage({ clipboard: 'old' });
    p.focus(p.box);
    p.box.selectionStart = p.box.selectionEnd = 3;
    p.latch();
    p.type('c');
    assert.strictEqual(p.state.clipboard, 'old');
});

test('Ctrl then x in a text box copies its selection and deletes it', () => {
    const p = newPage({ clipboard: 'old' });
    p.focus(p.box);
    p.latch();
    p.type('x');
    assert.strictEqual(p.state.clipboard, 'hello');
    assert.strictEqual(p.box.value, ' world');
    assert.deepStrictEqual(p.execLog, ['delete']);
});

test('a cut that could not reach Android\'s clipboard leaves the text where it is', () => {
    const p = newPage({ clipboard: 'old', copyWorks: false });
    p.focus(p.box);
    p.latch();
    p.type('x');
    assert.strictEqual(p.box.value, 'hello world');
    assert.deepStrictEqual(p.execLog, []);
});

test('Ctrl then v in a text box only sends the synthetic key', () => {
    const p = newPage({ clipboard: 'HI' });
    p.focus(p.box);
    p.latch();
    assert.strictEqual(p.type('v'), false);
    p.advance(100);
    assert.deepStrictEqual(p.bridgeLog, [], 'Ctrl+V read or wrote Android clipboard');
    assert.deepStrictEqual(p.execLog, []);
    assert.deepStrictEqual(p.events, [
        { type: 'keydown', key: 'v', ctrl: true },
        { type: 'keyup', key: 'v', ctrl: true },
        { type: 'keyup', key: 'Control', ctrl: false },
    ], 'Ctrl+V emitted an extra event');
    assert.strictEqual(p.box.value, 'hello world');
});

test('a read-only box can be copied from but not cut from', () => {
    const p = newPage({ clipboard: 'old' });
    p.focus(p.box);
    p.box.readOnly = true;
    p.latch(); p.type('c');
    assert.strictEqual(p.state.clipboard, 'hello');
    p.state.clipboard = 'old';
    p.latch(); p.type('x');
    assert.strictEqual(p.state.clipboard, 'old');
    assert.strictEqual(p.box.value, 'hello world');
});

test('a password box is neither copied from nor cut from by the row', () => {
    const p = newPage({ clipboard: 'old' });
    p.focus(p.secret);
    p.latch(); p.type('c');
    assert.strictEqual(p.state.clipboard, 'old');
    p.latch(); p.type('x');
    assert.strictEqual(p.state.clipboard, 'old');
    assert.strictEqual(p.secret.value, 'hunter2');
});

// What must not change.

test('the terminal keeps Ctrl+C, Ctrl+X and Ctrl+V: the key reaches xterm and the clipboard is untouched', () => {
    const p = newPage({ clipboard: 'old' });
    p.focus(p.terminal);
    for (const letter of ['c', 'x', 'v']) {
        p.latch();
        assert.strictEqual(p.type(letter), false);
    }
    // The terminal's chord waits a task, so the key is not sent in this one, and the clipboard is not asked for.
    assert.deepStrictEqual(p.bridgeLog, []);
    assert.strictEqual(p.state.clipboard, 'old');
});

test('the terminal is left alone even by a key xterm did not take', () => {
    const p = newPage({ clipboard: 'old', terminalCancels: false });
    p.focus(p.terminal);
    p.latch(); p.type('v');
    assert.deepStrictEqual(p.bridgeLog, []);
    assert.deepStrictEqual(p.execLog, []);
});

test('a key a binding takes is not given the default action: Ctrl+C bound to something else', () => {
    const p = newPage({ clipboard: 'old', bound: ['c'] });
    p.latch();
    p.type('c');
    assert.deepStrictEqual(p.answered, ['c']);
    assert.strictEqual(p.state.clipboard, 'old');
    assert.deepStrictEqual(p.bridgeLog, []);
});

test('Ctrl then a keeps going to its binding, and nothing is copied', () => {
    const p = newPage({ clipboard: 'old', bound: ['a'] });
    p.latch();
    p.type('a');
    assert.deepStrictEqual(p.answered, ['a']);
    assert.deepStrictEqual(p.bridgeLog, []);
});

test('Ctrl+Shift+C, a capital typed with Ctrl latched, is not a copy', () => {
    const p = newPage({ clipboard: 'old' });
    p.latch();
    p.type('C');
    assert.strictEqual(p.state.clipboard, 'old');
    p.latch({ ctrl: true, shift: true });
    p.type('v');
    assert.deepStrictEqual(p.bridgeLog, []);
});

test('Alt then c is not a copy', () => {
    const p = newPage({ clipboard: 'old' });
    p.latch({ alt: true });
    p.type('c');
    assert.deepStrictEqual(p.bridgeLog, []);
});

test('Ctrl and Alt together are not a copy either', () => {
    const p = newPage({ clipboard: 'old' });
    p.latch({ ctrl: true, alt: true });
    p.type('c');
    assert.deepStrictEqual(p.bridgeLog, []);
});

test('with nothing latched a c is typed, as ever', () => {
    const p = newPage({ clipboard: 'old' });
    assert.strictEqual(p.type('c'), true);
    assert.deepStrictEqual(p.bridgeLog, []);
});

test('without the bridge or the token the key is still sent and nothing throws', () => {
    for (const options of [{ bridge: false }, { token: '' }]) {
        const p = newPage({ clipboard: 'old', ...options });
        p.latch();
        assert.doesNotThrow(() => p.type('c'));
        assert.strictEqual(p.state.clipboard, 'old');
        assert.deepStrictEqual(p.mods(), { ctrl: false, alt: false, shift: false });
    }
});

test('the synthetic key is still sent first, with Ctrl, so a chord keeps working', () => {
    const p = newPage({ clipboard: 'old' });
    const seen = [];
    p.editor.addEventListener('keydown', (e) => seen.push(`${e.key} ${e.code} ${e.keyCode} ctrl=${!!e.ctrlKey}`));
    p.latch();
    p.type('c');
    assert.deepStrictEqual(seen, ['c KeyC 67 ctrl=true']);
});

let failed = 0;
for (const { name, fn } of cases) {
    try {
        fn();
        console.log(`ok   ${name}`);
    } catch (error) {
        failed += 1;
        console.log(`FAIL ${name}\n     ${String(error.message).split('\n').join('\n     ')}`);
    }
}
console.log(`${cases.length - failed} of ${cases.length} cases pass`);
process.exit(failed ? 1 : 0);
