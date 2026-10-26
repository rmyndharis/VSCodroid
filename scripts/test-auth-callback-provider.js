'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { execFileSync } = require('node:child_process');
const { stripTypeScriptTypes } = require('node:module');

const root = path.join(__dirname, '..');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'callback-provider-'));
let source;
let callbackSource;
try {
    const target = path.join(temporary, 'src/vs/code/browser/workbench/workbench.ts');
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(__dirname, 'fixtures/callback-provider.ts'), target);
    execFileSync('git', ['apply', path.join(root, 'patches/0019-callback-nonce.patch')], { cwd: temporary });
    source = stripTypeScriptTypes(fs.readFileSync(target, 'utf8'), { mode: 'transform' });
    const callback = path.join(temporary, 'src/vs/code/browser/workbench/callback.html');
    fs.copyFileSync(path.join(__dirname, 'fixtures/callback.html'), callback);
    execFileSync('git', ['apply', path.join(root, 'patches/0006-callback-intent-relay.patch')], { cwd: temporary });
    callbackSource = fs.readFileSync(callback, 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];
} finally {
    fs.rmSync(temporary, { recursive: true, force: true });
}

function fixture({ bridge = true, ready = false, accept = true, nativeGeneration = 7 } = {}) {
    const listeners = new Map();
    const calls = [];
    const storage = new Map();
    const delivered = [];
    const errors = [];
    const writes = [];
    const window = {
        location: { href: 'http://127.0.0.1:13337/', origin: 'http://127.0.0.1:13337', pathname: '/' },
        addEventListener(name, callback) { listeners.set(name, callback); },
        removeEventListener(name) { listeners.delete(name); },
        dispatchEvent(event) { listeners.get(event.type)?.(event); },
    };
    window.top = window;
    if (bridge) window.AndroidBridge = {
        isAuthPageCurrent(token, generation) { assert.equal(token, 'session'); return generation === nativeGeneration; },
        registerAuthCallback(...args) { calls.push(args); return typeof accept === 'function' ? accept(...args) : accept; },
    };
    if (ready) window.__vscodroid = { authToken: 'session', pageGeneration: 7 };
    class Disposable {
        constructor() { this.disposables = []; }
        _register(value) { this.disposables.push(value); return value; }
        dispose() { this.disposables.forEach(value => value.dispose()); }
    }
    const context = {
        mainWindow: window, window, location: window.location, AndroidBridge: window.AndroidBridge,
        Event: class { constructor(type) { this.type = type; } },
        StorageEvent: class { constructor(type, properties) { this.type = type; Object.assign(this, properties); } },
        Disposable,
        Emitter: class { event() {} fire(value) { delivered.push(value); } dispose() {} },
        addDisposableListener(target, name, callback) {
            target.addEventListener(name, callback);
            return { dispose() { target.removeEventListener(name); } };
        },
        crypto: require('node:crypto').webcrypto,
        URI: { parse() { return { with(parts) { return parts; } }; }, revive(value) { return value; } },
        localStorage: {
            setItem(key, value) { writes.push([key, value]); storage.set(key, value); },
            removeItem(key) { storage.delete(key); },
            getItem(key) { return storage.get(key) ?? null; },
        },
        setTimeout, clearTimeout, console: { error(error) { errors.push(error); } },
    };
    vm.runInNewContext(source + '\nglobalThis.Provider = LocalStorageURLCallbackProvider;', context);
    return { provider: new context.Provider('/callback'), window, calls, listeners, storage, delivered, errors, writes, context };
}

