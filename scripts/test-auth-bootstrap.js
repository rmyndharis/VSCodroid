'use strict';

// Execute the simple Kotlin completion callback with controlled page and registration results.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../android/app/src/main/kotlin/com/vscodroid/MainActivity.kt'), 'utf8');
const method = source.slice(source.indexOf('private fun injectBridgeToken()'));
const marker = '.trimIndent()) { result ->';
const start = method.indexOf(marker) + marker.length;
assert.ok(start >= marker.length, 'bootstrap completion callback was not found');
let depth = 1, end = start;
for (; end < method.length && depth; end++) {
    if (method[end] === '{') depth++;
    if (method[end] === '}') depth--;
}
assert.equal(depth, 0, 'bootstrap completion callback was not closed');
const callback = method.slice(start, end - 1)
    .split('\n').filter(line => !line.trimStart().startsWith('//')).join('\n')
    .replace(/\bval /g, 'const ').replace(/return@evaluateJavascript/g, 'return');

const injections = ['injectSafeAreaCSS', 'injectBridgeRelay', 'injectMemoryPressureHandler',
    'injectTouchTargetCSS', 'injectKeyboardGuard', 'injectTouchContextMenu', 'injectCaretDrag',
    'injectListEditKeeper', 'injectComposingEnter', 'injectLeaveCurtain', 'injectWindowOpenOverride',
    'injectClipboardReadFallback', 'injectDownloadCapture'];

function run(code, verdict, { staleView = false, stalePage = false } = {}) {
    const events = [], view = {};
    const sandbox = {
        webView: staleView ? {} : view, targetView: view, generation: 7, workbenchLoaded: false,
        authBootstrapVerdict: () => verdict,
        client: { authPage: () => ({ generation: stalePage ? 8 : 7 }),
            completeAuthBootstrap: (_, generation, ready) => { assert.equal(generation, 7); events.push('complete:' + ready); } },
        extraKeyRow: { keyInjector: { setupModifierInterceptor: () => events.push('modifier') } },
    };
    for (const name of injections) sandbox[name] = () => events.push(name);
    vm.runInNewContext(`(function(result) { ${code}\n})(null);`, sandbox);
    return { events, ready: sandbox.workbenchLoaded };
}

function checks(code) {
    const injected = ['modifier', ...injections];
    assert.deepEqual(run(code, true), { events: [...injected, 'complete:true'], ready: true });
    assert.deepEqual(run(code, false), { events: ['complete:false', ...injected], ready: false });
    assert.deepEqual(run(code, null), { events: ['complete:false'], ready: false });
    for (const stale of [{ staleView: true }, { stalePage: true }]) {
        assert.deepEqual(run(code, true, stale), { events: [], ready: false });
    }
}

checks(callback);
for (const [before, after] of [
    ['if (verdict == null)', 'if (verdict != true)'],
    ['if (verdict) {', 'if (verdict != null) {'],
    ['if (verdict != true) client.completeAuthBootstrap(targetView, generation, false)', ''],
    ['?.generation != generation', '?.generation == generation'],
]) {
    assert.ok(callback.includes(before), 'mutation target absent: ' + before);
    assert.throws(() => checks(callback.replace(before, after)), undefined, 'undetected mutation: ' + before);
}
console.log('ok: actual bootstrap completion, stale pages, injection ordering, and four mutations');
