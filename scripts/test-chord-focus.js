/**
 * Self-check for what the page does when the workbench answers the first key
 * of a chord, such as the first Esc of Esc Esc in Zen Mode or the Ctrl+K of
 * Ctrl+K Ctrl+S.
 *
 *   node scripts/test-chord-focus.js
 *
 * The workbench switches its IME off while a chord is pending, and the editor
 * does that by moving focus from its EditContext host to a read-only textarea,
 * `.ime-text-area`. A soft keyboard cannot stay up over a read-only box, so the
 * keyboard went down, the extra key row went with it, and the second key had
 * nothing to be tapped on for the five seconds the chord waits. The row's keys
 * are synthetic key events, so the interceptor keeps focus where it is for
 * those. A physical keyboard's keys are trusted, and for those the move has to
 * happen: with the IME on, Gboard hands even a physical letter over as key
 * code 229, which no keybinding takes, so Ctrl+K then Z stops working.
 *
 * The focus call is made inside the keydown's own dispatch, by the keybinding
 * service, so the page tells the two apart by `window.event`. That is state at
 * the moment of the call, which text matching cannot see: a check that read the
 * last key instead of the current one would also hold focus for a call made by
 * a click after a synthetic key.
 *
 * This extracts the real script from `KeyInjector.setupModifierInterceptor()`
 * and runs it under `vm` against a minimal page, with a recording `focus()` on
 * an `HTMLElement` stand-in. Extraction is deliberately strict: if the raw
 * string moves or changes shape this fails saying so, rather than quietly
 * checking an empty string.
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
    const substituted = js.split('$keyLookup').join('{}')
        .split('$RELEASE_MODIFIERS_JS').join('function releaseModifiers() {}');
    assert.ok(!substituted.includes('$'),
        'the interceptor gained a Kotlin interpolation this check does not know how to fill');
    assert.ok(substituted.includes('var EDGE'),
        'the extracted text has no EDGE table, so the wrong block was extracted');
    return substituted;
}

const INTERCEPTOR = extractInterceptor();

/** Anything events can be listened for on; the interceptor only registers here. */
class Target {
    addEventListener() {}
    removeEventListener() {}
}

/**
 * A page with the interceptor installed. `focused` records which element each
 * `focus()` call that reached the platform was made on.
 */
function newPage() {
    const focused = [];
    class HTMLElement extends Target {
        constructor(className, tagName = 'DIV') {
            super();
            this.tagName = tagName;
            this.classList = { contains: (name) => name === className };
        }
        focus() { focused.push(this.classList.contains('ime-text-area') ? 'ime-text-area' : this.tagName); }
    }
    const window = new Target();
    const document = Object.assign(new Target(), { activeElement: null });
    vm.runInContext(INTERCEPTOR, vm.createContext({
        window, document, HTMLElement, navigator: { userAgent: '' },
        setTimeout() {}, queueMicrotask() {},
    }));
    /** Runs `handler` as the workbench's keybinding listener does: inside a dispatch whose `window.event` is `event`. */
    const during = (event, handler) => {
        window.event = event;
        try { handler(); } finally { window.event = undefined; }
    };
    return { window, HTMLElement, focused, during };
}

const cases = [];
const test = (name, fn) => cases.push({ name, fn });

test('a synthetic keydown, as the key row sends, leaves focus on the editor', () => {
    const p = newPage();
    const imeTextArea = new p.HTMLElement('ime-text-area', 'TEXTAREA');
    p.during({ type: 'keydown', isTrusted: false }, () => imeTextArea.focus());
    assert.deepStrictEqual(p.focused, []);
});

test('a trusted keydown, from a physical keyboard, still moves focus to the read-only box', () => {
    const p = newPage();
    const imeTextArea = new p.HTMLElement('ime-text-area', 'TEXTAREA');
    p.during({ type: 'keydown', isTrusted: true }, () => imeTextArea.focus());
    assert.deepStrictEqual(p.focused, ['ime-text-area']);
});

test('a focus call outside any event, or in a click, reaches the platform', () => {
    const p = newPage();
    const imeTextArea = new p.HTMLElement('ime-text-area', 'TEXTAREA');
    imeTextArea.focus();
    p.during({ type: 'click', isTrusted: false }, () => imeTextArea.focus());
    assert.deepStrictEqual(p.focused, ['ime-text-area', 'ime-text-area']);
});

test('a synthetic keyup does not hold the box either way: only the keydown that starts a chord is kept', () => {
    const p = newPage();
    const imeTextArea = new p.HTMLElement('ime-text-area', 'TEXTAREA');
    p.during({ type: 'keyup', isTrusted: false }, () => imeTextArea.focus());
    assert.deepStrictEqual(p.focused, ['ime-text-area']);
});

test('any other element still takes focus during a synthetic keydown, as Quick Open does', () => {
    const p = newPage();
    const quickOpen = new p.HTMLElement('quick-input', 'INPUT');
    const editor = new p.HTMLElement('native-edit-context');
    p.during({ type: 'keydown', isTrusted: false }, () => { quickOpen.focus(); editor.focus(); });
    assert.deepStrictEqual(p.focused, ['INPUT', 'DIV']);
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
