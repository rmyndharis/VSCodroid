/**
 * Self-check for the download capture script, which is what makes saving a
 * file from the Explorer possible at all.
 *
 *   node scripts/test-download-capture.js
 *
 * The script is JavaScript written inside a Kotlin raw string and handed to
 * `WebView.evaluateJavascript`. Nothing compiles it, nothing lints it, and no
 * Kotlin test can reach it: the unit tests stop at the bridge methods it calls.
 * So the half that decides whether a download has any bytes to save had no
 * cover at all.
 *
 * What it is protecting is not obvious from reading it, so it is worth stating
 * once. The editor reads a file into memory, wraps it in a blob, hands the
 * platform a `blob:` URL and then revokes that URL on the very next task:
 *
 *     e = URL.createObjectURL(n), setTimeout(() => URL.revokeObjectURL(e))
 *
 * (`workbench.web.main.internal.js`, the body of `triggerDownload`.) Saving
 * needs the user to choose a destination first, which takes seconds. So by the
 * time there is anywhere to write, an untouched `blob:` URL names nothing and
 * every download fails. Deferring that revocation is the whole reason this
 * script exists, and it is a one-line behaviour that no other check would
 * notice losing: remove the deferral and this file goes red, while the Kotlin
 * suite, lint and the build all stay green and every download fails on device.
 *
 * The second such behaviour is where the bytes are read from. A live `blob:`
 * URL is still not fetchable here: the server sends the workbench page
 *
 *     connect-src 'self' ws: wss: https:;
 *
 * (`out/server-main.js`, the `Content-Security-Policy` response header), and
 * `blob:` is not in that list, so Blink refuses the request with "Connecting to
 * 'blob:...' violates the following Content Security Policy directive" before
 * it leaves the page. Measured under that exact header. So the bytes come off
 * the Blob object, which is not a request and which no directive governs, and
 * the script keeps the object beside the URL to have one to read.
 *
 * Extraction is deliberately strict. If the raw string moves or changes shape
 * this fails saying so, rather than quietly checking an empty string, which is
 * the failure mode that makes a scan of nothing look like a pass.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.join(__dirname, '..');
const MAIN_ACTIVITY = path.join(
    ROOT, 'android/app/src/main/kotlin/com/vscodroid/MainActivity.kt',
);

/** How a literal dollar is written inside a Kotlin raw string. */
const DOLLAR_ESCAPE = "${'$'}";

/**
 * The body of the raw string in `injectDownloadCapture()`, with the indentation
 * `trimIndent()` removes taken off, which is the text the WebView is given.
 */
function extractCapture() {
    const lines = fs.readFileSync(MAIN_ACTIVITY, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('private fun injectDownloadCapture()'));
    assert.notStrictEqual(
        fn, -1,
        'injectDownloadCapture() is gone from MainActivity.kt, so this check has nothing to ' +
        'run. If the script moved, point this at its new home rather than deleting the check.',
    );

    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close !== -1 && close > open + 1,
        'could not find the raw string in injectDownloadCapture(); its shape changed');

    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');

    // A bare $ is interpolated by Kotlin before the WebView ever sees the
    // string, so the injected script would not be the one written here.
    const withoutEscapes = js.split(DOLLAR_ESCAPE).join('');
    assert.ok(
        !withoutEscapes.includes('$'),
        'the capture script contains a bare $, which Kotlin interpolates into the raw string ' +
        'before the WebView sees it. Escape it, or the injected script is not what is written.',
    );
    assert.ok(js.includes('revokeObjectURL'),
        'the extracted text does not mention revokeObjectURL, so the wrong block was extracted');
    assert.ok(js.includes('writeDownloadChunk'),
        'the extracted text does not mention writeDownloadChunk, so the wrong block was extracted');

    return js.split(DOLLAR_ESCAPE).join('$');
}

const SCRIPT = extractCapture();

// ---- the page, stubbed ----------------------------------------------------

/**
 * One run of the capture script against a fresh fake page.
 *
 * Rebuilt per case rather than shared. The script installs itself once per
 * document and refuses to install twice, so a shared page would leave every
 * case after the first testing the previous case's hooks.
 */
