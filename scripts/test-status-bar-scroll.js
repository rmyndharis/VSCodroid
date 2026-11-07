/**
 * Self-check for the status bar's right-hand entries on a narrow touch screen.
 *
 *   node scripts/test-status-bar-scroll.js
 *
 * The workbench lays those entries out as a wrapping row inside a bar that clips
 * whatever wraps, and the wrap drops the highest-priority entries first. At the
 * 411px a phone gives in portrait, with a file open, six of twelve entries (Ln/Col,
 * Spaces, UTF-8, LF, language mode and status) were out of reach. The stylesheet in
 * `MainActivity.injectTouchTargetCSS()` keeps them on one row, and the script in the
 * same raw string scrolls that row by hand, because the workbench's gesture layer
 * takes a pan that starts on an entry and the browser then never scrolls anything
 * (measured on an API 37 emulator: the touch events stop before the bubble phase and
 * no pointercancel follows).
 *
 * What the script has to get right is behaviour, so this extracts the real raw
 * string, runs it under `vm` against a stub window and document, and drives the
 * listeners it installs with fake touches. The row is `row-reverse`: its scrollLeft
 * is 0 at the right end and goes negative towards the hidden entries, so a finger
 * moving right must make it more negative, and neither end may be passed.
 *
 * Only the script is exercised. The stylesheet text is checked for the rule and for
 * being inside the coarse-pointer block, and nothing here lays it out: whether the
 * row really scrolls in Chromium is a device fact.
 *
 * NEGATIVE CONTROL, measured, each by editing the script and running this: flipping
 * the sign of the finger's distance fails six cases; dropping the clamp at the last
 * hidden entry fails two and dropping the clamp at the right end fails one; leaving
 * the pan running after touchend and touchcancel fails the case that lifts the finger;
 * registering the listeners without the capture phase, or without passive, fails the
 * case that reads their options; looking the row up by `.right-items` alone fails
 * seven; not ending the pan when a second finger comes down fails that case, and
 * accepting a pan that starts with two fingers fails its own; removing the stylesheet
 * rule, or its `min-width: 0`, fails the first case; dropping the touchcancel
 * listener fails three.
 *
 * Extraction is deliberately strict. If the raw string moves or changes shape this
 * fails saying so, rather than quietly checking an empty string.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const MAIN_ACTIVITY = path.join(
    __dirname, '..', 'android/app/src/main/kotlin/com/vscodroid/MainActivity.kt',
);

const STRIP_SELECTOR = '.part.statusbar > .right-items';

/**
 * The body of the raw string in `injectTouchTargetCSS()`, with the indentation
 * `trimIndent()` removes taken off, which is the text the WebView is given.
 */
function extractScript() {
    const lines = fs.readFileSync(MAIN_ACTIVITY, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('private fun injectTouchTargetCSS()'));
    assert.notStrictEqual(
        fn, -1,
        'injectTouchTargetCSS() is gone from MainActivity.kt, so this check has nothing to ' +
        'run. If the script moved, point this at its new home rather than deleting the check.',
    );

    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close !== -1 && close > open + 1,
        'could not find the raw string in injectTouchTargetCSS(); its shape changed');

    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
    assert.ok(!js.includes('$'),
        'the script gained a Kotlin interpolation this check does not know how to fill');
    assert.ok(js.includes("getElementById('vscodroid-touch-css')"),
        'the extracted text is not the touch-target script, so the wrong block was extracted');
    return js;
}

const SCRIPT = extractScript();

/**
 * A page the script can be run in: a document that remembers what was appended to
 * its head, a window that remembers its listeners, and one status bar row with the
 * scroll geometry a case asks for.
 */
function makePage({ scrollWidth = 665, clientWidth = 273 } = {}) {
    const listeners = [];
    const appended = [];
    const asked = [];
    const strip = { scrollWidth, clientWidth, scrollLeft: 0 };
    const entry = {
        closest(selector) {
            asked.push(selector);
            return selector === STRIP_SELECTOR ? strip : null;
        },
    };
    const elsewhere = { closest() { return null; } };
    const document = {
        getElementById: (id) => appended.find((e) => e.id === id) || null,
        createElement: () => ({ id: '', textContent: '' }),
        head: { appendChild: (el) => appended.push(el) },
    };
    const window = {
        addEventListener: (type, fn, options) => listeners.push({ type, fn, options }),
    };
    const context = vm.createContext({ window, document });
    return {
        strip, entry, elsewhere, listeners, appended, asked,
        run: () => vm.runInContext(SCRIPT, context),
        fire(type, event) {
            for (const l of listeners.filter((x) => x.type === type)) l.fn(event);
        },
    };
}

function touches(target, ...xs) {
    return { target, touches: xs.map((clientX) => ({ clientX })) };
}

/** Puts a finger down at `from`, moves it through `to`, and lifts it. */
function drag(page, target, from, ...to) {
    page.fire('touchstart', touches(target, from));
    for (const x of to) page.fire('touchmove', touches(target, x));
    page.fire('touchend', touches(target));
}

const cases = [];
const check = (name, fn) => cases.push({ name, fn });

