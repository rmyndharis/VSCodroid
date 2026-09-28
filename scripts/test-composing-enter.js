/**
 * Self-check for the script that makes a soft keyboard's Enter reach the
 * workbench as Enter.
 *
 *   node scripts/test-composing-enter.js
 *
 * The script is JavaScript inside the Kotlin raw string of
 * `MainActivity.injectComposingEnter()`, handed to `evaluateJavascript`, so
 * nothing compiles it and `ComposingEnterWiringTest` can only match its text.
 * It handles two Enters Gboard sends. A composing one is replaced by an Enter
 * the workbench recognises. A non-composing one in a single-line input (the
 * IME action GO) arrives with an empty `code`, and the workbench resolves
 * keybindings from `code`, so the Command Palette, Quick Open and every
 * input box ignore it; the script fills the `code` in. Which Enter takes
 * which branch, and that a latched Ctrl or Alt keeps the empty `code` so the
 * key row can still build its chord from the `beforeinput` that follows, is
 * the kind of thing that stays textually present while being wrong.
 *
 * This extracts the real script, runs it under `vm` against a stub window, and
 * drives the keydown listener it installs with fake events. `code` is a
 * read-only accessor on the fake event, as it is on a real KeyboardEvent, so
 * assigning it does nothing and only `Object.defineProperty` changes it.
 *
 * NEGATIVE CONTROL, measured: against the script as it was before the fill
 * (main at 2ff780ca) the three cases that expect code Enter fail and the other
 * six pass. Writing `e.code = 'Enter'` in place of the `defineProperty` fails
 * the same three.
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
 * The body of the raw string in `injectComposingEnter()`, with the indentation
 * `trimIndent()` removes taken off, which is the text the WebView is given.
 */
function extractScript() {
    const lines = fs.readFileSync(MAIN_ACTIVITY, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('private fun injectComposingEnter()'));
    assert.notStrictEqual(
        fn, -1,
        'injectComposingEnter() is gone from MainActivity.kt, so this check has nothing to ' +
        'run. If the script moved, point this at its new home rather than deleting the check.',
    );

    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close !== -1 && close > open + 1,
        'could not find the raw string in injectComposingEnter(); its shape changed');

    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
    assert.ok(!js.includes('$'),
        'the script gained a Kotlin interpolation this check does not know how to fill');
    assert.ok(js.includes("addEventListener('keydown'"),
        'the extracted text installs no keydown listener, so the wrong block was extracted');
    return js;
}

class FakeKeyboardEvent {
    constructor(type, init = {}) {
        this.type = type;
        this.init = init;
    }
    get key() { return this.init.key || ''; }
    get code() { return this.init.code || ''; }
    get keyCode() { return this.init.keyCode || 0; }
    get isComposing() { return !!this.init.isComposing; }
}

class FakeCompositionEvent {
    constructor(type, init = {}) {
        this.type = type;
        this.data = init.data;
    }
}

const listeners = [];
const win = {
    addEventListener: (type, fn, capture) => listeners.push({ type, fn, capture }),
};
vm.runInContext(extractScript(), vm.createContext({
    window: win,
    KeyboardEvent: FakeKeyboardEvent,
    CompositionEvent: FakeCompositionEvent,
}));

const keydowns = listeners.filter((l) => l.type === 'keydown');
assert.strictEqual(keydowns.length, 1, 'expected one keydown listener on the window');
assert.ok(keydowns[0].capture,
    'the keydown listener is not in the capture phase, so the workbench sees the key first');
const onKeydown = keydowns[0].fn;
const onCompositionUpdate = listeners.find((l) => l.type === 'compositionupdate').fn;

/**
 * What the listener did to one keydown: its `code` afterwards, whether that
 * is now an own property (defined by the script), whether the real key was
 * stopped or cancelled, and what was dispatched in its place.
 */
function press(init, { latched, inEditor = false } = {}) {
    win.__vscodroid = latched && { ctrl: false, alt: false, shift: false, ...latched };
    const dispatched = [];
    const target = {
        tagName: 'INPUT',
        closest: () => (inEditor ? {} : null),
        dispatchEvent: (ev) => {
            dispatched.push(ev.type === 'keydown'
                ? `keydown ${ev.key} ${ev.code} ${ev.keyCode}`
                : `${ev.type} ${ev.data}`);
            return true;
        },
    };
    const e = new FakeKeyboardEvent('keydown', { keyCode: 13, ...init });
    let stopped = false;
    let prevented = false;
    e.target = target;
    e.stopImmediatePropagation = () => { stopped = true; };
    e.preventDefault = () => { prevented = true; };
    onKeydown(e);
    return {
        code: e.code,
        defined: Object.prototype.hasOwnProperty.call(e, 'code'),
        stopped,
        prevented,
        dispatched,
    };
}

const NO_LATCH = {};
const filled = { code: 'Enter', defined: true, stopped: false, prevented: false, dispatched: [] };
const untouched = (code) =>
    ({ code, defined: false, stopped: false, prevented: false, dispatched: [] });
const gboardEnter = { key: 'Enter', code: '' };

const cases = [
    ['a code-less Enter gets code Enter and goes on to the workbench',
        press(gboardEnter, { latched: NO_LATCH }), filled],
    ['the same before the key row has set up its latch state',
        press(gboardEnter), filled],
    ['the same inside the editor, where its rename box lives',
        press(gboardEnter, { latched: NO_LATCH, inEditor: true }), filled],
    ['with Ctrl latched it keeps code "" for the key row to chord',
        press(gboardEnter, { latched: { ctrl: true } }), untouched('')],
    ['with Alt latched it keeps code "" for the key row to chord',
        press(gboardEnter, { latched: { alt: true } }), untouched('')],
    ['an Enter that already has code Enter is left alone',
        press({ key: 'Enter', code: 'Enter' }, { latched: NO_LATCH }), untouched('Enter')],
    ['a code-less key that is not Enter is left alone',
        press({ key: 'Unidentified', code: '', keyCode: 229 }, { latched: NO_LATCH }),
        untouched('')],
];

onCompositionUpdate({ data: 'alpha' });
cases.push(['a composing Enter is still stopped and replaced',
    press({ ...gboardEnter, isComposing: true }, { latched: NO_LATCH }),
    {
        code: '',
        defined: false,
        stopped: true,
        prevented: false,
        dispatched: ['compositionend alpha', 'keydown Enter Enter 13'],
    }]);
cases.push(['a composing Enter inside the editor is left alone',
    press({ ...gboardEnter, isComposing: true }, { latched: NO_LATCH, inEditor: true }),
    untouched('')]);

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
