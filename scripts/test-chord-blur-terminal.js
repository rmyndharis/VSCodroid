/**
 * Self-check for the blur the key row's chord makes over a word a keyboard composes in the
 * terminal, run through both scripts that take part in it, in one page.
 *
 *   node scripts/test-chord-blur-terminal.js
 *
 * With Ctrl latched on the row, a keyboard that composes (Gboard 12.4 adds every letter
 * to the word) puts the letter in the terminal's textarea as part of the word. The modifier
 * interceptor (`KeyInjector.setupModifierInterceptor`) turns it into the chord: a task
 * later it blurs the field, which ends the composition, puts the field's text back as it
 * was before the letter, focuses it again and sends the chord. xterm arms the send of the
 * ended word at its compositionend, and reads the field when that send runs.
 *
 * The terminal script (`MainActivity.injectTerminalTypedCharacters`) sends a pending
 * composition at a field's blur, because xterm empties the field in its own blur handler.
 * At this blur it must not: the field still holds the chord's letter, and the word would go
 * out with it. The interceptor marks the field in `window.__vscodroidChordBlur` for the
 * length of the blur, and the terminal script leaves that blur to xterm's own timer, which
 * runs after the text is put back. This runs the real scripts, extracted from the Kotlin
 * sources, against a small page with a model of xterm's composition send.
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.join(__dirname, '..', 'android/app/src/main/kotlin/com/vscodroid');

/** The raw string in `setupModifierInterceptor()`, with its two Kotlin interpolations filled. */
function extractInterceptor() {
    const lines = fs.readFileSync(path.join(ROOT, 'keyboard/KeyInjector.kt'), 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('fun setupModifierInterceptor()'));
    assert.notEqual(fn, -1, 'setupModifierInterceptor() is gone from KeyInjector.kt');
    const open = lines.findIndex((l, i) => i > fn && l.trim() === 'val js = """');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close > open + 1, 'the raw string in setupModifierInterceptor() changed shape');
    const js = lines.slice(open + 1, close).join('\n')
        .split('$keyLookup').join('{}')
        .split('$RELEASE_MODIFIERS_JS').join('function releaseModifiers() {}');
    assert.ok(!js.includes('$'), 'the interceptor gained a Kotlin interpolation this check cannot fill');
    assert.ok(js.includes('__vscodroidChordBlur'), 'the interceptor no longer marks the blur its chord makes');
    return js;
}

/** The raw string in `injectTerminalTypedCharacters()`. */
function extractTerminalScript() {
    const source = fs.readFileSync(path.join(ROOT, 'MainActivityPageScripts.kt'), 'utf8');
    const method = source.split('internal fun MainActivity.injectTerminalTypedCharacters()')[1];
    assert.ok(method, 'injectTerminalTypedCharacters() is gone from MainActivityPageScripts.kt');
    const js = method.split('"""')[1];
    assert.ok(js.includes('__vscodroidChordBlur'), 'the terminal script no longer reads the chord\'s mark');
    return js;
}

const INTERCEPTOR = extractInterceptor();
const TERMINAL = extractTerminalScript();