function newPage(options) {
    const opts = options || {};
    const state = {
        revoked: [],          // urls that reached the real revokeObjectURL
        clicked: [],          // urls whose click was passed through
        named: [],            // {token, url, fileName} reported to the bridge
        chunks: [],           // base64 pieces handed to the bridge
        finished: [],         // {id, error} reported to the bridge
        fetched: [],          // urls a network request was attempted for
        cancelled: false,     // whether the reader was cancelled
        minted: 0,            // how many object URLs the page asked for
        timers: [],
    };

    const URLStub = {
        createObjectURL() {
            state.minted += 1;
            return `blob:http://127.0.0.1:5000/object-${state.minted}`;
        },
        revokeObjectURL(url) { state.revoked.push(url); },
    };

    function HTMLAnchorElement() {}
    HTMLAnchorElement.prototype.click = function () { state.clicked.push(this.href); };
    HTMLAnchorElement.prototype.getAttribute = function (name) {
        return Object.prototype.hasOwnProperty.call(this.attrs, name) ? this.attrs[name] : null;
    };

    const AndroidBridge = {
        noteDownloadName(token, url, fileName) {
            if (opts.bridgeThrows) throw new Error('bridge is unhappy');
            state.named.push({ token, url, fileName });
        },
        writeDownloadChunk(token, id, base64) {
            state.chunks.push({ token, id, base64 });
            return opts.refuseChunkAt === undefined
                || state.chunks.length <= opts.refuseChunkAt;
        },
        finishDownload(token, id, error) {
            state.finished.push({ token, id, error });
        },
    };

    // A reader over fixed pieces, which is the shape both a Blob's stream and
    // an http response body give.
    function readerOver(pieces) {
        let next = 0;
        return {
            read() {
                if (next >= pieces.length) return Promise.resolve({ done: true });
                const value = pieces[next];
                next += 1;
                return Promise.resolve({ done: false, value });
            },
            cancel() { state.cancelled = true; },
        };
    }

    function responseFor(url) {
        state.fetched.push(url);
        // The page is served under connect-src 'self' ws: wss: https:, so Blink
        // refuses a request for a blob: URL before it starts. Modelled here
        // rather than left out: a harness that answers one is a harness that
        // passes over the failure the whole feature ran into.
        if (url.lastIndexOf('blob:', 0) === 0) {
            return Promise.reject(new TypeError('Failed to fetch'));
        }
        if (opts.fetchRejects) return Promise.reject(new Error('network is down'));
        if (opts.status && opts.status !== 200) {
            return Promise.resolve({ ok: false, status: opts.status });
        }
        return Promise.resolve({
            ok: true,
            status: 200,
            body: { getReader: () => readerOver(opts.pieces || []) },
        });
    }

    // Zero-delay timers are the pump yielding between pieces and have to run
    // for real. The hold expiry is two minutes away, so it is captured instead
    // and fired on demand: waiting for it would make this check take longer
    // than the whole suite, and stubbing the clock away would leave the release
    // untested, which is how a hold that never expires ships as a memory leak.
    state.pendingHolds = [];
    const timer = (fn, ms) => {
        if (ms >= 1000) {
            state.pendingHolds.push(fn);
            return 0;
        }
        return setTimeout(fn, ms);
    };

    const sandbox = {
        console,
        setTimeout: timer,
        clearTimeout,
        Promise,
        Set,
        Map,
        String,
        Error,
        Object,
        Uint8Array,
        URL: URLStub,
        HTMLAnchorElement,
        btoa: (binary) => Buffer.from(binary, 'binary').toString('base64'),
        fetch: responseFor,
    };
    sandbox.window = sandbox;
    sandbox.AndroidBridge = AndroidBridge;
    if (opts.token !== null) sandbox.__vscodroid = { authToken: opts.token || 'session-token' };
    if (opts.noBridge) delete sandbox.AndroidBridge;

    const context = vm.createContext(sandbox);
    vm.runInContext(SCRIPT, context);

    state.context = context;
    state.sandbox = sandbox;
    state.anchor = (href, attrs) => {
        const a = new HTMLAnchorElement();
        a.href = href;
        a.attrs = attrs || {};
        return a;
    };
    state.revoke = (url) => sandbox.URL.revokeObjectURL(url);
    state.send = (url, id) => sandbox.window.__vscodroidDownload.send(url, id);
    state.runScriptAgain = () => vm.runInContext(SCRIPT, context);
    // What the editor does: wrap the file's bytes in a Blob and mint a URL for
    // it. Through the page's own URL.createObjectURL, so the script sees it.
    state.objectUrl = (pieces) => sandbox.URL.createObjectURL({
        stream: () => readerOverStream(pieces),
    });
    function readerOverStream(pieces) {
        return { getReader: () => readerOver(pieces) };
    }
    return state;
}