check('the stylesheet keeps the entries on one row, inside the coarse-pointer block', () => {
    const page = makePage();
    page.run();
    assert.strictEqual(page.appended.length, 1, 'the script appends exactly one style element');
    const css = page.appended[0].textContent;
    const open = css.indexOf('@media (pointer: coarse) {');
    assert.notStrictEqual(open, -1, 'the coarse-pointer block is gone');
    const rule = css.indexOf(STRIP_SELECTOR + ' {');
    assert.ok(rule > open, 'the rule is missing, or sits before the coarse-pointer block');
    // The first closing brace at depth zero after the block opened ends it.
    let depth = 0, end = -1;
    for (let i = open; i < css.length; i++) {
        if (css[i] === '{') depth++;
        if (css[i] === '}' && --depth === 0) { end = i; break; }
    }
    assert.ok(rule < end, 'the rule is outside the coarse-pointer block');
    const declarations = css.slice(rule, css.indexOf('}', rule));
    for (const d of ['flex-wrap: nowrap', 'overflow-x: hidden', 'min-width: 0']) {
        assert.ok(declarations.includes(d), `the rule lost ${d}`);
    }
});

check('four listeners on the window, in the capture phase and passive', () => {
    const page = makePage();
    page.run();
    assert.deepStrictEqual(
        page.listeners.map((l) => l.type).sort(),
        ['touchcancel', 'touchend', 'touchmove', 'touchstart'],
    );
    for (const l of page.listeners) {
        assert.strictEqual(l.options && l.options.capture, true,
            `${l.type} must be captured, or the workbench's own handler stops it first`);
        assert.strictEqual(l.options && l.options.passive, true,
            `${l.type} must be passive, or scrolling the page waits on this handler`);
    }
});

check('a finger moving right scrolls the row towards the hidden entries by that distance', () => {
    const page = makePage();
    page.run();
    drag(page, page.entry, 200, 250, 300);
    assert.strictEqual(page.strip.scrollLeft, -100);
});

check('the row follows the finger back, and stops at the right end', () => {
    const page = makePage();
    page.run();
    page.fire('touchstart', touches(page.entry, 200));
    page.fire('touchmove', touches(page.entry, 300));
    assert.strictEqual(page.strip.scrollLeft, -100);
    page.fire('touchmove', touches(page.entry, 260));
    assert.strictEqual(page.strip.scrollLeft, -60);
    page.fire('touchmove', touches(page.entry, 100));
    assert.strictEqual(page.strip.scrollLeft, 0, 'the row passed its right end');
});

check('the row stops at the last hidden entry', () => {
    const page = makePage();
    page.run();
    drag(page, page.entry, 0, 1000);
    assert.strictEqual(page.strip.scrollLeft, -(665 - 273), 'the row passed its left end');
});

check('the next swipe continues from where the row was left', () => {
    const page = makePage();
    page.run();
    drag(page, page.entry, 200, 300);
    drag(page, page.entry, 200, 250);
    assert.strictEqual(page.strip.scrollLeft, -150);
});

check('a row that fits does not move', () => {
    const page = makePage({ scrollWidth: 273, clientWidth: 273 });
    page.run();
    drag(page, page.entry, 200, 300);
    // `===` and not strictEqual: Math.max(-0, ...) leaves -0, which scrollLeft treats as 0.
    assert.ok(page.strip.scrollLeft === 0, `the row moved to ${page.strip.scrollLeft}`);
});

check('a touch that starts anywhere else leaves the row alone', () => {
    const page = makePage();
    page.run();
    drag(page, page.elsewhere, 200, 300);
    assert.strictEqual(page.strip.scrollLeft, 0);
});

check('a second finger ends the pan, and the pan does not resume when it lifts', () => {
    const page = makePage();
    page.run();
    page.fire('touchstart', touches(page.entry, 200));
    page.fire('touchmove', touches(page.entry, 220));
    assert.strictEqual(page.strip.scrollLeft, -20);
    page.fire('touchmove', touches(page.entry, 230, 400));
    page.fire('touchmove', touches(page.entry, 300));
    assert.strictEqual(page.strip.scrollLeft, -20);
});

check('a pan that starts with two fingers is not taken', () => {
    const page = makePage();
    page.run();
    page.fire('touchstart', touches(page.entry, 200, 400));
    page.fire('touchmove', touches(page.entry, 300));
    assert.strictEqual(page.strip.scrollLeft, 0);
});

check('touchend and touchcancel each end the pan', () => {
    for (const ending of ['touchend', 'touchcancel']) {
        const page = makePage();
        page.run();
        page.fire('touchstart', touches(page.entry, 200));
        page.fire('touchmove', touches(page.entry, 250));
        page.fire(ending, touches(page.entry));
        page.fire('touchmove', touches(page.entry, 350));
        assert.strictEqual(page.strip.scrollLeft, -50, `a move after ${ending} still scrolled the row`);
    }
});

check('the row is looked up by its own selector and nothing broader', () => {
    const page = makePage();
    page.run();
    drag(page, page.entry, 200, 300);
    assert.ok(page.asked.length > 0, 'closest() was never asked');
    for (const selector of page.asked) assert.strictEqual(selector, STRIP_SELECTOR);
});

check('running the script again installs nothing twice', () => {
    const page = makePage();
    page.run();
    page.run();
    assert.strictEqual(page.appended.length, 1);
    assert.strictEqual(page.listeners.length, 4);
});

let failed = 0;
for (const { name, fn } of cases) {
    try {
        fn();
        console.log(`  ok    ${name}`);
    } catch (e) {
        failed++;
        console.log(`  FAIL  ${name}\n        ${String(e.message).split('\n').join('\n        ')}`);
    }
}
console.log(failed ? `${failed} of ${cases.length} failed` : `all ${cases.length} cases passed`);
process.exit(failed ? 1 : 0);