/** A page: listeners with capture and bubble, one textarea, a task queue, both scripts in the order the app injects them. */
function page({ interceptor = INTERCEPTOR, terminal = TERMINAL } = {}) {
    let clock = 0, next = 0;
    const tasks = [];

    class EventTarget {
        constructor() { this.listeners = {}; }
        addEventListener(type, fn, capture) {
            (this.listeners[type] ||= []).push({ fn, capture: !!(capture && (capture === true || capture.capture)) });
        }
        removeEventListener(type, fn, capture) {
            const list = this.listeners[type] || [];
            const c = !!(capture && (capture === true || capture.capture));
            const i = list.findIndex((l) => l.fn === fn && l.capture === c);
            if (i >= 0) list.splice(i, 1);
        }
        dispatchEvent(event) { return dispatch(this, event); }
    }
    class Event {
        constructor(type, init = {}) {
            Object.assign(this, init);
            this.type = type;
            this.bubbles = !!init.bubbles;
            this.cancelable = !!init.cancelable;
            this.defaultPrevented = false;
            this.isTrusted = false;
            this.eventPhase = 0;
        }
        preventDefault() { if (this.cancelable) this.defaultPrevented = true; }
        stopPropagation() { this._stopped = true; }
        stopImmediatePropagation() { this._stopped = this._immediate = true; }
    }
    class InputEvent extends Event {}
    class KeyboardEvent extends Event {}
    class CompositionEvent extends Event {}

    const window = new EventTarget();
    const document = new EventTarget();
    window.window = window;
    window.document = document;

    function dispatch(target, event) {
        event.target = target;
        const path = target === window ? [] : target === document ? [window] : [window, document];
        const run = (node, phase, capture) => {
            event.currentTarget = node;
            event.eventPhase = phase;
            for (const l of [...(node.listeners[event.type] || [])]) {
                if (capture !== null && l.capture !== capture) continue;
                l.fn.call(node, event);
                if (event._immediate) return;
            }
        };
        for (const node of path) { if (event._stopped) break; run(node, 1, true); }
        if (!event._stopped) run(target, 2, null);
        if (event.bubbles) for (const node of [...path].reverse()) { if (event._stopped) break; run(node, 3, false); }
        event.eventPhase = 0;
        return !event.defaultPrevented;
    }

    class HTMLElement extends EventTarget {
        constructor(tagName, className) {
            super();
            this.tagName = tagName;
            this.classList = { contains: (name) => name === className };
            this.value = '';
            this.selectionStart = this.selectionEnd = 0;
            this.selectionDirection = 'none';
            this.composing = null;
        }
        closest() { return null; }
        setSelectionRange(start, end, direction) {
            this.selectionStart = start; this.selectionEnd = end; this.selectionDirection = direction || 'none';
        }
        focus() {
            if (document.activeElement === this) return;
            document.activeElement = this;
            dispatch(this, new Event('focus'));
            dispatch(this, new Event('focusin', { bubbles: true }));
        }
        blur() {
            if (document.activeElement !== this) return;
            // Chromium ends a composition when its field loses the focus: compositionend, then the blur, in one task.
            if (this.composing !== null) {
                const data = this.composing;
                this.composing = null;
                dispatch(this, new CompositionEvent('compositionend', { data, bubbles: true }));
            }
            document.activeElement = null;
            dispatch(this, new Event('blur'));
            dispatch(this, new Event('focusout', { bubbles: true }));
        }
    }

    Object.assign(window, {
        Event, InputEvent, KeyboardEvent, CompositionEvent, HTMLElement,
        navigator: { userAgent: 'Mozilla/5.0 Chrome/134.0' },
        queueMicrotask: (fn) => tasks.push({ at: clock, seq: ++next, fn, args: [] }),
        setTimeout(fn, delay = 0, ...args) {
            const id = ++next;
            tasks.push({ id, at: clock + Number(delay || 0), seq: id, fn, args });
            return id;
        },
        clearTimeout(id) {
            const i = tasks.findIndex((t) => t.id === id);
            if (i >= 0) tasks.splice(i, 1);
        },
        getSelection: () => null,
    });
    document.activeElement = null;
    document.body = new HTMLElement('BODY', '');

    const ctx = vm.createContext(window);
    vm.runInContext(interceptor, ctx);
    vm.runInContext(terminal, ctx);

    // The terminal's field and the part of xterm that sends a composition: armed at the field's
    // compositionend with a zero timer, it reads the field from where the word began when it
    // runs, as xterm's _finalizeComposition does once nothing composes. The field is emptied in
    // xterm's own blur handler.
    const ta = new HTMLElement('TEXTAREA', 'xterm-helper-textarea');
    const sent = [];
    const chords = [];
    const xterm = { _isSendingComposition: false, start: 0 };
    ta.addEventListener('compositionstart', () => { xterm.start = ta.selectionStart; });
    ta.addEventListener('compositionend', () => {
        xterm._isSendingComposition = true;
        window.setTimeout(function sendComposition() {
            if (!xterm._isSendingComposition) return;
            xterm._isSendingComposition = false;
            sent.push(ta.value.substring(xterm.start));
        }, 0);
    });
    ta.addEventListener('blur', () => { ta.value = ''; ta.setSelectionRange(0, 0); });
    ta.addEventListener('keydown', (e) => { if (e.ctrlKey) chords.push('Ctrl+' + e.key); });

    function advance(ms) {
        clock += ms;
        for (;;) {
            const due = tasks.filter((t) => t.at <= clock).sort((a, b) => a.at - b.at || a.seq - b.seq)[0];
            if (!due) return;
            tasks.splice(tasks.indexOf(due), 1);
            due.fn.apply(window, due.args);
        }
    }

    /** What a composing keyboard does for one more letter of the word: the update, the beforeinput, and the insertion. */
    function compose(word) {
        if (ta.composing === null) {
            ta.composing = '';
            dispatch(ta, new CompositionEvent('compositionstart', { data: '', bubbles: true }));
        }
        const start = xterm.start;
        dispatch(ta, new CompositionEvent('compositionupdate', { data: word, bubbles: true }));
        const before = new InputEvent('beforeinput', {
            inputType: 'insertCompositionText', data: word, bubbles: true, cancelable: true, composed: true,
        });
        dispatch(ta, before);
        // A composition update cannot be cancelled: the text goes in whatever the listeners did.
        ta.value = ta.value.substring(0, start) + word;
        ta.composing = word;
        ta.setSelectionRange(ta.value.length, ta.value.length);
        dispatch(ta, new InputEvent('input', { inputType: 'insertCompositionText', data: word, bubbles: true }));
    }

    return { window, ta, sent, chords, compose, advance };
}