/** Lets every queued microtask and zero-delay timer run. */
function settle(until) {
    // Polls for the condition rather than sleeping a fixed span. This was a flat
    // 5 ms, which is not a promise about work that hops through the microtask
    // queue and a reader callback: on a loaded CI runner the read had not
    // finished, the bridge had recorded nothing, and the case failed for the
    // speed of the machine rather than for anything in the code. Called with no
    // condition it yields a few turns, which is all a case asserting that
    // something did NOT happen can ask for.
    const deadline = Date.now() + 2000;
    return new Promise((done) => {
        const tick = () => {
            if (!until || until() || Date.now() >= deadline) return done();
            setTimeout(tick, 1);
        };
        setTimeout(tick, until ? 0 : 25);
    });
}

const BLOB = 'blob:http://127.0.0.1:5000/6f1d2c30-9a4b-4c1e-8f77-2b0a5d3e91cc';

/** A download of something the editor did not hold in memory, served over http. */
const REMOTE = 'http://127.0.0.1:5000/vscode-remote-resource?path=/w/App.kt';

// ---- the cases ------------------------------------------------------------

const cases = [];
function test(name, fn) { cases.push({ name, fn }); }

/**
 * The one that matters. The editor revokes the blob URL on the next task, and
 * the user has not even seen the destination picker by then.
 */
test('a blob a download used stays resolvable after the editor revokes it', () => {
    const page = newPage();
    const anchor = page.anchor(BLOB, { download: 'App.kt' });

    anchor.click();
    page.revoke(BLOB);

    assert.deepStrictEqual(page.revoked, [],
        'the editor revoked the blob one task after clicking; letting that through leaves ' +
        'nothing to save by the time the user has picked a destination');
});

/** Nothing else is held. A blanket deferral would keep every blob the workbench makes. */
test('a blob no download used is revoked as the page asked', () => {
    const page = newPage();
    const other = 'blob:http://127.0.0.1:5000/unrelated';

    page.anchor(BLOB, { download: 'App.kt' }).click();
    page.revoke(other);

    assert.deepStrictEqual(page.revoked, [other],
        'only the URL a download is using may be held back');
});

/**
 * A held URL releases itself rather than living as long as the page does.
 *
 * The hold pins the file's bytes in memory, so a hold that never expires is a
 * download-shaped leak: click download, back out of the picker, repeat, and the
 * page keeps every one of those files. Both halves are asserted, because a hold
 * that expires immediately passes the release test and breaks the deferral, and
 * one that never expires passes the deferral test and leaks.
 */
test('a held blob is revoked once its hold expires', () => {
    const page = newPage();
    page.anchor(BLOB, { download: 'App.kt' }).click();
    page.revoke(BLOB);
    assert.deepStrictEqual(page.revoked, [], 'still held while the download could be running');

    assert.strictEqual(page.pendingHolds.length, 1, 'the hold has to have an expiry queued');
    page.pendingHolds.forEach((fire) => fire());

    assert.deepStrictEqual(page.revoked, [BLOB],
        'an expired hold has to release the blob it was keeping alive');

    // And the release is complete: the URL is no longer special, so a later
    // revoke passes straight through rather than being swallowed forever.
    page.revoke(BLOB);
    assert.deepStrictEqual(page.revoked, [BLOB, BLOB]);
});

/** The name is the only thing that knows what the file is called. */
test('a download click reports its name and token to Android', () => {
    const page = newPage();

    page.anchor(BLOB, { download: 'App.kt' }).click();

    assert.deepStrictEqual(page.named, [
        { token: 'session-token', url: BLOB, fileName: 'App.kt' },
    ]);
});