{
    const { provider, calls, window } = fixture({ ready: true });
    const callback = provider.create({ scheme: 'vscodroid', authority: 'example.test' });
    const query = new URLSearchParams(callback.query);
    assert.equal(calls.length, 1, 'create must register its actual minted identity before returning');
    assert.equal(calls[0][0], query.get('vscode-reqid'));
    assert.equal(calls[0][1], query.get('vscodroid-nonce'));
    assert.equal(calls[0][2], window.__vscodroidCallbackDocumentId);
    assert.equal(calls[0][3], 7);
    assert.equal(calls[0][4], 'session');
}
{
    const { provider, calls, window, listeners } = fixture();
    const callbacks = Array.from({ length: 32 }, () => provider.create({ authority: 'example.test' }));
    assert.equal(calls.length, 0);
    assert.throws(() => provider.create(), /Too many pending sign-in requests/);
    window.__vscodroid = { authToken: 'session', pageGeneration: 9, authCallbacksReady: true };
    window.dispatchEvent({ type: 'vscodroid-bridge-ready', detail: { id: '999', nonce: 'chosen' } });
    assert.equal(calls.length, 32);
    for (let index = 0; index < 32; index++) {
        const query = new URLSearchParams(callbacks[index].query);
        assert.equal(calls[index][0], query.get('vscode-reqid'));
        assert.equal(calls[index][1], query.get('vscodroid-nonce'));
        assert.equal(calls[index][2], window.__vscodroidCallbackDocumentId);
        assert.equal(calls[index][3], 9);
    }
    window.dispatchEvent({ type: 'vscodroid-bridge-ready' });
    assert.equal(calls.length, 32, 'repeated ready events must not duplicate registrations');
    provider.dispose();
    assert.equal(listeners.has('vscodroid-bridge-ready'), false);
}
{
    const { provider, calls, window } = fixture({ accept: false });
    provider.create({ authority: 'example.test' });
    window.__vscodroid = { authToken: 'session', pageGeneration: 10, authCallbacksReady: true };
    window.dispatchEvent({ type: 'vscodroid-bridge-ready' });
    assert.equal(window.__vscodroid.authCallbacksReady, false,
        'a swallowed event-listener exception must explicitly refuse bootstrap completion');
    assert.equal(calls.length, 1);
    window.AndroidBridge.registerAuthCallback = (...args) => { calls.push(args); return true; };
    window.__vscodroid.authCallbacksReady = true;
    window.dispatchEvent({ type: 'vscodroid-bridge-ready' });
    assert.equal(calls.length, 2, 'a refused queued identity must remain available for a later flush');
    assert.equal(calls[1][0], calls[0][0]);
    assert.equal(calls[1][1], calls[0][1]);
}
{
    const { provider, calls, delivered, storage } = fixture({ ready: true, accept: false });
    assert.throws(() => provider.create({ authority: 'example.test' }), /could not register/);
    storage.set('vscode-web.url-callbacks[1]', '{"scheme":"vscodroid","authority":"example.test"}');
    provider.checkCallbacks();
    assert.equal(delivered.length, 0, 'a refused create must not leave a pending consumer');
    assert.equal(calls.length, 1);
}
{
    const { provider, calls } = fixture({ ready: true });
    provider.create({ authority: 'vscode.github-authentication', path: '/dummy' });
    assert.equal(calls.length, 0, 'the dummy callback has no pending consumer and must not register');
}
{
    const { provider, calls } = fixture({ bridge: false });
    for (let index = 0; index < 40; index++) provider.create({ authority: 'example.test' });
    assert.equal(calls.length, 0, 'ordinary browser operation must not require Android');
}
{
    const first = fixture({ ready: true });
    const second = fixture({ ready: true });
    const documentId = first.window.__vscodroidCallbackDocumentId;
    assert.match(documentId, /^[0-9a-f]{32}$/, 'the actual provider must own an immutable document identity');
    assert.notEqual(documentId, second.window.__vscodroidCallbackDocumentId);
    assert.throws(() => { first.window.__vscodroidCallbackDocumentId = 'chosen'; }, TypeError);
    assert.throws(() => Object.defineProperty(first.window, '__vscodroidCallbackDocumentId', { value: 'chosen' }), TypeError);
    first.provider.dispose();
    assert.equal(first.window.__vscodroidCallbackDocumentId, documentId);
}
const activity = fs.readFileSync(path.join(root, 'android/app/src/main/kotlin/com/vscodroid/MainActivity.kt'), 'utf8');
const injection = activity.slice(activity.indexOf('private fun injectBridgeToken()'))
    .match(/targetView\.evaluateJavascript\("""([\s\S]*?)"""\.trimIndent\(\)\)/);