/** `echo ` in the field, `he` composed, Ctrl latched on the row, and `l` typed into the word. */
function chordOverComposingWord(p) {
    p.ta.focus();
    p.ta.value = 'echo ';
    p.ta.setSelectionRange(5, 5);
    p.compose('h');
    p.compose('he');
    p.advance(0);
    p.window.__vscodroid.ctrl = true;
    p.compose('hel');
    let marked = 'never blurred';
    p.ta.addEventListener('blur', () => { marked = p.window.__vscodroidChordBlur === p.ta; });
    p.advance(0);
    p.advance(100);
    return { marked };
}

const results = [];
function row(name, fn) {
    try { fn(); results.push(`ok: ${name}`); } catch (error) { results.push(`FAIL: ${name}\n${error.message}`); }
}

row('the word goes out as it was before the chord\'s letter, and the chord after it', () => {
    const p = page();
    const { marked } = chordOverComposingWord(p);
    assert.equal(marked, true, 'the field was not marked while the chord blurred it');
    assert.deepEqual(p.sent, ['he'], 'xterm sent the word with the chord\'s letter in it, or not at all');
    assert.deepEqual(p.chords, ['Ctrl+l']);
    assert.equal(p.ta.value, 'echo he', 'the field\'s text was not put back');
});

row('the mark is gone once the blur is over', () => {
    const p = page();
    chordOverComposingWord(p);
    assert.equal(p.window.__vscodroidChordBlur, null);
});

row('negative control: a terminal script that ignores the mark sends the chord\'s letter with the word', () => {
    const p = page({ terminal: TERMINAL.replace('if (e.target === window.__vscodroidChordBlur) return;', '') });
    chordOverComposingWord(p);
    assert.deepEqual(p.sent, ['hel']);
});

row('negative control: an interceptor that does not mark its blur sends the chord\'s letter with the word', () => {
    const marking = 'if (untouched) window.__vscodroidChordBlur = box;';
    assert.ok(INTERCEPTOR.includes(marking), 'the interceptor marks its blur some other way; point this control at it');
    const p = page({ interceptor: INTERCEPTOR.replace(marking, '') });
    chordOverComposingWord(p);
    assert.deepEqual(p.sent, ['hel']);
});

for (const line of results) console.log(line);
const failed = results.filter((line) => line.startsWith('FAIL'));
assert.equal(failed.length, 0, `${failed.length} chord blur checks failed`);