/** An ordinary link is not a download and must not be touched. */
test('a link with no download attribute is left entirely alone', () => {
    const page = newPage();

    page.anchor('https://example.com/page', {}).click();
    page.revoke(BLOB);

    assert.deepStrictEqual(page.named, [], 'a plain link is not a download');
    assert.deepStrictEqual(page.revoked, [BLOB], 'and it holds nothing back');
    assert.deepStrictEqual(page.clicked, ['https://example.com/page'],
        'the click still happens');
});

/**
 * Bookkeeping must never cost the page a click. A throw here is the difference
 * between a download that does not save and a workbench where links stop
 * working.
 */
test('a bridge that throws does not swallow the click', () => {
    const page = newPage({ bridgeThrows: true });

    page.anchor(BLOB, { download: 'App.kt' }).click();

    assert.deepStrictEqual(page.clicked, [BLOB],
        'the original click has to run whatever the recording did');
});

/**
 * The one this feature turns on, and the one the shipped policy decides.
 *
 * Every file the editor can hold in memory is downloaded through a `blob:`
 * URL, and the page may not fetch one: `connect-src 'self' ws: wss: https:`
 * does not list `blob:`, so Blink blocks the request and the user gets a
 * destination picker followed by a failure. The bytes therefore have to come
 * off the Blob, and this asserts both halves, because reading the right bytes
 * over a request the policy refuses is exactly what looked correct.
 */
test('a blob download is read off the blob, never over a request', async () => {
    const page = newPage();
    const url = page.objectUrl([Uint8Array.from([0, 1, 2, 250]), Uint8Array.from([255, 65, 66])]);

    page.anchor(url, { download: 'App.kt' }).click();
    page.revoke(url);
    assert.strictEqual(page.send(url, 'dl-1'), true, 'a started read reports that it started');
    await settle(() => page.finished.length > 0);

    assert.deepStrictEqual(page.fetched, [],
        'fetching a blob: URL is refused by the page policy, so a download that fetches one ' +
        'fails for every file the editor holds in memory');
    const bytes = Buffer.concat(page.chunks.map((c) => Buffer.from(c.base64, 'base64')));
    assert.deepStrictEqual(
        Array.from(bytes), [0, 1, 2, 250, 255, 65, 66],
        'the file that reaches Android must be the file the page read, byte for byte',
    );
    assert.deepStrictEqual(page.finished, [{ token: 'session-token', id: 'dl-1', error: '' }],
        'an empty error is how success is spelled across the bridge');
});

/**
 * The record of which Blob a URL names is bounded, like every other page-fed
 * store here. It pins the bytes, so a page that mints object URLs nobody
 * downloads must not be able to hold the file it did download hostage.
 */
test('object URLs nobody downloads do not pin their bytes', async () => {
    const page = newPage();
    const first = page.objectUrl([Uint8Array.from([7])]);
    for (let i = 0; i < 8; i += 1) page.objectUrl([Uint8Array.from([i])]);

    page.anchor(first, { download: 'App.kt' }).click();
    page.send(first, 'dl-1');
    await settle(() => page.finished.length > 0);

    assert.deepStrictEqual(page.fetched, [first],
        'a blob the page stopped tracking has to fall through to a request rather than ' +
        'be answered with the bytes of another file');
    assert.ok(page.finished[0].error, 'and the refusal that follows is reported');
});

/** Every piece is decodable on its own, because Android decodes them one at a time. */
test('each piece decodes on its own', async () => {
    const page = newPage();
    const url = page.objectUrl(
        [Uint8Array.from([1, 2]), Uint8Array.from([3]), Uint8Array.from([4, 5, 6])],
    );
    page.anchor(url, { download: 'App.kt' }).click();

    page.send(url, 'dl-1');
    await settle(() => page.chunks.length >= 3);

    assert.strictEqual(page.chunks.length, 3, 'one bridge call per piece read');
    const perPiece = page.chunks.map((c) => Array.from(Buffer.from(c.base64, 'base64')));
    assert.deepStrictEqual(perPiece, [[1, 2], [3], [4, 5, 6]],
        'a piece that only decodes when joined to its neighbours would corrupt the file');
});

