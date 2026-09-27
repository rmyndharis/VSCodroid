/**
 * Self-check for the guard that ends an arrow at the edge of a text box.
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
 * real edges to spatial navigation, which moves focus out of the box.
 *
 * This extracts the real script, runs it under `vm` against a stub window and
 * document, and drives the keydown listener it installs with fake events.
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
 * indentation `trimIndent()` removes taken off. `$keyLookup` is the one
 * interpolation, and the guard does not read it, so an empty table stands in.
 */
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
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');

    const substituted = js.split('$keyLookup').join('{}');
    assert.ok(!substituted.includes('$'),
        'the interceptor gained a Kotlin interpolation this check does not know how to fill');
    assert.ok(substituted.includes('var EDGE'),
        'the extracted text has no EDGE table, so the wrong block was extracted or the guard ' +
        'is gone');
    return substituted;
}

/** Runs the script once and returns the keydown listener it adds to the window. */
function installGuard() {
    const listeners = [];
    const target = (where) => ({
        addEventListener: (type, fn, capture) => listeners.push({ where, type, fn, capture }),
    });
    const sandbox = { document: { ...target('document'), activeElement: null } };
    sandbox.window = { ...target('window') };
    vm.runInContext(extractInterceptor(), vm.createContext(sandbox));

    const keydown = listeners.filter((l) => l.where === 'window' && l.type === 'keydown');
    assert.strictEqual(keydown.length, 1, 'expected one keydown listener on the window');
    assert.ok(!keydown[0].capture,
        'the guard listens in the capture phase, so it cancels an arrow before the editor ' +
        'or the quick input has had the chance to use it');
    return keydown[0].fn;
}

const guard = installGuard();

/**
 * Whether the guard cancels `key` on `box`. The box is reached only through
 * `composedPath()[0]`, the way a box inside a shadow root is: at the window,
 * `target` is its shadow host.
 */
function cancels(key, box, opts = {}) {
    let prevented = false;
    guard({
        key,
        target: { tagName: 'DIV' },
        defaultPrevented: !!opts.handled,
        shiftKey: !!opts.shift,
        composedPath: () => [box],
        preventDefault() { prevented = true; },
    });
    return prevented;
}

const box = (value, start, end, direction = 'none', tagName = 'INPUT') =>
    ({ tagName, value, selectionStart: start, selectionEnd: end, selectionDirection: direction });

const cases = [
    ['Left at the start', cancels('ArrowLeft', box('abc', 0, 0)), true],
    ['Up at the start', cancels('ArrowUp', box('abc', 0, 0)), true],
    ['Right at the end', cancels('ArrowRight', box('abc', 3, 3)), true],
    ['Down at the end', cancels('ArrowDown', box('abc', 3, 3)), true],
    ['Left in the middle moves the caret', cancels('ArrowLeft', box('abc', 1, 1)), false],
    ['Right at the start moves the caret', cancels('ArrowRight', box('abc', 0, 0)), false],
    ['Left at the end moves the caret', cancels('ArrowLeft', box('abc', 3, 3)), false],
    ['an arrow without Shift collapses a selection', cancels('ArrowRight', box('abc', 1, 3)), false],
    ['Shift+Right with the selection already at the end',
        cancels('ArrowRight', box('abc', 1, 3, 'forward'), { shift: true }), true],
    ['Shift+Left with a backward selection at the start',
        cancels('ArrowLeft', box('abc', 0, 2, 'backward'), { shift: true }), true],
    ['Shift+Right moves the start of a backward selection',
        cancels('ArrowRight', box('abc', 0, 2, 'backward'), { shift: true }), false],
    ['an arrow a handler already used', cancels('ArrowLeft', box('abc', 0, 0), { handled: true }), false],
    ['Tab is not an arrow', cancels('Tab', box('abc', 3, 3)), false],
    ['Home is not an arrow', cancels('Home', box('abc', 0, 0)), false],
    ['a target that takes no text', cancels('ArrowLeft', { tagName: 'DIV' }), false],
    ['an input with no caret, such as a checkbox', cancels('ArrowLeft', box('', null, null)), false],
    ['Down at the end of a textarea',
        cancels('ArrowDown', box('a\nb', 3, 3, 'none', 'TEXTAREA')), true],
    ['Right in an empty box', cancels('ArrowRight', box('', 0, 0)), true],
];

let failed = 0;
for (const [name, got, want] of cases) {
    if (got === want) {
        console.log(`ok   ${name}`);
    } else {
        failed += 1;
        console.error(`FAIL ${name}: ${want ? 'expected the arrow cancelled' : 'expected it left alone'}`);
    }
}
console.log(`\n${cases.length - failed}/${cases.length} passed`);
process.exit(failed ? 1 : 0);
