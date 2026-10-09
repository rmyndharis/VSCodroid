/**
 * Self-check for the script that gives a plain-text page, and the WebView's own error
 * page, the background of its own colour scheme.
 *
 *   node scripts/test-plain-text-page.js
 *
 * The server answers a request it refuses with a bare text/plain body, "Forbidden."
 * among them, and the WebView answers a load that failed, such as a folder switch
 * while the editor server restarts, with "Webpage not available", an HTML page at
 * chrome-error://chromewebdata/. Neither paints a background, so behind its text is
 * the WebView's own, the colour the workbench page last painted, which is dark under
 * a dark theme. The script is JavaScript inside the Kotlin raw string of
 * `plainTextPageScript()` in `VSCodroidWebView.kt`, which
 * `addPlainTextPageScript` registers to run at the start of every document of every
 * origin, so nothing compiles it and the Kotlin suite can only see that it is
 * registered. This runs the real script under `vm` against fake documents: a
 * top-level plain-text page and a top-level error page are given the `Canvas`
 * background, an HTML page such as the workbench and a frame of either kind are given
 * nothing, and the script leaves no name behind in any page it runs in. What `Canvas`
 * then paints is Chromium's to decide: measured in Chromium 151 over a #1E1E1E
 * background, white under black text in light mode and the dark canvas under white
 * text in dark mode for the plain-text page, and white under black text in both
 * modes for the error page, which names no colour scheme.
 *
 * NEGATIVE CONTROL, run: without the style sheet, without the top-frame test,
 * without the type test, without the error-page test, and with the function wrapper
 * taken off, a case fails.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SOURCE = path.join(
    __dirname, '..', 'android/app/src/main/kotlin/com/vscodroid/webview/VSCodroidWebView.kt',
);

/** The raw string after the declaration, as `trimIndent()` hands it to the WebView. */
function extract() {
    const declaration = 'internal fun plainTextPageScript()';
    const lines = fs.readFileSync(SOURCE, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes(declaration));
    assert.notStrictEqual(fn, -1,
        `${declaration} is gone from VSCodroidWebView.kt, so this check has nothing to run. ` +
        'If it moved, point this at its new home rather than deleting the check.');
    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close > open + 1,
        `could not find the raw string after ${declaration}; its shape changed`);
    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
    // A bare $ is interpolated by Kotlin before the WebView sees the string.
    assert.ok(!js.includes('$'), 'the script has a $, which Kotlin interpolates');
    return js;
}

const SCRIPT = extract();

/** One document the script starts in; answers its adopted style sheets and globals. */
function run({ type, protocol = 'http:', top = true }) {
    class CSSStyleSheet {
        replaceSync(text) { this.text = text; }
    }
    const page = { document: { contentType: type, adoptedStyleSheets: [] }, location: { protocol }, CSSStyleSheet };
    page.window = page;
    page.top = top ? page : {};
    const context = vm.createContext(page);
    const before = Object.keys(context);
    vm.runInContext(SCRIPT, context);
    return {
        // Out of the page's realm, which deepStrictEqual compares by prototype.
        sheets: Array.from(page.document.adoptedStyleSheets, (s) => s.text),
        added: Object.keys(context).filter((k) => !before.includes(k)),
    };
}

const CANVAS = [':root { background: Canvas; }'];

// How Chromium commits the page for a load that failed: an HTML document at
// kUnreachableWebDataURL, chrome-error://chromewebdata/.
const ERROR_PAGE = { type: 'text/html', protocol: 'chrome-error:' };

const forbidden = run({ type: 'text/plain' });
assert.deepStrictEqual(forbidden.sheets, CANVAS,
    'a top-level plain-text page, such as the server\'s "Forbidden.", is not given the ' +
    'background of its own scheme, so under a dark theme its black text sits on the ' +
    'WebView\'s dark background in light mode');

assert.deepStrictEqual(run(ERROR_PAGE).sheets, CANVAS,
    'the WebView\'s own error page, "Webpage not available" for a load that failed, is not ' +
    'given the background of its own scheme, so under a dark theme its black text sits on ' +
    'the WebView\'s dark background in both modes');

for (const [label, doc] of [
    ['an HTML page, such as the workbench, which colours itself,', { type: 'text/html' }],
    ['a plain-text frame, behind which is the page around it,', { type: 'text/plain', top: false }],
    ['an error page in a frame, behind which is the page around it,', { ...ERROR_PAGE, top: false }],
]) {
    assert.deepStrictEqual(run(doc).sheets, [], `${label} is given the plain-text background`);
}

for (const [label, doc] of [
    ['a text/plain page', { type: 'text/plain' }],
    ['a text/html page', { type: 'text/html' }],
    ['the error page', ERROR_PAGE],
]) {
    assert.deepStrictEqual(run(doc).added, [],
        `the script leaves a global behind in ${label}, where every page script shares the ` +
        'window it runs in');
}

console.log('ok: a top-level plain-text page and the WebView\'s own error page are given the ' +
    'background of their own scheme, an HTML page and a frame are not, and no page is left a global');