assert.ok(injection, 'the real bridge bootstrap script was not found');
const bootstrap = injection[1]
    .replaceAll('$origin', JSON.stringify('http://127.0.0.1:13337'))
    .replaceAll('$token', JSON.stringify('session'))
    .replaceAll('$generation', '7');
{
    const { provider, calls, context } = fixture();
    provider.create({ authority: 'example.test' });
    assert.equal(calls.length, 0);
    assert.equal(JSON.stringify(vm.runInNewContext(bootstrap, context)), '[7,true]');
    assert.equal(calls.length, 1, 'bootstrap must flush genuine queued issuance before reporting success');
    provider.create({ authority: 'example.test' });
    assert.equal(calls.length, 2, 'late create must register before native completion is observed');
}
{
    const { provider, calls, context, errors } = fixture({ accept: false });
    provider.create({ authority: 'example.test' });
    assert.equal(JSON.stringify(vm.runInNewContext(bootstrap, context)), '[7,false]');
    assert.equal(calls.length, 1);
    assert.equal(errors.length, 1);
}
for (const change of [
    window => { window.top = {}; },
    window => { window.location.origin = 'http://127.0.0.1:12345'; },
    window => { window.location.origin = 'https://127.0.0.1:13337'; },
    window => { window.location.pathname = '/vscode-remote-resource/file'; },
]) {
    const { provider, calls, context, window } = fixture();
    provider.create({ authority: 'example.test' });
    change(window);
    assert.equal(vm.runInNewContext(bootstrap, context), null);
    assert.equal(window.__vscodroid, undefined, 'an unowned document must never receive the token');
    assert.equal(calls.length, 0);
}
const callbackMatch = activity.slice(activity.indexOf('private fun handleExtensionCallback('))
    .match(/targetView\.evaluateJavascript\("""([\s\S]*?)"""/);
assert.ok(callbackMatch, 'the real native callback delivery script was not found');
function delivery(documentId, generation, code) {
    return callbackMatch[1]
        .replaceAll('$documentId', JSON.stringify(documentId))
        .replaceAll('$generation', String(generation))
        .replaceAll('$origin', JSON.stringify('http://127.0.0.1:13337'))
        .replaceAll('$key', JSON.stringify('vscode-web.url-callbacks[1]'))
        .replaceAll('$value', JSON.stringify(JSON.stringify({ scheme: 'vscodroid', authority: 'example.test', query: 'code=' + code })));
}
const pageA = fixture({ ready: true });
pageA.provider.create({ authority: 'example.test' });
const documentA = pageA.window.__vscodroidCallbackDocumentId;
const callbackA = delivery(documentA, 7, 'A');
function replacementPage() {
    const page = fixture({ nativeGeneration: 8, accept: (_id, _nonce, _documentId, generation) => generation === 8 });
    const created = page.provider.create({ authority: 'example.test' });
    assert.equal(new URLSearchParams(created.query).get('vscode-reqid'), '1');
    assert.equal(vm.runInNewContext(bootstrap, page.context), null);
    assert.equal(page.window.__vscodroid, undefined, 'stale bootstrap wrote into its replacement');
    return page;
}
{
    const pageB = replacementPage();
    vm.runInNewContext(callbackA, pageB.context);
    pageB.provider.checkCallbacks();
    assert.equal(pageB.writes.length, 0, 'an old callback must not write into the replacement document');
    assert.equal(pageB.delivered.length, 0, 'an old callback must not consume the replacement pending id');
    assert.equal(JSON.stringify(vm.runInNewContext(bootstrap.replaceAll('pageGeneration = 7', 'pageGeneration = 8').replaceAll('return [7,', 'return [8,').replaceAll('isAuthPageCurrent("session", 7)', 'isAuthPageCurrent("session", 8)'), pageB.context)), '[8,true]');
    const callbackB = delivery(pageB.window.__vscodroidCallbackDocumentId, 8, 'B');
    vm.runInNewContext(callbackB, pageB.context);
    pageB.provider.checkCallbacks();
    assert.equal(pageB.delivered.length, 1, 'the replacement document must retain its genuine pending request');
    assert.equal(pageB.delivered[0].query, 'code=B');
    vm.runInNewContext(callbackB, pageB.context);
    pageB.provider.checkCallbacks();
    assert.equal(pageB.delivered.length, 1, 'a collected provider request must remain consumed');
    pageB.provider.dispose();
}
{
    const pageB = replacementPage();
    pageB.window.__vscodroid = { authToken: 'session', pageGeneration: 7 };
    const mutableOnly = callbackA.replace(/^\s*if \(window\.__vscodroidCallbackDocumentId !==.*\n/m, '');
    assert.notEqual(mutableOnly, callbackA, 'the control must remove the immutable document guard');
    vm.runInNewContext(mutableOnly, pageB.context);
    pageB.provider.checkCallbacks();
    assert.equal(pageB.delivered.length, 1, 'the control must expose the mutable-generation-only delivery failure');
    assert.equal(pageB.delivered[0].query, 'code=A');
    pageB.provider.dispose();
}
{
    const pageB = fixture({ nativeGeneration: 8, accept: (_id, _nonce, _documentId, generation) => generation === 8 });
    assert.equal(vm.runInNewContext(bootstrap, pageB.context), null);
    assert.equal(pageB.window.__vscodroid, undefined);
    const callback = pageB.provider.create({ authority: 'example.test' });
    assert.equal(pageB.calls.length, 0, 'replacement issuance used stale metadata');
    const fresh = bootstrap.replaceAll('pageGeneration = 7', 'pageGeneration = 8')
        .replaceAll('return [7,', 'return [8,')
        .replaceAll('isAuthPageCurrent("session", 7)', 'isAuthPageCurrent("session", 8)');
    assert.equal(JSON.stringify(vm.runInNewContext(fresh, pageB.context)), '[8,true]');
    assert.equal(pageB.calls.length, 1);
    assert.equal(pageB.calls[0][0], new URLSearchParams(callback.query).get('vscode-reqid'));
    assert.equal(pageB.calls[0][3], 8);
    vm.runInNewContext(delivery(pageB.window.__vscodroidCallbackDocumentId, 8, 'fresh'), pageB.context);
    pageB.provider.checkCallbacks();
    assert.equal(pageB.delivered.length, 1, 'early replacement issuance was not retained');
    assert.equal(pageB.delivered[0].query, 'code=fresh');
    pageB.provider.dispose();
    const control = fixture({ nativeGeneration: 8, accept: (_id, _nonce, _documentId, generation) => generation === 8 });
    const unguarded = bootstrap.replace(/^\s*if \(!AndroidBridge\.isAuthPageCurrent.*\n/m, '');
    assert.notEqual(unguarded, bootstrap, 'generation guard mutation target is absent');
    assert.equal(JSON.stringify(vm.runInNewContext(unguarded, control.context)), '[7,true]');
    assert.throws(() => control.provider.create({ authority: 'example.test' }), /could not register/);
    control.provider.dispose();
}
for (const options of [
    { path: '/plain', query: 'state=ordinary', fragment: 'done' },
    { path: '/percent%name', query: 'state=literal%252F&code=a%2Bb', fragment: 'literal%2F' },
    { path: '/literal%2Fsegment', query: 'state=space+plus%2B', fragment: 'space + %25' },
]) {
    const { provider } = fixture({ ready: true });
    const callback = provider.create({ scheme: 'vscodroid', authority: 'example.test', ...options });
    const storage = new Map();
    const window = { location: { href: 'http://127.0.0.1:13337/callback?' + callback.query } };
    vm.runInNewContext(callbackSource, {
        window, URL, URLSearchParams,
        localStorage: { setItem(key, value) { storage.set(key, value); } },
    });
    const id = new URLSearchParams(callback.query).get('vscode-reqid');
    const uri = JSON.parse(storage.get(`vscode-web.url-callbacks[${id}]`));
    assert.equal(uri.path, options.path, 'callback paths must survive one query-parameter round trip');
    assert.equal(uri.fragment, options.fragment);
    for (const [key, value] of new URLSearchParams(options.query)) {
        assert.equal(new URLSearchParams(uri.query).get(key), value);
    }
    const payload = window.location.href.split('?data=')[1].split('#Intent;')[0];
    assert.deepEqual(JSON.parse(decodeURIComponent(payload)), { id, uri });
    provider.dispose();
}
console.log('ok: callback issuance, document identity and URI round trips');
