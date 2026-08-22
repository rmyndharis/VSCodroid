/**
 * Self-check for the BroadcastChannel bridge relay, end to end.
 *
 *   node scripts/test-bridge-relay.js
 *
 * The relay is JavaScript written inside a Kotlin raw string and handed to
 * `WebView.evaluateJavascript`. Nothing compiles it, nothing lints it, and no
 * unit test reads it -- Kotlin tests exercise the bridge methods it calls and
 * stop at the language boundary. So the half of a bridge change that is
 * actually user-visible had no cover at all: inverting a ternary here, or
 * going back to an unconditional `ok: true`, leaves every suite green and
 * restores the defect in full.
 *
 * This extracts the real relay from MainActivity.kt, runs it against a stub
 * AndroidBridge, and drives it through the real bundled extension, so the only
 * faked things are Android itself and the vscode API. What is asserted is what
 * the user would be shown.
 *
 * Extraction is deliberately strict. If the raw string moves or changes shape
 * this fails saying so, rather than quietly checking an empty string -- the
 * failure mode that makes a scan of nothing look like a pass.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const Module = require('module');
const vm = require('vm');
const { EXTENSIONS_DIR, newestExtensionDir } = require('./lib/bundled-extension');

const ROOT = path.join(__dirname, '..');
const MAIN_ACTIVITY = path.join(
    ROOT, 'android/app/src/main/kotlin/com/vscodroid/MainActivity.kt',
);
const ANDROID_BRIDGE = path.join(
    ROOT, 'android/app/src/main/kotlin/com/vscodroid/bridge/AndroidBridge.kt',
);
const STRINGS_XML = path.join(
    ROOT, 'android/app/src/main/res/values/strings.xml',
);

/**
 * One of the user-facing decline reasons, read from where it now lives.
 *
 * The relay used to hold this sentence as a literal and the checks below read it
 * from there. It moved to the bridge, which is the only side that knows which
 * failure happened, so the checks follow it rather than being dropped: the
 * question they ask is about the words a user is shown, and those words are the
 * same words wherever they are declared.
 */