/**
 * A download the editor did not hold in memory is an ordinary URL the policy
 * allows, and it still has to work: it is the path everything above falls back
 * to.
 */
test('a download the page has no blob for is fetched', async () => {
    const page = newPage({ pieces: [Uint8Array.from([65, 66])] });

    page.send(REMOTE, 'dl-1');
    await settle(() => page.finished.length > 0);

    assert.deepStrictEqual(page.fetched, [REMOTE]);
    const bytes = Buffer.concat(page.chunks.map((c) => Buffer.from(c.base64, 'base64')));
    assert.deepStrictEqual(Array.from(bytes), [65, 66]);
    assert.deepStrictEqual(page.finished, [{ token: 'session-token', id: 'dl-1', error: '' }]);
});

/** A read that fails has to say so, or the download waits on a file forever. */
test('a failed read is reported as an error', async () => {
    const page = newPage({ fetchRejects: true });

    page.send(REMOTE, 'dl-1');
    await settle(() => page.finished.length > 0);

    assert.strictEqual(page.finished.length, 1, 'a failure still ends the download');
    assert.strictEqual(page.finished[0].id, 'dl-1');
    assert.ok(page.finished[0].error, 'an empty error would be read as success');
});

/** A URL that resolves but answers an error status is a failure too. */
test('a non-ok response is reported as an error', async () => {
    const page = newPage({ status: 404 });

    page.send(REMOTE, 'dl-1');
    await settle(() => page.finished.length > 0);

    assert.strictEqual(page.finished.length, 1);
    assert.ok(/404/.test(page.finished[0].error),
        `the status belongs in the reason; got ${page.finished[0].error}`);
});

/**
 * Android refuses a piece when the write failed, and it has already told the
 * user why. Reading on would send the rest of a file into a stream that is
 * gone; finishing again would replace a real reason with a generic one.
 */
test('a refused piece stops the read without reporting a second reason', async () => {
    const page = newPage({ refuseChunkAt: 1 });
    const url = page.objectUrl(
        [Uint8Array.from([1]), Uint8Array.from([2]), Uint8Array.from([3])],
    );
    page.anchor(url, { download: 'App.kt' }).click();

    page.send(url, 'dl-1');
    await settle(() => page.cancelled);

    assert.strictEqual(page.chunks.length, 2, 'reading stops at the piece that was refused');
    assert.strictEqual(page.cancelled, true, 'the reader is released rather than left open');
    assert.deepStrictEqual(page.finished, [],
        'Android already reported this failure; a second report would overwrite the reason');
});

/**
 * Without a token or a bridge nothing can be sent, and Android has to hear that
 * from the return value: there is no other channel left to say it on.
 */
test('send refuses when it has no way to answer', () => {
    assert.strictEqual(newPage({ noBridge: true }).send(BLOB, 'dl-1'), false,
        'no bridge means no route for the bytes or for the failure');
    assert.strictEqual(newPage({ token: null }).send(BLOB, 'dl-1'), false,
        'an unauthenticated call would be refused on the other side with nothing said');
});

/**
 * The script is injected on every page load. Installing twice would wrap the
 * revoke hook around itself, and a held URL would then be released by the inner
 * copy while the outer one still refuses it.
 */
test('injecting the script twice changes nothing', () => {
    const page = newPage();
    page.runScriptAgain();

    page.anchor(BLOB, { download: 'App.kt' }).click();
    page.revoke(BLOB);

    assert.deepStrictEqual(page.revoked, [], 'the hold still holds after a second injection');
    assert.deepStrictEqual(page.named, [
        { token: 'session-token', url: BLOB, fileName: 'App.kt' },
    ], 'one click still reports one name');
});

// ---- run ------------------------------------------------------------------

(async () => {
    let failed = 0;
    for (const c of cases) {
        try {
            await c.fn();
            console.log(`ok   ${c.name}`);
        } catch (e) {
            failed += 1;
            console.error(`FAIL ${c.name}`);
            console.error(`     ${e.message}`);
        }
    }
    console.log(`\n${cases.length - failed}/${cases.length} passed`);
    process.exit(failed ? 1 : 0);
})();