function bridgeReason(name) {
    const src = fs.readFileSync(ANDROID_BRIDGE, 'utf8');
    // Two hops, because the words moved once already. The bridge now names a
    // resource rather than carrying the sentence, so that a translation can
    // reach it; the sentence itself lives in strings.xml. Following both hops
    // keeps this asking the question it has always asked, which is about the
    // words a user is shown, not about where they happen to be declared.
    const ref = src.match(new RegExp('val ' + name + '\\s*=\\s*R\\.string\\.(\\w+)'));
    assert.ok(
        ref,
        name + ' is gone from AndroidBridge.kt, so this check has nothing to read. If the ' +
        'reasons moved, point this at their new home rather than deleting the check.',
    );
    const strings = fs.readFileSync(STRINGS_XML, 'utf8');
    const text = strings.match(
        new RegExp('<string name="' + ref[1] + '"[^>]*>([\\s\\S]*?)</string>'),
    );
    assert.ok(
        text,
        name + ' points at R.string.' + ref[1] + ', which strings.xml does not declare. ' +
        'The bridge would then fail at getString rather than showing the reason.',
    );
    // What a user reads, not what the file stores: the resource compiler
    // unescapes these, and a format argument is filled before the sentence is
    // shown, so the assertions below would otherwise compare against text that
    // never appears on screen.
    return text[1]
        .replace(/%\d+\$s/g, '')
        .replace(/\\'/g, "'")
        .replace(/\\"/g, '"')
        .replace(/&amp;/g, '&')
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .trim();
}

/** How a literal dollar is written inside a Kotlin raw string. */
const DOLLAR_ESCAPE = "${'$'}";

/**
 * The bundled bridge extension, whatever version it is at.
 *
 * Not a hardcoded directory. Bumping the version of a bundled extension is
 * mandatory here whenever its contents change -- a device only re-extracts a
 * directory whose name it does not already have -- so a path pinned to one
 * version is a path that breaks on the next correct change, and the version in
 * it is stale the moment anyone edits the extension.
 *
 * Ordered by parsed version rather than by name, because 1.10.0 sorts below
 * 1.9.0 as text. check-welcome-claims.py sorts its directory by parsed version
 * for the same reason; the comparison here is not identical to that one -- it
 * zero-pads a shorter tail where Python's tuple compare would sort it first --
 * which only shows up between 1.2 and 1.2.0 and does not arise in this tree.
 */
function bridgeExtension() {
    return path.join(
        newestExtensionDir('vscodroid.vscodroid-saf-bridge-'), 'extension.js',
    );
}

/**
 * The body of the raw string in one of MainActivity's injectors, with the
 * indentation `trimIndent()` removes taken off, which is the text the WebView is
 * given.
 *
 * Takes the function name because there are two scripts worth running here, not
 * one. `injectWindowOpenOverride` calls the same bridge method as the relay and
 * reads its answer differently, so a change to that answer can be right in one
 * and inverted in the other.
 *
 * @param mustMention a token the extracted text has to contain, so that pulling
 *   the wrong block fails here instead of passing as a scan of something else.
 */
function extractInjected(fnName, mustMention) {
    const lines = fs.readFileSync(MAIN_ACTIVITY, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('private fun ' + fnName + '()'));
    assert.notStrictEqual(
        fn, -1,
        fnName + '() is gone from MainActivity.kt, so this check has nothing to run. ' +
        'If it moved, point this at its new home rather than deleting the check.',
    );

    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close !== -1 && close > open + 1,
        'could not find the raw string in ' + fnName + '(); its shape changed');

    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');

    // A bare $ is interpolated by Kotlin before the WebView ever sees the string,
    // so the injected script would not be the one written here. The escape for a
    // literal dollar is itself spelled with one, so valid escapes are removed
    // before looking -- checking the raw text would reject exactly the form this
    // message tells the reader to use.
    const withoutEscapes = js.split(DOLLAR_ESCAPE).join('');
    assert.ok(
        !withoutEscapes.includes('$'),
        'the relay contains a bare $, which Kotlin interpolates into the raw string before the ' +
        'WebView sees it. Escape it, or the injected script is not what is written here.',
    );
    assert.ok(js.includes(mustMention),
        'the extracted text does not mention ' + mustMention + ', so the wrong block was extracted');

    // Resolve the escapes the way Kotlin does, so what runs below is what runs there.
    return js.split(DOLLAR_ESCAPE).join('$');
}

// ---- the Android side, stubbed -------------------------------------------
// The bridge answers with the reason it did not open, and the empty string when
// it did. A boolean here would let a relay that inverted the test stay green.
let bridgeAnswer = '';
const bridgeCalls = [];

// The four disk-walking commands answer by reply id: they hand back at once and
// the value follows through the relay's reply hook. So the stub's return value
// is not the answer, it is only the refusal channel, and every case below that
// wants an answer has to post one.
let listAnswer = '';
let reclaimAnswer = '';
let breakdownAnswer = '';
const listCalls = [];
const reclaimCalls = [];
const breakdownCalls = [];

const AndroidBridge = {
    openExternalUrl(url, token) {
        bridgeCalls.push({ url, token });
        return bridgeAnswer;
    },
    listSafMirrors(token, replyId) {
        listCalls.push({ token, replyId });
        return listAnswer;
    },
    reclaimSafMirror(token, hash, force, replyId) {
        reclaimCalls.push({ token, hash, force, replyId });
        return reclaimAnswer;
    },
    getStorageBreakdown(token, replyId) {
        breakdownCalls.push({ token, replyId });
        return breakdownAnswer;
    },
};

// ---- the vscode API, stubbed ---------------------------------------------
const shown = { info: [], error: [] };
let inputBoxAnswer = null;
// What the user does at each prompt of the device-folder flow. Null is the
// dismissal, which is what every case that does not reach a removal wants.
let quickPickChoice = null;
let warningChoice = null;
const commands = new Map();
const vscodeStub = {
    commands: {
        registerCommand: (id, fn) => { commands.set(id, fn); return { dispose() {} }; },
        executeCommand: async () => {},
    },
    window: {
        showInputBox: async () => inputBoxAnswer,
        showInformationMessage: (m) => { shown.info.push(m); },
        showErrorMessage: (m) => { shown.error.push(m); },
        showWarningMessage: async () => warningChoice || undefined,
        showQuickPick: async (items) => (quickPickChoice ? quickPickChoice(items) : undefined),
        createStatusBarItem: () => ({ show() {}, hide() {}, dispose() {} }),
    },
    env: { clipboard: { writeText: async () => {} } },
    Uri: { parse: (s) => ({ toString: () => s }) },
    StatusBarAlignment: { Left: 1, Right: 2 },
};

const realLoad = Module._load;
Module._load = function (request) {
    if (request === 'vscode') return vscodeStub;
    return realLoad.apply(this, arguments);
};

/**
 * Every command a bundled extension sends must have a branch in the relay.
 *
 * The severity word is not the only string crossing this boundary. The relay
 * dispatches twelve command names, written once in a Kotlin raw string; the
 * bundled extensions send nine of them, written again in JavaScript. Nothing
 * compared the two sets. A name that matches no branch is not
 * an error anywhere -- the relay's `if/else if` chain simply ends, no reply is
 * posted, and the extension's promise rejects five seconds later with a timeout
 * that says the app might not be running. The cause and the symptom share no
 * words.
 *
 * Only this direction is asserted. Branches with no sender are dead code, not a
 * user-visible failure, and there are some; they are reported rather than
 * refused so removing one stays a deliberate decision.
 *
 * The limit worth knowing, because a reader would assume otherwise: both sides
 * are read as LITERALS. A command name built from a variable is absent from the
 * sent set and therefore unchecked -- silently, and in the one direction this
 * exists for. `sent.size > 0` catches total blindness, not a partial miss. Every
 * name on both sides is a literal today, which is what makes the check worth
 * having and also what would make the first non-literal invisible.
 */
function checkCommandCoverage(relay) {
    const dispatched = new Set(
        [...relay.matchAll(/d\.cmd === ['"]([A-Za-z0-9_.-]+)['"]/g)].map((m) => m[1]),
    );
    const extensionsDir = path.join(ROOT, 'android/app/src/main/assets/extensions');
    const sent = new Set();
    for (const dir of fs.readdirSync(extensionsDir)) {
        // The entry point the manifest names, not a guess at it. Reading
        // <dir>/extension.js was a guess that happens to be right for every
        // bundled extension today, and it fails silently: an extension whose
        // manifest points elsewhere contributes nothing to `sent`, `sent.size > 0`
        // is already satisfied by its neighbours, and the summary line then claims
        // every command an extension sends has a branch.
        const manifestPath = path.join(extensionsDir, dir, 'package.json');
        if (!fs.existsSync(manifestPath)) continue;
        const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
        const entry = manifest.browser || manifest.main;
        if (!entry) continue;
        // Resolved the way the extension host resolves it, which is require():
        // vscode-eslint names "./client/out/extension" and ships extension.js,
        // and treating the manifest string as a path finds nothing there.
        const base = path.join(extensionsDir, dir, entry);
        const file = [base, `${base}.js`, path.join(base, 'index.js')].find(fs.existsSync);
        assert.ok(
            file,
            `${dir}'s manifest names an entry point that resolves to nothing: ${entry}. The ` +
            'extension cannot load, and this sweep would have skipped it silently.',
        );
        for (const m of fs.readFileSync(file, 'utf8').matchAll(/sendBridgeCommand\(['"]([A-Za-z0-9_.-]+)['"]/g)) {
            sent.add(m[1]);
        }
    }

    // Both sides report success by finding nothing, so both are asserted to
    // have found something before anything is concluded from the comparison.
    assert.ok(dispatched.size > 0, 'no dispatch branches were read from the relay');
    assert.ok(sent.size > 0, 'no bridge commands were read from any bundled extension');

    const unhandled = [...sent].filter((c) => !dispatched.has(c)).sort();
    assert.deepStrictEqual(
        unhandled, [],
        `these commands are sent by a bundled extension and have no branch in the relay, so ` +
        `they post no reply and fail five seconds later as "Bridge timeout: is the app ` +
        `running on Android?", which names neither the command nor the real cause: ` +
        `${unhandled.join(', ')}`,
    );

    return {
        dispatched: dispatched.size,
        sent: sent.size,
        unused: [...dispatched].filter((c) => !sent.has(c)).sort(),
    };
}

async function main() {
    const relay = extractInjected('injectBridgeRelay', 'openExternalUrl');
    const NO_HANDLER = bridgeReason('OPEN_URL_NO_HANDLER');
    const STALE_SESSION = bridgeReason('STORAGE_STALE_SESSION');
    const coverage = checkCommandCoverage(relay);
    // Held rather than written inline, because the relay installs the reply hook
    // ON this object. That hook is the road a late answer takes back into the
    // page, so a test that cannot reach it cannot drive any command that answers
    // by id, which is four of the fourteen.
    const relayWindow = { __vscodroid: { authToken: 'test-token' } };
    vm.runInNewContext(relay, {
        AndroidBridge,
        BroadcastChannel,
        window: relayWindow,
        console,
    });
    assert.strictEqual(
        typeof relayWindow.__vscodroidBridgeReply, 'function',
        'the relay installed no reply hook, so nothing can answer the four commands that ' +
        'hand back before their work is done. Every one of them would then sit until the ' +
        'extension\'s two-minute deadline and report that the app may not be on Android.',
    );

    const context = { subscriptions: [] };
    require(bridgeExtension()).activate(context);

    const openInBrowser = commands.get('vscodroid.openInBrowser');
    assert.ok(openInBrowser, 'the bundled extension no longer registers vscodroid.openInBrowser');

    async function run(url, answer) {
        shown.info.length = 0; shown.error.length = 0; bridgeCalls.length = 0;
        bridgeAnswer = answer;
        inputBoxAnswer = url;
        // No wait after this. The handler is async and awaits sendBridgeCommand,
        // whose promise settles only from the channel's onmessage or its own 5s
        // timeout, and the catch calls showErrorMessage synchronously -- so by the
        // time this returns, anything the user would see has already been pushed.
        // A sleep here would add nothing except a way for a contended runner to
        // fail two required workflows with a message about the relay.
        await openInBrowser();
        return { info: [...shown.info], error: [...shown.error], calls: [...bridgeCalls] };
    }

    // A URL the bridge declines has to reach the user. This is the whole point:
    // the relay used to post ok:true here, which resolved the extension's promise
    // and left its error handler unreachable.
    const LAN = 'http://192.168.1.5:3000';
    const refused = await run(LAN, NO_HANDLER);
    assert.strictEqual(refused.calls.length, 1, 'the relay never reached the bridge');

    // What it passed, not just that it passed something. The relay calls
    // openExternalUrl(d.url, token) and the bridge validates the SECOND argument
    // as the session token; swapping them -- which reads like a tidy-up, since
    // every sibling branch takes the token first -- hands the URL to the token
    // check and refuses every open on device, while a length assertion stays green.
    assert.deepStrictEqual(
        refused.calls[0], { url: LAN, token: 'test-token' },
        'the relay passed the wrong arguments to the bridge: ' + JSON.stringify(refused.calls[0]),
    );
    assert.strictEqual(
        refused.error.length, 1,
        refused.error.length === 0
            ? 'the bridge was called but nothing reached the user. Either the relay stopped ' +
              'answering a declined URL -- the defect this check exists for -- or the await in ' +
              'run() no longer covers the channel round trip, which would be a fault in this ' +
              'check rather than in the relay. Rule the second out before reading the first.'
            : 'a declined URL must surface exactly one message; got ' + JSON.stringify(refused.error),
    );
    assert.ok(!refused.error[0].includes('undefined'),
        'the surfaced message leaked an undefined: ' + refused.error[0]);

    // The relay's own text has to be the text that arrives. Asserting only that
    // SOME message appeared accepts the failure this check exists to catch: a
    // relay that answers nothing leaves the extension to reject on its own
    // five-second timeout, which is also exactly one message, and says the app
    // may not be running.
    assert.ok(
        refused.error[0].includes(NO_HANDLER),
        'the message the user saw is not the reason the bridge gave. Saw: ' + refused.error[0],
    );

    // Forwarded, not re-stated. The relay posted one fixed sentence for every
    // refusal and the check above would accept that again, since the sentence it
    // posted is the one the bridge now returns for this case. A reason no source
    // file contains can only arrive by being passed through.
    const ONLY_HERE = 'a reason that exists only inside this check';
    const passedThrough = await run('http://192.168.1.5:3000', ONLY_HERE);
    assert.ok(
        passedThrough.error.length === 1 && passedThrough.error[0].includes(ONLY_HERE),
        'the relay did not forward the reason the bridge gave, so it is stating a cause it ' +
        'cannot know. Saw: ' + JSON.stringify(passedThrough.error),
    );

    // The message must not describe a URL policy. This asked for the opposite
    // until the allow-list was removed: while the bridge refused http on any host
    // but localhost, a boolean could not say WHICH of the two causes fired, so the
    // message had to name both. With the filter gone there is one cause left --
    // nothing on the device claimed the link -- and a message still listing
    // permitted schemes would send a user hunting for a rule that no longer
    // exists. Pinned as an absence rather than left to prose, because the wording
    // and the policy are in different files and only one of them fails a build.
    for (const stale of ['https, mailto', 'allow', 'localhost', 'scheme']) {
        assert.ok(
            !NO_HANDLER.toLowerCase().includes(stale),
            `the decline message mentions "${stale}", which reads as a URL restriction. There ` +
            'is none: any URL is handed to the platform, and the only way to get here is that ' +
            `no installed app took the intent. Message: ${NO_HANDLER}`,
        );
    }
    assert.ok(
        /no app|handles that link/i.test(NO_HANDLER),
        'the decline message no longer names the one cause that can produce it -- that nothing ' +
        'on the device handles the link: ' + NO_HANDLER,
    );

    // A different scheme, same handling. The bridge is stubbed here and ignores
    // the URL, so this cannot fail independently of the run above; what it pins is
    // that the relay does not branch on the scheme before deciding what to say.
    // Which failure produced the reason is decided in Kotlin and is covered there.
    const allowedButUnopened = await run('mailto:someone@example.com', NO_HANDLER);
    assert.strictEqual(allowedButUnopened.error.length, 1,
        'an allowed URL that failed to open must still reach the user');
    assert.ok(
        allowedButUnopened.error[0].includes(NO_HANDLER),
        'the mailto case surfaced a different message: ' + allowedButUnopened.error[0],
    );

    // The control. Without it, a relay that reported failure unconditionally
    // would satisfy the assertion above and put an error in front of every
    // successful open.
    const opened = await run('http://localhost:3000', '');
    assert.strictEqual(opened.calls.length, 1, 'the relay never reached the bridge');
    assert.strictEqual(
        opened.error.length, 0,
        'a URL the bridge opened must not surface an error; got ' + JSON.stringify(opened.error),
    );

    // Cancelling the input box must not reach Android at all.
    for (const [label, value] of [['cancelled', undefined], ['whitespace', '   ']]) {
        const inert = await run(value, '');
        assert.strictEqual(inert.calls.length, 0, `${label} input still called the bridge`);
        assert.strictEqual(inert.error.length, 0, `${label} input surfaced an error`);
    }

    // The other reader of the same answer. `injectWindowOpenOverride` calls the
    // same bridge method and decides from its answer whether the click was
    // handled, it is injected JavaScript that nothing compiles, and it reads the
    // answer the opposite way round from the relay: success is the empty string,
    // so a bare truthiness test claims every click it FAILED to open and lets the
    // one it did open fall through to the WebView as well. Both directions are
    // asserted, because that inversion satisfies either one on its own.
    const override = extractInjected('injectWindowOpenOverride', 'openExternalUrl');

    function windowOpen(answer, url) {
        const seen = { bridge: 0, orig: 0 };
        const win = {
            __vscodroid: { authToken: 'test-token' },
            open() { seen.orig++; return 'fell through to the WebView'; },
        };
        vm.runInNewContext(override, {
            window: win,
            AndroidBridge: {
                openExternalUrl() { seen.bridge++; return answer; },
            },
        });
        return { ...seen, result: win.open(url), calls: seen };
    }

    const claimed = windowOpen('', 'https://example.com/docs');
    assert.strictEqual(claimed.calls.bridge, 1, 'window.open never reached the bridge');
    assert.strictEqual(
        claimed.calls.orig, 0,
        'the bridge opened the URL and window.open fell through as well, so the page opens ' +
        'twice: once in a browser and once inside the WebView',
    );
    assert.strictEqual(claimed.result, null, 'a click that was handled must report that it was');

    const fellThrough = windowOpen(NO_HANDLER, 'https://example.com/docs');
    assert.strictEqual(fellThrough.calls.bridge, 1, 'window.open never reached the bridge');
    assert.strictEqual(
        fellThrough.calls.orig, 1,
        'the bridge did not open the URL and window.open swallowed the click anyway, so it ' +
        'did nothing and said nothing',
    );

    // ---- the commands that answer by reply id ----------------------------
    //
    // These four hand back the empty string and deliver the value afterwards,
    // because a bridge call holds the calling JavaScript thread until it returns
    // and the caller here is the workbench page's own thread. Nothing else runs
    // this shape: the Kotlin tests stop at the language boundary, and the
    // extension is only ever driven against a relay that answered inline.
    //
    // The pending promise is what makes the case meaningful, so the handler is
    // started rather than awaited, and it is only awaited once an answer has been
    // posted. If a branch ever went back to answering inline, `started` below
    // settles with the stub's empty return and every assertion about the posted
    // answer fails.
    const manageDeviceFolders = commands.get('vscodroid.manageDeviceFolders');
    assert.ok(manageDeviceFolders,
        'the bundled extension no longer registers vscodroid.manageDeviceFolders');

    /** Lets queued channel messages and the awaits behind them run. */
    async function settle() {
        for (let i = 0; i < 5; i += 1) await new Promise((r) => setTimeout(r, 0));
    }

    /** Starts the command and reports whether it has finished. */
    function start() {
        shown.info.length = 0; shown.error.length = 0;
        listCalls.length = 0; reclaimCalls.length = 0;
        const state = { done: false };
        state.running = manageDeviceFolders().then(() => { state.done = true; });
        return state;
    }

    listAnswer = '';
    reclaimAnswer = '';
    quickPickChoice = null;
    warningChoice = null;

    // The listing is asked for with the session token and a reply id, and NOTHING
    // has happened by the time the call returns.
    const listing = start();
    await settle();
    assert.strictEqual(listCalls.length, 1, 'the relay never reached listSafMirrors');
    assert.strictEqual(
        listCalls[0].token, 'test-token',
        'the relay passed the wrong first argument to listSafMirrors: ' +
        JSON.stringify(listCalls[0]),
    );
    assert.ok(
        typeof listCalls[0].replyId === 'string' && listCalls[0].replyId.length > 0,
        'the relay called listSafMirrors without a reply id, so the answer has no way ' +
        'back and the caller waits for its own deadline: ' + JSON.stringify(listCalls[0]),
    );
    assert.strictEqual(
        listing.done, false,
        'the listing settled before Android posted anything back, so the relay is not ' +
        'waiting for the answer -- it took the empty acceptance for the list itself',
    );
    assert.deepStrictEqual(
        { info: shown.info, error: shown.error }, { info: [], error: [] },
        'something was shown to the user while the walk was still running: ' +
        JSON.stringify(shown),
    );

    // The answer arrives by id and the flow carries on. showQuickPick is dismissed,
    // so what is asserted is that nothing went wrong on the way there.
    relayWindow.__vscodroidBridgeReply(
        listCalls[0].replyId, true,
        JSON.stringify([{ hash: 'abc123', name: 'Notes', bytes: 12, reclaimable: true }]),
    );
    await listing.running;
    assert.deepStrictEqual(
        { info: shown.info, error: shown.error }, { info: [], error: [] },
        'a listing that arrived by reply id was not understood: ' + JSON.stringify(shown),
    );

    // A refusal posted by id reaches the user in the words Android chose. This is
    // openExternalUrl's pass-through property, on the other road back.
    const POSTED_ONLY_HERE = 'a refusal that exists only inside this check';
    const rejected = start();
    await settle();
    relayWindow.__vscodroidBridgeReply(listCalls[0].replyId, false, POSTED_ONLY_HERE);
    await rejected.running;
    assert.ok(
        shown.error.length === 1 && shown.error[0].includes(POSTED_ONLY_HERE),
        'a refusal posted by reply id did not reach the user unchanged. Saw: ' +
        JSON.stringify(shown.error),
    );

    // A refusal decided before any work started travels back as the RETURN value,
    // and the relay has to post it rather than dropping it: the caller is waiting
    // for an answer that will never come otherwise.
    listAnswer = STALE_SESSION;
    const refusedAtOnce = start();
    await refusedAtOnce.running;
    assert.ok(
        shown.error.length === 1 && shown.error[0].includes(STALE_SESSION),
        'a command refused before it started did not reach the user, so the caller waits ' +
        'two minutes and is then told the app may not be running on Android. Saw: ' +
        JSON.stringify(shown.error),
    );
    listAnswer = '';

    // The removal, end to end: the confirmed removal is sent with its hash, its
    // force flag and a reply id, and the refusal that comes back by id is the
    // sentence the user is shown.
    quickPickChoice = (items) => items[0];
    warningChoice = 'Remove';
    const removal = start();
    await settle();
    relayWindow.__vscodroidBridgeReply(
        listCalls[0].replyId, true,
        JSON.stringify([{ hash: 'abc123', name: 'Notes', bytes: 12, reclaimable: false }]),
    );
    await settle();
    assert.strictEqual(reclaimCalls.length, 1, 'the confirmed removal never reached the bridge');
    assert.deepStrictEqual(
        { token: reclaimCalls[0].token, hash: reclaimCalls[0].hash, force: reclaimCalls[0].force },
        { token: 'test-token', hash: 'abc123', force: true },
        'the relay passed the wrong arguments to reclaimSafMirror. The reply id is LAST ' +
        'here and first nowhere, so an argument out of place hands the hash to the token ' +
        'check or deletes with the wrong force flag: ' + JSON.stringify(reclaimCalls[0]),
    );
    assert.ok(
        typeof reclaimCalls[0].replyId === 'string' && reclaimCalls[0].replyId.length > 0,
        'the removal was sent without a reply id: ' + JSON.stringify(reclaimCalls[0]),
    );
    assert.strictEqual(
        removal.done, false,
        'the removal settled before its outcome was posted, so a refusal could not have ' +
        'been reported and the user is told nothing about a copy that is still there',
    );

    const IN_USE = 'That folder is open in the editor.';
    relayWindow.__vscodroidBridgeReply(reclaimCalls[0].replyId, false, IN_USE);
    await removal.running;
    assert.ok(
        shown.error.length === 1 && shown.error[0].includes(IN_USE),
        'a refused removal did not reach the user in the words Android chose, so the ' +
        'thing that has to change first is never named. Saw: ' + JSON.stringify(shown.error),
    );
    quickPickChoice = null;
    warningChoice = null;

    // The storage screen's Total row, which is the first and largest thing a
    // person sees on the screen they open because they are out of disk.
    //
    // It is the one item built without a key, so it reaches the picker's final
    // arm. That arm used to close the picker in silence, which reads as a screen
    // that does not work; the sentence put there instead said Total was the sum
    // of the rows below it, and it is not. Total is every byte under the app's
    // data and cache directories, and the rows name the subtrees that can be
    // identified: the cache row alone was narrowed to the four directories the
    // clear action reaches, while the whole cache directory stays in Total.
    //
    // So this drives the real command with a breakdown whose rows deliberately
    // do NOT add up, and asserts the two figures are BOTH named. Numbers rather
    // than wording, because a check on the phrasing is one a synonym walks past.
    //
    // NEGATIVE CONTROL, measured: restore 'That is the sum of the rows below it.
    // Pick one of those to see what can be freed.' and the second assertion goes
    // red -- the sentence names neither figure.
    const showStorageUsage = commands.get('vscodroid.showStorageUsage');
    assert.ok(showStorageUsage, 'the bundled extension no longer registers vscodroid.showStorageUsage');

    const MB = 1024 * 1024;
    // Two rows of 512 MB inside a 3 GB total: the rows add to 1.0 GB, a figure
    // that is neither the total nor any single row, so naming it cannot be
    // satisfied by echoing something already on the screen.
    const breakdown = {
        vscode_server: 512 * MB,
        extensions: 512 * MB,
        total: 3 * 1024 * MB,
        clearable: ['cache', 'logs'],
    };
    let offered = null;
    quickPickChoice = (items) => { offered = items; return items[0]; };
    shown.info.length = 0; shown.error.length = 0; breakdownCalls.length = 0;
    const usage = showStorageUsage();
    await settle();
    assert.strictEqual(breakdownCalls.length, 1, 'the relay never reached getStorageBreakdown');
    relayWindow.__vscodroidBridgeReply(
        breakdownCalls[0].replyId, true, JSON.stringify(breakdown),
    );
    await usage;
    quickPickChoice = null;

    assert.ok(
        offered && offered[0] && offered[0].key === undefined,
        'the first item is no longer the keyless Total row, so this drove some other arm: ' +
        JSON.stringify(offered),
    );
    assert.strictEqual(
        shown.info.length, 1,
        'the Total row said nothing at all, so the screen reads as one that does not work: ' +
        JSON.stringify(shown),
    );
    const totalSaid = shown.info[0];
    assert.ok(
        totalSaid.includes('3.0 GB'),
        'the Total row does not name the figure it is showing: ' + totalSaid,
    );
    assert.ok(
        totalSaid.includes('1.0 GB'),
        'the Total row does not say how much of itself the rows below account for. They add ' +
        'to 1.0 GB of a 3.0 GB total, and a person out of disk adds them up: any claim that ' +
        'the rows come to the total is one this breakdown does not support. Saw: ' + totalSaid,
    );

    const unused = coverage.unused.length
        ? `; ${coverage.unused.length} relay branches have no sender (${coverage.unused.join(', ')})`
        : '';
    say(
        `ok -- a declined URL surfaces "${refused.error[0]}", an opened one stays silent, ` +
        'and window.open claims only the clicks the bridge opened; a device-folder listing ' +
        'and a removal each hand back before their work is done and are answered by reply ' +
        'id, and a refusal on either road reaches the user in the bridge\'s own words; ' +
        `all ${coverage.sent} commands sent by an extension have a relay branch${unused}\n`,
    );
}

/**
 * Writes synchronously, because the exit below cannot wait for it otherwise.
 *
 * `process.exit()` is documented to leave pending writes to stdout unflushed,
 * and stdout is only synchronous when it is a TTY. Under CI it is a pipe, so a
 * console.log immediately before the exit is exactly the line that goes
 * missing -- and a check that exits 0 having printed nothing reads as a check
 * that did not run.
 */
function say(text) {
    fs.writeSync(1, text);
}

// Exit explicitly. The relay opens a BroadcastChannel inside the VM context and
// nothing here holds a reference to unref it, so the event loop stays alive and
// the process hangs forever after the last assertion -- green, and never
// finishing, which in CI is a stall rather than a pass.
main().then(
    () => process.exit(0),
    (e) => { fs.writeSync(2, `${e.message}\n`); process.exit(1); },
);
